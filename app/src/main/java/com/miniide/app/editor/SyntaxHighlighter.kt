package com.miniide.app.editor

import android.text.Editable
import android.text.Spanned
import android.text.style.ForegroundColorSpan

/**
 * Görünür satır penceresi (+/- 50 satır) üzerinde çalışan ve satır başına çok satırlı
 * yorum/string durumunu (LineStateCache) önbelleğe alan yüksek performanslı sözdizimi renklendirici.
 */
object SyntaxHighlighter {

    class HlSpan(color: Int) : ForegroundColorSpan(color)

    const val STATE_NORMAL = 0
    const val STATE_BLOCK_COMMENT = 1
    const val STATE_MULTILINE_STRING = 2

    data class TokenSpan(val start: Int, val end: Int, val color: Int)

    data class WindowHighlightResult(
        val lineRange: IntRange,
        val startOffset: Int,
        val endOffset: Int,
        val tokens: List<TokenSpan>
    )

    /**
     * Satır sonlarındaki çok satırlı yorum (/* ... */, <!-- ... -->) ve çok satırlı
     * string ("""...""", XML "...") durumunu satır indeksine göre önbellekte tutar.
     */
    class LineStateCache {
        private var endStates = IntArray(256)
        var validUpToLine: Int = -1
            private set

        fun reset() {
            validUpToLine = -1
        }

        /** Metin belirli bir satırdan itibaren değiştiğinde sonraki satırların önbelleğini geçersiz kılar. */
        fun invalidateFromLine(lineZeroBased: Int) {
            val target = (lineZeroBased - 1).coerceAtLeast(-1)
            if (target < validUpToLine) {
                validUpToLine = target
            }
        }

        fun stateAtLineStart(
            text: CharSequence,
            lineIndex: LineIndex,
            language: String,
            lineZeroBased: Int
        ): Int {
            if (lineZeroBased <= 0) return STATE_NORMAL
            val prevLine = (lineZeroBased - 1).coerceAtMost(lineIndex.lineCount - 1)
            ensureComputedUpTo(text, lineIndex, language, prevLine)
            return endStates[prevLine]
        }

        fun stateAtLineEnd(
            text: CharSequence,
            lineIndex: LineIndex,
            language: String,
            lineZeroBased: Int
        ): Int {
            val clamped = lineZeroBased.coerceIn(0, lineIndex.lineCount - 1)
            ensureComputedUpTo(text, lineIndex, language, clamped)
            return endStates[clamped]
        }

        fun ensureComputedUpTo(
            text: CharSequence,
            lineIndex: LineIndex,
            language: String,
            targetLineZeroBased: Int
        ) {
            if (language.isEmpty()) return
            val maxLine = (lineIndex.lineCount - 1).coerceAtLeast(0)
            val target = targetLineZeroBased.coerceIn(0, maxLine)
            if (validUpToLine >= target) return

            ensureCapacity(maxLine + 1)
            val textLen = text.length
            var state = if (validUpToLine >= 0) endStates[validUpToLine] else STATE_NORMAL
            for (line in (validUpToLine + 1)..target) {
                val lStart = lineIndex.lineStart(line)
                val lEnd = lineIndex.lineEnd(line, textLen)
                state = scanLineState(text, lStart, lEnd, language, state)
                endStates[line] = state
            }
            validUpToLine = target
        }

        private fun ensureCapacity(minCap: Int) {
            if (endStates.size >= minCap) return
            var cap = endStates.size * 2
            while (cap < minCap) cap *= 2
            endStates = endStates.copyOf(cap)
        }
    }

    private val javaKeywords = Regex(
        "\\b(abstract|assert|boolean|break|byte|case|catch|char|class|const|continue|default|do|double|else|enum|" +
        "extends|final|finally|float|for|goto|if|implements|import|instanceof|int|interface|long|native|new|" +
        "package|private|protected|public|return|short|static|strictfp|super|switch|synchronized|this|throw|" +
        "throws|transient|try|void|volatile|while|true|false|null|var|val|fun|object|companion|override|when|is|in)\\b"
    )
    private val typeIdentifiers = Regex("\\b[A-Z][A-Za-z0-9_]*\\b")
    private val number = Regex("\\b0[xX][0-9a-fA-F]+\\b|\\b\\d+(\\.\\d+)?[fFlLdD]?\\b")
    private val annotation = Regex("@[A-Za-z_][A-Za-z0-9_]*")

    private val xmlTag = Regex("</?[\\w:.\\-]+|/?>")
    private val xmlAttr = Regex("[\\w:.\\-]+(?=\\s*=)")

    private val propKey = Regex("^[\\w.\\-]+(?=\\s*[=:])", RegexOption.MULTILINE)
    private val propComment = Regex("^[#!][^\\n]*", RegexOption.MULTILINE)

    class Palette(
        val keyword: Int,
        val type: Int,
        val string: Int,
        val comment: Int,
        val number: Int,
        val annotation: Int,
        val tag: Int,
        val attr: Int
    )

    val light = Palette(
        keyword = 0xFF1D4ED8.toInt(),
        type = 0xFF0F766E.toInt(),
        string = 0xFF15803D.toInt(),
        comment = 0xFF64748B.toInt(),
        number = 0xFFC2410C.toInt(),
        annotation = 0xFFB45309.toInt(),
        tag = 0xFF7E22CE.toInt(),
        attr = 0xFF0369A1.toInt()
    )

    val dark = Palette(
        keyword = 0xFF7AA2F7.toInt(),
        type = 0xFF2AC3DE.toInt(),
        string = 0xFF9ECE6A.toInt(),
        comment = 0xFF6B7280.toInt(),
        number = 0xFFFF9E64.toInt(),
        annotation = 0xFFE0AF68.toInt(),
        tag = 0xFFBB9AF7.toInt(),
        attr = 0xFF7DCFFF.toInt()
    )

    fun languageFor(fileName: String): String = when (fileName.substringAfterLast('.', "").lowercase()) {
        "java", "kt", "kts", "gradle" -> "java"
        "xml", "html", "svg" -> "xml"
        "json" -> "json"
        "properties", "pro", "cfg", "ini" -> "properties"
        else -> ""
    }

    /**
     * Yalnızca görünen satırlar +/- bufferLines aralığını renklendirir.
     * Pencere dışında kalan eski HlSpan'ları temizleyerek 20.000 satırlık dosyalarda
     * SpannableStringBuilder içindeki span sayısını sabit (~150 satır) tutar.
     */
    fun highlightWindow(
        editable: Editable?,
        lineIndex: LineIndex,
        language: String,
        palette: Palette,
        stateCache: LineStateCache,
        firstVisibleLine: Int,
        lastVisibleLine: Int,
        bufferLines: Int = LineIndex.DEFAULT_BUFFER_LINES
    ): WindowHighlightResult? {
        if (editable == null) return null
        if (language.isEmpty() || editable.isEmpty()) {
            clearAllSpans(editable)
            return null
        }

        val result = computeWindowTokens(
            text = editable,
            lineIndex = lineIndex,
            language = language,
            palette = palette,
            stateCache = stateCache,
            firstVisibleLine = firstVisibleLine,
            lastVisibleLine = lastVisibleLine,
            bufferLines = bufferLines
        )

        // Mevcut tüm HlSpan'ları temizle ve yalnızca aktif pencerenin span'larını uygula
        clearAllSpans(editable)
        val len = editable.length
        for (token in result.tokens) {
            val s = token.start.coerceIn(0, len)
            val e = token.end.coerceIn(s, len)
            if (e > s) {
                editable.setSpan(HlSpan(token.color), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        return result
    }

    /** Geriye dönük uyumluluk: tüm görünür metni tek pencere olarak renklendirir. */
    fun highlight(editable: Editable?, language: String, p: Palette) {
        if (editable == null) return
        val idx = LineIndex().apply { rebuild(editable) }
        val cache = LineStateCache()
        highlightWindow(editable, idx, language, p, cache, 0, idx.lineCount - 1, LineIndex.DEFAULT_BUFFER_LINES)
    }

    private fun clearAllSpans(editable: Editable) {
        val existing = editable.getSpans(0, editable.length, HlSpan::class.java)
        for (s in existing) {
            editable.removeSpan(s)
        }
    }

    /**
     * Saf Kotlin (JVM birim testlerinde Android bağımlılığı olmadan çalışabilen)
     * pencere bazlı token hesaplayıcı.
     */
    fun computeWindowTokens(
        text: CharSequence,
        lineIndex: LineIndex,
        language: String,
        palette: Palette,
        stateCache: LineStateCache,
        firstVisibleLine: Int,
        lastVisibleLine: Int,
        bufferLines: Int = LineIndex.DEFAULT_BUFFER_LINES
    ): WindowHighlightResult {
        val windowLines = LineIndex.computeVisibleWindow(
            firstVisibleLine = firstVisibleLine,
            lastVisibleLine = lastVisibleLine,
            totalLines = lineIndex.lineCount,
            bufferLines = bufferLines
        )
        val startOffset = lineIndex.lineStart(windowLines.first)
        val endOffset = lineIndex.lineEnd(windowLines.last, text.length)
        if (language.isEmpty() || endOffset <= startOffset) {
            return WindowHighlightResult(windowLines, startOffset, endOffset, emptyList())
        }

        stateCache.ensureComputedUpTo(text, lineIndex, language, windowLines.last)
        val startState = stateCache.stateAtLineStart(text, lineIndex, language, windowLines.first)
        val slice = text.subSequence(startOffset, endOffset).toString()

        val tokens = when (language) {
            "java" -> tokenizeJavaWindow(slice, startOffset, startState, palette)
            "xml" -> tokenizeXmlWindow(slice, startOffset, startState, palette)
            "json" -> tokenizeJavaWindow(slice, startOffset, startState, palette, keywordsEnabled = false)
            "properties" -> tokenizePropertiesWindow(slice, startOffset, palette)
            else -> emptyList()
        }
        return WindowHighlightResult(windowLines, startOffset, endOffset, tokens)
    }

    private fun tokenizeJavaWindow(
        slice: String,
        baseOffset: Int,
        startState: Int,
        p: Palette,
        keywordsEnabled: Boolean = true
    ): List<TokenSpan> {
        // 1. Önce yorum ve string bölgelerini durum makinesiyle (startState'den başlayarak) çıkar
        val overlaySpans = scanJavaCommentsAndStrings(slice, baseOffset, startState, p)

        // 2. Yorum/string dışındaki bölgelerde anahtar kelime, tür, sayı ve anotasyonları bul
        val codeTokens = ArrayList<TokenSpan>(overlaySpans.size + 64)
        if (keywordsEnabled) {
            collectNonOverlapping(slice, baseOffset, typeIdentifiers, p.type, overlaySpans, codeTokens)
            collectNonOverlapping(slice, baseOffset, javaKeywords, p.keyword, overlaySpans, codeTokens)
            collectNonOverlapping(slice, baseOffset, annotation, p.annotation, overlaySpans, codeTokens)
        }
        collectNonOverlapping(slice, baseOffset, number, p.number, overlaySpans, codeTokens)

        codeTokens.addAll(overlaySpans)
        return codeTokens
    }

    private fun tokenizeXmlWindow(
        slice: String,
        baseOffset: Int,
        startState: Int,
        p: Palette
    ): List<TokenSpan> {
        val overlaySpans = scanXmlCommentsAndStrings(slice, baseOffset, startState, p)
        val tokens = ArrayList<TokenSpan>(overlaySpans.size + 64)
        collectNonOverlapping(slice, baseOffset, xmlTag, p.tag, overlaySpans, tokens)
        collectNonOverlapping(slice, baseOffset, xmlAttr, p.attr, overlaySpans, tokens)
        tokens.addAll(overlaySpans)
        return tokens
    }

    private fun tokenizePropertiesWindow(
        slice: String,
        baseOffset: Int,
        p: Palette
    ): List<TokenSpan> {
        val tokens = ArrayList<TokenSpan>()
        for (m in propKey.findAll(slice)) {
            tokens.add(TokenSpan(baseOffset + m.range.first, baseOffset + m.range.last + 1, p.attr))
        }
        for (m in propComment.findAll(slice)) {
            tokens.add(TokenSpan(baseOffset + m.range.first, baseOffset + m.range.last + 1, p.comment))
        }
        return tokens
    }

    private fun collectNonOverlapping(
        slice: String,
        baseOffset: Int,
        regex: Regex,
        color: Int,
        protectedSpans: List<TokenSpan>,
        out: MutableList<TokenSpan>
    ) {
        for (m in regex.findAll(slice)) {
            val s = baseOffset + m.range.first
            val e = baseOffset + m.range.last + 1
            if (!overlapsAny(s, e, protectedSpans)) {
                out.add(TokenSpan(s, e, color))
            }
        }
    }

    private fun overlapsAny(start: Int, end: Int, spans: List<TokenSpan>): Boolean {
        var low = 0
        var high = spans.size - 1
        while (low <= high) {
            val mid = (low + high) ushr 1
            val sp = spans[mid]
            if (sp.end <= start) {
                low = mid + 1
            } else if (sp.start >= end) {
                high = mid - 1
            } else {
                return true
            }
        }
        return false
    }

    private fun scanJavaCommentsAndStrings(
        slice: String,
        baseOffset: Int,
        startState: Int,
        p: Palette
    ): List<TokenSpan> {
        val list = ArrayList<TokenSpan>()
        val len = slice.length
        var i = 0
        var state = startState
        var regionStart = 0

        while (i < len) {
            when (state) {
                STATE_BLOCK_COMMENT -> {
                    val closeIdx = slice.indexOf("*/", i)
                    if (closeIdx < 0) {
                        list.add(TokenSpan(baseOffset + regionStart, baseOffset + len, p.comment))
                        return list
                    }
                    val end = closeIdx + 2
                    list.add(TokenSpan(baseOffset + regionStart, baseOffset + end, p.comment))
                    i = end
                    state = STATE_NORMAL
                }
                STATE_MULTILINE_STRING -> {
                    val closeIdx = slice.indexOf("\"\"\"", i)
                    if (closeIdx < 0) {
                        list.add(TokenSpan(baseOffset + regionStart, baseOffset + len, p.string))
                        return list
                    }
                    val end = closeIdx + 3
                    list.add(TokenSpan(baseOffset + regionStart, baseOffset + end, p.string))
                    i = end
                    state = STATE_NORMAL
                }
                else -> {
                    val ch = slice[i]
                    if (ch == '/' && i + 1 < len) {
                        val next = slice[i + 1]
                        if (next == '/') {
                            val nl = slice.indexOf('\n', i + 2)
                            val end = if (nl < 0) len else nl
                            list.add(TokenSpan(baseOffset + i, baseOffset + end, p.comment))
                            i = end
                            continue
                        } else if (next == '*') {
                            regionStart = i
                            i += 2
                            state = STATE_BLOCK_COMMENT
                            continue
                        }
                    } else if (ch == '"' && i + 2 < len && slice[i + 1] == '"' && slice[i + 2] == '"') {
                        regionStart = i
                        i += 3
                        state = STATE_MULTILINE_STRING
                        continue
                    } else if (ch == '"' || ch == '\'') {
                        val quote = ch
                        val strStart = i
                        i++
                        while (i < len) {
                            val c = slice[i]
                            if (c == '\\') {
                                i += 2
                                continue
                            }
                            if (c == quote || c == '\n') {
                                if (c == quote) i++
                                break
                            }
                            i++
                        }
                        list.add(TokenSpan(baseOffset + strStart, baseOffset + i.coerceAtMost(len), p.string))
                        continue
                    }
                    i++
                }
            }
        }

        if (state == STATE_BLOCK_COMMENT) {
            list.add(TokenSpan(baseOffset + regionStart, baseOffset + len, p.comment))
        } else if (state == STATE_MULTILINE_STRING) {
            list.add(TokenSpan(baseOffset + regionStart, baseOffset + len, p.string))
        }
        return list
    }

    private fun scanXmlCommentsAndStrings(
        slice: String,
        baseOffset: Int,
        startState: Int,
        p: Palette
    ): List<TokenSpan> {
        val list = ArrayList<TokenSpan>()
        val len = slice.length
        var i = 0
        var state = startState
        var regionStart = 0

        while (i < len) {
            when (state) {
                STATE_BLOCK_COMMENT -> {
                    val closeIdx = slice.indexOf("-->", i)
                    if (closeIdx < 0) {
                        list.add(TokenSpan(baseOffset + regionStart, baseOffset + len, p.comment))
                        return list
                    }
                    val end = closeIdx + 3
                    list.add(TokenSpan(baseOffset + regionStart, baseOffset + end, p.comment))
                    i = end
                    state = STATE_NORMAL
                }
                STATE_MULTILINE_STRING -> {
                    val closeIdx = slice.indexOf('"', i)
                    if (closeIdx < 0) {
                        list.add(TokenSpan(baseOffset + regionStart, baseOffset + len, p.string))
                        return list
                    }
                    val end = closeIdx + 1
                    list.add(TokenSpan(baseOffset + regionStart, baseOffset + end, p.string))
                    i = end
                    state = STATE_NORMAL
                }
                else -> {
                    if (slice.startsWith("<!--", i)) {
                        regionStart = i
                        i += 4
                        state = STATE_BLOCK_COMMENT
                        continue
                    } else if (slice[i] == '"') {
                        regionStart = i
                        i++
                        state = STATE_MULTILINE_STRING
                        continue
                    }
                    i++
                }
            }
        }

        if (state == STATE_BLOCK_COMMENT) {
            list.add(TokenSpan(baseOffset + regionStart, baseOffset + len, p.comment))
        } else if (state == STATE_MULTILINE_STRING) {
            list.add(TokenSpan(baseOffset + regionStart, baseOffset + len, p.string))
        }
        return list
    }

    internal fun scanLineState(
        text: CharSequence,
        startOffset: Int,
        endOffset: Int,
        language: String,
        startState: Int
    ): Int {
        return when (language) {
            "java", "json" -> scanJavaLineState(text, startOffset, endOffset, startState)
            "xml" -> scanXmlLineState(text, startOffset, endOffset, startState)
            else -> STATE_NORMAL
        }
    }

    private fun scanJavaLineState(
        text: CharSequence,
        startOffset: Int,
        endOffset: Int,
        startState: Int
    ): Int {
        var state = startState
        var i = startOffset
        while (i < endOffset) {
            when (state) {
                STATE_BLOCK_COMMENT -> {
                    if (i + 1 < endOffset && text[i] == '*' && text[i + 1] == '/') {
                        state = STATE_NORMAL
                        i += 2
                        continue
                    }
                    i++
                }
                STATE_MULTILINE_STRING -> {
                    if (text[i] == '\\' && i + 1 < endOffset) {
                        i += 2
                        continue
                    }
                    if (i + 2 < endOffset && text[i] == '"' && text[i + 1] == '"' && text[i + 2] == '"') {
                        state = STATE_NORMAL
                        i += 3
                        continue
                    }
                    i++
                }
                else -> {
                    val ch = text[i]
                    if (ch == '/' && i + 1 < endOffset) {
                        val next = text[i + 1]
                        if (next == '/') return STATE_NORMAL
                        if (next == '*') {
                            state = STATE_BLOCK_COMMENT
                            i += 2
                            continue
                        }
                    } else if (ch == '"' && i + 2 < endOffset && text[i + 1] == '"' && text[i + 2] == '"') {
                        state = STATE_MULTILINE_STRING
                        i += 3
                        continue
                    } else if (ch == '"' || ch == '\'') {
                        val q = ch
                        i++
                        while (i < endOffset) {
                            if (text[i] == '\\') {
                                i += 2
                                continue
                            }
                            if (text[i] == q) {
                                i++
                                break
                            }
                            i++
                        }
                        continue
                    }
                    i++
                }
            }
        }
        return state
    }

    private fun scanXmlLineState(
        text: CharSequence,
        startOffset: Int,
        endOffset: Int,
        startState: Int
    ): Int {
        var state = startState
        var i = startOffset
        while (i < endOffset) {
            when (state) {
                STATE_BLOCK_COMMENT -> {
                    if (i + 2 < endOffset && text[i] == '-' && text[i + 1] == '-' && text[i + 2] == '>') {
                        state = STATE_NORMAL
                        i += 3
                        continue
                    }
                    i++
                }
                STATE_MULTILINE_STRING -> {
                    if (text[i] == '"') {
                        state = STATE_NORMAL
                    }
                    i++
                }
                else -> {
                    if (i + 3 < endOffset &&
                        text[i] == '<' && text[i + 1] == '!' && text[i + 2] == '-' && text[i + 3] == '-'
                    ) {
                        state = STATE_BLOCK_COMMENT
                        i += 4
                        continue
                    } else if (text[i] == '"') {
                        state = STATE_MULTILINE_STRING
                        i++
                        continue
                    }
                    i++
                }
            }
        }
        return state
    }
}
