package com.edgevoice.agent.core.hook

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test

/** Hook 注册表：前置改写链传递、Block 短路、后置全部执行。 */
class HookRegistryTest {

    @Test
    fun empty_registry_continues_with_original_context() = runBlocking {
        val registry = HookRegistry()
        val ctx = HookContext(text = "原文", stage = "user_speech")
        val decision = registry.runBefore(ctx)
        assertThat(decision).isInstanceOf(HookDecision.Continue::class.java)
        assertThat((decision as HookDecision.Continue).context.text).isEqualTo("原文")
    }

    @Test
    fun before_hooks_can_rewrite_text_in_chain() = runBlocking {
        val registry = HookRegistry()
        registry.register(object : AgentHook {
            override suspend fun beforeExecute(context: HookContext) =
                HookDecision.Continue(context.copy(text = context.text + "|A"))
        })
        registry.register(object : AgentHook {
            override suspend fun beforeExecute(context: HookContext) =
                HookDecision.Continue(context.copy(text = context.text + "|B"))
        })

        val decision = registry.runBefore(HookContext("x", "s"))
        assertThat((decision as HookDecision.Continue).context.text).isEqualTo("x|A|B")
    }

    @Test
    fun block_short_circuits_remaining_hooks() = runBlocking {
        val registry = HookRegistry()
        var secondCalled = false
        registry.register(object : AgentHook {
            override suspend fun beforeExecute(context: HookContext) =
                HookDecision.Block(reason = "denied")
        })
        registry.register(object : AgentHook {
            override suspend fun beforeExecute(context: HookContext): HookDecision {
                secondCalled = true
                return HookDecision.Continue(context)
            }
        })

        val decision = registry.runBefore(HookContext("x", "s"))
        assertThat(decision).isInstanceOf(HookDecision.Block::class.java)
        assertThat((decision as HookDecision.Block).reason).isEqualTo("denied")
        assertThat(secondCalled).isFalse()
    }

    @Test
    fun after_hooks_all_run_even_if_multiple() = runBlocking {
        val registry = HookRegistry()
        val seen = mutableListOf<Int>()
        registry.register(object : AgentHook {
            override suspend fun afterExecute(context: HookContext) {
                seen.add(1)
            }
        })
        registry.register(object : AgentHook {
            override suspend fun afterExecute(context: HookContext) {
                seen.add(2)
            }
        })
        registry.runAfter(HookContext("x", "s"))
        assertThat(seen).containsExactly(1, 2).inOrder()
    }

    @Test
    fun unregister_removes_hook() = runBlocking {
        val registry = HookRegistry()
        val hook = object : AgentHook {
            override suspend fun beforeExecute(context: HookContext) =
                HookDecision.Continue(context.copy(text = context.text + "!"))
        }
        registry.register(hook)
        assertThat(registry.size()).isEqualTo(1)
        registry.unregister(hook)
        assertThat(registry.size()).isEqualTo(0)
        val decision = registry.runBefore(HookContext("x", "s"))
        assertThat((decision as HookDecision.Continue).context.text).isEqualTo("x")
    }
}
