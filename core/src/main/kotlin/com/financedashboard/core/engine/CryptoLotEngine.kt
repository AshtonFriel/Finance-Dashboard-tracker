package com.financedashboard.core.engine

import com.financedashboard.core.model.TransactionRecord
import java.time.LocalDate

/**
 * Estimates crypto cost basis and realized gains from exchange transaction
 * memos (e.g. "buy 0.001965 BTC for $59846.42 each"). Honest limitation: this
 * only works when the memo carries the detail; sells rarely name the lot, so
 * realized gains use FIFO. A user-supplied lots CSV is the accurate override.
 */
object CryptoLotEngine {

    data class Lot(val date: LocalDate, val units: Double, val pricePerUnit: Double) {
        val costBasis: Double get() = units * pricePerUnit
    }

    data class RealizedSale(
        val date: LocalDate,
        val units: Double,
        val proceeds: Double,
        val costBasis: Double,
    ) {
        val gain: Double get() = proceeds - costBasis
    }

    data class Result(
        val lots: List<Lot>,
        val realized: List<RealizedSale>,
        val remainingUnits: Double,
        val remainingCostBasis: Double,
        val totalInvested: Double,
        val realizedGain: Double,
    )

    // "buy 0.001965 BTC for $59846.42 each" / "sell 0.5 ETH for $3,000.00 each"
    private val tradeRegex = Regex(
        """(buy|sell)\s+([\d.]+)\s+([A-Za-z]{2,5})\s+for\s+\$?([\d,]+\.?\d*)\s*(each)?""",
        RegexOption.IGNORE_CASE,
    )

    data class Trade(val date: LocalDate, val side: String, val units: Double, val price: Double, val symbol: String)

    fun parseTrades(transactions: List<TransactionRecord>): List<Trade> =
        transactions.mapNotNull { tx ->
            val text = "${tx.statement} ${tx.notes}"
            val m = tradeRegex.find(text) ?: return@mapNotNull null
            val side = m.groupValues[1].lowercase()
            val units = m.groupValues[2].toDoubleOrNull() ?: return@mapNotNull null
            val symbol = m.groupValues[3].uppercase()
            val price = m.groupValues[4].replace(",", "").toDoubleOrNull() ?: return@mapNotNull null
            // "each" means per-unit price; otherwise treat the number as total.
            val perUnit = if (m.groupValues[5].isNotBlank()) price else if (units > 0) price / units else price
            Trade(tx.date, side, units, perUnit, symbol)
        }.sortedBy { it.date }

    /** FIFO cost-basis accounting over parsed (or supplied) trades. */
    fun account(trades: List<Trade>): Result {
        val openLots = ArrayDeque<Lot>()
        val realized = mutableListOf<RealizedSale>()
        var totalInvested = 0.0

        for (t in trades.sortedBy { it.date }) {
            if (t.side == "buy") {
                openLots.addLast(Lot(t.date, t.units, t.price))
                totalInvested += t.units * t.price
            } else {
                var toSell = t.units
                var basis = 0.0
                while (toSell > 1e-12 && openLots.isNotEmpty()) {
                    val lot = openLots.first()
                    val take = minOf(toSell, lot.units)
                    basis += take * lot.pricePerUnit
                    toSell -= take
                    if (take >= lot.units - 1e-12) openLots.removeFirst()
                    else openLots[0] = lot.copy(units = lot.units - take)
                }
                realized += RealizedSale(t.date, t.units - toSell, t.units * t.price, basis)
            }
        }

        val remainingUnits = openLots.sumOf { it.units }
        val remainingBasis = openLots.sumOf { it.costBasis }
        return Result(
            lots = openLots.toList(),
            realized = realized,
            remainingUnits = remainingUnits,
            remainingCostBasis = remainingBasis,
            totalInvested = totalInvested,
            realizedGain = realized.sumOf { it.gain },
        )
    }

    fun realizedByYear(result: Result): Map<Int, Double> =
        result.realized.groupBy { it.date.year }.mapValues { (_, s) -> s.sumOf { it.gain } }
}
