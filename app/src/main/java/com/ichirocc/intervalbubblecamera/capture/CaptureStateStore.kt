package com.ichirocc.intervalbubblecamera.capture

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class CapturePhase {
    IDLE,
    STARTING,
    RUNNING,
    ERROR,
}

data class CaptureUiState(
    val phase: CapturePhase = CapturePhase.IDLE,
    val intervalSeconds: Int = 10,
    val lensFacing: String = IntervalCaptureService.LENS_BACK,
    val photoCount: Int = 0,
    val lastPhotoName: String? = null,
    val detail: String = "設定後に撮影を開始してください。",
) {
    val isActive: Boolean
        get() = phase == CapturePhase.STARTING || phase == CapturePhase.RUNNING
}

object CaptureStateStore {
    private val mutableState = MutableStateFlow(CaptureUiState())
    val state: StateFlow<CaptureUiState> = mutableState.asStateFlow()

    fun markStarting(intervalSeconds: Int, lensFacing: String) {
        mutableState.value = CaptureUiState(
            phase = CapturePhase.STARTING,
            intervalSeconds = intervalSeconds,
            lensFacing = lensFacing,
            detail = "カメラを準備しています…",
        )
    }

    fun markRunning(intervalSeconds: Int, lensFacing: String) {
        mutableState.value = mutableState.value.copy(
            phase = CapturePhase.RUNNING,
            intervalSeconds = intervalSeconds,
            lensFacing = lensFacing,
            detail = "${intervalSeconds}秒ごとに自動撮影しています。",
        )
    }

    fun markPhotoSaved(fileName: String) {
        val current = mutableState.value
        mutableState.value = current.copy(
            phase = CapturePhase.RUNNING,
            photoCount = current.photoCount + 1,
            lastPhotoName = fileName,
            detail = "${current.intervalSeconds}秒ごとに自動撮影しています。",
        )
    }

    fun markCaptureError(message: String) {
        mutableState.value = mutableState.value.copy(
            phase = CapturePhase.ERROR,
            detail = message,
        )
    }

    fun markRecovering(message: String) {
        mutableState.value = mutableState.value.copy(
            phase = CapturePhase.RUNNING,
            detail = message,
        )
    }

    fun markStopped(detail: String = "撮影を停止しました。") {
        mutableState.value = mutableState.value.copy(
            phase = CapturePhase.IDLE,
            detail = detail,
        )
    }
}
