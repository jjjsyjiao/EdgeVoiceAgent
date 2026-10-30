package com.edgevoice.agent.core.vision

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * 一帧（端侧时间戳 + JPEG 字节）。
 *
 * 同一类型承载「待上传」与「已召回」两个方向：字段完全一致（ts 对齐时间窗，jpeg 复用上行编码产物）。
 */
class SessionFrame(val tsMs: Long, val jpeg: ByteArray)

/**
 * V3 云端会话帧留存契约（同仓 server/，FastAPI + 磁盘帧环）。
 *
 * 内容边界（已与用户确认）：会话**原始 JPEG 帧**，默认开；服务端 TTL 24h + 全局容量上限兜底。
 * 所有失败一律静默降级 —— 留存/召回是增强能力，绝不能让主链路因远端不可用而崩。
 */
interface ISessionFrameClient {
    /** 批量上传；返回服务端接收条数（null = 失败，调用方丢弃本批）。 */
    suspend fun upload(sessionId: String, frames: List<SessionFrame>): Int?

    /** 超窗召回（闭区间）；按时间升序返回抽帧（空 = 无命中/失败）。 */
    suspend fun recall(
        fromMs: Long,
        toMs: Long,
        maxFrames: Int,
        sessionId: String?,
    ): List<SessionFrame>
}

/** 未配置端点时的空实现（隐私关闭 / 本地模式 / 单测替代）。 */
object NoOpSessionFrameClient : ISessionFrameClient {
    override suspend fun upload(sessionId: String, frames: List<SessionFrame>): Int? = null

    override suspend fun recall(
        fromMs: Long,
        toMs: Long,
        maxFrames: Int,
        sessionId: String?,
    ): List<SessionFrame> = emptyList()
}

/**
 * HTTP 客户端实现：对接 FastAPI 的 POST /session/frames、POST /session/recall。
 *
 * 与 [com.edgevoice.agent.core.memory.HttpLongTermMemory] 同一 OkHttp 范式：
 * `withContext(IO)` + `runCatching` 静默降级 + JSONObject 手写载荷。
 *
 * Base64 用 [java.util.Base64]（API 26+ 可用，且 JVM 单测非 stub），与 server 侧裸 base64 契约一致。
 */
class HttpSessionFrameClient(
    private val baseUrl: String,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build(),
) : ISessionFrameClient {

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    private suspend fun post(path: String, body: JSONObject): JSONObject? = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url(baseUrl.trimEnd('/') + path)
                .post(body.toString().toRequestBody(jsonMedia))
                .build()
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                resp.body?.string()?.takeIf { it.isNotBlank() }?.let { JSONObject(it) }
            }
        }.getOrNull()
    }

    override suspend fun upload(sessionId: String, frames: List<SessionFrame>): Int? {
        if (frames.isEmpty()) return 0
        val arr = JSONArray()
        for (f in frames) {
            arr.put(
                JSONObject()
                    .put("ts", f.tsMs)
                    .put("image", Base64.getEncoder().encodeToString(f.jpeg)),
            )
        }
        val res = post(
            "/session/frames",
            JSONObject().put("session_id", sessionId).put("frames", arr),
        ) ?: return null
        return res.optInt("accepted", 0)
    }

    override suspend fun recall(
        fromMs: Long,
        toMs: Long,
        maxFrames: Int,
        sessionId: String?,
    ): List<SessionFrame> {
        val body = JSONObject()
            .put("from_ts", fromMs)
            .put("to_ts", toMs)
            .put("max_frames", maxFrames)
        if (!sessionId.isNullOrBlank()) body.put("session_id", sessionId)
        val res = post("/session/recall", body) ?: return emptyList()
        val arr = res.optJSONArray("frames") ?: return emptyList()
        val out = ArrayList<SessionFrame>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val jpeg = runCatching { Base64.getDecoder().decode(o.optString("image")) }.getOrNull() ?: continue
            if (jpeg.isEmpty()) continue
            out.add(SessionFrame(o.optLong("ts"), jpeg))
        }
        return out
    }
}
