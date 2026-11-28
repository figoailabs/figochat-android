package com.figolabs.figochat

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * Thin client over the Figo AI public REST APIs:
 *  - POST /api/v1/chat/assistant/{assistantId}   chat with the assistant
 *  - POST /api/v1/stt                            speech to text (multipart, field `audio`)
 *  - POST /api/v1/cdn/upload/ocr/{assistantId}   upload files to the CDN (images are OCR'd)
 *  - GET  /api/v1/realtime/config                public Pusher settings for live agent messages
 *  - GET  /api/v1/sdk/assistant/{assistantId}    the assistant's name, persona, waiting and intro messages
 *  - GET  /api/v1/chat/messages/{assistantId}/{threadId}   the messages of a conversation, oldest first
 */
class FigoChatClient internal constructor(config: FigoChatConfig) {

    /** Settings used for each request; [FigoChat.setCustomerDetails] swaps in the customer's details. */
    @Volatile
    private var config: FigoChatConfig = config

    internal fun updateConfig(config: FigoChatConfig) {
        this.config = config
    }

    private val http = OkHttpClient.Builder()
        .callTimeout(config.timeoutMillis, TimeUnit.MILLISECONDS)
        .readTimeout(config.timeoutMillis, TimeUnit.MILLISECONDS)
        .build()

    /** Thread id of the current conversation, once the first reply has arrived. */
    @Volatile
    var threadId: String? = config.threadId
        private set

    /** "ai" or "liveagent". */
    @Volatile
    var threadMode: String? = null
        private set

    /** Forget the current thread so the next message starts a new conversation. */
    fun resetThread() {
        threadId = null
        threadMode = null
    }

    /** Continues [id] (null: none) on the next message; used when a conversation session is resumed. */
    internal fun useThread(id: String?) {
        if (id == threadId) return
        threadId = id
        threadMode = null
    }

    /**
     * Sends a message. CDN urls of uploaded files are appended to the question so the assistant can read
     * them (the API adds the OCR text of uploaded images).
     */
    suspend fun sendMessage(text: String, attachmentUrls: List<String> = emptyList()): FigoChatReply = withContext(Dispatchers.IO) {
        val question = (listOf(text.trim()) + attachmentUrls).filter { it.isNotEmpty() }.joinToString("\n")
        if (question.isEmpty()) throw FigoChatException("Message is empty.", "EMPTY")

        val body = JSONObject()
            .put("question", question)
            .put("fullName", config.fullName)
            .put("emailAddress", config.emailAddress)
            .put("phoneNumber", config.mobilePhone)
        threadId?.let { body.put("threadId", it) }

        val request = request(url("api", "v1", "chat", "assistant", config.assistantId))
            .post(body.toString().toRequestBody(JSON))
            .build()

        execute(request) { res ->
            val json = parse(res)
            res.header(HEADER_THREAD_ID)?.takeIf { it.isNotEmpty() }?.let { threadId = it }
            res.header(HEADER_THREAD_MODE)?.takeIf { it.isNotEmpty() }?.let { threadMode = it }
            FigoChatReply(json.optString("responseData", ""), threadId, threadMode)
        }
    }

    /** Transcribes a recorded audio file (AAC in an .m4a container) to text. */
    suspend fun transcribe(audio: File, mimeType: String = "audio/mp4"): String = withContext(Dispatchers.IO) {
        val form = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("audio", audio.name, audio.asRequestBody(mimeType.toMediaType()))
            .build()
        val url = url("api", "v1", "stt").newBuilder().addQueryParameter("mimetype", mimeType).build()

        execute(request(url).post(form).build()) { res -> parse(res).optString("responseData", "").trim() }
    }

    /**
     * Uploads files to the CDN and returns one url per file, in order. Images are OCR'd by the API, so
     * sending their urls in a chat message lets the assistant read them.
     */
    suspend fun uploadFiles(files: List<FigoUploadFile>): List<String> = withContext(Dispatchers.IO) {
        if (files.isEmpty()) return@withContext emptyList()
        val payload = JSONArray()
        files.forEach { payload.put(JSONObject().put("extension", it.extension).put("base64String", Base64.getEncoder().encodeToString(it.bytes))) }

        val request = request(url("api", "v1", "cdn", "upload", "ocr", config.assistantId))
            .post(payload.toString().toRequestBody(JSON))
            .build()

        execute(request) { res ->
            // a partial failure still returns the urls that did upload, with null in the failed slots
            val json = parse(res, allowFailureCode = true)
            val data = json.optJSONArray("responseData")
            val urls = (0 until (data?.length() ?: 0)).map { data!!.optString(it, "").takeUnless { u -> u == "null" } ?: "" }
            if (urls.isEmpty() || urls.any { it.isEmpty() }) {
                throw FigoChatException(json.optString("responseMessage", "Upload failed."), json.optString("responseCode", "UPLOAD"))
            }
            urls
        }
    }

    /** The assistant's display details: name, persona, waiting message, intro message, disclaimer and company links. */
    suspend fun getAssistant(): FigoAssistantInfo = withContext(Dispatchers.IO) {
        execute(request(url("api", "v1", "sdk", "assistant", config.assistantId)).get().build()) { res ->
            val data = parse(res).optJSONObject("responseData") ?: return@execute FigoAssistantInfo()
            fun field(obj: JSONObject, name: String) = if (obj.isNull(name)) null else obj.optString(name)
            val company = data.optJSONObject("company")?.let {
                FigoCompanyInfo(termsAndCondition = field(it, "termsAndCondition"), privacyPolicy = field(it, "privacyPolicy"))
            }
            FigoAssistantInfo(
                assistantName = field(data, "assistantName"),
                persona = field(data, "persona"),
                waitingMessage = field(data, "waitingMessage"),
                introMessage = field(data, "introMessage"),
                disclaimerMessage = field(data, "disclaimerMessage"),
                company = company,
            )
        }
    }

    /**
     * The messages of a conversation, oldest first: the user's on the right ([FigoMessageRole.USER]) and the assistant's
     * or live agent's ([FigoMessageRole.ASSISTANT], HTML) on the left. An unknown thread, one that isn't this assistant's
     * or one with no messages gives an empty list; other failures throw [FigoChatException].
     */
    suspend fun getMessages(threadId: String? = this.threadId): List<FigoChatMessage> = withContext(Dispatchers.IO) {
        val id = threadId?.trim()?.takeIf { it.isNotEmpty() } ?: return@withContext emptyList()
        execute(request(url("api", "v1", "chat", "messages", config.assistantId, id)).get().build()) { res ->
            if (res.code == 404) return@execute emptyList()
            val data = parse(res).optJSONArray("responseData") ?: return@execute emptyList()
            (0 until data.length()).mapNotNull { i ->
                val item = data.optJSONObject(i) ?: return@mapNotNull null
                val role = when (item.optString("role").trim().lowercase()) {
                    "user" -> FigoMessageRole.USER
                    "assistant" -> FigoMessageRole.ASSISTANT
                    else -> return@mapNotNull null
                }
                val text = if (item.isNull("message")) "" else item.optString("message", "")
                if (text.isBlank()) return@mapNotNull null
                FigoChatMessage(
                    id = FigoChat.newMessageId(),
                    role = role,
                    text = text,
                    createdAt = parseServerDate(if (item.isNull("dateCreated")) null else item.optString("dateCreated")) ?: System.currentTimeMillis(),
                    status = FigoMessageStatus.SENT,
                )
            }
        }
    }

    /** Public Pusher settings used to subscribe to the thread channel for live agent messages. */
    suspend fun getRealtimeConfig(): FigoRealtimeConfig = withContext(Dispatchers.IO) {
        execute(request(url("api", "v1", "realtime", "config")).get().build()) { res ->
            val data = parse(res).optJSONObject("responseData")
            val key = data?.optString("key").orEmpty()
            val cluster = data?.optString("cluster").orEmpty()
            if (key.isEmpty() || cluster.isEmpty()) throw FigoChatException("Realtime is not configured on the server.", "REALTIME")
            FigoRealtimeConfig(
                key = key,
                cluster = cluster,
                eventName = data?.optString("eventName").orEmpty().ifEmpty { "figoai" },
                provider = data?.optString("provider").orEmpty().ifEmpty { "pusher" },
            )
        }
    }

    /** Builds base url + path segments; each segment is percent-encoded. */
    private fun url(vararg segments: String): HttpUrl =
        config.baseUrl.trimEnd('/').toHttpUrl().newBuilder().apply { segments.forEach { addPathSegment(it) } }.build()

    private fun request(url: HttpUrl) = Request.Builder()
        .url(url)
        .header("Accept", "application/json")
        .header("Authorization", "Bearer ${config.apiKey}")

    private inline fun <T> execute(request: Request, handle: (Response) -> T): T {
        val response = try {
            http.newCall(request).execute()
        } catch (e: InterruptedIOException) {
            throw FigoChatException("The request timed out. Please try again.", "TIMEOUT", e)
        } catch (e: IOException) {
            throw FigoChatException("Network error. Check your connection and try again.", "NETWORK", e)
        }
        return response.use(handle)
    }

    private fun parse(res: Response, allowFailureCode: Boolean = false): JSONObject {
        val raw = res.body?.string().orEmpty()
        if (!res.isSuccessful) {
            // ApiAccessKeyAuthorize returns a plain string such as "Invalid api-key was passed"
            val text = raw.trim().removeSurrounding("\"")
            val message = if (res.code == 401) "Unauthorized: ${text.ifEmpty { "check your API key." }}"
            else text.ifEmpty { "Request failed (${res.code})." }
            throw FigoChatException(message, res.code.toString())
        }

        val json = try {
            JSONObject(raw)
        } catch (e: JSONException) {
            throw FigoChatException("Unexpected response from the server.", "PARSE", e)
        }

        val code = json.optString("responseCode")
        val message = json.optString("responseMessage")
        if (code == CODE_LIMIT_REACHED) throw FigoChatException(message.ifEmpty { "Usage limit reached." }, code)
        if (code != CODE_SUCCESS && !allowFailureCode) throw FigoChatException(message.ifEmpty { "Something went wrong." }, code.ifEmpty { "UNKNOWN" })
        return json
    }

    internal companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
        const val CODE_SUCCESS = "00"
        const val CODE_LIMIT_REACHED = "49"
        const val HEADER_THREAD_ID = "X-Thread-Id"
        const val HEADER_THREAD_MODE = "X-Thread-Mode"

        private val OFFSET_WITHOUT_COLON = Regex("""([+-]\d{2})(\d{2})$""")

        /**
         * Epoch millis of an ISO-8601 date from the API, leniently: with or without a fraction (any number of digits),
         * a `Z` or `+hh:mm` / `+hhmm` offset (none means UTC), a space instead of `T`, or a date alone. null if unreadable.
         */
        fun parseServerDate(value: String?): Long? {
            var v = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            v = v.replace(' ', 'T')
            if (v.length > 10) v = OFFSET_WITHOUT_COLON.replace(v) { "${it.groupValues[1]}:${it.groupValues[2]}" }
            return try {
                java.time.OffsetDateTime.parse(v).toInstant().toEpochMilli()
            } catch (_: Exception) {
                try {
                    java.time.LocalDateTime.parse(v).toInstant(java.time.ZoneOffset.UTC).toEpochMilli()
                } catch (_: Exception) {
                    try {
                        java.time.LocalDate.parse(v).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
                    } catch (_: Exception) {
                        null
                    }
                }
            }
        }
    }
}
