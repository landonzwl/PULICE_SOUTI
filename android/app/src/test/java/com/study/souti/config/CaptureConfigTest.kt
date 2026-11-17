package com.study.souti.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CaptureConfigTest {
    @Test
    fun validConfigurationIsNormalized() {
        val config = CaptureConfig.create(
            serverUrl = " https://example.test/ ",
            deviceId = " device-1 ",
            deviceToken = " token-1 ",
            intervalSeconds = 3,
        )

        assertEquals("https://example.test", config.serverUrl)
        assertEquals("device-1", config.deviceId)
        assertEquals("token-1", config.deviceToken)
        assertEquals(3, config.intervalSeconds)
    }

    @Test
    fun nonHttpServerIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            validConfig(serverUrl = "ftp://example.test")
        }
    }

    @Test
    fun urlCredentialsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            validConfig(serverUrl = "https://user:password@example.test")
        }
    }

    @Test
    fun tooShortIntervalIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            validConfig(intervalSeconds = 2)
        }
    }

    @Test
    fun blankTokenIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            validConfig(deviceToken = " ")
        }
    }

    private fun validConfig(
        serverUrl: String = "https://example.test",
        deviceToken: String = "token",
        intervalSeconds: Int = 3,
    ) = CaptureConfig.create(
        serverUrl = serverUrl,
        deviceId = "device",
        deviceToken = deviceToken,
        intervalSeconds = intervalSeconds,
    )
}
