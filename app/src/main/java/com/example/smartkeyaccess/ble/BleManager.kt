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

    private val _status = MutableStateFlow("Sẵn sàng tìm ESP32")
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

    // Danh sách chờ ghi descriptor để không bị ghi chồng lệnh GATT
    private val descriptorWriteQueue = ArrayDeque<BluetoothGattDescriptor>()

    private val requestTimeout = Runnable {
        if (requestPending) {
            requestPending = false
            failGatt("Hết thời gian chờ phản hồi (3000ms)")
        }
    }

    private val scanTimeout = Runnable {
        if (scanning) {
            stopScan()
            _bleState.value = BleState.ERROR
            _status.value = "Hết 10s không tìm thấy ESP32 quảng bá"
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val matchesService = result.scanRecord?.serviceUuids?.any { it.uuid == SERVICE_UUID } == true
            if (matchesService) {
                val device = result.device
                stopScan()
                _bleState.value = BleState.FOUND
                _status.value = "Tìm thấy ESP32 (${device.address}), đang kết nối GATT..."
                connectGatt(device)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            stopScan()
            _bleState.value = BleState.ERROR
            _status.value = "Quét BLE thất bại (Mã lỗi: $errorCode)"
        }
    }

    fun startScan(adapter: BluetoothAdapter): Boolean {
        if (scanning) return true
        cleanupGatt()
        _rttMs.value = null

        val leScanner = adapter.bluetoothLeScanner
        if (leScanner == null) {
            _bleState.value = BleState.ERROR
            _status.value = "Không khởi tạo được BLE Scanner"
            return false
        }

        scanner = leScanner
        scanning = true
        _bleState.value = BleState.SCANNING
        _status.value = "Đang quét tìm ESP32..."
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
            // autoConnect = false để kết nối radio ngay lập tức
            bluetoothGatt = device.connectGatt(context, false, gattCallback)
        } catch (e: Exception) {
            failGatt("Lỗi connectGatt: ${e.message}")
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
        _status.value = "GATT Lỗi: $reason"
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
        _status.value = "Sẵn sàng tìm ESP32"
    }

    // Gửi frame nhị phân hoặc chuỗi byte qua RX (Write)
    fun writeBleFrame(frame: ByteArray, onFailed: (Int) -> Unit = {}): Boolean {
        val gatt = bluetoothGatt ?: run {
            failGatt("Chưa kết nối GATT")
            return false
        }
        val targetChar = writeChar ?: run {
            failGatt("Không tìm thấy characteristic RX")
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

    // Ghi response trực tiếp vào Nonce Characteristic (6E400004)
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

    // Gửi PING để đo RTT nếu cần
    fun sendPing() {
        requestStartNs = SystemClock.elapsedRealtimeNanos()
        requestPending = true
        handler.removeCallbacks(requestTimeout)
        handler.postDelayed(requestTimeout, 3_000L)
        _status.value = "Đang gửi PING (4B) tới ESP32..."

        val frame = "PING".encodeToByteArray()
        writeBleFrame(frame) { errCode ->
            failGatt("Ghi PING thất bại (mã $errCode)")
        }
    }

    // Gửi gói AUTH_REQ nhị phân chuẩn bị cho bước bắt tay
    fun sendAuthRequest() {
        requestStartNs = SystemClock.elapsedRealtimeNanos()
        requestPending = true
        handler.removeCallbacks(requestTimeout)
        handler.postDelayed(requestTimeout, 3_000L)
        _status.value = "Đang gửi AUTH_REQ nhị phân (60B)..."

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
            failGatt("Ghi AUTH_REQ thất bại (mã $errCode)")
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                bluetoothGatt = gatt
                val ok = try { gatt.discoverServices() } catch (_: Exception) { false }
                if (ok) {
                    _bleState.value = BleState.DISCOVERING
                    _status.value = "Đã kết nối! Đang tìm GATT Services..."
                } else {
                    failGatt("Không thể gọi discoverServices()")
                }
            } else {
                failGatt("Ngắt kết nối hoặc lỗi status=$status")
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                failGatt("Tìm service thất bại (status $status)")
                return
            }

            val service = gatt.getService(SERVICE_UUID)
            if (service == null) {
                failGatt("Không tìm thấy Service Smart Key ($SERVICE_UUID)")
                return
            }

            writeChar = service.getCharacteristic(RX_UUID)
            notifyChar = service.getCharacteristic(TX_UUID)
            nonceChar = service.getCharacteristic(NONCE_UUID)

            if (writeChar == null || notifyChar == null) {
                failGatt("Thiếu characteristic RX hoặc TX trong service")
                return
            }

            // Xin MTU 512B
            _bleState.value = BleState.MTU_NEGOTIATING
            _status.value = "Đang thương lượng MTU (512 bytes)..."
            val started = try { gatt.requestMtu(512) } catch (_: Exception) { false }
            if (!started) {
                startSubscribing(gatt)
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            val actual = if (status == BluetoothGatt.GATT_SUCCESS) mtu else 23
            _negotiatedMtu.value = actual
            _status.value = "MTU đạt $actual bytes. Đang đăng ký nhận Notify..."
            startSubscribing(gatt)
        }

        // Bật Notify tuần tự cho TX và Nonce characteristic
        private fun startSubscribing(gatt: BluetoothGatt) {
            _bleState.value = BleState.SUBSCRIBING
            descriptorWriteQueue.clear()

            // 1. Subscribe TX (6E400003)
            notifyChar?.let { char ->
                try {
                    gatt.setCharacteristicNotification(char, true)
                    char.getDescriptor(CCCD_UUID)?.let { cccd ->
                        descriptorWriteQueue.add(cccd)
                    }
                } catch (_: Exception) {}
            }

            // 2. Subscribe Nonce (6E400004) nếu firmware có cung cấp
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
                // Đã bật xong toàn bộ notification cần thiết
                _bleState.value = BleState.DATA_READY
                _status.value = "Đã bật Notify (TX + Nonce). Sẵn sàng nhận dữ liệu từ ESP32."
                return
            }

            val desc = descriptorWriteQueue.removeFirst()
            try {
                gatt.writeDescriptor(desc, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            } catch (e: Exception) {
                failGatt("Ghi CCCD lỗi: ${e.message}")
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                writeNextDescriptor(gatt)
            } else {
                failGatt("Ghi CCCD thất bại (status $status)")
            }
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                requestPending = false
                handler.removeCallbacks(requestTimeout)
                failGatt("Ghi dữ liệu characteristic thất bại (status $status)")
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            when (characteristic.uuid) {
                TX_UUID -> handleTxMessage(value)
                NONCE_UUID -> handleNonceMessage(value)
            }
        }
    }

    // Xử lý gói tin đến từ TX (PONG, JSON telemetry, binary frame...)
    private fun handleTxMessage(value: ByteArray) {
        val textValue = try { String(value, Charsets.UTF_8).trim() } catch (_: Exception) { null }

        // 1. Nhận PONG
        if (value.contentEquals("PONG".encodeToByteArray()) || textValue == "PONG") {
            val endNs = SystemClock.elapsedRealtimeNanos()
            val rtt = (endNs - requestStartNs) / 1_000_000.0
            requestPending = false
            handler.removeCallbacks(requestTimeout)
            _bleState.value = BleState.DATA_READY
            _rttMs.value = rtt
            _status.value = "Nhận PONG! RTT = %.2f ms".format(Locale.US, rtt)
            return
        }

        // 2. Nhận JSON telemetry từ ESP32 (ví dụ: {"phone":200,"esp32":100})
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
                    append("Đã nhận JSON telemetry!")
                    if (phone != null) append(" Phone: ${phone}ms")
                    if (esp != null) append(" | ESP32: ${esp}ms")
                }
                return
            } catch (_: Exception) {}
        }

        // 3. Khung nhị phân SmartKeyProtocol
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
                    _status.value = "Nhận binary PONG! RTT = %.2f ms".format(Locale.US, rtt)
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
                    _status.value = "Nhận AUTH_RESP! Session = $sid (RTT = %.2f ms)".format(Locale.US, rtt)
                }
                SmartKeyProtocol.MSG_UWB_CONFIG_RESP -> {
                    _bleState.value = BleState.DATA_READY
                    _status.value = "Nhận UWB_CONFIG_RESP! Sẵn sàng đo UWB (RTT = %.2f ms)".format(Locale.US, rtt)
                }
                SmartKeyProtocol.MSG_ERROR_IND -> {
                    val errCode = if (value.size > SmartKeyProtocol.HEADER_SIZE) value[SmartKeyProtocol.HEADER_SIZE] else SmartKeyProtocol.ERR_INTERNAL_FAULT
                    _bleState.value = BleState.ERROR
                    _status.value = "ESP32 báo lỗi: " + SmartKeyProtocol.getErrorMessage(errCode)
                }
                else -> {
                    _status.value = "Nhận binary msg_type: 0x%02X".format(header.msgType)
                }
            }
            return
        }

        // 4. Fallback text hoặc hex byte
        if (textValue != null && textValue.isNotEmpty() && textValue.all { it.code in 32..126 || it == '\n' || it == '\r' }) {
            _rawTxPayload.value = textValue
            _status.value = "Nhận TX text: $textValue"
        } else {
            val hex = CryptoManager.toHex(value)
            _rawTxPayload.value = hex
            _status.value = "Nhận TX raw (${value.size}B): $hex"
        }
    }

    // Xử lý gói tin đến từ Nonce Characteristic (6E400004)
    private fun handleNonceMessage(value: ByteArray) {
        val hex = CryptoManager.toHex(value)
        _nonceEvent.value = "ESP32 gửi Nonce (${value.size}B): $hex"

        // Băm hoặc ký ECDSA bằng ID_PRIVATE rồi ghi ngược lại 6E400004
        try {
            val privKey = NfcHceBridge.phoneIdentityKeyPair.private
            val signature = CryptoManager.signEcdsaP1363(privKey, value)
            val writeOk = writeNonceResponse(signature)
            if (writeOk) {
                _nonceEvent.value = "Đã ký ECDSA (64B) và gửi lại qua Nonce characteristic"
            } else {
                _nonceEvent.value = "Ký xong nhưng gửi lại Nonce characteristic thất bại"
            }
        } catch (e: Exception) {
            _nonceEvent.value = "Lỗi xử lý ký Nonce: ${e.message}"
        }
    }
}
