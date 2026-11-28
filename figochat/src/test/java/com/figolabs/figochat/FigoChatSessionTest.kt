package com.figolabs.figochat

import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
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
        /** Response to GET /api/v1/chat/messages/...; null answers 404. */
        val history: JSONArray? = null,
    ) : Dispatcher() {
        val gate = CountDownLatch(1)
        val requests = CopyOnWriteArrayList<Pair<RecordedRequest, String>>()

        override fun dispatch(request: RecordedRequest): MockResponse {
            requests += request to request.body.readUtf8()
            gate.await(5, TimeUnit.SECONDS)
            val path = request.path.orEmpty()
            return when {
                path == "/api/v1/sdk/assistant/asst-1" -> json(JSONObject().put("responseCode", "00").put("responseData", assistant))
                path.startsWith("/api/v1/chat/messages/") ->
                    if (history == null) MockResponse().setResponseCode(404).setBody("\"Not found\"")
                    else json(JSONObject().put("responseCode", "00").put("responseMessage", "Successful").put("responseData", history))
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
    private val originalLegacy = FigoChat.deleteLegacyDetails
    private val originalClock = FigoChat.clock

    /** Times the details older versions stored on the device were deleted. */
    private val legacyDeletes = java.util.concurrent.atomic.AtomicInteger()

    /** The fake clock used for session expiry. */
    @Volatile private var now = 1_700_000_000_000L

    @Before
    fun setUp() {
        // no main looper in JVM tests: run listener callbacks right away
        FigoChat.dispatchToMain = { it.run() }
        FigoChat.deleteLegacyDetails = { legacyDeletes.incrementAndGet() }
        FigoChat.clock = { now }
        server.start()
        useApi(FakeApi())
    }

    @After
    fun tearDown() {
        api.gate.countDown()
        FigoChat.logout()
        FigoChat.dispatchToMain = originalDispatch
        FigoChat.deleteLegacyDetails = originalLegacy
        FigoChat.clock = originalClock
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
    fun theDisclaimerAndCompanyLinksAreParsed() {
        val data = JSONObject(defaultAssistant.toString())
            .put("disclaimerMessage", "  AI can make mistakes. Double-check sensitive information ")
            .put("company", JSONObject().put("termsAndCondition", "https://example.com/terms").put("privacyPolicy", "https://example.com/privacy"))
        useApi(FakeApi(assistant = data).also { it.gate.countDown() })
        initialize()
        eventually("assistant info") { FigoChat.assistantInfo != null }

        val info = FigoChat.assistantInfo!!
        assertEquals("Dave", info.persona)
        assertEquals("  AI can make mistakes. Double-check sensitive information ", info.disclaimerMessage)
        assertEquals("AI can make mistakes. Double-check sensitive information", info.disclaimer)
        assertEquals(FigoCompanyInfo("https://example.com/terms", "https://example.com/privacy"), info.company)
        assertEquals("https://example.com/terms" to "https://example.com/privacy", info.consentLinks)
    }

    @Test
    fun missingOrNullDisclaimerAndCompanyAreNull() {
        val data = JSONObject(defaultAssistant.toString()).put("disclaimerMessage", JSONObject.NULL).put("company", JSONObject.NULL)
        useApi(FakeApi(assistant = data).also { it.gate.countDown() })
        initialize()
        eventually("assistant info") { FigoChat.assistantInfo != null }
        assertNull(FigoChat.assistantInfo!!.disclaimerMessage)
        assertNull(FigoChat.assistantInfo!!.company)
        FigoChat.logout()

        // older servers: neither field is sent, and the company may have null links
        useApi(FakeApi(assistant = JSONObject(defaultAssistant.toString()).put("company", JSONObject().put("privacyPolicy", JSONObject.NULL))).also { it.gate.countDown() })
        initialize()
        eventually("assistant info") { FigoChat.assistantInfo != null }
        val info = FigoChat.assistantInfo!!
        assertEquals(FigoAssistantInfo("Support Bot", "Dave", "One moment…", "<b>Hi!</b>", company = FigoCompanyInfo()), info)
        assertNull(info.disclaimer)
        assertNull(info.consentLinks)
    }

    @Test
    fun theConsentCheckboxNeedsBothTheTermsAndThePrivacyPolicy() {
        fun links(terms: String?, privacy: String?) = FigoAssistantInfo(company = FigoCompanyInfo(terms, privacy)).consentLinks
        assertEquals("https://a.com/t" to "https://a.com/p", links(" https://a.com/t ", "https://a.com/p\n"))
        assertEquals("Our terms" to "Our privacy policy", links("Our terms", "Our privacy policy"))
        assertNull(links("https://a.com/t", null))
        assertNull(links(null, "https://a.com/p"))
        assertNull(links("https://a.com/t", "   "))
        assertNull(links("", "https://a.com/p"))
        assertNull(FigoAssistantInfo().consentLinks)
        assertNull(FigoAssistantInfo(disclaimerMessage = "  ").disclaimer)

        assertTrue(isWebUrl("https://a.com/t"))
        assertTrue(isWebUrl(" HTTP://a.com/t "))
        assertFalse(isWebUrl("Our terms"))
        assertFalse(isWebUrl("mailto:legal@a.com"))
        assertFalse(isWebUrl("https://"))
    }

    @Test
    fun theDefaultCountryIsTheOneGivenElseNigeriaWhateverTheDeviceRegion() {
        val original = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.UK)
            assertEquals("NG", FigoCountries.default(null).code)
            assertEquals("NG", FigoCountries.default("").code)
            assertEquals("NG", FigoCountries.default("  ").code)
            assertEquals("NG", FigoCountries.default("XX").code)
            assertEquals("NG", FigoCountries.default("Nigeria").code)
            assertEquals("GH", FigoCountries.default(" gh ").code)
            assertEquals("US", FigoCountries.default("US").code)
        } finally {
            java.util.Locale.setDefault(original)
        }
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

    // ---------- details form (anonymous visitors) ----------

    private fun initializeAnonymous(firstCustomerMessage: String? = null, askForDetails: Boolean = true, defaultCountry: String? = null) =
        FigoChat.initialize(
            apiKey = "k",
            assistantId = "asst-1",
            primaryColor = "#F97316",
            baseUrl = server.url("/").toString(),
            firstCustomerMessage = firstCustomerMessage,
            askForDetails = askForDetails,
            defaultCountry = defaultCountry,
        )

    private fun chatRequests() = api.requests.filter { it.first.path.orEmpty().startsWith("/api/v1/chat/assistant/") }

    @Test
    fun withoutNameEmailAndPhoneTheChatAsksForThemAndTheFirstMessageWaitsForTheForm() {
        initializeAnonymous(firstCustomerMessage = "Where is my order?", defaultCountry = "NG")
        api.gate.countDown()
        eventually("assistant info") { FigoChat.assistantInfo != null }
        assertTrue(FigoChat.needsDetails)
        assertNull("not sent before the form", FigoChat.firstExchange)
        assertTrue(chatRequests().isEmpty())
        assertEquals(FigoCustomerDetails(), FigoChat.customerDetails)

        val ng = FigoCountries.default(FigoChat.config.defaultCountry)
        assertEquals("\uD83C\uDDF3\uD83C\uDDEC +234", "${ng.flag} +${ng.dial}")
        assertTrue(FigoCountries.all.size > 200)

        // validation, in order
        assertEquals(FigoDetailsMessages.NAME, validateCustomerDetails("", "", ng, "").error)
        assertEquals("Please enter your full name.", validateCustomerDetails(" A ", "", ng, "").error)
        assertEquals("Please enter a valid email address.", validateCustomerDetails("Ada Lovelace", "ada@", ng, "").error)
        assertEquals("Please enter a valid phone number.", validateCustomerDetails("Ada Lovelace", "ada@example.com", ng, "12").error)
        assertEquals(FigoDetailsField.MOBILE_PHONE, validateCustomerDetails("Ada Lovelace", "ada@example.com", ng, "12").field)

        // a GB number typed with its trunk 0
        val gb = FigoCountries.byCode("gb")!!
        assertEquals("\uD83C\uDDEC\uD83C\uDDE7 +44", "${gb.flag} +${gb.dial}")
        val ok = validateCustomerDetails(" Ada Lovelace ", "ada@example.com", gb, "07700 900123")
        assertNull(ok.error)
        FigoChat.submitDetails(ok.details!!)

        assertFalse(FigoChat.needsDetails)
        val expected = FigoCustomerDetails("Ada Lovelace", "ada@example.com", "+447700900123")
        assertEquals(expected, FigoChat.customerDetails)
        eventually("first exchange") { FigoChat.firstExchange?.done == true }
        val body = JSONObject(chatRequests().single().second)
        assertEquals("Where is my order?", body.getString("question"))
        assertEquals("Ada Lovelace", body.getString("fullName"))
        assertEquals("ada@example.com", body.getString("emailAddress"))
        assertEquals("+447700900123", body.getString("phoneNumber"))
    }

    @Test
    fun formDetailsAreKeptInMemoryForTheSessionNotOnTheDeviceAndLogoutForgetsThem() {
        api.gate.countDown()
        initializeAnonymous()
        assertTrue(FigoChat.needsDetails)
        // what older versions stored on the device is deleted on initialize
        eventually("legacy details deleted") { legacyDeletes.get() > 0 }

        val details = FigoCustomerDetails("Ada Lovelace", "ada@example.com", "+447700900123")
        FigoChat.submitDetails(details)

        // the chat opened again later in the same app run: not asked again
        initializeAnonymous()
        assertFalse(FigoChat.needsDetails)
        assertEquals(details, FigoChat.customerDetails)
        assertEquals("ada@example.com", FigoChat.config.emailAddress)

        // nothing but the in-memory session holds them: no storage seam exists any more, and logout forgets them
        FigoChat.logout()
        initializeAnonymous()
        assertTrue(FigoChat.needsDetails)
        assertEquals(FigoCustomerDetails(), FigoChat.customerDetails)
    }

    @Test
    fun theOldDetailsPrefsAreClearedAndErrorsAreIgnored() {
        FigoChat.deleteLegacyDetails = { legacyDeletes.incrementAndGet(); throw IllegalStateException("no storage") }
        initializeAnonymous()
        eventually("legacy details deleted") { legacyDeletes.get() > 0 }
        assertTrue(FigoChat.isInitialized)
        assertTrue(FigoChat.needsDetails)
    }

    @Test
    fun skippingChatsWithoutDetailsAndIsRememberedForTheAppRun() {
        api.gate.countDown()
        initializeAnonymous(firstCustomerMessage = "Hi")
        assertTrue(FigoChat.needsDetails)
        FigoChat.skipDetails()
        assertFalse(FigoChat.needsDetails)
        eventually("first exchange") { FigoChat.firstExchange?.done == true }
        val body = JSONObject(chatRequests().single().second)
        assertEquals("Hi", body.getString("question"))
        assertEquals("", body.getString("fullName"))
        assertEquals("", body.getString("emailAddress"))
        assertEquals("", body.getString("phoneNumber"))

        initializeAnonymous() // e.g. the chat opened again later in the same app run
        assertFalse(FigoChat.needsDetails)
    }

    @Test
    fun setCustomerDetailsOrAskForDetailsFalseAvoidTheForm() {
        api.gate.countDown()
        initializeAnonymous()
        assertTrue(FigoChat.needsDetails)
        FigoChat.setCustomerDetails(fullName = " Grace ", emailAddress = "g@x.io", mobilePhone = "+15550100")
        assertFalse(FigoChat.needsDetails)
        assertEquals(FigoCustomerDetails("Grace", "g@x.io", "+15550100"), FigoChat.customerDetails)
        // later requests use them
        kotlinx.coroutines.runBlocking { FigoChat.client.sendMessage("Hello") }
        assertEquals("Grace", JSONObject(chatRequests().last().second).getString("fullName"))
        FigoChat.logout()

        initializeAnonymous(askForDetails = false)
        assertFalse(FigoChat.needsDetails)
        assertEquals(FigoCustomerDetails(), FigoChat.customerDetails)
    }

    @Test
    fun countriesCanBeSearchedByNameCodeOrDialingCode() {
        assertTrue(FigoCountries.search("+44").any { it.code == "GB" })
        assertTrue(FigoCountries.search("ng").any { it.code == "NG" })
        assertTrue(FigoCountries.search("nigeria").any { it.code == "NG" })
        assertEquals("", FigoCountries.internationalNumber(FigoCountries.byCode("NG")!!, "0"))
        assertEquals("+2348012345678", FigoCountries.internationalNumber(FigoCountries.byCode("NG")!!, "0801-234-5678"))
    }

    // ---------- conversation session (1 hour) and history ----------

    private fun history() = JSONArray()
        .put(JSONObject().put("role", "user").put("message", "Where is my order?").put("isLiveAgent", false).put("dateCreated", "2026-11-28T10:00:00.1234567Z"))
        .put(JSONObject().put("role", "assistant").put("message", "<p>It ships <b>today</b></p>").put("isLiveAgent", false).put("dateCreated", "2026-11-28T10:00:05"))
        .put(JSONObject().put("role", "assistant").put("message", "Hi, this is Sam").put("isLiveAgent", true).put("dateCreated", "2026-11-28T11:00:00+01:00"))
        .put(JSONObject().put("role", "system").put("message", "ignored"))
        .put(JSONObject().put("role", "user").put("message", JSONObject.NULL).put("dateCreated", "x"))

    @Test
    fun getMessagesParsesTheHistoryOldestFirst() {
        useApi(FakeApi(history = history()).also { it.gate.countDown() })
        FigoChat.initialize(apiKey = "k", assistantId = "asst-1", primaryColor = "#F97316", baseUrl = server.url("/").toString(), threadId = "thread-7")
        val messages = kotlinx.coroutines.runBlocking { FigoChat.client.getMessages() }

        assertEquals(listOf(FigoMessageRole.USER, FigoMessageRole.ASSISTANT, FigoMessageRole.ASSISTANT), messages.map { it.role })
        assertEquals(listOf("Where is my order?", "<p>It ships <b>today</b></p>", "Hi, this is Sam"), messages.map { it.text })
        assertTrue(messages.all { it.status == FigoMessageStatus.SENT })
        assertEquals(messages.size, messages.map { it.id }.toSet().size)
        assertEquals(java.time.Instant.parse("2026-11-28T10:00:00.123Z").toEpochMilli(), messages[0].createdAt)
        assertEquals(java.time.Instant.parse("2026-11-28T10:00:05Z").toEpochMilli(), messages[1].createdAt)
        assertEquals(java.time.Instant.parse("2026-11-28T10:00:00Z").toEpochMilli(), messages[2].createdAt)

        val request = api.requests.map { it.first }.first { it.path.orEmpty().startsWith("/api/v1/chat/messages/") }
        assertEquals("GET", request.method)
        assertEquals("/api/v1/chat/messages/asst-1/thread-7", request.path)
        assertEquals("Bearer k", request.getHeader("Authorization"))
    }

    @Test
    fun serverDatesAreParsedLeniently() {
        fun at(iso: String) = java.time.Instant.parse(iso).toEpochMilli()
        assertEquals(at("2026-11-28T10:00:00Z"), FigoChatClient.parseServerDate("2026-11-28T10:00:00Z"))
        assertEquals(at("2026-11-28T10:00:00.120Z"), FigoChatClient.parseServerDate(" 2026-11-28 10:00:00.12 "))
        assertEquals(at("2026-11-28T08:30:00Z"), FigoChatClient.parseServerDate("2026-11-28T10:30:00+0200"))
        assertEquals(at("2026-11-28T00:00:00Z"), FigoChatClient.parseServerDate("2026-11-28"))
        assertNull(FigoChatClient.parseServerDate("yesterday"))
        assertNull(FigoChatClient.parseServerDate(""))
        assertNull(FigoChatClient.parseServerDate(null))
    }

    @Test
    fun getMessagesGivesAnEmptyListFor404AndThrowsForOtherFailures() {
        api.gate.countDown()
        initialize()
        assertTrue(kotlinx.coroutines.runBlocking { FigoChat.client.getMessages("unknown") }.isEmpty())
        // no thread yet: nothing to ask for
        assertTrue(kotlinx.coroutines.runBlocking { FigoChat.client.getMessages() }.isEmpty())

        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setResponseCode(500).setBody("\"Server error\"")
        }
        val error = try {
            kotlinx.coroutines.runBlocking { FigoChat.client.getMessages("thread-7") }
            null
        } catch (e: FigoChatException) {
            e
        }
        assertEquals("500", error?.code)
    }

    @Test
    fun anHourWithoutActivityEndsTheSessionClearsThreadAndDetailsAndAsksAgain() {
        api.gate.countDown()
        initializeAnonymous(firstCustomerMessage = "Hi")
        FigoChat.submitDetails(FigoCustomerDetails("Ada Lovelace", "ada@example.com", "+447700900123"))
        eventually("first exchange") { FigoChat.firstExchange?.done == true }
        assertEquals("thread-42", FigoChat.threadId)
        val conversation = FigoChat.conversation.value

        now += FigoChat.SESSION_TIMEOUT_MILLIS - 1
        assertFalse(FigoChat.expireSessionIfIdle())
        assertEquals("thread-42", FigoChat.threadId)

        now += 1
        assertTrue(FigoChat.expireSessionIfIdle())
        assertNull(FigoChat.threadId)
        assertNull(FigoChat.firstExchange)
        assertTrue(FigoChat.needsDetails)
        assertEquals(FigoCustomerDetails(), FigoChat.customerDetails)
        assertTrue("the chat screen clears its messages", FigoChat.conversation.value > conversation)
        assertFalse("checked once", FigoChat.expireSessionIfIdle())

        // a new initialize after the hour doesn't resume either; the skipped flag is forgotten too
        FigoChat.skipDetails()
        assertFalse(FigoChat.needsDetails)
        now += FigoChat.SESSION_TIMEOUT_MILLIS
        initializeAnonymous()
        assertTrue(FigoChat.needsDetails)
        assertNull(FigoChat.threadId)
    }

    @Test
    fun expiryKeepsDetailsTheAppPassedAndDoesNotAskForTheForm() {
        api.gate.countDown()
        initialize(firstCustomerMessage = "Hi")
        eventually("first exchange") { FigoChat.firstExchange?.done == true }
        now += FigoChat.SESSION_TIMEOUT_MILLIS
        assertTrue(FigoChat.expireSessionIfIdle())
        assertNull(FigoChat.threadId)
        assertFalse(FigoChat.needsDetails)
        assertEquals(FigoCustomerDetails("Ada", "a@b.c", "+1"), FigoChat.customerDetails)

        // askForDetails = false: no form after expiry either
        FigoChat.logout()
        initializeAnonymous(askForDetails = false, firstCustomerMessage = "Hi")
        eventually("first exchange") { FigoChat.firstExchange?.done == true }
        now += FigoChat.SESSION_TIMEOUT_MILLIS
        assertTrue(FigoChat.expireSessionIfIdle())
        assertFalse(FigoChat.needsDetails)
    }

    @Test
    fun activityRefreshesTheHourAndTheSessionIsResumedByInitialize() {
        api.gate.countDown()
        initializeAnonymous()
        FigoChat.submitDetails(FigoCustomerDetails("Ada Lovelace", "ada@example.com", "+447700900123"))
        kotlinx.coroutines.runBlocking { FigoChat.client.sendMessage("Hello") }
        FigoChat.recordActivity() // what the chat screen does after a reply
        assertEquals("thread-42", FigoChat.threadId)

        now += FigoChat.SESSION_TIMEOUT_MILLIS - 1000
        FigoChat.recordActivity() // e.g. a live agent message
        now += FigoChat.SESSION_TIMEOUT_MILLIS - 1000
        assertFalse(FigoChat.expireSessionIfIdle())

        // initialize again in the same app run, within the hour: same thread, same details, no form
        initializeAnonymous()
        assertEquals("thread-42", FigoChat.threadId)
        assertFalse(FigoChat.needsDetails)
        assertEquals("ada@example.com", FigoChat.customerDetails.emailAddress)

        // other app-passed details start a new session
        initialize()
        assertNull(FigoChat.threadId)

        // a threadId the app passes starts the hour now
        FigoChat.initialize(apiKey = "k", assistantId = "asst-1", primaryColor = "#F97316", baseUrl = server.url("/").toString(), threadId = "thread-9")
        assertEquals("thread-9", FigoChat.threadId)
        now += FigoChat.SESSION_TIMEOUT_MILLIS - 1
        assertFalse(FigoChat.expireSessionIfIdle())
        now += 1
        assertTrue(FigoChat.expireSessionIfIdle())
        assertNull(FigoChat.threadId)
    }
}
