package com.study.souti.capture

import android.graphics.Rect
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.MeteringRectangle
import android.util.Log
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import com.study.souti.config.CaptureProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

object CameraParameters {
    private const val TAG = "CameraParameters"
    private const val APPLY_TIMEOUT_MS = 2_000L
    private const val FOCUS_SETTLE_MS = 1_200L
    private const val FOCUS_RECT_HALF = 120

    /**
     * 把参数档一次性写入相机。
     * 返回 true 表示对焦已由参数档接管，调用方不必再做启动对焦。
     */
    @OptIn(ExperimentalCamera2Interop::class)
    suspend fun apply(camera: Camera, profile: CaptureProfile): Boolean {
        applyZoom(camera, profile.zoomRatio)

        val info = Camera2CameraInfo.from(camera.cameraInfo)
        val options = CaptureRequestOptions.Builder()
        val tapFocus = profile.focusMode == CaptureProfile.FOCUS_MODE_TAP
        val focusHandled = if (tapFocus) {
            applyTapFocus(info, profile.focusX, profile.focusY, options)
        } else {
            applyFixedFocus(info, profile, options)
        }
        applyExposure(info, profile, options)
        applyWhiteBalance(info, profile, options)
        applyOptions(camera, options.build())

        if (tapFocus && focusHandled) {
            delay(FOCUS_SETTLE_MS)
            applyOptions(camera, triggerIdleOptions())
        }
        return focusHandled
    }

    /** 取景页点击对焦：对指定归一化坐标做一次自动对焦。 */
    @OptIn(ExperimentalCamera2Interop::class)
    suspend fun focusAt(camera: Camera, x: Float, y: Float) {
        val info = Camera2CameraInfo.from(camera.cameraInfo)
        val afModes = info.getCameraCharacteristic(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)
        if (afModes == null || !afModes.contains(CaptureRequest.CONTROL_AF_MODE_AUTO)) return

        val rect = focusRect(x, y)
        val options = CaptureRequestOptions.Builder()
            .setCaptureRequestOption(
                CaptureRequest.CONTROL_AF_MODE,
                CaptureRequest.CONTROL_AF_MODE_AUTO,
            )
            .setCaptureRequestOption<Array<MeteringRectangle>>(
                CaptureRequest.CONTROL_AF_REGIONS,
                arrayOf(meteringRectangle(rect)),
            )
            .setCaptureRequestOption<Array<MeteringRectangle>>(
                CaptureRequest.CONTROL_AE_REGIONS,
                arrayOf(meteringRectangle(rect)),
            )
            .setCaptureRequestOption(
                CaptureRequest.CONTROL_AF_TRIGGER,
                CaptureRequest.CONTROL_AF_TRIGGER_START,
            )
            .build()
        applyOptions(camera, options)
        delay(FOCUS_SETTLE_MS)
        applyOptions(camera, triggerIdleOptions())
    }

    @OptIn(ExperimentalCamera2Interop::class)
    suspend fun applyOptions(camera: Camera, options: CaptureRequestOptions) {
        try {
            withContext(Dispatchers.IO) {
                Camera2CameraControl.from(camera.cameraControl)
                    .setCaptureRequestOptions(options)
                    .get(APPLY_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            }
        } catch (error: Exception) {
            Log.w(TAG, "相机参数未能应用", error)
        }
    }

    @OptIn(ExperimentalCamera2Interop::class)
    private fun triggerIdleOptions(): CaptureRequestOptions = CaptureRequestOptions.Builder()
        .setCaptureRequestOption(
            CaptureRequest.CONTROL_AF_TRIGGER,
            CaptureRequest.CONTROL_AF_TRIGGER_IDLE,
        )
        .build()

    private fun applyZoom(camera: Camera, zoomRatio: Float) {
        try {
            val state = camera.cameraInfo.zoomState.value
            val min = state?.minZoomRatio ?: CaptureProfile.MIN_ZOOM
            val max = state?.maxZoomRatio ?: CaptureProfile.MIN_ZOOM
            camera.cameraControl.setZoomRatio(zoomRatio.coerceIn(min, max))
        } catch (error: Exception) {
            Log.w(TAG, "变焦参数未能应用", error)
        }
    }

    @OptIn(ExperimentalCamera2Interop::class)
    private fun applyFixedFocus(
        info: Camera2CameraInfo,
        profile: CaptureProfile,
        options: CaptureRequestOptions.Builder,
    ): Boolean {
        val afModes = info.getCameraCharacteristic(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)
        val maxDiopter =
            info.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)
        if (afModes == null || maxDiopter == null || maxDiopter <= 0f) return false
        if (!afModes.contains(CaptureRequest.CONTROL_AF_MODE_OFF)) return false

        options.setCaptureRequestOption(
            CaptureRequest.CONTROL_AF_MODE,
            CaptureRequest.CONTROL_AF_MODE_OFF,
        )
        options.setCaptureRequestOption(
            CaptureRequest.LENS_FOCUS_DISTANCE,
            profile.diopter.coerceIn(0f, maxDiopter),
        )
        return true
    }

    @OptIn(ExperimentalCamera2Interop::class)
    private fun applyTapFocus(
        info: Camera2CameraInfo,
        x: Float,
        y: Float,
        options: CaptureRequestOptions.Builder,
    ): Boolean {
        val afModes = info.getCameraCharacteristic(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)
        if (afModes == null || !afModes.contains(CaptureRequest.CONTROL_AF_MODE_AUTO)) return false

        val rect = focusRect(x, y)
        options.setCaptureRequestOption(
            CaptureRequest.CONTROL_AF_MODE,
            CaptureRequest.CONTROL_AF_MODE_AUTO,
        )
        options.setCaptureRequestOption<Array<MeteringRectangle>>(
            CaptureRequest.CONTROL_AF_REGIONS,
            arrayOf(meteringRectangle(rect)),
        )
        options.setCaptureRequestOption<Array<MeteringRectangle>>(
            CaptureRequest.CONTROL_AE_REGIONS,
            arrayOf(meteringRectangle(rect)),
        )
        options.setCaptureRequestOption(
            CaptureRequest.CONTROL_AF_TRIGGER,
            CaptureRequest.CONTROL_AF_TRIGGER_START,
        )
        return true
    }

    private fun meteringRectangle(rect: Rect): MeteringRectangle =
        MeteringRectangle(rect, 1000)

    private fun focusRect(x: Float, y: Float): Rect {
        val centerX = (x * 2000f - 1000f).roundToInt().coerceIn(-1000, 1000)
        val centerY = (y * 2000f - 1000f).roundToInt().coerceIn(-1000, 1000)
        return Rect(
            (centerX - FOCUS_RECT_HALF).coerceAtLeast(-1000),
            (centerY - FOCUS_RECT_HALF).coerceAtLeast(-1000),
            (centerX + FOCUS_RECT_HALF).coerceAtMost(1000),
            (centerY + FOCUS_RECT_HALF).coerceAtMost(1000),
        )
    }

    @OptIn(ExperimentalCamera2Interop::class)
    private fun applyExposure(
        info: Camera2CameraInfo,
        profile: CaptureProfile,
        options: CaptureRequestOptions.Builder,
    ) {
        val range =
            info.getCameraCharacteristic(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
        val step = info.getCameraCharacteristic(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)
        if (range == null || step == null || step.toFloat() <= 0f) return
        val index = (profile.exposureEv / step.toFloat()).roundToInt()
            .coerceIn(range.lower, range.upper)
        options.setCaptureRequestOption(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, index)
    }

    @OptIn(ExperimentalCamera2Interop::class)
    private fun applyWhiteBalance(
        info: Camera2CameraInfo,
        profile: CaptureProfile,
        options: CaptureRequestOptions.Builder,
    ) {
        val available =
            info.getCameraCharacteristic(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES)
        val mode = awbModeFor(profile.whiteBalanceMode) ?: return
        if (available == null || !available.contains(mode)) return
        options.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, mode)
    }

    private fun awbModeFor(key: String): Int? = when (key) {
        "auto" -> CaptureRequest.CONTROL_AWB_MODE_AUTO
        "incandescent" -> CaptureRequest.CONTROL_AWB_MODE_INCANDESCENT
        "fluorescent" -> CaptureRequest.CONTROL_AWB_MODE_FLUORESCENT
        "daylight" -> CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT
        "cloudy" -> CaptureRequest.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT
        "shade" -> CaptureRequest.CONTROL_AWB_MODE_SHADE
        else -> null
    }
}
