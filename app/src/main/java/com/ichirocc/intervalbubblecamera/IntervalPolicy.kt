package com.ichirocc.intervalbubblecamera

object IntervalPolicy {
    const val MIN_SECONDS = 1
    const val MAX_SECONDS = 60
    const val DEFAULT_SECONDS = 10

    fun clampSeconds(value: Int): Int = value.coerceIn(MIN_SECONDS, MAX_SECONDS)

    fun secondsFromSeekProgress(progress: Int): Int =
        clampSeconds(progress + MIN_SECONDS)

    fun seekProgressFromSeconds(seconds: Int): Int =
        clampSeconds(seconds) - MIN_SECONDS

    fun delayAfterCapture(intervalSeconds: Int, captureDurationMillis: Long): Long {
        val intervalMillis = clampSeconds(intervalSeconds) * 1_000L
        return (intervalMillis - captureDurationMillis.coerceAtLeast(0L)).coerceAtLeast(0L)
    }
}

