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
import com.example.smartkeyaccess.ble.BleManager
import com.example.smartkeyaccess.ble.BleState
import com.example.smartkeyaccess.crypto.CryptoManager
import com.example.smartkeyaccess.mock.MockDataProvider
import com.example.smartkeyaccess.nfc.MasterCardData
import com.example.smartkeyaccess.nfc.NfcHceBridge
import com.example.smartkeyaccess.nfc.NfcReaderManager
import com.example.smartkeyaccess.ui.theme.SmartKeyAccessTheme
import com.example.smartkeyaccess.uwb.UwbManager
import com.example.smartkeyaccess.uwb.UwbState

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
            Toast.makeText(this, "Bluetooth permissions required to scan ESP32", Toast.LENGTH_SHORT).show()
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
            onCardProvisioned = { isNfcReaderEnabled = false }
        )
        uwbManager = UwbManager()

        checkAndRequestPermissions()

        setContent {
            SmartKeyAccessTheme {
                val bleState by bleManager.bleState.collectAsState()
                val bleStatus by bleManager.status.collectAsState()
                val rttMs by bleManager.rttMs.collectAsState()
                val mtu by bleManager.negotiatedMtu.collectAsState()
                val sessionId by bleManager.sessionId.collectAsState()

                val esp32Json by bleManager.esp32JsonPayload.collectAsState()
                val phoneDelay by bleManager.phoneDelayMs.collectAsState()
                val esp32Delay by bleManager.esp32DelayMs.collectAsState()
                val rawTx by bleManager.rawTxPayload.collectAsState()
                val nonceEvent by bleManager.nonceEvent.collectAsState()

                val nfcStatus by nfcReaderManager.status.collectAsState()
                val provisionedCard by nfcReaderManager.provisionedCard.collectAsState()
                val hceEvent by NfcHceBridge.eventFlow.collectAsState()
                val hceVid by NfcHceBridge.provisionedVehicleId.collectAsState()

                val uwbState by uwbManager.uwbState.collectAsState()
                val uwbStatus by uwbManager.status.collectAsState()

                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    SmartKeyMainScreen(
                        modifier = Modifier.padding(innerPadding),
                        bleState = bleState,
                        bleStatus = bleStatus,
                        rttMs = rttMs,
                        mtu = mtu,
                        sessionId = sessionId,
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
                        uwbState = uwbState,
                        uwbStatus = uwbStatus,
                        onScanBle = { startBleScan() },
                        onResetBle = { bleManager.reset() },
                        onSendPing = { bleManager.sendPing() },
                        onSendAuthReq = { bleManager.sendAuthRequest() },
                        onToggleNfcReader = { toggleNfcReader() },
                        onLoadMockNfc = { loadMockNfcCredentials() },
                        onTestUwb = { uwbManager.startIntegrationTest() },
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

    override fun onDestroy() {
        super.onDestroy()
        bleManager.reset()
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
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.NFC) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.NFC)
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
    uwbState: UwbState,
    uwbStatus: String,
    onScanBle: () -> Unit,
    onResetBle: () -> Unit,
    onSendPing: () -> Unit,
    onSendAuthReq: () -> Unit,
    onToggleNfcReader: () -> Unit,
    onLoadMockNfc: () -> Unit,
    onTestUwb: () -> Unit,
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

        // Telemetry and nonce section
        TelemetryCardSection(
            esp32Json = esp32Json,
            phoneDelay = phoneDelay,
            esp32Delay = esp32Delay,
            rawTx = rawTx,
            nonceEvent = nonceEvent
        )

        // Manual UWB integration trigger. No distance is displayed until a real
        // Android UWB session and firmware exchange provide a measured result.
        UwbCardSection(
            uwbState = uwbState,
            status = uwbStatus,
            onTestUwb = onTestUwb,
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
                    Text("Scan ESP32")
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
                    enabled = bleState == BleState.DATA_READY
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
                    text = rttMs?.let { "RTT: %.2f ms".format(it) } ?: "RTT: --",
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

// UWB integration-test trigger. This card must not claim ranging or distance
// until the Android UWB session and firmware POLL/RESPONSE/FINAL contract exist.
@Composable
private fun UwbCardSection(
    uwbState: UwbState,
    status: String,
    onTestUwb: () -> Unit,
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
                    text = "UWB Integration Test",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = uwbState.name,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = when (uwbState) {
                        UwbState.ERROR -> MaterialTheme.colorScheme.error
                        UwbState.TEST_REQUESTED -> Color(0xFFE65100)
                        UwbState.IDLE -> MaterialTheme.colorScheme.outline
                    }
                )
            }

            Text(
                text = status,
                style = MaterialTheme.typography.bodySmall
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onTestUwb,
                    enabled = uwbState != UwbState.TEST_REQUESTED
                ) {
                    Text("Start UWB Test")
                }
                OutlinedButton(onClick = onResetUwb) {
                    Text("Reset UWB")
                }
            }

            Text(
                text = "No distance is produced by the current Mobile implementation.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// BLE state indicator badge
@Composable
private fun StateIndicatorBadge(state: BleState) {
    val color = when (state) {
        BleState.DATA_READY, BleState.CONNECTED -> Color(0xFF2E7D32)
        BleState.SCANNING, BleState.FOUND, BleState.CONNECTING, BleState.DISCOVERING, BleState.MTU_NEGOTIATING, BleState.SUBSCRIBING -> Color(0xFFE65100)
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
            uwbState = UwbState.TEST_REQUESTED,
            uwbStatus = "UWB test requested: waiting for firmware/UWB session contract",
            onScanBle = {},
            onResetBle = {},
            onSendPing = {},
            onSendAuthReq = {},
            onToggleNfcReader = {},
            onLoadMockNfc = {},
            onTestUwb = {},
            onResetUwb = {}
        )
    }
}
