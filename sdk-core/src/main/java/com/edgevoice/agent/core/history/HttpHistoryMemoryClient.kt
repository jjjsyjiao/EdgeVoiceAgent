package com.edgevoice.agent.core.history

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.io.InterruptedIOException
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** 鉴权、可取消的主动记物客户端。服务失败与无匹配是两种不同结果。 */
class HttpHistoryMemoryClient(
    baseUrl: String,
    private val apiToken: String,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .writeTimeout(12, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build(),
) : HistoryMemoryClient {
    private val endpoint: HttpUrl = baseUrl.trimEnd('/').toHttpUrl()
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    init {
        require(apiToken.isNotBlank()) { "Visual memory token is required" }
        require(endpoint.username.isEmpty() && endpoint.password.isEmpty() && endpoint.query == null && endpoint.fragment == null) {
            "Visual memory endpoint must not contain credentials, query, or fragment"
        }
    }

    override suspend fun save(record: HistoryMemoryRecord): HistoryMemoryRecord {
        if (record.recordId.isBlank() || record.sessionId.isBlank() || record.objectName.isBlank() ||
            record.capturedAtMs <= 0L || record.jpeg.isEmpty() || record.jpeg.size > MAX_IMAGE_BYTES
        ) throw HistoryMemoryException(HistoryFailureKind.INVALID_REQUEST)
        val body = JSONObject()
            .put("record_id", record.recordId)
            .put("session_id", record.sessionId)
            .put("captured_at_ms", record.capturedAtMs)
            .put("object_name", record.objectName)
            .put("aliases", JSONArray(record.aliases))
            .put("location", record.location)
            .put("note", record.note)
            .put("image_base64", Base64.getEncoder().encodeToString(record.jpeg))
        val result = request("records", body)
        return parse {
            if (!result.getBoolean("ok")) invalidResponse()
            parseRecord(result.getJSONObject("record")).also { saved ->
                if (saved.recordId != record.recordId || saved.sessionId != record.sessionId ||
                    saved.capturedAtMs != record.capturedAtMs || !saved.jpeg.contentEquals(record.jpeg)
                ) invalidResponse()
            }
        }
    }

    override suspend fun search(query: String, fromTsMs: Long?, toTsMs: Long?, limit: Int): HistorySearchResult =
        search(HistorySearchRequest(query, fromTsMs = fromTsMs, toTsMs = toTsMs, limit = limit))

    override suspend fun search(request: HistorySearchRequest): HistorySearchResult {
        val (query, rawQuery, queryImage, intent, fromTsMs, toTsMs, limit) = request
        if (query.isBlank() || query.length > 120 || (rawQuery != null && (rawQuery.isBlank() || rawQuery.length > 2_048)) ||
            (queryImage != null && (queryImage.isEmpty() || queryImage.size > MAX_IMAGE_BYTES)) ||
            limit !in 1..3 || (fromTsMs != null && fromTsMs < 0) ||
            (toTsMs != null && toTsMs < 0) || (fromTsMs != null && toTsMs != null && fromTsMs >= toTsMs)
        ) throw HistoryMemoryException(HistoryFailureKind.INVALID_REQUEST)
        val body = JSONObject().put("query", query).put("limit", limit).put("intent", intent.wireValue)
        rawQuery?.let { body.put("raw_query", it) }
        queryImage?.let { body.put("query_image_base64", Base64.getEncoder().encodeToString(it)) }
        fromTsMs?.let { body.put("from_ts_ms", it) }
        toTsMs?.let { body.put("to_ts_ms", it) }
        val response = request("search", body)
        return parse {
            val status = when (response.getString("status")) {
                "matched" -> HistorySearchStatus.MATCHED
                "no_match" -> HistorySearchStatus.NO_MATCH
                "evidence_unavailable" -> HistorySearchStatus.EVIDENCE_UNAVAILABLE
                else -> invalidResponse()
            }
            val array = response.getJSONArray("records")
            if (array.length() > limit) invalidResponse()
            val records = List(array.length()) { parseRecord(array.getJSONObject(it)) }
            // 即使服务端实现回归，也不能把无图片的 matched 继续交给模型比较。
            if ((status == HistorySearchStatus.MATCHED) != records.isNotEmpty()) invalidResponse()
            if (records.map { it.recordId }.distinct().size != records.size) invalidResponse()
            if (records.any { (fromTsMs != null && it.capturedAtMs < fromTsMs) ||
                    (toTsMs != null && it.capturedAtMs >= toTsMs) }) invalidResponse()
            val coverage = response.getJSONObject("coverage")
            val unavailableCount = response.optInt("unavailable_records_count", 0)
            if (unavailableCount < 0) invalidResponse()
            HistorySearchResult(
                status = status,
                records = records,
                coverage = HistoryCoverage(
                    fromTsMs = coverage.nullableLong("from_ts_ms"),
                    toTsMs = coverage.nullableLong("to_ts_ms"),
                    retentionDays = coverage.getInt("retention_days"),
                    recordsCount = coverage.getInt("records_count"),
                    continuous = coverage.optBoolean("continuous", false),
                ),
                unavailableRecordsCount = unavailableCount,
                retrieval = if (response.has("retrieval") && !response.isNull("retrieval")) parseRetrieval(response.getJSONObject("retrieval")) else null,
            )
        }
    }

    private fun parseRetrieval(json: JSONObject): HistoryRetrievalInfo {
        val mode = json.getString("mode")
        if (mode !in setOf("metadata", "multimodal", "hybrid")) invalidResponse()
        val status = when (json.getString("status")) {
            "ready" -> HistoryRetrievalStatus.READY
            "indexing" -> HistoryRetrievalStatus.INDEXING
            "degraded" -> HistoryRetrievalStatus.DEGRADED
            "unavailable" -> HistoryRetrievalStatus.UNAVAILABLE
            else -> invalidResponse()
        }
        val pending = json.optInt("pending_records_count", 0)
        val failed = json.optInt("failed_records_count", 0)
        if (pending < 0 || failed < 0) invalidResponse()
        val warningsJson = json.optJSONArray("warnings") ?: JSONArray()
        if (warningsJson.length() > SAFE_WARNING_CODES.size) invalidResponse()
        val warnings = List(warningsJson.length()) { warningsJson.getString(it) }
        if (warnings.any { it !in SAFE_WARNING_CODES }) invalidResponse()
        val traceId = if (json.has("trace_id") && !json.isNull("trace_id")) json.getString("trace_id") else null
        if (traceId != null && !traceId.matches(Regex("[A-Za-z0-9_-]{1,80}"))) invalidResponse()
        return HistoryRetrievalInfo(mode, status, pending, failed, warnings.distinct(), traceId)
    }

    override suspend fun delete(recordId: String): Boolean {
        if (recordId.isBlank()) throw HistoryMemoryException(HistoryFailureKind.INVALID_REQUEST)
        val url = endpoint.newBuilder().addPathSegment("visual-memory").addPathSegment("records")
            .addPathSegment(recordId).build()
        val response = execute(Request.Builder().url(url).header("Authorization", "Bearer $apiToken").delete().build())
        return parse {
            if (!response.getBoolean("ok")) invalidResponse()
            response.getBoolean("deleted")
        }
    }

    private suspend fun request(path: String, body: JSONObject): JSONObject {
        val url = endpoint.newBuilder().addPathSegment("visual-memory").addPathSegment(path).build()
        return execute(
            Request.Builder().url(url).header("Authorization", "Bearer $apiToken")
                .post(body.toString().toRequestBody(jsonMedia)).build(),
        )
    }

    private suspend fun execute(request: Request): JSONObject = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(ioFailure(e))
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    val result = response.use {
                        if (!it.isSuccessful) {
                            val kind = when (it.code) {
                                401, 403 -> HistoryFailureKind.AUTHENTICATION
                                408, 429, in 500..599 -> HistoryFailureKind.UNAVAILABLE
                                400, 413, 422 -> HistoryFailureKind.INVALID_REQUEST
                                else -> HistoryFailureKind.HTTP
                            }
                            throw HistoryMemoryException(kind, it.code)
                        }
                        val body = it.body ?: invalidResponse()
                        if (body.contentLength() > MAX_RESPONSE_BYTES) invalidResponse()
                        val source = body.source()
                        source.request(MAX_RESPONSE_BYTES + 1L)
                        if (source.buffer.size > MAX_RESPONSE_BYTES) invalidResponse()
                        parse { JSONObject(source.readUtf8()) }
                    }
                    if (continuation.isActive) continuation.resume(result)
                } catch (error: Throwable) {
                    if (continuation.isActive) continuation.resumeWithException(
                        when (error) {
                            is HistoryMemoryException -> error
                            is IOException -> ioFailure(error)
                            else -> HistoryMemoryException(HistoryFailureKind.INVALID_RESPONSE)
                        },
                    )
                }
            }
        })
    }

    private fun parseRecord(json: JSONObject): HistoryMemoryRecord {
        val image = json.getString("image_base64")
        if (image.length > MAX_IMAGE_BASE64_CHARS) invalidResponse()
        val jpeg = Base64.getDecoder().decode(image)
        if (jpeg.isEmpty() || jpeg.size > MAX_IMAGE_BYTES) invalidResponse()
        val recordId = json.getString("record_id")
        val sessionId = json.getString("session_id")
        val name = json.getString("object_name")
        val capturedAt = json.getLong("captured_at_ms")
        val recordedAt = json.getLong("recorded_at_ms")
        if (recordId.isBlank() || sessionId.isBlank() || name.isBlank() || capturedAt <= 0L || recordedAt <= 0L) invalidResponse()
        val aliases = json.optJSONArray("aliases") ?: JSONArray()
        return HistoryMemoryRecord(
            recordId = recordId,
            sessionId = sessionId,
            capturedAtMs = capturedAt,
            objectName = name,
            recordedAtMs = recordedAt,
            aliases = List(aliases.length()) { aliases.getString(it) },
            location = json.optString("location", ""),
            note = json.optString("note", ""),
            jpeg = jpeg,
            source = json.optString("source", "user_recorded"),
            score = json.optDouble("score", 1.0),
        )
    }

    private inline fun <T> parse(block: () -> T): T = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (known: HistoryMemoryException) {
        throw known
    } catch (_: Exception) {
        invalidResponse()
    }

    private fun JSONObject.nullableLong(name: String): Long? = if (has(name) && !isNull(name)) getLong(name) else null
    private fun invalidResponse(): Nothing = throw HistoryMemoryException(HistoryFailureKind.INVALID_RESPONSE)
    private fun ioFailure(error: IOException) = HistoryMemoryException(
        if (error is InterruptedIOException) HistoryFailureKind.TIMEOUT else HistoryFailureKind.NETWORK,
    )

    companion object {
        const val MAX_IMAGE_BYTES = 256 * 1024
        private val SAFE_WARNING_CODES = setOf(
            "indexing_pending", "indexing_failed", "embedding_unavailable", "rerank_unavailable",
            "partial_evidence", "uncalibrated_similarity", "keyword_only", "time_scope_incomplete",
        )
        private const val MAX_IMAGE_BASE64_CHARS = (MAX_IMAGE_BYTES + 2) / 3 * 4
        private const val MAX_RESPONSE_BYTES = 2L * 1024 * 1024
    }
}
