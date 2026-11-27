package com.example.weather

import android.app.Activity
import android.app.AlertDialog
import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Process
import android.widget.Toast
import java.io.PrintWriter
import java.io.StringWriter
import java.util.Date
import kotlin.system.exitProcess

/**
 * Installs an early uncaught-exception handler and stores a local diagnostic
 * report. The report is shown with the platform UI on the next app launch,
 * before Compose is initialized.
 */
class QandilApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        val rainAlertsEnabled = getSharedPreferences("qandil_native", Context.MODE_PRIVATE)
            .getBoolean("rain_notifications", false)
        if (rainAlertsEnabled) scheduleRainMonitoring(this)

        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                CrashDiagnostics.save(
                    this,
                    CrashDiagnostics.createReport(this, thread, throwable)
                )
            } catch (loggingError: Throwable) {
                android.util.Log.e("QandilCrash", "Could not save crash report", loggingError)
            }

            if (previousHandler != null) {
                previousHandler.uncaughtException(thread, throwable)
            } else {
                Process.killProcess(Process.myPid())
                exitProcess(10)
            }
        }
    }
}

object CrashDiagnostics {
    private const val PREFS_NAME = "qandil_crash_diagnostics"
    private const val REPORT_KEY = "last_uncaught_exception"

    fun read(context: Context): String? =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(REPORT_KEY, null)

    fun save(context: Context, report: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(REPORT_KEY, report.take(40_000))
            .commit()
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(REPORT_KEY)
            .commit()
    }

    fun createReport(context: Context, thread: Thread, throwable: Throwable): String {
        val packageInfo = runCatching { context.packageManager.getPackageInfo(context.packageName, 0) }.getOrNull()
        val version = packageInfo?.let {
            val code = if (Build.VERSION.SDK_INT >= 28) it.longVersionCode else it.versionCode.toLong()
            "${it.versionName ?: "unknown"} ($code)"
        } ?: "unknown"

        val writer = StringWriter()
        PrintWriter(writer).use { throwable.printStackTrace(it) }

        return buildString {
            appendLine("Qandil crash report")
            appendLine("App version: $version")
            appendLine("Package: ${context.packageName}")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("Thread: ${thread.name}")
            appendLine("Time: ${Date()}")
            appendLine()
            append(writer.toString())
        }.take(40_000)
    }
}

fun Activity.showCrashReportIfAvailable(onContinue: () -> Unit): Boolean {
    val report = CrashDiagnostics.read(this)?.takeIf { it.isNotBlank() } ?: return false

    AlertDialog.Builder(this)
        .setTitle("گزارش خطای قندیل")
        .setMessage(
            "قندیل در اجرای قبلی ناگهان بسته شد. گزارش فنی ذخیره شده است. " +
                "برای پیدا کردن علت، آن را کپی کن و در گفت‌وگو بفرست."
        )
        .setPositiveButton("کپی گزارش و خروج") { _, _ ->
            val copied = runCatching {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("گزارش خطای قندیل", report))
            }.isSuccess

            if (copied) {
                CrashDiagnostics.clear(this)
                Toast.makeText(this, "گزارش کپی شد؛ آن را در گفت‌وگو ارسال کن.", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this, "کپی گزارش انجام نشد؛ دوباره تلاش کن.", Toast.LENGTH_LONG).show()
            }
            finish()
        }
        .setNegativeButton("ادامه اجرای برنامه") { _, _ ->
            CrashDiagnostics.clear(this)
            onContinue()
        }
        .setNeutralButton("بستن") { _, _ -> finish() }
        .setOnCancelListener { finish() }
        .show()

    return true
}
