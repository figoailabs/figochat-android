package com.figolabs.figochat

/** Settings passed to [FigoChat.initialize]. */
data class FigoChatConfig(
    /** API key from the FigoChat admin (API Keys in the sidebar at app.figolabs.ai). Sent as `Authorization: Bearer <apiKey>`. */
    val apiKey: String,
    /** Assistant the user chats with. */
    val assistantId: String,
    /** The customer's name; "" when unknown (see [askForDetails]). */
    val fullName: String = "",
    /** The customer's email; "" when unknown. */
    val emailAddress: String = "",
    /** Phone number in international format, e.g. +2348012345678; "" when unknown. */
    val mobilePhone: String = "",
    /** Brand color as hex: #RGB, #RRGGBB or #RRGGBBAA. */
    val primaryColor: String,
    /** API host. */
    val baseUrl: String = DEFAULT_BASE_URL,
    /**
     * A message sent on the customer's behalf as soon as [FigoChat.initialize] runs, for example the question they
     * typed on your own screen before opening the chat. It is sent like any other message: the reply and the thread
     * id are kept, so the chat continues from it and shows both when it opens.
     */
    val firstCustomerMessage: String? = null,
    /**
     * Resumes an earlier conversation; use the value from [FigoChat.threadId]. Its messages are shown when the chat
     * opens. Without it, a conversation from this app run is resumed for an hour after its last message.
     */
    val threadId: String? = null,
    val maxFiles: Int = 5,
    val maxFileSizeMB: Int = 10,
    /** true sends a transcribed voice note right away; false puts the text in the input for editing. */
    val autoSendVoice: Boolean = true,
    val timeoutMillis: Long = 120_000,
    /**
     * When [fullName], [emailAddress] and [mobilePhone] are all empty, the chat first asks the visitor for them
     * (they can skip). Details they enter are kept in memory only, for the conversation session: until an hour after
     * the last message, [FigoChat.logout] or the end of the app process. false chats without them.
     */
    val askForDetails: Boolean = true,
    /**
     * Country preselected in the details form's phone field (ISO code such as NG or GB, any case). null, or a code
     * that isn't a known country, preselects Nigeria (NG).
     */
    val defaultCountry: String? = null,
) {
    override fun toString() = "FigoChatConfig(assistantId=$assistantId, baseUrl=$baseUrl, apiKey=***)"

    companion object {
        const val DEFAULT_BASE_URL = "https://api.figolabs.ai"
    }
}

/**
 * Display details of the assistant, from GET /api/v1/sdk/assistant/{assistantId}. The chat header shows [persona]
 * (or [assistantName]), the first bubble shows [introMessage] (HTML), and [waitingMessage] is shown on the left
 * while a reply is being prepared. [disclaimerMessage] is shown in small grey text above the "Powered by" footer,
 * and the [company]'s terms and privacy links add a consent checkbox to the details form. Any field may be null.
 */
data class FigoAssistantInfo(
    val assistantName: String? = null,
    val persona: String? = null,
    val waitingMessage: String? = null,
    val introMessage: String? = null,
    /** Short note such as "AI can make mistakes. Double-check sensitive information". */
    val disclaimerMessage: String? = null,
    /** The assistant's company: its terms and privacy policy links. */
    val company: FigoCompanyInfo? = null,
) {
    /** [disclaimerMessage] trimmed, or null when empty. */
    internal val disclaimer: String? get() = disclaimerMessage?.trim()?.takeIf { it.isNotEmpty() }

    /** The terms and privacy links, trimmed, when both are set; the details form then asks the visitor to agree. */
    internal val consentLinks: Pair<String, String>?
        get() {
            val terms = company?.termsAndCondition?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val privacy = company.privacyPolicy?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            return terms to privacy
        }
}

/** true for an http(s) link that can be opened in the browser. */
internal fun isWebUrl(value: String): Boolean {
    val v = value.trim()
    return (v.startsWith("https://", ignoreCase = true) || v.startsWith("http://", ignoreCase = true)) && v.length > 8 && v.none { it.isWhitespace() }
}

/** Company links from the assistant details. Either may be null. */
data class FigoCompanyInfo(
    /** Terms and conditions, usually a URL. */
    val termsAndCondition: String? = null,
    /** Privacy policy, usually a URL. */
    val privacyPolicy: String? = null,
)

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
