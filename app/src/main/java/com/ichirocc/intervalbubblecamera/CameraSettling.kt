package com.ichirocc.intervalbubblecamera

/**
 * カメラの自動露出（AE）・自動ホワイトバランス（AWB）・自動ピント（AF）の状態。Camera2 の撮影結果
 * （CaptureResult.CONTROL_AE_STATE などの値）。報告されない項目は null。
 */
data class ThreeAState(val ae: Int?, val awb: Int?, val af: Int?)

/**
 * カメラを切り替えた直後、露出・ホワイトバランス・ピントが落ち着くのを待つ。決まった時間だけ待つのではなく、
 * カメラが毎コマ知らせる状態を見て、落ち着いたら撮る（暗い所では長めに、明るい所ではすぐに）。
 */
object CameraSettling {
    // CaptureResult.CONTROL_AE_STATE_* と同じ値。
    private const val AE_CONVERGED = 2
    private const val AE_LOCKED = 3
    private const val AE_FLASH_REQUIRED = 4

    // CaptureResult.CONTROL_AWB_STATE_* と同じ値。
    private const val AWB_CONVERGED = 2
    private const val AWB_LOCKED = 3

    // CaptureResult.CONTROL_AF_STATE_* と同じ値。探している最中の 2 つ以外は落ち着いている
    // （ピントを合わせないカメラは INACTIVE のまま）。
    private const val AF_PASSIVE_SCAN = 1
    private const val AF_ACTIVE_SCAN = 3

    /** 切り替えてから最低これだけは待つ（最初の数コマは前のカメラの設定のまま報告されることがある）。 */
    const val MIN_WAIT_MS = 100L

    /** 露出・ホワイトバランス・ピントがどれも落ち着いたか。報告されない項目は落ち着いているとみなす。 */
    fun settled(state: ThreeAState): Boolean {
        val ae = state.ae == null || state.ae == AE_CONVERGED || state.ae == AE_LOCKED || state.ae == AE_FLASH_REQUIRED
        val awb = state.awb == null || state.awb == AWB_CONVERGED || state.awb == AWB_LOCKED
        val af = state.af == null || (state.af != AF_PASSIVE_SCAN && state.af != AF_ACTIVE_SCAN)
        return ae && awb && af
    }

    /**
     * もう待つのをやめて撮ってよいか。最低 [MIN_WAIT_MS] 待ったうえで、どのカメラも落ち着いた（[states] に
     * 切り替えた後の状態が届き、どれも [settled]）か、[maxWaitMs] 待った。状態が届かないカメラ（夜景モードなど）は
     * 最後まで待つ。
     */
    fun doneWaiting(states: List<ThreeAState?>, waitedMs: Long, maxWaitMs: Long): Boolean {
        if (waitedMs < MIN_WAIT_MS) return false
        if (waitedMs >= maxWaitMs) return true
        return states.isNotEmpty() && states.all { it != null && settled(it) }
    }
}
