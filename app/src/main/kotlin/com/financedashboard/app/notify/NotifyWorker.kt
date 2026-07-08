package com.financedashboard.app.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.financedashboard.app.MainActivity
import com.financedashboard.app.R
import com.financedashboard.app.data.FinanceRepository
import com.financedashboard.app.data.SettingsStore
import com.financedashboard.app.data.db.AppDatabase
import java.time.LocalDate
import kotlinx.coroutines.flow.first

/**
 * Weekly check that surfaces genuine milestones without nagging: it only
 * notifies on a state change it hasn't announced before (payoff % crossing a
 * quarter, emergency fund funded, a new net-worth high) or clearly stale data.
 */
class NotifyWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val settings = SettingsStore(applicationContext)
        if (!settings.notificationsEnabledNow()) return Result.success()

        val db = AppDatabase.get(applicationContext)
        val repo = FinanceRepository(db)
        ensureChannel()

        // Stale data: newest balance snapshot older than 35 days.
        val latest = repo.accounts.first().mapNotNull { it.latestDate }.maxOrNull()
        if (latest != null && latest.isBefore(LocalDate.now().minusDays(35))) {
            notify(1, "Time to refresh", "Your latest data is from $latest. Import fresh exports to keep projections accurate.")
        }

        // Debt payoff progress crossing a 25% threshold.
        val debts = repo.debtExtraction.first().active
        val assumptions = repo.debtAssumptions.first()
        val maxOwed = repo.maxOwedByAccount.first()
        val included = debts.filter { (assumptions[it.accountName]?.includeInPlan ?: true) }
        val totalNow = included.sumOf { -it.balance }
        val totalOrig = included.sumOf { maxOf(maxOwed[it.accountName] ?: 0.0, -it.balance) }
        if (totalOrig > 0) {
            val pct = ((1.0 - totalNow / totalOrig) * 100).toInt()
            val bucket = (pct / 25) * 25
            if (bucket > settings.getNotifiedPayoffPct() && bucket in 25..100) {
                settings.setNotifiedPayoffPct(bucket)
                val msg = if (bucket >= 100) "You're debt-free. 🎉" else "You've paid off $bucket% of your debt. Keep going."
                notify(2, "Debt milestone", msg)
            }
        }

        // Net-worth new high.
        val series = repo.monthlyNetWorth.first()
        val currentNet = series.lastOrNull()?.net ?: 0.0
        val priorHigh = settings.getNotifiedNetWorthHigh()
        if (currentNet > priorHigh && currentNet > 0 && priorHigh > 0) {
            notify(3, "New net-worth high", "Your net worth just reached a new high.")
        }
        if (currentNet > priorHigh) settings.setNotifiedNetWorthHigh(currentNet)

        // Budget overspend: notify once per category per month it first goes over.
        val budgets = settings.categoryBudgets.first()
        if (budgets.isNotEmpty()) {
            val today = LocalDate.now()
            val spent = repo.currentMonthSpendByCategory.first()
            val summary = com.financedashboard.core.engine.BudgetEngine.evaluate(
                budgets, spent, today.dayOfMonth, today.lengthOfMonth(),
            )
            val over = summary.lines
                .filter { it.status == com.financedashboard.core.engine.BudgetEngine.Status.OVER }
                .map { it.category }
            val monthKey = "${today.year}-${today.monthValue}"
            val storedParts = settings.getNotifiedBudgetsOver().split("|")
            val alreadyNotified = if (storedParts.firstOrNull() == monthKey) storedParts.drop(1).toSet() else emptySet()
            val fresh = over.filter { it !in alreadyNotified }
            if (fresh.isNotEmpty()) {
                val msg = if (over.size == 1) "You're over budget on ${over.first()} this month."
                    else "You're over budget in ${over.size} categories: ${over.joinToString(", ")}."
                notify(4, "Budget alert", msg)
                settings.setNotifiedBudgetsOver((listOf(monthKey) + over).joinToString("|"))
            } else if (storedParts.firstOrNull() != monthKey) {
                // New month with no overspend yet — reset the marker.
                settings.setNotifiedBudgetsOver(monthKey)
            }
        }

        return Result.success()
    }

    private fun ensureChannel() {
        val channel = NotificationChannel(CHANNEL, "Milestones", NotificationManager.IMPORTANCE_DEFAULT)
        ContextCompat.getSystemService(applicationContext, NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    private fun notify(id: Int, title: String, text: String) {
        if (ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) return
        val intent = applicationContext.packageManager.getLaunchIntentForPackage(applicationContext.packageName)
        val pending = android.app.PendingIntent.getActivity(
            applicationContext, id, intent ?: android.content.Intent(applicationContext, MainActivity::class.java),
            android.app.PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(applicationContext).notify(id, notification)
    }

    companion object {
        private const val CHANNEL = "milestones"
    }
}
