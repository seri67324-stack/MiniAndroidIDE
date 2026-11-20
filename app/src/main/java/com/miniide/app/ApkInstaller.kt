package com.miniide.app

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File

/** APK kurma, paylaşma ve kurulan uygulamayı başlatma yardımcıları. */
object ApkInstaller {

    enum class Result { STARTED, NEEDS_PERMISSION, FILE_MISSING }

    fun install(activity: Activity, apk: File): Result {
        if (!apk.exists()) return Result.FILE_MISSING

        if (!activity.packageManager.canRequestPackageInstalls()) {
            activity.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}"))
            )
            return Result.NEEDS_PERMISSION
        }

        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", apk)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        activity.startActivity(intent)
        return Result.STARTED
    }

    /** Kurulu uygulamayı açar. Kurulu değilse false döner. */
    fun launch(activity: Activity, packageName: String): Boolean {
        val intent = activity.packageManager.getLaunchIntentForPackage(packageName) ?: return false
        activity.startActivity(intent)
        return true
    }

    /** Üretilen APK veya dışa aktarılan ZIP dosyasını sistem paylaşım menüsüyle paylaşır. */
    fun shareFile(activity: Activity, file: File, mimeType: String, title: String): Boolean {
        if (!file.exists()) return false
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", file)
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        activity.startActivity(Intent.createChooser(shareIntent, title))
        return true
    }
}
