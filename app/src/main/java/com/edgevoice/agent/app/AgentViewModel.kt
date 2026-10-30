package com.edgevoice.agent.app

import android.app.Application
import androidx.camera.core.Preview
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.edgevoice.agent.app.camera.AgentCameraController
import com.edgevoice.agent.core.MediaTelemetry
import com.edgevoice.agent.executor.ExecutorMode
import com.edgevoice.agent.ui.model.AgentMode
import com.edgevoice.agent.ui.model.AgentUiState
import com.edgevoice.agent.ui.model.DebugTelemetry
import com.edgevoice.agent.ui.model.ConnectionPhase
import com.edgevoice.agent.ui.model.PermissionTarget
import com.edgevoice.agent.ui.model.RecoveryHint
import com.edgevoice.agent.ui.model.SessionUiState
import com.edgevoice.agent.ui.model.permissionHint
import com.edgevoice.agent.ui.model.recoveryHint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 唯一的界面状态持有者（MVI）：
 *  - 订阅 OrchestrationEngine 的只读 StateFlow，与 1s 治理快照 tick 汇聚成 [AgentUiState]；
 *  - 用户意图（[com.edgevoice.agent.ui.model.AgentUiActions]）只调本类方法，单向数据流；
 *  - 错误同时进入字幕区和 [messages]，下一次启动时清除，便于用户理解失败原因。
 */
class AgentViewModel(app: Application, private val savedStateHandle: SavedStateHandle) : AndroidViewModel(app) {

    private val container = AgentContainer(app)
    private val engine = container.engine
    private val savedConversation = SavedConversation(savedStateHandle)
    private val camera = AgentCameraController(app, onFrame = engine::onVisionFrame)

    // ---- UI 交互态（VM 自己持有，引擎不感知 UI 概念）----
    private val _mode = MutableStateFlow(AgentMode.CLOUD)
    val mode = _mode.asStateFlow()

    private val _starting = MutableStateFlow(false)
    val running = combine(engine.sessionStatus, _starting) { status, starting ->
        starting || ConnectionPhase.valueOf(status.phase.name).active
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private val _visionEnabled = MutableStateFlow(false)
    val visionEnabled = _visionEnabled.asStateFlow()

    private val _renderFps = MutableStateFlow(60)

    /** RTT 折线历史（引擎只给最新值，滚动窗口在装配层维护）。 */
    private val rttHistory = MutableStateFlow<List<Long>>(emptyList())

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages = _messages.asSharedFlow()
    private val _localError = MutableStateFlow<RecoveryHint?>(null)
    private var sessionStartJob: Job? = null
    private var startupGeneration = 0L

    init {
        engine.restoreConversation(savedConversation.restore())
        // 独立于 UI 的 WhileSubscribed：后台停止/重建时也保留最近五轮字幕。
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            engine.conversation.collect { savedConversation.save(it) }
        }
        engine.setVisionEnabled(false)
        // 只收真实测量值（>0）；切换模式/停止时由动作方显式清空
        viewModelScope.launch {
            engine.rttMs.collect { rtt ->
                if (rtt > 0L) {
                    rttHistory.update { (it + rtt).takeLast(RTT_HISTORY_MAX) }
                }
            }
        }
        viewModelScope.launch {
            engine.errorMessage.collect { error ->
                if (error != null) {
                    // Retry/terminal ownership belongs to the engine. Stopping here would cancel recovery.
                    val hint = recoveryHint(engine.sessionStatus.value.errorKind?.name, error)
                    hint?.let { message(it.title) }
                }
            }
        }
        viewModelScope.launch {
            engine.sessionStatus.collect { status ->
                if (status.phase.name == "FAILED") unbindCamera()
            }
        }
    }

    /** 1s tick：治理组件是普通 @Synchronized 读方法而非 Flow，用 tick 拉取快照。 */
    private val telemetryTick = flow {
        while (true) {
            emit(Unit)
            kotlinx.coroutines.delay(TELEMETRY_INTERVAL_MS)
        }
    }

    private val telemetry = combine(engine.rttMs, telemetryTick, _renderFps) { rtt, _, fps ->
        buildTelemetry(rtt, fps)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS),
        initialValue = buildTelemetry(0L, 60),
    )

    private val sessionUi = combine(
        engine.sessionStatus, engine.audioRouteLabel, engine.errorMessage, _localError, _starting,
    ) { status, route, error, local, starting ->
        val phase = ConnectionPhase.valueOf(status.phase.name)
        SessionUiState(
            phase = if (starting && phase == ConnectionPhase.STOPPED) ConnectionPhase.CONNECTING else phase,
            retryAttempt = status.retryAttempt,
            message = status.message,
            audioRouteLabel = route,
            recovery = local ?: recoveryHint(status.errorKind?.name, error),
        )
    }

    val uiState = combine(
        combine(engine.state, engine.inputLevel, engine.outputLevel) { s, input, output ->
            Triple(s, input, output)
        },
        _mode,
        combine(_visionEnabled, camera.fps) { enabled, fps -> enabled to fps },
        telemetry,
        combine(
            combine(engine.conversation, running, sessionUi) { conversation, running, session ->
                Triple(conversation, running, session)
            },
            engine.historyState,
        ) { presentation, history ->
            presentation to history
        },
    ) { levels, mode, vision, tel, presentation ->
        val sessionPresentation = presentation.first
        AgentUiState(
            state = if (sessionPresentation.third.phase == ConnectionPhase.READY) levels.first else com.edgevoice.agent.core.state.AgentState.IDLE_OBSERVING,
            mode = mode,
            inputLevel = levels.second,
            outputLevel = levels.third,
            visionEnabled = vision.first,
            visionFps = vision.second,
            telemetry = tel,
            sessionRunning = sessionPresentation.second,
            conversationTurns = sessionPresentation.first.visibleTurns,
            userText = sessionPresentation.first.userText,
            assistantText = sessionPresentation.first.assistantText,
            assistantInterrupted = sessionPresentation.first.assistantInterrupted,
            errorMessage = sessionPresentation.third.recovery?.let { "${it.title}：${it.guidance}" },
            session = sessionPresentation.third,
            history = presentation.second,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS),
        initialValue = AgentUiState(
            conversationTurns = engine.conversation.value.visibleTurns,
            userText = engine.conversation.value.userText,
            assistantText = engine.conversation.value.assistantText,
            assistantInterrupted = engine.conversation.value.assistantInterrupted,
            history = engine.historyState.value,
        ),
    )

    private fun buildTelemetry(rtt: Long, fps: Int): DebugTelemetry {
        val media = if (running.value) {
            engine.mediaTelemetry()
        } else {
            MediaTelemetry(uplinkRingPct = 0f, downlinkBufferedMs = 0, jitterLossRate = 0f)
        }
        val g = container.governance
        return DebugTelemetry(
            rttMs = rtt,
            rttHistory = rttHistory.value,
            ringBufferPct = media.uplinkRingPct,
            // UI 契约单位为百分数值（0.2 表示 0.2%），引擎给的是 0..1 比率
            jitterLossPct = media.jitterLossRate * 100f,
            tokenUsed = g.tokenBudget.usedTokens(),
            tokenLimit = AgentContainer.TOKEN_LIMIT,
            iterationUsed = g.iterationGuard.currentDepth(),
            iterationLimit = AgentContainer.ITERATION_LIMIT,
            circuitBreaker = g.circuitBreaker.currentState().name,
            securityHits = g.sanitizer.hitCount(),
            renderFps = fps,
            visionActive = media.visionActive,
            visionSentFrames = media.visionSentFrames,
            visionDroppedFrames = media.visionDroppedFrames,
            visionRejectedFrames = media.visionRejectedFrames,
            visionLastFrameKb = media.visionLastFrameBase64Kb,
            visionLastFrameAgeMs = media.visionLastFrameAgeMs,
            visionRecentFps = media.visionRecentFps,
            recallEnabled = media.recallEnabled,
            frameRingCount = media.frameRingCount,
            frameRingMb = media.frameRingMb,
            frameRingOldestAgeSec = media.frameRingOldestAgeSec,
            replayCount = media.replayCount,
            replayLastSkip = media.replayLastSkip,
            sessionFrameEnabled = media.sessionFrameEnabled,
            sessionUploadFrames = media.sessionUploadFrames,
            sessionUploadBatches = media.sessionUploadBatches,
            sessionUploadFailures = media.sessionUploadFailures,
            sessionUploadPendingFrames = media.sessionUploadPendingFrames,
        )
    }

    // ---------------- 用户意图（由 Activity 在权限流程后调用） ----------------

    /** 仅在用户看过本轮冻结照片并明确点按后，才允许保存模糊语音命令。 */
    fun confirmHistorySave() = engine.confirmPendingHistorySave()

    fun discardHistorySave() = engine.discardPendingHistorySave()

    /** 麦克风按钮：运行中直接停；未运行的启动由 Activity 先过权限再调 [startAfterMicGranted]。 */
    fun stopSession() {
        savedConversation.save(engine.conversation.value)
        startupGeneration++
        _starting.value = false
        sessionStartJob?.cancel()
        sessionStartJob = null
        unbindCamera()
        engine.stop()
        // stop 与事件处理共享引擎生命周期锁；同步读取，避免等主线程 collector 才保存最后增量。
        savedConversation.save(engine.conversation.value)
        rttHistory.value = emptyList()
    }

    /** RECORD_AUDIO 已授权后启动会话。 */
    fun startAfterMicGranted() {
        if (_starting.value || running.value) return
        if (!container.cloudConfigured) {
            _localError.value = recoveryHint("AUTHENTICATION", "credentials missing")
            message("尚未配置云端模型，请检查模型账号与访问配置")
            return
        }
        _localError.value = null
        val request = ++startupGeneration
        // 连接期间也属于活动会话，防止重复点击启动并保证退后台能取消连接。
        _starting.value = true
        sessionStartJob = viewModelScope.launch {
            try {
                engine.start(_mode.value.toExecutor())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                if (request == startupGeneration && engine.errorMessage.value == null &&
                    engine.sessionStatus.value.phase.name != "RECONNECTING"
                ) {
                    _localError.value = recoveryHint("UNKNOWN", "start failed")
                    message("连接未能完成，请重新连接")
                }
            } finally {
                if (request == startupGeneration) _starting.value = false
            }
        }
    }

    /** 保留双模入口的外观；首版仅开放已具备完整音视频链路的云端模式。 */
    fun changeMode(target: AgentMode) {
        if (target == AgentMode.LOCAL) {
            message("本地语音模式尚未开放，请使用 Cloud 模式")
            return
        }
        _mode.value = AgentMode.CLOUD
    }

    // ---------------- 视觉 ----------------

    /**
     * 视觉开启的正确时序（解决 surface 与权限的先有鸡先有蛋）：
     *  1. Activity 确认 CAMERA 已授权后调 [enableCamera]：同步视觉意图与引擎开关；
     *  2. VisionCard 进入组合，PreviewView factory 回调 [onPreviewSurfaceReady]；
     *  3. 此时才真正 bind（Preview.setSurfaceProvider 必须在 bind 前完成）。
     */
    fun enableCamera() {
        val local = _localError.value
        if (local?.permissionTarget == PermissionTarget.CAMERA || local?.title == "相机暂时不可用") _localError.value = null
        engine.setVisionEnabled(true)
        _visionEnabled.value = true
    }

    /** PreviewView 创建完成：挂上 SurfaceProvider，并在视觉开启时完成相机绑定。 */
    fun onPreviewSurfaceReady(owner: LifecycleOwner, surfaceProvider: Preview.SurfaceProvider) {
        camera.attachSurfaceProvider(surfaceProvider)
        if (_visionEnabled.value) bindCamera(owner)
    }

    private var cameraBindJob: Job? = null

    private fun bindCamera(owner: LifecycleOwner) {
        cameraBindJob?.cancel()
        cameraBindJob = viewModelScope.launch {
            try {
                camera.bind(owner)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                camera.unbind()
                engine.setVisionEnabled(false)
                _visionEnabled.value = false
                _localError.value = if (t is SecurityException) permissionHint(PermissionTarget.CAMERA, true) else
                    RecoveryHint("相机暂时不可用", "请关闭其他占用相机的应用，再点击摄像头重新开启。", com.edgevoice.agent.ui.model.RecoveryAction.NONE)
                message("相机启动失败，请检查权限或相机占用情况")
            }
        }
    }

    fun unbindCamera() {
        engine.setVisionEnabled(false)
        cameraBindJob?.cancel()
        cameraBindJob = null
        camera.unbind()
        _visionEnabled.value = false
    }

    fun reportPermissionDenied(target: PermissionTarget, permanentlyDenied: Boolean) {
        if (target == PermissionTarget.MICROPHONE) stopSession() else unbindCamera()
        _localError.value = permissionHint(target, permanentlyDenied)
    }

    /** Returning from Settings never starts media; only active media or stale permission hints change. */
    fun checkPermissions(microphoneGranted: Boolean, cameraGranted: Boolean) {
        if (running.value && !microphoneGranted) {
            reportPermissionDenied(PermissionTarget.MICROPHONE, true)
        } else if (_visionEnabled.value && !cameraGranted) {
            reportPermissionDenied(PermissionTarget.CAMERA, true)
        }
        val target = _localError.value?.permissionTarget
        if ((target == PermissionTarget.MICROPHONE && microphoneGranted) ||
            (target == PermissionTarget.CAMERA && cameraGranted)
        ) {
            _localError.value = null
            message("权限已允许，请手动开启所需功能")
        }
    }

    // ---------------- 渲染 FPS 观测 ----------------

    fun updateRenderFps(fps: Int) {
        _renderFps.value = fps
    }

    private fun message(text: String) {
        _messages.tryEmit(text)
    }

    override fun onCleared() {
        stopSession()
        container.close()
    }

    private fun AgentMode.toExecutor(): ExecutorMode = when (this) {
        AgentMode.CLOUD -> ExecutorMode.CLOUD_REALTIME
        AgentMode.LOCAL -> ExecutorMode.ON_DEVICE_LOCAL
    }

    companion object {
        private const val RTT_HISTORY_MAX = 48
        private const val TELEMETRY_INTERVAL_MS = 1000L
        private const val SUBSCRIPTION_TIMEOUT_MS = 500L
    }
}
