package com.figolabs.figochat

import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** The session store behind the chat screen: assistant details and the firstCustomerMessage exchange, against a fake API. */
class FigoChatSessionTest {

    private val defaultAssistant = JSONObject()
        .put("assistantName", "Support Bot")
        .put("persona", "Dave")
        .put("waitingMessage", "One moment…")
        .put("introMessage", "<b>Hi!</b>")

    /** Fake API; requests wait on [gate] until it is opened, so the in-flight state can be checked. */
    private inner class FakeApi(
        val assistant: JSONObject = defaultAssistant,
        val answer: String = "<p>Your balance is <b>₦500</b></p>",
        val fail: Boolean = false,
    ) : Dispatcher() {
        val gate = CountDownLatch(1)
        val requests = CopyOnWriteArrayList<Pair<RecordedRequest, String>>()

        override fun dispatch(request: RecordedRequest): MockResponse {
            requests += request to request.body.readUtf8()
            gate.await(5, TimeUnit.SECONDS)
            val path = request.path.orEmpty()
            return when {
                path == "/api/v1/sdk/assistant/asst-1" -> json(JSONObject().put("responseCode", "00").put("responseData", assistant))
                path.startsWith("/api/v1/chat/assistant/") ->
                    if (fail) MockResponse().setResponseCode(401).setBody("\"Invalid api-key was passed\"")
                    else json(JSONObject().put("responseCode", "00").put("responseData", answer))
                        .setHeader("X-Thread-Id", "thread-42")
                        .setHeader("X-Thread-Mode", "ai")
                else -> MockResponse().setResponseCode(404)
            }
        }

        private fun json(body: JSONObject) = MockResponse().setHeader("Content-Type", "application/json").setBody(body.toString())
    }

    private val server = MockWebServer()
    private lateinit var api: FakeApi
    private val originalDispatch = FigoChat.dispatchToMain

    @Before
    fun setUp() {
        // no main looper in JVM tests: run listener callbacks right away
        FigoChat.dispatchToMain = { it.run() }
        server.start()
        useApi(FakeApi())
    }

    @After
    fun tearDown() {
        api.gate.countDown()
        FigoChat.logout()
        FigoChat.dispatchToMain = originalDispatch
        server.shutdown()
    }

    private fun useApi(fake: FakeApi) {
        api = fake
        server.dispatcher = fake
    }

    private fun initialize(firstCustomerMessage: String? = null, baseUrl: String = server.url("/").toString()) =
        FigoChat.initialize(
            apiKey = "k",
            fullName = "Ada",
            emailAddress = "a@b.c",
            mobilePhone = "+1",
            assistantId = "asst-1",
            primaryColor = "#F97316",
            baseUrl = baseUrl,
            firstCustomerMessage = firstCustomerMessage,
        )

    private fun eventually(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) throw AssertionError("timed out waiting for $what")
            Thread.sleep(10)
        }
    }

    @Test
    fun initializeLoadsTheAssistantDetailsForTitleIntroAndWaitingMessage() {
        initialize()
        assertNull(FigoChat.assistantInfo)
        api.gate.countDown()
        eventually("assistant info") { FigoChat.assistantInfo != null }

        assertEquals(FigoAssistantInfo("Support Bot", "Dave", "One moment…", "<b>Hi!</b>"), FigoChat.assistantInfo)
        val (request, _) = api.requests.first()
        assertEquals("GET", request.method)
        assertEquals("/api/v1/sdk/assistant/asst-1", request.path)
        assertEquals("Bearer k", request.getHeader("Authorization"))
        assertNull(FigoChat.firstExchange)
    }

    @Test
    fun firstCustomerMessageIsSentOnInitializeKeepsTheThreadAndRaisesBothEvents() {
        val events = CopyOnWriteArrayList<Pair<String, String>>()
        val listener = FigoChat.addListener(object : FigoChatListener {
            override fun onMessageSent(text: String) { events += "sent" to text }
            override fun onMessageReceived(text: String) { events += "received" to text }
        })
        try {
            initialize(firstCustomerMessage = "  What is my balance?  ")
            val first = FigoChat.firstExchange!!
            assertEquals("What is my balance?", first.message.text)
            assertEquals(FigoMessageStatus.SENDING, first.message.status)
            assertFalse(first.done)

            api.gate.countDown()
            eventually("first exchange") { FigoChat.firstExchange?.done == true }
            val done = FigoChat.firstExchange!!
            assertEquals(FigoMessageStatus.SENT, done.message.status)
            assertEquals("<p>Your balance is <b>₦500</b></p>", done.reply?.text)
            assertNull(done.error)
            assertEquals("thread-42", FigoChat.threadId)

            val chat = api.requests.first { it.first.path.orEmpty().startsWith("/api/v1/chat/assistant/") }
            val body = JSONObject(chat.second)
            assertEquals(setOf("question", "fullName", "emailAddress", "phoneNumber"), body.keySet())
            assertEquals("What is my balance?", body.getString("question"))
            assertEquals("Ada", body.getString("fullName"))
            assertEquals("a@b.c", body.getString("emailAddress"))
            assertEquals("+1", body.getString("phoneNumber"))
            assertEquals(listOf("sent" to "What is my balance?", "received" to "Your balance is ₦500"), events.toList())
        } finally {
            FigoChat.removeListener(listener)
        }
    }

    @Test
    fun aFailedFirstCustomerMessageIsMarkedForRetryAndResetConversationClearsIt() {
        useApi(FakeApi(fail = true).also { it.gate.countDown() })
        initialize(firstCustomerMessage = "Hello")
        eventually("first exchange") { FigoChat.firstExchange?.done == true }
        val first = FigoChat.firstExchange!!
        assertEquals(FigoMessageStatus.ERROR, first.message.status)
        assertEquals("401", first.error?.code)

        useApi(FakeApi().also { it.gate.countDown() })
        FigoChat.retryFirstMessage()
        eventually("retried first exchange") { FigoChat.firstExchange?.done == true }
        assertEquals(FigoMessageStatus.SENT, FigoChat.firstExchange?.message?.status)
        assertNotNull(FigoChat.firstExchange?.reply)

        FigoChat.resetConversation()
        assertNull(FigoChat.firstExchange)
        assertNull(FigoChat.threadId)
    }

    @Test
    fun theChatStillWorksWhenTheAssistantDetailsCannotLoad() {
        val offline = server.url("/").toString()
        server.shutdown()
        initialize(baseUrl = offline)
        Thread.sleep(300)
        assertNull(FigoChat.assistantInfo)
        assertTrue(FigoChat.isInitialized)
    }
}
