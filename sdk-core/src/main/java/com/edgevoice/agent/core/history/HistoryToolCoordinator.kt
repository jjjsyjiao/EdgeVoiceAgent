package com.edgevoice.agent.core.history

import com.edgevoice.agent.executor.ExecutorToolCall
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.util.Locale
import java.util.UUID

data class HistoryCapturedFrame(val jpeg: ByteArray, val capturedAtMs: Long, val acceptedAtElapsedMs: Long)
data class HistoryQuestionSnapshot(
    val pcm: ByteArray?, val current: HistoryCapturedFrame?, val sequence: Long,
    /** Populated only by ASR whose itemId matches this original user input. */
    val userTranscript: String = "",
)

sealed interface HistoryToolAction {
    data class Reply(val outputJson: String, val state: HistoryUiState) : HistoryToolAction
    data class Compare(
        val outputJson: String,
        val questionPcm: ByteArray,
        val jpeg: ByteArray,
        val records: List<HistoryMemoryRecord>,
        val coverageWarning: String? = null,
    ) : HistoryToolAction
}

/** Tool work is outside the executor event collector, so media and cancellation keep flowing. */
class HistoryToolCoordinator(
    private val client: HistoryMemoryClient,
    private val config: HistoryMemoryConfig,
    private val compose: (HistoryCapturedFrame?, List<HistoryMemoryRecord>) -> ByteArray,
    private val now: () -> Long = System::currentTimeMillis,
) {
    suspend fun execute(
        call: ExecutorToolCall,
        question: HistoryQuestionSnapshot?,
        sessionId: String,
        onState: (HistoryUiState) -> Unit,
    ): HistoryToolAction = executeInternal(call, question, sessionId, onState, confirmedByUser = false)

    /** Only the UI confirmation path may call this after a same-turn frozen preview was shown. */
    internal suspend fun executeConfirmedSave(
        call: ExecutorToolCall,
        question: HistoryQuestionSnapshot,
        sessionId: String,
        onState: (HistoryUiState) -> Unit,
    ): HistoryToolAction.Reply {
        require(call.name == HistoryToolDefinitions.REMEMBER)
        require(HistorySaveIntent.needsConfirmation(question.userTranscript))
        val safeCall = call.copy(argumentsJson = HistorySaveIntent.safeConfirmationArguments(call.argumentsJson, question.userTranscript))
        return executeInternal(safeCall, question, sessionId, onState, confirmedByUser = true) as HistoryToolAction.Reply
    }

    private suspend fun executeInternal(
        call: ExecutorToolCall,
        question: HistoryQuestionSnapshot?,
        sessionId: String,
        onState: (HistoryUiState) -> Unit,
        confirmedByUser: Boolean,
    ): HistoryToolAction {
        try {
            require(call.argumentsJson.length <= 8_192) { "工具参数过长，请缩短问题后重试。" }
            val args = JSONObject(call.argumentsJson)
            return when (call.name) {
                HistoryToolDefinitions.REMEMBER -> remember(args, question, sessionId, onState, confirmedByUser)
                HistoryToolDefinitions.SEARCH -> search(args, question, onState)
                else -> failure("不支持这个历史工具。", "unknown_tool")
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: HistoryMemoryException) {
            return HistoryToolFailures.reply(failure.kind, call.name, config)
        } catch (failure: Exception) {
            // No URLs, credentials or image data are exposed in assistant text/logs.
            return failure(
                if (failure is IllegalArgumentException) failure.message ?: "证据不完整，请重新提问。" else "工具参数或历史证据无效，请重新提问。",
                "invalid_evidence_or_arguments",
            )
        }
    }

    private suspend fun remember(
        args: JSONObject, question: HistoryQuestionSnapshot?, sessionId: String,
        onState: (HistoryUiState) -> Unit, confirmedByUser: Boolean,
    ): HistoryToolAction {
        val original = question?.userTranscript.orEmpty()
        if (original.isBlank()) {
            return failure("本轮语音未转写成功，无法确认保存要求；请对准物品重新说“记一下”。", "save_transcript_unavailable")
        }
        if (!confirmedByUser && !HistorySaveIntent.isExplicit(original)) {
            return failure("本轮没有确认到明确保存要求；如需保存，请对着物品说“记一下”或“帮我记住”。", "save_not_requested")
        }
        val name = args.requiredText("object_name", 80)
        val proposedLocation = args.requiredText("location", 200, allowEmpty = true)
        args.requiredText("note", 500, allowEmpty = true)
        // User-recorded provenance must be backed by the actual ASR, not a guessed model field.
        fun normalized(value: String) = value.replace(Regex("[\\s，,。.!！?？；;的]"), "")
        val location = proposedLocation.takeIf { it.isNotEmpty() && normalized(original).contains(normalized(it)) }.orEmpty()
        val note = original.take(1_024)
        val rawAliases = args.getJSONArray("aliases")
        require(rawAliases.length() <= 8) { "物品别名过多，请精简。" }
        val aliases = (0 until rawAliases.length()).map { index ->
            require(rawAliases.get(index) is String) { "别名格式无效。" }
            rawAliases.getString(index).trim().also { require(it.length in 1..80) }
        }.distinct()
        val frame = question?.current ?: return failure("没有本轮清晰的相机画面，请打开相机对准物品，再说记一下。", "missing_current_frame")
        require(frame.jpeg.isNotEmpty()) { "本轮照片为空，请重试。" }
        onState(HistoryUiState(HistoryStatus.SAVING, "正在保存这次物品记录"))
        val saved = client.save(HistoryMemoryRecord(
            recordId = UUID.randomUUID().toString(), sessionId = sessionId,
            capturedAtMs = frame.capturedAtMs, objectName = name, aliases = aliases,
            location = location, note = note, jpeg = frame.jpeg,
        ))
        val confirmationInstruction = if (saved.location.isBlank()) {
            "只确认物品照片已经保存。用户没有口述位置，本次回答不要添加任何位置描述；画面不能证明物品现在的位置。"
        } else {
            "只确认实际保存的内容。可复述用户口述的位置，但那是当时记录的位置，不能声称物品现在仍在那里。"
        }
        return HistoryToolAction.Reply(
            JSONObject().put("status", "saved").put("record", metadata(saved, "H1"))
                .put("location_provided_by_user", saved.location.isNotBlank())
                .put("instruction", confirmationInstruction).toString(),
            HistoryUiState(HistoryStatus.SAVED, "已保存：$name", listOf(saved)),
        )
    }

    private suspend fun search(
        args: JSONObject, question: HistoryQuestionSnapshot?, onState: (HistoryUiState) -> Unit,
    ): HistoryToolAction {
        val query = args.requiredText("query", 120)
        val expression = args.requiredText("time_expression", 40, allowEmpty = true)
        require(args.get("compare_current") is Boolean) { "比较选项无效。" }
        val compareCurrent = args.getBoolean("compare_current")
        val rawQuery = question?.userTranscript?.trim()?.takeIf { it.isNotEmpty() }?.take(2_048)
        val intent = searchIntent(args, rawQuery.orEmpty(), compareCurrent)
        // A frozen snapshot already passed HistoryFrameCache's freshness check at speech stop.
        // An unrelated live camera view must not bias a text-only search for a missing object.
        val useCurrent = compareCurrent || hasVisualReference(rawQuery.orEmpty())
        val current = if (useCurrent) question?.current else null
        if (useCurrent && current == null) {
            return failure("没有本轮当前画面，请打开相机对准物品后重新提问。", "missing_current_frame")
        }
        val range = HistoryDateRange.resolve(expression, now(), config.timeZoneId)
        onState(HistoryUiState(HistoryStatus.SEARCHING, "正在查找已保存的物品记录"))
        val result = client.search(HistorySearchRequest(
            query = query, rawQuery = rawQuery, queryImage = current?.jpeg,
            intent = intent, fromTsMs = range.fromTsMs, toTsMs = range.toTsMs,
        ))
        val incomplete = result.unavailableRecordsCount > 0 || result.retrieval?.incomplete == true
        val uncalibrated = result.retrieval?.uncalibratedSimilarity == true
        if (result.status == HistorySearchStatus.EVIDENCE_UNAVAILABLE && result.records.isEmpty()) {
            return failure("找到了相关记录，但历史照片不可用，暂时不能根据画面判断。", "evidence_unavailable", HistoryStatus.EVIDENCE_UNAVAILABLE)
        }
        if (result.status == HistorySearchStatus.NO_MATCH || result.records.isEmpty()) {
            if (incomplete) {
                val indexing = result.retrieval?.status == HistoryRetrievalStatus.INDEXING ||
                    (result.retrieval?.pendingRecordsCount ?: 0) > 0
                val message = if (indexing) "部分记录还在建立索引，暂时没有找到可用证据，请稍后重试。"
                    else "历史检索暂不完整，目前没有可用证据，请稍后重试。"
                return HistoryToolAction.Reply(
                    JSONObject().put("status", "retrieval_incomplete").put("message", message)
                        .put("retrieval", retrievalMetadata(result))
                        .put("instruction", "检索未完整完成；不得说没有保存过记录，也不能猜测位置或物品身份。请说明仍在索引或服务降级，建议稍后重试。").toString(),
                    HistoryUiState(HistoryStatus.EVIDENCE_UNAVAILABLE, message),
                )
            }
            return HistoryToolAction.Reply(
                JSONObject().put("status", "no_match").put("query", query).put("time_expression", expression)
                    .put("retrieval", retrievalMetadata(result))
                    .put("instruction", "没有符合条件的已保存记录；不能据此断言从未看见，也不能判断是否同一个。请坦诚说明没有证据。").toString(),
                HistoryUiState(HistoryStatus.NO_MATCH, "没有找到符合条件的已保存记录"),
            )
        }
        val frozen = question ?: return failure("缺少本轮原声问题，请重新开启麦克风并提问。", "missing_question")
        val pcm = frozen.pcm?.takeIf { it.size in 9_600..1_440_000 }
            ?: return failure("本轮原声问题不完整或过长，请在30秒内重新提问。", "missing_question_audio")
        val records = result.records.take(3)
        // Composer must decode EVERY required image before a media response may be requested.
        val jpeg = compose(current, records)
        require(jpeg.isNotEmpty() && jpeg.size <= ComparisonImageComposer.MAX_JPEG_BYTES) { "证据图片不可用。" }
        val output = JSONObject().put("status", "evidence_ready")
            .put("query", query).put("time_expression", expression).put("time_zone", config.timeZoneId)
            .put("intent", intent.wireValue).put("retrieval", retrievalMetadata(result))
            .put("coverage_incomplete", incomplete)
            .put("similarity_uncalibrated", uncalibrated)
            .put("must_not_claim_latest", (incomplete || uncalibrated) && intent == HistorySearchIntent.FIND_LAST_LOCATION)
            .put("current_frame_present", current != null)
            .put("current_captured_at", current?.let { Instant.ofEpochMilli(it.capturedAtMs).toString() } ?: JSONObject.NULL)
            .put("records", JSONArray(records.mapIndexed { index, record -> metadata(record, "H${index + 1}") }))
            .put("instruction", "下一条内部用户媒体包含原声问题和标识组合图。C是本轮当前画面（若存在），H1起是上述历史候选照片。先核对图片是否与问题相关，不得把相似度分数当作事实或物品身份依据。只根据实际像素与时间回答；最后记录的位置不保证是当前位置，外观一致也不能无条件确认物品身份。" +
                (if (incomplete) "本次检索覆盖不完整，请明确说明只能核对这些候选，不能声称已经找全、这是最新记录或没有其他相关记录。" else "") +
                (if (uncalibrated) "相似度尚未经过数据集校准，候选可能不相关；先核对实际物品，不能仅凭排序认定找到了目标或这是目标的最新记录。" else ""))
            .toString()
        val warning = when {
            incomplete -> "检索覆盖不完整，已展示可用候选；不能保证是最新记录。"
            uncalibrated -> "已找到相似候选，请结合照片核对是否为目标物品。"
            else -> null
        }
        onState(HistoryUiState(HistoryStatus.COMPARING, warning ?: "已找到证据，正在交给当前模型查看", records))
        return HistoryToolAction.Compare(output, pcm, jpeg, records, warning)
    }

    private fun searchIntent(args: JSONObject, original: String, compareCurrent: Boolean): HistorySearchIntent {
        if (compareCurrent) return HistorySearchIntent.COMPARE
        if (args.has("intent")) {
            val intent = HistorySearchIntent.entries.firstOrNull { it.wireValue == args.getString("intent") }
                ?: throw IllegalArgumentException("检索意图无效，请重新提问。")
            require(intent != HistorySearchIntent.COMPARE) { "比较选项不完整，请重新提问。" }
            return intent
        }
        return if (Regex("放.{0,12}(哪|位置)|在哪|在哪里|位置在哪").containsMatchIn(original)) {
            HistorySearchIntent.FIND_LAST_LOCATION
        } else HistorySearchIntent.RECALL
    }

    private fun hasVisualReference(original: String): Boolean = Regex(
        "这(?:个|把|盏|支|件|双|副|套|只|张|部|台|块|条|瓶)(?!周|月|星期|礼拜|问题|时候)|眼前|当前画面|镜头(?:里|前)|摄像头|面前|现在看到的",
    ).containsMatchIn(original)

    private fun retrievalMetadata(result: HistorySearchResult): JSONObject {
        val info = result.retrieval
        return JSONObject().put("mode", info?.mode ?: "metadata")
            .put("status", info?.status?.name?.lowercase(Locale.ROOT) ?: "ready")
            .put("pending_records_count", info?.pendingRecordsCount ?: 0)
            .put("failed_records_count", info?.failedRecordsCount ?: 0)
            .put("unavailable_records_count", result.unavailableRecordsCount)
            .put("warnings", JSONArray(info?.warnings.orEmpty()))
    }

    private fun metadata(record: HistoryMemoryRecord, label: String): JSONObject = JSONObject()
        .put("label", label).put("record_id", record.recordId).put("object_name", record.objectName)
        .put("captured_at", Instant.ofEpochMilli(record.capturedAtMs).toString())
        .put("location_user_recorded", record.location).put("note_user_recorded", record.note)
        .put("source", record.source)

    private fun failure(message: String, code: String, status: HistoryStatus = HistoryStatus.ERROR) = HistoryToolAction.Reply(
        JSONObject().put("status", code).put("message", message)
            .put("instruction", "请说明这个实际错误。证据不全，不得猜测历史位置或判断同一物品。").toString(),
        HistoryUiState(status, message),
    )

    private fun JSONObject.requiredText(key: String, max: Int, allowEmpty: Boolean = false): String {
        require(has(key) && get(key) is String) { "缺少工具参数：$key" }
        return getString(key).trim().also {
            require(it.length <= max && (allowEmpty || it.isNotEmpty())) { "工具参数不完整或过长：$key" }
        }
    }
}
