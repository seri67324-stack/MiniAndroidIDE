package com.miniide.app.project

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Proje klasörleri yönetimi: filesDir/projects/<ad>/
 * Yeni proje oluşturma, ZIP içe/dışa aktarma, örnek proje sıfırlama ve proje meta verisi okuma.
 */
object ProjectManager {
    private const val PREFS = "ide_prefs"
    private const val KEY_CURRENT = "current_project"
    const val SAMPLE = "GocbilHatirlatici"

    data class ProjectInfo(
        val dir: File,
        val name: String,
        val packageName: String,
        val versionName: String,
        val minSdk: Int,
        val targetSdk: Int,
        val javaCount: Int,
        val xmlCount: Int,
        val builtApk: File?
    )

    fun root(ctx: Context): File = File(ctx.filesDir, "projects").apply { mkdirs() }

    fun list(ctx: Context): List<File> =
        root(ctx).listFiles { f -> f.isDirectory && File(f, "AndroidManifest.xml").exists() }
            ?.sortedBy { it.name.lowercase() } ?: emptyList()

    fun current(ctx: Context): File? {
        val name = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_CURRENT, null)
        if (name != null) {
            val f = File(root(ctx), name)
            if (f.isDirectory && File(f, "AndroidManifest.xml").exists()) return f
        }
        return list(ctx).firstOrNull()
    }

    fun setCurrent(ctx: Context, project: File) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_CURRENT, project.name).apply()
    }

    fun getInfo(projectDir: File): ProjectInfo {
        val manifest = File(projectDir, "AndroidManifest.xml")
        val text = if (manifest.exists()) manifest.readText() else ""
        val pkg = Regex("""package\s*=\s*"([^"]+)"""").find(text)?.groupValues?.get(1) ?: "com.example.app"
        val ver = Regex("""android:versionName\s*=\s*"([^"]+)"""").find(text)?.groupValues?.get(1) ?: "1.0"
        val minSdk = Regex("""android:minSdkVersion\s*=\s*"(\d+)"""").find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 21
        val targetSdk = Regex("""android:targetSdkVersion\s*=\s*"(\d+)"""").find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 34

        var javaCount = 0
        var xmlCount = 0
        File(projectDir, "src").takeIf { it.exists() }?.walkTopDown()?.forEach {
            if (it.isFile && it.extension.equals("java", ignoreCase = true)) javaCount++
        }
        File(projectDir, "res").takeIf { it.exists() }?.walkTopDown()?.forEach {
            if (it.isFile && it.extension.equals("xml", ignoreCase = true)) xmlCount++
        }

        val apk = File(projectDir, "bin/${projectDir.name}-debug.apk").takeIf { it.exists() }
        return ProjectInfo(
            dir = projectDir,
            name = projectDir.name,
            packageName = pkg,
            versionName = ver,
            minSdk = minSdk,
            targetSdk = targetSdk,
            javaCount = javaCount,
            xmlCount = xmlCount,
            builtApk = apk
        )
    }

    /** Örnek proje yoksa assets içinden çıkarır. Var olan projeye dokunmaz. */
    fun ensureSample(ctx: Context) {
        if (!File(root(ctx), SAMPLE).exists()) resetSample(ctx)
    }

    /** Örnek projeyi silip assets'ten yeniden çıkarır. */
    fun resetSample(ctx: Context) {
        File(root(ctx), SAMPLE).deleteRecursively()
        ctx.assets.open("GocbilHatirlatici.zip").use { unzip(it, root(ctx)) }
    }

    /** Sıfırdan modern bir Android projesi oluşturur. */
    fun createNewProject(ctx: Context, appName: String, packageName: String): File {
        val cleanAppName = appName.trim().ifBlank { "YeniUygulama" }
        val folderBase = cleanAppName.replace(Regex("[^\\w.\\-]"), "").ifBlank { "YeniUygulama" }
        val pkg = packageName.trim().lowercase()
        require(Regex("^[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+$").matches(pkg)) {
            "Geçersiz paket adı (örnek: com.ornek.uygulama)"
        }

        var projectDir = File(root(ctx), folderBase)
        var suffix = 2
        while (projectDir.exists()) {
            projectDir = File(root(ctx), "$folderBase-$suffix")
            suffix++
        }

        val pkgDir = File(projectDir, "src/" + pkg.replace('.', '/')).apply { mkdirs() }
        val layoutDir = File(projectDir, "res/layout").apply { mkdirs() }
        val valuesDir = File(projectDir, "res/values").apply { mkdirs() }
        val drawableDir = File(projectDir, "res/drawable").apply { mkdirs() }
        File(projectDir, "assets").mkdirs()
        File(projectDir, "libs").mkdirs()

        File(projectDir, "AndroidManifest.xml").writeText(
            """<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="$pkg"
    android:versionCode="1"
    android:versionName="1.0">

    <uses-sdk
        android:minSdkVersion="21"
        android:targetSdkVersion="34" />

    <application
        android:allowBackup="true"
        android:icon="@drawable/ic_launcher"
        android:label="@string/app_name"
        android:supportsRtl="true"
        android:theme="@android:style/Theme.Material.Light.NoActionBar">

        <activity
            android:name=".MainActivity"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>

    </application>
</manifest>
"""
        )

        File(valuesDir, "strings.xml").writeText(
            """<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="app_name">${escapeXml(cleanAppName)}</string>
    <string name="welcome_title">${escapeXml(cleanAppName)}</string>
    <string name="welcome_subtitle">Mini Android IDE ile geliştirildi</string>
</resources>
"""
        )

        File(valuesDir, "colors.xml").writeText(
            """<?xml version="1.0" encoding="utf-8"?>
<resources>
    <color name="primary">#2563EB</color>
    <color name="primary_dark">#1E40AF</color>
    <color name="bg_page">#F8FAFC</color>
    <color name="card_bg">#FFFFFF</color>
    <color name="text_primary">#0F172A</color>
    <color name="text_secondary">#475569</color>
</resources>
"""
        )

        File(drawableDir, "ic_launcher.xml").writeText(
            """<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="72dp"
    android:height="72dp"
    android:viewportWidth="72"
    android:viewportHeight="72">
    <path
        android:fillColor="#2563EB"
        android:pathData="M16,0h40c8.8,0 16,7.2 16,16v40c0,8.8 -7.2,16 -16,16H16C7.2,72 0,64.8 0,56V16C0,7.2 7.2,0 16,0z" />
    <path
        android:fillColor="#FFFFFF"
        android:pathData="M28,24L16,36L28,48L31,45L22,36L31,27Z" />
    <path
        android:fillColor="#93C5FD"
        android:pathData="M44,24L41,27L50,36L41,45L44,48L56,36Z" />
</vector>
"""
        )

        File(drawableDir, "card_bg.xml").writeText(
            """<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android">
    <solid android:color="@color/card_bg" />
    <corners android:radius="16dp" />
    <stroke android:width="1dp" android:color="#E2E8F0" />
</shape>
"""
        )

        File(drawableDir, "btn_primary.xml").writeText(
            """<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android">
    <solid android:color="@color/primary" />
    <corners android:radius="12dp" />
</shape>
"""
        )

        File(layoutDir, "activity_main.xml").writeText(
            """<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:background="@color/bg_page"
    android:gravity="center"
    android:orientation="vertical"
    android:padding="24dp">

    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:background="@drawable/card_bg"
        android:gravity="center_horizontal"
        android:orientation="vertical"
        android:padding="24dp">

        <TextView
            android:id="@+id/tvTitle"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:text="@string/welcome_title"
            android:textColor="@color/text_primary"
            android:textSize="24sp"
            android:textStyle="bold" />

        <TextView
            android:id="@+id/tvSubtitle"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:layout_marginTop="8dp"
            android:text="@string/welcome_subtitle"
            android:textColor="@color/text_secondary"
            android:textSize="14sp" />

        <TextView
            android:id="@+id/tvCounter"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:layout_marginTop="24dp"
            android:text="Sayaç: 0"
            android:textColor="@color/primary"
            android:textSize="32sp"
            android:textStyle="bold" />

        <Button
            android:id="@+id/btnAction"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginTop="24dp"
            android:background="@drawable/btn_primary"
            android:padding="12dp"
            android:text="Artır (+1)"
            android:textColor="#FFFFFF"
            android:textStyle="bold" />
    </LinearLayout>

</LinearLayout>
"""
        )

        File(pkgDir, "MainActivity.java").writeText(
            """package $pkg;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {

    private int count = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        final TextView tvCounter = findViewById(R.id.tvCounter);
        Button btnAction = findViewById(R.id.btnAction);

        btnAction.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                count++;
                tvCounter.setText("Sayaç: " + count);
                if (count % 5 == 0) {
                    Toast.makeText(MainActivity.this, "Harika! Sayaç: " + count, Toast.LENGTH_SHORT).show();
                }
            }
        });
    }
}
"""
        )

        setCurrent(ctx, projectDir)
        return projectDir
    }

    /** Projeyi (bin/ ve gen/ hariç) paylaşılabilir bir ZIP dosyasına paketler. */
    fun exportZip(ctx: Context, projectDir: File): File {
        val exportDir = File(ctx.cacheDir, "exports").apply { mkdirs() }
        val outZip = File(exportDir, "${projectDir.name}.zip")
        if (outZip.exists()) outZip.delete()

        val rootPath = projectDir.canonicalPath
        ZipOutputStream(FileOutputStream(outZip)).use { zos ->
            projectDir.walkTopDown()
                .onEnter { dir ->
                    val rel = dir.canonicalPath.removePrefix(rootPath).trimStart(File.separatorChar)
                    rel != "bin" && rel != "gen" && !rel.startsWith("bin" + File.separator) && !rel.startsWith("gen" + File.separator)
                }
                .filter { it.isFile }
                .forEach { file ->
                    val rel = file.canonicalPath.removePrefix(rootPath).trimStart(File.separatorChar).replace('\\', '/')
                    val entryName = "${projectDir.name}/$rel"
                    zos.putNextEntry(ZipEntry(entryName))
                    file.inputStream().use { it.copyTo(zos) }
                    zos.closeEntry()
                }
        }
        return outZip
    }

    fun deleteProject(ctx: Context, projectDir: File): Boolean {
        val ok = projectDir.deleteRecursively()
        val cur = current(ctx)
        if (cur == null || !cur.exists()) {
            list(ctx).firstOrNull()?.let { setCurrent(ctx, it) }
        }
        return ok
    }

    private fun escapeXml(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "\\'")

    fun unzip(input: InputStream, dest: File) {
        dest.mkdirs()
        val base = dest.canonicalPath + File.separator
        ZipInputStream(input).use { zis ->
            var e = zis.nextEntry
            while (e != null) {
                val out = File(dest, e.name)
                if (!out.canonicalPath.startsWith(base)) throw SecurityException("Geçersiz zip girdisi: ${e.name}")
                if (e.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    out.outputStream().use { zis.copyTo(it) }
                }
                e = zis.nextEntry
            }
        }
    }

    /** İçe aktarma sonucu: oluşan proje klasörü + kullanıcıya gösterilecek notlar. */
    data class ImportResult(
        val projectDir: File,
        val notes: List<String> = emptyList(),
        val hasKotlinFiles: Boolean = false
    )

    /** Zip'ten proje içe aktarır. Klasik düzen ve Gradle (app/src/main) düzeni desteklenir. */
    fun importZip(ctx: Context, uri: Uri): ImportResult {
        val tmp = File(ctx.cacheDir, "import_${System.currentTimeMillis()}").apply { mkdirs() }
        try {
            val stream = ctx.contentResolver.openInputStream(uri) ?: throw IOException("Dosya açılamadı")
            stream.use { unzip(it, tmp) }
            return importFromExtracted(tmp, displayName(ctx, uri).removeSuffix(".zip"), root(ctx))
        } finally {
            tmp.deleteRecursively()
        }
    }

    /**
     * Çıkarılmış zip içeriğinden (tmp) proje üretir. Android'e bağımlı değildir, birim testle denenebilir.
     * @param fallbackName manifest zip kökündeyse proje adı olarak kullanılacak ad.
     */
    fun importFromExtracted(tmp: File, fallbackName: String, projectsRoot: File): ImportResult {
        val manifestDir = findManifestDir(tmp)
            ?: throw IOException(
                "AndroidManifest.xml bulunamadı. Desteklenen yapı: kökte AndroidManifest.xml + src/ + res/ " +
                    "veya Gradle düzeni (app/src/main/AndroidManifest.xml)."
            )

        val isGradle = manifestDir.name == "main" && manifestDir.parentFile?.name == "src"
        val moduleDir = if (isGradle) manifestDir.parentFile!!.parentFile!! else manifestDir

        var name = if (isGradle) {
            val parent = moduleDir.parentFile
            if (moduleDir != tmp && parent != null && parent != tmp) parent.name
            else if (moduleDir != tmp && moduleDir.name != "app") moduleDir.name
            else fallbackName
        } else {
            if (manifestDir == tmp) fallbackName else manifestDir.name
        }
        name = name.replace(Regex("[^\\w.\\- ]"), "_").trim().ifBlank { "Proje" }

        projectsRoot.mkdirs()
        var dest = File(projectsRoot, name)
        var n = 2
        while (dest.exists()) { dest = File(projectsRoot, "$name-$n"); n++ }

        return try {
            if (isGradle) {
                normalizeGradleProject(moduleDir, dest)
            } else {
                if (!manifestDir.copyRecursively(dest)) throw IOException("Proje kopyalanamadı")
                val kt = hasKotlin(File(dest, "src"))
                ImportResult(
                    dest,
                    if (kt) listOf(KOTLIN_NOTE) else emptyList(),
                    kt
                )
            }
        } catch (e: Exception) {
            dest.deleteRecursively()
            throw e
        }
    }

    private const val KOTLIN_NOTE =
        "Projede .kt (Kotlin) dosyaları var; cihaz üzerinde yalnızca Java derlenebilir."

    private fun hasKotlin(dir: File): Boolean =
        dir.exists() && dir.walkTopDown().any {
            it.isFile && (it.extension.equals("kt", true) || it.extension.equals("kts", true))
        }

    /**
     * Gradle modülünü (moduleDir/src/main/...) klasik düzene (dest/AndroidManifest.xml, src/, res/, assets/, libs/) çevirir.
     */
    private fun normalizeGradleProject(moduleDir: File, dest: File): ImportResult {
        val main = File(moduleDir, "src/main")
        val notes = ArrayList<String>()
        dest.mkdirs()

        val gradleText = listOf("build.gradle.kts", "build.gradle")
            .map { File(moduleDir, it) }
            .firstOrNull { it.isFile }
            ?.readText(Charsets.UTF_8) ?: ""

        // 1) Manifest: package + uses-sdk yoksa Gradle dosyasından tamamla
        var manifest = File(main, "AndroidManifest.xml").readText(Charsets.UTF_8)
        val manifestTag = Regex("<manifest\\b[^>]*>").find(manifest)?.value ?: ""
        if (!Regex("\\bpackage\\s*=").containsMatchIn(manifestTag)) {
            val pkg = Regex("namespace\\s*=?\\s*[\"']([^\"']+)[\"']").find(gradleText)?.groupValues?.get(1)
                ?: Regex("applicationId\\s*=?\\s*[\"']([^\"']+)[\"']").find(gradleText)?.groupValues?.get(1)
                ?: throw IOException("Manifest'te package yok ve build.gradle içinde namespace/applicationId bulunamadı.")
            manifest = manifest.replaceFirst("<manifest", "<manifest package=\"$pkg\"")
            notes.add("package adı build.gradle'dan alındı: $pkg")
        }
        if (!manifest.contains("<uses-sdk")) {
            val minSdk = Regex("minSdk(?:Version)?\\s*=?\\s*(\\d+)").find(gradleText)?.groupValues?.get(1)?.toIntOrNull() ?: 21
            val targetSdk = Regex("targetSdk(?:Version)?\\s*=?\\s*(\\d+)").find(gradleText)?.groupValues?.get(1)?.toIntOrNull() ?: 34
            val sdkTag = "\n    <uses-sdk android:minSdkVersion=\"$minSdk\" android:targetSdkVersion=\"$targetSdk\" />\n"
            val tagEnd = Regex("<manifest\\b[^>]*>").find(manifest)?.range?.last
            if (tagEnd != null) {
                manifest = manifest.substring(0, tagEnd + 1) + sdkTag + manifest.substring(tagEnd + 1)
            }
        }
        File(dest, "AndroidManifest.xml").writeText(manifest, Charsets.UTF_8)

        // 2) Kaynak klasörleri
        File(main, "java").takeIf { it.isDirectory }?.copyRecursively(File(dest, "src"), overwrite = true)
        File(main, "res").takeIf { it.isDirectory }?.copyRecursively(File(dest, "res"), overwrite = true)
        File(main, "assets").takeIf { it.isDirectory }?.copyRecursively(File(dest, "assets"), overwrite = true)
        File(moduleDir, "libs").takeIf { it.isDirectory }?.copyRecursively(File(dest, "libs"), overwrite = true)
        File(dest, "src").mkdirs()

        // 3) Uyarılar
        val kt = hasKotlin(main)
        if (kt) {
            notes.add(KOTLIN_NOTE)
            // Kotlin kaynakları derlenmeyeceği için src/ içinde kalmaları sorun çıkarmaz ama bilgi verelim.
            File(main, "kotlin").takeIf { it.isDirectory }?.copyRecursively(File(dest, "src"), overwrite = true)
        }
        if (Regex("androidx|appcompat|material", RegexOption.IGNORE_CASE).containsMatchIn(gradleText)) {
            notes.add("build.gradle içinde AndroidX/Material bağımlılıkları var; bunlar cihaz üzerinde çözülmez, derleme hata verebilir.")
        }
        if (!File(dest, "src").walkTopDown().any { it.isFile && it.extension.equals("java", true) }) {
            notes.add("src/ içinde .java dosyası bulunamadı; derleme için en az bir Java dosyası gerekir.")
        }
        return ImportResult(dest, notes, kt)
    }

    /**
     * En sığ AndroidManifest.xml içeren klasörü bulur (en fazla 6 seviye). Aynı seviyede birden çok aday varsa
     * MAIN intent'i içeren (uygulama) manifest tercih edilir. build/.git/.gradle/.idea/__MACOSX atlanır.
     */
    private fun findManifestDir(root: File): File? {
        val skip = setOf("build", ".git", ".gradle", ".idea", "__MACOSX", "node_modules", "bin", "gen")
        var level: List<File> = listOf(root)
        for (depth in 0..6) {
            val hits = level.filter { File(it, "AndroidManifest.xml").isFile }
            if (hits.isNotEmpty()) {
                return hits.firstOrNull { d ->
                    runCatching { File(d, "AndroidManifest.xml").readText(Charsets.UTF_8).contains("android.intent.action.MAIN") }
                        .getOrDefault(false)
                } ?: hits.first()
            }
            level = level.flatMap { d ->
                d.listFiles { f -> f.isDirectory && f.name !in skip }?.sortedBy { it.name } ?: emptyList()
            }
            if (level.isEmpty()) break
        }
        return null
    }

    private fun displayName(ctx: Context, uri: Uri): String {
        try {
            ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (i >= 0 && c.moveToFirst()) return c.getString(i) ?: "Proje"
            }
        } catch (_: Exception) {}
        return "Proje"
    }

    fun isSafeName(name: String): Boolean =
        name.isNotBlank() && !name.contains('/') && !name.contains('\\') && name != "." && name != ".."

    /** src/ altındaki yeni .java dosyası için paket + sınıf iskeleti. */
    fun javaTemplate(projectDir: File, file: File): String {
        val srcDir = File(projectDir, "src")
        val cls = file.nameWithoutExtension
        val parent = file.parentFile
        val sb = StringBuilder()
        if (parent != null && parent.canonicalPath.startsWith(srcDir.canonicalPath + File.separator)) {
            val pkg = parent.canonicalPath.removePrefix(srcDir.canonicalPath + File.separator).replace(File.separatorChar, '.')
            sb.append("package ").append(pkg).append(";\n\n")
        }
        sb.append("public class ").append(cls).append(" {\n\n")
        sb.append("    public ").append(cls).append("() {\n")
        sb.append("        \n")
        sb.append("    }\n")
        sb.append("}\n")
        return sb.toString()
    }

    /** res/ altındaki yeni .xml dosyası için uygun XML şablonu. */
    fun xmlTemplate(file: File): String {
        val parentName = file.parentFile?.name?.lowercase() ?: ""
        return when {
            parentName.startsWith("layout") -> """<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:orientation="vertical"
    android:padding="16dp">

</LinearLayout>
"""
            parentName.startsWith("values") -> """<?xml version="1.0" encoding="utf-8"?>
<resources>

</resources>
"""
            parentName.startsWith("drawable") -> """<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android">
    <solid android:color="#FFFFFF" />
    <corners android:radius="8dp" />
</shape>
"""
            else -> """<?xml version="1.0" encoding="utf-8"?>
<root>

</root>
"""
        }
    }
}
