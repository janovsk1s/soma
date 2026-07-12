package com.soma.app

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.time.Duration
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

object CalendarChangeEvents {
    private val mutableChanges = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val changes = mutableChanges.asSharedFlow()

    fun notifyChanged() {
        mutableChanges.tryEmit(Unit)
    }
}

object NotificationPermission {
    fun granted(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
}

object DailyReminderScheduler {
    private const val UNIQUE_WORK = "soma-daily-reminder"
    private const val REMINDER_HOUR = 9

    fun setEnabled(context: Context, enabled: Boolean) {
        SomaPrefs.setReminder(context, enabled)
        val work = WorkManager.getInstance(context)
        if (!enabled) {
            work.cancelUniqueWork(UNIQUE_WORK)
            NotificationCenter.cancelReminder(context)
            return
        }
        scheduleNext(context)
    }

    fun scheduleNext(context: Context) {
        if (!SomaPrefs.reminder(context) || !NotificationPermission.granted(context)) return
        val now = ZonedDateTime.now()
        var next = now.toLocalDate().atTime(REMINDER_HOUR, 0).atZone(now.zone)
        if (!next.isAfter(now)) next = next.plusDays(1)
        val delay = Duration.between(now, next).toMillis().coerceAtLeast(1L)
        val request = OneTimeWorkRequestBuilder<DailyReminderWorker>()
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE_WORK, ExistingWorkPolicy.REPLACE, request)
    }
}

class DailyReminderWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        if (SomaPrefs.reminder(applicationContext) && NotificationPermission.granted(applicationContext)) {
            NotificationCenter.showReminder(applicationContext)
            DailyReminderScheduler.scheduleNext(applicationContext)
        }
        return Result.success()
    }
}

class ReminderRescheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action !in RESCHEDULE_ACTIONS) return
        CalendarChangeEvents.notifyChanged()
        if (SomaPrefs.reminder(context)) DailyReminderScheduler.scheduleNext(context)
    }

    private companion object {
        val RESCHEDULE_ACTIONS = setOf(
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_DATE_CHANGED,
        )
    }
}

object NotificationCenter {
    private const val REMINDER_CHANNEL = "soma_reminder"
    private const val BROWSER_CHANNEL = "soma_browser"
    private const val REMINDER_ID = 101
    private const val BROWSER_ID = 102

    fun showReminder(context: Context) {
        if (!NotificationPermission.granted(context)) return
        val manager = context.getSystemService(NotificationManager::class.java)
        createChannel(
            manager,
            REMINDER_CHANNEL,
            context.getString(R.string.notification_channel_reminder),
            NotificationManager.IMPORTANCE_DEFAULT,
        )
        manager.notify(
            REMINDER_ID,
            Notification.Builder(context, REMINDER_CHANNEL)
                .setSmallIcon(R.drawable.ic_soma_notification)
                .setContentTitle(context.getString(R.string.app_name))
                .setContentText(context.getString(R.string.notification_reminder_text))
                .setContentIntent(
                    PendingIntent.getActivity(
                        context,
                        REMINDER_ID,
                        Intent(context, MainActivity::class.java).addFlags(
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP,
                        ),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    ),
                )
                .setAutoCancel(true)
                .build(),
        )
    }

    fun showBrowserRunning(context: Context, url: String) {
        if (!NotificationPermission.granted(context)) return
        val manager = context.getSystemService(NotificationManager::class.java)
        createChannel(
            manager,
            BROWSER_CHANNEL,
            context.getString(R.string.notification_channel_browser),
            NotificationManager.IMPORTANCE_LOW,
        )
        manager.notify(
            BROWSER_ID,
            Notification.Builder(context, BROWSER_CHANNEL)
                .setSmallIcon(R.drawable.ic_soma_notification)
                .setContentTitle(context.getString(R.string.notification_browser_title))
                .setContentText(url)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build(),
        )
    }

    fun cancelBrowser(context: Context) =
        context.getSystemService(NotificationManager::class.java).cancel(BROWSER_ID)

    fun cancelReminder(context: Context) =
        context.getSystemService(NotificationManager::class.java).cancel(REMINDER_ID)

    private fun createChannel(
        manager: NotificationManager,
        id: String,
        name: String,
        importance: Int,
    ) {
        if (manager.getNotificationChannel(id) == null) {
            manager.createNotificationChannel(NotificationChannel(id, name, importance))
        }
    }
}
