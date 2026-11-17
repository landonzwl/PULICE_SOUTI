package com.study.souti.upload

import com.study.souti.config.CaptureConfig
import com.study.souti.solver.QuestionAnswer
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.TimeUnit

/** 向云端答案服务提交文字结果；图片不经过服务器。 */
class FrameApiClient(private val config: CaptureConfig) : Closeable {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS)
        .build()

    // 流水线事件只需尽力上报，短超时避免拖住答题主流程。
    private val eventClient = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .writeTimeout(4, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .callTimeout(5, TimeUnit.SECONDS)
        .build()

    fun reportAnswers(frame: PendingFrame, answers: List<QuestionAnswer>, isFinal: Boolean) {
        val payload = JSONObject()
            .put("device_id", config.deviceId)
            .put("client_frame_id", frame.deviceFrameId)
            .put("captured_at", frame.capturedAtEpochMs / 1000.0)
            .put("final", isFinal)
            .put(
                "answers",
                JSONArray().apply {
                    answers.forEach { answer ->
                        put(
                            JSONObject()
                                .put("no", answer.no)
                                .put("stem", answer.stem)
                                .put("answer", answer.answer)
                                .put("method", answer.method)
                                .put("confidence", answer.confidence),
                        )
                    }
                },
            )
            .toString()
            .toRequestBody(JSON_MEDIA_TYPE)
        val request = authorizedRequest("/api/v1/answers").post(payload).build()
        execute(request)
    }

    fun heartbeat(sessionId: String) {
        val payload = JSONObject()
            .put("session_id", sessionId)
            .toString()
            .toRequestBody(JSON_MEDIA_TYPE)
        val request = authorizedRequest("/api/v1/devices/heartbeat")
            .post(payload)
            .build()
        execute(request)
    }

    fun reportEvent(
        sessionId: String,
        frameId: String,
        stage: String,
        status: String,
        message: String,
    ) {
        val payload = JSONObject()
            .put("device_id", config.deviceId)
            .put("session_id", sessionId)
            .put("client_frame_id", frameId)
            .put("stage", stage)
            .put("status", status)
            .put("message", message)
            .toString()
            .toRequestBody(JSON_MEDIA_TYPE)
        val request = authorizedRequest("/api/v1/events").post(payload).build()
        eventClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("事件上报失败 HTTP ${response.code}")
            }
        }
    }

    private fun authorizedRequest(path: String): Request.Builder = Request.Builder()
        .url(config.serverUrl + path)
        .header("Authorization", "Bearer ${config.deviceToken}")
        .header("X-Device-ID", config.deviceId)

    private fun execute(request: Request) {
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("服务器返回 HTTP ${response.code}")
            }
        }
    }

    override fun close() {
        client.dispatcher.cancelAll()
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdown()
        eventClient.dispatcher.cancelAll()
        eventClient.connectionPool.evictAll()
        eventClient.dispatcher.executorService.shutdown()
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
