package com.ichirocc.intervalbubblecamera.capture

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.ichirocc.intervalbubblecamera.LumaFrame
import com.ichirocc.intervalbubblecamera.MotionDetector

/** 撮影した JPEG を動体検知用の小さな輝度画像へ縮小する。 */
object MotionFrameSampler {
    fun fromJpeg(jpeg: ByteArray): LumaFrame? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sampleSize = 1
        while (bounds.outWidth / (sampleSize * 2) >= MotionDetector.GRID_WIDTH * 2 &&
            bounds.outHeight / (sampleSize * 2) >= MotionDetector.GRID_HEIGHT * 2
        ) {
            sampleSize *= 2
        }
        val decoded = BitmapFactory.decodeByteArray(
            jpeg,
            0,
            jpeg.size,
            BitmapFactory.Options().apply { inSampleSize = sampleSize },
        ) ?: return null

        val grid = Bitmap.createScaledBitmap(
            decoded,
            MotionDetector.GRID_WIDTH,
            MotionDetector.GRID_HEIGHT,
            true,
        )
        val argb = IntArray(MotionDetector.GRID_WIDTH * MotionDetector.GRID_HEIGHT)
        grid.getPixels(argb, 0, grid.width, 0, 0, grid.width, grid.height)
        if (grid !== decoded) grid.recycle()
        decoded.recycle()

        return LumaFrame(
            MotionDetector.GRID_WIDTH,
            MotionDetector.GRID_HEIGHT,
            IntArray(argb.size) { MotionDetector.lumaOf(argb[it]) },
        )
    }
}
