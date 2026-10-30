package com.edgevoice.agent.executor

import android.content.Context
import com.edgevoice.agent.executor.cloud.CloudRealtimeExecutor
import com.edgevoice.agent.executor.local.OnDeviceLocalExecutor
import com.edgevoice.agent.transport.realtime.RealtimeProfile
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 执行器工厂 + 双模热切换器。
 *
 * 热切换协议（架构文档 §4.1「业务层一键切换，底层对上层透明」）：
 *   1. 从旧 executor 取上下文摘要（由上层提供 summarize 函数，因摘要属于记忆层职责）；
 *   2. shutdown 旧 executor（断连 / 释放 NPU）；
 *   3. 创建并公布新 executor，通过 beforePrepare 安装事件订阅，再注入摘要 prepare；
 *   4. 配置确认后返回；失败释放新实例并清除 [current]。
 *
 * 并发安全：切换全程由 [switchMutex] 串行化（对标 Java 里的 synchronized 切换块），
 * 切换期间到达的 sendAudioFrame/sendUserText 会拿到旧引用但旧实例已 shutdown，自然丢弃，
 * 不会造成双写。
 */
class AgentExecutorFactory(
    private val context: Context,
    private val cloudConfig: CloudConfig,
    private val localConfig: LocalConfig,
) {
    data class CloudConfig(
        val wsUrl: String,
        val apiKey: String,
        val model: String,
        /** 显式音色；null 时使用供应商画像默认音色。 */
        val voice: String? = null,
        val instructions: String? = null,
        /** Realtime 供应商画像：OpenAI 官方（默认）或阿里百炼 Qwen-Omni。 */
        val profile: RealtimeProfile = RealtimeProfile.OPENAI,
        /** 首次建连/配置总上限；Ready后的恢复使用传输层独立预算，用户停止立即取消。 */
        val prepareTimeoutMs: Long = 60_000L,
        /** 完整 function schema JSON 数组；null 保留纯实时语音模式。 */
        val toolsJson: String? = null,
    )

    data class LocalConfig(
        val modelPath: String,
        val maxTokens: Int = 1024,
        val temperature: Float = 0.7f,
    )

    private val switchMutex = Mutex()

    @Volatile var current: IAgentExecutor? = null
        private set

    fun create(mode: ExecutorMode): IAgentExecutor = when (mode) {
        ExecutorMode.CLOUD_REALTIME -> CloudRealtimeExecutor(
            wsUrl = cloudConfig.wsUrl,
            apiKey = cloudConfig.apiKey,
            model = cloudConfig.model,
            voice = cloudConfig.voice,
            instructions = cloudConfig.instructions,
            profile = cloudConfig.profile,
            prepareTimeoutMs = cloudConfig.prepareTimeoutMs,
            toolsJson = cloudConfig.toolsJson,
        )
        ExecutorMode.ON_DEVICE_LOCAL -> OnDeviceLocalExecutor(
            context = context,
            modelPath = localConfig.modelPath,
            maxTokens = localConfig.maxTokens,
            temperature = localConfig.temperature,
        )
    }

    /**
     * 热切换。
     * @param summarize 从当前会话提炼交接摘要的函数（记忆层注入）；无摘要需求传 null。
     * @return 切换后的新 executor。
     */
    suspend fun switchTo(
        mode: ExecutorMode,
        summarize: (suspend () -> String?)? = null,
    ): IAgentExecutor = switchTo(mode, beforePrepare = {}, summarize = summarize)

    /**
     * 先公布新实例并安装事件订阅，再建立连接，使首连/自动恢复状态立即可见。
     * 无回调的旧签名保留为重载，兼容 switchTo(mode) { summary } 的尾随 lambda。
     */
    suspend fun switchTo(
        mode: ExecutorMode,
        beforePrepare: (IAgentExecutor) -> Unit,
        summarize: (suspend () -> String?)? = null,
    ): IAgentExecutor = switchMutex.withLock {
        val old = current
        if (old?.mode == mode && old.ready.value) {
            beforePrepare(old)
            return old
        }

        val summary = summarize?.invoke()
        old?.shutdown()
        current = null

        val next = create(mode)
        current = next
        try {
            beforePrepare(next)
            next.prepare(handoverContext = summary)
        } catch (t: Throwable) {
            try {
                next.shutdown()
            } catch (cleanup: Throwable) {
                t.addSuppressed(cleanup)
            } finally {
                if (current === next) current = null
            }
            throw t
        }
        next
    }
}
