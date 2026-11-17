package com.study.souti.config

import android.content.Context

class CapturePreferences(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        "capture_settings",
        Context.MODE_PRIVATE,
    )

    val serverUrl: String
        get() = preferences.getString(KEY_SERVER_URL, DEFAULT_SERVER_URL) ?: DEFAULT_SERVER_URL

    val deviceId: String
        get() = preferences.getString(KEY_DEVICE_ID, DEFAULT_DEVICE_ID) ?: DEFAULT_DEVICE_ID

    val deviceToken: String
        get() = preferences.getString(KEY_DEVICE_TOKEN, DEFAULT_DEVICE_TOKEN)
            ?: DEFAULT_DEVICE_TOKEN

    val intervalSeconds: Int
        get() = preferences.getInt(KEY_INTERVAL_SECONDS, CaptureConfig.DEFAULT_INTERVAL_SECONDS)

    fun readConfig(): CaptureConfig = CaptureConfig.create(
        serverUrl = serverUrl,
        deviceId = deviceId,
        deviceToken = deviceToken,
        intervalSeconds = intervalSeconds,
    )

    fun save(config: CaptureConfig) {
        preferences.edit()
            .putString(KEY_SERVER_URL, config.serverUrl)
            .putString(KEY_DEVICE_ID, config.deviceId)
            .putString(KEY_DEVICE_TOKEN, config.deviceToken)
            .putInt(KEY_INTERVAL_SECONDS, config.intervalSeconds)
            .apply()
    }

    val profiles: List<CaptureProfile>
        get() = CaptureProfileCodec.decode(preferences.getString(KEY_PROFILES, null))

    val activeProfileName: String
        get() = preferences.getString(KEY_ACTIVE_PROFILE, "") ?: ""

    fun readActiveProfile(): CaptureProfile? {
        val all = profiles
        return all.firstOrNull { it.name == activeProfileName } ?: all.firstOrNull()
    }

    fun saveProfiles(profiles: List<CaptureProfile>, activeName: String) {
        preferences.edit()
            .putString(KEY_PROFILES, CaptureProfileCodec.encode(profiles))
            .putString(KEY_ACTIVE_PROFILE, activeName)
            .apply()
    }

    private companion object {
        // ===== 使用前必须修改 =====
        // 把下面的地址改成你自己的服务器地址（例如 http://你的服务器IP）。
        // 令牌改成你自己设置的字符串，和服务器保持一致即可（服务器目前不校验，随便填也行）。
        const val DEFAULT_SERVER_URL = "http://你的服务器IP"
        const val DEFAULT_DEVICE_ID = "default"
        const val DEFAULT_DEVICE_TOKEN = "自己设一个密码"
        const val KEY_SERVER_URL = "server_url"
        const val KEY_DEVICE_ID = "device_id"
        const val KEY_DEVICE_TOKEN = "device_token"
        const val KEY_INTERVAL_SECONDS = "capture_interval_seconds"
        const val KEY_PROFILES = "capture_profiles"
        const val KEY_ACTIVE_PROFILE = "active_capture_profile"
    }
}
