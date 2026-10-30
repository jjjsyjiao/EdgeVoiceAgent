package com.edgevoice.agent.media.video

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * NV21 旋转纯字节运算的验证。
 * 策略：Y 平面填位置编码 y*w+x（旋转必须是双射），色度 V/U 填可区分的位置值，
 * 既断言人工推导的关键映射点，也用「值集合不变」兜底全图双射。
 */
class Nv21TransformTest {

    private val w = 6
    private val h = 4

    private fun buildNv21(): ByteArray {
        val ySize = w * h
        val out = ByteArray(ySize + ySize / 2)
        for (y in 0 until h) {
            for (x in 0 until w) {
                out[y * w + x] = (y * w + x).toByte() // 0..23，均在 byte 范围
            }
        }
        // V=0xC0+位置码，U=0x40+位置码，保证 V/U 值域不重叠且逐块可辨
        var p = ySize
        for (cy in 0 until h / 2) {
            for (cx in 0 until w / 2) {
                out[p++] = (0xC0 + cy * 3 + cx).toByte()
                out[p++] = (0x40 + cy * 3 + cx).toByte()
            }
        }
        return out
    }

    @Test
    fun rotate0_returns_copy_with_same_dimensions_and_bytes() {
        val src = buildNv21()
        val r = Nv21Transform.rotate(src, w, h, 0)
        assertThat(r.width).isEqualTo(w)
        assertThat(r.height).isEqualTo(h)
        assertThat(r.data).isNotSameInstanceAs(src) // 防御性拷贝
        assertThat(r.data).asList().containsExactlyElementsIn(src.asList()).inOrder()
    }

    @Test
    fun rotate90_swaps_dimensions_and_maps_known_points() {
        val r = Nv21Transform.rotate(buildNv21(), w, h, 90)
        assertThat(r.width).isEqualTo(h) // 4
        assertThat(r.height).isEqualTo(w) // 6

        // 输入 (2,1) 值=8 -> 输出 (h-1-y=2, x=2)，索引 2*4+2=10
        assertThat(r.data[10].toInt()).isEqualTo(8)
        // 输入 (5,3) 值=23 -> 输出 (0,5)，索引 5*4+0=20
        assertThat(r.data[20].toInt()).isEqualTo(23)
        // 输出 Y 平面必须恰好是输入的双射重排
        assertThat(r.data.copyOfRange(0, 24).toSortedSet())
            .containsExactlyElementsIn((0..23).map { it.toByte() }.toSet())
    }

    @Test
    fun rotate180_maps_known_point_and_is_bijection() {
        val r = Nv21Transform.rotate(buildNv21(), w, h, 180)
        assertThat(r.width).isEqualTo(w)
        assertThat(r.height).isEqualTo(h)
        // 输入 (2,1) 值=8 -> 输出 (w-1-x=3, h-1-y=2)，索引 2*6+3=15
        assertThat(r.data[15].toInt()).isEqualTo(8)
        assertThat(r.data.copyOfRange(0, 24).toSortedSet())
            .containsExactlyElementsIn((0..23).map { it.toByte() }.toSet())
    }

    @Test
    fun rotate270_swaps_dimensions_and_maps_known_points() {
        val r = Nv21Transform.rotate(buildNv21(), w, h, 270)
        assertThat(r.width).isEqualTo(h)
        assertThat(r.height).isEqualTo(w)
        // 输入 (2,1) 值=8 -> 输出 (y=1, w-1-x=3)，索引 3*4+1=13
        assertThat(r.data[13].toInt()).isEqualTo(8)
        // 输入 (0,0) 值=0 -> 输出 (0,5)，索引 5*4+0=20
        assertThat(r.data[20].toInt()).isEqualTo(0)
        assertThat(r.data.copyOfRange(0, 24).toSortedSet())
            .containsExactlyElementsIn((0..23).map { it.toByte() }.toSet())
    }

    @Test
    fun rotate_preserves_chroma_value_sets() {
        // 旋转不改变任何色度采样块的内容构成：V 集合与 U 集合各自不变
        val src = buildNv21()
        val srcV = src.copyOfRange(24, src.size).toSortedSet()
        for (deg in listOf(90, 180, 270)) {
            val r = Nv21Transform.rotate(src, w, h, deg)
            assertThat(r.data.copyOfRange(24, r.data.size).toSortedSet())
                .containsExactlyElementsIn(srcV)
        }
    }

    @Test
    fun illegal_rotation_is_rejected() {
        val src = buildNv21()
        runCatching { Nv21Transform.rotate(src, w, h, 45) }
            .also { assertThat(it.isFailure).isTrue() }
    }

    @Test
    fun odd_dimensions_and_bad_length_are_rejected() {
        val src = buildNv21()
        assertThat(runCatching { Nv21Transform.rotate(src, 5, 4, 90) }.isFailure).isTrue()
        assertThat(runCatching { Nv21Transform.rotate(ByteArray(10), 6, 4, 90) }.isFailure).isTrue()
    }

    private fun ByteArray.toSortedSet() = toList().toSortedSet()
}
