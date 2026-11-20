package com.miniide.app.workspace

import com.miniide.app.editor.SyntaxHighlighter
import com.miniide.app.editor.UndoManager
import java.io.File

/**
 * VS Code benzeri çalışma alanı (Workspace) durum yöneticisi:
 * - Çoklu sekme (açma, kapama, diğerlerini kapatma, sıralama, aktif sekme seçimi)
 * - Her sekme için imleç konumu, kaydırma (scrollY), kaydedilmemiş (dirty) durumu,
 *   bellek içi taslak metin (draftText), UndoManager ve LineStateCache
 * - Oturum kaydetme ve geri yükleme (serializeSession / restoreSession)
 * - Dışarıdan dosya değişimi tespiti (lastModifiedOnDisk karşılaştırması)
 * - Yan panel (Explorer) ağaç görünümü düğüm hesaplaması
 */
class WorkspaceState {

    data class TabEntry(
        val path: String,
        val name: String,
        var dirty: Boolean = false,
        var cursorOffset: Int = 0,
        var scrollY: Int = 0,
        var lastModifiedOnDisk: Long = 0L,
        var lineEnding: String = "LF",
        var encoding: String = "UTF-8",
        var indentLabel: String = "Boşluk:4",
        var draftText: String? = null,
        val undoManager: UndoManager = UndoManager(),
        val stateCache: SyntaxHighlighter.LineStateCache = SyntaxHighlighter.LineStateCache()
    )

    data class TreeNode(
        val file: File,
        val path: String,
        val name: String,
        val depth: Int,
        val isDirectory: Boolean,
        val isExpanded: Boolean
    )

    private val tabList = ArrayList<TabEntry>()
    val expandedDirs = LinkedHashSet<String>()

    var activeTabPath: String? = null
        private set

    var secondarySplitPath: String? = null
        private set

    var splitViewEnabled: Boolean = false
        private set

    var gitBranchName: String = "main"

    val tabs: List<TabEntry>
        get() = tabList.toList()

    val activeTab: TabEntry?
        get() = activeTabPath?.let { p -> tabList.firstOrNull { it.path == p } }

    val secondaryTab: TabEntry?
        get() = secondarySplitPath?.let { p -> tabList.firstOrNull { it.path == p } }

    fun getTab(path: String): TabEntry? {
        val norm = normalizePath(path)
        return tabList.firstOrNull { it.path == norm }
    }

    /**
     * Dosyayı sekmede açar ve aktif sekme yapar.
     * Zaten açıksa mevcut sekmeyi aktif hale getirir.
     */
    fun openTab(
        path: String,
        cursorOffset: Int = -1,
        lastModifiedOnDisk: Long = 0L
    ): TabEntry {
        val norm = normalizePath(path)
        val existing = tabList.firstOrNull { it.path == norm }
        if (existing != null) {
            activeTabPath = existing.path
            if (cursorOffset >= 0) {
                existing.cursorOffset = cursorOffset
            }
            if (lastModifiedOnDisk > 0L && existing.lastModifiedOnDisk == 0L) {
                existing.lastModifiedOnDisk = lastModifiedOnDisk
            }
            return existing
        }

        val name = norm.substringAfterLast('/')
        val entry = TabEntry(
            path = norm,
            name = name,
            dirty = false,
            cursorOffset = cursorOffset.coerceAtLeast(0),
            scrollY = 0,
            lastModifiedOnDisk = lastModifiedOnDisk
        )
        tabList.add(entry)
        activeTabPath = entry.path
        return entry
    }

    /**
     * Belirtilen sekmeyi kapatır. Aktif sekme kapatıldıysa komşu sekmeyi aktif yapar.
     */
    fun closeTab(path: String): TabEntry? {
        val norm = normalizePath(path)
        val idx = tabList.indexOfFirst { it.path == norm }
        if (idx < 0) return activeTab

        tabList.removeAt(idx)
        if (secondarySplitPath == norm) {
            secondarySplitPath = null
            splitViewEnabled = false
        }
        if (activeTabPath == norm) {
            activeTabPath = if (tabList.isEmpty()) {
                null
            } else {
                val nextIdx = idx.coerceIn(0, tabList.size - 1)
                tabList[nextIdx].path
            }
        }
        return activeTab
    }

    /** Uzun basma menüsü: Belirtilen sekme dışındaki tüm sekmeleri kapatır. */
    fun closeOtherTabs(keepPath: String): TabEntry? {
        val norm = normalizePath(keepPath)
        val keep = tabList.firstOrNull { it.path == norm } ?: return activeTab
        tabList.clear()
        tabList.add(keep)
        activeTabPath = keep.path
        if (secondarySplitPath != null && secondarySplitPath != keep.path) {
            secondarySplitPath = null
            splitViewEnabled = false
        }
        return keep
    }

    /** Kaydedilmiş (dirty == false) olan tüm sekmeleri kapatır. */
    fun closeSavedTabs(): TabEntry? {
        tabList.removeAll { !it.dirty }
        if (activeTabPath != null && tabList.none { it.path == activeTabPath }) {
            activeTabPath = tabList.lastOrNull()?.path
        }
        if (secondarySplitPath != null && tabList.none { it.path == secondarySplitPath }) {
            secondarySplitPath = null
            splitViewEnabled = false
        }
        return activeTab
    }

    fun closeAllTabs() {
        tabList.clear()
        activeTabPath = null
        secondarySplitPath = null
        splitViewEnabled = false
    }

    /** Sekme sırasını değiştirir (fromIndex -> toIndex). */
    fun reorderTab(fromIndex: Int, toIndex: Int): Boolean {
        if (fromIndex !in tabList.indices || toIndex !in tabList.indices) return false
        if (fromIndex == toIndex) return true
        val item = tabList.removeAt(fromIndex)
        tabList.add(toIndex, item)
        return true
    }

    /** Ctrl+Tab ile sonraki sekmeye geçiş yapar. */
    fun selectNextTab(): TabEntry? {
        if (tabList.isEmpty()) return null
        val curIdx = tabList.indexOfFirst { it.path == activeTabPath }
        val nextIdx = if (curIdx < 0) 0 else (curIdx + 1) % tabList.size
        activeTabPath = tabList[nextIdx].path
        return tabList[nextIdx]
    }

    /** Yan yana çift editör (Split View) durumunu ayarlar. */
    fun openInSecondarySplit(path: String) {
        val norm = normalizePath(path)
        if (tabList.none { it.path == norm }) {
            openTab(norm)
        }
        secondarySplitPath = norm
        splitViewEnabled = true
    }

    fun closeSecondarySplit() {
        secondarySplitPath = null
        splitViewEnabled = false
    }

    fun setDirty(path: String, dirty: Boolean) {
        getTab(path)?.dirty = dirty
    }

    fun updateCursorAndScroll(path: String, cursorOffset: Int, scrollY: Int) {
        getTab(path)?.let {
            it.cursorOffset = cursorOffset.coerceAtLeast(0)
            it.scrollY = scrollY.coerceAtLeast(0)
        }
    }

    /**
     * Dosyanın editör dışında değiştirilip değiştirilmediğini kontrol eder.
     */
    fun isExternallyModified(path: String, currentDiskLastModified: Long): Boolean {
        val tab = getTab(path) ?: return false
        if (tab.lastModifiedOnDisk <= 0L || currentDiskLastModified <= 0L) return false
        return currentDiskLastModified > tab.lastModifiedOnDisk
    }

    fun updateDiskTimestamp(path: String, lastModified: Long) {
        getTab(path)?.lastModifiedOnDisk = lastModified
    }

    // ---------- Oturum Kaydetme / Geri Yükleme ----------

    /**
     * Açık sekmeleri, imleç/kaydırma konumlarını ve aktif sekmeyi metin olarak serileştirir.
     */
    fun serializeSession(): String = buildString {
        append("ACTIVE=").append(activeTabPath ?: "").append('\n')
        append("SPLIT=").append(if (splitViewEnabled) (secondarySplitPath ?: "") else "").append('\n')
        for (t in tabList) {
            append("TAB=")
                .append(t.path).append('|')
                .append(t.cursorOffset).append('|')
                .append(t.scrollY)
                .append('\n')
        }
    }

    /**
     * Serileştirilmiş oturum metninden açık sekmeleri ve imleç konumlarını geri yükler.
     */
    fun restoreSession(
        serialized: String,
        fileExistsPredicate: (String) -> Boolean = { File(it).isFile }
    ) {
        tabList.clear()
        activeTabPath = null
        secondarySplitPath = null
        splitViewEnabled = false

        var savedActive: String? = null
        var savedSplit: String? = null

        for (rawLine in serialized.lines()) {
            val line = rawLine.trim()
            when {
                line.startsWith("ACTIVE=") -> {
                    savedActive = line.removePrefix("ACTIVE=").takeIf { it.isNotBlank() }?.let { normalizePath(it) }
                }
                line.startsWith("SPLIT=") -> {
                    savedSplit = line.removePrefix("SPLIT=").takeIf { it.isNotBlank() }?.let { normalizePath(it) }
                }
                line.startsWith("TAB=") -> {
                    val payload = line.removePrefix("TAB=")
                    val parts = payload.split('|')
                    if (parts.isNotEmpty()) {
                        val p = normalizePath(parts[0])
                        if (p.isNotBlank() && fileExistsPredicate(p)) {
                            val cursor = parts.getOrNull(1)?.toIntOrNull() ?: 0
                            val scroll = parts.getOrNull(2)?.toIntOrNull() ?: 0
                            val tab = openTab(p, cursorOffset = cursor)
                            tab.scrollY = scroll.coerceAtLeast(0)
                        }
                    }
                }
            }
        }

        if (savedActive != null && tabList.any { it.path == savedActive }) {
            activeTabPath = savedActive
        } else {
            activeTabPath = tabList.firstOrNull()?.path
        }

        if (savedSplit != null && tabList.any { it.path == savedSplit }) {
            secondarySplitPath = savedSplit
            splitViewEnabled = true
        }
    }

    // ---------- Explorer Ağaç Görünümü (Genişlet / Daralt) ----------

    fun toggleDirectoryExpanded(dirPath: String): Boolean {
        val norm = normalizePath(dirPath)
        return if (expandedDirs.contains(norm)) {
            expandedDirs.remove(norm)
            false
        } else {
            expandedDirs.add(norm)
            true
        }
    }

    fun expandToFile(projectRoot: File, targetFile: File) {
        val rootNorm = normalizePath(projectRoot.absolutePath)
        expandedDirs.add(rootNorm)
        var p = targetFile.parentFile
        while (p != null) {
            val norm = normalizePath(p.absolutePath)
            expandedDirs.add(norm)
            if (norm == rootNorm) break
            p = p.parentFile
        }
    }

    fun buildExplorerTree(projectRoot: File): List<TreeNode> {
        val out = ArrayList<TreeNode>()
        val rootNorm = normalizePath(projectRoot.absolutePath)
        expandedDirs.add(rootNorm)
        appendChildrenRecursive(projectRoot, depth = 0, out = out)
        return out
    }

    private fun appendChildrenRecursive(dir: File, depth: Int, out: MutableList<TreeNode>) {
        val children = dir.listFiles()
            ?.filter { it.name != "bin" && it.name != "gen" }
            ?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
            ?: return

        for (child in children) {
            val norm = normalizePath(child.absolutePath)
            val isDir = child.isDirectory
            val expanded = isDir && expandedDirs.contains(norm)
            out.add(
                TreeNode(
                    file = child,
                    path = norm,
                    name = child.name,
                    depth = depth,
                    isDirectory = isDir,
                    isExpanded = expanded
                )
            )
            if (isDir && expanded) {
                appendChildrenRecursive(child, depth + 1, out)
            }
        }
    }

    companion object {
        fun normalizePath(path: String): String = path.replace('\\', '/').trimEnd('/')

        fun detectLineEnding(text: CharSequence): String {
            for (i in 0 until text.length) {
                if (text[i] == '\r') return "CRLF"
                if (text[i] == '\n') return "LF"
            }
            return "LF"
        }

        fun detectIndentation(text: CharSequence): String {
            var tabLines = 0
            var space2Lines = 0
            var space4Lines = 0
            var i = 0
            val len = minOf(text.length, 32_768)
            while (i < len) {
                var spaces = 0
                var hasTab = false
                while (i < len && (text[i] == ' ' || text[i] == '\t')) {
                    if (text[i] == '\t') hasTab = true else spaces++
                    i++
                }
                if (hasTab) tabLines++
                else if (spaces >= 4 && spaces % 4 == 0) space4Lines++
                else if (spaces >= 2 && spaces % 2 == 0) space2Lines++

                while (i < len && text[i] != '\n') i++
                i++
            }
            return when {
                tabLines > space4Lines && tabLines > space2Lines -> "Tab:4"
                space2Lines > space4Lines * 2 -> "Boşluk:2"
                else -> "Boşluk:4"
            }
        }

        /** Breadcrumb çubuğu için proje kökünden dosyaya kadar olan segmentleri üretir. */
        fun buildBreadcrumbs(projectRoot: File?, currentFile: File): List<Pair<String, File>> {
            val result = ArrayList<Pair<String, File>>()
            if (projectRoot == null) {
                currentFile.parentFile?.let { result.add(it.name to it) }
                result.add(currentFile.name to currentFile)
                return result
            }
            val rootNorm = normalizePath(projectRoot.absolutePath)
            val stack = ArrayList<File>()
            var cur: File? = currentFile
            while (cur != null) {
                stack.add(cur)
                if (normalizePath(cur.absolutePath).equals(rootNorm, ignoreCase = true)) break
                cur = cur.parentFile
            }
            for (f in stack.asReversed()) {
                result.add(f.name to f)
            }
            return result
        }
    }
}
