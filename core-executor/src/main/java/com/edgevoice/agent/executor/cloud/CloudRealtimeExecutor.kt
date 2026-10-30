package com.edgevoice.agent.executor.cloud

import com.edgevoice.agent.executor.ExecutorConnectionState
import com.edgevoice.agent.executor.ExecutorEvent
import com.edgevoice.agent.executor.ExecutorToolCall
import com.edgevoice.agent.executor.ExecutorMode
import com.edgevoice.agent.executor.IAgentExecutor
import com.edgevoice.agent.executor.RealtimeSendException
import com.edgevoice.agent.executor.ResponseIncompleteReason
import com.edgevoice.agent.transport.ITransportClient
import com.edgevoice.agent.transport.TransportFailure
import com.edgevoice.agent.transport.TransportListener
import com.edgevoice.agent.transport.TransportState
import com.edgevoice.agent.transport.realtime.RealtimeProfile
import com.edgevoice.agent.transport.realtime.RealtimeProtocol
import com.edgevoice.agent.transport.ws.WebSocketTransport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.TimeoutException

/** 只读瞬时快照：仅队列大小/连接代次，不包含音视频、密钥或端点。 */
data class RealtimeQueueStats(
    val callbackBytes: Long,
    val eventBytes: Long,
    val outgoingBytes: Long,
    val connectionEpoch: Long,
)

/**
 * 有界、可恢复 Realtime 会话。断线使旧 epoch 的输入/输出失效，绝不重播断线前 PCM 或回答。
 * 收包解析与订阅交付分别由单消费者执行；任一队列过载均明确终止，不能静默丢控制事件。
 */
class CloudRealtimeExecutor(
    private val wsUrl: String,
    private val apiKey: String,
    private val model: String,
    private val voice: String? = null,
    private val instructions: String? = null,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val profile: RealtimeProfile = RealtimeProfile.OPENAI,
    private val transportFactory: (String, Map<String, String>) -> ITransportClient = { url, headers ->
        WebSocketTransport(url = url, headers = headers)
    },
    // 首次建连/配置总上限60秒；进入Ready后，连接恢复独立遵循传输层的有界重试预算。
    private val prepareTimeoutMs: Long = 60_000L,
    private val configurationTimeoutMs: Long = 10_000L,
    private val callbackCapacity: Int = 256,
    private val eventCapacity: Int = 256,
    private val maxCallbackBytes: Long = 4L * 1024 * 1024,
    private val maxEventBytes: Long = 4L * 1024 * 1024,
    private val toolsJson: String? = null,
    private val mediaResponseTimeoutMs: Long = 90_000L,
) : IAgentExecutor {
    override val mode = ExecutorMode.CLOUD_REALTIME
    override val supportsVideoInput: Boolean get() = profile.supportsVideoInput
    override val supportsTools: Boolean get() = !toolsJson.isNullOrBlank() &&
        runCatching { org.json.JSONArray(toolsJson).length() > 0 }.getOrDefault(false)
    private val lifetime = SupervisorJob(scope.coroutineContext[Job])
    private val workerScope = CoroutineScope(scope.coroutineContext + lifetime)
    private val _events = MutableSharedFlow<ExecutorEvent>()
    override val events: SharedFlow<ExecutorEvent> = _events
    private val _ready = MutableStateFlow(false)
    override val ready: StateFlow<Boolean> = _ready
    private val _connectionState = MutableStateFlow(ExecutorConnectionState.CLOSED)
    val connectionState: StateFlow<ExecutorConnectionState> = _connectionState
    private data class Callback(val epoch: Long, val bytes: Int, val block: suspend () -> Unit)
    private data class Event(
        val epoch: Long,
        val event: ExecutorEvent,
        val bytes: Long,
        val warningResponseGeneration: Long? = null,
    )
    private val callbacks = Channel<Callback>(callbackCapacity)
    private val outgoing = Channel<Event>(eventCapacity)
    private val queuedCallbackBytes = AtomicLong(0)
    private val queuedEventBytes = AtomicLong(0)
    private val prepareMutex = Mutex()
    private val prepared = CompletableDeferred<Unit>()
    private val terminated = AtomicBoolean(false)
    private val userClosed = AtomicBoolean(false)
    private val epochGate = Any()
    @Volatile private var currentEpoch = 0L
    override val inputEpoch: Long get() = currentEpoch
    private val audioBytesSent = AtomicLong(0L)
    override val inputAudioBytesSent: Long get() = audioBytesSent.get()
    private val vadAudioBytesSent = AtomicLong(0L)
    override val inputVadAudioBytesSent: Long get() = vadAudioBytesSent.get()
    @Volatile private var currentAttempt = 0
    @Volatile private var transport: ITransportClient? = null
    private var lastTransportState = TransportState.IDLE
    @Volatile private var configurationSent = false
    private var configurationWatchdog: Job? = null
    private var eventForwarder: Job? = null
    private var terminalDelivery: Job? = null
    @Volatile private var lastRecoveryFailure: Throwable? = null
    @Volatile private var terminalFailure: Throwable? = null
    /** 仅单消费者设置；不可用全局currentEpoch替旧回调重新盖章。 */
    private var processingEpoch = -1L
    private var mergedInstructions: String? = null

    private val responseLock = Any()
    private var responseActive = false
    private var activeResponseId: String? = null
    private var suppressAnonymousResponse = false
    private val ignoredResponseIds = linkedSetOf<String>()
    private val cancelledResponseIds = linkedSetOf<String>()
    private enum class TextStream { TEXT, AUDIO_TRANSCRIPT }
    private var textStream: TextStream? = null
    private val responseText = StringBuilder()
    private var finalTextSent = false
    private var responseAudioBytes = 0L
    private var responseAudioDone = false
    private var responseGeneration = 0L
    /** Transport sends never hold responseLock. Gate serializes tool output against user interruption. */
    private val toolSendGate = Any()
    private val mediaMutex = Mutex()
    private data class IssuedTool(val call: ExecutorToolCall, val generation: Long)
    private val responseToolCalls = linkedMapOf<String, RealtimeProtocol.ServerEvent.FunctionCall>()
    private val issuedTools = linkedMapOf<String, IssuedTool>()
    private var interactionGeneration = 0L
    @Volatile private var controlledInput = false
    private var sessionControlAck: CompletableDeferred<Boolean>? = null
    private var expectedControlVad: Boolean? = null
    private var expectedControlToolCount: Int? = null
    private var audioCommitAck: CompletableDeferred<Boolean>? = null
    private var mediaResponseAck: CompletableDeferred<Boolean>? = null
    private var mediaResponseId: String? = null
    private var mediaResponseRequested = false
    private val internalInputItems = linkedSetOf<String>()
    private var awaitingInternalCommit = false
    private var activeUserInputId: String? = null

    init {
        require(callbackCapacity > 0 && eventCapacity > 0 && maxCallbackBytes > 0 && maxEventBytes > 0)
        require(prepareTimeoutMs > 0 && configurationTimeoutMs > 0)
        restartEventForwarder()
    }

    private val callbackJob = workerScope.launch(start = CoroutineStart.UNDISPATCHED) {
        for (callback in callbacks) {
            queuedCallbackBytes.addAndGet(-callback.bytes.toLong())
            if (!accepting() || callback.epoch != currentEpoch) continue
            processingEpoch = callback.epoch
            try {
                callback.block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                if (failure is RealtimeSendException && failure.recoverable) {
                    recoverConnection(failure, callback.epoch)
                } else terminate(failure, callback.epoch)
            } finally {
                processingEpoch = -1L
            }
        }
    }

    private fun accepting() = !terminated.get() && !userClosed.get()

    fun queueStats(): RealtimeQueueStats = RealtimeQueueStats(
        callbackBytes = queuedCallbackBytes.get().coerceAtLeast(0L),
        eventBytes = queuedEventBytes.get().coerceAtLeast(0L),
        outgoingBytes = transport?.queuedBytes?.coerceAtLeast(0L) ?: 0L,
        connectionEpoch = currentEpoch,
    )

    override suspend fun prepare(handoverContext: String?) = prepareMutex.withLock {
        check(accepting()) { "Realtime executor is closed" }
        if (_ready.value) return@withLock
        try {
            if (transport == null) {
                mergedInstructions = listOfNotNull(instructions, handoverContext?.let { "【会话上下文摘要】$it" })
                    .takeIf { it.isNotEmpty() }?.joinToString("\n\n")
                val headers = buildMap {
                    put("Authorization", "Bearer $apiKey")
                    if (profile.sendRealtimeBetaHeader) put("OpenAI-Beta", "realtime=v1")
                }
                val separator = if ('?' in wsUrl) "&" else "?"
                val next = transportFactory("$wsUrl${separator}model=$model", headers)
                transport = next
                next.setListener(transportListener)
                next.connect()
                if (next.state.value == TransportState.CONNECTED && currentEpoch == 0L) {
                    transportListener.onStateChanged(TransportState.CONNECTED)
                }
            }
            // 只将本执行器自己的准备期限转换为业务超时。外部任务/外层withTimeout取消必须透传。
            val configured = withTimeoutOrNull(prepareTimeoutMs) { prepared.await(); true }
            if (configured != true) {
                throw TimeoutException("Realtime initial connection/configuration timed out after ${prepareTimeoutMs}ms").apply {
                    initCause(lastRecoveryFailure)
                }
            }
        } catch (failure: Throwable) {
            shutdown()
            throw failure
        }
    }

    private val transportListener = object : TransportListener {
        // 兼容既有 Fake/第三方通道；新 WebSocketTransport 使用带 epoch 的回调。
        override fun onStateChanged(state: TransportState) {
            val epoch = transport?.connectionEpoch?.takeIf { it > 0L } ?: synchronized(epochGate) {
                if (state != lastTransportState && state in setOf(TransportState.CONNECTING, TransportState.CONNECTED, TransportState.RECONNECTING)) currentEpoch + 1L
                else currentEpoch
            }
            onConnectionChanged(state, epoch, transport?.reconnectAttempt ?: 0)
        }

        override fun onConnectionChanged(state: TransportState, epoch: Long, attempt: Int) {
            if (!accepting() || epoch < currentEpoch) return
            synchronized(epochGate) {
                if (epoch < currentEpoch || !accepting()) return
                if (epoch != currentEpoch) {
                    currentEpoch = epoch
                    audioBytesSent.set(0L)
                    vadAudioBytesSent.set(0L)
                    _ready.value = false
                    configurationSent = false
                    configurationWatchdog?.cancel()
                    invalidateToolWork()
                    synchronized(responseLock) {
                        internalInputItems.clear()
                        awaitingInternalCommit = false
                        activeUserInputId = null
                    }
                    finishResponse()
                    while (true) {
                        val old = outgoing.tryReceive().getOrNull() ?: break
                        queuedEventBytes.addAndGet(-old.bytes)
                    }
                    restartEventForwarder()
                }
                currentAttempt = attempt
                lastTransportState = state
                if (state != TransportState.CONNECTED) _ready.value = false
            }
            enqueue(epoch) { handleConnection(state) }
        }

        override fun onTextMessage(text: String) = onTextMessage(text, currentEpoch)

        override fun onTextMessage(text: String, epoch: Long) {
            enqueue(epoch, text.length * 2) { handleServerEvent(RealtimeProtocol.parseServerEvent(text)) }
        }

        override fun onError(cause: Throwable) = onError(cause, currentEpoch)

        override fun onError(cause: Throwable, epoch: Long) {
            synchronized(epochGate) {
                if (!accepting() || epoch < currentEpoch) return
                if (epoch > currentEpoch) {
                    // 握手失败可在排队的CONNECTING交付前终止通道，原始401/429不可被泛化的FAILED覆盖。
                    if (cause is TransportFailure && cause.recoverable) return
                    currentEpoch = epoch
                }
                _ready.value = false
            }
            if (cause is TransportFailure && cause.recoverable) {
                lastRecoveryFailure = cause
                enqueue(epoch) { publish(ExecutorEvent.Error(cause, recoverable = true)) }
            } else terminate(cause, epoch)
        }

        override fun onRttMeasured(rttMs: Long) {
            enqueue(currentEpoch) { publish(ExecutorEvent.RttMeasured(rttMs)) }
        }
    }

    private fun enqueue(epoch: Long, bytes: Int = 0, block: suspend () -> Unit) {
        if (!accepting() || epoch != currentEpoch) return
        if (queuedCallbackBytes.addAndGet(bytes.toLong()) > maxCallbackBytes) {
            queuedCallbackBytes.addAndGet(-bytes.toLong())
            terminate(IOException("Realtime incoming event byte budget exceeded"), epoch)
            return
        }
        if (callbacks.trySend(Callback(epoch, bytes, block)).isFailure) {
            queuedCallbackBytes.addAndGet(-bytes.toLong())
            if (accepting()) terminate(IOException("Realtime incoming event queue is full"), epoch)
        }
    }

    private fun handleConnection(state: TransportState) {
        when (state) {
            TransportState.CONNECTED -> configureSession()
            TransportState.CONNECTING -> {
                resetConfiguration()
                publish(ExecutorEvent.ConnectionChanged(ExecutorConnectionState.CONNECTING, currentAttempt))
            }
            TransportState.RECONNECTING -> {
                resetConfiguration()
                publish(ExecutorEvent.ConnectionChanged(ExecutorConnectionState.RECONNECTING, currentAttempt))
            }
            TransportState.CLOSED -> terminate(IOException("Realtime connection was closed"), processingEpoch)
            TransportState.FAILED -> terminate(IOException("Realtime connection recovery failed"), processingEpoch)
            TransportState.IDLE -> Unit
        }
    }

    private fun resetConfiguration() {
        configurationSent = false
        configurationWatchdog?.cancel()
        configurationWatchdog = null
        invalidateToolWork()
        clearResponse()
    }

    private fun configureSession() {
        val epoch = processingEpoch
        val current = transport ?: return
        // 读取外部通道状态不能放在epochGate内，避免WebSocket monitor与epochGate锁序反转。
        val connected = current.state.value == TransportState.CONNECTED
        val transportEpoch = current.connectionEpoch
        synchronized(epochGate) {
            if (!accepting() || epoch != currentEpoch || configurationSent || !connected) return
            if (transportEpoch != 0L && transportEpoch != epoch) return
            configurationSent = true
            _ready.value = false
        }
        publish(ExecutorEvent.ConnectionChanged(ExecutorConnectionState.CONFIGURING, currentAttempt))
        (current as? WebSocketTransport)?.markProtocolPingSent()
        if (!current.sendText(RealtimeProtocol.sessionUpdate(
                model = model, voice = voice ?: profile.defaultVoice,
                instructions = mergedInstructions, profile = profile, toolsJson = toolsJson,
            ), if (transportEpoch == 0L) 0L else epoch)) {
            recoverConnection(IOException("Realtime session configuration could not be queued"), epoch)
            return
        }
        if (!accepting() || currentEpoch != epoch) return
        configurationWatchdog?.cancel()
        configurationWatchdog = workerScope.launch {
            delay(configurationTimeoutMs)
            if (accepting() && currentEpoch == epoch && !_ready.value) {
                recoverConnection(IOException("Realtime session configuration acknowledgement timed out"), epoch)
            }
        }
    }

    private suspend fun handleServerEvent(event: RealtimeProtocol.ServerEvent) {
        if (!accepting() || processingEpoch != currentEpoch) return
        when (event) {
            is RealtimeProtocol.ServerEvent.SessionCreated -> Unit
            is RealtimeProtocol.ServerEvent.SessionUpdated -> {
                // A temporary manual-input configuration must not emit Ready or restart capture.
                val controlAck = synchronized(responseLock) { sessionControlAck }
                if (controlAck != null && _ready.value) {
                    val matches = synchronized(responseLock) {
                        (event.serverVad == null || event.serverVad == expectedControlVad) &&
                            (event.toolCount == null || event.toolCount == expectedControlToolCount)
                    }
                    if (matches) controlAck.complete(true)
                    return
                }
                val epoch = processingEpoch
                val current = transport ?: return
                val connected = current.state.value == TransportState.CONNECTED
                val transportEpoch = current.connectionEpoch
                if (!connected || (transportEpoch != 0L && transportEpoch != epoch)) return
                // 通道调用不占epochGate；调用期间换代后，下面的提交必须整体失效。
                current.markSessionReady(if (transportEpoch == 0L) 0L else epoch)
                synchronized(epochGate) {
                    if (!accepting() || epoch != currentEpoch || !configurationSent || !connected || _ready.value) return
                    if (transportEpoch != 0L && transportEpoch != epoch) return
                    _ready.value = true
                    configurationWatchdog?.cancel()
                    configurationWatchdog = null
                    // Ready与首次prepare确认属于同一次原子提交，不能在锁外给新epoch补签旧确认。
                    prepared.complete(Unit)
                }
                (current as? WebSocketTransport)?.notifyProtocolPongReceived()
                publish(ExecutorEvent.ConnectionChanged(ExecutorConnectionState.READY, currentAttempt))
                publish(ExecutorEvent.Ready)
            }
            is RealtimeProtocol.ServerEvent.Error -> {
                val code = event.code.orEmpty().lowercase()
                if (code.contains("response_cancel_not_active") || event.message.lowercase().contains("no active response")) return
                val recoverable = code.contains("rate_limit") || code.contains("429") || code.contains("overload") ||
                    code.contains("throttl") || code.contains("too_many_requests") || code.contains("server_error") ||
                    code.contains("service_unavailable") || code.contains("timeout")
                val statusCode = when {
                    code.contains("rate_limit") || code.contains("429") || code.contains("throttl") || code.contains("too_many_requests") -> 429
                    code.contains("api_key") || code.contains("apikey") || code.contains("authentication") || code.contains("unauthorized") -> 401
                    code.contains("permission") || code.contains("forbidden") || code.contains("access_denied") -> 403
                    code.startsWith("invalid_") -> 400
                    recoverable -> 503
                    else -> null
                }
                val failure = TransportFailure(
                    "Realtime service error: ${event.code.orEmpty()}: ${event.message}",
                    recoverable = recoverable, statusCode = statusCode,
                )
                if (recoverable) recoverConnection(failure, processingEpoch) else terminate(failure, processingEpoch)
            }
            else -> {
                if (!_ready.value) return
                handleResponseEvent(event)
            }
        }
    }

    private suspend fun handleResponseEvent(event: RealtimeProtocol.ServerEvent) {
        when (event) {
            is RealtimeProtocol.ServerEvent.ResponseCreated -> {
                synchronized(responseLock) {
                    if (mediaResponseRequested && mediaResponseId == null) mediaResponseId = event.responseId
                }
                startResponse(event.responseId)
            }
            is RealtimeProtocol.ServerEvent.FunctionCall -> {
                if (!supportsTools || !acceptResponse(event.responseId)) return
                if (event.name.isBlank() || event.callId.isBlank() || event.argumentsJson.length > 32_768) {
                    terminate(IOException("Realtime tool call is malformed or exceeds its argument budget"), processingEpoch)
                    return
                }
                synchronized(responseLock) {
                    if (responseToolCalls.size >= 8 && event.callId !in responseToolCalls) {
                        throw IOException("Realtime tool call count exceeds its bounded budget")
                    }
                    responseToolCalls[event.callId] = event
                }
            }
            is RealtimeProtocol.ServerEvent.AudioCommitted -> synchronized(responseLock) {
                val itemId = event.itemId
                if (awaitingInternalCommit && itemId != null) {
                    internalInputItems.add(itemId)
                    if (internalInputItems.size > 64) internalInputItems.remove(internalInputItems.first())
                    awaitingInternalCommit = false
                    audioCommitAck?.complete(true)
                }
            }
            is RealtimeProtocol.ServerEvent.AudioDelta -> {
                if (event.pcm.isNotEmpty() && acceptResponse(event.responseId)) {
                    synchronized(responseLock) { responseAudioBytes += event.pcm.size }
                    publish(ExecutorEvent.AudioDelta(event.pcm, responseId(event.responseId)))
                }
            }
            is RealtimeProtocol.ServerEvent.AudioDone -> {
                if (acceptResponse(event.responseId)) synchronized(responseLock) { responseAudioDone = true }
            }
            is RealtimeProtocol.ServerEvent.TextDelta -> textDelta(event.text, event.responseId, TextStream.TEXT)
            is RealtimeProtocol.ServerEvent.TextDone -> textFinal(event.text, event.responseId, TextStream.TEXT)
            is RealtimeProtocol.ServerEvent.AudioTranscriptDelta -> textDelta(event.text, event.responseId, TextStream.AUDIO_TRANSCRIPT)
            is RealtimeProtocol.ServerEvent.AudioTranscriptDone -> textFinal(event.text, event.responseId, TextStream.AUDIO_TRANSCRIPT)
            is RealtimeProtocol.ServerEvent.UserTranscript -> {
                if (allowTranscript(event.itemId)) publish(ExecutorEvent.UserTranscript(event.text, event.itemId))
            }
            is RealtimeProtocol.ServerEvent.SpeechBoundary -> {
                if (event.started) {
                    cancelActiveResponse()
                    synchronized(responseLock) { activeUserInputId = event.itemId }
                    publish(ExecutorEvent.UserSpeechStarted)
                    event.itemId?.let { publish(ExecutorEvent.UserInputIdentity(it, event.audioStartMs)) }
                } else {
                    publish(ExecutorEvent.UserInputEnd(event.itemId, event.audioEndMs))
                    publish(ExecutorEvent.UserSpeechStopped)
                }
            }
            RealtimeProtocol.ServerEvent.SpeechStarted -> {
                cancelActiveResponse()
                synchronized(responseLock) { activeUserInputId = null }
                publish(ExecutorEvent.UserSpeechStarted)
            }
            RealtimeProtocol.ServerEvent.SpeechStopped -> publish(ExecutorEvent.UserSpeechStopped)
            is RealtimeProtocol.ServerEvent.ResponseDone -> {
                if (!acceptResponse(event.responseId)) return
                if (event.status == "cancelled" || event.status == "canceled") {
                    synchronized(responseLock) { mediaResponseAck?.complete(false); responseToolCalls.clear() }
                    finishResponse(); return
                }
                if (event.status == "failed") { terminate(IOException("Realtime response failed"), processingEpoch); return }
                val id = responseId(event.responseId)
                val (generation, audioBytes) = synchronized(responseLock) { responseGeneration to responseAudioBytes }
                val toolCalls = synchronized(responseLock) {
                    if (supportsTools) event.toolCalls.forEach { responseToolCalls.putIfAbsent(it.callId, it) }
                    responseToolCalls.values.toList().also { responseToolCalls.clear() }
                }
                if (toolCalls.isNotEmpty() && event.status == "completed") {
                    require(toolCalls.size <= 8) { "Realtime tool call count exceeds its budget" }
                    val calls = synchronized(responseLock) {
                        toolCalls.map { tool ->
                            require(tool.name.isNotBlank() && tool.callId.isNotBlank() && tool.argumentsJson.length <= 32_768)
                            ExecutorToolCall(tool.name, tool.callId, tool.argumentsJson, id.orEmpty(), currentEpoch).also {
                                issuedTools[it.callId] = IssuedTool(it, interactionGeneration)
                            }
                        }
                    }
                    finishResponse()
                    calls.forEach { publish(ExecutorEvent.ToolCallReady(it)) }
                    return // Tool-only response has no speech by design; wait for the tool continuation.
                }
                val warning = when (event.status) {
                    "completed" -> if (audioBytes == 0L) ResponseIncompleteReason.MISSING_AUDIO else null
                    "incomplete" -> ResponseIncompleteReason.INCOMPLETE
                    else -> ResponseIncompleteReason.UNCONFIRMED
                }
                if (!finalTextSent) {
                    val completeText = responseText.toString().ifEmpty { event.text.orEmpty() }
                    if (completeText.isNotEmpty()) {
                        finalTextSent = true
                        publish(ExecutorEvent.AssistantTextFinal(completeText, responseId(event.responseId)))
                    }
                }
                synchronized(responseLock) {
                    if (mediaResponseRequested && (mediaResponseId == null || mediaResponseId == id)) {
                        mediaResponseAck?.complete(event.status == "completed" && audioBytes > 0L && responseAudioDone &&
                            (finalTextSent || responseText.isNotEmpty() || !event.text.isNullOrBlank()))
                    }
                }
                finishResponse()
                if (warning != null) publish(ExecutorEvent.ResponseIncomplete(warning, id), generation)
                publish(ExecutorEvent.TurnComplete)
            }
            else -> Unit
        }
    }
    private suspend fun startResponse(id: String) {
        val started = synchronized(responseLock) {
            if (id.isNotEmpty() && id in ignoredResponseIds) return@synchronized false
            if (responseActive && activeResponseId == id.ifEmpty { null }) return@synchronized false
            activeResponseId?.let(::ignoreResponse)
            activeResponseId = id.ifEmpty { null }
            responseActive = true
            suppressAnonymousResponse = false
            responseAudioBytes = 0L
            responseAudioDone = false
            responseToolCalls.clear()
            responseGeneration++
            true
        }
        if (!started) return
        textStream = null
        responseText.clear()
        finalTextSent = false
        publish(ExecutorEvent.ResponseStarted(id))
    }

    private suspend fun acceptResponse(id: String?): Boolean {
        val decision = synchronized(responseLock) {
            when {
                id != null && id in ignoredResponseIds -> 0
                responseActive && id != null && activeResponseId != null && id != activeResponseId -> 0
                responseActive -> 1
                suppressAnonymousResponse && id == null -> 0
                else -> 2
            }
        }
        if (decision == 2) startResponse(id.orEmpty())
        return decision != 0
    }

    private fun responseId(id: String?): String? = id ?: synchronized(responseLock) { activeResponseId }

    private suspend fun textDelta(text: String, id: String?, stream: TextStream) {
        if (!acceptResponse(id) || finalTextSent || text.isEmpty()) return
        if (textStream == null) textStream = stream
        if (textStream != stream) return // 同一回答只消费一种文字流，避免正文/音频转写双份拼接
        if (responseText.length + text.length > 65_536) {
            terminate(IOException("Realtime response text exceeds the bounded caption budget"), processingEpoch)
            return
        }
        responseText.append(text)
        publish(ExecutorEvent.TextDelta(text, responseId(id)))
    }

    private suspend fun textFinal(text: String, id: String?, stream: TextStream) {
        if (!acceptResponse(id) || finalTextSent) return
        if (textStream == null) textStream = stream
        if (textStream != stream) return
        val completeText = text.ifEmpty { responseText.toString() }
        if (completeText.isEmpty()) return
        finalTextSent = true
        publish(ExecutorEvent.AssistantTextFinal(completeText, responseId(id)))
    }

    private fun ignoreResponse(id: String) {
        ignoredResponseIds.add(id)
        if (ignoredResponseIds.size > 128) ignoredResponseIds.remove(ignoredResponseIds.first())
    }

    private fun finishResponse() = synchronized(responseLock) {
        activeResponseId?.let(::ignoreResponse)
        activeResponseId = null
        responseActive = false
        suppressAnonymousResponse = true
    }

    private fun clearResponse() {
        finishResponse()
        textStream = null
        responseText.clear()
        finalTextSent = false
    }

    private fun publish(event: ExecutorEvent, warningResponseGeneration: Long? = null) {
        val epoch = processingEpoch
        synchronized(epochGate) {
            if (!accepting() || epoch != currentEpoch) return
            if (event is ExecutorEvent.ConnectionChanged) _connectionState.value = event.state
        }
        val bytes = when (event) {
            is ExecutorEvent.AudioDelta -> event.pcm.size.toLong()
            is ExecutorEvent.TextDelta -> event.text.length * 2L
            is ExecutorEvent.AssistantTextFinal -> event.text.length * 2L
            is ExecutorEvent.UserTranscript -> event.text.length * 2L
            is ExecutorEvent.ResponseIncomplete -> (event.responseId?.length ?: 0) * 2L
            is ExecutorEvent.ToolCallReady -> (event.call.name.length + event.call.callId.length + event.call.argumentsJson.length) * 2L
            else -> 0L
        }
        if (queuedEventBytes.addAndGet(bytes) > maxEventBytes) {
            queuedEventBytes.addAndGet(-bytes)
            terminate(IOException("Realtime subscriber event byte budget exceeded"), epoch)
            return
        }
        if (outgoing.trySend(Event(epoch, event, bytes, warningResponseGeneration)).isFailure) {
            queuedEventBytes.addAndGet(-bytes)
            terminate(IOException("Realtime subscriber cannot keep up; event queue is full"), epoch)
        }
    }

    private fun restartEventForwarder() {
        eventForwarder?.cancel()
        eventForwarder = workerScope.launch(start = CoroutineStart.UNDISPATCHED) {
            _events.subscriptionCount.first { it > 0 }
            for (envelope in outgoing) {
                queuedEventBytes.addAndGet(-envelope.bytes)
                ensureActive()
                val warningCurrent = envelope.warningResponseGeneration?.let { generation ->
                    synchronized(responseLock) { generation == responseGeneration }
                } ?: true
                if (envelope.epoch == currentEpoch && accepting() && warningCurrent && deliverable(envelope.event)) _events.emit(envelope.event)
            }
        }
    }

    private fun allowTranscript(itemId: String?): Boolean = synchronized(responseLock) {
        itemId !in internalInputItems && (itemId == null || activeUserInputId == null || itemId == activeUserInputId)
    }

    private fun deliverable(event: ExecutorEvent): Boolean {
        if (event is ExecutorEvent.UserTranscript) return allowTranscript(event.itemId)
        if (event is ExecutorEvent.ToolCallReady) return synchronized(responseLock) { validTool(event.call) }
        val responseId = when (event) {
            is ExecutorEvent.AudioDelta -> event.responseId
            is ExecutorEvent.TextDelta -> event.responseId
            is ExecutorEvent.AssistantTextFinal -> event.responseId
            is ExecutorEvent.ResponseStarted -> event.responseId
            is ExecutorEvent.ResponseIncomplete -> event.responseId
            else -> null
        }
        return responseId == null || synchronized(responseLock) { responseId !in cancelledResponseIds }
    }

    private fun recoverConnection(failure: Throwable, expectedEpoch: Long? = null): Boolean {
        val epoch: Long
        val current: ITransportClient
        synchronized(epochGate) {
            if (!accepting()) return false
            if (expectedEpoch != null && expectedEpoch != currentEpoch) return true
            epoch = currentEpoch
            current = transport ?: return false
            lastRecoveryFailure = failure
            _ready.value = false
            invalidateToolWork()
            finishResponse()
        }
        configurationWatchdog?.cancel()
        val problem = failure as? TransportFailure ?: TransportFailure("Realtime session needs reconnection", true, cause = failure)
        val transportEpoch = current.connectionEpoch
        if (transportEpoch != 0L && transportEpoch != epoch) return true
        if (current.requestReconnect(problem, if (transportEpoch == 0L) 0L else epoch)) return true
        if (currentEpoch != epoch || current.connectionEpoch != transportEpoch) return accepting()
        terminate(failure, epoch)
        return false
    }

    private fun terminate(failure: Throwable, expectedEpoch: Long? = null) {
        synchronized(epochGate) {
            if (userClosed.get() || terminated.get() || (expectedEpoch != null && expectedEpoch != currentEpoch)) return
            // 先发布原始故障再撤销accepting，发送线程才能保留鉴权/限流分类。
            terminalFailure = failure
            terminated.set(true)
            _ready.value = false
            _connectionState.value = ExecutorConnectionState.FAILED
        }
        prepared.completeExceptionally(failure)
        invalidateToolWork()
        configurationWatchdog?.cancel()
        transport?.setListener(null)
        transport?.close()
        transport = null
        callbacks.cancel()
        queuedCallbackBytes.set(0L)
        callbackJob.cancel()
        outgoing.cancel()
        queuedEventBytes.set(0L)
        eventForwarder?.cancel()
        finishResponse()
        // 终止通知不经过已满队列；单个、有归属的任务等待现有订阅者恢复消费。
        terminalDelivery = workerScope.launch {
            _events.subscriptionCount.first { it > 0 }
            _events.emit(ExecutorEvent.ConnectionChanged(ExecutorConnectionState.FAILED, currentAttempt))
            _events.emit(ExecutorEvent.Error(failure, recoverable = false))
        }
    }

    private fun sendRequired(message: String, expectedEpoch: Long = currentEpoch) {
        if (!accepting()) {
            throw inputFailure("Realtime session has terminated", recoverableWhenActive = false)
        }
        if (expectedEpoch != currentEpoch) {
            throw inputFailure("Stale input from an earlier connection was discarded")
        }
        if (!_ready.value) {
            throw inputFailure("Realtime connection is not ready; discard current input")
        }
        val current = transport ?: throw inputFailure("Realtime transport is unavailable")
        // epoch=0 是旧版/第三方 Fake 通道的兼容值；真实通道必须在发送锁内校验原始令牌。
        val transportEpoch = current.connectionEpoch
        if (transportEpoch != 0L && transportEpoch != expectedEpoch) {
            throw inputFailure("Transport changed before input could be sent")
        }
        if (!current.sendText(message, if (transportEpoch == 0L) 0L else expectedEpoch)) {
            if (!accepting()) {
                throw inputFailure("Realtime session failed while sending", recoverableWhenActive = false)
            }
            if (currentEpoch != expectedEpoch || current.connectionEpoch != transportEpoch) {
                throw inputFailure("Connection changed while sending; stale input was discarded")
            }
            val recovering = recoverConnection(IOException("Realtime outgoing queue rejected the current input"), expectedEpoch)
            throw inputFailure("Realtime input was discarded while reconnecting", recovering)
        }
    }

    private fun inputFailure(message: String, recoverableWhenActive: Boolean = true): RealtimeSendException =
        RealtimeSendException(message, accepting() && recoverableWhenActive, terminalFailure)

    override fun sendAudioFrame(pcm: ByteBuffer) = sendAudioFrame(pcm, inputEpoch)

    override fun sendAudioFrame(pcm: ByteBuffer, expectedEpoch: Long) {
        val duplicate = pcm.duplicate()
        val bytes = ByteArray(duplicate.remaining())
        duplicate.get(bytes)
        synchronized(toolSendGate) {
            if (controlledInput) return
            sendRequired(RealtimeProtocol.appendAudio(bytes), expectedEpoch)
            synchronized(epochGate) {
                if (currentEpoch == expectedEpoch) {
                    audioBytesSent.addAndGet(bytes.size.toLong())
                    vadAudioBytesSent.addAndGet(bytes.size.toLong())
                }
            }
        }
    }

    override fun sendVideoFrame(jpeg: ByteArray) = sendVideoFrame(jpeg, inputEpoch)

    override fun sendVideoFrame(jpeg: ByteArray, expectedEpoch: Long) {
        if (!supportsVideoInput) return
        require(RealtimeProtocol.base64Length(jpeg.size) <= RealtimeProtocol.MAX_IMAGE_BASE64_BYTES) {
            "Realtime image exceeds the Base64 frame limit"
        }
        synchronized(toolSendGate) {
            if (controlledInput) return
            sendRequired(RealtimeProtocol.appendVideoFrame(jpeg), expectedEpoch)
        }
    }

    override fun sendUserText(text: String) = synchronized(toolSendGate) {
        invalidateToolWork()
        val epoch = inputEpoch
        sendRequired(conversationItem("user", text), epoch)
        sendRequired(RealtimeProtocol.createResponse(), epoch)
    }

    override fun injectContext(text: String) = sendRequired(conversationItem("system", text))

    /** responseLock must be held. */
    private fun validTool(call: ExecutorToolCall): Boolean {
        val issued = issuedTools[call.callId] ?: return false
        return accepting() && _ready.value && call.inputEpoch == currentEpoch &&
            issued.call == call && issued.generation == interactionGeneration
    }

    private fun invalidateToolWork() = synchronized(responseLock) {
        interactionGeneration++
        issuedTools.clear()
        responseToolCalls.clear()
        sessionControlAck?.complete(false)
        audioCommitAck?.complete(false)
        mediaResponseAck?.complete(false)
    }

    override fun completeTool(call: ExecutorToolCall, outputJson: String, requestResponse: Boolean): Boolean = synchronized(toolSendGate) {
        require(outputJson.length <= 65_536) { "Realtime tool result exceeds its bounded budget" }
        val valid = synchronized(responseLock) {
            if (!validTool(call) || controlledInput) false else { issuedTools.remove(call.callId); true }
        }
        if (!valid) return@synchronized false
        sendRequired(RealtimeProtocol.toolOutput(call.callId, outputJson), call.inputEpoch)
        if (requestResponse) sendRequired(RealtimeProtocol.createResponse(), call.inputEpoch)
        true
    }

    override suspend fun respondToToolWithMedia(
        call: ExecutorToolCall,
        outputJson: String,
        questionPcm: ByteArray,
        comparisonJpeg: ByteArray,
    ): Boolean = mediaMutex.withLock {
        require(supportsVideoInput) { "Realtime provider does not support video input" }
        require(questionPcm.size in (profile.audioSampleRate / 5)..(profile.audioSampleRate * 2 * 30) && questionPcm.size % 2 == 0) {
            "Comparison requires a complete PCM question within the 30 second budget"
        }
        require(comparisonJpeg.isNotEmpty() && RealtimeProtocol.base64Length(comparisonJpeg.size) <= RealtimeProtocol.MAX_IMAGE_BASE64_BYTES) {
            "Comparison image is missing or exceeds its frame budget"
        }
        require(outputJson.length <= 65_536) { "Realtime tool result exceeds its bounded budget" }
        val generation = synchronized(toolSendGate) {
            synchronized(responseLock) {
                if (!validTool(call)) return@withLock false
                issuedTools.remove(call.callId)
                controlledInput = true
                interactionGeneration
            }
        }
        val epoch = call.inputEpoch
        fun stillCurrent(): Boolean = synchronized(responseLock) {
            accepting() && _ready.value && epoch == currentEpoch && generation == interactionGeneration
        }
        var comparisonCompleted = false
        var responseRequested = false
        try {
            synchronized(toolSendGate) {
                if (!stillCurrent()) return@withLock false
                sendRequired(RealtimeProtocol.toolOutput(call.callId, outputJson), epoch)
            }
            // No tools in the comparison round prevents an accidental recursive retrieval.
            if (!updateControlSession(epoch, serverVad = false, toolSchemas = "[]", generation = generation)) return@withLock false
            if (!stillCurrent()) return@withLock false
            sendRequired(RealtimeProtocol.clearAudio(), epoch)
            val chunkBytes = profile.audioSampleRate * 2 / 50 // 20 ms, PCM16 mono.
            var offset = 0
            while (offset < questionPcm.size) {
                val length = minOf(chunkBytes, questionPcm.size - offset)
                synchronized(toolSendGate) {
                    if (!stillCurrent()) return@withLock false
                    sendRequired(RealtimeProtocol.appendAudio(questionPcm, offset, length), epoch)
                    synchronized(epochGate) { if (currentEpoch == epoch) audioBytesSent.addAndGet(length.toLong()) }
                    if (offset == 0) sendRequired(RealtimeProtocol.appendVideoFrame(comparisonJpeg), epoch)
                }
                offset += length
                delay(20L) // Keep outgoing buffers bounded and preserve the source audio time base.
            }
            if (!stillCurrent()) return@withLock false
            val commit = CompletableDeferred<Boolean>()
            synchronized(responseLock) { audioCommitAck = commit }
            try {
                synchronized(toolSendGate) {
                    if (!stillCurrent()) return@withLock false
                    synchronized(responseLock) { awaitingInternalCommit = true }
                    sendRequired(RealtimeProtocol.commitAudio(), epoch)
                }
                if (withTimeoutOrNull(configurationTimeoutMs) { commit.await() } != true || !stillCurrent()) return@withLock false
            } finally {
                synchronized(responseLock) { if (audioCommitAck === commit) audioCommitAck = null }
            }
            val done = CompletableDeferred<Boolean>()
            synchronized(responseLock) {
                mediaResponseAck = done
                mediaResponseId = null
                mediaResponseRequested = true
            }
            synchronized(toolSendGate) {
                if (!stillCurrent()) return@withLock false
                responseRequested = true
                sendRequired(RealtimeProtocol.createResponse(), epoch)
            }
            val completed = withTimeoutOrNull(mediaResponseTimeoutMs) { done.await() } == true
            comparisonCompleted = completed && stillCurrent()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } finally {
            // A cancellation before response.created has no response ID to filter. Reconnect rather
            // than let an unidentifiable, delayed answer enter the next live conversation.
            val unacknowledgedResponse = synchronized(responseLock) { responseRequested && mediaResponseId == null }
            if (!comparisonCompleted && unacknowledgedResponse && accepting() && _ready.value && currentEpoch == epoch) {
                recoverConnection(IOException("Realtime comparison cancelled before response acknowledgement"), epoch)
            } else if (!comparisonCompleted && stillCurrent()) cancelActiveResponse()
            // Cancellation may occur after the server accepted manual mode. Restore it before reopening live input.
            val connectionCurrent = accepting() && _ready.value && currentEpoch == epoch
            if (connectionCurrent) {
                val restored = withContext(NonCancellable) {
                    runCatching {
                        sendRequired(RealtimeProtocol.clearAudio(), epoch)
                        updateControlSession(epoch, serverVad = true, toolSchemas = toolsJson ?: "[]")
                    }.getOrDefault(false)
                }
                if (!restored) comparisonCompleted = false
                if (!restored && accepting() && currentEpoch == epoch) {
                    recoverConnection(IOException("Realtime comparison session could not restore live input"), epoch)
                }
            } else comparisonCompleted = false // A new connection configures its own original VAD/tools.
            synchronized(responseLock) {
                controlledInput = false
                mediaResponseAck = null
                mediaResponseId = null
                mediaResponseRequested = false
            }
        }
        comparisonCompleted
    }

    private suspend fun updateControlSession(epoch: Long, serverVad: Boolean, toolSchemas: String, generation: Long? = null): Boolean {
        val ack = CompletableDeferred<Boolean>()
        synchronized(responseLock) {
            sessionControlAck = ack
            expectedControlVad = serverVad
            expectedControlToolCount = org.json.JSONArray(toolSchemas).length()
        }
        return try {
            synchronized(toolSendGate) {
                if (generation != null && synchronized(responseLock) { generation != interactionGeneration }) return false
                sendRequired(RealtimeProtocol.sessionUpdate(
                    model = model, voice = voice ?: profile.defaultVoice, instructions = mergedInstructions,
                    serverVad = serverVad, profile = profile, toolsJson = toolSchemas,
                ), epoch)
            }
            withTimeoutOrNull(configurationTimeoutMs) { ack.await() } == true &&
                accepting() && _ready.value && currentEpoch == epoch
        } finally {
            synchronized(responseLock) {
                if (sessionControlAck === ack) {
                    sessionControlAck = null
                    expectedControlVad = null
                    expectedControlToolCount = null
                }
            }
        }
    }

    private fun conversationItem(role: String, text: String): String = org.json.JSONObject()
        .put("type", "conversation.item.create")
        .put("item", org.json.JSONObject().put("type", "message").put("role", role)
            .put("content", org.json.JSONArray().put(org.json.JSONObject()
                .put("type", "input_text").put("text", text))))
        .toString()

    private fun cancelActiveResponse() = synchronized(toolSendGate) {
        invalidateToolWork()
        val epoch = inputEpoch
        val shouldCancel = synchronized(responseLock) {
            // 即使 response.done 已处理，用户的新提问也会使尚未交付的旧轮提示失效。
            responseGeneration++
            if (!responseActive) false else {
                activeResponseId?.let {
                    cancelledResponseIds.add(it)
                    if (cancelledResponseIds.size > 128) cancelledResponseIds.remove(cancelledResponseIds.first())
                }
                finishResponse()
                true
            }
        }
        if (shouldCancel) sendRequired(RealtimeProtocol.cancelResponse(), epoch)
    }

    override fun interrupt() = cancelActiveResponse()

    override fun shutdown() {
        synchronized(epochGate) {
            if (!userClosed.compareAndSet(false, true)) return
            _ready.value = false
            _connectionState.value = ExecutorConnectionState.CLOSED
        }
        prepared.completeExceptionally(IOException("Realtime session shut down"))
        invalidateToolWork()
        configurationWatchdog?.cancel()
        transport?.setListener(null)
        transport?.close()
        transport = null
        callbacks.cancel()
        queuedCallbackBytes.set(0L)
        outgoing.cancel()
        queuedEventBytes.set(0L)
        terminalDelivery?.cancel()
        lifetime.cancel()
        finishResponse()
    }
}
