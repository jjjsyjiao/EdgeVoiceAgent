package com.edgevoice.agent.media.ring

import java.io.Closeable
import java.nio.ByteBuffer

/**
 * NDK 零拷贝环形缓冲区的 Kotlin 封装（对齐 core-media 模块契约）。
 *
 * - 生产者（AudioRecord 采集线程）与消费者（网络传输 / 播放线程）通过 native SPSC 环内存隔离，
 *   彻底消除高频 JVM 堆分配与 GC 抖动；
 * - [write]/[read] 走 DirectByteBuffer 堆外地址，为真正的 zero-copy 路径。
 */
class NativeRingBuffer private constructor(
    private var handle: Long,
    val capacityBytes: Int,
) : Closeable {

    /** 零拷贝写入 DirectByteBuffer，返回实际写入字节数。 */
    fun write(src: ByteBuffer): Int {
        checkOpen()
        require(src.isDirect) { "write 需要 DirectByteBuffer 才能零拷贝" }
        return nativeWriteDirect(handle, src, src.remaining())
    }

    /** 零拷贝读取到 DirectByteBuffer，返回实际读取字节数。 */
    fun read(dst: ByteBuffer): Int {
        checkOpen()
        require(dst.isDirect) { "read 需要 DirectByteBuffer 才能零拷贝" }
        return nativeReadDirect(handle, dst, dst.remaining())
    }

    /** 数组回退路径（单测/调试用，非零拷贝）。 */
    fun write(src: ByteArray, offset: Int = 0, length: Int = src.size - offset): Int {
        checkOpen()
        require(offset == 0 && length == src.size) { "数组回退路径暂只支持整段写入" }
        return nativeWriteArray(handle, src, length)
    }

    fun read(dst: ByteArray, length: Int = dst.size): Int {
        checkOpen()
        return nativeReadArray(handle, dst, length)
    }

    fun availableToRead(): Int = if (handle == 0L) 0 else nativeAvailableToRead(handle)

    fun availableToWrite(): Int = if (handle == 0L) 0 else nativeAvailableToWrite(handle)

    /** 打断场景：清空未读队列（等价于丢弃 AudioTrack 待播 PCM）。 */
    fun clear() = nativeClear(handle)

    override fun close() {
        if (handle != 0L) {
            nativeDestroy(handle)
            handle = 0L
        }
    }

    private fun checkOpen() {
        check(handle != 0L) { "NativeRingBuffer 已关闭" }
    }

    companion object {
        init {
            System.loadLibrary("edgevoice_media")
        }

        /** 创建环形缓冲区；[capacityBytes] 会被向上取整为 2 的幂。 */
        fun create(capacityBytes: Int): NativeRingBuffer {
            require(capacityBytes > 0) { "capacityBytes 必须为正数" }
            val h = nativeCreate(capacityBytes)
            check(h != 0L) { "无法分配环形缓冲区（native 创建失败）" }
            return NativeRingBuffer(h, nativeCapacity(h))
        }

        @JvmStatic private external fun nativeCreate(capacityBytes: Int): Long
        @JvmStatic private external fun nativeDestroy(handle: Long)
        @JvmStatic private external fun nativeWriteDirect(handle: Long, directBuffer: ByteBuffer, length: Int): Int
        @JvmStatic private external fun nativeReadDirect(handle: Long, directBuffer: ByteBuffer, length: Int): Int
        @JvmStatic private external fun nativeWriteArray(handle: Long, array: ByteArray, length: Int): Int
        @JvmStatic private external fun nativeReadArray(handle: Long, array: ByteArray, length: Int): Int
        @JvmStatic private external fun nativeAvailableToRead(handle: Long): Int
        @JvmStatic private external fun nativeAvailableToWrite(handle: Long): Int
        @JvmStatic private external fun nativeCapacity(handle: Long): Int
        @JvmStatic private external fun nativeClear(handle: Long)
    }
}
