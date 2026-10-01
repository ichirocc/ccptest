package com.ichirocc.intervalbubblecamera.capture

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import com.ichirocc.intervalbubblecamera.DetectedTarget
import com.ichirocc.intervalbubblecamera.NormalizedBox
import com.ichirocc.intervalbubblecamera.TargetKind
import com.ichirocc.intervalbubblecamera.TargetTracker

/**
 * 端末内で人（全身・体の一部・手だけ）と車などを検出する。すべて MediaPipe Tasks:
 * - 物体検出（EfficientDet-Lite0）: 全身・大部分が写った人と車など
 * - 姿勢推定（Pose Landmarker lite）: 見えている関節だけで体の一部（脚・足首・つま先を含む）
 * - 手の検出（Hand Landmarker）: 手だけが写っている場合
 * 作れなかった検出器は使わず、残りで検出する。
 */
class TargetDetector private constructor(
    private val objectDetector: ObjectDetector?,
    private val poseLandmarker: PoseLandmarker?,
    private val handLandmarker: HandLandmarker?,
) : AutoCloseable {

    /** 撮影した JPEG から対象を検出し、回す前の撮影画像（センサーの向き）の座標で返す。 */
    fun detect(jpeg: ByteArray, rotationDegrees: Int): List<DetectedTarget> {
        val upright = decodeUpright(jpeg, rotationDegrees) ?: return emptyList()
        try {
            val image = BitmapImageBuilder(upright).build()
            val found = detectObjects(image, upright) + detectBodyParts(image) + detectHands(image)
            return found.map { it.copy(box = it.box.unrotate(rotationDegrees)) }
        } finally {
            upright.recycle()
        }
    }

    override fun close() {
        objectDetector?.close()
        poseLandmarker?.close()
        handLandmarker?.close()
    }

    private fun detectObjects(image: MPImage, bitmap: Bitmap): List<DetectedTarget> {
        val detector = objectDetector ?: return emptyList()
        return runCatching { detector.detect(image).detections() }
            .onFailure { Log.w(TAG, "Object detection failed", it) }
            .getOrDefault(emptyList())
            .map { detection ->
                val box = detection.boundingBox()
                val label = detection.categories().firstOrNull()?.categoryName()
                DetectedTarget(
                    NormalizedBox(
                        (box.left / bitmap.width).toDouble().coerceIn(0.0, 1.0),
                        (box.top / bitmap.height).toDouble().coerceIn(0.0, 1.0),
                        (box.right / bitmap.width).toDouble().coerceIn(0.0, 1.0),
                        (box.bottom / bitmap.height).toDouble().coerceIn(0.0, 1.0),
                    ),
                    if (label == PERSON_LABEL) TargetKind.PERSON else TargetKind.VEHICLE,
                )
            }
    }

    private fun detectBodyParts(image: MPImage): List<DetectedTarget> {
        val landmarker = poseLandmarker ?: return emptyList()
        return runCatching { landmarker.detect(image).landmarks() }
            .onFailure { Log.w(TAG, "Pose detection failed", it) }
            .getOrDefault(emptyList())
            .mapNotNull { pose ->
                TargetTracker.boxFromLandmarks(
                    points = pose.map { it.x().toDouble() to it.y().toDouble() },
                    visible = pose.map { it.isVisible() },
                )?.let { DetectedTarget(it, TargetKind.BODY_PART) }
            }
    }

    private fun detectHands(image: MPImage): List<DetectedTarget> {
        val landmarker = handLandmarker ?: return emptyList()
        return runCatching { landmarker.detect(image).landmarks() }
            .onFailure { Log.w(TAG, "Hand detection failed", it) }
            .getOrDefault(emptyList())
            .mapNotNull { hand ->
                TargetTracker.boxFromLandmarks(
                    points = hand.map { it.x().toDouble() to it.y().toDouble() },
                    visible = hand.map { true },
                )?.let { DetectedTarget(it, TargetKind.HAND) }
            }
    }

    private fun NormalizedLandmark.isVisible(): Boolean =
        visibility().orElse(1f) >= MIN_LANDMARK_VISIBILITY && presence().orElse(1f) >= MIN_LANDMARK_VISIBILITY

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
        private const val DETECT_SIZE = 640
        private const val PERSON_LABEL = "person"
        private const val MIN_LANDMARK_VISIBILITY = 0.5f
        private val TARGET_LABELS = listOf(PERSON_LABEL, "bicycle", "car", "motorcycle", "bus", "truck")

        /** 1 つも作れなければ null（その場合は画像の変化だけで判定する）。 */
        fun createOrNull(context: Context): TargetDetector? {
            val objectDetector = create("object detector") {
                ObjectDetector.createFromOptions(
                    context,
                    ObjectDetector.ObjectDetectorOptions.builder()
                        .setBaseOptions(BaseOptions.builder().setModelAssetPath("efficientdet_lite0.tflite").build())
                        .setRunningMode(RunningMode.IMAGE)
                        .setScoreThreshold(0.4f)
                        .setMaxResults(10)
                        .setCategoryAllowlist(TARGET_LABELS)
                        .build(),
                )
            }
            val poseLandmarker = create("pose landmarker") {
                PoseLandmarker.createFromOptions(
                    context,
                    PoseLandmarker.PoseLandmarkerOptions.builder()
                        .setBaseOptions(BaseOptions.builder().setModelAssetPath("pose_landmarker_lite.task").build())
                        .setRunningMode(RunningMode.IMAGE)
                        .setNumPoses(3)
                        .setMinPoseDetectionConfidence(0.5f)
                        .build(),
                )
            }
            val handLandmarker = create("hand landmarker") {
                HandLandmarker.createFromOptions(
                    context,
                    HandLandmarker.HandLandmarkerOptions.builder()
                        .setBaseOptions(BaseOptions.builder().setModelAssetPath("hand_landmarker.task").build())
                        .setRunningMode(RunningMode.IMAGE)
                        .setNumHands(4)
                        .setMinHandDetectionConfidence(0.5f)
                        .build(),
                )
            }
            if (objectDetector == null && poseLandmarker == null && handLandmarker == null) return null
            return TargetDetector(objectDetector, poseLandmarker, handLandmarker)
        }

        private fun <T> create(name: String, block: () -> T): T? = runCatching(block)
            .onFailure { Log.w(TAG, "Unable to create $name", it) }
            .getOrNull()
    }
}
