package com.miniide.app

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.BackgroundColorSpan
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.miniide.app.editor.CodeEditText
import com.miniide.app.editor.CodeIntelligenceEngine
import com.miniide.app.editor.EditorSearchSheets
import com.miniide.app.editor.EditorTextTransforms
import com.miniide.app.editor.KeyAction
import com.miniide.app.editor.KeyStroke
import com.miniide.app.editor.KeybindingRegistry
import com.miniide.app.editor.LineIndex
import com.miniide.app.editor.LogicalKey
import com.miniide.app.editor.ModifierKey
import com.miniide.app.editor.SearchAndCommandEngine
import com.miniide.app.editor.StickyModifierManager
import com.miniide.app.editor.StickyState
import com.miniide.app.editor.SyntaxHighlighter
import com.miniide.app.editor.UndoManager
import com.miniide.app.git.GitDiffAndIgnore
import com.miniide.app.git.GitManager
import com.miniide.app.git.GitSourceControlSheet
import com.miniide.app.panel.BottomPanelController
import com.miniide.app.project.ProjectManager
import com.miniide.app.project.ProjectTemplatesAndTools
import com.miniide.app.settings.SettingsAndThemes
import com.miniide.app.workspace.WorkspaceState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * VS Code benzeri birleşik çalışma alanı (Workspace) ve yüksek performanslı kod editörü:
 * - Çoklu dosya sekme çubuğu (kaydedilmemiş nokta, kapatma, uzun basınca diğerlerini kapat, oturum kaydı/geri yükleme)
 * - Sol Explorer ağaç paneli (telefonda çekmece/açılır panel, tablet/yatay modda kalıcı bölme)
 * - Etkileşimli Breadcrumb çubuğu (proje > klasör > dosya)
 * - Yatay/tablet modda yan yana çift editör (Split View)
 * - VS Code durum çubuğu (dal adı, satır:sütun, girinti, kodlama, LF/CRLF, dil)
 * - Dışarıdan dosya değişimi tespiti ve yenileme uyarısı
 * - LineIndex, pencereli SyntaxHighlighter (+/- 50 satır), delta UndoManager ve PC Tuş Çubuğu
 */
class EditorActivity : AppCompatActivity() {

    private lateinit var editor: CodeEditText
    private lateinit var editorSecondary: CodeEditText
    private lateinit var paneSecondaryEditor: View
    private lateinit var tvSecondaryTitle: TextView
    private lateinit var panelExplorer: View
    private lateinit var tvExplorerHeader: TextView
    private lateinit var lvExplorerTree: ListView
    private lateinit var llTabs: LinearLayout
    private lateinit var llBreadcrumb: LinearLayout
    private lateinit var tvTitle: TextView
    private lateinit var tvSubtitle: TextView
    private lateinit var tvGitBranch: TextView
    private lateinit var tvCursorStatus: TextView
    private lateinit var tvIndentStatus: TextView
    private lateinit var tvEncodingStatus: TextView
    private lateinit var tvEolStatus: TextView
    private lateinit var tvFileStats: TextView
    private lateinit var hsvPcKeys: HorizontalScrollView

    private var projectRoot: File? = null
    private var file: File? = null
    private var language = ""
    private var secondaryLanguage = ""
    private lateinit var palette: SyntaxHighlighter.Palette

    private class FindMatchSpan(color: Int) : BackgroundColorSpan(color)

    private lateinit var panelFindBar: View
    private lateinit var llReplaceRow: View
    private lateinit var etFindQuery: EditText
    private lateinit var etReplaceText: EditText
    private lateinit var tvFindCounter: TextView
    private lateinit var btnFindCase: Button
    private lateinit var btnFindWord: Button
    private lateinit var btnFindRegex: Button

    private var findCaseSensitive = false
    private var findWholeWord = false
    private var findUseRegex = false
    private var currentFindMatches: List<SearchAndCommandEngine.MatchRange> = emptyList()
    private var currentFindMatchIndex = -1
    private var lastProjectReplaceBackup: SearchAndCommandEngine.ProjectReplaceBackup? = null

    private lateinit var bottomPanelController: BottomPanelController
    private var lastLiveDiagnostics: List<CodeIntelligenceEngine.LineDiagnostic> = emptyList()
    private val commandRegistry = SearchAndCommandEngine.CommandRegistry()
    private val workspaceState = WorkspaceState()
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val handler = Handler(Looper.getMainLooper())
    private val stickyModifiers = StickyModifierManager()
    private val keybindingRegistry = KeybindingRegistry()
    private lateinit var explorerAdapter: ExplorerTreeAdapter

    private val activeUndoManager: UndoManager
        get() = workspaceState.activeTab?.undoManager ?: fallbackUndoManager
    private val activeStateCache: SyntaxHighlighter.LineStateCache
        get() = workspaceState.activeTab?.stateCache ?: fallbackStateCache

    private val fallbackUndoManager = UndoManager()
    private val fallbackStateCache = SyntaxHighlighter.LineStateCache()
    private val secondaryStateCache = SyntaxHighlighter.LineStateCache()

    private var btnCtrlView: Button? = null
    private var btnAltView: Button? = null
    private var btnShiftView: Button? = null

    private var programmatic = false
    private var secondaryProgrammatic = false
    private var loaded = false
    private var pendingOldText = ""
    private var lastHighlightedWindow: IntRange = IntRange.EMPTY
    private var selectionAnchor: Int = -1
    private var activeCursorEnd: Int = 0
    private var repeatRunnable: Runnable? = null

    private val highlightRunnable = Runnable { highlightVisibleWindow(force = true) }
    private val scrollHighlightRunnable = Runnable { highlightVisibleWindow(force = false) }
    private val secondaryHighlightRunnable = Runnable { highlightSecondaryWindow() }
    private val liveAnalyzeRunnable = Runnable { runLiveEcjAnalysis() }
    private var completionPopup: androidx.appcompat.widget.ListPopupWindow? = null
    private var cachedAndroidJarClasses: List<String> = emptyList()
    private var lastCompletionStartOffset = 0
    private var lastCompletionItems: List<CodeIntelligenceEngine.CompletionItem> = emptyList()

    private val isWideOrLandscape: Boolean
        get() = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE ||
                resources.configuration.smallestScreenWidthDp >= 600

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_editor)

        keybindingRegistry.loadFromPrefs(this)

        editor = findViewById(R.id.editor)
        editorSecondary = findViewById(R.id.editorSecondary)
        paneSecondaryEditor = findViewById(R.id.paneSecondaryEditor)
        tvSecondaryTitle = findViewById(R.id.tvSecondaryTitle)
        panelExplorer = findViewById(R.id.panelExplorer)
        tvExplorerHeader = findViewById(R.id.tvExplorerHeader)
        lvExplorerTree = findViewById(R.id.lvExplorerTree)
        llTabs = findViewById(R.id.llTabs)
        llBreadcrumb = findViewById(R.id.llBreadcrumb)
        tvTitle = findViewById(R.id.tvEditorTitle)
        tvSubtitle = findViewById(R.id.tvEditorSubtitle)
        tvGitBranch = findViewById(R.id.tvGitBranch)
        tvCursorStatus = findViewById(R.id.tvCursorStatus)
        tvIndentStatus = findViewById(R.id.tvIndentStatus)
        tvEncodingStatus = findViewById(R.id.tvEncodingStatus)
        tvEolStatus = findViewById(R.id.tvEolStatus)
        tvFileStats = findViewById(R.id.tvFileStats)
        hsvPcKeys = findViewById(R.id.hsvPcKeys)

        val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        palette = if (night) SyntaxHighlighter.dark else SyntaxHighlighter.light

        // Proje kökünü ve başlangıç dosyasını belirle
        val explicitProject = intent.getStringExtra(EXTRA_PROJECT)?.let { File(it) }?.takeIf { it.isDirectory }
        val explicitFile = intent.getStringExtra(EXTRA_PATH)?.let { File(it) }?.takeIf { it.isFile }
        projectRoot = explicitProject ?: ProjectManager.current(this) ?: explicitFile?.parentFile
        tvExplorerHeader.text = (projectRoot?.name ?: "GEZGİN").uppercase()

        // Tablet/yatay modda Explorer paneli kalıcı açık, dikey telefonda çekmece (kapalı) başlar
        panelExplorer.visibility = if (isWideOrLandscape) View.VISIBLE else View.GONE

        setupExplorerTree()
        setupBottomPanel()
        setupFindBar()
        setupPrimaryEditorListeners()
        setupSecondaryEditorListeners()
        setupToolbarButtons()
        registerCommands()
        buildPcKeyBar()
        buildSymbolBar()

        // Oturumu geri yükle ve gerekirse intent ile gelen dosyayı aç
        restoreWorkspaceSession(explicitFile)

        // android.jar sınıf indeksini arka planda önbelleğe al (Prompt 5)
        uiScope.launch {
            cachedAndroidJarClasses = withContext(Dispatchers.IO) {
                val jar = File(filesDir, "sdk/android.jar")
                CodeIntelligenceEngine.getOrBuildAndroidJarIndex(jar)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        checkExternalFileChanges()
    }

    // ---------- Explorer Ağaç Paneli (Sol Yan Panel) ----------

    private fun setupBottomPanel() {
        bottomPanelController = BottomPanelController(
            context = this,
            uiScope = uiScope,
            panelRoot = findViewById(R.id.panelBottom),
            dragHandle = findViewById(R.id.viewBottomDragHandle),
            contentContainer = findViewById(R.id.containerBottomContent),
            tabButtons = mapOf(
                BottomPanelController.Tab.PROBLEMS to findViewById(R.id.btnTabProblems),
                BottomPanelController.Tab.OUTPUT to findViewById(R.id.btnTabOutput),
                BottomPanelController.Tab.TERMINAL to findViewById(R.id.btnTabTerminal),
                BottomPanelController.Tab.LOGCAT to findViewById(R.id.btnTabLogcat)
            ),
            projectRootProvider = { projectRoot },
            activeFileRelativeProvider = {
                val f = file
                val root = projectRoot
                if (f != null && root != null) {
                    f.absolutePath.removePrefix(root.absolutePath).trimStart('\\', '/').replace('\\', '/')
                } else f?.name
            },
            liveDiagnosticsProvider = { lastLiveDiagnostics },
            onNavigateToProblem = { relPath, line ->
                val root = projectRoot
                val target = if (relPath.startsWith("/")) File(relPath) else if (root != null) File(root, relPath) else null
                if (target != null && target.isFile) {
                    openFileInTab(target, line)
                } else if (line > 0) {
                    gotoLine(line)
                }
            }
        )
    }

    private fun setupExplorerTree() {
        explorerAdapter = ExplorerTreeAdapter(this)
        lvExplorerTree.adapter = explorerAdapter

        lvExplorerTree.setOnItemClickListener { _, _, position, _ ->
            val node = explorerAdapter.getItem(position) ?: return@setOnItemClickListener
            if (node.isDirectory) {
                workspaceState.toggleDirectoryExpanded(node.path)
                refreshExplorerTree()
            } else {
                openFileInTab(node.file)
                if (!isWideOrLandscape) {
                    panelExplorer.visibility = View.GONE
                }
            }
        }

        lvExplorerTree.setOnItemLongClickListener { _, _, position, _ ->
            val node = explorerAdapter.getItem(position) ?: return@setOnItemLongClickListener true
            showExplorerNodeActions(node)
            true
        }

        findViewById<Button>(R.id.btnExplorerNewFile).setOnClickListener {
            val baseDir = file?.parentFile ?: projectRoot ?: return@setOnClickListener
            askName("Yeni Dosya Adı", "") { createFileInDir(baseDir, it) }
        }
        findViewById<Button>(R.id.btnExplorerNewDir).setOnClickListener {
            val baseDir = file?.parentFile ?: projectRoot ?: return@setOnClickListener
            askName("Yeni Klasör Adı", "") { createDirInDir(baseDir, it) }
        }
    }

    private fun refreshExplorerTree() {
        val root = projectRoot ?: return
        uiScope.launch {
            val (nodes, gitStatus) = withContext(Dispatchers.IO) {
                workspaceState.buildExplorerTree(root) to GitManager.status(root)
            }
            workspaceState.gitBranchName = if (gitStatus.isGitRepo) gitStatus.branch else "git-yok"
            tvGitBranch.text = "⎇ ${workspaceState.gitBranchName}"
            explorerAdapter.activePath = workspaceState.activeTabPath
            explorerAdapter.projectRootPath = WorkspaceState.normalizePath(root.absolutePath)
            explorerAdapter.gitFileStates = gitStatus.fileStates
            explorerAdapter.clear()
            explorerAdapter.addAll(nodes)
            explorerAdapter.notifyDataSetChanged()
            refreshActiveFileGitGutter()
        }
    }

    private fun refreshActiveFileGitGutter() {
        val root = projectRoot ?: return
        val curFile = file ?: return
        if (!loaded) return
        val currentText = editor.text?.toString() ?: return
        val rel = curFile.absolutePath.removePrefix(root.absolutePath).trimStart('\\', '/').replace('\\', '/')
        uiScope.launch {
            val markers = withContext(Dispatchers.IO) {
                val headText = GitManager.getHeadFileContent(root, rel) ?: return@withContext emptyMap()
                GitDiffAndIgnore.LineDiffCalculator.computeGutterMarkers(headText, currentText)
            }
            if (file?.absolutePath == curFile.absolutePath) {
                editor.gitDiffMarkers = markers
            }
        }
    }

    private fun showExplorerNodeActions(node: WorkspaceState.TreeNode) {
        val f = node.file
        val targetDir = if (f.isDirectory) f else (f.parentFile ?: projectRoot ?: return)
        val options = if (f.isFile) {
            arrayOf(
                "Sekmede Aç",
                "Yandaki Bölmede Aç (Split View)",
                "Yeniden Adlandır",
                "Kopyala (Çoğalt)",
                "Taşı (Klasör Seç)",
                "Sil"
            )
        } else {
            arrayOf(
                "Yeni Dosya Oluştur",
                "Yeni Klasör Oluştur",
                "Yeniden Adlandır",
                "Taşı (Klasör Seç)",
                "Sil"
            )
        }

        AlertDialog.Builder(this)
            .setTitle(f.name)
            .setItems(options) { _, which ->
                if (f.isFile) {
                    when (which) {
                        0 -> openFileInTab(f)
                        1 -> openInSplitView(f)
                        2 -> askName("Yeni Ad", f.name) { renameNode(f, it) }
                        3 -> duplicateNode(f)
                        4 -> showMoveNodeDialog(f)
                        5 -> confirmDeleteNode(f)
                    }
                } else {
                    when (which) {
                        0 -> askName("Yeni Dosya Adı", "") { createFileInDir(targetDir, it) }
                        1 -> askName("Yeni Klasör Adı", "") { createDirInDir(targetDir, it) }
                        2 -> askName("Yeni Klasör Adı", f.name) { renameNode(f, it) }
                        3 -> showMoveNodeDialog(f)
                        4 -> confirmDeleteNode(f)
                    }
                }
            }
            .show()
    }

    private fun createFileInDir(dir: File, name: String) {
        if (!ProjectManager.isSafeName(name)) { toast("Geçersiz ad"); return }
        val root = projectRoot ?: dir
        uiScope.launch {
            val res = withContext(Dispatchers.IO) {
                val target = File(dir, name)
                if (target.exists()) return@withContext Result.failure<File>(IllegalStateException("Bu adda öğe zaten var"))
                val content = when {
                    name.endsWith(".java", ignoreCase = true) -> ProjectManager.javaTemplate(root, target)
                    name.endsWith(".xml", ignoreCase = true) -> ProjectManager.xmlTemplate(target)
                    else -> ""
                }
                target.writeText(content, Charsets.UTF_8)
                Result.success(target)
            }
            res.onSuccess { created ->
                workspaceState.expandToFile(root, created)
                refreshExplorerTree()
                openFileInTab(created)
            }.onFailure { toast(it.message ?: "Oluşturulamadı") }
        }
    }

    private fun createDirInDir(dir: File, name: String) {
        if (!ProjectManager.isSafeName(name)) { toast("Geçersiz klasör adı"); return }
        uiScope.launch {
            val ok = withContext(Dispatchers.IO) {
                val target = File(dir, name)
                !target.exists() && target.mkdirs()
            }
            if (ok) {
                workspaceState.expandedDirs.add(WorkspaceState.normalizePath(dir.absolutePath))
                refreshExplorerTree()
            } else {
                toast("Klasör oluşturulamadı veya zaten var")
            }
        }
    }

    private fun renameNode(target: File, newName: String) {
        if (!ProjectManager.isSafeName(newName)) { toast("Geçersiz ad"); return }
        val dest = File(target.parentFile, newName)
        if (dest.exists()) { toast("Bu adda öğe zaten var"); return }
        uiScope.launch {
            val ok = withContext(Dispatchers.IO) { target.renameTo(dest) }
            if (ok) {
                if (workspaceState.getTab(target.absolutePath) != null) {
                    workspaceState.closeTab(target.absolutePath)
                    if (dest.isFile) openFileInTab(dest)
                }
                refreshExplorerTree()
                renderTabs()
                renderBreadcrumbs()
            } else {
                toast("Yeniden adlandırılamadı")
            }
        }
    }

    private fun duplicateNode(src: File) {
        uiScope.launch {
            val copy = withContext(Dispatchers.IO) {
                val base = src.nameWithoutExtension
                val ext = src.extension.let { if (it.isNotEmpty()) ".$it" else "" }
                var dest = File(src.parentFile, "${base}_kopya$ext")
                var n = 2
                while (dest.exists()) {
                    dest = File(src.parentFile, "${base}_kopya$n$ext")
                    n++
                }
                src.copyTo(dest)
                dest
            }
            refreshExplorerTree()
            toast("Kopyalandı: ${copy.name}")
        }
    }

    private fun showMoveNodeDialog(target: File) {
        val root = projectRoot ?: return
        uiScope.launch {
            val dirs = withContext(Dispatchers.IO) {
                root.walkTopDown()
                    .filter { it.isDirectory && it.name != "bin" && it.name != "gen" && it.canonicalPath != target.canonicalPath }
                    .sortedBy { it.absolutePath.lowercase() }
                    .toList()
            }
            val labels = dirs.map { d ->
                val rel = d.absolutePath.removePrefix(root.absolutePath).replace('\\', '/').ifEmpty { "/" }
                "${root.name}$rel"
            }.toTypedArray()

            AlertDialog.Builder(this@EditorActivity)
                .setTitle("Hedef Klasör Seç: ${target.name}")
                .setItems(labels) { _, which ->
                    val destDir = dirs[which]
                    val dest = File(destDir, target.name)
                    if (dest.exists()) {
                        toast("Hedef klasörde bu adda öğe zaten var")
                        return@setItems
                    }
                    uiScope.launch {
                        val ok = withContext(Dispatchers.IO) { target.renameTo(dest) }
                        if (ok) {
                            if (workspaceState.getTab(target.absolutePath) != null) {
                                workspaceState.closeTab(target.absolutePath)
                                if (dest.isFile) openFileInTab(dest)
                            }
                            workspaceState.expandToFile(root, dest)
                            refreshExplorerTree()
                            renderTabs()
                            renderBreadcrumbs()
                            toast("Taşındı: ${destDir.name}/${dest.name}")
                        } else {
                            toast("Taşınamadı")
                        }
                    }
                }
                .setNegativeButton("İptal", null)
                .show()
        }
    }

    private fun confirmDeleteNode(target: File) {
        AlertDialog.Builder(this)
            .setTitle("Silinsin mi?")
            .setMessage("\"${target.name}\" kalıcı olarak silinecek.")
            .setPositiveButton("Sil") { _, _ ->
                uiScope.launch {
                    val ok = withContext(Dispatchers.IO) { target.deleteRecursively() }
                    if (ok) {
                        workspaceState.closeTab(target.absolutePath)
                        refreshExplorerTree()
                        renderTabs()
                        val next = workspaceState.activeTab
                        if (next != null) loadTabIntoPrimaryEditor(next, 0)
                    } else {
                        toast("Silinemedi")
                    }
                }
            }
            .setNegativeButton("İptal", null)
            .show()
    }

    private fun askName(title: String, initial: String, onOk: (String) -> Unit) {
        val input = EditText(this).apply {
            setText(initial)
            setSingleLine()
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(input)
            .setPositiveButton("Tamam") { _, _ -> onOk(input.text.toString().trim()) }
            .setNegativeButton("İptal", null)
            .show()
    }

    // ---------- Çoklu Sekme Yönetimi (Tab Bar) & Oturum Kaydı ----------

    private fun restoreWorkspaceSession(explicitFile: File?) {
        val sessionKey = sessionPrefsKey()
        val saved = getSharedPreferences(PREFS_WORKSPACE, Context.MODE_PRIVATE).getString(sessionKey, null)
        if (!saved.isNullOrBlank()) {
            workspaceState.restoreSession(saved) { path -> File(path).isFile }
        }

        val initialLine = intent.getIntExtra(EXTRA_LINE, 0)
        intent.removeExtra(EXTRA_LINE)

        if (explicitFile != null && explicitFile.isFile) {
            val tab = workspaceState.openTab(explicitFile.absolutePath, lastModifiedOnDisk = explicitFile.lastModified())
            projectRoot?.let { workspaceState.expandToFile(it, explicitFile) }
            loadTabIntoPrimaryEditor(tab, initialLine)
        } else {
            val active = workspaceState.activeTab
            if (active != null && File(active.path).isFile) {
                projectRoot?.let { workspaceState.expandToFile(it, File(active.path)) }
                loadTabIntoPrimaryEditor(active, initialLine)
            } else {
                // Oturum boşsa projenin ilk anlamlı dosyasını (MainActivity.java veya AndroidManifest.xml) aç
                val root = projectRoot
                val fallback = root?.let { findInitialProjectFile(it) }
                if (fallback != null) {
                    val tab = workspaceState.openTab(fallback.absolutePath, lastModifiedOnDisk = fallback.lastModified())
                    workspaceState.expandToFile(root, fallback)
                    loadTabIntoPrimaryEditor(tab, 0)
                } else {
                    renderTabs()
                    renderBreadcrumbs()
                }
            }
        }

        // Varsa ikincil split bölmesini geri yükle
        if (workspaceState.splitViewEnabled) {
            val sec = workspaceState.secondaryTab
            if (sec != null && File(sec.path).isFile) {
                openInSplitView(File(sec.path))
            }
        }
        refreshExplorerTree()
    }

    private fun findInitialProjectFile(root: File): File? {
        val mainJava = File(root, "src").takeIf { it.isDirectory }
            ?.walkTopDown()
            ?.firstOrNull { it.isFile && it.name.equals("MainActivity.java", ignoreCase = true) }
        if (mainJava != null) return mainJava
        val anyJava = File(root, "src").takeIf { it.isDirectory }
            ?.walkTopDown()
            ?.firstOrNull { it.isFile && it.extension.equals("java", ignoreCase = true) }
        if (anyJava != null) return anyJava
        return File(root, "AndroidManifest.xml").takeIf { it.isFile }
    }

    private fun saveWorkspaceSession() {
        syncActiveEditorToTabState()
        val sessionKey = sessionPrefsKey()
        getSharedPreferences(PREFS_WORKSPACE, Context.MODE_PRIVATE)
            .edit()
            .putString(sessionKey, workspaceState.serializeSession())
            .apply()
    }

    private fun sessionPrefsKey(): String =
        "session_" + (projectRoot?.name ?: "default")

    private fun syncActiveEditorToTabState() {
        val active = workspaceState.activeTab ?: return
        if (!loaded) return
        val curText = editor.text?.toString() ?: ""
        active.draftText = curText
        active.cursorOffset = editor.selectionStart.coerceAtLeast(0)
        active.scrollY = editor.scrollY.coerceAtLeast(0)
    }

    private fun openFileInTab(targetFile: File, targetLine: Int = 0) {
        if (!targetFile.isFile) {
            toast("Dosya bulunamadı")
            return
        }
        syncActiveEditorToTabState()
        val tab = workspaceState.openTab(targetFile.absolutePath, lastModifiedOnDisk = targetFile.lastModified())
        projectRoot?.let { workspaceState.expandToFile(it, targetFile) }
        recordRecentFile(targetFile.absolutePath)
        loadTabIntoPrimaryEditor(tab, targetLine)
        saveWorkspaceSession()
        refreshExplorerTree()
    }

    private fun closeTabWithPrompt(tab: WorkspaceState.TabEntry) {
        if (tab.path == workspaceState.activeTabPath) {
            syncActiveEditorToTabState()
        }
        if (tab.dirty) {
            AlertDialog.Builder(this)
                .setTitle("Kaydedilmemiş Değişiklikler")
                .setMessage("\"${tab.name}\" dosyasında kaydedilmemiş değişiklikler var. Kapatmadan önce kaydedilsin mi?")
                .setPositiveButton("Kaydet ve Kapat") { _, _ ->
                    saveTabToDiskAsync(tab) { performCloseTab(tab.path) }
                }
                .setNegativeButton("Kaydetmeden Kapat") { _, _ -> performCloseTab(tab.path) }
                .setNeutralButton("İptal", null)
                .show()
        } else {
            performCloseTab(tab.path)
        }
    }

    private fun performCloseTab(path: String) {
        val next = workspaceState.closeTab(path)
        saveWorkspaceSession()
        if (next != null) {
            loadTabIntoPrimaryEditor(next, 0)
        } else {
            file = null
            loaded = false
            programmatic = true
            editor.setCodeText("")
            programmatic = false
            tvTitle.text = "Açık dosya yok"
            tvSubtitle.text = projectRoot?.name ?: ""
            renderTabs()
            renderBreadcrumbs()
        }
        if (!workspaceState.splitViewEnabled) {
            paneSecondaryEditor.visibility = View.GONE
        }
        refreshExplorerTree()
    }

    private fun renderTabs() {
        llTabs.removeAllViews()
        val dp = resources.displayMetrics.density
        val allTabs = workspaceState.tabs
        val activePath = workspaceState.activeTabPath

        for ((index, tab) in allTabs.withIndex()) {
            val isActive = tab.path == activePath
            val chip = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    (32 * dp).toInt()
                ).apply {
                    marginEnd = (4 * dp).toInt()
                }
                layoutParams = lp
                setPadding((10 * dp).toInt(), 0, (4 * dp).toInt(), 0)
                background = GradientDrawable().apply {
                    cornerRadius = 6 * dp
                    if (isActive) {
                        setColor(Color.rgb(0x1E, 0x29, 0x3B))
                        setStroke((1.5f * dp).toInt(), Color.rgb(0x38, 0xBD, 0xF8))
                    } else {
                        setColor(Color.rgb(0x0F, 0x17, 0x2A))
                    }
                }
                setOnClickListener {
                    if (tab.path != workspaceState.activeTabPath) {
                        openFileInTab(File(tab.path))
                    }
                }
                setOnLongClickListener {
                    showTabContextMenu(tab, index, allTabs.size)
                    true
                }
            }

            val label = TextView(this).apply {
                text = (if (tab.dirty) "● " else "") + tab.name
                textSize = 11.5f
                typeface = if (isActive) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                setTextColor(
                    when {
                        tab.dirty -> Color.rgb(0xFB, 0xBF, 0x24)
                        isActive -> Color.WHITE
                        else -> Color.rgb(0x94, 0xA3, 0xB8)
                    }
                )
            }
            chip.addView(label)

            val closeBtn = TextView(this).apply {
                text = " ✕"
                textSize = 11.5f
                setTextColor(Color.rgb(0x94, 0xA3, 0xB8))
                setPadding((6 * dp).toInt(), (4 * dp).toInt(), (6 * dp).toInt(), (4 * dp).toInt())
                setOnClickListener { closeTabWithPrompt(tab) }
            }
            chip.addView(closeBtn)

            llTabs.addView(chip)
        }
    }

    private fun showTabContextMenu(tab: WorkspaceState.TabEntry, index: Int, totalCount: Int) {
        val options = mutableListOf(
            "Diğerlerini Kapat",
            "Kaydedilenleri Kapat",
            "Yandaki Bölmede Aç (Split View)",
            "Tümünü Kapat"
        )
        if (index > 0) options.add("Sekmeyi Sola Taşı")
        if (index < totalCount - 1) options.add("Sekmeyi Sağa Taşı")

        AlertDialog.Builder(this)
            .setTitle(tab.name)
            .setItems(options.toTypedArray()) { _, which ->
                when (options[which]) {
                    "Diğerlerini Kapat" -> {
                        syncActiveEditorToTabState()
                        workspaceState.closeOtherTabs(tab.path)
                        loadTabIntoPrimaryEditor(tab, 0)
                        saveWorkspaceSession()
                    }
                    "Kaydedilenleri Kapat" -> {
                        syncActiveEditorToTabState()
                        val next = workspaceState.closeSavedTabs()
                        if (next != null) loadTabIntoPrimaryEditor(next, 0) else renderTabs()
                        saveWorkspaceSession()
                    }
                    "Yandaki Bölmede Aç (Split View)" -> openInSplitView(File(tab.path))
                    "Tümünü Kapat" -> {
                        workspaceState.closeAllTabs()
                        file = null
                        loaded = false
                        programmatic = true
                        editor.setCodeText("")
                        programmatic = false
                        paneSecondaryEditor.visibility = View.GONE
                        renderTabs()
                        renderBreadcrumbs()
                        saveWorkspaceSession()
                    }
                    "Sekmeyi Sola Taşı" -> {
                        workspaceState.reorderTab(index, index - 1)
                        renderTabs()
                        saveWorkspaceSession()
                    }
                    "Sekmeyi Sağa Taşı" -> {
                        workspaceState.reorderTab(index, index + 1)
                        renderTabs()
                        saveWorkspaceSession()
                    }
                }
            }
            .show()
    }

    // ---------- Breadcrumb Çubuğu (proje > klasör > dosya) ----------

    private fun renderBreadcrumbs() {
        llBreadcrumb.removeAllViews()
        val curFile = file ?: return
        val segments = WorkspaceState.buildBreadcrumbs(projectRoot, curFile)
        val dp = resources.displayMetrics.density

        segments.forEachIndexed { idx, (label, segFile) ->
            val tv = TextView(this).apply {
                text = label
                textSize = 11f
                typeface = if (idx == segments.lastIndex) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                setTextColor(if (idx == segments.lastIndex) Color.rgb(0x38, 0xBD, 0xF8) else Color.rgb(0xCB, 0xD5, 0xE1))
                setPadding((4 * dp).toInt(), (2 * dp).toInt(), (4 * dp).toInt(), (2 * dp).toInt())
                setOnClickListener {
                    val dirToBrowse = if (segFile.isDirectory) segFile else (segFile.parentFile ?: return@setOnClickListener)
                    showBreadcrumbDirectoryPopup(dirToBrowse)
                }
            }
            llBreadcrumb.addView(tv)

            if (idx < segments.lastIndex) {
                val sep = TextView(this).apply {
                    text = " › "
                    textSize = 11f
                    setTextColor(Color.rgb(0x64, 0x74, 0x8B))
                }
                llBreadcrumb.addView(sep)
            }
        }
    }

    private fun showBreadcrumbDirectoryPopup(dir: File) {
        val children = dir.listFiles()
            ?.filter { it.name != "bin" && it.name != "gen" }
            ?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
            ?: emptyList()
        if (children.isEmpty()) {
            toast("Klasör boş")
            return
        }
        val labels = children.map { (if (it.isDirectory) "📁 " else "📄 ") + it.name }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(dir.name)
            .setItems(labels) { _, which ->
                val chosen = children[which]
                if (chosen.isDirectory) {
                    showBreadcrumbDirectoryPopup(chosen)
                } else {
                    openFileInTab(chosen)
                }
            }
            .setNegativeButton("Kapat", null)
            .show()
    }

    // ---------- Çift Editör (Split View) ----------

    private fun toggleSplitView() {
        if (workspaceState.splitViewEnabled) {
            workspaceState.closeSecondarySplit()
            paneSecondaryEditor.visibility = View.GONE
            saveWorkspaceSession()
            return
        }
        val otherTab = workspaceState.tabs.firstOrNull { it.path != workspaceState.activeTabPath }
        val targetFile = otherTab?.let { File(it.path) } ?: file
        if (targetFile == null) {
            toast("Bölünmüş görünüm için önce bir dosya açın")
            return
        }
        openInSplitView(targetFile)
    }

    private fun openInSplitView(targetFile: File) {
        if (!targetFile.isFile) return
        workspaceState.openInSecondarySplit(targetFile.absolutePath)
        paneSecondaryEditor.visibility = View.VISIBLE
        tvSecondaryTitle.text = targetFile.name
        secondaryLanguage = SyntaxHighlighter.languageFor(targetFile.name)
        secondaryStateCache.reset()

        uiScope.launch {
            val content = withContext(Dispatchers.IO) {
                try {
                    if (targetFile.length() > MAX_SIZE) null else targetFile.readText(Charsets.UTF_8)
                } catch (_: Exception) {
                    null
                }
            }
            if (content == null) {
                toast("İkinci bölmede dosya açılamadı")
                return@launch
            }
            secondaryProgrammatic = true
            editorSecondary.setCodeText(content)
            secondaryProgrammatic = false
            editorSecondary.post { highlightSecondaryWindow() }
        }
        renderTabs()
        saveWorkspaceSession()
    }

    private fun setupSecondaryEditorListeners() {
        editorSecondary.onVisibleLinesChanged = { _, _ ->
            handler.removeCallbacks(secondaryHighlightRunnable)
            handler.postDelayed(secondaryHighlightRunnable, 32)
        }
        editorSecondary.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (secondaryProgrammatic) return
                val line = editorSecondary.lineIndex.lineAtOffset(start)
                secondaryStateCache.invalidateFromLine(line)
            }
            override fun afterTextChanged(s: Editable?) {
                if (secondaryProgrammatic) return
                handler.removeCallbacks(secondaryHighlightRunnable)
                handler.postDelayed(secondaryHighlightRunnable, 140)
                val secPath = workspaceState.secondarySplitPath ?: return
                workspaceState.getTab(secPath)?.let { tab ->
                    tab.dirty = true
                    tab.draftText = s?.toString()
                }
                renderTabs()
            }
        })
    }

    private fun highlightSecondaryWindow() {
        val (first, last) = editorSecondary.getVisibleLineBounds()
        SyntaxHighlighter.highlightWindow(
            editable = editorSecondary.text,
            lineIndex = editorSecondary.lineIndex,
            language = secondaryLanguage,
            palette = palette,
            stateCache = secondaryStateCache,
            firstVisibleLine = first,
            lastVisibleLine = last
        )
    }

    // ---------- Dışarıdan Dosya Değişimi Kontrolü ----------

    private fun checkExternalFileChanges() {
        val active = workspaceState.activeTab ?: return
        val diskFile = File(active.path)
        if (!diskFile.isFile) return
        val currentMod = diskFile.lastModified()
        if (!workspaceState.isExternallyModified(active.path, currentMod)) return

        AlertDialog.Builder(this)
            .setTitle("Dosya Dışarıdan Değişti")
            .setMessage("\"${active.name}\" dosyası editör dışında değiştirildi. Diskteki güncel sürümle yenilensin mi?")
            .setPositiveButton("Yenile") { _, _ ->
                active.draftText = null
                active.dirty = false
                active.lastModifiedOnDisk = currentMod
                loadTabIntoPrimaryEditor(active, 0)
            }
            .setNegativeButton("Mevcut Halini Koru") { _, _ ->
                workspaceState.updateDiskTimestamp(active.path, currentMod)
            }
            .show()
    }

    // ---------- Birincil Editör Dinleyicileri & Araç Çubuğu ----------

    private fun setupPrimaryEditorListeners() {
        editor.onCursorChanged = { line, col ->
            updateCursorAndModifierStatus(line, col)
            editor.getDiagnosticAtLine(line)?.let { diag ->
                val prefix = if (diag.isError) "⛔ " else "⚠ "
                tvCursorStatus.text = "Satır $line, Sütun $col  $prefix${diag.messageTr}"
            }
        }

        editor.onVisibleLinesChanged = { first, last ->
            if (loaded && !windowCovers(lastHighlightedWindow, first, last)) {
                handler.removeCallbacks(scrollHighlightRunnable)
                handler.postDelayed(scrollHighlightRunnable, 24)
            }
        }

        editor.filters = arrayOf(smartInputFilter)
        editor.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
                if (programmatic || !loaded || s == null) return
                pendingOldText = if (count > 0) s.subSequence(start, start + count).toString() else ""
            }

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (programmatic || !loaded || s == null) return
                val newText = if (count > 0) s.subSequence(start, start + count).toString() else ""
                activeUndoManager.recordEdit(start, pendingOldText, newText)
                pendingOldText = ""

                val editedLine = editor.lineIndex.lineAtOffset(start)
                activeStateCache.invalidateFromLine(editedLine)
            }

            override fun afterTextChanged(s: Editable?) {
                if (programmatic || !loaded) return
                setDirty(true)
                updateFileStats()
                handler.removeCallbacks(highlightRunnable)
                handler.postDelayed(highlightRunnable, 110)
                handler.removeCallbacks(liveAnalyzeRunnable)
                handler.postDelayed(liveAnalyzeRunnable, 800)
                triggerAutocompleteIfReady()
            }
        })
    }

    private fun setupToolbarButtons() {
        findViewById<Button>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<Button>(R.id.btnToggleExplorer).setOnClickListener { toggleExplorerPanel() }
        findViewById<Button>(R.id.btnSplitView).setOnClickListener { toggleSplitView() }
        findViewById<Button>(R.id.btnCloseSplit).setOnClickListener {
            workspaceState.closeSecondarySplit()
            paneSecondaryEditor.visibility = View.GONE
            saveWorkspaceSession()
        }
        findViewById<Button>(R.id.btnBuildFromEditor).setOnClickListener { executeKeyAction(KeyAction.BUILD_AND_RUN) }
        findViewById<Button>(R.id.btnSave).setOnClickListener { executeKeyAction(KeyAction.SAVE) }
        findViewById<Button>(R.id.btnUndo).setOnClickListener { executeKeyAction(KeyAction.UNDO) }
        findViewById<Button>(R.id.btnRedo).setOnClickListener { executeKeyAction(KeyAction.REDO) }
        findViewById<Button>(R.id.btnFind).setOnClickListener { openFindBar(showReplace = true) }
        findViewById<Button>(R.id.btnProjectSearch).setOnClickListener { executeKeyAction(KeyAction.PROJECT_SEARCH) }
        findViewById<Button>(R.id.btnGoto).setOnClickListener { showGotoDialog() }
        findViewById<Button>(R.id.btnToggleBottomPanel).setOnClickListener { bottomPanelController.toggle() }
        findViewById<Button>(R.id.btnCloseBottomPanel).setOnClickListener {
            findViewById<View>(R.id.panelBottom).visibility = View.GONE
        }
        findViewById<Button>(R.id.btnCommandPalette).setOnClickListener { showCommandPaletteDialog() }
        findViewById<Button>(R.id.btnToggleKeyBar).setOnClickListener {
            hsvPcKeys.visibility = if (hsvPcKeys.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        findViewById<Button>(R.id.btnKeySettings).setOnClickListener { showKeybindingSettingsDialog() }
        findViewById<Button>(R.id.btnSnippet).setOnClickListener { showSnippetsDialog() }
        findViewById<Button>(R.id.btnZoomOut).setOnClickListener {
            editor.setFontSizeSp(editor.getFontSizeSp() - 1.5f)
            editor.post { highlightVisibleWindow(force = true) }
        }
        findViewById<Button>(R.id.btnZoomIn).setOnClickListener {
            editor.setFontSizeSp(editor.getFontSizeSp() + 1.5f)
            editor.post { highlightVisibleWindow(force = true) }
        }

        // Durum çubuğunda LF <-> CRLF geçişi
        tvEolStatus.setOnClickListener {
            val tab = workspaceState.activeTab ?: return@setOnClickListener
            tab.lineEnding = if (tab.lineEnding == "LF") "CRLF" else "LF"
            tab.dirty = true
            tvEolStatus.text = tab.lineEnding
            renderTabs()
            toast("Satır sonu biçimi: ${tab.lineEnding}")
        }
        tvGitBranch.text = "⎇ ${workspaceState.gitBranchName}"
        tvGitBranch.setOnClickListener { openGitSourceControl() }
    }

    private fun openGitSourceControl() {
        GitSourceControlSheet.show(
            context = this,
            uiScope = uiScope,
            projectDir = projectRoot,
            onRepoStateChanged = { refreshExplorerTree() },
            onClonedNewProject = { clonedDir ->
                projectRoot = clonedDir
                tvExplorerHeader.text = clonedDir.name.uppercase()
                workspaceState.closeAllTabs()
                restoreWorkspaceSession(null)
            }
        )
    }

    private fun toggleExplorerPanel() {
        panelExplorer.visibility = if (panelExplorer.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        if (panelExplorer.visibility == View.VISIBLE) {
            refreshExplorerTree()
        }
    }

    // ---------- Birleşik Fiziksel Klavye + PC Tuş Çubuğu Katmanı ----------

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (loaded && event.action == KeyEvent.ACTION_DOWN) {
            val logicalKey = LogicalKey.fromAndroidKeyCode(event.keyCode)
            if (logicalKey != null) {
                val hasModifiers = event.isCtrlPressed || event.isAltPressed ||
                        stickyModifiers.ctrl.isActive || stickyModifiers.alt.isActive || stickyModifiers.shift.isActive
                val isSpecialNavKey = logicalKey in setOf(
                    LogicalKey.ESC, LogicalKey.TAB, LogicalKey.HOME, LogicalKey.END,
                    LogicalKey.PGUP, LogicalKey.PGDN, LogicalKey.DEL,
                    LogicalKey.F1, LogicalKey.F2, LogicalKey.F3, LogicalKey.F4,
                    LogicalKey.F5, LogicalKey.F6, LogicalKey.F7, LogicalKey.F8,
                    LogicalKey.F9, LogicalKey.F10, LogicalKey.F11, LogicalKey.F12
                )
                val isArrowWithModifier = logicalKey in setOf(
                    LogicalKey.LEFT, LogicalKey.RIGHT, LogicalKey.UP, LogicalKey.DOWN
                ) && (hasModifiers || event.isShiftPressed)

                if (hasModifiers || isSpecialNavKey || isArrowWithModifier) {
                    if (handleLogicalKeyPress(
                            key = logicalKey,
                            hwCtrl = event.isCtrlPressed,
                            hwAlt = event.isAltPressed,
                            hwShift = event.isShiftPressed
                        )
                    ) {
                        return true
                    }
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (loaded && event != null) {
            val logicalKey = LogicalKey.fromAndroidKeyCode(keyCode)
            if (logicalKey != null && (event.isCtrlPressed || event.isAltPressed)) {
                if (handleLogicalKeyPress(logicalKey, event.isCtrlPressed, event.isAltPressed, event.isShiftPressed)) {
                    return true
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun handleLogicalKeyPress(
        key: LogicalKey,
        hwCtrl: Boolean = false,
        hwAlt: Boolean = false,
        hwShift: Boolean = false
    ): Boolean {
        val stroke = stickyModifiers.consumeForKey(key, hwCtrl, hwAlt, hwShift)
        refreshModifierButtonsUi()

        val action = keybindingRegistry.resolve(stroke)
        if (action != null) {
            executeKeyAction(action)
            return true
        }
        return executeNavigationOrEditKey(stroke)
    }

    private fun executeKeyAction(action: KeyAction) {
        when (action) {
            KeyAction.SAVE -> {
                activeUndoManager.breakCoalescing()
                saveAsync(showToast = true)
            }
            KeyAction.UNDO -> doUndo()
            KeyAction.REDO -> doRedo()
            KeyAction.FIND -> openFindBar(showReplace = false)
            KeyAction.REPLACE -> openFindBar(showReplace = true)
            KeyAction.PROJECT_SEARCH -> showProjectSearchDialog()
            KeyAction.GOTO_LINE -> showGotoDialog()
            KeyAction.QUICK_OPEN -> showQuickOpenDialog()
            KeyAction.COMMAND_PALETTE -> showCommandPaletteDialog()
            KeyAction.SELECT_NEXT_MATCH -> doSelectNextMatch()
            KeyAction.TOGGLE_COMMENT -> applyTextTransform { text, idx, s, e ->
                EditorTextTransforms.toggleLineComment(text, idx, s, e, language)
            }
            KeyAction.INDENT_LINE -> applyTextTransform { text, idx, s, e ->
                EditorTextTransforms.indentLines(text, idx, s, e)
            }
            KeyAction.OUTDENT_LINE -> applyTextTransform { text, idx, s, e ->
                EditorTextTransforms.outdentLines(text, idx, s, e)
            }
            KeyAction.MOVE_LINE_UP -> applyTextTransform { text, idx, s, e ->
                EditorTextTransforms.moveLinesUp(text, idx, s, e)
            }
            KeyAction.MOVE_LINE_DOWN -> applyTextTransform { text, idx, s, e ->
                EditorTextTransforms.moveLinesDown(text, idx, s, e)
            }
            KeyAction.DUPLICATE_LINE_UP -> applyTextTransform { text, idx, s, e ->
                EditorTextTransforms.duplicateLines(text, idx, s, e, up = true)
            }
            KeyAction.DUPLICATE_LINE_DOWN -> applyTextTransform { text, idx, s, e ->
                EditorTextTransforms.duplicateLines(text, idx, s, e, up = false)
            }
            KeyAction.DELETE_LINE -> applyTextTransform { text, idx, s, e ->
                EditorTextTransforms.deleteLines(text, idx, s, e)
            }
            KeyAction.WORD_LEFT -> moveCursorByWord(forward = false, extendSelection = false)
            KeyAction.WORD_RIGHT -> moveCursorByWord(forward = true, extendSelection = false)
            KeyAction.WORD_LEFT_SELECT -> moveCursorByWord(forward = false, extendSelection = true)
            KeyAction.WORD_RIGHT_SELECT -> moveCursorByWord(forward = true, extendSelection = true)
            KeyAction.SELECT_ALL -> {
                selectionAnchor = 0
                activeCursorEnd = editor.text?.length ?: 0
                editor.setSelection(0, activeCursorEnd)
            }
            KeyAction.COPY -> copySelectionToClipboard(cut = false)
            KeyAction.CUT -> copySelectionToClipboard(cut = true)
            KeyAction.PASTE -> pasteFromClipboard()
            KeyAction.TOGGLE_SIDE_PANEL -> toggleExplorerPanel()
            KeyAction.SWITCH_TAB -> {
                if (workspaceState.tabs.size > 1) {
                    syncActiveEditorToTabState()
                    val next = workspaceState.selectNextTab()
                    if (next != null) loadTabIntoPrimaryEditor(next, 0)
                    saveWorkspaceSession()
                } else {
                    showQuickOpenDialog()
                }
            }
            KeyAction.CLOSE_TAB -> {
                val active = workspaceState.activeTab
                if (active != null) closeTabWithPrompt(active) else finish()
            }
            KeyAction.BUILD_AND_RUN -> {
                saveAsync(showToast = false) {
                    val intent = Intent(this, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        .putExtra(MainActivity.EXTRA_AUTO_BUILD, true)
                        .putExtra(MainActivity.EXTRA_AUTO_INSTALL_AFTER_BUILD, true)
                    startActivity(intent)
                }
            }
        }
    }

    private fun executeNavigationOrEditKey(stroke: KeyStroke): Boolean {
        val editable = editor.text ?: return false
        val len = editable.length
        val curA = minOf(editor.selectionStart, editor.selectionEnd).coerceIn(0, len)
        val curB = maxOf(editor.selectionStart, editor.selectionEnd).coerceIn(0, len)
        if (!stroke.shift || selectionAnchor < 0) {
            selectionAnchor = curA
            activeCursorEnd = curB
        }
        val activePos = if (stroke.shift) activeCursorEnd.coerceIn(0, len) else editor.selectionEnd.coerceIn(0, len)

        when (stroke.key) {
            LogicalKey.ESC -> {
                if (panelFindBar.visibility == View.VISIBLE) {
                    closeFindBar()
                    return true
                }
                stickyModifiers.clearAll()
                refreshModifierButtonsUi()
                selectionAnchor = -1
                editor.setSelection(curB)
                return true
            }
            LogicalKey.TAB -> {
                if (stroke.shift) {
                    applyTextTransform { text, idx, s, e -> EditorTextTransforms.outdentLines(text, idx, s, e) }
                } else if (curB > curA && editable.subSequence(curA, curB).contains('\n')) {
                    applyTextTransform { text, idx, s, e -> EditorTextTransforms.indentLines(text, idx, s, e) }
                } else {
                    insertText("    ")
                }
                return true
            }
            LogicalKey.DEL -> {
                if (curB > curA) {
                    editable.delete(curA, curB)
                } else if (curA < len) {
                    editable.delete(curA, curA + 1)
                }
                return true
            }
            LogicalKey.LEFT -> {
                val target = if (!stroke.shift && curB > curA) curA else (activePos - 1).coerceAtLeast(0)
                applyCursorMove(target, stroke.shift)
                return true
            }
            LogicalKey.RIGHT -> {
                val target = if (!stroke.shift && curB > curA) curB else (activePos + 1).coerceAtMost(len)
                applyCursorMove(target, stroke.shift)
                return true
            }
            LogicalKey.UP -> {
                val target = computeVerticalLineOffset(activePos, deltaLines = -1)
                applyCursorMove(target, stroke.shift)
                return true
            }
            LogicalKey.DOWN -> {
                val target = computeVerticalLineOffset(activePos, deltaLines = 1)
                applyCursorMove(target, stroke.shift)
                return true
            }
            LogicalKey.PGUP -> {
                val target = computeVerticalLineOffset(activePos, deltaLines = -20)
                applyCursorMove(target, stroke.shift)
                return true
            }
            LogicalKey.PGDN -> {
                val target = computeVerticalLineOffset(activePos, deltaLines = 20)
                applyCursorMove(target, stroke.shift)
                return true
            }
            LogicalKey.HOME -> {
                val target = if (stroke.ctrl) 0 else EditorTextTransforms.smartHomeOffset(editable, editor.lineIndex, activePos)
                applyCursorMove(target, stroke.shift)
                return true
            }
            LogicalKey.END -> {
                val target = if (stroke.ctrl) len else EditorTextTransforms.smartEndOffset(editable, editor.lineIndex, activePos)
                applyCursorMove(target, stroke.shift)
                return true
            }
            LogicalKey.F12 -> {
                goToDefinitionAtCursor()
                return true
            }
            else -> return false
        }
    }

    private fun computeVerticalLineOffset(currentOffset: Int, deltaLines: Int): Int {
        val idx = editor.lineIndex
        val textLen = editor.text?.length ?: 0
        val pos = idx.lineAndColumn(currentOffset)
        val targetLine = (pos.lineZeroBased + deltaLines).coerceIn(0, (idx.lineCount - 1).coerceAtLeast(0))
        val lStart = idx.lineStart(targetLine)
        val lEnd = idx.lineEnd(targetLine, textLen)
        return (lStart + pos.columnZeroBased).coerceIn(lStart, lEnd)
    }

    private fun applyCursorMove(targetOffset: Int, extendWithShift: Boolean) {
        val len = editor.text?.length ?: 0
        val safeTarget = targetOffset.coerceIn(0, len)
        if (!extendWithShift) {
            selectionAnchor = safeTarget
            activeCursorEnd = safeTarget
            editor.setSelection(safeTarget)
        } else {
            if (selectionAnchor < 0) {
                selectionAnchor = editor.selectionStart.coerceIn(0, len)
            }
            activeCursorEnd = safeTarget
            val (s, e) = EditorTextTransforms.computeSelectionAfterMove(selectionAnchor, safeTarget, true)
            editor.setSelection(minOf(s, e).coerceIn(0, len), maxOf(s, e).coerceIn(0, len))
        }
        activeUndoManager.breakCoalescing()
        editor.requestFocus()
    }

    private fun moveCursorByWord(forward: Boolean, extendSelection: Boolean) {
        val editable = editor.text ?: return
        val len = editable.length
        if (!extendSelection || selectionAnchor < 0) {
            selectionAnchor = minOf(editor.selectionStart, editor.selectionEnd).coerceIn(0, len)
            activeCursorEnd = maxOf(editor.selectionStart, editor.selectionEnd).coerceIn(0, len)
        }
        val basePos = if (extendSelection) activeCursorEnd else editor.selectionEnd.coerceIn(0, len)
        val target = EditorTextTransforms.wordJumpOffset(editable, basePos, forward)
        applyCursorMove(target, extendSelection)
    }

    private fun applyTextTransform(
        block: (CharSequence, LineIndex, Int, Int) -> EditorTextTransforms.TextTransformResult?
    ) {
        val editable = editor.text ?: return
        val s = minOf(editor.selectionStart, editor.selectionEnd).coerceIn(0, editable.length)
        val e = maxOf(editor.selectionStart, editor.selectionEnd).coerceIn(0, editable.length)
        val res = block(editable, editor.lineIndex, s, e) ?: return

        activeUndoManager.breakCoalescing()
        editable.replace(res.replaceStart, res.replaceEnd, res.replacement)
        activeUndoManager.breakCoalescing()

        val len = editable.length
        val newA = res.newSelStart.coerceIn(0, len)
        val newB = res.newSelEnd.coerceIn(newA, len)
        selectionAnchor = newA
        activeCursorEnd = newB
        editor.setSelection(newA, newB)
        editor.requestFocus()
    }

    private fun doSelectNextMatch() {
        val editable = editor.text ?: return
        val match = EditorTextTransforms.selectNextOccurrence(editable, editor.selectionStart, editor.selectionEnd)
        if (match == null) {
            toast("Eşleşme bulunamadı")
            return
        }
        selectionAnchor = match.first
        activeCursorEnd = match.second
        editor.requestFocus()
        editor.setSelection(match.first, match.second)
        editor.post { highlightVisibleWindow(force = false) }
    }

    private fun copySelectionToClipboard(cut: Boolean) {
        val editable = editor.text ?: return
        val a = minOf(editor.selectionStart, editor.selectionEnd).coerceIn(0, editable.length)
        val b = maxOf(editor.selectionStart, editor.selectionEnd).coerceIn(0, editable.length)
        val range = if (b > a) {
            a to b
        } else {
            val curLine = editor.lineIndex.lineAtOffset(a)
            editor.lineIndex.lineStart(curLine) to editor.lineIndex.lineEnd(curLine, editable.length)
        }
        val textToCopy = editable.subSequence(range.first, range.second).toString()
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("code", textToCopy))
        if (cut && range.second > range.first) {
            activeUndoManager.breakCoalescing()
            editable.delete(range.first, range.second)
            activeUndoManager.breakCoalescing()
        } else {
            toast("Kopyalandı")
        }
    }

    private fun pasteFromClipboard() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = cm.primaryClip ?: return
        if (clip.itemCount <= 0) return
        val pasteText = clip.getItemAt(0).coerceToText(this)?.toString() ?: return
        if (pasteText.isNotEmpty()) {
            activeUndoManager.breakCoalescing()
            insertText(pasteText)
            activeUndoManager.breakCoalescing()
        }
    }

    // ---------- PC Tuş Çubuğu ----------

    @SuppressLint("ClickableViewAccessibility")
    private fun buildPcKeyBar() {
        val bar = findViewById<LinearLayout>(R.id.llPcKeys)
        bar.removeAllViews()

        val repeatableKeys = setOf(
            LogicalKey.LEFT, LogicalKey.UP, LogicalKey.DOWN, LogicalKey.RIGHT,
            LogicalKey.PGUP, LogicalKey.PGDN, LogicalKey.DEL
        )

        fun addModifierButton(label: String, mod: ModifierKey): Button {
            val btn = createKeyBarButton(label)
            btn.setOnClickListener {
                stickyModifiers.onModifierTapped(mod)
                refreshModifierButtonsUi()
            }
            bar.addView(btn)
            return btn
        }

        fun addKeyButton(key: LogicalKey) {
            val btn = createKeyBarButton(key.label)
            if (key in repeatableKeys) {
                btn.setOnTouchListener { v, ev ->
                    when (ev.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            v.isPressed = true
                            stopKeyRepeat()
                            handleLogicalKeyPress(key)
                            val r = object : Runnable {
                                override fun run() {
                                    if (v.isPressed && v.isShown) {
                                        handleLogicalKeyPress(key)
                                        handler.postDelayed(this, KEY_REPEAT_INTERVAL_MS)
                                    }
                                }
                            }
                            repeatRunnable = r
                            handler.postDelayed(r, KEY_REPEAT_INITIAL_DELAY_MS)
                            true
                        }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                            v.isPressed = false
                            stopKeyRepeat()
                            true
                        }
                        else -> false
                    }
                }
            } else {
                btn.setOnClickListener { handleLogicalKeyPress(key) }
            }
            bar.addView(btn)
        }

        addKeyButton(LogicalKey.ESC)
        addKeyButton(LogicalKey.TAB)
        btnCtrlView = addModifierButton("Ctrl", ModifierKey.CTRL)
        btnAltView = addModifierButton("Alt", ModifierKey.ALT)
        btnShiftView = addModifierButton("Shift", ModifierKey.SHIFT)

        addKeyButton(LogicalKey.LEFT)
        addKeyButton(LogicalKey.UP)
        addKeyButton(LogicalKey.DOWN)
        addKeyButton(LogicalKey.RIGHT)
        addKeyButton(LogicalKey.HOME)
        addKeyButton(LogicalKey.END)
        addKeyButton(LogicalKey.PGUP)
        addKeyButton(LogicalKey.PGDN)
        addKeyButton(LogicalKey.DEL)

        val fKeys = listOf(
            LogicalKey.F1, LogicalKey.F2, LogicalKey.F3, LogicalKey.F4,
            LogicalKey.F5, LogicalKey.F6, LogicalKey.F7, LogicalKey.F8,
            LogicalKey.F9, LogicalKey.F10, LogicalKey.F11, LogicalKey.F12
        )
        for (fk in fKeys) addKeyButton(fk)
        refreshModifierButtonsUi()
    }

    private fun stopKeyRepeat() {
        repeatRunnable?.let { handler.removeCallbacks(it) }
        repeatRunnable = null
    }

    private fun createKeyBarButton(label: String): Button {
        val dp = resources.displayMetrics.density
        return Button(this, null, android.R.attr.borderlessButtonStyle).apply {
            text = label
            isAllCaps = false
            typeface = Typeface.MONOSPACE
            textSize = 11.5f
            setTextColor(Color.WHITE)
            minWidth = 0
            minimumWidth = 0
            minHeight = 0
            minimumHeight = 0
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                (30 * dp).toInt()
            ).apply {
                marginStart = (2 * dp).toInt()
                marginEnd = (2 * dp).toInt()
            }
            layoutParams = lp
            setPadding((10 * dp).toInt(), 0, (10 * dp).toInt(), 0)
            background = keyButtonBackground(StickyState.OFF)
        }
    }

    private fun keyButtonBackground(state: StickyState): GradientDrawable {
        val dp = resources.displayMetrics.density
        return GradientDrawable().apply {
            cornerRadius = 6 * dp
            when (state) {
                StickyState.OFF -> setColor(Color.rgb(0x33, 0x41, 0x55))
                StickyState.ONE_SHOT -> {
                    setColor(Color.rgb(0x25, 0x63, 0xEB))
                    setStroke((1.5f * dp).toInt(), Color.rgb(0x93, 0xC5, 0xFD))
                }
                StickyState.LOCKED -> {
                    setColor(Color.rgb(0x05, 0x96, 0x69))
                    setStroke((1.5f * dp).toInt(), Color.rgb(0x6E, 0xE7, 0xB7))
                }
            }
        }
    }

    private fun refreshModifierButtonsUi() {
        fun updateBtn(btn: Button?, baseName: String, state: StickyState) {
            btn ?: return
            btn.text = when (state) {
                StickyState.OFF -> baseName
                StickyState.ONE_SHOT -> "$baseName•"
                StickyState.LOCKED -> "$baseName🔒"
            }
            btn.background = keyButtonBackground(state)
        }
        updateBtn(btnCtrlView, "Ctrl", stickyModifiers.ctrl)
        updateBtn(btnAltView, "Alt", stickyModifiers.alt)
        updateBtn(btnShiftView, "Shift", stickyModifiers.shift)

        val pos = editor.lineIndex.lineAndColumn(editor.selectionStart.coerceAtLeast(0))
        updateCursorAndModifierStatus(pos.line, pos.column)
    }

    private fun updateCursorAndModifierStatus(line: Int, col: Int) {
        val mods = mutableListOf<String>()
        if (stickyModifiers.ctrl.isActive) mods.add(if (stickyModifiers.ctrl == StickyState.LOCKED) "CTRL🔒" else "CTRL")
        if (stickyModifiers.alt.isActive) mods.add(if (stickyModifiers.alt == StickyState.LOCKED) "ALT🔒" else "ALT")
        if (stickyModifiers.shift.isActive) mods.add(if (stickyModifiers.shift == StickyState.LOCKED) "SHIFT🔒" else "SHIFT")
        val modSuffix = if (mods.isEmpty()) "" else " [${mods.joinToString("+")}]"
        tvCursorStatus.text = "Satır $line, Sütun $col$modSuffix"
    }

    // ---------- Komut Kaydı, Hızlı Dosya Aç, Komut Paleti, Projede Ara ----------

    private fun registerCommands() {
        for (action in KeyAction.entries) {
            commandRegistry.register(
                SearchAndCommandEngine.IdeCommand(
                    id = action.name,
                    titleTr = action.titleTr,
                    shortcutLabel = keybindingRegistry.getStroke(action).format(),
                    categoryTr = "Editör"
                ) { executeKeyAction(action) }
            )
        }
        commandRegistry.register(
            SearchAndCommandEngine.IdeCommand(
                id = "TOGGLE_SPLIT_VIEW",
                titleTr = "Yan Yana Çift Editör (Split View)",
                shortcutLabel = "◫",
                categoryTr = "Görünüm"
            ) { toggleSplitView() }
        )
        commandRegistry.register(
            SearchAndCommandEngine.IdeCommand(
                id = "TOGGLE_PC_KEYBAR",
                titleTr = "PC Tuş Çubuğunu Aç/Kapat",
                shortcutLabel = "⌨",
                categoryTr = "Görünüm"
            ) {
                hsvPcKeys.visibility = if (hsvPcKeys.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            }
        )
        commandRegistry.register(
            SearchAndCommandEngine.IdeCommand(
                id = "FORMAT_DOCUMENT",
                titleTr = "Kodu Biçimlendir (Java/XML)",
                shortcutLabel = "Shift+Alt+F",
                categoryTr = "Kod Zekası"
            ) { formatActiveDocument() }
        )
        commandRegistry.register(
            SearchAndCommandEngine.IdeCommand(
                id = "GO_TO_DEFINITION",
                titleTr = "Tanıma Git (Sınıf/Metot)",
                shortcutLabel = "F12",
                categoryTr = "Kod Zekası"
            ) { goToDefinitionAtCursor() }
        )
        commandRegistry.register(
            SearchAndCommandEngine.IdeCommand(
                id = "CODE_FOLDING",
                titleTr = "Kod Katlama Blokları",
                shortcutLabel = "{}",
                categoryTr = "Kod Zekası"
            ) { showCodeFoldingDialog() }
        )
        commandRegistry.register(
            SearchAndCommandEngine.IdeCommand(
                id = "TOGGLE_BOTTOM_PANEL",
                titleTr = "Alt Paneli Aç/Kapat (Problemler/Çıktı/Terminal/Logcat)",
                shortcutLabel = "▤",
                categoryTr = "Görünüm"
            ) { bottomPanelController.toggle() }
        )
        commandRegistry.register(
            SearchAndCommandEngine.IdeCommand(
                id = "OPEN_TERMINAL",
                titleTr = "Terminali Aç (/system/bin/sh)",
                shortcutLabel = ">_",
                categoryTr = "Panel"
            ) { bottomPanelController.showTab(BottomPanelController.Tab.TERMINAL) }
        )
        commandRegistry.register(
            SearchAndCommandEngine.IdeCommand(
                id = "OPEN_LOGCAT",
                titleTr = "Logcat Panelini Aç",
                shortcutLabel = "Log",
                categoryTr = "Panel"
            ) { bottomPanelController.showTab(BottomPanelController.Tab.LOGCAT) }
        )
        commandRegistry.register(
            SearchAndCommandEngine.IdeCommand(
                id = "OPEN_SOURCE_CONTROL",
                titleTr = "Kaynak Denetimi (Git: Commit/Push/Pull/Diff)",
                shortcutLabel = "⎇ Git",
                categoryTr = "Git"
            ) { openGitSourceControl() }
        )
        commandRegistry.register(
            SearchAndCommandEngine.IdeCommand(
                id = "XML_LAYOUT_PREVIEW",
                titleTr = "XML Düzen Önizleme (Layout Preview)",
                shortcutLabel = "👁 XML",
                categoryTr = "Android Araçları"
            ) { showXmlLayoutPreviewDialog() }
        )
        commandRegistry.register(
            SearchAndCommandEngine.IdeCommand(
                id = "THEME_PICKER",
                titleTr = "Tema Seç (Dark+, Light+, Monokai, Dracula, One Dark, AMOLED)",
                shortcutLabel = "🎨",
                categoryTr = "Ayarlar"
            ) { showThemePickerDialog() }
        )
        commandRegistry.register(
            SearchAndCommandEngine.IdeCommand(
                id = "TOGGLE_ZEN_MODE",
                titleTr = "Zen Modu (Tam Ekran Editör)",
                shortcutLabel = "⛶",
                categoryTr = "Görünüm"
            ) { toggleZenMode() }
        )
        commandRegistry.register(
            SearchAndCommandEngine.IdeCommand(
                id = "KEYBINDING_SETTINGS",
                titleTr = "Klavye Kısayolları Ayarları",
                shortcutLabel = "⚙",
                categoryTr = "Ayarlar"
            ) { showKeybindingSettingsDialog() }
        )
    }

    // ---------- Prompt 5: Otomatik Tamamlama, 800ms ECJ Analizi, Biçimlendirici, Kod Katlama, Tanıma Git ----------

    private fun triggerAutocompleteIfReady() {
        val editable = editor.text ?: return
        val cursor = editor.selectionStart.coerceIn(0, editable.length)
        val resDir = projectRoot?.let { File(it, "res") }
        val (tokenStart, candidates) = CodeIntelligenceEngine.computeCompletions(
            text = editable,
            cursorOffset = cursor,
            language = language,
            resDir = resDir,
            androidClasses = cachedAndroidJarClasses
        )
        if (candidates.isEmpty()) {
            completionPopup?.dismiss()
            return
        }
        lastCompletionStartOffset = tokenStart
        lastCompletionItems = candidates

        val popup = completionPopup ?: androidx.appcompat.widget.ListPopupWindow(this).apply {
            anchorView = editor
            isModal = false
            width = (260 * resources.displayMetrics.density).toInt()
            height = (200 * resources.displayMetrics.density).toInt()
            setOnItemClickListener { _, _, position, _ ->
                val chosen = lastCompletionItems.getOrNull(position) ?: return@setOnItemClickListener
                applyCompletion(chosen)
                dismiss()
            }
            completionPopup = this
        }
        val labels = candidates.map { "${it.label}   (${it.detail})" }
        popup.setAdapter(ArrayAdapter(this, android.R.layout.simple_list_item_1, labels))
        if (!popup.isShowing) {
            popup.show()
        }
    }

    private fun applyCompletion(item: CodeIntelligenceEngine.CompletionItem) {
        val editable = editor.text ?: return
        val cur = editor.selectionStart.coerceIn(0, editable.length)
        val start = lastCompletionStartOffset.coerceIn(0, cur)
        activeUndoManager.breakCoalescing()
        editable.replace(start, cur, item.insertText)
        val targetCursor = (start + item.insertText.length - item.cursorBackOffset).coerceIn(0, editable.length)
        editor.setSelection(targetCursor)
        activeUndoManager.breakCoalescing()
    }

    private fun runLiveEcjAnalysis() {
        val activeFile = file ?: return
        if (language != "java" || !loaded) return
        val sourceSnapshot = editor.text?.toString() ?: return
        if (sourceSnapshot.length > 500_000) return
        val fileName = activeFile.name
        val jarFile = File(filesDir, "sdk/android.jar")

        uiScope.launch {
            val diags = withContext(Dispatchers.IO) {
                CodeIntelligenceEngine.analyzeJavaWithEcj(sourceSnapshot, fileName, jarFile)
            }
            if (file?.name == fileName) {
                lastLiveDiagnostics = diags
                editor.setDiagnostics(diags)
                bottomPanelController.refreshIfVisible()
            }
        }
    }

    private fun formatActiveDocument() {
        val editable = editor.text ?: return
        val old = editable.toString()
        val formatted = CodeIntelligenceEngine.formatCode(old, language)
        if (formatted == old) {
            toast("Kod zaten düzgün biçimlendirilmiş")
            return
        }
        activeUndoManager.breakCoalescing()
        activeUndoManager.recordEdit(0, old, formatted)
        activeUndoManager.breakCoalescing()
        programmatic = true
        editor.setCodeText(formatted)
        programmatic = false
        activeStateCache.reset()
        setDirty(true)
        updateFileStats()
        highlightVisibleWindow(force = true)
        toast("✓ Kod biçimlendirildi")
    }

    private fun showCodeFoldingDialog() {
        val editable = editor.text ?: return
        val ranges = CodeIntelligenceEngine.computeFoldRanges(editable, language)
        if (ranges.isEmpty()) {
            toast("Katlanabilir blok bulunamadı")
            return
        }
        val labels = ranges.map { r ->
            "Satır ${r.startLineZeroBased + 1}..${r.endLineZeroBased + 1}: ${r.label}"
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Kod Blokları (${ranges.size})")
            .setItems(labels) { _, which ->
                gotoLine(ranges[which].startLineZeroBased + 1)
            }
            .setNegativeButton("Kapat", null)
            .show()
    }

    private fun goToDefinitionAtCursor() {
        val editable = editor.text ?: return
        val match = EditorTextTransforms.selectNextOccurrence(editable, editor.selectionStart, editor.selectionStart)
        val symbol = if (match != null) editable.subSequence(match.first, match.second).toString() else ""
        if (symbol.isBlank()) {
            toast("Tanıma gitmek için bir sınıf veya metot adı seçin")
            return
        }
        val currentText = editable.toString()
        uiScope.launch {
            val def = withContext(Dispatchers.IO) {
                CodeIntelligenceEngine.findDefinition(currentText, symbol, projectRoot)
            }
            if (def == null) {
                toast("'$symbol' tanımı projede bulunamadı")
                return@launch
            }
            if (def.file == null) {
                editor.errorHighlightLine = def.lineOneBased
                gotoLine(def.lineOneBased)
            } else {
                openFileInTab(def.file, def.lineOneBased)
            }
        }
    }

    private fun showXmlLayoutPreviewDialog() {
        val content = editor.text?.toString() ?: ""
        if (language != "xml") {
            toast("Önizleme yalnızca res/layout/*.xml dosyaları için çalışır")
            return
        }
        val res = ProjectTemplatesAndTools.XmlLayoutPreviewer.inflatePreview(this, content)
        res.onFailure { err ->
            AlertDialog.Builder(this)
                .setTitle("XML Önizleme Hatası")
                .setMessage(err.message ?: "XML ayrıştırılamadı")
                .setPositiveButton("Tamam", null)
                .show()
        }.onSuccess { previewView ->
            val frame = ScrollView(this).apply {
                setBackgroundColor(Color.WHITE)
                addView(previewView)
            }
            AlertDialog.Builder(this)
                .setTitle("XML Önizleme: ${file?.name ?: ""}")
                .setView(frame)
                .setPositiveButton("Kapat", null)
                .show()
        }
    }

    private fun showThemePickerDialog() {
        val themes = SettingsAndThemes.builtInThemes
        val names = themes.map { it.name }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Tema Seç (JSON Tabanlı)")
            .setItems(names) { _, which ->
                val chosen = themes[which]
                val cur = SettingsAndThemes.loadSettings(this)
                SettingsAndThemes.saveSettings(this, cur.copy(themeId = chosen.id))
                palette = chosen.toSyntaxPalette()
                editor.setBackgroundColor(SettingsAndThemes.parseHexColor(chosen.editorBg))
                editor.setTextColor(SettingsAndThemes.parseHexColor(chosen.editorFg))
                highlightVisibleWindow(force = true)
                toast("Tema uygulandı: ${chosen.name}")
            }
            .setNegativeButton("İptal", null)
            .show()
    }

    private var zenModeActive = false
    private fun toggleZenMode() {
        zenModeActive = !zenModeActive
        val vis = if (zenModeActive) View.GONE else View.VISIBLE
        findViewById<View>(R.id.hsvTabs).visibility = vis
        findViewById<View>(R.id.hsvBreadcrumb).visibility = vis
        hsvPcKeys.visibility = vis
        if (zenModeActive) {
            panelExplorer.visibility = View.GONE
            findViewById<View>(R.id.panelBottom).visibility = View.GONE
        }
        toast(if (zenModeActive) "Zen Modu Açık (Çıkmak için Komut Paleti)" else "Zen Modu Kapalı")
    }

    private fun showQuickOpenDialog() {
        EditorSearchSheets.showQuickOpenBottomSheet(
            context = this,
            uiScope = uiScope,
            projectRoot = projectRoot,
            onGotoLineInCurrent = { line ->
                editor.errorHighlightLine = line
                gotoLine(line)
            },
            onOpenFileAtLine = { f, line -> openFileInTab(f, line) }
        )
    }

    private fun showCommandPaletteDialog() {
        // Kısayol etiketlerini güncel tut
        registerCommands()
        EditorSearchSheets.showCommandPaletteBottomSheet(
            context = this,
            registry = commandRegistry,
            onOpenKeySettings = { showKeybindingSettingsDialog() }
        )
    }

    private fun showProjectSearchDialog() {
        syncActiveEditorToTabState()
        EditorSearchSheets.showProjectSearchBottomSheet(
            context = this,
            uiScope = uiScope,
            projectRoot = projectRoot,
            lastBackupProvider = { lastProjectReplaceBackup },
            onBackupUpdated = { lastProjectReplaceBackup = it },
            onOpenMatch = { targetFile, line -> openFileInTab(targetFile, line) },
            onFilesChangedOnDisk = {
                // Açık sekmelerin disk önbelleğini sıfırlayıp aktif sekmeyi yenile
                workspaceState.tabs.forEach { t ->
                    t.draftText = null
                    t.dirty = false
                }
                workspaceState.activeTab?.let { loadTabIntoPrimaryEditor(it, 0) }
            }
        )
    }

    private fun showKeybindingSettingsDialog() {
        val actions = KeyAction.entries
        val allConflicts = keybindingRegistry.findAllConflicts()
        val conflictStrokes = allConflicts.keys

        val dp = resources.displayMetrics.density
        val rootBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (14 * dp).toInt()
            setPadding(pad, pad / 2, pad, 0)
        }

        if (allConflicts.isNotEmpty()) {
            val warnView = TextView(this).apply {
                val desc = allConflicts.entries.joinToString("\n") { (stroke, acts) ->
                    "⚠ ${stroke.format()}: ${acts.joinToString(", ") { it.titleTr }}"
                }
                text = "Çakışan Kısayollar Tespit Edildi:\n$desc"
                setTextColor(Color.rgb(0xDC, 0x26, 0x26))
                textSize = 12f
                setPadding(0, 0, 0, (8 * dp).toInt())
            }
            rootBox.addView(warnView)
        }

        val scroll = ScrollView(this)
        val listLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(listLayout)
        rootBox.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (360 * dp).toInt()))

        var parentDialog: AlertDialog? = null
        for (action in actions) {
            val stroke = keybindingRegistry.getStroke(action)
            val isConflicting = stroke in conflictStrokes
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding((6 * dp).toInt(), (10 * dp).toInt(), (6 * dp).toInt(), (10 * dp).toInt())
                setOnClickListener {
                    parentDialog?.dismiss()
                    showRebindActionDialog(action)
                }
            }
            val tvName = TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                text = action.titleTr
                textSize = 13.5f
            }
            val tvStroke = TextView(this).apply {
                text = (if (isConflicting) "⚠ " else "") + stroke.format()
                typeface = Typeface.MONOSPACE
                textSize = 12.5f
                setTextColor(if (isConflicting) Color.rgb(0xDC, 0x26, 0x26) else Color.rgb(0x25, 0x63, 0xEB))
            }
            row.addView(tvName)
            row.addView(tvStroke)
            listLayout.addView(row)
        }

        parentDialog = AlertDialog.Builder(this)
            .setTitle("Klavye Kısayolları (Değiştirmek için dokun)")
            .setView(rootBox)
            .setNeutralButton("Varsayılanlara Sıfırla") { _, _ ->
                keybindingRegistry.resetToDefaults()
                keybindingRegistry.saveToPrefs(this)
                toast("Kısayollar varsayılanlara sıfırlandı")
                showKeybindingSettingsDialog()
            }
            .setPositiveButton("Tamam", null)
            .show()
    }

    private fun showRebindActionDialog(action: KeyAction) {
        val current = keybindingRegistry.getStroke(action)
        val dp = resources.displayMetrics.density
        val pad = (16 * dp).toInt()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }

        val cbCtrl = CheckBox(this).apply { text = "Ctrl"; isChecked = current.ctrl }
        val cbAlt = CheckBox(this).apply { text = "Alt"; isChecked = current.alt }
        val cbShift = CheckBox(this).apply { text = "Shift"; isChecked = current.shift }
        box.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(cbCtrl); addView(cbAlt); addView(cbShift)
        })

        val keys = LogicalKey.entries
        val spinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@EditorActivity, android.R.layout.simple_spinner_dropdown_item, keys.map { it.label })
            setSelection(keys.indexOf(current.key).coerceAtLeast(0))
        }
        box.addView(spinner)

        val tvConflictWarning = TextView(this).apply {
            textSize = 12.5f
            setPadding(0, (8 * dp).toInt(), 0, 0)
        }
        box.addView(tvConflictWarning)

        fun buildCandidate(): KeyStroke = KeyStroke(
            key = keys[spinner.selectedItemPosition.coerceIn(0, keys.size - 1)],
            ctrl = cbCtrl.isChecked,
            alt = cbAlt.isChecked,
            shift = cbShift.isChecked
        )

        fun updateWarning() {
            val candidate = buildCandidate()
            val conflicts = keybindingRegistry.findConflicts(action, candidate)
            if (conflicts.isEmpty()) {
                tvConflictWarning.text = "✓ Çakışma yok (${candidate.format()})"
                tvConflictWarning.setTextColor(Color.rgb(0x05, 0x96, 0x69))
            } else {
                tvConflictWarning.text = "⚠ Çakışma Uyarısı: '${candidate.format()}' kısayolu '${conflicts.joinToString(", ") { it.titleTr }}' tarafından kullanılıyor!"
                tvConflictWarning.setTextColor(Color.rgb(0xDC, 0x26, 0x26))
            }
        }

        cbCtrl.setOnCheckedChangeListener { _, _ -> updateWarning() }
        cbAlt.setOnCheckedChangeListener { _, _ -> updateWarning() }
        cbShift.setOnCheckedChangeListener { _, _ -> updateWarning() }
        spinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) = updateWarning()
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }
        updateWarning()

        AlertDialog.Builder(this)
            .setTitle("Kısayol Ata: ${action.titleTr}")
            .setView(box)
            .setPositiveButton("Kaydet") { _, _ ->
                val candidate = buildCandidate()
                keybindingRegistry.setStroke(action, candidate)
                keybindingRegistry.saveToPrefs(this)
                showKeybindingSettingsDialog()
            }
            .setNegativeButton("İptal") { _, _ -> showKeybindingSettingsDialog() }
            .show()
    }

    // ---------- Asenkron Sekme Yükleme / Kaydetme (Dispatchers.IO) ----------

    private fun loadTabIntoPrimaryEditor(tab: WorkspaceState.TabEntry, targetLine: Int) {
        val targetFile = File(tab.path)
        file = targetFile
        language = SyntaxHighlighter.languageFor(targetFile.name)
        tvTitle.text = (if (tab.dirty) "● " else "") + targetFile.name
        tvSubtitle.text = projectRoot?.name ?: (targetFile.parentFile?.name ?: "")
        renderTabs()
        renderBreadcrumbs()

        val cachedDraft = tab.draftText
        if (cachedDraft != null) {
            applyLoadedContentToEditor(tab, cachedDraft, targetLine)
            return
        }

        editor.isEnabled = false
        uiScope.launch {
            val result = withContext(Dispatchers.IO) {
                if (!targetFile.isFile) return@withContext Result.failure<Pair<String, Long>>(IllegalArgumentException("Dosya bulunamadı"))
                if (targetFile.length() > MAX_SIZE) {
                    return@withContext Result.failure<Pair<String, Long>>(IllegalArgumentException("Dosya çok büyük (en fazla 8 MB açılır)"))
                }
                val bytes = targetFile.readBytes()
                val probe = minOf(bytes.size, 4096)
                for (i in 0 until probe) {
                    if (bytes[i] == 0.toByte()) {
                        return@withContext Result.failure<Pair<String, Long>>(IllegalArgumentException("İkili (binary) dosya editörde açılamaz"))
                    }
                }
                Result.success(String(bytes, Charsets.UTF_8) to targetFile.lastModified())
            }

            result.onFailure { err ->
                editor.isEnabled = true
                toast(err.message ?: "Dosya açılamadı")
                return@launch
            }

            val (rawText, diskMod) = result.getOrThrow()
            tab.lineEnding = WorkspaceState.detectLineEnding(rawText)
            tab.indentLabel = WorkspaceState.detectIndentation(rawText)
            tab.lastModifiedOnDisk = diskMod
            val normalizedText = rawText.replace("\r\n", "\n")
            tab.draftText = normalizedText
            applyLoadedContentToEditor(tab, normalizedText, targetLine)
        }
    }

    private fun applyLoadedContentToEditor(tab: WorkspaceState.TabEntry, text: String, targetLine: Int) {
        programmatic = true
        editor.setCodeText(text)
        val safeCursor = tab.cursorOffset.coerceIn(0, text.length)
        editor.setSelection(safeCursor)
        programmatic = false

        selectionAnchor = safeCursor
        activeCursorEnd = safeCursor
        lastHighlightedWindow = IntRange.EMPTY
        loaded = true
        editor.isEnabled = true

        tvIndentStatus.text = tab.indentLabel
        tvEncodingStatus.text = tab.encoding
        tvEolStatus.text = tab.lineEnding
        updateFileStats()
        renderTabs()
        renderBreadcrumbs()

        editor.post {
            if (targetLine > 0) {
                editor.errorHighlightLine = targetLine
                gotoLine(targetLine)
            } else if (tab.scrollY > 0) {
                editor.scrollTo(0, tab.scrollY)
            }
            highlightVisibleWindow(force = true)
        }
    }

    private fun saveAsync(showToast: Boolean, onSaved: (() -> Unit)? = null) {
        if (!loaded) { onSaved?.invoke(); return }
        syncActiveEditorToTabState()
        val dirtyTabs = workspaceState.tabs.filter { it.dirty }
        if (dirtyTabs.isEmpty()) {
            val active = workspaceState.activeTab
            if (active != null) {
                saveTabToDiskAsync(active, showToast = showToast, onSaved = onSaved)
            } else {
                onSaved?.invoke()
            }
            return
        }
        uiScope.launch {
            val err = withContext(Dispatchers.IO) {
                try {
                    for (t in dirtyTabs) {
                        val content = t.draftText ?: continue
                        val outText = if (t.lineEnding == "CRLF") content.replace("\n", "\r\n") else content
                        val target = File(t.path)
                        target.writeText(outText, Charsets.UTF_8)
                        t.lastModifiedOnDisk = target.lastModified()
                        t.dirty = false
                    }
                    null
                } catch (e: Exception) {
                    e
                }
            }
            if (err == null) {
                setDirty(false)
                renderTabs()
                if (showToast) toast("✓ Kaydedildi")
                onSaved?.invoke()
            } else {
                toast("Kaydedilemedi: ${err.message}")
            }
        }
    }

    private fun saveTabToDiskAsync(tab: WorkspaceState.TabEntry, showToast: Boolean = false, onSaved: (() -> Unit)? = null) {
        val content = tab.draftText ?: (if (tab.path == workspaceState.activeTabPath) editor.text?.toString() else null) ?: return
        uiScope.launch {
            val err = withContext(Dispatchers.IO) {
                try {
                    val outText = if (tab.lineEnding == "CRLF") content.replace("\n", "\r\n") else content
                    val target = File(tab.path)
                    target.writeText(outText, Charsets.UTF_8)
                    tab.lastModifiedOnDisk = target.lastModified()
                    tab.dirty = false
                    null
                } catch (e: Exception) {
                    e
                }
            }
            if (err == null) {
                if (tab.path == workspaceState.activeTabPath) setDirty(false)
                renderTabs()
                if (showToast) toast("✓ Kaydedildi")
                onSaved?.invoke()
            } else {
                toast("Kaydedilemedi: ${err.message}")
            }
        }
    }

    private fun setDirty(d: Boolean) {
        val tab = workspaceState.activeTab
        if (tab != null) {
            tab.dirty = d
        }
        val name = file?.name ?: "Editör"
        tvTitle.text = (if (d) "● " else "") + name
        renderTabs()
    }

    private fun updateFileStats() {
        val len = editor.text?.length ?: 0
        val lines = editor.lineIndex.lineCount
        val langLabel = language.uppercase().ifEmpty { "TEXT" }
        tvFileStats.text = "$langLabel • $lines satır • $len krk"
    }

    override fun onPause() {
        super.onPause()
        stopKeyRepeat()
        saveWorkspaceSession()
        val dirtyTabs = workspaceState.tabs.filter { it.dirty && it.draftText != null }
        if (dirtyTabs.isNotEmpty()) {
            val snapshots = dirtyTabs.map { Triple(File(it.path), it.draftText!!, it.lineEnding) }
            dirtyTabs.forEach { it.dirty = false }
            Thread {
                for ((target, content, eol) in snapshots) {
                    try {
                        val out = if (eol == "CRLF") content.replace("\n", "\r\n") else content
                        target.writeText(out, Charsets.UTF_8)
                        workspaceState.updateDiskTimestamp(target.absolutePath, target.lastModified())
                    } catch (_: Exception) {}
                }
            }.start()
        }
    }

    override fun onDestroy() {
        stopKeyRepeat()
        handler.removeCallbacksAndMessages(null)
        uiScope.cancel()
        super.onDestroy()
    }

    // ---------- Pencereli (Windowed +/- 50 Satır) Renklendirme ----------

    private fun windowCovers(window: IntRange, firstVisible: Int, lastVisible: Int): Boolean {
        if (window.isEmpty()) return false
        val margin = LineIndex.DEFAULT_BUFFER_LINES / 3
        val minNeeded = (firstVisible - margin).coerceAtLeast(0)
        val maxNeeded = (lastVisible + margin).coerceAtMost((editor.lineIndex.lineCount - 1).coerceAtLeast(0))
        return window.first <= minNeeded && window.last >= maxNeeded
    }

    private fun highlightVisibleWindow(force: Boolean) {
        if (!loaded) return
        val (first, last) = editor.getVisibleLineBounds()
        if (!force && windowCovers(lastHighlightedWindow, first, last)) return

        val res = SyntaxHighlighter.highlightWindow(
            editable = editor.text,
            lineIndex = editor.lineIndex,
            language = language,
            palette = palette,
            stateCache = activeStateCache,
            firstVisibleLine = first,
            lastVisibleLine = last,
            bufferLines = LineIndex.DEFAULT_BUFFER_LINES
        )
        if (res != null) {
            lastHighlightedWindow = res.lineRange
        }
    }

    // ---------- Değişim (Delta) Tabanlı Geri Al / Yinele ----------

    private fun applyOp(op: UndoManager.ApplyOp) {
        val editable = editor.text ?: return
        programmatic = true
        try {
            val safeStart = op.start.coerceIn(0, editable.length)
            val safeEnd = op.end.coerceIn(safeStart, editable.length)
            editable.replace(safeStart, safeEnd, op.replacement)
            val sel = op.selectionAfter.coerceIn(0, editable.length)
            selectionAnchor = sel
            activeCursorEnd = sel
            editor.setSelection(sel)
        } finally {
            programmatic = false
        }
        val changedLine = editor.lineIndex.lineAtOffset(op.start)
        activeStateCache.invalidateFromLine(changedLine)
        setDirty(true)
        updateFileStats()
        highlightVisibleWindow(force = true)
    }

    private fun doUndo() {
        val op = activeUndoManager.undo()
        if (op == null) { toast("Geri alınacak değişiklik yok"); return }
        applyOp(op)
    }

    private fun doRedo() {
        val op = activeUndoManager.redo()
        if (op == null) { toast("Yinelenecek değişiklik yok"); return }
        applyOp(op)
    }

    // ---------- Akıllı Girdi Filtresi ----------

    private val smartInputFilter = InputFilter { source, start, end, dest, dstart, _ ->
        if (programmatic || !loaded) return@InputFilter null

        if (end - start == 1 && (stickyModifiers.ctrl.isActive || stickyModifiers.alt.isActive)) {
            val ch = source[start]
            val mappedKey = mapCharToLogicalKey(ch)
            if (mappedKey != null) {
                handler.post { handleLogicalKeyPress(mappedKey) }
                return@InputFilter ""
            }
        }

        if (end - start == 1 && source[start] == '\n') {
            val lineStart = dest.lastIndexOf('\n', dstart - 1) + 1
            val prefix = dest.subSequence(lineStart, dstart)
            val indent = StringBuilder()
            for (ch in prefix) {
                if (ch == ' ' || ch == '\t') indent.append(ch) else break
            }
            val opensBlock = dstart > 0 && (dest[dstart - 1] == '{' || dest[dstart - 1] == ':')
            if (opensBlock) indent.append("    ")
            return@InputFilter "\n" + indent
        }
        null
    }

    private fun mapCharToLogicalKey(ch: Char): LogicalKey? = when (ch.lowercaseChar()) {
        'a' -> LogicalKey.A; 'b' -> LogicalKey.B; 'c' -> LogicalKey.C; 'd' -> LogicalKey.D
        'e' -> LogicalKey.E; 'f' -> LogicalKey.F; 'g' -> LogicalKey.G; 'h' -> LogicalKey.H
        'i' -> LogicalKey.I; 'j' -> LogicalKey.J; 'k' -> LogicalKey.K; 'l' -> LogicalKey.L
        'm' -> LogicalKey.M; 'n' -> LogicalKey.N; 'o' -> LogicalKey.O; 'p' -> LogicalKey.P
        'q' -> LogicalKey.Q; 'r' -> LogicalKey.R; 's' -> LogicalKey.S; 't' -> LogicalKey.T
        'u' -> LogicalKey.U; 'v' -> LogicalKey.V; 'w' -> LogicalKey.W; 'x' -> LogicalKey.X
        'y' -> LogicalKey.Y; 'z' -> LogicalKey.Z
        '/' -> LogicalKey.SLASH; '[' -> LogicalKey.LEFT_BRACKET; ']' -> LogicalKey.RIGHT_BRACKET
        '\t' -> LogicalKey.TAB
        else -> null
    }

    // ---------- Sembol Çubuğu & Şablonlar ----------

    private fun buildSymbolBar() {
        val bar = findViewById<LinearLayout>(R.id.llSymbols)
        val syms = listOf(
            "Tab", "{", "}", "(", ")", "[", "]", ";", "\"", "'", "=",
            "<", ">", "/", "!", "&", "|", "+", "-", "*", ":", ",", ".", "_"
        )
        for (s in syms) {
            val b = Button(this, null, android.R.attr.borderlessButtonStyle).apply {
                text = s
                isAllCaps = false
                typeface = Typeface.MONOSPACE
                setTextColor(Color.WHITE)
                minWidth = 0
                minimumWidth = 0
                setPadding(28, 0, 28, 0)
                setOnClickListener {
                    when (s) {
                        "Tab" -> handleLogicalKeyPress(LogicalKey.TAB)
                        "{" -> insertPaired("{", "}")
                        "(" -> insertPaired("(", ")")
                        "[" -> if (stickyModifiers.ctrl.isActive || stickyModifiers.alt.isActive) handleLogicalKeyPress(LogicalKey.LEFT_BRACKET) else insertPaired("[", "]")
                        "]" -> if (stickyModifiers.ctrl.isActive || stickyModifiers.alt.isActive) handleLogicalKeyPress(LogicalKey.RIGHT_BRACKET) else insertText("]")
                        "/" -> if (stickyModifiers.ctrl.isActive || stickyModifiers.alt.isActive) handleLogicalKeyPress(LogicalKey.SLASH) else insertText("/")
                        "\"" -> insertPaired("\"", "\"")
                        else -> insertText(s)
                    }
                }
            }
            bar.addView(b)
        }
    }

    private fun insertText(t: String) {
        val editable = editor.text ?: return
        val a = minOf(editor.selectionStart, editor.selectionEnd).coerceAtLeast(0)
        val b = maxOf(editor.selectionStart, editor.selectionEnd).coerceAtLeast(0)
        editable.replace(a, b, t)
    }

    private fun insertPaired(open: String, close: String) {
        val editable = editor.text ?: return
        val a = minOf(editor.selectionStart, editor.selectionEnd).coerceAtLeast(0)
        val b = maxOf(editor.selectionStart, editor.selectionEnd).coerceAtLeast(0)
        if (b > a) {
            val selected = editable.subSequence(a, b)
            editable.replace(a, b, "$open$selected$close")
        } else {
            editable.replace(a, b, "$open$close")
            editor.setSelection((a + open.length).coerceAtMost(editable.length))
        }
    }

    private fun showSnippetsDialog() {
        val snippets: List<Pair<String, String>> = if (language == "xml") {
            listOf(
                "TextView (Metin Alanı)" to """<TextView
    android:layout_width="wrap_content"
    android:layout_height="wrap_content"
    android:text="Merhaba"
    android:textSize="16sp" />""",
                "Button (Düğme)" to """<Button
    android:id="@+id/btnAction"
    android:layout_width="wrap_content"
    android:layout_height="wrap_content"
    android:text="Tıkla" />""",
                "LinearLayout (Dikey Kapsayıcı)" to """<LinearLayout
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:orientation="vertical"
    android:padding="16dp">

</LinearLayout>"""
            )
        } else {
            listOf(
                "Toast Mesajı Göster" to """Toast.makeText(this, "Merhaba!", Toast.LENGTH_SHORT).show();""",
                "Button OnClickListener" to """findViewById(R.id.btnAction).setOnClickListener(new View.OnClickListener() {
    @Override
    public void onClick(View v) {
        
    }
});""",
                "Intent (Yeni Ekran Aç)" to """Intent intent = new Intent(this, MainActivity.class);
startActivity(intent);"""
            )
        }
        AlertDialog.Builder(this)
            .setTitle("Hızlı Kod Şablonu Ekle")
            .setItems(snippets.map { it.first }.toTypedArray()) { _, which ->
                activeUndoManager.breakCoalescing()
                insertText(snippets[which].second)
            }
            .setNegativeButton("İptal", null)
            .show()
    }

    // ---------- Satıra Git & Ara/Değiştir ----------

    private fun showGotoDialog() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            imeOptions = EditorInfo.IME_ACTION_DONE
            hint = "Satır numarası (1..${editor.lineIndex.lineCount})"
        }
        AlertDialog.Builder(this)
            .setTitle("Satıra Git (Ctrl+G)")
            .setView(input)
            .setPositiveButton("Git") { _, _ ->
                val n = input.text.toString().toIntOrNull()
                if (n != null) {
                    editor.errorHighlightLine = n
                    gotoLine(n)
                }
            }
            .setNegativeButton("İptal", null)
            .show()
    }

    private fun gotoLine(n: Int) {
        val total = editor.lineIndex.lineCount
        if (total <= 0) return
        val idx = (n - 1).coerceIn(0, total - 1)
        val textLen = editor.text?.length ?: 0
        val lineStart = editor.lineIndex.lineStart(idx).coerceIn(0, textLen)
        val lineEnd = editor.lineIndex.lineEnd(idx, textLen).coerceIn(lineStart, textLen)
        activeUndoManager.breakCoalescing()
        selectionAnchor = lineStart
        activeCursorEnd = lineEnd
        editor.requestFocus()
        editor.setSelection(lineStart, lineEnd)
        editor.post { highlightVisibleWindow(force = true) }
    }

    // ---------- Kalıcı Dosya İçi Ara / Değiştir Çubuğu ----------

    private fun setupFindBar() {
        panelFindBar = findViewById(R.id.panelFindBar)
        llReplaceRow = findViewById(R.id.llReplaceRow)
        etFindQuery = findViewById(R.id.etFindQuery)
        etReplaceText = findViewById(R.id.etReplaceText)
        tvFindCounter = findViewById(R.id.tvFindCounter)
        btnFindCase = findViewById(R.id.btnFindCase)
        btnFindWord = findViewById(R.id.btnFindWord)
        btnFindRegex = findViewById(R.id.btnFindRegex)

        fun updateToggleStyle(btn: Button, active: Boolean) {
            btn.setTextColor(if (active) Color.rgb(0x38, 0xBD, 0xF8) else Color.rgb(0x94, 0xA3, 0xB8))
            btn.setBackgroundColor(if (active) Color.rgb(0x1E, 0x3A, 0x5F) else Color.TRANSPARENT)
        }

        btnFindCase.setOnClickListener {
            findCaseSensitive = !findCaseSensitive
            updateToggleStyle(btnFindCase, findCaseSensitive)
            refreshFindMatches(jumpToNearest = false)
        }
        btnFindWord.setOnClickListener {
            findWholeWord = !findWholeWord
            updateToggleStyle(btnFindWord, findWholeWord)
            refreshFindMatches(jumpToNearest = false)
        }
        btnFindRegex.setOnClickListener {
            findUseRegex = !findUseRegex
            updateToggleStyle(btnFindRegex, findUseRegex)
            refreshFindMatches(jumpToNearest = false)
        }

        findViewById<Button>(R.id.btnFindPrev).setOnClickListener { stepFindMatch(forward = false) }
        findViewById<Button>(R.id.btnFindNext).setOnClickListener { stepFindMatch(forward = true) }
        findViewById<Button>(R.id.btnFindClose).setOnClickListener { closeFindBar() }
        findViewById<Button>(R.id.btnReplaceOne).setOnClickListener { replaceCurrentMatch() }
        findViewById<Button>(R.id.btnReplaceAll).setOnClickListener { replaceAllInCurrentFile() }

        etFindQuery.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) = refreshFindMatches(jumpToNearest = true)
        })
    }

    private fun openFindBar(showReplace: Boolean) {
        panelFindBar.visibility = View.VISIBLE
        llReplaceRow.visibility = if (showReplace) View.VISIBLE else View.GONE

        val editable = editor.text
        if (editable != null) {
            val selA = minOf(editor.selectionStart, editor.selectionEnd).coerceIn(0, editable.length)
            val selB = maxOf(editor.selectionStart, editor.selectionEnd).coerceIn(0, editable.length)
            if (selB > selA && selB - selA <= 200) {
                val sel = editable.subSequence(selA, selB).toString()
                if (!sel.contains('\n')) etFindQuery.setText(sel)
            }
        }
        if (showReplace && etFindQuery.text.isNotEmpty()) {
            etReplaceText.requestFocus()
        } else {
            etFindQuery.requestFocus()
        }
        refreshFindMatches(jumpToNearest = false)
    }

    private fun closeFindBar() {
        panelFindBar.visibility = View.GONE
        currentFindMatches = emptyList()
        currentFindMatchIndex = -1
        clearFindMatchSpans()
        editor.requestFocus()
    }

    private fun currentFindOptions(): SearchAndCommandEngine.SearchOptions =
        SearchAndCommandEngine.SearchOptions(
            query = etFindQuery.text?.toString() ?: "",
            caseSensitive = findCaseSensitive,
            wholeWord = findWholeWord,
            useRegex = findUseRegex
        )

    private fun clearFindMatchSpans() {
        val editable = editor.text ?: return
        for (sp in editable.getSpans(0, editable.length, FindMatchSpan::class.java)) {
            editable.removeSpan(sp)
        }
    }

    private fun refreshFindMatches(jumpToNearest: Boolean) {
        clearFindMatchSpans()
        val editable = editor.text ?: return
        val opts = currentFindOptions()
        if (opts.query.isEmpty()) {
            currentFindMatches = emptyList()
            currentFindMatchIndex = -1
            tvFindCounter.text = "0/0"
            return
        }

        val matches = SearchAndCommandEngine.findMatchesInText(editable, opts)
        currentFindMatches = matches
        if (matches.isEmpty()) {
            currentFindMatchIndex = -1
            tvFindCounter.text = "0/0"
            return
        }

        val curPos = editor.selectionStart.coerceAtLeast(0)
        if (jumpToNearest || currentFindMatchIndex !in matches.indices) {
            currentFindMatchIndex = matches.indexOfFirst { it.startOffset >= curPos }.let { if (it < 0) 0 else it }
        }
        applyFindHighlights()
        if (jumpToNearest) {
            focusCurrentFindMatch()
        }
    }

    private fun applyFindHighlights() {
        clearFindMatchSpans()
        val editable = editor.text ?: return
        val len = editable.length
        tvFindCounter.text = if (currentFindMatches.isEmpty()) {
            "0/0"
        } else {
            "${currentFindMatchIndex + 1}/${currentFindMatches.size}"
        }
        for ((idx, m) in currentFindMatches.withIndex()) {
            val s = m.startOffset.coerceIn(0, len)
            val e = m.endOffset.coerceIn(s, len)
            if (e > s) {
                val color = if (idx == currentFindMatchIndex) 0x8838BDF8.toInt() else 0x55F59E0B.toInt()
                editable.setSpan(FindMatchSpan(color), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
    }

    private fun stepFindMatch(forward: Boolean) {
        if (currentFindMatches.isEmpty()) {
            refreshFindMatches(jumpToNearest = true)
            return
        }
        currentFindMatchIndex = if (forward) {
            (currentFindMatchIndex + 1) % currentFindMatches.size
        } else {
            (currentFindMatchIndex - 1 + currentFindMatches.size) % currentFindMatches.size
        }
        applyFindHighlights()
        focusCurrentFindMatch()
    }

    private fun focusCurrentFindMatch() {
        val m = currentFindMatches.getOrNull(currentFindMatchIndex) ?: return
        val len = editor.text?.length ?: 0
        val s = m.startOffset.coerceIn(0, len)
        val e = m.endOffset.coerceIn(s, len)
        selectionAnchor = s
        activeCursorEnd = e
        editor.setSelection(s, e)
        editor.post { highlightVisibleWindow(force = false) }
    }

    private fun replaceCurrentMatch() {
        val editable = editor.text ?: return
        val m = currentFindMatches.getOrNull(currentFindMatchIndex) ?: run {
            refreshFindMatches(jumpToNearest = true)
            return
        }
        val repl = etReplaceText.text?.toString() ?: ""
        val s = m.startOffset.coerceIn(0, editable.length)
        val e = m.endOffset.coerceIn(s, editable.length)
        activeUndoManager.breakCoalescing()
        editable.replace(s, e, repl)
        activeUndoManager.breakCoalescing()
        refreshFindMatches(jumpToNearest = true)
    }

    private fun replaceAllInCurrentFile() {
        val opts = currentFindOptions()
        if (opts.query.isEmpty()) return
        val old = editor.text?.toString() ?: ""
        val repl = etReplaceText.text?.toString() ?: ""
        val (nu, count) = SearchAndCommandEngine.replaceAllInText(old, opts, repl)
        if (count == 0) {
            toast("Bulunamadı")
            return
        }

        activeUndoManager.breakCoalescing()
        activeUndoManager.recordEdit(0, old, nu)
        activeUndoManager.breakCoalescing()

        programmatic = true
        editor.setCodeText(nu)
        programmatic = false

        activeStateCache.reset()
        setDirty(true)
        updateFileStats()
        highlightVisibleWindow(force = true)
        refreshFindMatches(jumpToNearest = false)
        toast("$count eşleşme değiştirildi")
    }

    private fun recordRecentFile(path: String) {
        val prefs = getSharedPreferences("ide_recent_files", Context.MODE_PRIVATE)
        val current = prefs.getString("list", "")?.split('\n')?.filter { it.isNotBlank() }?.toMutableList() ?: mutableListOf()
        current.remove(path)
        current.add(0, path)
        while (current.size > 12) current.removeLast()
        prefs.edit().putString("list", current.joinToString("\n")).apply()
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()

    // ---------- Explorer Ağaç Adaptörü ----------

    private class ExplorerTreeAdapter(ctx: Context) : ArrayAdapter<WorkspaceState.TreeNode>(ctx, 0) {
        private val dp = ctx.resources.displayMetrics.density
        var activePath: String? = null
        var projectRootPath: String = ""
        var gitFileStates: Map<String, GitManager.FileGitState> = emptyMap()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val tv = (convertView as? TextView) ?: TextView(context).apply {
                textSize = 12f
                typeface = Typeface.MONOSPACE
                setSingleLine(true)
            }
            val node = getItem(position)!!
            val indentPx = ((8 + node.depth * 14) * dp).toInt()
            val vPad = (7 * dp).toInt()
            tv.setPadding(indentPx, vPad, (8 * dp).toInt(), vPad)

            val icon = when {
                node.isDirectory && node.isExpanded -> "▾ 📁 "
                node.isDirectory -> "▸ 📁 "
                node.name.endsWith(".java", ignoreCase = true) -> "☕ "
                node.name.endsWith(".xml", ignoreCase = true) -> "🧩 "
                else -> "📄 "
            }
            val relPath = node.path.removePrefix(projectRootPath).trimStart('/')
            val gitState = if (!node.isDirectory) gitFileStates[relPath] else null
            val badgeSuffix = if (gitState != null) "  [${gitState.badge}]" else ""
            tv.text = "$icon${node.name}$badgeSuffix"

            val isActive = !node.isDirectory && node.path == activePath
            tv.setBackgroundColor(if (isActive) Color.rgb(0x1E, 0x3A, 0x5F) else Color.TRANSPARENT)
            tv.setTextColor(
                when {
                    isActive -> Color.WHITE
                    gitState == GitManager.FileGitState.MODIFIED -> Color.rgb(0x60, 0xA5, 0xFA)
                    gitState == GitManager.FileGitState.ADDED || gitState == GitManager.FileGitState.UNTRACKED -> Color.rgb(0x34, 0xD3, 0x99)
                    gitState == GitManager.FileGitState.DELETED -> Color.rgb(0xF8, 0x71, 0x71)
                    node.isDirectory -> Color.rgb(0x93, 0xC5, 0xFD)
                    else -> Color.rgb(0xCB, 0xD5, 0xE1)
                }
            )
            return tv
        }
    }

    companion object {
        const val EXTRA_PATH = "path"
        const val EXTRA_PROJECT = "project"
        const val EXTRA_LINE = "line"
        private const val PREFS_WORKSPACE = "ide_workspace_sessions"
        private const val MAX_SIZE = 8 * 1024 * 1024L
        private const val KEY_REPEAT_INITIAL_DELAY_MS = 350L
        private const val KEY_REPEAT_INTERVAL_MS = 55L
    }
}
