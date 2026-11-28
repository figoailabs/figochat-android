# FigoChat SDK for Android

Native Android (Kotlin, Jetpack Compose) SDK for chatting with a Figo AI assistant. Users can type, send voice notes and upload files (images are read with OCR), and see live agent replies. The screen uses your brand color.

- **Min SDK:** 26 (Android 8.0)
- **Module:** `:figochat` (library)
- **Sample:** `:sample` (a View-based app that opens the chat)

## Install

The SDK is open source at [github.com/figoailabs/figochat-android](https://github.com/figoailabs/figochat-android) and installs through [JitPack](https://jitpack.io/#figoailabs/figochat-android), which builds it from the GitHub release tag.

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}

// app/build.gradle.kts
dependencies {
    implementation("com.github.figoailabs:figochat-android:2.4.0")
}
```

You can also copy the `figochat/` module into your project and use `implementation(project(":figochat"))`.

The library's manifest already declares `INTERNET`, `ACCESS_NETWORK_STATE`, `RECORD_AUDIO` and the chat activity. The microphone permission is requested the first time the user taps the mic.

## Initialize

```kotlin
FigoChat.initialize(
    apiKey = "YOUR_API_KEY",
    assistantId = "YOUR_ASSISTANT_ID",
    fullName = "Ada Lovelace",
    emailAddress = "ada@example.com",
    mobilePhone = "+2348012345678",
    primaryColor = "#F97316",
    // optional: send the customer's first question for them
    firstCustomerMessage = "Where is my order #1042?",
)
```

The header title, welcome message and waiting message come from the chatbot's settings in the FigoChat admin (**AI Chatbots** → your chatbot → **Edit**: **Persona** or **Name**, **Welcome Message**, **Choose a Waiting Message**), so they aren't passed to `initialize`. `firstCustomerMessage` is sent for the customer straight away; the chat opens with it and its reply, and continues the same thread.

Java apps can call `FigoChat.initialize(...)` the same way. The optional parameters have overloads.

**Users you don't know:** leave `fullName`, `emailAddress` and `mobilePhone` out and the chat asks for them with a short form (name, email, phone with a searchable country-code picker) that the user can skip. Submitted details are used as if passed to `initialize` and kept in memory only, for the conversation session (see 2.4.0 below); nothing is saved on the device, and `logout` forgets them. Set `askForDetails: false` to skip the form, `defaultCountry` to preselect a country in the phone field (an ISO code such as `GB`; when it's unset or unknown, Nigeria (`NG`) is preselected), and call `setCustomerDetails` when the user signs in later. When the company has both a terms and conditions link and a privacy policy link, the form shows an "I have read and agreed to the Terms, conditions, and privacy policy" checkbox, and **Start chat** stays disabled until it's ticked (**Skip** still works).

## Show the chat

```kotlin
// Full-screen chat, from any Activity or Context
FigoChat.open(context)

// Or embed it in Compose
FigoChatScreen(onClose = { /* navigate back */ })
```

`FigoChatScreen` parameters: `modifier`, `onClose`, `showHeader`, `placeholder`, `enableVoice`, `enableAttachments`, `onError`.

## Other calls

```kotlin
FigoChat.threadId             // save it to resume later via initialize(threadId = ...)
FigoChat.resetConversation()  // next message starts a new thread
FigoChat.logout()             // clear settings on sign-out

// Build your own UI (suspend functions)
val reply = FigoChat.client.sendMessage("Hello")
val text = FigoChat.client.transcribe(audioFile)
val urls = FigoChat.client.uploadFiles(listOf(FigoUploadFile("receipt.jpg", bytes, "image/jpeg")))
val history = FigoChat.client.getMessages()   // the current thread's messages, oldest first ([] when none)
```

### Events

```kotlin
val listener = FigoChat.addListener(object : FigoChatListener {
    override fun onMessageSent(text: String) { /* the user's message, after the API accepts it */ }
    override fun onMessageReceived(text: String) { /* assistant or live agent reply, HTML stripped */ }
})
// later
FigoChat.removeListener(listener)
```

Both are called on the main thread. Listeners can be added before `initialize` and stay registered after `logout`. A listener that throws never breaks the chat.

### Formatted replies

Assistant replies arrive as HTML. The chat renders them natively as formatted text (bold, italics, underline, headings, lists, line breaks, tappable links), so users never see tags or markdown symbols. For your own UI, `htmlToRuns(html)` returns the same styled runs and `htmlToText(html)` returns plain text.

Errors are `FigoChatException` with a `code` (`401`, `49`, `TIMEOUT`, `NETWORK`, …). The chat screen never shows these technical messages to users. It shows friendly text instead, such as "Chat isn't available right now. Please try again later." for a bad key, no credits or a plan limit, and "You seem to be offline…" for network problems. `onError` still receives the full technical error for your logs. Apps with their own UI can use `friendlyMessage(error)`.

## 2.4.0

A phone or tablet may be shared, so the chat now behaves like a browser tab.

- **One-hour conversation session:** the conversation's thread and the details typed into the "Personalize your chat" form make up a session that lasts one hour from the last activity (a message the user sent, or a reply or live agent message that arrived). Calling `initialize` again in the same app run, for the same assistant and the same details passed by the app, continues it. After an hour without activity, the next `initialize`, opening the chat or sending a message starts a new conversation: the thread is reset, the form details and a skipped form are forgotten, and an anonymous visitor sees the details form again (unless `askForDetails` is `false`). Details your app passes to `initialize` or `setCustomerDetails` are kept.
- **Details kept only for the app's run:** form details live in memory and are gone when the app process ends. They're no longer saved in SharedPreferences, and details saved there by earlier versions are deleted on `initialize`.
- **Restored history:** when the chat opens on an active conversation (one from this app run, or a `threadId` passed to `initialize`), its earlier messages are loaded from `GET /api/v1/chat/messages/{assistantId}/{threadId}` and shown below the welcome message: the user's on the right, the assistant's and live agent's on the left, formatted like any reply. A `firstCustomerMessage` exchange that the history already holds isn't shown twice. If loading fails, the chat works as before. Apps with their own UI can call `FigoChat.client.getMessages(threadId)`.

## 2.3.0

- **Consent checkbox:** when the assistant's company has both a terms and conditions link and a privacy policy link, the details form shows "I have read and agreed to the Terms, conditions, and privacy policy". "Terms, conditions" and "privacy policy" open the links in the browser, the box uses your primary color, and **Start chat** is disabled until it's ticked. **Skip** is still available. With only one link (or none) there's no checkbox.
- **Disclaimer:** the assistant's disclaimer message (for example "AI can make mistakes. Double-check sensitive information") is shown in small grey text above the "Powered by Figo AI Labs" footer, on the chat and on the details form. `FigoChat.assistantInfo` now has `disclaimerMessage` and `company` (`termsAndCondition`, `privacyPolicy`).
- **Nigeria default country:** the phone field preselects `defaultCountry` when it's a known ISO code, otherwise Nigeria (`NG`). The device's region is no longer used.

## Live agent messages

After the first reply, the chat subscribes to the Pusher channel named after the conversation's thread id. Messages a live agent sends from Tawk.to, Zoho SalesIQ or FreshChat appear on the left, like assistant replies. The Pusher key and cluster come from `GET /api/v1/realtime/config`. They're kept encrypted in memory with `FigoCrypto` (AES-256-GCM with a random per-session key) and decrypted only when subscribing.

## Build the sample

```bash
./gradlew :sample:installDebug -PfigoApiKey=YOUR_API_KEY -PfigoAssistantId=YOUR_ASSISTANT_ID
```

Building needs JDK 17. Set `JAVA_HOME` to a JDK 17 if your default is older.

## API key security

The API key ships inside your app, so anyone who unpacks the app can read it and call every API-key endpoint, spending the company's credits. Create a key just for the SDK under **API Keys** in the FigoChat admin ([app.figolabs.ai](https://app.figolabs.ai)). Keys expire 3 years after they're created. If a key leaks, delete it there and ship an update with a new key. For stronger protection, have your own backend issue short-lived keys and pass them to `initialize` after sign-in.
