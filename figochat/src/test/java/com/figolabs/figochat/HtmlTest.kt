package com.figolabs.figochat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class HtmlTest {
    private val reply =
        "<span style=\"line-height:1.3\">\n<b>What I can help you with</b>\n<p>I can handle a lot inside <i>BillsClan</i>.</p>\n\n" +
            "<ul>\n<li>Buy airtime</li>\n<li>Pay electricity bills</li>\n</ul>\n<p>Visit <a href=\"https://billsclan.com\">our site</a>.</p>" +
            "<ol><li>One</li><li>Two</li></ol></span>"

    @Test
    fun assistantHtmlReplyBecomesStyledRunsNeverMarkdown() {
        assertEquals(
            listOf(
                FigoTextRun("What I can help you with", bold = true),
                FigoTextRun("\n\nI can handle a lot inside "),
                FigoTextRun("BillsClan", italic = true),
                FigoTextRun(".\n\n• Buy airtime\n• Pay electricity bills\n\nVisit "),
                FigoTextRun("our site", link = "https://billsclan.com"),
                FigoTextRun(".\n\n1. One\n2. Two"),
            ),
            htmlToRuns(reply),
        )
    }

    @Test
    fun htmlToTextStripsTheHtmlToPlainText() {
        assertEquals(
            "What I can help you with\n\nI can handle a lot inside BillsClan.\n\n• Buy airtime\n• Pay electricity bills\n\nVisit our site.\n\n1. One\n2. Two",
            htmlToText(reply),
        )
        assertEquals("Hi Ada", htmlToText("&lt;p&gt;Hi &lt;b&gt;Ada&lt;/b&gt;&lt;/p&gt;"))
        assertEquals("Ada & Co says:\nPrice is <5> 'ok'", htmlToText("<b>Ada &amp; Co</b> says:<br/>Price is &lt;5&gt; &#39;ok&#39;"))
    }

    @Test
    fun plainTextKeepsLineBreaksAndDrawsBoldWithoutAsterisks() {
        assertEquals(
            listOf(FigoTextRun("Line 1\nLine 2, 5 < 6 and "), FigoTextRun("bold", bold = true)),
            htmlToRuns("Line 1\nLine 2, 5 < 6 and **bold**"),
        )
    }

    @Test
    fun urlsBecomeLinksUnsafeHrefsDoNot() {
        assertEquals(
            listOf(FigoTextRun("See "), FigoTextRun("https://figolabs.ai", link = "https://figolabs.ai"), FigoTextRun(".")),
            htmlToRuns("See https://figolabs.ai."),
        )
        assertEquals(listOf(FigoTextRun("x")), htmlToRuns("<a href=\"javascript:alert(1)\">x</a>"))
        assertEquals(listOf(FigoTextRun("<b>typed</b>")), plainToRuns("<b>typed</b>"))
    }
}

class FigoChatListenerTest {
    // dispatch posts to the main looper, so only registration is tested on the JVM
    @Test
    fun addAndRemoveListener() {
        val listener = object : FigoChatListener {}
        assertSame(listener, FigoChat.addListener(listener))
        assertTrue(FigoChat.hasListener(listener))
        FigoChat.removeListener(listener)
        assertFalse(FigoChat.hasListener(listener))
    }
}
