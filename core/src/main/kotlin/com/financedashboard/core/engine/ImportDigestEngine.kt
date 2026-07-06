package com.financedashboard.core.engine

/**
 * "What changed since last import" digest. Because a re-import replaces all
 * data, the app otherwise loses the story of what moved. We persist a small
 * scalar [Snapshot] after each import and diff the next one against it, turning
 * every import into a short statement: net worth up/down, debt paid, cash moved,
 * and how many new transactions arrived.
 */
object ImportDigestEngine {

    data class Snapshot(
        val netWorth: Double,
        val totalDebt: Double,
        val liquidCash: Double,
        val currentMonthSpend: Double,
        val transactionCount: Int,
        /** Newest transaction date at snapshot time, epoch-day. */
        val latestTxEpochDay: Long,
        /** When the snapshot was taken, epoch-day. */
        val takenEpochDay: Long,
    )

    data class Digest(
        val hasPrevious: Boolean,
        val netWorthDelta: Double,
        val debtDelta: Double,
        val cashDelta: Double,
        val spendDelta: Double,
        /** New transactions since the last import (count delta, floored at 0). */
        val newTransactions: Int,
        val daysSincePrevious: Long,
        val current: Snapshot,
    ) {
        /** True when at least one tracked figure moved materially. */
        val hasChanges: Boolean
            get() = hasPrevious && (
                kotlin.math.abs(netWorthDelta) > 0.5 ||
                    kotlin.math.abs(debtDelta) > 0.5 ||
                    kotlin.math.abs(cashDelta) > 0.5 ||
                    newTransactions > 0
                )
    }

    fun diff(previous: Snapshot?, current: Snapshot): Digest {
        if (previous == null) {
            return Digest(false, 0.0, 0.0, 0.0, 0.0, 0, 0, current)
        }
        return Digest(
            hasPrevious = true,
            // Debts are stored as positive magnitudes here; a drop is a paydown.
            netWorthDelta = current.netWorth - previous.netWorth,
            debtDelta = current.totalDebt - previous.totalDebt,
            cashDelta = current.liquidCash - previous.liquidCash,
            spendDelta = current.currentMonthSpend - previous.currentMonthSpend,
            newTransactions = (current.transactionCount - previous.transactionCount).coerceAtLeast(0),
            daysSincePrevious = (current.takenEpochDay - previous.takenEpochDay).coerceAtLeast(0),
            current = current,
        )
    }
}
