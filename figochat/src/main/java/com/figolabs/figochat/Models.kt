package com.figolabs.figochat

/** Settings passed to [FigoChat.initialize]. */
data class FigoChatConfig(
    /** API key from the FigoChat admin (API Keys in the sidebar at app.figolabs.ai). Sent as `Authorization: Bearer <apiKey>`. */
    val apiKey: String,
    /** Assistant the user chats with. */
    val assistantId: String,
    val fullName: String,
    val emailAddress: String,
    /** Phone number in international format, e.g. +2348012345678. */
    val mobilePhone: String,
    /** Brand color as hex: #RGB, #RRGGBB or #RRGGBBAA. */
    val primaryColor: String,
    /** API host. */
    val baseUrl: String = DEFAULT_BASE_URL,
    /** Header title. */
    val title: String = "Chat",
    /** First assistant message, shown before the user writes anything. */
    val welcomeMessage: String? = null,
    /** Resumes an earlier conversation; use the value from [FigoChat.threadId]. */
    val threadId: String? = null,
    val maxFiles: Int = 5,
    val maxFileSizeMB: Int = 10,
    /** true sends a transcribed voice note right away; false puts the text in the input for editing. */
    val autoSendVoice: Boolean = true,
    val timeoutMillis: Long = 120_000,
) {
    override fun toString() = "FigoChatConfig(assistantId=$assistantId, baseUrl=$baseUrl, apiKey=***)"

    companion object {
        const val DEFAULT_BASE_URL = "https://api.figolabs.ai"
    }
}

/** Error thrown by every SDK call. [code] is the API response code or the HTTP status. */
class FigoChatException(message: String, val code: String, cause: Throwable? = null) : Exception(message, cause)

enum class FigoMessageRole { USER, ASSISTANT, SYSTEM }

enum class FigoMessageStatus { SENDING, SENT, ERROR }

/** An attachment on a chat message. */
data class FigoAttachment(
    /** CDN url returned by the upload. */
    val url: String,
    val name: String,
    val mimeType: String? = null,
    /** Local content uri, used for image previews. */
    val localUri: String? = null,
) {
    val isImage: Boolean
        get() = mimeType?.startsWith("image/") == true || IMAGE_EXT.containsMatchIn(name) || IMAGE_EXT.containsMatchIn(url)

    private companion object {
        val IMAGE_EXT = Regex("""\.(png|jpe?g|gif|webp|bmp|heic|heif)(\?|$)""", RegexOption.IGNORE_CASE)
    }
}

data class FigoChatMessage(
    val id: String,
    val role: FigoMessageRole,
    /** What the user typed, or for assistant messages the reply as received (often HTML; [htmlToText] gives plain text). */
    val text: String,
    val attachments: List<FigoAttachment> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    val status: FigoMessageStatus = FigoMessageStatus.SENT,
)

data class FigoChatReply(
    val answer: String,
    val threadId: String?,
    /** "ai" normally, "liveagent" once a human agent has taken over. */
    val threadMode: String?,
)

/** A file to upload: its name (with extension) and contents. */
class FigoUploadFile(val name: String, val bytes: ByteArray, val mimeType: String? = null) {
    val extension: String
        get() {
            val dot = name.lastIndexOf('.')
            if (dot > 0 && dot < name.length - 1) return name.substring(dot + 1).lowercase()
            val sub = mimeType?.substringAfterLast('/')?.lowercase()
            return if (sub == "jpeg") "jpg" else sub ?: "txt"
        }
}

/** Public realtime settings from GET /api/v1/realtime/config. */
data class FigoRealtimeConfig(
    val key: String,
    val cluster: String,
    /** Event bound on the thread channel; its payload is {"message": "..."}. */
    val eventName: String,
    val provider: String = "pusher",
) {
    override fun toString() = "FigoRealtimeConfig(provider=$provider, eventName=$eventName, key=***, cluster=***)"
}
