package com.edgevoice.agent.core.vision

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PcmEchoRingTest {

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }
    private fun ByteArray.ints() = map { it.toInt() }

    @Test
    fun snapshot_returns_what_was_appended_in_order() {
        val r = PcmEchoRing(10)
        r.append(bytes(1, 2, 3))
        r.append(bytes(4, 5))
        assertThat(r.snapshotTrailing().ints()).containsExactly(1, 2, 3, 4, 5).inOrder()
        assertThat(r.size()).isEqualTo(5)
    }

    @Test
    fun overflow_overwrites_oldest_and_preserves_chronology() {
        val r = PcmEchoRing(5)
        r.append(bytes(1, 2, 3))
        r.append(bytes(4, 5, 6)) // 容量 5：1 被覆盖，逻辑内容应为 2,3,4,5,6
        assertThat(r.snapshotTrailing().ints()).containsExactly(2, 3, 4, 5, 6).inOrder()
        assertThat(r.size()).isEqualTo(5)
    }

    @Test
    fun wrap_boundary_snapshot_is_unwrapped_correctly() {
        // 制造写指针绕回：cap5 写满后再写 2，尾部必然跨越物理数组边界
        val r = PcmEchoRing(5)
        r.append(bytes(1, 2, 3, 4, 5))
        r.append(bytes(6, 7)) // 逻辑：3,4,5,6,7
        val snap = r.snapshotTrailing(4)
        // 最近 4 字节跨边界：4,5,6,7
        assertThat(snap.ints()).containsExactly(4, 5, 6, 7).inOrder()
    }

    @Test
    fun append_larger_than_capacity_keeps_only_tail() {
        val r = PcmEchoRing(3)
        r.append(bytes(1, 2, 3, 4, 5, 6, 7))
        assertThat(r.snapshotTrailing().ints()).containsExactly(5, 6, 7).inOrder()
    }

    @Test
    fun snapshot_is_a_copy_later_writes_do_not_mutate() {
        val r = PcmEchoRing(8)
        r.append(bytes(1, 2, 3))
        val snap = r.snapshotTrailing()
        r.append(bytes(9, 9, 9, 9, 9))
        assertThat(snap.ints()).containsExactly(1, 2, 3).inOrder()
    }

    @Test
    fun clear_resets_ring() {
        val r = PcmEchoRing(4)
        r.append(bytes(1, 2))
        r.clear()
        assertThat(r.size()).isEqualTo(0)
        assertThat(r.snapshotTrailing()).isEmpty()
        // 清空后仍可正常使用
        r.append(bytes(7, 8))
        assertThat(r.snapshotTrailing().ints()).containsExactly(7, 8).inOrder()
    }
}
