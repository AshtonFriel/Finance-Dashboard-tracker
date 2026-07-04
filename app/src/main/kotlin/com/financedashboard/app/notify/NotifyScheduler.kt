package com.financedashboard.app.notify

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/** Schedules (or cancels) the weekly milestone-check worker. */
object NotifyScheduler {

    private const val WORK_NAME = "milestone-checks"

    /** Returns true on success; never throws so a scheduling problem can't crash the app. */
    fun setEnabled(context: Context, enabled: Boolean): Boolean = try {
        val wm = WorkManager.getInstance(context)
        if (enabled) {
            val request = PeriodicWorkRequestBuilder<NotifyWorker>(7, TimeUnit.DAYS)
                .setConstraints(Constraints.Builder().build())
                .build()
            wm.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        } else {
            wm.cancelUniqueWork(WORK_NAME)
        }
        true
    } catch (e: Exception) {
        android.util.Log.e("NotifyScheduler", "Failed to ${if (enabled) "schedule" else "cancel"} notifications", e)
        false
    }
}
