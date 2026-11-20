package com.miniide.app.builder

import android.content.Context
import com.android.tools.r8.D8
import com.android.tools.r8.D8Command
import com.android.tools.r8.OutputMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import org.eclipse.jdt.internal.compiler.batch.Main as EcjMain
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Klasik Android proje yapısını (AndroidManifest.xml, res/, assets/, src/, libs/, gen/, bin/)
 * cihaz üzerinde uçtan uca derleyen ana hat:
 * 1. Hazırlık -> 2. AAPT2 -> 3. ECJ -> 4. D8 -> 5. Paketleme (ZipAlign) -> 6. İmzalama (ApkSigner V1+V2+V3)
 */
class LegacyProjectBuilder(
    private val context: Context,
    private val projectDir: File,
    private val androidJarFile: File,
    private val logger: BuildLogger
) {

    @Volatile
    private var activeProcess: Process? = null

    fun cancelActiveProcess() {
        try {
            activeProcess?.destroyForcibly()
        } catch (_: Exception) {}
    }

    fun build(scope: CoroutineScope): File {
        val disposable = scope.coroutineContext.job.invokeOnCompletion { cancelActiveProcess() }
        try {
            val manifestFile = File(projectDir, "AndroidManifest.xml")
            val resDir = File(projectDir, "res")
            val assetsDir = File(projectDir, "assets")
            val srcDir = File(projectDir, "src")
            val libsDir = File(projectDir, "libs")
            val genDir = File(projectDir, "gen")
            val binDir = File(projectDir, "bin")

            require(manifestFile.exists()) { "AndroidManifest.xml bulunamadı: ${manifestFile.absolutePath}" }
            require(srcDir.exists()) { "src/ klasörü bulunamadı: ${srcDir.absolutePath}" }
            require(androidJarFile.exists()) { "SDK android.jar bulunamadı: ${androidJarFile.absolutePath}" }

            val manifestText = manifestFile.readText()
            val minSdk = Regex("""android:minSdkVersion\s*=\s*"(\d+)"""")
                .find(manifestText)?.groupValues?.get(1)?.toIntOrNull() ?: 21

            // 1. ADIM: Klasör Hazırlığı
            scope.ensureActive()
            logger.onProgress("Hazırlık", 8)
            logger.info("Hazırlık", "Proje dizini temizleniyor: ${projectDir.name} (minSdk=$minSdk)")

            genDir.deleteRecursively()
            binDir.deleteRecursively()
            genDir.mkdirs()
            binDir.mkdirs()

            val classesDir = File(binDir, "classes").apply { mkdirs() }
            val dexDir = File(binDir, "dex").apply { mkdirs() }
            val resCompiledZip = File(binDir, "res_compiled.zip")
            val resourcesAp = File(binDir, "resources.ap_")

            // 2. ADIM: AAPT2 (compile & link)
            scope.ensureActive()
            logger.onProgress("AAPT2", 25)
            runAapt2(resDir, assetsDir, manifestFile, genDir, resCompiledZip, resourcesAp)

            // 3. ADIM: ECJ (java -> class)
            scope.ensureActive()
            logger.onProgress("ECJ", 50)
            val jarLibs = libsDir.listFiles { f -> f.isFile && f.extension.equals("jar", ignoreCase = true) }
                ?.toList() ?: emptyList()
            runEcj(srcDir, genDir, classesDir, jarLibs)

            // 4. ADIM: D8 (class -> dex)
            scope.ensureActive()
            logger.onProgress("D8", 72)
            val dexFiles = runD8(classesDir, dexDir, jarLibs, minSdk)

            // 5. ADIM: Paketleme ve 4-byte hizalama
            scope.ensureActive()
            logger.onProgress("Paketleme", 86)
            val unsignedApk = File(binDir, "unsigned.apk")
            ZipAlignPackager.packageAndAlign(resourcesAp, dexFiles, unsignedApk)
            logger.info("Paketleme", "4-byte hizalı APK oluşturuldu (${unsignedApk.length() / 1024} KB)")

            // 6. ADIM: İmzalama
            scope.ensureActive()
            logger.onProgress("İmzalama", 95)
            val signedApk = File(binDir, "${projectDir.name}-debug.apk")
            DebugKeystoreHelper.signApk(unsignedApk, signedApk, File(context.filesDir, "keystore"), minSdk)
            logger.info("İmzalama", "V1+V2+V3 imzalı APK hazır: ${signedApk.name}")

            logger.onProgress("Bitti", 100)
            return signedApk
        } finally {
            disposable.dispose()
        }
    }

    private fun aapt2Binary(): File {
        val f = File(context.applicationInfo.nativeLibraryDir, "libaapt2.so")
        require(f.exists()) {
            "libaapt2.so bulunamadı (${f.absolutePath}). Cihaz mimarisi arm64-v8a olmalı ve libaapt2.so pakette yer almalıdır."
        }
        if (!f.canExecute()) {
            try { f.setExecutable(true) } catch (_: Exception) {}
        }
        return f
    }

    private fun runProcess(step: String, cmd: List<String>) {
        logger.info(step, cmd.joinToString(" ") { if (it.contains(' ')) "\"$it\"" else it })
        val pb = ProcessBuilder(cmd).redirectErrorStream(true)
        val proc = pb.start()
        activeProcess = proc
        val output = try {
            proc.inputStream.bufferedReader().readText()
        } finally {
            activeProcess = null
        }
        val code = proc.waitFor()
        if (output.isNotBlank()) {
            if (code == 0) logger.info(step, output.trim()) else logger.error(step, output.trim())
        }
        if (code != 0) {
            val parsed = ErrorParser.parseAapt(output, projectDir)
            if (parsed.isNotEmpty()) logger.onErrors(parsed)
            throw RuntimeException("$step başarısız oldu (çıkış kodu $code)")
        }
    }

    private fun runAapt2(
        resDir: File,
        assetsDir: File,
        manifest: File,
        genDir: File,
        resCompiledZip: File,
        resourcesAp: File
    ) {
        val aapt2 = aapt2Binary().absolutePath

        val link = mutableListOf(
            aapt2, "link",
            "-I", androidJarFile.absolutePath,
            "--manifest", manifest.absolutePath,
            "--java", genDir.absolutePath,
            "-o", resourcesAp.absolutePath,
            "--auto-add-overlay"
        )

        if (assetsDir.exists() && assetsDir.isDirectory) {
            link.add("-A")
            link.add(assetsDir.absolutePath)
            logger.info("AAPT2", "assets/ klasörü pakete dahil ediliyor.")
        }

        val hasResFiles = resDir.exists() && resDir.isDirectory &&
                resDir.walkTopDown().any { it.isFile }
        if (hasResFiles) {
            runProcess("AAPT2", listOf(aapt2, "compile", "--dir", resDir.absolutePath, "-o", resCompiledZip.absolutePath))
            link.add(resCompiledZip.absolutePath)
        } else {
            logger.info("AAPT2", "res/ klasörü boş veya yok, yalnızca manifest bağlanıyor.")
        }

        runProcess("AAPT2", link)
        logger.info("AAPT2", "R.java ve resources.ap_ başarıyla üretildi.")
    }

    private fun runEcj(srcDir: File, genDir: File, classesDir: File, jarLibs: List<File>) {
        val javaFilesCount = srcDir.walkTopDown().count { it.isFile && it.extension.equals("java", ignoreCase = true) }
        require(javaFilesCount > 0) { "src/ klasöründe derlenecek .java dosyası bulunamadı" }

        val hasGenJava = genDir.exists() &&
                genDir.walkTopDown().any { it.isFile && it.extension.equals("java", ignoreCase = true) }

        val argsList = mutableListOf(
            "-source", "1.8",
            "-target", "1.8",
            "-proc:none",
            "-nowarn",
            "-encoding", "UTF-8",
            "-bootclasspath", androidJarFile.absolutePath
        )

        if (jarLibs.isNotEmpty()) {
            val cp = jarLibs.joinToString(File.pathSeparator) { it.absolutePath }
            argsList.add("-classpath")
            argsList.add(cp)
            logger.info("ECJ", "${jarLibs.size} harici JAR kütüphanesi eklendi.")
        }

        argsList.add("-d")
        argsList.add(classesDir.absolutePath)
        argsList.add(srcDir.absolutePath)
        if (hasGenJava) {
            argsList.add(genDir.absolutePath)
        }

        logger.info("ECJ", "$javaFilesCount Java kaynak dosyası derleniyor...")

        val outSw = StringWriter()
        val errSw = StringWriter()
        val main = EcjMain(PrintWriter(outSw, true), PrintWriter(errSw, true), false, null, null)
        val ok = main.compile(argsList.toTypedArray())

        if (outSw.toString().isNotBlank()) logger.info("ECJ", outSw.toString().trim())
        val allOutput = errSw.toString() + "\n" + outSw.toString()
        val parsed = ErrorParser.parseEcj(allOutput, projectDir)
        if (parsed.isNotEmpty()) {
            logger.onErrors(parsed)
        }

        if (!ok) {
            logger.error("ECJ", errSw.toString().trim())
            throw RuntimeException("Java derlemesi başarısız oldu (${parsed.count { it.isError }} hata)")
        }
        if (errSw.toString().isNotBlank()) logger.info("ECJ", errSw.toString().trim())
        logger.info("ECJ", "Java derlemesi tamamlandı.")
    }

    private fun runD8(classesDir: File, dexDir: File, jarLibs: List<File>, minSdk: Int): List<File> {
        val classFiles = classesDir.walkTopDown()
            .filter { it.isFile && it.extension == "class" }
            .map { it.toPath() }
            .toList()
        require(classFiles.isNotEmpty()) { "Derlenmiş .class dosyası bulunamadı" }
        logger.info("D8", "${classFiles.size} sınıf dosyası DEX formatına çevriliyor...")

        val builder = D8Command.builder()
            .addLibraryFiles(androidJarFile.toPath())
            .addProgramFiles(classFiles)
            .setMinApiLevel(minSdk.coerceAtLeast(21))
            .setOutput(dexDir.toPath(), OutputMode.DexIndexed)

        if (jarLibs.isNotEmpty()) {
            builder.addProgramFiles(jarLibs.map { it.toPath() })
        }

        D8.run(builder.build())

        val dexFiles = dexDir.listFiles { f -> f.extension == "dex" }
            ?.sortedBy { it.name.removePrefix("classes").removeSuffix(".dex").ifEmpty { "1" }.toIntOrNull() ?: 1 }
            ?: emptyList()
        require(dexFiles.isNotEmpty()) { "D8 dex dosyası üretmedi" }
        logger.info("D8", "${dexFiles.size} DEX dosyası üretildi.")
        return dexFiles
    }
}
