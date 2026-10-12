package com.example.smartkeyaccess.uwb

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.random.Random

enum class UwbState {
    IDLE,
    RANGING,
    SUCCESS,
    ERROR
}

class UwbManager(
    private val scope: CoroutineScope
) {
    private val _uwbState = MutableStateFlow(UwbState.IDLE)
    val uwbState: StateFlow<UwbState> = _uwbState.asStateFlow()

    private val _distanceMeters = MutableStateFlow<Float?>(null)
    val distanceMeters: StateFlow<Float?> = _distanceMeters.asStateFlow()

    private val _status = MutableStateFlow("UWB: Ready (Tap Test UWB)")
    val status: StateFlow<String> = _status.asStateFlow()

    private var rangingJob: Job? = null

    // Manual test trigger to verify ranging workflow
    fun testUwbManual(targetDistance: Float? = null) {
        if (_uwbState.value == UwbState.RANGING) return

        rangingJob?.cancel()
        rangingJob = scope.launch {
            _uwbState.value = UwbState.RANGING
            _status.value = "UWB: Ranging..."

            // Simulate DW3000 pulse round-trip delay
            delay(600)

            val distance = targetDistance ?: (0.8f + Random.nextFloat() * 0.7f)
            _distanceMeters.value = distance

            if (distance <= 1.6f) {
                _uwbState.value = UwbState.SUCCESS
                _status.value = "UWB Ranging OK: %.2fm (Within 1.6m unlock range)".format(distance)
            } else {
                _uwbState.value = UwbState.ERROR
                _status.value = "UWB Rejected: %.2fm (Exceeds 1.6m safe range)".format(distance)
            }
        }
    }

    fun reset() {
        rangingJob?.cancel()
        _uwbState.value = UwbState.IDLE
        _distanceMeters.value = null
        _status.value = "UWB: Ready (Tap Test UWB)"
    }
}
