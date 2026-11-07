package com.ichirocc.intervalbubblecamera.capture

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.ichirocc.intervalbubblecamera.LumaFrame
import com.ichirocc.intervalbubblecamera.MotionDetector

/** 撮影した JPEG を 1 回だけ縮小して読み込み、人の検出用の画像と動体検知用の小さな輝度画像を作る。 */
object MotionFrameSampler {
    /**
     * JPEG を長辺が [longSide] 以上（2 倍未満）になるよう縮小して読み込む（センサーの向きのまま）。
     * 人の検出と動体検知で同じ画像を使い、大きな JPEG を 2 回読み込まないようにする。
     */
    fun decode(jpeg: ByteArray, longSide: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        // 動体検知の格子（80×60）より小さくはしない。
        val minLongSide = maxOf(longSide, MotionDetector.GRID_WIDTH * 2)
        var sampleSize = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sampleSize * 2) >= minLongSide) sampleSize *= 2
        val decoded = BitmapFactory.decodeByteArray(
            jpeg,
            0,
            jpeg.size,
            BitmapFactory.Options().apply { inSampleSize = sampleSize },
        ) ?: return null
        // 2 の累乗でしか縮められないので、全画素の写真（5000 万画素など）では長辺が [longSide] の 2 倍近くに
        // なることがある。人の検出は大きさに比例して遅くなるので、[longSide] まで縮める。
        val longest = maxOf(decoded.width, decoded.height)
        if (longest <= minLongSide * MAX_OVERSIZE) return decoded
        val scale = minLongSide.toDouble() / longest
        val resized = Bitmap.createScaledBitmap(
            decoded,
            (decoded.width * scale).toInt().coerceAtLeast(1),
            (decoded.height * scale).toInt().coerceAtLeast(1),
            true,
        )
        if (resized !== decoded) decoded.recycle()
        return resized
    }

    /** JPEG の幅と高さ（画素。中身は読み込まず、見出しだけ読む）。読めなければ null。 */
    fun size(jpeg: ByteArray): Pair<Int, Int>? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
        return if (bounds.outWidth > 0 && bounds.outHeight > 0) bounds.outWidth to bounds.outHeight else null
    }

    /** 読み込んだ画像の長辺が、ほしい大きさのこの倍を超えたら縮める。 */
    private const val MAX_OVERSIZE = 1.25

    /** 読み込んだ画像から動体検知用の 80×60 の輝度画像を作る（[image] はそのまま残す）。 */
    fun fromBitmap(image: Bitmap): LumaFrame {
        // 格子ごとに中の画素をすべて平均する（点で取ると、1 画素に満たない手ぶれで値が大きく変わるため）。
        val argb = IntArray(image.width * image.height)
        image.getPixels(argb, 0, image.width, 0, 0, image.width, image.height)
        return MotionDetector.gridFromArgb(argb, image.width, image.height)
    }
}
