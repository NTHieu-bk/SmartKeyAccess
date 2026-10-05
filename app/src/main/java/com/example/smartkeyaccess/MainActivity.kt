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
    PIPE_TESTING,
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
     * Test vectors for Milestone M1 BLE GATT baseline validation.
     * Verifies bi-directional pipe throughput before introducing crypto (M4) and UWB ranging (M2/M3).
     */
    private val PING = "PING".encodeToByteArray()
    private val PONG = "PONG".encodeToByteArray()
    private var pingStartNs: Long = 0L
    private var pingPending = false

    /**
     * Guard against dropped packets or unresponsive peripheral during PING/PONG pipe test.
     */
    private val pingTimeout = Runnable {
        if (pingPending) {
            pingPending = false
            failGatt("PING timeout: No PONG received within 2000ms")
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
                        isBleBusy = scanning || bleState == BleState.CONNECTING || bleState == BleState.DISCOVERING || bleState == BleState.MTU_NEGOTIATING || bleState == BleState.SUBSCRIBING || bleState == BleState.PIPE_TESTING,
                        onFindBleClick = ::startFindingEsp32,
                        onResetBleClick = ::resetConnection,
                        nfcStatus = nfcStatus,
                        vehicleIdHex = vehicleId?.let(CryptoManager::toHex),
                        masterKeyHex = masterSecretKey?.let(CryptoManager::toHex),
                        onStartNfcClick = ::startNfcReader,
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
        handler.removeCallbacks(pingTimeout)
        val gatt = bluetoothGatt
        bluetoothGatt = null

        writeCharacteristic = null
        notifyCharacteristic = null
        pingPending = false

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
            // Graceful fallback: Do not fail connection. Default ATT MTU (23 bytes) is sufficient for M1 PING/PONG.
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

    private fun sendPing(gatt: BluetoothGatt) {
        val writeChar = writeCharacteristic ?: run {
            failGatt("Write characteristic missing")
            return
        }

        pingStartNs = SystemClock.elapsedRealtimeNanos()
        pingPending = true

        // Bound the pipe test so a missing PONG cannot leave the state machine stuck.
        handler.removeCallbacks(pingTimeout)
        handler.postDelayed(pingTimeout, 2_000L)

        runOnUiThread {
            status = "Testing data pipe: Sending PING (4B) to ESP32..."
        }

        val result = try {
            gatt.writeCharacteristic(
                writeChar,
                PING,
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            )
        } catch (_: SecurityException) {
            BluetoothStatusCodes.ERROR_MISSING_BLUETOOTH_CONNECT_PERMISSION
        }

        if (result != BluetoothStatusCodes.SUCCESS) {
            pingPending = false
            handler.removeCallbacks(pingTimeout)
            failGatt("writeCharacteristic PING failed (code $result)")
        }
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
            // Do NOT fail GATT if MTU is small. Baseline M1 only needs 4 bytes for PING/PONG.
            // Higher layer (M4 Crypto) will inspect negotiatedMtu to decide direct vs fragmented transmission.
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
                    // Semantics: At this point, notifications are enabled, but data path is not yet verified.
                    bleState = BleState.PIPE_TESTING
                    this@MainActivity.status = "Notifications enabled. Testing bidirectional data pipe (PING -> PONG)..."
                }
                sendPing(gatt)
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
                pingPending = false
                handler.removeCallbacks(pingTimeout)
                failGatt("PING write failed with status $status")
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            if (!pingPending) return

            if (characteristic.uuid != notifyCharacteristic?.uuid) {
                return
            }

            if (value.contentEquals(PONG)) {
                val endNs = SystemClock.elapsedRealtimeNanos()
                val rtt = (endNs - pingStartNs) / 1_000_000.0
                pingPending = false
                handler.removeCallbacks(pingTimeout)

                runOnUiThread {
                    // Only transition to DATA_READY once PONG is verified over the air
                    bleState = BleState.DATA_READY
                    rttTelemetryMs = rtt
                    this@MainActivity.status = "PONG received! BLE GATT RTT = ${String.format(Locale.US, "%.2f", rtt)} ms"
                }
            }
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
    isBleBusy: Boolean,
    onFindBleClick: () -> Unit,
    onResetBleClick: () -> Unit,
    nfcStatus: String,
    vehicleIdHex: String?,
    masterKeyHex: String?,
    onStartNfcClick: () -> Unit,
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

        // CARD 2: BLE Central GATT Pipeline (M1 Baseline)
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "2. BLE Central Pipeline (M1 Baseline)",
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
                Text(
                    text = "Status: $bleStatus",
                    style = MaterialTheme.typography.bodyMedium
                )

                if (rttMs != null) {
                    Text(
                        text = "BLE GATT Round-Trip (RTT): ${String.format(Locale.US, "%.2f", rttMs)} ms",
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
            bleStatus = "PONG received! BLE GATT RTT = 24.50 ms",
            rttMs = 24.5,
            negotiatedMtu = 512,
            isBleBusy = false,
            onFindBleClick = {},
            onResetBleClick = {},
            nfcStatus = "NFC active. Tap Master Card to device...",
            vehicleIdHex = "0102030405060708",
            masterKeyHex = "0102030405060708090A0B0C0D0E0F101112131415161718191A1B1C1D1E1F20",
            onStartNfcClick = {}
        )
    }
}
