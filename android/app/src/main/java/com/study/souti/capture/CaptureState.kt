package com.study.souti.capture

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.time.LocalTime
import java.time.format.DateTimeFormatter

data class CaptureUiState(
    val isRunning: Boolean = false,
    val status: String = "未运行",
    val lastUpload: String = "尚未上传",
    val error: String = "无",
    val stage: String = "空闲",
    val stageDetail: String = "",
    val logs: List<String> = emptyList(),
)

object CaptureState {
    private val mutableState = MutableStateFlow(CaptureUiState())
    val state: StateFlow<CaptureUiState> = mutableState.asStateFlow()
    private val timeFormat = DateTimeFormatter.ofPattern("HH:mm:ss")

    private fun now(): String = LocalTime.now().format(timeFormat)

    private fun withLog(state: CaptureUiState, line: String): List<String> =
        (state.logs + "${now()}  $line").takeLast(MAX_LOGS)

    fun starting() {
        mutableState.update {
            it.copy(
                isRunning = true,
                status = "正在启动前台服务",
                error = "无",
                stage = "启动",
                stageDetail = "正在初始化相机与网络",
                logs = withLog(it, "启动采集服务"),
            )
        }
    }

    fun running() {
        mutableState.update {
            it.copy(
                isRunning = true,
                status = "正在采集",
                stage = "采集",
                stageDetail = "相机已就绪，等待下一帧",
                logs = withLog(it, "相机就绪，开始采集"),
            )
        }
    }

    /** 更新当前环节，同时写入可见日志。 */
    fun stage(label: String, detail: String) {
        mutableState.update {
            it.copy(
                stage = label,
                stageDetail = detail,
                logs = withLog(it, if (detail.isBlank()) label else "$label：$detail"),
            )
        }
    }

    fun uploaded(timestamp: String) {
        mutableState.update {
            it.copy(isRunning = true, status = "正在采集", lastUpload = timestamp)
        }
    }

    /** 一轮完整成功后才清除错误，避免错误提示被下一次暂定上报覆盖。 */
    fun succeeded(timestamp: String) {
        mutableState.update {
            it.copy(
                isRunning = true,
                status = "正在采集",
                lastUpload = timestamp,
                error = "无",
            )
        }
    }

    fun reportError(message: String) {
        mutableState.update {
            it.copy(
                status = if (it.isRunning) "运行中（有错误）" else it.status,
                error = message,
                logs = withLog(it, "错误：$message"),
            )
        }
    }

    fun stopping() {
        mutableState.update { it.copy(isRunning = true, status = "正在停止") }
    }

    fun stopped() {
        mutableState.update {
            it.copy(
                isRunning = false,
                status = "已停止",
                stage = "已停止",
                stageDetail = "",
                logs = withLog(it, "采集服务已停止"),
            )
        }
    }

    private const val MAX_LOGS = 14
}
