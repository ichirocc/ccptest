package com.ichirocc.intervalbubblecamera.capture

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector
import com.ichirocc.intervalbubblecamera.NormalizedBox

/** 端末内で人・車などを検出する（MediaPipe Object Detector、EfficientDet-Lite0）。 */
class TargetDetector private constructor(private val detector: ObjectDetector) : AutoCloseable {

    /** 撮影した JPEG から人・車などの枠を、回す前の撮影画像（センサーの向き）の座標で返す。 */
    fun detect(jpeg: ByteArray, rotationDegrees: Int): List<NormalizedBox> {
        val upright = decodeUpright(jpeg, rotationDegrees) ?: return emptyList()
        try {
            val result = detector.detect(BitmapImageBuilder(upright).build())
            return result.detections().map { detection ->
                val box = detection.boundingBox()
                NormalizedBox(
                    left = (box.left / upright.width).toDouble().coerceIn(0.0, 1.0),
                    top = (box.top / upright.height).toDouble().coerceIn(0.0, 1.0),
                    right = (box.right / upright.width).toDouble().coerceIn(0.0, 1.0),
                    bottom = (box.bottom / upright.height).toDouble().coerceIn(0.0, 1.0),
                ).unrotate(rotationDegrees)
            }
        } finally {
            upright.recycle()
        }
    }

    override fun close() = detector.close()

    private fun decodeUpright(jpeg: ByteArray, rotationDegrees: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sampleSize = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sampleSize * 2) >= DETECT_SIZE) sampleSize *= 2
        val decoded = BitmapFactory.decodeByteArray(
            jpeg,
            0,
            jpeg.size,
            BitmapFactory.Options().apply { inSampleSize = sampleSize },
        ) ?: return null
        if (rotationDegrees % 360 == 0) return decoded

        val rotated = Bitmap.createBitmap(
            decoded,
            0,
            0,
            decoded.width,
            decoded.height,
            Matrix().apply { postRotate(rotationDegrees.toFloat()) },
            true,
        )
        if (rotated !== decoded) decoded.recycle()
        return rotated
    }

    companion object {
        private const val TAG = "TargetDetector"
        private const val MODEL_ASSET = "efficientdet_lite0.tflite"
        private const val DETECT_SIZE = 640
        private const val SCORE_THRESHOLD = 0.4f
        private val TARGET_LABELS = listOf("person", "bicycle", "car", "motorcycle", "bus", "truck")

        /** 作れなければ null（その場合は画像の変化だけで判定する）。 */
        fun createOrNull(context: Context): TargetDetector? = runCatching {
            val options = ObjectDetector.ObjectDetectorOptions.builder()
                .setBaseOptions(BaseOptions.builder().setModelAssetPath(MODEL_ASSET).build())
                .setRunningMode(RunningMode.IMAGE)
                .setScoreThreshold(SCORE_THRESHOLD)
                .setMaxResults(10)
                .setCategoryAllowlist(TARGET_LABELS)
                .build()
            TargetDetector(ObjectDetector.createFromOptions(context, options))
        }.onFailure { Log.w(TAG, "Object detector unavailable; using pixel change only", it) }
            .getOrNull()
    }
}
