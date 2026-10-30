package com.edgevoice.agent.core.governance

/**
 * 死循环阻断（技术文档 §2.2）：限制连续工具调用深度，防止 Agent 递归不收敛。
 *
 * 语义：每进入一次工具调用 +1；产出终止指令（Stop Token / 轮次正常结束）后归零。
 * 超深返回 false，由上层切换兜底话术。
 */
class MaxIterationGuard(private val maxIterations: Int = 3) {

    private var depth = 0
    private var blocked = 0

    /** 进入一次迭代；返回是否仍在允许深度内。 */
    @Synchronized
    fun enter(): Boolean {
        depth++
        if (depth > maxIterations) {
            blocked++
            return false
        }
        return true
    }

    /** 正常终止：深度归零。 */
    @Synchronized
    fun onTerminal() {
        depth = 0
    }

    @Synchronized
    fun reset() {
        depth = 0
        blocked = 0
    }

    @Synchronized
    fun currentDepth(): Int = depth

    /** 累计被阻断次数（调试抽屉可视化）。 */
    @Synchronized
    fun blockedCount(): Int = blocked
}
