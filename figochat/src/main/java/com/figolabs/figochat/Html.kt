package com.figolabs.figochat

/** A piece of message text with one style; a message is rendered as a list of these. */
data class FigoTextRun(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    /** Url opened when the run is tapped. */
    val link: String? = null,
)

/**
 * Parses the HTML that assistant replies and live agent messages arrive in into styled runs, so the chat can
 * render it natively (no web view):
 * - `<b>`, `<strong>` and headings are bold, `<i>`/`<em>` italic, `<u>` underlined, `<a href>` a tappable link;
 * - paragraphs, divs, headings and `<br>` break lines; `<ul>` items get `• ` bullets and `<ol>` items `1. `, `2. `…;
 * - other tags (span, font…) keep their text and drop their styling; entities are decoded;
 * - bare urls in the text become tappable too.
 * Plain text (no tags) keeps its own line breaks. HTML that arrives entity-encoded (`&lt;p&gt;…`) is decoded first.
 */
fun htmlToRuns(html: String): List<FigoTextRun> {
    var s = html
    if (!TAG.containsMatchIn(s) && ENCODED_TAG.containsMatchIn(s)) s = decodeEntities(s)
    val runs = if (TAG.containsMatchIn(s)) parseHtml(s) else parsePlain(decodeEntities(s))
    return linkify(merge(runs))
}

/**
 * The same message as plain text: no tags and no markdown, with line breaks, `• ` bullets and `1. ` numbers kept.
 * This is what [FigoChatListener.onMessageReceived] gets.
 */
fun htmlToText(html: String): String = htmlToRuns(html).joinToString("") { it.text }

/** Runs for text the user typed: never parsed as HTML, only urls are made tappable. */
fun plainToRuns(text: String): List<FigoTextRun> = linkify(listOf(FigoTextRun(text)))

// ---------- parsing ----------

private fun ci(pattern: String) = Regex(pattern, RegexOption.IGNORE_CASE)

/** The characters JavaScript's `\s` matches, so whitespace handling is the same as in the other SDKs. */
private const val WS = "\\t\\n\\u000B\\f\\r \\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000\\uFEFF"

private val TAG = ci("""</?[a-z][a-z0-9]*([$WS][^<>]*)?/?>""")
private val ENCODED_TAG = ci("""&lt;/?[a-z][a-z0-9]*([$WS][\s\S]*?)?/?&gt;""")
private val URL = Regex("""https?://[^$WS<>)]*[^$WS<>).,!?;:'"]""")
private val BOLD_MARKDOWN = Regex("""\*\*([^*\n]+)\*\*""")
private val SKIPPED = ci("""<(script|style|head|title)\b[\s\S]*?</\1[$WS]*>""")
private val COMMENT = Regex("""<!--[\s\S]*?-->""")
private val TOKEN = ci("""<(/?)([a-z][a-z0-9]*)\b([^>]*)>|([^<]+)|<""")
private val SPACES = Regex("""[$WS]+""")
private val LEADING_SPACES = Regex("""^ +""")
private val TRAILING_SPACES = Regex(""" +$""")
private val SAFE_HREF = ci("""^(https?:|mailto:|tel:)""")

private fun isJsSpace(c: Char) = c == '\t' || c == '\n' || c == '\u000B' || c == '\u000C' || c == '\r' || c == ' ' ||
    c == ' ' || c == ' ' || c in ' '..' ' || c == ' ' || c == ' ' ||
    c == ' ' || c == ' ' || c == '　' || c == '﻿'

private fun parsePlain(text: String): List<FigoTextRun> {
    // `**bold**` is drawn bold rather than shown with its asterisks
    val runs = mutableListOf<FigoTextRun>()
    val t = text.trim(::isJsSpace)
    var last = 0
    for (m in BOLD_MARKDOWN.findAll(t)) {
        if (m.range.first > last) runs += FigoTextRun(t.substring(last, m.range.first))
        runs += FigoTextRun(m.groupValues[1], bold = true)
        last = m.range.last + 1
    }
    if (last < t.length) runs += FigoTextRun(t.substring(last))
    return runs
}

private class ListState(val ordered: Boolean, var n: Int = 0)

private fun parseHtml(html: String): List<FigoTextRun> {
    val s = html.replace(SKIPPED, "").replace(COMMENT, "")
    val out = RunBuilder()
    var bold = 0
    var italic = 0
    var underline = 0
    val links = ArrayList<String>()
    val lists = ArrayList<ListState>()
    fun style(text: String) = FigoTextRun(
        text = text,
        bold = bold > 0,
        italic = italic > 0,
        underline = underline > 0,
        link = links.lastOrNull()?.takeIf { it.isNotEmpty() },
    )

    for (m in TOKEN.findAll(s)) {
        val tagName = m.groups[2]?.value
        if (tagName == null) {
            out.text(style(decodeEntities((m.groups[4]?.value ?: "<").replace(SPACES, " "))))
            continue
        }
        val closing = m.groupValues[1] == "/"
        val name = tagName.lowercase()
        val delta = if (closing) -1 else 1
        when (name) {
            "br" -> out.lineBreak()
            "b", "strong" -> bold = maxOf(0, bold + delta)
            "i", "em" -> italic = maxOf(0, italic + delta)
            "u", "ins" -> underline = maxOf(0, underline + delta)
            "a" -> if (closing) links.removeLastOrNull() else links.add(safeHref(attr(m.groupValues[3], "href")))
            "h1", "h2", "h3", "h4", "h5", "h6" -> {
                out.block(2)
                bold = maxOf(0, bold + delta)
            }
            "ul", "ol" -> {
                out.block(if (lists.isNotEmpty()) 1 else 2)
                if (closing) lists.removeLastOrNull() else lists.add(ListState(ordered = name == "ol"))
            }
            "li" -> if (!closing) {
                out.block(1)
                val list = lists.lastOrNull()
                val indent = "    ".repeat(maxOf(0, lists.size - 1))
                out.raw(indent + if (list?.ordered == true) "${++list.n}. " else "• ")
            }
            "p", "blockquote", "table", "pre" -> out.block(2)
            "div", "tr", "section", "article", "header", "footer" -> out.block(1)
            else -> Unit // span, font, img… keep the text, drop the tag
        }
    }
    return out.finish()
}

/** Collects runs while collapsing whitespace the way a browser does. */
private class RunBuilder {
    private val runs = ArrayList<FigoTextRun>()
    private var pendingBreaks = 0

    fun block(lines: Int) {
        if (runs.isNotEmpty()) pendingBreaks = maxOf(pendingBreaks, lines)
    }

    fun lineBreak() {
        if (runs.isNotEmpty()) pendingBreaks = minOf(pendingBreaks + 1, 2)
    }

    fun text(run: FigoTextRun) {
        var t = run.text
        val last = runs.lastOrNull()?.text ?: ""
        if (pendingBreaks > 0 || runs.isEmpty() || last.endsWith(" ") || last.endsWith("\n")) t = t.replace(LEADING_SPACES, "")
        if (t.isEmpty()) return
        flush()
        runs += run.copy(text = t)
    }

    /** Text that is written as is, such as a list bullet. */
    fun raw(t: String) {
        flush()
        runs += FigoTextRun(t)
    }

    fun finish(): List<FigoTextRun> {
        trimTrailingSpaces()
        return runs
    }

    private fun flush() {
        if (pendingBreaks > 0 && runs.isNotEmpty()) {
            trimTrailingSpaces()
            runs += FigoTextRun("\n".repeat(pendingBreaks))
        }
        pendingBreaks = 0
    }

    private fun trimTrailingSpaces() {
        while (runs.isNotEmpty()) {
            val last = runs.last()
            val trimmed = last.text.replace(TRAILING_SPACES, "")
            if (trimmed.isNotEmpty()) {
                runs[runs.size - 1] = last.copy(text = trimmed)
                return
            }
            runs.removeAt(runs.size - 1)
        }
    }
}

private fun attr(attrs: String, name: String): String {
    val m = ci("""\b$name[$WS]*=[$WS]*(?:"([^"]*)"|'([^']*)'|([^$WS>]+))""").find(attrs)
    val value = m?.groups?.let { it[1] ?: it[2] ?: it[3] }?.value ?: ""
    return decodeEntities(value).trim(::isJsSpace)
}

/** Only web, mail and phone links are tappable. */
private fun safeHref(href: String): String = if (SAFE_HREF.containsMatchIn(href)) href else ""

private fun sameStyle(a: FigoTextRun, b: FigoTextRun) =
    a.bold == b.bold && a.italic == b.italic && a.underline == b.underline && a.link == b.link

private fun merge(runs: List<FigoTextRun>): List<FigoTextRun> {
    val merged = ArrayList<FigoTextRun>()
    for (r in runs) {
        val last = merged.lastOrNull()
        if (last != null && sameStyle(last, r)) merged[merged.size - 1] = last.copy(text = last.text + r.text)
        else merged += r
    }
    return merged
}

private fun linkify(runs: List<FigoTextRun>): List<FigoTextRun> {
    val result = ArrayList<FigoTextRun>()
    for (r in runs) {
        if (r.link != null) {
            result += r
            continue
        }
        var last = 0
        for (m in URL.findAll(r.text)) {
            if (m.range.first > last) result += r.copy(text = r.text.substring(last, m.range.first))
            result += r.copy(text = m.value, link = m.value)
            last = m.range.last + 1
        }
        if (last < r.text.length) result += if (last > 0) r.copy(text = r.text.substring(last)) else r
    }
    return result
}

// ---------- entities ----------

private val NAMED = mapOf(
    "quot" to "\"", "apos" to "'", "lt" to "<", "gt" to ">", "nbsp" to " ",
    "rsquo" to "’", "lsquo" to "‘", "rdquo" to "”", "ldquo" to "“",
    "hellip" to "…", "mdash" to "—", "ndash" to "–", "bull" to "•",
    "copy" to "©", "reg" to "®", "trade" to "™", "naira" to "₦",
)

private val HEX_ENTITY = ci("""&#x([0-9a-f]+);""")
private val DEC_ENTITY = Regex("""&#(\d+);""")
private val NAMED_ENTITY = ci("""&([a-z]+);""")
private val AMP = ci("&amp;")

private fun decodeEntities(s: String): String = s
    .replace(HEX_ENTITY) { fromCode(it.groupValues[1].toIntOrNull(16), it.value) }
    .replace(DEC_ENTITY) { fromCode(it.groupValues[1].toIntOrNull(), it.value) }
    .replace(NAMED_ENTITY) { NAMED[it.groupValues[1].lowercase()] ?: it.value }
    .replace(AMP, "&")

private fun fromCode(code: Int?, fallback: String): String =
    if (code == null || !Character.isValidCodePoint(code)) fallback else String(Character.toChars(code))
