package com.edgevoice.agent.app

import android.app.Instrumentation
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.os.Bundle
import android.os.SystemClock
import com.edgevoice.agent.core.history.ComparisonImageComposer
import com.edgevoice.agent.core.history.HistoryCapturedFrame
import com.edgevoice.agent.core.history.HistoryMemoryClient
import com.edgevoice.agent.core.history.HistoryMemoryConfig
import com.edgevoice.agent.core.history.HistoryMemoryRecord
import com.edgevoice.agent.core.history.HistoryQuestionSnapshot
import com.edgevoice.agent.core.history.HistoryRetrievalInfo
import com.edgevoice.agent.core.history.HistoryRetrievalStatus
import com.edgevoice.agent.core.history.HistorySearchIntent
import com.edgevoice.agent.core.history.HistorySearchRequest
import com.edgevoice.agent.core.history.HistorySearchResult
import com.edgevoice.agent.core.history.HistorySearchStatus
import com.edgevoice.agent.core.history.HistoryToolAction
import com.edgevoice.agent.core.history.HistoryToolCoordinator
import com.edgevoice.agent.core.history.HistoryToolDefinitions
import com.edgevoice.agent.core.history.HistoryStatus
import com.edgevoice.agent.core.history.HttpHistoryMemoryClient
import com.edgevoice.agent.executor.ExecutorEvent
import com.edgevoice.agent.executor.ExecutorToolCall
import com.edgevoice.agent.executor.cloud.CloudRealtimeExecutor
import com.edgevoice.agent.transport.ITransportClient
import com.edgevoice.agent.transport.TransportListener
import com.edgevoice.agent.transport.TransportState
import com.edgevoice.agent.transport.realtime.RealtimeProfile
import com.edgevoice.agent.transport.ws.WebSocketTransport
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
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.ZoneId
import java.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Component end-to-end proof using the production HTTP client, coordinator and executor.
 * The cloud receives only generated cards and offline TTS. No microphone or camera is opened.
 * A transport decorator records counts and image equality, never credentials or payloads.
 */
internal object HistoryToolChecks {
    fun run(instrumentation: Instrumentation, requireHybrid: Boolean = false, requireReady: Boolean = false): Bundle =
        Checks(instrumentation, requireHybrid || requireReady, requireReady).run()

    private class Failure(val reason: String) : AssertionError(reason)

    private class Checks(
        private val instrumentation: Instrumentation,
        private val requireHybrid: Boolean,
        private val requireReady: Boolean,
    ) {
        private val result = Bundle()
        private var stage = "history_initializing"

        fun run(): Bundle {
            try {
                runBlocking {
                    withTimeout(240_000L) {
                        checkImageGates()
                        checkCloudHistory()
                    }
                }
                result.putString("status", "PASS")
                result.putString("scope", "synthetic_component_e2e_no_microphone_no_camera")
            } catch (failure: Throwable) {
                result.putString("status", "FAIL")
                result.putString("failed_stage", stage)
                // Exception messages from provider/HTTP libraries may contain private endpoints.
                result.putString("failure", if (failure is Failure) failure.reason else failure.javaClass.simpleName)
            }
            return result
        }

        private fun report(next: String) {
            stage = next
            instrumentation.sendStatus(1, Bundle().apply { putString("stage", next) })
        }

        private suspend fun checkImageGates() {
            report("history_android_image_gates")
            val now = System.currentTimeMillis()
            val current = HistoryCapturedFrame(card("1234"), now, SystemClock.elapsedRealtime())
            val record = fixture("fixture", card("5678"), now - 86_400_000L)
            val composer = ComparisonImageComposer(ZoneId.systemDefault().id)
            val combined = composer.compose(current, listOf(record))
            val bitmap = BitmapFactory.decodeByteArray(combined, 0, combined.size)
            verify(bitmap != null, "combined_jpeg_not_decodable_on_android")
            bitmap?.let {
                try { verify(it.width == 960 && it.height == 440, "combined_image_panel_dimensions_invalid") }
                finally { it.recycle() }
            }
            verify(combined.size <= ComparisonImageComposer.MAX_JPEG_BYTES, "combined_jpeg_over_budget")
            expectInvalid { composer.compose(current, emptyList()) }
            expectInvalid { composer.compose(current.copy(jpeg = byteArrayOf(1, 2, 3)), listOf(record)) }
            expectInvalid { composer.compose(current, listOf(record.copy(jpeg = byteArrayOf(1, 2, 3)))) }

            var saves = 0
            var searchResult = HistorySearchResult(HistorySearchStatus.MATCHED, listOf(record.copy(jpeg = byteArrayOf(1, 2, 3))))
            val fake = object : HistoryMemoryClient {
                override suspend fun save(record: HistoryMemoryRecord): HistoryMemoryRecord { saves++; return record }
                override suspend fun search(query: String, fromTsMs: Long?, toTsMs: Long?, limit: Int) =
                    searchResult
                override suspend fun delete(recordId: String) = false
            }
            val coordinator = HistoryToolCoordinator(fake, HistoryMemoryConfig(), composer::compose)
            val remember = coordinator.execute(
                tool(HistoryToolDefinitions.REMEMBER, """{"object_name":"fixture","aliases":[],"location":"","note":""}"""),
                null, "fixture", {},
            )
            verify(remember is HistoryToolAction.Reply && remember.state.status == HistoryStatus.ERROR && saves == 0,
                "missing_current_was_not_blocked_before_save")
            val corrupt = coordinator.execute(
                tool(HistoryToolDefinitions.SEARCH, """{"query":"fixture","time_expression":"","compare_current":true}"""),
                HistoryQuestionSnapshot(ByteArray(48_000), current, 1), "fixture", {},
            )
            verify(corrupt is HistoryToolAction.Reply && corrupt.state.status == HistoryStatus.ERROR,
                "corrupt_historical_pixels_reached_comparison")
            result.putInt("android_image_gate_checks", 6)

            searchResult = HistorySearchResult(HistorySearchStatus.NO_MATCH, retrieval = HistoryRetrievalInfo(
                "hybrid", HistoryRetrievalStatus.INDEXING, pendingRecordsCount = 1, warnings = listOf("indexing_pending"),
            ))
            val indexing = coordinator.execute(
                tool(HistoryToolDefinitions.SEARCH, """{"query":"fixture","time_expression":"","compare_current":true}"""),
                HistoryQuestionSnapshot(ByteArray(48_000), current, 1), "fixture", {},
            )
            verify(indexing is HistoryToolAction.Reply && indexing.state.status == HistoryStatus.EVIDENCE_UNAVAILABLE &&
                JSONObject(indexing.outputJson).getString("status") == "retrieval_incomplete",
                "pending_index_was_misreported_as_no_stored_record")
            result.putInt("android_retrieval_state_checks", 1)
        }

        private suspend fun checkCloudHistory() = coroutineScope {
            report("history_configuration")
            val config = HistoryMemoryConfig(
                BuildConfig.HISTORY_ENABLED, BuildConfig.HISTORY_ENDPOINT, BuildConfig.HISTORY_TEST_API_TOKEN,
                BuildConfig.HISTORY_TIME_ZONE.ifBlank { ZoneId.systemDefault().id },
            )
            verify(config.active && config.apiToken != BuildConfig.HISTORY_API_TOKEN,
                "isolated_history_test_token_not_configured")
            verify(BuildConfig.OPENAI_API_KEY.isNotBlank(), "cloud_credentials_missing")
            val profile = RealtimeProfile.fromId(BuildConfig.REALTIME_PROVIDER)
            verify(profile.supportsVideoInput && profile.audioSampleRate == 24_000, "provider_requires_24k_video_support")
            val question = instrumentation.context.assets.open("question_history.pcm").use { it.readBytes() }
            verify(question.size in 9_600..1_440_000 && question.size % 2 == 0 && question.any { it != 0.toByte() },
                "invalid_history_pcm_asset")
            val random = SecureRandom()
            var currentCode = (1000 + random.nextInt(9000)).toString()
            while (currentCode == "9473") currentCode = (1000 + random.nextInt(9000)).toString()
            var historicalCode = (1000 + random.nextInt(9000)).toString()
            while (historicalCode == currentCode || historicalCode == "9473") historicalCode = (1000 + random.nextInt(9000)).toString()
            val currentJpeg = card(currentCode)
            val recordId = "history_accept_${UUID.randomUUID().toString().replace("-", "") }"
            val record = fixture(recordId, card(historicalCode), System.currentTimeMillis() - 86_400_000L)
            val memory = HttpHistoryMemoryClient(config.baseUrl, config.apiToken)
            var saved = false
            var saveAttempted = false
            val transportCreations = AtomicInteger()
            val connectionCount = AtomicInteger()
            lateinit var tracking: TrackingTransport
            val executor = CloudRealtimeExecutor(
                wsUrl = BuildConfig.OPENAI_REALTIME_URL,
                apiKey = BuildConfig.OPENAI_API_KEY,
                model = BuildConfig.OPENAI_REALTIME_MODEL,
                profile = profile,
                toolsJson = HistoryToolDefinitions.toolsJson,
                instructions = HistoryToolDefinitions.instructions +
                    "\n本轮是合成验收。查询验收台灯九四七三时用search_history_frames，query保留物品全名与编号，compare_current=true。" +
                    "查看组合图后，请用阿拉伯数字完整读出C当前和H1历史的四位编号，再说明两个编号是否相同。简短中文回答。",
                transportFactory = { url, headers ->
                    transportCreations.incrementAndGet()
                    TrackingTransport(WebSocketTransport(url, headers), connectionCount).also { tracking = it }
                },
            )
            val toolCalls = Channel<ExecutorToolCall>(4)
            val comparisonActive = AtomicBoolean()
            val complete = CompletableDeferred<Unit>()
            val output = OutputStats()
            val subscription = launch(start = CoroutineStart.UNDISPATCHED) {
                executor.events.collect { event ->
                    when (event) {
                        is ExecutorEvent.ToolCallReady -> {
                            verify(!comparisonActive.get(), "recursive_tool_call_during_media_round")
                            toolCalls.send(event.call)
                        }
                        is ExecutorEvent.Error -> throw Failure("history_cloud_event_error")
                        is ExecutorEvent.ResponseStarted -> if (comparisonActive.get()) output.started(event.responseId)
                        is ExecutorEvent.TextDelta -> if (comparisonActive.get()) output.text(event.text, false)
                        is ExecutorEvent.AssistantTextFinal -> if (comparisonActive.get()) output.text(event.text, true)
                        is ExecutorEvent.AudioDelta -> if (comparisonActive.get()) output.audio(event.pcm)
                        is ExecutorEvent.ResponseIncomplete -> throw Failure("history_response_incomplete")
                        ExecutorEvent.TurnComplete -> if (comparisonActive.get() && output.hasResponse()) complete.complete(Unit)
                        else -> Unit
                    }
                }
            }
            try {
                report("history_fixture_save")
                saveAttempted = true
                memory.save(record)
                saved = true
                if (requireHybrid) {
                    report("history_fixture_index_wait")
                    awaitHybridIndexReady(config)
                }
                report("history_cloud_prepare")
                withTimeout(40_000L) { executor.prepare() }
                val epoch = executor.inputEpoch
                verify(executor.ready.value && executor.supportsTools, "history_tools_not_ready")
                report("history_function_wait")
                val sender = launch(Dispatchers.IO) { sendQuestion(executor, question, currentJpeg, profile.audioSampleRate) }
                val call = try { withTimeout(65_000L) { toolCalls.receive() } } finally { sender.cancelAndJoin() }
                verify(call.name == HistoryToolDefinitions.SEARCH, "model_did_not_call_history_search")
                verify(call.inputEpoch == epoch, "history_tool_epoch_changed")
                result.putBoolean("real_function_call_received", true)
                val checkedMemory = object : HistoryMemoryClient by memory {
                    override suspend fun search(request: HistorySearchRequest): HistorySearchResult {
                        verify(request.intent == HistorySearchIntent.COMPARE, "comparison_retrieval_intent_missing")
                        verify(request.queryImage?.contentEquals(currentJpeg) == true, "frozen_current_query_image_missing_or_changed")
                        val response = memory.search(request)
                        result.putBoolean("current_query_image_submitted", true)
                        result.putBoolean("hybrid_retrieval_required", requireHybrid)
                        result.putBoolean("ready_retrieval_required", requireReady)
                        result.putString("retrieval_mode", response.retrieval?.mode ?: "metadata")
                        result.putString("retrieval_status", response.retrieval?.status?.name ?: "legacy")
                        // HttpHistoryMemoryClient accepted these only after its fixed-code allowlist validation.
                        result.putString("retrieval_warnings", response.retrieval?.warnings.orEmpty().joinToString(","))
                        if (requireHybrid) verify(response.retrieval?.mode == "hybrid", "hybrid_retrieval_service_not_enabled")
                        if (requireReady) verify(response.retrieval?.status == HistoryRetrievalStatus.READY,
                            "hybrid_retrieval_was_not_ready")
                        return response
                    }
                }
                val coordinator = HistoryToolCoordinator(checkedMemory, config, ComparisonImageComposer(config.timeZoneId)::compose)
                report("history_retrieve_and_compose")
                val action = coordinator.execute(
                    call,
                    HistoryQuestionSnapshot(
                        question,
                        HistoryCapturedFrame(currentJpeg, System.currentTimeMillis(), SystemClock.elapsedRealtime()), 1,
                    ),
                    "new_query_session", {},
                )
                verify(action is HistoryToolAction.Compare, "history_tool_did_not_produce_comparison")
                action as HistoryToolAction.Compare
                // Fail before cloud submission if configuration accidentally points to non-fixture data.
                verify(action.records.size == 1 && action.records.single().recordId == recordId,
                    "history_query_must_retrieve_only_isolated_fixture")
                verify(action.records.single().sessionId != "new_query_session", "history_search_was_not_cross_session")
                tracking.expectComparison(action.jpeg)
                comparisonActive.set(true)
                report("history_controlled_comparison")
                val finished = executor.respondToToolWithMedia(call, action.outputJson, action.questionPcm, action.jpeg)
                withTimeout(5_000L) { complete.await() }
                verify(finished, "controlled_history_round_did_not_complete_and_restore")
                val answer = output.answer()
                val digits = normalizedDigits(answer)
                verify(digits.contains(currentCode), "current_image_code_missing_or_incorrect")
                verify(digits.contains(historicalCode), "historical_image_code_missing_or_incorrect")
                verify(answer.contains("不同") || answer.contains("不一样") || answer.contains("不相同") || answer.contains("不一致"),
                    "different_codes_were_not_reported_different")
                verify(output.audioBytes() > 4_800L && output.audioBytes() % 2L == 0L, "history_audio_missing_or_invalid_pcm")
                verify(executor.inputEpoch == epoch && executor.ready.value, "history_round_reconnected_or_lost_ready")
                verify(transportCreations.get() == 1 && connectionCount.get() == 1, "history_round_used_more_than_one_websocket")
                verify(tracking.comparisonImages.get() == 1, "exact_comparison_jpeg_not_sent_once")
                verify(tracking.functionOutputs.get() == 1, "function_output_missing_or_repeated")
                verify(tracking.manualSessions.get() == 1 && tracking.restoredSessions.get() == 1,
                    "manual_vad_tools_switch_or_restore_missing")
                verify(tracking.commits.get() == 1 && tracking.responseCreates.get() == 1,
                    "controlled_media_commit_or_response_create_missing")
                result.putInt("websocket_connections", connectionCount.get())
                result.putInt("tool_output_events", tracking.functionOutputs.get())
                result.putInt("exact_comparison_jpeg_events", tracking.comparisonImages.get())
                result.putInt("controlled_commit_events", tracking.commits.get())
                result.putInt("manual_mode_transitions", tracking.manualSessions.get())
                result.putInt("live_mode_restores", tracking.restoredSessions.get())
                result.putInt("answer_chars", answer.length)
                result.putLong("answer_pcm_bytes", output.audioBytes())
                result.putBoolean("current_and_history_codes_correct", true)
                result.putBoolean("different_codes_correct", true)
                result.putBoolean("cross_session_record_retrieved", true)
                result.putBoolean("text_and_audio_complete", true)
            } finally {
                withContext(NonCancellable + Dispatchers.IO) {
                    executor.shutdown()
                    subscription.cancelAndJoin()
                    toolCalls.close()
                    if (saveAttempted) {
                        val previousStage = stage
                        report("history_fixture_cleanup")
                        val removed = memory.delete(recordId)
                        verify(removed || !saved, "history_fixture_cleanup_failed")
                        result.putBoolean("fixture_deleted", true)
                        stage = previousStage
                    }
                }
            }
        }

        /** Poll metadata only, not the paid search path. The isolated fixture must be indexed first. */
        private suspend fun awaitHybridIndexReady(config: HistoryMemoryConfig) = withContext(Dispatchers.IO) {
            val started = SystemClock.elapsedRealtime()
            val deadline = started + 30_000L
            val statusClient = OkHttpClient.Builder()
                .connectTimeout(3, TimeUnit.SECONDS).readTimeout(3, TimeUnit.SECONDS)
                .followRedirects(false).followSslRedirects(false).build()
            val url = config.baseUrl.trimEnd('/').toHttpUrl().newBuilder()
                .addPathSegment("visual-memory").addPathSegment("status").build()
            val request = Request.Builder().url(url).header("Authorization", "Bearer ${config.apiToken}").get().build()
            try {
                while (SystemClock.elapsedRealtime() < deadline) {
                    val remaining = deadline - SystemClock.elapsedRealtime()
                    if (remaining <= 0) break
                    val call = statusClient.newCall(request)
                    call.timeout().timeout(minOf(3_000L, remaining), TimeUnit.MILLISECONDS)
                    val state = try {
                        call.execute().use { response ->
                            if (response.code in setOf(408, 429) || response.code >= 500) null else {
                                verify(response.isSuccessful, "history_index_status_http_failed")
                                val body = response.body ?: throw Failure("history_index_status_empty")
                                val source = body.source()
                                source.request(65_537L)
                                verify(source.buffer.size <= 65_536L, "history_index_status_oversized")
                                JSONObject(source.readUtf8())
                            }
                        }
                    } catch (_: IOException) { null }
                    if (state != null) {
                        verify(state.getString("mode") == "hybrid", "hybrid_retrieval_service_not_enabled")
                        val index = state.getJSONObject("index")
                        val total = index.getInt("total")
                        val ready = index.getInt("ready")
                        val pending = index.getInt("pending")
                        val failed = index.getInt("failed")
                        verify(total >= 0 && ready >= 0 && pending >= 0 && failed >= 0, "history_index_status_invalid")
                        result.putInt("index_ready_records", ready)
                        result.putInt("index_pending_records", pending)
                        result.putInt("index_failed_records", failed)
                        if (total > 0 && ready == total && pending == 0 && failed == 0) {
                            result.putBoolean("index_ready_before_query", true)
                            result.putLong("index_wait_ms", SystemClock.elapsedRealtime() - started)
                            return@withContext
                        }
                    }
                    delay(minOf(500L, (deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0L)))
                }
                result.putLong("index_wait_ms", SystemClock.elapsedRealtime() - started)
                throw Failure("history_fixture_index_not_ready_within_30_seconds")
            } finally {
                statusClient.connectionPool.evictAll()
                statusClient.dispatcher.executorService.shutdown()
            }
        }

        private suspend fun sendQuestion(executor: CloudRealtimeExecutor, question: ByteArray, jpeg: ByteArray, sampleRate: Int) {
            val input = ByteArray(sampleRate * 2 / 4) + question + ByteArray(sampleRate * 2)
            val frameBytes = sampleRate * 2 / 50
            val started = SystemClock.elapsedRealtime()
            var offset = 0
            var frame = 0
            while (offset < input.size) {
                val count = minOf(frameBytes, input.size - offset)
                executor.sendAudioFrame(ByteBuffer.wrap(input, offset, count))
                if (frame % 50 == 0) executor.sendVideoFrame(jpeg)
                offset += count
                frame++
                delay((started + offset * 1_000L / (sampleRate * 2) - SystemClock.elapsedRealtime()).coerceAtLeast(0))
            }
        }

        private fun fixture(id: String, jpeg: ByteArray, time: Long) = HistoryMemoryRecord(
            recordId = id, sessionId = "old_fixture_session", capturedAtMs = time,
            objectName = "验收台灯9473", aliases = listOf("验收台灯九四七三", "测试台灯九四七三", "验收台灯"),
            location = "合成验收桌面", note = "仅用于自动验收的合成照片，四位编号只存在照片像素中。", jpeg = jpeg,
        )

        private fun tool(name: String, arguments: String) = ExecutorToolCall(name, "fixture", arguments, "fixture_response", 1)

        private fun expectInvalid(block: () -> Unit) {
            try { block() } catch (_: IllegalArgumentException) { return }
            throw Failure("invalid_image_was_not_rejected")
        }

        private fun verify(condition: Boolean, reason: String) { if (!condition) throw Failure(reason) }
    }

    private class OutputStats {
        private var response: String? = null
        private val text = StringBuilder()
        private var bytes = 0L
        @Synchronized fun started(id: String) { if (response != null) throw Failure("multiple_comparison_responses"); response = id }
        @Synchronized fun hasResponse() = response != null
        @Synchronized fun text(value: String, final: Boolean) { if (final) text.clear(); text.append(value) }
        @Synchronized fun audio(value: ByteArray) {
            bytes += value.size
            if (bytes > 24_000L * 2 * 45) throw Failure("history_audio_exceeded_45_seconds")
        }
        @Synchronized fun answer() = text.toString()
        @Synchronized fun audioBytes() = bytes
    }

    private class TrackingTransport(
        private val delegate: ITransportClient,
        private val connections: AtomicInteger,
    ) : ITransportClient by delegate {
        val comparisonImages = AtomicInteger()
        val functionOutputs = AtomicInteger()
        val manualSessions = AtomicInteger()
        val restoredSessions = AtomicInteger()
        val commits = AtomicInteger()
        val responseCreates = AtomicInteger()
        @Volatile private var expectedImageHash: ByteArray? = null
        fun expectComparison(jpeg: ByteArray) { expectedImageHash = digest(jpeg) }

        override fun setListener(listener: TransportListener?) {
            if (listener == null) { delegate.setListener(null); return }
            delegate.setListener(object : TransportListener {
                override fun onConnectionChanged(state: TransportState, epoch: Long, attempt: Int) {
                    if (state == TransportState.CONNECTED) connections.incrementAndGet()
                    listener.onConnectionChanged(state, epoch, attempt)
                }
                override fun onStateChanged(state: TransportState) = listener.onStateChanged(state)
                override fun onTextMessage(text: String) = listener.onTextMessage(text)
                override fun onTextMessage(text: String, epoch: Long) = listener.onTextMessage(text, epoch)
                override fun onBinaryFrame(data: ByteBuffer) = listener.onBinaryFrame(data)
                override fun onRttMeasured(rttMs: Long) = listener.onRttMeasured(rttMs)
                override fun onError(cause: Throwable) = listener.onError(cause)
                override fun onError(cause: Throwable, epoch: Long) = listener.onError(cause, epoch)
            })
        }

        override fun sendText(text: String): Boolean = delegate.sendText(text).also { if (it) record(text) }
        override fun sendText(text: String, expectedEpoch: Long): Boolean =
            delegate.sendText(text, expectedEpoch).also { if (it) record(text) }

        private fun record(text: String) {
            val message = JSONObject(text)
            when (message.optString("type")) {
                "conversation.item.create" -> if (message.optJSONObject("item")?.optString("type") == "function_call_output") functionOutputs.incrementAndGet()
                "input_image_buffer.append" -> expectedImageHash?.let { expected ->
                    if (digest(Base64.getDecoder().decode(message.getString("image"))).contentEquals(expected)) comparisonImages.incrementAndGet()
                }
                "input_audio_buffer.commit" -> commits.incrementAndGet()
                "response.create" -> responseCreates.incrementAndGet()
                "session.update" -> {
                    val session = message.getJSONObject("session")
                    val toolCount = session.optJSONArray("tools")?.length() ?: 0
                    if (session.isNull("turn_detection") && toolCount == 0) manualSessions.incrementAndGet()
                    else if (manualSessions.get() > 0 && !session.isNull("turn_detection") && toolCount == 2) restoredSessions.incrementAndGet()
                }
            }
        }
        private fun digest(value: ByteArray) = MessageDigest.getInstance("SHA-256").digest(value)
    }

    private fun normalizedDigits(text: String): String {
        val chinese = mapOf('零' to '0', '〇' to '0', '一' to '1', '二' to '2', '三' to '3', '四' to '4',
            '五' to '5', '六' to '6', '七' to '7', '八' to '8', '九' to '9')
        return text.map { chinese[it] ?: it }.filter { it in '0'..'9' }.joinToString("")
    }

    private fun card(code: String): ByteArray {
        val bitmap = Bitmap.createBitmap(640, 480, Bitmap.Config.RGB_565)
        try {
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.WHITE)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(30, 80, 110); strokeWidth = 18f }
            canvas.drawLine(320f, 80f, 320f, 235f, paint)
            canvas.drawOval(240f, 225f, 400f, 250f, paint)
            val shade = Path().apply { moveTo(260f, 50f); lineTo(380f, 50f); lineTo(425f, 145f); lineTo(215f, 145f); close() }
            canvas.drawPath(shade, paint)
            paint.color = Color.BLACK
            paint.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            paint.textAlign = Paint.Align.CENTER
            paint.textSize = 155f
            canvas.drawText(code, 320f, 435f, paint)
            return ByteArrayOutputStream().use { stream ->
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 88, stream)) throw Failure("fixture_jpeg_encoding_failed")
                stream.toByteArray()
            }
        } finally { bitmap.recycle() }
    }
}
