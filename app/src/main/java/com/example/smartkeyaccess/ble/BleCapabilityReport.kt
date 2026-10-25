package com.example.smartkeyaccess.ble

// Diagnostic report of the phone's local Bluetooth Low Energy readiness.
//
// Notice: This assesses the phone's local BLE subsystem; it does NOT claim protocol
// compatibility with ESP32 until GATT connection, service discovery, and authentication succeed.
data class BleCapabilityReport(
    val hardwareSupported: Boolean = false,
    val bluetoothEnabled: Boolean = false,
    val scanPermissionGranted: Boolean = false,
    val connectPermissionGranted: Boolean = false,
    val diagnosisMessage: String = "BLE capability check not run yet"
) {
    val readyToScan: Boolean
        get() = hardwareSupported &&
                bluetoothEnabled &&
                scanPermissionGranted &&
                connectPermissionGranted
}
