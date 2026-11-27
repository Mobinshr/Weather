package com.example.weather

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

private const val RAIN_MONITOR_WORK_NAME = "qandil_rain_monitor"

/**
 * Periodic background check. WorkManager may defer runs to respect Doze and
 * battery optimizations; this is a best-effort forecast monitor, not an alarm.
 */
class RainAlertWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val repository = WeatherRepository(applicationContext)
        if (!repository.notificationsEnabled()) return Result.success()
        return try {
            val city = repository.loadSavedCity()
            val weather = repository.loadWeather(city, forceRefresh = true)
            sendRainNotifications(applicationContext, weather)
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }
}

fun scheduleRainMonitoring(context: Context) {
    val constraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()
    val request = PeriodicWorkRequestBuilder<RainAlertWorker>(15, TimeUnit.MINUTES)
        .setConstraints(constraints)
        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
        .build()
    WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
        RAIN_MONITOR_WORK_NAME,
        ExistingPeriodicWorkPolicy.UPDATE,
        request
    )
}

fun cancelRainMonitoring(context: Context) {
    WorkManager.getInstance(context.applicationContext).cancelUniqueWork(RAIN_MONITOR_WORK_NAME)
}
