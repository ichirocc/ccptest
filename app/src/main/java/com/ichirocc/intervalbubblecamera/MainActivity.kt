package com.ichirocc.intervalbubblecamera

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.provider.Settings
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import com.ichirocc.intervalbubblecamera.capture.CapturePhase
import com.ichirocc.intervalbubblecamera.capture.CaptureStateStore
import com.ichirocc.intervalbubblecamera.capture.CaptureUiState
import com.ichirocc.intervalbubblecamera.capture.IntervalCaptureService
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private lateinit var statusDot: android.view.View
    private lateinit var statusTitle: TextView
    private lateinit var statusDetail: TextView
    private lateinit var photoCount: TextView
    private lateinit var lastPhoto: TextView
    private lateinit var intervalValue: TextView
    private lateinit var intervalSeekBar: SeekBar
    private lateinit var lensGroup: RadioGroup
    private lateinit var backCamera: RadioButton
    private lateinit var frontCamera: RadioButton
    private lateinit var iconColorPreview: ImageView
    private lateinit var iconColorSelectedLabel: TextView
    private lateinit var iconColorButtons: Map<AppIconColor, ImageButton>
    private lateinit var permissionStatus: TextView
    private lateinit var overlayPermissionButton: MaterialButton
    private lateinit var startStopButton: MaterialButton
    private lateinit var openGalleryButton: MaterialButton

    private val preferences by lazy { getSharedPreferences(PREFERENCES_NAME, MODE_PRIVATE) }
    private var selectedIconColor = AppIconColor.DEFAULT
    private var pendingStart = false
    private var minimizeWhenRunning = false

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        refreshPermissionStatus()
        if (granted && pendingStart) {
            continueStartFlow()
        } else if (!granted) {
            cancelPendingStart(getString(R.string.camera_permission_required))
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        refreshPermissionStatus()
        if (granted && pendingStart) {
            continueStartFlow()
        } else if (!granted) {
            cancelPendingStart(getString(R.string.notification_permission_required))
        }
    }

    private val overlaySettingsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        refreshPermissionStatus()
        if (pendingStart) {
            if (Settings.canDrawOverlays(this)) {
                continueStartFlow()
            } else {
                cancelPendingStart(getString(R.string.overlay_permission_required))
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT != ANDROID_16_API_LEVEL) {
            Toast.makeText(this, R.string.android_16_only, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        setContentView(R.layout.activity_main)
        bindViews()
        restorePreferences()
        configureControls()
        observeCaptureState()
        refreshPermissionStatus()
    }

    override fun onResume() {
        super.onResume()
        refreshPermissionStatus()
    }

    private fun bindViews() {
        statusDot = findViewById(R.id.statusDot)
        statusTitle = findViewById(R.id.statusTitle)
        statusDetail = findViewById(R.id.statusDetail)
        photoCount = findViewById(R.id.photoCount)
        lastPhoto = findViewById(R.id.lastPhoto)
        intervalValue = findViewById(R.id.intervalValue)
        intervalSeekBar = findViewById(R.id.intervalSeekBar)
        lensGroup = findViewById(R.id.lensGroup)
        backCamera = findViewById(R.id.backCamera)
        frontCamera = findViewById(R.id.frontCamera)
        iconColorPreview = findViewById(R.id.iconColorPreview)
        iconColorSelectedLabel = findViewById(R.id.iconColorSelectedLabel)
        iconColorButtons = linkedMapOf(
            AppIconColor.BLUE to findViewById(R.id.iconColorBlue),
            AppIconColor.BLACK to findViewById(R.id.iconColorBlack),
            AppIconColor.GREEN to findViewById(R.id.iconColorGreen),
            AppIconColor.RED to findViewById(R.id.iconColorRed),
            AppIconColor.ORANGE to findViewById(R.id.iconColorOrange),
            AppIconColor.PURPLE to findViewById(R.id.iconColorPurple),
            AppIconColor.PINK to findViewById(R.id.iconColorPink),
        )
        permissionStatus = findViewById(R.id.permissionStatus)
        overlayPermissionButton = findViewById(R.id.overlayPermissionButton)
        startStopButton = findViewById(R.id.startStopButton)
        openGalleryButton = findViewById(R.id.openGalleryButton)
    }

    private fun restorePreferences() {
        val savedInterval = preferences.getInt(
            KEY_INTERVAL_SECONDS,
            IntervalPolicy.DEFAULT_SECONDS,
        )
        intervalSeekBar.progress = IntervalPolicy.seekProgressFromSeconds(savedInterval)
        intervalValue.text = getString(
            R.string.interval_seconds,
            IntervalPolicy.clampSeconds(savedInterval),
        )

        when (preferences.getString(KEY_LENS_FACING, IntervalCaptureService.LENS_BACK)) {
            IntervalCaptureService.LENS_FRONT -> frontCamera.isChecked = true
            else -> backCamera.isChecked = true
        }

        selectedIconColor = AppIconColor.fromStorageKey(
            preferences.getString(KEY_ICON_COLOR, AppIconColor.DEFAULT.storageKey),
        )
        renderIconColor()
        updateLauncherIcon(selectedIconColor, showFailure = false)
    }

    private fun configureControls() {
        intervalSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val seconds = IntervalPolicy.secondsFromSeekProgress(progress)
                intervalValue.text = getString(R.string.interval_seconds, seconds)
                if (fromUser) {
                    preferences.edit { putInt(KEY_INTERVAL_SECONDS, seconds) }
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })

        lensGroup.setOnCheckedChangeListener { _, checkedId ->
            val lens = if (checkedId == R.id.frontCamera) {
                IntervalCaptureService.LENS_FRONT
            } else {
                IntervalCaptureService.LENS_BACK
            }
            preferences.edit { putString(KEY_LENS_FACING, lens) }
        }

        iconColorButtons.forEach { (color, button) ->
            button.setOnClickListener { selectIconColor(color) }
        }

        overlayPermissionButton.setOnClickListener {
            pendingStart = false
            openOverlaySettings()
        }

        startStopButton.setOnClickListener {
            if (CaptureStateStore.state.value.isActive) {
                stopCapture()
            } else {
                beginStartFlow()
            }
        }

        openGalleryButton.setOnClickListener { openGallery() }
    }

    private fun observeCaptureState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                CaptureStateStore.state.collect { renderState(it) }
            }
        }
    }

    private fun renderState(state: CaptureUiState) {
        statusTitle.text = when (state.phase) {
            CapturePhase.IDLE -> getString(R.string.status_idle)
            CapturePhase.STARTING -> getString(R.string.status_starting)
            CapturePhase.RUNNING -> getString(R.string.status_running)
            CapturePhase.ERROR -> getString(R.string.status_error)
        }
        statusDot.setBackgroundResource(
            when (state.phase) {
                CapturePhase.RUNNING -> R.drawable.bg_status_running
                CapturePhase.ERROR -> R.drawable.bg_status_error
                else -> R.drawable.bg_status_idle
            },
        )
        statusDetail.text = state.detail
        photoCount.text = getString(R.string.photo_count, state.photoCount)
        lastPhoto.text = state.lastPhotoName?.let {
            getString(R.string.last_photo_name, it)
        } ?: getString(R.string.last_photo_none)

        val controlsEnabled = !state.isActive
        intervalSeekBar.isEnabled = controlsEnabled
        backCamera.isEnabled = controlsEnabled
        frontCamera.isEnabled = controlsEnabled

        startStopButton.isEnabled = state.phase != CapturePhase.STARTING
        if (state.isActive) {
            startStopButton.text = getString(R.string.stop_capture)
            startStopButton.setIconResource(R.drawable.ic_stop)
            startStopButton.backgroundTintList = ColorStateList.valueOf(getColor(R.color.danger))
        } else {
            startStopButton.text = if (state.phase == CapturePhase.ERROR) {
                getString(R.string.retry_and_bubble)
            } else {
                getString(R.string.start_and_bubble)
            }
            startStopButton.setIconResource(R.drawable.ic_camera)
            startStopButton.backgroundTintList = ColorStateList.valueOf(getColor(R.color.primary))
        }

        if (state.phase == CapturePhase.RUNNING && minimizeWhenRunning) {
            minimizeWhenRunning = false
            window.decorView.post { moveTaskToBack(true) }
        }
    }

    private fun beginStartFlow() {
        pendingStart = true
        continueStartFlow()
    }

    private fun continueStartFlow() {
        if (!pendingStart) return

        when {
            ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) !=
                PackageManager.PERMISSION_GRANTED -> cameraPermissionLauncher.launch(Manifest.permission.CAMERA)

            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED ->
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)

            !Settings.canDrawOverlays(this) -> openOverlaySettings()
            else -> startCapture()
        }
    }

    private fun startCapture() {
        pendingStart = false
        val intervalSeconds = IntervalPolicy.secondsFromSeekProgress(intervalSeekBar.progress)
        val lensFacing = if (frontCamera.isChecked) {
            IntervalCaptureService.LENS_FRONT
        } else {
            IntervalCaptureService.LENS_BACK
        }
        preferences.edit {
            putInt(KEY_INTERVAL_SECONDS, intervalSeconds)
            putString(KEY_LENS_FACING, lensFacing)
            putString(KEY_ICON_COLOR, selectedIconColor.storageKey)
        }

        val serviceIntent = Intent(this, IntervalCaptureService::class.java).apply {
            action = IntervalCaptureService.ACTION_START
            putExtra(IntervalCaptureService.EXTRA_INTERVAL_SECONDS, intervalSeconds)
            putExtra(IntervalCaptureService.EXTRA_LENS_FACING, lensFacing)
            putExtra(IntervalCaptureService.EXTRA_ICON_COLOR, selectedIconColor.storageKey)
        }

        runCatching {
            ContextCompat.startForegroundService(this, serviceIntent)
        }.onSuccess {
            minimizeWhenRunning = true
        }.onFailure { error ->
            minimizeWhenRunning = false
            showMessage(
                getString(
                    R.string.service_start_failed,
                    error.localizedMessage ?: error.javaClass.simpleName,
                ),
            )
        }
    }

    private fun stopCapture() {
        minimizeWhenRunning = false
        startService(
            Intent(this, IntervalCaptureService::class.java)
                .setAction(IntervalCaptureService.ACTION_STOP),
        )
    }

    private fun openOverlaySettings() {
        val appSpecificIntent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            "package:$packageName".toUri(),
        )
        try {
            overlaySettingsLauncher.launch(appSpecificIntent)
        } catch (_: ActivityNotFoundException) {
            overlaySettingsLauncher.launch(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
        }
    }

    private fun openGallery() {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "image/*")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { startActivity(intent) }
            .onFailure { showMessage(getString(R.string.gallery_open_failed)) }
    }

    private fun selectIconColor(color: AppIconColor) {
        if (selectedIconColor == color) return

        selectedIconColor = color
        preferences.edit { putString(KEY_ICON_COLOR, color.storageKey) }
        renderIconColor()
        updateLauncherIcon(color, showFailure = true)

        if (CaptureStateStore.state.value.isActive) {
            runCatching {
                startService(
                    Intent(this, IntervalCaptureService::class.java).apply {
                        action = IntervalCaptureService.ACTION_UPDATE_ICON_COLOR
                        putExtra(IntervalCaptureService.EXTRA_ICON_COLOR, color.storageKey)
                    },
                )
            }.onFailure {
                showMessage(getString(R.string.icon_color_live_update_failed))
            }
        }
    }

    private fun renderIconColor() {
        iconColorPreview.setImageResource(selectedIconColor.iconRes)
        iconColorSelectedLabel.text = getString(
            R.string.icon_color_selected,
            getString(selectedIconColor.labelRes),
        )
        iconColorSelectedLabel.setTextColor(
            ContextCompat.getColor(this, selectedIconColor.colorRes),
        )

        iconColorButtons.forEach { (color, button) ->
            val selected = color == selectedIconColor
            button.isSelected = selected
            button.alpha = if (selected) 1f else 0.52f
            button.scaleX = if (selected) 1.08f else 0.92f
            button.scaleY = if (selected) 1.08f else 0.92f
            button.setBackgroundResource(
                if (selected) R.drawable.bg_icon_color_selected else android.R.color.transparent,
            )
        }
    }

    private fun updateLauncherIcon(color: AppIconColor, showFailure: Boolean) {
        val updates = AppIconColor.entries.map { candidate ->
            PackageManager.ComponentEnabledSetting(
                ComponentName(
                    packageName,
                    "$packageName.${candidate.launcherAliasSuffix}",
                ),
                if (candidate == color) {
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                } else {
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                },
                PackageManager.DONT_KILL_APP,
            )
        }

        runCatching { packageManager.setComponentEnabledSettings(updates) }
            .onFailure {
                if (showFailure) showMessage(getString(R.string.launcher_icon_update_failed))
            }
    }

    private fun refreshPermissionStatus() {
        if (!::permissionStatus.isInitialized) return
        val cameraGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        val overlayGranted = Settings.canDrawOverlays(this)
        val notificationGranted =
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

        permissionStatus.text = buildString {
            appendLine(getString(R.string.permission_camera, permissionLabel(cameraGranted)))
            appendLine(getString(R.string.permission_overlay, permissionLabel(overlayGranted)))
            append(getString(R.string.permission_notification, permissionLabel(notificationGranted)))
        }
        overlayPermissionButton.isVisible = !overlayGranted
    }

    private fun permissionLabel(granted: Boolean): String = if (granted) {
        getString(R.string.permission_granted)
    } else {
        getString(R.string.permission_missing)
    }

    private fun cancelPendingStart(message: String) {
        pendingStart = false
        minimizeWhenRunning = false
        AlertDialog.Builder(this)
            .setTitle(R.string.permission_required_title)
            .setMessage(message)
            .setPositiveButton(R.string.action_ok, null)
            .show()
    }

    private fun showMessage(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    companion object {
        private const val PREFERENCES_NAME = "capture_preferences"
        private const val KEY_INTERVAL_SECONDS = "interval_seconds"
        private const val KEY_LENS_FACING = "lens_facing"
        private const val KEY_ICON_COLOR = "icon_color"
        private const val ANDROID_16_API_LEVEL = 36
    }
}
