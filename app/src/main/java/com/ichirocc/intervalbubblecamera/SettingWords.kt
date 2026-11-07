package com.ichirocc.intervalbubblecamera

/**
 * くわしい設定の数を、小学生にも分かることばの段階に分ける。数そのものではなく「どうなるか」で
 * 決められるようにするため。画面のことばは MainActivity が文字列に当てる。
 */
object SettingWords {
    /** 明るさがどれだけ変わったら動いたとみなすか（数が小さいほど、少しの変化でも反応する）。 */
    enum class Sensitivity { VERY_SENSITIVE, SENSITIVE, NORMAL, DULL, VERY_DULL }

    /** 画面のどれだけが変わったら動いたとみなすか。 */
    enum class AreaSize { TINY, SMALL, PART, WIDE }

    /** 何秒ごとに撮るかで、電池がどれくらい減るか。 */
    enum class Battery { DRAINS_FAST, NORMAL, LASTS_LONG }

    fun sensitivityOf(pixelThreshold: Int): Sensitivity = when {
        pixelThreshold <= 14 -> Sensitivity.VERY_SENSITIVE
        pixelThreshold <= 25 -> Sensitivity.SENSITIVE
        pixelThreshold <= 35 -> Sensitivity.NORMAL
        pixelThreshold <= 55 -> Sensitivity.DULL
        else -> Sensitivity.VERY_DULL
    }

    /** [areaPermille] は 0.1% 単位（10 で 1.0%）。 */
    fun areaSizeOf(areaPermille: Int): AreaSize = when {
        areaPermille <= 3 -> AreaSize.TINY
        areaPermille <= 12 -> AreaSize.SMALL
        areaPermille <= 40 -> AreaSize.PART
        else -> AreaSize.WIDE
    }

    fun batteryOf(intervalSeconds: Int): Battery = when {
        intervalSeconds <= 3 -> Battery.DRAINS_FAST
        intervalSeconds < 15 -> Battery.NORMAL
        else -> Battery.LASTS_LONG
    }

    /** 1 時間に撮るおよその回数。 */
    fun shotsPerHour(intervalSeconds: Int): Int = 3600 / IntervalPolicy.clampSeconds(intervalSeconds)
}
