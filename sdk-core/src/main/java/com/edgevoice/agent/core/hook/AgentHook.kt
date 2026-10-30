package com.edgevoice.agent.core.hook

import java.util.concurrent.CopyOnWriteArrayList

/** 拦截上下文：可在 Hook 间传递与修改。 */
data class HookContext(
    val text: String,
    val stage: String,
    val attributes: MutableMap<String, Any?> = mutableMapOf(),
)

/** Hook 决策：继续（可携带改写后的上下文）或阻断。 */
sealed class HookDecision {
    data class Continue(val context: HookContext) : HookDecision()
    data class Block(val reason: String) : HookDecision()
}

/**
 * Agent 执行前后拦截器（技术文档 §2.2 Hook Framework）。
 * 典型用法：PreToolCallHook 校验参数/防越权；PostToolCallHook 格式化输出。
 */
interface AgentHook {
    suspend fun beforeExecute(context: HookContext): HookDecision = HookDecision.Continue(context)
    suspend fun afterExecute(context: HookContext) {}
}

/**
 * Hook 注册表。用 CopyOnWriteArrayList：注册/注销是低频操作，遍历是高频操作，
 * 读多写少场景下比加锁遍历更优（对标 Java 的 Listener 列表惯例）。
 */
class HookRegistry {
    private val hooks = CopyOnWriteArrayList<AgentHook>()

    fun register(hook: AgentHook) {
        hooks.add(hook)
    }

    fun unregister(hook: AgentHook) {
        hooks.remove(hook)
    }

    /** 依次执行前置拦截；任一 Hook 阻断则短路返回。 */
    suspend fun runBefore(context: HookContext): HookDecision {
        var current = context
        for (h in hooks) {
            when (val d = h.beforeExecute(current)) {
                is HookDecision.Block -> return d
                is HookDecision.Continue -> current = d.context
            }
        }
        return HookDecision.Continue(current)
    }

    suspend fun runAfter(context: HookContext) {
        for (h in hooks) h.afterExecute(context)
    }

    fun size(): Int = hooks.size
}
