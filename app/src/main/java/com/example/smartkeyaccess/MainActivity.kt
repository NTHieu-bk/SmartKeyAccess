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
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.example.smartkeyaccess.uwb.UwbTimestampDebug
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
                val timestampDebug by uwbManager.timestampDebug.collectAsState()
                val startUwbTrigger by bleManager.startUwbTrigger.collectAsState()

                // Trigger UWB ranging automatically when ESP32 sends START UWB via BLE
                LaunchedEffect(startUwbTrigger) {
                    if (startUwbTrigger > 0L) {
                        val cfg = esp32UwbConfig
                        if (cfg != null && bleState == BleState.UWB_CONFIG_CONFIRMED) {
                            uwbManager.startRanging(cfg)
                        }
                    }
                }

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
                        timestampDebug = timestampDebug,
                        onScanBle = { startBleScan() },
                        onResetBle = { bleManager.reset() },
                        onCheckBleCapabilities = { bleManager.checkCapabilities() },
                        onSendPing = { bleManager.sendPing() },
                        onSendAuthReq = { bleManager.sendAuthRequest() },
                        onSendUwbConfigReq = {
                            lifecycleScope.launch {
                                val phoneAddr = uwbManager.getLocalAddressBytes()
                                if (phoneAddr == null || phoneAddr.size != 2) {
                                    Toast.makeText(
                                        this@MainActivity,
                                        "Phone UWB address not ready. Ensure UWB is available on this device.",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                    return@launch
                                }
                                bleManager.sendUwbConfigFromPhone(phoneUwbAddress = phoneAddr)
                            }
                        },
                        onToggleNfcReader = { toggleNfcReader() },
                        onLoadMockNfc = { loadMockNfcCredentials() },
                        onCheckCapabilities = { uwbManager.checkCapabilities() },
                        onArmUwb = {
                            val cfg = esp32UwbConfig
                            if (cfg != null && bleState == BleState.UWB_CONFIG_CONFIRMED) {
                                uwbManager.armForStart()
                            } else {
                                Toast.makeText(this@MainActivity, "Exchange UWB parameters and confirm with ESP32 first", Toast.LENGTH_SHORT).show()
                            }
                        },
                        onStartRanging = {
                            val cfg = esp32UwbConfig
                            if (cfg != null && bleState == BleState.UWB_CONFIG_CONFIRMED) {
                                uwbManager.startRanging(cfg)
                            } else {
                                Toast.makeText(this@MainActivity, "Exchange UWB parameters and confirm with ESP32 first", Toast.LENGTH_SHORT).show()
                            }
                        },
                        onStopRanging = { uwbManager.stopRanging() },
                        onAcceptUwbConfig = { bleManager.simulateUwbConfigAcceptedForTesting() },
                        onResetUwb = { uwbManager.reset() },
                        onMockUwbRanging = { uwbManager.mockRangingResult(1.25f) }
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
    timestampDebug: UwbTimestampDebug = UwbTimestampDebug(),
    onScanBle: () -> Unit,
    onResetBle: () -> Unit,
    onCheckBleCapabilities: () -> Unit,
    onSendPing: () -> Unit,
    onSendAuthReq: () -> Unit,
    onSendUwbConfigReq: () -> Unit,
    onToggleNfcReader: () -> Unit,
    onLoadMockNfc: () -> Unit,
    onCheckCapabilities: () -> Unit,
    onArmUwb: () -> Unit,
    onStartRanging: () -> Unit,
    onStopRanging: () -> Unit,
    onAcceptUwbConfig: () -> Unit = {},
    onResetUwb: () -> Unit,
    onMockUwbRanging: () -> Unit = {}
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
            esp32Json = esp32Json,
            phoneDelay = phoneDelay,
            esp32Delay = esp32Delay,
            rawTx = rawTx,
            nonceEvent = nonceEvent,
            report = bleCapabilityReport,
            onScan = onScanBle,
            onReset = onResetBle,
            onSendPing = onSendPing,
            onSendAuthReq = onSendAuthReq,
            onCheckBleCapabilities = onCheckBleCapabilities
        )

        // Hardware capability diagnostic section
        UwbDiagnosticCardSection(
            report = capabilityReport,
            status = uwbStatus,
            sessionState = uwbSessionState,
            onCheckCapabilities = onCheckCapabilities
        )

        // UWB live ranging section
        UwbRangingCardSection(
            bleState = bleState,
            sessionState = uwbSessionState,
            status = uwbStatus,
            uwbConfig = uwbConfig,
            rawDistance = rawDistance,
            activeLocalAddress = activeSessionLocalAddress,
            timestampDebug = timestampDebug,
            onArmUwb = onArmUwb,
            onStartRanging = onStartRanging,
            onStopRanging = onStopRanging,
            onSendUwbConfigReq = onSendUwbConfigReq,
            onAcceptUwbConfig = onAcceptUwbConfig,
            onResetUwb = onResetUwb,
            onMockUwbRanging = onMockUwbRanging
        )

        Spacer(modifier = Modifier.height(16.dp))
    }
}

// NFC master card and HCE state-driven section
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
    var showDiagnostics by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Header: Title with NFC icon and state badge
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "💳",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "NFC Master Card & HCE",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                Text(
                    text = when {
                        provisionedCard != null -> "PROVISIONED"
                        readerEnabled -> "SCANNING"
                        else -> "IDLE"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = when {
                        provisionedCard != null -> Color(0xFF2E7D32)
                        readerEnabled -> Color(0xFF1976D2)
                        else -> MaterialTheme.colorScheme.outline
                    }
                )
            }

            // Central State-driven Visual Area
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                when {
                    provisionedCard != null -> {
                        Text(
                            text = "✓",
                            style = MaterialTheme.typography.headlineLarge,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF2E7D32)
                        )
                        Text(
                            text = "Master Card Enrolled",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF2E7D32)
                        )
                        Text(
                            text = "Ready to emulate card (HCE) to PN532 on vehicle.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = CryptoManager.toHex(provisionedCard.vehicleId),
                            style = MaterialTheme.typography.headlineSmall,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF2E7D32)
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Vehicle ID verified (8B) • MSK Loaded (32B)",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                    readerEnabled -> {
                        CircularProgressIndicator(
                            modifier = Modifier.size(36.dp),
                            strokeWidth = 3.dp,
                            color = Color(0xFF1976D2)
                        )
                        Text(
                            text = "NFC Reader Scanning...",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF1976D2)
                        )
                        Text(
                            text = "Hold vehicle master card / tag near phone antenna.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Waiting for Tag...",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF1976D2)
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Listening for ISO 14443 Type-A / MIFARE frames",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                    else -> {
                        Text(
                            text = "💳",
                            style = MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.outline
                        )
                        Text(
                            text = "No Card Provisioned",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Enable reader to scan card or load mock credentials.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "— VID",
                            style = MaterialTheme.typography.displayMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.outline
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Action: Enable NFC reader or tap 'Load Mock Card'",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                }
            }

            // Dedicated Telemetry Debug Surface
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "NFC & HCE Telemetry Debug:",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = when {
                                provisionedCard != null -> Color(0xFF2E7D32).copy(alpha = 0.15f)
                                readerEnabled -> Color(0xFF1976D2).copy(alpha = 0.15f)
                                else -> MaterialTheme.colorScheme.surfaceVariant
                            }
                        ) {
                            Text(
                                text = when {
                                    provisionedCard != null -> "HCE Armed"
                                    readerEnabled -> "Reader Active"
                                    else -> "Standby"
                                },
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = when {
                                    provisionedCard != null -> Color(0xFF2E7D32)
                                    readerEnabled -> Color(0xFF1976D2)
                                    else -> MaterialTheme.colorScheme.outline
                                }
                            )
                        }
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp))

                    // Reader Status row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Reader Status:",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = status,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium
                        )
                    }

                    // Vehicle ID (VID) row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Vehicle ID (VID):",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = provisionedCard?.let { "${CryptoManager.toHex(it.vehicleId)} (8B)" } ?: "—",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = if (provisionedCard != null) FontWeight.Bold else FontWeight.Normal,
                            color = if (provisionedCard != null) Color(0xFF2E7D32) else MaterialTheme.colorScheme.onSurface
                        )
                    }

                    // Master Secret (MSK) row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Master Secret (MSK):",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = if (provisionedCard != null) "Loaded / Valid (32B AES-GCM)" else "—",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = if (provisionedCard != null) Color(0xFF2E7D32) else MaterialTheme.colorScheme.onSurface
                        )
                    }

                    // Host Card Emulation (HCE) event row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "HCE APDU Status:",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = hceEvent,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium
                        )
                    }

                    // Emulated HCE VID if present
                    if (hceVid != null) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "Active HCE VID:",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = CryptoManager.toHex(hceVid),
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }

                    Text(
                        text = "Used to debug vehicle master credential provisioning and HCE APDU dispatch.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }

            // Action Buttons: Enable/Disable Reader and Load Mock Card
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onToggleReader,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(if (readerEnabled) "Disable NFC Reader" else "Enable NFC Reader")
                }

                OutlinedButton(
                    onClick = onLoadMock,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Load Mock Card")
                }
            }

            // Collapsible Developer Diagnostics Section
            TextButton(
                onClick = { showDiagnostics = !showDiagnostics },
                modifier = Modifier.align(Alignment.CenterHorizontally)
            ) {
                Text(
                    text = if (showDiagnostics) "Hide Developer Diagnostics ▲" else "Show Developer Diagnostics ▼",
                    style = MaterialTheme.typography.labelMedium
                )
            }

            AnimatedVisibility(visible = showDiagnostics) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                    Text(
                        text = "HCE AID: F00102030405 (SmartKey Application)",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "Protocol: ISO/IEC 7816-4 APDU over ISO 14443-A (106 kbps)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "Hardware Target: PN532 NFC Controller on ESP32 Car Hub",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "Feature Testing: Tap 'Load Mock Card' to inject simulated 8-byte VID and 32-byte MSK without physical NFC tags.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }
        }
    }
}

// BLE central pipeline state-driven section
@Composable
private fun BleCardSection(
    bleState: BleState,
    status: String,
    rttMs: Double?,
    mtu: Int,
    sessionId: Long?,
    esp32Json: String?,
    phoneDelay: Int?,
    esp32Delay: Int?,
    rawTx: String?,
    nonceEvent: String?,
    report: BleCapabilityReport,
    onScan: () -> Unit,
    onReset: () -> Unit,
    onSendPing: () -> Unit,
    onSendAuthReq: () -> Unit,
    onCheckBleCapabilities: () -> Unit
) {
    var showDiagnostics by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Header: Title with Bluetooth symbol and state badge
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "⚡",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "BLE Central Pipeline",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                StateIndicatorBadge(bleState)
            }

            // Central State-driven Visual Area
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                when {
                    bleState == BleState.AUTHENTICATED || bleState == BleState.UWB_CONFIG_CONFIRMED -> {
                        Text(
                            text = "✓",
                            style = MaterialTheme.typography.headlineLarge,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF2E7D32)
                        )
                        Text(
                            text = "ESP32 Authenticated & Paired",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF2E7D32)
                        )
                        Text(
                            text = "Encrypted GATT link active. Ready for UWB ranging session.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = rttMs?.let { "%.2f ms".format(Locale.US, it) } ?: "— ms",
                            style = MaterialTheme.typography.displayMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF2E7D32)
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = sessionId?.let { "Session: 0x%08X • MTU: %d bytes".format(it, mtu) } ?: "Session active • MTU: $mtu bytes",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                    bleState == BleState.DATA_READY || bleState == BleState.CONNECTED -> {
                        Text(
                            text = "⚡",
                            style = MaterialTheme.typography.headlineLarge,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF1976D2)
                        )
                        Text(
                            text = "Connected & Services Discovered",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF1976D2)
                        )
                        Text(
                            text = "GATT pipeline ready. Tap AUTH_REQ to verify session key.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = rttMs?.let { "%.2f ms".format(Locale.US, it) } ?: "— ms",
                            style = MaterialTheme.typography.displayMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF1976D2)
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "MTU: $mtu bytes • Next action: Perform Authentication",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                    bleState in listOf(
                        BleState.SCANNING,
                        BleState.FOUND,
                        BleState.CONNECTING,
                        BleState.DISCOVERING,
                        BleState.MTU_NEGOTIATING,
                        BleState.SUBSCRIBING,
                        BleState.AUTHENTICATING,
                        BleState.UWB_CONFIG_EXCHANGING
                    ) -> {
                        CircularProgressIndicator(
                            modifier = Modifier.size(36.dp),
                            strokeWidth = 3.dp,
                            color = Color(0xFFE65100)
                        )
                        Text(
                            text = when (bleState) {
                                BleState.SCANNING, BleState.FOUND -> "Scanning for ESP32..."
                                BleState.CONNECTING -> "Connecting to ESP32 GATT..."
                                BleState.AUTHENTICATING -> "Authenticating Handshake..."
                                BleState.UWB_CONFIG_EXCHANGING -> "Exchanging UWB Config..."
                                else -> "Negotiating BLE Pipeline..."
                            },
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFE65100)
                        )
                        Text(
                            text = status,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "— ms",
                            style = MaterialTheme.typography.displayMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.outline
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Searching BLE advertisements on 2.4 GHz...",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                    bleState == BleState.ERROR -> {
                        Text(
                            text = "⚠",
                            style = MaterialTheme.typography.headlineLarge,
                            color = MaterialTheme.colorScheme.error
                        )
                        Text(
                            text = "BLE Connection Failed",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.error
                        )
                        Text(
                            text = status,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "— ms",
                            style = MaterialTheme.typography.displayMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.outline
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Action: Verify ESP32 is powered and tap 'Connect ESP32'",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                    else -> {
                        Text(
                            text = "⚡",
                            style = MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.outline
                        )
                        Text(
                            text = "BLE Pipeline Standby",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Ready to discover and connect with ESP32-SmartKey.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "— ms",
                            style = MaterialTheme.typography.displayMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.outline
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Action: Tap 'Connect ESP32' to begin BLE GATT discovery",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                }
            }

            // Dedicated Telemetry Debug Surface
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "GATT Pipeline Telemetry & RTT Debug:",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = when (bleState) {
                                BleState.AUTHENTICATED, BleState.UWB_CONFIG_CONFIRMED -> Color(0xFF2E7D32).copy(alpha = 0.15f)
                                BleState.DATA_READY, BleState.CONNECTED -> Color(0xFF1976D2).copy(alpha = 0.15f)
                                BleState.SCANNING, BleState.CONNECTING, BleState.AUTHENTICATING -> Color(0xFFE65100).copy(alpha = 0.15f)
                                else -> MaterialTheme.colorScheme.surfaceVariant
                            }
                        ) {
                            Text(
                                text = when (bleState) {
                                    BleState.AUTHENTICATED, BleState.UWB_CONFIG_CONFIRMED -> "Authenticated"
                                    BleState.DATA_READY, BleState.CONNECTED -> "Data Ready"
                                    BleState.SCANNING, BleState.CONNECTING, BleState.AUTHENTICATING -> "Connecting"
                                    else -> "Standby"
                                },
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = when (bleState) {
                                    BleState.AUTHENTICATED, BleState.UWB_CONFIG_CONFIRMED -> Color(0xFF2E7D32)
                                    BleState.DATA_READY, BleState.CONNECTED -> Color(0xFF1976D2)
                                    BleState.SCANNING, BleState.CONNECTING, BleState.AUTHENTICATING -> Color(0xFFE65100)
                                    else -> MaterialTheme.colorScheme.outline
                                }
                            )
                        }
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp))

                    // RTT row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "GATT Round-Trip Latency (RTT):",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = rttMs?.let { "%.2f ms".format(Locale.US, it) } ?: "—",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = if (rttMs != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                        )
                    }

                    // MTU row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Negotiated MTU Size:",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "$mtu bytes",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace
                        )
                    }

                    // Session ID row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Session ID:",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = sessionId?.let { "0x%08X".format(it) } ?: "—",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace
                        )
                    }

                    // Internal Delays row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Processing Delays:",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "Phone: ${phoneDelay?.let { "${it} ms" } ?: "—"} | ESP32: ${esp32Delay?.let { "${it} ms" } ?: "—"}",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace
                        )
                    }

                    // Nonce row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Nonce (...0004):",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = if (nonceEvent != null) "Received" else "Waiting",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium,
                            color = if (nonceEvent != null) Color(0xFF2E7D32) else MaterialTheme.colorScheme.outline
                        )
                    }

                    Text(
                        text = "Used to debug GATT round-trip latency, MTU payload sizing, and challenge nonce.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }

            // Connection Action Buttons: Connect ESP32 and Reset BLE
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onScan,
                    modifier = Modifier.weight(1f),
                    enabled = bleState == BleState.READY || bleState == BleState.ERROR
                ) {
                    Text(if (bleState == BleState.SCANNING || bleState == BleState.CONNECTING) "Connecting..." else "Connect ESP32")
                }

                OutlinedButton(
                    onClick = onReset,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Reset BLE")
                }
            }

            // Feature Testing Action Buttons: Send PING (RTT) and AUTH_REQ
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onSendPing,
                    modifier = Modifier.weight(1f),
                    enabled = bleState == BleState.DATA_READY || bleState == BleState.AUTHENTICATED || bleState == BleState.UWB_CONFIG_CONFIRMED
                ) {
                    Text("Send PING (RTT)")
                }

                Button(
                    onClick = onSendAuthReq,
                    modifier = Modifier.weight(1f),
                    enabled = bleState == BleState.DATA_READY
                ) {
                    Text("AUTH_REQ")
                }
            }

            // Collapsible Developer Diagnostics Section
            TextButton(
                onClick = { showDiagnostics = !showDiagnostics },
                modifier = Modifier.align(Alignment.CenterHorizontally)
            ) {
                Text(
                    text = if (showDiagnostics) "Hide Developer Diagnostics ▲" else "Show Developer Diagnostics ▼",
                    style = MaterialTheme.typography.labelMedium
                )
            }

            AnimatedVisibility(visible = showDiagnostics) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                    Text(
                        text = "Hardware Readiness & Permissions:",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold
                    )
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

                    Button(
                        onClick = onCheckBleCapabilities,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Check BLE Capabilities")
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                    Text(
                        text = "Telemetry & Raw Payloads:",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold
                    )

                    if (nonceEvent != null) {
                        Text(
                            text = "Nonce Event: $nonceEvent",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontFamily = FontFamily.Monospace
                        )
                    }

                    if (esp32Json != null) {
                        Text(
                            text = "ESP32 JSON: $esp32Json",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace
                        )
                    }

                    if (rawTx != null && rawTx != esp32Json) {
                        Text(
                            text = "Raw TX data: $rawTx",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace
                        )
                    }

                    if (esp32Json == null && rawTx == null && nonceEvent == null) {
                        Text(
                            text = "No telemetry frames or nonce received from ESP32 yet.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
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

// UWB Controller live ranging and state-driven smart card
@Composable
private fun UwbRangingCardSection(
    bleState: BleState,
    sessionState: UwbSessionState,
    status: String,
    uwbConfig: UwbConfig?,
    rawDistance: Float?,
    activeLocalAddress: String?,
    timestampDebug: UwbTimestampDebug = UwbTimestampDebug(),
    onArmUwb: () -> Unit,
    onStartRanging: () -> Unit,
    onStopRanging: () -> Unit,
    onSendUwbConfigReq: () -> Unit,
    onAcceptUwbConfig: () -> Unit = {},
    onResetUwb: () -> Unit,
    onMockUwbRanging: () -> Unit = {}
) {
    var showDiagnostics by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Header: Title with radar icon & session state badge
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "((•))",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "UWB Ranging",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                Text(
                    text = sessionState.name,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = when (sessionState) {
                        UwbSessionState.RANGING_ACTIVE -> Color(0xFF1976D2)
                        UwbSessionState.WAITING_FOR_START -> Color(0xFFE65100)
                        UwbSessionState.COMPLETED -> Color(0xFF2E7D32)
                        UwbSessionState.TIMEOUT, UwbSessionState.PEER_DISCONNECTED -> Color(0xFFE65100)
                        UwbSessionState.ERROR, UwbSessionState.PERMISSION_REQUIRED, UwbSessionState.SERVICE_UNAVAILABLE -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.outline
                    }
                )
            }

            // Central State-driven Visual Area
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                when (sessionState) {
                    UwbSessionState.IDLE -> {
                        Text(
                            text = "((•))",
                            style = MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.outline
                        )
                        Text(
                            text = "No Ranging Result",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Activate UWB to prepare ranging session.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "— m",
                            style = MaterialTheme.typography.displayMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.outline
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Action: Active UWB once BLE configuration is ready",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                    UwbSessionState.WAITING_FOR_START -> {
                        CircularProgressIndicator(
                            modifier = Modifier.size(36.dp),
                            strokeWidth = 3.dp,
                            color = Color(0xFFE65100)
                        )
                        Text(
                            text = "Waiting for START UWB Signal",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFE65100)
                        )
                        Text(
                            text = "Phone is armed. Waiting for ESP32 trigger via BLE...",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "— m",
                            style = MaterialTheme.typography.displayMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                    UwbSessionState.CHECKING, UwbSessionState.CONFIGURED -> {
                        CircularProgressIndicator(
                            modifier = Modifier.size(36.dp),
                            strokeWidth = 3.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "Initializing UWB Session...",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = status,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "— m",
                            style = MaterialTheme.typography.displayMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                    UwbSessionState.RANGING_ACTIVE -> {
                        CircularProgressIndicator(
                            modifier = Modifier.size(36.dp),
                            strokeWidth = 3.dp,
                            color = Color(0xFF1976D2)
                        )
                        Text(
                            text = "Measuring Distance (UWB)...",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF1976D2)
                        )
                        Text(
                            text = "Exchanging FiRa radio pulses with ESP32...",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = rawDistance?.let { "%.2f m".format(Locale.US, it) } ?: "— m",
                            style = MaterialTheme.typography.displayMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF1976D2)
                        )
                    }
                    UwbSessionState.COMPLETED -> {
                        Text(
                            text = "✓",
                            style = MaterialTheme.typography.headlineLarge,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF2E7D32)
                        )
                        Text(
                            text = "Ranging Completed Successfully",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF2E7D32)
                        )
                        Text(
                            text = "Access Authority: ESP32 (Vehicle Unlock Decision)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = rawDistance?.let { "%.2f m".format(Locale.US, it) } ?: "— m",
                            style = MaterialTheme.typography.displayMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF2E7D32)
                        )
                    }
                    else -> {
                        Text(
                            text = "⚠",
                            style = MaterialTheme.typography.headlineLarge,
                            color = MaterialTheme.colorScheme.error
                        )
                        Text(
                            text = "UWB Session Incomplete",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.error
                        )
                        Text(
                            text = status,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "— m",
                            style = MaterialTheme.typography.displayMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                }
            }

            // Dedicated Timestamp Difference Area (t1 -> t4 Debug)
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Timestamp Difference (t1 → t4 Debug):",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = when (sessionState) {
                                UwbSessionState.COMPLETED -> Color(0xFF2E7D32).copy(alpha = 0.15f)
                                UwbSessionState.RANGING_ACTIVE -> Color(0xFF1976D2).copy(alpha = 0.15f)
                                UwbSessionState.WAITING_FOR_START -> Color(0xFFE65100).copy(alpha = 0.15f)
                                else -> MaterialTheme.colorScheme.surfaceVariant
                            }
                        ) {
                            Text(
                                text = when (sessionState) {
                                    UwbSessionState.COMPLETED -> "Synchronized"
                                    UwbSessionState.RANGING_ACTIVE -> "Ranging"
                                    UwbSessionState.WAITING_FOR_START -> "Waiting START"
                                    else -> "Idle"
                                },
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = when (sessionState) {
                                    UwbSessionState.COMPLETED -> Color(0xFF2E7D32)
                                    UwbSessionState.RANGING_ACTIVE -> Color(0xFF1976D2)
                                    UwbSessionState.WAITING_FOR_START -> Color(0xFFE65100)
                                    else -> MaterialTheme.colorScheme.outline
                                }
                            )
                        }
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp))

                    val (t1Display, t4Display, deltaDisplay) = when (sessionState) {
                        UwbSessionState.IDLE -> Triple("N/A", "N/A", "N/A")
                        UwbSessionState.WAITING_FOR_START -> Triple("Waiting for POLL...", "Waiting for RESP...", "N/A")
                        else -> Triple(
                            if (timestampDebug.t1PollTx.isNotBlank()) timestampDebug.t1PollTx else "N/A",
                            if (timestampDebug.t4ResponseRx.isNotBlank()) timestampDebug.t4ResponseRx else "N/A",
                            if (timestampDebug.deltaT4T1.isNotBlank()) timestampDebug.deltaT4T1 else "N/A"
                        )
                    }

                    // t1 row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "t1 (POLL TX - Phone):",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = t1Display,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Medium
                        )
                    }

                    // t4 row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "t4 (RESPONSE RX - ESP32):",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = t4Display,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Medium
                        )
                    }

                    // Delta row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Difference Δ (t4 − t1):",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = deltaDisplay,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = if (sessionState == UwbSessionState.COMPLETED) Color(0xFF2E7D32) else MaterialTheme.colorScheme.primary
                        )
                    }

                    Text(
                        text = "Used to debug and verify timestamp telemetry exchange with ESP32.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }

            // Action Buttons: Active UWB and Reset UWB
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onArmUwb,
                    modifier = Modifier.weight(1f),
                    enabled = bleState == BleState.UWB_CONFIG_CONFIRMED && uwbConfig != null && sessionState != UwbSessionState.RANGING_ACTIVE && sessionState != UwbSessionState.WAITING_FOR_START
                ) {
                    Text(if (sessionState == UwbSessionState.COMPLETED) "Re-arm (Active UWB)" else "Active UWB")
                }

                if (sessionState == UwbSessionState.RANGING_ACTIVE || sessionState == UwbSessionState.WAITING_FOR_START) {
                    OutlinedButton(
                        onClick = onStopRanging,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Stop Ranging")
                    }
                } else {
                    OutlinedButton(
                        onClick = onResetUwb,
                        modifier = Modifier.weight(1f),
                        enabled = sessionState != UwbSessionState.IDLE
                    ) {
                        Text("Reset UWB")
                    }
                }
            }

            // Collapsible Developer Diagnostics Section
            TextButton(
                onClick = { showDiagnostics = !showDiagnostics },
                modifier = Modifier.align(Alignment.CenterHorizontally)
            ) {
                Text(
                    text = if (showDiagnostics) "Hide Developer Diagnostics ▲" else "Show Developer Diagnostics ▼",
                    style = MaterialTheme.typography.labelMedium
                )
            }

            AnimatedVisibility(visible = showDiagnostics) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                    Text(
                        text = "Phone Role: Controller (Initiator - transmits POLL)",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "ESP32 Role: Controlee (Responder - replies with RESP)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "Access Decision: ESP32 Access Authority (Vehicle Unlock)",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFF2E7D32)
                    )

                    if (activeLocalAddress != null) {
                        Text(
                            text = "Phone Local UWB Address: $activeLocalAddress",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }

                    if (uwbConfig != null) {
                        Text(
                            text = "ESP32 Peer: [${uwbConfig.peerAddressHex}] | Ch ${uwbConfig.channel} | Session 0x%08X".format(uwbConfig.sessionId),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace
                        )
                    } else {
                        Text(
                            text = "No UWB parameters received from BLE yet.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    // Diagnostic & testing buttons
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = onStartRanging,
                            modifier = Modifier.weight(1f),
                            enabled = bleState == BleState.UWB_CONFIG_CONFIRMED && uwbConfig != null && sessionState != UwbSessionState.RANGING_ACTIVE
                        ) {
                            Text("Manual Range")
                        }

                        OutlinedButton(
                            onClick = onSendUwbConfigReq,
                            modifier = Modifier.weight(1f),
                            enabled = bleState == BleState.AUTHENTICATED || bleState == BleState.UWB_CONFIG_CONFIRMED || bleState == BleState.UWB_CONFIG_EXCHANGING
                        ) {
                            Text("Config REQ")
                        }
                    }

                    if (bleState == BleState.UWB_CONFIG_EXCHANGING) {
                        OutlinedButton(
                            onClick = onAcceptUwbConfig,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("MOCK: Simulate ESP32 ACK (Manual Test Only)")
                        }
                    }

                    OutlinedButton(
                        onClick = onMockUwbRanging,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Simulate UWB Ranging (Mock Demo t1 → t4)")
                    }
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
            uwbStatus = "UWB Controller: Idle",
            capabilityReport = UwbCapabilityReport(phoneUwbCapable = true),
            rawDistance = null,
            activeSessionLocalAddress = null,
            timestampDebug = UwbTimestampDebug(),
            onScanBle = {},
            onResetBle = {},
            onCheckBleCapabilities = {},
            onSendPing = {},
            onSendAuthReq = {},
            onSendUwbConfigReq = {},
            onToggleNfcReader = {},
            onLoadMockNfc = {},
            onCheckCapabilities = {},
            onArmUwb = {},
            onStartRanging = {},
            onStopRanging = {},
            onResetUwb = {},
            onMockUwbRanging = {}
        )
    }
}
