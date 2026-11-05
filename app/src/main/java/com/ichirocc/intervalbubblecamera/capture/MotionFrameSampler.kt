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
        return BitmapFactory.decodeByteArray(
            jpeg,
            0,
            jpeg.size,
            BitmapFactory.Options().apply { inSampleSize = sampleSize },
        )
    }

    /** 読み込んだ画像から動体検知用の 80×60 の輝度画像を作る（[image] はそのまま残す）。 */
    fun fromBitmap(image: Bitmap): LumaFrame {
        val grid = Bitmap.createScaledBitmap(
            image,
            MotionDetector.GRID_WIDTH,
            MotionDetector.GRID_HEIGHT,
            true,
        )
        val argb = IntArray(MotionDetector.GRID_WIDTH * MotionDetector.GRID_HEIGHT)
        grid.getPixels(argb, 0, grid.width, 0, 0, grid.width, grid.height)
        if (grid !== image) grid.recycle()

        return LumaFrame(
            MotionDetector.GRID_WIDTH,
            MotionDetector.GRID_HEIGHT,
            IntArray(argb.size) { MotionDetector.lumaOf(argb[it]) },
        )
    }
}
