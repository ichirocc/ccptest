package com.ichirocc.intervalbubblecamera

/** カメラが持っている、撮影の質を上げる機能（Camera2 の特性から調べる）。 */
data class CameraFeatures(
    /** 光学式手ぶれ補正（レンズを動かしてぶれを打ち消す）。 */
    val opticalStabilization: Boolean,
    /** 高品質のノイズ除去。 */
    val highQualityNoiseReduction: Boolean,
    /** 高品質の輪郭の処理。 */
    val highQualityEdge: Boolean,
)

/** カメラのつなぎ方。全画素で撮るか・撮影の質を上げる設定を使うか・Ultra HDR で撮るか。 */
data class CaptureSetup(val fullResolution: Boolean, val enhanced: Boolean, val ultraHdr: Boolean) {
    /** どれも使わない、ふつうの撮り方か。 */
    val plain: Boolean get() = !fullResolution && !enhanced && !ultraHdr

    companion object {
        val PLAIN = CaptureSetup(fullResolution = false, enhanced = false, ultraHdr = false)
    }
}

/**
 * 端末のカメラの力をできるだけ使う撮り方を決める。どれも、そのカメラが対応しているときだけ使う。
 * つなげなかった・撮れなかったカメラは、全部をやめるのではなく Ultra HDR → 質を上げる設定 → 全画素 の順に
 * 1 つずつ外す（1 つが組み合わせられないだけで、ほかの機能まで使えなくならないように）。
 */
object CapturePolicy {
    /**
     * 全画素で撮るか。機種の設定（[DeviceProfile.fullResolution]）が有効で、温度が普通で、夜景モードでなければ
     * （夜景モードは自分で大きさを決める）。
     */
    fun useFullResolution(profile: DeviceProfile, heat: HeatLevel, night: Boolean): Boolean =
        profile.fullResolution && heat == HeatLevel.NORMAL && !night

    /**
     * 撮影の質を上げる設定（遅れの少ない撮影・光学式手ぶれ補正・高品質の処理）を使うか。夜景モードでは
     * 使わない（メーカーの夜景処理と重なるため）。
     */
    fun useEnhancedCapture(night: Boolean): Boolean = !night

    /** 外す順。[level] 段外すと、この並びの先頭から [level] 個を使わない。 */
    const val LEVELS = 3

    /** 望む撮り方 [wanted] から [level] 段外した撮り方（1: Ultra HDR、2: 質を上げる設定、3: 全画素も外す）。 */
    fun setupAt(wanted: CaptureSetup, level: Int): CaptureSetup = CaptureSetup(
        fullResolution = wanted.fullResolution && level < 3,
        enhanced = wanted.enhanced && level < 2,
        ultraHdr = wanted.ultraHdr && level < 1,
    )

    /**
     * 今 [level] 段外した [wanted] から、実際に撮り方が変わる次の段。もう外すものが無ければ null
     * （使っていない機能を外しても同じ撮り方のままなので、その段は飛ばす）。
     */
    fun nextLevel(wanted: CaptureSetup, level: Int): Int? {
        val now = setupAt(wanted, level)
        return (level + 1..LEVELS).firstOrNull { setupAt(wanted, it) != now }
    }
}

/**
 * カメラごとに、撮り方を何段外したか（[CapturePolicy.setupAt]）。つなげなかったときは毎回同じなので、その撮影の
 * あいだずっと外す。撮れなかったとき（切り替え直後などの一時的な失敗かもしれない）は [RETRY_AFTER_CYCLES] 周の
 * あいだだけ外し、そのあとはもう一度元の撮り方で試す。
 */
class CaptureFallback {
    private val bindLevels = mutableMapOf<String, Int>()
    private val captureLevels = mutableMapOf<String, Pair<Int, Int>>()

    /** カメラ [key] を、周 [cycle] で何段外すか。 */
    fun level(key: String, cycle: Int): Int {
        val bind = bindLevels[key] ?: 0
        val capture = captureLevels[key]?.takeIf { cycle < it.second }?.first ?: 0
        return maxOf(bind, capture)
    }

    /** いっしょにつなぐカメラ [keys] のうち、一番多く外したカメラの段。 */
    fun level(keys: Collection<String>, cycle: Int): Int = keys.maxOfOrNull { level(it, cycle) } ?: 0

    /** [keys] を [level] 段外した撮り方でもつなげなかったので、その撮影のあいだは [newLevel] 段外す。 */
    fun bindFailed(keys: Collection<String>, newLevel: Int) {
        keys.forEach { bindLevels[it] = maxOf(bindLevels[it] ?: 0, newLevel) }
    }

    /** カメラ [key] が周 [cycle] で撮れなかったので、[RETRY_AFTER_CYCLES] 周のあいだ [newLevel] 段外す。 */
    fun captureFailed(key: String, cycle: Int, newLevel: Int) {
        captureLevels[key] = newLevel to cycle + RETRY_AFTER_CYCLES
    }

    fun clear() {
        bindLevels.clear()
        captureLevels.clear()
    }

    companion object {
        /** 撮れなくて外した撮り方を、もう一度試すまでの周の数。 */
        const val RETRY_AFTER_CYCLES = 30
    }
}
