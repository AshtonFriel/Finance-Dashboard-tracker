package com.financedashboard.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.financedashboard.app.MainActivity
import com.financedashboard.app.R
import com.financedashboard.app.data.db.AppDatabase
import com.financedashboard.core.engine.NetWorthAggregator
import com.financedashboard.core.model.BalanceRecord
import java.time.LocalDate
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * A home-screen glanceable: current net worth and total debt, read from the
 * local encrypted database. Offline by construction — it renders only stored
 * data and never touches the network. Uses RemoteViews (not Glance) to keep the
 * dependency surface small and avoid Compose-compiler coupling.
 */
class NetWorthWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        for (id in ids) updateOne(context, manager, id)
    }

    private fun updateOne(context: Context, manager: AppWidgetManager, widgetId: Int) {
        val views = RemoteViews(context.packageName, R.layout.widget_net_worth)
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        views.setOnClickPendingIntent(R.id.widget_root, open)

        // Read the DB off the main thread, then push the values.
        CoroutineScope(Dispatchers.IO).launch {
            val netWorth: Double
            val debt: Double
            try {
                val db = AppDatabase.get(context)
                val balances = db.balanceDao().allOnce()
                    .map { BalanceRecord(LocalDate.ofEpochDay(it.epochDay), it.balance, it.accountName) }
                val series = NetWorthAggregator.monthlySeries(balances)
                netWorth = series.lastOrNull()?.net ?: 0.0
                debt = series.lastOrNull()?.debts ?: 0.0
            } catch (_: Throwable) {
                views.setTextViewText(R.id.widget_value, "—")
                manager.updateAppWidget(widgetId, views)
                return@launch
            }
            views.setTextViewText(R.id.widget_value, money(netWorth))
            views.setTextViewText(R.id.widget_sub, "Debt ${money(-debt)}")
            manager.updateAppWidget(widgetId, views)
        }
    }

    private fun money(v: Double): String {
        val a = abs(v)
        val sign = if (v < 0) "-" else ""
        return when {
            a >= 1_000_000 -> "$sign$${"%.2f".format(a / 1_000_000)}M"
            a >= 1_000 -> "$sign$${"%.1f".format(a / 1_000)}K"
            else -> "$sign$${"%.0f".format(a)}"
        }
    }

    companion object {
        /** Ask the launcher to refresh all widgets (call after an import). */
        fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, NetWorthWidget::class.java))
            if (ids.isNotEmpty()) NetWorthWidget().onUpdate(context, manager, ids)
        }
    }
}
