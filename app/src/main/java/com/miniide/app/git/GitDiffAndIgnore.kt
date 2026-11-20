package com.miniide.app.git

/**
 * Prompt 7 Saf Kotlin Git Yardımcıları (JVM üzerinde birim test edilebilir):
 * - GitIgnoreMatcher: .gitignore kurallarını (build/, gen/, *.apk, !istisna, **) eşleştirir
 * - LineDiffCalculator: iki metin arasında satır bazlı diff, gutter çubukları (yeşil/mavi/kırmızı)
 *   ve satır içi diff önizlemesi hesaplar
 */
object GitDiffAndIgnore {

    const val DEFAULT_ANDROID_GITIGNORE = """# Mini Android IDE varsayılan .gitignore
build/
bin/
gen/
.gradle/
local.properties
*.apk
*.ap_
*.dex
*.class
"""

    enum class DiffType { UNCHANGED, ADDED, MODIFIED, DELETED }

    data class DiffLine(
        val type: DiffType,
        val oldLineNumber: Int?,
        val newLineNumber: Int?,
        val content: String
    )

    class GitIgnoreMatcher(rulesText: String) {
        private data class Rule(
            val regex: Regex,
            val negated: Boolean,
            val directoryOnly: Boolean,
            val matchFullPath: Boolean
        )

        private val rules: List<Rule> = rulesText.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .map { parseRule(it) }

        fun isIgnored(relativePath: String, isDirectory: Boolean = false): Boolean {
            val norm = relativePath.replace('\\', '/').trimStart('/').trimEnd('/')
            if (norm.isEmpty()) return false
            val segments = norm.split('/')
            val fileName = segments.last()

            var ignored = false
            for (rule in rules) {
                val matched = if (rule.directoryOnly) {
                    // Klasör kuralı (ör. build/) hem klasörün kendisini hem de içindeki tüm dosyaları eşleştirir
                    if (rule.matchFullPath) {
                        rule.regex.matches(norm) || norm.startsWith(rule.regex.pattern.removePrefix("^").removeSuffix("$") + "/")
                    } else {
                        segments.dropLast(if (isDirectory) 0 else 1).any { rule.regex.matches(it) } ||
                                (isDirectory && rule.regex.matches(fileName))
                    }
                } else {
                    if (rule.matchFullPath) {
                        rule.regex.matches(norm)
                    } else {
                        rule.regex.matches(fileName) || rule.regex.matches(norm)
                    }
                }

                if (matched) {
                    ignored = !rule.negated
                }
            }
            return ignored
        }

        private fun parseRule(raw: String): Rule {
            var pattern = raw
            val negated = pattern.startsWith("!")
            if (negated) pattern = pattern.substring(1)

            val directoryOnly = pattern.endsWith("/")
            if (directoryOnly) pattern = pattern.trimEnd('/')

            val anchored = pattern.startsWith("/")
            if (anchored) pattern = pattern.substring(1)

            val matchFullPath = anchored || pattern.contains('/')
            val regexStr = globToRegex(pattern)
            return Rule(
                regex = Regex("^$regexStr$"),
                negated = negated,
                directoryOnly = directoryOnly,
                matchFullPath = matchFullPath
            )
        }

        private fun globToRegex(glob: String): String {
            val sb = StringBuilder()
            var i = 0
            while (i < glob.length) {
                val c = glob[i]
                when {
                    c == '*' && i + 1 < glob.length && glob[i + 1] == '*' -> {
                        if (i + 2 < glob.length && glob[i + 2] == '/') {
                            sb.append("(?:.*/)?")
                            i += 3
                        } else {
                            sb.append(".*")
                            i += 2
                        }
                    }
                    c == '*' -> { sb.append("[^/]*"); i++ }
                    c == '?' -> { sb.append("[^/]"); i++ }
                    c in ".+()|^$[]{}\\" -> { sb.append('\\').append(c); i++ }
                    else -> { sb.append(c); i++ }
                }
            }
            return sb.toString()
        }
    }

    object LineDiffCalculator {
        const val MARKER_ADDED = 1    // yeşil
        const val MARKER_MODIFIED = 2 // mavi
        const val MARKER_DELETED = 3  // kırmızı

        /**
         * İki metin arasında LCS tabanlı satır diff'i hesaplar.
         * Çok büyük dosyalarda (> 3000 satır) hızlı satır-karşılaştırma fallback'i kullanır.
         */
        fun computeDiff(oldText: String, newText: String): List<DiffLine> {
            val oldLines = if (oldText.isEmpty()) emptyList() else oldText.replace("\r\n", "\n").split('\n')
            val newLines = if (newText.isEmpty()) emptyList() else newText.replace("\r\n", "\n").split('\n')

            if (oldLines.size.toLong() * newLines.size.toLong() > 2_000_000L) {
                return computeFastFallbackDiff(oldLines, newLines)
            }

            val m = oldLines.size
            val n = newLines.size
            val dp = Array(m + 1) { IntArray(n + 1) }

            for (i in m - 1 downTo 0) {
                for (j in n - 1 downTo 0) {
                    dp[i][j] = if (oldLines[i] == newLines[j]) {
                        dp[i + 1][j + 1] + 1
                    } else {
                        maxOf(dp[i + 1][j], dp[i][j + 1])
                    }
                }
            }

            val raw = ArrayList<DiffLine>()
            var i = 0
            var j = 0
            while (i < m && j < n) {
                when {
                    oldLines[i] == newLines[j] -> {
                        raw.add(DiffLine(DiffType.UNCHANGED, i + 1, j + 1, newLines[j]))
                        i++; j++
                    }
                    dp[i + 1][j] >= dp[i][j + 1] -> {
                        raw.add(DiffLine(DiffType.DELETED, i + 1, null, oldLines[i]))
                        i++
                    }
                    else -> {
                        raw.add(DiffLine(DiffType.ADDED, null, j + 1, newLines[j]))
                        j++
                    }
                }
            }
            while (i < m) {
                raw.add(DiffLine(DiffType.DELETED, i + 1, null, oldLines[i]))
                i++
            }
            while (j < n) {
                raw.add(DiffLine(DiffType.ADDED, null, j + 1, newLines[j]))
                j++
            }
            return raw
        }

        private fun computeFastFallbackDiff(oldLines: List<String>, newLines: List<String>): List<DiffLine> {
            val result = ArrayList<DiffLine>()
            val max = maxOf(oldLines.size, newLines.size)
            for (i in 0 until max) {
                val o = oldLines.getOrNull(i)
                val n = newLines.getOrNull(i)
                when {
                    o != null && n != null && o == n -> result.add(DiffLine(DiffType.UNCHANGED, i + 1, i + 1, n))
                    o != null && n != null -> result.add(DiffLine(DiffType.MODIFIED, i + 1, i + 1, n))
                    n != null -> result.add(DiffLine(DiffType.ADDED, null, i + 1, n))
                    o != null -> result.add(DiffLine(DiffType.DELETED, i + 1, null, o))
                }
            }
            return result
        }

        /**
         * Editör gutter'ında gösterilecek 1-tabanlı satır işaretlerini (1=eklendi, 2=değişti, 3=silindi) üretir.
         */
        fun computeGutterMarkers(oldText: String, newText: String): Map<Int, Int> {
            if (oldText == newText) return emptyMap()
            val diff = computeDiff(oldText, newText)
            val markers = LinkedHashMap<Int, Int>()

            var idx = 0
            while (idx < diff.size) {
                val d = diff[idx]
                when (d.type) {
                    DiffType.MODIFIED -> {
                        d.newLineNumber?.let { markers[it] = MARKER_MODIFIED }
                        idx++
                    }
                    DiffType.DELETED -> {
                        // Ardışık DELETED + ADDED bloklarını MODIFIED (mavi) olarak işaretle
                        var delCount = 0
                        while (idx + delCount < diff.size && diff[idx + delCount].type == DiffType.DELETED) delCount++
                        var addCount = 0
                        while (idx + delCount + addCount < diff.size && diff[idx + delCount + addCount].type == DiffType.ADDED) addCount++

                        if (addCount > 0) {
                            val modPairs = minOf(delCount, addCount)
                            for (k in 0 until modPairs) {
                                diff[idx + delCount + k].newLineNumber?.let { markers[it] = MARKER_MODIFIED }
                            }
                            for (k in modPairs until addCount) {
                                diff[idx + delCount + k].newLineNumber?.let { markers[it] = MARKER_ADDED }
                            }
                            idx += delCount + addCount
                        } else {
                            // Sadece silme: bir sonraki mevcut satırda kırmızı işaret göster
                            val anchorLine = diff.getOrNull(idx + delCount)?.newLineNumber
                                ?: diff.getOrNull(idx - 1)?.newLineNumber
                                ?: 1
                            markers[anchorLine] = MARKER_DELETED
                            idx += delCount
                        }
                    }
                    DiffType.ADDED -> {
                        d.newLineNumber?.let { markers[it] = MARKER_ADDED }
                        idx++
                    }
                    DiffType.UNCHANGED -> idx++
                }
            }
            return markers
        }

        /** Satır içi (inline) unified benzeri diff metni üretir. */
        fun formatInlineDiff(oldText: String, newText: String): String {
            val diff = computeDiff(oldText, newText)
            val sb = StringBuilder()
            for (d in diff) {
                when (d.type) {
                    DiffType.UNCHANGED -> sb.append("  ").append(d.content).append('\n')
                    DiffType.ADDED -> sb.append("+ ").append(d.content).append('\n')
                    DiffType.DELETED -> sb.append("- ").append(d.content).append('\n')
                    DiffType.MODIFIED -> sb.append("~ ").append(d.content).append('\n')
                }
            }
            return sb.toString()
        }
    }
}
