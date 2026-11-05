package com.ichirocc.intervalbubblecamera.capture

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

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

    // 撮影・検出・保存のスレッドから同時に呼ばれるので、今の値を読んで書き戻す更新は update で行う
    // （別のスレッドの更新を上書きして取りこぼさないため）。

    fun markStarting(intervalSeconds: Int) {
        mutableState.value = CaptureUiState(
            phase = CapturePhase.STARTING,
            intervalSeconds = intervalSeconds,
            detail = "カメラを準備しています…",
        )
    }

    fun markRunning(intervalSeconds: Int, detail: String) = mutableState.update {
        it.copy(phase = CapturePhase.RUNNING, intervalSeconds = intervalSeconds, detail = detail)
    }

    fun markPhotosSaved(fileNames: List<String>) = mutableState.update {
        it.copy(
            phase = CapturePhase.RUNNING,
            photoCount = it.photoCount + fileNames.size,
            lastPhotoName = fileNames.joinToString(" / "),
            detail = "動体を検知して${fileNames.size}枚保存しました（見送り${it.skippedCount}回）。",
        )
    }

    /** 停止した後に保存し終えた分。状態や説明は変えず、枚数と最終ファイルだけ数える。 */
    fun markSavedAfterStop(fileNames: List<String>) = mutableState.update {
        it.copy(photoCount = it.photoCount + fileNames.size, lastPhotoName = fileNames.joinToString(" / "))
    }

    fun markMotionBaseline() = mutableState.update {
        it.copy(
            phase = CapturePhase.RUNNING,
            detail = "比較用の最初の画像を取得しました。次の撮影から動体を検知します。",
        )
    }

    fun markNoMotion(changedRatio: Double, phoneMoved: Boolean = false) {
        val reason = if (phoneMoved) "スマホが動いたため" else "動体なしのため"
        mutableState.update {
            val skipped = it.skippedCount + 1
            it.copy(
                phase = CapturePhase.RUNNING,
                skippedCount = skipped,
                detail = "${reason}保存を見送りました（変化${"%.1f".format(changedRatio * 100)}%・見送り${skipped}回）。",
            )
        }
    }

    fun updateCameraSummary(summary: String) = mutableState.update { it.copy(cameraSummary = summary) }

    fun updateDetectorSummary(summary: String) = mutableState.update { it.copy(detectorSummary = summary) }

    fun updateFailedCameras(cameras: List<String>) = mutableState.update { it.copy(failedCameras = cameras) }

    fun markCaptureError(message: String) = mutableState.update {
        it.copy(phase = CapturePhase.ERROR, detail = message)
    }

    fun markRecovering(message: String) = mutableState.update {
        it.copy(phase = CapturePhase.RUNNING, detail = message)
    }

    fun markStopped(detail: String = "撮影を停止しました。") = mutableState.update {
        it.copy(phase = CapturePhase.IDLE, detail = detail)
    }
}
