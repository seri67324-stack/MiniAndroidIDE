package com.miniide.app.service

import com.miniide.app.builder.BuildError
import java.io.File

/** Servis ile ekran arasındaki paylaşılan durum. Ekran kapanıp açılsa da veri korunur. */
object BuildEvents {
    private val logBuf = StringBuilder()

    @Volatile var listener: ((String) -> Unit)? = null
    @Volatile var progress: ((String, Int) -> Unit)? = null
    @Volatile var errorsListener: ((List<BuildError>) -> Unit)? = null
    @Volatile var finished: (() -> Unit)? = null

    @Volatile var running: Boolean = false
    @Volatile var currentStep: String = "Hazır"
    @Volatile var currentPercent: Int = 0
    @Volatile var startTimeMs: Long = 0L
    @Volatile var lastDurationMs: Long = 0L
    @Volatile var lastApk: File? = null
    @Volatile var lastPackage: String? = null
    @Volatile var lastProject: File? = null
    @Volatile var lastErrors: List<BuildError> = emptyList()

    fun reset() {
        synchronized(logBuf) { logBuf.setLength(0) }
        currentStep = "Hazırlık"
        currentPercent = 0
        startTimeMs = System.currentTimeMillis()
        lastDurationMs = 0L
        lastApk = null
        lastPackage = null
        lastProject = null
        lastErrors = emptyList()
    }

    fun post(line: String) {
        synchronized(logBuf) { logBuf.append(line).append('\n') }
        listener?.invoke(line)
    }

    fun postProgress(step: String, percent: Int) {
        currentStep = step
        currentPercent = percent
        progress?.invoke(step, percent)
    }

    /** Son derlemenin verilen projeye ait olup olmadığını söyler (başka projenin APK/log bilgisini göstermemek için). */
    fun matchesProject(projectDir: File?): Boolean {
        val last = lastProject ?: return false
        val cur = projectDir ?: return false
        return try {
            last.canonicalPath == cur.canonicalPath
        } catch (_: java.io.IOException) {
            last.absolutePath == cur.absolutePath
        }
    }

    fun fullLog(): String = synchronized(logBuf) { logBuf.toString() }

    fun postErrors(errors: List<BuildError>) {
        lastErrors = errors
        errorsListener?.invoke(errors)
    }
}
