package com.miniide.app.panel

import com.miniide.app.builder.BuildError
import com.miniide.app.editor.CodeIntelligenceEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BottomPanelTest {

    @Test
    fun testLogcatParserThreadtimeAndBriefFormats() {
        assertNull(BottomPanelEngine.LogcatParser.parseLine("--------- beginning of main"))

        val tt = BottomPanelEngine.LogcatParser.parseLine(
            "11-20 06:55:12.345  1234  5678 E AndroidRuntime : FATAL EXCEPTION: main"
        )
        assertNotNull(tt)
        assertEquals("11-20 06:55:12.345", tt!!.timestamp)
        assertEquals(1234, tt.pid)
        assertEquals(5678, tt.tid)
        assertEquals(BottomPanelEngine.LogLevel.ERROR, tt.level)
        assertEquals("AndroidRuntime", tt.tag)
        assertEquals("FATAL EXCEPTION: main", tt.message)

        val brief = BottomPanelEngine.LogcatParser.parseLine(
            "W/MiniIDE( 4321): Düşük bellek uyarısı"
        )
        assertNotNull(brief)
        assertEquals(BottomPanelEngine.LogLevel.WARN, brief!!.level)
        assertEquals("MiniIDE", brief.tag)
        assertEquals(4321, brief.pid)
        assertEquals("Düşük bellek uyarısı", brief.message)
    }

    @Test
    fun testLogcatFilterByLevelTextAndTag() {
        val rawLines = listOf(
            "11-20 07:00:01.000  100  100 D MainActivity : Başlatıldı",
            "11-20 07:00:02.000  100  100 I CompileService : AAPT2 tamamlandı",
            "11-20 07:00:03.000  100  100 W MainActivity : Yavaş işlem",
            "11-20 07:00:04.000  100  100 E CompileService : NullPointerException"
        )
        val parsed = rawLines.mapNotNull { BottomPanelEngine.LogcatParser.parseLine(it) }

        // Sadece WARN ve üzeri -> 2 kayıt
        val warnPlus = BottomPanelEngine.LogcatParser.filterLogs(parsed, minLevel = BottomPanelEngine.LogLevel.WARN)
        assertEquals(2, warnPlus.size)

        // Etiket/paket filtresi "CompileService" + metin "NullPointer" -> 1 kayıt
        val specific = BottomPanelEngine.LogcatParser.filterLogs(
            parsed,
            minLevel = BottomPanelEngine.LogLevel.INFO,
            query = "nullpointer",
            tagOrPackageFilter = "CompileService"
        )
        assertEquals(1, specific.size)
        assertEquals(BottomPanelEngine.LogLevel.ERROR, specific[0].level)
    }

    @Test
    fun testProblemsGroupingAndSeverityFilter() {
        val buildErrors = listOf(
            BuildError("ECJ", true, "src/A.java", 10, "err1", "Hata 1"),
            BuildError("ECJ", false, "src/A.java", 20, "warn1", "Uyarı 1"),
            BuildError("AAPT2", true, "res/layout/main.xml", 5, "err2", "Kaynak yok")
        )
        val liveDiags = listOf(
            CodeIntelligenceEngine.LineDiagnostic(10, true, "Hata 1", "err1"), // tekrarlı, elenmeli
            CodeIntelligenceEngine.LineDiagnostic(35, true, "Noktalı virgül eksik", "Syntax error")
        )

        val allGrouped = BottomPanelEngine.ProblemsManager.mergeAndGroup(
            buildErrors, liveDiags, "src/A.java", BottomPanelEngine.ProblemSeverityFilter.ALL
        )
        assertEquals(2, allGrouped.size)
        assertEquals(3, allGrouped["src/A.java"]!!.size) // 2 derleme + 1 yeni anlık tanı

        val onlyErrors = BottomPanelEngine.ProblemsManager.mergeAndGroup(
            buildErrors, liveDiags, "src/A.java", BottomPanelEngine.ProblemSeverityFilter.ERRORS_ONLY
        )
        assertTrue(onlyErrors.values.flatten().all { it.isError })
        assertEquals(2, onlyErrors["src/A.java"]!!.size)
    }

    @Test
    fun testTerminalCommandHistoryNavigation() {
        val session = BottomPanelEngine.TerminalSession(1, File("."))
        session.recordHistory("ls -la")
        session.recordHistory("pwd")
        session.recordHistory("echo merhaba")

        assertEquals("echo merhaba", session.historyUp())
        assertEquals("pwd", session.historyUp())
        assertEquals("ls -la", session.historyUp())
        assertEquals("pwd", session.historyDown())
        assertEquals("echo merhaba", session.historyDown())
        assertEquals("", session.historyDown())
    }
}
