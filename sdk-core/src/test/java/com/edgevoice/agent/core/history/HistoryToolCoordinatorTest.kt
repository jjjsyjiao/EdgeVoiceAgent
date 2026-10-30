package com.edgevoice.agent.core.history

import com.edgevoice.agent.executor.ExecutorToolCall
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Test

class HistoryToolCoordinatorTest {
    private class FakeClient : HistoryMemoryClient {
        var saves = 0
        var savedRecord: HistoryMemoryRecord? = null
        var saveFailure: HistoryMemoryException? = null
        var searches = 0
        var lastSearch: HistorySearchRequest? = null
        var searchFailure: HistoryMemoryException? = null
        var result = HistorySearchResult(HistorySearchStatus.MATCHED, listOf(record()))
        override suspend fun save(record: HistoryMemoryRecord): HistoryMemoryRecord {
            saves++
            saveFailure?.let { throw it }
            savedRecord = record
            return record
        }
        override suspend fun search(query: String, fromTsMs: Long?, toTsMs: Long?, limit: Int): HistorySearchResult {
            searches++
            searchFailure?.let { throw it }
            return result
        }
        override suspend fun search(request: HistorySearchRequest): HistorySearchResult {
            lastSearch = request
            return search(request.query, request.fromTsMs, request.toTsMs, request.limit)
        }
        override suspend fun delete(recordId: String) = true
    }
    private fun coordinator(client: FakeClient, composer: (HistoryCapturedFrame?, List<HistoryMemoryRecord>) -> ByteArray = { _, _ -> byteArrayOf(1) }) =
        HistoryToolCoordinator(client, HistoryMemoryConfig(timeZoneId = "Asia/Shanghai"), composer, now = { 1_800_000_000_000L })

    @Test fun saveRequiresExplicitUserRequestEvenIfModelCallsTool() = runTest {
        val client = FakeClient()
        val call = call(HistoryToolDefinitions.REMEMBER, """{"object_name":"钥匙","location":"玄关","note":"","aliases":[]}""")
        val noRequest = coordinator(client).execute(call, question("钥匙在哪里？"), "s", {}) as HistoryToolAction.Reply
        assertThat(JSONObject(noRequest.outputJson).getString("status")).isEqualTo("save_not_requested")
        assertThat(client.saves).isEqualTo(0)
        val saved = coordinator(client).execute(call, question("帮我记一下，钥匙放在玄关。"), "s", {}) as HistoryToolAction.Reply
        assertThat(saved.state.status).isEqualTo(HistoryStatus.SAVED)
        assertThat(client.saves).isEqualTo(1)
    }

    @Test fun saveNegationCannotAuthorizeUpload() {
        assertThat(HistorySaveIntent.isExplicit("不要帮我记住这张照片")).isFalse()
        assertThat(HistorySaveIntent.isExplicit("这句话里有记住两个字")).isFalse()
        assertThat(HistorySaveIntent.isExplicit("请你记住这把备用钥匙的位置")).isTrue()
        assertThat(HistorySaveIntent.isExplicit("这个蓝色的包帮我记一下，放在柜子里")).isTrue()
        assertThat(HistorySaveIntent.isExplicit("这部手机记一下。 ")).isTrue()
        assertThat(HistorySaveIntent.isExplicit("以后再记一下这个包")).isFalse()
        assertThat(HistorySaveIntent.isExplicit("这个手机不要记一下")).isFalse()
        assertThat(HistorySaveIntent.isExplicit("我不想让你记一下这个包")).isFalse()
        assertThat(HistorySaveIntent.isExplicit("如果我要你记一下这个包会怎样？")).isFalse()
        assertThat(HistorySaveIntent.isExplicit("我昨天说过记一下这个包")).isFalse()
        assertThat(HistorySaveIntent.isExplicit("你说“记一下这个包”是什么意思？")).isFalse()
        assertThat(HistorySaveIntent.isExplicit("“记一下”这个按钮在哪？")).isFalse()
        assertThat(HistorySaveIntent.isExplicit("这句我是不是该记一下？")).isFalse()
        assertThat(HistorySaveIntent.isExplicit("我想让你记一下这个包")).isTrue()
        assertThat(HistorySaveIntent.objectName("记一下这个蓝色的包，放在柜子里")).isEqualTo("蓝色的包")
        assertThat(HistorySaveIntent.objectName("这个蓝色的包帮我记一下，放在柜子里")).isEqualTo("蓝色的包")
        assertThat(HistorySaveIntent.objectName("这部手机帮我记一下")).isEqualTo("手机")
        assertThat(HistorySaveIntent.location("记一下这个蓝色的包，放在柜子里")).isEqualTo("柜子里")
        assertThat(HistorySaveIntent.location("记一下这部手机")).isEmpty()
    }

    @Test fun equivalentSaveCommandsAreExplicitButNegatedAndReportedCommandsAreNot() {
        listOf(
            "帮我记着这个蓝色的包", "记录下来这部手机", "把台灯保存起来",
            "给我存下来这把钥匙", "留个记录这只杯子",
        ).forEach { assertThat(HistorySaveIntent.isExplicit(it)).isTrue() }
        listOf(
            "不要帮我保存起来这个包", "我昨天说过存下来这个手机",
            "如果以后要留个记录会怎样？", "你说“记着”是什么意思？",
            "保存下来的记录在哪里？", "暂时不留个记录",
        ).forEach { assertThat(HistorySaveIntent.isExplicit(it)).isFalse() }
    }

    @Test fun asrHomophoneRequiresVisibleConfirmationAndNeverDirectlySaves() = runTest {
        val transcript = "系一下这个手表。"
        assertThat(HistorySaveIntent.isExplicit(transcript)).isFalse()
        assertThat(HistorySaveIntent.needsConfirmation(transcript)).isTrue()
        assertThat(HistorySaveIntent.needsConfirmation("不要系一下这个手表")).isFalse()
        assertThat(HistorySaveIntent.needsConfirmation("我昨天说过系一下这个手表")).isFalse()
        assertThat(HistorySaveIntent.needsConfirmation("系一下是什么意思？")).isFalse()

        val client = FakeClient()
        val safeArgs = HistorySaveIntent.safeArguments(
            """{"object_name":"手表","location":"","note":"","aliases":[]}""", transcript,
        )
        val call = call(HistoryToolDefinitions.REMEMBER, safeArgs)
        val unconfirmed = coordinator(client).execute(call, question(transcript), "s", {}) as HistoryToolAction.Reply
        assertThat(JSONObject(unconfirmed.outputJson).getString("status")).isEqualTo("save_not_requested")
        assertThat(client.saves).isEqualTo(0)

        val confirmed = coordinator(client).executeConfirmedSave(call, question(transcript), "s", {})
        assertThat(confirmed.state.status).isEqualTo(HistoryStatus.SAVED)
        assertThat(client.saves).isEqualTo(1)
        assertThat(client.savedRecord?.objectName).isEqualTo("手表")
        assertThat(client.savedRecord?.note).isEqualTo(transcript)
    }

    @Test fun ambiguousPhraseNameIsGroundedOnlyOnConfirmedSave() = runTest {
        val transcript = "系一下这个手表。"
        assertThat(HistorySaveIntent.objectName(transcript)).isEqualTo("这件物品")
        val repaired = JSONObject(HistorySaveIntent.safeConfirmationArguments(
            """{"object_name":"台灯","location":"桌上","note":"模型猜测","aliases":[]}""", transcript,
        ))
        assertThat(repaired.getString("object_name")).isEqualTo("手表")
        assertThat(repaired.getString("location")).isEmpty()
        assertThat(repaired.getString("note")).isEmpty()
        assertThat(JSONObject(HistorySaveIntent.safeConfirmationArguments(
            """{"object_name":"系一下这个手表","location":"","note":"","aliases":[]}""", transcript,
        )).getString("object_name")).isEqualTo("手表")
        assertThat(JSONObject(HistorySaveIntent.safeConfirmationArguments("{}", "这个蓝色的包帮我系一下"))
            .getString("object_name")).isEqualTo("蓝色的包")

        val client = FakeClient()
        val call = call(HistoryToolDefinitions.REMEMBER, "{}")
        val unconfirmed = coordinator(client).execute(call, question(transcript), "s", {}) as HistoryToolAction.Reply
        assertThat(JSONObject(unconfirmed.outputJson).getString("status")).isEqualTo("save_not_requested")
        assertThat(client.saves).isEqualTo(0)
        val confirmed = coordinator(client).executeConfirmedSave(call, question(transcript), "s", {})
        assertThat(confirmed.state.status).isEqualTo(HistoryStatus.SAVED)
        assertThat(client.savedRecord?.objectName).isEqualTo("手表")
        assertThat(client.saves).isEqualTo(1)
    }

    @Test fun saveArgumentsRepairMalformedToolOutputAndRejectUngroundedDetails() {
        val spoken = "这个蓝色的包帮我记一下，放在柜子里"
        val malformed = JSONObject(HistorySaveIntent.safeArguments("{bad json", spoken))
        assertThat(malformed.getString("object_name")).isEqualTo("蓝色的包")
        assertThat(malformed.getString("location")).isEqualTo("柜子里")
        assertThat(malformed.getJSONArray("aliases").length()).isEqualTo(0)

        val invented = JSONObject(HistorySaveIntent.safeArguments(
            """{"object_name":"红色的包","aliases":["蓝色的包","红色的包"],"location":"厨房","note":"模型猜测"}""",
            spoken,
        ))
        assertThat(invented.getString("object_name")).isEqualTo("蓝色的包")
        assertThat(invented.getString("location")).isEqualTo("柜子里")
        assertThat(invented.getJSONArray("aliases").length()).isEqualTo(1)
        assertThat(invented.getJSONArray("aliases").getString(0)).isEqualTo("蓝色的包")
        assertThat(invented.getString("note")).isEmpty()
    }

    @Test fun saveConfirmationDoesNotInventAnUnspokenLocation() = runTest {
        val client = FakeClient()
        val action = coordinator(client).execute(
            call(HistoryToolDefinitions.REMEMBER, """{"object_name":"手机","location":"","note":"","aliases":[]}"""),
            question("保存一下这个手机。"), "s", {},
        ) as HistoryToolAction.Reply
        val output = JSONObject(action.outputJson)
        assertThat(action.state.status).isEqualTo(HistoryStatus.SAVED)
        assertThat(output.getBoolean("location_provided_by_user")).isFalse()
        assertThat(output.getString("instruction")).contains("不要添加任何位置描述")
        assertThat(client.savedRecord?.location).isEmpty()
    }

    @Test fun missingTranscriptionCannotAuthorizeSaveOrPretendUserDidNotAsk() = runTest {
        val client = FakeClient()
        val call = call(HistoryToolDefinitions.REMEMBER, """{"object_name":"手机","location":"","note":"","aliases":[]}""")
        val action = coordinator(client).execute(call, question(""), "s", {}) as HistoryToolAction.Reply
        assertThat(JSONObject(action.outputJson).getString("status")).isEqualTo("save_transcript_unavailable")
        assertThat(action.state.message).contains("未转写成功")
        assertThat(client.saves).isEqualTo(0)
    }

    @Test fun disconnectedSaveIsNeverShownAsSaved() = runTest {
        val client = FakeClient().apply { saveFailure = HistoryMemoryException(HistoryFailureKind.NETWORK) }
        val action = HistoryToolCoordinator(
            client,
            HistoryMemoryConfig(true, "http://127.0.0.1:8000", "test-token", debugConnectionHints = true),
            { _, _ -> byteArrayOf(1) },
        ).execute(
            call(HistoryToolDefinitions.REMEMBER, """{"object_name":"钥匙","location":"","note":"","aliases":[]}"""),
            question("记一下这把钥匙"), "s", {},
        ) as HistoryToolAction.Reply
        val output = JSONObject(action.outputJson)
        assertThat(output.getString("status")).isEqualTo("network_error")
        assertThat(output.getString("operation")).isEqualTo("save")
        assertThat(action.state.status).isEqualTo(HistoryStatus.ERROR)
        assertThat(action.state.message).contains("未确认保存")
        assertThat(action.state.message).contains("无线调试转发")
        assertThat(client.saves).isEqualTo(1)
    }

    @Test fun disconnectedSearchCannotClaimHistoryIsEmpty() = runTest {
        val client = FakeClient().apply { searchFailure = HistoryMemoryException(HistoryFailureKind.NETWORK) }
        val action = HistoryToolCoordinator(
            client,
            HistoryMemoryConfig(true, "https://history.example", "test-token", debugConnectionHints = true),
            { _, _ -> byteArrayOf(1) },
        ).execute(searchCall(false), question("我的钥匙在哪里？"), "s", {}) as HistoryToolAction.Reply
        val output = JSONObject(action.outputJson)
        assertThat(output.getString("operation")).isEqualTo("search")
        assertThat(output.getString("status")).isEqualTo("network_error")
        assertThat(output.getString("instruction")).contains("不得说没有保存过")
        assertThat(action.state.status).isEqualTo(HistoryStatus.ERROR)
        assertThat(action.state.message).contains("查询未完成")
        assertThat(action.state.message).doesNotContain("USB")
    }

    @Test fun authenticationFailureIsDistinguishedFromMissingRecord() = runTest {
        val client = FakeClient().apply { searchFailure = HistoryMemoryException(HistoryFailureKind.AUTHENTICATION) }
        val action = coordinator(client).execute(searchCall(false), question("台灯在哪？"), "s", {}) as HistoryToolAction.Reply
        assertThat(JSONObject(action.outputJson).getString("status")).isEqualTo("authentication_failed")
        assertThat(action.state.message).contains("身份验证失败")
    }

    @Test fun guessedLocationCannotBeMarkedAsUserRecorded() = runTest {
        val client = FakeClient()
        coordinator(client).execute(
            call(HistoryToolDefinitions.REMEMBER, """{"object_name":"钥匙","location":"玄关","note":"模型猜的备注","aliases":[]}"""),
            question("记一下，这把钥匙放在书桌的右边。"), "s", {},
        )
        assertThat(client.savedRecord!!.location).isEmpty()
        assertThat(client.savedRecord!!.note).isEqualTo("记一下，这把钥匙放在书桌的右边。")
    }

    @Test fun noMatchCannotStartComparison() = runTest {
        val client = FakeClient().apply { result = HistorySearchResult(HistorySearchStatus.NO_MATCH) }
        val action = coordinator(client) { _, _ -> error("must not compose missing history") }
            .execute(searchCall(), question(), "s", {}) as HistoryToolAction.Reply
        assertThat(action.state.status).isEqualTo(HistoryStatus.NO_MATCH)
    }

    @Test fun unavailableLowRankedEvidenceDoesNotBlockValidSelectedPictures() = runTest {
        val client = FakeClient().apply { result = result.copy(unavailableRecordsCount = 1) }
        val action = coordinator(client).execute(searchCall(), question(), "s", {}) as HistoryToolAction.Compare
        assertThat(action.records).containsExactlyElementsIn(client.result.records)
        assertThat(JSONObject(action.outputJson).getBoolean("coverage_incomplete")).isTrue()
        assertThat(action.coverageWarning).contains("不完整")
    }

    @Test fun missingCurrentImageBlocksIdentityComparison() = runTest {
        val action = coordinator(FakeClient()) { _, _ -> error("must not compare without current pixels") }
            .execute(searchCall(), question().copy(current = null), "s", {}) as HistoryToolAction.Reply
        assertThat(JSONObject(action.outputJson).getString("status")).isEqualTo("missing_current_frame")
    }

    @Test fun badImageDecodeBlocksMediaAndReturnsExplanation() = runTest {
        val action = coordinator(FakeClient()) { _, _ -> throw IllegalArgumentException("历史照片无法解码，不能比较。") }
            .execute(searchCall(), question(), "s", {}) as HistoryToolAction.Reply
        assertThat(action.state.status).isEqualTo(HistoryStatus.ERROR)
    }

    @Test fun successfulSearchPassesOnlyRetrievedPixelsAndFrozenQuestion() = runTest {
        val client = FakeClient()
        val frozen = question()
        var composedCurrent: HistoryCapturedFrame? = null
        var composedRecords: List<HistoryMemoryRecord> = emptyList()
        val action = coordinator(client) { current, records ->
            composedCurrent = current; composedRecords = records; byteArrayOf(8, 9)
        }.execute(searchCall(), frozen, "s", {}) as HistoryToolAction.Compare
        assertThat(action.questionPcm).isSameInstanceAs(frozen.pcm)
        assertThat(composedCurrent).isSameInstanceAs(frozen.current)
        assertThat(composedRecords).containsExactlyElementsIn(client.result.records)
        assertThat(action.jpeg.toList()).containsExactly(8.toByte(), 9.toByte()).inOrder()
        assertThat(client.searches).isEqualTo(1)
        assertThat(client.saves).isEqualTo(0)
        assertThat(JSONObject(action.outputJson).getBoolean("current_frame_present")).isTrue()
    }

    @Test fun historyOnlyQuestionCanUseEvidenceWithoutCurrentCamera() = runTest {
        val action = coordinator(FakeClient()).execute(searchCall(false), question("上次看见台灯是什么时候？").copy(current = null), "s", {})
        assertThat(action).isInstanceOf(HistoryToolAction.Compare::class.java)
    }

    @Test fun locationQueryUsesOriginalAsrAndDoesNotUploadUnrelatedCamera() = runTest {
        val client = FakeClient()
        var composedCurrent: HistoryCapturedFrame? = question().current
        coordinator(client) { current, _ -> composedCurrent = current; byteArrayOf(1) }
            .execute(searchCall(false), question("我上次把白色台灯放在哪里了？"), "s", {})
        assertThat(client.lastSearch!!.rawQuery).isEqualTo("我上次把白色台灯放在哪里了？")
        assertThat(client.lastSearch!!.intent).isEqualTo(HistorySearchIntent.FIND_LAST_LOCATION)
        assertThat(client.lastSearch!!.queryImage).isNull()
        assertThat(composedCurrent).isNull()
    }

    @Test fun explicitVisualReferenceSearchUsesOnlyFrozenCurrentPixels() = runTest {
        val client = FakeClient()
        val frozen = question("我上次把这盏台灯放在哪了？")
        coordinator(client).execute(searchCall(false), frozen, "s", {})
        assertThat(client.lastSearch!!.queryImage).isSameInstanceAs(frozen.current!!.jpeg)
        assertThat(client.lastSearch!!.intent).isEqualTo(HistorySearchIntent.FIND_LAST_LOCATION)
    }

    @Test fun missingAsrCannotBorrowPreviousQuestionOrAuthorizeCameraSearch() = runTest {
        val client = FakeClient()
        coordinator(client).execute(searchCall(false), question(""), "s", {})
        assertThat(client.lastSearch!!.rawQuery).isNull()
        assertThat(client.lastSearch!!.queryImage).isNull()
    }

    @Test fun timeReferenceIsNotVisualReference() = runTest {
        val client = FakeClient()
        coordinator(client).execute(searchCall(false), question("这个月我把台灯放哪了？"), "s", {})
        assertThat(client.lastSearch!!.queryImage).isNull()
    }

    @Test fun indexingEmptyResultCannotBeReportedAsNoStoredRecords() = runTest {
        val client = FakeClient().apply {
            result = HistorySearchResult(HistorySearchStatus.NO_MATCH, retrieval = HistoryRetrievalInfo(
                "hybrid", HistoryRetrievalStatus.INDEXING, pendingRecordsCount = 2, warnings = listOf("indexing_pending"),
            ))
        }
        val action = coordinator(client).execute(searchCall(false), question("台灯放哪了？"), "s", {}) as HistoryToolAction.Reply
        assertThat(JSONObject(action.outputJson).getString("status")).isEqualTo("retrieval_incomplete")
        assertThat(action.state.status).isEqualTo(HistoryStatus.EVIDENCE_UNAVAILABLE)
        assertThat(action.state.message).contains("建立索引")
    }

    @Test fun degradedEmptyResultCannotBeReportedAsNoMatch() = runTest {
        val client = FakeClient().apply {
            result = HistorySearchResult(HistorySearchStatus.NO_MATCH, retrieval = HistoryRetrievalInfo(
                "metadata", HistoryRetrievalStatus.DEGRADED, warnings = listOf("embedding_unavailable"),
            ))
        }
        val action = coordinator(client).execute(searchCall(false), question("台灯放哪了？"), "s", {}) as HistoryToolAction.Reply
        assertThat(JSONObject(action.outputJson).getString("status")).isEqualTo("retrieval_incomplete")
    }

    @Test fun partialLocationResultMayBeComparedButCannotClaimItIsLatest() = runTest {
        val client = FakeClient().apply {
            result = result.copy(retrieval = HistoryRetrievalInfo(
                "hybrid", HistoryRetrievalStatus.DEGRADED, pendingRecordsCount = 1, failedRecordsCount = 1,
                warnings = listOf("indexing_pending", "indexing_failed"),
            ))
        }
        val action = coordinator(client).execute(searchCall(false), question("台灯放哪了？"), "s", {}) as HistoryToolAction.Compare
        val output = JSONObject(action.outputJson)
        assertThat(output.getBoolean("must_not_claim_latest")).isTrue()
        assertThat(output.getJSONObject("retrieval").getInt("pending_records_count")).isEqualTo(1)
        assertThat(output.getString("instruction")).contains("不能声称已经找全")
    }

    @Test fun uncalibratedSimilarityRequiresModelToVerifyCandidates() = runTest {
        val client = FakeClient().apply {
            result = result.copy(retrieval = HistoryRetrievalInfo(
                "multimodal", HistoryRetrievalStatus.READY, warnings = listOf("uncalibrated_similarity"),
            ))
        }
        val action = coordinator(client).execute(searchCall(), question(), "s", {}) as HistoryToolAction.Compare
        assertThat(JSONObject(action.outputJson).getString("instruction")).contains("不得把相似度分数当作事实")
        assertThat(JSONObject(action.outputJson).getBoolean("similarity_uncalibrated")).isTrue()
        assertThat(JSONObject(action.outputJson).getBoolean("coverage_incomplete")).isFalse()
        assertThat(action.coverageWarning).isNotNull()
    }

    @Test fun malformedArgumentsAreVisibleErrorNotNoMatch() = runTest {
        val action = coordinator(FakeClient()).execute(call(HistoryToolDefinitions.SEARCH, "{"), question(), "s", {})
            as HistoryToolAction.Reply
        assertThat(action.state.status).isEqualTo(HistoryStatus.ERROR)
    }

    private fun searchCall(current: Boolean = true) = call(HistoryToolDefinitions.SEARCH,
        """{"query":"台灯","time_expression":"最近七天","compare_current":$current}""")
    private fun call(name: String, args: String) = ExecutorToolCall(name, "call", args, "response", 1)
    private fun question(text: String = "这个台灯和上次的是同一个吗？") = HistoryQuestionSnapshot(
        ByteArray(48_000), HistoryCapturedFrame(byteArrayOf(1), 1_800_000_000_000L, 100), 1, text,
    )

    companion object {
        private fun record() = HistoryMemoryRecord("r", "prior-session", 1_799_900_000_000L, "台灯", jpeg = byteArrayOf(2))
    }
}
