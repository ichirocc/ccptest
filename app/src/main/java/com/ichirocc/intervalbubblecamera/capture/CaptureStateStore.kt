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
    val skippedCount: Int = 0,
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
            detail = "${intervalSeconds}秒ごとに撮影し、前回と比べて動体があるときだけ保存します。",
        )
    }

    fun markPhotoSaved(fileName: String) {
        val current = mutableState.value
        mutableState.value = current.copy(
            phase = CapturePhase.RUNNING,
            photoCount = current.photoCount + 1,
            lastPhotoName = fileName,
            detail = "動体を検知して保存しました（見送り${current.skippedCount}枚）。",
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
            detail = "動体なしのため保存を見送りました（変化${"%.1f".format(changedRatio * 100)}%・見送り${skipped}枚）。",
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
