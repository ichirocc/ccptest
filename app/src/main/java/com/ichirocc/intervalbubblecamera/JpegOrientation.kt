package com.ichirocc.intervalbubblecamera

/**
 * JPEG の Exif に書かれた写真の向き（Orientation）を、ファイルの形を変えずにその場で書き換える。
 *
 * Ultra HDR の写真は、ふつうの JPEG のあとに明るさの幅を広げるための画像（ゲインマップ）を持ち、その
 * 位置をファイルの中の位置で指している。Exif を作り直して大きさが変わると位置がずれてゲインマップが
 * 使えなくなるおそれがあるので、Orientation の 2 バイトだけを書き換える。ファイルを一度書き出して
 * 作り直す必要もないので、保存も速い。
 */
object JpegOrientation {
    private const val TAG_ORIENTATION = 0x0112
    private const val TYPE_SHORT = 3

    /**
     * [jpeg] の 0 番目の IFD にある Orientation を [orientation]（Exif の値 1〜8）にする。書き換えられたら
     * true。Exif が無い・Orientation が無いなど、その場で書き換えられなければ何も変えずに false。
     */
    fun setInPlace(jpeg: ByteArray, orientation: Int): Boolean {
        require(orientation in 1..8) { "orientation must be 1..8" }
        if (jpeg.size < 4 || u8(jpeg, 0) != 0xFF || u8(jpeg, 1) != 0xD8) return false
        var pos = 2
        while (pos + 4 <= jpeg.size) {
            if (u8(jpeg, pos) != 0xFF) return false
            val marker = u8(jpeg, pos + 1)
            // 画像のデータが始まったら、それより後に Exif は無い。
            if (marker == 0xDA || marker == 0xD9) return false
            val length = (u8(jpeg, pos + 2) shl 8) or u8(jpeg, pos + 3)
            if (length < 2) return false
            val start = pos + 4
            val end = pos + 2 + length
            if (end > jpeg.size) return false
            if (marker == 0xE1 && isExifHeader(jpeg, start, end)) return setInTiff(jpeg, start + 6, end, orientation)
            pos = end
        }
        return false
    }

    private fun isExifHeader(jpeg: ByteArray, start: Int, end: Int): Boolean =
        end - start >= 14 &&
            jpeg[start] == 'E'.code.toByte() && jpeg[start + 1] == 'x'.code.toByte() &&
            jpeg[start + 2] == 'i'.code.toByte() && jpeg[start + 3] == 'f'.code.toByte() &&
            jpeg[start + 4] == 0.toByte() && jpeg[start + 5] == 0.toByte()

    private fun setInTiff(jpeg: ByteArray, tiff: Int, end: Int, orientation: Int): Boolean {
        val little = when {
            u8(jpeg, tiff) == 'I'.code && u8(jpeg, tiff + 1) == 'I'.code -> true
            u8(jpeg, tiff) == 'M'.code && u8(jpeg, tiff + 1) == 'M'.code -> false
            else -> return false
        }
        if (u16(jpeg, tiff + 2, little) != 42) return false
        val ifd = tiff + u32(jpeg, tiff + 4, little)
        if (ifd < tiff || ifd + 2 > end) return false
        val count = u16(jpeg, ifd, little)
        for (i in 0 until count) {
            val entry = ifd + 2 + 12 * i
            if (entry + 12 > end) return false
            if (u16(jpeg, entry, little) != TAG_ORIENTATION) continue
            if (u16(jpeg, entry + 2, little) != TYPE_SHORT || u32(jpeg, entry + 4, little) != 1) return false
            // 1 個の SHORT は、項目の値の欄の先頭 2 バイトに入っている。
            val value = entry + 8
            if (little) {
                jpeg[value] = (orientation and 0xFF).toByte()
                jpeg[value + 1] = 0
            } else {
                jpeg[value] = 0
                jpeg[value + 1] = (orientation and 0xFF).toByte()
            }
            return true
        }
        return false
    }

    private fun u8(b: ByteArray, i: Int): Int = b[i].toInt() and 0xFF

    private fun u16(b: ByteArray, i: Int, little: Boolean): Int =
        if (little) u8(b, i) or (u8(b, i + 1) shl 8) else (u8(b, i) shl 8) or u8(b, i + 1)

    private fun u32(b: ByteArray, i: Int, little: Boolean): Int =
        if (little) {
            u8(b, i) or (u8(b, i + 1) shl 8) or (u8(b, i + 2) shl 16) or (u8(b, i + 3) shl 24)
        } else {
            (u8(b, i) shl 24) or (u8(b, i + 1) shl 16) or (u8(b, i + 2) shl 8) or u8(b, i + 3)
        }
}
