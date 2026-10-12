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

    private val _status = MutableStateFlow("UWB: Sẵn sàng (Bấm Test UWB để đo)")
    val status: StateFlow<String> = _status.asStateFlow()

    private var rangingJob: Job? = null

    // Nút manual test để kiểm tra luồng UWB
    fun testUwbManual(targetDistance: Float? = null) {
        if (_uwbState.value == UwbState.RANGING) return

        rangingJob?.cancel()
        rangingJob = scope.launch {
            _uwbState.value = UwbState.RANGING
            _status.value = "UWB: Đang phát xung đo khoảng cách (Ranging)..."

            // Giả lập độ trễ đo xung của module DW3000
            delay(600)

            val distance = targetDistance ?: (0.8f + Random.nextFloat() * 0.7f)
            _distanceMeters.value = distance

            if (distance <= 1.6f) {
                _uwbState.value = UwbState.SUCCESS
                _status.value = "UWB Ranging OK: %.2fm (Trong phạm vi mở cửa < 1.6m)".format(distance)
            } else {
                _uwbState.value = UwbState.ERROR
                _status.value = "UWB Từ chối: %.2fm (Vượt quá phạm vi an toàn 1.6m)".format(distance)
            }
        }
    }

    fun reset() {
        rangingJob?.cancel()
        _uwbState.value = UwbState.IDLE
        _distanceMeters.value = null
        _status.value = "UWB: Sẵn sàng (Bấm Test UWB để đo)"
    }
}
