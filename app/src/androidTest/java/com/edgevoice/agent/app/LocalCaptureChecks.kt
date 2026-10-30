package com.edgevoice.agent.app

import android.Manifest
import android.app.Instrumentation
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Bundle
import android.os.SystemClock
import android.view.WindowManager
import androidx.lifecycle.Lifecycle
import com.edgevoice.agent.media.audio.AndroidAudioSessionController
import com.edgevoice.agent.media.audio.AudioCaptureEngine
import com.edgevoice.agent.media.audio.AudioConfig
import com.edgevoice.agent.media.ring.NativeRingBuffer
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** Opt-in real microphone resource checks. PCM stays in memory and is immediately discarded. */
internal object LocalCaptureChecks {
    fun run(instrumentation: Instrumentation): Bundle = Checks(instrumentation).run()

    private class Failure(val reason: String) : AssertionError(reason)

    private class Checks(private val instrumentation: Instrumentation) {
        private val config = AudioConfig.REALTIME_UP
        private val result = Bundle()
        private val failure = AtomicReference<Failure?>(null)
        private var activity: MainActivity? = null
        private var stage = "capture_local_initializing"
        private var completedCycles = 0
        private var captureStarts = 0
        private var captureStops = 0
        private var ringsClosed = 0
        private var modeRestorationChecks = 0
        private var effectsReleaseChecks = 0
        private var capturedBytes = 0L
        private var discardedBytes = 0L

        fun run(): Bundle {
            result.putString("coverage", "local_real_microphone_resources")
            try {
                report("capture_local_permission")
                requireCheck(instrumentation.targetContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED, "capture_local_record_audio_must_be_pregranted")
                launchForegroundHost()
                runBlocking {
                    // stop()/awaitStopped() can join an AudioRecord worker; never run them on main.
                    withContext(Dispatchers.IO) {
                        val manager = requireNotNull(instrumentation.targetContext.getSystemService(AudioManager::class.java))
                        val baselineMode = manager.mode
                        requireCheck(captureThreads() == 0, "capture_local_capture_worker_already_running")
                        repeat(3) { cycle ->
                            checkFailure()
                            requireForegroundHost()
                            checkCycle(cycle + 1, manager, baselineMode)
                        }
                    }
                }
                checkFailure()
                result.putString("status", "PASS")
            } catch (error: Throwable) {
                result.putString("status", "FAIL")
                result.putString("failed_stage", stage)
                val controlled = generateSequence(error) { it.cause }.take(8).filterIsInstance<Failure>().firstOrNull()
                // Only fixed codes escape this suite; never platform messages, media or device IDs.
                result.putString("failure", controlled?.reason ?: failure.get()?.reason ?: "${stage}_failed")
            } finally {
                runCatching {
                    onMain { activity?.finish() }
                    instrumentation.waitForIdleSync()
                }.onFailure {
                    result.putString("status", "FAIL")
                    result.putString("cleanup_failure", "capture_local_activity_cleanup_failed")
                }
                result.putInt("resource_restart_cycles", completedCycles)
                result.putInt("capture_start_count", captureStarts)
                result.putInt("capture_stop_count", captureStops)
                result.putInt("jni_ring_close_count", ringsClosed)
                result.putInt("audio_mode_restoration_checks", modeRestorationChecks)
                result.putInt("audio_effect_release_checks", effectsReleaseChecks)
                result.putInt("capture_threads_remaining", captureThreads())
                result.putLong("captured_bytes", capturedBytes)
                result.putLong("discarded_bytes", discardedBytes)
            }
            return result
        }

        private fun launchForegroundHost() {
            report("capture_local_activity_launch")
            activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            }) as MainActivity
            onMain { requireNotNull(activity).window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
            instrumentation.waitForIdleSync()
            val until = SystemClock.elapsedRealtime() + 4_000L
            while (!isForegroundHost()) {
                requireCheck(SystemClock.elapsedRealtime() < until, "capture_local_activity_not_foreground")
                SystemClock.sleep(40L)
            }
            // The real UI is only a host. No button, permission launcher or VM session action is called.
            result.putBoolean("main_activity_foreground_host", true)
            result.putBoolean("keep_screen_on_window_flag", true)
        }

        private suspend fun checkCycle(number: Int, manager: AudioManager, baselineMode: Int) {
            report("capture_local_ring_create")
            val discard = ByteBuffer.allocateDirect(config.bytesFor(20).toInt())
            val ring = NativeRingBuffer.create(config.bytesFor(1_000).toInt())
            var session: AndroidAudioSessionController? = null
            var capture: AudioCaptureEngine? = null
            var workerStopped = false
            var cycleDiscardedBytes = 0L
            try {
                val activeSession = AndroidAudioSessionController(instrumentation.targetContext)
                session = activeSession
                val engine = AudioCaptureEngine(config, ring,
                    onError = { recordFailure("capture_local_audio_record_error") },
                )
                capture = engine
                report("capture_local_audio_session_start")
                activeSession.start { recordFailure("capture_local_audio_focus_interrupted") }
                report("capture_local_route_wait")
                try {
                    withTimeout(4_000L) { activeSession.route.first { checkFailure(); it.active } }
                } catch (_: TimeoutCancellationException) {
                    throw Failure("capture_local_route_timeout")
                }
                checkFailure()
                requireCheck(activeSession.route.value.active, "capture_local_route_not_active")
                requireForegroundHost()

                report("capture_local_audio_record_start")
                engine.start()
                captureStarts++
                val processing = engine.audioProcessing
                result.putString("cycle_${number}_aec_status", processing.echoCancellation.name)
                result.putString("cycle_${number}_ns_status", processing.noiseSuppression.name)

                report("capture_local_discard_pcm")
                val until = SystemClock.elapsedRealtime() + 300L
                // Exactly one consumer. No consumer is left running when stop() may clear the ring.
                do {
                    checkFailure()
                    requireCheck(activeSession.route.value.active, "capture_local_route_lost")
                    requireForegroundHost()
                    discard.clear()
                    val count = ring.read(discard)
                    cycleDiscardedBytes += count
                    discardedBytes += count
                    // Never interpret, serialize or retain PCM, including in assertion output.
                    wipe(discard)
                    delay(10L)
                } while (SystemClock.elapsedRealtime() < until)
                requireCheck(engine.capturedBytes > 0L, "capture_local_no_captured_bytes")
                requireCheck(cycleDiscardedBytes > 0L, "capture_local_no_ring_bytes")
            } finally {
                // There is no consumer after the loop, even on failure. Keep JNI alive until
                // awaitStopped() confirms the actual producer exited, rather than a running flag.
                withContext(NonCancellable + Dispatchers.IO) {
                    wipe(discard)
                    try {
                        val engine = capture
                        if (engine != null) {
                            stopAndAwait(engine)
                            workerStopped = true
                            captureStops++
                            val cycleCapturedBytes = engine.capturedBytes
                            capturedBytes += cycleCapturedBytes
                            result.putLong("cycle_${number}_captured_bytes", cycleCapturedBytes)
                            result.putLong("cycle_${number}_discarded_bytes", cycleDiscardedBytes)
                            requireCheck(engine.audioProcessing.audioSessionId == null,
                                "capture_local_audio_effects_not_released")
                            effectsReleaseChecks++
                        } else {
                            workerStopped = true
                        }
                    } finally {
                        try {
                            session?.stop()
                        } catch (_: Throwable) {
                            recordFailure("capture_local_audio_session_stop_failed")
                        } finally {
                            try {
                                if (session?.route?.value?.active == true) {
                                    recordFailure("capture_local_audio_route_leaked")
                                }
                                if (captureThreads() != 0) {
                                    recordFailure("capture_local_capture_worker_leaked")
                                }
                                if (manager.mode == baselineMode) {
                                    modeRestorationChecks++
                                } else {
                                    recordFailure("capture_local_audio_mode_not_restored")
                                }
                            } catch (_: Throwable) {
                                recordFailure("capture_local_resource_state_check_failed")
                            } finally {
                                // Never destroy a ring still referenced by a native SPSC producer.
                                if (workerStopped) {
                                    try {
                                        ring.close()
                                        ringsClosed++
                                    } catch (_: Throwable) {
                                        recordFailure("capture_local_ring_close_failed")
                                    }
                                } else {
                                    recordFailure("capture_local_worker_cleanup_failed")
                                }
                            }
                        }
                    }
                }
            }
            report("capture_local_resource_release")
            checkFailure()
            completedCycles++
        }

        private fun stopAndAwait(capture: AudioCaptureEngine) {
            var interrupted = false
            try {
                try {
                    capture.stop()
                } catch (_: InterruptedException) {
                    interrupted = true
                    recordFailure("capture_local_cleanup_interrupted")
                } catch (_: Throwable) {
                    recordFailure("capture_local_capture_stop_failed")
                } finally {
                    // join has no safe destruction timeout: the ring must outlive the worker.
                    while (true) {
                        try {
                            capture.awaitStopped()
                            break
                        } catch (_: InterruptedException) {
                            interrupted = true
                            recordFailure("capture_local_cleanup_interrupted")
                        }
                    }
                }
            } finally {
                if (interrupted) Thread.currentThread().interrupt()
            }
        }

        private fun wipe(buffer: ByteBuffer) {
            buffer.clear()
            while (buffer.hasRemaining()) buffer.put(0.toByte())
            buffer.clear()
        }

        private fun isForegroundHost(): Boolean = onMain {
            val host = activity
            host != null && host.lifecycle.currentState == Lifecycle.State.RESUMED && host.hasWindowFocus()
        }

        private fun requireForegroundHost() =
            requireCheck(isForegroundHost(), "capture_local_activity_lost_foreground")

        private fun captureThreads(): Int =
            Thread.getAllStackTraces().keys.count { it.isAlive && it.name == "evs-audio-capture" }

        private fun recordFailure(reason: String) { failure.compareAndSet(null, Failure(reason)) }
        private fun checkFailure() { failure.get()?.let { throw it } }
        private fun requireCheck(condition: Boolean, reason: String) { if (!condition) throw Failure(reason) }

        private fun report(next: String) {
            stage = next
            instrumentation.sendStatus(1, Bundle().apply {
                putString("stage", next)
                putInt("resource_restart_cycles", completedCycles)
            })
        }

        private fun <T> onMain(block: () -> T): T {
            val value = AtomicReference<T>()
            val thrown = AtomicReference<Throwable?>()
            instrumentation.runOnMainSync {
                try { value.set(block()) } catch (error: Throwable) { thrown.set(error) }
            }
            thrown.get()?.let { throw it }
            return value.get()
        }
    }
}
