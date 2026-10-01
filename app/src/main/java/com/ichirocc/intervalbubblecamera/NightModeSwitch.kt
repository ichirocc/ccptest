package com.ichirocc.intervalbubblecamera

/**
 * 周りの明るさ（照度センサーのルクス）で夜景モードを自動で切り替える。
 * 暗くなったら [enterLux] 未満で夜景、明るくなったら [exitLux] 超で普通に戻す
 * （間を空けて、境目でモードが行ったり来たりしないようにする）。
 */
class NightModeSwitch(
    private val enterLux: Float = ENTER_LUX,
    private val exitLux: Float = EXIT_LUX,
) {
    init {
        require(enterLux < exitLux) { "enterLux must be below exitLux" }
    }

    var night: Boolean = false
        private set

    /** 新しい照度を反映し、モードが変わったら true を返す。 */
    fun update(lux: Float): Boolean {
        val next = when {
            !night && lux < enterLux -> true
            night && lux > exitLux -> false
            else -> night
        }
        val changed = next != night
        night = next
        return changed
    }

    fun reset() {
        night = false
    }

    companion object {
        const val ENTER_LUX = 10f
        const val EXIT_LUX = 30f
    }
}
