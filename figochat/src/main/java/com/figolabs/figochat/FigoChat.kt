package com.figolabs.figochat

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import com.figolabs.figochat.ui.FigoChatActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.CopyOnWriteArraySet

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
 * Entry point of the SDK. Call [initialize] once (for example after the user signs in), then show the chat
 * with [open] or embed the `FigoChatScreen` composable.
 */
object FigoChat {

    private class Session(val config: FigoChatConfig, val client: FigoChatClient, val realtime: FigoRealtimeConfigStore)

    @Volatile private var session: Session? = null
    private val _version = MutableStateFlow(0)

    /** Bumped on initialize / reset / logout so open chat screens refresh. */
    val version: StateFlow<Int> = _version.asStateFlow()

    private val eventListeners = CopyOnWriteArraySet<FigoChatListener>()
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

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
        title: String = "Chat",
        welcomeMessage: String? = null,
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
            title = title,
            welcomeMessage = welcomeMessage,
            threadId = threadId,
            maxFiles = maxFiles,
            maxFileSizeMB = maxFileSizeMB,
            autoSendVoice = autoSendVoice,
            timeoutMillis = timeoutMillis,
        )
        val client = FigoChatClient(config)
        // realtime settings are fetched on first use and kept encrypted with a per-session key
        session = Session(config, client, FigoRealtimeConfigStore(client, FigoCrypto()))
        _version.value++
    }

    @JvmStatic
    val isInitialized: Boolean get() = session != null

    @JvmStatic
    val config: FigoChatConfig get() = requireSession().config

    /** The API client, for apps that want to build their own UI. */
    @JvmStatic
    val client: FigoChatClient get() = requireSession().client

    internal val realtime: FigoRealtimeConfigStore get() = requireSession().realtime

    /** Thread id of the current conversation; store it and pass it as `threadId` to resume later. */
    @JvmStatic
    val threadId: String? get() = session?.client?.threadId

    /** Starts a new conversation on the next message. */
    @JvmStatic
    fun resetConversation() {
        session?.client?.resetThread()
        _version.value++
    }

    /** Clears the configuration, e.g. when the user signs out. */
    @JvmStatic
    fun logout() {
        session = null
        _version.value++
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
        if (Looper.myLooper() == Looper.getMainLooper()) dispatch.run() else mainHandler.post(dispatch)
    }

    private fun requireSession(): Session =
        session ?: throw FigoChatException("FigoChat is not initialized. Call FigoChat.initialize(...) first.", "NOT_INITIALIZED")
}
