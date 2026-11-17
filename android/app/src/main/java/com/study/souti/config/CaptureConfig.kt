package com.study.souti.config

import java.net.URI

data class CaptureConfig(
    val serverUrl: String,
    val deviceId: String,
    val deviceToken: String,
    val intervalSeconds: Int,
) {
    companion object {
        const val DEFAULT_INTERVAL_SECONDS = 3

        fun create(
            serverUrl: String,
            deviceId: String,
            deviceToken: String,
            intervalSeconds: Int,
        ): CaptureConfig {
            val normalizedUrl = normalizeServerUrl(serverUrl)
            val normalizedDeviceId = deviceId.trim()
            val normalizedToken = deviceToken.trim()

            require(normalizedDeviceId.isNotEmpty()) { "设备 ID 不能为空" }
            require(normalizedDeviceId.none { it == '\r' || it == '\n' }) { "设备 ID 不能包含换行" }
            require(normalizedToken.isNotEmpty()) { "设备 Bearer 令牌不能为空" }
            require(normalizedToken.none { it == '\r' || it == '\n' }) { "设备令牌不能包含换行" }
            require(intervalSeconds in 3..3600) { "采集间隔必须在 3 到 3600 秒之间" }

            return CaptureConfig(
                serverUrl = normalizedUrl,
                deviceId = normalizedDeviceId,
                deviceToken = normalizedToken,
                intervalSeconds = intervalSeconds,
            )
        }

        fun normalizeServerUrl(serverUrl: String): String {
            val value = serverUrl.trim().trimEnd('/')
            require(value.isNotEmpty()) { "服务器 URL 不能为空" }
            val uri = URI(value)
            require(uri.scheme.equals("http", ignoreCase = true) ||
                uri.scheme.equals("https", ignoreCase = true)) {
                "服务器 URL 仅支持 HTTP 或 HTTPS"
            }
            require(!uri.host.isNullOrBlank()) { "服务器 URL 必须包含有效主机" }
            require(uri.userInfo == null) { "服务器 URL 不能包含用户名或密码" }
            require(uri.query == null && uri.fragment == null) { "服务器 URL 不能包含查询参数或片段" }
            return value
        }
    }
}
