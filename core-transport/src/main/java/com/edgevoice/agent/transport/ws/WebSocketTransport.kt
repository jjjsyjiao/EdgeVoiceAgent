package com.edgevoice.agent.transport.ws

import com.edgevoice.agent.transport.ITransportClient
import com.edgevoice.agent.transport.TransportFailure
import com.edgevoice.agent.transport.TransportListener
import com.edgevoice.agent.transport.TransportState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit

/**
 * 有界实时传输：断线取消整个旧 socket（含待发 PCM），不重播积压；每次连接拥有独立 epoch。
 * OkHttp 4.12 的 pingInterval 在一次 ping 周期内收不到 pong 时通过 onFailure 报超时。
 */
class WebSocketTransport(
    private val url: String,
    private val headers: Map<String, String> = emptyMap(),
    private val reconnectPolicy: ReconnectPolicy = ReconnectPolicy(),
    private val heartbeatIntervalSec: Long = 15L,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val maxOutgoingBytes: Long = 1L * 1024 * 1024,
    private val maxOutgoingAgeMs: Long = 3_000L,
    private val callbackCapacity: Int = 256,
    private val stableConnectionMs: Long = 30_000L,
    private val nanoTime: () -> Long = System::nanoTime,
    private val socketFactory: ((Request, WebSocketListener) -> WebSocket)? = null,
) : ITransportClient {
    private val lifetime = SupervisorJob(scope.coroutineContext[Job])
    private val workerScope = CoroutineScope(scope.coroutineContext + lifetime)
    private val client = OkHttpClient.Builder()
        .pingInterval(heartbeatIntervalSec, TimeUnit.SECONDS)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .build()
    private val mutableState = MutableStateFlow(TransportState.IDLE)
    override val state: StateFlow<TransportState> = mutableState
    @Volatile override var connectionEpoch: Long = 0L
        private set
    @Volatile override var reconnectAttempt: Int = 0
        private set
    @Volatile private var listener: TransportListener? = null
    @Volatile private var socket: WebSocket? = null
    override val queuedBytes: Long get() = socket?.queueSize()?.coerceAtLeast(0L) ?: 0L
    @Volatile private var terminated = false
    private var reconnectJob: Job? = null
    private var stableJob: Job? = null
    private var backlogSinceNs: Long? = null
    private var protocolPingSentAtNs: Long? = null
    private val callbacks = OrderedTransportCallbacks(workerScope, callbackCapacity, onFailure = { failure ->
        terminate(TransportFailure("Realtime inbound callback overload or listener failure", false, cause = failure))
    })
    private val watchdog = workerScope.launch {
        while (isActive) {
            delay(250L)
            synchronized(this@WebSocketTransport) {
                if (mutableState.value == TransportState.CONNECTED && outgoingExpired()) {
                    requestReconnect(TransportFailure("Realtime outgoing media became stale", true))
                }
            }
        }
    }

    init {
        require(maxOutgoingBytes > 0 && maxOutgoingAgeMs > 0 && callbackCapacity > 0 && stableConnectionMs > 0)
    }

    override fun setListener(listener: TransportListener?) { this.listener = listener }

    @Synchronized
    override fun connect() {
        check(!terminated) { "Closed WebSocketTransport cannot be reused; create a new transport" }
        if (mutableState.value != TransportState.IDLE) return
        openSocket()
    }

    @Synchronized
    private fun openSocket() {
        if (terminated) return
        connectionEpoch++
        val epoch = connectionEpoch
        transition(TransportState.CONNECTING, epoch)
        try {
            val request = Request.Builder().url(url).apply {
                headers.forEach { (name, value) -> addHeader(name, value) }
            }.build()
            val created = (socketFactory ?: { req, callback -> client.newWebSocket(req, callback) })(request, socketListener(epoch))
            // A test factory may synchronously invoke onOpen/onFailure before returning.
            if (!terminated && connectionEpoch == epoch) socket = created else created.cancel()
        } catch (failure: Throwable) {
            handleFailure(epoch, TransportFailure("Realtime WebSocket could not be opened", false, cause = failure))
        }
    }

    private fun socketListener(epoch: Long) = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            synchronized(this@WebSocketTransport) {
                if (!isCurrent(epoch)) { webSocket.cancel(); return }
                socket = webSocket
                backlogSinceNs = null
                transition(TransportState.CONNECTED, epoch)
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (!isCurrent(epoch)) return
            dispatch(epoch, text.length * 2) { it.onTextMessage(text, epoch) }
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            if (!isCurrent(epoch)) return
            dispatch(epoch, bytes.size) { it.onBinaryFrame(ByteBuffer.wrap(bytes.toByteArray())) }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            // 别等待 close 握手完成：立即撤销 ready、销毁待发媒体，服务端正常到期也会重新建会话。
            val retry = code !in setOf(1002, 1003, 1007, 1008, 1009)
            handleFailure(epoch, TransportFailure("Realtime server closed the connection ($code)", retry))
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            handleFailure(epoch, TransportFailure("Realtime connection closed ($code)", code !in setOf(1002, 1003, 1007, 1008, 1009)))
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            val status = response?.code
            val retry = status == null || status == 408 || status == 429 || status >= 500
            val retryAfter = response?.header("Retry-After")?.toLongOrNull()?.coerceIn(0L, 30L)?.times(1_000L)
            handleFailure(epoch, TransportFailure(
                if (status == null) "Realtime network or heartbeat failure" else "Realtime handshake failed (HTTP $status)",
                retry, status, retryAfter, t,
            ))
        }
    }

    private fun isCurrent(epoch: Long) = !terminated && epoch == connectionEpoch

    @Synchronized
    private fun handleFailure(epoch: Long, failure: TransportFailure) {
        if (!isCurrent(epoch)) return
        if (!failure.recoverable) { terminate(failure); return }
        scheduleReconnect(failure)
    }

    @Synchronized
    override fun requestReconnect(cause: Throwable): Boolean {
        if (terminated) return false
        if (mutableState.value == TransportState.RECONNECTING) return true
        val failure = cause as? TransportFailure ?: TransportFailure("Realtime connection needs recovery", true, cause = cause)
        if (!failure.recoverable) { terminate(failure); return false }
        return scheduleReconnect(failure)
    }

    @Synchronized
    override fun requestReconnect(cause: Throwable, expectedEpoch: Long): Boolean {
        if (expectedEpoch != connectionEpoch) return false
        return requestReconnect(cause)
    }

    private fun scheduleReconnect(failure: TransportFailure): Boolean {
        val pause = reconnectPolicy.nextDelayMs(reconnectAttempt)
        if (pause == null) {
            terminate(TransportFailure("Realtime reconnect attempts exhausted", false, statusCode = failure.statusCode, cause = failure))
            return false
        }
        connectionEpoch++ // 先失效旧回调，再 cancel socket；cancel 产生的 onFailure 不得重复重连。
        socket?.cancel()
        socket = null
        stableJob?.cancel()
        stableJob = null
        protocolPingSentAtNs = null
        backlogSinceNs = null
        reconnectAttempt++
        val epoch = connectionEpoch
        transition(TransportState.RECONNECTING, epoch)
        dispatch(epoch) { it.onError(failure, epoch) }
        reconnectJob?.cancel()
        reconnectJob = workerScope.launch {
            delay(maxOf(pause, failure.retryAfterMs ?: 0L))
            openSocket()
        }
        return true
    }

    @Synchronized
    override fun markSessionReady() {
        markSessionReady(connectionEpoch)
    }

    @Synchronized
    override fun markSessionReady(expectedEpoch: Long) {
        if (expectedEpoch != connectionEpoch) return
        if (terminated || mutableState.value != TransportState.CONNECTED) return
        val epoch = connectionEpoch
        stableJob?.cancel()
        stableJob = workerScope.launch {
            delay(stableConnectionMs)
            synchronized(this@WebSocketTransport) {
                if (isCurrent(epoch) && mutableState.value == TransportState.CONNECTED) reconnectAttempt = 0
            }
        }
    }

    @Synchronized
    override fun sendText(text: String): Boolean = sendText(text, connectionEpoch)

    @Synchronized
    override fun sendText(text: String, expectedEpoch: Long): Boolean {
        if (expectedEpoch != connectionEpoch) return false
        return send(text.toByteArray(Charsets.UTF_8).size.toLong()) { it.send(text) }
    }

    @Synchronized
    override fun sendBinary(data: ByteBuffer): Boolean {
        val duplicate = data.duplicate()
        val bytes = ByteArray(duplicate.remaining())
        duplicate.get(bytes)
        return send(bytes.size.toLong()) { it.send(bytes.toByteString()) }
    }

    private fun send(size: Long, write: (WebSocket) -> Boolean): Boolean {
        val current = socket ?: return false
        if (terminated || mutableState.value != TransportState.CONNECTED) return false
        val queued = current.queueSize()
        if (queued == 0L) backlogSinceNs = null
        if (size > maxOutgoingBytes || queued > maxOutgoingBytes - size || outgoingExpired()) {
            requestReconnect(TransportFailure("Realtime outgoing queue exceeded its byte/time budget", true))
            return false
        }
        if (!write(current)) {
            requestReconnect(TransportFailure("Realtime outgoing queue rejected a message", true))
            return false
        }
        if (current.queueSize() > 0L && backlogSinceNs == null) backlogSinceNs = nanoTime()
        return true
    }

    private fun outgoingExpired(): Boolean {
        val queued = socket?.queueSize() ?: 0L
        if (queued == 0L) { backlogSinceNs = null; return false }
        val since = backlogSinceNs ?: nanoTime().also { backlogSinceNs = it }
        return (nanoTime() - since) / 1_000_000L >= maxOutgoingAgeMs
    }

    @Synchronized
    private fun terminate(failure: TransportFailure) {
        if (terminated) return
        val failedEpoch = connectionEpoch
        terminated = true
        connectionEpoch++
        mutableState.value = TransportState.FAILED
        reconnectJob?.cancel()
        stableJob?.cancel()
        watchdog.cancel()
        socket?.cancel()
        socket = null
        callbacks.cancel()
        // 队列已过载时走一次受控的终止通知，不能把 FAILED 再塞进满队列并静默遗失。
        val currentListener = listener
        currentListener?.onError(failure, failedEpoch)
        currentListener?.onConnectionChanged(TransportState.FAILED, connectionEpoch, reconnectAttempt)
        releaseClient()
        lifetime.cancel()
    }

    @Synchronized
    override fun close() {
        if (terminated) return
        terminated = true
        connectionEpoch++
        mutableState.value = TransportState.CLOSED
        reconnectJob?.cancel()
        stableJob?.cancel()
        watchdog.cancel()
        socket?.cancel() // 主动停止也不把旧 PCM/图像继续发送到服务端。
        socket = null
        callbacks.cancel()
        listener?.onConnectionChanged(TransportState.CLOSED, connectionEpoch, reconnectAttempt)
        releaseClient()
        lifetime.cancel()
    }

    private fun releaseClient() {
        client.dispatcher.cancelAll()
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdown()
    }

    @Synchronized fun markProtocolPingSent() { protocolPingSentAtNs = nanoTime() }

    @Synchronized
    fun notifyProtocolPongReceived() {
        val since = protocolPingSentAtNs ?: return
        protocolPingSentAtNs = null
        val rtt = ((nanoTime() - since) / 1_000_000L).coerceAtLeast(0L)
        dispatch(connectionEpoch) { it.onRttMeasured(rtt) }
    }

    private fun transition(next: TransportState, epoch: Long) {
        mutableState.value = next
        val attempt = reconnectAttempt
        dispatch(epoch) { it.onConnectionChanged(next, epoch, attempt) }
    }

    private fun dispatch(epoch: Long, bytes: Int = 0, block: (TransportListener) -> Unit) {
        val target = listener ?: return
        callbacks.post(bytes) { if (isCurrent(epoch) && listener === target) block(target) }
    }
}
