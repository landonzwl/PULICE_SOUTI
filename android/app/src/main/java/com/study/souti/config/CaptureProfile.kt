package com.study.souti.config

import org.json.JSONArray
import org.json.JSONObject

data class CaptureProfile(
    val name: String,
    val focusMode: String,
    val focusDistanceCm: Int,
    val focusX: Float,
    val focusY: Float,
    val zoomRatio: Float,
    val exposureEv: Float,
    val whiteBalanceMode: String,
    val cropLeft: Float,
    val cropTop: Float,
    val cropRight: Float,
    val cropBottom: Float,
) {
    val diopter: Float
        get() = 100f / focusDistanceCm

    val cropWidth: Float
        get() = 1f - cropLeft - cropRight

    val cropHeight: Float
        get() = 1f - cropTop - cropBottom

    companion object {
        const val FOCUS_MODE_FIXED = "fixed"
        const val FOCUS_MODE_TAP = "tap"

        const val MIN_FOCUS_CM = 10
        const val MAX_FOCUS_CM = 100
        const val MIN_ZOOM = 1.0f
        const val MAX_ZOOM = 5.0f
        const val MIN_EV = -2.0f
        const val MAX_EV = 2.0f
        const val MAX_CROP_MARGIN = 0.45f
        const val MIN_CROP_SIDE = 0.2f
        const val DEFAULT_NAME = "默认"
        const val DEFAULT_FOCUS_CM = 30
        const val DEFAULT_WB = "auto"

        val FOCUS_MODES = listOf(FOCUS_MODE_FIXED, FOCUS_MODE_TAP)

        val WHITE_BALANCE_KEYS = listOf(
            "auto",
            "incandescent",
            "fluorescent",
            "daylight",
            "cloudy",
            "shade",
        )

        fun create(
            name: String,
            focusMode: String,
            focusDistanceCm: Int,
            focusX: Float,
            focusY: Float,
            zoomRatio: Float,
            exposureEv: Float,
            whiteBalanceMode: String,
            cropLeft: Float,
            cropTop: Float,
            cropRight: Float,
            cropBottom: Float,
        ): CaptureProfile {
            val trimmedName = name.trim()
            require(trimmedName.isNotEmpty()) { "参数档名称不能为空" }
            require(trimmedName.length <= 24) { "参数档名称不能超过 24 个字符" }
            require(focusMode in FOCUS_MODES) { "对焦方式无效" }
            require(focusDistanceCm in MIN_FOCUS_CM..MAX_FOCUS_CM) {
                "对焦距离必须在 $MIN_FOCUS_CM 到 $MAX_FOCUS_CM 厘米之间"
            }
            require(focusX in 0f..1f && focusY in 0f..1f) { "焦点坐标必须位于画面内" }
            require(zoomRatio in MIN_ZOOM..MAX_ZOOM) {
                "变焦倍率必须在 $MIN_ZOOM 到 $MAX_ZOOM 之间"
            }
            require(exposureEv in MIN_EV..MAX_EV) {
                "曝光补偿必须在 $MIN_EV 到 $MAX_EV 之间"
            }
            require(whiteBalanceMode in WHITE_BALANCE_KEYS) { "白平衡模式无效" }
            val margins = listOf(cropLeft, cropTop, cropRight, cropBottom)
            require(margins.all { it in 0f..MAX_CROP_MARGIN }) {
                "裁剪边距必须在 0 到 ${(MAX_CROP_MARGIN * 100).toInt()}% 之间"
            }
            require(1f - cropLeft - cropRight >= MIN_CROP_SIDE) { "裁剪后宽度不能小于 20%" }
            require(1f - cropTop - cropBottom >= MIN_CROP_SIDE) { "裁剪后高度不能小于 20%" }
            return CaptureProfile(
                name = trimmedName,
                focusMode = focusMode,
                focusDistanceCm = focusDistanceCm,
                focusX = focusX,
                focusY = focusY,
                zoomRatio = zoomRatio,
                exposureEv = exposureEv,
                whiteBalanceMode = whiteBalanceMode,
                cropLeft = cropLeft,
                cropTop = cropTop,
                cropRight = cropRight,
                cropBottom = cropBottom,
            )
        }

        fun defaultProfile(): CaptureProfile = CaptureProfile(
            name = DEFAULT_NAME,
            focusMode = FOCUS_MODE_FIXED,
            focusDistanceCm = DEFAULT_FOCUS_CM,
            focusX = 0.5f,
            focusY = 0.5f,
            zoomRatio = MIN_ZOOM,
            exposureEv = 0f,
            whiteBalanceMode = DEFAULT_WB,
            cropLeft = 0f,
            cropTop = 0f,
            cropRight = 0f,
            cropBottom = 0f,
        )
    }
}

object CaptureProfileCodec {
    fun encode(profiles: List<CaptureProfile>): String {
        val array = JSONArray()
        profiles.forEach { profile ->
            array.put(
                JSONObject()
                    .put("name", profile.name)
                    .put("focusMode", profile.focusMode)
                    .put("focus", profile.focusDistanceCm)
                    .put("focusX", profile.focusX.toDouble())
                    .put("focusY", profile.focusY.toDouble())
                    .put("zoom", profile.zoomRatio.toDouble())
                    .put("ev", profile.exposureEv.toDouble())
                    .put("wb", profile.whiteBalanceMode)
                    .put("cropLeft", profile.cropLeft.toDouble())
                    .put("cropTop", profile.cropTop.toDouble())
                    .put("cropRight", profile.cropRight.toDouble())
                    .put("cropBottom", profile.cropBottom.toDouble()),
            )
        }
        return array.toString()
    }

    fun decode(raw: String?): List<CaptureProfile> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            val array = JSONArray(raw)
            val profiles = mutableListOf<CaptureProfile>()
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val name = item.optString("name").trim()
                if (name.isEmpty()) continue
                runCatching {
                    profiles += CaptureProfile.create(
                        name = name,
                        focusMode = item.optString("focusMode", CaptureProfile.FOCUS_MODE_FIXED),
                        focusDistanceCm = item.optInt("focus", CaptureProfile.DEFAULT_FOCUS_CM),
                        focusX = item.optDouble("focusX", 0.5).toFloat(),
                        focusY = item.optDouble("focusY", 0.5).toFloat(),
                        zoomRatio = item.optDouble("zoom", 1.0).toFloat(),
                        exposureEv = item.optDouble("ev", 0.0).toFloat(),
                        whiteBalanceMode = item.optString("wb", CaptureProfile.DEFAULT_WB),
                        cropLeft = item.optDouble("cropLeft", 0.0).toFloat(),
                        cropTop = item.optDouble("cropTop", 0.0).toFloat(),
                        cropRight = item.optDouble("cropRight", 0.0).toFloat(),
                        cropBottom = item.optDouble("cropBottom", 0.0).toFloat(),
                    )
                }
            }
            profiles
        } catch (error: Exception) {
            emptyList()
        }
    }
}
