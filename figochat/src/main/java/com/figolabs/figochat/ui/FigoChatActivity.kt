package com.figolabs.figochat.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.figolabs.figochat.FigoChat
import kotlinx.coroutines.flow.StateFlow

/** Full-screen chat opened by [FigoChat.open]. */
class FigoChatActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!FigoChat.isInitialized) {
            // the process was recreated without the host app initializing the SDK again
            finish()
            return
        }
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                Box(Modifier.fillMaxSize().background(Color.White).windowInsetsPadding(WindowInsets.safeDrawing)) {
                    FigoChatScreen(onClose = { finish() })
                }
            }
        }
    }
}

@Composable
internal fun <T> StateFlow<T>.collectAsStateCompat(): State<T> = collectAsState()
