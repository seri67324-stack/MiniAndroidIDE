package com.miniide.app.git

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.text.InputType
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.miniide.app.project.ProjectManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Prompt 7 VS Code Benzeri Kaynak Denetimi (Source Control) Paneli.
 */
object GitSourceControlSheet {

    fun show(
        context: Context,
        uiScope: CoroutineScope,
        projectDir: File?,
        onRepoStateChanged: () -> Unit,
        onClonedNewProject: (File) -> Unit
    ) {
        if (projectDir == null) {
            Toast.makeText(context, "Aktif proje yok", Toast.LENGTH_SHORT).show()
            return
        }

        val dialog = BottomSheetDialog(context)
        val dp = context.resources.displayMetrics.density
        val pad = (14 * dp).toInt()

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        val header = TextView(context).apply {
            text = "⎇ Kaynak Denetimi (Git) — ${projectDir.name}"
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
        }
        root.addView(header)

        val contentBox = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(context).apply { addView(contentBox) }
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (420 * dp).toInt()))

        fun refreshUi() {
            contentBox.removeAllViews()
            contentBox.addView(TextView(context).apply { text = "Git durumu okunuyor..." })
            uiScope.launch {
                val st = withContext(Dispatchers.IO) { GitManager.status(projectDir) }
                contentBox.removeAllViews()

                if (!st.isGitRepo) {
                    contentBox.addView(TextView(context).apply {
                        text = "Bu proje henüz bir Git deposu değil.\nYerel depo başlatabilir veya uzak bir HTTPS deposunu klonlayabilirsiniz."
                        textSize = 13f
                        setPadding(0, (8 * dp).toInt(), 0, (12 * dp).toInt())
                    })
                    val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
                    row.addView(Button(context).apply {
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                        text = "Git Başlat (init)"
                        setOnClickListener {
                            uiScope.launch {
                                withContext(Dispatchers.IO) { GitManager.init(projectDir) }
                                Toast.makeText(context, "✓ Git deposu ve .gitignore oluşturuldu", Toast.LENGTH_SHORT).show()
                                onRepoStateChanged()
                                refreshUi()
                            }
                        }
                    })
                    row.addView(Button(context).apply {
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                        text = "Klonla (clone)"
                        setOnClickListener {
                            showCloneDialog(context, uiScope, onClonedNewProject)
                            dialog.dismiss()
                        }
                    })
                    contentBox.addView(row)
                    return@launch
                }

                // 1. Dal (Branch) ve Ağ İşlemleri (Pull / Push / Log / PAT)
                val branchBar = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                branchBar.addView(TextView(context).apply {
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    text = "Dal: ⎇ ${st.branch}"
                    typeface = Typeface.MONOSPACE
                    textSize = 13f
                    setTextColor(Color.rgb(0x10, 0xB9, 0x81))
                })
                branchBar.addView(Button(context, null, android.R.attr.borderlessButtonStyle).apply {
                    text = "Dallar"; isAllCaps = false; textSize = 11.5f
                    setOnClickListener { showBranchDialog(context, uiScope, projectDir) { onRepoStateChanged(); refreshUi() } }
                })
                branchBar.addView(Button(context, null, android.R.attr.borderlessButtonStyle).apply {
                    text = "Geçmiş"; isAllCaps = false; textSize = 11.5f
                    setOnClickListener { showLogDialog(context, uiScope, projectDir) }
                })
                branchBar.addView(Button(context, null, android.R.attr.borderlessButtonStyle).apply {
                    text = "PAT"; isAllCaps = false; textSize = 11.5f
                    setOnClickListener { showPatVaultDialog(context) }
                })
                contentBox.addView(branchBar)

                // 2. Commit mesajı + Commit / Stage All / Pull / Push
                val etCommit = EditText(context).apply {
                    hint = "Commit mesajı..."
                    setSingleLine()
                }
                contentBox.addView(etCommit)

                val actionRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
                actionRow.addView(Button(context).apply {
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    text = "Tümünü Ekle"
                    textSize = 11f
                    setOnClickListener {
                        uiScope.launch {
                            withContext(Dispatchers.IO) { GitManager.stageAll(projectDir) }
                            onRepoStateChanged()
                            refreshUi()
                        }
                    }
                })
                actionRow.addView(Button(context).apply {
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    text = "Commit"
                    textSize = 11f
                    setOnClickListener {
                        val msg = etCommit.text.toString().trim()
                        if (msg.isEmpty()) { Toast.makeText(context, "Commit mesajı yazın", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
                        uiScope.launch {
                            val res = withContext(Dispatchers.IO) {
                                runCatching { GitManager.commit(projectDir, msg) }
                            }
                            res.onSuccess { hash ->
                                Toast.makeText(context, "✓ Commit oluşturuldu ($hash)", Toast.LENGTH_SHORT).show()
                                etCommit.setText("")
                                onRepoStateChanged()
                                refreshUi()
                            }.onFailure { Toast.makeText(context, "Commit hatası: ${it.message}", Toast.LENGTH_LONG).show() }
                        }
                    }
                })
                contentBox.addView(actionRow)

                val syncRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
                syncRow.addView(Button(context, null, android.R.attr.borderlessButtonStyle).apply {
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    text = "⬇ Pull (İnternet)"
                    isAllCaps = false; textSize = 11.5f
                    setOnClickListener {
                        val (u, t) = GitManager.TokenVault.loadCredentials(context)
                        uiScope.launch {
                            val res = withContext(Dispatchers.IO) { runCatching { GitManager.pull(projectDir, u, t) } }
                            res.onSuccess { Toast.makeText(context, it, Toast.LENGTH_SHORT).show(); onRepoStateChanged(); refreshUi() }
                                .onFailure { Toast.makeText(context, "Pull hatası: ${it.message}", Toast.LENGTH_LONG).show() }
                        }
                    }
                })
                syncRow.addView(Button(context, null, android.R.attr.borderlessButtonStyle).apply {
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    text = "⬆ Push (İnternet)"
                    isAllCaps = false; textSize = 11.5f
                    setOnClickListener {
                        val (u, t) = GitManager.TokenVault.loadCredentials(context)
                        uiScope.launch {
                            val res = withContext(Dispatchers.IO) { runCatching { GitManager.push(projectDir, u, t) } }
                            res.onSuccess { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
                                .onFailure { Toast.makeText(context, "Push hatası: ${it.message}", Toast.LENGTH_LONG).show() }
                        }
                    }
                })
                contentBox.addView(syncRow)

                // 3. Aşamalı (Staged) Değişiklikler
                contentBox.addView(TextView(context).apply {
                    text = "Aşamalı Değişiklikler (${st.staged.size})"
                    typeface = Typeface.DEFAULT_BOLD
                    textSize = 12.5f
                    setTextColor(Color.rgb(0x10, 0xB9, 0x81))
                    setPadding(0, (8 * dp).toInt(), 0, (4 * dp).toInt())
                })
                for (path in st.staged) {
                    contentBox.addView(buildFileRow(context, dp, path, "−", Color.rgb(0x10, 0xB9, 0x81),
                        onDiff = { showFileDiffDialog(context, uiScope, projectDir, path) },
                        onToggleStage = {
                            uiScope.launch {
                                withContext(Dispatchers.IO) { GitManager.unstageFile(projectDir, path) }
                                onRepoStateChanged(); refreshUi()
                            }
                        }
                    ))
                }

                // 4. Değişiklikler (Unstaged + Untracked)
                val changes = st.unstaged + st.untracked
                contentBox.addView(TextView(context).apply {
                    text = "Değişiklikler (${changes.size})"
                    typeface = Typeface.DEFAULT_BOLD
                    textSize = 12.5f
                    setTextColor(Color.rgb(0xF5, 0x9E, 0x0B))
                    setPadding(0, (8 * dp).toInt(), 0, (4 * dp).toInt())
                })
                for (path in changes) {
                    val badge = st.fileStates[path]?.badge ?: "M"
                    val color = when (badge) {
                        "U", "A" -> Color.rgb(0x10, 0xB9, 0x81)
                        "D" -> Color.rgb(0xEF, 0x44, 0x44)
                        else -> Color.rgb(0x3B, 0x82, 0xF6)
                    }
                    contentBox.addView(buildFileRow(context, dp, "[$badge] $path", "+", color,
                        onDiff = { showFileDiffDialog(context, uiScope, projectDir, path) },
                        onToggleStage = {
                            uiScope.launch {
                                withContext(Dispatchers.IO) { GitManager.stageFile(projectDir, path) }
                                onRepoStateChanged(); refreshUi()
                            }
                        }
                    ))
                }
            }
        }

        refreshUi()
        dialog.setContentView(root)
        dialog.show()
    }

    private fun buildFileRow(
        context: Context,
        dp: Float,
        label: String,
        actionIcon: String,
        color: Int,
        onDiff: () -> Unit,
        onToggleStage: () -> Unit
    ): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((4 * dp).toInt(), (4 * dp).toInt(), (4 * dp).toInt(), (4 * dp).toInt())
            addView(TextView(context).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                text = label
                textSize = 12f
                typeface = Typeface.MONOSPACE
                setTextColor(color)
                setOnClickListener { onDiff() }
            })
            addView(Button(context, null, android.R.attr.borderlessButtonStyle).apply {
                text = "Diff"; isAllCaps = false; minWidth = 0; textSize = 11f
                setOnClickListener { onDiff() }
            })
            addView(Button(context, null, android.R.attr.borderlessButtonStyle).apply {
                text = actionIcon; minWidth = 0; textSize = 14f
                setOnClickListener { onToggleStage() }
            })
        }
    }

    private fun showFileDiffDialog(context: Context, uiScope: CoroutineScope, projectDir: File, relPath: String) {
        uiScope.launch {
            val diffText = withContext(Dispatchers.IO) {
                val headText = GitManager.getHeadFileContent(projectDir, relPath) ?: ""
                val diskFile = File(projectDir, relPath)
                val diskText = if (diskFile.isFile) diskFile.readText(Charsets.UTF_8) else ""
                GitDiffAndIgnore.LineDiffCalculator.formatInlineDiff(headText, diskText)
            }
            val tv = TextView(context).apply {
                text = diffText.ifBlank { "(Fark yok)" }
                typeface = Typeface.MONOSPACE
                textSize = 11f
                setPadding(24, 16, 24, 16)
                setTextIsSelectable(true)
            }
            AlertDialog.Builder(context)
                .setTitle("Diff: $relPath")
                .setView(ScrollView(context).apply { addView(tv) })
                .setPositiveButton("Kapat", null)
                .show()
        }
    }

    private fun showBranchDialog(context: Context, uiScope: CoroutineScope, projectDir: File, onChanged: () -> Unit) {
        uiScope.launch {
            val branches = withContext(Dispatchers.IO) { GitManager.listBranches(projectDir) }
            AlertDialog.Builder(context)
                .setTitle("Dallar (Branches)")
                .setItems(branches.toTypedArray()) { _, which ->
                    uiScope.launch {
                        val res = withContext(Dispatchers.IO) { runCatching { GitManager.checkoutBranch(projectDir, branches[which]) } }
                        res.onSuccess { Toast.makeText(context, "Dal değiştirildi: ${branches[which]}", Toast.LENGTH_SHORT).show(); onChanged() }
                            .onFailure { Toast.makeText(context, "Hata: ${it.message}", Toast.LENGTH_LONG).show() }
                    }
                }
                .setPositiveButton("+ Yeni Dal") { _, _ ->
                    val input = EditText(context).apply { hint = "Dal adı (ör. feature/yeni)"; setSingleLine() }
                    AlertDialog.Builder(context)
                        .setTitle("Yeni Dal Oluştur")
                        .setView(input)
                        .setPositiveButton("Oluştur") { _, _ ->
                            val name = input.text.toString().trim()
                            if (name.isEmpty()) return@setPositiveButton
                            uiScope.launch {
                                val res = withContext(Dispatchers.IO) { runCatching { GitManager.createAndCheckoutBranch(projectDir, name) } }
                                res.onSuccess { Toast.makeText(context, "Yeni dal: $name", Toast.LENGTH_SHORT).show(); onChanged() }
                                    .onFailure { Toast.makeText(context, "Hata: ${it.message}", Toast.LENGTH_LONG).show() }
                            }
                        }
                        .setNegativeButton("İptal", null)
                        .show()
                }
                .setNegativeButton("Kapat", null)
                .show()
        }
    }

    private fun showLogDialog(context: Context, uiScope: CoroutineScope, projectDir: File) {
        uiScope.launch {
            val entries = withContext(Dispatchers.IO) { GitManager.log(projectDir) }
            val text = if (entries.isEmpty()) {
                "Henüz commit kaydı yok."
            } else {
                entries.joinToString("\n\n") { "${it.shortHash} • ${it.dateText} (${it.author})\n${it.message}" }
            }
            AlertDialog.Builder(context)
                .setTitle("Commit Geçmişi (Git Log)")
                .setMessage(text)
                .setPositiveButton("Kapat", null)
                .show()
        }
    }

    private fun showPatVaultDialog(context: Context) {
        val (savedUser, savedToken) = GitManager.TokenVault.loadCredentials(context)
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 20, 40, 0)
        }
        val etUser = EditText(context).apply { hint = "GitHub Kullanıcı Adı"; setText(savedUser); setSingleLine() }
        val etPat = EditText(context).apply {
            hint = "Kişisel Erişim Anahtarı (PAT - Keystore şifreli)"
            setText(savedToken)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine()
        }
        box.addView(etUser); box.addView(etPat)
        AlertDialog.Builder(context)
            .setTitle("Git Kimlik Kasası (Android Keystore)")
            .setView(box)
            .setPositiveButton("Şifrele ve Kaydet") { _, _ ->
                GitManager.TokenVault.saveCredentials(context, etUser.text.toString(), etPat.text.toString())
                Toast.makeText(context, "✓ PAT Android Keystore ile şifrelenerek saklandı", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("İptal", null)
            .show()
    }

    private fun showCloneDialog(context: Context, uiScope: CoroutineScope, onCloned: (File) -> Unit) {
        val (savedUser, savedToken) = GitManager.TokenVault.loadCredentials(context)
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 20, 40, 0)
        }
        val etUrl = EditText(context).apply { hint = "HTTPS Depo URL (https://github.com/...)"; setSingleLine() }
        val etUser = EditText(context).apply { hint = "Kullanıcı Adı"; setText(savedUser); setSingleLine() }
        val etPat = EditText(context).apply {
            hint = "Kişisel Erişim Anahtarı (PAT)"
            setText(savedToken)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine()
        }
        box.addView(etUrl); box.addView(etUser); box.addView(etPat)

        AlertDialog.Builder(context)
            .setTitle("Git Deposu Klonla (HTTPS)")
            .setView(box)
            .setPositiveButton("Klonla") { _, _ ->
                val url = etUrl.text.toString().trim()
                if (url.isEmpty()) return@setPositiveButton
                val name = url.substringAfterLast('/').removeSuffix(".git").ifBlank { "ClonedProject" }
                val dest = File(ProjectManager.root(context), name)
                GitManager.TokenVault.saveCredentials(context, etUser.text.toString(), etPat.text.toString())
                Toast.makeText(context, "Klonlanıyor...", Toast.LENGTH_SHORT).show()
                uiScope.launch {
                    val res = withContext(Dispatchers.IO) {
                        runCatching { GitManager.clone(url, dest, etUser.text.toString(), etPat.text.toString()) }
                    }
                    res.onSuccess { cloned ->
                        ProjectManager.setCurrent(context, cloned)
                        Toast.makeText(context, "✓ Klonlandı: ${cloned.name}", Toast.LENGTH_LONG).show()
                        onCloned(cloned)
                    }.onFailure {
                        Toast.makeText(context, "Klonlama hatası: ${it.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
            .setNegativeButton("İptal", null)
            .show()
    }
}
