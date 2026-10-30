package com.edgevoice.agent.core

import android.os.SystemClock
import android.util.Log
import com.edgevoice.agent.core.governance.Governance
import com.edgevoice.agent.core.hook.HookContext
import com.edgevoice.agent.core.hook.HookDecision
import com.edgevoice.agent.core.hook.HookRegistry
import com.edgevoice.agent.core.memory.ContextStore
import com.edgevoice.agent.core.memory.PreferenceDistiller
import com.edgevoice.agent.core.history.ComparisonImageComposer
import com.edgevoice.agent.core.history.HistoryFrameCache
import com.edgevoice.agent.core.history.HistoryMemoryClient
import com.edgevoice.agent.core.history.HistoryMemoryConfig
import com.edgevoice.agent.core.history.HistoryFailureKind
import com.edgevoice.agent.core.history.HistoryQuestionCapture
import com.edgevoice.agent.core.history.HistoryQuestionSnapshot
import com.edgevoice.agent.core.history.HistorySaveIntent
import com.edgevoice.agent.core.history.HistoryStatus
import com.edgevoice.agent.core.history.HistoryToolAction
import com.edgevoice.agent.core.history.HistoryToolCoordinator
import com.edgevoice.agent.core.history.HistoryToolDefinitions
import com.edgevoice.agent.core.history.HistoryToolFailures
import com.edgevoice.agent.core.history.HistoryUiState
import com.edgevoice.agent.core.state.AgentState
import com.edgevoice.agent.core.state.AgentStateMachine
import com.edgevoice.agent.executor.AgentExecutorFactory
import com.edgevoice.agent.executor.ExecutorEvent
import com.edgevoice.agent.executor.ExecutorMode
import com.edgevoice.agent.executor.ExecutorConnectionState
import com.edgevoice.agent.executor.IAgentExecutor
import com.edgevoice.agent.executor.ExecutorToolCall
import com.edgevoice.agent.executor.RealtimeSendException
import com.edgevoice.agent.media.audio.AudioCaptureEngine
import com.edgevoice.agent.media.audio.AudioPlaybackEngine
import com.edgevoice.agent.media.audio.JitterBuffer
import com.edgevoice.agent.media.audio.PcmUtils
import com.edgevoice.agent.media.audio.AudioSessionController
import com.edgevoice.agent.media.ring.NativeRingBuffer
import com.edgevoice.agent.media.video.JpegFrameEncoder
import com.edgevoice.agent.media.video.VideoFrame
import com.edgevoice.agent.core.vision.DiskFrameRing
import com.edgevoice.agent.core.vision.ISessionFrameClient
import com.edgevoice.agent.core.vision.PcmEchoRing
import com.edgevoice.agent.core.vision.SessionFrameUploader
import com.edgevoice.agent.core.vision.VisionReplayPlayer
import com.edgevoice.agent.core.vision.VisionUplinkPipeline
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicLong
import java.util.UUID
import org.json.JSONObject

/**
 * 媒体链路运行快照（调试抽屉 1s 轮询，非热流）。
 *
 * @param uplinkRingPct 上行采集环占用率 0..1（生产者 AudioRecord / 消费者上行泵的压差）
 * @param downlinkBufferedMs 下行 JitterBuffer 当前缓冲时长
 * @param jitterLossRate 下行异常分片占比（欠载+丢弃 / 推入），0..1
 * @param visionActive 视觉上行管道是否在运行（开关关 / 编码器未注入时为 false，UI 据此隐藏卡片）
 * @param visionSentFrames 本次会话实际发出的画面帧累计
 * @param visionDroppedFrames 被丢帧累计（1fps 闸门 / 队列丢旧 / 能力变更 / 预算）
 * @param visionRejectedFrames 编码后仍超 256KB 硬闸被放弃的帧累计
 * @param visionLastFrameBase64Kb 最近发出一帧的 Base64 后体积（KB，256KB 红线的实测口径）
 * @param recallEnabled 情景回忆是否已装配（帧环+回声环+回放器）；false 时 UI 隐藏回忆卡片
 * @param frameRingCount 磁盘帧环当前留存帧数
 * @param frameRingMb 帧环当前总占用（MB）
 * @param frameRingOldestAgeSec 最旧一帧的年龄（秒，null=空环；看板上对照 30min 上限）
 * @param replayCount 本次会话成功触发的回放次数
 * @param replayLastSkip 最近一次回放未触发的门控原因（not_recall/no_frames/audio_too_short…）
 * @param sessionFrameEnabled 云端会话帧留存是否在跑（false 时 UI 隐藏上传卡片）
 * @param sessionUploadFrames 服务端确认接收的帧累计（V3）
 * @param sessionUploadBatches 成功上传批次数
 * @param sessionUploadFailures 失败批次数（静默降级丢弃）
 * @param sessionUploadPendingFrames 当前本地待上传帧数
 */
data class MediaTelemetry(
    val uplinkRingPct: Float,
    val downlinkBufferedMs: Int,
    val jitterLossRate: Float,
    val visionActive: Boolean = false,
    val visionSentFrames: Long = 0L,
    val visionDroppedFrames: Long = 0L,
    val visionRejectedFrames: Long = 0L,
    val visionLastFrameBase64Kb: Int = 0,
    val recallEnabled: Boolean = false,
    val frameRingCount: Int = 0,
    val frameRingMb: Int = 0,
    val frameRingOldestAgeSec: Int? = null,
    val replayCount: Long = 0L,
    val replayLastSkip: String? = null,
    val sessionFrameEnabled: Boolean = false,
    val sessionUploadFrames: Long = 0L,
    val sessionUploadBatches: Long = 0L,
    val sessionUploadFailures: Long = 0L,
    val sessionUploadPendingFrames: Int = 0,
    val visionLastFrameAgeMs: Long? = null,
    val visionRecentFps: Float = 0f,
    val captureBytes: Long = 0,
    val uplinkBytes: Long = 0,
    val receivedAudioBytes: Long = 0,
    val playedAudioBytes: Long = 0,
    val inputDiscardedBytes: Long = 0,
    val microphoneActive: Boolean = false,
    val echoCancellation: String = "UNAVAILABLE",
    val noiseSuppression: String = "UNAVAILABLE",
    val lastResponseLatencyMs: Long? = null,
    val lastBargeInHandlingMs: Long? = null,
)

/**
 * Agent 编排引擎 —— 整个 SDK 的大脑（架构文档 §2.2）。
 *
 * 职责：
 *  1. 驱动状态机（状态经 StateFlow 投影给 UI，单向数据流）；
 *  2. 打通「采集 → 上行 / 下行 → 播放」音频闭环（两条 SPSC 环，无锁）；
 *  3. 消费执行器统一事件流，串联记忆、治理、Hook；
 *  4. 处理 barge-in 全链路（状态抢占 + 播放清队列 + 传输取消）。
 *
 * 线程模型：
 *  - 事件消费在 [scope]（Default）串行执行，避免状态迁移竞态；
 *  - 音频上行泵在 IO 线程独立运行；
 *  - UI 只读 StateFlow，不持有任何锁。
 */
class OrchestrationEngine(
    private val config: AgentConfig,
    private val executorFactory: AgentExecutorFactory,
    private val contextStore: ContextStore,
    /**
     * 视觉帧 JPEG 编码器（注入以便单测/装配）。null = 视觉上行关闭：
     * 相机帧仍登记时间戳锚点，但不编码、不入队、不上行。
     */
    private val frameEncoder: JpegFrameEncoder? = null,
    /**
     * V2 情景回忆磁盘帧环（app 装配 cacheDir/edgevoice/frames）。
     * null 或 replay.enabled=false 时：管道不旁路落盘，回声环/回放编排均不创建。
     */
    private val frameRing: DiskFrameRing? = null,
    /**
     * V3 云端会话帧留存客户端（app 装配 HttpSessionFrameClient）。
     * null 或 config.sessionFrame 未激活时：不创建上传器/不做召回，零开销。
     */
    private val sessionFrameClient: ISessionFrameClient? = null,
    private val historyClient: HistoryMemoryClient? = null,
    private val historyConfig: HistoryMemoryConfig = HistoryMemoryConfig(),
    private val governance: Governance = Governance(),
    private val hooks: HookRegistry = HookRegistry(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val audioSession: AudioSessionController? = null,
) {
    val stateMachine = AgentStateMachine()

    /** UI 订阅：当前状态 */
    val state: StateFlow<AgentState> = stateMachine.state

    /** UI 订阅：麦克风 RMS（LISTENING 态律动） */
    private val _inputLevel = MutableStateFlow(0f)
    val inputLevel: StateFlow<Float> = _inputLevel.asStateFlow()

    /** UI 订阅：下行音频 RMS（SPEAKING 态律动） */
    private val _outputLevel = MutableStateFlow(0f)
    val outputLevel: StateFlow<Float> = _outputLevel.asStateFlow()

    /** UI 订阅：协议级 RTT（调试抽屉） */
    private val _rttMs = MutableStateFlow(0L)
    val rttMs: StateFlow<Long> = _rttMs.asStateFlow()

    /** UI 订阅：最近一次用户转写 */
    private val _lastUserText = MutableStateFlow("")
    val lastUserText: StateFlow<String> = _lastUserText.asStateFlow()

    private val captions = ConversationCaptionStore()
    val conversation: StateFlow<ConversationCaption> = captions.state
    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()
    private val _sessionStatus = MutableStateFlow(SessionStatus())
    val sessionStatus: StateFlow<SessionStatus> = _sessionStatus.asStateFlow()
    private val _audioRouteLabel = MutableStateFlow("音频未启动")
    val audioRouteLabel: StateFlow<String> = _audioRouteLabel.asStateFlow()

    private val _historyState = MutableStateFlow(HistoryUiState())
    val historyState: StateFlow<HistoryUiState> = _historyState.asStateFlow()
    private val historyEnabled = historyConfig.enabled && historyClient != null
    private val historyFrameCache = if (historyEnabled) HistoryFrameCache() else null
    private val historyQuestionCapture = if (historyEnabled) HistoryQuestionCapture(
        bytesPerSecond = config.captureAudio.bytesFor(1_000).toInt(),
    ) else null
    private val historyCoordinator = historyClient?.takeIf { historyEnabled }?.let { client ->
        HistoryToolCoordinator(client, historyConfig, ComparisonImageComposer(historyConfig.timeZoneId)::compose)
    }
    private var historyQuestionSequence = 0L
    private var historyQuestionSnapshot: HistoryQuestionSnapshot? = null
    private var historyUserItemId: String? = null
    private var historyTranscript = CompletableDeferred<String>()
    private var historyOriginalTranscript: String? = null
    private var historyCallsThisQuestion = 0
    private var historySessionId = UUID.randomUUID().toString()
    private var historyToolJob: Job? = null
    private var historyAutoSaveJob: Job? = null
    private var historyAutoConfirmJob: Job? = null
    private var historyConfirmSaveJob: Job? = null
    private var historySaveResult = CompletableDeferred<HistoryToolAction.Reply>()
    private var historySaveStarted = false
    private var historyRememberCallSeen = false
    private data class PendingHistorySave(
        val call: ExecutorToolCall,
        val question: HistoryQuestionSnapshot,
        val clientSessionId: String,
        val engineSession: Long,
        val sequence: Long,
        val inputEpoch: Long,
    )
    private var pendingHistorySave: PendingHistorySave? = null
    private var historyConfirmationDismissedSequence: Long? = null
    private var historyRequestGeneration = 0L
    /** Only the live uplink is gated; playback and executor event collection remain active. */
    @Volatile private var historyInputPaused = false

    private val uplinkBytes = AtomicLong(0)
    private val downlinkBytes = AtomicLong(0)
    private val capturedOffset = AtomicLong(0)
    private val playedOffset = AtomicLong(0)
    private val discardedInputBytes = AtomicLong(0)
    private val sessionGeneration = AtomicLong(0)
    @Volatile private var mediaRunning = false
    @Volatile private var mediaInputEpoch = -1L
    private var mediaGeneration = 0L
    private var mediaStartJob: Job? = null
    private var mediaStartingEpoch: Long? = null
    private var everReady = false
    private var sessionStartedAt = 0L
    @Volatile private var lastUserActivityAt = 0L
    @Volatile private var speechStoppedAt: Long? = null
    @Volatile private var turnStartedAt: Long? = null
    @Volatile private var lastResponseLatencyMs: Long? = null
    @Volatile private var lastBargeInHandlingMs: Long? = null
    @Volatile private var activePlaybackGeneration = -1L
    @Volatile private var responseInProgress = false
    private val responseGate = Any()
    private val visionGate = Any()
    @Volatile private var visionEnabled = false

    /** 上行环形缓冲：AudioRecord 采集线程（生产者）→ 上行泵（消费者） */
    private val ring = NativeRingBuffer.create(
        config.captureAudio.bytesFor(config.ringCapacityMs).toInt(),
    )
    private val capture = AudioCaptureEngine(
        config = config.captureAudio,
        ring = ring,
        frameMs = config.pumpFrameMs,
        onLevel = { _inputLevel.value = it },
        onError = { failure ->
            val session = sessionGeneration.get()
            scope.launch { failSession(failure, session, SessionErrorKind.AUDIO_DEVICE) }
        },
    )
    private val jitter = JitterBuffer(config.playbackAudio)
    private val playback = AudioPlaybackEngine(
        config.playbackAudio,
        jitter,
        frameMs = config.pumpFrameMs,
        onPlaybackComplete = { generation ->
            scope.launch {
                synchronized(responseGate) {
                    if (started && generation == activePlaybackGeneration && !responseInProgress) {
                        turnStartedAt = null
                        _outputLevel.value = 0f
                        stateMachine.transitionTo(AgentState.LISTENING)
                        Log.i("EdgeVoiceSession", "playback complete: bytes=${playbackBytes()}")
                    }
                }
            }
        },
        onError = { failure ->
            val session = sessionGeneration.get()
            scope.launch { failSession(failure, session, SessionErrorKind.AUDIO_DEVICE) }
        },
    )

    private fun playbackBytes(): Long = playedOffset.get() + if (mediaRunning) playback.playedBytes else 0L
    private fun captureBytes(): Long = capturedOffset.get() + if (mediaRunning) capture.capturedBytes else 0L

    init {
        require(config.maxSessionDurationMs > 0 && config.idleTimeoutMs > 0 && config.maxTurnDurationMs > 0)
        require(config.maxCaptureBacklogMs >= config.pumpFrameMs)
        audioSession?.let { controller ->
            scope.launch {
                controller.route.collect { route ->
                    _audioRouteLabel.value = route.label
                    if (!route.active) synchronized(this@OrchestrationEngine) {
                        if (mediaRunning && !controller.route.value.active) {
                            failSession(
                                IllegalStateException("Active communication route changed"),
                                preferredKind = SessionErrorKind.AUDIO_DEVICE,
                                userMessage = "通话音频设备已断开或正在切换，会话已停止；请确认耳机就绪后手动重新开始。",
                            )
                        }
                    }
                }
            }
        }
    }

    private val distiller = PreferenceDistiller()

    /** 下行分包暂存（DirectByteBuffer，避免每帧堆分配） */
    private val downlinkBuf = ByteBuffer.allocateDirect(
        config.playbackAudio.bytesFor(120).toInt(),
    ).order(java.nio.ByteOrder.LITTLE_ENDIAN)

    @Volatile private var started = false
    @Volatile private var closed = false
    private var executorSub: Job? = null
    private var pumpJob: Job? = null
    private var maintenanceJob: Job? = null

    /** 视觉上行管道；frameEncoder 为空或配置关闭时恒为 null */
    private var visionPipeline: VisionUplinkPipeline? = null

    /** V2：上行 PCM 原声尾环（仅帧环+回放配置同时可用时创建） */
    private val echoRing: PcmEchoRing? =
        if (frameRing != null && config.replay.enabled) PcmEchoRing(config.replay.echoRingBytes()) else null

    /** V2：回放编排器，与视觉管道同生命周期 */
    private var replayPlayer: VisionReplayPlayer? = null

    /** V3：会话帧周期批量上传器，与视觉管道同生命周期 */
    private var frameUploader: SessionFrameUploader? = null

    // ---------------- 生命周期 ----------------

    /** 仅恢复停止状态下的最近五轮展示字幕，不连接模型、不启动音视频或恢复历史上下文。 */
    @Synchronized
    fun restoreConversation(caption: ConversationCaption): Boolean {
        if (closed || started || _sessionStatus.value.phase != SessionPhase.STOPPED) return false
        captions.restore(caption)
        return true
    }

    suspend fun start(mode: ExecutorMode) {
        val session = synchronized(this) {
            check(!closed) { "Agent engine is closed" }
            if (started) return
            val next = sessionGeneration.incrementAndGet()
            _errorMessage.value = null
            // 新会话保留仅供界面查看的最近五轮；未形成文字的 VAD 误触不跨会话。
            captions.cancelPendingTurn()
            _lastUserText.value = ""
            uplinkBytes.set(0)
            downlinkBytes.set(0)
            capturedOffset.set(0)
            playedOffset.set(0)
            discardedInputBytes.set(0)
            everReady = false
            lastResponseLatencyMs = null
            lastBargeInHandlingMs = null
            turnStartedAt = null
            sessionStartedAt = nowMs()
            lastUserActivityAt = sessionStartedAt
            ring.clear()
            jitter.reset()
            clearHistoryWork()
            historySessionId = UUID.randomUUID().toString()
            _historyState.value = HistoryUiState()
            started = true
            stateMachine.forceTo(AgentState.IDLE_OBSERVING)
            _sessionStatus.value = SessionStatus(SessionPhase.CONNECTING, message = "正在连接模型")
            next
        }
        startMaintenance(session)
        try {
            switchExecutor(mode)
            if (started && session == sessionGeneration.get() && executorFactory.current?.ready?.value == true) {
                resumeMedia(session)
            }
        } catch (cancelled: CancellationException) {
            if (started && session == sessionGeneration.get()) stop()
            throw cancelled
        } catch (failure: Throwable) {
            failSession(failure, session)
            throw failure
        }
    }

    /** Irreversible owner cleanup; JNI memory must outlive every possible worker access. */
    @Synchronized
    fun close() {
        if (closed) return
        closed = true
        val engineJob = scope.coroutineContext[Job]
        stop()
        scope.cancel()
        // This cleanup cannot be a child of the scope just cancelled. A vendor's
        // stuck AudioRecord may exceed stop's UI-safe join deadline; retain its
        // ring until its real worker exits instead of freeing live native memory.
        CoroutineScope(Dispatchers.IO).launch {
            engineJob?.join()
            capture.awaitStopped()
            playback.awaitStopped()
            ring.close()
            jitter.close()
        }
    }

    @Synchronized
    fun stop() {
        if (!started) {
            _errorMessage.value = null
            _sessionStatus.value = SessionStatus()
            return
        }
        started = false
        sessionGeneration.incrementAndGet()
        clearHistoryWork()
        maintenanceJob?.cancel()
        maintenanceJob = null
        pumpJob?.cancel()
        pumpJob = null
        executorSub?.cancel()
        executorSub = null
        synchronized(visionGate) {
            visionPipeline?.stop()
            visionPipeline = null
        }
        frameUploader?.stop()
        frameUploader = null
        replayPlayer?.reset()
        replayPlayer = null
        echoRing?.clear()
        stopMedia()
        ring.clear()
        jitter.reset()
        synchronized(responseGate) {
            if (responseInProgress || state.value == AgentState.SPEAKING) captions.interrupt()
            captions.cancelPendingTurn()
            responseInProgress = false
            activePlaybackGeneration = -1L
            turnStartedAt = null
        }
        executorFactory.current?.shutdown()
        // 会话结束：预算/迭代深度归零（安全命中计数为累计安全指标，不在此清零）
        governance.tokenBudget.reset()
        governance.iterationGuard.reset()
        _rttMs.value = 0L
        _inputLevel.value = 0f
        _outputLevel.value = 0f
        stateMachine.forceTo(AgentState.IDLE_OBSERVING)
        _errorMessage.value = null
        _sessionStatus.value = SessionStatus()
        Log.i("EdgeVoiceSession", "stopped: captured=${captureBytes()}, sent=${uplinkBytes.get()}, received=${downlinkBytes.get()}, played=${playbackBytes()}")
    }

    /** 媒体链路只读快照（供 UI 1s 轮询；普通函数避免高频 StateFlow 驱动重组）。 */
    @Synchronized
    fun mediaTelemetry(): MediaTelemetry {
        if (closed) return MediaTelemetry(0f, 0, 0f)
        val cap = ring.capacityBytes
        val pct = if (cap <= 0) 0f else ring.availableToRead().toFloat() / cap
        val js = jitter.stats()
        val vs = visionPipeline?.stats()
        val ringStats = frameRing?.stats()
        val nowWall = System.currentTimeMillis()
        val upload = frameUploader?.stats()
        return MediaTelemetry(
            uplinkRingPct = pct.coerceIn(0f, 1f),
            downlinkBufferedMs = js.bufferedMs,
            jitterLossRate = js.lossRate,
            visionActive = vs != null && mediaRunning && _sessionStatus.value.phase == SessionPhase.READY,
            visionSentFrames = vs?.sentFrames ?: 0L,
            visionDroppedFrames = vs?.droppedFrames ?: 0L,
            visionRejectedFrames = vs?.rejectedFrames ?: 0L,
            visionLastFrameBase64Kb = (vs?.lastSentBase64Bytes ?: 0) / 1024,
            recallEnabled = replayPlayer != null,
            frameRingCount = ringStats?.frameCount ?: 0,
            frameRingMb = ((ringStats?.totalBytes ?: 0L) / (1024L * 1024L)).toInt(),
            frameRingOldestAgeSec = ringStats?.oldestTimestampMs
                ?.let { ((nowWall - it) / 1000L).toInt().coerceAtLeast(0) },
            replayCount = replayPlayer?.replayCount() ?: 0L,
            replayLastSkip = replayPlayer?.lastSkipReason(),
            sessionFrameEnabled = upload != null,
            sessionUploadFrames = upload?.uploadedFrames ?: 0L,
            sessionUploadBatches = upload?.batches ?: 0L,
            sessionUploadFailures = upload?.failures ?: 0L,
            sessionUploadPendingFrames = upload?.pendingFrames ?: 0,
            visionLastFrameAgeMs = vs?.lastSentAtMs?.let { (nowMs() - it).coerceAtLeast(0) },
            visionRecentFps = vs?.recentFps ?: 0f,
            captureBytes = captureBytes(),
            uplinkBytes = uplinkBytes.get(),
            receivedAudioBytes = downlinkBytes.get(),
            playedAudioBytes = playbackBytes(),
            inputDiscardedBytes = discardedInputBytes.get(),
            microphoneActive = mediaRunning,
            echoCancellation = capture.audioProcessing.echoCancellation.name,
            noiseSuppression = capture.audioProcessing.noiseSuppression.name,
            lastResponseLatencyMs = lastResponseLatencyMs,
            lastBargeInHandlingMs = lastBargeInHandlingMs,
        )
    }

    /** 双模热切换入口（调试抽屉一键切换）。 */
    suspend fun switchExecutor(mode: ExecutorMode) {
        // 关键顺序：先取消旧订阅，再 switchTo。
        // 否则 switchTo 内部 shutdown 旧 executor 时，旧订阅者仍存活，
        // 旧通道的残余事件（如断连 Error）会流入 handleEvent 造成状态机误迁移。
        executorSub?.cancel()
        executorSub = null
        executorFactory.switchTo(
            mode,
            summarize = { contextStore.summarizeForHandover() },
            beforePrepare = ::bindExecutor,
        )
        // 新执行器尚未产生测量值：清掉旧模式残留 RTT（本地模式恒为 0）
        _rttMs.value = 0L
    }

    private fun bindExecutor(ex: IAgentExecutor) {
        executorSub?.cancel()
        val session = sessionGeneration.get()
        executorSub = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            ex.events.collect { ev ->
                if (!started || session != sessionGeneration.get()) return@collect
                try {
                    handleEvent(ev, session)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Throwable) {
                    failSession(failure, session)
                }
            }
        }
    }

    // ---------------- 事件消费 ----------------

    private suspend fun handleEvent(ev: ExecutorEvent, expectedSession: Long) {
        if (ev is ExecutorEvent.UserTranscript) {
            handleUserTranscript(ev.text, expectedSession, ev.itemId)
            return
        }
        synchronized(this) {
            if (!started || expectedSession != sessionGeneration.get()) return
            when (ev) {
                is ExecutorEvent.ConnectionChanged -> handleConnection(ev, expectedSession)
                is ExecutorEvent.Ready -> {
                    resumeMedia(expectedSession)
                    Log.i("EdgeVoiceSession", "session configured")
                }

                is ExecutorEvent.ResponseStarted -> {
                    synchronized(responseGate) {
                        if (!started || !mediaRunning) return
                        _sessionStatus.value = _sessionStatus.value.clearModelOutputWarning()
                        captions.beginResponse()
                        if (turnStartedAt == null) turnStartedAt = nowMs()
                        responseInProgress = true
                        activePlaybackGeneration = playback.beginResponse()
                        stateMachine.transitionTo(AgentState.PROCESSING)
                    }
                }

                is ExecutorEvent.AudioDelta -> {
                    synchronized(responseGate) {
                        if (!started || !mediaRunning) return
                        speechStoppedAt?.let { lastResponseLatencyMs = (nowMs() - it).coerceAtLeast(0); speechStoppedAt = null }
                        if (!responseInProgress) {
                            responseInProgress = true
                            activePlaybackGeneration = playback.beginResponse()
                        }
                        stateMachine.transitionTo(AgentState.SPEAKING)
                        deliverAudio(ev.pcm)
                    }
                }

                is ExecutorEvent.TextDelta -> {
                    if (!mediaRunning) return
                    // 模型输出同样占用上下文预算（启发式估算）
                    governance.tokenBudget.tryConsume(ev.text)
                    contextStore.recordAssistantSpeech(ev.text, nowMs())
                    captions.append(ev.text)
                }

                is ExecutorEvent.AssistantTextFinal -> if (mediaRunning) captions.finish(ev.text)

                is ExecutorEvent.ResponseIncomplete -> if (mediaRunning) {
                    _sessionStatus.value = _sessionStatus.value.withModelOutputWarning(ev.reason)
                }

                is ExecutorEvent.UserTranscript -> Unit // handled outside the lifecycle lock

                is ExecutorEvent.ToolCallReady -> beginHistoryTool(ev.call, expectedSession)

                is ExecutorEvent.UserInputIdentity -> {
                    if (!historyInputPaused) {
                        historyUserItemId = ev.itemId
                        historyQuestionCapture?.speechStartedAt(ev.audioStartMs)
                    }
                }

                is ExecutorEvent.UserInputEnd -> {
                    if (!historyInputPaused && ev.itemId != null && ev.itemId == historyUserItemId) {
                        historyQuestionCapture?.speechEndedAt(ev.audioEndMs)
                    }
                }

                ExecutorEvent.UserSpeechStarted -> {
                    if (historyInputPaused) return // controlled original-audio reinjection is not a new user turn
                    pendingHistorySave = null
                    if (_historyState.value.status == HistoryStatus.CONFIRM_SAVE) _historyState.value = HistoryUiState()
                    if (_historyState.value.status == HistoryStatus.SAVING) {
                        _historyState.value = HistoryUiState(HistoryStatus.ERROR, "上次保存被新提问打断，结果未确认；请查询或重新记录。")
                    }
                    historyToolJob?.cancel()
                    historyToolJob = null
                    historyAutoSaveJob?.cancel()
                    historyAutoSaveJob = null
                    historyAutoConfirmJob?.cancel()
                    historyAutoConfirmJob = null
                    historyConfirmSaveJob?.cancel()
                    historyConfirmSaveJob = null
                    historyRequestGeneration++
                    historyQuestionSequence++
                    historyCallsThisQuestion = 0
                    historyQuestionSnapshot = null
                    historyUserItemId = null
                    historyTranscript.complete("")
                    historyTranscript = CompletableDeferred()
                    historyOriginalTranscript = null
                    historySaveResult = CompletableDeferred()
                    historySaveStarted = false
                    historyRememberCallSeen = false
                    historyConfirmationDismissedSequence = null
                    historyQuestionCapture?.speechStartedAt(null) // identity/offset event must follow before authorizing history
                    _sessionStatus.value = _sessionStatus.value.clearModelOutputWarning()
                    lastUserActivityAt = nowMs()
                    onBargeIn()
                    captions.nextTurn()
                }

                ExecutorEvent.UserSpeechStopped -> {
                    if (historyInputPaused) return
                    if (historyEnabled) historyQuestionSnapshot = HistoryQuestionSnapshot(
                        historyQuestionCapture?.speechStopped(), historyFrameCache?.snapshot(nowMs()), historyQuestionSequence,
                    )
                    scheduleExplicitHistorySaveIfReady(expectedSession)
                    scheduleAmbiguousHistoryConfirmationIfReady(expectedSession)
                    speechStoppedAt = nowMs()
                    turnStartedAt = speechStoppedAt
                    if (mediaRunning) stateMachine.transitionTo(AgentState.PROCESSING)
                }

                ExecutorEvent.TurnComplete -> onTurnComplete()

                is ExecutorEvent.RttMeasured -> _rttMs.value = ev.rttMs

                is ExecutorEvent.Error -> {
                    governance.circuitBreaker.onFailure()
                    if (ev.recoverable) pauseForRecovery(expectedSession)
                    else failSession(ev.cause, expectedSession)
                }
            }
            Unit
        }
    }

    @Synchronized
    private fun handleConnection(event: ExecutorEvent.ConnectionChanged, session: Long) {
        if (!started || session != sessionGeneration.get()) return
        when (event.state) {
            ExecutorConnectionState.READY -> resumeMedia(session)
            ExecutorConnectionState.CONNECTING, ExecutorConnectionState.CONFIGURING,
            ExecutorConnectionState.RECONNECTING -> {
                pauseForRecovery(session, updateStatus = false)
                val phase = when (event.state) {
                    ExecutorConnectionState.CONFIGURING -> SessionPhase.CONFIGURING
                    ExecutorConnectionState.RECONNECTING -> SessionPhase.RECONNECTING
                    else -> if (everReady) SessionPhase.RECONNECTING else SessionPhase.CONNECTING
                }
                _sessionStatus.value = SessionStatus(
                    phase,
                    retryAttempt = event.attempt,
                    message = when (phase) {
                        SessionPhase.CONFIGURING -> "正在确认模型会话配置"
                        SessionPhase.RECONNECTING -> "连接中断，正在恢复；当前暂停收音，请等待就绪后重新提问。"
                        else -> "正在连接模型"
                    },
                )
            }
            ExecutorConnectionState.CLOSED, ExecutorConnectionState.FAILED -> {
                // The following reliable Error carries the actual cause/status code.
                pauseForRecovery(session, updateStatus = false)
                _sessionStatus.value = SessionStatus(
                    SessionPhase.FAILED, event.attempt, "会话无法继续，请重新连接。", SessionErrorKind.NETWORK,
                )
            }
        }
    }

    @Synchronized
    private fun resumeMedia(expectedSession: Long) {
        if (!started || expectedSession != sessionGeneration.get() || executorFactory.current?.ready?.value != true) return
        val epoch = executorFactory.current?.inputEpoch ?: return
        if (mediaRunning && mediaInputEpoch == epoch) return
        if (mediaStartingEpoch == epoch) return
        if (mediaRunning || mediaStartingEpoch != null) pauseForRecovery(expectedSession, updateStatus = false)
        try {
            audioSession?.start { reason ->
                scope.launch {
                    synchronized(this@OrchestrationEngine) {
                        if (mediaInputEpoch == epoch || mediaStartingEpoch == epoch) {
                            failSession(IllegalStateException(reason), expectedSession, SessionErrorKind.AUDIO_DEVICE, reason)
                        }
                    }
                }
            }
            val controller = audioSession
            if (controller != null && !controller.route.value.active) {
                mediaStartingEpoch = epoch
                _sessionStatus.value = SessionStatus(SessionPhase.CONFIGURING, message = "正在准备通话音频设备")
                mediaStartJob = scope.launch {
                    try {
                        withTimeout(4_000L) { controller.route.first { it.active } }
                        synchronized(this@OrchestrationEngine) {
                            if (!started || expectedSession != sessionGeneration.get() || mediaStartingEpoch != epoch) return@launch
                            mediaStartJob = null
                            mediaStartingEpoch = null
                            startPhysicalMedia(expectedSession, epoch)
                        }
                    } catch (cancelled: kotlinx.coroutines.TimeoutCancellationException) {
                        failAudioPreparation(java.util.concurrent.TimeoutException("Audio route was not confirmed"), expectedSession, epoch)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Throwable) {
                        failAudioPreparation(failure, expectedSession, epoch)
                    }
                }
                return
            }
            startPhysicalMedia(expectedSession, epoch)
        } catch (failure: Throwable) {
            stopMedia()
            failSession(failure, expectedSession, if (failure is SecurityException) SessionErrorKind.PERMISSION else SessionErrorKind.AUDIO_DEVICE)
        }
    }

    @Synchronized
    private fun failAudioPreparation(failure: Throwable, expectedSession: Long, epoch: Long) {
        if (mediaStartingEpoch != epoch) return
        failSession(failure, expectedSession, SessionErrorKind.AUDIO_DEVICE)
    }

    @Synchronized
    private fun startPhysicalMedia(expectedSession: Long, epoch: Long) {
        if (!started || expectedSession != sessionGeneration.get()) return
        val ex = executorFactory.current
        if (ex?.ready?.value != true || ex.inputEpoch != epoch) {
            pauseForRecovery(expectedSession)
            return
        }
        try {
            ring.clear()
            playback.start()
            capture.start()
            mediaInputEpoch = epoch
            mediaGeneration++
            mediaRunning = true
            synchronized(visionGate) {
                startVisionPipeline()
                visionPipeline?.resetConnection()
                visionPipeline?.setUplinkPaused(false)
            }
            startAudioPump()
            val recovered = everReady
            everReady = true
            lastUserActivityAt = nowMs()
            _errorMessage.value = null
            _sessionStatus.value = SessionStatus(
                SessionPhase.READY,
                message = if (recovered) "连接已恢复，请重新提问。" else null,
            )
            stateMachine.forceTo(AgentState.LISTENING)
        } catch (failure: Throwable) {
            // Includes partial acquisition: always release audio focus and hardware.
            stopMedia()
            failSession(failure, expectedSession, if (failure is SecurityException) SessionErrorKind.PERMISSION else SessionErrorKind.AUDIO_DEVICE)
        }
    }

    /** Only the engine lifecycle lock may start/stop physical media. */
    private fun stopMedia() {
        val wasRunning = mediaRunning
        mediaStartJob?.cancel()
        mediaStartJob = null
        mediaStartingEpoch = null
        mediaRunning = false
        mediaGeneration++
        mediaInputEpoch = -1L
        runCatching { capture.stop() }
        runCatching { playback.stop() }
        if (wasRunning) {
            capturedOffset.addAndGet(capture.capturedBytes)
            playedOffset.addAndGet(playback.playedBytes)
        }
        runCatching { audioSession?.stop() }
        ring.clear()
        _inputLevel.value = 0f
        _outputLevel.value = 0f
    }

    @Synchronized
    private fun pauseForRecovery(expectedSession: Long, updateStatus: Boolean = true) {
        if (!started || expectedSession != sessionGeneration.get()) return
        clearHistoryWork()
        synchronized(responseGate) {
            if (responseInProgress || state.value == AgentState.SPEAKING) captions.interrupt()
            responseInProgress = false
            activePlaybackGeneration = -1L
        }
        stopMedia()
        synchronized(visionGate) {
            visionPipeline?.setUplinkPaused(true)
            visionPipeline?.resetConnection()
        }
        echoRing?.clear()
        speechStoppedAt = null
        turnStartedAt = null
        stateMachine.forceTo(AgentState.IDLE_OBSERVING)
        if (updateStatus) {
            _sessionStatus.value = SessionStatus(
                SessionPhase.RECONNECTING,
                retryAttempt = _sessionStatus.value.retryAttempt,
                message = "连接中断，正在恢复；当前暂停收音，请等待就绪后重新提问。",
            )
        }
    }

    @Synchronized
    private fun startMaintenance(session: Long) {
        if (!started || session != sessionGeneration.get()) return
        maintenanceJob?.cancel()
        maintenanceJob = scope.launch {
            while (isActive && started && session == sessionGeneration.get()) {
                delay(1_000)
                if (turnStartedAt?.let { nowMs() - it >= config.maxTurnDurationMs } == true) {
                    failSession(java.util.concurrent.TimeoutException("Realtime answer did not finish"), session)
                    return@launch
                }
                val reason = sessionExpiration(
                    nowMs(), sessionStartedAt, lastUserActivityAt,
                    responseInProgress || state.value == AgentState.SPEAKING,
                    config.maxSessionDurationMs, config.idleTimeoutMs,
                ) ?: continue
                synchronized(this@OrchestrationEngine) {
                    if (!started || session != sessionGeneration.get()) return@launch
                    stop()
                    _sessionStatus.value = SessionStatus(SessionPhase.STOPPED, message = reason)
                }
                return@launch
            }
        }
    }

    private suspend fun handleUserTranscript(text: String, expectedSession: Long, itemId: String?) {
        // V2：原声重注必然产生一轮与触发句相同的重复转写。抑制窗内整条事件
        // 必须在一切副作用之前丢弃 —— 不登记记忆、不触发治理、不再次回放（防循环）。
        if (historyInputPaused || replayPlayer?.isSuppressed() == true) return

        val ts = nowMs()
        var transcriptQuestion = -1L
        synchronized(this) {
            if (!started || expectedSession != sessionGeneration.get() || !mediaRunning) return
            if (historyEnabled && itemId != null && historyUserItemId != null && itemId != historyUserItemId) return
            if (historyEnabled && itemId != null && itemId == historyUserItemId) transcriptQuestion = historyQuestionSequence
            lastUserActivityAt = ts
            _lastUserText.value = text
            captions.user(text)
        }

        // 治理：注入过滤
        val cleaned = governance.sanitizer.sanitize(text)
        val ctx = HookContext(
            text = cleaned.text,
            stage = "user_speech",
            attributes = mutableMapOf("flagged" to cleaned.flagged),
        )
        // Hook 有权阻断（Block）或改写文本（Continue 携带新 context），改写后才是有效文本
        val effectiveText = when (val decision = hooks.runBefore(ctx)) {
            is HookDecision.Block -> {
                synchronized(this) {
                    if (started && expectedSession == sessionGeneration.get()) {
                        if (transcriptQuestion == historyQuestionSequence && itemId == historyUserItemId) {
                            historyTranscript.complete("")
                        }
                        stateMachine.transitionTo(AgentState.LISTENING)
                    }
                }
                return
            }
            is HookDecision.Continue -> decision.context.text
        }

        synchronized(this) {
            if (transcriptQuestion == historyQuestionSequence && started && mediaRunning &&
                expectedSession == sessionGeneration.get() && itemId == historyUserItemId && historyTranscript.complete(text)) {
                historyOriginalTranscript = text
                scheduleExplicitHistorySaveIfReady(expectedSession)
                scheduleAmbiguousHistoryConfirmationIfReady(expectedSession)
            }
        }
        if (!started || expectedSession != sessionGeneration.get() || !mediaRunning) return
        contextStore.recordUserSpeech(effectiveText, ts)

        // V2 情景回忆：启发式命中「刚才/有没有经过…」→ 抽历史帧+原声重注回放。
        // 非回忆语句零开销返回；重放循环自行挂起，不阻塞事件消费。
        replayPlayer?.let { player ->
            scope.launch { player.tryTrigger(effectiveText) }
        }

        // 用户话语触发一轮模型回复 = 一次迭代深度。
        // 诚实边界：语音 Realtime 链路无法在服务端已开始响应后阻止本轮，
        // 因此 enter() 返回 false（超深）时仅累计 blocked 供看板观测，不强行打断。
        governance.iterationGuard.enter()
        governance.tokenBudget.tryConsume(effectiveText)

        // 多模态时间戳对齐：把该话语对应的视觉帧转成文本锚点登记
        contextStore.fusion.describe(ts)?.let { contextStore.recordSystem(it, ts) }

        // 显式「记住」通道
        distiller.detectExplicit(effectiveText)?.takeIf { !historyEnabled }?.let { pref ->
            scope.launch { contextStore.remember(pref, "preference", "explicit") }
        }

        // Tier3 检索 + 注入（异步；injectContext 不触发回复）
        // 预算门控：剩余预算不足时放弃本次长期记忆注入（上层上下文压缩的第一道闸）
        val recallSession = expectedSession
        val recallExecutor = executorFactory.current
        scope.launch {
            try {
                val hits = contextStore.recall(effectiveText)
                contextStore.renderRecall(hits)?.let { rendered ->
                    if (started && recallSession == sessionGeneration.get() &&
                        executorFactory.current === recallExecutor && recallExecutor?.ready?.value == true &&
                        governance.tokenBudget.tryConsume(rendered)
                    ) {
                        recallExecutor.injectContext(rendered)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                Log.w("EdgeVoiceSession", "optional memory context unavailable")
            }
        }

        // 自动提炼通道
        if (distiller.tick()) {
            scope.launch { contextStore.distillRecent() }
        }

        hooks.runAfter(ctx)
    }

    /** barge-in：状态抢占 + 播放清队列 + （执行器侧已自动 response.cancel）。 */
    private fun onBargeIn(): Unit = synchronized(responseGate) {
        if (!started || !mediaRunning) return
        val began = nowMs()
        if (responseInProgress || state.value == AgentState.SPEAKING) captions.interrupt()
        responseInProgress = false
        turnStartedAt = null
        activePlaybackGeneration = -1L
        stateMachine.transitionTo(AgentState.INTERRUPTED)
        playback.interrupt()
        _outputLevel.value = 0f
        stateMachine.transitionTo(AgentState.LISTENING)
        lastBargeInHandlingMs = nowMs() - began
        Unit
    }

    private fun onTurnComplete() = synchronized(responseGate) {
        if (!started || !mediaRunning) return
        governance.iterationGuard.onTerminal()
        responseInProgress = false
        playback.finishResponse()
    }

    /** The lifecycle lock also guards live-uplink gating and tool-result submission. */
    private fun clearHistoryWork() {
        historyRequestGeneration++
        historyQuestionSequence++
        historyToolJob?.cancel()
        historyToolJob = null
        historyAutoSaveJob?.cancel()
        historyAutoSaveJob = null
        historyAutoConfirmJob?.cancel()
        historyAutoConfirmJob = null
        historyConfirmSaveJob?.cancel()
        historyConfirmSaveJob = null
        pendingHistorySave = null
        historyConfirmationDismissedSequence = null
        historyInputPaused = false
        historyQuestionSnapshot = null
        historyUserItemId = null
        historyTranscript.complete("")
        historyTranscript = CompletableDeferred()
        historyOriginalTranscript = null
        historyCallsThisQuestion = 0
        historySaveResult = CompletableDeferred()
        historySaveStarted = false
        historyRememberCallSeen = false
        historyQuestionCapture?.clear()
        historyFrameCache?.clear()
        if (_historyState.value.status == HistoryStatus.CONFIRM_SAVE) {
            _historyState.value = HistoryUiState()
        } else if (_historyState.value.status in setOf(HistoryStatus.SAVING, HistoryStatus.SEARCHING, HistoryStatus.COMPARING)) {
            _historyState.value = _historyState.value.copy(status = HistoryStatus.ERROR, message = "会话已中断，本次历史操作已取消。")
        }
    }

    /** The preview is local; this tap is the authorization for a single HTTP save. */
    @Synchronized
    fun confirmPendingHistorySave() {
        val pending = pendingHistorySave ?: return
        if (!started || !mediaRunning || pending.engineSession != sessionGeneration.get() ||
            pending.sequence != historyQuestionSequence || pending.inputEpoch != mediaInputEpoch ||
            _historyState.value.status != HistoryStatus.CONFIRM_SAVE || historySaveStarted) {
            pendingHistorySave = null
            if (_historyState.value.status == HistoryStatus.CONFIRM_SAVE) _historyState.value = HistoryUiState()
            return
        }
        val coordinator = historyCoordinator ?: return
        pendingHistorySave = null
        historySaveStarted = true
        val result = historySaveResult
        _historyState.value = HistoryUiState(HistoryStatus.SAVING, "正在确认保存这次物品记录")
        fun current() = started && mediaRunning && pending.engineSession == sessionGeneration.get() &&
            pending.sequence == historyQuestionSequence && pending.inputEpoch == mediaInputEpoch
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val action = try {
                    withTimeout(20_000L) {
                        coordinator.executeConfirmedSave(pending.call, pending.question, pending.clientSessionId) { update ->
                            synchronized(this@OrchestrationEngine) { if (current()) _historyState.value = update }
                        }
                    }
                } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
                    HistoryToolFailures.reply(HistoryFailureKind.TIMEOUT, HistoryToolDefinitions.REMEMBER, historyConfig)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    HistoryToolFailures.reply(HistoryFailureKind.INVALID_RESPONSE, HistoryToolDefinitions.REMEMBER, historyConfig)
                }
                synchronized(this@OrchestrationEngine) {
                    if (current()) {
                        result.complete(action)
                        _historyState.value = action.state
                    }
                }
            } finally {
                synchronized(this@OrchestrationEngine) { if (current()) historyConfirmSaveJob = null }
            }
        }
        historyConfirmSaveJob = job
        job.start()
    }

    @Synchronized
    fun discardPendingHistorySave() {
        if (pendingHistorySave != null && _historyState.value.status == HistoryStatus.CONFIRM_SAVE) {
            historyConfirmationDismissedSequence = historyQuestionSequence
        }
        pendingHistorySave = null
        if (_historyState.value.status == HistoryStatus.CONFIRM_SAVE) _historyState.value = HistoryUiState()
    }

    private fun preparePendingHistorySave(
        call: ExecutorToolCall,
        question: HistoryQuestionSnapshot,
        session: Long,
        sequence: Long,
    ): Pair<PendingHistorySave, HistoryUiState>? {
        val frame = question.current ?: return null
        val frozenFrame = frame.copy(jpeg = frame.jpeg.copyOf())
        val safeCall = call.copy(argumentsJson = HistorySaveIntent.safeConfirmationArguments(call.argumentsJson, question.userTranscript))
        val name = JSONObject(safeCall.argumentsJson).optString("object_name")
            .takeIf { it.isNotBlank() && it != "这件物品" }
        val message = if (name == null) "刚才的语音有歧义。要保存这张照片吗？确认后才会存入历史记录。"
            else "刚才的语音有歧义。要把这张“$name”的照片存入历史记录吗？"
        return PendingHistorySave(
            safeCall, question.copy(pcm = null, current = frozenFrame), historySessionId,
            session, sequence, call.inputEpoch,
        ) to HistoryUiState(HistoryStatus.CONFIRM_SAVE, message, pendingJpeg = frozenFrame.jpeg.copyOf())
    }

    /** A homophone may offer a local preview even when the model never calls the tool. */
    private fun scheduleAmbiguousHistoryConfirmationIfReady(session: Long) {
        val original = historyOriginalTranscript ?: return
        val question = historyQuestionSnapshot ?: return
        if (!HistorySaveIntent.needsConfirmation(original) || historyCoordinator == null ||
            historyAutoConfirmJob != null || historyRememberCallSeen || historyCallsThisQuestion != 0 ||
            historySaveStarted || pendingHistorySave != null ||
            historyConfirmationDismissedSequence == historyQuestionSequence ||
            !started || !mediaRunning || session != sessionGeneration.get()) return

        val sequence = historyQuestionSequence
        val epoch = mediaInputEpoch
        val call = ExecutorToolCall(HistoryToolDefinitions.REMEMBER, "local-confirm-$sequence", "{}", "", epoch)
        val preview = preparePendingHistorySave(call, question.copy(userTranscript = original), session, sequence) ?: return
        fun current() = started && mediaRunning && session == sessionGeneration.get() &&
            sequence == historyQuestionSequence && epoch == mediaInputEpoch
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                delay(1_500L)
                synchronized(this@OrchestrationEngine) {
                    if (current() && !historyRememberCallSeen && historyCallsThisQuestion == 0 &&
                        !historySaveStarted && pendingHistorySave == null &&
                        historyConfirmationDismissedSequence != sequence) {
                        pendingHistorySave = preview.first
                        _historyState.value = preview.second
                    }
                }
            } finally {
                synchronized(this@OrchestrationEngine) { if (current()) historyAutoConfirmJob = null }
            }
        }
        historyAutoConfirmJob = job
        job.start()
    }

    /** The user's own matched ASR is enough to save; a model tool call is optional enrichment. */
    private fun scheduleExplicitHistorySaveIfReady(session: Long) {
        val original = historyOriginalTranscript ?: return
        val question = historyQuestionSnapshot ?: return
        val coordinator = historyCoordinator ?: return
        if (!HistorySaveIntent.isExplicit(original) || historySaveStarted || historyRememberCallSeen ||
            !started || !mediaRunning || session != sessionGeneration.get()) return

        val sequence = historyQuestionSequence
        val epoch = mediaInputEpoch
        val clientSession = historySessionId
        val result = historySaveResult
        val frozen = question.copy(userTranscript = original)
        val args = JSONObject().put("object_name", HistorySaveIntent.objectName(original))
            .put("aliases", org.json.JSONArray()).put("location", HistorySaveIntent.location(original)).put("note", "")
        val call = ExecutorToolCall(HistoryToolDefinitions.REMEMBER, "local-save-$sequence", args.toString(), "", epoch)
        fun current() = started && mediaRunning && session == sessionGeneration.get() &&
            sequence == historyQuestionSequence && epoch == mediaInputEpoch

        _historyState.value = HistoryUiState(HistoryStatus.SAVING, "正在确认保存这次物品记录")
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                // Give a valid model tool call a brief chance to supply richer fields.
                // The explicit ASR still saves if the model produces only a spoken reply.
                delay(1_500L)
                val shouldSave = synchronized(this@OrchestrationEngine) {
                    if (!current() || historyRememberCallSeen || historySaveStarted) false
                    else {
                        historySaveStarted = true
                        true
                    }
                }
                if (!shouldSave) return@launch
                val action = try {
                    withTimeout(20_000L) {
                        coordinator.execute(call, frozen, clientSession) { update ->
                            synchronized(this@OrchestrationEngine) { if (current()) _historyState.value = update }
                        } as HistoryToolAction.Reply
                    }
                } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
                    HistoryToolFailures.reply(HistoryFailureKind.TIMEOUT, HistoryToolDefinitions.REMEMBER, historyConfig)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    HistoryToolFailures.reply(HistoryFailureKind.INVALID_RESPONSE, HistoryToolDefinitions.REMEMBER, historyConfig)
                }
                synchronized(this@OrchestrationEngine) {
                    if (current()) {
                        result.complete(action)
                        _historyState.value = action.state
                    }
                }
            } finally {
                synchronized(this@OrchestrationEngine) {
                    if (current()) historyAutoSaveJob = null
                }
            }
        }
        historyAutoSaveJob = job
        job.start()
    }

    private fun beginHistoryTool(call: ExecutorToolCall, session: Long) {
        val executor = executorFactory.current ?: return
        if (!mediaRunning || call.inputEpoch != mediaInputEpoch || !executor.supportsTools) return
        val coordinator = historyCoordinator ?: run {
            executor.completeTool(call, """{"status":"disabled","message":"历史功能未配置，无法查询或保存。不得编造记录。"}""")
            return
        }
        historyCallsThisQuestion++
        if (historyCallsThisQuestion > 3) {
            historyToolJob?.cancel()
            pendingHistorySave = null
            executor.interrupt()
            onBargeIn()
            _historyState.value = HistoryUiState(HistoryStatus.ERROR, "本轮工具调用过多，已停止；请重新提问。")
            return
        }
        if (call.name == HistoryToolDefinitions.REMEMBER) {
            historyRememberCallSeen = true
            historyAutoConfirmJob?.cancel()
            historyAutoConfirmJob = null
            if (!historySaveStarted) {
                historyAutoSaveJob?.cancel()
                historyAutoSaveJob = null
            }
        } else {
            historyAutoConfirmJob?.cancel()
            historyAutoConfirmJob = null
            pendingHistorySave = null
            if (_historyState.value.status == HistoryStatus.CONFIRM_SAVE) _historyState.value = HistoryUiState()
        }
        historyToolJob?.cancel()
        val request = ++historyRequestGeneration
        val question = historyQuestionSnapshot
        val transcript = historyTranscript
        val saveResult = historySaveResult
        val sequence = historyQuestionSequence
        fun current() = started && session == sessionGeneration.get() &&
            request == historyRequestGeneration && sequence == historyQuestionSequence &&
            executorFactory.current === executor && executor.inputEpoch == call.inputEpoch && mediaRunning

        val job = scope.launch(start = CoroutineStart.LAZY) {
            var pendingCandidate: PendingHistorySave? = null
            try {
                val action = withTimeout(20_000L) {
                    val authorizedQuestion = if (question != null && call.name in setOf(HistoryToolDefinitions.REMEMBER, HistoryToolDefinitions.SEARCH)) {
                        // The deferred belongs to the original input item captured above. Never
                        // substitute lastUserText, which may still contain the preceding question.
                        val original = withTimeoutOrNull(8_000L) { transcript.await() }.orEmpty()
                        question.copy(userTranscript = original)
                    } else question
                    if (call.name == HistoryToolDefinitions.REMEMBER) {
                        val original = authorizedQuestion?.userTranscript.orEmpty()
                        val requested = HistorySaveIntent.isExplicit(original)
                        val safeCall = call.copy(argumentsJson = HistorySaveIntent.safeArguments(call.argumentsJson, original))
                        if (!requested && HistorySaveIntent.needsConfirmation(original) && authorizedQuestion?.current != null) {
                            val prior = synchronized(this@OrchestrationEngine) { if (historySaveStarted) saveResult else null }
                            if (prior != null) prior.await()
                            else {
                                val preview = preparePendingHistorySave(safeCall, authorizedQuestion, session, sequence)
                                    ?: error("本轮画面不可用")
                                pendingCandidate = preview.first
                                HistoryToolAction.Reply(
                                    JSONObject().put("status", "confirmation_required")
                                        .put("message", "用户需要在页面确认这次保存；目前尚未存入历史服务。")
                                        .put("instruction", "不得声称照片已保存，也不要再次调用保存工具。请让用户查看当前照片并点击确认或取消。")
                                        .toString(),
                                    preview.second,
                                )
                            }
                        } else {
                            val prior = if (requested) synchronized(this@OrchestrationEngine) {
                                if (historySaveStarted) saveResult else {
                                    historySaveStarted = true
                                    null
                                }
                            } else null
                            if (requested && prior != null) prior.await()
                            else coordinator.execute(safeCall, authorizedQuestion, historySessionId) { update ->
                                synchronized(this@OrchestrationEngine) { if (current()) _historyState.value = update }
                            }
                        }
                    } else coordinator.execute(call, authorizedQuestion, historySessionId) { update ->
                        synchronized(this@OrchestrationEngine) { if (current()) _historyState.value = update }
                    }
                }
                ensureActive()
                when (action) {
                    is HistoryToolAction.Reply -> {
                        val awaitUserConfirmedSave = synchronized(this@OrchestrationEngine) {
                            if (!current()) false
                            else if (action.state.status == HistoryStatus.CONFIRM_SAVE && historySaveStarted) true
                            else {
                                val resolved = if (action.state.status == HistoryStatus.CONFIRM_SAVE &&
                                    historyConfirmationDismissedSequence == sequence) {
                                    HistoryToolAction.Reply(
                                        JSONObject().put("status", "user_declined")
                                            .put("message", "用户取消了保存，没有写入历史记录。")
                                            .put("instruction", "不要再调用保存工具；可继续普通对话。")
                                            .toString(),
                                        HistoryUiState(),
                                    )
                                } else action
                                if (call.name == HistoryToolDefinitions.REMEMBER) {
                                    if (resolved.state.status == HistoryStatus.CONFIRM_SAVE && pendingCandidate != null) {
                                        if (pendingHistorySave == null) pendingHistorySave = pendingCandidate
                                    } else if (historySaveStarted) {
                                        saveResult.complete(resolved)
                                    } else {
                                        historyRememberCallSeen = false
                                        scheduleExplicitHistorySaveIfReady(session)
                                    }
                                }
                                if (resolved.state.status != HistoryStatus.CONFIRM_SAVE || pendingHistorySave === pendingCandidate) {
                                    _historyState.value = resolved.state
                                }
                                if (!executor.completeTool(call, resolved.outputJson)) {
                                    pendingHistorySave = null
                                    _historyState.value = HistoryUiState(HistoryStatus.ERROR, "本轮会话已变化，请重新提问。")
                                }
                                false
                            }
                        }
                        if (awaitUserConfirmedSave) {
                            val confirmed = saveResult.await()
                            synchronized(this@OrchestrationEngine) {
                                if (current()) {
                                    _historyState.value = confirmed.state
                                    if (!executor.completeTool(call, confirmed.outputJson)) {
                                        _historyState.value = HistoryUiState(HistoryStatus.ERROR, "本轮会话已变化，请重新提问。")
                                    }
                                }
                            }
                        }
                    }
                    is HistoryToolAction.Compare -> {
                        val allowed = synchronized(this@OrchestrationEngine) {
                            if (!current()) false else {
                                historyInputPaused = true
                                historyQuestionCapture?.clear()
                                synchronized(visionGate) { visionPipeline?.setUplinkPaused(true) }
                                _historyState.value = HistoryUiState(
                                    HistoryStatus.COMPARING,
                                    listOfNotNull(action.coverageWarning, "当前模型正在查看证据；本轮结束后恢复收音，可点击麦克风停止。").joinToString(" "), action.records,
                                )
                                true
                            }
                        }
                        if (!allowed) return@launch
                        val complete = executor.respondToToolWithMedia(call, action.outputJson, action.questionPcm, action.jpeg)
                        synchronized(this@OrchestrationEngine) {
                            if (current()) _historyState.value = HistoryUiState(
                                if (complete) HistoryStatus.RESULT else HistoryStatus.ERROR,
                                if (complete) action.coverageWarning ?: "已根据这些照片回答；可点击查看证据。" else "本轮模型回答未完整完成，请查看提示后重新提问。",
                                action.records,
                            )
                        }
                    }
                }
            } catch (failure: kotlinx.coroutines.TimeoutCancellationException) {
                synchronized(this@OrchestrationEngine) {
                    if (current()) {
                        val failure = HistoryToolFailures.reply(HistoryFailureKind.TIMEOUT, call.name, historyConfig)
                        if (call.name == HistoryToolDefinitions.REMEMBER) {
                            saveResult.complete(failure)
                            if (!historySaveStarted) historyRememberCallSeen = false
                        }
                        _historyState.value = failure.state
                        executor.completeTool(call, failure.outputJson)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                synchronized(this@OrchestrationEngine) {
                    if (current()) {
                        _historyState.value = _historyState.value.copy(status = HistoryStatus.ERROR, message = "历史交互未完成，请重新提问。")
                        executor.interrupt()
                        onBargeIn()
                    }
                }
            } finally {
                synchronized(this@OrchestrationEngine) {
                    // Old cancelled work must never reopen a new session's microphone/video gate.
                    if (current()) {
                        if (historyInputPaused) {
                            ring.clear()
                            historyQuestionCapture?.clear()
                            historyFrameCache?.clear()
                            synchronized(visionGate) {
                                visionPipeline?.resetConnection()
                                visionPipeline?.setUplinkPaused(false)
                            }
                            historyInputPaused = false
                        }
                        historyToolJob = null
                        lastUserActivityAt = nowMs()
                    }
                }
            }
        }
        historyToolJob = job
        job.start()
    }

    // ---------------- 音频闭环 ----------------

    private fun startAudioPump() {
        pumpJob?.cancel()
        val session = sessionGeneration.get()
        val mediaRun = mediaGeneration
        val inputEpoch = mediaInputEpoch
        pumpJob = scope.launch(Dispatchers.IO) {
            val buf = ByteBuffer.allocateDirect(config.captureAudio.bytesFor(config.pumpFrameMs).toInt())
            var lastDiagnosticAt = 0L
            while (isActive) {
                var sentFrame = false
                try {
                    // Lifecycle, ring reads, and enqueue are one operation. A cancelled
                    // old pump cannot consume a resumed microphone's new SPSC ring.
                    synchronized(this@OrchestrationEngine) {
                        ensureActive()
                        if (!started || session != sessionGeneration.get() || mediaRun != mediaGeneration) return@launch
                        val ex = executorFactory.current
                        if (ex != null && ex.ready.value && mediaRunning) {
                            if (ex.inputEpoch != inputEpoch) {
                                pauseForRecovery(session, updateStatus = false)
                                resumeMedia(session) // configuration already confirmed; no old PCM is replayed
                                return@launch
                            }
                            val now = nowMs()
                            if (now - lastDiagnosticAt >= 5_000L) {
                                Log.i("EdgeVoiceSession", "media: captured=${captureBytes()}, sent=${uplinkBytes.get()}, received=${downlinkBytes.get()}, played=${playbackBytes()}, frames=${visionPipeline?.stats()?.sentFrames ?: 0}, route=${_audioRouteLabel.value}, aec=${capture.audioProcessing.echoCancellation}, ns=${capture.audioProcessing.noiseSuppression}")
                                lastDiagnosticAt = now
                            }
                            val available = ring.availableToRead()
                            if (available > config.captureAudio.bytesFor(config.maxCaptureBacklogMs)) {
                                discardedInputBytes.addAndGet(available.toLong())
                                failSession(IllegalStateException("音频上行积压超出时长上限"), session, SessionErrorKind.OVERLOAD)
                                return@launch
                            }
                            if (available >= buf.capacity()) {
                                buf.clear()
                                val n = ring.read(buf)
                                if (n > 0) {
                                    buf.limit(n) // native read uses the base address and leaves position at zero
                                    if (historyInputPaused) {
                                        discardedInputBytes.addAndGet(n.toLong())
                                    } else {
                                        val before = ex.inputVadAudioBytesSent
                                        ex.sendAudioFrame(buf, inputEpoch)
                                        uplinkBytes.addAndGet(n.toLong())
                                        if (historyQuestionCapture != null && ex.inputVadAudioBytesSent - before == n.toLong()) {
                                            val copy = ByteArray(n)
                                            buf.duplicate().get(copy)
                                            historyQuestionCapture.append(copy, before)
                                        }
                                    }
                                    if (!historyInputPaused && echoRing != null) {
                                        val copy = ByteArray(n)
                                        buf.duplicate().get(copy)
                                        echoRing?.append(copy)
                                    }
                                    if (!historyInputPaused) synchronized(visionGate) { visionPipeline?.onAudioFrameSent() }
                                    sentFrame = true
                                }
                            }
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Throwable) {
                    ensureActive()
                    synchronized(this@OrchestrationEngine) {
                        if (!started || session != sessionGeneration.get() || mediaRun != mediaGeneration) return@launch
                        if (failure is RealtimeSendException && failure.recoverable) {
                            pauseForRecovery(session)
                            val ex = executorFactory.current
                            if (ex?.ready?.value == true && ex.inputEpoch != inputEpoch) resumeMedia(session)
                        } else failSession(failure, session)
                    }
                    return@launch
                }
                if (!sentFrame) delay(5)
            }
        }
    }

    /** 下行音频入队：分块写入 JitterBuffer（抗抖动）。 */
    private fun deliverAudio(pcm: ByteArray) {
        if (pcm.isEmpty()) return
        downlinkBytes.addAndGet(pcm.size.toLong())
        _outputLevel.value = PcmUtils.rms(pcm)

        var offset = 0
        val cap = downlinkBuf.capacity()
        while (offset < pcm.size) {
            val len = minOf(cap, pcm.size - offset)
            downlinkBuf.clear()
            downlinkBuf.put(pcm, offset, len)
            downlinkBuf.position(0)
            downlinkBuf.limit(len)
            jitter.push(downlinkBuf)
            offset += len
        }
    }

    // ---------------- 视觉帧接入 ----------------

    /**
     * 相机链路回调（CameraX analyze 线程）：
     *  1. 始终登记视觉帧时间戳/运动分，供多模态对齐与文本锚点（contextStore.fusion）；
     *  2. 若视觉管道开启，帧进入「1fps 闸门 → 异步编码 → 音频时间轴上行」流程，
     *     本调用不做任何重活并立即返回。
     */
    fun onVisionFrame(frame: VideoFrame) {
        if (!started || !visionEnabled) return
        if (!historyInputPaused && mediaRunning) historyFrameCache?.accepted(frame.timestampNs, nowMs(), System.currentTimeMillis())
        contextStore.recordVisionFrame(
            timestampMs = frame.timestampNs / 1_000_000L,
            frameRef = null,
            motionScore = frame.motionScore,
        )
        visionPipeline?.onFrame(frame)
    }

    /** 视觉上行计数快照（视觉关闭时返回 null，UI 据此隐藏卡片）。 */
    fun visionStats() = visionPipeline?.stats()

    /** UI 关闭相机后同步撤下待发管道；预览可独立于语音会话。 */
    fun setVisionEnabled(enabled: Boolean) {
        synchronized(visionGate) {
            visionEnabled = enabled
            if (!enabled) {
                historyFrameCache?.clear()
                visionPipeline?.stop()
                visionPipeline = null
            } else if (started && visionPipeline == null) {
                startVisionPipeline()
            }
        }
    }

    @Synchronized
    private fun failSession(
        failure: Throwable,
        expectedSession: Long = sessionGeneration.get(),
        preferredKind: SessionErrorKind? = null,
        userMessage: String? = null,
    ) {
        if (!started || expectedSession != sessionGeneration.get()) return
        val kind = preferredKind ?: sessionFailureKind(failure)
        val message = userMessage ?: sessionFailureMessage(kind, failure)
        stop()
        _sessionStatus.value = SessionStatus(SessionPhase.FAILED, message = message, errorKind = kind)
        _errorMessage.value = message
        Log.w("EdgeVoiceSession", "session failed: kind=$kind, cause=${failure.javaClass.simpleName}")
    }

    /**
     * 创建视觉上行管道。能力查询与发送均为延迟 lambda：
     * 双模热切换后无需重建管道，每帧自然跟随当前 executor 的能力矩阵。
     */
    private fun startVisionPipeline() {
        if (!visionEnabled || visionPipeline != null) return
        val encoder = frameEncoder ?: return
        if (!config.vision.enabled) return
        startFrameUploader()
        // 本地帧环与云端上传任一存在，编码产物就需要旁路；两者皆无才不提供 sink。
        val sinkEnabled = frameRing != null || frameUploader != null || historyEnabled
        val pipeline = VisionUplinkPipeline(
            config = config.vision,
            encoder = encoder,
            scope = scope,
            supportsVideo = { mediaRunning && executorFactory.current?.ready?.value == true && executorFactory.current?.supportsVideoInput == true },
            sender = { jpeg ->
                val executor = checkNotNull(executorFactory.current) { "模型会话尚未连接" }
                executor.sendVideoFrame(jpeg, mediaInputEpoch)
            },
            // V2/V3 本地旁路：编码产物同时喂磁盘帧环与上传缓冲，与上行能力解耦
            // （OpenAI 画像即使不上云，本地回忆与云端留存仍需落盘）。
            frameSink = if (sinkEnabled) {
                { source: VideoFrame, encoded ->
                    if (!historyInputPaused && mediaRunning) historyFrameCache?.encoded(source.timestampNs, encoded.jpeg, nowMs())
                    frameRing?.append(encoded.jpeg)
                    frameUploader?.onFrame(encoded.jpeg)
                }
            } else {
                null
            },
        )
        visionPipeline = pipeline
        pipeline.start()
        startReplayPlayer()
    }

    /**
     * V3：创建会话帧上传器（与视觉管道同生命周期，且仅在客户端与配置同时就绪时）。
     * 上传在独立协程周期进行，与音频/视觉上行完全解耦。
     */
    private fun startFrameUploader() {
        if (historyEnabled) return
        val client = sessionFrameClient ?: return
        if (!config.sessionFrame.active) return
        val uploader = SessionFrameUploader(
            config = config.sessionFrame,
            client = client,
            scope = scope,
        )
        frameUploader = uploader
        uploader.start()
    }

    /**
     * V2：创建回放编排器（与帧环、回声环同在）。
     * 打断回调必须完整复用 barge-in 语义（状态抢占+清播放），再显式取消服务端当前回复；
     * 随后回声 PCM 上行会自然触发服务端 VAD 的 speech_started，状态继续由事件流驱动。
     */
    private fun startReplayPlayer() {
        if (historyEnabled) return
        val ring = frameRing ?: return
        val echo = echoRing ?: return
        replayPlayer = VisionReplayPlayer(
            config = config.replay,
            frameRing = ring,
            echoRing = echo,
            audioSender = { bb -> executorFactory.current?.sendAudioFrame(bb) },
            videoSender = { jpeg -> executorFactory.current?.sendVideoFrame(jpeg) },
            interrupt = {
                playback.interrupt()
                _outputLevel.value = 0f
                stateMachine.transitionTo(AgentState.INTERRUPTED)
                stateMachine.transitionTo(AgentState.LISTENING)
                executorFactory.current?.interrupt()
            },
            supportsVideo = { executorFactory.current?.supportsVideoInput == true },
            onReplayWindow = { active -> visionPipeline?.setUplinkPaused(active) },
            // V3：长窗回忆（昨天/前天…）超出本地帧环时回退服务端会话帧留存
            remoteFrames = frameUploader?.let { up ->
                { from, to, max -> up.recall(from, to, max).map { it.jpeg } }
            },
        )
    }

    /** 供传输层回传 RTT 时调用。 */
    fun onRttMeasured(rttMs: Long) {
        _rttMs.value = rttMs
    }

    private fun nowMs(): Long = SystemClock.elapsedRealtime()
}
