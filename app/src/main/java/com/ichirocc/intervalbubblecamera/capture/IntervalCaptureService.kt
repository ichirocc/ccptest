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
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import android.view.OrientationEventListener
import android.view.Surface
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceOrientedMeteringPointFactory
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.extensions.ExtensionMode
import androidx.camera.extensions.ExtensionsManager
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayInputStream
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.google.common.util.concurrent.ListenableFuture
import com.ichirocc.intervalbubblecamera.AppIconColor
import com.ichirocc.intervalbubblecamera.CameraMovement
import com.ichirocc.intervalbubblecamera.CameraCandidate
import com.ichirocc.intervalbubblecamera.CameraDetectionTuning
import com.ichirocc.intervalbubblecamera.CameraSettling
import com.ichirocc.intervalbubblecamera.ThreeAState
import com.ichirocc.intervalbubblecamera.DetectionTuning
import com.ichirocc.intervalbubblecamera.DogfoodRecord
import com.ichirocc.intervalbubblecamera.CameraFeatures
import com.ichirocc.intervalbubblecamera.CaptureFallback
import com.ichirocc.intervalbubblecamera.CapturePreset
import com.ichirocc.intervalbubblecamera.TiledDetection
import com.ichirocc.intervalbubblecamera.ColorThumb
import com.ichirocc.intervalbubblecamera.ColorEvidence
import com.ichirocc.intervalbubblecamera.CapturePolicy
import com.ichirocc.intervalbubblecamera.CaptureSetup
import com.ichirocc.intervalbubblecamera.CameraSelectionPolicy
import com.ichirocc.intervalbubblecamera.CameraSetDecision
import com.ichirocc.intervalbubblecamera.CameraSetMotion
import com.ichirocc.intervalbubblecamera.DetectedTarget
import com.ichirocc.intervalbubblecamera.DetectionLevel
import com.ichirocc.intervalbubblecamera.DeviceProfile
import com.ichirocc.intervalbubblecamera.FrameAlignment
import com.ichirocc.intervalbubblecamera.FrameShift
import com.ichirocc.intervalbubblecamera.HeatLevel
import com.ichirocc.intervalbubblecamera.IntervalPolicy
import com.ichirocc.intervalbubblecamera.JpegOrientation
import com.ichirocc.intervalbubblecamera.LumaFrame
import com.ichirocc.intervalbubblecamera.MainActivity
import com.ichirocc.intervalbubblecamera.MotionCenter
import com.ichirocc.intervalbubblecamera.MotionResult
import com.ichirocc.intervalbubblecamera.LowLightEnhancer
import com.ichirocc.intervalbubblecamera.MotionThreshold
import com.ichirocc.intervalbubblecamera.NightMotion
import com.ichirocc.intervalbubblecamera.DetectionSkipping
import com.ichirocc.intervalbubblecamera.GyroBlur
import com.ichirocc.intervalbubblecamera.GyroHistory
import com.ichirocc.intervalbubblecamera.MovingTargets
import com.ichirocc.intervalbubblecamera.NightModeSwitch
import com.ichirocc.intervalbubblecamera.PhoneMotion
import com.ichirocc.intervalbubblecamera.R
import com.ichirocc.intervalbubblecamera.TargetTracker
import com.ichirocc.intervalbubblecamera.SteadyMoment
import com.ichirocc.intervalbubblecamera.WalkingDetector
import com.ichirocc.intervalbubblecamera.ThermalPolicy
import com.ichirocc.intervalbubblecamera.overlay.BubbleOverlay
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.coroutines.resume

class IntervalCaptureService : LifecycleService() {
    private val notificationManager by lazy { getSystemService(NotificationManager::class.java) }
    private val powerManager by lazy { getSystemService(PowerManager::class.java) }
    private val deviceProfile by lazy {
        DeviceProfile.detect(Build.MODEL, Build.VERSION.MEDIA_PERFORMANCE_CLASS)
            .also { Log.i(TAG, "Device ${Build.MODEL} -> ${it.label} profile") }
    }
    private val captureExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val detectionExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val detectionDispatcher = detectionExecutor.asCoroutineDispatcher()
    private var cameraProvider: ProcessCameraProvider? = null
    private var cameraEntries: List<CameraEntry> = emptyList()
    private var captureGroups: List<List<String>> = emptyList()
    private var boundCaptures: Map<String, ImageCapture> = emptyMap()
    private var boundCameras: Map<String, Camera> = emptyMap()
    private var extensionsManager: ExtensionsManager? = null
    private val nightModeSwitch = NightModeSwitch()
    private var latestLux: Float? = null
    private var boundNight = false
    private val lightListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            latestLux = event.values.firstOrNull()
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    /** スマホの今の向き（単位クォータニオン [w, x, y, z]）。センサーが無ければ null。 */
    @Volatile private var latestOrientation: FloatArray? = null

    /** [latestOrientation] を受け取った時刻（SystemClock.elapsedRealtimeNanos 基準）。 */
    @Volatile private var latestOrientationNanos = 0L
    private val orientationAtFrame = mutableMapOf<String, FloatArray>()

    /** スマホの今の重力の向き（加速度 [x, y, z]）。ジャイロの無い端末でも傾きと平置きが分かる。 */
    @Volatile private var latestGravity: FloatArray? = null
    @Volatile private var latestGravityNanos = 0L
    private val gravityAtFrame = mutableMapOf<String, FloatArray>()

    /** 撮影している人が歩いているか（加速度の揺れから）。センサーのイベントも判定も main スレッド。 */
    private val walkingDetector = WalkingDetector()
    /** ジャイロで測った今の回転の速さ（rad/s）と受け取った時刻。歩きながらぶれの少ない瞬間を待つのに使う。 */
    @Volatile private var latestAngularSpeed = 0.0
    @Volatile private var latestAngularSpeedNanos = 0L
    private val gyroListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val (x, y, z) = Triple(event.values[0], event.values[1], event.values[2])
            latestAngularSpeed = Math.sqrt((x * x + y * y + z * z).toDouble())
            gyroHistory.add(event.timestamp, latestAngularSpeed)
            latestAngularSpeedNanos = event.timestamp
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    private val walkListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            walkingDetector.add(event.timestamp, event.values[0], event.values[1], event.values[2])
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }
    private var gravityIsRaw = false
    private val gravityListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val previous = latestGravity
            latestGravity = if (gravityIsRaw && previous != null) {
                // 重力センサーが無く加速度をそのまま使うときは、細かな揺れをならして重力だけを残す。
                FloatArray(3) { previous[it] + GRAVITY_SMOOTHING * (event.values[it] - previous[it]) }
            } else {
                event.values.copyOf(3)
            }
            latestGravityNanos = event.timestamp
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    /** この回の判定で「スマホが動いた」とみなしたカメラ。 */
    private val phoneMovedCameras = mutableSetOf<String>()
    private val rotationListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            latestOrientation = FloatArray(4).also { SensorManager.getQuaternionFromVector(it, event.values) }
            latestOrientationNanos = event.timestamp
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }
    private var targetRotation = Surface.ROTATION_0
    private var captureJob: Job? = null

    /** 動体があった回の保存。撮影は止めずに裏で保存し、次の保存はこれが終わるまで待つ。 */
    private var saveJob: Job? = null
    private val saveExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val saveDispatcher = saveExecutor.asCoroutineDispatcher()

    /** カメラごとに、今ピントを合わせてある位置と合わせた時刻（つなぎ直したら消す）。 */
    private val focusedAt = mutableMapOf<String, Pair<MotionCenter, Long>>()
    private var bubbleOverlay: BubbleOverlay? = null
    private var sessionGeneration = 0
    private var currentIntervalSeconds = IntervalPolicy.DEFAULT_SECONDS
    private var currentIconColor = AppIconColor.DEFAULT
    private var motionThreshold = MotionThreshold.DEFAULT
    private val motionReferences = mutableMapOf<String, LumaFrame>()

    /** カメラごとの、前回の縮めた色つきの画像（「外・遠く」で遠くの小さな人が動いたかを確かめる。[ColorEvidence]）。 */
    private val colorThumbs = mutableMapOf<String, ColorThumb>()
    private val lastMotionCenters = mutableMapOf<String, MotionCenter>()

    /** 直近 2 秒の回転の速さ（撮影中の手ぶれを見積もる）。センサーのイベントも読み出しも main スレッド。 */
    private val gyroHistory = GyroHistory()

    /** カメラごとの、撮影の質を上げる機能（光学式手ぶれ補正など）。 */
    private val cameraFeatures = mutableMapOf<String, CameraFeatures>()

    /** Ultra HDR（明暗の幅が広い写真）で撮れるカメラ。 */
    private val ultraHdrCameras = mutableSetOf<String>()

    /** つなげなかった・撮れなかったカメラで、撮り方を何段外したか（Ultra HDR → 質を上げる設定 → 全画素）。 */
    private val captureFallback = CaptureFallback()

    /** 今つながっているカメラの撮り方。 */
    private var boundSetup = CaptureSetup.PLAIN
    private val boundFullResolution: Boolean get() = boundSetup.fullResolution
    private val boundEnhanced: Boolean get() = boundSetup.enhanced

    /** カメラごとの 焦点距離 ÷ センサーの幅（手ぶれで像がどれだけ流れるかの計算に使う）。 */
    private val focalOverSensorWidth = mutableMapOf<String, Double>()

    /**
     * カメラごとの、つないでから届いた最新の露出・ホワイトバランス・ピントの状態。カメラのスレッドから書き、
     * main スレッドで読む。つなぎ直したら消す（前のカメラの状態で「落ち着いた」と判断しないため）。
     */
    private val threeAStates = java.util.concurrent.ConcurrentHashMap<String, ThreeAState>()

    /** 実機で使って確かめるための記録（[DogfoodLog]）。何周目かの番号も記録に入れる。 */
    private val dogfood by lazy { DogfoodLog(DogfoodLog.directory(this)) }
    private var cycleNumber = 0

    /** カメラごとの人の検出の調整（画角で決める。[CameraDetectionTuning]）。 */
    private val detectionTunings = mutableMapOf<String, DetectionTuning>()

    /** カメラごとに、続けて人の検出を省いた回数（[DetectionSkipping]）。 */
    private val skippedDetections = mutableMapOf<String, Int>()
    private val targetTrackers = mutableMapOf<String, TargetTracker>()
    private var targetDetector: TargetDetector? = null
    // 検出用のスレッドと main スレッドの両方から読み書きする。
    @Volatile private var targetDetectorTried = false

    /** 本体の温度の段階（1 周ごとに測り直す）。 */
    private var heatLevel = HeatLevel.NORMAL

    /** 直近に測れた温度の見込み（測れない・呼び出しが早すぎて NaN のときは前回の値を使う）。 */
    private var lastHeadroom: Float? = null
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
                val threshold = MotionThreshold.clamped(
                    intent.getIntExtra(EXTRA_MOTION_PIXEL_THRESHOLD, MotionThreshold.DEFAULT.pixelThreshold),
                    intent.getIntExtra(EXTRA_MOTION_AREA_PERMILLE, MotionThreshold.DEFAULT.areaPermille),
                )
                dogfood.enabled = intent.getBooleanExtra(EXTRA_DOGFOOD_LOG, true)
                startCapture(interval, iconColor, threshold)
            }

            ACTION_UPDATE_ICON_COLOR -> updateIconColor(
                AppIconColor.fromStorageKey(intent.getStringExtra(EXTRA_ICON_COLOR)),
            )

            ACTION_STOP -> stopCapture("とるのを やめました。")
            else -> stopSelf(startId)
        }

        return START_NOT_STICKY
    }

    private fun startCapture(
        intervalSeconds: Int,
        iconColor: AppIconColor,
        threshold: MotionThreshold,
    ) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            CaptureStateStore.markCaptureError("カメラの 許可が ありません。アプリを ひらいて 許可してね。")
            stopSelf()
            return
        }

        currentIntervalSeconds = intervalSeconds
        currentIconColor = iconColor
        motionThreshold = threshold
        // 撮影中にもう一度開始されたときは、センサーを一度外してから付け直す（二重に受け取らず、古い値も残さない）。
        stopSensors()
        focusedAt.clear()
        cycleNumber = 0
        logEvent(
            "start",
            "",
            "model=${Build.MODEL} profile=${deviceProfile.name} interval=$intervalSeconds " +
                "pixel=${threshold.pixelThreshold} area_permille=${threshold.areaPermille}",
        )
        motionReferences.clear()
        colorThumbs.clear()
        orientationAtFrame.clear()
        gravityAtFrame.clear()
        lastMotionCenters.clear()
        skippedDetections.clear()
        captureFallback.clear()
        targetTrackers.clear()
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
                "とりはじめることが できませんでした。アプリを ひらいたまま、もういちど スタートしてね。",
            )
            stopSelf()
            return
        }

        if (!showBubble()) {
            CaptureStateStore.markCaptureError(
                "バブルを 出せませんでした。「バブルを 出す」の 許可を たしかめてね。",
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
                    val extensionsFuture = ExtensionsManager.getInstanceAsync(this, provider)
                    extensionsFuture.addListener(
                        {
                            if (generation != sessionGeneration) return@addListener
                            extensionsManager = runCatching { extensionsFuture.get() }
                                .onFailure { Log.w(TAG, "Camera extensions unavailable", it) }
                                .getOrNull()
                            runCatching {
                                bindCameras(provider)
                                startSensors()
                                CaptureStateStore.markRunning(intervalSeconds, runningDetail(intervalSeconds))
                                CaptureStateStore.updateCameraSummary(cameraSummary())
                                publishDetectorSummary()
                                startCaptureLoop(generation)
                            }.onFailure { error -> failToStartCamera(error) }
                        },
                        ContextCompat.getMainExecutor(this),
                    )
                }.onFailure { error ->
                    Log.e(TAG, "Unable to initialize CameraX", error)
                    CaptureStateStore.markCaptureError(
                        "カメラを 使えませんでした。ほかのアプリで カメラを 使っていないか、たしかめてね。",
                    )
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
    private fun failToStartCamera(error: Throwable) {
        Log.e(TAG, "Unable to start cameras", error)
        CaptureStateStore.markCaptureError(
            "カメラを 使えませんでした。ほかのアプリで カメラを 使っていないか、たしかめてね。",
        )
        stopAfterFatalError()
    }

    private fun bindCameras(provider: ProcessCameraProvider) {
        cameraEntries = enumerateCameras(provider)
        check(cameraEntries.isNotEmpty()) { "No camera available" }
        provider.unbindAll()
        boundCaptures = emptyMap()

        nightModeSwitch.reset()
        // 前後同時撮影は使わない（Pixel 10 Pro XL では 1920×1440 に制限され、露出も合いにくい）。
        // 全カメラを 1 台ずつ切り替えて、各カメラの最大解像度で撮る。
        captureGroups = cameraEntries.map { listOf(it.key) }
        bindGroup(provider, captureGroups.first())
    }

    @OptIn(ExperimentalCamera2Interop::class)
    private fun enumerateCameras(provider: ProcessCameraProvider): List<CameraEntry> {
        val logical = provider.availableCameraInfos
            .filter { it.lensFacing == CameraSelector.LENS_FACING_BACK || it.lensFacing == CameraSelector.LENS_FACING_FRONT }
            .sortedBy { if (it.lensFacing == CameraSelector.LENS_FACING_BACK) 0 else 1 }
        val knownIds = logical.map { Camera2CameraInfo.from(it).cameraId }.toMutableSet()
        val candidates = mutableListOf<CameraCandidate>()

        val all = buildList {
            for (info in logical) {
                val logicalId = Camera2CameraInfo.from(info).cameraId
                val logicalKey = cameraKey(info.lensFacing, logicalId)
                add(CameraEntry(logicalKey, info.cameraSelector, info.lensFacing, false, nightSelectorFor(info.cameraSelector)))
                candidates.add(candidateOf(logicalKey, null, info))
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
                    val physicalKey = cameraKey(physical.lensFacing, physicalId)
                    add(CameraEntry(physicalKey, selector, physical.lensFacing, true, null))
                    candidates.add(candidateOf(physicalKey, logicalKey, physical))
                }
            }
        }

        val selected = CameraSelectionPolicy.select(candidates).toSet()
        Log.i(TAG, "Cameras: ${candidates.joinToString { it.key }} -> using $selected")
        return all.filter { it.key in selected }.ifEmpty { all }
    }

    /** 夜景モード（メーカーのカメラ拡張）に対応していれば、それを使う CameraSelector。 */
    private fun nightSelectorFor(base: CameraSelector): CameraSelector? {
        val manager = extensionsManager ?: return null
        return runCatching {
            if (manager.isExtensionAvailable(base, ExtensionMode.NIGHT)) {
                manager.getExtensionEnabledCameraSelector(base, ExtensionMode.NIGHT)
            } else {
                null
            }
        }.onFailure { Log.w(TAG, "Night extension check failed", it) }.getOrNull()
    }

    @OptIn(ExperimentalCamera2Interop::class)
    private fun candidateOf(key: String, logicalKey: String?, info: CameraInfo): CameraCandidate {
        val camera2 = Camera2CameraInfo.from(info)
        val pixelArray = camera2.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
        val capabilities = camera2.getCameraCharacteristic(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
        val focalLengths = camera2.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
        val sensorSize = camera2.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        val ultraHdr = runCatching {
            ImageCapture.getImageCaptureCapabilities(info).supportedOutputFormats
                .contains(ImageCapture.OUTPUT_FORMAT_JPEG_ULTRA_HDR)
        }.getOrDefault(false)
        if (ultraHdr) ultraHdrCameras.add(key) else ultraHdrCameras.remove(key)
        cameraFeatures[key] = CameraFeatures(
            opticalStabilization = camera2.getCameraCharacteristic(
                CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION,
            )?.contains(CameraCharacteristics.LENS_OPTICAL_STABILIZATION_MODE_ON) == true,
            highQualityNoiseReduction = camera2.getCameraCharacteristic(
                CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES,
            )?.contains(CameraCharacteristics.NOISE_REDUCTION_MODE_HIGH_QUALITY) == true,
            highQualityEdge = camera2.getCameraCharacteristic(
                CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES,
            )?.contains(CameraCharacteristics.EDGE_MODE_HIGH_QUALITY) == true,
        )
        val focal = focalLengths?.firstOrNull()
        if (focal != null && sensorSize != null && sensorSize.width > 0f) {
            focalOverSensorWidth[key] = focal.toDouble() / sensorSize.width
        }
        detectionTunings[key] = CameraDetectionTuning.forCamera(
            focalOverSensorWidth[key],
            front = info.lensFacing == CameraSelector.LENS_FACING_FRONT,
        )
        return CameraCandidate(
            key = key,
            logicalKey = logicalKey,
            pixelCount = pixelArray?.let { it.width.toLong() * it.height } ?: Long.MAX_VALUE,
            isMonochrome = capabilities?.contains(
                CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MONOCHROME,
            ) == true,
            focalLengths = focalLengths?.toList() ?: emptyList(),
        )
    }

    private fun cameraKey(lensFacing: Int, cameraId: String): String =
        (if (lensFacing == CameraSelector.LENS_FACING_FRONT) LENS_FRONT else LENS_BACK) + cameraId

    private fun bindGroup(provider: ProcessCameraProvider, keys: List<String>) {
        provider.unbindAll()
        focusedAt.clear()
        boundCaptures = emptyMap()
        boundCameras = emptyMap()
        val entries = keys.map { key -> cameraEntries.first { it.key == key } }
        val night = wantsNight(keys)
        val wanted = wantedSetup(keys)
        var level = captureFallback.level(keys, cycleNumber)
        while (true) {
            val setup = CapturePolicy.setupAt(wanted, level)
            try {
                bindCaptures(provider, keys, entries, night, setup)
                return
            } catch (error: RuntimeException) {
                // この撮り方ではつなげなかった。全部をやめずに 1 つだけ外してつなぎ直す（どれを外したかを記録する）。
                val next = CapturePolicy.nextLevel(wanted, level) ?: throw error
                val dropped = CapturePolicy.setupAt(wanted, next)
                Log.w(TAG, "Unable to bind $keys with $setup; trying $dropped", error)
                logEvent(
                    "bind_fallback",
                    keys.joinToString("/"),
                    "failed=${describe(setup)} next=${describe(dropped)} ${error.javaClass.simpleName}: ${error.message}",
                )
                captureFallback.bindFailed(keys, next)
                level = next
                provider.unbindAll()
            }
        }
    }

    private fun describe(setup: CaptureSetup): String =
        "full_resolution=${setup.fullResolution}/enhanced=${setup.enhanced}/ultra_hdr=${setup.ultraHdr}"

    private fun bindCaptures(
        provider: ProcessCameraProvider,
        keys: List<String>,
        entries: List<CameraEntry>,
        night: Boolean,
        setup: CaptureSetup,
    ) {
        keys.forEach { threeAStates.remove(it) }
        val captures = keys.associateWith { newImageCapture(it, setup, reportState = !night) }
        val cameras = when {
            night -> {
                // 夜景モードは露出を合わせるために映像の流れが要るので、画面に出さないプレビューも一緒につなぐ。
                val entry = entries.single()
                listOf(
                    provider.bindToLifecycle(
                        this,
                        entry.nightSelector!!,
                        newHiddenPreview(),
                        captures.getValue(entry.key),
                    ),
                )
            }

            else -> entries.map { entry ->
                provider.bindToLifecycle(this, entry.selector, captures.getValue(entry.key))
            }
        }
        boundCaptures = captures
        boundCameras = keys.zip(cameras).toMap()
        boundNight = night
        boundSetup = setup
        if (!setup.plain) Log.i(TAG, "Bound $keys ($setup)")
        logEvent(
            "bind",
            keys.joinToString("/"),
            "full_resolution=${setup.fullResolution} enhanced=${setup.enhanced} night=$night " +
                "ultra_hdr=${setup.ultraHdr} heat=$heatLevel",
        )
    }

    /** カメラ [keys] で望む撮り方（外す前）。Ultra HDR は質を上げる設定のときだけ、対応しているカメラで使う。 */
    private fun wantedSetup(keys: List<String>): CaptureSetup {
        val night = wantsNight(keys)
        val enhanced = CapturePolicy.useEnhancedCapture(night)
        return CaptureSetup(
            fullResolution = CapturePolicy.useFullResolution(deviceProfile, heatLevel, night),
            enhanced = enhanced,
            ultraHdr = enhanced && keys.all { it in ultraHdrCameras },
        )
    }

    /** カメラ [keys] を今つなぐ撮り方（外した段を差し引いたもの）。 */
    private fun wantsSetup(keys: List<String>): CaptureSetup =
        CapturePolicy.setupAt(wantedSetup(keys), captureFallback.level(keys, cycleNumber))

    private fun wantsNight(keys: List<String>): Boolean =
        nightModeSwitch.night && keys.size == 1 && cameraEntries.first { it.key == keys.single() }.nightSelector != null

    /** 画面に表示しないプレビュー（夜景モードの露出合わせ用）。 */
    private fun newHiddenPreview(): Preview = Preview.Builder().build().also { preview ->
        preview.setSurfaceProvider(ContextCompat.getMainExecutor(this)) { request ->
            val texture = SurfaceTexture(false).apply {
                setDefaultBufferSize(request.resolution.width, request.resolution.height)
            }
            val surface = Surface(texture)
            request.provideSurface(surface, ContextCompat.getMainExecutor(this)) {
                surface.release()
                texture.release()
            }
        }
    }

    private fun startSensors() {
        val sensorManager = getSystemService(SensorManager::class.java) ?: return
        val light = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT)
        if (light == null) {
            Log.w(TAG, "No light sensor; night mode stays off")
        } else {
            sensorManager.registerListener(lightListener, light, SensorManager.SENSOR_DELAY_NORMAL)
        }
        // 地磁気を使わない向きのセンサー（ジャイロ＋加速度）。手持ちのぶれを測る。
        // ジャイロの無い端末の向き（地磁気・加速度から計算）は止めていても数度ぶれ、毎回「スマホが動いた」と
        // 判定して何も保存されなくなるため使わない。その場合は画像の変化だけで判定する。
        val rotation = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
            ?.let { sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR) }
        if (rotation == null) {
            Log.w(TAG, "No gyroscope rotation sensor; phone movement is judged from the image only")
        } else {
            sensorManager.registerListener(rotationListener, rotation, SensorManager.SENSOR_DELAY_GAME)
        }
        // 重力の向き。ジャイロの無い端末でも、傾きの変化と平らに置いているかが分かる。
        val gravity = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY)
        gravityIsRaw = gravity == null
        val gravitySensor = gravity ?: sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (gravitySensor == null) {
            Log.w(TAG, "No gravity sensor")
        } else {
            sensorManager.registerListener(gravityListener, gravitySensor, SensorManager.SENSOR_DELAY_UI)
        }
        // 撮影している人が歩いているかを見る（1 歩ごとの揺れを拾えるよう、毎秒 50 回ほど受け取る）。
        // 歩きながら撮るときに、スマホの揺れが止まった瞬間を待つ。
        sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)?.let {
            sensorManager.registerListener(gyroListener, it, SensorManager.SENSOR_DELAY_GAME)
        }
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
            sensorManager.registerListener(walkListener, it, SensorManager.SENSOR_DELAY_GAME)
        } ?: Log.w(TAG, "No accelerometer; walking is not detected")
    }

    private fun stopSensors() {
        getSystemService(SensorManager::class.java)?.let {
            it.unregisterListener(lightListener)
            it.unregisterListener(rotationListener)
            it.unregisterListener(gravityListener)
            it.unregisterListener(walkListener)
            it.unregisterListener(gyroListener)
        }
        latestGravity = null
        walkingDetector.clear()
        latestAngularSpeedNanos = 0L
        gyroHistory.clear()
        latestGravityNanos = 0L
        gravityAtFrame.clear()
        latestLux = null
        latestOrientation = null
        latestOrientationNanos = 0L
        orientationAtFrame.clear()
    }

    /** 今の重力の向き。センサーが無い、または値が古いときは null。 */
    private fun currentGravity(): FloatArray? {
        val gravity = latestGravity ?: return null
        val age = SystemClock.elapsedRealtimeNanos() - latestGravityNanos
        return if (age in 0..ORIENTATION_MAX_AGE_NANOS) gravity else null
    }

    /** 今のスマホの向き。センサーが無い、または値が古い（届かなくなった）ときは null。 */
    private fun currentOrientation(): FloatArray? {
        val orientation = latestOrientation ?: return null
        val age = SystemClock.elapsedRealtimeNanos() - latestOrientationNanos
        return if (age in 0..ORIENTATION_MAX_AGE_NANOS) orientation else null
    }

    /**
     * 照明の明るさで夜景モードと普通のモードを自動で切り替える。切り替えたら、夜景に対応するカメラの
     * 比較用画像を捨てる（明るさが大きく変わり、動体と誤判定するため）。
     */
    private fun updateNightMode() {
        val lux = latestLux ?: return
        if (!nightModeSwitch.update(lux)) return
        val night = nightModeSwitch.night
        Log.i(TAG, "Lighting ${lux}lx -> ${if (night) "night" else "normal"} mode")
        val nightCameras = cameraEntries.filter { it.nightSelector != null }.map { it.key }
        nightCameras.forEach { motionReferences.remove(it) }
        CaptureStateStore.updateCameraSummary(cameraSummary())
    }

    /**
     * カメラ [key] の撮影の設定。
     * - [fullResolution]: センサーの全画素で撮る（高画素のセンサーは、ふだん 4 画素を 1 つにまとめて撮られる）。
     * - [enhanced]: 遅れの少ない撮影（ZSL。カメラが流し続けている映像から、撮った瞬間の 1 枚を取り出すので
     *   シャッターの遅れがほぼ無い。対応していないカメラではふつうの撮影になる）と、そのカメラが対応していれば
     *   光学式手ぶれ補正・高品質のノイズ除去・高品質の輪郭の処理。対応していれば Ultra HDR（明暗の幅が広い写真。
     *   ふつうの JPEG として読めて、対応した画面では明るい所がより明るく見える）でも撮る。
     */
    @OptIn(ExperimentalCamera2Interop::class)
    private fun newImageCapture(key: String, setup: CaptureSetup, reportState: Boolean): ImageCapture {
        val fullResolution = setup.fullResolution
        val enhanced = setup.enhanced
        val builder = ImageCapture.Builder()
            .setCaptureMode(
                if (enhanced) ImageCapture.CAPTURE_MODE_ZERO_SHUTTER_LAG else ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY,
            )
            .setJpegQuality(deviceProfile.jpegQuality)
            .setFlashMode(ImageCapture.FLASH_MODE_OFF)
            .setTargetRotation(targetRotation)
        if (fullResolution) {
            builder.setResolutionSelector(
                ResolutionSelector.Builder()
                    .setAllowedResolutionMode(ResolutionSelector.PREFER_HIGHER_RESOLUTION_OVER_CAPTURE_RATE)
                    .setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY)
                    .build(),
            )
        }
        if (setup.ultraHdr && key in ultraHdrCameras) builder.setOutputFormat(ImageCapture.OUTPUT_FORMAT_JPEG_ULTRA_HDR)
        if (reportState) {
            // カメラが毎コマ知らせる露出・ホワイトバランス・ピントの状態を受け取る（切り替え直後に落ち着くのを待つ）。
            // 夜景モード（メーカーのカメラ拡張）では受け取れないので付けない。
            Camera2Interop.Extender(builder).setSessionCaptureCallback(
                object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        result: TotalCaptureResult,
                    ) {
                        threeAStates[key] = ThreeAState(
                            ae = result.get(CaptureResult.CONTROL_AE_STATE),
                            awb = result.get(CaptureResult.CONTROL_AWB_STATE),
                            af = result.get(CaptureResult.CONTROL_AF_STATE),
                        )
                    }
                },
            )
        }
        val features = cameraFeatures[key]
        if (enhanced && features != null) {
            val extender = Camera2Interop.Extender(builder)
            if (features.opticalStabilization) {
                extender.setCaptureRequestOption(
                    CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                    CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON,
                )
            }
            if (features.highQualityNoiseReduction) {
                extender.setCaptureRequestOption(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY)
            }
            if (features.highQualityEdge) {
                extender.setCaptureRequestOption(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_HIGH_QUALITY)
            }
        }
        return builder.build()
    }

    private fun cameraSummary(): String {
        val nightCameras = cameraEntries.filter { it.nightSelector != null }.map { it.key }
        val lighting = when {
            nightCameras.isEmpty() -> "夜景モード非対応"
            nightModeSwitch.night -> "夜景モード（${nightCameras.joinToString()}）"
            else -> "普通のモード（暗いと${nightCameras.joinToString()}は夜景モード）"
        }
        val hdr = cameraEntries.map { it.key }.filter { it in ultraHdrCameras && captureFallback.level(it, cycleNumber) == 0 }
        val hdrLine = if (hdr.isEmpty()) "" else "\nUltra HDR: ${hdr.joinToString()}"
        val tuningLine = "\n人の検出: " + cameraEntries.joinToString { "${it.key} ${tuningOf(it.key).lensClass.label}" }
        return "カメラ${cameraEntries.size}台（1台ずつ切替）: ${cameraEntries.joinToString { it.key }}\n$lighting$hdrLine$tuningLine"
    }

    private fun runningDetail(intervalSeconds: Int): String {
        val count = cameraEntries.size
        val how = when {
            count == 1 -> "カメラ1台で とって"
            else -> "カメラ${count}台で じゅんばんに とって"
        }
        return "${intervalSeconds}秒ごとに$how、動きが あったときだけ 写真を のこします。"
    }

    private fun startCaptureLoop(generation: Int) {
        captureJob?.cancel()
        captureJob = lifecycleScope.launch {
            while (isActive && generation == sessionGeneration) {
                refreshWakeLock()
                updateHeatLevel()
                val startedAt = SystemClock.elapsedRealtime()
                val result = captureWithMotionCheck()
                if (generation != sessionGeneration) break
                when (result) {
                    is PhotoResult.Motion -> saveInBackground(generation, result.shots)
                    is PhotoResult.Baseline -> CaptureStateStore.markMotionBaseline()
                    is PhotoResult.NoMotion -> CaptureStateStore.markNoMotion(result.changedRatio, result.phoneMoved)

                    is PhotoResult.Failed -> {
                        Log.w(TAG, "Capture cycle failed: ${result.message}")
                        CaptureStateStore.markRecovering(
                            // くわしい理由（result.message）は logcat に残し、画面には分かりやすく出す。
                            "さっきは うまく とれませんでした。つぎに もういちど とります。",
                        )
                    }
                }

                val captureDuration = SystemClock.elapsedRealtime() - startedAt
                // 本体が熱いときは間隔を延ばし、撮影が長引いても 1 周ごとに必ず休んで冷ます。
                delay(ThermalPolicy.delayAfterCapture(currentIntervalSeconds, captureDuration, heatLevel))
            }
        }
    }

    /**
     * カメラごとに前回の画像と比べ、動体を検知したカメラはその位置にピントを合わせて撮り直す。
     * どれかで動体があれば全カメラの画像を保存する。
     */
    private suspend fun captureWithMotionCheck(): PhotoResult {
        updateNightMode()
        cycleNumber += 1
        val traces = linkedMapOf<String, JudgeTrace>()
        val shots = linkedMapOf<String, InMemoryResult.Captured>()
        val judgements = linkedMapOf<String, MotionResult?>()
        var firstFailure: String? = null
        phoneMovedCameras.clear()

        val failed = mutableListOf<String>()
        forEachCameraGroup { key, shot ->
            if (shot is InMemoryResult.Failed) {
                Log.w(TAG, "Camera $key failed: ${shot.message}")
                logEvent("capture_failed", key, shot.message)
                failed.add(key)
                if (firstFailure == null) firstFailure = shot.message
                return@forEachCameraGroup
            }
            var captured = shot as InMemoryResult.Captured
            val orientation = currentOrientation()
            val rotation = orientationAtFrame[key]?.let { previous ->
                orientation?.let { CameraMovement.rotationDegrees(previous, it) }
            }
            val gravity = currentGravity()
            val tilt = gravityAtFrame[key]?.let { previous ->
                gravity?.let { CameraMovement.tiltDegrees(previous, it) }
            }
            val lyingFlat = gravity?.let { CameraMovement.isLyingFlat(it) } ?: false
            val holderWalking = walkingDetector.isWalking(SystemClock.elapsedRealtimeNanos())
            val trace = JudgeTrace(rotation = rotation, tilt = tilt, walking = holderWalking)
            traces[key] = trace
            val shotTaken = captured
            val thumb = if (tiledDetectionEnabled()) captured.image?.let { colorThumbOf(it) } else null
            val judgement = motionReferences[key]?.let {
                judgeMotion(key, it, captured, rotation, tilt, lyingFlat, holderWalking, thumb, trace)
            }
            if (judgement == null) trace.reason = "baseline"
            captured.image = null
            val center = judgement?.center
            if (center != null) {
                lastMotionCenters[key] = center
                val retaken = refocusAndRetake(key, center)
                trace.refocused = retaken != null
                captured = retaken ?: captured
                captured.image = null
            }
            // 保存しそうな写真がいつもよりぶれていたら、止まった瞬間を待って撮り直し、くっきりしたほうを使う。
            trace.blur = blurOf(key, captured)
            if (judgement?.motionDetected == true) {
                val sharper = retakeIfBlurry(key, captured)
                trace.blurRetaken = sharper !== captured
                captured = sharper
            }
            motionReferences[key] = captured.frame
            // 撮り直した回は、縮めた色つきの画像が次に比べる画像と合わないので捨てる。
            if (thumb != null && captured === shotTaken) colorThumbs[key] = thumb else colorThumbs.remove(key)
            // 向きが測れなかった回は前回の向きも捨てる（古い向きと比べて誤判定しないため）。
            if (orientation != null) orientationAtFrame[key] = orientation else orientationAtFrame.remove(key)
            if (gravity != null) gravityAtFrame[key] = gravity.copyOf() else gravityAtFrame.remove(key)
            shots[key] = captured
            judgements[key] = judgement
        }
        recordFailures(failed)
        if (shots.isEmpty()) return PhotoResult.Failed(firstFailure ?: "カメラが準備されていません")

        val decision = CameraSetMotion.decide(judgements)
        val saved = decision is CameraSetDecision.Motion
        shots.forEach { (key, shot) -> logShot(key, shot, judgements[key], traces.getValue(key), saved) }
        return when (decision) {
            CameraSetDecision.Baseline -> PhotoResult.Baseline
            is CameraSetDecision.NoMotion -> PhotoResult.NoMotion(
                decision.maxChangedRatio,
                phoneMoved = phoneMovedCameras.isNotEmpty(),
            )
            is CameraSetDecision.Motion -> PhotoResult.Motion(shots.mapValues { it.value.jpeg to it.value.rotationDegrees })
        }
    }

    /**
     * 動体があった回の画像を裏で保存し、撮影はそのまま続ける（6 枚の書き込みを待たずに次の周へ進む）。
     * 前の保存が終わっていなければ待つ（保存待ちの画像がたまってメモリが足りなくならないように）。
     */
    private suspend fun saveInBackground(generation: Int, shots: Map<String, Pair<ByteArray, Int>>) {
        saveJob?.join()
        saveJob = lifecycleScope.launch {
            // 撮影を止めても、書きかけの写真は書き終えて枚数に数える（止めると lifecycleScope が取り消されるため、
            // 取り消されない文脈で保存する）。
            val result = withContext(NonCancellable + saveDispatcher) {
                runCatching { saveCameraSet(shots) }
                    .onFailure { Log.w(TAG, "Unable to save motion photos", it) }
            }
            if (generation != sessionGeneration) {
                // 停止後に保存し終えた分も枚数には数える。
                result.onSuccess { CaptureStateStore.markSavedAfterStop(it) }
                return@launch
            }
            result.onSuccess { logEvent("saved", "", it.joinToString(" ")) }
                .onFailure { logEvent("save_failed", "", "${it.javaClass.simpleName}: ${it.message}") }
            result.fold(
                onSuccess = { CaptureStateStore.markPhotosSaved(it) },
                onFailure = { error ->
                    CaptureStateStore.markRecovering("写真を のこせませんでした。しまう場所が いっぱいでないか、たしかめてね。")
                },
            )
        }
    }

    /**
     * 全カメラで毎回、端末内の検出で人（全身・体の一部・手だけ）や車などを探して追跡し、
     * 画像の変化と重なるか位置が動いた対象があれば動体とする。
     * 動いた対象があればその中心をピントの位置にする。検出を使えない端末では変化だけで判定する。
     * スマホが動いた回は、人（全身・体の一部・手）が写っていればそれだけで動体とする。
     */
    private suspend fun judgeMotion(
        key: String,
        reference: LumaFrame,
        shot: InMemoryResult.Captured,
        phoneRotationDegrees: Double?,
        phoneTiltDegrees: Double?,
        lyingFlat: Boolean,
        holderWalking: Boolean,
        thumb: ColorThumb?,
        trace: JudgeTrace,
    ): MotionResult {
        // 暗い場面では雑音だけで変わったように見えるので、前回との差から明るさごとの雑音を見積もり、
        // 雑音より十分大きい変化だけを数える（ELD の雑音のモデル。NightMotion）。
        val threshold = NightMotion.thresholdFor(reference, shot.frame, motionThreshold)
        threshold.noise?.let { Log.d(TAG, "Dark frame for $key; noise $it") }
        // ずらさずに比べた変わった画素（MotionDetector.compare と同じ）。止まっている回は、そのまま使い回す。
        val rawMask = FrameAlignment.alignedMask(reference, shot.frame, FrameShift.NONE, threshold)
        val rawMotion = FrameAlignment.resultOf(rawMask, shot.frame.width, shot.frame.height, FrameShift.NONE, threshold)
        val tracker = targetTrackers.getOrPut(key) { TargetTracker(minMove = tuningOf(key).minMove) }
        val phoneMotion = CameraMovement.judge(phoneRotationDegrees, rawMotion.changedRatio, phoneTiltDegrees, lyingFlat)
        trace.phoneMotion = phoneMotion.name
        trace.rawChangedRatio = rawMotion.changedRatio
        trace.noisePixelThreshold = threshold.noise?.let {
            threshold.pixelThresholdAt(NightMotion.meanLuma(reference).toInt())
        }
        // スマホが動いていて、撮影している人も歩いている（体ごと動いている）回は、写っているものに関係なく保存する。
        // 歩きながらの撮影では景色ごと変わり、重ね直しも人の検出も当てにならないため。
        val walkingShot = holderWalking && phoneMotion == PhoneMotion.MOVED
        fun finish(result: MotionResult): MotionResult {
            trace.motion = result.motionDetected
            if (!walkingShot || result.motionDetected) return result
            Log.i(TAG, "Phone and its holder are moving for $key; saving")
            trace.reason = "walking"
            trace.motion = true
            return result.copy(motionDetected = true)
        }
        // スマホが動いた回は画面全体がずれるので、前回の画像を同じだけずらして重ねてから比べる（手ぶれ補正と同じ）。
        // 重ねられれば、スマホが止まっているときと同じように判定し、追跡中の位置も同じだけずらして続ける。
        val compensation = if (phoneMotion == PhoneMotion.MOVED) compensateShift(reference, shot.frame) else null
        // スマホが止まっている扱い（ジャイロで 2° 未満）でも、手ぶれで画面が 1〜2 格子ずれることがあるので、
        // その範囲だけ重ね直して比べる（実機の写真で確かめると、1〜2° の手ぶれで変わる画素が 20〜40% から 0.5% 以下に
        // 減った）。追跡中の位置はずらさない（確かめると、ずらしても結果は変わらなかった）。
        val steadyShift = if (phoneMotion != PhoneMotion.MOVED) {
            FrameAlignment.estimate(reference, shot.frame, FrameAlignment.STEADY_MAX_SHIFT)
        } else {
            FrameShift.NONE
        }
        val comparedShift = compensation?.shift ?: steadyShift
        val comparedThreshold = compensation?.threshold ?: threshold
        // 重ね直した比べ方の変わった画素は、人を探す範囲を決めるのにも使うので 1 回だけ求める。
        val steadyMask = when {
            compensation != null -> null
            steadyShift == FrameShift.NONE -> rawMask
            else -> FrameAlignment.alignedMask(reference, shot.frame, steadyShift, threshold)
        }
        val pixelMotion = compensation?.motion ?: if (steadyShift != FrameShift.NONE) {
            FrameAlignment.resultOf(steadyMask!!, shot.frame.width, shot.frame.height, steadyShift, threshold)
        } else {
            rawMotion
        }
        // 区画ごとに明るさをそろえて比べた変化。照明が一部だけ変わったときの変化は消え、人の動きや、すぐ近くを
        // 覆ったものは残る。人に変化が重なったかと、近くを覆ったかの判定に使う。
        val unevenMask = FrameAlignment.alignedMask(reference, shot.frame, comparedShift, comparedThreshold, FrameAlignment.BLOCK_SIZE)
        val unevenRatio = FrameAlignment.changedRatio(unevenMask, shot.frame.width, shot.frame.height, comparedShift)
        trace.changedRatio = pixelMotion.changedRatio
        // 重ね直したずれ（スマホが動いた回は "moved:"、止まっている扱いの手ぶれは "steady:"）と、明るさをそろえた変化。
        trace.compensatedShift = when {
            compensation != null -> "moved:${comparedShift.dx}/${comparedShift.dy}"
            steadyShift != FrameShift.NONE -> "steady:${comparedShift.dx}/${comparedShift.dy}"
            else -> ""
        } + " uneven=" + String.format(java.util.Locale.US, "%.4f", unevenRatio)
        // 重ねきれないほど動いた回は、画像の変化や位置の移動では判定しない。ただし人（全身・体の一部・手）が
        // 写っていれば保存する。追跡中の位置も画面ごとずれるため、追跡はやり直す。
        val phoneMoved = phoneMotion == PhoneMotion.MOVED && compensation == null
        // スマホが止まっていて画面の大部分が変わったら、すぐ近くを人が覆った（スマホの上を人がまたいだなど）と
        // みなし、人を検出できなくても保存する。真下や真横から見た人は検出できないことが多いため。
        val closeSubject = CameraMovement.closeSubject(phoneMotion, pixelMotion.changedRatio, unevenRatio)
        if (compensation != null) {
            Log.i(TAG, "Phone moved for $key; compensated ${compensation.shift} (change=${pixelMotion.changedRatio})")
            tracker.shift(
                compensation.shift.normalizedX(shot.frame.width),
                compensation.shift.normalizedY(shot.frame.height),
            )
        } else if (phoneMoved) {
            Log.i(
                TAG,
                "Phone moved for $key (rotation=$phoneRotationDegrees, tilt=$phoneTiltDegrees, change=${rawMotion.changedRatio})",
            )
            tracker.clear()
            phoneMovedCameras.add(key)
        }
        val withoutDetection = if (phoneMoved) pixelMotion.copy(motionDetected = false, center = null) else pixelMotion
        // 人の検出は全カメラで毎回行う（画像の変化が閾値未満でも、位置の移動で動きを拾う）。
        // 本体が熱いときは検出を止め、画像の変化だけで判定する（強制終了や性能低下を防ぐ）。
        if (heatLevel.detection == DetectionLevel.NONE) {
            trace.reason = if (withoutDetection.motionDetected) "pixel_change(no_detection:heat)" else "no_change(no_detection:heat)"
            return finish(withoutDetection)
        }
        // 正立以外の向きでも探すのは、温度が普通で、画像が変わった・追跡中の対象がいる・スマホが動いた回だけ
        // （何も変わっていない回まで 4 回検出すると発熱が大きいため）。
        val allOrientations = heatLevel.detection == DetectionLevel.ALL_ORIENTATIONS &&
            (pixelMotion.motionDetected || phoneMoved || tracker.hasTracks)
        // 画像が変わっておらず、追跡中の人もいない回は、重い検出を省く（Reducto）。続けて省くのは数回まで。
        val nothingChanged = !pixelMotion.motionDetected && !tracker.hasTracks &&
            phoneMotion != PhoneMotion.MOVED && !closeSubject && !walkingShot
        val skipped = skippedDetections[key] ?: 0
        if (nothingChanged && DetectionSkipping.shouldSkip(skipped, heatLevel)) {
            skippedDetections[key] = skipped + 1
            trace.detectionSkipped = true
            trace.reason = "skipped_no_change"
            return finish(withoutDetection)
        }
        skippedDetections[key] = 0

        // GPU の検出器は作ったスレッドでしか使えないため、作成も検出も専用の 1 本のスレッドで行う。
        return finish(withContext(detectionDispatcher) {
            val detector = loadTargetDetector() ?: run {
                trace.reason = if (withoutDetection.motionDetected) "pixel_change(no_detector)" else "no_change(no_detector)"
                return@withContext withoutDetection
            }
            trace.detectionSkipped = false
            runCatching {
                val image = shot.image ?: return@runCatching null
                // 暗い画像は、人の検出に渡す前だけ明るくする（Zero-DCE の曲線。保存する写真は変えない）。
                // やや熱いときは小さくしてから検出する（ApproxDet。速く、熱を出さない）。
                val dark = NightMotion.meanLuma(shot.frame) < LowLightEnhancer.DARK_MEAN
                val detectImage = imageForDetection(image, dark, heatLevel.detectScale)
                var detections = try {
                    detector.detect(detectImage, shot.rotationDegrees, allOrientations)
                } finally {
                    if (detectImage !== image) detectImage.recycle()
                }
                trace.detections = detections.size
                trace.confidentPersons = detections.count { it.kind.isPerson && it.confident }
                if (phoneMoved) {
                    // 追跡をこの回の位置から始め直し、写っている人がいればその枠を返す。
                    tracker.update(detections, shareOfChange = { 0.0 })
                    val person = TargetTracker.personInFrame(detections)
                    trace.targetKind = person?.kind?.name
                    return@runCatching person?.box
                }
                val mask = compensation?.mask ?: steadyMask!!
                if (pixelMotion.motionDetected) {
                    val before = detections.size
                    detections = withZoomedDetections(key, detector, shot, mask, detections, allOrientations)
                    // 切り出して探し直して、見つけたものが増えたか（まとめた後の数で比べる）。
                    trace.zoomed = detections.size != before
                    trace.detections = detections.size
                    trace.confidentPersons = detections.count { it.kind.isPerson && it.confident }
                }
                // 「外・遠く」では、画面を 4 つの区画に分けて撮った写真から切り出し、遠くの小さな人も探す。
                val tiled = tiledDetectionEnabled()
                if (tiled) {
                    val found = TiledDetection.regions.flatMap { region ->
                        detector.detectRegion(shot.jpeg, region, shot.rotationDegrees, TiledDetection.LONG_SIDE, allOrientations = false)
                    }
                    trace.tiledDetections = found.size
                    if (found.isNotEmpty()) {
                        detections = TargetTracker.fuse(detections + found)
                        trace.detections = detections.size
                        trace.confidentPersons = detections.count { it.kind.isPerson && it.confident }
                    }
                }
                // 遠くの小さな人は明るさの格子では変化が薄まるので、「外・遠く」では縮めた色つきの画像の差で確かめる。
                val previousThumb = colorThumbs[key]
                val colorMask = if (tiled && thumb != null && previousThumb != null) {
                    ColorEvidence.changedMask(previousThumb, thumb, comparedShift, shot.frame.width, shot.frame.height)
                } else {
                    null
                }
                // 画面の変化が閾値（大きさ・広さ）に届いたときだけ、人の枠の中の変化を動きとして数える。
                // 届かなくても、人の位置が前回から動き、枠の中の画素も変わっていれば動きとする（ゆっくりした動き・遠くの人）。
                val tracked = tracker.update(
                    detections,
                    changedInBox = { box ->
                        if (colorMask != null && thumb != null) {
                            ColorEvidence.movedInside(colorMask, thumb.width, thumb.height, box)
                        } else {
                            MovingTargets.changedInside(unevenMask, shot.frame.width, shot.frame.height, box)
                        }
                    },
                    shareOfChange = { box ->
                        // 変化がその人の枠に集まっているときだけ、人に変化が重なったとみなす（照明の変化や手ぶれは画面じゅうに
                        // 広がる）。区画ごとに明るさをそろえた変化で見る。
                        val w = shot.frame.width
                        val h = shot.frame.height
                        if (pixelMotion.motionDetected && MovingTargets.changeConcentratedInside(unevenMask, w, h, box)) {
                            MovingTargets.shareOfChangeInside(unevenMask, w, h, box)
                        } else {
                            0.0
                        }
                    },
                )
                val target = TargetTracker.focusTarget(tracked)
                trace.targetKind = target?.kind?.name
                trace.boxJumpOnly = target == null && tracked.any { it.boxJumpOnly }
                target?.box
            }.fold(
                onSuccess = { box ->
                    if (box == null && closeSubject) {
                        Log.i(TAG, "Something close covered $key (change=${pixelMotion.changedRatio}); saving")
                        trace.reason = "close_subject"
                        pixelMotion
                    } else if (box == null) {
                        trace.reason = when {
                            phoneMoved -> "phone_moved"
                            // 人の枠は跳んだが、枠の中の画素は変わっていなかった（実機でこの決まりが効いた回を数える）。
                            trace.boxJumpOnly -> "no_moving_target(box_jump)"
                            else -> "no_moving_target"
                        }
                        pixelMotion.copy(motionDetected = false, center = null)
                    } else {
                        if (phoneMoved) Log.i(TAG, "Phone moved for $key but a person is in frame; saving")
                        trace.reason = (if (phoneMoved) "phone_moved_person:" else "moving_target:") + trace.targetKind
                        pixelMotion.copy(motionDetected = true, center = box.center)
                    }
                },
                onFailure = { error ->
                    Log.w(TAG, "Target detection failed; using pixel change only", error)
                    trace.reason = "detection_failed"
                    withoutDetection
                },
            )
        })
    }

    /**
     * 人の検出に渡す画像。[scale] が 1 未満なら縮め、[dark] なら明るくする。どちらも無ければ [image] そのもの
     * （呼び出した側は、[image] と違う画像が返ったら使い終わったら手放す）。
     */
    private fun imageForDetection(image: Bitmap, dark: Boolean, scale: Double): Bitmap {
        val scaled = if (scale < 1.0) {
            Bitmap.createScaledBitmap(
                image,
                (image.width * scale).toInt().coerceAtLeast(1),
                (image.height * scale).toInt().coerceAtLeast(1),
                true,
            )
        } else {
            image
        }
        if (!dark) return scaled
        val width = scaled.width
        val height = scaled.height
        val pixels = IntArray(width * height)
        scaled.getPixels(pixels, 0, width, 0, 0, width, height)
        if (scaled !== image) scaled.recycle()
        return Bitmap.createBitmap(LowLightEnhancer.enhance(pixels), width, height, Bitmap.Config.ARGB_8888)
    }

    /** スマホの動きを打ち消した比べ方の結果。 */
    private class ShiftCompensation(
        val shift: FrameShift,
        val motion: MotionResult,
        val mask: BooleanArray,
        val threshold: MotionThreshold,
    )

    /**
     * 画面全体のずれを求めて打ち消す。打ち消しきれない（大きく動いた・その場で回した）ときは null。
     * 暗い場面の雑音も、ずれを打ち消して重ねた画素どうしで見積もり直す（ずれたままの見積もりは景色の模様の
     * 差まで雑音に数えて大きすぎ、閾値が上がって何も動体にならなくなるため）。
     */
    private fun compensateShift(reference: LumaFrame, frame: LumaFrame): ShiftCompensation? {
        val shift = FrameAlignment.estimate(reference, frame)
        val threshold = NightMotion.thresholdFor(reference, frame, motionThreshold, shift)
        val mask = FrameAlignment.alignedMask(reference, frame, shift, threshold)
        val motion = FrameAlignment.resultOf(mask, frame.width, frame.height, shift, threshold)
        if (!FrameAlignment.aligned(motion)) return null
        return ShiftCompensation(shift, motion, mask, threshold)
    }

    /**
     * 画面が変わったのに、その変化を説明する人（確からしい検出）が見つからなかったら、変化した辺りを
     * 撮影画像から高い解像度で切り出して探し直し、見つけたものを [detections] とまとめて返す（SAHI）。
     * 遠くの小さな人を見つけるため。検出が 1 回増えるので、変化があった回だけ行う。
     */
    private fun withZoomedDetections(
        key: String,
        detector: TargetDetector,
        shot: InMemoryResult.Captured,
        mask: BooleanArray,
        detections: List<DetectedTarget>,
        allOrientations: Boolean,
    ): List<DetectedTarget> {
        val width = shot.frame.width
        val height = shot.frame.height
        val explained = detections.any {
            it.kind.isPerson && it.confident &&
                MovingTargets.shareOfChangeInside(mask, width, height, it.box) >= MovingTargets.MIN_SHARE_OF_CHANGE
        }
        if (explained) return detections
        val tuning = tuningOf(key)
        val region = MovingTargets.changedRegion(mask, width, height, tuning.zoomMinRegion) ?: return detections
        val zoomed = detector.detectRegion(shot.jpeg, region, shot.rotationDegrees, detectSizeOf(key), allOrientations)
        if (zoomed.isEmpty()) return detections
        Log.i(TAG, "Zoomed detection found ${zoomed.size} target(s) in $region")
        return TargetTracker.fuse(detections + zoomed)
    }

    /** 区画に分けて人を探すか（「外・遠く」の撮り方で、本体が熱くないとき）。 */
    private fun tiledDetectionEnabled(): Boolean =
        TiledDetection.enabled(CapturePreset.matching(currentIntervalSeconds, motionThreshold), heatLevel)

    /** 人の検出用の縮小画像から、色を比べるための小さな画像を作る。 */
    private fun colorThumbOf(image: Bitmap): ColorThumb {
        val width = ColorEvidence.WIDTH
        val height = (image.height.toLong() * width / image.width).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(image, width, height, true)
        val pixels = IntArray(width * height)
        scaled.getPixels(pixels, 0, width, 0, 0, width, height)
        if (scaled !== image) scaled.recycle()
        return ColorThumb(width, height, pixels)
    }

    private fun tuningOf(key: String): DetectionTuning = detectionTunings[key] ?: CameraDetectionTuning.WIDE

    /** カメラ [key] で人の検出に渡す画像の長辺（機種ごとの大きさ × カメラの画角による倍率）。 */
    private fun detectSizeOf(key: String): Int = (deviceProfile.detectSize * tuningOf(key).detectSizeFactor).toInt()

    private fun publishDetectorSummary() {
        val detector = when {
            !targetDetectorTried -> null
            else -> "${deviceProfile.label}向け設定・" + (targetDetector?.summary ?: "検出なし（画像の変化だけで判定）")
        }
        CaptureStateStore.updateDetectorSummary(listOfNotNull(detector, ThermalPolicy.summary(heatLevel)).joinToString("\n"))
    }

    /** 本体の温度を測り直し、段階が変わったら画面の表示も変える。 */
    private fun updateHeatLevel() {
        val headroom = runCatching { powerManager.getThermalHeadroom(HEADROOM_FORECAST_SECONDS) }
            .getOrNull()
            ?.takeUnless { it.isNaN() }
        if (headroom != null) lastHeadroom = headroom
        val level = ThermalPolicy.level(powerManager.currentThermalStatus, lastHeadroom, heatLevel)
        if (level == heatLevel) return
        Log.i(TAG, "Heat level ${heatLevel} -> $level (status=${powerManager.currentThermalStatus}, headroom=$lastHeadroom)")
        heatLevel = level
        logEvent("heat", "", "level=$level headroom=$lastHeadroom")
        publishDetectorSummary()
    }

    private fun loadTargetDetector(): TargetDetector? {
        if (!targetDetectorTried) {
            targetDetectorTried = true
            targetDetector = TargetDetector.createOrNull(this, deviceProfile)
            publishDetectorSummary()
        }
        return targetDetector
    }

    private fun recordFailures(failed: List<String>) {
        CaptureStateStore.updateFailedCameras(failed)
    }

    /** 全カメラを組ごとに撮り、撮れた順に [onShot] へ渡す（呼び出し中はその組がつながっている）。 */
    private suspend fun forEachCameraGroup(onShot: suspend (key: String, shot: InMemoryResult) -> Unit) {
        val provider = cameraProvider
        // 今つながっている組から撮り、切り替えは 1 周につき (組の数 - 1) 回で済ませる。
        val groups = captureGroups.sortedBy { !isBound(it) }
        for (group in groups) {
            if (!isBound(group)) {
                val failure = when {
                    provider == null -> "カメラが準備されていません"
                    else -> try {
                        bindGroup(provider, group)
                        null
                    } catch (error: RuntimeException) {
                        Log.w(TAG, "Unable to switch to cameras $group", error)
                        "カメラを切り替えられませんでした"
                    }
                }
                if (failure != null) {
                    group.forEach { onShot(it, InMemoryResult.Failed(failure)) }
                    continue
                }
                // 切り替え直後は露出・ホワイトバランス・ピントが合っておらず、暗い画像や色の違う画像を動体と
                // 誤判定するため、カメラごとにそれらが落ち着くまで待つ。
                waitUntilSettled(group)
            }
            val captures = boundCaptures
            val taken = coroutineScope {
                group.map { key ->
                    key to async {
                        // 保存されうる画像は必ずピントを合わせてから撮る（最後に動いた位置、無ければ中央）。
                        val alreadyFocused = focusedAt[key]
                        if (focusAt(key, lastMotionCenters[key] ?: FRAME_CENTER) && focusedAt[key] != alreadyFocused) {
                            waitForFramesAfterFocus()
                        }
                        // スマホがほぼ止まった瞬間を待って撮る（ぶれを減らす。ジャイロで回転を測る）。歩きながらなら 1 歩ごとの
                        // 揺れの合間まで、手持ちなら手のふるえの合間まで待つ。止まっていれば待たない。
                        val walking = walkingDetector.isWalking(SystemClock.elapsedRealtimeNanos())
                        waitForSteadyMoment(if (walking) SteadyMoment.MAX_WAIT_MS else SteadyMoment.MAX_WAIT_HANDHELD_MS)
                        val first = captureToMemory(key, captures.getValue(key))
                        if (first is InMemoryResult.Captured) return@async first
                        // 切り替え直後などで失敗したら、もう少し待って 1 回だけ撮り直す。
                        Log.w(TAG, "Retrying camera $key after: ${(first as InMemoryResult.Failed).message}")
                        delay(deviceProfile.switchSettleMs)
                        val second = captureToMemory(key, captures.getValue(key))
                        if (second is InMemoryResult.Failed && !boundSetup.plain) {
                            // この撮り方では撮れなかった。しばらくのあいだ 1 つ外して撮り、あとでもう一度元の撮り方で試す。
                            val wanted = wantedSetup(listOf(key))
                            val level = captureFallback.level(key, cycleNumber)
                            CapturePolicy.nextLevel(wanted, level)?.let { next ->
                                Log.w(TAG, "Camera $key failed twice with $boundSetup; using level $next for a while")
                                logEvent(
                                    "capture_fallback",
                                    key,
                                    "failed=${describe(boundSetup)} next=${describe(CapturePolicy.setupAt(wanted, next))} " +
                                        second.message,
                                )
                                captureFallback.captureFailed(key, cycleNumber, next)
                            }
                        }
                        second
                    }
                }.map { (key, pending) -> key to pending.await() }
            }
            taken.forEach { (key, shot) -> onShot(key, shot) }
        }
    }

    /**
     * [captured] の撮影中に手ぶれで像が流れていれば（ジャイロで見積もる。[GyroBlur]）、スマホがほぼ止まる
     * 瞬間を待って 1 回だけ撮り直し、ぶれの小さいほうを返す。見積もれなければそのまま。
     */
    private suspend fun retakeIfBlurry(key: String, captured: InMemoryResult.Captured): InMemoryResult.Captured {
        val blur = blurOf(key, captured) ?: return captured
        if (!GyroBlur.isBlurry(blur)) return captured
        waitForSteadyMoment(SteadyMoment.MAX_WAIT_MS)
        val capture = boundCaptures[key] ?: return captured
        val retake = captureToMemory(key, capture) as? InMemoryResult.Captured ?: return captured
        retake.image = null
        val retakeBlur = blurOf(key, retake)
        Log.i(TAG, "Blurry shot for $key (blur=$blur of the width); retake blur=$retakeBlur")
        return if (retakeBlur != null && retakeBlur < blur) retake else captured
    }

    /**
     * 撮影中（センサーの露出の始まりから露出時間のあいだ）の手ぶれによる像の流れの、画像の幅に対する割合。
     * 露出時間・撮影の時刻・ジャイロの記録・焦点距離のどれかが分からなければ null。
     */
    private fun blurOf(key: String, shot: InMemoryResult.Captured): Double? {
        val exposure = shot.exposureSeconds ?: return null
        val ratio = focalOverSensorWidth[key] ?: return null
        val start = shot.sensorTimestampNanos
        val end = start + (exposure * 1e9).toLong()
        val speed = gyroHistory.maxBetween(start - GyroBlur.SEARCH_MARGIN_NANOS, end + GyroBlur.SEARCH_MARGIN_NANOS)
            ?: return null
        return GyroBlur.blurFraction(speed, exposure, ratio)
    }

    /**
     * つないだカメラ [keys] の露出・ホワイトバランス・ピントが落ち着くまで待つ（[CameraSettling]）。最長は機種ごとの
     * 待ち時間の 2 倍。状態が届かない夜景モードでは、これまでどおり機種ごとの待ち時間だけ待つ。
     */
    private suspend fun waitUntilSettled(keys: List<String>) {
        if (boundNight) {
            delay(deviceProfile.switchSettleMs)
            return
        }
        val started = SystemClock.elapsedRealtime()
        val maxWait = deviceProfile.switchSettleMs * 2
        while (true) {
            val waited = SystemClock.elapsedRealtime() - started
            val states = keys.map { threeAStates[it] }
            if (CameraSettling.doneWaiting(states, waited, maxWait)) {
                if (waited >= maxWait) Log.i(TAG, "Cameras $keys did not settle in ${maxWait}ms: $states")
                return
            }
            delay(SETTLE_POLL_MS)
        }
    }

    /**
     * 遅れの少ない撮影（ZSL）は、カメラが流し続けている直前の映像から 1 枚を取り出す。ピントを合わせ直した
     * 直後は、取り出す映像がまだ合わせる前のものかもしれないので、合わせた後の映像がたまるまで少し待つ。
     */
    private suspend fun waitForFramesAfterFocus() {
        if (boundEnhanced) delay(ZSL_AFTER_FOCUS_MS)
    }

    /** 回転の速さが小さくなる（スマホがほぼ止まる）まで、最大で [maxWaitMs] 待つ。 */
    private suspend fun waitForSteadyMoment(maxWaitMs: Long) {
        val started = SystemClock.elapsedRealtime()
        while (!SteadyMoment.shouldCapture(currentAngularSpeed(), SystemClock.elapsedRealtime() - started, maxWaitMs)) {
            delay(STEADY_POLL_MS)
        }
    }

    /** 今の回転の速さ。ジャイロが無い、または値が古いときは null。 */
    private fun currentAngularSpeed(): Double? {
        val age = SystemClock.elapsedRealtimeNanos() - latestAngularSpeedNanos
        return if (latestAngularSpeedNanos != 0L && age in 0..GYRO_MAX_AGE_NANOS) latestAngularSpeed else null
    }

    private fun isBound(group: List<String>): Boolean =
        group.toSet() == boundCaptures.keys && boundNight == wantsNight(group) &&
            boundSetup == wantsSetup(group)

    /** 動いた位置にピントを合わせて撮り直す。合わせられないカメラでは null。 */
    private suspend fun refocusAndRetake(key: String, center: MotionCenter): InMemoryResult.Captured? {
        // すでにその位置にピントを合わせて撮った画像なら、撮り直しても同じなので撮り直さない。
        if (focusedAt[key]?.first == center) return null
        if (!focusAt(key, center)) return null
        waitForFramesAfterFocus()
        val capture = boundCaptures[key] ?: return null
        return captureToMemory(key, capture) as? InMemoryResult.Captured
    }

    /**
     * 指定位置にピントだけを合わせる。露出は画面全体でカメラに任せる（小さな一点で露出を決めると、
     * そこが暗いと画面全体が白飛びするため）。合わせられなかったら false。
     */
    private suspend fun focusAt(key: String, center: MotionCenter): Boolean {
        // つなぎ直していなければピントはその位置に固定されたままなので、合わせ直さない（撮影を速くする）。
        val now = SystemClock.elapsedRealtime()
        focusedAt[key]?.let { (point, at) -> if (point == center && now - at < FOCUS_REFRESH_MS) return true }
        focusedAt.remove(key)
        val camera = boundCameras[key] ?: return false
        val capture = boundCaptures[key] ?: return false
        val action = runCatching {
            val point = SurfaceOrientedMeteringPointFactory(1f, 1f, capture)
                .createPoint(center.x.toFloat(), center.y.toFloat())
            FocusMeteringAction.Builder(
                point,
                FocusMeteringAction.FLAG_AF,
            ).disableAutoCancel().build()
        }.getOrNull() ?: return false
        if (!camera.cameraInfo.isFocusMeteringSupported(action)) return false

        val focused = withTimeoutOrNull(FOCUS_TIMEOUT_MS) {
            runCatching { camera.cameraControl.startFocusAndMetering(action).await() }
                .onFailure { Log.w(TAG, "Focus failed for $key", it) }
                .isSuccess
        } ?: false
        if (focused) focusedAt[key] = center to now
        return focused
    }

    private suspend fun <T> ListenableFuture<T>.await(): T = suspendCancellableCoroutine { continuation ->
        addListener(
            {
                val result = runCatching { get() }
                if (continuation.isActive) {
                    result.fold({ continuation.resume(it) }, { continuation.resumeWith(Result.failure(it)) })
                }
            },
            ContextCompat.getMainExecutor(this@IntervalCaptureService),
        )
        continuation.invokeOnCancellation { cancel(false) }
    }

    private suspend fun captureToMemory(key: String, capture: ImageCapture): InMemoryResult =
        withTimeoutOrNull(CAPTURE_TIMEOUT_MS) { takePictureToMemory(capture, detectSizeOf(key)) }
            ?: InMemoryResult.Failed("撮影が時間内に終わりませんでした")

    private suspend fun takePictureToMemory(capture: ImageCapture, detectSize: Int): InMemoryResult =
        suspendCancellableCoroutine { continuation ->
            capture.takePicture(
                captureExecutor,
                object : ImageCapture.OnImageCapturedCallback() {
                    override fun onCaptureSuccess(image: ImageProxy) {
                        val result = image.use {
                            val buffer = it.planes[0].buffer
                            val jpeg = ByteArray(buffer.remaining()).also { bytes -> buffer.get(bytes) }
                            // 人の検出と動体検知で同じ縮小画像を使い、大きな JPEG の読み込みを 1 回で済ませる。
                            val small = MotionFrameSampler.decode(jpeg, detectSize)
                            if (small == null) {
                                InMemoryResult.Failed("画像を解析できませんでした")
                            } else {
                                val frame = MotionFrameSampler.fromBitmap(small)
                                val dimensions = MotionFrameSampler.size(jpeg)
                                InMemoryResult.Captured(
                                    jpeg,
                                    it.imageInfo.rotationDegrees,
                                    frame,
                                    small,
                                    // 露出の始まりの時刻（ジャイロと同じ時計）と露出時間。手ぶれの見積もりに使う。
                                    sensorTimestampNanos = it.imageInfo.timestamp,
                                    exposureSeconds = exposureSecondsOf(jpeg),
                                    imageWidth = dimensions?.first,
                                    imageHeight = dimensions?.second,
                                )
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

    /** JPEG の Exif に書かれた露出時間（秒）。無ければ null。 */
    private fun exposureSecondsOf(jpeg: ByteArray): Double? = runCatching {
        ExifInterface(ByteArrayInputStream(jpeg)).getAttributeDouble(ExifInterface.TAG_EXPOSURE_TIME, -1.0)
    }.getOrNull()?.takeIf { it > 0.0 }

    private fun saveCameraSet(shots: Map<String, Pair<ByteArray, Int>>): List<String> {
        val stamp = FILE_DATE_FORMAT.format(LocalDateTime.now())
        return shots.map { (lens, shot) ->
            "IBC_MOTION_${stamp}_$lens.jpg".also { saveJpeg(shot.first, shot.second, it) }
        }
    }

    private fun saveJpeg(jpeg: ByteArray, rotationDegrees: Int, fileName: String) {
        // 向きは Exif の中でその場で書き換える（ファイルの形が変わらないので Ultra HDR のゲインマップも壊れず、
        // 一時ファイルを経ないので速い）。その場で書き換えられない JPEG だけ、一時ファイルで Exif を作り直す。
        if (JpegOrientation.setInPlace(jpeg, exifOrientationOf(rotationDegrees))) {
            writeToGallery(fileName) { output -> output.write(jpeg) }
            return
        }
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
            writeToGallery(fileName) { output -> tempFile.inputStream().use { it.copyTo(output) } }
        } finally {
            tempFile.delete()
        }
    }

    /** `Pictures/IntervalBubbleCamera` に [fileName] の JPEG を作り、[write] で中身を書く。失敗したら作った項目を消す。 */
    private fun writeToGallery(fileName: String, write: (java.io.OutputStream) -> Unit) {
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
            contentResolver.openOutputStream(uri)?.use(write) ?: error("保存先を開けませんでした")
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
        boundCameras = emptyMap()
        motionReferences.clear()
        colorThumbs.clear()
        lastMotionCenters.clear()
        skippedDetections.clear()
        captureFallback.clear()
        targetTrackers.clear()
        focusedAt.clear()
        orientationListener.disable()
        stopSensors()
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
        boundCameras = emptyMap()
        motionReferences.clear()
        colorThumbs.clear()
        lastMotionCenters.clear()
        skippedDetections.clear()
        captureFallback.clear()
        targetTrackers.clear()
        focusedAt.clear()
        orientationListener.disable()
        stopSensors()
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

    private fun buildNotification(): Notification {
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
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_camera)
            .setContentIntent(openAppIntent)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            // 情報は表示しない（Android の決まりで必要な通知なので、停止ボタンだけ置く）。
            .addAction(R.drawable.ic_stop, getString(R.string.notification_stop), stopIntent)
            .build()
    }


    override fun onDestroy() {
        sessionGeneration += 1
        captureJob?.cancel()
        cameraProvider?.unbindAll()
        orientationListener.disable()
        stopSensors()
        releaseWakeLock()
        bubbleOverlay?.hide()
        captureExecutor.shutdown()
        // 保存中の画像は書き終えてから止める（shutdown は実行中・待ち中の保存を最後まで行う）。
        saveExecutor.shutdown()
        val detector = targetDetector
        targetDetector = null
        detectionExecutor.execute { detector?.close() }
        detectionExecutor.shutdown()
        dogfood.close()
        if (CaptureStateStore.state.value.isActive) {
            CaptureStateStore.markStopped("とるのが おわりました。")
        }
        super.onDestroy()
    }

    /** 1 台のカメラの 1 回の判定で、記録（[DogfoodRecord]）に残すことがら。分からないものは null。 */
    private class JudgeTrace(
        val rotation: Double?,
        val tilt: Double?,
        val walking: Boolean,
    ) {
        var phoneMotion = ""
        var compensatedShift = ""
        var rawChangedRatio: Double? = null
        var changedRatio: Double? = null
        var noisePixelThreshold: Int? = null
        var detectionSkipped: Boolean? = null
        var detections: Int? = null
        var confidentPersons: Int? = null
        var zoomed: Boolean? = null
        var tiledDetections: Int? = null
        var targetKind: String? = null
        var motion: Boolean? = null
        var reason = ""
        var boxJumpOnly = false
        var refocused: Boolean? = null
        var blur: Double? = null
        var blurRetaken: Boolean? = null
    }

    /** 撮影ごと・カメラごとの記録を 1 行書く。 */
    private fun logShot(key: String, shot: InMemoryResult.Captured, judgement: MotionResult?, trace: JudgeTrace, saved: Boolean) {
        dogfood.write(
            DogfoodRecord(
                time = DogfoodLog.now(),
                kind = "shot",
                cycle = cycleNumber,
                camera = key,
                intervalSeconds = currentIntervalSeconds,
                pixelThreshold = motionThreshold.pixelThreshold,
                areaPermille = motionThreshold.areaPermille,
                fullResolution = boundFullResolution,
                enhanced = boundEnhanced,
                night = boundNight,
                ultraHdr = boundSetup.ultraHdr && key in ultraHdrCameras,
                imageWidth = shot.imageWidth,
                imageHeight = shot.imageHeight,
                exposureSeconds = shot.exposureSeconds,
                heat = heatLevel.name,
                phoneMotion = trace.phoneMotion,
                rotationDegrees = trace.rotation,
                tiltDegrees = trace.tilt,
                walking = trace.walking,
                compensatedShift = trace.compensatedShift,
                rawChangedRatio = trace.rawChangedRatio,
                changedRatio = trace.changedRatio,
                noisePixelThreshold = trace.noisePixelThreshold,
                detectionSkipped = trace.detectionSkipped,
                detections = trace.detections,
                confidentPersons = trace.confidentPersons,
                zoomed = trace.zoomed,
                tiledDetections = trace.tiledDetections,
                motion = judgement?.motionDetected,
                reason = trace.reason,
                refocused = trace.refocused,
                blurFraction = trace.blur,
                blurRetaken = trace.blurRetaken,
                saved = saved,
            ),
        )
    }

    /** カメラのつなぎ方・温度の変化・失敗などの出来事を 1 行書く。 */
    private fun logEvent(kind: String, camera: String, message: String) {
        dogfood.write(
            DogfoodRecord(time = DogfoodLog.now(), kind = "event", cycle = cycleNumber, camera = camera, reason = kind, message = message),
        )
    }

    private sealed interface PhotoResult {
        /** 動体あり。カメラごとの JPEG と回転角を保存する。 */
        class Motion(val shots: Map<String, Pair<ByteArray, Int>>) : PhotoResult
        data class Failed(val message: String) : PhotoResult
        data object Baseline : PhotoResult
        data class NoMotion(val changedRatio: Double, val phoneMoved: Boolean) : PhotoResult
    }

    private class CameraEntry(
        val key: String,
        val selector: CameraSelector,
        val lensFacing: Int,
        val isPhysical: Boolean,
        /** 夜景モード用の CameraSelector（非対応なら null）。 */
        val nightSelector: CameraSelector?,
    )

    private sealed interface InMemoryResult {
        class Captured(
            val jpeg: ByteArray,
            val rotationDegrees: Int,
            val frame: LumaFrame,
            /** 人の検出用の縮小画像（センサーの向き）。判定が済んだら手放す。 */
            var image: Bitmap?,
            /** 露出の始まりの時刻（SystemClock.elapsedRealtimeNanos と同じ時計）。 */
            val sensorTimestampNanos: Long,
            /** 露出時間（秒）。JPEG の Exif から。分からなければ null。 */
            val exposureSeconds: Double?,
            /** 撮れた写真の幅と高さ（画素）。全画素で撮れたかを記録で確かめるため。 */
            val imageWidth: Int?,
            val imageHeight: Int?,
        ) : InMemoryResult
        data class Failed(val message: String) : InMemoryResult
    }

    companion object {
        const val ACTION_START = "com.ichirocc.intervalbubblecamera.action.START"
        const val ACTION_STOP = "com.ichirocc.intervalbubblecamera.action.STOP"
        const val ACTION_UPDATE_ICON_COLOR =
            "com.ichirocc.intervalbubblecamera.action.UPDATE_ICON_COLOR"
        const val EXTRA_INTERVAL_SECONDS = "interval_seconds"
        const val EXTRA_ICON_COLOR = "icon_color"
        const val EXTRA_MOTION_PIXEL_THRESHOLD = "motion_pixel_threshold"
        const val EXTRA_MOTION_AREA_PERMILLE = "motion_area_permille"
        const val EXTRA_DOGFOOD_LOG = "dogfood_log"
        const val LENS_BACK = "back"
        const val LENS_FRONT = "front"

        private const val TAG = "IntervalCaptureService"
        private const val NOTIFICATION_CHANNEL_ID = "interval_capture"
        private const val NOTIFICATION_ID = 1042
        private const val ALBUM_NAME = "IntervalBubbleCamera"
        private const val WAKE_LOCK_TAG = "IntervalBubbleCamera:IntervalCapture"
        private const val FOCUS_TIMEOUT_MS = 2_000L
        /** 露出・ホワイトバランス・ピントが落ち着いたかを見直す間隔。 */
        private const val SETTLE_POLL_MS = 20L
        /** ピントを合わせ直してから、合わせた後の映像（30 コマ/秒で 3 コマほど）がたまるまで待つ時間。 */
        private const val ZSL_AFTER_FOCUS_MS = 100L
        /** ぶれの少ない瞬間を待つときに、回転の速さを見直す間隔。 */
        private const val STEADY_POLL_MS = 15L
        private const val GYRO_MAX_AGE_NANOS = 200_000_000L
        /** 何秒先の温度の見込みで負荷を下げるか。 */
        private const val HEADROOM_FORECAST_SECONDS = 10
        /** これより古い向きのセンサーの値は使わない（画面オフなどで届かなくなったとき）。 */
        private const val ORIENTATION_MAX_AGE_NANOS = 1_000_000_000L
        /** 加速度から重力を取り出すときのならし具合（小さいほど強くならす）。 */
        private const val GRAVITY_SMOOTHING = 0.1f
        // 夜景モードは 1 枚に数秒かかるため長めに待つ。
        private const val CAPTURE_TIMEOUT_MS = 15_000L
        private val FRAME_CENTER = MotionCenter(0.5, 0.5)
        private const val WAKE_LOCK_TIMEOUT_MS = 10 * 60 * 1_000L
        // DateTimeFormatter はスレッドをまたいで使っても安全（SimpleDateFormat は安全でない）。
        private val FILE_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS", Locale.US)
        /** 同じ位置にピントを合わせたままでも、これだけ経ったら合わせ直す。 */
        private const val FOCUS_REFRESH_MS = 30_000L
    }
}
