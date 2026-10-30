package com.edgevoice.agent.app

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Bundle
import android.os.Build
import android.os.SystemClock
import android.view.WindowManager
import com.edgevoice.agent.executor.ExecutorEvent
import com.edgevoice.agent.executor.cloud.CloudRealtimeExecutor
import com.edgevoice.agent.media.audio.AudioConfig
import com.edgevoice.agent.media.audio.AudioPlaybackEngine
import com.edgevoice.agent.media.audio.JitterBuffer
import com.edgevoice.agent.media.ring.NativeRingBuffer
import com.edgevoice.agent.transport.realtime.RealtimeProfile
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.PI
import kotlin.math.sin
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * Device-only smoke checks for the actual JNI, cloud protocol and AudioTrack boundaries.
 * Default/daily inputs are synthetic test assets and in-memory cards. The explicit
 * capture_local suite opens the microphone locally; no suite opens the camera.
 * This runner lives in the test APK and deliberately prints only bounded test statistics.
 */
class NecessaryChainInstrumentation : Instrumentation() {
    private class SmokeFailure(message: String) : AssertionError(message)

    private data class TurnResult(
        val transcript: String,
        val answer: String,
        val audio: ByteArray,
        val inputFrames: Int,
        val videoFrames: Int,
    )

    private var stage = "initializing"
    private val result = Bundle()
    private val audioConfig = AudioConfig.REALTIME_UP
    private var testArguments = Bundle()

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        testArguments = arguments?.let(::Bundle) ?: Bundle()
        start()
    }

    override fun onStart() {
        super.onStart()
        if (testArguments.getString("suite") == "capture_local") {
            val capture = LocalCaptureChecks.run(this)
            finish(if (capture.getString("status") == "PASS") Activity.RESULT_OK else Activity.RESULT_CANCELED, capture)
            return
        }
        if (testArguments.getString("suite") == "daily") {
            val daily = runDailyWithForegroundActivity()
            finish(if (daily.getString("status") == "PASS") Activity.RESULT_OK else Activity.RESULT_CANCELED, daily)
            return
        }
        if (testArguments.getString("suite") == "lifecycle") {
            val lifecycle = LifecycleChecks.run(this, testArguments)
            finish(if (lifecycle.getString("status") == "PASS") Activity.RESULT_OK else Activity.RESULT_CANCELED, lifecycle)
            return
        }
        if (testArguments.getString("suite") == "captions") {
            val captions = CaptionLifecycleChecks.run(this)
            finish(if (captions.getString("status") == "PASS") Activity.RESULT_OK else Activity.RESULT_CANCELED, captions)
            return
        }
        if (testArguments.getString("suite") == "history") {
            val history = runHistoryWithForegroundActivity()
            finish(if (history.getString("status") == "PASS") Activity.RESULT_OK else Activity.RESULT_CANCELED, history)
            return
        }
        try {
            runBlocking {
                withTimeout(240_000L) {
                    reportStage("jni_ring")
                    checkNativeRing()
                    reportStage("short_audio_37ms")
                    val shortAudio = syntheticTone(37)
                    result.putLong("short_audio_played_bytes", checkPlayback(shortAudio))
                    runCloudChecks()
                }
            }
            result.putString("status", "PASS")
            result.putString("summary", "JNI, short-tail playback, cloud voice, vision 7→3 and complete audio playback passed")
            finish(Activity.RESULT_OK, result)
        } catch (failure: Throwable) {
            result.putString("status", "FAIL")
            result.putString("failed_stage", stage)
            // Provider/transport exception messages may contain endpoints. Never print them.
            val explanation = if (failure is SmokeFailure) failure.message else failure.javaClass.simpleName
            result.putString("failure", explanation?.take(180))
            finish(Activity.RESULT_CANCELED, result)
        }
    }

    private fun runHistoryWithForegroundActivity(): Bundle {
        var activity: MainActivity? = null
        return try {
            activity = startActivitySync(Intent(targetContext, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            }) as MainActivity
            runMainAction { requireNotNull(activity).window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
            waitForIdleSync()
            HistoryToolChecks.run(this,
                requireHybrid = testArguments.getString("requireHybrid") == "true",
                requireReady = testArguments.getString("requireReady") == "true")
        } finally {
            activity?.let { screen -> runMainAction {
                screen.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                screen.finish()
            } }
        }
    }

    private fun runDailyWithForegroundActivity(): Bundle {
        var activity: MainActivity? = null
        var daily = Bundle()
        var dailyStage = "daily_activity_launch"
        try {
            sendStatus(1, Bundle().apply { putString("stage", dailyStage) })
            activity = startActivitySync(Intent(targetContext, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            }) as MainActivity
            dailyStage = "daily_activity_keep_screen_on"
            runMainAction {
                requireNotNull(activity).window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            waitForIdleSync()
            dailyStage = "daily_checks"
            // Only host the idle Compose UI; no microphone, camera or VM start action is invoked.
            daily = DailyUseChecks.run(this, testArguments)
            daily.putBoolean("main_activity_foreground_host", true)
            daily.putBoolean("keep_screen_on_window_flag", true)
        } catch (error: Throwable) {
            daily.putString("status", "FAIL")
            daily.putString("failed_stage", dailyStage)
            daily.putString("failure", error.javaClass.simpleName.take(180))
        } finally {
            runCatching {
                runMainAction { activity?.finish() }
                waitForIdleSync()
            }.onFailure { error ->
                daily.putString("status", "FAIL")
                daily.putString("cleanup_failure", error.javaClass.simpleName)
            }
        }
        return daily
    }

    private fun runMainAction(action: () -> Unit) {
        val failure = AtomicReference<Throwable?>()
        runOnMainSync {
            try { action() } catch (error: Throwable) { failure.set(error) }
        }
        failure.get()?.let { throw it }
    }

    private fun reportStage(next: String) {
        stage = next
        sendStatus(1, Bundle().apply { putString("stage", next) })
    }

    private fun verify(condition: Boolean, message: String) {
        if (!condition) throw SmokeFailure(message)
    }

    private fun checkNativeRing() {
        val expected = ByteArray(777) { ((it * 73 + 19) and 0xff).toByte() }
        NativeRingBuffer.create(2_048).use { ring ->
            val input = ByteBuffer.allocateDirect(expected.size)
            input.put(expected)
            input.flip()
            verify(ring.write(input) == expected.size, "jni_write_count_mismatch")
            verify(ring.availableToRead() == expected.size, "jni_available_count_mismatch")
            val output = ByteBuffer.allocateDirect(expected.size)
            verify(ring.read(output) == expected.size, "jni_read_count_mismatch")
            val actual = ByteArray(expected.size)
            output.position(0)
            output.get(actual)
            verify(actual.contentEquals(expected), "jni_pcm_content_mismatch")
            verify(ring.availableToRead() == 0, "jni_ring_not_empty")
        }
        result.putInt("jni_roundtrip_bytes", expected.size)
    }

    private suspend fun runCloudChecks() = coroutineScope {
        val profile = RealtimeProfile.fromId(BuildConfig.REALTIME_PROVIDER)
        verify(profile.supportsVideoInput, "configured_provider_has_no_video_input")
        verify(BuildConfig.OPENAI_API_KEY.isNotBlank(), "cloud_credentials_missing")
        val voiceQuestion = readQuestion("question_audio.pcm")
        val visionQuestion = readQuestion("question_vision.pcm")
        val executor = CloudRealtimeExecutor(
            wsUrl = BuildConfig.OPENAI_REALTIME_URL,
            apiKey = BuildConfig.OPENAI_API_KEY,
            model = BuildConfig.OPENAI_REALTIME_MODEL,
            profile = profile,
            instructions = "你是实时语音应用的自动化验收助手。请用简短中文回答语音问题。" +
                "当用户询问画面中的数字时，以最近收到的画面为准，只回答一个阿拉伯数字。",
        )
        val events = Channel<ExecutorEvent>(Channel.UNLIMITED)
        // Install the subscriber before prepare; Ready and early errors must be observable.
        val subscription = launch(start = CoroutineStart.UNDISPATCHED) {
            executor.events.collect { events.send(it) }
        }
        try {
            reportStage("cloud_prepare")
            withTimeout(25_000L) { executor.prepare() }
            verify(executor.ready.value, "cloud_prepare_did_not_become_ready")

            reportStage("cloud_voice")
            val voice = sendQuestion(executor, events, voiceQuestion)
            recordTurn("voice", voice)
            reportStage("cloud_voice_playback")
            result.putLong("voice_played_bytes", checkPlayback(voice.audio))

            reportStage("cloud_vision_7")
            val seven = sendQuestion(executor, events, visionQuestion, digitCard('7'))
            recordTurn("vision7", seven)
            verify(seven.answer.contains('7') || seven.answer.contains('七'), "vision7_answer_did_not_recognize_7")
            reportStage("cloud_vision_7_playback")
            result.putLong("vision7_played_bytes", checkPlayback(seven.audio))

            // Same session, changed pixels: a stale last-image implementation must fail.
            reportStage("cloud_vision_3")
            val three = sendQuestion(executor, events, visionQuestion, digitCard('3'))
            recordTurn("vision3", three)
            verify(three.answer.contains('3') || three.answer.contains('三'), "vision3_answer_did_not_update_to_3")
            verify(!three.answer.contains('7') && !three.answer.contains('七'), "vision3_answer_contains_stale_7")
            reportStage("cloud_vision_3_playback")
            result.putLong("vision3_played_bytes", checkPlayback(three.audio))
        } finally {
            executor.shutdown()
            subscription.cancelAndJoin()
            events.close()
        }
    }

    private fun readQuestion(name: String): ByteArray {
        // context is the instrumentation/test package, where androidTest/assets are packaged.
        val pcm = context.assets.open(name).use { it.readBytes() }
        verify(pcm.isNotEmpty() && pcm.size % audioConfig.frameSizeBytes == 0, "invalid_test_pcm_asset")
        verify(pcm.size <= audioConfig.bytesFor(20_000), "test_pcm_asset_exceeds_20_seconds")
        verify(pcm.any { it != 0.toByte() }, "test_pcm_asset_is_silent")
        return pcm
    }

    private suspend fun sendQuestion(
        executor: CloudRealtimeExecutor,
        events: Channel<ExecutorEvent>,
        question: ByteArray,
        image: ByteArray? = null,
    ): TurnResult = coroutineScope {
        val transcript = StringBuilder()
        val answer = StringBuilder()
        val audio = ByteArrayOutputStream()
        var inputFrames = 0
        var videoFrames = 0
        val sender = launch {
            val frameBytes = audioConfig.bytesFor(20).toInt()
            val silence = ByteArray(audioConfig.bytesFor(1_000).toInt())
            val input = question + silence
            val startedAt = SystemClock.elapsedRealtime()
            var offset = 0
            while (offset < input.size) {
                val count = minOf(frameBytes, input.size - offset)
                executor.sendAudioFrame(ByteBuffer.wrap(input, offset, count))
                inputFrames++
                // An audio block always precedes a JPEG; never send faster than 1 fps.
                if (image != null && (inputFrames - 1) % 50 == 0) {
                    executor.sendVideoFrame(image)
                    videoFrames++
                }
                offset += count
                val deadline = startedAt + offset * 1_000L / (audioConfig.sampleRateHz * audioConfig.frameSizeBytes)
                delay((deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0L))
            }
        }
        try {
            withTimeout(65_000L) {
                var complete = false
                // ASR finalization may arrive after response.done on a separate server path.
                while (!complete || transcript.isBlank()) {
                    when (val event = events.receive()) {
                        is ExecutorEvent.UserTranscript -> {
                            if (transcript.isNotEmpty()) transcript.append(' ')
                            transcript.append(event.text)
                        }
                        is ExecutorEvent.TextDelta -> answer.append(event.text)
                        is ExecutorEvent.AssistantTextFinal -> {
                            answer.clear()
                            answer.append(event.text)
                        }
                        is ExecutorEvent.AudioDelta -> {
                            verify(audio.size() + event.pcm.size <= audioConfig.bytesFor(60_000), "cloud_audio_exceeds_60_seconds")
                            audio.write(event.pcm)
                        }
                        is ExecutorEvent.Error -> throw SmokeFailure("cloud_event_error:${event.cause.javaClass.simpleName}")
                        ExecutorEvent.TurnComplete -> complete = true
                        else -> Unit
                    }
                }
                sender.join()
            }
            verify(transcript.isNotBlank(), "cloud_user_transcript_missing")
            verify(answer.isNotBlank(), "cloud_assistant_text_missing")
            verify(audio.size() > 0, "cloud_assistant_audio_missing")
            verify(image == null || videoFrames > 0, "vision_frame_was_not_sent")
            TurnResult(transcript.toString(), answer.toString(), audio.toByteArray(), inputFrames, videoFrames)
        } finally {
            sender.cancelAndJoin()
        }
    }

    private fun recordTurn(prefix: String, turn: TurnResult) {
        result.putInt("${prefix}_asr_chars", turn.transcript.length)
        result.putInt("${prefix}_answer_chars", turn.answer.length)
        result.putInt("${prefix}_audio_bytes", turn.audio.size)
        result.putInt("${prefix}_input_frames", turn.inputFrames)
        result.putInt("${prefix}_video_frames", turn.videoFrames)
    }

    private suspend fun checkPlayback(pcm: ByteArray): Long {
        verify(pcm.isNotEmpty() && pcm.size % audioConfig.frameSizeBytes == 0, "invalid_playback_pcm")
        checkJitterRoundTrip(pcm)
        val completion = CompletableDeferred<Long>()
        val jitter = JitterBuffer(audioConfig)
        val playback = AudioPlaybackEngine(
            config = audioConfig,
            jitter = jitter,
            onPlaybackComplete = { generation -> completion.complete(generation) },
            onError = { failure -> completion.completeExceptionally(
                SmokeFailure("audio_track_error:${failure.javaClass.simpleName}"),
            ) },
        )
        try {
            playback.start()
            val generation = playback.beginResponse()
            verify(jitter.push(ByteBuffer.wrap(pcm)) == pcm.size, "playback_queue_rejected_pcm")
            playback.finishResponse()
            val completedGeneration = withTimeout(audioConfig.millisFor(pcm.size.toLong()).toLong() + 12_000L) {
                completion.await()
            }
            verify(completedGeneration == generation, "playback_completion_generation_mismatch")
            // API 26–30 may require appended silence to release AudioTrack's start threshold.
            verify(playback.playedBytes >= pcm.size.toLong(), "playback_did_not_write_all_pcm_bytes")
            if (Build.VERSION.SDK_INT >= 31) {
                verify(playback.playedBytes == pcm.size.toLong(), "modern_audio_track_received_unexpected_padding")
            }
            verify(jitter.availableBytes == 0L, "playback_retained_tail_audio")
            return playback.playedBytes
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                try {
                    try { playback.stop() } finally { playback.awaitStopped() }
                } finally {
                    jitter.close()
                }
            }
        }
    }

    private fun checkJitterRoundTrip(pcm: ByteArray) {
        JitterBuffer(audioConfig).use { queue ->
            queue.beginResponse()
            verify(queue.push(ByteBuffer.wrap(pcm)) == pcm.size, "jitter_roundtrip_input_count_mismatch")
            queue.finishResponse()
            val actual = ByteArrayOutputStream(pcm.size)
            val block = ByteBuffer.allocateDirect(audioConfig.bytesFor(20).toInt())
            while (queue.availableBytes > 0L) {
                block.clear()
                val count = queue.readInto(block)
                verify(count > 0, "jitter_roundtrip_stalled_on_tail")
                block.position(0)
                block.limit(count)
                val bytes = ByteArray(count)
                block.get(bytes)
                actual.write(bytes)
            }
            verify(actual.toByteArray().contentEquals(pcm), "jitter_changed_or_truncated_speech_pcm")
        }
    }

    private fun digitCard(digit: Char): ByteArray {
        val bitmap = Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.WHITE)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
                textSize = 340f
                textAlign = Paint.Align.CENTER
            }
            val baseline = (bitmap.height - paint.ascent() - paint.descent()) / 2f
            canvas.drawText(digit.toString(), bitmap.width / 2f, baseline, paint)
            return ByteArrayOutputStream().use { output ->
                verify(bitmap.compress(Bitmap.CompressFormat.JPEG, 85, output), "synthetic_card_encoding_failed")
                output.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun syntheticTone(durationMs: Int): ByteArray {
        val samples = audioConfig.sampleRateHz * durationMs / 1_000
        val buffer = ByteBuffer.allocate(samples * 2).order(ByteOrder.LITTLE_ENDIAN)
        repeat(samples) { index ->
            val sample = (sin(2.0 * PI * 440.0 * index / audioConfig.sampleRateHz) * 800.0).toInt().toShort()
            buffer.putShort(sample)
        }
        return buffer.array()
    }
}
