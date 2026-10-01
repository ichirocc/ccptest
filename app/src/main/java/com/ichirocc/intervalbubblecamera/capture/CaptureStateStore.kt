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
    val photoCount: Int = 0,
    val skippedCount: Int = 0,
    val lastPhotoName: String? = null,
    /** 使っているカメラと撮り方（例: 「カメラ6台・切替: back0, …」）。 */
    val cameraSummary: String? = null,
    /** 検出に使っている機種設定と実行先（例: 「Pixel 10 Pro XL・物体 GPU / 姿勢 GPU / 手 CPU」）。 */
    val detectorSummary: String? = null,
    val failedCameras: List<String> = emptyList(),
    val detail: String = "設定後に撮影を開始してください。",
) {
    val isActive: Boolean
        get() = phase == CapturePhase.STARTING || phase == CapturePhase.RUNNING
}

object CaptureStateStore {
    private val mutableState = MutableStateFlow(CaptureUiState())
    val state: StateFlow<CaptureUiState> = mutableState.asStateFlow()

    fun markStarting(intervalSeconds: Int) {
        mutableState.value = CaptureUiState(
            phase = CapturePhase.STARTING,
            intervalSeconds = intervalSeconds,
            detail = "カメラを準備しています…",
        )
    }

    fun markRunning(intervalSeconds: Int, detail: String) {
        mutableState.value = mutableState.value.copy(
            phase = CapturePhase.RUNNING,
            intervalSeconds = intervalSeconds,
            detail = detail,
        )
    }

    fun markPhotosSaved(fileNames: List<String>) {
        val current = mutableState.value
        mutableState.value = current.copy(
            phase = CapturePhase.RUNNING,
            photoCount = current.photoCount + fileNames.size,
            lastPhotoName = fileNames.joinToString(" / "),
            detail = "動体を検知して${fileNames.size}枚保存しました（見送り${current.skippedCount}回）。",
        )
    }

    fun markMotionBaseline() {
        mutableState.value = mutableState.value.copy(
            phase = CapturePhase.RUNNING,
            detail = "比較用の最初の画像を取得しました。次の撮影から動体を検知します。",
        )
    }

    fun markNoMotion(changedRatio: Double) {
        val current = mutableState.value
        val skipped = current.skippedCount + 1
        mutableState.value = current.copy(
            phase = CapturePhase.RUNNING,
            skippedCount = skipped,
            detail = "動体なしのため保存を見送りました（変化${"%.1f".format(changedRatio * 100)}%・見送り${skipped}回）。",
        )
    }

    fun updateCameraSummary(summary: String) {
        mutableState.value = mutableState.value.copy(cameraSummary = summary)
    }

    fun updateDetectorSummary(summary: String) {
        mutableState.value = mutableState.value.copy(detectorSummary = summary)
    }

    fun updateFailedCameras(cameras: List<String>) {
        if (mutableState.value.failedCameras == cameras) return
        mutableState.value = mutableState.value.copy(failedCameras = cameras)
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
