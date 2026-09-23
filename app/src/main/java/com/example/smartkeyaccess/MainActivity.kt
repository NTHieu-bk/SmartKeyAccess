package com.example.smartkeyaccess

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.smartkeyaccess.ui.theme.SmartKeyAccessTheme
import java.util.UUID
import androidx.compose.ui.Alignment

class MainActivity : ComponentActivity() {
    private val serviceUuid = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
    private val handler = Handler(Looper.getMainLooper())
    private var scanner: BluetoothLeScanner? = null
    private var scanning by mutableStateOf(false)
    private var status by mutableStateOf("Ready to find ESP32")

    private val scanTimeout = Runnable {
        if (scanning) {
            stopScan()
            status = "ESP32 advertising service UUID not found in 10s"
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val hasService = result.scanRecord?.serviceUuids?.any { it.uuid == serviceUuid } == true
            if (hasService) {
                runOnUiThread {
                    if (scanning) {
                        stopScan()
                        status = "Smart Key service advertising detected; not connected"
                    }
                }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            runOnUiThread {
                if (!scanning) return@runOnUiThread
                stopScan()
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
            status = "Nearby devices permission required to find ESP32"
        }
    }

    private val enableBluetoothLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            startFindingEsp32()
        } else {
            status = "Bluetooth is disabled"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SmartKeyAccessTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    ScanScreen(
                        status = status,
                        scanning = scanning,
                        onFindClick = ::startFindingEsp32,
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
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
            status = "Device does not support BLE"
            return
        }

        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null) {
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
                status = "Unable to obtain BLE scanner"
                return
            }

            scanner = activeScanner
            scanning = true
            status = "Scanning for ESP32..."
            activeScanner.startScan(scanCallback)
            handler.postDelayed(scanTimeout, 10_000L)
        } catch (_: SecurityException) {
            stopScan()
            status = "Bluetooth permission missing"
        } catch (_: IllegalStateException) {
            stopScan()
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
            // Quyền có thể bị thu hồi khi app đang scan.
        }
        scanner = null
    }

    override fun onDestroy() {
        stopScan()
        super.onDestroy()
    }
}

@Composable
private fun ScanScreen(
    status: String,
    scanning: Boolean,
    onFindClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Text(status)
        Button(onClick = onFindClick, enabled = !scanning, modifier = Modifier.padding(top = 8.dp)) {
            Text("Find ESP32")
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun ScanScreenPreview() {
    SmartKeyAccessTheme {
        ScanScreen(
            status = "Ready to find ESP32",
            scanning = false,
            onFindClick = {}
        )
    }
}

