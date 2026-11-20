package com.miniide.app.editor

/**
 * 20.000+ satırlık büyük dosyalarda O(log N) satır/sütun sorgusu ve
 * artımlı (incremental) metin değişimi güncellemesi sağlayan satır başlangıç indeksi.
 */
class LineIndex {

    data class LineCol(
        val line: Int,              // 1-tabanlı satır numarası
        val column: Int,            // 1-tabanlı sütun numarası
        val lineZeroBased: Int,     // 0-tabanlı satır indeksi
        val columnZeroBased: Int    // 0-tabanlı sütun indeksi
    )

    private var starts = IntArray(128)
    private var count = 1

    init {
        starts[0] = 0
    }

    val lineCount: Int
        get() = count

    /** Tüm metni tek geçişte tarayarak satır başlangıç tablosunu sıfırdan kurar. */
    fun rebuild(text: CharSequence) {
        count = 1
        starts[0] = 0
        val len = text.length
        for (i in 0 until len) {
            if (text[i] == '\n') {
                ensureCapacity(count + 1)
                starts[count++] = i + 1
            }
        }
    }

    /**
     * TextWatcher (onTextChanged) parametreleriyle yalnızca değişen bölgeyi tarar
     * ve sonraki satır başlangıçlarını fark (delta) kadar kaydırır.
     */
    fun onTextChanged(text: CharSequence, start: Int, beforeLength: Int, afterLength: Int) {
        if (start < 0) {
            rebuild(text)
            return
        }
        if (beforeLength == 0 && afterLength == 0) return

        val oldEnd = start + beforeLength
        val safeNewEnd = (start + afterLength).coerceAtMost(text.length)
        val delta = afterLength - beforeLength

        // Silinen [start, oldEnd) aralığındaki '\n' karakterlerinin ürettiği satır başlangıçları
        // (start + 1)..oldEnd aralığındadır.
        val firstRemovedIdx = findFirstGreaterThan(start)
        var firstKeptAfterIdx = firstRemovedIdx
        while (firstKeptAfterIdx < count && starts[firstKeptAfterIdx] <= oldEnd) {
            firstKeptAfterIdx++
        }
        val removedCount = firstKeptAfterIdx - firstRemovedIdx

        // Yeni eklenen [start, safeNewEnd) aralığındaki '\n' sayısını bul
        var addedCount = 0
        for (i in start until safeNewEnd) {
            if (text[i] == '\n') addedCount++
        }

        val newCount = count - removedCount + addedCount
        ensureCapacity(newCount)

        val tailCount = count - firstKeptAfterIdx
        val newTailStart = firstRemovedIdx + addedCount

        if (tailCount > 0) {
            if (addedCount > removedCount) {
                for (i in tailCount - 1 downTo 0) {
                    starts[newTailStart + i] = starts[firstKeptAfterIdx + i] + delta
                }
            } else {
                for (i in 0 until tailCount) {
                    starts[newTailStart + i] = starts[firstKeptAfterIdx + i] + delta
                }
            }
        }

        if (addedCount > 0) {
            var writeIdx = firstRemovedIdx
            for (i in start until safeNewEnd) {
                if (text[i] == '\n') {
                    starts[writeIdx++] = i + 1
                }
            }
        }

        count = newCount
    }

    /** Verilen karakter ofsetinin ait olduğu 0-tabanlı satır indeksini ikili aramayla (O(log N)) bulur. */
    fun lineAtOffset(offset: Int): Int {
        val target = offset.coerceAtLeast(0)
        var low = 0
        var high = count - 1
        while (low <= high) {
            val mid = (low + high) ushr 1
            val midVal = starts[mid]
            if (midVal <= target) {
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return high.coerceAtLeast(0)
    }

    /** Verilen karakter ofseti için 1-tabanlı ve 0-tabanlı (satır, sütun) bilgisini döndürür. */
    fun lineAndColumn(offset: Int): LineCol {
        val safeOffset = offset.coerceAtLeast(0)
        val line0 = lineAtOffset(safeOffset)
        val col0 = (safeOffset - starts[line0]).coerceAtLeast(0)
        return LineCol(
            line = line0 + 1,
            column = col0 + 1,
            lineZeroBased = line0,
            columnZeroBased = col0
        )
    }

    /** 0-tabanlı satırın başlangıç karakter ofsetini döndürür. */
    fun lineStart(lineZeroBased: Int): Int {
        val idx = lineZeroBased.coerceIn(0, count - 1)
        return starts[idx]
    }

    /** 0-tabanlı satırın bitiş karakter ofsetini ('\n' hariç) döndürür. */
    fun lineEnd(lineZeroBased: Int, textLength: Int): Int {
        val idx = lineZeroBased.coerceIn(0, count - 1)
        val rawEnd = if (idx + 1 < count) starts[idx + 1] - 1 else textLength
        return rawEnd.coerceIn(starts[idx], textLength.coerceAtLeast(starts[idx]))
    }

    /** Test ve doğrulama için mevcut satır başlangıçlarını dizi olarak kopyalar. */
    fun toIntArray(): IntArray = starts.copyOf(count)

    private fun findFirstGreaterThan(offset: Int): Int {
        val line = lineAtOffset(offset)
        return (line + 1).coerceAtMost(count)
    }

    private fun ensureCapacity(minCapacity: Int) {
        if (starts.size >= minCapacity) return
        var newCap = starts.size * 2
        while (newCap < minCapacity) newCap *= 2
        starts = starts.copyOf(newCap)
    }

    companion object {
        const val DEFAULT_BUFFER_LINES = 50

        /**
         * Görünür satır aralığı (firstVisibleLine..lastVisibleLine) için
         * +/- bufferLines tamponlu (0..totalLines-1 sınırları içinde) aralığı hesaplar.
         */
        fun computeVisibleWindow(
            firstVisibleLine: Int,
            lastVisibleLine: Int,
            totalLines: Int,
            bufferLines: Int = DEFAULT_BUFFER_LINES
        ): IntRange {
            if (totalLines <= 0) return 0..0
            val maxLine = totalLines - 1
            val safeFirst = firstVisibleLine.coerceIn(0, maxLine)
            val safeLast = maxOf(safeFirst, lastVisibleLine.coerceIn(0, maxLine))
            val pad = bufferLines.coerceAtLeast(0)
            val start = (safeFirst - pad).coerceAtLeast(0)
            val end = (safeLast + pad).coerceAtMost(maxLine)
            return start..end
        }
    }
}
