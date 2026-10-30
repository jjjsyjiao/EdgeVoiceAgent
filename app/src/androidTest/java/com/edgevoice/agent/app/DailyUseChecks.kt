package com.edgevoice.agent.app

import android.app.Instrumentation
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Debug
import android.os.SystemClock
import com.edgevoice.agent.executor.ExecutorConnectionState
import com.edgevoice.agent.executor.ExecutorEvent
import com.edgevoice.agent.executor.RealtimeSendException
import com.edgevoice.agent.executor.cloud.CloudRealtimeExecutor
import com.edgevoice.agent.media.audio.AndroidAudioSessionController
import com.edgevoice.agent.media.audio.AudioConfig
import com.edgevoice.agent.media.audio.AudioPlaybackEngine
import com.edgevoice.agent.media.audio.JitterBuffer
import com.edgevoice.agent.media.ring.NativeRingBuffer
import com.edgevoice.agent.transport.ITransportClient
import com.edgevoice.agent.transport.TransportListener
import com.edgevoice.agent.transport.TransportFailure
import com.edgevoice.agent.transport.realtime.RealtimeProfile
import com.edgevoice.agent.transport.ws.WebSocketTransport
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.sin
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Synthetic-only, opt-in device soak. This code and its assets are absent from the app APK. */
internal object DailyUseChecks {
    fun run(instrumentation: Instrumentation, arguments: Bundle): Bundle = Checks(instrumentation, arguments).run()

    private class Failure(val reason: String) : AssertionError(reason)

    /** Counts only protocol structure. No payload, transcript, URL or credentials are retained. */
    private class WireAudioDiagnostics {
        private val counts = linkedMapOf(
            "audio_delta_events" to 0L,
            "audio_delta_encoded_chars" to 0L,
            "response_audio_delta" to 0L,
            "response_output_audio_delta" to 0L,
            "response_text_delta" to 0L,
            "response_text_done" to 0L,
            "response_output_text_delta" to 0L,
            "response_output_text_done" to 0L,
            "response_audio_transcript_delta" to 0L,
            "response_audio_transcript_done" to 0L,
            "response_output_audio_transcript_delta" to 0L,
            "response_output_audio_transcript_done" to 0L,
            "response_done_completed" to 0L,
            "response_done_cancelled" to 0L,
            "response_done_incomplete" to 0L,
            "response_done_failed" to 0L,
            "response_done_other" to 0L,
            "unparseable_events" to 0L,
        )

        fun observe(text: String) {
            // Parsing is observational: a malformed event still reaches the original listener.
            val event = try { JSONObject(text) } catch (_: Exception) {
                synchronized(this) { increment("unparseable_events") }
                return
            }
            val type = event.optString("type")
            synchronized(this) {
                when (type) {
                    "response.audio.delta", "response.output_audio.delta" -> {
                        increment("audio_delta_events")
                        increment("audio_delta_encoded_chars", (event.opt("delta") as? String)?.length?.toLong() ?: 0L)
                        increment(type.replace('.', '_'))
                    }
                    "response.text.delta", "response.text.done",
                    "response.output_text.delta", "response.output_text.done",
                    "response.audio_transcript.delta", "response.audio_transcript.done",
                    "response.output_audio_transcript.delta", "response.output_audio_transcript.done" ->
                        increment(type.replace('.', '_'))
                    "response.done" -> {
                        val status = event.optJSONObject("response")?.optString("status")
                        val safeStatus = status?.takeIf { it in setOf("completed", "cancelled", "incomplete", "failed") } ?: "other"
                        increment("response_done_$safeStatus")
                    }
                }
            }
        }

        private fun increment(key: String, amount: Long = 1L) { counts[key] = counts.getValue(key) + amount }
        @Synchronized fun snapshot(): Map<String, Long> = counts.toMap()

        fun transport(url: String, headers: Map<String, String>): ITransportClient {
            val inner = WebSocketTransport(url = url, headers = headers)
            return object : ITransportClient by inner {
                override fun setListener(listener: TransportListener?) {
                    inner.setListener(listener?.let { downstream ->
                        object : TransportListener by downstream {
                            override fun onTextMessage(text: String, epoch: Long) {
                                observe(text)
                                downstream.onTextMessage(text, epoch)
                            }

                            override fun onTextMessage(text: String) {
                                observe(text)
                                downstream.onTextMessage(text)
                            }
                        }
                    })
                }
            }
        }
    }

    private class Round(
        val number: Int,
        val pcm: ByteArray,
        val lastSpeechByte: Int,
        val digit: Char?,
        val image: ByteArray?,
        val longAnswer: Boolean = false,
        val inputEpoch: Long,
        val attempt: Int,
    ) {
        val interrupted = CompletableDeferred<Unit>()
        val inputSent = CompletableDeferred<Unit>()
        val transcriptReceived = CompletableDeferred<Unit>()
        val responseDone = CompletableDeferred<Unit>()
        val playbackDone = CompletableDeferred<Unit>()
        val transcript = StringBuilder()
        val answer = StringBuilder()
        var startedAtMs = 0L
        var speechEndedAtMs = 0L
        var firstAudioAtMs = 0L
        var receivedBytes = 0L
        var responseStarts = 0
        var speechStarts = 0
        var outputWarning: String? = null
        var playbackBaseBytes = 0L
        var wireBaseline: Map<String, Long> = emptyMap()
        @Volatile var generation = -1L
        @Volatile var playbackCompletedAtMs = 0L
    }

    private class Recovery(val fromEpoch: Long, val startedAtMs: Long, val planned: Boolean, val originStage: String) {
        val ready = CompletableDeferred<Unit>()
        var playbackStopped = false
    }

    private class Checks(private val instrumentation: Instrumentation, private val args: Bundle) {
        private val config = AudioConfig.REALTIME_UP
        private val result = Bundle()
        private var stage = "daily_initializing"
        private val failure = AtomicReference<Failure?>(null)
        private val currentRound = AtomicReference<Round?>(null)
        private val wireAudioDiagnostics = WireAudioDiagnostics()
        private val requests = Channel<Round>(1)
        private val completeLatencies = mutableListOf<Long>()
        private val firstAudioLatencies = mutableListOf<Long>()
        private var sentAudioBytes = 0L
        private var sentImages = 0L
        private var receivedAudioBytes = 0L
        private var completedRounds = 0
        private var completedVisionRounds = 0
        private var completedLongRounds = 0
        private var reconnects = 0
        private var maxJitterBytes = 0L
        private var maxCallbackBytes = 0L
        private var maxEventBytes = 0L
        private var maxOutgoingBytes = 0L
        private var maxCombinedBytes = 0L
        private var maxHeapBytes = 0L
        private var maxPssKb = 0L
        private var lastPssKb = 0L
        private var baselineHeapBytes = 0L
        private var baselinePssKb = 0L
        private var soakStartedAtMs = 0L
        private var memorySamples = 0
        private var recoveryWindow = false
        private var manualRecoveryStarted = false
        private var activeRecovery: Recovery? = null
        private var lastReadyEpoch = -1L
        private var naturalReconnectEvents = 0
        private var plannedReconnectEvents = 0
        private var naturalRecoveryWindows = 0
        private var naturalRecoveryCount = 0
        private var reaskedQuestions = 0
        private var interruptedAttempts = 0
        private var recoverableErrors = 0
        private var recoverableSendErrors = 0
        private var lastRecoverableErrorClass: String? = null
        private var lastRecoverableStatusCode: Int? = null
        private var lastConnectionState = "NOT_STARTED"
        private var playedBytesInCompletedRuns = 0L
        private var playbackCounterCarried = false
        private var disconnected = CompletableDeferred<Unit>()
        private var recovered = CompletableDeferred<Unit>()

        private fun requireCheck(condition: Boolean, reason: String) {
            if (!condition) throw Failure(reason)
        }

        private fun checkFailure() { failure.get()?.let { throw it } }

        private fun report(next: String, operation: String? = null) {
            stage = next
            instrumentation.sendStatus(1, snapshot().apply {
                putString("stage", next)
                operation?.let { putString("operator_action", it) }
            })
        }

        fun run(): Bundle {
            try {
                runBlocking {
                    report("daily_local_resources")
                    withTimeout(120_000L) { checkLocalResources() }
                    if (args.getString("local_only").equals("true", ignoreCase = true)) {
                        result.putString("coverage", "local_only")
                        result.putString("summary", "20 audio-session/AudioTrack restarts and JNI/short-tail checks passed; cloud soak was not run")
                    } else {
                        val duration = argumentInt("duration_seconds", 1_800, 180..7_200)
                        val rounds = argumentInt("rounds", 20, 20..100)
                        val visionEvery = argumentInt("vision_every", 4, 2..5)
                        result.putInt("duration_seconds_requested", duration)
                        result.putInt("rounds_requested", rounds)
                        result.putString("coverage", "daily_cloud_soak")
                        withTimeout((duration + 180L) * 1_000L) { cloudSoak(duration, rounds, visionEvery) }
                        result.putString("summary", "Synthetic daily soak completed with ASR/text/streamed audio and changing digit cards; no microphone/camera capture")
                    }
                }
                result.putString("status", "PASS")
            } catch (error: Throwable) {
                result.putString("status", "FAIL")
                result.putString("failed_stage", stage)
                // Never return backend messages, URLs, credentials or raw media.
                val controlledFailure = generateSequence(error) { it.cause }.take(8).filterIsInstance<Failure>().firstOrNull()
                result.putString("failure", (controlledFailure?.reason ?: error.javaClass.simpleName).take(180))
            }
            result.putAll(snapshot())
            return result
        }

        private fun argumentInt(name: String, default: Int, range: IntRange): Int {
            val text = args.getString(name) ?: return default
            val number = text.toIntOrNull() ?: throw Failure("invalid_argument_$name")
            requireCheck(number in range, "argument_out_of_range_$name")
            return number
        }

        private suspend fun checkLocalResources() {
            val manager = instrumentation.targetContext.getSystemService(AudioManager::class.java)
            val baselineMode = manager.mode
            val initialPlaybackThreads = playbackThreads()
            val initialFdCount = File("/proc/self/fd").list()?.size
            val tone = tone37ms()
            repeat(20) { cycle ->
                checkFailure()
                checkNativeRoundTrip()
                val audioSession = AndroidAudioSessionController(instrumentation.targetContext)
                val jitter = JitterBuffer(config)
                val done = CompletableDeferred<Long>()
                val playback = AudioPlaybackEngine(config, jitter,
                    onPlaybackComplete = { done.complete(it) },
                    onError = {
                        val error = Failure("local_audio_track_error")
                        failure.compareAndSet(null, error)
                        done.completeExceptionally(error)
                    },
                )
                try {
                    audioSession.start {
                        val error = Failure("local_audio_focus_interrupted")
                        failure.compareAndSet(null, error)
                        done.completeExceptionally(error)
                    }
                    withTimeout(4_000L) { audioSession.route.first { checkFailure(); it.active } }
                    checkFailure()
                    requireCheck(audioSession.route.value.active, "local_audio_route_not_active")
                    playback.start()
                    val generation = playback.beginResponse()
                    requireCheck(jitter.push(ByteBuffer.wrap(tone)) == tone.size, "local_short_audio_not_queued")
                    playback.finishResponse()
                    requireCheck(withTimeout(8_000L) { done.await() } == generation, "local_playback_generation_mismatch")
                    requireCheck(playback.playedBytes >= tone.size, "local_short_audio_truncated")
                    requireCheck(jitter.availableBytes == 0L, "local_short_tail_retained")
                } finally {
                    withContext(NonCancellable + Dispatchers.IO) {
                        try {
                            try { playback.stop() } finally { playback.awaitStopped() }
                        } finally {
                            try { jitter.close() } finally { audioSession.stop() }
                        }
                    }
                }
                delay(100L)
                checkFailure()
                requireCheck(!audioSession.route.value.active, "local_audio_route_leaked")
                requireCheck(manager.mode == baselineMode, "local_audio_mode_not_restored")
                requireCheck(playbackThreads() <= initialPlaybackThreads, "local_playback_thread_leaked")
                result.putInt("resource_restart_cycles", cycle + 1)
            }
            val finalFdCount = File("/proc/self/fd").list()?.size
            if (initialFdCount != null && finalFdCount != null) {
                result.putInt("resource_fd_delta", finalFdCount - initialFdCount)
                // Platform binder/audio caches can retain a few descriptors after warmup.
                requireCheck(finalFdCount - initialFdCount <= 12, "local_file_descriptors_grew_after_restarts")
            }
            result.putInt("resource_playback_threads_remaining", playbackThreads())
            sampleMemory(enforceBudget = false)
        }

        private fun playbackThreads(): Int = Thread.getAllStackTraces().keys.count { it.isAlive && it.name == "evs-audio-playback" }

        private fun checkNativeRoundTrip() {
            val expected = ByteArray(777) { ((it * 73 + 19) and 255).toByte() }
            NativeRingBuffer.create(2_048).use { ring ->
                val input = ByteBuffer.allocateDirect(expected.size)
                input.put(expected)
                input.flip()
                requireCheck(ring.write(input) == expected.size, "daily_jni_write_failed")
                val output = ByteBuffer.allocateDirect(expected.size)
                requireCheck(ring.read(output) == expected.size, "daily_jni_read_failed")
                val actual = ByteArray(expected.size)
                output.get(actual)
                requireCheck(actual.contentEquals(expected) && ring.availableToRead() == 0, "daily_jni_content_mismatch")
            }
        }

        private suspend fun cloudSoak(durationSeconds: Int, rounds: Int, visionEvery: Int) = coroutineScope {
            val initialPlaybackThreads = playbackThreads()
            report("daily_cloud_assets")
            val profile = RealtimeProfile.fromId(BuildConfig.REALTIME_PROVIDER)
            requireCheck(profile.supportsVideoInput, "daily_provider_has_no_vision")
            requireCheck(BuildConfig.OPENAI_API_KEY.isNotBlank(), "daily_credentials_missing")
            val questionAudio = asset("question_audio.pcm")
            val questionVision = asset("question_vision.pcm")
            val questionLong = asset("question_long.pcm")
            requireCheck(rounds / visionEvery >= 5, "daily_requires_at_least_5_vision_rounds")
            val longRoundNumbers = mutableSetOf<Int>()
            for (preferred in listOf(6, 14)) {
                val number = (preferred..rounds).firstOrNull {
                    it % visionEvery != 0 && it !in longRoundNumbers
                } ?: throw Failure("daily_missing_nonvision_long_round_slot")
                longRoundNumbers.add(number)
            }
            val seven = card('7')
            val three = card('3')
            val executor = CloudRealtimeExecutor(
                BuildConfig.OPENAI_REALTIME_URL, BuildConfig.OPENAI_API_KEY, BuildConfig.OPENAI_REALTIME_MODEL,
                profile = profile,
                instructions = "你是实时语音应用的自动化验收助手。严格按照当前语音问题回答，不执行其他任务。" +
                    "询问画面中的数字时，以最近收到的画面为准，简短回答当前数字，不提及其他数字。" +
                    "算术问题用一句简短完整的中文回答。只有用户明确要求数数时才数数，并完整说出要求范围内的每个数字。",
                // Delegates every transport operation; only the session-configuration RTT cast
                // is unavailable through this test wrapper. Native WS heartbeat is unchanged.
                transportFactory = wireAudioDiagnostics::transport,
            )
            val jitter = JitterBuffer(config)
            val audioSession = AndroidAudioSessionController(instrumentation.targetContext)
            val playback = AudioPlaybackEngine(config, jitter,
                onPlaybackComplete = { generation ->
                    currentRound.get()?.let {
                        if (!it.interrupted.isCompleted && it.generation == generation) {
                            it.playbackCompletedAtMs = SystemClock.elapsedRealtime()
                            it.playbackDone.complete(Unit)
                        }
                    }
                },
                onError = { failure.compareAndSet(null, Failure("daily_audio_track_error")) },
            )
            val subscriber = launch(start = CoroutineStart.UNDISPATCHED) {
                executor.events.collect { event ->
                    checkFailure()
                    // Recovery invalidates the old attempt immediately. Late response markers may
                    // still be delivered before the executor's new epoch; none can complete it.
                    if (activeRecovery != null && event !is ExecutorEvent.Error &&
                        event !is ExecutorEvent.ConnectionChanged && event != ExecutorEvent.Ready) return@collect
                    when (event) {
                        is ExecutorEvent.Error -> {
                            if (!event.recoverable) throw Failure("daily_cloud_error:${safeErrorClass(event.cause)}")
                            recordRecoverableError(event.cause)
                            beginRecovery(playback)
                        }
                        is ExecutorEvent.ConnectionChanged -> {
                            lastConnectionState = event.state.name
                            if (event.state == ExecutorConnectionState.RECONNECTING) {
                                val recovery = beginRecovery(playback)
                                reconnects++
                                if (recovery.planned) {
                                    plannedReconnectEvents++
                                    if (recoveryWindow) disconnected.complete(Unit)
                                } else naturalReconnectEvents++
                            }
                            if (event.state == ExecutorConnectionState.READY) observeReady(executor, playback)
                            if (event.state == ExecutorConnectionState.FAILED || event.state == ExecutorConnectionState.CLOSED) {
                                throw Failure("daily_connection_terminated")
                            }
                        }
                        ExecutorEvent.Ready -> observeReady(executor, playback)
                        is ExecutorEvent.ResponseStarted -> {
                            val round = currentRound.get() ?: throw Failure("daily_unsolicited_response")
                            round.responseStarts++
                            requireCheck(round.responseStarts == 1, "daily_multiple_responses_for_one_question")
                            round.generation = playback.beginResponse()
                        }
                        ExecutorEvent.UserSpeechStarted -> {
                            val round = currentRound.get() ?: throw Failure("daily_unsolicited_speech")
                            round.speechStarts++
                            // Reject split synthetic questions rather than mixing cancelled answers.
                            requireCheck(round.receivedBytes == 0L, "daily_question_split_by_vad_after_answer_started")
                        }
                        is ExecutorEvent.UserTranscript -> {
                            val round = currentRound.get() ?: throw Failure("daily_unsolicited_transcript")
                            requireCheck(event.text.isNotBlank(), "daily_empty_asr")
                            round.transcript.append(event.text)
                            requireCheck(round.transcript.length <= 4_096, "daily_asr_too_large")
                            round.transcriptReceived.complete(Unit)
                        }
                        is ExecutorEvent.TextDelta -> {
                            val round = currentRound.get() ?: throw Failure("daily_unsolicited_text")
                            round.answer.append(event.text)
                            requireCheck(round.answer.length <= 4_096, "daily_response_too_large")
                        }
                        is ExecutorEvent.AssistantTextFinal -> {
                            val round = currentRound.get() ?: throw Failure("daily_unsolicited_text")
                            requireCheck(event.text.length <= 4_096, "daily_response_too_large")
                            round.answer.clear()
                            round.answer.append(event.text)
                        }
                        is ExecutorEvent.ResponseIncomplete -> {
                            val round = currentRound.get() ?: throw Failure("daily_unsolicited_output_warning")
                            round.outputWarning = event.reason.name
                            // Missing audio / incomplete output is a content failure, never a
                            // reason to spend the disconnection reask budget.
                            throw Failure("daily_incomplete_response_${event.reason.name.lowercase()}")
                        }
                        is ExecutorEvent.AudioDelta -> {
                            val round = currentRound.get() ?: throw Failure("daily_unsolicited_audio")
                            if (round.generation < 0L) round.generation = playback.beginResponse()
                            if (round.firstAudioAtMs == 0L) round.firstAudioAtMs = SystemClock.elapsedRealtime()
                            requireCheck(round.receivedBytes + event.pcm.size <= config.bytesFor(JitterBuffer.DEFAULT_CAPACITY_MS), "daily_response_audio_exceeds_turn_budget")
                            requireCheck(jitter.push(ByteBuffer.wrap(event.pcm)) == event.pcm.size, "daily_audio_queue_rejected_speech")
                            round.receivedBytes += event.pcm.size
                            receivedAudioBytes += event.pcm.size
                            maxJitterBytes = max(maxJitterBytes, jitter.availableBytes)
                        }
                        ExecutorEvent.TurnComplete -> {
                            val round = currentRound.get() ?: throw Failure("daily_unsolicited_completion")
                            validateRoundContent(round)
                            playback.finishResponse()
                            round.responseDone.complete(Unit)
                        }
                        else -> Unit
                    }
                    sampleQueues(executor, jitter)
                }
            }
            var input: Job? = null
            var monitor: Job? = null
            try {
                report("daily_cloud_prepare")
                withTimeout(25_000L) { executor.prepare() }
                requireCheck(executor.ready.value, "daily_cloud_not_ready")
                lastReadyEpoch = executor.inputEpoch
                lastConnectionState = ExecutorConnectionState.READY.name
                audioSession.start { failure.compareAndSet(null, Failure("daily_audio_focus_interrupted")) }
                withTimeout(4_000L) { audioSession.route.first { checkFailure(); it.active } }
                checkFailure()
                requireCheck(audioSession.route.value.active, "daily_communication_route_not_active")
                result.putString("audio_route_kind", audioSession.route.value.kind.name)
                playback.start()
                sampleMemory(enforceBudget = false)
                baselineHeapBytes = usedHeap()
                baselinePssKb = currentPssKb()
                soakStartedAtMs = SystemClock.elapsedRealtime()
                val deadline = soakStartedAtMs + durationSeconds * 1_000L
                input = launch { streamInput(executor, jitter, playback) }
                monitor = launch {
                    while (isActive) {
                        delay(45_000L)
                        checkFailure()
                        sampleMemory(enforceBudget = true)
                        sampleQueues(executor, jitter)
                        instrumentation.sendStatus(0, snapshot().apply { putString("stage", stage) })
                    }
                }
                report("daily_soak_running")
                var immediateNext = false
                repeat(rounds) { index ->
                    // Leave 45 seconds for the final response and observe the remaining idle stream.
                    val target = soakStartedAtMs + index * (durationSeconds * 1_000L - 45_000L) / (rounds - 1)
                    if (!immediateNext) waitUntil(target) { awaitHealthy(executor, playback) }
                    immediateNext = false
                    awaitHealthy(executor, playback)
                    checkFailure()
                    val vision = (index + 1) % visionEvery == 0
                    val longAnswer = index + 1 in longRoundNumbers
                    val visionIndex = (index + 1) / visionEvery
                    val digit = if (!vision) null else if (visionIndex % 2 == 1) '7' else '3'
                    val pcm = when {
                        vision -> questionVision
                        longAnswer -> questionLong
                        else -> questionAudio
                    }
                    var attempt = 0
                    var round: Round
                    while (true) {
                        awaitHealthy(executor, playback)
                        round = Round(index + 1, pcm, lastSpeechByte(pcm), digit,
                            when (digit) { '7' -> seven; '3' -> three; else -> null }, longAnswer,
                            inputEpoch = executor.inputEpoch, attempt = attempt)
                        // awaitHealthy has joined the old playback worker and restarted it, so
                        // this baseline cannot include a late write from the interrupted attempt.
                        round.playbackBaseBytes = playback.playedBytes
                        round.wireBaseline = wireAudioDiagnostics.snapshot()
                        requireCheck(currentRound.compareAndSet(null, round), "daily_round_overlap")
                        report(if (attempt == 0) "daily_round_${index + 1}" else "daily_round_${index + 1}_reasked")
                        requests.send(round)
                        val finished = withTimeout(100_000L) {
                            awaitStep(round, round.inputSent) && awaitStep(round, round.transcriptReceived) &&
                                awaitStep(round, round.responseDone) && awaitStep(round, round.playbackDone)
                        }
                        if (!executor.ready.value || executor.inputEpoch != round.inputEpoch) beginRecovery(playback, round.inputEpoch)
                        // A fully returned but wrong/zero-audio answer must not be hidden by
                        // a later network loss while its playback is still being drained.
                        if (round.responseDone.isCompleted || round.outputWarning != null) validateRoundContent(round)
                        if (finished && !round.interrupted.isCompleted) break
                        requireCheck(currentRound.compareAndSet(round, null), "daily_interrupted_round_state_changed")
                        interruptedAttempts++
                        report("daily_round_${index + 1}_interrupted")
                        requireCheck(attempt == 0 && reaskedQuestions < 2, "daily_question_reask_budget_exhausted")
                        awaitHealthy(executor, playback)
                        attempt++
                        reaskedQuestions++
                    }
                    checkFailure()
                    validateRoundContent(round)
                    requireCheck(jitter.availableBytes == 0L, "daily_audio_tail_not_drained")
                    val played = playback.playedBytes - round.playbackBaseBytes
                    requireCheck(played >= round.receivedBytes, "daily_audio_bytes_truncated")
                    if (Build.VERSION.SDK_INT >= 31) requireCheck(played == round.receivedBytes, "daily_unexpected_audio_padding")
                    if (digit != null) completedVisionRounds++
                    else if (round.longAnswer) completedLongRounds++
                    requireCheck(round.firstAudioAtMs >= round.speechEndedAtMs && round.speechEndedAtMs > 0, "daily_invalid_first_audio_timing")
                    firstAudioLatencies.add(round.firstAudioAtMs - round.speechEndedAtMs)
                    requireCheck(round.playbackCompletedAtMs >= round.speechEndedAtMs, "daily_invalid_playback_completion_timing")
                    completeLatencies.add(round.playbackCompletedAtMs - round.speechEndedAtMs)
                    completedRounds++
                    result.putLong("played_bytes", totalPlayedBytes(playback))
                    instrumentation.sendStatus(0, snapshot().apply { putString("stage", "daily_round_${index + 1}_completed") })
                    requireCheck(currentRound.compareAndSet(round, null), "daily_round_state_changed")
                    stage = "daily_between_rounds"
                    if (index == 2 && args.getString("recovery_test").equals("true", ignoreCase = true)) {
                        manualRecovery(executor, playback)
                        immediateNext = true
                    }
                }
                report("daily_soak_final_idle")
                waitUntil(deadline) { awaitHealthy(executor, playback) }
                awaitHealthy(executor, playback)
                checkFailure()
                sampleMemory(enforceBudget = true)
                sampleQueues(executor, jitter)
                requireCheck(completedRounds == rounds && completedVisionRounds >= 5 && completedLongRounds == 2, "daily_required_rounds_missing")
                requireCheck(executor.ready.value && audioSession.route.value.active, "daily_session_not_alive_at_end")
                requireCheck(SystemClock.elapsedRealtime() - soakStartedAtMs >= durationSeconds * 1_000L, "daily_duration_not_reached")
                result.putLong("played_bytes", totalPlayedBytes(playback))
                result.putBoolean("session_ready_at_end", true)
                result.putString("terminal_connection_status", ExecutorConnectionState.READY.name)
            } finally {
                withContext(NonCancellable) {
                    input?.cancelAndJoin()
                    monitor?.cancelAndJoin()
                    subscriber.cancelAndJoin()
                    if (!result.containsKey("terminal_connection_status")) result.putString("terminal_connection_status", lastConnectionState)
                    result.putLong("played_bytes", totalPlayedBytes(playback))
                    try {
                        executor.shutdown()
                    } finally {
                        withContext(Dispatchers.IO) {
                            try {
                                try { playback.stop() } finally { playback.awaitStopped() }
                            } finally {
                                try { jitter.close() } finally {
                                    try { audioSession.stop() } finally { requests.close() }
                                }
                            }
                        }
                    }
                }
            }
            checkFailure()
            val remainingPlaybackThreads = playbackThreads()
            result.putInt("cloud_playback_threads_remaining", remainingPlaybackThreads)
            requireCheck(remainingPlaybackThreads <= initialPlaybackThreads, "daily_playback_thread_leaked")
            requireCheck(!audioSession.route.value.active, "daily_audio_route_leaked")
        }

        private fun validateRoundContent(round: Round) {
            requireCheck(round.outputWarning == null,
                "daily_incomplete_response_${round.outputWarning?.lowercase()}")
            requireCheck(round.answer.isNotBlank() && round.receivedBytes > 0, "daily_missing_text_or_audio")
            val digit = round.digit
            if (digit != null) {
                val numerals = answerNumerals(round.answer.toString())
                val expected = setOf(digit.toString(), if (digit == '7') "七" else "三")
                requireCheck(numerals.isNotEmpty() && numerals.all { it in expected },
                    "daily_vision_answer_incorrect_or_contains_other_numerals")
            } else if (round.longAnswer) {
                requireCheck(hasCompleteCountToTwenty(round.answer.toString()), "daily_long_answer_missing_ordered_numbers_1_to_20")
                requireCheck(round.receivedBytes > config.bytesFor(3_000), "daily_long_answer_audio_not_longer_than_3_seconds")
            } else {
                requireCheck(round.answer.any { it == '2' || it == '二' || it == '两' }, "daily_voice_answer_did_not_recognize_2")
            }
        }

        private fun totalPlayedBytes(playback: AudioPlaybackEngine): Long =
            playedBytesInCompletedRuns + if (playbackCounterCarried) 0L else playback.playedBytes

        private fun safeErrorClass(cause: Throwable): String = cause.javaClass.simpleName
            .takeIf { it.matches(Regex("[A-Za-z][A-Za-z0-9_]{0,79}")) } ?: "OtherThrowable"

        private fun recordRecoverableError(cause: Throwable) {
            recoverableErrors++
            lastRecoverableErrorClass = safeErrorClass(cause)
            // Only the class name and numeric transport status are diagnostic output.
            lastRecoverableStatusCode = generateSequence(cause) { it.cause }.take(8)
                .filterIsInstance<TransportFailure>().firstOrNull()?.statusCode
        }

        private fun beginRecovery(playback: AudioPlaybackEngine, fromEpoch: Long = currentRound.get()?.inputEpoch ?: lastReadyEpoch): Recovery {
            activeRecovery?.let { return it }
            val recovery = Recovery(fromEpoch, SystemClock.elapsedRealtime(), recoveryWindow, stage)
            activeRecovery = recovery
            lastConnectionState = ExecutorConnectionState.RECONNECTING.name
            if (!recovery.planned) naturalRecoveryWindows++
            currentRound.get()?.interrupted?.complete(Unit)
            while (requests.tryReceive().getOrNull() != null) { /* Discard queued old questions. */ }
            playback.interrupt() // Invalidates completion and requests the playback queue flush.
            report(if (recovery.planned) "daily_planned_recovery_wait_ready" else "daily_natural_recovery_wait_ready")
            return recovery
        }

        private fun observeReady(executor: CloudRealtimeExecutor, playback: AudioPlaybackEngine) {
            if (!executor.ready.value) return
            // A very fast reconnect can finish between two 20 ms input ticks.
            // Preserve the old attempt's epoch before accepting that new READY.
            if (activeRecovery == null && lastReadyEpoch >= 0L && executor.inputEpoch != lastReadyEpoch) beginRecovery(playback)
            lastConnectionState = ExecutorConnectionState.READY.name
            val recovery = activeRecovery
            if (recovery != null) {
                // An old READY marker cannot close a window opened for the next epoch.
                if (executor.inputEpoch == recovery.fromEpoch) return
                recovery.ready.complete(Unit)
                if (recovery.planned && recoveryWindow && disconnected.isCompleted) recovered.complete(Unit)
            } else lastReadyEpoch = executor.inputEpoch
        }

        private suspend fun awaitHealthy(executor: CloudRealtimeExecutor, playback: AudioPlaybackEngine) {
            checkFailure()
            if (activeRecovery == null && (!executor.ready.value || executor.inputEpoch != lastReadyEpoch)) beginRecovery(playback)
            while (true) {
                val recovery = activeRecovery ?: return
                // Accept an already-consumed new READY using the executor's authoritative
                // protocol-ready state; waiting only for another event would deadlock.
                observeReady(executor, playback)
                // The same recovery window owns one deadline, including repeated reconnect events.
                val remaining = recovery.startedAtMs + 70_000L - SystemClock.elapsedRealtime()
                requireCheck(remaining > 0L, "daily_recovery_ready_timeout")
                withTimeout(remaining) {
                    if (!recovery.playbackStopped) {
                        withContext(Dispatchers.IO) {
                            playback.stop()
                            playback.awaitStopped()
                        }
                        playedBytesInCompletedRuns += playback.playedBytes
                        playbackCounterCarried = true
                        recovery.playbackStopped = true
                    }
                    recovery.ready.await()
                }
                checkFailure()
                // A second loss while the first READY is being consumed stays in this window.
                if (!executor.ready.value || executor.inputEpoch == recovery.fromEpoch) {
                    delay(20L)
                    continue
                }
                playback.start()
                playbackCounterCarried = false
                lastReadyEpoch = executor.inputEpoch
                if (!recovery.planned) naturalRecoveryCount++
                activeRecovery = null
                report(if (recovery.planned) "daily_planned_recovery_ready" else "daily_natural_recovery_ready")
                return
            }
        }

        private suspend fun awaitStep(round: Round, completion: CompletableDeferred<Unit>): Boolean = select {
            // Interruption wins when both signals are already complete.
            round.interrupted.onAwait { false }
            completion.onAwait { true }
        }

        private suspend fun streamInput(executor: CloudRealtimeExecutor, jitter: JitterBuffer, playback: AudioPlaybackEngine) {
            val bytesPerFrame = config.bytesFor(20).toInt()
            val silence = ByteArray(bytesPerFrame)
            var active: Round? = null
            var offset = 0
            var sentFrame = 0L
            var nextAtMs = SystemClock.elapsedRealtime()
            while (true) {
                checkFailure()
                if (!executor.ready.value || activeRecovery != null || executor.inputEpoch != lastReadyEpoch) {
                    if (activeRecovery == null) beginRecovery(playback)
                    // Never retain a partial synthetic question across a new connection epoch.
                    active = null
                    offset = 0
                    delay(20L)
                    nextAtMs = SystemClock.elapsedRealtime()
                    continue
                }
                if (active == null) {
                    active = requests.tryReceive().getOrNull()
                    offset = 0
                    active?.startedAtMs = SystemClock.elapsedRealtime()
                }
                val question = active
                if (question != null && (question.interrupted.isCompleted || executor.inputEpoch != question.inputEpoch)) {
                    beginRecovery(playback, question.inputEpoch)
                    active = null
                    offset = 0
                    continue
                }
                val frameEpoch = question?.inputEpoch ?: executor.inputEpoch
                val count = if (question == null) bytesPerFrame else minOf(bytesPerFrame, question.pcm.size - offset)
                val audio = if (question == null) ByteBuffer.wrap(silence) else ByteBuffer.wrap(question.pcm, offset, count)
                try {
                    executor.sendAudioFrame(audio, frameEpoch)
                    sentAudioBytes += count
                    if (question?.image != null && offset % (bytesPerFrame * 50) == 0) {
                        executor.sendVideoFrame(question.image, frameEpoch)
                        sentImages++
                    }
                } catch (sendFailure: RealtimeSendException) {
                    if (!sendFailure.recoverable) throw Failure("daily_unrecoverable_send_error:${safeErrorClass(sendFailure)}")
                    recoverableSendErrors++
                    recordRecoverableError(sendFailure)
                    beginRecovery(playback, frameEpoch)
                    active = null
                    offset = 0
                    delay(20L)
                    nextAtMs = SystemClock.elapsedRealtime()
                    continue
                }
                if (question != null) {
                    offset += count
                    if (question.speechEndedAtMs == 0L && offset >= question.lastSpeechByte) {
                        question.speechEndedAtMs = SystemClock.elapsedRealtime()
                    }
                    if (offset >= question.pcm.size) {
                        question.inputSent.complete(Unit)
                        active = null
                    }
                }
                sentFrame++
                if (sentFrame % 50L == 0L) sampleQueues(executor, jitter)
                nextAtMs += 20L
                // A delayed scheduler must not catch up by bursting stale audio into the socket.
                nextAtMs = max(nextAtMs, SystemClock.elapsedRealtime())
                delay((nextAtMs - SystemClock.elapsedRealtime()).coerceAtLeast(1L))
            }
        }

        private suspend fun manualRecovery(executor: CloudRealtimeExecutor, playback: AudioPlaybackEngine) {
            // Settle an earlier natural loss before opening the operator's distinct window.
            awaitHealthy(executor, playback)
            requireCheck(!manualRecoveryStarted && activeRecovery == null && executor.ready.value, "daily_manual_recovery_window_overlap")
            manualRecoveryStarted = true
            disconnected = CompletableDeferred()
            recovered = CompletableDeferred()
            recoveryWindow = true
            try {
                report("daily_recovery_disable_network", "Disable the active network now; do not restore until the next stage")
                withTimeout(70_000L) { disconnected.await() }
                report("daily_recovery_restore_network", "Restore the network now; this test never changes system networking")
                awaitHealthy(executor, playback)
                requireCheck(recovered.isCompleted && executor.ready.value, "daily_recovery_missing_ready")
                result.putBoolean("manual_recovery_passed", true)
            } finally {
                recoveryWindow = false
            }
        }

        private suspend fun waitUntil(target: Long, whileWaiting: suspend () -> Unit = {}) {
            while (SystemClock.elapsedRealtime() < target) {
                checkFailure()
                whileWaiting()
                val remaining = target - SystemClock.elapsedRealtime()
                if (remaining > 0L) delay(minOf(500L, remaining))
            }
        }

        private fun sampleQueues(executor: CloudRealtimeExecutor, jitter: JitterBuffer) {
            val queue = executor.queueStats()
            requireCheck(queue.callbackBytes >= 0L && queue.eventBytes >= 0L && queue.outgoingBytes >= 0L, "daily_negative_queue_accounting")
            maxCallbackBytes = max(maxCallbackBytes, queue.callbackBytes)
            maxEventBytes = max(maxEventBytes, queue.eventBytes)
            maxOutgoingBytes = max(maxOutgoingBytes, queue.outgoingBytes)
            maxJitterBytes = max(maxJitterBytes, jitter.availableBytes)
            maxCombinedBytes = max(maxCombinedBytes, queue.callbackBytes + queue.eventBytes + queue.outgoingBytes + jitter.availableBytes)
            result.putLong("connection_epoch", queue.connectionEpoch)
        }

        private fun usedHeap(): Long = Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }
        private fun currentPssKb(): Long = Debug.MemoryInfo().let { Debug.getMemoryInfo(it); it.totalPss.toLong() }

        private fun sampleMemory(enforceBudget: Boolean) {
            val heap = usedHeap()
            val pss = currentPssKb()
            lastPssKb = pss
            maxHeapBytes = max(maxHeapBytes, heap)
            maxPssKb = max(maxPssKb, pss)
            memorySamples++
            if (enforceBudget) {
                requireCheck(heap - baselineHeapBytes <= 64L * 1_024 * 1_024, "daily_java_heap_growth_exceeded_64mb")
                requireCheck(pss - baselinePssKb <= 128L * 1_024, "daily_pss_growth_exceeded_128mb")
            }
        }

        private fun snapshot() = Bundle().apply {
            val heap = usedHeap()
            putLong("elapsed_seconds", if (soakStartedAtMs == 0L) 0L else (SystemClock.elapsedRealtime() - soakStartedAtMs) / 1_000L)
            putInt("rounds_completed", completedRounds)
            putInt("vision_rounds_completed", completedVisionRounds)
            putInt("long_rounds_completed", completedLongRounds)
            currentRound.get()?.let { round ->
                putInt("active_round_number", round.number)
                putInt("active_round_attempt", round.attempt + 1)
                putBoolean("active_round_interrupted", round.interrupted.isCompleted)
                putInt("active_round_response_starts", round.responseStarts)
                putInt("active_round_speech_starts", round.speechStarts)
                round.outputWarning?.let { putString("active_round_output_warning", it) }
                putInt("active_round_asr_chars", round.transcript.length)
                putInt("active_round_answer_chars", round.answer.length)
                putInt("active_round_received_audio_ms", config.millisFor(round.receivedBytes))
                putBoolean("active_round_is_count_to_twenty", hasCompleteCountToTwenty(round.answer.toString()))
                // Only a whitelist of numeral tokens escapes; no answer prose is logged.
                putString("active_round_answer_numerals", answerNumerals(round.answer.toString()).joinToString(",").take(128))
                val wireNow = wireAudioDiagnostics.snapshot()
                putLong("active_round_wire_baseline_audio_events", round.wireBaseline["audio_delta_events"] ?: 0L)
                putLong("active_round_wire_baseline_encoded_chars", round.wireBaseline["audio_delta_encoded_chars"] ?: 0L)
                wireNow.forEach { (name, count) ->
                    putLong("active_round_wire_$name", count - (round.wireBaseline[name] ?: 0L))
                }
            }
            putLong("sent_audio_bytes", sentAudioBytes)
            putLong("sent_image_frames", sentImages)
            putLong("received_audio_bytes", receivedAudioBytes)
            putInt("observed_reconnect_events", reconnects)
            putInt("natural_reconnect_events", naturalReconnectEvents)
            putInt("planned_reconnect_events", plannedReconnectEvents)
            putInt("natural_recovery_windows", naturalRecoveryWindows)
            putInt("natural_recovery_count", naturalRecoveryCount)
            putInt("interrupted_attempts", interruptedAttempts)
            putInt("reasked_questions", reaskedQuestions)
            putInt("recoverable_error_count", recoverableErrors)
            putInt("recoverable_send_error_count", recoverableSendErrors)
            lastRecoverableErrorClass?.let { putString("last_recoverable_error_class", it) }
            lastRecoverableStatusCode?.let { putInt("last_recoverable_transport_status_code", it) }
            putString("connection_status", lastConnectionState)
            activeRecovery?.let {
                putString("recovery_kind", if (it.planned) "planned" else "natural")
                putString("recovery_origin_stage", it.originStage)
                putLong("recovery_elapsed_ms", SystemClock.elapsedRealtime() - it.startedAtMs)
            }
            putLong("sampled_max_callback_bytes", maxCallbackBytes)
            putLong("sampled_max_event_bytes", maxEventBytes)
            putLong("sampled_max_outgoing_bytes", maxOutgoingBytes)
            putLong("max_playback_queue_bytes", maxJitterBytes)
            putLong("sampled_max_total_queue_bytes", maxCombinedBytes)
            putLong("baseline_java_heap_bytes", baselineHeapBytes)
            putLong("baseline_pss_kb", baselinePssKb)
            putLong("java_heap_bytes", heap)
            putLong("java_heap_growth_bytes", if (soakStartedAtMs == 0L) 0L else heap - baselineHeapBytes)
            putLong("pss_growth_kb", if (soakStartedAtMs == 0L) 0L else lastPssKb - baselinePssKb)
            putLong("max_java_heap_bytes", maxHeapBytes)
            putLong("max_pss_kb", maxPssKb)
            putLong("pss_kb", lastPssKb)
            putInt("memory_samples", memorySamples)
            putLong("first_audio_p50_ms", percentile(firstAudioLatencies, 0.50))
            putLong("first_audio_p95_ms", percentile(firstAudioLatencies, 0.95))
            putLong("first_audio_max_ms", firstAudioLatencies.maxOrNull() ?: -1L)
            putLong("response_complete_p95_ms", percentile(completeLatencies, 0.95))
        }

        private fun percentile(values: List<Long>, fraction: Double): Long {
            if (values.isEmpty()) return -1L
            val sorted = values.sorted()
            return sorted[(ceil(sorted.size * fraction).toInt() - 1).coerceIn(sorted.indices)]
        }

        /** Tokenize longest Chinese numerals first, so 十一 is never mistaken for 一. */
        private fun answerNumerals(answer: String): List<String> =
            Regex("[0-9]+|[零〇一二两三四五六七八九十百千万]+")
                .findAll(answer.replace(Regex("一(?=[个张幅])"), ""))
                .map { it.value }.toList()

        /** Tokenize longest Chinese numerals first, so 十一 is never mistaken for 一. */
        private fun hasCompleteCountToTwenty(answer: String): Boolean {
            val units = mapOf('一' to 1, '二' to 2, '两' to 2, '三' to 3, '四' to 4,
                '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9)
            val tokens = Regex("(?<![0-9])(?:20|1[0-9]|[1-9])(?![0-9])|二十|十[一二三四五六七八九]?|[一二两三四五六七八九]")
                .findAll(answer).map { match ->
                    val token = match.value
                    token.toIntOrNull() ?: when {
                        token == "二十" -> 20
                        token.startsWith('十') -> 10 + (token.getOrNull(1)?.let { units[it] } ?: 0)
                        else -> units.getValue(token.single())
                    }
                }.toList()
            return tokens.windowed(20).any { it == (1..20).toList() }
        }

        private fun asset(name: String): ByteArray = instrumentation.context.assets.open(name).use { stream ->
            val pcm = stream.readBytes()
            requireCheck(pcm.isNotEmpty() && pcm.size % 2 == 0 && pcm.size <= config.bytesFor(20_000), "daily_invalid_pcm_asset")
            requireCheck(lastSpeechByte(pcm) > 0, "daily_silent_question_asset")
            pcm
        }

        private fun lastSpeechByte(pcm: ByteArray): Int {
            for (offset in pcm.size - 2 downTo 0 step 2) {
                val value = (((pcm[offset + 1].toInt() and 255) shl 8) or (pcm[offset].toInt() and 255)).toShort().toInt()
                if (abs(value) > 160) return offset + 2
            }
            return 0
        }

        private fun tone37ms(): ByteArray = ByteBuffer.allocate(config.bytesFor(37).toInt()).order(ByteOrder.LITTLE_ENDIAN).apply {
            repeat(capacity() / 2) { sample -> putShort((sin(2.0 * PI * 440.0 * sample / config.sampleRateHz) * 600.0).toInt().toShort()) }
        }.array()

        private fun card(digit: Char): ByteArray {
            val bitmap = Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888)
            try {
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.BLACK
                    textSize = 340f
                    typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
                    textAlign = Paint.Align.CENTER
                }
                Canvas(bitmap).apply {
                    drawColor(Color.WHITE)
                    drawText(digit.toString(), 320f, (480 - paint.ascent() - paint.descent()) / 2f, paint)
                }
                return ByteArrayOutputStream().use { output ->
                    requireCheck(bitmap.compress(Bitmap.CompressFormat.JPEG, 85, output), "daily_digit_jpeg_failed")
                    output.toByteArray()
                }
            } finally {
                bitmap.recycle()
            }
        }
    }
}
