package com.figolabs.figochat.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.InsertDriveFile
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.figolabs.figochat.FigoAttachment
import com.figolabs.figochat.FigoChat
import com.figolabs.figochat.FigoChatException
import com.figolabs.figochat.FigoChatMessage
import com.figolabs.figochat.FigoMessageRole
import com.figolabs.figochat.FigoMessageStatus
import com.figolabs.figochat.htmlToRuns
import com.figolabs.figochat.htmlToText
import com.figolabs.figochat.plainToRuns
import kotlinx.coroutines.delay

private val BubbleGrey = Color(0xFFF1F3F5)
private val TextDark = Color(0xFF1F2328)
private val TextMuted = Color(0xFF6B7280)
private val ErrorRed = Color(0xFFD93025)
private val FooterGrey = Color(0xFF9AA0A6)
private const val FIGO_LABS_URL = "https://figolabs.ai"

/**
 * The FigoChat chat screen: message list, text input, voice notes and file attachments.
 * Call `FigoChat.initialize(...)` before showing it.
 *
 * @param onClose shows a close button in the header and is called when it is pressed
 * @param showHeader hide the header if the host app draws its own
 * @param onError called with every error the screen shows, for logging
 */
@Composable
fun FigoChatScreen(
    modifier: Modifier = Modifier,
    onClose: (() -> Unit)? = null,
    showHeader: Boolean = true,
    placeholder: String = "Type a message",
    enableVoice: Boolean = true,
    enableAttachments: Boolean = true,
    onError: ((FigoChatException) -> Unit)? = null,
) {
    val version by FigoChat.version.collectAsStateCompat()
    if (!FigoChat.isInitialized) {
        Box(modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Text("FigoChat is not initialized. Call FigoChat.initialize(...) first.", color = TextMuted, textAlign = TextAlign.Center)
        }
        return
    }

    val vm: FigoChatViewModel = viewModel()
    val context = LocalContext.current
    val primary = remember(version) { parseHexColor(FigoChat.config.primaryColor) }
    val onPrimary = if (primary.luminance() > 0.45f) Color(0xFF111111) else Color.White

    LaunchedEffect(vm, onError) { vm.errors.collect { onError?.invoke(it) } }

    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.startRecording() else vm.showNotice("Allow microphone access in Settings to send voice messages.")
    }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> vm.addFiles(uris) }

    fun startVoice() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) vm.startRecording()
        else micPermission.launch(Manifest.permission.RECORD_AUDIO)
    }

    Column(modifier.fillMaxSize().background(Color.White)) {
        if (showHeader) {
            Row(
                Modifier.fillMaxWidth().background(primary).padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    vm.title,
                    color = onPrimary,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = vm::newConversation) { Icon(Icons.Filled.Refresh, "New conversation", tint = onPrimary) }
                if (onClose != null) IconButton(onClick = onClose) { Icon(Icons.Filled.Close, "Close chat", tint = onPrimary) }
            }
        }

        val items = vm.displayMessages.asReversed()
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            reverseLayout = true,
            contentPadding = PaddingValues(12.dp),
            // Alignment.Bottom keeps a short conversation at the bottom; spacedBy(8.dp) alone aligns to the top
            // and overrides reverseLayout
            verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.Bottom),
        ) {
            if (vm.waiting) item(key = "typing") { WaitingBubble(vm.waitingMessage) }
            items(items, key = { it.id }) { m -> MessageBubble(m, primary, onPrimary) { vm.retry(m) } }
        }

        vm.notice?.let {
            Text(
                it,
                color = Color.White,
                fontSize = 13.sp,
                modifier = Modifier
                    .padding(horizontal = 12.dp, vertical = 4.dp)
                    .fillMaxWidth()
                    .background(Color(0xFF323232), RoundedCornerShape(8.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }

        if (vm.pending.isNotEmpty()) PendingRow(vm, primary)
        HorizontalDivider(thickness = 0.5.dp, color = Color(0xFFE5E7EB))

        if (vm.recording || vm.transcribing) {
            RecordingBar(vm, primary, onPrimary)
        } else {
            val hasText = vm.input.isNotBlank()
            val canSend = !vm.waiting && (hasText || vm.pending.any { it.url != null && !it.failed })
            val showMic = enableVoice && !canSend && !hasText
            Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 8.dp), verticalAlignment = Alignment.Bottom) {
                if (enableAttachments) {
                    IconButton(onClick = { filePicker.launch(arrayOf("*/*")) }, enabled = !vm.waiting) {
                        Icon(Icons.Filled.AttachFile, "Attach files", tint = TextMuted)
                    }
                }
                TextField(
                    value = vm.input,
                    onValueChange = { if (it.length <= 4000) vm.input = it },
                    placeholder = { Text(placeholder, color = Color(0xFF8A8F98)) },
                    modifier = Modifier.weight(1f).heightIn(min = 44.dp, max = 140.dp),
                    shape = RoundedCornerShape(22.dp),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = BubbleGrey,
                        unfocusedContainerColor = BubbleGrey,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        cursorColor = primary,
                        focusedTextColor = TextDark,
                        unfocusedTextColor = TextDark,
                    ),
                )
                Spacer(Modifier.size(8.dp))
                RoundButton(
                    color = primary,
                    enabled = if (showMic) !vm.waiting else canSend,
                    onClick = { if (showMic) startVoice() else vm.send() },
                ) {
                    if (showMic) Icon(Icons.Filled.Mic, "Record a voice message", tint = onPrimary)
                    else Icon(Icons.AutoMirrored.Filled.Send, "Send message", tint = onPrimary, modifier = Modifier.size(20.dp))
                }
            }
        }
        Text(
            buildAnnotatedString {
                append("Powered by ")
                withLink(LinkAnnotation.Url(FIGO_LABS_URL, TextLinkStyles(SpanStyle(color = FooterGrey, textDecoration = TextDecoration.Underline)))) {
                    append("Figo AI Labs")
                }
            },
            fontSize = 11.sp,
            color = FooterGrey,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 6.dp),
        )
    }
}

@Composable
private fun RoundButton(color: Color, enabled: Boolean = true, onClick: () -> Unit, content: @Composable () -> Unit) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = CircleShape,
        color = color,
        modifier = Modifier.size(44.dp).alpha(if (enabled) 1f else 0.4f),
    ) { Box(contentAlignment = Alignment.Center) { content() } }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PendingRow(vm: FigoChatViewModel, primary: Color) {
    FlowRow(
        Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        vm.pending.forEach { p ->
            Row(
                Modifier
                    .widthIn(max = 220.dp)
                    .background(if (p.failed) Color(0xFFFDECEA) else BubbleGrey, RoundedCornerShape(14.dp))
                    .padding(start = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (p.uploading) {
                    CircularProgressIndicator(Modifier.size(12.dp), color = primary, strokeWidth = 2.dp)
                    Spacer(Modifier.size(6.dp))
                }
                Text(
                    if (p.failed) "Failed: ${p.name}" else p.name,
                    fontSize = 12.sp,
                    color = TextDark,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                IconButton(onClick = { vm.removePending(p.id) }, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Filled.Close, "Remove ${p.name}", tint = TextMuted, modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}

@Composable
private fun RecordingBar(vm: FigoChatViewModel, primary: Color, onPrimary: Color) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp).heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
        if (vm.transcribing) {
            CircularProgressIndicator(Modifier.size(16.dp), color = primary, strokeWidth = 2.dp)
            Spacer(Modifier.size(10.dp))
            Text("Processing your voice message…", fontSize = 15.sp, color = TextDark)
        } else {
            Box(Modifier.size(10.dp).background(ErrorRed, CircleShape))
            Spacer(Modifier.size(10.dp))
            val s = vm.recordingMillis / 1000
            Text("Recording ${s / 60}:${(s % 60).toString().padStart(2, '0')}", fontSize = 15.sp, color = TextDark, modifier = Modifier.weight(1f))
            TextButton(onClick = { vm.stopRecording(discard = true) }) { Text("Cancel", color = TextMuted, fontSize = 15.sp) }
            RoundButton(color = primary, onClick = { vm.stopRecording() }) { Icon(Icons.Filled.Stop, "Stop recording and send", tint = onPrimary) }
        }
    }
}

@Composable
private fun MessageBubble(message: FigoChatMessage, primary: Color, onPrimary: Color, onRetry: () -> Unit) {
    if (message.role == FigoMessageRole.SYSTEM) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text(
                message.text,
                fontSize = 12.sp,
                color = Color(0xFFB3261E),
                textAlign = TextAlign.Center,
                modifier = Modifier.background(Color(0xFFFDECEA), RoundedCornerShape(10.dp)).padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
        return
    }

    val mine = message.role == FigoMessageRole.USER
    val textColor = if (mine) onPrimary else TextDark
    val maxWidth = (LocalConfiguration.current.screenWidthDp * 0.8f).dp
    val shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = if (mine) 18.dp else 4.dp, bottomEnd = if (mine) 4.dp else 18.dp)

    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
        Column(
            Modifier.widthIn(max = maxWidth).background(if (mine) primary else BubbleGrey, shape).padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            message.attachments.forEach { AttachmentView(it, textColor) }
            val rich = remember(message.text, mine, primary, onPrimary) { richText(message.text, html = !mine, if (mine) onPrimary else primary) }
            Text(rich, color = textColor, fontSize = 15.sp, lineHeight = 21.sp)
        }
        when (message.status) {
            FigoMessageStatus.SENDING -> if (mine) Text("Sending…", fontSize = 11.sp, color = TextMuted, modifier = Modifier.padding(top = 3.dp))
            FigoMessageStatus.ERROR -> if (mine) Text(
                "Not sent. Tap to retry",
                fontSize = 11.sp,
                color = ErrorRed,
                modifier = Modifier.padding(top = 3.dp).clickable(onClick = onRetry),
            )
            FigoMessageStatus.SENT -> Unit
        }
    }
}

@Composable
private fun AttachmentView(attachment: FigoAttachment, color: Color) {
    val uriHandler = LocalUriHandler.current
    val open = { runCatching { uriHandler.openUri(attachment.url) }; Unit }
    if (attachment.isImage) {
        AsyncImage(
            model = attachment.localUri ?: attachment.url,
            contentDescription = attachment.name,
            contentScale = ContentScale.Crop,
            modifier = Modifier.padding(bottom = 6.dp).size(width = 200.dp, height = 150.dp).clip(RoundedCornerShape(12.dp)).clickable(onClick = open),
        )
    } else {
        Row(
            Modifier
                .padding(bottom = 6.dp)
                .widthIn(max = 240.dp)
                .border(0.5.dp, color.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                .clickable(onClick = open)
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.InsertDriveFile, null, tint = color, modifier = Modifier.size(16.dp))
            Spacer(Modifier.size(6.dp))
            Text(attachment.name, color = color, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Shown on the left while a reply is on its way: the assistant's waiting message (HTML) with animated dots, or the dots alone. */
@Composable
private fun WaitingBubble(waitingMessage: String?) {
    var dots by remember { mutableIntStateOf(1) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(400)
            dots = dots % 3 + 1
        }
    }
    val text = remember(waitingMessage) { waitingMessage?.let(::htmlToText)?.trim()?.takeIf { it.isNotEmpty() } }
    val maxWidth = (LocalConfiguration.current.screenWidthDp * 0.8f).dp
    Box(Modifier.fillMaxWidth()) {
        val bubble = Modifier
            .widthIn(max = maxWidth)
            .background(BubbleGrey, RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomEnd = 18.dp, bottomStart = 4.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp)
        if (text != null) {
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(color = TextMuted, fontStyle = FontStyle.Italic)) { append(text) }
                    withStyle(SpanStyle(color = FooterGrey)) { append(" " + "•".repeat(dots)) }
                },
                fontSize = 15.sp,
                lineHeight = 21.sp,
                modifier = bubble,
            )
        } else {
            Text("•".repeat(dots), color = TextMuted, fontSize = 15.sp, modifier = bubble.widthIn(min = 24.dp))
        }
    }
}

/**
 * Assistant and agent messages ([html]) are rendered from their HTML: bold, italics, underline, lists and tappable
 * links, drawn natively. Text the user typed is shown as typed, with tappable urls.
 */
private fun richText(text: String, html: Boolean, linkColor: Color): AnnotatedString = buildAnnotatedString {
    val runs = if (html) htmlToRuns(text) else plainToRuns(text)
    for (r in runs) {
        val style = SpanStyle(
            fontWeight = if (r.bold) FontWeight.Bold else null,
            fontStyle = if (r.italic) FontStyle.Italic else null,
            textDecoration = if (r.underline) TextDecoration.Underline else null,
        )
        val link = r.link
        if (link != null) {
            val linkStyle = TextLinkStyles(style.merge(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)))
            withLink(LinkAnnotation.Url(link, linkStyle)) { append(r.text) }
        } else {
            withStyle(style) { append(r.text) }
        }
    }
}

/** Parses #RGB, #RRGGBB or #RRGGBBAA. */
internal fun parseHexColor(hex: String): Color {
    var h = hex.removePrefix("#")
    if (h.length == 3) h = h.map { "$it$it" }.joinToString("")
    val argb = when (h.length) {
        6 -> 0xFF000000 or h.toLong(16)
        8 -> (h.substring(6).toLong(16) shl 24) or h.substring(0, 6).toLong(16)
        else -> 0xFF2563EB
    }
    return Color(argb.toInt())
}
