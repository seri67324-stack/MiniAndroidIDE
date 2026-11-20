package com.miniide.app.editor

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorPerformanceTest {

    @Test
    fun testLineIndexRebuildAndBinarySearch() {
        val text = "package com.test;\n\npublic class Main {\n    int x = 1;\n}"
        val idx = LineIndex()
        idx.rebuild(text)

        assertEquals(5, idx.lineCount)
        assertArrayEquals(intArrayOf(0, 18, 19, 39, 54), idx.toIntArray())

        // 1. satır başı
        val p0 = idx.lineAndColumn(0)
        assertEquals(1, p0.line)
        assertEquals(1, p0.column)

        // 2. satır (boş satır, ofset 18)
        val p2 = idx.lineAndColumn(18)
        assertEquals(2, p2.line)
        assertEquals(1, p2.column)

        // 4. satır "    int x = 1;" içinde 'x' (ofset 39 + 8 = 47)
        val px = idx.lineAndColumn(47)
        assertEquals(4, px.line)
        assertEquals(9, px.column)

        // Son karakter '}' (ofset 54)
        val pEnd = idx.lineAndColumn(54)
        assertEquals(5, pEnd.line)
        assertEquals(1, pEnd.column)
    }

    @Test
    fun testLineIndexIncrementalUpdatesMatchFullRebuild() {
        val sb = StringBuilder("satir1\nsatir2\nsatir3\nsatir4")
        val incremental = LineIndex().apply { rebuild(sb) }
        val reference = LineIndex()

        fun applyEdit(start: Int, end: Int, replacement: String) {
            val beforeLen = end - start
            sb.replace(start, end, replacement)
            incremental.onTextChanged(sb, start, beforeLen, replacement.length)
            reference.rebuild(sb)
            assertArrayEquals(
                "Artımlı güncelleme tam taramayla birebir aynı olmalı (metin: '$sb')",
                reference.toIntArray(),
                incremental.toIntArray()
            )
        }

        // 1. Tek karakter ekleme (satır sayısı değişmez)
        applyEdit(3, 3, "X")
        // 2. Çok satırlı metin ekleme
        applyEdit(7, 7, "A\nB\nC")
        // 3. Satır sonu ('\n') silerek iki satırı birleştirme
        val nlPos = sb.indexOf('\n')
        applyEdit(nlPos, nlPos + 1, "")
        // 4. Birden fazla satırı kapsayan bölgeyi tek satırla değiştirme
        applyEdit(2, sb.length - 2, "TEK_SATIR")
        // 5. Sona yeni satırlar ekleme
        applyEdit(sb.length, sb.length, "\nson1\nson2")
    }

    @Test
    fun testVisibleWindowCalculation() {
        // Normal orta bölge (+/- 50 satır)
        val w1 = LineIndex.computeVisibleWindow(
            firstVisibleLine = 200,
            lastVisibleLine = 230,
            totalLines = 20_000,
            bufferLines = 50
        )
        assertEquals(150..280, w1)

        // Dosya başında negatif indeksleri 0'a kırpmalı
        val wStart = LineIndex.computeVisibleWindow(
            firstVisibleLine = 10,
            lastVisibleLine = 35,
            totalLines = 20_000,
            bufferLines = 50
        )
        assertEquals(0..85, wStart)

        // Dosya sonunda totalLines - 1 sınırına kırpmalı
        val wEnd = LineIndex.computeVisibleWindow(
            firstVisibleLine = 19_970,
            lastVisibleLine = 19_995,
            totalLines = 20_000,
            bufferLines = 50
        )
        assertEquals(19_920..19_999, wEnd)

        // Küçük dosyada tüm aralığı döndürmeli
        val wSmall = LineIndex.computeVisibleWindow(
            firstVisibleLine = 0,
            lastVisibleLine = 5,
            totalLines = 10,
            bufferLines = 50
        )
        assertEquals(0..9, wSmall)
    }

    @Test
    fun testUndoManagerCoalescingAndRoundtrip() {
        val undoMgr = UndoManager(maxSteps = 500, maxMemoryChars = 100_000, coalesceWindowMs = 1000L)
        val buffer = StringBuilder("class A {}")

        fun userType(pos: Int, deletedCount: Int, inserted: String, timeMs: Long) {
            val old = buffer.substring(pos, pos + deletedCount)
            buffer.replace(pos, pos + deletedCount, inserted)
            undoMgr.recordEdit(pos, old, inserted, timeMs)
        }

        // Ardışık "int x" yazımı tek bir Undo adımında birleşmeli
        userType(9, 0, "i", 100L)
        userType(10, 0, "n", 200L)
        userType(11, 0, "t", 300L)
        userType(12, 0, " ", 400L)
        userType(13, 0, "x", 500L)

        assertEquals("class A {int x}", buffer.toString())
        assertEquals(1, undoMgr.undoCount)

        // '\n' yazımı birleştirmeyi bölmeli ve yeni adım açmalı
        userType(14, 0, "\n", 600L)
        assertEquals(2, undoMgr.undoCount)

        // Undo 1: '\n' geri alınır
        val op1 = undoMgr.undo()
        assertNotNull(op1)
        buffer.replace(op1!!.start, op1.end, op1.replacement)
        assertEquals("class A {int x}", buffer.toString())

        // Undo 2: birleştirilmiş "int x" tek seferde geri alınır
        val op2 = undoMgr.undo()
        assertNotNull(op2)
        buffer.replace(op2!!.start, op2.end, op2.replacement)
        assertEquals("class A {}", buffer.toString())
        assertFalse(undoMgr.canUndo)
        assertTrue(undoMgr.canRedo)

        // Redo 1: "int x" geri gelir
        val redo1 = undoMgr.redo()
        assertNotNull(redo1)
        buffer.replace(redo1!!.start, redo1.end, redo1.replacement)
        assertEquals("class A {int x}", buffer.toString())
    }

    @Test
    fun testUndoManagerStepAndMemoryLimits() {
        // 1. En fazla 500 adım sınırı testi
        val stepLimited = UndoManager(maxSteps = 500, maxMemoryChars = 10_000_000, coalesceWindowMs = 0L)
        for (i in 0 until 650) {
            stepLimited.recordEdit(i, "", "a", timestampMs = i * 1000L)
        }
        assertEquals(500, stepLimited.undoCount)

        // 2. Bellek sınırı testi (en fazla 100 karakterlik toplam delta)
        val memLimited = UndoManager(maxSteps = 500, maxMemoryChars = 100, coalesceWindowMs = 0L)
        for (i in 0 until 10) {
            // Her adım 20 karakter -> en fazla 5 adım (100 karakter) bellekte tutulmalı
            memLimited.recordEdit(0, "", "01234567890123456789", timestampMs = i * 1000L)
        }
        assertTrue(memLimited.usedMemoryChars <= 100)
        assertEquals(5, memLimited.undoCount)

        // Tek başına bellek sınırını aşan düzenleme geçmişi sıfırlamalı
        memLimited.recordEdit(0, "", "X".repeat(250), timestampMs = 99_000L)
        assertEquals(0, memLimited.undoCount)
        assertNull(memLimited.undo())
    }

    @Test
    fun test20000LinesWindowedHighlightingAndStateCache() {
        // 20.000 satırlık büyük Java dosyası oluştur (satır 5000..5010 arası çok satırlı blok yorum içerir)
        val sb = StringBuilder(600_000)
        for (i in 0 until 20_000) {
            when (i) {
                5000 -> sb.append("/* Çok satırlı yorum başlangıcı\n")
                in 5001..5009 -> sb.append("   public class YorumIcindeKod { int x = 42; }\n")
                5010 -> sb.append("   yorum sonu */\n")
                else -> sb.append("public int method").append(i).append("() { return ").append(i).append("; }\n")
            }
        }

        val lineIndex = LineIndex().apply { rebuild(sb) }
        assertEquals(20_001, lineIndex.lineCount)

        val stateCache = SyntaxHighlighter.LineStateCache()
        val palette = SyntaxHighlighter.dark

        // Satır 5005 görünür olduğunda (yorum başlangıcı 5000. satırda!), stateCache 5005'in yorum içinde olduğunu bilmeli
        val resComment = SyntaxHighlighter.computeWindowTokens(
            text = sb,
            lineIndex = lineIndex,
            language = "java",
            palette = palette,
            stateCache = stateCache,
            firstVisibleLine = 5003,
            lastVisibleLine = 5007,
            bufferLines = 2
        )
        assertEquals(5001..5009, resComment.lineRange)
        assertEquals(
            SyntaxHighlighter.STATE_BLOCK_COMMENT,
            stateCache.stateAtLineStart(sb, lineIndex, "java", 5005)
        )
        // 5001..5009 aralığının tamamı blok yorum içinde olduğu için yalnızca tek bir yorum span'ı üretilmeli,
        // içindeki "public class" keyword olarak boyanmamalı!
        assertTrue(resComment.tokens.all { it.color == palette.comment })

        // Satır 12.000..12.030 görünür olduğunda yalnızca 11.950..12.080 (+/- 50 satır) taranmalı
        val resMid = SyntaxHighlighter.computeWindowTokens(
            text = sb,
            lineIndex = lineIndex,
            language = "java",
            palette = palette,
            stateCache = stateCache,
            firstVisibleLine = 12_000,
            lastVisibleLine = 12_030,
            bufferLines = 50
        )
        assertEquals(11_950..12_080, resMid.lineRange)
        assertTrue(resMid.tokens.isNotEmpty())
        assertTrue(resMid.tokens.all { it.start >= resMid.startOffset && it.end <= resMid.endOffset })
    }
}
