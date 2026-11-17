package com.figolabs.figochat

import com.pusher.client.Pusher
import com.pusher.client.PusherOptions
import com.pusher.client.connection.ConnectionEventListener
import com.pusher.client.connection.ConnectionState
import com.pusher.client.connection.ConnectionStateChange
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

/** Realtime settings as kept in memory: key and cluster are encrypted with the SDK's [FigoCrypto] instance. */
internal class EncryptedRealtimeConfig(val key: String, val cluster: String, val eventName: String)

/** Fetches GET /api/v1/realtime/config once and keeps the result encrypted in memory. */
internal class FigoRealtimeConfigStore(private val client: FigoChatClient, val crypto: FigoCrypto) {
    private val mutex = Mutex()
    private var cached: EncryptedRealtimeConfig? = null

    suspend fun get(): EncryptedRealtimeConfig = mutex.withLock {
        cached ?: client.getRealtimeConfig().let {
            EncryptedRealtimeConfig(crypto.encrypt(it.key), crypto.encrypt(it.cluster), it.eventName)
        }.also { cached = it } // a failed fetch throws and is retried on the next subscribe
    }
}

/**
 * Subscribes to the Pusher channel named after the thread id (the X-Thread-Id value) and passes each message
 * published on it (live agent replies, live agent handoff notices) to [onMessage] as received (HTML); messages
 * without text are skipped. Callbacks run on
 * Pusher's background thread. The key and cluster are decrypted only here, right before connecting.
 */
internal class FigoRealtimeSubscription(
    private val store: FigoRealtimeConfigStore,
    val threadId: String,
    private val onMessage: (String) -> Unit,
    private val onError: (Throwable) -> Unit,
) {
    @Volatile private var pusher: Pusher? = null
    @Volatile private var closed = false

    suspend fun start() {
        try {
            val config = store.get()
            if (closed) return

            val options = PusherOptions().setCluster(store.crypto.decrypt(config.cluster)).setUseTLS(true)
            val client = Pusher(store.crypto.decrypt(config.key), options)
            pusher = client

            client.connect(object : ConnectionEventListener {
                override fun onConnectionStateChange(change: ConnectionStateChange) = Unit
                override fun onError(message: String?, code: String?, e: Exception?) {
                    if (!closed) onError(FigoChatException("Live updates are unavailable: ${message ?: code}", "REALTIME", e))
                }
            }, ConnectionState.ALL)

            client.subscribe(threadId).bind(config.eventName) { event ->
                val raw = messageFrom(event.data)
                if (raw.isNotEmpty() && htmlToText(raw).isNotEmpty() && !closed) onMessage(raw)
            }
            if (closed) stop()
        } catch (e: Exception) {
            if (!closed) onError(e)
        }
    }

    fun stop() {
        closed = true
        pusher?.let {
            runCatching { it.unsubscribe(threadId) }
            runCatching { it.disconnect() }
        }
        pusher = null
    }

    private fun messageFrom(data: String?): String {
        if (data.isNullOrEmpty()) return ""
        return runCatching { JSONObject(data).optString("message") }.getOrNull() ?: data
    }
}
