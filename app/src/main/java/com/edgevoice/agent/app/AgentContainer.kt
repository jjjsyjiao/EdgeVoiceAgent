package com.edgevoice.agent.app

import android.content.Context
import com.edgevoice.agent.core.AgentConfig
import com.edgevoice.agent.core.OrchestrationEngine
import com.edgevoice.agent.core.governance.Governance
import com.edgevoice.agent.core.governance.MaxIterationGuard
import com.edgevoice.agent.core.governance.TokenBudget
import com.edgevoice.agent.core.history.HistoryMemoryConfig
import com.edgevoice.agent.core.history.HttpHistoryMemoryClient
import com.edgevoice.agent.core.history.HistoryToolDefinitions
import com.edgevoice.agent.core.memory.ContextStore
import com.edgevoice.agent.core.memory.HttpLongTermMemory
import com.edgevoice.agent.core.memory.NoOpLongTermMemory
import com.edgevoice.agent.core.vision.SessionFrameConfig
import com.edgevoice.agent.core.vision.ReplayConfig
import com.edgevoice.agent.core.vision.VisionUplinkConfig
import com.edgevoice.agent.executor.AgentExecutorFactory
import com.edgevoice.agent.media.video.AndroidFrameJpegCodec
import com.edgevoice.agent.media.video.JpegFrameEncoder
import com.edgevoice.agent.media.audio.AndroidAudioSessionController
import com.edgevoice.agent.transport.realtime.RealtimeProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.time.ZoneId

/**
 * 应用级装配容器（手写的轻量 DI，对标 Java 项目里的 ServiceLocator）。
 *
 * 生命周期：由 [AgentViewModel] 持有，ViewModel 清场时同步 stop 引擎；
 * 不使用 DI 框架——整个 SDK 的对象图很小，显式装配更便于逐行审查。
 *
 * 密钥/地址全部来自 BuildConfig（构建期由 local.properties 注入，不入库）。
 */
class AgentContainer(context: Context) {

    private val appContext: Context = context.applicationContext

    /**
     * 治理四件套由容器持有并注入引擎 —— 引擎内部计数、外部（ViewModel）读取，
     * 必须是同一个实例，否则看板数字永远是默认值。
     * 上限与 UI 看板契约对齐：Token 32000、迭代深度 20。
     */
    val governance: Governance = Governance(
        tokenBudget = TokenBudget(maxTokens = TOKEN_LIMIT),
        iterationGuard = MaxIterationGuard(maxIterations = ITERATION_LIMIT),
    )

    private val longTermMemory =
        if (BuildConfig.MEMORY_ENDPOINT.isNotBlank()) HttpLongTermMemory(BuildConfig.MEMORY_ENDPOINT)
        else NoOpLongTermMemory

    private val contextStore = ContextStore(longTerm = longTermMemory)

    /** 引擎级 scope：由容器持有，close() 时统一取消（对标 Java 里 ExecutorService.shutdown）。 */
    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** 新历史链路仅由语音工具主动保存；不会随相机开启而批量上传。 */
    private val historyConfig = HistoryMemoryConfig(
        enabled = BuildConfig.HISTORY_ENABLED,
        baseUrl = BuildConfig.HISTORY_ENDPOINT,
        apiToken = BuildConfig.HISTORY_API_TOKEN,
        timeZoneId = BuildConfig.HISTORY_TIME_ZONE.ifBlank { ZoneId.systemDefault().id },
        debugConnectionHints = BuildConfig.DEBUG,
    )
    private val historyClient = if (historyConfig.active) {
        HttpHistoryMemoryClient(historyConfig.baseUrl, historyConfig.apiToken)
    } else null

    private val executorFactory = AgentExecutorFactory(
        context = appContext,
        cloudConfig = AgentExecutorFactory.CloudConfig(
            wsUrl = BuildConfig.OPENAI_REALTIME_URL,
            apiKey = BuildConfig.OPENAI_API_KEY,
            model = BuildConfig.OPENAI_REALTIME_MODEL,
            profile = RealtimeProfile.fromId(BuildConfig.REALTIME_PROVIDER),
            toolsJson = if (historyConfig.active) HistoryToolDefinitions.toolsJson else null,
            instructions = if (historyConfig.active) HistoryToolDefinitions.instructions else null,
        ),
        localConfig = AgentExecutorFactory.LocalConfig(
            modelPath = BuildConfig.MEDIAPIPE_MODEL_PATH,
        ),
    )

    /**
     * 视觉上行装配（V1）：
     *  - 总开关 vision.uplink 关闭 → 编码器不构造、管道不启动（零开销，帧仅登记时间戳）；
     *  - 编码参数固定 720p/q80 起步 + 质量/分辨率阶梯（V0 探针实测 58~78KB，远低于 256KB 红线）；
     *  - 是否真正发送还过执行器能力位（qwen=true/openai=false），开关与能力矩阵双门控。
     */
    private val visionUplinkConfig =
        if (BuildConfig.VISION_UPLINK_ENABLED) VisionUplinkConfig() else VisionUplinkConfig.DISABLED
    private val frameEncoder =
        if (BuildConfig.VISION_UPLINK_ENABLED) JpegFrameEncoder(AndroidFrameJpegCodec) else null

    val engine: OrchestrationEngine = OrchestrationEngine(
        config = AgentConfig(
            memoryEndpoint = BuildConfig.MEMORY_ENDPOINT,
            vision = visionUplinkConfig,
            replay = ReplayConfig.DISABLED,
            sessionFrame = SessionFrameConfig.DISABLED,
        ),
        executorFactory = executorFactory,
        contextStore = contextStore,
        frameEncoder = frameEncoder,
        historyClient = historyClient,
        historyConfig = historyConfig,
        governance = governance,
        audioSession = AndroidAudioSessionController(appContext),
        scope = engineScope,
    )

    /** ViewModel 清场时取消引擎全部协程（事件消费/上行泵），防止 Default scope 泄漏。 */
    fun close() {
        engine.close()
        engineScope.cancel()
    }

    /** 云端模式是否可用（未配置 Key 时 UI 应阻止启动并提示）。 */
    val cloudConfigured: Boolean get() = BuildConfig.OPENAI_API_KEY.isNotBlank()

    /** 本地模式是否可用（模型文件路径未配置/文件不存在时切换必然失败）。 */
    val localModelConfigured: Boolean get() = BuildConfig.MEDIAPIPE_MODEL_PATH.isNotBlank() &&
        java.io.File(BuildConfig.MEDIAPIPE_MODEL_PATH).exists()

    companion object {
        const val TOKEN_LIMIT = 32_000
        const val ITERATION_LIMIT = 20
    }
}
