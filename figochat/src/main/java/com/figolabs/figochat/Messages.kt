package com.figolabs.figochat

/** What the user was doing when the error happened; picks the fallback wording. */
enum class FigoErrorContext { CHAT, VOICE, RECORDING, UPLOAD }

/**
 * Turns an SDK error into a message that is safe to show to the app's users. Setup problems the user can't fix
 * (invalid or expired API key, no credits, plan limit) all read as "chat isn't available"; the technical message
 * stays on the exception, which `onError` receives for logging.
 */
fun friendlyMessage(error: Throwable, context: FigoErrorContext = FigoErrorContext.CHAT): String =
    when ((error as? FigoChatException)?.code) {
        "401", "403", "49", "CONFIG", "NOT_INITIALIZED" -> "Chat isn't available right now. Please try again later."
        "NETWORK" -> "You seem to be offline. Check your internet connection and try again."
        "TIMEOUT" -> "This is taking longer than usual. Please try again."
        else -> when (context) {
            FigoErrorContext.CHAT -> "Your message couldn't be sent. Please try again."
            FigoErrorContext.VOICE -> "We couldn't process your voice message. Please try again."
            FigoErrorContext.RECORDING -> "The microphone couldn't start. Please try again."
            FigoErrorContext.UPLOAD -> "We couldn't upload that file. Please try again."
        }
    }
