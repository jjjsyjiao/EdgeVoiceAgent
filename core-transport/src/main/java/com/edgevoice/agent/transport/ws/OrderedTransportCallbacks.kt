package com.edgevoice.agent.transport.ws

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** 有界、单消费者回调队列。过载或监听方异常必须终止所属连接，不能悄悄丢控制事件。 */
internal class OrderedTransportCallbacks(
    scope: CoroutineScope,
    capacity: Int = 256,
    private val maxQueuedBytes: Long = 4L * 1024 * 1024,
    private val onFailure: (Throwable) -> Unit = { throw it },
) {
    private data class Callback(val bytes: Int, val block: () -> Unit)
    private val queue = Channel<Callback>(capacity)
    private val closed = AtomicBoolean(false)
    private val queuedBytes = AtomicLong(0)
    private val worker = scope.launch(start = CoroutineStart.UNDISPATCHED) {
        try {
            for (callback in queue) {
                queuedBytes.addAndGet(-callback.bytes.toLong())
                callback.block()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            fail(failure)
        }
    }

    fun post(bytes: Int = 0, callback: () -> Unit): Boolean {
        if (closed.get()) return false
        val weight = bytes.coerceAtLeast(0)
        if (queuedBytes.addAndGet(weight.toLong()) > maxQueuedBytes) {
            queuedBytes.addAndGet(-weight.toLong())
            fail(IOException("Realtime callback byte budget exceeded"))
            return false
        }
        if (queue.trySend(Callback(weight, callback)).isFailure) {
            queuedBytes.addAndGet(-weight.toLong())
            if (!closed.get()) fail(IOException("Realtime callback queue is full"))
            return false
        }
        return true
    }

    private fun fail(failure: Throwable) {
        if (!closed.compareAndSet(false, true)) return
        queue.cancel()
        worker.cancel()
        onFailure(failure)
    }

    fun close() {
        closed.set(true)
        queue.close()
    }

    fun cancel() {
        closed.set(true)
        queue.cancel()
        worker.cancel()
    }
}
