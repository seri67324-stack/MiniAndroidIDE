package com.miniide.app.builder

data class BuildError(
    val source: String,          // "ECJ", "AAPT2", "D8", "Sistem"
    val isError: Boolean,
    val file: String?,           // projeye göre göreli yol
    val line: Int,               // 0 = bilinmiyor
    val message: String,         // orijinal (İngilizce) mesaj
    val messageTr: String        // Türkçe açıklama (çevrilemezse orijinal)
) {
    fun normalizedFile(): String? = file?.replace('\\', '/')

    fun location(): String {
        val f = normalizedFile() ?: return source
        val name = f.substringAfterLast('/')
        return if (line > 0) "$name:$line" else name
    }

    fun asText(): String = buildString {
        append(if (isError) "HATA" else "UYARI").append(" [").append(source).append("]")
        val f = normalizedFile()
        if (f != null) append(" ").append(f).append(if (line > 0) ":$line" else "")
        append("\n").append(messageTr)
        if (messageTr != message) append("\n(Orijinal: ").append(message).append(")")
    }
}
