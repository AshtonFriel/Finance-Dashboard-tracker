package com.financedashboard.core.engine

import com.financedashboard.core.model.AccountType
import com.financedashboard.core.model.BalanceRecord
import com.financedashboard.core.model.TransactionRecord
import java.time.LocalDate
import java.time.YearMonth

/**
 * Decomposes a net-worth change over a period into what drove it:
 * cash saved, investment market gains, debt principal paid, and an explicit
 * unexplained residual. Approximate by construction — the residual is shown
 * rather than hidden so the numbers stay honest.
 *
 *   ΔNetWorth = cashSaved + marketChange + debtPrincipalPaid + residual
 *
 * where marketChange = Δinvestment balances − net contributions into them
 * (contributions are inferred from transfers/buys landing in investment
 * accounts), and debtPrincipalPaid = reduction in debt balances.
 */
object AttributionEngine {

    data class Attribution(
        val startNet: Double,
        val endNet: Double,
        val cashSaved: Double,
        val marketChange: Double,
        val debtPrincipalPaid: Double,
        val assetRevaluation: Double,
        val residual: Double,
    ) {
        val totalChange: Double get() = endNet - startNet
    }

    private val providerHints = listOf(
        "robinhood", "coinbase", "wealthfront", "vanguard", "fidelity", "schwab",
        "betterment", "sofi invest", "401", "brokerage", "crypto", "ira",
    )

    fun attribute(
        balances: List<BalanceRecord>,
        transactions: List<TransactionRecord>,
        typeOf: (String) -> AccountType,
        from: LocalDate,
        to: LocalDate,
    ): Attribution {
        fun snapshot(onOrBefore: LocalDate): Map<String, Double> =
            balances.filter { it.date <= onOrBefore }
                .groupBy { it.account }
                .mapValues { (_, rows) -> rows.maxBy { it.date }.balance }

        val start = snapshot(from)
        val end = snapshot(to)
        val accounts = (start.keys + end.keys)
        val windowTxs = transactions.filter { it.date in from..to }

        // Investment contributions = money that entered investments this period,
        // measured from whichever side the CSV recorded: inflows onto investment
        // accounts, or outflows from other accounts to investment providers.
        val contributions = run {
            val inflowToInvestments = windowTxs
                .filter { typeOf(it.account) == AccountType.INVESTMENT && it.amount > 0 }
                .sumOf { it.amount }
            val outflowToProviders = windowTxs
                .filter { typeOf(it.account) != AccountType.INVESTMENT && it.amount < 0 }
                .filter { tx -> providerHints.any { tx.merchant.lowercase().contains(it) } }
                .sumOf { -it.amount }
            inflowToInvestments + outflowToProviders
        }

        var cashSaved = 0.0
        var marketChange = 0.0
        var debtPrincipalPaid = 0.0
        var assetReval = 0.0
        var investmentDelta = 0.0

        for (acc in accounts) {
            val delta = (end[acc] ?: 0.0) - (start[acc] ?: 0.0)
            when (typeOf(acc)) {
                AccountType.CASH -> cashSaved += delta
                // Debts are stored negative; paying down raises the balance toward 0,
                // so a positive delta is principal paid.
                AccountType.DEBT -> debtPrincipalPaid += delta
                AccountType.INVESTMENT -> investmentDelta += delta
                AccountType.ASSET, AccountType.UNKNOWN -> assetReval += delta
            }
        }
        // Money moved into investments is "saved", not market growth.
        cashSaved += contributions
        marketChange = investmentDelta - contributions

        val startNet = netWorth(start, typeOf)
        val endNet = netWorth(end, typeOf)
        val explained = cashSaved + marketChange + debtPrincipalPaid + assetReval
        val residual = (endNet - startNet) - explained

        return Attribution(
            startNet = startNet,
            endNet = endNet,
            cashSaved = cashSaved,
            marketChange = marketChange,
            debtPrincipalPaid = debtPrincipalPaid,
            assetRevaluation = assetReval,
            residual = residual,
        )
    }

    private fun netWorth(snapshot: Map<String, Double>, typeOf: (String) -> AccountType): Double =
        snapshot.entries.sumOf { (acc, bal) ->
            when (typeOf(acc)) {
                AccountType.DEBT -> bal // negative
                else -> bal
            }
        }
}
