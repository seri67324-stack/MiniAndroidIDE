package com.miniide.app.builder

interface BuildLogger {
    fun info(step: String, message: String)
    fun error(step: String, message: String)
    fun onProgress(step: String, percent: Int)
    /** Yapılandırılmış hata listesi (ECJ/AAPT2 ayrıştırması). */
    fun onErrors(errors: List<BuildError>) {}
}
