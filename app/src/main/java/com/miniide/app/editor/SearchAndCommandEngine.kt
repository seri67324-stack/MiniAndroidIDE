package com.miniide.app.editor

import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/**
 * Prompt 4:
 * 1. CommandRegistry & Bulanık (Fuzzy) Arama (Komut Paleti, son kullanılanlar üstte)
 * 2. Hızlı Dosya Aç (Ctrl+P): dosya adı fuzzy arama + ":120" satır numarası ayrıştırma
 * 3. Projede Ara / Tümünü Değiştir (Ctrl+Shift+F): çok iş parçacıklı arka plan taraması,
 *    düz metin, büyük/küçük harf, tam kelime, regex, klasör hariç tutma (build/, gen/, bin/),
 *    önizlemeli ve geri alınabilir toplu değiştirme
 * 4. Dosya içi kalıcı arama çubuğu eşleşme hesaplayıcı
 */
object SearchAndCommandEngine {

    data class IdeCommand(
        val id: String,
        val titleTr: String,
        val shortcutLabel: String,
        val categoryTr: String = "Editör",
        val action: (() -> Unit)? = null
    )

    data class QuickOpenQuery(
        val fileQuery: String,
        val targetLine: Int?
    )

    data class QuickOpenMatch<T>(
        val item: T,
        val label: String,
        val score: Int
    )

    data class SearchOptions(
        val query: String,
        val caseSensitive: Boolean = false,
        val wholeWord: Boolean = false,
        val useRegex: Boolean = false,
        val includeExtensions: Set<String> = emptySet(),
        val excludedDirs: Set<String> = setOf("build", "gen", "bin", ".git", ".gradle")
    )

    data class MatchRange(
        val startOffset: Int,
        val endOffset: Int,
        val lineOneBased: Int,
        val linePreview: String
    )

    data class FileSearchResult(
        val file: File,
        val relativePath: String,
        val matches: List<MatchRange>
    )

    data class ReplacePreviewItem(
        val file: File,
        val relativePath: String,
        val matchCount: Int,
        val originalContent: String,
        val newContent: String
    )

    class ProjectReplaceBackup(
        val snapshots: Map<File, String>
    ) {
        /** Toplu değiştirme işlemini tek adımda geri alır. */
        fun rollback() {
            for ((file, original) in snapshots) {
                if (file.exists()) {
                    file.writeText(original, Charsets.UTF_8)
                }
            }
        }
    }

    /**
     * Tüm IDE komutlarını ve son kullanım (MRU) sırasını tutan kayıt defteri.
     */
    class CommandRegistry {
        private val commands = LinkedHashMap<String, IdeCommand>()
        private val recentIds = ArrayList<String>()

        fun register(cmd: IdeCommand) {
            commands[cmd.id] = cmd
        }

        fun markUsed(id: String) {
            recentIds.remove(id)
            recentIds.add(0, id)
            while (recentIds.size > 25) recentIds.removeLast()
        }

        fun search(query: String): List<IdeCommand> {
            val all = commands.values.toList()
            val q = query.trim()
            if (q.isEmpty()) {
                val recents = recentIds.mapNotNull { commands[it] }
                val rest = all.filter { it.id !in recentIds }
                return recents + rest
            }
            return all.mapNotNull { cmd ->
                val titleScore = fuzzyScore(q, cmd.titleTr)
                val catScore = fuzzyScore(q, "${cmd.categoryTr} ${cmd.titleTr}")
                val best = maxOf(titleScore, catScore)
                if (best > 0) {
                    val mruBonus = if (cmd.id in recentIds) (30 - recentIds.indexOf(cmd.id).coerceAtMost(25)) else 0
                    cmd to (best + mruBonus)
                } else {
                    null
                }
            }.sortedByDescending { it.second }.map { it.first }
        }
    }

    /**
     * Bulanık (fuzzy) eşleşme skoru hesaplar.
     * Eşleşme yoksa 0, eşleşme varsa ardışık karakter, kelime başı ve tam önek bonuslarıyla > 0 döndürür.
     */
    fun fuzzyScore(query: String, candidate: String): Int {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return 1
        val c = candidate.lowercase()
        if (c == q) return 1000
        if (c.startsWith(q)) return 800 - (c.length - q.length).coerceAtMost(100)

        val directIdx = c.indexOf(q)
        if (directIdx >= 0) {
            val boundaryBonus = if (directIdx == 0 || !c[directIdx - 1].isLetterOrDigit()) 120 else 0
            return 600 + boundaryBonus - directIdx.coerceAtMost(100)
        }

        var qIdx = 0
        var score = 0
        var consecutive = 0
        var firstMatchIdx = -1

        for (i in c.indices) {
            if (qIdx < q.length && c[i] == q[qIdx]) {
                if (firstMatchIdx < 0) firstMatchIdx = i
                score += 20
                if (consecutive > 0) {
                    score += 25 * consecutive
                }
                if (i == 0 || c[i - 1] == '/' || c[i - 1] == '.' || c[i - 1] == '_' || c[i - 1] == ' ' ||
                    (candidate[i].isUpperCase() && !candidate[i - 1].isUpperCase())
                ) {
                    score += 30
                }
                consecutive++
                qIdx++
            } else {
                consecutive = 0
            }
        }

        if (qIdx < q.length) return 0
        val penalty = (firstMatchIdx.coerceAtLeast(0) * 2) + (c.length - q.length)
        return (score - penalty).coerceAtLeast(1)
    }

    /**
     * Ctrl+P sorgusunu ("MainActivity:120" veya ":45" veya "MainAct") ayrıştırır.
     */
    fun parseQuickOpenQuery(raw: String): QuickOpenQuery {
        val trimmed = raw.trim()
        val colonIdx = trimmed.lastIndexOf(':')
        if (colonIdx < 0) {
            return QuickOpenQuery(trimmed, null)
        }
        val filePart = trimmed.substring(0, colonIdx).trim()
        val linePart = trimmed.substring(colonIdx + 1).trim().toIntOrNull()
        return QuickOpenQuery(filePart, linePart?.takeIf { it > 0 })
    }

    /**
     * Arama seçeneklerine (düz metin, büyük/küçük harf, tam kelime, regex) göre Regex derler.
     */
    fun buildSearchRegex(options: SearchOptions): Regex? {
        if (options.query.isEmpty()) return null
        val basePattern = if (options.useRegex) {
            options.query
        } else {
            Regex.escape(options.query)
        }
        val finalPattern = if (options.wholeWord) {
            "\\b(?:$basePattern)\\b"
        } else {
            basePattern
        }
        val regexOpts = mutableSetOf<RegexOption>()
        if (!options.caseSensitive) {
            regexOpts.add(RegexOption.IGNORE_CASE)
        }
        return try {
            Regex(finalPattern, regexOpts)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Tek bir metin içindeki tüm eşleşmeleri ve satır önizlemelerini bulur.
     */
    fun findMatchesInText(text: CharSequence, options: SearchOptions): List<MatchRange> {
        val regex = buildSearchRegex(options) ?: return emptyList()
        val lineIndex = LineIndex().apply { rebuild(text) }
        val results = ArrayList<MatchRange>()

        for (m in regex.findAll(text)) {
            if (m.range.isEmpty()) continue
            val start = m.range.first
            val end = m.range.last + 1
            val line0 = lineIndex.lineAtOffset(start)
            val lStart = lineIndex.lineStart(line0)
            val lEnd = lineIndex.lineEnd(line0, text.length)
            val preview = text.subSequence(lStart, lEnd).toString().trim()
            results.add(MatchRange(start, end, line0 + 1, preview))
        }
        return results
    }

    /**
     * Tek bir metin üzerinde arama seçeneklerine göre tüm eşleşmeleri değiştirir.
     */
    fun replaceAllInText(text: String, options: SearchOptions, replacement: String): Pair<String, Int> {
        val regex = buildSearchRegex(options) ?: return text to 0
        val count = regex.findAll(text).count { !it.range.isEmpty() }
        if (count == 0) return text to 0
        val safeReplacement = if (options.useRegex) replacement else Regex.escapeReplacement(replacement)
        val updated = regex.replace(text, safeReplacement)
        return updated to count
    }

    /**
     * Projede çok iş parçacıklı (multi-threaded) arama yapar; build/, gen/, bin/ klasörlerini hariç tutar.
     */
    fun searchInProject(projectRoot: File, options: SearchOptions): List<FileSearchResult> {
        val regex = buildSearchRegex(options) ?: return emptyList()
        val candidateFiles = collectSearchableFiles(projectRoot, options)
        if (candidateFiles.isEmpty()) return emptyList()

        val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
        val pool = Executors.newFixedThreadPool(threads)
        try {
            val rootPath = projectRoot.canonicalPath
            val tasks = candidateFiles.map { file ->
                Callable<FileSearchResult?> {
                    try {
                        if (file.length() > 2 * 1024 * 1024L) return@Callable null
                        val bytes = file.readBytes()
                        val probe = minOf(bytes.size, 1024)
                        for (i in 0 until probe) if (bytes[i] == 0.toByte()) return@Callable null
                        val content = String(bytes, Charsets.UTF_8)
                        val lineIndex = LineIndex().apply { rebuild(content) }
                        val matches = ArrayList<MatchRange>()
                        for (m in regex.findAll(content)) {
                            if (m.range.isEmpty()) continue
                            val s = m.range.first
                            val e = m.range.last + 1
                            val line0 = lineIndex.lineAtOffset(s)
                            val lStart = lineIndex.lineStart(line0)
                            val lEnd = lineIndex.lineEnd(line0, content.length)
                            matches.add(MatchRange(s, e, line0 + 1, content.substring(lStart, lEnd).trim()))
                        }
                        if (matches.isEmpty()) null else {
                            val rel = file.canonicalPath.removePrefix(rootPath).trimStart('\\', '/').replace('\\', '/')
                            FileSearchResult(file, rel, matches)
                        }
                    } catch (_: Exception) {
                        null
                    }
                }
            }
            return pool.invokeAll(tasks).mapNotNull { it.get() }.sortedBy { it.relativePath }
        } finally {
            pool.shutdown()
        }
    }

    /**
     * Projede "Tümünü Değiştir" için önizleme listesi üretir.
     */
    fun previewReplaceInProject(
        projectRoot: File,
        options: SearchOptions,
        replacement: String
    ): List<ReplacePreviewItem> {
        val searchResults = searchInProject(projectRoot, options)
        val previews = ArrayList<ReplacePreviewItem>()
        for (res in searchResults) {
            val orig = res.file.readText(Charsets.UTF_8)
            val (updated, count) = replaceAllInText(orig, options, replacement)
            if (count > 0 && updated != orig) {
                previews.add(ReplacePreviewItem(res.file, res.relativePath, count, orig, updated))
            }
        }
        return previews
    }

    /**
     * Önizlenen toplu değişiklikleri diske uygular ve geri alma (rollback) yedeği döndürür.
     */
    fun applyReplaceInProject(previews: List<ReplacePreviewItem>): ProjectReplaceBackup {
        val backupMap = LinkedHashMap<File, String>()
        for (item in previews) {
            backupMap[item.file] = item.originalContent
            item.file.writeText(item.newContent, Charsets.UTF_8)
        }
        return ProjectReplaceBackup(backupMap)
    }

    private fun collectSearchableFiles(projectRoot: File, options: SearchOptions): List<File> {
        val rootPath = projectRoot.canonicalPath
        val excluded = options.excludedDirs.map { it.lowercase() }.toSet()
        val extFilter = options.includeExtensions.map { it.lowercase().removePrefix(".") }.filter { it.isNotEmpty() }.toSet()

        return projectRoot.walkTopDown()
            .onEnter { dir ->
                if (dir.canonicalPath == rootPath) true
                else dir.name.lowercase() !in excluded
            }
            .filter { f ->
                f.isFile && (extFilter.isEmpty() || f.extension.lowercase() in extFilter)
            }
            .toList()
    }
}
