package com.miniide.app.editor

import com.miniide.app.builder.BuildError
import com.miniide.app.builder.ErrorParser
import org.eclipse.jdt.internal.compiler.batch.Main as EcjMain
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.file.Files
import java.util.zip.ZipFile

/**
 * Prompt 5 Kod Zekası Motoru (Saf Kotlin + ECJ tek dosya analizi):
 * 1. Java & XML otomatik tamamlama (anahtar kelimeler, yerel/proje tanımlayıcıları,
 *    android.jar sınıf indeksi önbelleği, "nesne." üye tamamlama, res/ @string/@id/@color çözümleme)
 * 2. Anlık 800 ms ECJ tek-dosya sözdizimi/hata analizi (-d none, .class üretmeden)
 * 3. Parantez/tırnak eşleştirme, kod katlama aralıkları (Java blokları ve XML etiketleri)
 * 4. Java girinti biçimlendirici ve XML pretty-print biçimlendirici
 * 5. Tanıma Git (sınıf / metot tanımı arama)
 */
object CodeIntelligenceEngine {

    data class CompletionItem(
        val label: String,
        val insertText: String,
        val detail: String,
        val cursorBackOffset: Int = 0 // yerleştirmeden sonra imlecin geriye alınacağı karakter sayısı
    )

    data class FoldRange(
        val startLineZeroBased: Int,
        val endLineZeroBased: Int,
        val label: String
    )

    data class DefinitionLocation(
        val file: File?,       // null ise aktif dosya
        val lineOneBased: Int,
        val preview: String
    )

    data class LineDiagnostic(
        val lineOneBased: Int,
        val isError: Boolean,
        val messageTr: String,
        val originalMessage: String
    )

    private val javaKeywords = listOf(
        "public", "private", "protected", "class", "interface", "enum", "extends", "implements",
        "static", "final", "void", "return", "new", "this", "super", "import", "package",
        "if", "else", "for", "while", "do", "switch", "case", "default", "break", "continue",
        "try", "catch", "finally", "throw", "throws", "boolean", "int", "long", "float", "double",
        "char", "byte", "short", "true", "false", "null", "instanceof", "synchronized", "abstract"
    )

    private val xmlTags = listOf(
        "LinearLayout", "RelativeLayout", "FrameLayout", "ScrollView", "HorizontalScrollView",
        "TextView", "Button", "EditText", "ImageView", "CheckBox", "RadioButton", "Switch",
        "ProgressBar", "SeekBar", "ListView", "Spinner", "View", "WebView", "include"
    )

    private val xmlAttributes = listOf(
        "android:id" to "android:id=\"@+id/\"",
        "android:layout_width" to "android:layout_width=\"match_parent\"",
        "android:layout_height" to "android:layout_height=\"wrap_content\"",
        "android:orientation" to "android:orientation=\"vertical\"",
        "android:text" to "android:text=\"\"",
        "android:textSize" to "android:textSize=\"14sp\"",
        "android:textColor" to "android:textColor=\"#000000\"",
        "android:textStyle" to "android:textStyle=\"bold\"",
        "android:padding" to "android:padding=\"12dp\"",
        "android:layout_margin" to "android:layout_margin=\"8dp\"",
        "android:layout_marginTop" to "android:layout_marginTop=\"8dp\"",
        "android:background" to "android:background=\"\"",
        "android:gravity" to "android:gravity=\"center\"",
        "android:visibility" to "android:visibility=\"visible\"",
        "android:hint" to "android:hint=\"\"",
        "android:src" to "android:src=\"@drawable/\"",
        "android:layout_weight" to "android:layout_weight=\"1\""
    )

    private val xmlValues = listOf(
        "match_parent", "wrap_content", "vertical", "horizontal",
        "center", "center_horizontal", "center_vertical", "start", "end",
        "visible", "invisible", "gone", "bold", "italic", "normal"
    )

    private val commonAndroidMembers: Map<String, List<Pair<String, String>>> = mapOf(
        "String" to listOf(
            "length()" to "int length()",
            "isEmpty()" to "boolean isEmpty()",
            "trim()" to "String trim()",
            "substring()" to "String substring(int beginIndex)",
            "contains()" to "boolean contains(CharSequence s)",
            "equals()" to "boolean equals(Object anObject)",
            "startsWith()" to "boolean startsWith(String prefix)",
            "split()" to "String[] split(String regex)",
            "replace()" to "String replace(CharSequence target, CharSequence replacement)"
        ),
        "TextView" to listOf(
            "setText()" to "void setText(CharSequence text)",
            "getText()" to "CharSequence getText()",
            "setTextColor()" to "void setTextColor(int color)",
            "setTextSize()" to "void setTextSize(float size)",
            "setVisibility()" to "void setVisibility(int visibility)",
            "setOnClickListener()" to "void setOnClickListener(View.OnClickListener l)"
        ),
        "Button" to listOf(
            "setOnClickListener()" to "void setOnClickListener(View.OnClickListener l)",
            "setText()" to "void setText(CharSequence text)",
            "setEnabled()" to "void setEnabled(boolean enabled)",
            "setVisibility()" to "void setVisibility(int visibility)"
        ),
        "EditText" to listOf(
            "getText()" to "Editable getText()",
            "setText()" to "void setText(CharSequence text)",
            "setHint()" to "void setHint(CharSequence hint)",
            "setSelection()" to "void setSelection(int index)",
            "addTextChangedListener()" to "void addTextChangedListener(TextWatcher watcher)"
        ),
        "View" to listOf(
            "setVisibility()" to "void setVisibility(int visibility)",
            "setOnClickListener()" to "void setOnClickListener(View.OnClickListener l)",
            "findViewById()" to "T findViewById(int id)",
            "setBackgroundColor()" to "void setBackgroundColor(int color)",
            "post()" to "boolean post(Runnable action)",
            "invalidate()" to "void invalidate()"
        ),
        "Toast" to listOf(
            "makeText()" to "Toast makeText(Context ctx, CharSequence text, int duration)",
            "LENGTH_SHORT" to "int LENGTH_SHORT",
            "LENGTH_LONG" to "int LENGTH_LONG"
        ),
        "Intent" to listOf(
            "putExtra()" to "Intent putExtra(String name, String value)",
            "getStringExtra()" to "String getStringExtra(String name)",
            "getIntExtra()" to "int getIntExtra(String name, int defaultValue)",
            "addFlags()" to "Intent addFlags(int flags)",
            "setAction()" to "Intent setAction(String action)"
        ),
        "Activity" to listOf(
            "findViewById()" to "T findViewById(int id)",
            "setContentView()" to "void setContentView(int layoutResID)",
            "startActivity()" to "void startActivity(Intent intent)",
            "finish()" to "void finish()",
            "getSharedPreferences()" to "SharedPreferences getSharedPreferences(String name, int mode)",
            "runOnUiThread()" to "void runOnUiThread(Runnable action)"
        ),
        "List" to listOf(
            "add()" to "boolean add(E e)",
            "get()" to "E get(int index)",
            "size()" to "int size()",
            "isEmpty()" to "boolean isEmpty()",
            "clear()" to "void clear()",
            "remove()" to "E remove(int index)"
        ),
        "ArrayList" to listOf(
            "add()" to "boolean add(E e)",
            "get()" to "E get(int index)",
            "size()" to "int size()",
            "isEmpty()" to "boolean isEmpty()",
            "clear()" to "void clear()"
        ),
        "StringBuilder" to listOf(
            "append()" to "StringBuilder append(String str)",
            "toString()" to "String toString()",
            "length()" to "int length()",
            "setLength()" to "void setLength(int newLength)"
        )
    )

    @Volatile
    private var cachedAndroidClasses: List<String>? = null

    /**
     * android.jar içindeki public sınıf adlarını tek seferde indeksler ve bellekte önbelleğe alır.
     */
    fun getOrBuildAndroidJarIndex(androidJar: File?): List<String> {
        cachedAndroidClasses?.let { return it }
        if (androidJar == null || !androidJar.isFile) return emptyList()
        val names = LinkedHashSet<String>()
        try {
            ZipFile(androidJar).use { zip ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val name = entries.nextElement().name
                    if (!name.endsWith(".class") || name.contains('$')) continue
                    if (name.startsWith("android/") || name.startsWith("java/lang/") || name.startsWith("java/util/")) {
                        val simple = name.substringAfterLast('/').removeSuffix(".class")
                        if (simple.isNotEmpty() && simple[0].isUpperCase()) {
                            names.add(simple)
                        }
                    }
                }
            }
        } catch (_: Exception) {
            return emptyList()
        }
        val list = names.sorted()
        cachedAndroidClasses = list
        return list
    }

    /**
     * Projenin res/ klasöründen @string/, @color/, @drawable/, @layout/, @id/ başvurularını toplar.
     */
    fun collectXmlResources(resDir: File?): Map<String, List<String>> {
        val result = linkedMapOf(
            "string" to linkedSetOf<String>(),
            "color" to linkedSetOf<String>(),
            "drawable" to linkedSetOf<String>(),
            "layout" to linkedSetOf<String>(),
            "id" to linkedSetOf<String>()
        )
        if (resDir == null || !resDir.isDirectory) return result.mapValues { it.value.toList() }

        val nameAttrRegex = Regex("""<(string|color)\s+[^>]*name\s*=\s*"([^"]+)"""")
        val idAttrRegex = Regex("""android:id\s*=\s*"@\+?id/([^"]+)"""")

        resDir.walkTopDown().filter { it.isFile }.forEach { f ->
            val parent = f.parentFile?.name?.lowercase() ?: ""
            when {
                parent.startsWith("drawable") || parent.startsWith("mipmap") -> {
                    result["drawable"]?.add(f.nameWithoutExtension)
                }
                parent.startsWith("layout") -> {
                    result["layout"]?.add(f.nameWithoutExtension)
                    if (f.extension.equals("xml", ignoreCase = true) && f.length() < 512_000L) {
                        val content = f.readText(Charsets.UTF_8)
                        for (m in idAttrRegex.findAll(content)) {
                            result["id"]?.add(m.groupValues[1])
                        }
                    }
                }
                parent.startsWith("values") && f.extension.equals("xml", ignoreCase = true) -> {
                    if (f.length() < 512_000L) {
                        val content = f.readText(Charsets.UTF_8)
                        for (m in nameAttrRegex.findAll(content)) {
                            val type = m.groupValues[1]
                            val resName = m.groupValues[2]
                            result[type]?.add(resName)
                        }
                    }
                }
            }
        }
        return result.mapValues { it.value.sorted() }
    }

    /**
     * İmleç konumuna göre Java veya XML otomatik tamamlama adaylarını üretir.
     */
    fun computeCompletions(
        text: CharSequence,
        cursorOffset: Int,
        language: String,
        resDir: File? = null,
        androidClasses: List<String> = emptyList(),
        projectIdentifiers: List<String> = emptyList()
    ): Pair<Int, List<CompletionItem>> {
        val safeCursor = cursorOffset.coerceIn(0, text.length)
        return when (language) {
            "java" -> computeJavaCompletions(text, safeCursor, androidClasses, projectIdentifiers)
            "xml" -> computeXmlCompletions(text, safeCursor, resDir)
            else -> safeCursor to emptyList()
        }
    }

    private fun computeJavaCompletions(
        text: CharSequence,
        cursor: Int,
        androidClasses: List<String>,
        projectIdentifiers: List<String>
    ): Pair<Int, List<CompletionItem>> {
        var tokenStart = cursor
        while (tokenStart > 0 && isIdentChar(text[tokenStart - 1])) tokenStart--
        val prefix = text.subSequence(tokenStart, cursor).toString()

        // 1. "nesne." sonrası üye tamamlama (basit tür çıkarımı)
        if (tokenStart > 0 && text[tokenStart - 1] == '.') {
            var recvEnd = tokenStart - 1
            var recvStart = recvEnd
            while (recvStart > 0 && isIdentChar(text[recvStart - 1])) recvStart--
            val receiver = text.subSequence(recvStart, recvEnd).toString()
            if (receiver.isNotEmpty()) {
                val inferredType = inferReceiverType(text, receiver)
                val members = ArrayList<CompletionItem>()
                if (inferredType != null) {
                    commonAndroidMembers[inferredType]?.forEach { (sig, detail) ->
                        if (prefix.isEmpty() || sig.startsWith(prefix, ignoreCase = true)) {
                            val back = if (sig.endsWith("()")) 1 else 0
                            members.add(CompletionItem(sig, sig, "$inferredType • $detail", back))
                        }
                    }
                }
                if (receiver == "this") {
                    extractDeclaredMethods(text).forEach { mName ->
                        if (prefix.isEmpty() || mName.startsWith(prefix, ignoreCase = true)) {
                            members.add(CompletionItem("$mName()", "$mName()", "this metodu", 1))
                        }
                    }
                }
                return tokenStart to members.distinctBy { it.label }.take(25)
            }
        }

        // 2. Normal 2+ karakter tamamlama
        if (prefix.length < 2) return tokenStart to emptyList()
        val items = ArrayList<CompletionItem>()

        // Anahtar kelimeler
        for (kw in javaKeywords) {
            if (kw.startsWith(prefix, ignoreCase = true) && kw != prefix) {
                items.add(CompletionItem(kw, kw, "Anahtar kelime"))
            }
        }

        // Açık dosyadaki tanımlayıcılar
        val localIdents = Regex("""\b[A-Za-z_][A-Za-z0-9_]{2,}\b""")
            .findAll(text.subSequence(0, minOf(text.length, 60_000)))
            .map { it.value }
            .distinct()
        for (id in localIdents) {
            if (id != prefix && id.startsWith(prefix, ignoreCase = true)) {
                items.add(CompletionItem(id, id, "Dosya tanımlayıcısı"))
            }
        }

        // Proje tanımlayıcıları
        for (id in projectIdentifiers) {
            if (id != prefix && id.startsWith(prefix, ignoreCase = true)) {
                items.add(CompletionItem(id, id, "Proje sınıfı/üyeleri"))
            }
        }

        // android.jar sınıf adları
        for (cls in androidClasses) {
            if (cls != prefix && cls.startsWith(prefix, ignoreCase = true)) {
                items.add(CompletionItem(cls, cls, "Android SDK"))
            }
        }

        return tokenStart to items.distinctBy { it.label }.take(30)
    }

    private fun inferReceiverType(text: CharSequence, receiver: String): String? {
        if (receiver == "this") return "Activity"
        if (receiver.isNotEmpty() && receiver[0].isUpperCase()) return receiver // statik çağrı: Toast.makeText
        val sample = text.subSequence(0, minOf(text.length, 60_000))
        val declRegex = Regex("""\b([A-Z][A-Za-z0-9_]*)\s*(?:<[^>]+>)?\s+${Regex.escape(receiver)}\b""")
        val m = declRegex.find(sample)
        return m?.groupValues?.get(1)
    }

    private fun extractDeclaredMethods(text: CharSequence): List<String> {
        val sample = text.subSequence(0, minOf(text.length, 60_000))
        val methodRegex = Regex("""(?:public|private|protected)\s+(?:static\s+)?[A-Za-z0-9_<>\[\]]+\s+([a-z][A-Za-z0-9_]*)\s*\(""")
        return methodRegex.findAll(sample).map { it.groupValues[1] }.distinct().toList()
    }

    private fun computeXmlCompletions(
        text: CharSequence,
        cursor: Int,
        resDir: File?
    ): Pair<Int, List<CompletionItem>> {
        var tokenStart = cursor
        while (tokenStart > 0 && isXmlTokenChar(text[tokenStart - 1])) tokenStart--
        val token = text.subSequence(tokenStart, cursor).toString()

        // 1. @string/ @color/ @drawable/ @id/ kaynak başvuruları
        if (token.startsWith("@")) {
            val resources = collectXmlResources(resDir)
            val slashIdx = token.indexOf('/')
            val items = ArrayList<CompletionItem>()
            if (slashIdx < 0) {
                val types = listOf("@string/", "@color/", "@drawable/", "@layout/", "@id/", "@+id/")
                for (t in types) {
                    if (t.startsWith(token, ignoreCase = true)) {
                        items.add(CompletionItem(t, t, "Kaynak türü"))
                    }
                }
            } else {
                val resType = token.substring(1, slashIdx).removePrefix("+")
                val resPrefix = token.substring(slashIdx + 1)
                val prefixHeader = token.substring(0, slashIdx + 1)
                resources[resType]?.forEach { name ->
                    if (resPrefix.isEmpty() || name.startsWith(resPrefix, ignoreCase = true)) {
                        items.add(CompletionItem("$prefixHeader$name", "$prefixHeader$name", "res/$resType"))
                    }
                }
            }
            return tokenStart to items.take(25)
        }

        if (token.length < 2) return tokenStart to emptyList()

        val items = ArrayList<CompletionItem>()

        // 2. '<' sonrası XML etiket önerileri
        if (tokenStart > 0 && text[tokenStart - 1] == '<') {
            for (tag in xmlTags) {
                if (tag.startsWith(token, ignoreCase = true)) {
                    items.add(CompletionItem(tag, "$tag\n    android:layout_width=\"wrap_content\"\n    android:layout_height=\"wrap_content\" />", "XML Etiketi", 3))
                }
            }
            return tokenStart to items.take(20)
        }

        // 3. android: öznitelikleri
        for ((attrName, snippet) in xmlAttributes) {
            if (attrName.startsWith(token, ignoreCase = true) || attrName.substringAfter(':').startsWith(token, ignoreCase = true)) {
                val back = if (snippet.endsWith("\"\"")) 1 else 0
                items.add(CompletionItem(attrName, snippet, "XML Özniteliği", back))
            }
        }

        // 4. Yaygın değerler
        for (v in xmlValues) {
            if (v.startsWith(token, ignoreCase = true)) {
                items.add(CompletionItem(v, v, "XML Değeri"))
            }
        }

        return tokenStart to items.distinctBy { it.label }.take(25)
    }

    // ---------- 2. Anlık Tek-Dosya ECJ Analizi (Derleme Çıktısı Üretmeden) ----------

    fun analyzeJavaWithEcj(
        sourceCode: String,
        fileName: String,
        androidJarFile: File?
    ): List<LineDiagnostic> {
        if (androidJarFile == null || !androidJarFile.isFile) return emptyList()
        val tempDir = Files.createTempDirectory("ecj_lint").toFile()
        try {
            val tempFile = File(tempDir, fileName)
            tempFile.writeText(sourceCode, Charsets.UTF_8)

            val outSw = StringWriter()
            val errSw = StringWriter()
            val ecj = EcjMain(PrintWriter(outSw, true), PrintWriter(errSw, true), false, null, null)
            ecj.compile(
                arrayOf(
                    "-source", "1.8",
                    "-target", "1.8",
                    "-proc:none",
                    "-d", "none", // .class çıktısı üretmez!
                    "-encoding", "UTF-8",
                    "-bootclasspath", androidJarFile.absolutePath,
                    tempFile.absolutePath
                )
            )
            val parsed: List<BuildError> = ErrorParser.parseEcj(errSw.toString() + "\n" + outSw.toString(), tempDir)
            return parsed
                .filter { it.line > 0 }
                .map { LineDiagnostic(it.line, it.isError, it.messageTr, it.message) }
        } catch (_: Exception) {
            return emptyList()
        } finally {
            tempDir.deleteRecursively()
        }
    }

    // ---------- 3. Parantez/Tırnak Eşleştirme, Kod Katlama ve Kod Biçimlendirici ----------

    /**
     * İmleç konumundaki (veya hemen solundaki) parantez/tırnak karakterinin eşini döndürür.
     */
    fun findMatchingBracket(text: CharSequence, cursorOffset: Int): Pair<Int, Int>? {
        val len = text.length
        if (len == 0) return null
        val checkPositions = listOf(cursorOffset, cursorOffset - 1).filter { it in 0 until len }
        for (pos in checkPositions) {
            val ch = text[pos]
            val pair = when (ch) {
                '(' -> findForwardMatch(text, pos, '(', ')')
                '{' -> findForwardMatch(text, pos, '{', '}')
                '[' -> findForwardMatch(text, pos, '[', ']')
                ')' -> findBackwardMatch(text, pos, ')', '(')
                '}' -> findBackwardMatch(text, pos, '}', '{')
                ']' -> findBackwardMatch(text, pos, ']', '[')
                else -> null
            }
            if (pair != null) return pair
        }
        return null
    }

    private fun findForwardMatch(text: CharSequence, start: Int, open: Char, close: Char): Pair<Int, Int>? {
        var depth = 0
        for (i in start until text.length) {
            when (text[i]) {
                open -> depth++
                close -> {
                    depth--
                    if (depth == 0) return start to i
                }
            }
        }
        return null
    }

    private fun findBackwardMatch(text: CharSequence, start: Int, close: Char, open: Char): Pair<Int, Int>? {
        var depth = 0
        for (i in start downTo 0) {
            when (text[i]) {
                close -> depth++
                open -> {
                    depth--
                    if (depth == 0) return i to start
                }
            }
        }
        return null
    }

    /**
     * Java'da süslü parantez blokları ve XML'de çok satırlı etiketler için katlanabilir aralıkları hesaplar.
     */
    fun computeFoldRanges(text: CharSequence, language: String): List<FoldRange> {
        val lines = text.toString().split('\n')
        return when (language) {
            "xml" -> computeXmlFoldRanges(lines)
            else -> computeBraceFoldRanges(lines)
        }
    }

    private fun computeBraceFoldRanges(lines: List<String>): List<FoldRange> {
        val stack = ArrayDeque<Pair<Int, String>>()
        val result = ArrayList<FoldRange>()
        for ((lineIdx, rawLine) in lines.withIndex()) {
            val trimmed = rawLine.trim()
            if (trimmed.startsWith("//")) continue
            for (ch in rawLine) {
                if (ch == '{') {
                    stack.addLast(lineIdx to trimmed.take(48))
                } else if (ch == '}' && stack.isNotEmpty()) {
                    val (startLine, label) = stack.removeLast()
                    if (lineIdx > startLine) {
                        result.add(FoldRange(startLine, lineIdx, label))
                    }
                }
            }
        }
        return result.sortedBy { it.startLineZeroBased }
    }

    private fun computeXmlFoldRanges(lines: List<String>): List<FoldRange> {
        val openTagRegex = Regex("""<([A-Za-z_][\w:.-]*)\b[^>]*>""")
        val closeTagRegex = Regex("""</([A-Za-z_][\w:.-]*)\s*>""")
        val stack = ArrayDeque<Pair<Int, String>>()
        val result = ArrayList<FoldRange>()

        for ((lineIdx, rawLine) in lines.withIndex()) {
            val trimmed = rawLine.trim()
            if (trimmed.startsWith("<?") || trimmed.startsWith("<!--")) continue
            for (m in openTagRegex.findAll(trimmed)) {
                val full = m.value
                if (full.endsWith("/>")) continue
                val tag = m.groupValues[1]
                stack.addLast(lineIdx to tag)
            }
            for (m in closeTagRegex.findAll(trimmed)) {
                val tag = m.groupValues[1]
                val matchIdx = stack.indexOfLast { it.second == tag }
                if (matchIdx >= 0) {
                    val (startLine, label) = stack.removeAt(matchIdx)
                    if (lineIdx > startLine) {
                        result.add(FoldRange(startLine, lineIdx, "<$label>"))
                    }
                }
            }
        }
        return result.sortedBy { it.startLineZeroBased }
    }

    /** Java kodu için girinti biçimlendirici (4 boşluk). */
    fun formatJava(code: String, indentUnit: String = "    "): String {
        val sb = StringBuilder(code.length)
        var indentLevel = 0
        val lines = code.replace("\r\n", "\n").split('\n')
        for ((idx, raw) in lines.withIndex()) {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) {
                if (idx < lines.lastIndex) sb.append('\n')
                continue
            }
            val startsWithClose = trimmed.startsWith("}") || trimmed.startsWith(")")
            val effectiveIndent = (if (startsWithClose) indentLevel - 1 else indentLevel).coerceAtLeast(0)
            repeat(effectiveIndent) { sb.append(indentUnit) }
            sb.append(trimmed)
            if (idx < lines.lastIndex) sb.append('\n')

            var delta = 0
            var inStr = false
            var prev = '\u0000'
            for (c in trimmed) {
                if (c == '"' && prev != '\\') inStr = !inStr
                if (!inStr) {
                    if (c == '{') delta++
                    else if (c == '}') delta--
                }
                prev = c
            }
            indentLevel = (indentLevel + delta).coerceAtLeast(0)
        }
        return sb.toString()
    }

    /** XML kodu için pretty-print girinti biçimlendirici. */
    fun formatXml(code: String, indentUnit: String = "    "): String {
        val sb = StringBuilder(code.length)
        var indentLevel = 0
        val lines = code.replace("\r\n", "\n").split('\n')
        for ((idx, raw) in lines.withIndex()) {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) {
                if (idx < lines.lastIndex) sb.append('\n')
                continue
            }
            val isClosing = trimmed.startsWith("</")
            val isSelfClosing = trimmed.endsWith("/>") || trimmed.startsWith("<?") || trimmed.startsWith("<!--")
            val isOpening = trimmed.startsWith("<") && !isClosing && !isSelfClosing && !trimmed.contains("</")

            if (isClosing) indentLevel = (indentLevel - 1).coerceAtLeast(0)
            repeat(indentLevel) { sb.append(indentUnit) }
            sb.append(trimmed)
            if (idx < lines.lastIndex) sb.append('\n')
            if (isOpening) indentLevel++
        }
        return sb.toString()
    }

    fun formatCode(code: String, language: String): String = when (language) {
        "xml" -> formatXml(code)
        else -> formatJava(code)
    }

    // ---------- 4. Tanıma Git (Go To Definition) ----------

    fun findDefinition(
        currentText: CharSequence,
        symbolName: String,
        projectRoot: File?
    ): DefinitionLocation? {
        if (symbolName.isBlank()) return null
        val esc = Regex.escape(symbolName)
        val defRegex = Regex(
            """\b(?:class|interface|enum)[ \t]+$esc\b|(?:(?:public|private|protected|static|final)[ \t]+)*[A-Za-z0-9_<>\[\]]+[ \t]+$esc[ \t]*\("""
        )

        // 1. Önce aktif dosyada ara
        findInText(currentText, defRegex)?.let { (line, preview) ->
            return DefinitionLocation(null, line, preview)
        }

        // 2. Projedeki diğer .java dosyalarında ara
        val srcDir = projectRoot?.let { File(it, "src") }?.takeIf { it.isDirectory } ?: return null
        for (javaFile in srcDir.walkTopDown().filter { it.isFile && it.extension.equals("java", ignoreCase = true) }) {
            try {
                val content = javaFile.readText(Charsets.UTF_8)
                findInText(content, defRegex)?.let { (line, preview) ->
                    return DefinitionLocation(javaFile, line, preview)
                }
            } catch (_: Exception) {}
        }
        return null
    }

    private fun findInText(text: CharSequence, regex: Regex): Pair<Int, String>? {
        val m = regex.find(text) ?: return null
        val lineIndex = LineIndex().apply { rebuild(text) }
        val line0 = lineIndex.lineAtOffset(m.range.first)
        val lStart = lineIndex.lineStart(line0)
        val lEnd = lineIndex.lineEnd(line0, text.length)
        return (line0 + 1) to text.subSequence(lStart, lEnd).toString().trim()
    }

    private fun isIdentChar(c: Char): Boolean = c.isLetterOrDigit() || c == '_' || c == '$'
    private fun isXmlTokenChar(c: Char): Boolean = c.isLetterOrDigit() || c == '_' || c == ':' || c == '@' || c == '/' || c == '+' || c == '-'
}
