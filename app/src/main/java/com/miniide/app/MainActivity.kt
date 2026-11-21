package com.miniide.app

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import com.miniide.app.builder.BuildError
import com.miniide.app.builder.BuildExtensions
import com.miniide.app.project.ProjectManager
import com.miniide.app.project.ProjectTemplatesAndTools
import com.miniide.app.service.BuildEvents
import com.miniide.app.service.CompileService
import com.miniide.app.settings.SettingsAndThemes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Ana kontrol paneli: Proje yönetimi (Yeni Proje, Proje Seç, Sil, ZIP İçe/Dışa Aktar),
 * 6 aşamalı canlı derleme hattı, etkileşimli hata inceleyici ve APK kurulum/paylaşım.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var tvLog: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvStepBadge: TextView
    private lateinit var tvProject: TextView
    private lateinit var tvProjectMeta: TextView
    private lateinit var tvErrorsHeader: TextView
    private lateinit var cardErrors: View
    private lateinit var scrollLog: ScrollView
    private lateinit var scrollErrors: ScrollView
    private lateinit var llErrors: LinearLayout
    private lateinit var progress: ProgressBar
    private lateinit var btnInstall: Button
    private lateinit var btnRun: Button
    private lateinit var btnShareApk: Button
    private lateinit var btnBuild: Button
    private lateinit var btnCancel: Button

    private var autoInstallAfterBuild = false

    private val importLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importZip(uri)
    }

    // Android 13+ bildirim izni; reddedilse bile derleme çalışmaya devam eder.
    private val notifPermLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SettingsAndThemes.CrashReporter.install(this)
        setContentView(R.layout.activity_main)

        tvLog = findViewById(R.id.tvLog)
        tvStatus = findViewById(R.id.tvStatus)
        tvStepBadge = findViewById(R.id.tvStepBadge)
        tvProject = findViewById(R.id.tvProject)
        tvProjectMeta = findViewById(R.id.tvProjectMeta)
        tvErrorsHeader = findViewById(R.id.tvErrorsHeader)
        cardErrors = findViewById(R.id.cardErrors)
        scrollLog = findViewById(R.id.scrollLog)
        scrollErrors = findViewById(R.id.scrollErrors)
        llErrors = findViewById(R.id.llErrors)
        progress = findViewById(R.id.progress)
        btnInstall = findViewById(R.id.btnInstall)
        btnRun = findViewById(R.id.btnRun)
        btnShareApk = findViewById(R.id.btnShareApk)
        btnBuild = findViewById(R.id.btnBuild)
        btnCancel = findViewById(R.id.btnCancel)

        try {
            ProjectManager.ensureSample(this)
        } catch (e: Exception) {
            Toast.makeText(this, "Örnek proje çıkarılamadı: ${e.message}", Toast.LENGTH_LONG).show()
        }

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        findViewById<Button>(R.id.btnNewProject).setOnClickListener { showNewProjectDialog() }
        btnBuild.setOnClickListener { startBuild() }
        btnCancel.setOnClickListener {
            if (BuildEvents.running) {
                val step = BuildEvents.currentStep
                if (step.startsWith("ECJ") || step.startsWith("D8")) {
                    // ECJ/D8 kütüphane çağrılarıdır; ortada kesilemez, adım bitince durur.
                    Toast.makeText(this, "İptal istendi; $step adımı bitince derleme durdurulacak.", Toast.LENGTH_LONG).show()
                }
                startService(Intent(this, CompileService::class.java).setAction(CompileService.ACTION_CANCEL))
            }
        }
        btnInstall.setOnClickListener { onInstall() }
        btnRun.setOnClickListener { onRun() }
        btnShareApk.setOnClickListener { onShareApk() }
        findViewById<Button>(R.id.btnCopyLog).setOnClickListener { copy("Derleme günlüğü", BuildEvents.fullLog()) }
        findViewById<Button>(R.id.btnClearLog).setOnClickListener {
            BuildEvents.reset()
            tvLog.text = ""
            showErrors(emptyList())
            refreshButtons()
        }

        findViewById<Button>(R.id.btnProject).setOnClickListener { pickProject() }
        findViewById<Button>(R.id.btnFiles).setOnClickListener { openFiles() }
        findViewById<Button>(R.id.btnImport).setOnClickListener { if (canSwitchProject()) importLauncher.launch(arrayOf("*/*")) }
        findViewById<Button>(R.id.btnExport).setOnClickListener { exportCurrentProject() }

        if (intent?.getBooleanExtra(EXTRA_AUTO_BUILD, false) == true) {
            autoInstallAfterBuild = intent.getBooleanExtra(EXTRA_AUTO_INSTALL_AFTER_BUILD, false)
            intent.removeExtra(EXTRA_AUTO_BUILD)
            intent.removeExtra(EXTRA_AUTO_INSTALL_AFTER_BUILD)
            tvLog.post { startBuild() }
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent?.getBooleanExtra(EXTRA_AUTO_BUILD, false) == true) {
            autoInstallAfterBuild = intent.getBooleanExtra(EXTRA_AUTO_INSTALL_AFTER_BUILD, false)
            intent.removeExtra(EXTRA_AUTO_BUILD)
            intent.removeExtra(EXTRA_AUTO_INSTALL_AFTER_BUILD)
            tvLog.post { startBuild() }
        }
    }

    override fun onStart() {
        super.onStart()
        val logText = BuildEvents.fullLog()
        tvLog.text = logText.ifEmpty { "Mini Android IDE hazır.\nBir proje seçip '▶ APK Derle' düğmesine dokunun.\n" }
        scrollLog.post { scrollLog.fullScroll(View.FOCUS_DOWN) }
        showErrors(BuildEvents.lastErrors)
        refreshButtons()

        BuildEvents.listener = { line ->
            runOnUiThread {
                tvLog.append(line + "\n")
                scrollLog.post { scrollLog.fullScroll(View.FOCUS_DOWN) }
            }
        }
        BuildEvents.progress = { step, p ->
            runOnUiThread {
                progress.progress = p
                tvStepBadge.text = "$step • %$p"
            }
        }
        BuildEvents.errorsListener = { list -> runOnUiThread { showErrors(list) } }
        BuildEvents.finished = {
            runOnUiThread {
                refreshButtons()
                if (autoInstallAfterBuild) {
                    autoInstallAfterBuild = false
                    val apk = BuildEvents.lastApk
                    if (apk != null && apk.exists() && BuildEvents.lastErrors.none { it.isError }) {
                        onInstall()
                    }
                }
            }
        }
    }

    override fun onStop() {
        BuildEvents.listener = null
        BuildEvents.progress = null
        BuildEvents.errorsListener = null
        BuildEvents.finished = null
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        refreshButtons()
    }

    // ---------- Proje Yönetimi ----------

    private fun showNewProjectDialog() {
        if (!canSwitchProject()) return
        val dp = resources.displayMetrics.density
        val pad = (18 * dp).toInt()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }

        // Uygulama adı yazılırken paket adı otomatik üretilir; kullanıcı paketi elle değiştirirse otomasyon durur.
        var autoFilling = false
        var pkgEditedByUser = false

        val etName = EditText(this).apply {
            hint = "Uygulama Adı (ör. Not Defteri)"
            setSingleLine()
            setSelectAllOnFocus(true)
        }
        val etPkg = EditText(this).apply {
            hint = "Paket Adı (ör. com.ornek.notdefteri)"
            setSingleLine()
        }
        etPkg.doAfterTextChanged { if (!autoFilling) pkgEditedByUser = true }
        etName.doAfterTextChanged { text ->
            if (!pkgEditedByUser) {
                autoFilling = true
                etPkg.setText(ProjectTemplatesAndTools.suggestPackageName(text?.toString().orEmpty()))
                autoFilling = false
            }
        }
        etName.setText("YeniUygulama")

        val templates = ProjectTemplatesAndTools.ProjectTemplate.entries.toList()
        val spTemplate = android.widget.Spinner(this).apply {
            adapter = TemplateAdapter(this@MainActivity, templates)
        }
        val minSdks = listOf(21, 24, 26, 29, 31)
        val spMinSdk = android.widget.Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                minSdks.map { "minSdk: API $it" }
            )
        }
        box.addView(etName)
        box.addView(etPkg)
        box.addView(spTemplate)
        box.addView(spMinSdk)

        // Klavye açıkken Spinner'lara ulaşılabilsin diye içerik kaydırılabilir.
        val scroll = ScrollView(this).apply { addView(box) }

        val dialog = AlertDialog.Builder(this)
            .setTitle("Yeni Android Projesi Oluştur")
            .setView(scroll)
            .setPositiveButton("Oluştur") { _, _ ->
                try {
                    val tpl = templates[spTemplate.selectedItemPosition.coerceIn(0, templates.lastIndex)]
                    val sdk = minSdks[spMinSdk.selectedItemPosition.coerceIn(0, minSdks.lastIndex)]
                    val dir = ProjectTemplatesAndTools.createProjectFromTemplate(
                        ProjectManager.root(this),
                        etName.text.toString(),
                        etPkg.text.toString(),
                        tpl,
                        sdk
                    )
                    ProjectManager.setCurrent(this, dir)
                    resetUiForProjectSwitch(dir)
                    Toast.makeText(this, "Proje oluşturuldu: ${dir.name} (${tpl.titleTr})", Toast.LENGTH_SHORT).show()
                    openFiles()
                } catch (e: Exception) {
                    Toast.makeText(this, "Hata: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("İptal", null)
            .create()
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        dialog.show()
    }

    private fun pickProject() {
        if (!canSwitchProject()) return
        val projects = ProjectManager.list(this)
        if (projects.isEmpty()) {
            Toast.makeText(this, "Henüz proje yok. Yeni proje oluşturun veya ZIP içe aktarın.", Toast.LENGTH_LONG).show()
            return
        }
        val currentDir = ProjectManager.current(this)
        val names = projects.map { p ->
            val info = ProjectManager.getInfo(p)
            val prefix = if (currentDir?.name == p.name) "✓ " else "   "
            "$prefix${p.name} (${info.packageName})"
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Proje Seç (${projects.size})")
            .setItems(names) { _, i ->
                ProjectManager.setCurrent(this, projects[i])
                resetUiForProjectSwitch(projects[i])
            }
            .setPositiveButton("+ Yeni Proje") { _, _ -> showNewProjectDialog() }
            .setNeutralButton("Örneği Sıfırla") { _, _ -> confirmResetSample() }
            .setNegativeButton("Aktif Projeyi Sil") { _, _ ->
                currentDir?.let { confirmDeleteProject(it) }
            }
            .show()
    }

    private fun confirmDeleteProject(projectDir: File) {
        if (!canSwitchProject()) return
        AlertDialog.Builder(this)
            .setTitle("Proje Silinsin mi?")
            .setMessage("\"${projectDir.name}\" projesi ve içindeki tüm kaynak dosyaları kalıcı olarak silinecek.")
            .setPositiveButton("Sil") { _, _ ->
                ProjectManager.deleteProject(this, projectDir)
                Toast.makeText(this, "${projectDir.name} silindi", Toast.LENGTH_SHORT).show()
                resetUiForProjectSwitch(ProjectManager.current(this))
            }
            .setNegativeButton("İptal", null)
            .show()
    }

    private fun confirmResetSample() {
        if (!canSwitchProject()) return
        AlertDialog.Builder(this)
            .setTitle("Örnek proje sıfırlansın mı?")
            .setMessage("Göçbil Hatırlatıcı örnek projesindeki tüm düzenlemelerin silinir ve ilk haline döner.")
            .setPositiveButton("Sıfırla") { _, _ ->
                try {
                    ProjectManager.resetSample(this)
                    Toast.makeText(this, "Örnek proje sıfırlandı", Toast.LENGTH_SHORT).show()
                    resetUiForProjectSwitch(ProjectManager.current(this))
                } catch (e: Exception) {
                    Toast.makeText(this, "Sıfırlanamadı: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("İptal", null)
            .show()
    }

    private fun openFiles() {
        val p = ProjectManager.current(this)
        if (p == null) {
            Toast.makeText(this, "Önce bir proje seçin veya yeni proje oluşturun", Toast.LENGTH_LONG).show()
            return
        }
        startActivity(
            Intent(this, EditorActivity::class.java)
                .putExtra(EditorActivity.EXTRA_PROJECT, p.absolutePath)
        )
    }

    private fun importZip(uri: Uri) {
        Toast.makeText(this, "ZIP içe aktarılıyor...", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) { ProjectManager.importZip(this@MainActivity, uri) }
                ProjectManager.setCurrent(this@MainActivity, result.projectDir)
                resetUiForProjectSwitch(result.projectDir)
                Toast.makeText(this@MainActivity, "İçe aktarıldı: ${result.projectDir.name}", Toast.LENGTH_LONG).show()
                if (result.notes.isNotEmpty()) {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("İçe aktarma notları")
                        .setMessage(result.notes.joinToString("\n\n") { "• $it" })
                        .setPositiveButton("Tamam", null)
                        .show()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "İçe aktarılamadı: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun exportCurrentProject() {
        val p = ProjectManager.current(this)
        if (p == null) {
            Toast.makeText(this, "Dışa aktarılacak aktif proje yok", Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(this, "Proje ZIP olarak hazırlanıyor...", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            try {
                val zipFile = withContext(Dispatchers.IO) { ProjectManager.exportZip(this@MainActivity, p) }
                ApkInstaller.shareFile(this@MainActivity, zipFile, "application/zip", "Projeyi Paylaş (${zipFile.name})")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "Dışa aktarma başarısız: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    /** Proje değiştiğinde önceki projenin log / hata / APK bilgisini temizler. */
    private fun resetUiForProjectSwitch(newProject: File?) {
        if (!BuildEvents.matchesProject(newProject)) {
            BuildEvents.reset()
            tvLog.text = ""
            showErrors(emptyList())
            BuildEvents.post("Proje seçildi: ${newProject?.name ?: "-"}")
            BuildEvents.post("Derlemek için '▶ APK Derle' düğmesine dokunun.")
        }
        refreshButtons()
    }

    /** Derleme sürerken proje değiştirmeyi engeller. */
    private fun canSwitchProject(): Boolean {
        if (BuildEvents.running) {
            Toast.makeText(this, "Derleme devam ederken proje değiştirilemez. Önce derlemeyi iptal edin.", Toast.LENGTH_LONG).show()
            return false
        }
        return true
    }

    // ---------- Derleme ----------

    private fun startBuild() {
        val p = ProjectManager.current(this)
        if (p == null) {
            Toast.makeText(this, "Önce bir proje seçin veya oluşturun", Toast.LENGTH_LONG).show()
            return
        }
        tvLog.text = ""
        progress.progress = 0
        showErrors(emptyList())
        btnInstall.isEnabled = false
        btnRun.isEnabled = false
        btnShareApk.isEnabled = false
        tvStatus.text = "Derleniyor: ${p.name}..."
        tvStepBadge.text = "Hazırlık • %0"
        val i = Intent(this, CompileService::class.java).putExtra(CompileService.EXTRA_PROJECT, p.absolutePath)
        ContextCompat.startForegroundService(this, i)
    }

    private fun refreshButtons() {
        val cur = ProjectManager.current(this)
        val info = cur?.let { ProjectManager.getInfo(it) }

        val apk = currentAvailableApk()
        val pkg = currentAvailablePackage()
        val ok = apk != null && apk.exists() && !BuildEvents.running

        btnInstall.isEnabled = ok
        btnShareApk.isEnabled = ok
        btnRun.isEnabled = ok && pkg != null
        btnBuild.isEnabled = !BuildEvents.running && cur != null
        btnCancel.isEnabled = BuildEvents.running

        if (info != null) {
            tvProject.text = info.name
            tvProjectMeta.text = "${info.packageName} • v${info.versionName} • ${info.javaCount} Java, ${info.xmlCount} XML • SDK ${info.minSdk}→${info.targetSdk}"
        } else {
            tvProject.text = "Proje seçilmedi"
            tvProjectMeta.text = "Yeni proje oluşturun veya ZIP içe aktarın"
        }

        progress.progress = if (BuildEvents.running) BuildEvents.currentPercent else if (ok) 100 else 0
        tvStepBadge.text = when {
            BuildEvents.running -> "${BuildEvents.currentStep} • %${BuildEvents.currentPercent}"
            ok -> {
                val kb = (apk?.length() ?: 0L) / 1024
                "APK (${kb} KB)"
            }
            else -> "Hazırlık › AAPT2 › ECJ › D8 › İmza"
        }

        tvStatus.text = when {
            BuildEvents.running -> "⚡ Derleniyor (${BuildEvents.currentStep})..."
            BuildEvents.lastErrors.any { it.isError } ->
                "⛔ Derleme başarısız: ${BuildEvents.lastErrors.count { it.isError }} hata"
            ok -> "✓ APK Hazır: ${apk!!.name}"
            else -> "Hazır"
        }
    }

    private fun currentAvailableApk(): File? {
        val cur = ProjectManager.current(this) ?: return null
        // Son derleme yalnızca AYNI projeye aitse kullanılır; aksi halde projenin kendi bin/ klasörüne bakılır.
        if (BuildEvents.matchesProject(cur)) {
            BuildEvents.lastApk?.takeIf { it.exists() }?.let { return it }
        }
        return ProjectManager.getInfo(cur).builtApk
    }

    private fun currentAvailablePackage(): String? {
        val cur = ProjectManager.current(this) ?: return null
        if (BuildEvents.matchesProject(cur)) {
            BuildEvents.lastPackage?.let { return it }
        }
        return ProjectManager.getInfo(cur).packageName
    }

    private fun onInstall() {
        val apk = currentAvailableApk() ?: return
        when (ApkInstaller.install(this, apk)) {
            ApkInstaller.Result.STARTED -> Toast.makeText(this, "Paket yükleyici açılıyor...", Toast.LENGTH_SHORT).show()
            ApkInstaller.Result.NEEDS_PERMISSION -> Toast.makeText(
                this,
                "Önce 'Bilinmeyen uygulamaları yükle' izni verin, ardından Kur'a tekrar dokunun.",
                Toast.LENGTH_LONG
            ).show()
            ApkInstaller.Result.FILE_MISSING -> Toast.makeText(this, "APK bulunamadı, lütfen tekrar derleyin.", Toast.LENGTH_LONG).show()
        }
    }

    private fun onRun() {
        val pkg = currentAvailablePackage() ?: return
        if (!ApkInstaller.launch(this, pkg)) {
            Toast.makeText(this, "$pkg cihazda kurulu değil. Önce '📲 Kur' düğmesine basın.", Toast.LENGTH_LONG).show()
        }
    }

    private fun onShareApk() {
        val apk = currentAvailableApk() ?: return
        ApkInstaller.shareFile(this, apk, "application/vnd.android.package-archive", "Üretilen APK'yı Paylaş")
    }

    // ---------- Etkileşimli Hata ve Uyarı Listesi ----------

    private fun showErrors(errors: List<BuildError>) {
        llErrors.removeAllViews()
        if (errors.isEmpty()) {
            cardErrors.visibility = View.GONE
            return
        }
        val errCount = errors.count { it.isError }
        val warnCount = errors.size - errCount
        tvErrorsHeader.text = "⛔ Hatalar: $errCount   ⚠ Uyarılar: $warnCount"
        cardErrors.visibility = View.VISIBLE

        val dp = resources.displayMetrics.density
        for (e in errors) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding((8 * dp).toInt(), (6 * dp).toInt(), (8 * dp).toInt(), (6 * dp).toInt())
                setOnClickListener { showErrorDialog(e) }
            }

            val tv = TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                text = (if (e.isError) "⛔ " else "⚠ ") + e.location() + "\n" + e.messageTr
                textSize = 12.5f
                setTextColor(if (e.isError) Color.rgb(0xDC, 0x26, 0x26) else Color.rgb(0xD9, 0x77, 0x06))
            }
            row.addView(tv)

            val f = errorFile(e)
            if (f != null) {
                val btnOpen = TextView(this).apply {
                    text = "Editörde Aç ›"
                    textSize = 11.5f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(Color.rgb(0x25, 0x63, 0xEB))
                    setPadding((10 * dp).toInt(), (6 * dp).toInt(), (4 * dp).toInt(), (6 * dp).toInt())
                    setOnClickListener {
                        startActivity(
                            Intent(this@MainActivity, EditorActivity::class.java)
                                .putExtra(EditorActivity.EXTRA_PATH, f.absolutePath)
                                .putExtra(EditorActivity.EXTRA_LINE, e.line)
                        )
                    }
                }
                row.addView(btnOpen)
            }
            llErrors.addView(row)
        }
    }

    private fun errorFile(e: BuildError): File? {
        val rel = e.normalizedFile() ?: return null
        val baseDir = BuildEvents.lastProject ?: ProjectManager.current(this) ?: return null
        val f = if (rel.startsWith("/")) File(rel) else File(baseDir, rel)
        return if (f.isFile) f else null
    }

    private fun showErrorDialog(e: BuildError) {
        val quickFix = BuildExtensions.QuickFixEngine.suggestFix(e)
        val b = AlertDialog.Builder(this)
            .setTitle(e.location())
            .setMessage(e.asText() + if (quickFix != null) "\n\n💡 Öneri: ${quickFix.titleTr}" else "")
            .setPositiveButton("Kapat", null)

        val f = errorFile(e)
        if (quickFix != null) {
            b.setNeutralButton("⚡ Düzelt") { _, _ ->
                applyQuickFix(quickFix, f)
            }
        } else {
            b.setNeutralButton("Kopyala") { _, _ -> copy("Hata", e.asText()) }
        }

        if (f != null) {
            b.setNegativeButton("Editörde Aç") { _, _ ->
                startActivity(
                    Intent(this, EditorActivity::class.java)
                        .putExtra(EditorActivity.EXTRA_PATH, f.absolutePath)
                        .putExtra(EditorActivity.EXTRA_LINE, e.line)
                )
            }
        }
        b.show()
    }

    private fun applyQuickFix(fix: BuildExtensions.QuickFix, targetFile: File?) {
        when (fix) {
            is BuildExtensions.QuickFix.AddJavaImport -> {
                if (targetFile == null || !targetFile.isFile) {
                    Toast.makeText(this, "Hedef Java dosyası bulunamadı", Toast.LENGTH_SHORT).show()
                    return
                }
                val updated = BuildExtensions.QuickFixEngine.applyJavaImport(targetFile.readText(Charsets.UTF_8), fix.importFqn)
                targetFile.writeText(updated, Charsets.UTF_8)
                Toast.makeText(this, "✓ Eklendi: import ${fix.importFqn};", Toast.LENGTH_SHORT).show()
            }
            is BuildExtensions.QuickFix.AddManifestPermission -> {
                val proj = BuildEvents.lastProject ?: ProjectManager.current(this) ?: return
                val manifest = File(proj, "AndroidManifest.xml")
                if (!manifest.isFile) return
                val updated = BuildExtensions.QuickFixEngine.applyManifestPermission(manifest.readText(Charsets.UTF_8), fix.permission)
                manifest.writeText(updated, Charsets.UTF_8)
                Toast.makeText(this, "✓ Manifest izni eklendi: ${fix.permission}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun copy(label: String, text: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(this, "Panoya kopyalandı", Toast.LENGTH_SHORT).show()
    }

    companion object {
        const val EXTRA_AUTO_BUILD = "auto_build"
        const val EXTRA_AUTO_INSTALL_AFTER_BUILD = "auto_install_after_build"
    }
}

/** Şablon seçici: kalın başlık + altında tam görünen açıklama (tek satırda kesilmez). */
private class TemplateAdapter(
    ctx: Context,
    private val items: List<ProjectTemplatesAndTools.ProjectTemplate>
) : ArrayAdapter<ProjectTemplatesAndTools.ProjectTemplate>(ctx, android.R.layout.simple_list_item_2, items) {

    private fun bind(position: Int, convertView: View?, parent: ViewGroup): View {
        val v = convertView ?: LayoutInflater.from(context).inflate(android.R.layout.simple_list_item_2, parent, false)
        val t = items[position]
        v.findViewById<TextView>(android.R.id.text1).apply {
            text = t.titleTr
            setTypeface(null, Typeface.BOLD)
        }
        v.findViewById<TextView>(android.R.id.text2).apply {
            text = t.descriptionTr
            maxLines = 3
        }
        return v
    }

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View = bind(position, convertView, parent)
    override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View = bind(position, convertView, parent)
}
