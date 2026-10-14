package com.example.smartkeyaccess.uwb

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.uwb.RangingParameters
import androidx.core.uwb.RangingResult
import androidx.core.uwb.UwbComplexChannel
import androidx.core.uwb.UwbControleeSessionScope
import androidx.core.uwb.UwbDevice
import androidx.core.uwb.UwbManager as AndroidXUwbManager
import androidx.core.uwb.exceptions.UwbApiException
import androidx.core.uwb.exceptions.UwbHardwareNotAvailableException
import androidx.core.uwb.exceptions.UwbServiceNotAvailableException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

enum class UwbState {
    IDLE,
    CHECKING,
    READY_FOR_PEER,
    RANGING,
    IN_RANGE,
    OUT_OF_RANGE,
    TIMEOUT,
    UNSUPPORTED,
    PERMISSION_REQUIRED,
    SERVICE_UNAVAILABLE,
    ERROR
}

data class UwbSelfTestResult(
    val androidVersionSupported: Boolean = false,
    val hardwareSupported: Boolean = false,
    val permissionGranted: Boolean = false,
    val serviceAvailable: Boolean = false,
    val sessionScopeCreated: Boolean = false,
    val controleeScopeCreated: Boolean = sessionScopeCreated,
    val localAddress: String? = null,
    val channel: Int? = null,
    val preambleIndex: Int? = null,
    val distanceSupported: Boolean? = null,
    val azimuthSupported: Boolean? = null,
    val elevationSupported: Boolean? = null,
    val supportedChannels: Set<Int> = emptySet(),
    val supportedConfigIds: Set<Int> = emptySet()
)

/**
 * UWB Controlee Manager for the smart key system.
 *
 * Responsibilities:
 * - ESP32 acts as the UWB Controller: selects channel/preamble, coordinates session ID and STS,
 *   evaluates distance threshold on firmware, and controls physical lock authorization.
 * - Android Phone acts as the UWB Controlee: generates local UWB address, receives session configuration
 *   from ESP32 over BLE, participates in ranging using UwbControleeSessionScope, manages app-side timeout,
 *   and displays proximity feedback.
 */
class UwbManager(context: Context) {

    companion object {
        const val UNLOCK_THRESHOLD_METERS = 1.5f
        const val DEFAULT_RANGING_TIMEOUT_MS = 10000L
    }

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _uwbState = MutableStateFlow(UwbState.IDLE)
    val uwbState: StateFlow<UwbState> = _uwbState.asStateFlow()

    private val _status = MutableStateFlow("UWB Controlee: Self-test not started")
    val status: StateFlow<String> = _status.asStateFlow()

    private val _selfTestResult = MutableStateFlow(UwbSelfTestResult())
    val selfTestResult: StateFlow<UwbSelfTestResult> = _selfTestResult.asStateFlow()

    private val _latestDistanceMeters = MutableStateFlow<Float?>(null)
    val latestDistanceMeters: StateFlow<Float?> = _latestDistanceMeters.asStateFlow()

    private val _isWithinUnlockThreshold = MutableStateFlow(false)
    val isWithinUnlockThreshold: StateFlow<Boolean> = _isWithinUnlockThreshold.asStateFlow()

    private var cachedControleeScope: UwbControleeSessionScope? = null
    private var activeJob: Job? = null

    fun startIntegrationTest() {
        if (_uwbState.value == UwbState.CHECKING || _uwbState.value == UwbState.RANGING) return
        activeJob?.cancel()
        activeJob = scope.launch { runSelfTest() }
    }

    private suspend fun runSelfTest(): UwbControleeSessionScope? {
        _uwbState.value = UwbState.CHECKING
        _status.value = "UWB Controlee: Checking Android version, hardware, permission, and service..."

        val androidOk = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        if (!androidOk) {
            _selfTestResult.value = UwbSelfTestResult(androidVersionSupported = false)
            _uwbState.value = UwbState.UNSUPPORTED
            _status.value = "UWB unsupported: Android 12 (API 31) or newer is required."
            return null
        }

        val hardwareOk = appContext.packageManager.hasSystemFeature("android.hardware.uwb")
        if (!hardwareOk) {
            _selfTestResult.value = UwbSelfTestResult(
                androidVersionSupported = true,
                hardwareSupported = false
            )
            _uwbState.value = UwbState.UNSUPPORTED
            _status.value = "UWB unsupported: Device does not feature android.hardware.uwb."
            return null
        }

        val permissionOk = ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.UWB_RANGING
        ) == PackageManager.PERMISSION_GRANTED

        if (!permissionOk) {
            _selfTestResult.value = UwbSelfTestResult(
                androidVersionSupported = true,
                hardwareSupported = true,
                permissionGranted = false
            )
            _uwbState.value = UwbState.PERMISSION_REQUIRED
            _status.value = "UWB permission required: android.permission.UWB_RANGING not granted."
            return null
        }

        return try {
            val manager = AndroidXUwbManager.createInstance(appContext)
            val available = manager.isAvailable()

            if (!available) {
                _selfTestResult.value = UwbSelfTestResult(
                    androidVersionSupported = true,
                    hardwareSupported = true,
                    permissionGranted = true,
                    serviceAvailable = false
                )
                _uwbState.value = UwbState.SERVICE_UNAVAILABLE
                _status.value = "UWB service unavailable: Hardware detected but UWB subsystem is inactive."
                return null
            }

            val controleeScope = manager.controleeSessionScope()
            cachedControleeScope = controleeScope
            val caps = controleeScope.rangingCapabilities

            _selfTestResult.value = UwbSelfTestResult(
                androidVersionSupported = true,
                hardwareSupported = true,
                permissionGranted = true,
                serviceAvailable = true,
                sessionScopeCreated = true,
                localAddress = controleeScope.localAddress.toString(),
                channel = null,
                preambleIndex = null,
                distanceSupported = caps.isDistanceSupported,
                azimuthSupported = caps.isAzimuthalAngleSupported,
                elevationSupported = caps.isElevationAngleSupported,
                supportedChannels = caps.supportedChannels,
                supportedConfigIds = caps.supportedConfigIds
            )

            _uwbState.value = UwbState.READY_FOR_PEER
            _status.value = "UWB Controlee ready: Local address [${controleeScope.localAddress}]. Awaiting ESP32 Controller parameters via BLE."
            controleeScope

        } catch (e: UwbHardwareNotAvailableException) {
            _uwbState.value = UwbState.UNSUPPORTED
            _status.value = "UWB hardware not available: ${e.message ?: "unknown reason"}"
            null
        } catch (e: UwbServiceNotAvailableException) {
            _uwbState.value = UwbState.SERVICE_UNAVAILABLE
            _status.value = "UWB service unavailable: ${e.message ?: "unknown reason"}"
            null
        } catch (e: SecurityException) {
            _uwbState.value = UwbState.PERMISSION_REQUIRED
            _status.value = "UWB permission error: ${e.message ?: "permission denied"}"
            null
        } catch (e: Exception) {
            _uwbState.value = UwbState.ERROR
            _status.value = "UWB initialization failed: ${e.javaClass.simpleName}: ${e.message}"
            null
        }
    }

    fun startControleeRanging(
        peerAddress: String,
        channel: Int = 9,
        preambleIndex: Int = 9,
        sessionId: Int = 0x12345678,
        timeoutMs: Long = DEFAULT_RANGING_TIMEOUT_MS
    ) {
        if (_uwbState.value == UwbState.RANGING) return
        activeJob?.cancel()
        activeJob = scope.launch {
            val sessionScope = cachedControleeScope ?: runSelfTest()
            if (sessionScope == null) return@launch

            _uwbState.value = UwbState.RANGING
            _status.value = "UWB Controlee ranging with ESP32 [$peerAddress] (ch $channel)..."

            try {
                val complexChannel = UwbComplexChannel(channel, preambleIndex)
                val peerDevice = UwbDevice.createForAddress(peerAddress)
                val rangingParameters = RangingParameters(
                    uwbConfigType = RangingParameters.CONFIG_UNICAST_DS_TWR,
                    sessionId = sessionId,
                    subSessionId = 0,
                    sessionKeyInfo = null,
                    subSessionKeyInfo = null,
                    complexChannel = complexChannel,
                    peerDevices = listOf(peerDevice),
                    updateRateType = RangingParameters.RANGING_UPDATE_RATE_FREQUENT
                )

                val completedNormally = withTimeoutOrNull(timeoutMs) {
                    sessionScope.prepareSession(rangingParameters).collect { result ->
                        when (result) {
                            is RangingResult.RangingResultPosition -> {
                                val distance = result.position.distance?.value
                                if (distance != null) {
                                    _latestDistanceMeters.value = distance
                                    val inRange = distance <= UNLOCK_THRESHOLD_METERS
                                    _isWithinUnlockThreshold.value = inRange
                                    _uwbState.value = if (inRange) UwbState.IN_RANGE else UwbState.OUT_OF_RANGE
                                    _status.value = "Distance: %.2fm (%s). ESP32 firmware holds unlock authority.".format(
                                        distance,
                                        if (inRange) "Within unlock threshold" else "Outside threshold"
                                    )
                                }
                            }
                            is RangingResult.RangingResultPeerDisconnected -> {
                                _status.value = "ESP32 Controller disconnected from UWB session."
                            }
                            is RangingResult.RangingResultInitialized -> {
                                _status.value = "UWB session initialized with ESP32 Controller."
                            }
                        }
                    }
                }

                if (completedNormally == null) {
                    _uwbState.value = UwbState.TIMEOUT
                    _status.value = "UWB ranging timed out after ${timeoutMs}ms. Physical lock remains secure."
                }
            } catch (e: CancellationException) {
                // Ranging cancelled gracefully
            } catch (e: UwbApiException) {
                _uwbState.value = UwbState.ERROR
                _status.value = "UWB API error: ${e.message}"
            } catch (e: Exception) {
                _uwbState.value = UwbState.ERROR
                _status.value = "UWB ranging failed: ${e.javaClass.simpleName}: ${e.message}"
            }
        }
    }

    fun reset() {
        activeJob?.cancel()
        activeJob = null
        _latestDistanceMeters.value = null
        _isWithinUnlockThreshold.value = false
        _uwbState.value = UwbState.IDLE
        _selfTestResult.value = UwbSelfTestResult()
        _status.value = "UWB Controlee: Self-test not started"
    }

    fun close() {
        activeJob?.cancel()
        scope.cancel()
    }
}
