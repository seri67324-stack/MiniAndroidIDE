package com.miniide.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.miniide.app.MainActivity
import com.miniide.app.builder.BuildError
import com.miniide.app.builder.BuildLogger
import com.miniide.app.builder.LegacyProjectBuilder
import com.miniide.app.project.ProjectManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

/**
 * Derleme hattını arka planda (Foreground Service) çalıştıran servis.
 */
class CompileService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var activeBuilder: LegacyProjectBuilder? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // Kanal yalnızca bir kez oluşturulur (her bildirim güncellemesinde yeniden oluşturulmaz).
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Derleme Hizmeti", NotificationManager.IMPORTANCE_LOW))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            if (job?.isActive == true) {
                activeBuilder?.cancelActiveProcess()
                job?.cancel()
                BuildEvents.post("⚠ Derleme kullanıcı tarafından iptal edildi.")
            } else {
                stopSelf()
            }
            return START_NOT_STICKY
        }

        val notif = buildNotification("Derleme hazırlanıyor...")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, notif)
        }

        if (job?.isActive == true) return START_NOT_STICKY

        val projectPath = intent?.getStringExtra(EXTRA_PROJECT)
        BuildEvents.reset()
        BuildEvents.running = true

        job = scope.launch {
            val logger = object : BuildLogger {
                override fun info(step: String, message: String) = BuildEvents.post("[$step] $message")
                override fun error(step: String, message: String) = BuildEvents.post("[$step] HATA: $message")
                override fun onErrors(errors: List<BuildError>) = BuildEvents.postErrors(errors)
                override fun onProgress(step: String, percent: Int) {
                    BuildEvents.postProgress(step, percent)
                    updateNotification("$step (%$percent)")
                }
            }
            var finalText = "Derleme tamamlandı"
            try {
                val sdkDir = File(filesDir, "sdk").apply { mkdirs() }
                val androidJar = File(sdkDir, "android.jar")
                if (!androidJar.exists() || androidJar.length() == 0L) {
                    logger.info("Hazırlık", "SDK android.jar (API 34) çıkarılıyor...")
                    assets.open("android.jar").use { i ->
                        androidJar.outputStream().use { o -> i.copyTo(o) }
                    }
                }

                val projectDir = File(
                    projectPath ?: ProjectManager.current(this@CompileService)?.absolutePath
                        ?: throw IllegalStateException("Proje seçilmedi")
                )
                logger.info("Hazırlık", "Proje: ${projectDir.name}")
                BuildEvents.lastProject = projectDir

                val builder = LegacyProjectBuilder(this@CompileService, projectDir, androidJar, logger)
                activeBuilder = builder
                val apk = builder.build(this)

                BuildEvents.lastDurationMs = System.currentTimeMillis() - BuildEvents.startTimeMs
                BuildEvents.lastApk = apk
                BuildEvents.lastPackage = Regex("""package\s*=\s*"([^"]+)"""")
                    .find(File(projectDir, "AndroidManifest.xml").readText())?.groupValues?.get(1)

                val sec = String.format("%.1f", BuildEvents.lastDurationMs / 1000.0)
                BuildEvents.post("✓ BAŞARILI ($sec sn): ${apk.absolutePath}")
                finalText = "APK hazır (${apk.name})"
            } catch (e: kotlinx.coroutines.CancellationException) {
                BuildEvents.lastDurationMs = System.currentTimeMillis() - BuildEvents.startTimeMs
                BuildEvents.post("⚠ Derleme durduruldu.")
                finalText = "Derleme iptal edildi"
            } catch (e: Throwable) {
                BuildEvents.lastDurationMs = System.currentTimeMillis() - BuildEvents.startTimeMs
                BuildEvents.post("✗ BAŞARISIZ: ${e.javaClass.simpleName}: ${e.message}")
                if (BuildEvents.lastErrors.isEmpty()) {
                    val m = e.message ?: e.javaClass.simpleName
                    BuildEvents.postErrors(listOf(BuildError("Sistem", true, null, 0, m, m)))
                }
                finalText = "Derleme başarısız"
            } finally {
                activeBuilder = null
                BuildEvents.running = false
                BuildEvents.finished?.invoke()
                stopForeground(STOP_FOREGROUND_DETACH)
                // Bitiş bildirimi: kaydırılarak kapatılabilir, dokununca uygulamayı açar.
                postFinalNotification(finalText)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun buildNotification(text: String, ongoing: Boolean = true, finished: Boolean = false): Notification {
        val open = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pending = PendingIntent.getActivity(
            this, 0, open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL)
            .setContentTitle("Mini Android IDE")
            .setContentText(text)
            .setSmallIcon(if (finished) android.R.drawable.stat_sys_download_done else android.R.drawable.stat_sys_download)
            .setContentIntent(pending)
            .setOngoing(ongoing)
            .setAutoCancel(!ongoing)
            .build()
    }

    private fun updateNotification(text: String) {
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIF_ID, buildNotification(text))
    }

    private fun postFinalNotification(text: String) {
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIF_ID, buildNotification(text, ongoing = false, finished = true))
    }

    override fun onDestroy() {
        activeBuilder?.cancelActiveProcess()
        job?.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_CANCEL = "com.miniide.app.CANCEL"
        const val EXTRA_PROJECT = "project"
        private const val CHANNEL = "build"
        private const val NOTIF_ID = 1
    }
}
