package com.edgevoice.agent.transport.ws

import com.edgevoice.agent.transport.TransportListener
import com.edgevoice.agent.transport.TransportState
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Test
import java.nio.ByteBuffer

/**
 * 协议级 RTT 结算（不建立真实连接：仅测打点/结算对 listener 的通知语义）。
 * scope 用 Unconfined，dispatch 的 launch 在当前线程立即执行。
 */
class WebSocketTransportRttTest {

    private class RecordingListener : TransportListener {
        val rtts = mutableListOf<Long>()
        val states = mutableListOf<TransportState>()
        override fun onRttMeasured(rttMs: Long) {
            rtts.add(rttMs)
        }

        override fun onStateChanged(state: TransportState) {
            states.add(state)
        }
    }

    private fun newTransport() = WebSocketTransport(
        url = "http://127.0.0.1:1/never-connect",
        scope = CoroutineScope(Dispatchers.Unconfined),
    )

    @Test
    fun pong_after_ping_emits_non_negative_rtt() {
        val t = newTransport()
        val listener = RecordingListener()
        t.setListener(listener)

        t.markProtocolPingSent()
        t.notifyProtocolPongReceived()

        assertThat(listener.rtts).hasSize(1)
        assertThat(listener.rtts[0]).isAtLeast(0L)
        t.close()
    }

    @Test
    fun pong_without_ping_is_ignored() {
        val t = newTransport()
        val listener = RecordingListener()
        t.setListener(listener)

        t.notifyProtocolPongReceived()
        assertThat(listener.rtts).isEmpty()
        t.close()
    }

    @Test
    fun ping_is_one_shot_second_pong_without_new_ping_is_ignored() {
        val t = newTransport()
        val listener = RecordingListener()
        t.setListener(listener)

        t.markProtocolPingSent()
        t.notifyProtocolPongReceived()
        t.notifyProtocolPongReceived() // 打点已清零，不应再报
        assertThat(listener.rtts).hasSize(1)
        t.close()
    }

    @Test
    fun close_transitions_to_closed_and_notifies() {
        val t = newTransport()
        val listener = RecordingListener()
        t.setListener(listener)

        t.close()
        assertThat(t.state.value).isEqualTo(TransportState.CLOSED)
        assertThat(listener.states).contains(TransportState.CLOSED)
        assertThat(runCatching { t.connect() }.exceptionOrNull()).isInstanceOf(IllegalStateException::class.java)
    }
}
