package com.miniide.app.settings

import android.content.Context
import com.miniide.app.editor.SyntaxHighlighter
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Prompt 10:
 * 1. Kategorili ve aranabilir IDE ayarları (Editör, Tema, Kısayollar, Derleme, Git, Dosyalar)
 * 2. 7 dahili JSON tema (VS Code Dark+, Light+, Monokai, Solarized, Dracula, One Dark, AMOLED Siyah)
 *    + JSON içe/dışa aktarma ve doğrulama
 * 3. Yerel çökme günlükleyici (ağ gönderimi yok; yalnızca yerel dosyaya yazar ve paylaşım sunar)
 */
object SettingsAndThemes {

    data class IdeTheme(
        val id: String,
        val name: String,
        val isDark: Boolean,
        val editorBg: String,
        val editorFg: String,
        val gutterBg: String,
        val toolbarBg: String,
        val accent: String,
        val keyword: String,
        val type: String,
        val string: String,
        val comment: String,
        val number: String,
        val annotation: String,
        val tag: String,
        val attr: String
    ) {
        fun toSyntaxPalette(): SyntaxHighlighter.Palette = SyntaxHighlighter.Palette(
            keyword = parseHexColor(keyword),
            type = parseHexColor(type),
            string = parseHexColor(string),
            comment = parseHexColor(comment),
            number = parseHexColor(number),
            annotation = parseHexColor(annotation),
            tag = parseHexColor(tag),
            attr = parseHexColor(attr)
        )
    }

    data class IdeSettings(
        val themeId: String = "vscode_dark_plus",
        val fontFamily: String = "JetBrains Mono",
        val fontSizeSp: Float = 13.5f,
        val enableLigatures: Boolean = false,
        val tabSize: Int = 4,
        val useSpaces: Boolean = true,
        val wordWrap: Boolean = false,
        val autoSaveDelayMs: Long = 1500L,
        val showInvisibleChars: Boolean = false,
        val showMinimap: Boolean = false,
        val zenMode: Boolean = false,
        val showHiddenFiles: Boolean = false,
        val excludedDirs: String = "build,gen,bin,.git",
        val enableIncrementalBuild: Boolean = true,
        val enableReleaseSigning: Boolean = false,
        val enableMavenDependencies: Boolean = false,
        val onboardingCompleted: Boolean = false
    )

    val builtInThemes: List<IdeTheme> = listOf(
        IdeTheme(
            id = "vscode_dark_plus", name = "VS Code Dark+", isDark = true,
            editorBg = "#1E1E1E", editorFg = "#D4D4D4", gutterBg = "#1E1E1E", toolbarBg = "#252526", accent = "#007ACC",
            keyword = "#569CD6", type = "#4EC9B0", string = "#CE9178", comment = "#6A9955",
            number = "#B5CEA8", annotation = "#DCDCAA", tag = "#569CD6", attr = "#9CDCFE"
        ),
        IdeTheme(
            id = "vscode_light_plus", name = "VS Code Light+", isDark = false,
            editorBg = "#FFFFFF", editorFg = "#1E293B", gutterBg = "#F8FAFC", toolbarBg = "#E2E8F0", accent = "#2563EB",
            keyword = "#0000FF", type = "#267F99", string = "#A31515", comment = "#008000",
            number = "#098658", annotation = "#795E26", tag = "#800000", attr = "#FF0000"
        ),
        IdeTheme(
            id = "monokai", name = "Monokai", isDark = true,
            editorBg = "#272822", editorFg = "#F8F8F2", gutterBg = "#272822", toolbarBg = "#1E1F1C", accent = "#A6E22E",
            keyword = "#F92672", type = "#66D9EF", string = "#E6DB74", comment = "#75715E",
            number = "#AE81FF", annotation = "#A6E22E", tag = "#F92672", attr = "#A6E22E"
        ),
        IdeTheme(
            id = "solarized_dark", name = "Solarized Dark", isDark = true,
            editorBg = "#002B36", editorFg = "#839496", gutterBg = "#073642", toolbarBg = "#073642", accent = "#268BD2",
            keyword = "#859900", type = "#B58900", string = "#2AA198", comment = "#586E75",
            number = "#D33682", annotation = "#CB4B16", tag = "#268BD2", attr = "#93A1A1"
        ),
        IdeTheme(
            id = "dracula", name = "Dracula", isDark = true,
            editorBg = "#282A36", editorFg = "#F8F8F2", gutterBg = "#282A36", toolbarBg = "#21222C", accent = "#BD93F9",
            keyword = "#FF79C6", type = "#8BE9FD", string = "#F1FA8C", comment = "#6272A4",
            number = "#BD93F9", annotation = "#50FA7B", tag = "#FF79C6", attr = "#50FA7B"
        ),
        IdeTheme(
            id = "one_dark", name = "One Dark", isDark = true,
            editorBg = "#282C34", editorFg = "#ABB2BF", gutterBg = "#282C34", toolbarBg = "#21252B", accent = "#61AFEF",
            keyword = "#C678DD", type = "#E5C07B", string = "#98C379", comment = "#5C6370",
            number = "#D19A66", annotation = "#61AFEF", tag = "#E06C75", attr = "#D19A66"
        ),
        IdeTheme(
            id = "amoled_black", name = "AMOLED Siyah", isDark = true,
            editorBg = "#000000", editorFg = "#E5E7EB", gutterBg = "#000000", toolbarBg = "#0A0A0A", accent = "#38BDF8",
            keyword = "#60A5FA", type = "#2DD4BF", string = "#4ADE80", comment = "#6B7280",
            number = "#FB923C", annotation = "#FACC15", tag = "#C084FC", attr = "#38BDF8"
        )
    )

    fun findThemeById(id: String): IdeTheme =
        builtInThemes.firstOrNull { it.id == id } ?: builtInThemes.first()

    fun isValidHexColor(hex: String): Boolean =
        Regex("^#([0-9A-Fa-f]{6}|[0-9A-Fa-f]{8})$").matches(hex.trim())

    fun parseHexColor(hex: String): Int {
        val clean = hex.trim().removePrefix("#")
        val value = clean.toLong(16)
        return if (clean.length == 6) (0xFF000000L or value).toInt() else value.toInt()
    }

    /** Temayı standart JSON biçiminde dışa aktarır. */
    fun themeToJson(t: IdeTheme): String = """{
  "id": "${escapeJson(t.id)}",
  "name": "${escapeJson(t.name)}",
  "isDark": ${t.isDark},
  "editorBg": "${t.editorBg}",
  "editorFg": "${t.editorFg}",
  "gutterBg": "${t.gutterBg}",
  "toolbarBg": "${t.toolbarBg}",
  "accent": "${t.accent}",
  "keyword": "${t.keyword}",
  "type": "${t.type}",
  "string": "${t.string}",
  "comment": "${t.comment}",
  "number": "${t.number}",
  "annotation": "${t.annotation}",
  "tag": "${t.tag}",
  "attr": "${t.attr}"
}"""

    /** JSON metnini doğrulayarak IdeTheme nesnesine ayrıştırır. */
    fun themeFromJson(json: String): Result<IdeTheme> {
        fun strField(key: String): String? =
            Regex(""""$key"\s*:\s*"([^"]+)"""").find(json)?.groupValues?.get(1)
        fun boolField(key: String): Boolean? =
            Regex(""""$key"\s*:\s*(true|false)""").find(json)?.groupValues?.get(1)?.toBooleanStrictOrNull()

        val id = strField("id") ?: return Result.failure(IllegalArgumentException("Tema 'id' alanı eksik"))
        val name = strField("name") ?: return Result.failure(IllegalArgumentException("Tema 'name' alanı eksik"))
        val isDark = boolField("isDark") ?: true

        val colorKeys = listOf(
            "editorBg", "editorFg", "gutterBg", "toolbarBg", "accent",
            "keyword", "type", "string", "comment", "number", "annotation", "tag", "attr"
        )
        val colors = HashMap<String, String>()
        for (k in colorKeys) {
            val v = strField(k) ?: return Result.failure(IllegalArgumentException("Renk jetonu eksik: $k"))
            if (!isValidHexColor(v)) {
                return Result.failure(IllegalArgumentException("Geçersiz HEX renk ($k): $v"))
            }
            colors[k] = v
        }

        return Result.success(
            IdeTheme(
                id = id,
                name = name,
                isDark = isDark,
                editorBg = colors["editorBg"]!!,
                editorFg = colors["editorFg"]!!,
                gutterBg = colors["gutterBg"]!!,
                toolbarBg = colors["toolbarBg"]!!,
                accent = colors["accent"]!!,
                keyword = colors["keyword"]!!,
                type = colors["type"]!!,
                string = colors["string"]!!,
                comment = colors["comment"]!!,
                number = colors["number"]!!,
                annotation = colors["annotation"]!!,
                tag = colors["tag"]!!,
                attr = colors["attr"]!!
            )
        )
    }

    /** Saf Kotlin ayar serileştirme / geri yükleme (JVM birim testleri ve SharedPreferences için). */
    fun serializeSettings(s: IdeSettings): String = buildString {
        append("themeId=").append(s.themeId).append('\n')
        append("fontFamily=").append(s.fontFamily).append('\n')
        append("fontSizeSp=").append(s.fontSizeSp).append('\n')
        append("enableLigatures=").append(s.enableLigatures).append('\n')
        append("tabSize=").append(s.tabSize).append('\n')
        append("useSpaces=").append(s.useSpaces).append('\n')
        append("wordWrap=").append(s.wordWrap).append('\n')
        append("autoSaveDelayMs=").append(s.autoSaveDelayMs).append('\n')
        append("showInvisibleChars=").append(s.showInvisibleChars).append('\n')
        append("showMinimap=").append(s.showMinimap).append('\n')
        append("zenMode=").append(s.zenMode).append('\n')
        append("showHiddenFiles=").append(s.showHiddenFiles).append('\n')
        append("excludedDirs=").append(s.excludedDirs).append('\n')
        append("enableIncrementalBuild=").append(s.enableIncrementalBuild).append('\n')
        append("enableReleaseSigning=").append(s.enableReleaseSigning).append('\n')
        append("enableMavenDependencies=").append(s.enableMavenDependencies).append('\n')
        append("onboardingCompleted=").append(s.onboardingCompleted).append('\n')
    }

    fun deserializeSettings(raw: String): IdeSettings {
        val map = HashMap<String, String>()
        for (line in raw.lines()) {
            val eq = line.indexOf('=')
            if (eq > 0) map[line.substring(0, eq).trim()] = line.substring(eq + 1).trim()
        }
        return IdeSettings(
            themeId = map["themeId"] ?: "vscode_dark_plus",
            fontFamily = map["fontFamily"] ?: "JetBrains Mono",
            fontSizeSp = map["fontSizeSp"]?.toFloatOrNull()?.coerceIn(10f, 24f) ?: 13.5f,
            enableLigatures = map["enableLigatures"]?.toBooleanStrictOrNull() ?: false,
            tabSize = map["tabSize"]?.toIntOrNull()?.let { if (it == 2) 2 else 4 } ?: 4,
            useSpaces = map["useSpaces"]?.toBooleanStrictOrNull() ?: true,
            wordWrap = map["wordWrap"]?.toBooleanStrictOrNull() ?: false,
            autoSaveDelayMs = map["autoSaveDelayMs"]?.toLongOrNull()?.coerceIn(300L, 10_000L) ?: 1500L,
            showInvisibleChars = map["showInvisibleChars"]?.toBooleanStrictOrNull() ?: false,
            showMinimap = map["showMinimap"]?.toBooleanStrictOrNull() ?: false,
            zenMode = map["zenMode"]?.toBooleanStrictOrNull() ?: false,
            showHiddenFiles = map["showHiddenFiles"]?.toBooleanStrictOrNull() ?: false,
            excludedDirs = map["excludedDirs"] ?: "build,gen,bin,.git",
            enableIncrementalBuild = map["enableIncrementalBuild"]?.toBooleanStrictOrNull() ?: true,
            enableReleaseSigning = map["enableReleaseSigning"]?.toBooleanStrictOrNull() ?: false,
            enableMavenDependencies = map["enableMavenDependencies"]?.toBooleanStrictOrNull() ?: false,
            onboardingCompleted = map["onboardingCompleted"]?.toBooleanStrictOrNull() ?: false
        )
    }

    fun saveSettings(context: Context, settings: IdeSettings) {
        context.getSharedPreferences("ide_settings_v2", Context.MODE_PRIVATE)
            .edit()
            .putString("payload", serializeSettings(settings))
            .apply()
    }

    fun loadSettings(context: Context): IdeSettings {
        val raw = context.getSharedPreferences("ide_settings_v2", Context.MODE_PRIVATE)
            .getString("payload", null) ?: return IdeSettings()
        return deserializeSettings(raw)
    }

    /** Yerel çökme raporlayıcı (ağ gönderimi yoktur). */
    object CrashReporter {
        fun install(context: Context) {
            val appCtx = context.applicationContext
            val previous = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                try {
                    val dir = File(appCtx.filesDir, "crash_reports").apply { mkdirs() }
                    val report = File(dir, "last_crash.txt")
                    val sw = StringWriter()
                    throwable.printStackTrace(PrintWriter(sw))
                    val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
                    report.writeText("Tarih: $ts\nThread: ${thread.name}\n\n$sw", Charsets.UTF_8)
                } catch (_: Exception) {}
                previous?.uncaughtException(thread, throwable)
            }
        }

        fun getLastCrashReport(context: Context): File? =
            File(context.filesDir, "crash_reports/last_crash.txt").takeIf { it.isFile && it.length() > 0L }
    }

    private fun escapeJson(s: String): String = s.replace("\\", "\\\\").replace("\"", "\\\"")
}
