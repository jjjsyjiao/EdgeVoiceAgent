package com.edgevoice.agent.core.memory

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * ContextStore 门面：热切换摘要只取对话轮次、Tier1 双重裁剪、视觉帧登记不落像素。
 * 默认装配使用 NoOpLongTermMemory，纯 JVM 可测。
 */
class ContextStoreTest {

    private fun item(ts: Long, kind: ContextKind, text: String) = ContextItem(ts, kind, text)

    @Test
    fun handover_summary_contains_only_dialogue_turns() {
        val store = ContextStore()
        store.recordUserSpeech("我叫张三", 1_000L)
        store.recordAssistantSpeech("你好张三", 2_000L)
        store.recordSystem("系统内部状态不应进摘要", 3_000L)
        store.recordVisionFrame(4_000L, null, 0.5f)
        store.recordUserSpeech("帮我订杯咖啡", 5_000L)

        val summary = store.summarizeForHandover()
        assertThat(summary).isNotNull()
        assertThat(summary).contains("USER_SPEECH: 我叫张三")
        assertThat(summary).contains("ASSISTANT_SPEECH: 你好张三")
        assertThat(summary).contains("帮我订杯咖啡")
        // SYSTEM 与视觉帧不进交接摘要
        assertThat(summary).doesNotContain("系统内部状态")
    }

    @Test
    fun handover_summary_respects_max_turns_and_truncates_long_text() {
        val store = ContextStore()
        repeat(10) { i ->
            store.recordUserSpeech("用户第 $i 轮 " + "x".repeat(200), i * 10L)
        }
        val summary = store.summarizeForHandover(maxTurns = 2)!!
        // 只保留最近 2 轮
        assertThat(summary).contains("用户第 9 轮")
        assertThat(summary).contains("用户第 8 轮")
        assertThat(summary).doesNotContain("用户第 7 轮")
        // 单条截断到 120 字符（前缀+120），不会出现 200 个 x
        assertThat(summary.lines().first { it.startsWith("USER_SPEECH") }.length).isAtMost(
            "USER_SPEECH: ".length + 120,
        )
    }

    @Test
    fun handover_returns_null_without_dialogue() {
        val store = ContextStore()
        store.recordSystem("只有系统消息", 1L)
        assertThat(store.summarizeForHandover()).isNull()
    }

    @Test
    fun vision_frame_is_registered_to_fusion_without_pixels() {
        val store = ContextStore()
        store.recordVisionFrame(timestampMs = 10_000L, frameRef = null, motionScore = 0.8f)
        // 1.2s 后开口，可对齐到该帧的文本化描述
        store.recordUserSpeech("刚才那个是什么", 11_200L)
        // 视觉帧不进 Tier1 对话快照
        assertThat(store.working.snapshot().none { it.kind == ContextKind.VISION_FRAME }).isTrue()
        val aligned = store.fusion.alignTo(11_200L)
        assertThat(aligned).hasSize(1)
        assertThat(aligned[0].motionScore).isEqualTo(0.8f)
        assertThat(aligned[0].text).isEmpty()
    }

    @Test
    fun tier1_caps_item_count() {
        val mem = Tier1WorkingMemory(maxItems = 3, maxWindowMs = Long.MAX_VALUE)
        repeat(5) { mem.add(item(it.toLong(), ContextKind.SYSTEM, "m$it")) }
        assertThat(mem.size()).isEqualTo(3)
        assertThat(mem.snapshot().map { it.text }).containsExactly("m2", "m3", "m4").inOrder()
    }

    @Test
    fun tier1_evicts_items_older_than_window() {
        val mem = Tier1WorkingMemory(maxItems = 100, maxWindowMs = 1_000L)
        mem.add(item(0L, ContextKind.SYSTEM, "old"))
        mem.add(item(500L, ContextKind.SYSTEM, "mid"))
        // 新条目时间戳 2000：cutoff=1000，old 与 mid 都越窗被裁掉
        mem.add(item(2_000L, ContextKind.SYSTEM, "new"))
        assertThat(mem.snapshot().map { it.text }).containsExactly("new")
    }

    @Test
    fun recall_failure_degrades_to_empty_without_throwing() {
        // 指向一个不可达地址：HttpLongTermMemory 静默降级为空结果
        val store = ContextStore(longTerm = HttpLongTermMemory(baseUrl = "http://127.0.0.1:1"))
        store.recordUserSpeech("a", 1L)
        store.recordAssistantSpeech("b", 2L)
        // 不抛异常即合格；runBlocking 桥接 suspend
        kotlinx.coroutines.runBlocking {
            assertThat(store.recall("任意查询")).isEmpty()
            assertThat(store.remember("任意偏好")).isFalse()
            assertThat(store.distillRecent()).isEmpty()
        }
    }
}
