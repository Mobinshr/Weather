package com.example.weather

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale

private const val UPDATE_APP_NAME = "Qandil Weather"

internal data class AppUpdateInfo(
    val versionName: String,
    val versionCode: Long?,
    val packageName: String,
    val releaseNotes: String,
    val downloadUrl: String,
    val sha256: String
)

internal object AppUpdateClient {
    private const val REPOSITORY = "Mobinshr/Weather"
    private const val RELEASE_API = "https://api.github.com/repos/" + REPOSITORY + "/releases/latest"
    private const val DOWNLOAD_PREFIX = "https://github.com/" + REPOSITORY + "/releases/download/"

    suspend fun checkForUpdate(context: Context): AppUpdateInfo? = withContext(Dispatchers.IO) {
        val connection = (URL(RELEASE_API).openConnection() as HttpURLConnection).apply {
            connectTimeout = 12_000
            readTimeout = 15_000
            requestMethod = "GET"
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", UPDATE_APP_NAME.replace(" ", "-") + "-Android-Updater")
        }

        try {
            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                throw IOException("دریافت اطلاعات نسخه با کد " + responseCode + " ناموفق بود")
            }

            val release = connection.inputStream.bufferedReader().use { JSONObject(it.readText()) }
            val body = release.optString("body", "")
            val versionName = capture(body, Regex("""(?m)^\s*(?:[-*]\s*)?(?:\*\*)?App version:(?:\*\*)?\s*(\S+)\s*$"""))
                ?: throw IOException("شمارهٔ نسخه در اطلاعات انتشار پیدا نشد")
            val packageName = capture(body, Regex("""(?m)^\s*(?:[-*]\s*)?(?:\*\*)?Package:(?:\*\*)?\s*(\S+)\s*$"""))
                ?: throw IOException("شناسهٔ برنامه در اطلاعات انتشار پیدا نشد")

            if (packageName != context.packageName) {
                throw SecurityException("این به‌روزرسانی برای این برنامه ساخته نشده است")
            }

            val remoteVersionCode = capture(body, Regex("""(?m)^\s*(?:[-*]\s*)?(?:\*\*)?Version code:(?:\*\*)?\s*(\d+)\s*$"""))
                ?.toLongOrNull()

            val assets = release.optJSONArray("assets") ?: throw IOException("فایل نصب در انتشار پیدا نشد")
            var apkAsset: JSONObject? = null
            for (index in 0 until assets.length()) {
                val item = assets.optJSONObject(index) ?: continue
                if (item.optString("name") == "app-release.apk") {
                    apkAsset = item
                    break
                }
            }
            val asset = apkAsset ?: throw IOException("فایل APK نسخهٔ جدید موجود نیست")
            val downloadUrl = asset.optString("browser_download_url")
            if (!downloadUrl.startsWith(DOWNLOAD_PREFIX)) {
                throw SecurityException("نشانی دانلود معتبر نیست")
            }

            val sha256 = asset.optString("digest").removePrefix("sha256:").lowercase(Locale.US)
            if (!sha256.matches(Regex("[a-f0-9]{64}"))) {
                throw IOException("امضای محتوایی APK در اطلاعات انتشار موجود نیست")
            }

            @Suppress("DEPRECATION")
            val installed = context.packageManager.getPackageInfo(context.packageName, 0)
            val installedVersionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                installed.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                installed.versionCode.toLong()
            }
            val isNewer = if (remoteVersionCode != null) {
                remoteVersionCode > installedVersionCode
            } else {
                compareVersions(versionName, installed.versionName ?: "0") > 0
            }
            if (!isNewer) return@withContext null

            val notes = body.lineSequence()
                .filterNot {
                    val line = it.trim().removePrefix("- ").removePrefix("* ").replace("**", "").trim()
                    line.startsWith("App version:") ||
                        line.startsWith("Version code:") ||
                        line.startsWith("Package:") ||
                        line.startsWith("Minimum Android:") ||
                        line.startsWith("Commit:")
                }
                .joinToString("\n")
                .trim()
                .ifBlank { "بهبود عملکرد و رفع اشکال‌های برنامه." }

            AppUpdateInfo(
                versionName = versionName,
                versionCode = remoteVersionCode,
                packageName = packageName,
                releaseNotes = notes,
                downloadUrl = downloadUrl,
                sha256 = sha256
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun capture(source: String, pattern: Regex): String? =
        pattern.find(source)?.groupValues?.getOrNull(1)

    private fun compareVersions(first: String, second: String): Int {
        val a = Regex("""\d+""").findAll(first).mapNotNull { it.value.toLongOrNull() }.toList()
        val b = Regex("""\d+""").findAll(second).mapNotNull { it.value.toLongOrNull() }.toList()
        for (index in 0 until maxOf(a.size, b.size)) {
            val left = a.getOrElse(index) { 0L }
            val right = b.getOrElse(index) { 0L }
            if (left != right) return left.compareTo(right)
        }
        return 0
    }
}

@Composable
internal fun AppUpdateControl() {
    val context = LocalContext.current
    val preferences = remember(context) {
        context.getSharedPreferences("app_update_preferences", Context.MODE_PRIVATE)
    }
    val scope = rememberCoroutineScope()
    val currentVersionName = remember(context) {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "نامشخص"
        }.getOrDefault("نامشخص")
    }
    var checking by remember { mutableStateOf(false) }
    var updateInfo by remember { mutableStateOf<AppUpdateInfo?>(null) }
    var feedbackMessage by remember { mutableStateOf<String?>(null) }

    val performCheck: (Boolean) -> Unit = { showFeedback ->
        if (!checking) {
            scope.launch {
                checking = true
                try {
                    val available = AppUpdateClient.checkForUpdate(context)
                    updateInfo = available
                    if (available == null && showFeedback) {
                        feedbackMessage = "برنامه به‌روز است و نسخهٔ جدیدتری پیدا نشد."
                    }
                } catch (error: Exception) {
                    if (showFeedback) {
                        feedbackMessage = error.message ?: "بررسی به‌روزرسانی ناموفق بود؛ اتصال اینترنت را بررسی کن."
                    }
                } finally {
                    preferences.edit().putLong("last_update_check", System.currentTimeMillis()).apply()
                    checking = false
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        val lastCheck = preferences.getLong("last_update_check", 0L)
        val oneDay = 24L * 60L * 60L * 1000L
        if (System.currentTimeMillis() - lastCheck >= oneDay) performCheck(false)
    }

    IconButton(
        onClick = { performCheck(true) },
        enabled = !checking,
        modifier = Modifier.size(40.dp)
    ) {
        if (checking) {
            CircularProgressIndicator(modifier = Modifier.size(19.dp), strokeWidth = 2.dp)
        } else {
            Icon(Icons.Default.Refresh, contentDescription = "بررسی به‌روزرسانی")
        }
    }

    updateInfo?.let { info ->
        AppUpdateDialog(
            info = info,
            currentVersionName = currentVersionName,
            onDismiss = { updateInfo = null }
        )
    }

    feedbackMessage?.let { message ->
        AlertDialog(
            onDismissRequest = { feedbackMessage = null },
            title = { Text(UPDATE_APP_NAME) },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = { feedbackMessage = null }) { Text("باشه") }
            }
        )
    }
}

@Composable
private fun AppUpdateDialog(
    info: AppUpdateInfo,
    currentVersionName: String,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var downloading by remember(info.versionName) { mutableStateOf(false) }
    var progress by remember(info.versionName) { mutableIntStateOf(-1) }
    var statusText by remember(info.versionName) { mutableStateOf("") }
    var errorText by remember(info.versionName) { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = { if (!downloading) onDismiss() },
        title = { Text("نسخهٔ جدید " + UPDATE_APP_NAME) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Text("نسخهٔ فعلی: " + currentVersionName)
                Text("نسخهٔ جدید: " + info.versionName, color = MaterialTheme.colorScheme.primary)
                Text("تغییرات این نسخه", style = MaterialTheme.typography.titleSmall)
                Text(info.releaseNotes, style = MaterialTheme.typography.bodyMedium)
                if (downloading) {
                    Spacer(Modifier.height(4.dp))
                    if (progress in 0..100) {
                        LinearProgressIndicator(
                            progress = progress / 100f,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(progress.toString() + "٪", style = MaterialTheme.typography.labelMedium)
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    Text("در حال دریافت فایل نصب...")
                }
                if (statusText.isNotBlank()) {
                    Text(statusText, color = MaterialTheme.colorScheme.primary)
                }
                errorText?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Text(
                    "پس از دانلود، نصب از طریق پنجرهٔ رسمی اندروید انجام می‌شود و تأیید نهایی با شماست.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = !downloading,
                onClick = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                        !context.packageManager.canRequestPackageInstalls()
                    ) {
                        val settingsIntent = Intent(
                            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:" + context.packageName)
                        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(settingsIntent)
                        statusText = "مجوز نصب برنامه‌های ناشناس را فعال کن و دوباره روی دانلود و نصب بزن."
                        return@TextButton
                    }

                    downloading = true
                    progress = 0
                    errorText = null
                    statusText = ""
                    scope.launch {
                        try {
                            downloadAndInstall(context, info) { value -> progress = value }
                            statusText = "دانلود کامل شد؛ تأیید نصب را در پنجرهٔ اندروید انجام بده."
                        } catch (error: Exception) {
                            errorText = error.message ?: "دانلود یا بررسی فایل نصب ناموفق بود."
                        } finally {
                            downloading = false
                        }
                    }
                }
            ) {
                Text(if (downloading) "در حال دانلود..." else "دانلود و نصب")
            }
        },
        dismissButton = {
            TextButton(enabled = !downloading, onClick = onDismiss) { Text("بعداً") }
        }
    )
}

private data class DownloadSnapshot(
    val status: Int,
    val reason: Int,
    val progress: Int
)

private suspend fun downloadAndInstall(
    context: Context,
    info: AppUpdateInfo,
    onProgress: (Int) -> Unit
) {
    val manager = context.getSystemService(DownloadManager::class.java)
        ?: throw IOException("مدیر دانلود اندروید در دسترس نیست")
    val safeVersion = info.versionName.replace(Regex("""[^A-Za-z0-9._-]"""), "_")
    val request = DownloadManager.Request(Uri.parse(info.downloadUrl))
        .setTitle(UPDATE_APP_NAME + " " + info.versionName)
        .setDescription("دریافت به‌روزرسانی برنامه")
        .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
        .setAllowedOverMetered(true)
        .setAllowedOverRoaming(false)
        .setDestinationInExternalFilesDir(
            context,
            Environment.DIRECTORY_DOWNLOADS,
            "app-update-" + safeVersion + "-" + System.currentTimeMillis() + ".apk"
        )

    var downloadId = -1L
    try {
        downloadId = withContext(Dispatchers.IO) { manager.enqueue(request) }
        context.getSharedPreferences("app_update_cleanup", Context.MODE_PRIVATE)
            .edit()
            .putLong("pending_update_download_id", downloadId)
            .apply()
        val startedAt = System.currentTimeMillis()
        while (true) {
            if (System.currentTimeMillis() - startedAt > 20L * 60L * 1000L) {
                throw IOException("دانلود بیش از حد طول کشید؛ دوباره تلاش کن")
            }
            val snapshot = queryDownload(manager, downloadId)
            onProgress(snapshot.progress)
            when (snapshot.status) {
                DownloadManager.STATUS_SUCCESSFUL -> break
                DownloadManager.STATUS_FAILED -> {
                    throw IOException("دانلود APK ناموفق بود (کد " + snapshot.reason + ")")
                }
                else -> delay(450L)
            }
        }

        val apkUri = withContext(Dispatchers.IO) { manager.getUriForDownloadedFile(downloadId) }
            ?: throw IOException("فایل دانلودشده در دسترس نیست")
        val actualDigest = withContext(Dispatchers.IO) { calculateSha256(context, apkUri) }
        if (!actualDigest.equals(info.sha256, ignoreCase = true)) {
            throw SecurityException("بررسی سلامت APK ناموفق بود؛ فایل نصب رد شد")
        }

        val installIntent = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        withContext(Dispatchers.Main) { context.startActivity(installIntent) }
        onProgress(100)
    } catch (error: Exception) {
        if (downloadId >= 0L) {
            withContext(Dispatchers.IO) { manager.remove(downloadId) }
        }
        context.getSharedPreferences("app_update_cleanup", Context.MODE_PRIVATE)
            .edit()
            .remove("pending_update_download_id")
            .apply()
        throw error
    }
}

private suspend fun queryDownload(
    manager: DownloadManager,
    downloadId: Long
): DownloadSnapshot = withContext(Dispatchers.IO) {
    val cursor = manager.query(DownloadManager.Query().setFilterById(downloadId))
        ?: throw IOException("وضعیت دانلود قابل خواندن نیست")
    cursor.use {
        if (!it.moveToFirst()) throw IOException("رکورد دانلود پیدا نشد")
        val status = it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
        val reason = it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
        val downloaded = it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
        val total = it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
        val percent = if (total > 0L) {
            ((downloaded * 100L) / total).toInt().coerceIn(0, 100)
        } else {
            -1
        }
        DownloadSnapshot(status, reason, percent)
    }
}

private fun calculateSha256(context: Context, uri: Uri): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val input = context.contentResolver.openInputStream(uri)
        ?: throw IOException("نمی‌توان فایل دانلودشده را خواند")
    input.use {
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val read = it.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
