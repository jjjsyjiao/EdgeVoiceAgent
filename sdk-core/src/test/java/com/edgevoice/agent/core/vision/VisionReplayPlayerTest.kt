package com.edgevoice.agent.core.vision

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class VisionReplayPlayerTest {

    private lateinit var tempDir: File
    private var now = 1_000_000L

    /** 事件流：A=音频块，V=视频帧（载荷首字节即帧 id），用于断言交错时序 */
    private val events = mutableListOf<String>()
    private val windowStates = mutableListOf<Boolean>()
    private var interrupts = 0
    private var supports = true
    private var sleepGate: CompletableDeferred<Unit>? = null
    private val remoteCalls = mutableListOf<Triple<Long, Long, Int>>()

    private lateinit var config: ReplayConfig

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("replay-test").toFile()
        config = ReplayConfig()
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    private fun ring(): DiskFrameRing = DiskFrameRing(tempDir, clock = { now })

    private fun player(
        ring: DiskFrameRing,
        echo: PcmEchoRing,
        cfg: ReplayConfig = config,
        remote: (suspend (Long, Long, Int) -> List<ByteArray>)? = null,
    ): VisionReplayPlayer = VisionReplayPlayer(
        config = cfg,
        frameRing = ring,
        echoRing = echo,
        audioSender = { bb ->
            val first = bb.get(bb.position()).toInt() and 0xFF
            events += "A($first)"
        },
        videoSender = { jpeg -> events += "V(${jpeg[0].toInt() and 0xFF})" },
        interrupt = { interrupts++; events += "I" },
        supportsVideo = { supports },
        onReplayWindow = { windowStates += it; events += if (it) "W+" else "W-" },
        remoteFrames = remote,
        clock = { now },
        sleeper = { sleepGate?.await() },
    )

    /** 往帧环塞 [count] 帧，时间从 now-ageMs 起每秒一帧；帧载荷首字节=帧 id（1..count）。 */
    private fun seedFrames(ring: DiskFrameRing, count: Int, ageMs: Long) {
        val start = now - ageMs
        repeat(count) { i ->
            now = start + i * 1000L
            val id = (i + 1).coerceAtMost(255)
            ring.append(ByteArray(8) { id.toByte() })
        }
        now = 1_000_000L
    }

    /** 填满回声环 [audioMs] 毫秒（每块首字节递增可辨识）。 */
    private fun seedEcho(cfg: ReplayConfig = config, audioMs: Long = cfg.snapshotMs): PcmEchoRing {
        val echo = PcmEchoRing(cfg.echoRingBytes())
        val chunk = cfg.chunkBytes()
        val total = (audioMs * cfg.pcmBytesPerSec / 1000L).toInt()
        var written = 0
        var marker = 1
        while (written < total) {
            val len = minOf(chunk, total - written)
            echo.append(ByteArray(len) { marker.toByte() })
            written += len
            marker++
        }
        return echo
    }

    @Test
    fun happy_path_interrupts_then_interleaves_audio_and_frames_and_suppresses() = runBlocking {
        val r = ring()
        seedFrames(r, count = 12, ageMs = 60_000L) // 12 帧全在「刚才」3min 窗内
        val p = player(r, seedEcho())

        val result = p.tryTrigger("哎，我刚才有没有经过一家手机店？")

        assertThat(result.triggered).isTrue()
        assertThat(result.audioChunks).isEqualTo(80)          // 8000ms / 100ms
        assertThat(result.framesInjected).isEqualTo(10)      // maxFrames 截断 12→10
        assertThat(interrupts).isEqualTo(1)
        assertThat(windowStates).containsExactly(true, false).inOrder()

        // 时序铁律：先打断、再开窗；第一个媒体事件必须是音频（音频先行）
        assertThat(events.first()).isEqualTo("I")
        val firstMedia = events.first { it.startsWith("A") || it.startsWith("V") }
        assertThat(firstMedia).startsWith("A")

        val audioIdx = events.indices.filter { events[it].startsWith("A") }
        val videoIdx = events.indices.filter { events[it].startsWith("V") }
        // 所有历史帧都夹在第 2 块音频之后、最后一块音频之前
        assertThat(videoIdx.first()).isGreaterThan(audioIdx.first())
        assertThat(videoIdx.last()).isLessThan(audioIdx.last())
        assertThat(videoIdx).hasSize(10)
        // 等距分布：首尾必中（首帧 id=1，尾帧 id=12）
        assertThat(events[videoIdx.first()]).isEqualTo("V(1)")
        assertThat(events[videoIdx.last()]).isEqualTo("V(12)")

        // 重放结束即进入转写抑制；尾部余量过后解除
        assertThat(p.isSuppressed()).isTrue()
        now += config.suppressTailMs + 1
        assertThat(p.isSuppressed()).isFalse()
        assertThat(p.replayCount()).isEqualTo(1)
    }

    @Test
    fun non_recall_utterance_has_no_side_effects() = runBlocking {
        val p = player(ring(), seedEcho())
        val result = p.tryTrigger("这个菜单都有什么？")
        assertThat(result.triggered).isFalse()
        assertThat(result.reason).isEqualTo("not_recall")
        assertThat(interrupts).isEqualTo(0)
        assertThat(events).isEmpty()
    }

    @Test
    fun disabled_config_never_replays() = runBlocking {
        val r = ring()
        seedFrames(r, 5, 30_000L)
        val p = player(r, seedEcho(ReplayConfig.DISABLED), ReplayConfig.DISABLED)
        val result = p.tryTrigger("我刚才经过一家手机店吗")
        assertThat(result.reason).isEqualTo("disabled")
        assertThat(interrupts).isEqualTo(0)
    }

    @Test
    fun unsupported_executor_skips_replay() = runBlocking {
        supports = false
        val r = ring()
        seedFrames(r, 5, 30_000L)
        val p = player(r, seedEcho())
        val result = p.tryTrigger("我刚才经过一家手机店吗")
        assertThat(result.reason).isEqualTo("video_unsupported")
        assertThat(interrupts).isEqualTo(0)
    }

    @Test
    fun empty_frame_ring_skips_without_interrupt() = runBlocking {
        val p = player(ring(), seedEcho())
        val result = p.tryTrigger("我刚才看到一只猫了吗")
        assertThat(result.reason).isEqualTo("no_frames")
        assertThat(interrupts).isEqualTo(0)
        assertThat(windowStates).isEmpty()
    }

    @Test
    fun too_short_echo_skips() = runBlocking {
        val r = ring()
        seedFrames(r, 5, 30_000L)
        val echo = PcmEchoRing(config.echoRingBytes())
        echo.append(ByteArray(200) { 9 }) // 远小于 minAudioMs=500
        val p = player(r, echo)
        val result = p.tryTrigger("我刚才经过一家手机店吗")
        assertThat(result.reason).isEqualTo("audio_too_short")
        assertThat(interrupts).isEqualTo(0)
    }

    @Test
    fun only_frames_inside_lookback_window_are_replayed() = runBlocking {
        val r = ring()
        // 5 帧在 10 分钟前（超出「刚才」180s 窗），3 帧在最近 30s
        now = 1_000_000L - 600_000L
        repeat(5) { i ->
            now = 1_000_000L - 600_000L + i * 1000L
            r.append(ByteArray(8) { (100 + i).toByte() })
        }
        now = 1_000_000L
        seedFrames(r, count = 3, ageMs = 30_000L) // id 1..3
        val p = player(r, seedEcho())

        val result = p.tryTrigger("我刚才有没有看到共享单车")
        assertThat(result.triggered).isTrue()
        assertThat(result.framesInjected).isEqualTo(3)
        val videoIdx = events.indices.filter { events[it].startsWith("V") }
        assertThat(videoIdx.map { events[it] }).containsExactly("V(1)", "V(2)", "V(3)").inOrder()
    }

    @Test
    fun last_time_word_spans_full_ring() = runBlocking {
        val r = ring()
        // 帧在 25 分钟前（超出所有分级窗，但在帧环 30min 内）；「上次」应全环取帧
        now = 1_000_000L - 25 * 60_000L
        repeat(4) { i ->
            now = 1_000_000L - 25 * 60_000L + i * 1000L
            r.append(ByteArray(8) { (i + 1).toByte() })
        }
        now = 1_000_000L
        val p = player(r, seedEcho())
        val result = p.tryTrigger("我上次经过的那家银行叫什么")
        assertThat(result.triggered).isTrue()
        assertThat(result.framesInjected).isEqualTo(4)
    }

    @Test
    fun concurrent_trigger_while_replaying_returns_busy() = runBlocking {
        val r = ring()
        seedFrames(r, 6, 30_000L)
        val gate = CompletableDeferred<Unit>()
        sleepGate = gate
        val p = player(r, seedEcho())

        val scope = CoroutineScope(Job() + Dispatchers.Unconfined)
        val first = scope.launch { p.tryTrigger("我刚才经过一家手机店吗") }
        val secondResult = p.tryTrigger("我刚才看到一家奶茶店吗")
        assertThat(secondResult.triggered).isFalse()
        assertThat(secondResult.reason).isEqualTo("busy")

        gate.complete(Unit)
        first.join()
        assertThat(p.replayCount()).isEqualTo(1)
    }

    @Test
    fun local_coverage_uses_local_only_and_never_calls_remote() = runBlocking {
        val r = ring()
        // 帧环最旧帧恰为窗口起点（oldest == fromMs）→ 本地完整覆盖「刚才」180s 窗
        seedFrames(r, count = 6, ageMs = 180_000L)
        val p = player(r, seedEcho(), remote = { f, t, m ->
            remoteCalls += Triple(f, t, m)
            listOf(ByteArray(4) { 0x7F })
        })

        val result = p.tryTrigger("我刚才经过一家手机店吗")
        assertThat(result.triggered).isTrue()
        assertThat(remoteCalls).isEmpty() // 覆盖内零网络，纯本地
        assertThat(result.framesInjected).isEqualTo(6)
    }

    @Test
    fun long_window_beyond_local_falls_back_to_remote() = runBlocking {
        val r = ring()
        seedFrames(r, count = 3, ageMs = 30_000L) // 本地仅近 30s，远不覆盖「昨天」24h 窗
        val remote = listOf(ByteArray(4) { 0x11 }, ByteArray(4) { 0x22 })
        val p = player(r, seedEcho(), remote = { f, t, m ->
            remoteCalls += Triple(f, t, m)
            remote
        })

        val result = p.tryTrigger("我昨天看到过一家银行")
        assertThat(result.triggered).isTrue()
        assertThat(remoteCalls).hasSize(1)
        assertThat(remoteCalls[0].third).isEqualTo(config.maxFrames) // 抽帧上限随配置透传
        assertThat(result.framesInjected).isEqualTo(2)
        // 远端帧优先于本地：注入的是 0x11/0x22，而非本地 1/2/3
        assertThat(events.filter { it.startsWith("V") })
            .containsExactly("V(17)", "V(34)").inOrder()
    }

    @Test
    fun long_window_with_empty_remote_falls_back_to_local() = runBlocking {
        val r = ring()
        seedFrames(r, count = 4, ageMs = 30_000L)
        val p = player(r, seedEcho(), remote = { f, t, m ->
            remoteCalls += Triple(f, t, m)
            emptyList()
        })

        val result = p.tryTrigger("我昨天看到过一家银行")
        assertThat(result.triggered).isTrue()
        assertThat(remoteCalls).hasSize(1)
        assertThat(result.framesInjected).isEqualTo(4) // 远端空 → 退回本地 4 帧
        assertThat(events.filter { it.startsWith("V") }).hasSize(4)
    }

    @Test
    fun cancelled_replay_closes_window_in_finally() = runBlocking {
        val r = ring()
        seedFrames(r, 6, 30_000L)
        val gate = CompletableDeferred<Unit>()
        sleepGate = gate
        val p = player(r, seedEcho())

        val scope = CoroutineScope(Job() + Dispatchers.Unconfined)
        val job = scope.launch { p.tryTrigger("我刚才经过一家手机店吗") }
        assertThat(windowStates).containsExactly(true)
        job.cancel()
        gate.complete(Unit)
        job.join()
        assertThat(windowStates).containsExactly(true, false).inOrder()
        assertThat(p.replayCount()).isEqualTo(0) // 取消不计回放
    }
}
