package com.edgevoice.agent.transport.ws

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OrderedTransportCallbacksTest {
    @Test
    fun accepted_burst_preserves_order_and_close_drains_only_accepted_callbacks() = runTest {
        val callbacks = OrderedTransportCallbacks(backgroundScope, capacity = 300)
        val received = mutableListOf<Int>()
        repeat(256) { index -> callbacks.post { received.add(index) } }
        callbacks.close()
        assertThat(callbacks.post { received.add(999) }).isFalse()
        runCurrent()
        assertThat(received).containsExactlyElementsIn(0 until 256).inOrder()
    }

    @Test
    fun overload_aborts_backlog_and_reports_once_instead_of_silently_dropping() = runTest {
        val failures = mutableListOf<Throwable>()
        val received = mutableListOf<Int>()
        val callbacks = OrderedTransportCallbacks(backgroundScope, capacity = 2, onFailure = { failures.add(it) })
        callbacks.post { received.add(1) }
        callbacks.post { received.add(2) }
        callbacks.post { received.add(3) }
        assertThat(callbacks.post { received.add(4) }).isFalse()
        runCurrent()
        assertThat(failures).hasSize(1)
        assertThat(received).isEmpty()
    }

    @Test
    fun byte_budget_and_callback_exception_are_explicit_failures() = runTest {
        val failures = mutableListOf<Throwable>()
        val bytes = OrderedTransportCallbacks(backgroundScope, maxQueuedBytes = 4, onFailure = { failures.add(it) })
        assertThat(bytes.post(5) {}).isFalse()
        val throwing = OrderedTransportCallbacks(backgroundScope, onFailure = { failures.add(it) })
        throwing.post { error("listener failed") }
        runCurrent()
        assertThat(failures).hasSize(2)
    }
}
