package com.example.smartkeyaccess.uwb

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.core.uwb.RangingParameters
import androidx.core.uwb.RangingResult
import androidx.core.uwb.UwbClientSessionScope
import androidx.core.uwb.UwbControllerSessionScope
import androidx.core.uwb.UwbControleeSessionScope
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale

// Lifecycle state of the UWB Controller ranging session
enum class UwbSessionState {
    IDLE,
    CHECKING,
    CONFIGURED,
    WAITING_FOR_START,
    RANGING_ACTIVE,
    COMPLETED,
    TIMEOUT,
    PEER_DISCONNECTED,
    UNSUPPORTED,
    PERMISSION_REQUIRED,
    SERVICE_UNAVAILABLE,
    ERROR
}

// Data class holding TWR timestamp telemetry and debug measurements (POLL TX, RESPONSE RX, Delta)
data class UwbTimestampDebug(
    val t1PollTx: String = "N/A",
    val t4ResponseRx: String = "N/A",
    val deltaT4T1: String = "N/A"
)

// UWB Controller Manager: coordinates session lifecycle and receives distance telemetry.
// Unlock decision and proximity threshold are evaluated by ESP32 Access Authority.
class UwbManager(context: Context) {

    companion object {
        const val DEFAULT_RANGING_TIMEOUT_MS = 10000L
        const val MIN_ANDROID_API = 31
    }

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _sessionState = MutableStateFlow(UwbSessionState.IDLE)
    val sessionState: StateFlow<UwbSessionState> = _sessionState.asStateFlow()

    // Backward compatibility alias
    val uwbState: StateFlow<UwbSessionState> = _sessionState.asStateFlow()

    private val _status = MutableStateFlow("UWB Controller: Idle")
    val status: StateFlow<String> = _status.asStateFlow()

    private val _capabilityReport = MutableStateFlow(UwbCapabilityReport())
    val capabilityReport: StateFlow<UwbCapabilityReport> = _capabilityReport.asStateFlow()

    // Backward compatibility alias
    val selfTestReport: StateFlow<UwbCapabilityReport> = _capabilityReport.asStateFlow()

    private val _latestDistanceMeters = MutableStateFlow<Float?>(null)
    val latestDistanceMeters: StateFlow<Float?> = _latestDistanceMeters.asStateFlow()

    private val _timestampDebug = MutableStateFlow(UwbTimestampDebug())
    val timestampDebug: StateFlow<UwbTimestampDebug> = _timestampDebug.asStateFlow()

    private val _activeSessionLocalAddress = MutableStateFlow<String?>(null)
    val activeSessionLocalAddress: StateFlow<String?> = _activeSessionLocalAddress.asStateFlow()

    private var activeJob: Job? = null
    private var activeSessionScope: UwbClientSessionScope? = null

    // Check phone's local UWB capabilities in an isolated probe scope
    fun checkCapabilities() {
        if (_sessionState.value == UwbSessionState.CHECKING || _sessionState.value == UwbSessionState.RANGING_ACTIVE) return
        activeJob?.cancel()
        activeJob = scope.launch {
            inspectCapabilities()
        }
    }

    // Backward compatibility alias
    fun startSelfTest() = checkCapabilities()

    // Run isolated hardware and capability inspection
    suspend fun inspectCapabilities(): UwbCapabilityReport {
        _sessionState.value = UwbSessionState.CHECKING
        _status.value = "Checking UWB hardware capabilities..."

        val androidOk = Build.VERSION.SDK_INT >= MIN_ANDROID_API
        if (!androidOk) {
            val report = UwbCapabilityReport(
                phoneUwbCapable = false,
                androidVersionSupported = false,
                androidApiLevel = Build.VERSION.SDK_INT,
                diagnosisMessage = "Android 12 (API 31) or newer required for AndroidX UWB."
            )
            _capabilityReport.value = report
            _sessionState.value = UwbSessionState.UNSUPPORTED
            _status.value = report.diagnosisMessage
            return report
        }

        val hardwareOk = appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_UWB)
        if (!hardwareOk) {
            val report = UwbCapabilityReport(
                phoneUwbCapable = false,
                androidVersionSupported = true,
                hardwareSupported = false,
                diagnosisMessage = "Device does not feature UWB hardware."
            )
            _capabilityReport.value = report
            _sessionState.value = UwbSessionState.UNSUPPORTED
            _status.value = report.diagnosisMessage
            return report
        }

        val permissionOk = ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.UWB_RANGING
        ) == PackageManager.PERMISSION_GRANTED

        if (!permissionOk) {
            val report = UwbCapabilityReport(
                phoneUwbCapable = false,
                androidVersionSupported = true,
                hardwareSupported = true,
                permissionGranted = false,
                diagnosisMessage = "Permission UWB_RANGING not granted."
            )
            _capabilityReport.value = report
            _sessionState.value = UwbSessionState.PERMISSION_REQUIRED
            _status.value = report.diagnosisMessage
            return report
        }

        return try {
            val manager = AndroidXUwbManager.createInstance(appContext)
            val available = manager.isAvailable()

            if (!available) {
                val report = UwbCapabilityReport(
                    phoneUwbCapable = false,
                    androidVersionSupported = true,
                    hardwareSupported = true,
                    permissionGranted = true,
                    serviceAvailable = false,
                    diagnosisMessage = "UWB hardware detected but subsystem is disabled in settings."
                )
                _capabilityReport.value = report
                _sessionState.value = UwbSessionState.SERVICE_UNAVAILABLE
                _status.value = report.diagnosisMessage
                return report
            }

            val probeScope: UwbClientSessionScope = try {
                manager.controllerSessionScope()
            } catch (_: Exception) {
                manager.controleeSessionScope()
            }
            val caps = probeScope.rangingCapabilities
            val probeAddress = probeScope.localAddress.toString()

            val firaChannelsSupported = caps.supportedChannels.contains(5) || caps.supportedChannels.contains(9)
            val firaDsTwrSupported = caps.supportedConfigIds.contains(RangingParameters.CONFIG_UNICAST_DS_TWR)
            val firaProvisionedDsTwrSupported = caps.supportedConfigIds.contains(RangingParameters.CONFIG_PROVISIONED_UNICAST_DS_TWR)
            val phoneCapable = firaChannelsSupported && (firaDsTwrSupported || firaProvisionedDsTwrSupported)

            val summary = if (phoneCapable) {
                "Phone UWB ready (Ch 5/9, Provisioned STS: $firaProvisionedDsTwrSupported, Static STS: $firaDsTwrSupported)."
            } else {
                "Phone lacks Channel 5/9 or DS-TWR support."
            }

            val report = UwbCapabilityReport(
                phoneUwbCapable = phoneCapable,
                androidVersionSupported = true,
                hardwareSupported = true,
                permissionGranted = true,
                serviceAvailable = true,
                distanceSupported = caps.isDistanceSupported,
                azimuthSupported = caps.isAzimuthalAngleSupported,
                elevationSupported = caps.isElevationAngleSupported,
                supportedChannels = caps.supportedChannels,
                supportedConfigIds = caps.supportedConfigIds,
                firaChannelsSupported = firaChannelsSupported,
                firaDsTwrSupported = firaDsTwrSupported,
                firaProvisionedDsTwrSupported = firaProvisionedDsTwrSupported,
                localProbeAddress = probeAddress,
                diagnosisMessage = summary
            )

            _capabilityReport.value = report
            _sessionState.value = UwbSessionState.IDLE
            _status.value = summary
            report

        } catch (e: UwbHardwareNotAvailableException) {
            val report = UwbCapabilityReport(
                phoneUwbCapable = false,
                hardwareSupported = false,
                diagnosisMessage = "UWB hardware not available: ${e.message}"
            )
            _capabilityReport.value = report
            _sessionState.value = UwbSessionState.UNSUPPORTED
            _status.value = report.diagnosisMessage
            report
        } catch (e: UwbServiceNotAvailableException) {
            val report = UwbCapabilityReport(
                phoneUwbCapable = false,
                serviceAvailable = false,
                diagnosisMessage = "UWB service unavailable: ${e.message}"
            )
            _capabilityReport.value = report
            _sessionState.value = UwbSessionState.SERVICE_UNAVAILABLE
            _status.value = report.diagnosisMessage
            report
        } catch (e: SecurityException) {
            val report = UwbCapabilityReport(
                phoneUwbCapable = false,
                permissionGranted = false,
                diagnosisMessage = "Security exception: ${e.message}"
            )
            _capabilityReport.value = report
            _sessionState.value = UwbSessionState.PERMISSION_REQUIRED
            _status.value = report.diagnosisMessage
            report
        } catch (e: Exception) {
            val report = UwbCapabilityReport(
                phoneUwbCapable = false,
                diagnosisMessage = "Error checking capabilities: ${e.message}"
            )
            _capabilityReport.value = report
            _sessionState.value = UwbSessionState.ERROR
            _status.value = report.diagnosisMessage
            report
        }
    }

    // Backward compatibility alias
    suspend fun runHardwareSelfTest(): UwbCapabilityReport = inspectCapabilities()

    // Prepare local UWB session scope ahead of session to obtain local UWB address for BLE handshake.
    // In accordance with README, Phone acts as Controller (Initiator) coordinating ranging with ESP32 (Controlee/Responder).
    suspend fun getOrCreateSessionScope(isController: Boolean = true): UwbClientSessionScope? {
        activeSessionScope?.let { return it }
        val manager = try {
            AndroidXUwbManager.createInstance(appContext)
        } catch (e: Exception) {
            _status.value = "Failed to initialize UwbManager: ${e.message}"
            return null
        }
        if (!manager.isAvailable()) {
            _status.value = "UWB subsystem is unavailable."
            return null
        }
        val scope: UwbClientSessionScope = try {
            if (isController) manager.controllerSessionScope() else manager.controleeSessionScope()
        } catch (e: Exception) {
            _status.value = "Failed to allocate session scope: ${e.message}"
            return null
        }
        activeSessionScope = scope
        _activeSessionLocalAddress.value = scope.localAddress.toString()
        return scope
    }

    // Convenience and backward-compatibility aliases
    suspend fun getOrCreateControllerScope(): UwbClientSessionScope? = getOrCreateSessionScope(isController = true)
    suspend fun getOrCreateControleeScope(): UwbClientSessionScope? = getOrCreateSessionScope(isController = true)

    suspend fun getLocalAddressBytes(): ByteArray? {
        val s = getOrCreateSessionScope(isController = true) ?: return null
        return s.localAddress.address
    }

    // Validate UWB configuration against probed phone hardware capabilities
    fun validateConfigAgainstCapabilities(config: UwbConfig): String? {
        val report = _capabilityReport.value
        if (!report.readyToRange) {
            return "Phone UWB hardware is not ready for Provisioned STS ranging: ${report.diagnosisMessage}"
        }
        if (report.supportedChannels.isNotEmpty() && !report.supportedChannels.contains(config.channel)) {
            return "Channel ${config.channel} not supported by phone UWB hardware."
        }
        if (config.configId == RangingParameters.CONFIG_PROVISIONED_UNICAST_DS_TWR && !report.firaProvisionedDsTwrSupported) {
            return "Phone hardware does not support CONFIG_PROVISIONED_UNICAST_DS_TWR (Provisioned STS)."
        }
        if (report.supportedConfigIds.isNotEmpty() && !report.supportedConfigIds.contains(config.configId)) {
            return "Config profile ${config.configId} not supported by phone hardware."
        }
        return null
    }

    // Arm UWB system to wait for START UWB BLE signal from ESP32
    fun armForStart() {
        _sessionState.value = UwbSessionState.WAITING_FOR_START
        _status.value = "UWB: Armed, waiting for START UWB signal from ESP32..."
    }

    // Start UWB Controller ranging session using parameters coordinated with ESP32
    fun startRanging(
        config: UwbConfig,
        inactivityTimeoutMs: Long = 8000L,
        singleShot: Boolean = true
    ) {
        if (_sessionState.value == UwbSessionState.RANGING_ACTIVE) return

        if (!config.isValid()) {
            _sessionState.value = UwbSessionState.ERROR
            _status.value = "Invalid UWB configuration parameters."
            return
        }

        activeJob?.cancel()
        activeJob = scope.launch {
            purgeDistance()

            // Automatically inspect capabilities if not yet probed
            if (_capabilityReport.value.diagnosisMessage == "UWB capability check not run yet") {
                inspectCapabilities()
            }

            val capErr = validateConfigAgainstCapabilities(config)
            if (capErr != null) {
                _sessionState.value = UwbSessionState.ERROR
                _status.value = "Hardware compatibility error: $capErr"
                return@launch
            }

            val sessionScope = activeSessionScope ?: getOrCreateSessionScope(isController = true) ?: run {
                _sessionState.value = UwbSessionState.ERROR
                _status.value = "Failed to obtain UWB session scope."
                return@launch
            }

            val localAddrStr = sessionScope.localAddress.toString()
            _activeSessionLocalAddress.value = localAddrStr
            _sessionState.value = UwbSessionState.RANGING_ACTIVE
            _status.value = String.format(
                Locale.US,
                "Ranging: Local [%s] <-> ESP32 [%s] (Ch %d, Session 0x%08X)...",
                localAddrStr, config.peerAddressHex, config.channel, config.sessionId
            )

            // Monotonic watchdog tracking valid distance measurements from ESP32
            val lastValidDistanceMonoNs = java.util.concurrent.atomic.AtomicLong(SystemClock.elapsedRealtimeNanos())
            val watchdogJob = launch {
                while (isActive) {
                    delay(500L)
                    val idleMs = (SystemClock.elapsedRealtimeNanos() - lastValidDistanceMonoNs.get()) / 1_000_000L
                    if (idleMs > inactivityTimeoutMs) {
                        _sessionState.value = UwbSessionState.TIMEOUT
                        _status.value = "UWB Ranging Timeout: No valid distance measurements received for ${idleMs}ms (watchdog limit ${inactivityTimeoutMs}ms)."
                        purgeDistance()
                        activeSessionScope = null
                        activeJob?.cancel(CancellationException("Inactivity watchdog timeout"))
                        break
                    }
                }
            }

            try {
                val complexChannel = config.toUwbComplexChannel()
                val peerDevice = config.toUwbDevice()

                val rangingParameters = RangingParameters(
                    uwbConfigType = config.configId,
                    sessionId = config.sessionId,
                    subSessionId = config.subSessionId,
                    sessionKeyInfo = config.sessionKeyInfo,
                    subSessionKeyInfo = config.subSessionKeyInfo,
                    complexChannel = complexChannel,
                    peerDevices = listOf(peerDevice),
                    updateRateType = config.updateRateType
                )

                sessionScope.prepareSession(rangingParameters).collect { result ->
                    when (result) {
                        is RangingResult.RangingResultPosition -> {
                            val distance = result.position.distance?.value
                            if (distance != null && distance.isFinite() && distance >= 0f) {
                                lastValidDistanceMonoNs.set(SystemClock.elapsedRealtimeNanos())
                                _latestDistanceMeters.value = distance
                                _status.value = String.format(
                                    Locale.US,
                                    "Distance: %.2fm (Access Authority: ESP32)",
                                    distance
                                )

                                // Only display real radio hardware register data.
                                // Android UWB HAL does not expose raw t1/t4 register timestamps to apps; display N/A.
                                _timestampDebug.value = UwbTimestampDebug(
                                    t1PollTx = "N/A",
                                    t4ResponseRx = "N/A",
                                    deltaT4T1 = "N/A"
                                )

                                if (singleShot) {
                                    _sessionState.value = UwbSessionState.COMPLETED
                                    _status.value = String.format(
                                        Locale.US,
                                        "Distance: %.2fm (Access Authority: ESP32)",
                                        distance
                                    )
                                    activeSessionScope = null
                                    activeJob?.cancel(CancellationException("Single-shot ranging completed"))
                                }
                            }
                        }
                        is RangingResult.RangingResultPeerDisconnected -> {
                            _status.value = "ESP32 disconnected from UWB session."
                            _sessionState.value = UwbSessionState.PEER_DISCONNECTED
                            purgeDistance()
                            activeSessionScope = null
                            throw CancellationException("ESP32 peer disconnected")
                        }
                        is RangingResult.RangingResultInitialized -> {
                            _status.value = "UWB session initialized with ESP32. Awaiting pulses..."
                        }
                        else -> {
                            // Explicit handling for RangingResultFailure or platform-specific ranging interruptions
                            if (result.javaClass.simpleName.contains("Failure")) {
                                _status.value = "UWB ranging failure reported by stack (${result.javaClass.simpleName})."
                                _sessionState.value = UwbSessionState.ERROR
                                purgeDistance()
                                activeSessionScope = null
                                activeJob?.cancel(CancellationException("UWB ranging failure: ${result.javaClass.simpleName}"))
                            }
                        }
                    }
                }

                if (_sessionState.value == UwbSessionState.RANGING_ACTIVE) {
                    _sessionState.value = UwbSessionState.COMPLETED
                }

            } catch (e: CancellationException) {
                if (_sessionState.value == UwbSessionState.COMPLETED) {
                    _status.value = String.format(
                        Locale.US,
                        "UWB Ranging Complete (Single-shot): %.2fm",
                        _latestDistanceMeters.value ?: 0f
                    )
                } else {
                    if (_sessionState.value != UwbSessionState.PEER_DISCONNECTED && _sessionState.value != UwbSessionState.TIMEOUT) {
                        _status.value = "UWB session ended."
                    }
                    purgeDistance()
                }
            } catch (e: UwbApiException) {
                _sessionState.value = UwbSessionState.ERROR
                purgeDistance()
                _status.value = "UWB API error: ${e.message}"
            } catch (e: Exception) {
                _sessionState.value = UwbSessionState.ERROR
                purgeDistance()
                _status.value = "UWB ranging failed: ${e.message}"
            } finally {
                watchdogJob.cancel()
                _activeSessionLocalAddress.value = null
                activeSessionScope = null
            }
        }
    }

    // Backward compatibility alias
    fun startControleeRanging(
        config: UwbConfig,
        timeoutMs: Long = DEFAULT_RANGING_TIMEOUT_MS
    ) = startRanging(config, timeoutMs)

    // Stop ranging session and clear distance telemetry
    fun stopRanging() {
        activeJob?.cancel()
        activeJob = null
        activeSessionScope = null
        if (_sessionState.value in listOf(
                UwbSessionState.RANGING_ACTIVE,
                UwbSessionState.WAITING_FOR_START
            )
        ) {
            _sessionState.value = UwbSessionState.IDLE
            _status.value = "UWB Controller: Idle"
        }
        purgeDistance()
        _activeSessionLocalAddress.value = null
    }

    // Clear distance and timestamp telemetry
    private fun purgeDistance() {
        _latestDistanceMeters.value = null
        _timestampDebug.value = UwbTimestampDebug()
    }

    // Reset manager to idle state
    fun reset() {
        stopRanging()
        _capabilityReport.value = UwbCapabilityReport()
        _sessionState.value = UwbSessionState.IDLE
        _timestampDebug.value = UwbTimestampDebug()
        _status.value = "UWB Controller: Idle"
    }

    // Support direct telemetry updates from ESP32 if received via BLE
    fun updateExternalTimestamps(t1: Long, t4: Long, delta: Long? = null, distance: Float? = null) {
        val calculatedDelta = delta ?: (t4 - t1)
        _timestampDebug.value = UwbTimestampDebug(
            t1PollTx = String.format(Locale.US, "%,d ns", t1),
            t4ResponseRx = String.format(Locale.US, "%,d ns", t4),
            deltaT4T1 = String.format(Locale.US, "%,d ns%s", calculatedDelta, if (distance != null) " (%.2fm)".format(Locale.US, distance) else "")
        )
    }

    // Mock UWB ranging result and TWR timestamps (t1, t4, delta)
    // Used for UI verification and telemetry debugging without physical UWB hardware.
    fun mockRangingResult(distance: Float = 1.25f) {
        val nowNs = SystemClock.elapsedRealtimeNanos()
        val tofNs = (distance / 0.299792458).toLong()
        val tReplyNs = 100_000L
        val deltaNs = (2 * tofNs) + tReplyNs
        val t1 = (nowNs - deltaNs).coerceAtLeast(0L)
        val t4 = nowNs

        _latestDistanceMeters.value = distance
        _timestampDebug.value = UwbTimestampDebug(
            t1PollTx = String.format(Locale.US, "%,d ns [MOCK]", t1 % 1_000_000_000_000L),
            t4ResponseRx = String.format(Locale.US, "%,d ns [MOCK]", t4 % 1_000_000_000_000L),
            deltaT4T1 = String.format(Locale.US, "%,d ns [MOCK] (ToF: %d ns | %.2fm)", deltaNs, tofNs, distance)
        )
        _sessionState.value = UwbSessionState.COMPLETED
        _status.value = String.format(Locale.US, "Distance: %.2fm [MOCK] (Access Authority: ESP32)", distance)
    }

    fun cleanup() {
        stopRanging()
        scope.cancel()
    }

    fun close() = cleanup()
}
