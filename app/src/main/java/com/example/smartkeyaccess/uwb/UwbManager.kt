package com.example.smartkeyaccess.uwb

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class UwbState {
    IDLE,
    TEST_REQUESTED,
    ERROR
}

class UwbManager {
    private val _uwbState = MutableStateFlow(UwbState.IDLE)
    val uwbState: StateFlow<UwbState> = _uwbState.asStateFlow()

    private val _status = MutableStateFlow("UWB: Integration test not started")
    val status: StateFlow<String> = _status.asStateFlow()

    /**
     * Records the user's request to start the manual UWB integration test.
     *
     * No ranging session or POLL/RESPONSE/FINAL exchange is started here yet.
     * The transport, peer parameters, 200 ms reply delay, and test-vector usage
     * are still awaiting the firmware/UWB contract. Keeping this trigger separate
     * lets the real session implementation replace it without changing the UI flow.
     */
    fun startIntegrationTest() {
        if (_uwbState.value == UwbState.TEST_REQUESTED) return

        _uwbState.value = UwbState.TEST_REQUESTED
        _status.value = "UWB test requested: waiting for firmware/UWB session contract"
    }

    fun reset() {
        _uwbState.value = UwbState.IDLE
        _status.value = "UWB: Integration test not started"
    }
}
