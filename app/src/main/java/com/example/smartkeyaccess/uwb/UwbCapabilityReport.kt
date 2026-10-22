package com.example.smartkeyaccess.uwb

import android.os.Build

// Local hardware diagnostic only. A passing report DOES NOT prove ESP32/FiRa interoperation.
// The localProbeAddress is not the address to provision for the actual ranging session.
data class UwbCapabilityReport(
    val phoneUwbCapable: Boolean = false,
    val androidVersionSupported: Boolean = false,
    val androidApiLevel: Int = Build.VERSION.SDK_INT,
    val hardwareSupported: Boolean = false,
    val permissionGranted: Boolean = false,
    val serviceAvailable: Boolean = false,
    val distanceSupported: Boolean? = null,
    val azimuthSupported: Boolean? = null,
    val elevationSupported: Boolean? = null,
    val supportedChannels: Set<Int> = emptySet(),
    val supportedConfigIds: Set<Int> = emptySet(),
    val firaChannelsSupported: Boolean = false,
    val firaDsTwrSupported: Boolean = false,
    val firaProvisionedDsTwrSupported: Boolean = false,
    val localProbeAddress: String? = null,
    val diagnosisMessage: String = "UWB capability check not run yet"
) {
    val readyToRange: Boolean
        get() = phoneUwbCapable && hardwareSupported && permissionGranted && serviceAvailable &&
            distanceSupported == true && firaChannelsSupported && firaProvisionedDsTwrSupported

    // Backward compatibility alias for UI
    val isCompatible: Boolean
        get() = phoneUwbCapable

    val firaComplianceNotice: String
        get() = diagnosisMessage
}

// Backward compatibility alias
typealias UwbHardwareSelfTestReport = UwbCapabilityReport
