package com.ichirocc.intervalbubblecamera.capture

import android.content.Context
import android.util.Log
import com.ichirocc.intervalbubblecamera.DogfoodRecord
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.Executors

/**
 * 実機で使って確かめるための記録（[DogfoodRecord]）を、アプリ専用の場所の CSV ファイルに書く。
 * 書き込みは専用の 1 本のスレッドで行い、撮影を待たせない。[MAX_BYTES] を超えたら 1 つ前のファイルに
 * 回し、2 つ（合わせて約 10 MB）までしか残さない。「記録を送る」で共有できる。
 */
class DogfoodLog(private val directory: File) {
    private val executor = Executors.newSingleThreadExecutor()

    /** 記録を残すか（設定で切り替える）。 */
    @Volatile var enabled = true

    fun write(record: DogfoodRecord) {
        if (!enabled) return
        val line = record.toCsvRow()
        runCatching { executor.execute { append(line) } }
    }

    fun close() {
        executor.shutdown()
    }

    private fun append(line: String) {
        runCatching {
            directory.mkdirs()
            val current = File(directory, CURRENT)
            if (current.length() > MAX_BYTES) {
                val old = File(directory, OLD)
                old.delete()
                current.renameTo(old)
            }
            val header = if (!current.exists() || current.length() == 0L) DogfoodRecord.HEADER + "\n" else ""
            current.appendText(header + line + "\n")
        }.onFailure { Log.w(TAG, "Unable to write the dogfood log", it) }
    }

    companion object {
        private const val TAG = "DogfoodLog"
        const val CURRENT = "ibc_log.csv"
        const val OLD = "ibc_log.1.csv"
        const val MAX_BYTES = 5L * 1024 * 1024
        private val TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS", Locale.US)

        fun directory(context: Context): File = File(context.filesDir, "dogfood")

        /** 残っている記録のファイル（古い順）。 */
        fun files(context: Context): List<File> =
            listOf(OLD, CURRENT).map { File(directory(context), it) }.filter { it.exists() && it.length() > 0 }

        fun now(): String = TIME_FORMAT.format(LocalDateTime.now())
    }
}
