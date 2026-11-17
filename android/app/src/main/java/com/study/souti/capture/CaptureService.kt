package com.study.souti.capture

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.hardware.display.DisplayManager
import android.os.Build
import android.util.Log
import android.view.Display
import android.view.OrientationEventListener
import android.view.Surface
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import com.study.souti.MainActivity
import com.study.souti.R
import com.study.souti.config.CaptureConfig
import com.study.souti.config.CapturePreferences
import com.study.souti.config.CaptureProfile
import com.study.souti.solver.DirectModelClient
import com.study.souti.solver.QuestionAnswer
import com.study.souti.upload.FrameApiClient
import com.study.souti.upload.PendingFrame
import com.study.souti.upload.UploadQueue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class CaptureService : LifecycleService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private val uploadQueue = UploadQueue(capacity = 2)
    private val uploadSignal = Channel<Unit>(Channel.CONFLATED)
    private val captureDirectory by lazy { File(cacheDir, "frame_capture") }

    private var cameraProvider: ProcessCameraProvider? = null
    private var activeCamera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var preview: Preview? = null
    private var apiClient: FrameApiClient? = null
    private var modelClient: DirectModelClient? = null
    private var activeRawFile: File? = null
    private var activeProcessedFile: File? = null
    private var foregroundStarted = false
    private var running = false
    private var sessionId = ""
    private var frameSequence = 0L
    private var lastRotation = Surface.ROTATION_0
    private var activeProfile: CaptureProfile? = null

    private val orientationListener by lazy {
        object : OrientationEventListener(this) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN) return
                val rotation = when (orientation) {
                    in 45 until 135 -> Surface.ROTATION_270
                    in 135 until 225 -> Surface.ROTATION_180
                    in 225 until 315 -> Surface.ROTATION_90
                    else -> Surface.ROTATION_0
                }
                if (rotation != lastRotation) {
                    lastRotation = rotation
                    imageCapture?.targetRotation = rotation
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        CaptureState.starting()
        createNotificationChannel()
        if (!enterForeground()) {
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_START -> {
                if (foregroundStarted && !running) startCapture()
            }
            ACTION_STOP -> requestStop()
            else -> requestStop()
        }
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        requestStop()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        running = false
        orientationListener.disable()
        serviceScope.cancel()
        uploadSignal.close()

        try {
            cameraProvider?.unbindAll()
        } catch (error: RuntimeException) {
            Log.w(TAG, "解绑相机时发生错误", error)
        }
        activeCamera = null
        imageCapture = null
        preview = null
        cameraProvider = null

        apiClient?.close()
        apiClient = null
        modelClient?.release()
        modelClient = null
        activeRawFile?.delete()
        activeProcessedFile?.delete()
        activeRawFile = null
        activeProcessedFile = null
        uploadQueue.clear().forEach { it.file.delete() }
        cameraExecutor.shutdownNow()

        if (foregroundStarted) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            foregroundStarted = false
        }
        CaptureState.stopped()
        super.onDestroy()
    }

    private fun startCapture() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            CaptureState.reportError("相机权限不可用，采集未启动")
            requestStop()
            return
        }

        val config = try {
            CapturePreferences(this).readConfig()
        } catch (error: IllegalArgumentException) {
            CaptureState.reportError(error.message ?: "采集配置无效")
            requestStop()
            return
        }

        try {
            prepareCaptureDirectory()
        } catch (error: IOException) {
            CaptureState.reportError(error.message ?: "无法准备图像目录")
            requestStop()
            return
        }

        sessionId = UUID.randomUUID().toString()
        frameSequence = 0L
        val client = FrameApiClient(config)
        val models = DirectModelClient()
        apiClient = client
        modelClient = models
        running = true

        serviceScope.launch { solveLoop(client, models) }
        serviceScope.launch { heartbeatLoop(client, sessionId) }
        serviceScope.launch { initializeCameraAndCapture(config) }
    }

    private suspend fun initializeCameraAndCapture(config: CaptureConfig) {
        try {
            val provider = ProcessCameraProvider.getInstance(this).await()
            cameraProvider = provider
            val selector = when {
                provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) ->
                    CameraSelector.DEFAULT_BACK_CAMERA
                provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) ->
                    CameraSelector.DEFAULT_FRONT_CAMERA
                else -> throw IOException("设备没有可用摄像头")
            }
            val rotation = currentDisplayRotation()
            val previewUseCase = Preview.Builder()
                .setTargetRotation(rotation)
                .build()
            val captureUseCase = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .setTargetRotation(rotation)
                .build()

            provider.unbindAll()
            val camera = provider.bindToLifecycle(
                this,
                selector,
                previewUseCase,
                captureUseCase,
            )
            activeCamera = camera
            preview = previewUseCase
            imageCapture = captureUseCase
            lastRotation = rotation
            val activeProfile = CapturePreferences(this@CaptureService).readActiveProfile()
            this@CaptureService.activeProfile = activeProfile
            val fixedFocusApplied =
                activeProfile != null && CameraParameters.apply(camera, activeProfile)
            if (!fixedFocusApplied) configureFixedFocus(camera)
            orientationListener.enable()
            CaptureState.running()
            captureLoop(config)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            CaptureState.reportError("相机初始化失败：${error.shortMessage()}")
            requestStop()
        }
    }

    @OptIn(ExperimentalCamera2Interop::class)
    private suspend fun configureFixedFocus(camera: Camera) {
        val availableModes = Camera2CameraInfo.from(camera.cameraInfo)
            .getCameraCharacteristic(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)
            ?: return
        val control = Camera2CameraControl.from(camera.cameraControl)

        val afMode = when {
            availableModes.contains(CaptureRequest.CONTROL_AF_MODE_AUTO) ->
                CaptureRequest.CONTROL_AF_MODE_AUTO
            availableModes.contains(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE) ->
                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
            else -> return
        }

        applyFocusOptions(
            control,
            CaptureRequestOptions.Builder()
                .setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, afMode)
                .build(),
        )

        if (afMode == CaptureRequest.CONTROL_AF_MODE_AUTO) {
            applyFocusOptions(
                control,
                CaptureRequestOptions.Builder()
                    .setCaptureRequestOption(
                        CaptureRequest.CONTROL_AF_TRIGGER,
                        CaptureRequest.CONTROL_AF_TRIGGER_START,
                    )
                    .build(),
            )
            delay(FOCUS_SETTLE_DELAY_MS)
            applyFocusOptions(
                control,
                CaptureRequestOptions.Builder()
                    .setCaptureRequestOption(
                        CaptureRequest.CONTROL_AF_TRIGGER,
                        CaptureRequest.CONTROL_AF_TRIGGER_IDLE,
                    )
                    .build(),
            )
        } else {
            delay(FOCUS_SETTLE_DELAY_MS)
        }
    }

    @OptIn(ExperimentalCamera2Interop::class)
    private suspend fun applyFocusOptions(
        control: Camera2CameraControl,
        options: CaptureRequestOptions,
    ) {
        try {
            withContext(Dispatchers.IO) {
                control.setCaptureRequestOptions(options)
                    .get(FOCUS_APPLY_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.w(TAG, "对焦参数未能应用", error)
        }
    }

    private suspend fun captureLoop(config: CaptureConfig) {
        while (currentCoroutineContext().isActive && running) {
            try {
                captureOne()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                emit("capture", "fail", "拍照或图像处理失败：${error.shortMessage()}")
                CaptureState.reportError("拍照或图像处理失败：${error.shortMessage()}")
            }
            delay(config.intervalSeconds * 1000L)
        }
    }

    private suspend fun captureOne() {
        val capture = imageCapture ?: throw IOException("相机尚未就绪")
        if (!captureDirectory.exists() && !captureDirectory.mkdirs()) {
            throw IOException("无法创建图像目录")
        }

        val frameId = UUID.randomUUID().toString()
        val rawFile = File(captureDirectory, "$frameId.raw.jpg")
        val processedFile = File(captureDirectory, "$frameId.jpg")
        val capturedAtEpochMs = System.currentTimeMillis()
        var handedToQueue = false
        activeRawFile = rawFile
        activeProcessedFile = processedFile

        try {
            val captureStartedAt = System.currentTimeMillis()
            takePicture(capture, rawFile)
            emit(
                "capture",
                "ok",
                "拍照完成 ${System.currentTimeMillis() - captureStartedAt}ms",
                frameId,
            )
            val processStartedAt = System.currentTimeMillis()
            withContext(Dispatchers.IO) {
                val crop = activeProfile?.let {
                    ImageProcessor.Crop(
                        left = it.cropLeft,
                        top = it.cropTop,
                        right = it.cropRight,
                        bottom = it.cropBottom,
                    )
                } ?: ImageProcessor.Crop.FULL
                ImageProcessor.process(rawFile, processedFile, crop)
            }
            emit(
                "process",
                "ok",
                "裁剪压缩完成 ${System.currentTimeMillis() - processStartedAt}ms",
                frameId,
            )
            val frame = PendingFrame(
                sessionId = sessionId,
                deviceFrameId = frameId,
                capturedAtEpochMs = capturedAtEpochMs,
                file = processedFile,
                sequence = ++frameSequence,
            )
            val evicted = uploadQueue.enqueue(frame)
            handedToQueue = true
            if (evicted != null) {
                withContext(NonCancellable + Dispatchers.IO) { evicted.file.delete() }
            }
            uploadSignal.trySend(Unit)
        } finally {
            activeRawFile = null
            activeProcessedFile = null
            withContext(NonCancellable + Dispatchers.IO) {
                rawFile.delete()
                if (!handedToQueue) processedFile.delete()
            }
        }
    }

    private suspend fun takePicture(capture: ImageCapture, outputFile: File) {
        suspendCancellableCoroutine<Unit> { continuation ->
            continuation.invokeOnCancellation { outputFile.delete() }
            val options = ImageCapture.OutputFileOptions.Builder(outputFile).build()
            try {
                capture.takePicture(
                    options,
                    cameraExecutor,
                    object : ImageCapture.OnImageSavedCallback {
                        override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                            if (continuation.isActive) {
                                continuation.resume(Unit)
                            } else {
                                outputFile.delete()
                            }
                        }

                        override fun onError(exception: ImageCaptureException) {
                            outputFile.delete()
                            if (continuation.isActive) {
                                continuation.resumeWithException(
                                    IOException("拍照失败：${exception.shortMessage()}", exception),
                                )
                            }
                        }
                    },
                )
            } catch (error: RuntimeException) {
                outputFile.delete()
                if (continuation.isActive) continuation.resumeWithException(error)
            }
        }
    }

    private suspend fun solveLoop(client: FrameApiClient, models: DirectModelClient) {
        while (currentCoroutineContext().isActive && running) {
            uploadSignal.receive()
            while (currentCoroutineContext().isActive && running) {
                val (frame, superseded) = uploadQueue.pollLatest() ?: break
                if (superseded.isNotEmpty()) {
                    withContext(NonCancellable + Dispatchers.IO) {
                        superseded.forEach { it.file.delete() }
                    }
                }
                try {
                    emit("model", "running", "请求 Qwen + DeepSeek", frame.deviceFrameId)
                    models.solve(
                        frame.file,
                        onEvent = { status, message ->
                            emit("model", status, message, frame.deviceFrameId)
                        },
                    ) { answers, isFinal ->
                        submitAnswers(client, frame, answers, isFinal)
                    }
                    CaptureState.uploaded(Instant.now().toString())
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    emit("model", "fail", "答题失败：${error.shortMessage()}", frame.deviceFrameId)
                    CaptureState.reportError("答题失败：${error.shortMessage()}")
                } finally {
                    withContext(NonCancellable + Dispatchers.IO) { frame.file.delete() }
                }
            }
        }
    }

    private suspend fun submitAnswers(
        client: FrameApiClient,
        frame: PendingFrame,
        answers: List<QuestionAnswer>,
        isFinal: Boolean,
    ) {
        val kind = if (isFinal) "最终" else "暂定"
        emit("post", "running", "提交 ${answers.size} 题（$kind）", frame.deviceFrameId)
        try {
            withContext(Dispatchers.IO) { client.reportAnswers(frame, answers, isFinal) }
            emit("post", "ok", "云端已接收 ${answers.size} 题", frame.deviceFrameId)
            val timestamp = Instant.now().toString()
            if (!isFinal) {
                CaptureState.uploaded(timestamp)
            } else {
                emit("done", "ok", "本轮完成，共 ${answers.size} 题", frame.deviceFrameId)
                CaptureState.succeeded(timestamp)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            // 云端暂时不可达时继续等 DeepSeek 结果，不中断本次答题。
            emit("post", "fail", "答案提交失败：${error.shortMessage()}", frame.deviceFrameId)
            CaptureState.reportError("答案提交失败：${error.shortMessage()}")
        }
    }

    /** 同步更新手机界面，并尽力把同一事件送到云端状态页。 */
    private fun emit(stage: String, status: String, detail: String, frameId: String = "") {
        CaptureState.stage(STAGE_LABELS[stage] ?: stage, detail)
        val client = apiClient ?: return
        serviceScope.launch(Dispatchers.IO) {
            runCatching { client.reportEvent(sessionId, frameId, stage, status, detail) }
        }
    }

    private suspend fun heartbeatLoop(client: FrameApiClient, sessionId: String) {
        while (currentCoroutineContext().isActive && running) {
            try {
                withContext(Dispatchers.IO) { client.heartbeat(sessionId) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                CaptureState.reportError("心跳失败：${error.shortMessage()}")
            }
            delay(HEARTBEAT_INTERVAL_MS)
        }
    }

    private fun prepareCaptureDirectory() {
        if (captureDirectory.exists()) {
            if (!captureDirectory.isDirectory) throw IOException("图像缓存路径不是目录")
            captureDirectory.listFiles()?.forEach { file ->
                if (!file.delete()) Log.w(TAG, "无法删除过期缓存文件：${file.name}")
            }
        } else if (!captureDirectory.mkdirs()) {
            throw IOException("无法创建图像缓存目录")
        }
    }

    private fun currentDisplayRotation(): Int {
        val manager = getSystemService(DisplayManager::class.java)
        return manager.getDisplay(Display.DEFAULT_DISPLAY)?.rotation ?: Surface.ROTATION_0
    }

    private fun requestStop() {
        running = false
        CaptureState.stopping()
        stopSelf()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notification_channel_description)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun enterForeground(): Boolean {
        val notification = buildNotification()
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA,
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            foregroundStarted = true
            true
        } catch (error: RuntimeException) {
            CaptureState.reportError("前台服务启动失败：${error.shortMessage()}")
            false
        }
    }

    private fun buildNotification(): android.app.Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            1,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this,
            2,
            Intent(this, CaptureService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_camera)
            .setColor(ContextCompat.getColor(this, R.color.primary))
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, getString(R.string.notification_stop), stopIntent)
            .build()
    }

    private fun Throwable.shortMessage(): String = message ?: javaClass.simpleName

    companion object {
        private const val TAG = "CaptureService"
        private const val ACTION_START = "com.study.souti.action.START_CAPTURE"
        private const val ACTION_STOP = "com.study.souti.action.STOP_CAPTURE"
        private const val NOTIFICATION_CHANNEL_ID = "capture_service"
        private const val NOTIFICATION_ID = 1001
        private const val FOCUS_SETTLE_DELAY_MS = 1_500L
        private const val FOCUS_APPLY_TIMEOUT_MS = 2_000L
        private const val HEARTBEAT_INTERVAL_MS = 15_000L
        private val STAGE_LABELS = mapOf(
            "capture" to "拍照",
            "process" to "裁剪",
            "model" to "模型",
            "post" to "上报",
            "done" to "完成",
        )

        fun start(context: Context) {
            val intent = Intent(context, CaptureService::class.java).setAction(ACTION_START)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            CaptureState.stopping()
            val stopped = context.stopService(Intent(context, CaptureService::class.java))
            if (!stopped) CaptureState.stopped()
        }
    }
}
