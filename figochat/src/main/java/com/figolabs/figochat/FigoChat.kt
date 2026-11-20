package com.figolabs.figochat

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import com.figolabs.figochat.ui.FigoChatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicLong

/**
 * Receives chat events from the chat screen. Override only the callbacks you need; both get plain text.
 * Callbacks run on the main thread. An exception thrown by a listener is caught and never breaks the chat.
 */
interface FigoChatListener {
    /** The API accepted the user's message; [text] is what the user sent. */
    fun onMessageSent(text: String) {}

    /** An assistant or live agent reply arrived; [text] is the reply as plain text (HTML stripped, see [htmlToText]). */
    fun onMessageReceived(text: String) {}
}

/**
 * The `firstCustomerMessage` sent by [FigoChat.initialize] and what came back. The chat screen shows it at the top
 * of the conversation; the reply's thread id is kept by the client so the chat continues from it.
 */
internal data class FigoFirstExchange(
    val message: FigoChatMessage,
    /** The reply as received (HTML); null until it arrives, and when the reply had no text. */
    val reply: FigoChatMessage? = null,
    val error: FigoChatException? = null,
    val done: Boolean = false,
)

/**
 * Entry point of the SDK. Call [initialize] once (for example after the user signs in), then show the chat
 * with [open] or embed the `FigoChatScreen` composable.
 */
object FigoChat {

    private class Session(val config: FigoChatConfig, val client: FigoChatClient, val realtime: FigoRealtimeConfigStore) {
        /** Display details from GET /api/v1/sdk/assistant/{id}; null until loaded (or if loading failed). */
        @Volatile var assistant: FigoAssistantInfo? = null
        @Volatile var first: FigoFirstExchange? = null
    }

    @Volatile private var session: Session? = null
    private val _version = MutableStateFlow(0)
    private val _conversation = MutableStateFlow(0)

    /** Bumped on initialize / reset / logout and when session data arrives, so open chat screens refresh. */
    val version: StateFlow<Int> = _version.asStateFlow()

    /** Bumped on initialize / reset / logout only: a chat screen clears its own messages when it changes. */
    internal val conversation: StateFlow<Int> = _conversation.asStateFlow()

    private val eventListeners = CopyOnWriteArraySet<FigoChatListener>()
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    /** Runs listener callbacks on the main thread. A seam for JVM unit tests, where there is no main looper. */
    internal var dispatchToMain: (Runnable) -> Unit = { r ->
        if (Looper.myLooper() == Looper.getMainLooper()) r.run() else mainHandler.post(r)
    }

    /** Where the assistant details and the first message are loaded. */
    internal var backgroundScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val messageIds = AtomicLong()

    /** Unique id for chat messages. */
    internal fun newMessageId(): String = "${System.currentTimeMillis().toString(36)}-${messageIds.getAndIncrement().toString(36)}"

    private fun notifyChanged() = _version.update { it + 1 }

    private fun newConversation() {
        _conversation.update { it + 1 }
        notifyChanged()
    }

    private val HEX = Regex("^#?([0-9a-fA-F]{3}|[0-9a-fA-F]{6}|[0-9a-fA-F]{8})$")

    @JvmStatic
    @JvmOverloads
    fun initialize(
        apiKey: String,
        fullName: String,
        emailAddress: String,
        mobilePhone: String,
        assistantId: String,
        primaryColor: String,
        baseUrl: String = FigoChatConfig.DEFAULT_BASE_URL,
        firstCustomerMessage: String? = null,
        threadId: String? = null,
        maxFiles: Int = 5,
        maxFileSizeMB: Int = 10,
        autoSendVoice: Boolean = true,
        timeoutMillis: Long = 120_000,
    ) {
        val required = mapOf(
            "apiKey" to apiKey, "fullName" to fullName, "emailAddress" to emailAddress,
            "mobilePhone" to mobilePhone, "assistantId" to assistantId, "primaryColor" to primaryColor,
        )
        val missing = required.filterValues { it.isBlank() }.keys
        if (missing.isNotEmpty()) throw FigoChatException("FigoChat.initialize: missing ${missing.joinToString()}.", "CONFIG")
        if (!HEX.matches(primaryColor.trim())) {
            throw FigoChatException("FigoChat.initialize: primaryColor must be a hex color such as #F97316.", "CONFIG")
        }

        val config = FigoChatConfig(
            apiKey = apiKey.trim(),
            assistantId = assistantId.trim(),
            fullName = fullName.trim(),
            emailAddress = emailAddress.trim(),
            mobilePhone = mobilePhone.trim(),
            primaryColor = primaryColor.trim(),
            baseUrl = baseUrl,
            firstCustomerMessage = firstCustomerMessage,
            threadId = threadId,
            maxFiles = maxFiles,
            maxFileSizeMB = maxFileSizeMB,
            autoSendVoice = autoSendVoice,
            timeoutMillis = timeoutMillis,
        )
        val client = FigoChatClient(config)
        // realtime settings are fetched on first use and kept encrypted with a per-session key
        val s = Session(config, client, FigoRealtimeConfigStore(client, FigoCrypto()))
        session = s
        loadAssistant(s)
        firstCustomerMessage?.trim()?.takeIf { it.isNotEmpty() }?.let { sendFirstMessage(s, it) }
        newConversation()
    }

    @JvmStatic
    val isInitialized: Boolean get() = session != null

    @JvmStatic
    val config: FigoChatConfig get() = requireSession().config

    /** The API client, for apps that want to build their own UI. */
    @JvmStatic
    val client: FigoChatClient get() = requireSession().client

    internal val realtime: FigoRealtimeConfigStore get() = requireSession().realtime

    /**
     * The assistant's display details (name, persona, waiting message, intro message), or null until they have
     * loaded. They're fetched by [initialize]; [version] changes when they arrive.
     */
    @JvmStatic
    val assistantInfo: FigoAssistantInfo? get() = session?.assistant

    /** The `firstCustomerMessage` exchange, shown at the top of the chat. */
    internal val firstExchange: FigoFirstExchange? get() = session?.first

    /** Sends the `firstCustomerMessage` again after it failed (the chat's "Tap to retry"). */
    internal fun retryFirstMessage() {
        val s = session ?: return
        val first = s.first ?: return
        if (first.done && first.error != null) sendFirstMessage(s, first.message.text)
    }

    /** Thread id of the current conversation; store it and pass it as `threadId` to resume later. */
    @JvmStatic
    val threadId: String? get() = session?.client?.threadId

    /** Starts a new conversation on the next message. */
    @JvmStatic
    fun resetConversation() {
        session?.let {
            it.client.resetThread()
            it.first = null
        }
        newConversation()
    }

    /** Clears the configuration, e.g. when the user signs out. */
    @JvmStatic
    fun logout() {
        session = null
        newConversation()
    }

    /** Opens the full-screen chat. */
    @JvmStatic
    fun open(context: Context) {
        requireSession()
        val intent = Intent(context, FigoChatActivity::class.java)
        if (context !is android.app.Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    /**
     * Starts sending chat events to [listener] and returns it, so it can be kept for [removeListener].
     * Listeners can be added before [initialize] and stay registered across [logout]; adding the same
     * listener twice has no effect.
     */
    @JvmStatic
    fun addListener(listener: FigoChatListener): FigoChatListener {
        eventListeners.add(listener)
        return listener
    }

    /** Stops sending chat events to [listener]. */
    @JvmStatic
    fun removeListener(listener: FigoChatListener) {
        eventListeners.remove(listener)
    }

    internal fun hasListener(listener: FigoChatListener): Boolean = listener in eventListeners

    internal fun emitMessageSent(text: String) = emit { it.onMessageSent(text) }

    internal fun emitMessageReceived(text: String) = emit { it.onMessageReceived(text) }

    /** Calls every listener on the main thread; a listener that throws is ignored. */
    private fun emit(event: (FigoChatListener) -> Unit) {
        if (eventListeners.isEmpty()) return
        val dispatch = Runnable {
            eventListeners.forEach { listener ->
                try {
                    event(listener)
                } catch (_: Throwable) {
                    // the host app's listener threw; never let it break the chat
                }
            }
        }
        dispatchToMain(dispatch)
    }

    private fun loadAssistant(s: Session) {
        backgroundScope.launch {
            try {
                val info = s.client.getAssistant()
                if (session !== s) return@launch
                s.assistant = info
                notifyChanged()
            } catch (_: Exception) {
                // the chat still works without it: default title, no intro, typing dots instead of the waiting message
            }
        }
    }

    private fun sendFirstMessage(s: Session, text: String) {
        val message = FigoChatMessage(id = newMessageId(), role = FigoMessageRole.USER, text = text, status = FigoMessageStatus.SENDING)
        s.first = FigoFirstExchange(message)
        notifyChanged()
        backgroundScope.launch {
            val done = try {
                val reply = s.client.sendMessage(text)
                emitMessageSent(text)
                val replyText = htmlToText(reply.answer)
                val replyMessage = if (replyText.isEmpty()) null
                else FigoChatMessage(id = newMessageId(), role = FigoMessageRole.ASSISTANT, text = reply.answer).also { emitMessageReceived(replyText) }
                FigoFirstExchange(message.copy(status = FigoMessageStatus.SENT), reply = replyMessage, done = true)
            } catch (e: Exception) {
                val err = e as? FigoChatException ?: FigoChatException(e.message ?: e.toString(), "UNKNOWN", e)
                FigoFirstExchange(message.copy(status = FigoMessageStatus.ERROR), error = err, done = true)
            }
            // a reset, logout, retry or new initialize since then replaced this exchange
            if (session === s && s.first?.message?.id == message.id) {
                s.first = done
                notifyChanged()
            }
        }
    }

    private fun requireSession(): Session =
        session ?: throw FigoChatException("FigoChat is not initialized. Call FigoChat.initialize(...) first.", "NOT_INITIALIZED")
}
