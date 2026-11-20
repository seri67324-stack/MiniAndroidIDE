package com.miniide.app.panel

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.miniide.app.editor.CodeIntelligenceEngine
import com.miniide.app.service.BuildEvents
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Alt panel (Problemler, Çıktı, Terminal, Logcat) UI ve sürükleyerek boyutlandırma denetleyicisi.
 */
class BottomPanelController(
    private val context: Context,
    private val uiScope: CoroutineScope,
    private val panelRoot: View,
    private val dragHandle: View,
    private val contentContainer: FrameLayout,
    private val tabButtons: Map<Tab, Button>,
    private val projectRootProvider: () -> File?,
    private val activeFileRelativeProvider: () -> String?,
    private val liveDiagnosticsProvider: () -> List<CodeIntelligenceEngine.LineDiagnostic>,
    private val onNavigateToProblem: (relativePath: String, line: Int) -> Unit
) {

    enum class Tab { PROBLEMS, OUTPUT, TERMINAL, LOGCAT }

    private var activeTab: Tab = Tab.PROBLEMS
    private var problemFilter: BottomPanelEngine.ProblemSeverityFilter = BottomPanelEngine.ProblemSeverityFilter.ALL
    private var logLevelFilter: BottomPanelEngine.LogLevel = BottomPanelEngine.LogLevel.VERBOSE
    private var logTextFilter: String = ""

    private val terminalSessions = ArrayList<BottomPanelEngine.TerminalSession>()
    private var activeTerminalIdx = 0
    private var nextSessionId = 1

    private val dp = context.resources.displayMetrics.density

    init {
        setupDragResize()
        for ((tab, btn) in tabButtons) {
            btn.setOnClickListener { showTab(tab) }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupDragResize() {
        var startRawY = 0f
        var startHeight = 0
        val minH = (120 * dp).toInt()
        val maxH = (480 * dp).toInt()

        dragHandle.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startRawY = ev.rawY
                    startHeight = panelRoot.layoutParams.height.takeIf { it > 0 } ?: (220 * dp).toInt()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val deltaY = (startRawY - ev.rawY).toInt()
                    val lp = panelRoot.layoutParams
                    lp.height = (startHeight + deltaY).coerceIn(minH, maxH)
                    panelRoot.layoutParams = lp
                    true
                }
                else -> false
            }
        }
    }

    fun toggle() {
        if (panelRoot.visibility == View.VISIBLE) {
            panelRoot.visibility = View.GONE
        } else {
            panelRoot.visibility = View.VISIBLE
            renderActiveTab()
        }
    }

    fun showTab(tab: Tab) {
        activeTab = tab
        panelRoot.visibility = View.VISIBLE
        for ((t, btn) in tabButtons) {
            btn.setTextColor(if (t == tab) Color.rgb(0x38, 0xBD, 0xF8) else Color.rgb(0x94, 0xA3, 0xB8))
        }
        renderActiveTab()
    }

    fun refreshIfVisible() {
        if (panelRoot.visibility == View.VISIBLE) {
            renderActiveTab()
        }
    }

    private fun renderActiveTab() {
        contentContainer.removeAllViews()
        when (activeTab) {
            Tab.PROBLEMS -> contentContainer.addView(buildProblemsView())
            Tab.OUTPUT -> contentContainer.addView(buildOutputView())
            Tab.TERMINAL -> contentContainer.addView(buildTerminalView())
            Tab.LOGCAT -> contentContainer.addView(buildLogcatView())
        }
    }

    // ---------- 1. Problemler Sekmesi ----------

    private fun buildProblemsView(): View {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((8 * dp).toInt(), (4 * dp).toInt(), (8 * dp).toInt(), (4 * dp).toInt())
        }

        val filterBar = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        fun addFilterBtn(label: String, f: BottomPanelEngine.ProblemSeverityFilter) {
            filterBar.addView(Button(context, null, android.R.attr.borderlessButtonStyle).apply {
                text = label
                isAllCaps = false
                textSize = 11f
                setTextColor(if (problemFilter == f) Color.rgb(0x38, 0xBD, 0xF8) else Color.rgb(0x94, 0xA3, 0xB8))
                setOnClickListener {
                    problemFilter = f
                    renderActiveTab()
                }
            })
        }
        addFilterBtn("Tümü", BottomPanelEngine.ProblemSeverityFilter.ALL)
        addFilterBtn("Hatalar", BottomPanelEngine.ProblemSeverityFilter.ERRORS_ONLY)
        addFilterBtn("Uyarılar", BottomPanelEngine.ProblemSeverityFilter.WARNINGS_ONLY)
        root.addView(filterBar)

        val grouped = BottomPanelEngine.ProblemsManager.mergeAndGroup(
            buildErrors = BuildEvents.lastErrors,
            liveDiagnostics = liveDiagnosticsProvider(),
            activeFileRelativePath = activeFileRelativeProvider(),
            filter = problemFilter
        )

        val scroll = ScrollView(context)
        val listBox = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(listBox)

        if (grouped.isEmpty()) {
            listBox.addView(TextView(context).apply {
                text = "✓ Aktif derleme veya sözdizimi problemi yok."
                setTextColor(Color.rgb(0x10, 0xB9, 0x81))
                textSize = 12f
                setPadding((4 * dp).toInt(), (10 * dp).toInt(), (4 * dp).toInt(), (10 * dp).toInt())
            })
        } else {
            for ((filePath, items) in grouped) {
                listBox.addView(TextView(context).apply {
                    text = "📄 $filePath (${items.size})"
                    typeface = Typeface.DEFAULT_BOLD
                    textSize = 12f
                    setTextColor(Color.rgb(0x93, 0xC5, 0xFD))
                    setPadding(0, (6 * dp).toInt(), 0, (2 * dp).toInt())
                })
                for (item in items) {
                    listBox.addView(TextView(context).apply {
                        val icon = if (item.isError) "⛔" else "⚠"
                        text = "  $icon Satır ${item.line} [${item.source}]: ${item.messageTr}"
                        textSize = 11.5f
                        setTextColor(if (item.isError) Color.rgb(0xF8, 0x71, 0x71) else Color.rgb(0xFB, 0xBF, 0x24))
                        setPadding((6 * dp).toInt(), (4 * dp).toInt(), (4 * dp).toInt(), (4 * dp).toInt())
                        setOnClickListener {
                            onNavigateToProblem(item.file, item.line)
                        }
                    })
                }
            }
        }

        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        return root
    }

    // ---------- 2. Çıktı (BuildLogger) Sekmesi ----------

    private fun buildOutputView(): View {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((8 * dp).toInt(), (4 * dp).toInt(), (8 * dp).toInt(), (4 * dp).toInt())
        }

        val actionBar = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        actionBar.addView(Button(context, null, android.R.attr.borderlessButtonStyle).apply {
            text = "Kopyala"
            isAllCaps = false
            textSize = 11f
            setTextColor(Color.rgb(0x38, 0xBD, 0xF8))
            setOnClickListener {
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("build_log", BuildEvents.fullLog()))
                Toast.makeText(context, "Derleme çıktısı kopyalandı", Toast.LENGTH_SHORT).show()
            }
        })
        actionBar.addView(Button(context, null, android.R.attr.borderlessButtonStyle).apply {
            text = "Temizle"
            isAllCaps = false
            textSize = 11f
            setTextColor(Color.rgb(0x94, 0xA3, 0xB8))
            setOnClickListener {
                BuildEvents.reset()
                renderActiveTab()
            }
        })
        root.addView(actionBar)

        val logText = BuildEvents.fullLog().ifEmpty { "Henüz derleme çıktısı yok. F5 ile derleyebilirsiniz." }
        val colored = SpannableStringBuilder()
        for (line in logText.lines()) {
            val start = colored.length
            colored.append(line).append('\n')
            val color = when {
                line.contains("HATA") || line.contains("BAŞARISIZ") -> Color.rgb(0xF8, 0x71, 0x71)
                line.contains("BAŞARILI") || line.startsWith("✓") -> Color.rgb(0x34, 0xD3, 0x99)
                line.startsWith("[") -> Color.rgb(0x38, 0xBD, 0xF8)
                else -> Color.rgb(0xE2, 0xE8, 0xF0)
            }
            colored.setSpan(ForegroundColorSpan(color), start, colored.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

        val scroll = ScrollView(context)
        val tv = TextView(context).apply {
            text = colored
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setTextIsSelectable(true)
        }
        scroll.addView(tv)
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        return root
    }

    // ---------- 3. Terminal (/system/bin/sh, Çoklu Oturum, Geçmiş ↑/↓, Ctrl+C) ----------

    private fun ensureTerminalSession(): BottomPanelEngine.TerminalSession {
        if (terminalSessions.isEmpty()) {
            val dir = projectRootProvider() ?: context.filesDir
            terminalSessions.add(BottomPanelEngine.TerminalSession(nextSessionId++, dir))
            activeTerminalIdx = 0
        }
        activeTerminalIdx = activeTerminalIdx.coerceIn(0, terminalSessions.lastIndex)
        return terminalSessions[activeTerminalIdx]
    }

    private fun buildTerminalView(): View {
        val session = ensureTerminalSession()
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((8 * dp).toInt(), (4 * dp).toInt(), (8 * dp).toInt(), (4 * dp).toInt())
        }

        // Çoklu oturum çubuğu
        val sessionBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        for ((idx, s) in terminalSessions.withIndex()) {
            sessionBar.addView(Button(context, null, android.R.attr.borderlessButtonStyle).apply {
                text = "sh #${s.id}"
                isAllCaps = false
                textSize = 10.5f
                minWidth = 0
                setTextColor(if (idx == activeTerminalIdx) Color.rgb(0x10, 0xB9, 0x81) else Color.rgb(0x94, 0xA3, 0xB8))
                setOnClickListener {
                    activeTerminalIdx = idx
                    renderActiveTab()
                }
            })
        }
        sessionBar.addView(Button(context, null, android.R.attr.borderlessButtonStyle).apply {
            text = "+ Oturum"
            isAllCaps = false
            textSize = 10.5f
            setTextColor(Color.rgb(0x38, 0xBD, 0xF8))
            setOnClickListener {
                val dir = projectRootProvider() ?: context.filesDir
                terminalSessions.add(BottomPanelEngine.TerminalSession(nextSessionId++, dir))
                activeTerminalIdx = terminalSessions.lastIndex
                renderActiveTab()
            }
        })
        root.addView(sessionBar)

        val scroll = ScrollView(context)
        val tvOut = TextView(context).apply {
            text = session.outputBuffer.toString()
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setTextColor(Color.rgb(0xE2, 0xE8, 0xF0))
            setTextIsSelectable(true)
        }
        scroll.addView(tvOut)
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        // Komut giriş satırı + ↑ / ↓ + Ctrl+C + Çalıştır
        val inputRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val etCmd = EditText(context).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            hint = "Komut yaz (ör. ls -la, pwd)..."
            typeface = Typeface.MONOSPACE
            textSize = 11.5f
            setTextColor(Color.WHITE)
            setHintTextColor(Color.rgb(0x64, 0x74, 0x8B))
            setSingleLine()
        }
        val btnUp = Button(context, null, android.R.attr.borderlessButtonStyle).apply {
            text = "↑"; minWidth = 0; setTextColor(Color.WHITE); textSize = 11f
            setOnClickListener { session.historyUp()?.let { etCmd.setText(it); etCmd.setSelection(it.length) } }
        }
        val btnDown = Button(context, null, android.R.attr.borderlessButtonStyle).apply {
            text = "↓"; minWidth = 0; setTextColor(Color.WHITE); textSize = 11f
            setOnClickListener { val h = session.historyDown(); etCmd.setText(h); etCmd.setSelection(h.length) }
        }
        val btnCtrlC = Button(context, null, android.R.attr.borderlessButtonStyle).apply {
            text = "Ctrl+C"; isAllCaps = false; minWidth = 0; setTextColor(Color.rgb(0xF8, 0x71, 0x71)); textSize = 10.5f
            setOnClickListener {
                session.sendCtrlC()
                tvOut.text = session.outputBuffer.toString()
                scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
            }
        }
        val btnRun = Button(context, null, android.R.attr.borderlessButtonStyle).apply {
            text = "▶"; minWidth = 0; setTextColor(Color.rgb(0x10, 0xB9, 0x81)); textSize = 12f
            setOnClickListener {
                val cmd = etCmd.text.toString().trim()
                if (cmd.isEmpty()) return@setOnClickListener
                etCmd.setText("")
                uiScope.launch {
                    val updated = withContext(Dispatchers.IO) { session.executeCommandBlocking(cmd) }
                    tvOut.text = updated
                    scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
                }
            }
        }
        inputRow.addView(etCmd)
        inputRow.addView(btnUp)
        inputRow.addView(btnDown)
        inputRow.addView(btnCtrlC)
        inputRow.addView(btnRun)
        root.addView(inputRow)
        return root
    }

    // ---------- 4. Logcat Sekmesi ----------

    private fun buildLogcatView(): View {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((8 * dp).toInt(), (4 * dp).toInt(), (8 * dp).toInt(), (4 * dp).toInt())
        }

        root.addView(TextView(context).apply {
            text = BottomPanelEngine.LOGCAT_SANDBOX_NOTICE_TR
            textSize = 10.5f
            setTextColor(Color.rgb(0xFB, 0xBF, 0x24))
            setPadding(0, 0, 0, (4 * dp).toInt())
        })

        val controlRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val etFilter = EditText(context).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            hint = "Etiket veya metin filtrele..."
            setText(logTextFilter)
            textSize = 11.5f
            setTextColor(Color.WHITE)
            setHintTextColor(Color.rgb(0x64, 0x74, 0x8B))
            setSingleLine()
        }
        val btnLevel = Button(context, null, android.R.attr.borderlessButtonStyle).apply {
            text = logLevelFilter.labelTr
            isAllCaps = false
            textSize = 10.5f
            setTextColor(Color.rgb(0x38, 0xBD, 0xF8))
            setOnClickListener {
                val levels = BottomPanelEngine.LogLevel.entries
                val nextIdx = (levels.indexOf(logLevelFilter) + 1) % levels.size
                logLevelFilter = levels[nextIdx]
                renderActiveTab()
            }
        }
        val btnRefresh = Button(context, null, android.R.attr.borderlessButtonStyle).apply {
            text = "↻ Yenile"
            isAllCaps = false
            textSize = 10.5f
            setTextColor(Color.rgb(0x10, 0xB9, 0x81))
        }
        controlRow.addView(etFilter)
        controlRow.addView(btnLevel)
        controlRow.addView(btnRefresh)
        root.addView(controlRow)

        val scroll = ScrollView(context)
        val tvLogs = TextView(context).apply {
            text = "Loglar okunuyor..."
            typeface = Typeface.MONOSPACE
            textSize = 10.5f
            setTextColor(Color.rgb(0xE2, 0xE8, 0xF0))
            setTextIsSelectable(true)
        }
        scroll.addView(tvLogs)
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        fun loadLogs() {
            logTextFilter = etFilter.text?.toString() ?: ""
            uiScope.launch {
                val filtered = withContext(Dispatchers.IO) {
                    try {
                        val proc = ProcessBuilder("logcat", "-d", "-v", "threadtime", "-t", "200")
                            .redirectErrorStream(true)
                            .start()
                        val lines = proc.inputStream.bufferedReader().readLines()
                        proc.waitFor()
                        val parsed = lines.mapNotNull { BottomPanelEngine.LogcatParser.parseLine(it) }
                        BottomPanelEngine.LogcatParser.filterLogs(parsed, logLevelFilter, logTextFilter)
                    } catch (e: Exception) {
                        listOf(
                            BottomPanelEngine.LogEntry(
                                "", 0, 0, BottomPanelEngine.LogLevel.WARN, "Logcat",
                                "Log okunamadı: ${e.message}", ""
                            )
                        )
                    }
                }
                tvLogs.text = if (filtered.isEmpty()) {
                    "Seçilen filtreye uygun log kaydı bulunamadı."
                } else {
                    filtered.joinToString("\n") { "${it.level.code}/${it.tag}: ${it.message}" }
                }
                scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
            }
        }

        btnRefresh.setOnClickListener { loadLogs() }
        loadLogs()
        return root
    }
}
