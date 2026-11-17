package com.study.souti

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.study.souti.capture.CaptureService
import com.study.souti.capture.CaptureState
import com.study.souti.capture.CaptureUiState
import com.study.souti.config.CaptureConfig
import com.study.souti.config.CapturePreferences
import com.study.souti.config.CaptureProfile
import com.study.souti.ui.CalibrationActivity
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private lateinit var preferences: CapturePreferences
    private lateinit var serverUrlInput: EditText
    private lateinit var deviceIdInput: EditText
    private lateinit var deviceTokenInput: EditText
    private lateinit var intervalInput: EditText
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var statusText: TextView
    private lateinit var stageText: TextView
    private lateinit var stageDetailText: TextView
    private lateinit var logText: TextView
    private lateinit var lastUploadText: TextView
    private lateinit var errorText: TextView
    private lateinit var profileSpinner: Spinner

    private var profiles: MutableList<CaptureProfile> = mutableListOf()
    private var applyingProfile = false
    private var startAfterPermissions = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        val shouldStart = startAfterPermissions
        startAfterPermissions = false
        if (!shouldStart) return@registerForActivityResult

        if (!hasPermission(Manifest.permission.CAMERA)) {
            CaptureState.reportError(getString(R.string.camera_permission_required))
            return@registerForActivityResult
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !hasPermission(Manifest.permission.POST_NOTIFICATIONS)
        ) {
            Toast.makeText(this, R.string.notification_permission_denied, Toast.LENGTH_LONG).show()
        }
        startCaptureService()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        preferences = CapturePreferences(this)
        serverUrlInput = findViewById(R.id.serverUrlInput)
        deviceIdInput = findViewById(R.id.deviceIdInput)
        deviceTokenInput = findViewById(R.id.deviceTokenInput)
        intervalInput = findViewById(R.id.intervalInput)
        startButton = findViewById(R.id.startButton)
        stopButton = findViewById(R.id.stopButton)
        statusText = findViewById(R.id.statusText)
        stageText = findViewById(R.id.stageText)
        stageDetailText = findViewById(R.id.stageDetailText)
        logText = findViewById(R.id.logText)
        lastUploadText = findViewById(R.id.lastUploadText)
        errorText = findViewById(R.id.errorText)
        profileSpinner = findViewById(R.id.profileSpinner)

        serverUrlInput.setText(preferences.serverUrl)
        deviceIdInput.setText(preferences.deviceId)
        deviceTokenInput.setText(preferences.deviceToken)
        intervalInput.setText(preferences.intervalSeconds.toString())

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
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        findViewById<Button>(R.id.openCalibrationButton).setOnClickListener {
            startActivity(Intent(this, CalibrationActivity::class.java))
        }
        findViewById<Button>(R.id.saveButton).setOnClickListener {
            saveConfiguration(showConfirmation = true)
        }
        startButton.setOnClickListener {
            if (saveConfiguration(showConfirmation = false) != null) {
                requestPermissionsAndStart()
            }
        }
        stopButton.setOnClickListener {
            CaptureService.stop(this)
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                CaptureState.state.collect(::render)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshProfiles()
    }

    private fun refreshProfiles() {
        profiles = preferences.profiles.toMutableList()
        if (profiles.isEmpty()) {
            profiles.add(CaptureProfile.defaultProfile())
            preferences.saveProfiles(profiles, profiles.first().name)
        }
        val names = profiles.map { it.name }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, names)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        applyingProfile = true
        profileSpinner.adapter = adapter
        val index = names.indexOf(preferences.activeProfileName).coerceAtLeast(0)
        if (names.isNotEmpty()) {
            profileSpinner.setSelection(index)
        }
        applyingProfile = false
    }

    private fun saveConfiguration(showConfirmation: Boolean): CaptureConfig? {
        return try {
            val config = CaptureConfig.create(
                serverUrl = serverUrlInput.text.toString(),
                deviceId = deviceIdInput.text.toString(),
                deviceToken = deviceTokenInput.text.toString(),
                intervalSeconds = intervalInput.text.toString().toInt(),
            )
            preferences.save(config)
            if (showConfirmation) {
                Toast.makeText(this, R.string.settings_saved, Toast.LENGTH_SHORT).show()
            }
            config
        } catch (error: IllegalArgumentException) {
            errorText.text = getString(R.string.error_format, error.message ?: "配置无效")
            Toast.makeText(this, error.message ?: "配置无效", Toast.LENGTH_LONG).show()
            null
        }
    }

    private fun requestPermissionsAndStart() {
        val missingPermissions = mutableListOf<String>()
        if (!hasPermission(Manifest.permission.CAMERA)) {
            missingPermissions += Manifest.permission.CAMERA
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !hasPermission(Manifest.permission.POST_NOTIFICATIONS)
        ) {
            missingPermissions += Manifest.permission.POST_NOTIFICATIONS
        }

        if (missingPermissions.isEmpty()) {
            startCaptureService()
        } else {
            startAfterPermissions = true
            permissionLauncher.launch(missingPermissions.toTypedArray())
        }
    }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun startCaptureService() {
        try {
            CaptureService.start(this)
        } catch (error: RuntimeException) {
            CaptureState.reportError("无法启动采集服务：${error.message ?: error.javaClass.simpleName}")
        }
    }

    private fun render(state: CaptureUiState) {
        statusText.text = getString(R.string.status_format, state.status)
        stageText.text = getString(R.string.stage_format, state.stage)
        stageDetailText.text = state.stageDetail.ifBlank { "-" }
        logText.text = state.logs.joinToString("\n").ifBlank { "暂无日志" }
        lastUploadText.text = getString(R.string.last_upload_format, state.lastUpload)
        errorText.text = getString(R.string.error_format, state.error)
        startButton.isEnabled = !state.isRunning
        stopButton.isEnabled = state.isRunning
    }
}
