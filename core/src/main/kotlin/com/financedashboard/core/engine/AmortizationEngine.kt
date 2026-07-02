package com.financedashboard.core.engine

import com.financedashboard.core.model.Debt
import java.time.YearMonth

enum class PayoffStrategy { AVALANCHE, SNOWBALL, PRO_RATA, CUSTOM }

/**
 * Standard monthly amortization with extra-payment support and freed-payment
 * rollover. interest = balance x APR/12. When a debt is retired its minimum
 * payment rolls into the strategy's target debt (avalanche: highest APR first;
 * snowball: lowest balance first; pro-rata: extra split by balance share).
 */
object AmortizationEngine {

    data class ScheduleRow(
        val paymentNumber: Int,
        val month: YearMonth,
        val debtName: String,
        val payment: Double,
        val principal: Double,
        val interest: Double,
        val remainingBalance: Double,
    )

    data class DebtResult(
        val debt: Debt,
        val payoffMonth: YearMonth?,
        val totalPaid: Double,
        val totalInterest: Double,
        val schedule: List<ScheduleRow>,
    )

    data class PlanResult(
        val strategy: PayoffStrategy,
        val extraMonthly: Double,
        val debts: List<DebtResult>,
        val payoffMonth: YearMonth?,
        val totalPaid: Double,
        val totalInterest: Double,
        /** Combined remaining balance at each month from start until payoff. */
        val combinedBalanceByMonth: List<Pair<YearMonth, Double>>,
    )

    private const val MAX_MONTHS = 12 * 60
    private const val EPS = 0.005

    private class State(val debt: Debt) {
        var balance: Double = debt.balance
        var monthInterest: Double = 0.0
        var monthPayment: Double = 0.0
        var payoff: YearMonth? = null
        var totalPaid: Double = 0.0
        var totalInterest: Double = 0.0
        val schedule = mutableListOf<ScheduleRow>()

        fun pay(amount: Double) {
            val actual = amount.coerceIn(0.0, balance)
            balance -= actual
            monthPayment += actual
            totalPaid += actual
        }
    }

    /**
     * @param extraGrowthPctPerYear the extra payment grows by this each year
     *   (e.g. raises): extra(month m) = extraMonthly x (1+g)^floor((m-1)/12)
     * @param lumpSums one-time payments pinned to specific months, added to
     *   that month's strategy budget
     * @param customOrder explicit payoff priority for [PayoffStrategy.CUSTOM];
     *   debts not listed fall back to avalanche order after listed ones
     */
    fun computePlan(
        debts: List<Debt>,
        strategy: PayoffStrategy,
        extraMonthly: Double,
        startMonth: YearMonth,
        extraGrowthPctPerYear: Double = 0.0,
        lumpSums: Map<YearMonth, Double> = emptyMap(),
        customOrder: List<String> = emptyList(),
    ): PlanResult = run(debts, strategy, startMonth, customOrder) { n ->
        grownExtra(extraMonthly, extraGrowthPctPerYear, n) + (lumpSums[startMonth.plusMonths(n.toLong())] ?: 0.0)
    }

    private fun grownExtra(extra: Double, growthPct: Double, paymentNumber: Int): Double =
        extra * Math.pow(1.0 + growthPct / 100.0, ((paymentNumber - 1) / 12).toDouble())

    data class EmergencyFundPlan(
        val plan: PlanResult,
        /** Emergency fund balance at each month, aligned with the plan's start. */
        val efSeries: List<Pair<YearMonth, Double>>,
        /** Month the fund reaches its target; null if it never does within the plan. */
        val efFundedMonth: YearMonth?,
        val efTargetAmount: Double,
    )

    /**
     * Sequenced plan: minimum payments always continue, but the extra budget
     * fills the emergency fund to [efTargetAmount] before any of it goes to
     * debt. The month the fund crosses its target, the remainder (and every
     * later month's extra) flows to the payoff strategy.
     */
    fun computePlanWithEmergencyFund(
        debts: List<Debt>,
        strategy: PayoffStrategy,
        extraMonthly: Double,
        startMonth: YearMonth,
        efStartBalance: Double,
        efTargetAmount: Double,
        extraGrowthPctPerYear: Double = 0.0,
        lumpSums: Map<YearMonth, Double> = emptyMap(),
        customOrder: List<String> = emptyList(),
    ): EmergencyFundPlan {
        // Precompute the fund's fill schedule and the extra left for debt each month.
        var ef = efStartBalance.coerceAtLeast(0.0)
        val efByMonth = mutableListOf(startMonth to ef)
        val extraForDebt = DoubleArray(MAX_MONTHS)
        var fundedMonth: YearMonth? = if (ef >= efTargetAmount - EPS) startMonth else null
        for (m in 1..MAX_MONTHS) {
            val month = startMonth.plusMonths(m.toLong())
            val available = grownExtra(extraMonthly, extraGrowthPctPerYear, m) + (lumpSums[month] ?: 0.0)
            val toFund = (efTargetAmount - ef).coerceIn(0.0, available)
            ef += toFund
            extraForDebt[m - 1] = available - toFund
            efByMonth.add(month to ef)
            if (fundedMonth == null && ef >= efTargetAmount - EPS) fundedMonth = month
        }
        val plan = run(debts, strategy, startMonth, customOrder) { paymentNumber -> extraForDebt[paymentNumber - 1] }
        return EmergencyFundPlan(
            plan = plan,
            efSeries = efByMonth.take(plan.combinedBalanceByMonth.size),
            efFundedMonth = fundedMonth,
            efTargetAmount = efTargetAmount,
        )
    }

    private fun run(
        debts: List<Debt>,
        strategy: PayoffStrategy,
        startMonth: YearMonth,
        customOrder: List<String> = emptyList(),
        extraAt: (paymentNumber: Int) -> Double,
    ): PlanResult {
        // CUSTOM: explicit rank; unlisted debts follow listed ones in APR order.
        val customRank = customOrder.withIndex().associate { (i, name) -> name to i }
        val states = debts.filter { it.balance > EPS }.map { State(it) }
        val combined = mutableListOf(startMonth to states.sumOf { it.balance })

        var month = startMonth
        var paymentNumber = 0
        while (states.any { it.balance > EPS } && paymentNumber < MAX_MONTHS) {
            month = month.plusMonths(1)
            paymentNumber++
            val open = states.filter { it.balance > EPS }

            // Budget = every debt's minimum (retired debts' minimums roll over) + extra.
            var budget = states.sumOf { it.debt.minPayment } + extraAt(paymentNumber)

            // Accrue interest, then minimum payments.
            for (s in open) {
                val interest = s.balance * (s.debt.annualRatePct / 100.0) / 12.0
                s.balance += interest
                s.totalInterest += interest
                s.monthInterest = interest
                s.monthPayment = 0.0
                val minPay = minOf(s.debt.minPayment, s.balance)
                s.pay(minPay)
                budget -= minPay
            }

            // Direct the surplus.
            if (budget > EPS) {
                when (strategy) {
                    PayoffStrategy.PRO_RATA -> {
                        val stillOpen = states.filter { it.balance > EPS }
                        val totalBal = stillOpen.sumOf { it.balance }
                        if (totalBal > 0) {
                            for (s in stillOpen) s.pay(budget * (s.balance / totalBal))
                        }
                    }
                    else -> {
                        var remaining = budget
                        while (remaining > EPS) {
                            val target = states.filter { it.balance > EPS }.let { cs ->
                                when (strategy) {
                                    PayoffStrategy.AVALANCHE -> cs.maxByOrNull { it.debt.annualRatePct }
                                    PayoffStrategy.SNOWBALL -> cs.minByOrNull { it.balance }
                                    else -> cs.minByOrNull {
                                        (customRank[it.debt.name] ?: Int.MAX_VALUE).toDouble() * 1e6 - it.debt.annualRatePct
                                    }
                                }
                            } ?: break
                            val pay = minOf(remaining, target.balance)
                            target.pay(pay)
                            remaining -= pay
                        }
                    }
                }
            }

            for (s in open) {
                s.schedule.add(
                    ScheduleRow(
                        paymentNumber = paymentNumber,
                        month = month,
                        debtName = s.debt.name,
                        payment = s.monthPayment,
                        principal = s.monthPayment - s.monthInterest,
                        interest = s.monthInterest,
                        remainingBalance = s.balance.coerceAtLeast(0.0),
                    )
                )
                if (s.balance <= EPS && s.payoff == null) s.payoff = month
            }
            combined.add(month to states.sumOf { it.balance.coerceAtLeast(0.0) })
        }

        val results = states.map { s ->
            DebtResult(s.debt, s.payoff, s.totalPaid, s.totalInterest, s.schedule.toList())
        }
        return PlanResult(
            strategy = strategy,
            extraMonthly = extraAt(1),
            debts = results,
            payoffMonth = results.mapNotNull { it.payoffMonth }.maxOrNull(),
            totalPaid = results.sumOf { it.totalPaid },
            totalInterest = results.sumOf { it.totalInterest },
            combinedBalanceByMonth = combined,
        )
    }

    data class StrategyComparison(
        val strategy: PayoffStrategy,
        val extraMonthly: Double,
        val payoffMonth: YearMonth?,
        val monthsToPayoff: Int,
        val totalInterest: Double,
        /** Interest saved and months saved vs. the baseline (no-extra, pro-rata) plan. */
        val interestSavedVsBaseline: Double,
        val monthsSavedVsBaseline: Int,
    )

    fun compareStrategies(
        debts: List<Debt>,
        extraMonthly: Double,
        startMonth: YearMonth,
        extraGrowthPctPerYear: Double = 0.0,
        lumpSums: Map<YearMonth, Double> = emptyMap(),
        customOrder: List<String> = emptyList(),
    ): List<StrategyComparison> {
        val baseline = computePlan(debts, PayoffStrategy.PRO_RATA, 0.0, startMonth)
        val baselineMonths = baseline.combinedBalanceByMonth.size - 1
        return PayoffStrategy.entries.map { strat ->
            val plan = computePlan(debts, strat, extraMonthly, startMonth, extraGrowthPctPerYear, lumpSums, customOrder)
            val months = plan.combinedBalanceByMonth.size - 1
            StrategyComparison(
                strategy = strat,
                extraMonthly = extraMonthly,
                payoffMonth = plan.payoffMonth,
                monthsToPayoff = months,
                totalInterest = plan.totalInterest,
                interestSavedVsBaseline = baseline.totalInterest - plan.totalInterest,
                monthsSavedVsBaseline = baselineMonths - months,
            )
        }
    }
}
