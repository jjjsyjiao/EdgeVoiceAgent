package com.edgevoice.agent.transport

import kotlinx.coroutines.flow.StateFlow
import java.nio.ByteBuffer

/** 传输层连接状态。 */
enum class TransportState {
    IDLE,
    CONNECTING,
    CONNECTED,
    RECONNECTING,
    CLOSED,
    FAILED,
}

class TransportFailure(
    message: String,
    val recoverable: Boolean,
    val statusCode: Int? = null,
    val retryAfterMs: Long? = null,
    cause: Throwable? = null,
) : java.io.IOException(message, cause)

/**
 * 传输层事件监听（回调线程不保证，实现类需自行切换调度器；轻量处理，勿阻塞）。
 */
interface TransportListener {
    fun onStateChanged(state: TransportState) {}

    /** epoch 在断线及新连接时递增，旧连接排队回调不得跨 epoch 交付。 */
    fun onConnectionChanged(state: TransportState, epoch: Long, attempt: Int) { onStateChanged(state) }

    /** 收到文本协议事件（已解析为 JSON 字符串，交由上层协议层二次解码） */
    fun onTextMessage(text: String) {}
    fun onTextMessage(text: String, epoch: Long) { onTextMessage(text) }

    /** 收到二进制音频帧（Realtime API 默认走 JSON base64，预留二进制通道给 WebRTC 演进） */
    fun onBinaryFrame(data: ByteBuffer) {}

    /** 心跳/协议往返实测的 RTT 更新（调试抽屉 telemetry 用） */
    fun onRttMeasured(rttMs: Long) {}

    fun onError(cause: Throwable) {}
    fun onError(cause: Throwable, epoch: Long) { onError(cause) }
}

/**
 * 全双工传输通道契约（架构文档 §2.4）。
 *
 * 职责边界：只管「建立长连接 / 收发原始报文 / 断线重连 / 保活」，
 * 不感知任何具体 AI 协议 —— OpenAI Realtime 的 JSON 编解码在 [com.edgevoice.agent.transport.realtime.RealtimeProtocol]。
 * 可插拔：当前实现 WebSocket；WebRTC 实现遵循同一契约（含 data channel 时）。
 */
interface ITransportClient {
    val state: StateFlow<TransportState>
    val connectionEpoch: Long get() = 0L
    val reconnectAttempt: Int get() = 0
    /** 仅待发队列字节数，不含媒体内容；用于真机诊断。 */
    val queuedBytes: Long get() = 0L

    /** 异步建立连接；成功后 state 变为 CONNECTED。 */
    fun connect()

    /** 发送文本报文（协议 JSON）。返回是否成功入队。 */
    fun sendText(text: String): Boolean

    /** 原子代次检查由具体通道实现，禁止旧采集/编码结果写入新连接。 */
    fun sendText(text: String, expectedEpoch: Long): Boolean =
        expectedEpoch == connectionEpoch && sendText(text)

    /** 发送二进制帧。 */
    fun sendBinary(data: ByteBuffer): Boolean

    /** 终止实例（不再自动重连）；后续会话应新建传输实例，不能复用已关闭通道。 */
    fun close()

    fun setListener(listener: TransportListener?)

    /** 丢弃该连接所有待发媒体后按有界策略重连。false 表示已终止/不支持恢复。 */
    fun requestReconnect(cause: Throwable): Boolean = false

    fun requestReconnect(cause: Throwable, expectedEpoch: Long): Boolean =
        expectedEpoch == connectionEpoch && requestReconnect(cause)

    /** 协议配置确认后开始稳定连接计时；短暂握手成功不能无限重置重试预算。 */
    fun markSessionReady() {}

    /** 旧配置确认不能启动新连接的稳定计时。 */
    fun markSessionReady(expectedEpoch: Long) {
        if (expectedEpoch == connectionEpoch) markSessionReady()
    }
}
