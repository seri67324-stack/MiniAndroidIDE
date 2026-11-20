package com.miniide.app.builder

import java.io.File

/**
 * ECJ ve AAPT2 çıktılarını BuildError listesine çevirir ve Türkçe açıklamalar üretir.
 */
object ErrorParser {

    private val ecjHeader = Regex("""^\d+\.\s+(ERROR|WARNING)\s+in\s+(.+?)\s+\(at line (\d+)\)\s*$""")
    private val caretLine = Regex("""^\s*\^+\s*$""")
    private val aaptWithFile = Regex("""^(.+?):(\d+)(?::\d+)?:\s*(error|warning):\s*(.*)$""", RegexOption.IGNORE_CASE)
    private val aaptNoFile = Regex("""^(?:aapt2\s+)?(error|warning):\s*(.*)$""", RegexOption.IGNORE_CASE)

    fun parseEcj(text: String, projectDir: File): List<BuildError> {
        val result = mutableListOf<BuildError>()
        val lines = text.lines()
        var i = 0
        while (i < lines.size) {
            val m = ecjHeader.matchEntire(lines[i].trimEnd())
            if (m == null) { i++; continue }
            val isError = m.groupValues[1] == "ERROR"
            val file = rel(m.groupValues[2].trim(), projectDir)
            val lineNo = m.groupValues[3].toIntOrNull() ?: 0
            i++
            val block = mutableListOf<String>()
            while (i < lines.size && !lines[i].startsWith("----------") && ecjHeader.matchEntire(lines[i].trimEnd()) == null) {
                block.add(lines[i])
                i++
            }
            val content = block.filter { it.isNotBlank() && !caretLine.matches(it) }
            val msg = content.lastOrNull()?.trim() ?: "Bilinmeyen derleme hatası"
            result.add(BuildError("ECJ", isError, file, lineNo, msg, translate(msg)))
        }
        return result
    }

    fun parseAapt(text: String, projectDir: File): List<BuildError> {
        val result = mutableListOf<BuildError>()
        for (raw in text.lines()) {
            val l = raw.trim()
            if (l.isEmpty()) continue
            val a = aaptWithFile.matchEntire(l)
            if (a != null) {
                val msg = a.groupValues[4].trim()
                val isErr = a.groupValues[3].lowercase() == "error"
                result.add(
                    BuildError(
                        "AAPT2",
                        isErr,
                        rel(a.groupValues[1], projectDir),
                        a.groupValues[2].toIntOrNull() ?: 0,
                        msg,
                        translate(msg)
                    )
                )
                continue
            }
            val b = aaptNoFile.matchEntire(l)
            if (b != null) {
                val msg = b.groupValues[2].trim()
                val isErr = b.groupValues[1].lowercase() == "error"
                result.add(BuildError("AAPT2", isErr, null, 0, msg, translate(msg)))
            }
        }
        return result
    }

    private fun rel(path: String, projectDir: File): String {
        val normPath = path.replace('\\', '/')
        val base = projectDir.absolutePath.replace('\\', '/').trimEnd('/') + "/"
        return if (normPath.startsWith(base, ignoreCase = true)) {
            normPath.substring(base.length)
        } else {
            normPath
        }
    }

    /** En sık görülen ECJ/AAPT2 mesajlarının Türkçesi. Eşleşmezse orijinal döner. */
    fun translate(msg: String): String {
        val rules = listOf(
            Regex("""^(.+) cannot be resolved to a type$""") to "\"$1\" türü bulunamadı (import eksik veya sınıf adı yanlış olabilir)",
            Regex("""^(.+) cannot be resolved to a variable$""") to "\"$1\" değişkeni tanımlı değil",
            Regex("""^(.+) cannot be resolved or is not a field$""") to "\"$1\" alanı (field) bulunamadı veya tanımlı değil",
            Regex("""^(.+) cannot be resolved$""") to "\"$1\" çözümlenemedi / bulunamadı",
            Regex("""^Syntax error, insert "(.+)" to complete (.+)$""") to "Sözdizimi hatası: ifadeyi tamamlamak için \"$1\" eklenmeli",
            Regex("""^Syntax error on token "(.+)", (.+)$""") to "Sözdizimi hatası: \"$1\" ifadesinde sorun var ($2)",
            Regex("""^The method (.+) is undefined for the type (.+)$""") to "$2 sınıfında $1 metodu tanımlı değil",
            Regex("""^The constructor (.+) is undefined$""") to "$1 yapıcısı (constructor) bulunamadı",
            Regex("""^Type mismatch: cannot convert from (.+) to (.+)$""") to "Tür uyuşmazlığı: $1 türü $2 türüne dönüştürülemez",
            Regex("""^Cannot make a static reference to the non-static (.+)$""") to "Statik olmayan $1 öğesine statik bağlamdan erişilemez",
            Regex("""^The method (.+) of type (.+) must override or implement a supertype method$""") to "$1 metodu üst sınıfta bulunamadı (@Override hatası)",
            Regex("""^The final local variable (.+) cannot be assigned.*$""") to "final tanımlı \"$1\" değişkenine yeni değer atanamaz",
            Regex("""^The local variable (.+) may not have been initialized$""") to "\"$1\" yerel değişkenine başlangıç değeri verilmemiş",
            Regex("""^The value of the local variable (.+) is not used$""") to "\"$1\" değişkeni tanımlandı ancak hiç kullanılmadı",
            Regex("""^The import (.+) is never used$""") to "\"$1\" import edildi ancak hiç kullanılmadı",
            Regex("""^Missing return statement$""") to "Metotta return ifadesi eksik",
            Regex("""^Void methods cannot return a value$""") to "void metotlar geriye değer döndüremez",
            Regex("""^This method must return a result of type (.+)$""") to "Bu metot $1 türünde bir değer döndürmelidir",
            Regex("""^Unreachable code$""") to "Bu kod satırına hiçbir zaman ulaşılamaz",
            Regex("""^Duplicate local variable (.+)$""") to "\"$1\" değişkeni bu kapsamda zaten tanımlı",
            Regex("""^Duplicate method (.+) in type (.+)$""") to "$2 sınıfında $1 metodu birden fazla kez tanımlanmış",
            Regex("""^The public type (.+) must be defined in its own file$""") to "public sınıf \"$1\" kendi adını taşıyan ($1.java) dosyada tanımlanmalıdır",
            Regex("""^The import (.+) cannot be resolved$""") to "\"$1\" paketi/sınıfı bulunamadı",
            Regex("""^Unhandled exception type (.+)$""") to "$1 istisnası yakalanmamış (try/catch veya throws bildirimi gerekir)",
            Regex("""^resource (.+) not found\.?$""") to "$1 kaynağı (resource) bulunamadı",
            Regex("""^attribute (.+) not found\.?$""") to "$1 XML özniteliği bulunamadı",
            Regex("""^failed linking references\.?$""") to "Kaynak bağlama başarısız oldu (yukarıdaki XML hatalarını kontrol edin)",
            Regex("""^duplicate value for resource (.+?)( with config.*)?\.?$""") to "$1 kaynağı birden fazla kez tanımlanmış"
        )
        for ((re, tr) in rules) {
            val m = re.matchEntire(msg) ?: continue
            var out = tr
            for (g in 1 until m.groupValues.size) out = out.replace("$" + g, m.groupValues[g])
            return out
        }
        return msg
    }
}
