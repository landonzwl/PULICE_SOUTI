package com.study.souti.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.RadioButton
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.study.souti.R
import com.study.souti.capture.CameraParameters
import com.study.souti.capture.CaptureState
import com.study.souti.config.CapturePreferences
import com.study.souti.config.CaptureProfile
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

class CalibrationActivity : AppCompatActivity() {
    private lateinit var preferences: CapturePreferences
    private lateinit var previewView: PreviewView
    private lateinit var previewHint: TextView
    private lateinit var cropOverlay: CropOverlayView
    private lateinit var fixedModeRadio: RadioButton
    private lateinit var tapModeRadio: RadioButton
    private lateinit var focusSeek: SeekBar
    private lateinit var zoomSeek: SeekBar
    private lateinit var exposureSeek: SeekBar
    private lateinit var focusLabel: TextView
    private lateinit var zoomLabel: TextView
    private lateinit var exposureLabel: TextView
    private lateinit var whiteBalanceSpinner: Spinner
    private lateinit var cropTopSeek: SeekBar
    private lateinit var cropBottomSeek: SeekBar
    private lateinit var cropLeftSeek: SeekBar
    private lateinit var cropRightSeek: SeekBar
    private lateinit var cropTopLabel: TextView
    private lateinit var cropBottomLabel: TextView
    private lateinit var cropLeftLabel: TextView
    private lateinit var cropRightLabel: TextView
    private lateinit var profileNameInput: EditText
    private lateinit var profileSpinner: Spinner

    private var previewProvider: ProcessCameraProvider? = null
    private var previewCamera: Camera? = null
    private var profiles: MutableList<CaptureProfile> = mutableListOf()
    private var applyingProfile = false
    private var focusX = 0.5f
    private var focusY = 0.5f

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            bindPreview()
        } else {
            previewHint.setText(R.string.preview_unavailable)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_calibration)

        preferences = CapturePreferences(this)
        previewView = findViewById(R.id.previewView)
        previewHint = findViewById(R.id.previewHint)
        cropOverlay = findViewById(R.id.cropOverlay)
        fixedModeRadio = findViewById(R.id.fixedModeRadio)
        tapModeRadio = findViewById(R.id.tapModeRadio)
        focusSeek = findViewById(R.id.focusSeek)
        zoomSeek = findViewById(R.id.zoomSeek)
        exposureSeek = findViewById(R.id.exposureSeek)
        focusLabel = findViewById(R.id.focusLabel)
        zoomLabel = findViewById(R.id.zoomLabel)
        exposureLabel = findViewById(R.id.exposureLabel)
        whiteBalanceSpinner = findViewById(R.id.whiteBalanceSpinner)
        cropTopSeek = findViewById(R.id.cropTopSeek)
        cropBottomSeek = findViewById(R.id.cropBottomSeek)
        cropLeftSeek = findViewById(R.id.cropLeftSeek)
        cropRightSeek = findViewById(R.id.cropRightSeek)
        cropTopLabel = findViewById(R.id.cropTopLabel)
        cropBottomLabel = findViewById(R.id.cropBottomLabel)
        cropLeftLabel = findViewById(R.id.cropLeftLabel)
        cropRightLabel = findViewById(R.id.cropRightLabel)
        profileNameInput = findViewById(R.id.profileNameInput)
        profileSpinner = findViewById(R.id.profileSpinner)

        previewView.scaleType = PreviewView.ScaleType.FIT_CENTER
        cropOverlay.showFocusMarker = false

        setupControls()
        loadProfiles()
        setupTouchFocus()

        findViewById<Button>(R.id.backButton).setOnClickListener { finish() }
    }

    override fun onResume() {
        super.onResume()
        if (!hasPermission(Manifest.permission.CAMERA)) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        } else {
            bindPreview()
        }
    }

    override fun onPause() {
        unbindPreview()
        super.onPause()
    }

    private fun setupControls() {
        focusSeek.max = CaptureProfile.MAX_FOCUS_CM - CaptureProfile.MIN_FOCUS_CM
        zoomSeek.max = ((CaptureProfile.MAX_ZOOM - CaptureProfile.MIN_ZOOM) * 10).roundToInt()
        exposureSeek.max = ((CaptureProfile.MAX_EV - CaptureProfile.MIN_EV) * 2).roundToInt()
        val cropMax = (CaptureProfile.MAX_CROP_MARGIN * 100).roundToInt()
        cropTopSeek.max = cropMax
        cropBottomSeek.max = cropMax
        cropLeftSeek.max = cropMax
        cropRightSeek.max = cropMax

        val whiteBalanceAdapter = ArrayAdapter.createFromResource(
            this,
            R.array.white_balance_options,
            android.R.layout.simple_spinner_item,
        )
        whiteBalanceAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        whiteBalanceSpinner.adapter = whiteBalanceAdapter

        val seekListener = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                updateLabels()
                updateOverlay()
                if (fromUser) applyCurrentParameters()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit

            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        }
        focusSeek.setOnSeekBarChangeListener(seekListener)
        zoomSeek.setOnSeekBarChangeListener(seekListener)
        exposureSeek.setOnSeekBarChangeListener(seekListener)
        cropTopSeek.setOnSeekBarChangeListener(seekListener)
        cropBottomSeek.setOnSeekBarChangeListener(seekListener)
        cropLeftSeek.setOnSeekBarChangeListener(seekListener)
        cropRightSeek.setOnSeekBarChangeListener(seekListener)

        whiteBalanceSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long,
            ) {
                if (!applyingProfile) applyCurrentParameters()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        fixedModeRadio.setOnCheckedChangeListener { _, checked ->
            if (checked) {
                focusSeek.isEnabled = true
                cropOverlay.showFocusMarker = false
                applyCurrentParameters()
            }
        }
        tapModeRadio.setOnCheckedChangeListener { _, checked ->
            if (checked) {
                focusSeek.isEnabled = false
                cropOverlay.showFocusMarker = true
                applyCurrentParameters()
            }
        }

        profileSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long,
            ) {
                if (applyingProfile) return
                val profile = profiles.getOrNull(position) ?: return
                preferences.saveProfiles(profiles, profile.name)
                loadProfileToControls(profile)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        findViewById<Button>(R.id.saveProfileButton).setOnClickListener {
            saveCurrentProfile()
        }
        findViewById<Button>(R.id.deleteProfileButton).setOnClickListener {
            deleteSelectedProfile()
        }
    }

    private fun setupTouchFocus() {
        previewView.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_UP && tapModeRadio.isChecked) {
                focusX = (event.x / previewView.width.coerceAtLeast(1)).coerceIn(0f, 1f)
                focusY = (event.y / previewView.height.coerceAtLeast(1)).coerceIn(0f, 1f)
                cropOverlay.focusX = focusX
                cropOverlay.focusY = focusY
                cropOverlay.showFocusMarker = true
                previewCamera?.let { camera ->
                    lifecycleScope.launch { CameraParameters.focusAt(camera, focusX, focusY) }
                }
            }
            true
        }
    }

    private fun loadProfiles() {
        profiles = preferences.profiles.toMutableList()
        if (profiles.isEmpty()) {
            profiles.add(CaptureProfile.defaultProfile())
            preferences.saveProfiles(profiles, profiles.first().name)
        }
        refreshProfileSpinner(preferences.activeProfileName)
    }

    private fun refreshProfileSpinner(selectedName: String) {
        val names = profiles.map { it.name }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, names)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        applyingProfile = true
        profileSpinner.adapter = adapter
        val index = names.indexOf(selectedName).coerceAtLeast(0)
        if (names.isNotEmpty()) {
            profileSpinner.setSelection(index)
        }
        applyingProfile = false
        profiles.getOrNull(index)?.let(::loadProfileToControls)
    }

    private fun loadProfileToControls(profile: CaptureProfile) {
        applyingProfile = true
        fixedModeRadio.isChecked = profile.focusMode == CaptureProfile.FOCUS_MODE_FIXED
        tapModeRadio.isChecked = profile.focusMode == CaptureProfile.FOCUS_MODE_TAP
        focusSeek.isEnabled = profile.focusMode == CaptureProfile.FOCUS_MODE_FIXED
        focusX = profile.focusX
        focusY = profile.focusY
        cropOverlay.focusX = focusX
        cropOverlay.focusY = focusY
        cropOverlay.showFocusMarker = profile.focusMode == CaptureProfile.FOCUS_MODE_TAP
        focusSeek.progress = profile.focusDistanceCm - CaptureProfile.MIN_FOCUS_CM
        zoomSeek.progress = ((profile.zoomRatio - CaptureProfile.MIN_ZOOM) * 10).roundToInt()
        exposureSeek.progress = ((profile.exposureEv - CaptureProfile.MIN_EV) * 2).roundToInt()
        whiteBalanceSpinner.setSelection(
            CaptureProfile.WHITE_BALANCE_KEYS.indexOf(profile.whiteBalanceMode).coerceAtLeast(0),
        )
        cropLeftSeek.progress = (profile.cropLeft * 100).roundToInt()
        cropTopSeek.progress = (profile.cropTop * 100).roundToInt()
        cropRightSeek.progress = (profile.cropRight * 100).roundToInt()
        cropBottomSeek.progress = (profile.cropBottom * 100).roundToInt()
        profileNameInput.setText(profile.name)
        applyingProfile = false
        updateLabels()
        updateOverlay()
        applyCurrentParameters()
    }

    private fun updateLabels() {
        focusLabel.text = getString(
            R.string.focus_label_format,
            CaptureProfile.MIN_FOCUS_CM + focusSeek.progress,
        )
        zoomLabel.text = getString(
            R.string.zoom_label_format,
            CaptureProfile.MIN_ZOOM + zoomSeek.progress / 10f,
        )
        exposureLabel.text = getString(
            R.string.exposure_label_format,
            CaptureProfile.MIN_EV + exposureSeek.progress * 0.5f,
        )
        cropTopLabel.text = getString(R.string.crop_top_format, cropTopSeek.progress)
        cropBottomLabel.text = getString(R.string.crop_bottom_format, cropBottomSeek.progress)
        cropLeftLabel.text = getString(R.string.crop_left_format, cropLeftSeek.progress)
        cropRightLabel.text = getString(R.string.crop_right_format, cropRightSeek.progress)
    }

    private fun updateOverlay() {
        cropOverlay.cropLeft = cropLeftSeek.progress / 100f
        cropOverlay.cropTop = cropTopSeek.progress / 100f
        cropOverlay.cropRight = cropRightSeek.progress / 100f
        cropOverlay.cropBottom = cropBottomSeek.progress / 100f
        cropOverlay.showFocusMarker = tapModeRadio.isChecked
    }

    private fun currentProfileOrNull(): CaptureProfile? {
        val name = profileNameInput.text.toString().trim()
            .ifEmpty { CaptureProfile.DEFAULT_NAME }
        return runCatching {
            CaptureProfile.create(
                name = name.take(24),
                focusMode = if (tapModeRadio.isChecked) {
                    CaptureProfile.FOCUS_MODE_TAP
                } else {
                    CaptureProfile.FOCUS_MODE_FIXED
                },
                focusDistanceCm = CaptureProfile.MIN_FOCUS_CM + focusSeek.progress,
                focusX = focusX,
                focusY = focusY,
                zoomRatio = CaptureProfile.MIN_ZOOM + zoomSeek.progress / 10f,
                exposureEv = CaptureProfile.MIN_EV + exposureSeek.progress * 0.5f,
                whiteBalanceMode = whiteBalanceKey(),
                cropLeft = cropLeftSeek.progress / 100f,
                cropTop = cropTopSeek.progress / 100f,
                cropRight = cropRightSeek.progress / 100f,
                cropBottom = cropBottomSeek.progress / 100f,
            )
        }.getOrNull()
    }

    private fun whiteBalanceKey(): String {
        val index = whiteBalanceSpinner.selectedItemPosition.coerceAtLeast(0)
        return CaptureProfile.WHITE_BALANCE_KEYS[index]
    }

    private fun applyCurrentParameters() {
        val camera = previewCamera ?: return
        val profile = currentProfileOrNull() ?: return
        lifecycleScope.launch { CameraParameters.apply(camera, profile) }
    }

    private fun saveCurrentProfile() {
        val name = profileNameInput.text.toString().trim()
        if (name.isEmpty()) {
            Toast.makeText(this, R.string.profile_name_required, Toast.LENGTH_SHORT).show()
            return
        }
        val profile = runCatching {
            CaptureProfile.create(
                name = name,
                focusMode = if (tapModeRadio.isChecked) {
                    CaptureProfile.FOCUS_MODE_TAP
                } else {
                    CaptureProfile.FOCUS_MODE_FIXED
                },
                focusDistanceCm = CaptureProfile.MIN_FOCUS_CM + focusSeek.progress,
                focusX = focusX,
                focusY = focusY,
                zoomRatio = CaptureProfile.MIN_ZOOM + zoomSeek.progress / 10f,
                exposureEv = CaptureProfile.MIN_EV + exposureSeek.progress * 0.5f,
                whiteBalanceMode = whiteBalanceKey(),
                cropLeft = cropLeftSeek.progress / 100f,
                cropTop = cropTopSeek.progress / 100f,
                cropRight = cropRightSeek.progress / 100f,
                cropBottom = cropBottomSeek.progress / 100f,
            )
        }.getOrElse {
            Toast.makeText(this, it.message ?: "参数无效", Toast.LENGTH_LONG).show()
            return
        }
        val index = profiles.indexOfFirst { it.name == profile.name }
        if (index >= 0) profiles[index] = profile else profiles.add(profile)
        preferences.saveProfiles(profiles, profile.name)
        refreshProfileSpinner(profile.name)
        Toast.makeText(this, R.string.profile_saved, Toast.LENGTH_SHORT).show()
    }

    private fun deleteSelectedProfile() {
        val name = profileSpinner.selectedItem as? String ?: return
        profiles.removeAll { it.name == name }
        if (profiles.isEmpty()) {
            profiles.add(CaptureProfile.defaultProfile())
        }
        val next = profiles.first().name
        preferences.saveProfiles(profiles, next)
        refreshProfileSpinner(next)
        Toast.makeText(this, R.string.profile_deleted, Toast.LENGTH_SHORT).show()
    }

    private fun bindPreview() {
        if (CaptureState.state.value.isRunning) {
            previewHint.setText(R.string.preview_unavailable)
            return
        }
        lifecycleScope.launch {
            try {
                val provider = ProcessCameraProvider.getInstance(this@CalibrationActivity).await()
                previewProvider = provider
                val preview = Preview.Builder().build()
                preview.surfaceProvider = previewView.surfaceProvider
                provider.unbindAll()
                previewCamera = provider.bindToLifecycle(
                    this@CalibrationActivity,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                )
                previewHint.text = ""
                applyCurrentParameters()
            } catch (error: Exception) {
                previewHint.setText(R.string.preview_unavailable)
            }
        }
    }

    private fun unbindPreview() {
        previewCamera = null
        previewProvider?.unbindAll()
    }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
}
