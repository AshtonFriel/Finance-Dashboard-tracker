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

    fun setEnabled(context: Context, enabled: Boolean) {
        val wm = WorkManager.getInstance(context)
        if (!enabled) {
            wm.cancelUniqueWork(WORK_NAME)
            return
        }
        val request = PeriodicWorkRequestBuilder<NotifyWorker>(7, TimeUnit.DAYS)
            .setConstraints(Constraints.Builder().build())
            .build()
        wm.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }
}
