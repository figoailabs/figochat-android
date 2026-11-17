package com.figolabs.figochat.ui

import android.app.Application
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.figolabs.figochat.FigoAttachment
import com.figolabs.figochat.FigoChat
import com.figolabs.figochat.FigoChatException
import com.figolabs.figochat.FigoChatMessage
import com.figolabs.figochat.FigoErrorContext
import com.figolabs.figochat.FigoMessageRole
import com.figolabs.figochat.FigoMessageStatus
import com.figolabs.figochat.FigoRealtimeSubscription
import com.figolabs.figochat.FigoUploadFile
import com.figolabs.figochat.friendlyMessage
import com.figolabs.figochat.htmlToText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

internal data class PendingFile(
    val id: String,
    val name: String,
    val mimeType: String?,
    val localUri: String,
    val bytes: ByteArray,
    val url: String? = null,
    val uploading: Boolean = true,
    val failed: Boolean = false,
)

/** State and actions behind [FigoChatScreen]; survives configuration changes. */
class FigoChatViewModel(app: Application) : AndroidViewModel(app) {

    val messages = mutableStateListOf<FigoChatMessage>()
    internal val pending = mutableStateListOf<PendingFile>()
    var input by mutableStateOf("")
    var waiting by mutableStateOf(false)
        private set
    var recording by mutableStateOf(false)
        private set
    var recordingMillis by mutableLongStateOf(0L)
        private set
    var transcribing by mutableStateOf(false)
        private set
    var notice by mutableStateOf<String?>(null)
        private set

    private val _errors = MutableSharedFlow<FigoChatException>(extraBufferCapacity = 16)
    /** Every error the screen shows, for the host app's logging. */
    val errors: SharedFlow<FigoChatException> = _errors.asSharedFlow()

    private var ids = 0L
    private var subscription: FigoRealtimeSubscription? = null
    private var recorder: MediaRecorder? = null
    private var recordFile: File? = null
    private var recordTimer: Job? = null
    private var noticeJob: Job? = null

    init {
        resetMessages()
        syncRealtime()
        viewModelScope.launch {
            FigoChat.version.drop(1).collect {
                if (FigoChat.isInitialized && FigoChat.threadId == null) resetMessages()
                syncRealtime()
            }
        }
    }

    private fun newId() = "${System.currentTimeMillis()}-${ids++}"

    private fun resetMessages() {
        messages.clear()
        if (FigoChat.isInitialized) {
            FigoChat.config.welcomeMessage?.takeIf { it.isNotBlank() }?.let {
                messages += FigoChatMessage(id = "welcome", role = FigoMessageRole.ASSISTANT, text = it)
            }
        }
    }

    private fun report(e: Throwable): FigoChatException {
        val err = e as? FigoChatException ?: FigoChatException(e.message ?: e.toString(), "UNKNOWN", e)
        _errors.tryEmit(err)
        return err
    }

    fun showNotice(text: String) {
        noticeJob?.cancel()
        notice = text
        noticeJob = viewModelScope.launch {
            delay(4000)
            notice = null
        }
    }

    // ---------------- live agent messages ----------------

    /**
     * Keeps a subscription to the Pusher channel named after the current thread id; what arrives is shown on the
     * left, like an assistant reply. Re-subscribes when the thread changes and stops when there is none.
     */
    private fun syncRealtime() {
        val threadId = if (FigoChat.isInitialized) FigoChat.threadId else null
        if (subscription?.threadId == threadId) return

        subscription?.stop()
        subscription = null
        if (threadId == null) return

        val sub = FigoRealtimeSubscription(
            FigoChat.realtime,
            threadId,
            onMessage = { html ->
                viewModelScope.launch {
                    messages += FigoChatMessage(id = newId(), role = FigoMessageRole.ASSISTANT, text = html)
                    FigoChat.emitMessageReceived(htmlToText(html))
                }
            },
            onError = { e -> viewModelScope.launch { report(e) } },
        )
        subscription = sub
        viewModelScope.launch(Dispatchers.IO) { sub.start() }
    }

    // ---------------- chat ----------------

    private fun replace(id: String, transform: (FigoChatMessage) -> FigoChatMessage) {
        val index = messages.indexOfFirst { it.id == id }
        if (index >= 0) messages[index] = transform(messages[index])
    }

    private fun ask(message: FigoChatMessage) {
        waiting = true
        viewModelScope.launch {
            try {
                val reply = FigoChat.client.sendMessage(message.text, message.attachments.map { it.url })
                replace(message.id) { it.copy(status = FigoMessageStatus.SENT) }
                FigoChat.emitMessageSent(message.text)
                // in live agent mode the API relays the message to the agent and answers with nothing;
                // the agent's reply arrives on the thread channel instead
                val replyText = htmlToText(reply.answer)
                if (replyText.isNotEmpty()) {
                    // the raw reply (HTML) is kept; the bubble renders it as rich text
                    messages += FigoChatMessage(id = newId(), role = FigoMessageRole.ASSISTANT, text = reply.answer)
                    FigoChat.emitMessageReceived(replyText)
                } else if (reply.threadMode != "liveagent") {
                    messages += FigoChatMessage(id = newId(), role = FigoMessageRole.ASSISTANT, text = "…")
                }
                syncRealtime()
            } catch (e: Exception) {
                val err = report(e)
                replace(message.id) { it.copy(status = FigoMessageStatus.ERROR) }
                messages += FigoChatMessage(id = newId(), role = FigoMessageRole.SYSTEM, text = friendlyMessage(err, FigoErrorContext.CHAT))
            } finally {
                waiting = false
            }
        }
    }

    fun send(textOverride: String? = null) {
        val text = (textOverride ?: input).trim()
        val ready = pending.filter { !it.uploading && !it.failed && it.url != null }
        if (waiting || (text.isEmpty() && ready.isEmpty())) return
        if (pending.any { it.uploading }) {
            showNotice("Please wait, your files are still uploading.")
            return
        }

        val attachments = ready.map { FigoAttachment(url = it.url!!, name = it.name, mimeType = it.mimeType, localUri = it.localUri) }
        val message = FigoChatMessage(
            id = newId(),
            role = FigoMessageRole.USER,
            text = text.ifEmpty { if (attachments.size > 1) "Please review the attached files." else "Please review the attached file." },
            attachments = attachments,
            status = FigoMessageStatus.SENDING,
        )
        messages += message
        pending.clear()
        if (textOverride == null) input = ""
        ask(message)
    }

    fun retry(message: FigoChatMessage) {
        if (waiting) return
        replace(message.id) { it.copy(status = FigoMessageStatus.SENDING) }
        ask(message)
    }

    fun newConversation() {
        if (waiting) return
        FigoChat.resetConversation() // version change resets the messages and the subscription
        pending.clear()
        input = ""
    }

    // ---------------- files ----------------

    fun addFiles(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val config = FigoChat.config
        val room = config.maxFiles - pending.size
        if (room <= 0) {
            showNotice("You can attach up to ${config.maxFiles} files per message.")
            return
        }
        val maxBytes = config.maxFileSizeMB * 1024L * 1024L
        val resolver = getApplication<Application>().contentResolver

        viewModelScope.launch {
            val tooBig = mutableListOf<String>()
            val items = withContext(Dispatchers.IO) {
                uris.mapNotNull { uri ->
                    var name = uri.lastPathSegment ?: "file"
                    var size = -1L
                    resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                        if (c.moveToFirst()) {
                            c.getString(0)?.let { name = it }
                            if (!c.isNull(1)) size = c.getLong(1)
                        }
                    }
                    if (size > maxBytes) {
                        tooBig += name
                        return@mapNotNull null
                    }
                    val bytes = runCatching { resolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull() ?: return@mapNotNull null
                    if (bytes.size > maxBytes) {
                        tooBig += name
                        return@mapNotNull null
                    }
                    PendingFile(newId(), name, resolver.getType(uri), uri.toString(), bytes)
                }.take(room)
            }
            if (tooBig.isNotEmpty()) showNotice("${tooBig.joinToString()} is larger than ${config.maxFileSizeMB} MB.")
            pending += items

            // one request per file so a failure only marks that file
            items.forEach { item ->
                launch {
                    val updated = try {
                        val url = FigoChat.client.uploadFiles(listOf(FigoUploadFile(item.name, item.bytes, item.mimeType))).first()
                        item.copy(url = url, uploading = false)
                    } catch (e: Exception) {
                        report(e)
                        item.copy(uploading = false, failed = true)
                    }
                    val index = pending.indexOfFirst { it.id == item.id }
                    if (index >= 0) pending[index] = updated
                }
            }
        }
    }

    fun removePending(id: String) {
        pending.removeAll { it.id == id }
    }

    // ---------------- voice ----------------

    fun startRecording() {
        val context = getApplication<Application>()
        try {
            val file = File(context.cacheDir, "figochat-${System.currentTimeMillis()}.m4a")
            val r = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioSamplingRate(44_100)
            r.setAudioEncodingBitRate(128_000)
            r.setOutputFile(file.absolutePath)
            r.prepare()
            r.start()
            recorder = r
            recordFile = file
            recording = true
            recordingMillis = 0
            recordTimer = viewModelScope.launch {
                while (isActive) {
                    delay(250)
                    recordingMillis += 250
                }
            }
        } catch (e: Exception) {
            releaseRecorder()
            showNotice(friendlyMessage(report(e), FigoErrorContext.RECORDING))
        }
    }

    fun stopRecording(discard: Boolean = false) {
        recordTimer?.cancel()
        val duration = recordingMillis
        val file = recordFile
        val stopped = try {
            recorder?.stop()
            true
        } catch (e: RuntimeException) {
            false // stop() throws when nothing was recorded
        }
        releaseRecorder()
        recording = false

        if (discard || !stopped || file == null || duration < 600) {
            file?.delete()
            return
        }

        transcribing = true
        viewModelScope.launch {
            try {
                val text = FigoChat.client.transcribe(file, "audio/mp4")
                when {
                    text.isEmpty() -> showNotice("We could not hear anything in that recording. Please try again.")
                    !FigoChat.config.autoSendVoice -> input = if (input.isEmpty()) text else "$input $text"
                    else -> send(text)
                }
            } catch (e: Exception) {
                showNotice(friendlyMessage(report(e), FigoErrorContext.VOICE))
            } finally {
                transcribing = false
                file.delete()
            }
        }
    }

    private fun releaseRecorder() {
        runCatching { recorder?.release() }
        recorder = null
        recordFile = null
    }

    override fun onCleared() {
        subscription?.stop()
        subscription = null
        if (recording) stopRecording(discard = true)
        super.onCleared()
    }
}
