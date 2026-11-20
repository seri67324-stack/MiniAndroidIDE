package com.miniide.app.editor

/**
 * Satır taşıma (Alt+↑/↓), satır çoğaltma (Shift+Alt+↑/↓), yorum satırı aç/kapat (Ctrl+/),
 * girinti artırma/azaltma, satır silme (Ctrl+Shift+K), akıllı Home/End,
 * kelime atlama (Ctrl+←/→) ve sonraki eşleşmeyi seçme (Ctrl+D) için saf (pure) metin dönüşüm fonksiyonları.
 */
object EditorTextTransforms {

    data class TextTransformResult(
        val replaceStart: Int,
        val replaceEnd: Int,
        val replacement: String,
        val newSelStart: Int,
        val newSelEnd: Int
    ) {
        /** Testlerde doğrulama için dönüşümü tam metin üzerinde uygular. */
        fun applyToString(original: CharSequence): String {
            val sb = StringBuilder(original.length - (replaceEnd - replaceStart) + replacement.length)
            sb.append(original, 0, replaceStart)
            sb.append(replacement)
            sb.append(original, replaceEnd, original.length)
            return sb.toString()
        }
    }

    private fun selectedLineRange(
        text: CharSequence,
        lineIndex: LineIndex,
        selStart: Int,
        selEnd: Int
    ): IntRange {
        val len = text.length
        val a = minOf(selStart, selEnd).coerceIn(0, len)
        val b = maxOf(selStart, selEnd).coerceIn(0, len)
        val firstLine = lineIndex.lineAtOffset(a)
        var lastLine = lineIndex.lineAtOffset(b)
        // Çoklu satır seçiminde bitiş tam bir sonraki satırın 0. sütunundaysa o satırı dahil etme
        if (lastLine > firstLine && b == lineIndex.lineStart(lastLine)) {
            lastLine--
        }
        return firstLine..lastLine
    }

    /** Ctrl+/ : Seçili satırlarda yorum satırını açar veya kapatır. */
    fun toggleLineComment(
        text: CharSequence,
        lineIndex: LineIndex,
        selStart: Int,
        selEnd: Int,
        language: String = "java"
    ): TextTransformResult {
        val lines = selectedLineRange(text, lineIndex, selStart, selEnd)
        val blockStart = lineIndex.lineStart(lines.first)
        val blockEnd = lineIndex.lineEnd(lines.last, text.length)
        val rawBlock = text.subSequence(blockStart, blockEnd).toString()

        if (language == "xml") {
            val trimmed = rawBlock.trim()
            return if (trimmed.startsWith("<!--") && trimmed.endsWith("-->")) {
                val openIdx = rawBlock.indexOf("<!--")
                val closeIdx = rawBlock.lastIndexOf("-->")
                val inner = rawBlock.substring(0, openIdx) +
                        rawBlock.substring(openIdx + 4, closeIdx).removePrefix(" ").removeSuffix(" ") +
                        rawBlock.substring(closeIdx + 3)
                TextTransformResult(blockStart, blockEnd, inner, blockStart, blockStart + inner.length)
            } else {
                val commented = "<!-- $rawBlock -->"
                TextTransformResult(blockStart, blockEnd, commented, blockStart, blockStart + commented.length)
            }
        }

        val prefixToken = if (language == "properties") "#" else "//"
        val lineStrings = rawBlock.split('\n')
        val nonBlankLines = lineStrings.filter { it.isNotBlank() }
        val allCommented = nonBlankLines.isNotEmpty() && nonBlankLines.all {
            it.trimStart().startsWith(prefixToken)
        }

        val transformedLines = if (allCommented) {
            lineStrings.map { line ->
                val idx = line.indexOf(prefixToken)
                if (idx >= 0) {
                    val afterToken = idx + prefixToken.length
                    val removeEnd = if (afterToken < line.length && line[afterToken] == ' ') afterToken + 1 else afterToken
                    line.removeRange(idx, removeEnd)
                } else {
                    line
                }
            }
        } else {
            val minIndent = nonBlankLines.minOfOrNull { line ->
                line.indexOfFirst { !it.isWhitespace() }.let { if (it < 0) 0 else it }
            } ?: 0
            lineStrings.map { line ->
                if (line.isBlank()) {
                    line
                } else {
                    val insertAt = minIndent.coerceAtMost(line.length)
                    line.substring(0, insertAt) + "$prefixToken " + line.substring(insertAt)
                }
            }
        }

        val replacement = transformedLines.joinToString("\n")
        val delta = replacement.length - rawBlock.length
        val a = minOf(selStart, selEnd).coerceIn(0, text.length)
        val b = maxOf(selStart, selEnd).coerceIn(0, text.length)
        val newStart = if (a == b) (a + delta).coerceIn(blockStart, blockStart + replacement.length) else blockStart
        val newEnd = if (a == b) newStart else blockStart + replacement.length
        return TextTransformResult(blockStart, blockEnd, replacement, newStart, newEnd)
    }

    // Ctrl+] : Seçili satırların girintisini 4 boşluk artırır.
    fun indentLines(
        text: CharSequence,
        lineIndex: LineIndex,
        selStart: Int,
        selEnd: Int,
        indentUnit: String = "    "
    ): TextTransformResult {
        val lines = selectedLineRange(text, lineIndex, selStart, selEnd)
        val blockStart = lineIndex.lineStart(lines.first)
        val blockEnd = lineIndex.lineEnd(lines.last, text.length)
        val rawBlock = text.subSequence(blockStart, blockEnd).toString()
        val lineStrings = rawBlock.split('\n')
        val transformed = lineStrings.joinToString("\n") { "$indentUnit$it" }
        val a = minOf(selStart, selEnd).coerceIn(0, text.length)
        val b = maxOf(selStart, selEnd).coerceIn(0, text.length)
        return if (a == b) {
            val pos = (a + indentUnit.length).coerceAtMost(blockStart + transformed.length)
            TextTransformResult(blockStart, blockEnd, transformed, pos, pos)
        } else {
            TextTransformResult(blockStart, blockEnd, transformed, blockStart, blockStart + transformed.length)
        }
    }

    // Ctrl+[ veya Shift+Tab : Seçili satırların girintisini 4 boşluğa kadar azaltır.
    fun outdentLines(
        text: CharSequence,
        lineIndex: LineIndex,
        selStart: Int,
        selEnd: Int,
        indentSize: Int = 4
    ): TextTransformResult {
        val lines = selectedLineRange(text, lineIndex, selStart, selEnd)
        val blockStart = lineIndex.lineStart(lines.first)
        val blockEnd = lineIndex.lineEnd(lines.last, text.length)
        val rawBlock = text.subSequence(blockStart, blockEnd).toString()
        val lineStrings = rawBlock.split('\n')

        var firstLineRemoved = 0
        val transformed = lineStrings.mapIndexed { idx, line ->
            var removeCount = 0
            if (line.startsWith("\t")) {
                removeCount = 1
            } else {
                while (removeCount < indentSize && removeCount < line.length && line[removeCount] == ' ') {
                    removeCount++
                }
            }
            if (idx == 0) firstLineRemoved = removeCount
            line.substring(removeCount)
        }.joinToString("\n")

        val a = minOf(selStart, selEnd).coerceIn(0, text.length)
        val b = maxOf(selStart, selEnd).coerceIn(0, text.length)
        return if (a == b) {
            val pos = (a - firstLineRemoved).coerceIn(blockStart, blockStart + transformed.length)
            TextTransformResult(blockStart, blockEnd, transformed, pos, pos)
        } else {
            TextTransformResult(blockStart, blockEnd, transformed, blockStart, blockStart + transformed.length)
        }
    }

    /** Alt+↑ : Seçili satır veya satır bloğunu bir üst satırla yer değiştirir. */
    fun moveLinesUp(
        text: CharSequence,
        lineIndex: LineIndex,
        selStart: Int,
        selEnd: Int
    ): TextTransformResult? {
        val lines = selectedLineRange(text, lineIndex, selStart, selEnd)
        if (lines.first <= 0) return null

        val prevLine = lines.first - 1
        val prevStart = lineIndex.lineStart(prevLine)
        val prevEnd = lineIndex.lineEnd(prevLine, text.length)
        val prevText = text.subSequence(prevStart, prevEnd).toString()

        val curStart = lineIndex.lineStart(lines.first)
        val curEnd = lineIndex.lineEnd(lines.last, text.length)
        val curText = text.subSequence(curStart, curEnd).toString()

        val replacement = "$curText\n$prevText"
        val shift = -(prevText.length + 1)
        val a = minOf(selStart, selEnd).coerceIn(curStart, curEnd)
        val b = maxOf(selStart, selEnd).coerceIn(curStart, curEnd)
        return TextTransformResult(
            replaceStart = prevStart,
            replaceEnd = curEnd,
            replacement = replacement,
            newSelStart = (a + shift).coerceAtLeast(prevStart),
            newSelEnd = (b + shift).coerceAtLeast(prevStart)
        )
    }

    /** Alt+↓ : Seçili satır veya satır bloğunu bir alt satırla yer değiştirir. */
    fun moveLinesDown(
        text: CharSequence,
        lineIndex: LineIndex,
        selStart: Int,
        selEnd: Int
    ): TextTransformResult? {
        val lines = selectedLineRange(text, lineIndex, selStart, selEnd)
        if (lines.last >= lineIndex.lineCount - 1) return null

        val nextLine = lines.last + 1
        val curStart = lineIndex.lineStart(lines.first)
        val curEnd = lineIndex.lineEnd(lines.last, text.length)
        val curText = text.subSequence(curStart, curEnd).toString()

        val nextStart = lineIndex.lineStart(nextLine)
        val nextEnd = lineIndex.lineEnd(nextLine, text.length)
        val nextText = text.subSequence(nextStart, nextEnd).toString()

        val replacement = "$nextText\n$curText"
        val shift = nextText.length + 1
        val a = minOf(selStart, selEnd).coerceIn(curStart, curEnd)
        val b = maxOf(selStart, selEnd).coerceIn(curStart, curEnd)
        return TextTransformResult(
            replaceStart = curStart,
            replaceEnd = nextEnd,
            replacement = replacement,
            newSelStart = a + shift,
            newSelEnd = b + shift
        )
    }

    /** Shift+Alt+↑ / Shift+Alt+↓ : Seçili satır veya satır bloğunu yukarı/aşağı çoğaltır. */
    fun duplicateLines(
        text: CharSequence,
        lineIndex: LineIndex,
        selStart: Int,
        selEnd: Int,
        up: Boolean
    ): TextTransformResult {
        val lines = selectedLineRange(text, lineIndex, selStart, selEnd)
        val curStart = lineIndex.lineStart(lines.first)
        val curEnd = lineIndex.lineEnd(lines.last, text.length)
        val curText = text.subSequence(curStart, curEnd).toString()

        val replacement = "$curText\n$curText"
        val a = minOf(selStart, selEnd).coerceIn(curStart, curEnd)
        val b = maxOf(selStart, selEnd).coerceIn(curStart, curEnd)
        val shift = if (up) 0 else curText.length + 1
        return TextTransformResult(
            replaceStart = curStart,
            replaceEnd = curEnd,
            replacement = replacement,
            newSelStart = a + shift,
            newSelEnd = b + shift
        )
    }

    /** Ctrl+Shift+K : Seçili satır veya satır bloğunu tamamen siler. */
    fun deleteLines(
        text: CharSequence,
        lineIndex: LineIndex,
        selStart: Int,
        selEnd: Int
    ): TextTransformResult {
        val lines = selectedLineRange(text, lineIndex, selStart, selEnd)
        val curStart = lineIndex.lineStart(lines.first)
        val curEnd = lineIndex.lineEnd(lines.last, text.length)

        val delStart: Int
        val delEnd: Int
        if (curEnd < text.length && text[curEnd] == '\n') {
            delStart = curStart
            delEnd = curEnd + 1
        } else if (curStart > 0 && text[curStart - 1] == '\n') {
            delStart = curStart - 1
            delEnd = curEnd
        } else {
            delStart = curStart
            delEnd = curEnd
        }
        val targetCursor = delStart.coerceIn(0, (text.length - (delEnd - delStart)).coerceAtLeast(0))
        return TextTransformResult(delStart, delEnd, "", targetCursor, targetCursor)
    }

    /**
     * Akıllı Home tuşu davranışı:
     * Satırın ilk boşluk olmayan karakterine (girinti sonuna) atlar;
     * imleç zaten oradaysa satırın en başına (0. sütun) geçer.
     */
    fun smartHomeOffset(text: CharSequence, lineIndex: LineIndex, cursorOffset: Int): Int {
        val line = lineIndex.lineAtOffset(cursorOffset)
        val lStart = lineIndex.lineStart(line)
        val lEnd = lineIndex.lineEnd(line, text.length)
        var firstNonWs = lStart
        while (firstNonWs < lEnd && (text[firstNonWs] == ' ' || text[firstNonWs] == '\t')) {
            firstNonWs++
        }
        return if (cursorOffset == firstNonWs) lStart else firstNonWs
    }

    /** End tuşu davranışı: Satır sonuna atlar. */
    fun smartEndOffset(text: CharSequence, lineIndex: LineIndex, cursorOffset: Int): Int {
        val line = lineIndex.lineAtOffset(cursorOffset)
        return lineIndex.lineEnd(line, text.length)
    }

    /** Ctrl+← / Ctrl+→ : Önceki veya sonraki kelime sınırına atlar. */
    fun wordJumpOffset(text: CharSequence, cursorOffset: Int, forward: Boolean): Int {
        val len = text.length
        var i = cursorOffset.coerceIn(0, len)
        if (forward) {
            while (i < len && text[i].isWhitespace()) i++
            if (i < len && isWordChar(text[i])) {
                while (i < len && isWordChar(text[i])) i++
            } else {
                while (i < len && !text[i].isWhitespace() && !isWordChar(text[i])) i++
            }
            return i
        } else {
            while (i > 0 && text[i - 1].isWhitespace()) i--
            if (i > 0 && isWordChar(text[i - 1])) {
                while (i > 0 && isWordChar(text[i - 1])) i--
            } else {
                while (i > 0 && !text[i - 1].isWhitespace() && !isWordChar(text[i - 1])) i--
            }
            return i
        }
    }

    /**
     * Ctrl+D : Seçim yoksa imlecin üzerindeki kelimeyi seçer;
     * seçim varsa aynı metnin bir sonraki eşleşmesini bulup seçer.
     */
    fun selectNextOccurrence(text: CharSequence, selStart: Int, selEnd: Int): Pair<Int, Int>? {
        val len = text.length
        if (len == 0) return null
        val a = minOf(selStart, selEnd).coerceIn(0, len)
        val b = maxOf(selStart, selEnd).coerceIn(0, len)

        if (a == b) {
            var wStart = a
            var wEnd = a
            while (wStart > 0 && isWordChar(text[wStart - 1])) wStart--
            while (wEnd < len && isWordChar(text[wEnd])) wEnd++
            return if (wEnd > wStart) (wStart to wEnd) else null
        }

        val query = text.subSequence(a, b).toString()
        val full = text.toString()
        var nextIdx = full.indexOf(query, b, ignoreCase = false)
        if (nextIdx < 0) {
            nextIdx = full.indexOf(query, 0, ignoreCase = false)
        }
        return if (nextIdx >= 0) (nextIdx to (nextIdx + query.length)) else null
    }

    /**
     * Shift+Ok / Shift+Home/End ile seçim genişletme hesabı.
     * anchorOffset sabit tutulur, imlecin aktif ucu targetOffset konumuna taşınır.
     */
    fun computeSelectionAfterMove(
        anchorOffset: Int,
        targetOffset: Int,
        extendWithShift: Boolean
    ): Pair<Int, Int> {
        return if (extendWithShift) {
            anchorOffset to targetOffset
        } else {
            targetOffset to targetOffset
        }
    }

    private fun isWordChar(c: Char): Boolean = c.isLetterOrDigit() || c == '_' || c == '$'
}
