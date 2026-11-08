package com.ichirocc.intervalbubblecamera

/**
 * 撮り方のまとまり。撮影間隔と動体検知の閾値（変化の大きさ・変化の広さ）の 3 つを、撮る場面ごとに
 * まとめて決める。暗さ・手ぶれ・本体の温度・カメラの画角はアプリが自動で合わせるので、ユーザーは
 * 「何を撮るか」だけ選べばよい。どれにも当てはまらない値は [CUSTOM]（自分で決めた値）。
 */
enum class CapturePreset(
    val storageKey: String,
    val intervalSeconds: Int,
    val pixelThreshold: Int,
    val areaPermille: Int,
) {
    /** これまでの既定の値。迷ったらこれ。 */
    STANDARD("standard", intervalSeconds = 10, pixelThreshold = 28, areaPermille = 10),

    /**
     * 屋外で遠くの人を撮る: 狭い変化も拾い、こまめに撮る。変化の大きさは、実機の写真 33 枚から作った場面で
     * 22 → 28 にした（遠くの小さな人の見つけやすさは同じまま、1〜2° の手ぶれで保存してしまう回が 6 割ほど減った）。
     */
    DISTANT("distant", intervalSeconds = 5, pixelThreshold = 28, areaPermille = 3),

    /** 室内で近くの人を撮る: 照明のちらつきなど小さな変化は拾わない。 */
    NEARBY("nearby", intervalSeconds = 10, pixelThreshold = 32, areaPermille = 20),

    /** 長い時間撮り続ける: 撮る回数を減らして電池と発熱を抑える。 */
    LONG_RUN("long_run", intervalSeconds = 30, pixelThreshold = 28, areaPermille = 10),

    /** 自分で決めた値（スライダーを動かすとこれになる）。値は持たない。 */
    CUSTOM("custom", intervalSeconds = 0, pixelThreshold = 0, areaPermille = 0),
    ;

    val threshold: MotionThreshold get() = MotionThreshold(pixelThreshold, areaPermille)

    companion object {
        val DEFAULT = STANDARD

        /** 選べるまとまり（[CUSTOM] 以外）。 */
        val choices: List<CapturePreset> = entries.filter { it != CUSTOM }

        /** 値 [intervalSeconds]・[threshold] がちょうど当てはまるまとまり。どれでもなければ [CUSTOM]。 */
        fun matching(intervalSeconds: Int, threshold: MotionThreshold): CapturePreset = choices.firstOrNull {
            it.intervalSeconds == intervalSeconds &&
                it.pixelThreshold == threshold.pixelThreshold &&
                it.areaPermille == threshold.areaPermille
        } ?: CUSTOM

        /**
         * 以前の「外・遠く」の値（5 秒・22・0.3%）が保存されていれば、今の「外・遠く」の閾値。そうでなければ null。
         * 値を変えたあとも、前に「外・遠く」を選んでいた人が「自分で決める」にならないように置き換える。
         */
        fun upgradedThreshold(intervalSeconds: Int, threshold: MotionThreshold): MotionThreshold? =
            if (intervalSeconds == 5 && threshold == MotionThreshold(22, 3)) DISTANT.threshold else null
    }
}
