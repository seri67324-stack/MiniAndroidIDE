package com.miniide.app.builder

import java.io.File
import java.util.zip.ZipFile

/** libs/*.aar dosyalarının açılmış hali: classpath'e girecek jar'lar, AAPT2'ye verilecek res klasörleri ve R paketleri. */
class AarContents(
    val jars: List<File>,
    val resDirs: List<File>,
    val rPackages: List<String>,
    val warnings: List<String>
) {
    companion object {
        val EMPTY = AarContents(emptyList(), emptyList(), emptyList(), emptyList())
    }
}

/**
 * AAR açma yardımcısı (Android'e bağımlı değildir, birim testle denenebilir).
 * Desteklenen: classes.jar, libs/*.jar ve res/. Manifest birleştirme (merge) YAPILMAZ; yalnızca uyarı verilir.
 */
object AarSupport {

    private val packageRegex = Regex("""package\s*=\s*"([^"]+)"""")
    private val componentRegex = Regex("""<(activity|service|receiver|provider|uses-permission)\b""")

    fun extract(aars: List<File>, workDir: File, appPackage: String?): AarContents {
        if (aars.isEmpty()) return AarContents.EMPTY
        val jars = ArrayList<File>()
        val resDirs = ArrayList<File>()
        val packages = ArrayList<String>()
        val warnings = ArrayList<String>()

        aars.forEachIndexed { index, aar ->
            val safeName = aar.nameWithoutExtension.replace(Regex("[^A-Za-z0-9_.-]"), "_")
            val outDir = File(workDir, "${index}_$safeName").apply { mkdirs() }
            val outPrefix = outDir.canonicalPath + File.separator
            var hasRes = false
            var manifestText = ""

            ZipFile(aar).use { zip ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (entry.isDirectory) continue
                    val name = entry.name

                    if (name == "AndroidManifest.xml") {
                        manifestText = zip.getInputStream(entry).use { it.readBytes().toString(Charsets.UTF_8) }
                        continue
                    }

                    val wanted = name == "classes.jar" ||
                        (name.startsWith("libs/") && name.endsWith(".jar")) ||
                        name.startsWith("res/")
                    if (!wanted) continue

                    val target = File(outDir, name)
                    // Zip-slip koruması: hedef, çıkarma klasörünün dışına çıkamaz.
                    if (!target.canonicalPath.startsWith(outPrefix)) {
                        warnings.add("${aar.name}: geçersiz girdi atlandı ($name)")
                        continue
                    }
                    target.parentFile?.mkdirs()
                    zip.getInputStream(entry).use { input ->
                        target.outputStream().use { output -> input.copyTo(output) }
                    }
                    if (name.startsWith("res/")) hasRes = true
                    if (name.endsWith(".jar")) jars.add(target)
                }
            }

            if (hasRes) {
                resDirs.add(File(outDir, "res"))
                val pkg = packageRegex.find(manifestText)?.groupValues?.get(1)
                if (pkg != null && pkg != appPackage && pkg !in packages) packages.add(pkg)
            }
            if (componentRegex.containsMatchIn(manifestText)) {
                warnings.add(
                    "${aar.name}: AAR manifest'indeki bileşenler/izinler otomatik birleştirilmez. " +
                        "Gerekirse projenin AndroidManifest.xml dosyasına elle ekleyin."
                )
            }
        }
        return AarContents(jars, resDirs, packages, warnings)
    }
}
