package com.example.smartkeyaccess.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
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
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.core.uwb.RangingParameters
import com.example.smartkeyaccess.crypto.CryptoManager
import com.example.smartkeyaccess.mock.MockDataProvider
import com.example.smartkeyaccess.protocol.SmartKeyProtocol
import com.example.smartkeyaccess.uwb.UwbConfig
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
    AUTHENTICATING,
    AUTHENTICATED,
    UWB_CONFIG_EXCHANGING,
    UWB_CONFIG_CONFIRMED,
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

    private val _uwbConfig = MutableStateFlow<UwbConfig?>(null)
    val uwbConfig: StateFlow<UwbConfig?> = _uwbConfig.asStateFlow()
    val esp32UwbConfig: StateFlow<UwbConfig?> get() = _uwbConfig

    private val _capabilityReport = MutableStateFlow(BleCapabilityReport())
    val capabilityReport: StateFlow<BleCapabilityReport> = _capabilityReport.asStateFlow()

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

    private var currentMasterKey: ByteArray? = null
    private var lastPhoneNonce: ByteArray? = null
    private var lastPhoneNonceSent: ByteArray? = null
    private var lastLockNonceRecv: ByteArray? = null
    private var sessionStsKey: ByteArray? = null
    private var pendingRequestType: Byte? = null
    private var pendingSeqNum: Int = 0

    private val _lockDeviceId = MutableStateFlow<ByteArray?>(null)
    val lockDeviceId: StateFlow<ByteArray?> = _lockDeviceId.asStateFlow()

    fun setMasterKey(key: ByteArray?) {
        if (key == null) {
            currentMasterKey = null
            return
        }
        if (key.size == 16 || key.size == 32) {
            currentMasterKey = key.copyOf()
        }
    }

    fun isMasterKeyProvisioned(): Boolean = currentMasterKey != null

    private var scanner: BluetoothLeScanner? = null
    private var bluetoothGatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private var notifyChar: BluetoothGattCharacteristic? = null
    private var nonceChar: BluetoothGattCharacteristic? = null

    private var scanning = false
    private var requestStartNs = 0L
    private var requestPending = false

    private enum class PendingWrite {
        RX_APPLICATION,
        NONCE_RESPONSE
    }

    // Android completes a remote characteristic write asynchronously through
    // onCharacteristicWrite(). Keep at most one write in flight so a nonce
    // response cannot collide with a user-triggered RX write.
    private var pendingWrite: PendingWrite? = null

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

    fun checkCapabilities(): BleCapabilityReport {
        val pm = context.packageManager
        val hardwareSupported = pm.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)
        val btManager = context.getSystemService(BluetoothManager::class.java)
        val adapter = btManager?.adapter
        val bluetoothEnabled = adapter?.isEnabled == true
        val scanPerm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
        } else true
        val connectPerm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else true

        val message = when {
            !hardwareSupported -> "Device does not feature Bluetooth Low Energy (FEATURE_BLUETOOTH_LE)."
            !bluetoothEnabled -> "Bluetooth adapter is turned OFF. Please enable Bluetooth."
            !scanPerm -> "Permission BLUETOOTH_SCAN not granted."
            !connectPerm -> "Permission BLUETOOTH_CONNECT not granted."
            else -> "Phone BLE subsystem is ready for scanning and connection."
        }

        val report = BleCapabilityReport(
            hardwareSupported = hardwareSupported,
            bluetoothEnabled = bluetoothEnabled,
            scanPermissionGranted = scanPerm,
            connectPermissionGranted = connectPerm,
            diagnosisMessage = message
        )
        _capabilityReport.value = report
        return report
    }

    fun disconnect() {
        stopScan()
        cleanupGatt()
        _bleState.value = BleState.READY
        _status.value = "Disconnected from ESP32"
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

    /**
     * Connects to ESP32: initiates BLE scanning with filter for SERVICE_UUID,
     * connects GATT, negotiates MTU, and enables notifications.
     */
    fun connectToEsp32(adapter: BluetoothAdapter): Boolean = startScan(adapter)

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

    // Clears all ephemeral cryptographic, session, and negotiation state
    fun clearSessionState() {
        handler.removeCallbacks(requestTimeout)
        requestPending = false
        pendingRequestType = null
        pendingSeqNum = 0
        lastPhoneNonce = null
        lastPhoneNonceSent = null
        lastLockNonceRecv = null
        sessionStsKey = null
        _sessionId.value = null
        _uwbConfig.value = null
        _lockDeviceId.value = null
        _rttMs.value = null
        _phoneDelayMs.value = null
        _esp32DelayMs.value = null
        _esp32JsonPayload.value = null
    }

    private fun cleanupGatt() {
        clearSessionState()
        descriptorWriteQueue.clear()
        val gatt = bluetoothGatt
        bluetoothGatt = null
        writeChar = null
        notifyChar = null
        nonceChar = null
        pendingWrite = null

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
        _rawTxPayload.value = null
        _nonceEvent.value = null
        _negotiatedMtu.value = 23
        _bleState.value = BleState.READY
        _status.value = "Ready to find ESP32"
    }

    // Backward-compatible characteristic write helper for Android 12 (API 31-32) and Android 13+ (API 33+)
    private fun writeCharacteristicCompat(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray
    ): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeCharacteristic(characteristic, value, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
        } else {
            @Suppress("DEPRECATION")
            characteristic.value = value
            @Suppress("DEPRECATION")
            characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            @Suppress("DEPRECATION")
            if (gatt.writeCharacteristic(characteristic)) BluetoothStatusCodes.SUCCESS else BluetoothStatusCodes.ERROR_UNKNOWN
        }
    }

    // Backward-compatible descriptor write helper for Android 12 (API 31-32) and Android 13+ (API 33+)
    private fun writeDescriptorCompat(
        gatt: BluetoothGatt,
        descriptor: BluetoothGattDescriptor,
        value: ByteArray
    ): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeDescriptor(descriptor, value)
        } else {
            @Suppress("DEPRECATION")
            descriptor.value = value
            @Suppress("DEPRECATION")
            if (gatt.writeDescriptor(descriptor)) BluetoothStatusCodes.SUCCESS else BluetoothStatusCodes.ERROR_UNKNOWN
        }
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
        val maxPayload = maxOf(20, _negotiatedMtu.value - 3)
        if (frame.size > maxPayload) {
            _status.value = "Frame size (${frame.size}B) exceeds negotiated ATT MTU payload ($maxPayload bytes)"
            onFailed(BluetoothStatusCodes.ERROR_UNKNOWN)
            return false
        }
        if (pendingWrite != null) {
            onFailed(BluetoothStatusCodes.ERROR_UNKNOWN)
            return false
        }

        pendingWrite = PendingWrite.RX_APPLICATION
        val result = try {
            writeCharacteristicCompat(gatt, targetChar, frame)
        } catch (_: Exception) {
            BluetoothStatusCodes.ERROR_UNKNOWN
        }

        if (result != BluetoothStatusCodes.SUCCESS) {
            pendingWrite = null
            requestPending = false
            handler.removeCallbacks(requestTimeout)
            onFailed(result)
            return false
        }
        return true
    }

    fun writeNonceResponse(response: ByteArray): Boolean {
        val gatt = bluetoothGatt ?: return false
        val targetChar = nonceChar ?: return false
        if (pendingWrite != null) return false

        pendingWrite = PendingWrite.NONCE_RESPONSE
        val res = try {
            writeCharacteristicCompat(gatt, targetChar, response)
        } catch (_: Exception) {
            BluetoothStatusCodes.ERROR_UNKNOWN
        }
        if (res != BluetoothStatusCodes.SUCCESS) {
            pendingWrite = null
            return false
        }
        _nonceEvent.value = "Nonce response queued; waiting for GATT write confirmation"
        return true
    }

    // Send PING to measure OTA round-trip latency
    fun sendPing() {
        if (requestPending || pendingWrite != null) {
            _status.value = "BLE request already in progress; wait for its response or timeout"
            return
        }
        requestStartNs = SystemClock.elapsedRealtimeNanos()
        requestPending = true
        pendingRequestType = SmartKeyProtocol.MSG_PING
        pendingSeqNum = 0
        handler.removeCallbacks(requestTimeout)
        handler.postDelayed(requestTimeout, 3_000L)
        _status.value = "Sending PING (4B) to ESP32..."

        val frame = "PING".encodeToByteArray()
        writeBleFrame(frame) { errCode ->
            failGatt("PING write failed (code $errCode)")
        }
    }

    // Send binary AUTH_REQ frame with cryptographic HMAC-SHA256 authentication tag
    fun sendAuthRequest() {
        if (requestPending || pendingWrite != null) {
            _status.value = "BLE request already in progress; wait for its response or timeout"
            return
        }
        val msk = currentMasterKey ?: run {
            _bleState.value = BleState.ERROR
            _status.value = "Master key not provisioned! Scan NFC Master Card or load test credentials first."
            return
        }
        requestStartNs = SystemClock.elapsedRealtimeNanos()
        requestPending = true
        pendingRequestType = SmartKeyProtocol.MSG_AUTH_REQ
        pendingSeqNum = 1
        handler.removeCallbacks(requestTimeout)
        handler.postDelayed(requestTimeout, 3_000L)
        _status.value = "Sending binary AUTH_REQ (60B)..."

        val phoneNonce = ByteArray(SmartKeyProtocol.NONCE_SIZE).also { SecureRandom().nextBytes(it) }
        lastPhoneNonce = phoneNonce
        lastPhoneNonceSent = phoneNonce
        val phoneId = "PHON0001".toByteArray(Charsets.UTF_8)
        val payload = SmartKeyProtocol.buildAuthRequestPayload(phoneId, phoneNonce)

        val frame = SmartKeyProtocol.encodeAuthenticatedFrame(
            msgType = SmartKeyProtocol.MSG_AUTH_REQ,
            payload = payload,
            sessionId = 0L,
            seqNum = 1,
            key = msk
        )

        _bleState.value = BleState.AUTHENTICATING
        writeBleFrame(frame) { errCode ->
            failGatt("AUTH_REQ write failed (code $errCode)")
        }
    }

    // Send binary UWB_CONFIG_REQ frame to negotiate FiRa parameters with ESP32
    fun sendUwbConfigRequest(
        channel: Int = 9,
        preambleIndex: Int = 9,
        phoneUwbAddress: ByteArray
    ) {
        if (phoneUwbAddress.size != 2 && phoneUwbAddress.size != 8) {
            _bleState.value = BleState.ERROR
            _status.value = "Invalid phone UWB address (${phoneUwbAddress.size}B). Must be 2 or 8 bytes."
            return
        }
        if (requestPending || pendingWrite != null) {
            _status.value = "BLE request already in progress; wait for its response or timeout"
            return
        }
        val msk = currentMasterKey ?: run {
            _bleState.value = BleState.ERROR
            _status.value = "Cannot configure UWB: Master key missing."
            return
        }
        val sid = _sessionId.value ?: run {
            _status.value = "Cannot configure UWB: BLE session not authenticated yet."
            return
        }
        val stsKey = sessionStsKey ?: run {
            _status.value = "Cannot configure UWB: Valid STS session key not established."
            return
        }

        // P0: Do NOT fallback to all-zero dummy nonces. Must have valid nonces established during authentication.
        val phoneNonce = lastPhoneNonceSent ?: run {
            _bleState.value = BleState.ERROR
            _status.value = "Cannot configure UWB: Missing phone nonce from authentication."
            return
        }
        val lockNonce = lastLockNonceRecv ?: run {
            _bleState.value = BleState.ERROR
            _status.value = "Cannot configure UWB: Missing lock nonce from authentication."
            return
        }
        val bleTrafficKey = SmartKeyProtocol.deriveBleTrafficKey(msk, phoneNonce, lockNonce)
        val iv = java.nio.ByteBuffer.allocate(16).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt((sid and 0xFFFFFFFFL).toInt()).array()
        val protectedStsToken = SmartKeyProtocol.encryptSensitivePayload(bleTrafficKey, iv, stsKey)

        val payload = SmartKeyProtocol.buildUwbConfigRequestPayload(
            channel = channel,
            preambleIndex = preambleIndex,
            configId = RangingParameters.CONFIG_PROVISIONED_UNICAST_DS_TWR,
            phoneUwbAddress = phoneUwbAddress,
            subSessionId = 0,
            stsKey = protectedStsToken
        )

        val frame = SmartKeyProtocol.encodeAuthenticatedFrame(
            msgType = SmartKeyProtocol.MSG_UWB_CONFIG_REQ,
            payload = payload,
            sessionId = sid,
            seqNum = 2,
            key = msk
        )

        requestStartNs = SystemClock.elapsedRealtimeNanos()
        requestPending = true
        pendingRequestType = SmartKeyProtocol.MSG_UWB_CONFIG_REQ
        pendingSeqNum = 2
        handler.removeCallbacks(requestTimeout)
        handler.postDelayed(requestTimeout, 3_000L)
        _bleState.value = BleState.UWB_CONFIG_EXCHANGING
        _status.value = "Sending MSG_UWB_CONFIG_REQ (Ch $channel, Preamble $preambleIndex)..."

        writeBleFrame(frame) { errCode ->
            failGatt("UWB_CONFIG_REQ write failed (code $errCode)")
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

            val rx = writeChar
            val tx = notifyChar
            val nonce = nonceChar
            if (rx == null || tx == null || nonce == null) {
                failGatt("Missing required RX, TX, or NONCE characteristic")
                return
            }
            if (rx.properties and BluetoothGattCharacteristic.PROPERTY_WRITE == 0) {
                failGatt("RX characteristic does not support PROPERTY_WRITE")
                return
            }
            if (tx.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY == 0) {
                failGatt("TX characteristic does not support PROPERTY_NOTIFY")
                return
            }
            val nonceCanNotify = nonce.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0
            val nonceCanWrite = nonce.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0
            if (!nonceCanNotify || !nonceCanWrite) {
                failGatt("NONCE characteristic must support PROPERTY_NOTIFY and PROPERTY_WRITE")
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

            val tx = notifyChar ?: run {
                failGatt("TX characteristic unavailable while subscribing")
                return
            }
            val nonce = nonceChar ?: run {
                failGatt("NONCE characteristic unavailable while subscribing")
                return
            }
            val txCccd = tx.getDescriptor(CCCD_UUID) ?: run {
                failGatt("TX CCCD 0x2902 is missing")
                return
            }
            val nonceCccd = nonce.getDescriptor(CCCD_UUID) ?: run {
                failGatt("NONCE CCCD 0x2902 is missing")
                return
            }

            // Local notification routing must be enabled before writing each
            // remote CCCD. Both calls are checked because DATA_READY requires
            // notifications from TX and the dedicated nonce characteristic.
            val txLocalEnabled = try { gatt.setCharacteristicNotification(tx, true) } catch (_: Exception) { false }
            if (!txLocalEnabled) {
                failGatt("Failed to enable local TX notification routing")
                return
            }
            val nonceLocalEnabled = try { gatt.setCharacteristicNotification(nonce, true) } catch (_: Exception) { false }
            if (!nonceLocalEnabled) {
                failGatt("Failed to enable local NONCE notification routing")
                return
            }

            descriptorWriteQueue.add(txCccd)
            descriptorWriteQueue.add(nonceCccd)
            writeNextDescriptor(gatt)
        }

        private fun writeNextDescriptor(gatt: BluetoothGatt) {
            if (descriptorWriteQueue.isEmpty()) {
                _bleState.value = BleState.DATA_READY
                _status.value = "Notifications enabled (TX + Nonce). Ready for data exchange."
                return
            }

            val desc = descriptorWriteQueue.removeFirst()
            val result = try {
                writeDescriptorCompat(gatt, desc, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            } catch (e: Exception) {
                failGatt("writeDescriptor failed: ${e.message}")
                return
            }
            if (result != BluetoothStatusCodes.SUCCESS) {
                failGatt("writeDescriptor request rejected (code $result)")
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
            val completedWrite = pendingWrite
            pendingWrite = null
            if (status != BluetoothGatt.GATT_SUCCESS) {
                requestPending = false
                handler.removeCallbacks(requestTimeout)
                failGatt("Characteristic write failed (status $status)")
                return
            }

            if (completedWrite == PendingWrite.NONCE_RESPONSE && characteristic.uuid == NONCE_UUID) {
                _nonceEvent.value = "Nonce response write confirmed by GATT callback"
            }
        }

        // Android 13+ (API 33+) callback with explicit value byte array
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            when (characteristic.uuid) {
                TX_UUID -> handleTxMessage(value)
                NONCE_UUID -> handleNonceMessage(value)
            }
        }

        // Android 12 (API 31-32) callback
        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            val value = characteristic.value ?: return
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
            if (pendingRequestType == SmartKeyProtocol.MSG_PING) {
                requestPending = false
                handler.removeCallbacks(requestTimeout)
                pendingRequestType = null
            }
            if (_bleState.value == BleState.SUBSCRIBING || _bleState.value == BleState.CONNECTED) {
                _bleState.value = BleState.DATA_READY
            }
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

            when (header.msgType) {
                SmartKeyProtocol.MSG_PONG -> {
                    if (pendingRequestType == SmartKeyProtocol.MSG_PING) {
                        requestPending = false
                        handler.removeCallbacks(requestTimeout)
                        pendingRequestType = null
                    }
                    if (_bleState.value == BleState.SUBSCRIBING || _bleState.value == BleState.CONNECTED) {
                        _bleState.value = BleState.DATA_READY
                    }
                    _rttMs.value = rtt
                    _status.value = "Binary PONG received! RTT = %.2f ms".format(Locale.US, rtt)
                }
                SmartKeyProtocol.MSG_AUTH_RESP -> {
                    // Check state: Must be actively awaiting authentication
                    if (_bleState.value != BleState.AUTHENTICATING) {
                        _status.value = "Ignored unsolicited AUTH_RESP while not in AUTHENTICATING state."
                        return
                    }

                    // Check request matching: must match pending AUTH_REQ seqNum
                    if (pendingRequestType != SmartKeyProtocol.MSG_AUTH_REQ || header.seqNum != pendingSeqNum) {
                        _bleState.value = BleState.ERROR
                        _status.value = "AUTH_RESP rejected: Mismatched request sequence (expected seq $pendingSeqNum, got ${header.seqNum})."
                        return
                    }

                    val phoneNonce = lastPhoneNonce ?: run {
                        _bleState.value = BleState.ERROR
                        _status.value = "Authentication failed: Unsolicited or replayed AUTH_RESP (no pending phone nonce)."
                        return
                    }
                    lastPhoneNonce = null
                    requestPending = false
                    handler.removeCallbacks(requestTimeout)
                    pendingRequestType = null

                    val msk = currentMasterKey ?: run {
                        _bleState.value = BleState.ERROR
                        _status.value = "Authentication failed: Master key missing."
                        return
                    }

                    val payloadBytes = if (value.size > SmartKeyProtocol.HEADER_SIZE) {
                        value.copyOfRange(SmartKeyProtocol.HEADER_SIZE, value.size)
                    } else ByteArray(0)
                    val authResp = SmartKeyProtocol.parseAuthResponsePayload(payloadBytes)
                    if (authResp == null) {
                        _bleState.value = BleState.ERROR
                        _status.value = "Malformed AUTH_RESP payload from ESP32."
                        return
                    }

                    if (authResp.newSessionId == 0L) {
                        _bleState.value = BleState.ERROR
                        _status.value = "AUTH_RESP rejected: ESP32 provided invalid session ID 0."
                        return
                    }

                    // P0 Authentication verification: Verify auth tag against currentMasterKey
                    val headerBytes = value.copyOfRange(0, SmartKeyProtocol.HEADER_SIZE)
                    val rawPayloadWithoutTag = if (payloadBytes.size >= SmartKeyProtocol.AUTH_TAG_SIZE) {
                        payloadBytes.copyOfRange(0, payloadBytes.size - SmartKeyProtocol.AUTH_TAG_SIZE)
                    } else payloadBytes

                    val tagValid = SmartKeyProtocol.verifyAuthTag(
                        key = msk,
                        headerBytes = headerBytes,
                        payloadBytes = rawPayloadWithoutTag,
                        expectedTag = authResp.authTag
                    )

                    if (!tagValid) {
                        _bleState.value = BleState.ERROR
                        _sessionId.value = null
                        _uwbConfig.value = null
                        _status.value = "AUTH_RESP verification FAILED: Invalid auth tag! Untrusted ESP32."
                        return
                    }

                    // P1.B: Disentangle lockId from UWB address
                    val lockId = authResp.lockId
                    _lockDeviceId.value = lockId

                    val sid = authResp.newSessionId
                    _sessionId.value = sid
                    _bleState.value = BleState.AUTHENTICATED
                    _rttMs.value = rtt
                    _status.value = "AUTHENTICATED! Session 0x%08X (RTT = %.2f ms). Peer verified.".format(Locale.US, sid, rtt)

                    // P0 Safe UWB STS key derivation: Derived from currentMasterKey + exchanged nonces via HKDF
                    val stsKey = SmartKeyProtocol.deriveStsSessionKey(
                        sharedSecret = msk,
                        phoneNonce = phoneNonce,
                        lockNonce = authResp.lockNonce,
                        keyLength = 16
                    )
                    sessionStsKey = stsKey
                    lastLockNonceRecv = authResp.lockNonce

                    // Keep _uwbConfig null until UWB_CONFIG_RESP confirms the true ESP32 UWB address!
                    _uwbConfig.value = null
                }
                SmartKeyProtocol.MSG_UWB_CONFIG_RESP -> {
                    val currentSid = _sessionId.value ?: 0L
                    if (currentSid == 0L || header.sessionId != currentSid) {
                        _bleState.value = BleState.ERROR
                        _status.value = "UWB_CONFIG_RESP rejected: Invalid or mismatched session ID (expected 0x%08X, got 0x%08X)".format(currentSid, header.sessionId)
                        return
                    }

                    if (pendingRequestType != SmartKeyProtocol.MSG_UWB_CONFIG_REQ || header.seqNum != pendingSeqNum) {
                        _bleState.value = BleState.ERROR
                        _status.value = "UWB_CONFIG_RESP rejected: Unexpected request sequence (expected seq $pendingSeqNum, got ${header.seqNum})."
                        return
                    }
                    requestPending = false
                    handler.removeCallbacks(requestTimeout)
                    pendingRequestType = null

                    val msk = currentMasterKey ?: run {
                        _bleState.value = BleState.ERROR
                        _status.value = "Cannot verify UWB_CONFIG_RESP: Master key missing"
                        return
                    }

                    val payloadBytes = if (value.size > SmartKeyProtocol.HEADER_SIZE) {
                        value.copyOfRange(SmartKeyProtocol.HEADER_SIZE, value.size)
                    } else ByteArray(0)

                    // P0.1: Authentication tag is MANDATORY for UWB_CONFIG_RESP
                    val minExpectedSize = 4 + SmartKeyProtocol.AUTH_TAG_SIZE
                    if (payloadBytes.size < minExpectedSize) {
                        _bleState.value = BleState.ERROR
                        _status.value = "UWB_CONFIG_RESP rejected: Missing mandatory authentication tag."
                        return
                    }

                    val rawPayloadWithoutTag = payloadBytes.copyOfRange(0, payloadBytes.size - SmartKeyProtocol.AUTH_TAG_SIZE)
                    val headerBytes = value.copyOfRange(0, SmartKeyProtocol.HEADER_SIZE)
                    val expectedTag = payloadBytes.copyOfRange(payloadBytes.size - SmartKeyProtocol.AUTH_TAG_SIZE, payloadBytes.size)

                    val tagValid = SmartKeyProtocol.verifyAuthTag(msk, headerBytes, rawPayloadWithoutTag, expectedTag)
                    if (!tagValid) {
                        _bleState.value = BleState.ERROR
                        _status.value = "UWB_CONFIG_RESP verification FAILED: Invalid auth tag from ESP32!"
                        return
                    }

                    val configResp = SmartKeyProtocol.parseUwbConfigResponsePayload(rawPayloadWithoutTag)
                    if (configResp != null && configResp.status == SmartKeyProtocol.ERR_NONE) {
                        if (configResp.esp32UwbAddress.size != 2 && configResp.esp32UwbAddress.size != 8) {
                            _bleState.value = BleState.ERROR
                            _status.value = "ESP32 UWB address in CONFIG_RESP is invalid (${configResp.esp32UwbAddress.size}B). Handshake aborted."
                            return
                        }

                        val currentStsKey = sessionStsKey ?: run {
                            _bleState.value = BleState.ERROR
                            _status.value = "Cannot finalize UWB config: STS session key missing. Session invalid."
                            return
                        }

                        if (configResp.sessionId != currentSid) {
                            _bleState.value = BleState.ERROR
                            _status.value = "UWB_CONFIG_RESP rejected: Payload session ID (0x%08X) does not match active session (0x%08X).".format(configResp.sessionId, currentSid)
                            return
                        }

                        val peerAddr = configResp.esp32UwbAddress
                        val sid = (currentSid and 0xFFFFFFFFL).toInt()

                        _uwbConfig.value = UwbConfig(
                            peerAddress = peerAddr,
                            channel = 9,
                            preambleIndex = 9,
                            sessionId = sid,
                            configId = RangingParameters.CONFIG_PROVISIONED_UNICAST_DS_TWR,
                            sessionKeyInfo = currentStsKey
                        )

                        _bleState.value = BleState.UWB_CONFIG_CONFIRMED
                        _status.value = "UWB_CONFIG_CONFIRMED by ESP32 Controller! (Peer: ${_uwbConfig.value?.peerAddressHex}). Ready to range."
                    } else {
                        val err = configResp?.status ?: SmartKeyProtocol.ERR_INTERNAL_FAULT
                        _bleState.value = BleState.ERROR
                        _status.value = "ESP32 rejected UWB config: ${SmartKeyProtocol.getErrorMessage(err)}"
                    }
                }
                SmartKeyProtocol.MSG_ERROR_IND -> {
                    requestPending = false
                    handler.removeCallbacks(requestTimeout)
                    pendingRequestType = null
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

    /**
     * Receives the raw challenge bytes from the dedicated nonce characteristic.
     * Transport dispatch is ready, but the authentication response is blocked:
     * firmware has not yet confirmed the HMAC key/input, ECDSA signing input, or
     * response byte format. Do not sign the raw nonce or claim authentication.
     */
    private fun handleNonceMessage(value: ByteArray) {
        _nonceEvent.value =
            "Nonce received (${value.size}B); response BLOCKED pending firmware HMAC/signing contract"
    }
}
