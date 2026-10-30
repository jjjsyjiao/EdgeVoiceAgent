package com.edgevoice.agent.core.memory

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** 长期记忆检索命中项。 */
data class MemoryHit(val id: String, val text: String, val score: Float)

/**
 * Tier3 云端长期记忆契约（存于同仓 server/，FastAPI + Chroma）。
 *
 * 内容边界（已与用户确认）：**仅用户偏好 / 关键事实**，不做会话回放、不做视觉长期记忆。
 * 索引策略层（BGE-M3 单文本）在服务端，端侧只按契约收发。
 */
interface ILongTermMemory {
    suspend fun remember(text: String, kind: String = "preference", source: String = "explicit"): Boolean
    suspend fun recall(query: String, topK: Int = 3): List<MemoryHit>
    suspend fun distill(turns: List<String>): List<String>
}

/** 未配置云端地址时的空实现（本地端侧模式 / 隐私关闭长期记忆）。 */
object NoOpLongTermMemory : ILongTermMemory {
    override suspend fun remember(text: String, kind: String, source: String): Boolean = false
    override suspend fun recall(query: String, topK: Int): List<MemoryHit> = emptyList()
    override suspend fun distill(turns: List<String>): List<String> = emptyList()
}

/**
 * HTTP 客户端实现：对接 FastAPI 的 /memory/add、/memory/search、/memory/distill。
 * 所有失败都静默降级（记忆是增强能力，绝不能让主链路因远端不可用而崩）。
 */
class HttpLongTermMemory(
    private val baseUrl: String,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build(),
) : ILongTermMemory {

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

    override suspend fun remember(text: String, kind: String, source: String): Boolean {
        val res = post(
            "/memory/add",
            JSONObject().put("text", text).put("kind", kind).put("source", source),
        ) ?: return false
        return res.optBoolean("ok", false)
    }

    override suspend fun recall(query: String, topK: Int): List<MemoryHit> {
        val res = post("/memory/search", JSONObject().put("query", query).put("top_k", topK)) ?: return emptyList()
        val arr = res.optJSONArray("hits") ?: return emptyList()
        val out = ArrayList<MemoryHit>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out.add(MemoryHit(o.optString("id"), o.optString("text"), o.optDouble("score", 0.0).toFloat()))
        }
        return out
    }

    override suspend fun distill(turns: List<String>): List<String> {
        val res = post("/memory/distill", JSONObject().put("turns", JSONArray(turns))) ?: return emptyList()
        val arr = res.optJSONArray("preferences") ?: return emptyList()
        val out = ArrayList<String>()
        for (i in 0 until arr.length()) {
            arr.optString(i).takeIf { it.isNotBlank() }?.let { out.add(it) }
        }
        return out
    }
}
