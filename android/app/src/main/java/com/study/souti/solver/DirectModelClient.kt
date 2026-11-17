package com.study.souti.solver

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

data class QuestionAnswer(
    val no: String,
    val stem: String,
    val answer: String,
    val method: String,
    val confidence: Double,
)

object DirectModelConfig {
    // 图片不经过自建服务器：手机直接请求模型接口，密钥内置在 APP 中。
    //
    // ===== 使用前必须修改 =====
    // 把下面的 API_KEY 改成你自己的 SiliconFlow 密钥。
    // 获取地址：https://cloud.siliconflow.cn/account/ak
    // 改完后重新打包 APK。
    const val BASE_URL = "https://api.siliconflow.cn/v1"
    const val API_KEY = "在这里填入你的SiliconFlow_API_Key"
    const val QWEN_MODEL = "Qwen/Qwen3-VL-32B-Instruct"
    const val DEEPSEEK_MODEL = "deepseek-ai/DeepSeek-V4.1-Flash"
    const val FALLBACK_DELAY_MS = 45_000L
    const val REQUEST_TIMEOUT_MS = 120_000L
}

class DirectModelClient {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .readTimeout(DirectModelConfig.REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .callTimeout(DirectModelConfig.REQUEST_TIMEOUT_MS + 10_000L, TimeUnit.MILLISECONDS)
        .build()

    /**
     * 两个模型并发直答：DeepSeek 有结果优先展示；Qwen 先到则最多等 45 秒，
     * 之后先展示 Qwen，迟到的 DeepSeek 结果仍会再次提交覆盖。
     */
    suspend fun solve(
        imageFile: File,
        onEvent: (status: String, message: String) -> Unit = { _, _ -> },
        onUpdate: suspend (answers: List<QuestionAnswer>, isFinal: Boolean) -> Unit,
    ) = coroutineScope {
        val payload = withContext(Dispatchers.IO) { buildPayload(imageFile) }
        val events = Channel<Event>(Channel.UNLIMITED)

        val qwenJob = launch(Dispatchers.IO) {
            val startedAt = System.currentTimeMillis()
            val result = runCatching { request(QWEN, payload) }
            events.send(
                Event.Source(SOURCE_QWEN, result, System.currentTimeMillis() - startedAt),
            )
        }
        val deepseekJob = launch(Dispatchers.IO) {
            val startedAt = System.currentTimeMillis()
            val result = runCatching { request(DEEPSEEK, payload) }
            events.send(
                Event.Source(SOURCE_DEEPSEEK, result, System.currentTimeMillis() - startedAt),
            )
        }
        val deadlineJob = launch {
            delay(DirectModelConfig.FALLBACK_DELAY_MS)
            events.send(Event.Deadline)
        }

        var qwen: List<QuestionAnswer>? = null
        var deepseek: List<QuestionAnswer>? = null
        var qwenError: Throwable? = null
        var deepseekError: Throwable? = null
        var qwenDone = false
        var deepseekDone = false
        var published: List<QuestionAnswer>? = null
        val startedAt = System.currentTimeMillis()

        try {
            while (!(qwenDone && deepseekDone)) {
                when (val event = events.receive()) {
                    is Event.Source -> {
                        if (event.source == SOURCE_QWEN) {
                            qwen = event.result.getOrNull()
                            qwenError = event.result.exceptionOrNull()
                            qwenDone = true
                        } else {
                            deepseek = event.result.getOrNull()
                            deepseekError = event.result.exceptionOrNull()
                            deepseekDone = true
                        }
                        val label = if (event.source == SOURCE_QWEN) "Qwen" else "DeepSeek"
                        event.result.onSuccess {
                            onEvent("ok", "$label 返回 ${it.size} 题 ${event.elapsedMs}ms")
                        }.onFailure {
                            onEvent(
                                "fail",
                                "$label 失败：${it.message ?: it.javaClass.simpleName}",
                            )
                        }
                    }
                    Event.Deadline -> Unit
                }
                if (qwenDone && deepseekDone) break

                val merged = mergeAnswers(qwen, deepseek)
                val elapsed = System.currentTimeMillis() - startedAt
                val canPublish = deepseek != null || deepseekDone ||
                    elapsed >= DirectModelConfig.FALLBACK_DELAY_MS
                if (canPublish && merged.isNotEmpty() && merged != published) {
                    onUpdate(merged, false)
                    published = merged
                }
            }
            val merged = mergeAnswers(qwen, deepseek)
            if (merged.isEmpty()) {
                // 两个模型都失败时把真实原因带回界面，避免“无答案但看起来正常”。
                val failures = listOfNotNull(qwenError, deepseekError)
                if (failures.isNotEmpty()) {
                    val reason = failures
                        .map { it.message?.take(120) ?: it.javaClass.simpleName }
                        .distinct()
                        .joinToString("；")
                    throw IOException("模型请求失败：$reason")
                }
            }
            onUpdate(merged, true)
        } finally {
            deadlineJob.cancel()
            qwenJob.cancel()
            deepseekJob.cancel()
            events.close()
        }
    }

    private fun buildPayload(imageFile: File): String {
        val bytes = imageFile.readBytes()
        if (bytes.isEmpty()) throw IOException("待答题图像为空")
        val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
        val content = JSONArray()
            .put(JSONObject().put("type", "text").put("text", DIRECT_ANSWER_PROMPT))
            .put(
                JSONObject().put("type", "image_url").put(
                    "image_url",
                    JSONObject().put("url", "data:image/jpeg;base64,$base64"),
                ),
            )
        return JSONObject()
            .put("temperature", 0.0)
            .put("max_tokens", 2048)
            .put(
                "messages",
                JSONArray().put(JSONObject().put("role", "user").put("content", content)),
            )
            .toString()
    }

    private fun request(model: String, payload: String): List<QuestionAnswer> {
        val method = if (model == DirectModelConfig.DEEPSEEK_MODEL) METHOD_DEEPSEEK else METHOD_QWEN
        val body = JSONObject(payload).put("model", model)
        if (method == METHOD_DEEPSEEK) body.put("reasoning_effort", "low")

        val request = Request.Builder()
            .url("${DirectModelConfig.BASE_URL}/chat/completions")
            .header("Authorization", "Bearer ${DirectModelConfig.API_KEY}")
            .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val detail = response.body?.string()
                    ?.replace("\n", " ")
                    ?.take(160)
                    .orEmpty()
                throw IOException(
                    "模型返回 HTTP ${response.code}" + if (detail.isBlank()) "" else "：$detail",
                )
            }
            val raw = JSONObject(response.body?.string() ?: "")
                .getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
            return parseAnswers(raw, method)
        }
    }

    fun release() {
        client.dispatcher.cancelAll()
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdown()
    }

    private sealed interface Event {
        data class Source(
            val source: String,
            val result: Result<List<QuestionAnswer>>,
            val elapsedMs: Long,
        ) : Event

        data object Deadline : Event
    }

    private companion object {
        const val SOURCE_QWEN = "qwen"
        const val SOURCE_DEEPSEEK = "deepseek"
        const val METHOD_QWEN = "dual_qwen"
        const val METHOD_DEEPSEEK = "dual_deepseek"
        const val QWEN = DirectModelConfig.QWEN_MODEL
        const val DEEPSEEK = DirectModelConfig.DEEPSEEK_MODEL
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        const val DIRECT_ANSWER_PROMPT =
            "你是高速考试答题引擎。仔细看图片中完整显示的单选题，独立判断正确答案。" +
                "答案可能是 A 到 H 中的任意一个字母。只输出 JSON 数组，" +
                "每项格式为 {\"no\":\"题号\",\"stem\":\"题干前30个字符\",\"answer\":\"A\"}。" +
                "不要输出解析、思考过程或 Markdown。仅回答题干和选项都完整的题，残片不答；" +
                "没有完整题目就输出 []。页面已有的勾选、颜色或历史答案标记可能错误，不要照搬。"
    }
}

/** 解析模型返回的 JSON 数组，只保留 A-H 的完整答案。 */
internal fun parseAnswers(raw: String, method: String): List<QuestionAnswer> {
    val text = raw.trim()
    val start = text.indexOf('[')
    val end = text.lastIndexOf(']')
    if (start < 0 || end < start) throw IOException("模型未返回 JSON 数组")

    val array = JSONArray(text.substring(start, end + 1))
    val results = mutableListOf<QuestionAnswer>()
    for (index in 0 until array.length()) {
        val item = array.optJSONObject(index) ?: continue
        val answer = item.optString("answer").trim().uppercase()
        if (answer.length != 1 || answer[0] !in 'A'..'H') continue
        results += QuestionAnswer(
            no = item.optString("no").trim(),
            stem = item.optString("stem").trim().take(30),
            answer = answer,
            method = method,
            confidence = if (method == "dual_deepseek") 1.0 else 0.8,
        )
    }
    return results
}

/** DeepSeek 覆盖同题，Qwen 独有题目保留。 */
internal fun mergeAnswers(
    qwen: List<QuestionAnswer>?,
    deepseek: List<QuestionAnswer>?,
): List<QuestionAnswer> {
    val merged = LinkedHashMap<String, QuestionAnswer>()
    for (item in qwen.orEmpty()) merged[keyOf(item)] = item
    for (item in deepseek.orEmpty()) {
        val key = keyOf(item)
        val existing = merged[key]
        merged[key] = if (existing != null && item.stem.isEmpty()) {
            item.copy(stem = existing.stem)
        } else {
            item
        }
    }
    return merged.values.toList()
}

private fun keyOf(item: QuestionAnswer): String =
    if (item.no.isNotEmpty()) "no:${item.no}" else "stem:${item.stem}"
