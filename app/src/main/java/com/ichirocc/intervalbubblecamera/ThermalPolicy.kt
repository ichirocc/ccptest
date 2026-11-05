package com.ichirocc.intervalbubblecamera

/** 人の検出をどこまで行うか。 */
enum class DetectionLevel {
    /** 正立の向きで人が見つからなければ、90°・180°・270° 回した向きでも探す。 */
    ALL_ORIENTATIONS,

    /** スマホの向きから見た正立の向きだけで探す。 */
    UPRIGHT_ONLY,

    /** 検出しない（画像の変化だけで判定する）。 */
    NONE,
}

/**
 * 本体の温度の段階と、その段階での負荷の下げ方。熱くなるほど検出を減らし、撮影の間隔と
 * 1 周ごとの休みを延ばす（カメラ・検出・画像の処理を止める時間を作って本体を冷ます）。
 *
 * 検出の重さは ApproxDet（Xu et al., "ApproxDet: Content and Contention-Aware Approximate Object
 * Detection for Mobiles", SenSys 2020）にならい、入力の解像度（[detectScale]）と、検出する回の間引き
 * （[maxSkippedDetections]、Reducto の考え方。[DetectionSkipping]）を、端末の余裕（ここでは温度）に
 * 合わせて変える。
 */
enum class HeatLevel(
    val label: String,
    val detection: DetectionLevel,
    /** 撮影間隔の下限（秒）。設定がこれより短ければこの間隔で撮る。 */
    val minIntervalSeconds: Int,
    /** 1 周撮り終えてから次の周までの最低の休み（ミリ秒）。撮影が間隔より長くかかっても必ず休む。 */
    val minRestMillis: Long,
    /** 人の検出に渡す画像の大きさ（1 なら機種ごとの大きさのまま。小さいほど速く、熱を出さない）。 */
    val detectScale: Double,
    /** 何も変わっていない回に、続けて検出を省いてよい回数（[DetectionSkipping]）。 */
    val maxSkippedDetections: Int,
) {
    NORMAL("普通", DetectionLevel.ALL_ORIENTATIONS, 0, 0L, 1.0, 2),
    WARM("やや熱い", DetectionLevel.UPRIGHT_ONLY, 5, 2_000L, 0.7, 4),
    HOT("熱い", DetectionLevel.NONE, 15, 5_000L, 1.0, 0),
    CRITICAL("とても熱い", DetectionLevel.NONE, 60, 30_000L, 1.0, 0),
}

object ThermalPolicy {
    // PowerManager.THERMAL_STATUS_* と同じ値。
    const val STATUS_MODERATE = 2
    const val STATUS_SEVERE = 3
    const val STATUS_CRITICAL = 4

    /** この先の温度の見込み（PowerManager.getThermalHeadroom）がこれ以上なら「やや熱い」。1.0 で「熱い」の手前。 */
    const val WARM_HEADROOM = 0.85f
    const val HOT_HEADROOM = 1.0f

    /** 段階を下げる（負荷を戻す）には、見込みがその段階に入った値よりこれだけ下がるのを待つ。 */
    const val COOL_DOWN_MARGIN = 0.05f

    /**
     * 今の温度の状態 [status]（PowerManager.THERMAL_STATUS_*）と、この先の見込み [headroom]
     * （分からなければ null か NaN）から段階を決める。
     *
     * zTT（Kim et al., "zTT: Learning-based DVFS with Zero Thermal Throttling for Mobile Devices",
     * MobiSys 2021）の考え方にならい、端末が性能を落とす（熱で強制的に絞られる）前に、温度の見込みで先に
     * 負荷を下げる。また、下げた負荷は見込みが十分下がるまで戻さない（[COOL_DOWN_MARGIN]）。境目で
     * 負荷を上げ下げし続けると、温度も行ったり来たりして、かえって熱くなるため。[previous] は前回の段階。
     */
    fun level(status: Int, headroom: Float?, previous: HeatLevel = HeatLevel.NORMAL): HeatLevel {
        val forecast = headroom?.takeUnless { it.isNaN() }
        val now = when {
            status >= STATUS_CRITICAL -> HeatLevel.CRITICAL
            status >= STATUS_SEVERE || (forecast != null && forecast >= HOT_HEADROOM) -> HeatLevel.HOT
            status >= STATUS_MODERATE || (forecast != null && forecast >= WARM_HEADROOM) -> HeatLevel.WARM
            else -> HeatLevel.NORMAL
        }
        if (now.ordinal >= previous.ordinal) return now
        // 冷えてきた: 前の段階に入った見込みより十分下がるまで、前の段階のままにする。
        val entered = when (previous) {
            HeatLevel.WARM -> WARM_HEADROOM
            HeatLevel.HOT -> HOT_HEADROOM
            else -> return now
        }
        return if (forecast != null && forecast > entered - COOL_DOWN_MARGIN) previous else now
    }

    /** 1 周撮り終えてから次の周までの待ち時間（ミリ秒）。温度の段階に応じて間隔と休みを延ばす。 */
    fun delayAfterCapture(intervalSeconds: Int, captureDurationMillis: Long, level: HeatLevel): Long =
        maxOf(
            IntervalPolicy.delayAfterCapture(maxOf(intervalSeconds, level.minIntervalSeconds), captureDurationMillis),
            level.minRestMillis,
        )

    /** 画面に出す説明（例: 「本体の温度: やや熱い（検出は正立の向きだけ・間隔5秒以上）」）。 */
    fun summary(level: HeatLevel): String {
        val measures = buildList {
            when (level.detection) {
                DetectionLevel.ALL_ORIENTATIONS -> Unit
                DetectionLevel.UPRIGHT_ONLY -> add("検出は正立の向きだけ")
                DetectionLevel.NONE -> add("検出を止めて画像の変化だけで判定")
            }
            if (level.minIntervalSeconds > 0) add("間隔${level.minIntervalSeconds}秒以上")
        }
        return "本体の温度: ${level.label}" + if (measures.isEmpty()) "" else "（${measures.joinToString("・")}）"
    }
}

/**
 * 何も変わっていない回は、重い人の検出を省く。Reducto（Li et al., "Reducto: On-Camera Filtering for
 * Resource-Efficient Real-Time Video Analytics", SIGCOMM 2020）にならい、軽い特徴（ここでは前回との
 * 画素の差）で「検出しても結果が変わらない」回を見分けて、重い処理を走らせない。速くなり、熱も電池も減る。
 *
 * 画像が変わっておらず、追跡中の人もいなければ、検出しても「動いた人」は見つからない（新しく見つけた人は
 * 前回の位置が無いので、動いたとはみなされない）。ただ止まった人を次の回の比較のために覚えておけるので、
 * 省くのは続けて [HeatLevel.maxSkippedDetections] 回までにする（熱いほど多く省く）。
 */
object DetectionSkipping {
    /** 続けて [skippedSoFar] 回省いた後、今回も省いてよいか。 */
    fun shouldSkip(skippedSoFar: Int, level: HeatLevel): Boolean = skippedSoFar < level.maxSkippedDetections
}
