package com.miniide.app.editor

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.google.android.material.bottomsheet.BottomSheetDialog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Prompt 4 BottomSheet ve Arama Arayüzleri:
 * - Komut Paleti (Ctrl+Shift+P): bulanık (fuzzy) arama, son kullanılanlar üstte
 * - Hızlı Dosya Aç (Ctrl+P): fuzzy dosya arama + ":120" ile satıra git
 * - Projede Ara / Tümünü Değiştir (Ctrl+Shift+F): çok iş parçacıklı arka plan taraması,
 *   dosyaya göre gruplu sonuçlar, önizlemeli ve geri alınabilir toplu değiştirme
 */
object EditorSearchSheets {

    fun showCommandPaletteBottomSheet(
        context: Context,
        registry: SearchAndCommandEngine.CommandRegistry,
        onOpenKeySettings: () -> Unit
    ) {
        val dialog = BottomSheetDialog(context)
        val dp = context.resources.displayMetrics.density
        val pad = (14 * dp).toInt()

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        val headerRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = TextView(context).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            text = "⌘ Komut Paleti (Ctrl+Shift+P)"
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
        }
        val btnSettings = Button(context, null, android.R.attr.borderlessButtonStyle).apply {
            text = "⚙ Kısayollar"
            isAllCaps = false
            textSize = 12f
            setOnClickListener {
                dialog.dismiss()
                onOpenKeySettings()
            }
        }
        headerRow.addView(title)
        headerRow.addView(btnSettings)
        root.addView(headerRow)

        val etQuery = EditText(context).apply {
            hint = "Komut ara (bulanık arama, ör. 'kydt', 'satır', 'yorum')..."
            setSingleLine()
        }
        root.addView(etQuery)

        val scroll = ScrollView(context)
        val listBox = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(listBox)
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (340 * dp).toInt()))

        fun renderList(q: String) {
            listBox.removeAllViews()
            val matches = registry.search(q)
            if (matches.isEmpty()) {
                listBox.addView(TextView(context).apply {
                    text = "Eşleşen komut bulunamadı"
                    setPadding(0, (12 * dp).toInt(), 0, (12 * dp).toInt())
                })
                return
            }
            for (cmd in matches) {
                val row = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding((6 * dp).toInt(), (10 * dp).toInt(), (6 * dp).toInt(), (10 * dp).toInt())
                    setOnClickListener {
                        dialog.dismiss()
                        registry.markUsed(cmd.id)
                        cmd.action?.invoke()
                    }
                }
                val tvLabel = TextView(context).apply {
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    text = "${cmd.categoryTr}: ${cmd.titleTr}"
                    textSize = 13.5f
                }
                val tvKey = TextView(context).apply {
                    text = cmd.shortcutLabel
                    typeface = Typeface.MONOSPACE
                    textSize = 11.5f
                    setTextColor(Color.rgb(0x25, 0x63, 0xEB))
                }
                row.addView(tvLabel)
                row.addView(tvKey)
                listBox.addView(row)
            }
        }

        etQuery.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) = renderList(s?.toString() ?: "")
        })

        renderList("")
        dialog.setContentView(root)
        dialog.show()
        etQuery.requestFocus()
    }

    fun showQuickOpenBottomSheet(
        context: Context,
        uiScope: CoroutineScope,
        projectRoot: File?,
        onGotoLineInCurrent: (Int) -> Unit,
        onOpenFileAtLine: (File, Int) -> Unit
    ) {
        if (projectRoot == null) {
            Toast.makeText(context, "Aktif proje bulunamadı", Toast.LENGTH_SHORT).show()
            return
        }
        val dialog = BottomSheetDialog(context)
        val dp = context.resources.displayMetrics.density
        val pad = (14 * dp).toInt()

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        root.addView(TextView(context).apply {
            text = "⚡ Hızlı Dosya Aç (Ctrl+P) — ':satır' destekli"
            textSize = 14.5f
            typeface = Typeface.DEFAULT_BOLD
        })
        val etQuery = EditText(context).apply {
            hint = "Dosya adı ara veya :120 yaz..."
            setSingleLine()
        }
        root.addView(etQuery)

        val scroll = ScrollView(context)
        val listBox = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(listBox)
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (340 * dp).toInt()))

        var allFiles: List<Pair<File, String>> = emptyList()

        fun renderMatches(raw: String) {
            listBox.removeAllViews()
            val parsed = SearchAndCommandEngine.parseQuickOpenQuery(raw)
            if (parsed.fileQuery.isEmpty() && parsed.targetLine != null) {
                val gotoRow = TextView(context).apply {
                    text = "↕ Aktif dosyada ${parsed.targetLine}. satıra git"
                    textSize = 14f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(Color.rgb(0x25, 0x63, 0xEB))
                    setPadding((6 * dp).toInt(), (12 * dp).toInt(), (6 * dp).toInt(), (12 * dp).toInt())
                    setOnClickListener {
                        dialog.dismiss()
                        onGotoLineInCurrent(parsed.targetLine)
                    }
                }
                listBox.addView(gotoRow)
                return
            }

            val ranked = if (parsed.fileQuery.isEmpty()) {
                allFiles.take(40)
            } else {
                allFiles.mapNotNull { pair ->
                    val nameScore = SearchAndCommandEngine.fuzzyScore(parsed.fileQuery, pair.first.name)
                    val pathScore = SearchAndCommandEngine.fuzzyScore(parsed.fileQuery, pair.second)
                    val best = maxOf(nameScore * 2, pathScore)
                    if (best > 0) pair to best else null
                }.sortedByDescending { it.second }.take(40).map { it.first }
            }

            if (ranked.isEmpty()) {
                listBox.addView(TextView(context).apply {
                    text = "Eşleşen dosya bulunamadı"
                    setPadding(0, (12 * dp).toInt(), 0, (12 * dp).toInt())
                })
                return
            }

            for ((f, rel) in ranked) {
                val lineSuffix = if (parsed.targetLine != null) "  → Satır ${parsed.targetLine}" else ""
                val tv = TextView(context).apply {
                    text = "${f.name}$lineSuffix\n$rel"
                    textSize = 13f
                    setPadding((6 * dp).toInt(), (8 * dp).toInt(), (6 * dp).toInt(), (8 * dp).toInt())
                    setOnClickListener {
                        dialog.dismiss()
                        onOpenFileAtLine(f, parsed.targetLine ?: 0)
                    }
                }
                listBox.addView(tv)
            }
        }

        etQuery.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) = renderMatches(s?.toString() ?: "")
        })

        uiScope.launch {
            allFiles = withContext(Dispatchers.IO) {
                val rootPath = projectRoot.canonicalPath
                projectRoot.walkTopDown()
                    .onEnter { d -> d.canonicalPath == rootPath || (d.name != "bin" && d.name != "gen" && d.name != "build" && d.name != ".git") }
                    .filter { it.isFile }
                    .map { f -> f to f.canonicalPath.removePrefix(rootPath).trimStart('\\', '/').replace('\\', '/') }
                    .sortedBy { it.first.name.lowercase() }
                    .toList()
            }
            renderMatches(etQuery.text?.toString() ?: "")
        }

        dialog.setContentView(root)
        dialog.show()
        etQuery.requestFocus()
    }

    fun showProjectSearchBottomSheet(
        context: Context,
        uiScope: CoroutineScope,
        projectRoot: File?,
        lastBackupProvider: () -> SearchAndCommandEngine.ProjectReplaceBackup?,
        onBackupUpdated: (SearchAndCommandEngine.ProjectReplaceBackup?) -> Unit,
        onOpenMatch: (File, Int) -> Unit,
        onFilesChangedOnDisk: () -> Unit
    ) {
        if (projectRoot == null) {
            Toast.makeText(context, "Aktif proje bulunamadı", Toast.LENGTH_SHORT).show()
            return
        }
        val dialog = BottomSheetDialog(context)
        val dp = context.resources.displayMetrics.density
        val pad = (14 * dp).toInt()

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        root.addView(TextView(context).apply {
            text = "🔎 Projede Ara ve Değiştir (Ctrl+Shift+F)"
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
        })

        val etQuery = EditText(context).apply { hint = "Aranacak metin veya Regex"; setSingleLine() }
        val etReplace = EditText(context).apply { hint = "Yerine yazılacak (isteğe bağlı)"; setSingleLine() }
        val etExt = EditText(context).apply { hint = "Uzantı filtresi (ör. java,xml — boş=tümü)"; setSingleLine() }
        val etExclude = EditText(context).apply {
            hint = "Hariç tutulan klasörler"
            setText("build,gen,bin,.git")
            setSingleLine()
        }
        root.addView(etQuery)
        root.addView(etReplace)
        root.addView(etExt)
        root.addView(etExclude)

        val cbCase = CheckBox(context).apply { text = "Aa (Büyük/Küçük)" }
        val cbWord = CheckBox(context).apply { text = "Tam Kelime" }
        val cbRegex = CheckBox(context).apply { text = "Regex" }
        root.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(cbCase); addView(cbWord); addView(cbRegex)
        })

        val btnRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        val btnSearch = Button(context).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            text = "Ara"
        }
        val btnReplaceAll = Button(context).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            text = "Tümünü Değiştir"
        }
        val btnRollback = Button(context).apply {
            text = "Geri Al"
            isEnabled = lastBackupProvider() != null
        }
        btnRow.addView(btnSearch)
        btnRow.addView(btnReplaceAll)
        btnRow.addView(btnRollback)
        root.addView(btnRow)

        val tvSummary = TextView(context).apply {
            text = "Arama için metin girip 'Ara' düğmesine dokunun."
            textSize = 12f
            setPadding(0, (6 * dp).toInt(), 0, (4 * dp).toInt())
        }
        root.addView(tvSummary)

        val scroll = ScrollView(context)
        val resultsBox = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(resultsBox)
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (260 * dp).toInt()))

        fun buildOptions(): SearchAndCommandEngine.SearchOptions {
            val exts = etExt.text.toString().split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
            val excl = etExclude.text.toString().split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
            return SearchAndCommandEngine.SearchOptions(
                query = etQuery.text.toString(),
                caseSensitive = cbCase.isChecked,
                wholeWord = cbWord.isChecked,
                useRegex = cbRegex.isChecked,
                includeExtensions = exts,
                excludedDirs = excl
            )
        }

        btnSearch.setOnClickListener {
            val opts = buildOptions()
            if (opts.query.isEmpty()) {
                Toast.makeText(context, "Aranacak metin girin", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            tvSummary.text = "Çok iş parçacıklı tarama yapılıyor..."
            resultsBox.removeAllViews()
            uiScope.launch {
                val results = withContext(Dispatchers.IO) {
                    SearchAndCommandEngine.searchInProject(projectRoot, opts)
                }
                val totalMatches = results.sumOf { it.matches.size }
                tvSummary.text = "${results.size} dosyada toplam $totalMatches eşleşme bulundu"
                for (fileRes in results) {
                    resultsBox.addView(TextView(context).apply {
                        text = "📄 ${fileRes.relativePath} (${fileRes.matches.size})"
                        typeface = Typeface.DEFAULT_BOLD
                        textSize = 12.5f
                        setTextColor(Color.rgb(0x25, 0x63, 0xEB))
                        setPadding(0, (8 * dp).toInt(), 0, (2 * dp).toInt())
                    })
                    for (m in fileRes.matches.take(30)) {
                        resultsBox.addView(TextView(context).apply {
                            text = "  Satır ${m.lineOneBased}: ${m.linePreview}"
                            typeface = Typeface.MONOSPACE
                            textSize = 11.5f
                            setPadding((6 * dp).toInt(), (4 * dp).toInt(), (4 * dp).toInt(), (4 * dp).toInt())
                            setOnClickListener {
                                dialog.dismiss()
                                onOpenMatch(fileRes.file, m.lineOneBased)
                            }
                        })
                    }
                }
            }
        }

        btnReplaceAll.setOnClickListener {
            val opts = buildOptions()
            if (opts.query.isEmpty()) return@setOnClickListener
            val repl = etReplace.text.toString()
            uiScope.launch {
                val previews = withContext(Dispatchers.IO) {
                    SearchAndCommandEngine.previewReplaceInProject(projectRoot, opts, repl)
                }
                if (previews.isEmpty()) {
                    Toast.makeText(context, "Değiştirilecek eşleşme bulunamadı", Toast.LENGTH_SHORT).show()
                    return@launch
                }
                val total = previews.sumOf { it.matchCount }
                val previewMsg = buildString {
                    append("${previews.size} dosyada toplam $total eşleşme değiştirilecek:\n\n")
                    for (p in previews.take(15)) {
                        append("• ${p.relativePath} (${p.matchCount} eşleşme)\n")
                    }
                    if (previews.size > 15) append("... ve ${previews.size - 15} dosya daha.")
                }
                AlertDialog.Builder(context)
                    .setTitle("Toplu Değiştirme Önizlemesi")
                    .setMessage(previewMsg)
                    .setPositiveButton("Uygula") { _, _ ->
                        uiScope.launch {
                            val backup = withContext(Dispatchers.IO) {
                                SearchAndCommandEngine.applyReplaceInProject(previews)
                            }
                            onBackupUpdated(backup)
                            btnRollback.isEnabled = true
                            onFilesChangedOnDisk()
                            Toast.makeText(context, "✓ $total eşleşme değiştirildi (Geri Alınabilir)", Toast.LENGTH_LONG).show()
                        }
                    }
                    .setNegativeButton("İptal", null)
                    .show()
            }
        }

        btnRollback.setOnClickListener {
            val backup = lastBackupProvider() ?: return@setOnClickListener
            uiScope.launch {
                withContext(Dispatchers.IO) { backup.rollback() }
                onBackupUpdated(null)
                btnRollback.isEnabled = false
                onFilesChangedOnDisk()
                Toast.makeText(context, "✓ Toplu değiştirme geri alındı", Toast.LENGTH_SHORT).show()
            }
        }

        dialog.setContentView(root)
        dialog.show()
        etQuery.requestFocus()
    }
}
