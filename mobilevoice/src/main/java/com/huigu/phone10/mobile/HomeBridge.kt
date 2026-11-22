package com.huigu.phone10.mobile

import com.google.gson.Gson
import com.google.gson.JsonParser
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/**
 * 家里版：不连 Operit，连阿辞的服务器（/companion/call/ 下面那三个口子）。
 * 对外照 OperitBridge 的形状（listChats / reply / readReplies / close），别的文件只换构造。
 * 一个 HomeBridge 就是一通电话：session 随机生成，close() 告诉服务器挂了，服务器写摘要。
 */
class HomeBridge(settings: MobileSettings) : AutoCloseable {
    private val base = settings.homeUrl.orEmpty().trim().trimEnd('/').ifBlank { DEFAULT_URL }
    private val token = settings.homeToken.orEmpty().trim()
    private val session = UUID.randomUUID().toString()
    @Volatile private var talked = false
    @Volatile private var closed = false

    private fun request(path: String) = Request.Builder().url(base + path).header("Authorization", "Bearer $token")

    private fun body(text: String? = null) =
        gson.toJson(if (text == null) mapOf("session" to session) else mapOf("session" to session, "text" to text))
            .toRequestBody(JSON)

    /** 打断或挂断时协程被取消，顺手 cancel 掉正在读的请求，服务器那头就知道被打断了。 */
    private suspend fun <T> call(req: Request, read: suspend (Response) -> T): T = coroutineScope {
        val c = client.newCall(req)
        val watcher = launch(Dispatchers.IO) { try { awaitCancellation() } finally { c.cancel() } }
        try {
            withContext(Dispatchers.IO) { c.execute().use { read(it) } }
        } catch (failure: IOException) {
            currentCoroutineContext().ensureActive()
            throw IllegalStateException("HOME_UNAVAILABLE")
        } finally {
            watcher.cancel()
        }
    }

    suspend fun listChats(): List<OperitChat> {
        check(base.startsWith("https://") && token.isNotBlank()) { "HOME_NOT_CONFIGURED" }
        call(request("/companion/call/check").get().build()) { r -> check(r.isSuccessful) { "HOME_HTTP_${r.code}" } }
        return listOf(OperitChat(CHAT_ID, "阿辞"))
    }

    suspend fun reply(chatId: String, text: String, onChunk: suspend (String) -> Unit) {
        require(text.isNotBlank() && text.length <= 4000) { "HOME_INVALID_REQUEST" }
        talked = true
        call(request("/companion/call/reply").post(body(text)).build()) { r ->
            check(r.isSuccessful) { "HOME_HTTP_${r.code}" }
            val source = r.body.source()
            var done = false
            while (!done) {
                val line = source.readUtf8Line() ?: break
                if (line.isBlank()) continue
                val event = JsonParser.parseString(line).asJsonObject
                when {
                    event.has("d") -> onChunk(event["d"].asString)
                    event.has("end") -> done = true
                    event.has("err") -> error("HOME_MODEL_FAILED")
                }
            }
            check(done) { "HOME_INCOMPLETE" }
        }
    }

    /** 只听回复、群聊配音要从前端读消息，家里版没有。 */
    @Suppress("UNUSED_PARAMETER")
    internal suspend fun readReplies(chatId: String, after: Long?, requireRoles: Boolean = false): ReplySnapshot =
        error("HOME_LISTEN_UNSUPPORTED")

    override fun close() {
        if (closed) return
        closed = true
        if (!talked) return
        client.newCall(request("/companion/call/end").post(body()).build()).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {}
            override fun onResponse(call: Call, response: Response) { response.close() }
        })
    }

    companion object {
        const val CHAT_ID = "aci-home"
        const val DEFAULT_URL = "https://43-166-23-196.sslip.io"
        private val JSON = "application/json; charset=utf-8".toMediaType()
        private val gson = Gson()
        private val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS).readTimeout(90, TimeUnit.SECONDS).build()
    }
}
