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
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.ichirocc.intervalbubblecamera.AppIconColor
import com.ichirocc.intervalbubblecamera.IntervalPolicy
import com.ichirocc.intervalbubblecamera.MainActivity
import com.ichirocc.intervalbubblecamera.R
import com.ichirocc.intervalbubblecamera.overlay.BubbleOverlay
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
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
    private var imageCapture: ImageCapture? = null
    private var captureJob: Job? = null
    private var bubbleOverlay: BubbleOverlay? = null
    private var sessionGeneration = 0
    private var currentIntervalSeconds = IntervalPolicy.DEFAULT_SECONDS
    private var currentLensFacing = LENS_BACK
    private var currentIconColor = AppIconColor.DEFAULT
    private var wakeLock: PowerManager.WakeLock? = null
    private lateinit var orientationListener: OrientationEventListener

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        orientationListener = object : OrientationEventListener(this) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN) return
                imageCapture?.targetRotation = when (orientation) {
                    in 45 until 135 -> Surface.ROTATION_270
                    in 135 until 225 -> Surface.ROTATION_180
                    in 225 until 315 -> Surface.ROTATION_90
                    else -> Surface.ROTATION_0
                }
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
                val lens = intent.getStringExtra(EXTRA_LENS_FACING)
                    ?.takeIf { it == LENS_BACK || it == LENS_FRONT }
                    ?: LENS_BACK
                val iconColor = AppIconColor.fromStorageKey(
                    intent.getStringExtra(EXTRA_ICON_COLOR),
                )
                startCapture(interval, lens, iconColor)
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
        lensFacing: String,
        iconColor: AppIconColor,
    ) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            CaptureStateStore.markCaptureError("カメラ権限がありません。アプリを開いて許可してください。")
            stopSelf()
            return
        }

        currentIntervalSeconds = intervalSeconds
        currentLensFacing = lensFacing
        currentIconColor = iconColor
        sessionGeneration += 1
        val generation = sessionGeneration

        captureJob?.cancel()
        cameraProvider?.unbindAll()
        imageCapture = null
        CaptureStateStore.markStarting(intervalSeconds, lensFacing)

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
                    bindCamera(provider, lensFacing)
                    CaptureStateStore.markRunning(intervalSeconds, lensFacing)
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

    private fun bindCamera(provider: ProcessCameraProvider, requestedLens: String) {
        val requestedSelector = if (requestedLens == LENS_FRONT) {
            CameraSelector.DEFAULT_FRONT_CAMERA
        } else {
            CameraSelector.DEFAULT_BACK_CAMERA
        }
        val selector = when {
            provider.hasCamera(requestedSelector) -> requestedSelector
            provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) -> CameraSelector.DEFAULT_BACK_CAMERA
            else -> CameraSelector.DEFAULT_FRONT_CAMERA
        }

        val capture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setJpegQuality(90)
            .build()

        provider.unbindAll()
        provider.bindToLifecycle(this, selector, capture)
        imageCapture = capture
    }

    private fun startCaptureLoop(generation: Int) {
        captureJob?.cancel()
        captureJob = lifecycleScope.launch {
            while (isActive && generation == sessionGeneration) {
                refreshWakeLock()
                val startedAt = SystemClock.elapsedRealtime()
                when (val result = captureOnePhoto()) {
                    is PhotoResult.Saved -> {
                        CaptureStateStore.markPhotoSaved(result.fileName)
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

    private suspend fun captureOnePhoto(): PhotoResult = suspendCancellableCoroutine { continuation ->
        val capture = imageCapture
        if (capture == null) {
            continuation.resume(PhotoResult.Failed("カメラが準備されていません"))
            return@suspendCancellableCoroutine
        }

        val fileName = "IBC_${FILE_DATE_FORMAT.format(Date())}.jpg"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(
                MediaStore.Images.Media.RELATIVE_PATH,
                "${Environment.DIRECTORY_PICTURES}/$ALBUM_NAME",
            )
        }
        val outputOptions = ImageCapture.OutputFileOptions.Builder(
            contentResolver,
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            values,
        ).build()

        capture.takePicture(
            outputOptions,
            captureExecutor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    if (continuation.isActive) continuation.resume(PhotoResult.Saved(fileName))
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.w(TAG, "Photo capture failed", exception)
                    if (continuation.isActive) {
                        continuation.resume(PhotoResult.Failed(exception.message ?: "不明なエラー"))
                    }
                }
            },
        )
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
        imageCapture = null
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
        imageCapture = null
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
            ?: "${currentIntervalSeconds}秒ごと・${state.photoCount}枚保存"

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
        data class Saved(val fileName: String) : PhotoResult
        data class Failed(val message: String) : PhotoResult
    }

    companion object {
        const val ACTION_START = "com.ichirocc.intervalbubblecamera.action.START"
        const val ACTION_STOP = "com.ichirocc.intervalbubblecamera.action.STOP"
        const val ACTION_UPDATE_ICON_COLOR =
            "com.ichirocc.intervalbubblecamera.action.UPDATE_ICON_COLOR"
        const val EXTRA_INTERVAL_SECONDS = "interval_seconds"
        const val EXTRA_LENS_FACING = "lens_facing"
        const val EXTRA_ICON_COLOR = "icon_color"
        const val LENS_BACK = "back"
        const val LENS_FRONT = "front"

        private const val TAG = "IntervalCaptureService"
        private const val NOTIFICATION_CHANNEL_ID = "interval_capture"
        private const val NOTIFICATION_ID = 1042
        private const val ALBUM_NAME = "IntervalBubbleCamera"
        private const val WAKE_LOCK_TAG = "IntervalBubbleCamera:IntervalCapture"
        private const val WAKE_LOCK_TIMEOUT_MS = 10 * 60 * 1_000L
        private val FILE_DATE_FORMAT = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US)
    }
}
