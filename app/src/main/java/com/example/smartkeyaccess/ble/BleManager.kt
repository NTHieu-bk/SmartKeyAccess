package com.example.smartkeyaccess.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.example.smartkeyaccess.crypto.CryptoManager
import com.example.smartkeyaccess.nfc.NfcHceBridge
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.security.SecureRandom
import java.util.Locale
import java.util.UUID

enum class BleState {
    READY,
    SCANNING,
    FOUND,
    CONNECTING,
    CONNECTED,
    DISCOVERING,
    MTU_NEGOTIATING,
    SUBSCRIBING,
    DATA_READY,
    ERROR
}

@SuppressLint("MissingPermission")
class BleManager(
    private val context: Context
) {

    companion object {
        val SERVICE_UUID: UUID = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
        val RX_UUID: UUID = UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E")      // Phone -> ESP32 (Write)
        val TX_UUID: UUID = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E")      // ESP32 -> Phone (Notify)
        val NONCE_UUID: UUID = UUID.fromString("6E400004-B5A3-F393-E0A9-E50E24DCCA9E")   // ESP32 notify nonce, Phone write resp

        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    private val handler = Handler(Looper.getMainLooper())

    private val _bleState = MutableStateFlow(BleState.READY)
    val bleState: StateFlow<BleState> = _bleState.asStateFlow()

    private val _status = MutableStateFlow("Ready to find ESP32")
    val status: StateFlow<String> = _status.asStateFlow()

    private val _rttMs = MutableStateFlow<Double?>(null)
    val rttMs: StateFlow<Double?> = _rttMs.asStateFlow()

    private val _negotiatedMtu = MutableStateFlow(23)
    val negotiatedMtu: StateFlow<Int> = _negotiatedMtu.asStateFlow()

    private val _sessionId = MutableStateFlow<Long?>(null)
    val sessionId: StateFlow<Long?> = _sessionId.asStateFlow()

    private val _esp32JsonPayload = MutableStateFlow<String?>(null)
    val esp32JsonPayload: StateFlow<String?> = _esp32JsonPayload.asStateFlow()

    private val _phoneDelayMs = MutableStateFlow<Int?>(null)
    val phoneDelayMs: StateFlow<Int?> = _phoneDelayMs.asStateFlow()

    private val _esp32DelayMs = MutableStateFlow<Int?>(null)
    val esp32DelayMs: StateFlow<Int?> = _esp32DelayMs.asStateFlow()

    private val _rawTxPayload = MutableStateFlow<String?>(null)
    val rawTxPayload: StateFlow<String?> = _rawTxPayload.asStateFlow()

    private val _nonceEvent = MutableStateFlow<String?>(null)
    val nonceEvent: StateFlow<String?> = _nonceEvent.asStateFlow()

    private var scanner: BluetoothLeScanner? = null
    private var bluetoothGatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private var notifyChar: BluetoothGattCharacteristic? = null
    private var nonceChar: BluetoothGattCharacteristic? = null

    private var scanning = false
    private var requestStartNs = 0L
    private var requestPending = false

    // Queue for descriptor writes to avoid GATT command collisions
    private val descriptorWriteQueue = ArrayDeque<BluetoothGattDescriptor>()

    private val requestTimeout = Runnable {
        if (requestPending) {
            requestPending = false
            failGatt("Response timeout (3000ms)")
        }
    }

    private val scanTimeout = Runnable {
        if (scanning) {
            stopScan()
            _bleState.value = BleState.ERROR
            _status.value = "Scan timeout: ESP32 not found within 10s"
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val matchesService = result.scanRecord?.serviceUuids?.any { it.uuid == SERVICE_UUID } == true
            if (matchesService) {
                val device = result.device
                stopScan()
                _bleState.value = BleState.FOUND
                _status.value = "Found ESP32 (${device.address}), connecting GATT..."
                connectGatt(device)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            stopScan()
            _bleState.value = BleState.ERROR
            _status.value = "BLE scan failed with error code $errorCode"
        }
    }

    fun startScan(adapter: BluetoothAdapter): Boolean {
        if (scanning) return true
        cleanupGatt()
        _rttMs.value = null

        val leScanner = adapter.bluetoothLeScanner
        if (leScanner == null) {
            _bleState.value = BleState.ERROR
            _status.value = "Unable to initialize BLE Scanner"
            return false
        }

        scanner = leScanner
        scanning = true
        _bleState.value = BleState.SCANNING
        _status.value = "Scanning for ESP32..."
        leScanner.startScan(scanCallback)
        handler.postDelayed(scanTimeout, 10_000L)
        return true
    }

    fun stopScan() {
        if (!scanning) return
        scanning = false
        handler.removeCallbacks(scanTimeout)
        try {
            scanner?.stopScan(scanCallback)
        } catch (_: Exception) {}
        scanner = null
    }

    private fun connectGatt(device: BluetoothDevice) {
        _bleState.value = BleState.CONNECTING
        try {
            bluetoothGatt = device.connectGatt(context, false, gattCallback)
        } catch (e: Exception) {
            failGatt("connectGatt failed: ${e.message}")
        }
    }

    private fun cleanupGatt() {
        handler.removeCallbacks(requestTimeout)
        descriptorWriteQueue.clear()
        val gatt = bluetoothGatt
        bluetoothGatt = null
        writeChar = null
        notifyChar = null
        nonceChar = null
        requestPending = false

        try { gatt?.disconnect() } catch (_: Exception) {}
        try { gatt?.close() } catch (_: Exception) {}
    }

    private fun failGatt(reason: String) {
        cleanupGatt()
        _bleState.value = BleState.ERROR
        _status.value = "GATT error: $reason"
    }

    fun reset() {
        stopScan()
        cleanupGatt()
        _rttMs.value = null
        _sessionId.value = null
        _esp32JsonPayload.value = null
        _phoneDelayMs.value = null
        _esp32DelayMs.value = null
        _rawTxPayload.value = null
        _nonceEvent.value = null
        _negotiatedMtu.value = 23
        _bleState.value = BleState.READY
        _status.value = "Ready to find ESP32"
    }

    // Write binary frame or raw bytes over RX characteristic
    fun writeBleFrame(frame: ByteArray, onFailed: (Int) -> Unit = {}): Boolean {
        val gatt = bluetoothGatt ?: run {
            failGatt("GATT not connected")
            return false
        }
        val targetChar = writeChar ?: run {
            failGatt("RX characteristic not found")
            return false
        }

        val result = try {
            gatt.writeCharacteristic(
                targetChar,
                frame,
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            )
        } catch (_: Exception) {
            BluetoothStatusCodes.ERROR_UNKNOWN
        }

        if (result != BluetoothStatusCodes.SUCCESS) {
            requestPending = false
            handler.removeCallbacks(requestTimeout)
            onFailed(result)
            return false
        }
        return true
    }

    // Write response back to Nonce characteristic (6E400004)
    fun writeNonceResponse(response: ByteArray): Boolean {
        val gatt = bluetoothGatt ?: return false
        val targetChar = nonceChar ?: return false

        val res = try {
            gatt.writeCharacteristic(
                targetChar,
                response,
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            )
        } catch (_: Exception) {
            BluetoothStatusCodes.ERROR_UNKNOWN
        }
        return res == BluetoothStatusCodes.SUCCESS
    }

    // Send PING to measure OTA round-trip latency
    fun sendPing() {
        requestStartNs = SystemClock.elapsedRealtimeNanos()
        requestPending = true
        handler.removeCallbacks(requestTimeout)
        handler.postDelayed(requestTimeout, 3_000L)
        _status.value = "Sending PING (4B) to ESP32..."

        val frame = "PING".encodeToByteArray()
        writeBleFrame(frame) { errCode ->
            failGatt("PING write failed (code $errCode)")
        }
    }

    // Send binary AUTH_REQ frame
    fun sendAuthRequest() {
        requestStartNs = SystemClock.elapsedRealtimeNanos()
        requestPending = true
        handler.removeCallbacks(requestTimeout)
        handler.postDelayed(requestTimeout, 3_000L)
        _status.value = "Sending binary AUTH_REQ (60B)..."

        val phoneNonce = ByteArray(SmartKeyProtocol.NONCE_SIZE).also { SecureRandom().nextBytes(it) }
        val phoneId = "PHON0001".toByteArray(Charsets.UTF_8)
        val payload = SmartKeyProtocol.buildAuthRequestPayload(phoneId, phoneNonce)
        val authTag = ByteArray(SmartKeyProtocol.AUTH_TAG_SIZE)

        val frame = SmartKeyProtocol.encodeFrame(
            msgType = SmartKeyProtocol.MSG_AUTH_REQ,
            payload = payload,
            authTag = authTag
        )

        writeBleFrame(frame) { errCode ->
            failGatt("AUTH_REQ write failed (code $errCode)")
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                bluetoothGatt = gatt
                val ok = try { gatt.discoverServices() } catch (_: Exception) { false }
                if (ok) {
                    _bleState.value = BleState.DISCOVERING
                    _status.value = "Connected! Discovering GATT services..."
                } else {
                    failGatt("discoverServices() invocation failed")
                }
            } else {
                failGatt("Disconnected or error status=$status")
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                failGatt("Service discovery failed (status $status)")
                return
            }

            val service = gatt.getService(SERVICE_UUID)
            if (service == null) {
                failGatt("Smart Key Service not found ($SERVICE_UUID)")
                return
            }

            writeChar = service.getCharacteristic(RX_UUID)
            notifyChar = service.getCharacteristic(TX_UUID)
            nonceChar = service.getCharacteristic(NONCE_UUID)

            if (writeChar == null || notifyChar == null) {
                failGatt("Missing required RX or TX characteristic")
                return
            }

            _bleState.value = BleState.MTU_NEGOTIATING
            _status.value = "Negotiating MTU (512 bytes)..."
            val started = try { gatt.requestMtu(512) } catch (_: Exception) { false }
            if (!started) {
                startSubscribing(gatt)
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            val actual = if (status == BluetoothGatt.GATT_SUCCESS) mtu else 23
            _negotiatedMtu.value = actual
            _status.value = "MTU negotiated to $actual bytes. Subscribing to notifications..."
            startSubscribing(gatt)
        }

        // Subscribe sequentially to TX and Nonce notifications
        private fun startSubscribing(gatt: BluetoothGatt) {
            _bleState.value = BleState.SUBSCRIBING
            descriptorWriteQueue.clear()

            // Subscribe TX characteristic (6E400003)
            notifyChar?.let { char ->
                try {
                    gatt.setCharacteristicNotification(char, true)
                    char.getDescriptor(CCCD_UUID)?.let { cccd ->
                        descriptorWriteQueue.add(cccd)
                    }
                } catch (_: Exception) {}
            }

            // Subscribe Nonce characteristic (6E400004) if present
            nonceChar?.let { char ->
                try {
                    gatt.setCharacteristicNotification(char, true)
                    char.getDescriptor(CCCD_UUID)?.let { cccd ->
                        descriptorWriteQueue.add(cccd)
                    }
                } catch (_: Exception) {}
            }

            writeNextDescriptor(gatt)
        }

        private fun writeNextDescriptor(gatt: BluetoothGatt) {
            if (descriptorWriteQueue.isEmpty()) {
                _bleState.value = BleState.DATA_READY
                _status.value = "Notifications enabled (TX + Nonce). Ready for data exchange."
                return
            }

            val desc = descriptorWriteQueue.removeFirst()
            try {
                gatt.writeDescriptor(desc, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            } catch (e: Exception) {
                failGatt("writeDescriptor failed: ${e.message}")
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                writeNextDescriptor(gatt)
            } else {
                failGatt("CCCD write failed (status $status)")
            }
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                requestPending = false
                handler.removeCallbacks(requestTimeout)
                failGatt("Characteristic write failed (status $status)")
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            when (characteristic.uuid) {
                TX_UUID -> handleTxMessage(value)
                NONCE_UUID -> handleNonceMessage(value)
            }
        }
    }

    // Handle messages arriving on TX characteristic
    private fun handleTxMessage(value: ByteArray) {
        val textValue = try { String(value, Charsets.UTF_8).trim() } catch (_: Exception) { null }

        // PONG response
        if (value.contentEquals("PONG".encodeToByteArray()) || textValue == "PONG") {
            val endNs = SystemClock.elapsedRealtimeNanos()
            val rtt = (endNs - requestStartNs) / 1_000_000.0
            requestPending = false
            handler.removeCallbacks(requestTimeout)
            _bleState.value = BleState.DATA_READY
            _rttMs.value = rtt
            _status.value = "PONG received! RTT = %.2f ms".format(Locale.US, rtt)
            return
        }

        // JSON telemetry from ESP32
        if (textValue != null && textValue.startsWith("{") && textValue.endsWith("}")) {
            try {
                val json = JSONObject(textValue)

                fun readDelay(vararg keys: String): Int? {
                    for (k in keys) {
                        if (json.has(k) && !json.isNull(k)) {
                            val v = json.optInt(k, Int.MIN_VALUE)
                            if (v != Int.MIN_VALUE) return v
                        }
                    }
                    return null
                }

                val phone = readDelay("phone", "phoneDelay", "phone_delay")
                val esp = readDelay("esp32", "esp32Delay", "esp32_delay")

                _esp32JsonPayload.value = textValue
                _phoneDelayMs.value = phone
                _esp32DelayMs.value = esp
                _status.value = buildString {
                    append("Received JSON telemetry!")
                    if (phone != null) append(" Phone: ${phone}ms")
                    if (esp != null) append(" | ESP32: ${esp}ms")
                }
                return
            } catch (_: Exception) {}
        }

        // SmartKeyProtocol binary frame
        val header = SmartKeyProtocol.parseHeader(value)
        if (header != null) {
            val endNs = SystemClock.elapsedRealtimeNanos()
            val rtt = (endNs - requestStartNs) / 1_000_000.0
            requestPending = false
            handler.removeCallbacks(requestTimeout)

            when (header.msgType) {
                SmartKeyProtocol.MSG_PONG -> {
                    _bleState.value = BleState.DATA_READY
                    _rttMs.value = rtt
                    _status.value = "Binary PONG received! RTT = %.2f ms".format(Locale.US, rtt)
                }
                SmartKeyProtocol.MSG_AUTH_RESP -> {
                    val payloadBytes = if (value.size > SmartKeyProtocol.HEADER_SIZE) {
                        value.copyOfRange(SmartKeyProtocol.HEADER_SIZE, value.size)
                    } else ByteArray(0)
                    val authResp = SmartKeyProtocol.parseAuthResponsePayload(payloadBytes)
                    val sid = authResp?.newSessionId ?: header.sessionId
                    _sessionId.value = sid
                    _bleState.value = BleState.DATA_READY
                    _rttMs.value = rtt
                    _status.value = "AUTH_RESP received! Session = $sid (RTT = %.2f ms)".format(Locale.US, rtt)
                }
                SmartKeyProtocol.MSG_UWB_CONFIG_RESP -> {
                    _bleState.value = BleState.DATA_READY
                    _status.value = "UWB_CONFIG_RESP received! Ready for UWB (RTT = %.2f ms)".format(Locale.US, rtt)
                }
                SmartKeyProtocol.MSG_ERROR_IND -> {
                    val errCode = if (value.size > SmartKeyProtocol.HEADER_SIZE) value[SmartKeyProtocol.HEADER_SIZE] else SmartKeyProtocol.ERR_INTERNAL_FAULT
                    _bleState.value = BleState.ERROR
                    _status.value = "ESP32 reported error: " + SmartKeyProtocol.getErrorMessage(errCode)
                }
                else -> {
                    _status.value = "Received binary msg_type: 0x%02X".format(header.msgType)
                }
            }
            return
        }

        // Fallback text or raw hex bytes
        if (textValue != null && textValue.isNotEmpty() && textValue.all { it.code in 32..126 || it == '\n' || it == '\r' }) {
            _rawTxPayload.value = textValue
            _status.value = "Received TX text: $textValue"
        } else {
            val hex = CryptoManager.toHex(value)
            _rawTxPayload.value = hex
            _status.value = "Received TX raw (${value.size}B): $hex"
        }
    }

    // Handle incoming Nonce from ESP32 (...0004)
    private fun handleNonceMessage(value: ByteArray) {
        val hex = CryptoManager.toHex(value)
        _nonceEvent.value = "ESP32 Nonce (${value.size}B): $hex"

        // Sign challenge nonce with private key (ECDSA P1363 64B) and write response back
        try {
            val privKey = NfcHceBridge.phoneIdentityKeyPair.private
            val signature = CryptoManager.signEcdsaP1363(privKey, value)
            val writeOk = writeNonceResponse(signature)
            if (writeOk) {
                _nonceEvent.value = "Signed ECDSA (64B) and sent response via Nonce characteristic"
            } else {
                _nonceEvent.value = "Signed successfully but failed to write response to Nonce characteristic"
            }
        } catch (e: Exception) {
            _nonceEvent.value = "Nonce signing error: ${e.message}"
        }
    }
}
