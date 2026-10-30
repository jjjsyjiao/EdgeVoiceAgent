package com.edgevoice.agent.core.history

import org.json.JSONObject
import java.net.URI
import java.util.Locale

/** A failed request says nothing about record existence or whether a save reached the server. */
object HistoryToolFailures {
    fun reply(kind: HistoryFailureKind, toolName: String, config: HistoryMemoryConfig): HistoryToolAction.Reply {
        val saving = toolName == HistoryToolDefinitions.REMEMBER
        val localDebugService = config.debugConnectionHints && runCatching {
            URI(config.baseUrl).host?.lowercase(Locale.ROOT) in setOf("localhost", "127.0.0.1", "::1", "[::1]")
        }.getOrDefault(false)
        val reason = when (kind) {
            HistoryFailureKind.NETWORK -> if (localDebugService) {
                "手机尚未连接到电脑历史服务，请检查 USB 或无线调试转发，并确认电脑历史服务已启动。"
            } else "无法连接历史服务，请检查网络连接。"
            HistoryFailureKind.TIMEOUT -> "历史操作等待超时，请稍后重试。"
            HistoryFailureKind.AUTHENTICATION -> "历史服务身份验证失败，请检查访问配置。"
            HistoryFailureKind.UNAVAILABLE -> "历史服务暂时无法处理请求，请稍后重试。"
            HistoryFailureKind.INVALID_RESPONSE -> "历史服务返回的数据无法验证，请稍后重试。"
            HistoryFailureKind.INVALID_REQUEST -> "历史服务无法处理本次记录或问题，请重新提问。"
            HistoryFailureKind.HTTP -> "历史服务请求失败，请检查服务配置后重试。"
        }
        val nextStep = if (saving) {
            if (kind == HistoryFailureKind.NETWORK) "本次未确认保存，请恢复连接后重新说“记一下”。"
            else "本次未确认保存，请恢复服务后重新说“记一下”。"
        } else "本次查询未完成，不能据此判断是否保存过记录。"
        val code = when (kind) {
            HistoryFailureKind.NETWORK -> "network_error"
            HistoryFailureKind.TIMEOUT -> "timeout"
            HistoryFailureKind.AUTHENTICATION -> "authentication_failed"
            HistoryFailureKind.UNAVAILABLE -> "service_unavailable"
            HistoryFailureKind.INVALID_RESPONSE -> "invalid_response"
            HistoryFailureKind.INVALID_REQUEST -> "invalid_request"
            HistoryFailureKind.HTTP -> "http_error"
        }
        val message = reason + nextStep
        val instruction = if (saving) {
            "请说明实际错误和恢复方法。没有收到有效保存确认，不得声称已保存，也不得断言服务器一定没有写入。请用户恢复后重新说记一下。"
        } else {
            "请说明实际错误和恢复方法。这不是没有匹配记录；查询未完成，不得说没有保存过、查无记录或编造历史位置、物品身份。"
        }
        return HistoryToolAction.Reply(
            JSONObject().put("status", code).put("operation", if (saving) "save" else "search")
                .put("message", message).put("instruction", instruction).toString(),
            HistoryUiState(HistoryStatus.ERROR, message),
        )
    }
}
