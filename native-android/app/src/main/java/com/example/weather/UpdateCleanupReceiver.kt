package com.example.weather

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Environment

/** Deletes only updater APKs after Android confirms this package was replaced. */
internal class UpdateCleanupReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return

        val preferences = context.getSharedPreferences("app_update_cleanup", Context.MODE_PRIVATE)
        val pendingDownloadId = preferences.getLong("pending_update_download_id", -1L)
        if (pendingDownloadId >= 0L) {
            runCatching {
                context.getSystemService(DownloadManager::class.java)?.remove(pendingDownloadId)
            }
        }
        preferences.edit().remove("pending_update_download_id").apply()

        val downloadDirectory = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: return
        downloadDirectory.listFiles()?.forEach { file ->
            val isUpdaterApk = file.isFile &&
                (file.name.startsWith("app-update-") ||
                 file.name.startsWith("file-manager-update-")) &&
                file.name.endsWith(".apk", ignoreCase = true)
            if (isUpdaterApk) runCatching { file.delete() }
        }
    }
}
