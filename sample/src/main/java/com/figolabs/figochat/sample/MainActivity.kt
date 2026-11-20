package com.figolabs.figochat.sample

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.figolabs.figochat.FigoChat
import com.figolabs.figochat.FigoChatConfig
import com.figolabs.figochat.FigoChatException

/** A classic View-based app opening the chat; Compose apps can embed FigoChatScreen() directly. */
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Build with -PfigoApiKey=... -PfigoAssistantId=... (and optionally -PfigoFirstMessage=...); without them the chat still opens and messages fail.
        val apiKey = BuildConfig.FIGO_API_KEY
        val assistantId = BuildConfig.FIGO_ASSISTANT_ID
        val warning = when {
            apiKey.isBlank() -> "No figoApiKey set: messages will fail."
            else -> null
        }

        val error = try {
            FigoChat.initialize(
                apiKey = apiKey.ifBlank { "missing-api-key" },
                assistantId = assistantId.ifBlank { "missing-assistant-id" },
                fullName = "Ada Lovelace",
                emailAddress = "ada@example.com",
                mobilePhone = "+2348012345678",
                primaryColor = "#F97316",
                baseUrl = BuildConfig.FIGO_BASE_URL.ifBlank { FigoChatConfig.DEFAULT_BASE_URL },
                // the header title, intro and waiting messages come from the assistant's settings in the FigoChat admin
                firstCustomerMessage = BuildConfig.FIGO_FIRST_MESSAGE.takeIf { it.isNotBlank() },
            )
            null
        } catch (e: FigoChatException) {
            e.message
        }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(48, 48, 48, 48)
        }
        layout.addView(TextView(this).apply {
            text = "My App"
            textSize = 28f
            gravity = Gravity.CENTER
        })
        (error ?: warning)?.let { message ->
            layout.addView(TextView(this).apply {
                text = message
                setTextColor(Color.parseColor("#B3261E"))
                gravity = Gravity.CENTER
                setPadding(0, 24, 0, 24)
            })
        }
        layout.addView(Button(this).apply {
            text = "Chat with us"
            isEnabled = error == null
            setOnClickListener { FigoChat.open(this@MainActivity) }
        })
        setContentView(layout)
    }
}
