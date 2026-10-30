package com.edgevoice.agent.core.vision

import java.io.File

/**
 * 帧环中的一条记录（按时间升序）。
 *
 * @property timestampMs 帧落盘时刻（墙钟 [System.currentTimeMillis]；跨进程重启后仍可按「最近 N 分钟」查询）
 * @property file JPEG 文件（720p q80 阶梯产物，复用上行编码字节，不二次编码）
 * @property sizeBytes 文件字节数（容量淘汰与遥测用）
 */
class FrameRingEntry(
    val timestampMs: Long,
    val file: File,
    val sizeBytes: Long,
)

data class FrameRingStats(
    val frameCount: Int,
    val totalBytes: Long,
    val oldestTimestampMs: Long?,
    val newestTimestampMs: Long?,
)

/**
 * 磁盘滚动帧环（V2 情景回忆的本地存储）。
 *
 * 形态（用户已拍板）：逐帧 JPEG 文件 + 以文件名为索引；
 * 不引 Room/SQLite，重启后直接扫目录重建内存索引。
 *
 * 双限淘汰：超过 [maxAgeMs] 的最旧帧先删；仍超 [maxTotalBytes] 再从最旧继续删。
 * 并发：所有公开方法在 [lock] 上串行；写入用 tmp+rename 原子落盘，崩溃只留 .tmp 残片（启动清理）。
 *
 * 纯 java.io 实现：不依赖 Android，JVM 单测用临时目录即可覆盖全部路径。
 */
class DiskFrameRing(
    rootDir: File,
    private val maxAgeMs: Long = DEFAULT_MAX_AGE_MS,
    private val maxTotalBytes: Long = DEFAULT_MAX_TOTAL_BYTES,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private val root: File = rootDir
    private val entries = ArrayDeque<FrameRingEntry>() // 始终按 timestampMs 升序
    private val lock = Any()
    private var seq = 0
    private var totalBytes = 0L

    init {
        root.mkdirs()
        check(root.isDirectory) { "帧环目录不可用: $root" }
        scan()
    }

    /**
     * 落盘一帧。失败（IO 异常/磁盘满）返回 null —— 帧环是回忆增强而非主链路，
     * 调用方必须容忍 null 且不得向相机线程抛。
     */
    fun append(jpeg: ByteArray): FrameRingEntry? = synchronized(lock) {
        val now = clock()
        if (jpeg.isEmpty()) return null
        val target = uniqueFile(now)
        val tmp = File(root, target.name + ".tmp")
        try {
            tmp.writeBytes(jpeg)
            if (!tmp.renameTo(target)) {
                tmp.delete()
                return null
            }
        } catch (t: Throwable) {
            tmp.delete()
            return null
        }
        val entry = FrameRingEntry(now, target, target.length())
        entries.addLast(entry) // seq 单调递增保证同毫秒也严格有序
        totalBytes += entry.sizeBytes
        pruneLocked()
        return entry
    }

    /** 时间窗内的记录（闭区间，按时间升序）。 */
    fun framesInWindow(fromMs: Long, toMs: Long): List<FrameRingEntry> = synchronized(lock) {
        entries.filter { it.timestampMs in fromMs..toMs }
    }

    /** 读出一帧 JPEG；文件丢失/读取失败返回 null。 */
    fun readJpeg(entry: FrameRingEntry): ByteArray? =
        runCatching { entry.file.takeIf { it.isFile }?.readBytes() }.getOrNull()

    /**
     * 从时间窗内**均匀抽帧**（回放控制 video_tokens 成本的关键）。
     * 帧不足 [maxCount] 时全取；超出时取首/尾必中的等距下标，顺序严格按时间。
     */
    fun sampleJpegs(fromMs: Long, toMs: Long, maxCount: Int): List<ByteArray> {
        if (maxCount <= 0) return emptyList()
        val windowed = synchronized(lock) { entries.filter { it.timestampMs in fromMs..toMs } }
        val picked = if (windowed.size <= maxCount) windowed else {
            val n = windowed.size
            IntArray(maxCount) { i ->
                if (i == maxCount - 1) n - 1 else i * (n - 1) / (maxCount - 1)
            }.distinct().map { windowed[it] }
        }
        return picked.mapNotNull(::readJpeg)
    }

    fun stats(): FrameRingStats = synchronized(lock) {
        FrameRingStats(
            frameCount = entries.size,
            totalBytes = totalBytes,
            oldestTimestampMs = entries.firstOrNull()?.timestampMs,
            newestTimestampMs = entries.lastOrNull()?.timestampMs,
        )
    }

    /** 强制执行一次淘汰（正常每次 append 后已自动触发；供空闲维护调用）。 */
    fun prune() = synchronized(lock) { pruneLocked() }

    // ---------------- 内部 ----------------

    private fun pruneLocked() {
        val ageCutoff = clock() - maxAgeMs
        // 1) 时间淘汰
        while (entries.isNotEmpty() && entries.first().timestampMs < ageCutoff) {
            removeFirstLocked()
        }
        // 2) 容量淘汰（年龄相同的极端情况下也从最旧开始）
        while (entries.isNotEmpty() && totalBytes > maxTotalBytes) {
            removeFirstLocked()
        }
    }

    private fun removeFirstLocked() {
        val e = entries.removeFirst()
        totalBytes -= e.sizeBytes
        if (totalBytes < 0L) totalBytes = 0L
        e.file.delete()
    }

    private fun uniqueFile(now: Long): File {
        while (true) {
            val candidate = File(root, "%013d-%04d.jpg".format(now, seq and 0xFFFF))
            seq++
            if (!candidate.exists()) return candidate
        }
    }

    /** 冷启动重建：扫描全部帧文件名 → 排序 → 按当前双限立即淘汰一次。 */
    private fun scan() = synchronized(lock) {
        root.listFiles { f -> f.isFile }?.forEach { f ->
            if (f.name.endsWith(".tmp")) { f.delete(); return@forEach } // 上次崩溃残片
            val ts = f.name.substringBefore('-').toLongOrNull()
            if (ts == null) { f.delete(); return@forEach }
            entries.addLast(FrameRingEntry(ts, f, f.length()))
            totalBytes += f.length()
        }
        // 文件名时间戳排序（同毫秒顺序无法跨进程复原，影响可忽略）
        val sorted = entries.sortedWith(compareBy({ it.timestampMs }, { it.file.name }))
        entries.clear()
        sorted.forEach(entries::addLast)
        pruneLocked()
    }

    companion object {
        const val DEFAULT_MAX_AGE_MS: Long = 30L * 60L * 1000L       // 最近 30 分钟
        const val DEFAULT_MAX_TOTAL_BYTES: Long = 200L * 1024L * 1024L // 总占用 ≤ 200MB
    }
}
