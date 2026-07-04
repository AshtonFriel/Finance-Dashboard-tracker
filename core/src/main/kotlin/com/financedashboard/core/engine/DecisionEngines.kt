package com.financedashboard.core.engine

import com.financedashboard.core.model.Debt
import java.time.YearMonth
import kotlin.math.pow

/**
 * Paycheck wedge: how much of gross income never reaches take-home, split into
 * retirement (if known) and "tax & other". This is a take-home wedge, not a
 * pure tax rate — net deposits are also reduced by benefits and 401(k).
 */
object PaycheckWedgeEngine {

    data class YearWedge(
        val year: Int,
        val gross: Double,
        val net: Double,
        val retirement: Double,
        /** gross - net - retirement, floored at zero. */
        val taxAndOther: Double,
        val wedgePct: Double,
    )

    fun compute(
        grossByYear: Map<Int, Double>,
        netByYear: Map<Int, Double>,
        retirementByYear: Map<Int, Double> = emptyMap(),
    ): List<YearWedge> = grossByYear.keys.intersect(netByYear.keys).sorted().mapNotNull { y ->
        val gross = grossByYear.getValue(y)
        val net = netByYear.getValue(y)
        if (gross <= 0) return@mapNotNull null
        val retirement = (retirementByYear[y] ?: 0.0).coerceIn(0.0, gross)
        YearWedge(
            year = y,
            gross = gross,
            net = net,
            retirement = retirement,
            taxAndOther = (gross - net - retirement).coerceAtLeast(0.0),
            wedgePct = ((gross - net) / gross * 100).coerceIn(0.0, 100.0),
        )
    }
}

/**
 * Financial-independence targets. FIRE number = annual spending x (100 /
 * withdrawalRate). Coast-FIRE = the portfolio that, with no further
 * contributions, grows to the FIRE number by the retirement age.
 */
object FireEngine {

    data class Result(
        val annualSpending: Double,
        val fireNumber: Double,
        val currentPortfolio: Double,
        val progressPct: Double,
        /** Portfolio needed today to coast (no more contributions) to the FIRE number. */
        val coastNumber: Double,
        val hasCoasted: Boolean,
        /** Years until the current portfolio + contributions reaches the FIRE number, or null if never within horizon. */
        val yearsToFire: Int?,
    )

    fun compute(
        annualSpending: Double,
        currentPortfolio: Double,
        monthlyContribution: Double,
        realReturnPct: Double,
        yearsToRetirement: Int,
        withdrawalRatePct: Double = 4.0,
    ): Result {
        val fireNumber = if (withdrawalRatePct > 0) annualSpending * (100.0 / withdrawalRatePct) else 0.0
        val r = realReturnPct / 100.0
        // Coast: PV needed now to reach fireNumber in yearsToRetirement with no contributions.
        val coastNumber = if (r > -1.0) fireNumber / (1.0 + r).pow(yearsToRetirement) else fireNumber

        // Years for currentPortfolio + monthly contributions to reach fireNumber (real terms).
        val proj = InvestmentEngine.project(
            principal = currentPortfolio,
            monthlyContribution = monthlyContribution,
            annualReturnPct = realReturnPct,
            years = 60,
            inflationPct = 0.0,
        )
        val yearsToFire = proj.years.firstOrNull { it.endBalanceNominal >= fireNumber }?.yearIndex

        return Result(
            annualSpending = annualSpending,
            fireNumber = fireNumber,
            currentPortfolio = currentPortfolio,
            progressPct = if (fireNumber > 0) (currentPortfolio / fireNumber * 100).coerceIn(0.0, 100.0) else 0.0,
            coastNumber = coastNumber,
            hasCoasted = currentPortfolio >= coastNumber,
            yearsToFire = yearsToFire,
        )
    }
}

/** Compares a debt's current terms against a refinance offer, including fees. */
object RefinanceEngine {

    data class Comparison(
        val currentPayoff: YearMonth?,
        val currentTotalInterest: Double,
        val newPayoff: YearMonth?,
        val newTotalInterest: Double,
        val monthsSaved: Int,
        val interestSaved: Double,
        /** Net saving after closing costs. */
        val netSaving: Double,
        /** Month at which cumulative saving overtakes the fees, or null if it never does. */
        val breakEvenMonth: YearMonth?,
    )

    fun compare(
        balance: Double,
        currentAprPct: Double,
        newAprPct: Double,
        monthlyPayment: Double,
        fees: Double,
        startMonth: YearMonth,
        newTermMonths: Int? = null,
    ): Comparison {
        val current = AmortizationEngine.computePlan(
            listOf(Debt("current", balance, currentAprPct, monthlyPayment)),
            PayoffStrategy.AVALANCHE, 0.0, startMonth,
        )
        // If a term is given, derive the payment from it; otherwise keep the same payment.
        val newPayment = newTermMonths?.let { paymentFor(balance, newAprPct, it) } ?: monthlyPayment
        val refinanced = AmortizationEngine.computePlan(
            listOf(Debt("new", balance, newAprPct, newPayment)),
            PayoffStrategy.AVALANCHE, 0.0, startMonth,
        )
        val interestSaved = current.totalInterest - refinanced.totalInterest
        val curMonths = current.combinedBalanceByMonth.size - 1
        val newMonths = refinanced.combinedBalanceByMonth.size - 1

        // Break-even: first month where (current cumulative interest - new cumulative interest) exceeds fees.
        val breakEven = breakEvenMonth(current, refinanced, fees, startMonth)

        return Comparison(
            currentPayoff = current.payoffMonth,
            currentTotalInterest = current.totalInterest,
            newPayoff = refinanced.payoffMonth,
            newTotalInterest = refinanced.totalInterest,
            monthsSaved = curMonths - newMonths,
            interestSaved = interestSaved,
            netSaving = interestSaved - fees,
            breakEvenMonth = breakEven,
        )
    }

    private fun paymentFor(balance: Double, aprPct: Double, months: Int): Double {
        val r = aprPct / 100.0 / 12.0
        return if (r <= 0) balance / months
        else balance * r / (1 - (1 + r).pow(-months))
    }

    private fun breakEvenMonth(
        current: AmortizationEngine.PlanResult,
        refinanced: AmortizationEngine.PlanResult,
        fees: Double,
        startMonth: YearMonth,
    ): YearMonth? {
        val curInterest = cumulativeInterest(current)
        val newInterest = cumulativeInterest(refinanced)
        val n = maxOf(curInterest.size, newInterest.size)
        for (i in 0 until n) {
            val saved = (curInterest.getOrElse(i) { curInterest.lastOrNull() ?: 0.0 }) -
                (newInterest.getOrElse(i) { newInterest.lastOrNull() ?: 0.0 })
            if (saved >= fees) return startMonth.plusMonths((i + 1).toLong())
        }
        return null
    }

    private fun cumulativeInterest(plan: AmortizationEngine.PlanResult): List<Double> {
        val perMonth = plan.debts.flatMap { it.schedule }
            .groupBy { it.paymentNumber }
            .toSortedMap()
            .map { (_, rows) -> rows.sumOf { it.interest } }
        var running = 0.0
        return perMonth.map { running += it; running }
    }
}

/**
 * The marginal-dollar decision: pay down the highest-rate debt (a guaranteed
 * return equal to its APR) versus invest at an expected return. Returns the
 * crossover return at which investing wins, plus both dollar outcomes.
 */
object ExtraDollarOptimizer {

    data class Result(
        val monthlyAmount: Double,
        val targetDebtName: String?,
        val targetDebtApr: Double,
        /** Interest avoided over the debt's life by throwing the amount at it. */
        val interestSaved: Double,
        /** Projected growth if the same amount is invested for [years]. */
        val investedGrowth: Double,
        val expectedReturnPct: Double,
        /** Investing beats debt paydown above this return (= the debt APR, after tax if given). */
        val crossoverReturnPct: Double,
        val recommendation: String,
    )

    fun compare(
        debts: List<Debt>,
        monthlyAmount: Double,
        expectedReturnPct: Double,
        years: Int,
        startMonth: YearMonth,
        marginalTaxRatePct: Double = 0.0,
    ): Result? {
        val target = debts.filter { it.balance > 0.005 }.maxByOrNull { it.annualRatePct } ?: return null

        val base = AmortizationEngine.computePlan(debts, PayoffStrategy.AVALANCHE, 0.0, startMonth)
        val withExtra = AmortizationEngine.computePlan(debts, PayoffStrategy.AVALANCHE, monthlyAmount, startMonth)
        val interestSaved = base.totalInterest - withExtra.totalInterest

        val investFuture = InvestmentEngine
            .project(0.0, monthlyAmount, expectedReturnPct, years, 0.0)
            .years.last().endBalanceNominal
        val investGrowth = investFuture - monthlyAmount * 12 * years

        // Debt paydown is a guaranteed pre-tax return of the APR; investment
        // return may be taxed. Crossover: invest wins when after-tax expected
        // return exceeds the debt APR.
        val crossover = target.annualRatePct / (1 - marginalTaxRatePct / 100.0).coerceAtLeast(0.01)
        val investWins = expectedReturnPct > crossover

        return Result(
            monthlyAmount = monthlyAmount,
            targetDebtName = target.name,
            targetDebtApr = target.annualRatePct,
            interestSaved = interestSaved,
            investedGrowth = investGrowth,
            expectedReturnPct = expectedReturnPct,
            crossoverReturnPct = crossover,
            recommendation = if (investWins) {
                "Investing edges ahead: your ${fmt(expectedReturnPct)}% expected return beats the " +
                    "${fmt(target.annualRatePct)}% guaranteed by paying ${target.name} — but that return isn't guaranteed."
            } else {
                "Paying down ${target.name} wins: its ${fmt(target.annualRatePct)}% is a guaranteed, risk-free return " +
                    "your ${fmt(expectedReturnPct)}% expected market return doesn't clear."
            },
        )
    }

    private fun fmt(v: Double) = "%.1f".format(v)
}
