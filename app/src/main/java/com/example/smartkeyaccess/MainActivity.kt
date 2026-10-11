package com.example.smartkeyaccess

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.nfc.NfcAdapter
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.smartkeyaccess.ui.theme.SmartKeyAccessTheme
import com.example.smartkeyaccess.crypto.CryptoManager
import com.example.smartkeyaccess.mock.MockDataProvider
import com.example.smartkeyaccess.nfc.NfcHceBridge
import com.example.smartkeyaccess.protocol.SmartKeyProtocol
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.UUID

/**
 * High-level lifecycle states of the BLE Central subsystem for Milestone M1.
 * Models sequential progression from discovery to OTA ping-pong round-trip latency validation.
 */
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

class MainActivity : ComponentActivity() {

    /**
     * Standardized GATT 128-bit UUIDs agreed across ESP32 firmware and Android client
     * (derived from Nordic UART Service base: protocol/smartkey_protocol.h).
     */
    private val serviceUuid = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
    private val rxCharacteristicUuid = UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E") // Phone TX -> Lock RX (Write)
    private val txCharacteristicUuid = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E") // Lock TX -> Phone RX (Notify)

    /**
     * Standard Bluetooth SIG Client Characteristic Configuration Descriptor (CCCD).
     * Writing 0x0001 to this descriptor instructs the GATT Server firmware to transmit notifications over-the-air.
     */
    private val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    private var bleState by mutableStateOf(BleState.READY)
    private var scanning by mutableStateOf(false)
    private var status by mutableStateOf("Ready to find ESP32")
    private var rttTelemetryMs by mutableStateOf<Double?>(null)
    private var negotiatedMtu by mutableStateOf(23)
    private var currentSessionId by mutableStateOf<Long?>(null)

    // ESP32 TX Telemetry state (JSON parsing & arbitrary payload)
    private var esp32JsonPayload by mutableStateOf<String?>(null)
    private var phoneDelayReportedMs by mutableStateOf<Int?>(null)
    private var esp32DelayReportedMs by mutableStateOf<Int?>(null)
    private var rawTxPayload by mutableStateOf<String?>(null)

    // NFC Master Card Provisioning state
    private var nfcStatus by mutableStateOf("NFC Idle. Tap 'Enable NFC Reader' to scan Master Card...")
    private var vehicleId by mutableStateOf<ByteArray?>(null)
    private var masterSecretKey by mutableStateOf<ByteArray?>(null)

    private val handler = Handler(Looper.getMainLooper())
    private var scanner: BluetoothLeScanner? = null
    private var targetDevice: BluetoothDevice? = null
    private var bluetoothGatt: BluetoothGatt? = null

    private var writeCharacteristic: BluetoothGattCharacteristic? = null
    private var notifyCharacteristic: BluetoothGattCharacteristic? = null

    /**
     * Request tracking for BLE request/response transactions.
     *
     * Important note for this project:
     * - The current ESP32 firmware is allowed to send JSON telemetry to the phone at any time through TX Notify.
     * - We therefore do NOT make PING/PONG a mandatory gate before DATA_READY.
     * - requestPending is only used when the phone actively sends a request and expects a correlated response
     *   (for example AUTH_REQ -> AUTH_RESP).
     *
     * This separation is useful for us as students because it prevents a debug feature (PING/PONG) from
     * blocking the real data path that Kiệt needs to test.
     */
    private var requestStartNs: Long = 0L
    private var requestPending = false

    /**
     * Guard against dropped packets or unresponsive peripheral during transmission.
     */
    private val requestTimeout = Runnable {
        if (requestPending) {
            requestPending = false
            failGatt("Request timeout: No response received within 3000ms")
        }
    }

    /**
     * Scan timeout guard: Prevents battery depletion and radio lockup if target device is out of range.
     */
    private val scanTimeout = Runnable {
        if (scanning) {
            stopScan()
            bleState = BleState.ERROR
            status = "ESP32 advertising service UUID not found in 10s"
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val hasService = result.scanRecord?.serviceUuids?.any { it.uuid == serviceUuid } == true
            if (hasService) {
                val foundDevice = result.device
                runOnUiThread {
                    if (scanning) {
                        // Stop scanning before opening the GATT connection.
                        // This reduces unnecessary BLE radio activity while the connection is established
                        // and helps avoid connection instability observed on some Android devices.
                        stopScan()
                        targetDevice = foundDevice
                        bleState = BleState.FOUND
                        status = "Detected ESP32 (${foundDevice.address}). Connecting GATT..."
                        connectToTarget()
                    }
                }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            runOnUiThread {
                if (!scanning) return@runOnUiThread
                stopScan()
                bleState = BleState.ERROR
                status = "Scan failed (error code $errorCode)"
            }
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        if (granted[Manifest.permission.BLUETOOTH_SCAN] == true &&
            granted[Manifest.permission.BLUETOOTH_CONNECT] == true
        ) {
            startFindingEsp32()
        } else {
            bleState = BleState.ERROR
            status = "Nearby devices permission required to find ESP32"
        }
    }

    private val enableBluetoothLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            startFindingEsp32()
        } else {
            bleState = BleState.ERROR
            status = "Bluetooth is disabled"
        }
    }

    private fun startNfcReader() {
        val adapter = NfcAdapter.getDefaultAdapter(this)
        if (adapter == null) {
            nfcStatus = "Device lacks NFC hardware."
            return
        }
        if (!adapter.isEnabled) {
            nfcStatus = "NFC is disabled. Please enable NFC in system settings."
            return
        }
        nfcStatus = "NFC active. Hold phone near PN532 reader..."
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Observe NFC HCE telemetry events and provisioned credentials from background service
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    NfcHceBridge.eventFlow.collect { event ->
                        nfcStatus = event
                    }
                }
                launch {
                    NfcHceBridge.provisionedVehicleId.collect { vid ->
                        if (vid != null) {
                            vehicleId = vid
                            masterSecretKey = MockDataProvider.FIRMWARE_MSK_32B
                        }
                    }
                }
            }
        }

        setContent {
            SmartKeyAccessTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    SmartKeyMainScreen(
                        bleState = bleState,
                        bleStatus = status,
                        rttMs = rttTelemetryMs,
                        negotiatedMtu = negotiatedMtu,
                        sessionId = currentSessionId,
                        isBleBusy = scanning || bleState == BleState.CONNECTING || bleState == BleState.DISCOVERING || bleState == BleState.MTU_NEGOTIATING || bleState == BleState.SUBSCRIBING,
                        onFindBleClick = ::startFindingEsp32,
                        onResetBleClick = ::resetConnection,
                        onSendAuthClick = ::sendAuthRequest,
                        nfcStatus = nfcStatus,
                        vehicleIdHex = vehicleId?.let(CryptoManager::toHex),
                        masterKeyHex = masterSecretKey?.let(CryptoManager::toHex),
                        onStartNfcClick = ::startNfcReader,
                        esp32JsonPayload = esp32JsonPayload,
                        phoneDelayMs = phoneDelayReportedMs,
                        esp32DelayMs = esp32DelayReportedMs,
                        rawTxPayload = rawTxPayload,
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun startFindingEsp32() {
        if (scanning) return
        cleanupGatt()
        rttTelemetryMs = null

        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
            bleState = BleState.ERROR
            status = "Device does not support BLE"
            return
        }

        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null) {
            bleState = BleState.ERROR
            status = "Cannot find Bluetooth adapter"
            return
        }

        if (!hasPermission(Manifest.permission.BLUETOOTH_SCAN) ||
            !hasPermission(Manifest.permission.BLUETOOTH_CONNECT)
        ) {
            permissionLauncher.launch(
                arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
            )
            return
        }

        try {
            if (!adapter.isEnabled) {
                enableBluetoothLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                return
            }

            val activeScanner = adapter.bluetoothLeScanner
            if (activeScanner == null) {
                bleState = BleState.ERROR
                status = "Unable to obtain BLE scanner"
                return
            }

            scanner = activeScanner
            scanning = true
            bleState = BleState.SCANNING
            status = "Scanning for ESP32..."
            activeScanner.startScan(scanCallback)
            handler.postDelayed(scanTimeout, 10_000L)
        } catch (_: SecurityException) {
            stopScan()
            bleState = BleState.ERROR
            status = "Bluetooth permission missing"
        } catch (_: IllegalStateException) {
            stopScan()
            bleState = BleState.ERROR
            status = "Cannot start BLE scan"
        }
    }

    private fun stopScan() {
        if (!scanning) return
        scanning = false
        handler.removeCallbacks(scanTimeout)
        try {
            scanner?.stopScan(scanCallback)
        } catch (_: SecurityException) {
            // Guard against edge cases where permission was revoked while actively scanning
        }
        scanner = null
    }

    /**
     * Releases active GATT handles and resets transport references.
     * Prevents Android OS connection table exhaustion (BluetoothGatt handle leak).
     */
    private fun cleanupGatt() {
        handler.removeCallbacks(requestTimeout)
        val gatt = bluetoothGatt
        bluetoothGatt = null

        writeCharacteristic = null
        notifyCharacteristic = null
        requestPending = false

        try {
            gatt?.disconnect()
        } catch (_: SecurityException) {
        }

        gatt?.close()
    }

    /**
     * Fail-closed error handler: Ensures all radio resources are torn down before entering error state.
     */
    private fun failGatt(reason: String? = null) {
        cleanupGatt()
        runOnUiThread {
            bleState = BleState.ERROR
            status = if (reason != null) "GATT Error: $reason" else "GATT Error (Connection Failed)"
        }
    }

    private fun resetConnection() {
        stopScan()
        cleanupGatt()
        targetDevice = null
        rttTelemetryMs = null
        currentSessionId = null
        esp32JsonPayload = null
        phoneDelayReportedMs = null
        esp32DelayReportedMs = null
        rawTxPayload = null
        negotiatedMtu = 23
        bleState = BleState.READY
        status = "Ready to find ESP32"
    }

    private fun connectToTarget() {
        val device = targetDevice ?: run {
            failGatt("Target device is null")
            return
        }

        runOnUiThread {
            bleState = BleState.CONNECTING
            status = "Connecting GATT to ${device.address}..."
        }

        try {
            // autoConnect = false bypasses Android's background connection throttling,
            // executing an immediate direct radio handshake required for responsive hands-free entry.
            bluetoothGatt = device.connectGatt(
                this,
                false,
                gattCallback
            )
        } catch (_: SecurityException) {
            failGatt("Missing BLUETOOTH_CONNECT permission")
        }
    }

    private fun requestMtu(gatt: BluetoothGatt) {
        runOnUiThread {
            bleState = BleState.MTU_NEGOTIATING
            status = "Negotiating MTU (requesting 512 bytes)..."
        }

        val started = try {
            // Request a larger ATT MTU for future protocol messages.
            // Android 14+ requests 517 bytes for the first MTU request.
            // The negotiated value is delivered via onMtuChanged().
            gatt.requestMtu(512)
        } catch (_: SecurityException) {
            false
        }

        if (!started) {
            // Graceful fallback: do not fail the BLE connection only because MTU negotiation did not start.
            // Small telemetry JSON can still be received with the default ATT MTU. Larger crypto frames will
            // need either a bigger negotiated MTU or fragmentation later when that milestone is implemented.
            runOnUiThread {
                status = "MTU request not started, continuing with default 23 bytes..."
            }
            subscribeToNotifications(gatt)
        }
    }

    private fun subscribeToNotifications(gatt: BluetoothGatt) {
        val notifyChar = notifyCharacteristic ?: run {
            failGatt("Notify characteristic missing")
            return
        }

        runOnUiThread {
            bleState = BleState.SUBSCRIBING
            status = "Subscribing to TX notifications (CCCD 0x2902)..."
        }

        val enabled = try {
            // Enables notification listening in Android's local Bluetooth stack
            gatt.setCharacteristicNotification(notifyChar, true)
        } catch (_: SecurityException) {
            false
        }

        if (!enabled) {
            failGatt("Failed to enable notification locally")
            return
        }

        val cccd = notifyChar.getDescriptor(CCCD_UUID)
        if (cccd == null) {
            failGatt("CCCD descriptor not found on TX")
            return
        }

        val result = try {
            // Enables notifications on the remote GATT server by writing the CCCD.
            gatt.writeDescriptor(
                cccd,
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            )
        } catch (_: SecurityException) {
            BluetoothStatusCodes.ERROR_MISSING_BLUETOOTH_CONNECT_PERMISSION
        }

        if (result != BluetoothStatusCodes.SUCCESS) {
            failGatt("writeDescriptor failed (code $result)")
        }
    }

    // =========================================================================
    // TRANSPORT LAYER (Generic Frame Transmission)
    // =========================================================================

    /**
     * Transport Layer: Transmits an arbitrary binary frame over the BLE GATT RX characteristic.
     */
    private fun writeBleFrame(frame: ByteArray, onFailed: (Int) -> Unit = {}): Boolean {
        val gatt = bluetoothGatt ?: run {
            failGatt("BluetoothGatt handle is null")
            return false
        }
        val writeChar = writeCharacteristic ?: run {
            failGatt("Write characteristic missing")
            return false
        }

        val result = try {
            gatt.writeCharacteristic(
                writeChar,
                frame,
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            )
        } catch (_: SecurityException) {
            BluetoothStatusCodes.ERROR_MISSING_BLUETOOTH_CONNECT_PERMISSION
        }

        if (result != BluetoothStatusCodes.SUCCESS) {
            requestPending = false
            handler.removeCallbacks(requestTimeout)
            onFailed(result)
            return false
        }
        return true
    }

    // =========================================================================
    // APPLICATION LAYER (Outbound Messages)
    // =========================================================================

    /**
     * OPTIONAL diagnostic helper only.
     *
     * Earlier M1 code used ASCII "PING" -> "PONG" to prove that both BLE directions worked.
     * Kiệt's current firmware flow does not require PING/PONG before normal traffic, so this function is
     * intentionally NOT called automatically after CCCD subscription anymore.
     *
     * If the firmware later adds a PING handler, this helper can still be called manually while debugging.
     * The measured value is application/GATT round-trip time, not UWB Time-of-Flight and must not be used
     * to calculate distance.
     */
    private fun sendPing() {
        requestStartNs = SystemClock.elapsedRealtimeNanos()
        requestPending = true
        handler.removeCallbacks(requestTimeout)
        handler.postDelayed(requestTimeout, 3_000L)

        runOnUiThread {
            status = "Testing data pipe: Sending PING (4B) to ESP32..."
        }

        val frame = "PING".encodeToByteArray()
        writeBleFrame(frame) { errCode ->
            failGatt("writeCharacteristic PING failed (code $errCode)")
        }
    }

    /**
     * Application Layer: Performs cryptographic handshake using binary AUTH_REQ frame (60 bytes).
     */
    private fun sendAuthRequest() {
        requestStartNs = SystemClock.elapsedRealtimeNanos()
        requestPending = true
        handler.removeCallbacks(requestTimeout)
        handler.postDelayed(requestTimeout, 3_000L)

        runOnUiThread {
            status = "Authenticating: Sending binary AUTH_REQ (60B) to ESP32..."
        }

        val phoneNonce = ByteArray(SmartKeyProtocol.NONCE_SIZE).also { java.security.SecureRandom().nextBytes(it) }
        val phoneId = "PHON0001".toByteArray(Charsets.UTF_8)
        val payload = SmartKeyProtocol.buildAuthRequestPayload(phoneId, phoneNonce)
        val authTag = ByteArray(SmartKeyProtocol.AUTH_TAG_SIZE) // Empty tag for initial handshake

        val frame = SmartKeyProtocol.encodeFrame(
            msgType = SmartKeyProtocol.MSG_AUTH_REQ,
            payload = payload,
            authTag = authTag
        )

        writeBleFrame(frame) { errCode ->
            failGatt("writeCharacteristic AUTH_REQ failed (code $errCode)")
        }
    }

    // =========================================================================
    // PROTOCOL LAYER (Incoming Message Demuxing & Dispatch)
    // =========================================================================

    /**
     * Common receive entry point for every notification coming from ESP32 TX characteristic.
     *
     * We do not call readCharacteristic() here. TX is configured as NOTIFY, therefore ESP32 pushes a value
     * and Android delivers that value to onCharacteristicChanged(), which then forwards it here.
     *
     * For the current integration test requested by Kiệt, the most important branch is JSON telemetry:
     *     {"phone":200,"esp32":100}
     * The app keeps the complete JSON for display and also extracts the two delay values when present.
     * Other branches are kept because the same TX pipe will later carry AUTH_RESP and protocol frames.
     */
    private fun handleBleMessage(value: ByteArray) {
        val textValue = try {
            String(value, Charsets.UTF_8).trim()
        } catch (_: Exception) {
            null
        }

        // 1. ASCII or binary PONG pipe test response
        if (value.contentEquals("PONG".encodeToByteArray()) || textValue == "PONG") {
            val endNs = SystemClock.elapsedRealtimeNanos()
            val rtt = (endNs - requestStartNs) / 1_000_000.0
            requestPending = false
            handler.removeCallbacks(requestTimeout)
            runOnUiThread {
                handlePongResponse(rtt)
            }
            return
        }

        // 2. JSON Telemetry payload from ESP32 TX Notify (e.g. {"phone":200,"esp32":100})
        if (textValue != null && textValue.startsWith("{") && textValue.endsWith("}")) {
            try {
                val json = org.json.JSONObject(textValue)

                // Kiệt's current test packet uses the short keys "phone" and "esp32".
                // A few aliases are accepted as a small convenience while the JSON contract is still being finalized.
                fun readDelay(vararg keys: String): Int? {
                    for (key in keys) {
                        if (json.has(key) && !json.isNull(key)) {
                            val value = json.optInt(key, Int.MIN_VALUE)
                            if (value != Int.MIN_VALUE) return value
                        }
                    }
                    return null
                }

                val phoneDelay = readDelay("phone", "phoneDelay", "phone_delay")
                val esp32Delay = readDelay("esp32", "esp32Delay", "esp32_delay")

                runOnUiThread {
                    esp32JsonPayload = textValue
                    phoneDelayReportedMs = phoneDelay
                    esp32DelayReportedMs = esp32Delay
                    status = buildString {
                        append("JSON received from ESP32!")
                        if (phoneDelay != null) append(" Phone delay: ${phoneDelay}ms")
                        if (esp32Delay != null) append(" | ESP32 delay: ${esp32Delay}ms")
                    }
                }
                return
            } catch (_: Exception) {
                // Not valid JSON, continue to raw text
            }
        }

        // 3. Binary SmartKeyProtocol frame (0xA5 header)
        val header = SmartKeyProtocol.parseHeader(value)
        if (header != null) {
            val endNs = SystemClock.elapsedRealtimeNanos()
            val rtt = (endNs - requestStartNs) / 1_000_000.0
            requestPending = false
            handler.removeCallbacks(requestTimeout)

            runOnUiThread {
                when (header.msgType) {
                    SmartKeyProtocol.MSG_PONG -> {
                        handlePongResponse(rtt)
                    }
                    SmartKeyProtocol.MSG_AUTH_RESP -> {
                        val payloadBytes = if (value.size > SmartKeyProtocol.HEADER_SIZE) {
                            value.copyOfRange(SmartKeyProtocol.HEADER_SIZE, value.size)
                        } else ByteArray(0)
                        handleAuthResponse(header, payloadBytes, rtt)
                    }
                    SmartKeyProtocol.MSG_UWB_CONFIG_RESP -> {
                        bleState = BleState.DATA_READY
                        status = "UWB_CONFIG_RESP received! Anchor Ready (RTT = %.2f ms)".format(Locale.US, rtt)
                    }
                    SmartKeyProtocol.MSG_ERROR_IND -> {
                        val errCode = if (value.size > SmartKeyProtocol.HEADER_SIZE) value[SmartKeyProtocol.HEADER_SIZE] else SmartKeyProtocol.ERR_INTERNAL_FAULT
                        bleState = BleState.ERROR
                        status = "ESP32 Error: " + SmartKeyProtocol.getErrorMessage(errCode)
                    }
                    else -> {
                        status = "Received unknown binary msg_type: 0x%02X".format(header.msgType)
                    }
                }
            }
            return
        }

        // 4. Fallback: arbitrary text or raw byte payload
        if (textValue != null && textValue.isNotEmpty() && textValue.all { it.code in 32..126 || it == '\n' || it == '\r' }) {
            runOnUiThread {
                rawTxPayload = textValue
                status = "Received TX text: $textValue"
            }
        } else {
            runOnUiThread {
                rawTxPayload = CryptoManager.toHex(value)
                status = "Received raw TX bytes (${value.size}B): ${CryptoManager.toHex(value)}"
            }
        }
    }

    private fun handlePongResponse(rtt: Double) {
        bleState = BleState.DATA_READY
        rttTelemetryMs = rtt
        status = "PONG received! BLE GATT RTT = ${String.format(Locale.US, "%.2f", rtt)} ms"
    }

    private fun handleAuthResponse(header: SmartKeyProtocol.Header, payloadBytes: ByteArray, rtt: Double) {
        val authResp = SmartKeyProtocol.parseAuthResponsePayload(payloadBytes)
        val sessionId = authResp?.newSessionId ?: header.sessionId
        currentSessionId = sessionId
        bleState = BleState.DATA_READY
        rttTelemetryMs = rtt
        status = "AUTH_RESP received! Session = $sessionId (Handshake RTT = ${String.format(Locale.US, "%.2f", rtt)} ms)"
    }

    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(
            gatt: BluetoothGatt,
            gattStatus: Int,
            newState: Int
        ) {
            if (
                gattStatus == BluetoothGatt.GATT_SUCCESS &&
                newState == BluetoothProfile.STATE_CONNECTED
            ) {
                bluetoothGatt = gatt
                val started = try {
                    // Discover the remote GATT services before accessing characteristics.
                    gatt.discoverServices()
                } catch (_: SecurityException) {
                    false
                }

                // BluetoothGattCallback runs on Binder thread pool; marshal state changes to UI main looper
                runOnUiThread {
                    if (started) {
                        bleState = BleState.DISCOVERING
                        status = "Connected! Discovering GATT Services..."
                    } else {
                        failGatt("discoverServices failed to start")
                    }
                }
            } else {
                failGatt("Disconnected or GATT status $gattStatus")
            }
        }

        override fun onServicesDiscovered(
            gatt: BluetoothGatt,
            status: Int
        ) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                failGatt("Service discovery failed with status $status")
                return
            }

            val service = gatt.getService(serviceUuid)
            if (service == null) {
                failGatt("Smart Key Service ($serviceUuid) not found")
                return
            }

            val writeChar = service.getCharacteristic(rxCharacteristicUuid)
            val notifyChar = service.getCharacteristic(txCharacteristicUuid)

            if (writeChar == null || notifyChar == null) {
                failGatt("RX or TX characteristic missing in service")
                return
            }

            // Verify peripheral characteristic properties meet unidirectional protocol contracts
            val canWrite = writeChar.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0
            val canNotify = notifyChar.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0

            if (!canWrite || !canNotify) {
                failGatt("Characteristics lack WRITE or NOTIFY properties")
                return
            }

            writeCharacteristic = writeChar
            notifyCharacteristic = notifyChar

            requestMtu(gatt)
        }

        override fun onMtuChanged(
            gatt: BluetoothGatt,
            mtu: Int,
            status: Int
        ) {
            val actualMtu = if (status == BluetoothGatt.GATT_SUCCESS) mtu else 23
            runOnUiThread {
                negotiatedMtu = actualMtu
                this@MainActivity.status = "MTU negotiated: $actualMtu bytes. Subscribing to notifications..."
            }
            // Do not fail GATT only because the negotiated MTU is small.
            // The current milestone mainly needs TX Notify to receive Kiệt's JSON telemetry.
            // When public keys / encrypted frames are added, the higher protocol layer must check negotiatedMtu
            // and choose direct transmission or fragmentation instead of assuming every frame fits in one ATT packet.
            subscribeToNotifications(gatt)
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            if (
                descriptor.uuid == CCCD_UUID &&
                status == BluetoothGatt.GATT_SUCCESS
            ) {
                runOnUiThread {
                    /*
                     * Option B used for the current integration stage:
                     * once CCCD 0x2902 has been written successfully, the phone is ready to RECEIVE TX Notify.
                     * We do not force an automatic PING/PONG test here because Kiệt's current firmware may not
                     * implement the old text PING handler. Forcing PING would cause a timeout and disconnect even
                     * though the TX notification path itself is perfectly usable for the JSON test.
                     *
                     * DATA_READY here means "GATT data path configured and ready for application traffic",
                     * not "authentication finished" and not "UWB ready". Those are later protocol states.
                     */
                    bleState = BleState.DATA_READY
                    this@MainActivity.status =
                        "Notifications enabled. Ready to receive ESP32 TX data / JSON telemetry."
                }
            } else {
                failGatt("CCCD write failed with status $status")
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                requestPending = false
                handler.removeCallbacks(requestTimeout)
                failGatt("writeCharacteristic failed with status $status")
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            // TX is the ESP32 -> Phone direction. Android invokes this callback whenever Kiệt calls notify()
            // on the TX characteristic. We only accept the UUID that was discovered and saved earlier so that
            // a notification from another characteristic cannot accidentally enter the Smart Key parser.
            if (characteristic.uuid != notifyCharacteristic?.uuid) {
                return
            }

            // Keep the Bluetooth callback small. The common handler decides whether the value is JSON, PONG,
            // a binary SmartKey frame, or just raw text/bytes for debugging.
            handleBleMessage(value)
        }
    }

    override fun onDestroy() {
        stopScan()
        cleanupGatt()
        super.onDestroy()
    }
}

@Composable
private fun SmartKeyMainScreen(
    bleState: BleState,
    bleStatus: String,
    rttMs: Double?,
    negotiatedMtu: Int,
    sessionId: Long?,
    isBleBusy: Boolean,
    onFindBleClick: () -> Unit,
    onResetBleClick: () -> Unit,
    onSendAuthClick: () -> Unit,
    nfcStatus: String,
    vehicleIdHex: String?,
    masterKeyHex: String?,
    onStartNfcClick: () -> Unit,
    esp32JsonPayload: String? = null,
    phoneDelayMs: Int? = null,
    esp32DelayMs: Int? = null,
    rawTxPayload: String? = null,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "Smart Key Access (CCC-Inspired)",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )

        // CARD 1: NFC Master Card Provisioning
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "1. NFC Provisioning (Master Card)",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "Status: $nfcStatus",
                    style = MaterialTheme.typography.bodyMedium
                )

                if (vehicleIdHex != null && masterKeyHex != null) {
                    Text(
                        text = "VID: $vehicleIdHex",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        text = "MSK: $masterKeyHex",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace
                    )
                }

                Button(
                    onClick = onStartNfcClick,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Enable NFC Reader")
                }
            }
        }

        // CARD 2: BLE Central GATT Pipeline (Layered Architecture)
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "2. BLE Central Pipeline (Layered Protocol)",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "UUID: 6E400001-B5A3-F393-E0A9-E50E24DCCA9E",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    text = "BLE State: ${bleState.name}",
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "Negotiated MTU: $negotiatedMtu bytes (Payload max: ${negotiatedMtu - 3}B)",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace
                )
                if (sessionId != null) {
                    Text(
                        text = "Session ID: $sessionId",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                }
                Text(
                    text = "Status: $bleStatus",
                    style = MaterialTheme.typography.bodyMedium
                )

                if (rttMs != null) {
                    Text(
                        text = "Round-Trip Latency (RTT): ${String.format(Locale.US, "%.2f", rttMs)} ms",
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = onFindBleClick,
                        enabled = !isBleBusy,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(if (isBleBusy) "Busy..." else "Find ESP32")
                    }

                    if (bleState == BleState.DATA_READY || bleState == BleState.ERROR) {
                        OutlinedButton(
                            onClick = onResetBleClick,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Reset")
                        }
                    }
                }

                if (bleState == BleState.DATA_READY) {
                    Text(
                        text = "TX Notify is active. Waiting for ESP32 JSON telemetry; PING/PONG is not mandatory in Option B.",
                        style = MaterialTheme.typography.bodySmall
                    )

                    Button(
                        onClick = onSendAuthClick,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Auth Handshake (later protocol test)")
                    }
                }
            }
        }

        // CARD 3: ESP32 TX Telemetry (JSON / Payload Viewer)
        if (esp32JsonPayload != null || phoneDelayMs != null || esp32DelayMs != null || rawTxPayload != null) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "3. ESP32 TX Telemetry",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )

                    if (esp32JsonPayload != null) {
                        Text(
                            text = "JSON: $esp32JsonPayload",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace
                        )
                    }

                    if (phoneDelayMs != null || esp32DelayMs != null) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            if (phoneDelayMs != null) {
                                Text(
                                    text = "Phone delay: ${phoneDelayMs} ms",
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            if (esp32DelayMs != null) {
                                Text(
                                    text = "ESP32 delay: ${esp32DelayMs} ms",
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.secondary
                                )
                            }
                        }
                    }

                    if (rawTxPayload != null) {
                        Text(
                            text = "Raw TX: $rawTxPayload",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun SmartKeyMainScreenPreview() {
    SmartKeyAccessTheme {
        SmartKeyMainScreen(
            bleState = BleState.DATA_READY,
            bleStatus = "Notifications enabled. Ready to receive ESP32 TX data / JSON telemetry.",
            rttMs = null,
            negotiatedMtu = 512,
            sessionId = 412589L,
            isBleBusy = false,
            onFindBleClick = {},
            onResetBleClick = {},
            onSendAuthClick = {},
            nfcStatus = "NFC active. Tap Master Card to device...",
            vehicleIdHex = "0102030405060708",
            masterKeyHex = "0102030405060708090A0B0C0D0E0F101112131415161718191A1B1C1D1E1F20",
            onStartNfcClick = {},
            esp32JsonPayload = "{\"phone\":200,\"esp32\":100}",
            phoneDelayMs = 200,
            esp32DelayMs = 100,
            rawTxPayload = null
        )
    }
}

