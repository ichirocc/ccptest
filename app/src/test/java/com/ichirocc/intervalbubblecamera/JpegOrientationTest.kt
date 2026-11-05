package com.ichirocc.intervalbubblecamera

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class JpegOrientationTest {
    /**
     * Exif（向き [orientation]）と、そのあとに続くデータ [tail]（画像のデータやゲインマップの代わり）を持つ JPEG。
     * [little] なら II（リトルエンディアン）、そうでなければ MM。
     */
    private fun jpeg(orientation: Int?, little: Boolean, tail: ByteArray = byteArrayOf(1, 2, 3, 4)): ByteArray {
        fun u16(v: Int) = if (little) byteArrayOf(v.toByte(), (v shr 8).toByte()) else byteArrayOf((v shr 8).toByte(), v.toByte())
        fun u32(v: Int) = if (little) {
            byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())
        } else {
            byteArrayOf((v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte())
        }
        val tiff = ByteArrayOutputStream().apply {
            write(if (little) "II".toByteArray() else "MM".toByteArray())
            write(u16(42))
            write(u32(8))
            val entries = buildList {
                // 先に別の項目（Make）を置き、Orientation を探して読み進めることを確かめる。
                add(Triple(0x010F, 2, 0))
                if (orientation != null) add(Triple(0x0112, 3, orientation))
            }
            write(u16(entries.size))
            for ((tag, type, value) in entries) {
                write(u16(tag))
                write(u16(type))
                write(u32(1))
                write(if (type == 3) u16(value) + byteArrayOf(0, 0) else u32(value))
            }
            write(u32(0))
        }.toByteArray()
        val app1 = "Exif".toByteArray() + byteArrayOf(0, 0) + tiff
        return ByteArrayOutputStream().apply {
            write(byteArrayOf(0xFF.toByte(), 0xD8.toByte()))
            // Exif の前に別の APP0（JFIF）を置く。
            write(byteArrayOf(0xFF.toByte(), 0xE0.toByte(), 0, 4, 9, 9))
            write(byteArrayOf(0xFF.toByte(), 0xE1.toByte(), ((app1.size + 2) shr 8).toByte(), (app1.size + 2).toByte()))
            write(app1)
            write(byteArrayOf(0xFF.toByte(), 0xDA.toByte()))
            write(tail)
        }.toByteArray()
    }

    private fun orientationOf(jpeg: ByteArray, little: Boolean): Int {
        // 2 番目の項目（Orientation）の値の欄: SOI 2 + APP0 6 + APP1 見出し 4 + "Exif\0\0" 6 + TIFF 8 + 個数 2 + 項目 12 + 8。
        val value = 2 + 6 + 4 + 6 + 8 + 2 + 12 + 8
        val b0 = jpeg[value].toInt() and 0xFF
        val b1 = jpeg[value + 1].toInt() and 0xFF
        return if (little) b0 or (b1 shl 8) else (b0 shl 8) or b1
    }

    @Test
    fun `the orientation is rewritten in place in both byte orders`() {
        for (little in listOf(true, false)) {
            val photo = jpeg(orientation = 1, little = little)
            val size = photo.size
            assertTrue(JpegOrientation.setInPlace(photo, 6))
            assertEquals(6, orientationOf(photo, little))
            assertEquals(size, photo.size)
        }
    }

    @Test
    fun `everything after the exif is left untouched`() {
        val tail = ByteArray(64) { (it * 7).toByte() }
        val photo = jpeg(orientation = 1, little = true, tail = tail)
        assertTrue(JpegOrientation.setInPlace(photo, 8))
        assertArrayEquals(tail, photo.copyOfRange(photo.size - tail.size, photo.size))
    }

    @Test
    fun `nothing changes when there is no orientation or no exif`() {
        val noOrientation = jpeg(orientation = null, little = true)
        val before = noOrientation.copyOf()
        assertFalse(JpegOrientation.setInPlace(noOrientation, 6))
        assertArrayEquals(before, noOrientation)

        val noExif = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xDA.toByte(), 1, 2)
        assertFalse(JpegOrientation.setInPlace(noExif, 6))
        assertFalse(JpegOrientation.setInPlace(byteArrayOf(1, 2, 3), 6))
    }
}
