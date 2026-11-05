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
import android.hardware.camera2.CameraCharacteristics
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
import androidx.camera.extensions.ExtensionMode
import androidx.camera.extensions.ExtensionsManager
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.google.common.util.concurrent.ListenableFuture
import com.ichirocc.intervalbubblecamera.AppIconColor
import com.ichirocc.intervalbubblecamera.CameraMovement
import com.ichirocc.intervalbubblecamera.CameraCandidate
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
import com.ichirocc.intervalbubblecamera.LumaFrame
import com.ichirocc.intervalbubblecamera.MainActivity
import com.ichirocc.intervalbubblecamera.MotionCenter
import com.ichirocc.intervalbubblecamera.MotionDetector
import com.ichirocc.intervalbubblecamera.MotionResult
import com.ichirocc.intervalbubblecamera.MotionThreshold
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
    private val lastMotionCenters = mutableMapOf<String, MotionCenter>()
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
                startCapture(interval, iconColor, threshold)
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
        threshold: MotionThreshold,
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
        motionThreshold = threshold
        motionReferences.clear()
        orientationAtFrame.clear()
        gravityAtFrame.clear()
        lastMotionCenters.clear()
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
                        "カメラを開始できませんでした。別のアプリがカメラを使用していないか確認してください。",
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
            "カメラを開始できませんでした。別のアプリがカメラを使用していないか確認してください。",
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
        val captures = keys.associateWith { newImageCapture() }
        val night = wantsNight(keys)
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
    }

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

    private fun newImageCapture(): ImageCapture = ImageCapture.Builder()
        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
        .setJpegQuality(deviceProfile.jpegQuality)
        .setFlashMode(ImageCapture.FLASH_MODE_OFF)
        .setTargetRotation(targetRotation)
        .build()

    private fun cameraSummary(): String {
        val nightCameras = cameraEntries.filter { it.nightSelector != null }.map { it.key }
        val lighting = when {
            nightCameras.isEmpty() -> "夜景モード非対応"
            nightModeSwitch.night -> "夜景モード（${nightCameras.joinToString()}）"
            else -> "普通のモード（暗いと${nightCameras.joinToString()}は夜景モード）"
        }
        return "カメラ${cameraEntries.size}台（1台ずつ切替）: ${cameraEntries.joinToString { it.key }}\n$lighting"
    }

    private fun runningDetail(intervalSeconds: Int): String {
        val count = cameraEntries.size
        val how = when {
            count == 1 -> "カメラ1台で撮影し"
            else -> "カメラ${count}台を順に切り替えて撮影し"
        }
        return "${intervalSeconds}秒ごとに$how、どれかで動体があるときだけ全カメラの画像を保存します。"
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
                        CaptureStateStore.markRecovering(
                            "前回の撮影に失敗しました。次の間隔で再試行します: ${result.message}",
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
        val shots = linkedMapOf<String, InMemoryResult.Captured>()
        val judgements = linkedMapOf<String, MotionResult?>()
        var firstFailure: String? = null
        phoneMovedCameras.clear()

        val failed = mutableListOf<String>()
        forEachCameraGroup { key, shot ->
            if (shot is InMemoryResult.Failed) {
                Log.w(TAG, "Camera $key failed: ${shot.message}")
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
            val judgement = motionReferences[key]?.let {
                judgeMotion(key, it, captured, rotation, tilt, lyingFlat, holderWalking)
            }
            captured.image = null
            val center = judgement?.center
            if (center != null) {
                lastMotionCenters[key] = center
                captured = refocusAndRetake(key, center) ?: captured
                captured.image = null
            }
            motionReferences[key] = captured.frame
            // 向きが測れなかった回は前回の向きも捨てる（古い向きと比べて誤判定しないため）。
            if (orientation != null) orientationAtFrame[key] = orientation else orientationAtFrame.remove(key)
            if (gravity != null) gravityAtFrame[key] = gravity.copyOf() else gravityAtFrame.remove(key)
            shots[key] = captured
            judgements[key] = judgement
        }
        recordFailures(failed)
        if (shots.isEmpty()) return PhotoResult.Failed(firstFailure ?: "カメラが準備されていません")

        return when (val decision = CameraSetMotion.decide(judgements)) {
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
            val result = withContext(saveDispatcher) {
                runCatching { saveCameraSet(shots) }
                    .onFailure { Log.w(TAG, "Unable to save motion photos", it) }
            }
            if (generation != sessionGeneration) {
                // 停止後に保存し終えた分も枚数には数える。
                result.onSuccess { CaptureStateStore.markSavedAfterStop(it) }
                return@launch
            }
            result.fold(
                onSuccess = { CaptureStateStore.markPhotosSaved(it) },
                onFailure = { error ->
                    CaptureStateStore.markRecovering("保存に失敗しました: ${error.message ?: "不明なエラー"}")
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
    ): MotionResult {
        val rawMotion = MotionDetector.compare(reference, shot.frame, motionThreshold)
        val tracker = targetTrackers.getOrPut(key) { TargetTracker() }
        val phoneMotion = CameraMovement.judge(phoneRotationDegrees, rawMotion.changedRatio, phoneTiltDegrees, lyingFlat)
        // スマホが動いていて、撮影している人も歩いている（体ごと動いている）回は、写っているものに関係なく保存する。
        // 歩きながらの撮影では景色ごと変わり、重ね直しも人の検出も当てにならないため。
        val walkingShot = holderWalking && phoneMotion == PhoneMotion.MOVED
        fun finish(result: MotionResult): MotionResult {
            if (!walkingShot || result.motionDetected) return result
            Log.i(TAG, "Phone and its holder are moving for $key; saving")
            return result.copy(motionDetected = true)
        }
        // スマホが動いた回は画面全体がずれるので、前回の画像を同じだけずらして重ねてから比べる（手ぶれ補正と同じ）。
        // 重ねられれば、スマホが止まっているときと同じように判定し、追跡中の位置も同じだけずらして続ける。
        val compensation = if (phoneMotion == PhoneMotion.MOVED) compensateShift(reference, shot.frame) else null
        val pixelMotion = compensation?.motion ?: rawMotion
        // 重ねきれないほど動いた回は、画像の変化や位置の移動では判定しない。ただし人（全身・体の一部・手）が
        // 写っていれば保存する。追跡中の位置も画面ごとずれるため、追跡はやり直す。
        val phoneMoved = phoneMotion == PhoneMotion.MOVED && compensation == null
        // スマホが止まっていて画面の大部分が変わったら、すぐ近くを人が覆った（スマホの上を人がまたいだなど）と
        // みなし、人を検出できなくても保存する。真下や真横から見た人は検出できないことが多いため。
        val closeSubject = CameraMovement.closeSubject(phoneMotion, rawMotion.changedRatio)
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
        if (heatLevel.detection == DetectionLevel.NONE) return finish(withoutDetection)
        // 正立以外の向きでも探すのは、温度が普通で、画像が変わった・追跡中の対象がいる・スマホが動いた回だけ
        // （何も変わっていない回まで 4 回検出すると発熱が大きいため）。
        val allOrientations = heatLevel.detection == DetectionLevel.ALL_ORIENTATIONS &&
            (pixelMotion.motionDetected || phoneMoved || tracker.hasTracks)

        // GPU の検出器は作ったスレッドでしか使えないため、作成も検出も専用の 1 本のスレッドで行う。
        return finish(withContext(detectionDispatcher) {
            val detector = loadTargetDetector() ?: return@withContext withoutDetection
            runCatching {
                val image = shot.image ?: return@runCatching null
                var detections = detector.detect(image, shot.rotationDegrees, allOrientations)
                if (phoneMoved) {
                    // 追跡をこの回の位置から始め直し、写っている人がいればその枠を返す。
                    tracker.update(detections) { 0.0 }
                    return@runCatching TargetTracker.personInFrame(detections)?.box
                }
                val mask = compensation?.mask ?: MovingTargets.changedMask(reference, shot.frame, motionThreshold)
                if (pixelMotion.motionDetected) {
                    detections = withZoomedDetections(detector, shot, mask, detections, allOrientations)
                }
                // 画面の変化が閾値（大きさ・広さ）に届いたときだけ、人の枠の中の変化を動きとして数える。
                // 届かなくても、人の位置が前回から動いていれば動きとする（ゆっくりした動き・遠くの人）。
                val tracked = tracker.update(detections) { box ->
                    if (pixelMotion.motionDetected) {
                        MovingTargets.shareOfChangeInside(mask, shot.frame.width, shot.frame.height, box)
                    } else {
                        0.0
                    }
                }
                TargetTracker.focusTarget(tracked)?.box
            }.fold(
                onSuccess = { box ->
                    if (box == null && closeSubject) {
                        Log.i(TAG, "Something close covered $key (change=${pixelMotion.changedRatio}); saving")
                        pixelMotion
                    } else if (box == null) {
                        pixelMotion.copy(motionDetected = false, center = null)
                    } else {
                        if (phoneMoved) Log.i(TAG, "Phone moved for $key but a person is in frame; saving")
                        pixelMotion.copy(motionDetected = true, center = box.center)
                    }
                },
                onFailure = { error ->
                    Log.w(TAG, "Target detection failed; using pixel change only", error)
                    withoutDetection
                },
            )
        })
    }

    /** スマホの動きを打ち消した比べ方の結果。 */
    private class ShiftCompensation(val shift: FrameShift, val motion: MotionResult, val mask: BooleanArray)

    /** 画面全体のずれを求めて打ち消す。打ち消しきれない（大きく動いた・その場で回した）ときは null。 */
    private fun compensateShift(reference: LumaFrame, frame: LumaFrame): ShiftCompensation? {
        val shift = FrameAlignment.estimate(reference, frame)
        val motion = FrameAlignment.compareAligned(reference, frame, shift, motionThreshold)
        if (!FrameAlignment.aligned(motion)) return null
        return ShiftCompensation(shift, motion, FrameAlignment.alignedMask(reference, frame, shift, motionThreshold))
    }

    /**
     * 画面が変わったのに、その変化を説明する人（確からしい検出）が見つからなかったら、変化した辺りを
     * 撮影画像から高い解像度で切り出して探し直し、見つけたものを [detections] とまとめて返す（SAHI）。
     * 遠くの小さな人を見つけるため。検出が 1 回増えるので、変化があった回だけ行う。
     */
    private fun withZoomedDetections(
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
        val region = MovingTargets.changedRegion(mask, width, height) ?: return detections
        val zoomed = detector.detectRegion(shot.jpeg, region, shot.rotationDegrees, deviceProfile.detectSize, allOrientations)
        if (zoomed.isEmpty()) return detections
        Log.i(TAG, "Zoomed detection found ${zoomed.size} target(s) in $region")
        return TargetTracker.fuse(detections + zoomed)
    }

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
        val level = ThermalPolicy.level(powerManager.currentThermalStatus, lastHeadroom)
        if (level == heatLevel) return
        Log.i(TAG, "Heat level ${heatLevel} -> $level (status=${powerManager.currentThermalStatus}, headroom=$lastHeadroom)")
        heatLevel = level
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
                // 切り替え直後は露出が合っておらず、暗い画像を動体と誤判定するため待つ。
                delay(deviceProfile.switchSettleMs)
            }
            val captures = boundCaptures
            val taken = coroutineScope {
                group.map { key ->
                    key to async {
                        // 保存されうる画像は必ずピントを合わせてから撮る（最後に動いた位置、無ければ中央）。
                        focusAt(key, lastMotionCenters[key] ?: FRAME_CENTER)
                        // 歩きながらなら、1 歩ごとの揺れの合間（スマホがほぼ止まった瞬間）を待って撮る（ぶれを減らす）。
                        if (walkingDetector.isWalking(SystemClock.elapsedRealtimeNanos())) waitForSteadyMoment()
                        val first = captureToMemory(captures.getValue(key))
                        if (first is InMemoryResult.Captured) return@async first
                        // 切り替え直後などで失敗したら、もう少し待って 1 回だけ撮り直す。
                        Log.w(TAG, "Retrying camera $key after: ${(first as InMemoryResult.Failed).message}")
                        delay(deviceProfile.switchSettleMs)
                        captureToMemory(captures.getValue(key))
                    }
                }.map { (key, pending) -> key to pending.await() }
            }
            taken.forEach { (key, shot) -> onShot(key, shot) }
        }
    }

    /** 回転の速さが小さくなる（スマホがほぼ止まる）まで、最大で [SteadyMoment.MAX_WAIT_MS] 待つ。 */
    private suspend fun waitForSteadyMoment() {
        val started = SystemClock.elapsedRealtime()
        while (!SteadyMoment.shouldCapture(currentAngularSpeed(), SystemClock.elapsedRealtime() - started)) {
            delay(STEADY_POLL_MS)
        }
    }

    /** 今の回転の速さ。ジャイロが無い、または値が古いときは null。 */
    private fun currentAngularSpeed(): Double? {
        val age = SystemClock.elapsedRealtimeNanos() - latestAngularSpeedNanos
        return if (latestAngularSpeedNanos != 0L && age in 0..GYRO_MAX_AGE_NANOS) latestAngularSpeed else null
    }

    private fun isBound(group: List<String>): Boolean =
        group.toSet() == boundCaptures.keys && boundNight == wantsNight(group)

    /** 動いた位置にピントを合わせて撮り直す。合わせられないカメラでは null。 */
    private suspend fun refocusAndRetake(key: String, center: MotionCenter): InMemoryResult.Captured? {
        // すでにその位置にピントを合わせて撮った画像なら、撮り直しても同じなので撮り直さない。
        if (focusedAt[key]?.first == center) return null
        if (!focusAt(key, center)) return null
        val capture = boundCaptures[key] ?: return null
        return captureToMemory(capture) as? InMemoryResult.Captured
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

    private suspend fun captureToMemory(capture: ImageCapture): InMemoryResult =
        withTimeoutOrNull(CAPTURE_TIMEOUT_MS) { takePictureToMemory(capture) }
            ?: InMemoryResult.Failed("撮影が時間内に終わりませんでした")

    private suspend fun takePictureToMemory(capture: ImageCapture): InMemoryResult =
        suspendCancellableCoroutine { continuation ->
            capture.takePicture(
                captureExecutor,
                object : ImageCapture.OnImageCapturedCallback() {
                    override fun onCaptureSuccess(image: ImageProxy) {
                        val result = image.use {
                            val buffer = it.planes[0].buffer
                            val jpeg = ByteArray(buffer.remaining()).also { bytes -> buffer.get(bytes) }
                            // 人の検出と動体検知で同じ縮小画像を使い、大きな JPEG の読み込みを 1 回で済ませる。
                            val small = MotionFrameSampler.decode(jpeg, deviceProfile.detectSize)
                            if (small == null) {
                                InMemoryResult.Failed("画像を解析できませんでした")
                            } else {
                                val frame = MotionFrameSampler.fromBitmap(small)
                                InMemoryResult.Captured(jpeg, it.imageInfo.rotationDegrees, frame, small)
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

    private fun saveCameraSet(shots: Map<String, Pair<ByteArray, Int>>): List<String> {
        val stamp = FILE_DATE_FORMAT.format(LocalDateTime.now())
        return shots.map { (lens, shot) ->
            "IBC_MOTION_${stamp}_$lens.jpg".also { saveJpeg(shot.first, shot.second, it) }
        }
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
        boundCameras = emptyMap()
        motionReferences.clear()
        lastMotionCenters.clear()
        targetTrackers.clear()
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
        lastMotionCenters.clear()
        targetTrackers.clear()
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
        if (CaptureStateStore.state.value.isActive) {
            CaptureStateStore.markStopped("撮影サービスが終了しました。")
        }
        super.onDestroy()
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
        const val LENS_BACK = "back"
        const val LENS_FRONT = "front"

        private const val TAG = "IntervalCaptureService"
        private const val NOTIFICATION_CHANNEL_ID = "interval_capture"
        private const val NOTIFICATION_ID = 1042
        private const val ALBUM_NAME = "IntervalBubbleCamera"
        private const val WAKE_LOCK_TAG = "IntervalBubbleCamera:IntervalCapture"
        private const val FOCUS_TIMEOUT_MS = 2_000L
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
