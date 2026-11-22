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

    private class Session(
        @Volatile var config: FigoChatConfig,
        val client: FigoChatClient,
        val realtime: FigoRealtimeConfigStore,
        /** true while the chat should ask for the customer's name, email and phone (all were empty). */
        @Volatile var needsDetails: Boolean,
    ) {
        /** Display details from GET /api/v1/sdk/assistant/{id}; null until loaded (or if loading failed). */
        @Volatile var assistant: FigoAssistantInfo? = null
        @Volatile var first: FigoFirstExchange? = null
        /** firstCustomerMessage held back until the details form is submitted or skipped. */
        @Volatile var pendingFirst: String? = null
    }

    /** Where details typed into the form are remembered. A seam for JVM unit tests. */
    internal var detailsStore: FigoDetailsStore = SharedPreferencesDetailsStore

    /** Assistants whose details form the visitor skipped during this app run. */
    private val skippedDetails: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

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
        fullName: String = "",
        emailAddress: String = "",
        mobilePhone: String = "",
        assistantId: String,
        primaryColor: String,
        baseUrl: String = FigoChatConfig.DEFAULT_BASE_URL,
        firstCustomerMessage: String? = null,
        threadId: String? = null,
        maxFiles: Int = 5,
        maxFileSizeMB: Int = 10,
        autoSendVoice: Boolean = true,
        timeoutMillis: Long = 120_000,
        askForDetails: Boolean = true,
        defaultCountry: String? = null,
    ) {
        val required = mapOf("apiKey" to apiKey, "assistantId" to assistantId, "primaryColor" to primaryColor)
        val missing = required.filterValues { it.isBlank() }.keys
        if (missing.isNotEmpty()) throw FigoChatException("FigoChat.initialize: missing ${missing.joinToString()}.", "CONFIG")
        if (!HEX.matches(primaryColor.trim())) {
            throw FigoChatException("FigoChat.initialize: primaryColor must be a hex color such as #F97316.", "CONFIG")
        }

        var config = FigoChatConfig(
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
            askForDetails = askForDetails,
            defaultCountry = defaultCountry?.trim()?.takeIf { it.isNotEmpty() },
        )

        // anonymous visitor: reuse details they gave before, or ask for them (unless they skipped during this app run)
        var needsDetails = false
        if (askForDetails && config.fullName.isEmpty() && config.emailAddress.isEmpty() && config.mobilePhone.isEmpty()) {
            val saved = detailsStore.read(config.assistantId)
            if (saved != null) config = config.copy(fullName = saved.fullName, emailAddress = saved.emailAddress, mobilePhone = saved.mobilePhone)
            else needsDetails = config.assistantId !in skippedDetails
        }

        val client = FigoChatClient(config)
        // realtime settings are fetched on first use and kept encrypted with a per-session key
        val s = Session(config, client, FigoRealtimeConfigStore(client, FigoCrypto()), needsDetails)
        session = s
        loadAssistant(s)
        val first = firstCustomerMessage?.trim()?.takeIf { it.isNotEmpty() }
        if (first != null && needsDetails) s.pendingFirst = first
        else if (first != null) sendFirstMessage(s, first)
        newConversation()
    }

    /**
     * Sets the customer's name, email and phone, for example once a visitor signs in. They're used for the rest of
     * the conversation, exactly as if they'd been passed to [initialize], and close the details form if it's showing.
     * A null argument keeps the current value.
     */
    @JvmStatic
    @JvmOverloads
    fun setCustomerDetails(fullName: String? = null, emailAddress: String? = null, mobilePhone: String? = null) {
        val s = requireSession()
        val c = s.config
        val updated = c.copy(
            fullName = (fullName ?: c.fullName).trim(),
            emailAddress = (emailAddress ?: c.emailAddress).trim(),
            mobilePhone = (mobilePhone ?: c.mobilePhone).trim(),
        )
        s.config = updated
        s.client.updateConfig(updated)
        continueAfterDetails(s)
    }

    /** The customer's current name, email and phone (empty strings when unknown). */
    @JvmStatic
    val customerDetails: FigoCustomerDetails
        get() = requireSession().config.let { FigoCustomerDetails(it.fullName, it.emailAddress, it.mobilePhone) }

    /** true while the chat screen shows the details form instead of the conversation. */
    internal val needsDetails: Boolean get() = session?.needsDetails == true

    /** The details form was submitted: remember the details on this device, then chat with them. */
    internal fun submitDetails(details: FigoCustomerDetails) {
        val s = requireSession()
        detailsStore.save(s.config.assistantId, details)
        setCustomerDetails(details.fullName, details.emailAddress, details.mobilePhone)
    }

    /** The visitor skipped the details form: chat without details and don't ask again during this app run. */
    internal fun skipDetails() {
        val s = session ?: return
        skippedDetails += s.config.assistantId
        continueAfterDetails(s)
    }

    /** Leaves the details form: sends the held-back firstCustomerMessage and refreshes the chat. */
    private fun continueAfterDetails(s: Session) {
        s.needsDetails = false
        val first = s.pendingFirst
        s.pendingFirst = null
        if (first != null && session === s) sendFirstMessage(s, first) else notifyChanged()
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
     * The assistant's display details (name, persona, waiting message, intro message, disclaimer, company links), or null until they have
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

    /**
     * Clears the configuration, e.g. when the user signs out. Details a visitor typed into the details form are
     * forgotten too.
     */
    @JvmStatic
    fun logout() {
        session?.config?.assistantId?.let {
            detailsStore.forget(it)
            skippedDetails -= it
        }
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
