package com.example.smartkeyaccess

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.smartkeyaccess.ble.BleCapabilityReport
import com.example.smartkeyaccess.ble.BleManager
import com.example.smartkeyaccess.ble.BleState
import com.example.smartkeyaccess.crypto.CryptoManager
import com.example.smartkeyaccess.mock.MockDataProvider
import com.example.smartkeyaccess.nfc.MasterCardData
import com.example.smartkeyaccess.nfc.NfcHceBridge
import com.example.smartkeyaccess.nfc.NfcReaderManager
import com.example.smartkeyaccess.ui.theme.SmartKeyAccessTheme
import com.example.smartkeyaccess.protocol.SmartKeyProtocol
import com.example.smartkeyaccess.uwb.UwbCapabilityReport
import com.example.smartkeyaccess.uwb.UwbConfig
import com.example.smartkeyaccess.uwb.UwbManager
import com.example.smartkeyaccess.uwb.UwbSessionState
import java.util.Locale
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    // Subsystem managers
    private lateinit var bleManager: BleManager
    private lateinit var nfcReaderManager: NfcReaderManager
    private lateinit var uwbManager: UwbManager

    private var isNfcReaderEnabled by mutableStateOf(false)

    // Bluetooth and location permission launcher
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        val allGranted = perms.values.all { it }
        if (!allGranted) {
            Toast.makeText(this, "Permissions (Bluetooth / UWB) required for full operation", Toast.LENGTH_SHORT).show()
        }
    }

    // Bluetooth enable prompt launcher
    private val enableBtLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            startBleScan()
        } else {
            Toast.makeText(this, "Bluetooth is disabled", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        bleManager = BleManager(this)
        nfcReaderManager = NfcReaderManager(
            scope = lifecycleScope,
            onCardProvisioned = {
                isNfcReaderEnabled = false
                nfcReaderManager.provisionedCard.value?.masterSecretKey?.let {
                    bleManager.setMasterKey(it)
                }
            }
        )
        uwbManager = UwbManager(this)

        checkAndRequestPermissions()

        setContent {
            SmartKeyAccessTheme {
                val bleState by bleManager.bleState.collectAsState()
                val bleStatus by bleManager.status.collectAsState()
                val bleCapabilityReport by bleManager.capabilityReport.collectAsState()
                val rttMs by bleManager.rttMs.collectAsState()
                val mtu by bleManager.negotiatedMtu.collectAsState()
                val sessionId by bleManager.sessionId.collectAsState()
                val esp32UwbConfig by bleManager.esp32UwbConfig.collectAsState()

                val esp32Json by bleManager.esp32JsonPayload.collectAsState()
                val phoneDelay by bleManager.phoneDelayMs.collectAsState()
                val esp32Delay by bleManager.esp32DelayMs.collectAsState()
                val rawTx by bleManager.rawTxPayload.collectAsState()
                val nonceEvent by bleManager.nonceEvent.collectAsState()

                val nfcStatus by nfcReaderManager.status.collectAsState()
                val provisionedCard by nfcReaderManager.provisionedCard.collectAsState()
                val hceEvent by NfcHceBridge.eventFlow.collectAsState()
                val hceVid by NfcHceBridge.provisionedVehicleId.collectAsState()

                val uwbSessionState by uwbManager.sessionState.collectAsState()
                val uwbStatus by uwbManager.status.collectAsState()
                val capabilityReport by uwbManager.capabilityReport.collectAsState()
                val rawDistance by uwbManager.latestDistanceMeters.collectAsState()
                val activeSessionLocalAddress by uwbManager.activeSessionLocalAddress.collectAsState()

                // Stop UWB ranging immediately if BLE disconnects or encounters error
                LaunchedEffect(bleState) {
                    if (bleState == BleState.READY || bleState == BleState.ERROR) {
                        uwbManager.stopRanging()
                    }
                }

                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    SmartKeyMainScreen(
                        modifier = Modifier.padding(innerPadding),
                        bleState = bleState,
                        bleStatus = bleStatus,
                        rttMs = rttMs,
                        mtu = mtu,
                        sessionId = sessionId,
                        uwbConfig = esp32UwbConfig,
                        esp32Json = esp32Json,
                        phoneDelay = phoneDelay,
                        esp32Delay = esp32Delay,
                        rawTx = rawTx,
                        nonceEvent = nonceEvent,
                        nfcReaderEnabled = isNfcReaderEnabled,
                        nfcStatus = nfcStatus,
                        provisionedCard = provisionedCard,
                        hceEvent = hceEvent,
                        hceVid = hceVid,
                        bleCapabilityReport = bleCapabilityReport,
                        uwbSessionState = uwbSessionState,
                        uwbStatus = uwbStatus,
                        capabilityReport = capabilityReport,
                        rawDistance = rawDistance,
                        activeSessionLocalAddress = activeSessionLocalAddress,
                        onScanBle = { startBleScan() },
                        onResetBle = { bleManager.reset() },
                        onCheckBleCapabilities = { bleManager.checkCapabilities() },
                        onSendPing = { bleManager.sendPing() },
                        onSendAuthReq = { bleManager.sendAuthRequest() },
                        onSendUwbConfigReq = {
                            lifecycleScope.launch {
                                val phoneAddr = uwbManager.getLocalAddressBytes()
                                if (phoneAddr == null || (phoneAddr.size != 2 && phoneAddr.size != 8)) {
                                    Toast.makeText(this@MainActivity, "Cannot obtain valid local UWB address from phone. Check UWB subsystem.", Toast.LENGTH_LONG).show()
                                    return@launch
                                }
                                bleManager.sendUwbConfigRequest(phoneUwbAddress = phoneAddr)
                            }
                        },
                        onToggleNfcReader = { toggleNfcReader() },
                        onLoadMockNfc = { loadMockNfcCredentials() },
                        onCheckCapabilities = { uwbManager.checkCapabilities() },
                        onStartRanging = {
                            val cfg = esp32UwbConfig
                            if (cfg != null && bleState == BleState.UWB_CONFIG_CONFIRMED) {
                                uwbManager.startRanging(cfg)
                            } else {
                                Toast.makeText(this@MainActivity, "Exchange UWB parameters and confirm with ESP32 first", Toast.LENGTH_SHORT).show()
                            }
                        },
                        onStopRanging = { uwbManager.stopRanging() },
                        onResetUwb = { uwbManager.reset() }
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (isNfcReaderEnabled) {
            nfcReaderManager.startReader(this)
        }
    }

    override fun onPause() {
        super.onPause()
        if (isNfcReaderEnabled) {
            nfcReaderManager.stopReader(this)
        }
    }

    override fun onStop() {
        super.onStop()
        uwbManager.stopRanging()
    }

    override fun onDestroy() {
        super.onDestroy()
        bleManager.reset()
        uwbManager.cleanup()
    }

    private fun checkAndRequestPermissions() {
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.BLUETOOTH_SCAN)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.BLUETOOTH_CONNECT)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.UWB_RANGING) != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.UWB_RANGING)
            }
        }
        if (needed.isNotEmpty()) {
            permissionLauncher.launch(needed.toTypedArray())
        }
    }

    @SuppressLint("MissingPermission")
    private fun startBleScan() {
        val btManager = getSystemService(BluetoothManager::class.java)
        val adapter = btManager?.adapter
        if (adapter == null) {
            Toast.makeText(this, "Device does not support Bluetooth", Toast.LENGTH_SHORT).show()
            return
        }
        if (!adapter.isEnabled) {
            enableBtLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            return
        }
        bleManager.startScan(adapter)
    }

    private fun toggleNfcReader() {
        isNfcReaderEnabled = !isNfcReaderEnabled
        if (isNfcReaderEnabled) {
            val ok = nfcReaderManager.startReader(this)
            if (!ok) isNfcReaderEnabled = false
        } else {
            nfcReaderManager.stopReader(this)
        }
    }

    private fun loadMockNfcCredentials() {
        // Reader Mode disables card emulation. Stop it before provisioning the
        // explicit integration-test credentials so PN532 can select the HCE AID.
        if (isNfcReaderEnabled) {
            nfcReaderManager.stopReader(this)
            isNfcReaderEnabled = false
        }
        val mock = MockDataProvider.createMockMasterCard(useFirmwareSync = true)
        NfcHceBridge.updateProvisionedCard(mock.vehicleId, mock.masterSecretKey)
        bleManager.setMasterKey(mock.masterSecretKey)
        Toast.makeText(this, "Loaded mock VID & MSK matching ESP32 firmware", Toast.LENGTH_SHORT).show()
    }
}

@Composable
fun SmartKeyMainScreen(
    modifier: Modifier = Modifier,
    bleState: BleState,
    bleStatus: String,
    rttMs: Double?,
    mtu: Int,
    sessionId: Long?,
    uwbConfig: UwbConfig?,
    esp32Json: String?,
    phoneDelay: Int?,
    esp32Delay: Int?,
    rawTx: String?,
    nonceEvent: String?,
    nfcReaderEnabled: Boolean,
    nfcStatus: String,
    provisionedCard: MasterCardData?,
    hceEvent: String,
    hceVid: ByteArray?,
    bleCapabilityReport: BleCapabilityReport,
    uwbSessionState: UwbSessionState,
    uwbStatus: String,
    capabilityReport: UwbCapabilityReport,
    rawDistance: Float?,
    activeSessionLocalAddress: String?,
    onScanBle: () -> Unit,
    onResetBle: () -> Unit,
    onCheckBleCapabilities: () -> Unit,
    onSendPing: () -> Unit,
    onSendAuthReq: () -> Unit,
    onSendUwbConfigReq: () -> Unit,
    onToggleNfcReader: () -> Unit,
    onLoadMockNfc: () -> Unit,
    onCheckCapabilities: () -> Unit,
    onStartRanging: () -> Unit,
    onStopRanging: () -> Unit,
    onResetUwb: () -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // App header
        Column {
            Text(
                text = "Smart Key Access",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = "Anti-relay smart key system (BLE + NFC + UWB)",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // NFC master card and HCE section
        NfcCardSection(
            readerEnabled = nfcReaderEnabled,
            status = nfcStatus,
            provisionedCard = provisionedCard,
            hceEvent = hceEvent,
            hceVid = hceVid,
            onToggleReader = onToggleNfcReader,
            onLoadMock = onLoadMockNfc
        )

        // BLE central pipeline section
        BleCardSection(
            bleState = bleState,
            status = bleStatus,
            rttMs = rttMs,
            mtu = mtu,
            sessionId = sessionId,
            onScan = onScanBle,
            onReset = onResetBle,
            onSendPing = onSendPing,
            onSendAuthReq = onSendAuthReq
        )

        // BLE hardware and capability diagnostic section
        BleDiagnosticCardSection(
            report = bleCapabilityReport,
            onCheckBleCapabilities = onCheckBleCapabilities
        )

        // Telemetry and nonce section
        TelemetryCardSection(
            esp32Json = esp32Json,
            phoneDelay = phoneDelay,
            esp32Delay = esp32Delay,
            rawTx = rawTx,
            nonceEvent = nonceEvent
        )

        // Hardware capability diagnostic section
        UwbDiagnosticCardSection(
            report = capabilityReport,
            status = uwbStatus,
            sessionState = uwbSessionState,
            onCheckCapabilities = onCheckCapabilities
        )

        // UWB live ranging section
        UwbControleeRangingCardSection(
            bleState = bleState,
            sessionState = uwbSessionState,
            status = uwbStatus,
            uwbConfig = uwbConfig,
            rawDistance = rawDistance,
            activeLocalAddress = activeSessionLocalAddress,
            onStartRanging = onStartRanging,
            onStopRanging = onStopRanging,
            onSendUwbConfigReq = onSendUwbConfigReq,
            onResetUwb = onResetUwb
        )

        Spacer(modifier = Modifier.height(16.dp))
    }
}

// NFC card and HCE card
@Composable
private fun NfcCardSection(
    readerEnabled: Boolean,
    status: String,
    provisionedCard: MasterCardData?,
    hceEvent: String,
    hceVid: ByteArray?,
    onToggleReader: () -> Unit,
    onLoadMock: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "NFC Master Card & HCE",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onToggleReader) {
                    Text(if (readerEnabled) "Disable NFC Reader" else "Enable NFC Reader")
                }
                OutlinedButton(onClick = onLoadMock) {
                    Text("Load Mock Card")
                }
            }

            Text(
                text = "Reader status: $status",
                style = MaterialTheme.typography.bodySmall
            )

            if (provisionedCard != null) {
                Text(
                    text = "VID: ${CryptoManager.toHex(provisionedCard.vehicleId)} (8B)",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "MSK: Loaded / Valid (32B)",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            Text(
                text = "Host Card Emulation (PN532 <-> Phone):",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = hceEvent,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (hceVid != null) {
                Text(
                    text = "HCE VID: ${CryptoManager.toHex(hceVid)}",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

// BLE central pipeline card
@Composable
private fun BleCardSection(
    bleState: BleState,
    status: String,
    rttMs: Double?,
    mtu: Int,
    sessionId: Long?,
    onScan: () -> Unit,
    onReset: () -> Unit,
    onSendPing: () -> Unit,
    onSendAuthReq: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "BLE Central Pipeline",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                StateIndicatorBadge(bleState)
            }

            Text(
                text = status,
                style = MaterialTheme.typography.bodySmall
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onScan,
                    enabled = bleState == BleState.READY || bleState == BleState.ERROR
                ) {
                    Text("Connect ESP32")
                }
                OutlinedButton(onClick = onReset) {
                    Text("Reset")
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onSendPing,
                    enabled = bleState == BleState.DATA_READY || bleState == BleState.AUTHENTICATED || bleState == BleState.UWB_CONFIG_CONFIRMED
                ) {
                    Text("Send PING (RTT)")
                }
                Button(
                    onClick = onSendAuthReq,
                    enabled = bleState == BleState.DATA_READY
                ) {
                    Text("AUTH_REQ")
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "MTU: $mtu bytes",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    text = rttMs?.let { "RTT: %.2f ms".format(Locale.US, it) } ?: "RTT: --",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            if (sessionId != null) {
                Text(
                    text = "Session ID: 0x%08X".format(sessionId),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}

// BLE Hardware and capability diagnostic card
@Composable
private fun BleDiagnosticCardSection(
    report: BleCapabilityReport,
    onCheckBleCapabilities: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "BLE Capability & Diagnostics",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = if (report.readyToScan) "READY" else "DIAGNOSTIC",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = if (report.readyToScan) Color(0xFF2E7D32) else MaterialTheme.colorScheme.primary
                )
            }

            Text(
                text = report.diagnosisMessage,
                style = MaterialTheme.typography.bodySmall
            )

            Button(onClick = onCheckBleCapabilities) {
                Text("Check BLE Capabilities")
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
            Text(
                text = "• BLE Hardware Supported: " + if (report.hardwareSupported) "YES" else "NO",
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                text = "• Bluetooth Adapter Enabled: " + if (report.bluetoothEnabled) "YES" else "NO",
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                text = "• Scan Permission: " + if (report.scanPermissionGranted) "GRANTED" else "DENIED",
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                text = "• Connect Permission: " + if (report.connectPermissionGranted) "GRANTED" else "DENIED",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

// Telemetry and nonce card
@Composable
private fun TelemetryCardSection(
    esp32Json: String?,
    phoneDelay: Int?,
    esp32Delay: Int?,
    rawTx: String?,
    nonceEvent: String?
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Telemetry & Nonce (...0004)",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )

            if (nonceEvent != null) {
                Text(
                    text = nonceEvent,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium
                )
            } else {
                Text(
                    text = "Nonce characteristic (6E400004): Waiting for ESP32 notify...",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            if (esp32Json != null) {
                Text(
                    text = "JSON: $esp32Json",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Phone delay: ${phoneDelay ?: "--"} ms",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        text = "ESP32 delay: ${esp32Delay ?: "--"} ms",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            } else {
                Text(
                    text = "No JSON telemetry received yet from ESP32",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (rawTx != null && rawTx != esp32Json) {
                Text(
                    text = "Raw TX data: $rawTx",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}

// UWB Hardware & FiRa Compatibility Diagnostic Section
@Composable
private fun UwbDiagnosticCardSection(
    report: UwbCapabilityReport,
    status: String,
    sessionState: UwbSessionState,
    onCheckCapabilities: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "UWB Capability & Diagnostics",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = if (report.phoneUwbCapable) "PHONE READY" else if (sessionState == UwbSessionState.CHECKING) "CHECKING" else "DIAGNOSTIC",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = if (report.phoneUwbCapable) Color(0xFF2E7D32) else MaterialTheme.colorScheme.primary
                )
            }

            Text(
                text = status,
                style = MaterialTheme.typography.bodySmall
            )

            Button(
                onClick = onCheckCapabilities,
                enabled = sessionState != UwbSessionState.CHECKING && sessionState != UwbSessionState.RANGING_ACTIVE
            ) {
                Text(if (sessionState == UwbSessionState.CHECKING) "Diagnosing..." else "Check Capabilities")
            }

            if (report.androidVersionSupported) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                Text(
                    text = "Capability Diagnostic Results:",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "• Android API: ${report.androidApiLevel} (Target >= 31): OK",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    text = "• Hardware Feature (android.hardware.uwb): " + if (report.hardwareSupported) "DETECTED" else "NOT PRESENT",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    text = "• Permission UWB_RANGING: " + if (report.permissionGranted) "GRANTED" else "NOT GRANTED",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    text = "• Subsystem Active: " + if (report.serviceAvailable) "AVAILABLE" else "DISABLED / UNAVAILABLE",
                    style = MaterialTheme.typography.bodySmall
                )
                if (report.serviceAvailable) {
                    Text(
                        text = "• Supported Channels: ${report.supportedChannels} (FiRa Ch 5/9: ${if (report.firaChannelsSupported) "YES" else "NO"})",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        text = "• FiRa Unicast DS-TWR Config: " + if (report.firaDsTwrSupported) "SUPPORTED" else "NOT SUPPORTED",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        text = "• AoA Angles: Azimuth=${report.azimuthSupported ?: false}, Elevation=${report.elevationSupported ?: false}",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
            Text(
                text = report.firaComplianceNotice,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// UWB Controlee live ranging and distance decision policy card
@Composable
private fun UwbControleeRangingCardSection(
    bleState: BleState,
    sessionState: UwbSessionState,
    status: String,
    uwbConfig: UwbConfig?,
    rawDistance: Float?,
    activeLocalAddress: String?,
    onStartRanging: () -> Unit,
    onStopRanging: () -> Unit,
    onSendUwbConfigReq: () -> Unit,
    onResetUwb: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "UWB Ranging (Controlee)",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = sessionState.name,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = when (sessionState) {
                        UwbSessionState.RANGING_ACTIVE -> Color(0xFF1976D2)
                        UwbSessionState.COMPLETED -> Color(0xFF2E7D32)
                        UwbSessionState.TIMEOUT, UwbSessionState.PEER_DISCONNECTED -> Color(0xFFE65100)
                        UwbSessionState.ERROR, UwbSessionState.PERMISSION_REQUIRED, UwbSessionState.SERVICE_UNAVAILABLE -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.outline
                    }
                )
            }

            // Proximity & Single Access Authority Badge
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = rawDistance?.let { "ToF Distance: %.2f m".format(Locale.US, it) } ?: "ToF Distance: --",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (rawDistance != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )

                Text(
                    text = "AUTHORITY: ESP32",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF1976D2)
                )
            }

            Text(
                text = status,
                style = MaterialTheme.typography.bodySmall
            )

            // Session addresses
            if (activeLocalAddress != null) {
                Text(
                    text = "Ephemeral Local UWB Address: $activeLocalAddress",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            if (uwbConfig != null) {
                Text(
                    text = "ESP32 Controller: [${uwbConfig.peerAddressHex}] | Ch ${uwbConfig.channel} | Session 0x%08X".format(uwbConfig.sessionId),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace
                )
            } else {
                Text(
                    text = "Awaiting authenticated ESP32 UWB parameters from BLE (AUTH_REQ -> AUTH_RESP)...",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            // Action buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onStartRanging,
                    enabled = bleState == BleState.UWB_CONFIG_CONFIRMED && uwbConfig != null && sessionState != UwbSessionState.RANGING_ACTIVE
                ) {
                    Text("Start Ranging")
                }
                if (sessionState == UwbSessionState.RANGING_ACTIVE) {
                    OutlinedButton(onClick = onStopRanging) {
                        Text("Stop Ranging")
                    }
                } else {
                    OutlinedButton(onClick = onResetUwb) {
                        Text("Reset UWB")
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onSendUwbConfigReq,
                    enabled = bleState == BleState.AUTHENTICATED || bleState == BleState.UWB_CONFIG_CONFIRMED
                ) {
                    Text("Send UWB Config REQ (BLE)")
                }
            }
        }
    }
}

// BLE state indicator badge
@Composable
private fun StateIndicatorBadge(state: BleState) {
    val color = when (state) {
        BleState.UWB_CONFIG_CONFIRMED, BleState.AUTHENTICATED -> Color(0xFF2E7D32)
        BleState.DATA_READY, BleState.CONNECTED -> Color(0xFF1976D2)
        BleState.AUTHENTICATING, BleState.UWB_CONFIG_EXCHANGING, BleState.SCANNING, BleState.FOUND, BleState.CONNECTING, BleState.DISCOVERING, BleState.MTU_NEGOTIATING, BleState.SUBSCRIBING -> Color(0xFFE65100)
        BleState.ERROR -> Color(0xFFC62828)
        BleState.READY -> Color(0xFF757575)
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(color, CircleShape)
        )
        Text(
            text = state.name,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = color
        )
    }
}

@Preview(showBackground = true)
@Composable
fun SmartKeyMainScreenPreview() {
    SmartKeyAccessTheme {
        SmartKeyMainScreen(
            bleState = BleState.DATA_READY,
            bleStatus = "Connected! Ready to exchange data with ESP32.",
            rttMs = 18.5,
            mtu = 512,
            sessionId = 0x12345678L,
            uwbConfig = UwbConfig(
                peerAddress = byteArrayOf(0x00, 0x01),
                channel = 9,
                preambleIndex = 9,
                sessionId = 0x12345678
            ),
            esp32Json = "{\"phone\":200,\"esp32\":100}",
            phoneDelay = 200,
            esp32Delay = 100,
            rawTx = null,
            nonceEvent = "Nonce received (24B); response BLOCKED pending firmware HMAC/signing contract",
            nfcReaderEnabled = true,
            nfcStatus = "NFC Reader scanning...",
            provisionedCard = null,
            hceEvent = "NFC HCE: Ready for PN532...",
            hceVid = null,
            bleCapabilityReport = BleCapabilityReport(hardwareSupported = true, bluetoothEnabled = true),
            uwbSessionState = UwbSessionState.IDLE,
            uwbStatus = "UWB Controlee: Idle",
            capabilityReport = UwbCapabilityReport(phoneUwbCapable = true),
            rawDistance = 1.25f,
            activeSessionLocalAddress = "01:0A",
            onScanBle = {},
            onResetBle = {},
            onCheckBleCapabilities = {},
            onSendPing = {},
            onSendAuthReq = {},
            onSendUwbConfigReq = {},
            onToggleNfcReader = {},
            onLoadMockNfc = {},
            onCheckCapabilities = {},
            onStartRanging = {},
            onStopRanging = {},
            onResetUwb = {}
        )
    }
}
