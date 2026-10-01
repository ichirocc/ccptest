package com.ichirocc.intervalbubblecamera.capture

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Environment
import android.os.PowerManager
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import android.view.OrientationEventListener
import android.view.Surface
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.ConcurrentCamera.SingleCameraConfig
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.UseCaseGroup
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.ichirocc.intervalbubblecamera.AppIconColor
import com.ichirocc.intervalbubblecamera.CameraSetDecision
import com.ichirocc.intervalbubblecamera.CameraSetMotion
import com.ichirocc.intervalbubblecamera.IntervalPolicy
import com.ichirocc.intervalbubblecamera.LumaFrame
import com.ichirocc.intervalbubblecamera.MainActivity
import com.ichirocc.intervalbubblecamera.MotionSensitivity
import com.ichirocc.intervalbubblecamera.R
import com.ichirocc.intervalbubblecamera.overlay.BubbleOverlay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.coroutines.resume

class IntervalCaptureService : LifecycleService() {
    private val notificationManager by lazy { getSystemService(NotificationManager::class.java) }
    private val captureExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var cameraProvider: ProcessCameraProvider? = null
    private var cameraEntries: List<CameraEntry> = emptyList()
    private var captureGroups: List<List<String>> = emptyList()
    private var boundCaptures: Map<String, ImageCapture> = emptyMap()
    private var concurrentPairBound = false
    private var targetRotation = Surface.ROTATION_0
    private var captureJob: Job? = null
    private var bubbleOverlay: BubbleOverlay? = null
    private var sessionGeneration = 0
    private var currentIntervalSeconds = IntervalPolicy.DEFAULT_SECONDS
    private var currentIconColor = AppIconColor.DEFAULT
    private var motionSensitivity = MotionSensitivity.DEFAULT
    private val motionReferences = mutableMapOf<String, LumaFrame>()
    private var wakeLock: PowerManager.WakeLock? = null
    private lateinit var orientationListener: OrientationEventListener

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        orientationListener = object : OrientationEventListener(this) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN) return
                targetRotation = when (orientation) {
                    in 45 until 135 -> Surface.ROTATION_270
                    in 135 until 225 -> Surface.ROTATION_180
                    in 225 until 315 -> Surface.ROTATION_90
                    else -> Surface.ROTATION_0
                }
                boundCaptures.values.forEach { it.targetRotation = targetRotation }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        when (intent?.action) {
            ACTION_START -> {
                val interval = IntervalPolicy.clampSeconds(
                    intent.getIntExtra(EXTRA_INTERVAL_SECONDS, IntervalPolicy.DEFAULT_SECONDS),
                )
                val iconColor = AppIconColor.fromStorageKey(
                    intent.getStringExtra(EXTRA_ICON_COLOR),
                )
                val sensitivity = MotionSensitivity.fromStorageKey(
                    intent.getStringExtra(EXTRA_MOTION_SENSITIVITY),
                )
                startCapture(interval, iconColor, sensitivity)
            }

            ACTION_UPDATE_ICON_COLOR -> updateIconColor(
                AppIconColor.fromStorageKey(intent.getStringExtra(EXTRA_ICON_COLOR)),
            )

            ACTION_STOP -> stopCapture("撮影を停止しました。")
            else -> stopSelf(startId)
        }

        return START_NOT_STICKY
    }

    private fun startCapture(
        intervalSeconds: Int,
        iconColor: AppIconColor,
        sensitivity: MotionSensitivity,
    ) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            CaptureStateStore.markCaptureError("カメラ権限がありません。アプリを開いて許可してください。")
            stopSelf()
            return
        }

        currentIntervalSeconds = intervalSeconds
        currentIconColor = iconColor
        motionSensitivity = sensitivity
        motionReferences.clear()
        sessionGeneration += 1
        val generation = sessionGeneration

        captureJob?.cancel()
        cameraProvider?.unbindAll()
        boundCaptures = emptyMap()
        CaptureStateStore.markStarting(intervalSeconds)

        try {
            startAsCameraForegroundService()
            refreshWakeLock()
        } catch (error: RuntimeException) {
            Log.e(TAG, "Unable to enter camera foreground mode", error)
            CaptureStateStore.markCaptureError(
                "撮影サービスを開始できませんでした。アプリを前面にして再試行してください。",
            )
            stopSelf()
            return
        }

        if (!showBubble()) {
            CaptureStateStore.markCaptureError(
                "バブルを表示できませんでした。表示権限を確認してください。",
            )
            stopAfterFatalError()
            return
        }
        orientationListener.enable()

        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener(
            {
                if (generation != sessionGeneration) return@addListener
                runCatching {
                    val provider = providerFuture.get()
                    cameraProvider = provider
                    bindCameras(provider)
                    CaptureStateStore.markRunning(intervalSeconds, runningDetail(intervalSeconds))
                    updateForegroundNotification()
                    startCaptureLoop(generation)
                }.onFailure { error ->
                    Log.e(TAG, "Unable to initialize CameraX", error)
                    CaptureStateStore.markCaptureError(
                        "カメラを開始できませんでした。別のアプリがカメラを使用していないか確認してください。",
                    )
                    updateForegroundNotification("カメラを開始できませんでした")
                    stopAfterFatalError()
                }
            },
            ContextCompat.getMainExecutor(this),
        )
    }

    /**
     * 端末が公開しているカメラを全部使う。前後の 1 組は対応端末なら同時に撮り、
     * 残りのカメラは 1 台ずつ切り替えて撮る（同時に開けるのは最大 2 台のため）。
     */
    private fun bindCameras(provider: ProcessCameraProvider) {
        cameraEntries = enumerateCameras(provider)
        check(cameraEntries.isNotEmpty()) { "No camera available" }
        provider.unbindAll()
        boundCaptures = emptyMap()

        val pair = listOfNotNull(
            cameraEntries.firstOrNull { it.lensFacing == CameraSelector.LENS_FACING_BACK && !it.isPhysical },
            cameraEntries.firstOrNull { it.lensFacing == CameraSelector.LENS_FACING_FRONT && !it.isPhysical },
        ).map { it.key }
        concurrentPairBound = false
        if (pair.size == 2 && supportsFrontBackConcurrent(provider)) {
            try {
                bindGroup(provider, pair)
                concurrentPairBound = true
            } catch (error: RuntimeException) {
                Log.w(TAG, "Concurrent front/back binding failed; switching instead", error)
                provider.unbindAll()
                boundCaptures = emptyMap()
            }
        }

        captureGroups = if (concurrentPairBound) {
            listOf(pair) + cameraEntries.filter { it.key !in pair }.map { listOf(it.key) }
        } else {
            cameraEntries.map { listOf(it.key) }
        }
        if (boundCaptures.isEmpty()) bindGroup(provider, captureGroups.first())
    }

    @OptIn(ExperimentalCamera2Interop::class)
    private fun enumerateCameras(provider: ProcessCameraProvider): List<CameraEntry> {
        val logical = provider.availableCameraInfos
            .filter { it.lensFacing == CameraSelector.LENS_FACING_BACK || it.lensFacing == CameraSelector.LENS_FACING_FRONT }
            .sortedBy { if (it.lensFacing == CameraSelector.LENS_FACING_BACK) 0 else 1 }
        val knownIds = logical.map { Camera2CameraInfo.from(it).cameraId }.toMutableSet()

        return buildList {
            for (info in logical) {
                val logicalId = Camera2CameraInfo.from(info).cameraId
                add(CameraEntry(cameraKey(info.lensFacing, logicalId), info.cameraSelector, info.lensFacing, false))
                if (!info.isLogicalMultiCameraSupported) continue
                // 広角・超広角・望遠などの物理カメラは、単独で公開されていないものだけ追加する。
                for (physical in info.physicalCameraInfos.sortedBy { Camera2CameraInfo.from(it).cameraId }) {
                    val physicalId = Camera2CameraInfo.from(physical).cameraId
                    if (!knownIds.add(physicalId)) continue
                    val selector = CameraSelector.Builder()
                        .addCameraFilter { infos: List<CameraInfo> ->
                            infos.filter { Camera2CameraInfo.from(it).cameraId == logicalId }
                        }
                        .setPhysicalCameraId(physicalId)
                        .build()
                    add(CameraEntry(cameraKey(physical.lensFacing, physicalId), selector, physical.lensFacing, true))
                }
            }
        }
    }

    private fun cameraKey(lensFacing: Int, cameraId: String): String =
        (if (lensFacing == CameraSelector.LENS_FACING_FRONT) LENS_FRONT else LENS_BACK) + cameraId

    private fun supportsFrontBackConcurrent(provider: ProcessCameraProvider): Boolean {
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_CONCURRENT)) return false
        return provider.availableConcurrentCameraInfos.any { combination ->
            combination.map { it.lensFacing }.toSet() ==
                setOf(CameraSelector.LENS_FACING_BACK, CameraSelector.LENS_FACING_FRONT)
        }
    }

    private fun bindGroup(provider: ProcessCameraProvider, keys: List<String>) {
        provider.unbindAll()
        boundCaptures = emptyMap()
        val entries = keys.map { key -> cameraEntries.first { it.key == key } }
        val captures = keys.associateWith { newImageCapture() }
        if (entries.size == 1) {
            provider.bindToLifecycle(this, entries.single().selector, captures.getValue(keys.single()))
        } else {
            provider.bindToLifecycle(
                entries.map { entry ->
                    SingleCameraConfig(
                        entry.selector,
                        UseCaseGroup.Builder().addUseCase(captures.getValue(entry.key)).build(),
                        this,
                    )
                },
            )
        }
        boundCaptures = captures
    }

    private fun newImageCapture(): ImageCapture = ImageCapture.Builder()
        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
        .setJpegQuality(90)
        .setTargetRotation(targetRotation)
        .build()

    private fun runningDetail(intervalSeconds: Int): String {
        val count = cameraEntries.size
        val how = when {
            count == 1 -> "カメラ1台で撮影し"
            concurrentPairBound && count == 2 -> "前後のカメラで同時に撮影し"
            concurrentPairBound -> "カメラ${count}台で撮影し（前後は同時、ほかは順に切り替え）"
            else -> "カメラ${count}台を順に切り替えて撮影し"
        }
        return "${intervalSeconds}秒ごとに$how、どれかで動体があるときだけ全カメラの画像を保存します。"
    }

    private fun startCaptureLoop(generation: Int) {
        captureJob?.cancel()
        captureJob = lifecycleScope.launch {
            while (isActive && generation == sessionGeneration) {
                refreshWakeLock()
                val startedAt = SystemClock.elapsedRealtime()
                val result = captureWithMotionCheck()
                if (generation != sessionGeneration) break
                when (result) {
                    is PhotoResult.Saved -> {
                        CaptureStateStore.markPhotosSaved(result.fileNames)
                        updateForegroundNotification()
                    }

                    is PhotoResult.Baseline -> CaptureStateStore.markMotionBaseline()

                    is PhotoResult.NoMotion -> {
                        CaptureStateStore.markNoMotion(result.changedRatio)
                        updateForegroundNotification()
                    }

                    is PhotoResult.Failed -> {
                        CaptureStateStore.markRecovering(
                            "前回の撮影に失敗しました。次の間隔で再試行します: ${result.message}",
                        )
                        updateForegroundNotification("前回失敗・次回再試行します")
                    }
                }

                val captureDuration = SystemClock.elapsedRealtime() - startedAt
                delay(IntervalPolicy.delayAfterCapture(currentIntervalSeconds, captureDuration))
            }
        }
    }

    /** カメラごとに前回の画像と比べ、どれかで動体があれば全カメラの画像を保存する。 */
    private suspend fun captureWithMotionCheck(): PhotoResult {
        val shots = captureCameraSet()
        val captured = shots.mapNotNull { (lens, shot) ->
            (shot as? InMemoryResult.Captured)?.let { lens to it }
        }.toMap()
        if (captured.isEmpty()) {
            val message = shots.values.filterIsInstance<InMemoryResult.Failed>()
                .firstOrNull()?.message ?: "カメラが準備されていません"
            return PhotoResult.Failed(message)
        }

        val decision = CameraSetMotion.decide(
            previous = motionReferences.toMap(),
            current = captured.mapValues { it.value.frame },
            sensitivity = motionSensitivity,
        )
        captured.forEach { (lens, shot) -> motionReferences[lens] = shot.frame }

        return when (decision) {
            CameraSetDecision.Baseline -> PhotoResult.Baseline
            is CameraSetDecision.NoMotion -> PhotoResult.NoMotion(decision.maxChangedRatio)
            is CameraSetDecision.Motion -> withContext(Dispatchers.IO) {
                runCatching { saveCameraSet(captured) }
                    .getOrElse { error ->
                        Log.w(TAG, "Unable to save motion photos", error)
                        PhotoResult.Failed(error.message ?: "保存に失敗しました")
                    }
            }
        }
    }

    private suspend fun captureCameraSet(): Map<String, InMemoryResult> {
        val provider = cameraProvider
        val results = linkedMapOf<String, InMemoryResult>()
        // 今つながっている組から撮り、切り替えは 1 周につき (組の数 - 1) 回で済ませる。
        val groups = captureGroups.sortedBy { it.toSet() != boundCaptures.keys }
        for (group in groups) {
            if (group.toSet() != boundCaptures.keys) {
                if (provider == null) {
                    group.forEach { results[it] = InMemoryResult.Failed("カメラが準備されていません") }
                    continue
                }
                try {
                    bindGroup(provider, group)
                } catch (error: RuntimeException) {
                    Log.w(TAG, "Unable to switch to cameras $group", error)
                    group.forEach { results[it] = InMemoryResult.Failed("カメラを切り替えられませんでした") }
                    continue
                }
                // 切り替え直後は露出が合っておらず、暗い画像を動体と誤判定するため待つ。
                delay(SWITCH_SETTLE_MS)
            }
            val captures = boundCaptures
            coroutineScope {
                group.map { key -> key to async { captureToMemory(captures.getValue(key)) } }
                    .forEach { (key, pending) -> results[key] = pending.await() }
            }
        }
        return results
    }

    private suspend fun captureToMemory(capture: ImageCapture): InMemoryResult =
        suspendCancellableCoroutine { continuation ->
            capture.takePicture(
                captureExecutor,
                object : ImageCapture.OnImageCapturedCallback() {
                    override fun onCaptureSuccess(image: ImageProxy) {
                        val result = image.use {
                            val buffer = it.planes[0].buffer
                            val jpeg = ByteArray(buffer.remaining()).also { bytes -> buffer.get(bytes) }
                            val frame = MotionFrameSampler.fromJpeg(jpeg)
                            if (frame == null) {
                                InMemoryResult.Failed("画像を解析できませんでした")
                            } else {
                                InMemoryResult.Captured(jpeg, it.imageInfo.rotationDegrees, frame)
                            }
                        }
                        if (continuation.isActive) continuation.resume(result)
                    }

                    override fun onError(exception: ImageCaptureException) {
                        Log.w(TAG, "Photo capture failed", exception)
                        if (continuation.isActive) {
                            continuation.resume(InMemoryResult.Failed(exception.message ?: "不明なエラー"))
                        }
                    }
                },
            )
        }

    private fun saveCameraSet(shots: Map<String, InMemoryResult.Captured>): PhotoResult {
        val stamp = FILE_DATE_FORMAT.format(Date())
        val fileNames = shots.map { (lens, shot) ->
            "IBC_MOTION_${stamp}_$lens.jpg".also { saveJpeg(shot.jpeg, shot.rotationDegrees, it) }
        }
        return PhotoResult.Saved(fileNames)
    }

    private fun saveJpeg(jpeg: ByteArray, rotationDegrees: Int, fileName: String) {
        val tempFile = File.createTempFile("motion_", ".jpg", cacheDir)
        try {
            tempFile.writeBytes(jpeg)
            ExifInterface(tempFile).apply {
                setAttribute(
                    ExifInterface.TAG_ORIENTATION,
                    exifOrientationOf(rotationDegrees).toString(),
                )
                saveAttributes()
            }

            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(
                    MediaStore.Images.Media.RELATIVE_PATH,
                    "${Environment.DIRECTORY_PICTURES}/$ALBUM_NAME",
                )
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: error("保存先を作成できませんでした")
            try {
                contentResolver.openOutputStream(uri)?.use { output ->
                    tempFile.inputStream().use { it.copyTo(output) }
                } ?: error("保存先を開けませんでした")
                contentResolver.update(
                    uri,
                    ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) },
                    null,
                    null,
                )
            } catch (error: Exception) {
                contentResolver.delete(uri, null, null)
                throw error
            }
        } finally {
            tempFile.delete()
        }
    }

    private fun exifOrientationOf(rotationDegrees: Int): Int = when (rotationDegrees) {
        90 -> ExifInterface.ORIENTATION_ROTATE_90
        180 -> ExifInterface.ORIENTATION_ROTATE_180
        270 -> ExifInterface.ORIENTATION_ROTATE_270
        else -> ExifInterface.ORIENTATION_NORMAL
    }

    private fun startAsCameraForegroundService() {
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA,
        )
    }

    private fun refreshWakeLock() {
        val lock = wakeLock ?: getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
            .apply {
                setReferenceCounted(false)
                wakeLock = this
            }

        if (lock.isHeld) runCatching { lock.release() }
        lock.acquire(WAKE_LOCK_TIMEOUT_MS)
    }

    private fun releaseWakeLock() {
        val lock = wakeLock ?: return
        if (lock.isHeld) runCatching { lock.release() }
        wakeLock = null
    }

    private fun showBubble(): Boolean {
        bubbleOverlay?.hide()
        val overlay = BubbleOverlay(this, currentIconColor.iconRes)
        bubbleOverlay = overlay
        return overlay.show()
    }

    private fun updateIconColor(iconColor: AppIconColor) {
        currentIconColor = iconColor
        if (!CaptureStateStore.state.value.isActive) return

        val overlay = bubbleOverlay
        if (overlay == null) {
            if (!showBubble()) Log.w(TAG, "Unable to update the bubble icon color")
        } else {
            overlay.updateIcon(iconColor.iconRes)
        }
    }

    private fun stopAfterFatalError() {
        sessionGeneration += 1
        captureJob?.cancel()
        captureJob = null
        cameraProvider?.unbindAll()
        boundCaptures = emptyMap()
        motionReferences.clear()
        orientationListener.disable()
        releaseWakeLock()
        bubbleOverlay?.hide()
        bubbleOverlay = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun stopCapture(detail: String) {
        sessionGeneration += 1
        captureJob?.cancel()
        captureJob = null
        cameraProvider?.unbindAll()
        boundCaptures = emptyMap()
        motionReferences.clear()
        orientationListener.disable()
        releaseWakeLock()
        bubbleOverlay?.hide()
        bubbleOverlay = null
        CaptureStateStore.markStopped(detail)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notification_channel_description)
            setShowBadge(false)
        }
        notificationManager.createNotificationChannel(channel)
    }

    private fun buildNotification(overrideText: String? = null): Notification {
        val state = CaptureStateStore.state.value
        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, IntervalCaptureService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notificationText = overrideText
            ?: "${currentIntervalSeconds}秒ごとに動体検知・${state.photoCount}枚保存"

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_camera)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(notificationText)
            .setContentIntent(openAppIntent)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .addAction(R.drawable.ic_stop, getString(R.string.notification_stop), stopIntent)
            .build()
    }

    private fun updateForegroundNotification(overrideText: String? = null) {
        notificationManager.notify(NOTIFICATION_ID, buildNotification(overrideText))
    }

    override fun onDestroy() {
        sessionGeneration += 1
        captureJob?.cancel()
        cameraProvider?.unbindAll()
        orientationListener.disable()
        releaseWakeLock()
        bubbleOverlay?.hide()
        captureExecutor.shutdown()
        if (CaptureStateStore.state.value.isActive) {
            CaptureStateStore.markStopped("撮影サービスが終了しました。")
        }
        super.onDestroy()
    }

    private sealed interface PhotoResult {
        data class Saved(val fileNames: List<String>) : PhotoResult
        data class Failed(val message: String) : PhotoResult
        data object Baseline : PhotoResult
        data class NoMotion(val changedRatio: Double) : PhotoResult
    }

    private class CameraEntry(
        val key: String,
        val selector: CameraSelector,
        val lensFacing: Int,
        val isPhysical: Boolean,
    )

    private sealed interface InMemoryResult {
        class Captured(val jpeg: ByteArray, val rotationDegrees: Int, val frame: LumaFrame) : InMemoryResult
        data class Failed(val message: String) : InMemoryResult
    }

    companion object {
        const val ACTION_START = "com.ichirocc.intervalbubblecamera.action.START"
        const val ACTION_STOP = "com.ichirocc.intervalbubblecamera.action.STOP"
        const val ACTION_UPDATE_ICON_COLOR =
            "com.ichirocc.intervalbubblecamera.action.UPDATE_ICON_COLOR"
        const val EXTRA_INTERVAL_SECONDS = "interval_seconds"
        const val EXTRA_ICON_COLOR = "icon_color"
        const val EXTRA_MOTION_SENSITIVITY = "motion_sensitivity"
        const val LENS_BACK = "back"
        const val LENS_FRONT = "front"

        private const val TAG = "IntervalCaptureService"
        private const val NOTIFICATION_CHANNEL_ID = "interval_capture"
        private const val NOTIFICATION_ID = 1042
        private const val ALBUM_NAME = "IntervalBubbleCamera"
        private const val WAKE_LOCK_TAG = "IntervalBubbleCamera:IntervalCapture"
        private const val SWITCH_SETTLE_MS = 800L
        private const val WAKE_LOCK_TIMEOUT_MS = 10 * 60 * 1_000L
        private val FILE_DATE_FORMAT = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US)
    }
}
