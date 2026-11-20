package com.miniide.app.panel

import com.miniide.app.builder.BuildError
import com.miniide.app.editor.CodeIntelligenceEngine
import java.io.File

/**
 * Prompt 6 Alt Panel Motoru (Problemler, Çıktı, Terminal, Logcat):
 * - Logcat satır ayrıştırıcı (threadtime & brief) ve seviye/metin filtresi
 * - Problemler gruplama (dosyaya göre) ve önem filtresi
 * - Çoklu oturumlu /system/bin/sh Terminal ve komut geçmişi (↑/↓)
 */
object BottomPanelEngine {

    const val TERMINAL_SANDBOX_NOTICE_TR =
        "ℹ Sandbox Kısıtı: Terminal uygulama kullanıcısı (UID) yetkileriyle /system/bin/sh üzerinde çalışır. " +
        "Yalnızca proje ve uygulama dizinlerinde yazma yetkisi vardır; root (su) ve harici Linux paketleri bulunmaz."

    const val LOGCAT_SANDBOX_NOTICE_TR =
        "ℹ Android 4.1+ Güvenlik Sınırı: Google, READ_LOGS iznini sistem/ADB ile sınırlandırdığı için " +
        "bu ekran yalnızca aynı UID'ye sahip kendi sürecimizin loglarını okuyabilir. Kurduğunuz harici APK'nın " +
        "loglarını görmek için uygulamaya dosya/yayın günlükleyici ekleyebilir veya bilgisayardan 'adb logcat' kullanabilirsiniz."

    enum class LogLevel(val code: Char, val priority: Int, val labelTr: String) {
        VERBOSE('V', 1, "Tümü (V)"),
        DEBUG('D', 2, "Debug (D)"),
        INFO('I', 3, "Bilgi (I)"),
        WARN('W', 4, "Uyarı (W)"),
        ERROR('E', 5, "Hata (E)"),
        FATAL('F', 6, "Kritik (F)");

        companion object {
            fun fromChar(c: Char): LogLevel = when (c.uppercaseChar()) {
                'V' -> VERBOSE
                'D' -> DEBUG
                'I' -> INFO
                'W' -> WARN
                'E' -> ERROR
                'F', 'A' -> FATAL
                else -> INFO
            }
        }
    }

    data class LogEntry(
        val timestamp: String,
        val pid: Int,
        val tid: Int,
        val level: LogLevel,
        val tag: String,
        val message: String,
        val raw: String
    )

    enum class ProblemSeverityFilter { ALL, ERRORS_ONLY, WARNINGS_ONLY }

    data class ProblemItem(
        val source: String,
        val file: String,
        val line: Int,
        val isError: Boolean,
        val messageTr: String,
        val rawMessage: String
    )

    object LogcatParser {
        // Örnek threadtime: "11-20 06:55:12.345  1234  5678 E MyTag   : NullPointerException"
        private val threadtimeRegex = Regex(
            """^(\d{2}-\d{2}\s+\d{2}:\d{2}:\d{2}\.\d{3})\s+(\d+)\s+(\d+)\s+([VDIWEFA])\s+([^:]+?)\s*:\s*(.*)$"""
        )

        // Örnek brief: "E/MyTag( 1234): Something failed"
        private val briefRegex = Regex(
            """^([VDIWEFA])/([^(]+)\(\s*(\d+)\):\s*(.*)$"""
        )

        fun parseLine(rawLine: String): LogEntry? {
            val trimmed = rawLine.trimEnd()
            if (trimmed.isEmpty() || trimmed.startsWith("--------- beginning of")) return null

            threadtimeRegex.matchEntire(trimmed)?.let { m ->
                return LogEntry(
                    timestamp = m.groupValues[1],
                    pid = m.groupValues[2].toIntOrNull() ?: 0,
                    tid = m.groupValues[3].toIntOrNull() ?: 0,
                    level = LogLevel.fromChar(m.groupValues[4][0]),
                    tag = m.groupValues[5].trim(),
                    message = m.groupValues[6],
                    raw = trimmed
                )
            }

            briefRegex.matchEntire(trimmed)?.let { m ->
                return LogEntry(
                    timestamp = "",
                    pid = m.groupValues[3].toIntOrNull() ?: 0,
                    tid = 0,
                    level = LogLevel.fromChar(m.groupValues[1][0]),
                    tag = m.groupValues[2].trim(),
                    message = m.groupValues[4],
                    raw = trimmed
                )
            }

            return LogEntry(
                timestamp = "",
                pid = 0,
                tid = 0,
                level = LogLevel.INFO,
                tag = "System",
                message = trimmed,
                raw = trimmed
            )
        }

        fun filterLogs(
            entries: List<LogEntry>,
            minLevel: LogLevel = LogLevel.VERBOSE,
            query: String = "",
            tagOrPackageFilter: String = ""
        ): List<LogEntry> {
            val q = query.trim()
            val pkg = tagOrPackageFilter.trim()
            return entries.filter { entry ->
                if (entry.level.priority < minLevel.priority) return@filter false
                if (pkg.isNotEmpty() &&
                    !entry.tag.contains(pkg, ignoreCase = true) &&
                    !entry.message.contains(pkg, ignoreCase = true)
                ) {
                    return@filter false
                }
                if (q.isNotEmpty() &&
                    !entry.message.contains(q, ignoreCase = true) &&
                    !entry.tag.contains(q, ignoreCase = true)
                ) {
                    return@filter false
                }
                true
            }
        }
    }

    object ProblemsManager {
        fun mergeAndGroup(
            buildErrors: List<BuildError>,
            liveDiagnostics: List<CodeIntelligenceEngine.LineDiagnostic>,
            activeFileRelativePath: String?,
            filter: ProblemSeverityFilter = ProblemSeverityFilter.ALL
        ): Map<String, List<ProblemItem>> {
            val all = ArrayList<ProblemItem>()

            for (err in buildErrors) {
                all.add(
                    ProblemItem(
                        source = err.source,
                        file = err.normalizedFile() ?: "(Genel)",
                        line = err.line,
                        isError = err.isError,
                        messageTr = err.messageTr,
                        rawMessage = err.message
                    )
                )
            }

            if (activeFileRelativePath != null) {
                for (diag in liveDiagnostics) {
                    val alreadyListed = all.any {
                        it.file == activeFileRelativePath && it.line == diag.lineOneBased && it.rawMessage == diag.originalMessage
                    }
                    if (!alreadyListed) {
                        all.add(
                            ProblemItem(
                                source = "Anlık ECJ",
                                file = activeFileRelativePath,
                                line = diag.lineOneBased,
                                isError = diag.isError,
                                messageTr = diag.messageTr,
                                rawMessage = diag.originalMessage
                            )
                        )
                    }
                }
            }

            val filtered = when (filter) {
                ProblemSeverityFilter.ALL -> all
                ProblemSeverityFilter.ERRORS_ONLY -> all.filter { it.isError }
                ProblemSeverityFilter.WARNINGS_ONLY -> all.filter { !it.isError }
            }

            return filtered.groupBy { it.file }
        }
    }

    /**
     * Komut geçmişi (↑/↓) ve /system/bin/sh süreç yönetimi sağlayan tekil terminal oturumu.
     */
    class TerminalSession(val id: Int, val workingDir: File) {
        val outputBuffer = StringBuilder()
        private val history = ArrayList<String>()
        private var historyCursor = 0
        @Volatile
        private var activeProcess: Process? = null

        init {
            outputBuffer.append("MiniIDE Terminal #$id (${workingDir.name})\n")
            outputBuffer.append("$TERMINAL_SANDBOX_NOTICE_TR\n\n")
        }

        fun recordHistory(command: String) {
            val trimmed = command.trim()
            if (trimmed.isEmpty()) return
            if (history.lastOrNull() != trimmed) {
                history.add(trimmed)
                if (history.size > 100) history.removeAt(0)
            }
            historyCursor = history.size
        }

        fun historyUp(): String? {
            if (history.isEmpty()) return null
            if (historyCursor > 0) historyCursor--
            return history.getOrNull(historyCursor)
        }

        fun historyDown(): String {
            if (history.isEmpty()) return ""
            if (historyCursor < history.size - 1) {
                historyCursor++
                return history[historyCursor]
            }
            historyCursor = history.size
            return ""
        }

        fun executeCommandBlocking(command: String): String {
            recordHistory(command)
            outputBuffer.append("$ ").append(command).append('\n')
            val shell = if (File("/system/bin/sh").exists()) "/system/bin/sh" else "sh"
            return try {
                val proc = ProcessBuilder(shell, "-c", command)
                    .directory(workingDir)
                    .redirectErrorStream(true)
                    .start()
                activeProcess = proc
                val out = proc.inputStream.bufferedReader().readText()
                val code = proc.waitFor()
                activeProcess = null
                if (out.isNotEmpty()) {
                    outputBuffer.append(out)
                    if (!out.endsWith('\n')) outputBuffer.append('\n')
                }
                if (code != 0) {
                    outputBuffer.append("[Çıkış kodu: ").append(code).append("]\n")
                }
                outputBuffer.toString()
            } catch (e: Exception) {
                activeProcess = null
                outputBuffer.append("Hata: ").append(e.message).append('\n')
                outputBuffer.toString()
            }
        }

        /** Ctrl+C: Çalışan alt kabuk sürecini keser. */
        fun sendCtrlC() {
            val p = activeProcess
            if (p != null) {
                try { p.destroyForcibly() } catch (_: Exception) {}
                activeProcess = null
                outputBuffer.append("^C (İşlem durduruldu)\n")
            } else {
                outputBuffer.append("^C\n")
            }
        }
    }
}
