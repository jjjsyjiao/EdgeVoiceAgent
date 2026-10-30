package com.edgevoice.agent.core.vision

import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * [DiskFrameRing] 落盘/索引/双限淘汰/冷启动重建验证（真实临时目录，不 mock 文件系统）。
 */
class DiskFrameRingTest {

    private lateinit var dir: File
    private var now = 1_000_000_000_000L

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("frame_ring_test").toFile()
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun ring(maxAgeMs: Long = 30 * 60_000L, maxBytes: Long = 200L * 1024 * 1024) =
        DiskFrameRing(dir, maxAgeMs = maxAgeMs, maxTotalBytes = maxBytes, clock = { now })

    private fun jpeg(size: Int, tag: Byte) = ByteArray(size).also { it[0] = tag }

    @Test
    fun append_persists_file_and_indexes_by_time() {
        val r = ring()
        val e = r.append(jpeg(128, 7))!!

        assertThat(e.file.isFile).isTrue()
        assertThat(e.sizeBytes).isEqualTo(128L)
        assertThat(r.stats().frameCount).isEqualTo(1)
        assertThat(r.framesInWindow(now - 1, now + 1)).hasSize(1)
        // 时间窗外查不到
        assertThat(r.framesInWindow(now + 100, now + 200)).isEmpty()
    }

    @Test
    fun read_back_roundtrips_bytes() {
        val r = ring()
        val payload = jpeg(256, 0x41)
        val e = r.append(payload)!!
        assertThat(r.readJpeg(e)).isEqualTo(payload)
    }

    @Test
    fun age_eviction_drops_old_frames_on_next_append() {
        val r = ring(maxAgeMs = 10_000L)
        r.append(jpeg(10, 1))
        now += 5_000L
        r.append(jpeg(10, 2))
        assertThat(r.stats().frameCount).isEqualTo(2)

        now += 6_000L // 第 1 帧 11s 前，超 10s 窗
        r.append(jpeg(10, 3))

        val stats = r.stats()
        assertThat(stats.frameCount).isEqualTo(2) // 帧1被淘汰
        val remaining = r.framesInWindow(0, now).map { it.file.readBytes()[0].toInt() }
        assertThat(remaining).containsExactly(2, 3).inOrder()
        // 被淘汰帧文件必须真删，不留孤儿
        assertThat(dir.listFiles()!!.filter { it.name.endsWith(".jpg") }).hasSize(2)
    }

    @Test
    fun size_eviction_keeps_total_under_cap() {
        val r = ring(maxBytes = 250L)
        r.append(jpeg(100, 1))
        r.append(jpeg(100, 2))
        r.append(jpeg(100, 3)) // 累计 300 > 250 → 帧1被删

        val stats = r.stats()
        assertThat(stats.totalBytes).isEqualTo(200L)
        assertThat(r.framesInWindow(0, now).map { it.file.readBytes()[0].toInt() })
            .containsExactly(2, 3).inOrder()
    }

    @Test
    fun sample_picks_evenly_spaced_frames_including_endpoints() {
        val r = ring()
        repeat(10) {
            r.append(jpeg(10, it.toByte()))
            now += 1_000L
        }
        val samples = r.sampleJpegs(0, now, 4)
        // 10 帧取 4：下标必为 0、3、6、9（首尾必中、等距）
        assertThat(samples.map { it[0].toInt() }).containsExactly(0, 3, 6, 9).inOrder()
    }

    @Test
    fun sample_returns_all_when_window_smaller_than_cap() {
        val r = ring()
        repeat(3) {
            r.append(jpeg(10, (10 + it).toByte()))
            now += 1_000L
        }
        assertThat(r.sampleJpegs(0, now, 8)).hasSize(3)
    }

    @Test
    fun sample_respects_time_window() {
        val base = now
        val r = ring()
        repeat(5) {
            r.append(jpeg(10, it.toByte()))
            now += 1_000L
        }
        // 只回放 base+2000~base+3000 窗口（闭区间，命中 tag2/tag3）
        val samples = r.sampleJpegs(base + 2_000L, base + 3_000L, 10).map { it[0].toInt() }
        assertThat(samples).containsExactly(2, 3).inOrder()
    }

    @Test
    fun restart_rebuilds_index_from_filenames() {
        ring().apply {
            append(jpeg(10, 1)); now += 1_000L
            append(jpeg(10, 2)); now += 1_000L
            append(jpeg(10, 3))
        }
        // 全新实例扫同一目录：索引完整、顺序正确
        val rebuilt = ring()
        assertThat(rebuilt.stats().frameCount).isEqualTo(3)
        assertThat(rebuilt.framesInWindow(0, now).map { it.file.readBytes()[0].toInt() })
            .containsExactly(1, 2, 3).inOrder()
    }

    @Test
    fun restart_evicts_expired_and_cleans_garbage() {
        val first = ring(maxAgeMs = 10_000L)
        first.append(jpeg(10, 1))
        now += 20_000L
        first.append(jpeg(10, 2))
        // 模拟崩溃残片与脏文件
        File(dir, "99999-0000.jpg.tmp").writeBytes(ByteArray(5))
        File(dir, "garbage.dat").writeBytes(ByteArray(5))

        val rebuilt = ring(maxAgeMs = 10_000L)
        assertThat(rebuilt.stats().frameCount).isEqualTo(1)
        assertThat(dir.listFiles()!!.map { it.name }).doesNotContain("99999-0000.jpg.tmp")
        assertThat(dir.listFiles()!!.map { it.name }).doesNotContain("garbage.dat")
    }

    @Test
    fun empty_or_failed_input_is_tolerated() {
        val r = ring()
        assertThat(r.append(ByteArray(0))).isNull()
        assertThat(r.stats().frameCount).isEqualTo(0)
    }

    @Test
    fun same_millisecond_frames_stay_ordered() {
        // 同一墙钟毫秒内连续两帧（相机线程高频场景）：seq 保证都不丢、顺序不反
        val r = ring()
        r.append(jpeg(10, 1))
        r.append(jpeg(10, 2))
        assertThat(r.framesInWindow(now, now).map { it.file.readBytes()[0].toInt() })
            .containsExactly(1, 2).inOrder()
    }
}
