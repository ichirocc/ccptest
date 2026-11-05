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
 */
enum class HeatLevel(
    val label: String,
    val detection: DetectionLevel,
    /** 撮影間隔の下限（秒）。設定がこれより短ければこの間隔で撮る。 */
    val minIntervalSeconds: Int,
    /** 1 周撮り終えてから次の周までの最低の休み（ミリ秒）。撮影が間隔より長くかかっても必ず休む。 */
    val minRestMillis: Long,
) {
    NORMAL("普通", DetectionLevel.ALL_ORIENTATIONS, 0, 0L),
    WARM("やや熱い", DetectionLevel.UPRIGHT_ONLY, 5, 2_000L),
    HOT("熱い", DetectionLevel.NONE, 15, 5_000L),
    CRITICAL("とても熱い", DetectionLevel.NONE, 60, 30_000L),
}

object ThermalPolicy {
    // PowerManager.THERMAL_STATUS_* と同じ値。
    const val STATUS_MODERATE = 2
    const val STATUS_SEVERE = 3
    const val STATUS_CRITICAL = 4

    /** この先の温度の見込み（PowerManager.getThermalHeadroom）がこれ以上なら「やや熱い」。1.0 で「熱い」の手前。 */
    const val WARM_HEADROOM = 0.85f
    const val HOT_HEADROOM = 1.0f

    /**
     * 今の温度の状態 [status]（PowerManager.THERMAL_STATUS_*）と、この先の見込み [headroom]
     * （分からなければ null か NaN）から段階を決める。見込みも使い、熱くなりきる前に負荷を下げる。
     */
    fun level(status: Int, headroom: Float?): HeatLevel {
        val forecast = headroom?.takeUnless { it.isNaN() }
        return when {
            status >= STATUS_CRITICAL -> HeatLevel.CRITICAL
            status >= STATUS_SEVERE || (forecast != null && forecast >= HOT_HEADROOM) -> HeatLevel.HOT
            status >= STATUS_MODERATE || (forecast != null && forecast >= WARM_HEADROOM) -> HeatLevel.WARM
            else -> HeatLevel.NORMAL
        }
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
