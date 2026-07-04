package com.financedashboard.app

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.financedashboard.app.data.CsvImporter
import com.financedashboard.app.data.FinanceRepository
import com.financedashboard.app.data.db.AppDatabase
import com.financedashboard.app.data.db.DebtAssumptionEntity
import com.financedashboard.core.engine.AmortizationEngine
import com.financedashboard.core.engine.IncomeAggregator
import com.financedashboard.core.engine.InflationEngine
import com.financedashboard.core.engine.InvestmentEngine
import com.financedashboard.core.engine.PayoffStrategy
import com.financedashboard.core.model.AccountType
import com.financedashboard.core.model.Debt
import java.time.YearMonth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Default assumptions offered per debt type; always user-editable. */
object DefaultRates {
    fun aprFor(accountName: String): Double {
        val n = accountName.lowercase()
        return when {
            n.contains("student") || n.contains("aidvantage") -> 5.0
            n.contains("auto") || n.contains("car") -> 7.0
            n.contains("card") || n.contains("visa") || n.contains("amex") -> 24.0
            else -> 11.0
        }
    }

    fun minPaymentFor(balance: Double): Double = (balance * 0.02).coerceAtLeast(25.0)
}

data class DebtInput(
    val accountName: String,
    val balance: Double,
    val aprPct: Double,
    val minPayment: Double,
    val includeInPlan: Boolean,
    /** Non-null when the extractor flagged this account instead of auto-counting it. */
    val reviewNote: String? = null,
    val latestDate: java.time.LocalDate? = null,
    val last4: String? = null,
    /** Highest amount ever owed on this account (>= balance). */
    val originalBalance: Double = 0.0,
) {
    val paidProgress: Double
        get() = if (originalBalance > 0.005) (1.0 - balance / originalBalance).coerceIn(0.0, 1.0) else 0.0
}

@OptIn(ExperimentalCoroutinesApi::class)
class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val db = AppDatabase.get(app)
    val repo = FinanceRepository(db)
    private val importer = CsvImporter(app, db)
    private val settings = com.financedashboard.app.data.SettingsStore(app)

    private fun <T> kotlinx.coroutines.flow.Flow<T>.asState(initial: T) =
        stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), initial)

    /** Surfaces the last background error to the UI instead of it being silent. */
    val lastError = MutableStateFlow<String?>(null)
    fun clearError() { lastError.value = null }

    /**
     * All view-model background work runs through here. An uncaught exception in
     * a coroutine launched from viewModelScope would otherwise crash the whole
     * app; instead we catch it, log it, and surface a message.
     */
    private fun safeLaunch(
        context: kotlin.coroutines.CoroutineContext = kotlin.coroutines.EmptyCoroutineContext,
        block: suspend kotlinx.coroutines.CoroutineScope.() -> Unit,
    ) = viewModelScope.launch(context) {
        try {
            block()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            android.util.Log.e("AppViewModel", "Background task failed", e)
            lastError.value = e.message ?: e.javaClass.simpleName
        }
    }

    // ---- Accounts & net worth ----
    val accounts = repo.accounts.asState(emptyList())
    val netWorth = repo.monthlyNetWorth.asState(emptyList())
    val transactionCount = repo.transactionCount.asState(0)

    // ---- Inflation ----
    val incomeByYear = repo.incomeByYear.asState(emptyMap())
    val derivedNetIncome = repo.derivedNetIncomeByYear.asState(emptyMap())
    val manualIncome = repo.manualIncome.asState(emptyList())
    val cpiTable = repo.cpiTable.asState(InflationEngine.DEFAULT_CPI)

    /** GROSS = manual entries only; NET = paycheck-derived only; MERGED = manual overrides derived. */
    enum class IncomeSource { MERGED, NET }
    val incomeSource = MutableStateFlow(IncomeSource.MERGED)
    val baseYearOverride = MutableStateFlow<Int?>(null)

    val inflationImpact = combine(
        incomeByYear, derivedNetIncome, cpiTable, incomeSource, baseYearOverride,
    ) { merged, net, cpi, source, baseOverride ->
        val income = if (source == IncomeSource.NET) net else merged
        // Only full years both present in CPI and income; drop the current partial year.
        val currentYear = java.time.LocalDate.now().year
        val usable = income.filterKeys { it < currentYear && cpi.containsKey(it) }
        val base = baseOverride?.takeIf { usable.containsKey(it) } ?: usable.keys.minOrNull()
        base?.let { InflationEngine.computeImpact(usable, it, cpi) }
    }.flowOn(Dispatchers.Default).asState(null)

    val purchasingPower = combine(inflationImpact, cpiTable) { impact, cpi ->
        impact?.let {
            InflationEngine.purchasingPowerSeries(it.baseYear, it.years.last().year, cpi)
        } ?: emptyList()
    }.asState(emptyList())

    // ---- Debts ----
    val debtExtraction = repo.debtExtraction.asState(
        com.financedashboard.core.classify.DebtExtractor.Extraction(
            java.time.LocalDate.MIN, emptyList(), emptyList(), emptyList(),
        )
    )

    /**
     * Active debts are auto-included; extractor suspects (duplicates, card
     * statement balances) default to excluded until the user opts them in.
     */
    val debtInputs = combine(debtExtraction, repo.debtAssumptions, repo.maxOwedByAccount) { extraction, assumptions, maxOwed ->
        fun input(c: com.financedashboard.core.classify.DebtExtractor.Candidate, note: String?, defaultInclude: Boolean): DebtInput {
            val a = assumptions[c.accountName]
            return DebtInput(
                accountName = c.accountName,
                balance = -c.balance,
                aprPct = a?.aprPct ?: DefaultRates.aprFor(c.accountName),
                minPayment = a?.minPayment ?: DefaultRates.minPaymentFor(-c.balance),
                includeInPlan = a?.includeInPlan ?: defaultInclude,
                reviewNote = note,
                latestDate = c.latestDate,
                last4 = c.last4,
                originalBalance = maxOf(maxOwed[c.accountName] ?: 0.0, -c.balance),
            )
        }
        val active = extraction.active.map { input(it, null, defaultInclude = true) }
        val review = extraction.needsReview.map { r ->
            when (r.reason) {
                com.financedashboard.core.classify.DebtExtractor.ReviewReason.SUSPECTED_DUPLICATE ->
                    // Never double-count: duplicates stay out unless the user opts in.
                    input(r.candidate, "Suspected duplicate of ${r.duplicateOf}", defaultInclude = false)
                com.financedashboard.core.classify.DebtExtractor.ReviewReason.CARD_STATEMENT_BALANCE ->
                    // Real balances, so counted by default — one tap to exclude if paid in full monthly.
                    input(r.candidate, "Card statement balance — exclude if paid in full monthly", defaultInclude = true)
            }
        }
        (active + review).sortedWith(compareBy({ it.reviewNote != null }, { -it.balance }))
    }.asState(emptyList())

    val extraMonthly = settings.extraMonthly.asState(0.0)
    val strategy = settings.strategy.map { s ->
        runCatching { PayoffStrategy.valueOf(s) }.getOrDefault(PayoffStrategy.AVALANCHE)
    }.asState(PayoffStrategy.AVALANCHE)

    fun setExtraMonthly(v: Double) = safeLaunch { settings.setExtraMonthly(v) }
    fun setStrategy(v: PayoffStrategy) = safeLaunch { settings.setStrategy(v.name) }

    val extraGrowthPct = settings.extraGrowthPct.asState(0.0)
    val lumpSums = settings.lumpSums.asState(emptyMap())
    val customOrder = settings.customOrder.asState(emptyList())

    fun setExtraGrowthPct(v: Double) = safeLaunch { settings.setExtraGrowthPct(v) }
    fun addLumpSum(month: java.time.YearMonth, amount: Double) = safeLaunch {
        settings.setLumpSums(lumpSums.value + (month to amount))
    }
    fun removeLumpSum(month: java.time.YearMonth) = safeLaunch {
        settings.setLumpSums(lumpSums.value - month)
    }
    fun moveInCustomOrder(name: String, up: Boolean) = safeLaunch {
        val current = customOrder.value.ifEmpty {
            debtInputs.value.filter { it.includeInPlan }.sortedByDescending { it.aprPct }.map { it.accountName }
        }.toMutableList()
        val i = current.indexOf(name)
        val j = if (up) i - 1 else i + 1
        if (i >= 0 && j in current.indices) {
            current[i] = current[j].also { current[j] = current[i] }
            settings.setCustomOrder(current)
        } else if (i < 0) {
            settings.setCustomOrder(current + name)
        }
    }

    /** Session-only what-if: pretend these debts were paid off today; their minimums roll into the extra budget. */
    val simulatePaidOff = MutableStateFlow<Set<String>>(emptySet())
    fun toggleSimulatePaidOff(name: String) {
        simulatePaidOff.value =
            if (name in simulatePaidOff.value) simulatePaidOff.value - name else simulatePaidOff.value + name
    }

    private fun List<DebtInput>.toDebts() = filter { it.includeInPlan }
        .map { Debt(it.accountName, it.balance, it.aprPct, it.minPayment) }

    // ---- Emergency fund ----
    val liquidCash = repo.liquidCash.asState(0.0)
    // Median of the trailing 12 full months — robust to one-off big months
    // (car repair, medical, taxes) that would otherwise skew a mean.
    val avgMonthlyExpenses = repo.monthlyExpenses(12).map { months ->
        com.financedashboard.core.engine.EmergencyFundEngine.typicalMonthlyExpenses(months.map { it.second })
    }.asState(0.0)
    val avgMonthlyIncome = repo.avgMonthlyPaychecks.asState(0.0)
    val efTargetMonths = settings.efTargetMonths.asState(6)
    val efMonthlySaving = settings.efMonthlySaving.asState(500.0)
    val efFirstInPayoff = settings.efFirstInPayoff.asState(false)

    val emergencyFund = combine(
        liquidCash, avgMonthlyExpenses, efTargetMonths, efMonthlySaving,
    ) { cash, expenses, months, saving ->
        if (expenses <= 0.005 && cash <= 0.005) null
        else com.financedashboard.core.engine.EmergencyFundEngine.compute(cash, expenses, months, saving)
    }.flowOn(Dispatchers.Default).asState(null)

    fun setEfTargetMonths(v: Int) = safeLaunch { settings.setEfTargetMonths(v) }
    fun setEfMonthlySaving(v: Double) = safeLaunch { settings.setEfMonthlySaving(v) }
    fun setEfFirstInPayoff(v: Boolean) = safeLaunch { settings.setEfFirstInPayoff(v) }

    private data class EfConfig(val enabled: Boolean, val startBalance: Double, val targetAmount: Double)

    private val efConfig = combine(
        efFirstInPayoff, liquidCash, avgMonthlyExpenses, efTargetMonths,
    ) { enabled, cash, expenses, months ->
        EfConfig(enabled && expenses > 0.005, cash, expenses * months)
    }

    /** Plan plus, when EF-first is on, the fund schedule that precedes debt attack. */
    data class ActiveDebtPlan(
        val plan: AmortizationEngine.PlanResult,
        val ef: AmortizationEngine.EmergencyFundPlan?,
    )

    private data class DebtPlanParams(
        val extra: Double,
        val strategy: PayoffStrategy,
        val growthPct: Double,
        val lumpSums: Map<YearMonth, Double>,
        val customOrder: List<String>,
    )

    private val debtPlanParams = combine(extraMonthly, strategy, extraGrowthPct, lumpSums, customOrder) {
        extra, strat, growth, lumps, order ->
        DebtPlanParams(extra, strat, growth, lumps, order)
    }

    /** Debts + effective extra after the paid-off-today simulation rolls freed minimums into the budget. */
    private fun simulatedInputs(
        inputs: List<DebtInput>,
        simulated: Set<String>,
        extra: Double,
    ): Pair<List<Debt>, Double> {
        val kept = inputs.filter { it.includeInPlan && it.accountName !in simulated }
            .map { Debt(it.accountName, it.balance, it.aprPct, it.minPayment) }
        val freed = inputs.filter { it.includeInPlan && it.accountName in simulated }.sumOf { it.minPayment }
        return kept to (extra + freed)
    }

    val debtPlan = combine(debtInputs, debtPlanParams, efConfig, simulatePaidOff) { inputs, p, ef, simulated ->
        val (debts, extra) = simulatedInputs(inputs, simulated, p.extra)
        when {
            debts.isEmpty() -> null
            ef.enabled -> {
                val efPlan = AmortizationEngine.computePlanWithEmergencyFund(
                    debts, p.strategy, extra, YearMonth.now(), ef.startBalance, ef.targetAmount,
                    p.growthPct, p.lumpSums, p.customOrder,
                )
                ActiveDebtPlan(efPlan.plan, efPlan)
            }
            else -> ActiveDebtPlan(
                AmortizationEngine.computePlan(
                    debts, p.strategy, extra, YearMonth.now(), p.growthPct, p.lumpSums, p.customOrder,
                ),
                null,
            )
        }
    }.flowOn(Dispatchers.Default).asState(null)

    /** Same debts with no extra/lumps/growth, for the comparison overlay + delta banner. */
    val debtPlanBaseline = combine(debtInputs, strategy, customOrder, simulatePaidOff) { inputs, strat, order, simulated ->
        val (debts, _) = simulatedInputs(inputs, simulated, 0.0)
        if (debts.isEmpty()) null
        else AmortizationEngine.computePlan(debts, strat, 0.0, YearMonth.now(), customOrder = order)
    }.flowOn(Dispatchers.Default).asState(null)

    val strategyComparison = combine(debtInputs, debtPlanParams, simulatePaidOff) { inputs, p, simulated ->
        val (debts, extra) = simulatedInputs(inputs, simulated, p.extra)
        if (debts.isEmpty()) emptyList()
        else AmortizationEngine.compareStrategies(debts, extra, YearMonth.now(), p.growthPct, p.lumpSums, p.customOrder)
    }.flowOn(Dispatchers.Default).asState(emptyList())

    /** Included debts that still run on guessed rates — drives the confirm banner. */
    val unconfirmedRates = combine(debtExtraction, repo.debtAssumptions) { extraction, assumptions ->
        extraction.active.map { it.accountName }.filter { it !in assumptions }
    }.asState(emptyList())

    /** Fresh card accounts the user hasn't classified as revolving-or-not yet. */
    val cardQuestions = combine(debtExtraction, repo.debtAssumptions) { extraction, assumptions ->
        extraction.needsReview
            .filter { it.reason == com.financedashboard.core.classify.DebtExtractor.ReviewReason.CARD_STATEMENT_BALANCE }
            .filter { it.candidate.accountName !in assumptions }
            .map { it.candidate.accountName to -it.candidate.balance }
    }.asState(emptyList())

    fun answerCardQuestion(name: String, revolves: Boolean) = safeLaunch {
        repo.setDebtAssumption(
            DebtAssumptionEntity(
                accountName = name,
                aprPct = DefaultRates.aprFor(name),
                minPayment = DefaultRates.minPaymentFor(
                    debtInputs.value.firstOrNull { it.accountName == name }?.balance ?: 0.0,
                ),
                includeInPlan = revolves,
            )
        )
    }

    // ---- Investments ----
    val investmentAccounts = accounts.map { accs ->
        accs.filter { it.type == AccountType.INVESTMENT && it.latestBalance > 0.005 }
    }.asState(emptyList())

    val horizonYears = settings.horizonYears.asState(20)
    val monthlyContribution = settings.monthlyContribution.asState(500.0)
    val expectedReturnPct = settings.expectedReturnPct.asState(7.0)
    val assumedInflationPct = settings.assumedInflationPct.asState(2.7)
    val showReal = settings.showReal.asState(false)
    val redirectDebtBudget = settings.redirectDebtBudget.asState(false)

    fun setHorizonYears(v: Int) = safeLaunch { settings.setHorizonYears(v) }
    fun setMonthlyContribution(v: Double) = safeLaunch { settings.setMonthlyContribution(v) }
    fun setExpectedReturnPct(v: Double) = safeLaunch { settings.setExpectedReturnPct(v) }
    fun setAssumedInflationPct(v: Double) = safeLaunch { settings.setAssumedInflationPct(v) }
    fun setShowReal(v: Boolean) = safeLaunch { settings.setShowReal(v) }
    fun setRedirectDebtBudget(v: Boolean) = safeLaunch { settings.setRedirectDebtBudget(v) }

    /** Freed debt budget flowing into investments once the payoff plan completes. */
    data class Redirect(val fromMonth: Int, val amount: Double)

    val redirectInfo = combine(redirectDebtBudget, debtPlan, debtInputs, extraMonthly) { enabled, plan, inputs, extra ->
        val payoff = plan?.plan?.payoffMonth
        if (!enabled || payoff == null) null
        else Redirect(
            fromMonth = YearMonth.now().until(payoff, java.time.temporal.ChronoUnit.MONTHS).toInt().coerceAtLeast(0),
            amount = inputs.filter { it.includeInPlan }.sumOf { it.minPayment } + extra,
        )
    }.asState(null)

    val stressEnabled = settings.stressEnabled.asState(false)
    val stressRatePct = settings.stressRatePct.asState(2.0)
    fun setStressEnabled(v: Boolean) = safeLaunch { settings.setStressEnabled(v) }
    fun setStressRatePct(v: Double) = safeLaunch { settings.setStressRatePct(v) }

    private data class InvestParams(
        val years: Int,
        val contrib: Double,
        val expected: Double,
        val inflation: Double,
        val stress: Double?,
    )

    private val stressFlow = combine(stressEnabled, stressRatePct) { on, rate -> if (on) rate else null }

    private val investParams = combine(
        horizonYears, monthlyContribution, expectedReturnPct, assumedInflationPct, stressFlow,
    ) { years, contrib, expected, inflation, stress -> InvestParams(years, contrib, expected, inflation, stress) }

    private fun isCryptoAccount(name: String): Boolean {
        val n = name.lowercase()
        return listOf("crypto", "btc", "bitcoin", "coinbase", "ethereum").any { n.contains(it) }
    }

    /** Equities sleeve gets contributions and a tight band; crypto gets a deliberately wide band. */
    private fun sleevesFor(
        accounts: List<com.financedashboard.app.data.AccountUi>,
        contrib: Double,
        expected: Double,
    ): List<InvestmentEngine.Sleeve> {
        val (crypto, equities) = accounts.partition { isCryptoAccount(it.name) }
        return buildList {
            add(
                InvestmentEngine.Sleeve(
                    principal = equities.sumOf { it.latestBalance },
                    monthlyContribution = contrib,
                    pessimisticPct = (expected - 4.0).coerceAtLeast(0.0),
                    expectedPct = expected,
                    optimisticPct = expected + 3.0,
                    receivesContributions = true,
                )
            )
            val cryptoTotal = crypto.sumOf { it.latestBalance }
            if (cryptoTotal > 0.005) {
                add(
                    InvestmentEngine.Sleeve(
                        principal = cryptoTotal,
                        monthlyContribution = 0.0,
                        pessimisticPct = expected - 15.0,
                        expectedPct = expected,
                        optimisticPct = expected + 15.0,
                        receivesContributions = false,
                    )
                )
            }
        }
    }

    val investmentBand = combine(investmentAccounts, investParams, redirectInfo) { accs, p, redirect ->
        if (accs.isEmpty()) null
        else InvestmentEngine.sleeveBand(
            sleeves = sleevesFor(accs, p.contrib, p.expected),
            years = p.years,
            inflationPct = p.inflation,
            redirectFromMonth = redirect?.fromMonth,
            redirectAmount = redirect?.amount ?: 0.0,
            stressRatePct = p.stress,
            stressYears = 10,
        )
    }.flowOn(Dispatchers.Default).asState(null)

    /** Whether the portfolio has a crypto sleeve (drives the wide-band note). */
    val hasCryptoSleeve = investmentAccounts.map { accs -> accs.any { isCryptoAccount(it.name) } }.asState(false)

    // ---- Investment goals ----
    data class GoalUi(
        val goal: com.financedashboard.app.data.SettingsStore.Goal,
        val projectedNominal: Double,
        val progress: Double,
        val reachedYear: Int?,
    )

    val goals = settings.goals.asState(emptyList())

    val goalProjections = combine(goals, investmentAccounts, investParams) { gs, accs, p ->
        gs.map { g ->
            val proj = InvestmentEngine.sumProjections(
                sleevesFor(accs, p.contrib, p.expected).map { s ->
                    InvestmentEngine.project(
                        s.principal, if (s.receivesContributions) s.monthlyContribution else 0.0,
                        s.expectedPct, g.years, p.inflation,
                    )
                }.ifEmpty { listOf(InvestmentEngine.project(0.0, p.contrib, p.expected, g.years, p.inflation)) }
            )
            val projected = proj.years.last().endBalanceNominal
            GoalUi(
                goal = g,
                projectedNominal = projected,
                progress = (projected / g.target).coerceIn(0.0, 1.0),
                reachedYear = InvestmentEngine.milestoneYear(proj, g.target),
            )
        }
    }.flowOn(Dispatchers.Default).asState(emptyList())

    fun addGoal(name: String, target: Double, years: Int) = safeLaunch {
        settings.setGoals(goals.value.filter { it.name != name } + com.financedashboard.app.data.SettingsStore.Goal(name, target, years))
    }

    fun removeGoal(name: String) = safeLaunch {
        settings.setGoals(goals.value.filter { it.name != name })
    }

    // ---- Cash flow ----
    data class CashFlowMonth(val month: YearMonth, val income: Double, val spending: Double)

    val cashFlow = combine(repo.monthlyPaychecks(12), repo.monthlyExpenses(12)) { income, spending ->
        val incomeByMonth = income.toMap()
        val spendByMonth = spending.toMap()
        (incomeByMonth.keys + spendByMonth.keys).sorted().map { m ->
            CashFlowMonth(m, incomeByMonth[m] ?: 0.0, spendByMonth[m] ?: 0.0)
        }
    }.asState(emptyList())

    // ---- Spending & insights ----
    val spendingLastYear = repo.spendingByCategory(java.time.LocalDate.now().minusMonths(12)).asState(emptyList())
    val recurringCharges = repo.recurringCharges.asState(emptyList())
    val recurringMonthlyTotal = repo.recurringCharges.map {
        com.financedashboard.core.engine.RecurringDetector.monthlyTotal(it)
    }.asState(0.0)
    val topMovers = repo.topMovers.asState(emptyList())
    val inferredPayments = repo.inferredPayments.asState(emptyMap())

    // Transaction browser.
    val searchQuery = MutableStateFlow("")
    val searchCategory = MutableStateFlow("")
    fun setSearchQuery(v: String) { searchQuery.value = v }
    fun setSearchCategory(v: String) { searchCategory.value = if (searchCategory.value == v) "" else v }

    val searchResults = combine(searchQuery, searchCategory) { q, c -> q to c }
        .flatMapLatest { (q, c) -> repo.searchTransactions(q.trim(), c, 100) }
        .flowOn(Dispatchers.Default)
        .asState(emptyList())

    // ---- Personal (spending-weighted) inflation ----
    data class CategoryRate(val category: String, val share: Double, val ratePct: Double)
    data class PersonalInflation(val ratePct: Double, val headlinePct: Double, val categories: List<CategoryRate>)

    val categoryInflation = settings.categoryInflation.asState(emptyMap())
    fun setCategoryRate(category: String, ratePct: Double) = safeLaunch {
        settings.setCategoryInflation(categoryInflation.value + (category to ratePct))
    }
    fun clearCategoryRate(category: String) = safeLaunch {
        settings.setCategoryInflation(categoryInflation.value - category)
    }

    val personalInflation = combine(spendingLastYear, categoryInflation, cpiTable) { spending, overrides, cpi ->
        val years = cpi.keys.sorted()
        val headline = if (years.size >= 2) {
            val last = years.last(); val prev = years[years.size - 2]
            (cpi.getValue(last) / cpi.getValue(prev) - 1.0) * 100
        } else 2.7
        val total = spending.sumOf { it.total }
        if (total <= 0.005) return@combine PersonalInflation(headline, headline, emptyList())
        val top = spending.take(10)
        val cats = top.map { c ->
            CategoryRate(c.category, c.total / total, overrides[c.category] ?: headline)
        }
        val otherShare = 1.0 - cats.sumOf { it.share }
        val rate = cats.sumOf { it.share * it.ratePct } + otherShare * headline
        PersonalInflation(rate, headline, cats)
    }.flowOn(Dispatchers.Default).asState(null)

    // ---- Forward-looking projection ----
    val futureRaisePct = settings.futureRaisePct.asState(3.0)
    val futureInflationPct = settings.futureInflationPct.asState(2.7)
    fun setFutureRaisePct(v: Double) = safeLaunch { settings.setFutureRaisePct(v) }
    fun setFutureInflationPct(v: Double) = safeLaunch { settings.setFutureInflationPct(v) }

    data class ForwardYear(val year: Int, val nominal: Double, val real: Double)
    data class ForwardProjection(val years: List<ForwardYear>, val realDeltaPctPerYear: Double)

    val forwardProjection = combine(inflationImpact, futureRaisePct, futureInflationPct) { impact, raise, infl ->
        val last = impact?.years?.lastOrNull() ?: return@combine null
        val realFactor = (1 + raise / 100.0) / (1 + infl / 100.0)
        ForwardProjection(
            years = (1..5).map { t ->
                ForwardYear(
                    year = last.year + t,
                    nominal = last.actualIncome * Math.pow(1 + raise / 100.0, t.toDouble()),
                    real = last.realIncome * Math.pow(realFactor, t.toDouble()),
                )
            },
            realDeltaPctPerYear = (realFactor - 1.0) * 100,
        )
    }.asState(null)

    // ---- Import health / data quality (adjustments merged in so fixes stick) ----
    private val adjustmentRecords = settings.balanceAdjustments.map { adjustments ->
        adjustments.map {
            com.financedashboard.core.model.TransactionRecord(
                java.time.LocalDate.ofEpochDay(it.epochDay), "Manual adjustment", "Adjustment",
                it.account, "", "", it.amount, "", "Me",
            )
        }
    }

    val importHealth = combine(
        repo.allBalanceRecords, repo.allTransactionRecords, repo.accountTypeOf, adjustmentRecords,
    ) { balances, txs, typeOf, adjustments ->
        if (balances.isEmpty() && txs.isEmpty()) null
        else com.financedashboard.core.engine.ImportHealthEngine.analyze(balances, txs + adjustments, typeOf)
    }.flowOn(Dispatchers.Default).asState(null)

    fun applyBalanceAdjustment(account: String, amount: Double) = safeLaunch {
        settings.addBalanceAdjustment(
            com.financedashboard.app.data.SettingsStore.BalanceAdjustment(account, java.time.LocalDate.now().toEpochDay(), amount)
        )
    }

    // ---- Interest paid ledger ----
    val interestLedger = combine(debtExtraction, repo.allBalanceRecords, repo.allTransactionRecords) { ex, balances, txs ->
        val accounts = ex.active.map { it.accountName }
        com.financedashboard.core.engine.InterestLedgerEngine.compute(accounts, balances, txs)
    }.flowOn(Dispatchers.Default).asState(emptyList())

    // ---- Tax & simulation settings ----
    val ltcgRatePct = settings.ltcgRatePct.asState(15.0)
    val retirementRatePct = settings.retirementRatePct.asState(22.0)
    val mcVolatilityPct = settings.mcVolatilityPct.asState(15.0)
    fun setLtcgRatePct(v: Double) = safeLaunch { settings.setLtcgRatePct(v) }
    fun setRetirementRatePct(v: Double) = safeLaunch { settings.setRetirementRatePct(v) }
    fun setMcVolatilityPct(v: Double) = safeLaunch { settings.setMcVolatilityPct(v) }

    // ---- Subscription price hikes ----
    val priceHikes = repo.recurringCharges.map {
        com.financedashboard.core.engine.RecurringDetector.priceHikes(it)
    }.asState(emptyList())

    // ---- Paycheck wedge ----
    val retirementContribByYear = settings.retirementContribByYear.asState(emptyMap())
    val paycheckWedge = combine(incomeByYear, derivedNetIncome, retirementContribByYear) { gross, net, retire ->
        com.financedashboard.core.engine.PaycheckWedgeEngine.compute(gross, net, retire)
    }.asState(emptyList())
    fun setRetirementContrib(year: Int, amount: Double) = safeLaunch { settings.setRetirementContrib(year, amount) }

    // ---- Household split by owner ----
    val owners = repo.owners.asState(emptyList())
    val selectedOwner = MutableStateFlow("")
    fun setSelectedOwner(v: String) { selectedOwner.value = if (selectedOwner.value == v) "" else v }
    @Suppress("OPT_IN_USAGE")
    val ownerSpending = selectedOwner.flatMapLatest { owner ->
        repo.spendingByOwner(owner, java.time.LocalDate.now().minusMonths(12))
    }.asState(emptyList())

    // ---- Net-worth attribution (trailing 12 months) ----
    val attribution = combine(repo.allBalanceRecords, repo.allTransactionRecords, repo.accountTypeOf) { balances, txs, typeOf ->
        if (balances.isEmpty()) null
        else {
            val to = java.time.LocalDate.now()
            val from = to.minusYears(1)
            com.financedashboard.core.engine.AttributionEngine.attribute(balances, txs, typeOf, from, to)
        }
    }.flowOn(Dispatchers.Default).asState(null)

    // ---- Crypto cost basis ----
    val cryptoLots = repo.allTransactionRecords.map { txs ->
        val trades = com.financedashboard.core.engine.CryptoLotEngine.parseTrades(txs)
        if (trades.isEmpty()) null else com.financedashboard.core.engine.CryptoLotEngine.account(trades)
    }.flowOn(Dispatchers.Default).asState(null)

    // ---- FIRE ----
    val retirementYears = settings.retirementYears.asState(25)
    val withdrawalRatePct = settings.withdrawalRatePct.asState(4.0)
    val marginalTaxPct = settings.marginalTaxPct.asState(0.0)
    fun setRetirementYears(v: Int) = safeLaunch { settings.setRetirementYears(v) }
    fun setWithdrawalRatePct(v: Double) = safeLaunch { settings.setWithdrawalRatePct(v) }
    fun setMarginalTaxPct(v: Double) = safeLaunch { settings.setMarginalTaxPct(v) }

    val fire = combine(
        investmentAccounts, avgMonthlyExpenses, monthlyContribution, expectedReturnPct,
        combine(retirementYears, withdrawalRatePct, assumedInflationPct) { y, w, infl -> Triple(y, w, infl) },
    ) { accs, monthlyExp, contrib, expected, (years, wr, infl) ->
        val annualSpending = monthlyExp * 12
        if (annualSpending <= 0) null
        else com.financedashboard.core.engine.FireEngine.compute(
            annualSpending = annualSpending,
            currentPortfolio = accs.sumOf { it.latestBalance },
            monthlyContribution = contrib,
            realReturnPct = (expected - infl).coerceAtLeast(0.0),
            yearsToRetirement = years,
            withdrawalRatePct = wr,
        )
    }.flowOn(Dispatchers.Default).asState(null)

    // ---- Extra-dollar optimizer ----
    val optimizerResult = combine(debtInputs, extraMonthly, expectedReturnPct, marginalTaxPct) { inputs, extra, ret, tax ->
        val debts = inputs.filter { it.includeInPlan }.map { Debt(it.accountName, it.balance, it.aprPct, it.minPayment) }
        val amount = if (extra > 0) extra else 500.0
        if (debts.isEmpty()) null
        else com.financedashboard.core.engine.ExtraDollarOptimizer.compare(debts, amount, ret, 20, YearMonth.now(), tax)
    }.flowOn(Dispatchers.Default).asState(null)

    // ---- Refinance calculator (per-debt, on demand) ----
    fun refinance(
        balance: Double, currentApr: Double, newApr: Double, payment: Double, fees: Double, newTermMonths: Int?,
    ) = com.financedashboard.core.engine.RefinanceEngine.compare(
        balance, currentApr, newApr, payment, fees, YearMonth.now(), newTermMonths,
    )

    // ---- Sinking funds ----
    val sinkingFunds = settings.sinkingFunds.asState(emptyList())
    fun addSinkingFund(name: String, target: Double, current: Double, monthly: Double) = safeLaunch {
        settings.setSinkingFunds(sinkingFunds.value.filter { it.name != name } +
            com.financedashboard.app.data.SettingsStore.SinkingFund(name, target, current, monthly))
    }
    fun removeSinkingFund(name: String) = safeLaunch {
        settings.setSinkingFunds(sinkingFunds.value.filter { it.name != name })
    }

    // ---- Safe to spend ----
    val safeToSpend = combine(
        avgMonthlyIncome, recurringMonthlyTotal, repo.currentMonthSpending,
        combine(debtInputs, extraMonthly, efMonthlySaving, monthlyContribution) { debts, extra, ef, contrib ->
            debts.filter { it.includeInPlan }.sumOf { it.minPayment } + extra + ef + contrib
        },
    ) { income, recurring, spent, planned ->
        com.financedashboard.core.engine.SafeToSpendEngine.compute(income, recurring, planned, spent)
    }.flowOn(Dispatchers.Default).asState(null)

    // ---- Crypto sell analysis ----
    val cryptoSell = combine(cryptoLots, investmentAccounts, ltcgRatePct) { lots, accs, rate ->
        if (lots == null) null
        else {
            val currentValue = accs.filter { a -> listOf("crypto", "btc", "coinbase").any { a.name.lowercase().contains(it) } }
                .sumOf { it.latestBalance }.takeIf { it > 0 } ?: lots.remainingCostBasis
            com.financedashboard.core.engine.CryptoLotEngine.sellAnalysis(lots, currentValue, rate)
        }
    }.flowOn(Dispatchers.Default).asState(null)

    // ---- Monte Carlo retirement ----
    val monteCarlo = combine(
        investmentAccounts, monthlyContribution, expectedReturnPct,
        combine(mcVolatilityPct, retirementYears, fire) { vol, years, f -> Triple(vol, years, f?.fireNumber ?: 1_000_000.0) },
    ) { accs, contrib, ret, (vol, years, goal) ->
        com.financedashboard.core.engine.MonteCarloEngine.simulate(
            principal = accs.sumOf { it.latestBalance },
            monthlyContribution = contrib,
            annualReturnPct = ret,
            annualVolatilityPct = vol,
            years = years,
            goal = goal,
            runs = 1000,
        )
    }.flowOn(Dispatchers.Default).asState(null)

    // ---- Roth vs traditional ----
    val rothComparison = combine(
        monthlyContribution, marginalTaxPct, retirementRatePct, expectedReturnPct, retirementYears,
    ) { contrib, current, retire, ret, years ->
        if (contrib <= 0) null
        else com.financedashboard.core.engine.RothVsTraditionalEngine.compare(
            annualGrossContribution = contrib * 12,
            currentMarginalRatePct = if (current > 0) current else 24.0,
            retirementRatePct = retire,
            annualReturnPct = ret,
            years = years,
        )
    }.flowOn(Dispatchers.Default).asState(null)

    // ---- Security ----
    val biometricLock = settings.biometricLock.asState(false)
    fun setBiometricLock(v: Boolean) = safeLaunch { settings.setBiometricLock(v) }

    // ---- Saved scenarios (save & compare) ----
    val savedScenarios = settings.scenarios.asState(emptyList())
    val compareScenario = MutableStateFlow<String?>(null)
    fun setCompareScenario(name: String?) { compareScenario.value = if (compareScenario.value == name) null else name }

    fun saveCurrentScenario(name: String) = safeLaunch {
        val entry = com.financedashboard.app.data.SettingsStore.SavedScenario(
            name = name.trim(),
            strategy = strategy.value.name,
            extra = extraMonthly.value,
            growthPct = extraGrowthPct.value,
            lumpSums = lumpSums.value,
        )
        settings.setScenarios(savedScenarios.value.filter { it.name != entry.name } + entry)
    }

    fun deleteScenario(name: String) = safeLaunch {
        settings.setScenarios(savedScenarios.value.filter { it.name != name })
        if (compareScenario.value == name) compareScenario.value = null
    }

    /** Combined-balance line for the scenario selected for comparison. */
    val comparisonPlan = combine(compareScenario, savedScenarios, debtInputs) { name, saved, inputs ->
        val sc = saved.firstOrNull { it.name == name } ?: return@combine null
        val debts = inputs.filter { it.includeInPlan }.map { Debt(it.accountName, it.balance, it.aprPct, it.minPayment) }
        if (debts.isEmpty()) return@combine null
        val strat = runCatching { PayoffStrategy.valueOf(sc.strategy) }.getOrDefault(PayoffStrategy.AVALANCHE)
        AmortizationEngine.computePlan(debts, strat, sc.extra, YearMonth.now(), sc.growthPct, sc.lumpSums, customOrder.value)
    }.flowOn(Dispatchers.Default).asState(null)

    // ---- Inferred payments: accept into the debt assumption ----
    fun acceptInferredPayment(name: String) = safeLaunch {
        val inferred = inferredPayments.value[name] ?: return@safeLaunch
        val input = debtInputs.value.firstOrNull { it.accountName == name } ?: return@safeLaunch
        repo.setDebtAssumption(DebtAssumptionEntity(name, input.aprPct, inferred.monthlyPayment, input.includeInPlan))
    }

    // ---- Rates CSV import ----
    val ratesImportStatus = MutableStateFlow<String?>(null)
    fun importRates(uri: Uri) = safeLaunch {
        ratesImportStatus.value = withContext(Dispatchers.IO) {
            try {
                val entries = getApplication<Application>().contentResolver.openInputStream(uri)?.use { s ->
                    com.financedashboard.core.csv.RatesCsvParser().parse(java.io.BufferedReader(java.io.InputStreamReader(s)))
                } ?: return@withContext "Could not open file"
                if (entries.isEmpty()) "No rate rows found — expected columns like Account, APR, MinPayment"
                else {
                    val applied = repo.applyRateEntries(entries)
                    "Applied $applied of ${entries.size} rates"
                }
            } catch (e: Exception) {
                "Rates import failed: ${e.message}"
            }
        }
    }

    // ---- Backup / restore ----
    val backupStatus = MutableStateFlow<String?>(null)

    fun exportBackup() = safeLaunch {
        backupStatus.value = withContext(Dispatchers.IO) {
            try {
                val json = com.financedashboard.core.backup.BackupCodec.encode(repo.buildBackup())
                val dir = java.io.File(getApplication<Application>().cacheDir, "exports").apply { mkdirs() }
                val stamp = java.time.LocalDate.now()
                val file = java.io.File(dir, "finance-backup-$stamp.json")
                file.writeText(json)
                val ctx = getApplication<Application>()
                val fileUri = androidx.core.content.FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
                val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                    type = "application/json"
                    putExtra(android.content.Intent.EXTRA_STREAM, fileUri)
                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                ctx.startActivity(android.content.Intent.createChooser(intent, "Save backup").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                "Backup ready to share"
            } catch (e: Exception) {
                "Backup failed: ${e.message}"
            }
        }
    }

    data class PendingRestore(val backup: com.financedashboard.core.backup.BackupCodec.Backup, val summary: String)
    // (BackupCodec.Backup fully-qualified is fine here — it is only a type reference.)
    val pendingRestore = MutableStateFlow<PendingRestore?>(null)

    fun requestRestore(uri: Uri) = safeLaunch {
        backupStatus.value = withContext(Dispatchers.IO) {
            try {
                val text = getApplication<Application>().contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
                val backup = text?.let { com.financedashboard.core.backup.BackupCodec.decode(it) }
                if (backup == null) "Not a valid backup file"
                else {
                    pendingRestore.value = PendingRestore(
                        backup,
                        "${backup.balances.size} balances, ${backup.transactions.size} transactions, " +
                            "${backup.accounts.size} accounts",
                    )
                    null
                }
            } catch (e: Exception) {
                "Restore failed: ${e.message}"
            }
        }
    }

    fun confirmRestore() {
        val pending = pendingRestore.value ?: return
        pendingRestore.value = null
        safeLaunch {
            backupStatus.value = "Restoring…"
            repo.restoreBackup(pending.backup)
            backupStatus.value = "Restored ${pending.summary}"
        }
    }

    fun cancelRestore() { pendingRestore.value = null }

    fun exportAnalytics() = safeLaunch {
        val (header, rows) = repo.analyticsRows()
        com.financedashboard.app.data.TableExporter.shareCsv(getApplication(), "finance-analytics.csv", header, rows)
    }

    // ---- Year in Review ----
    data class YearReview(
        val year: Int,
        val income: Double,
        val spending: Double,
        val savingsRate: Double,
        val netWorthStart: Double,
        val netWorthEnd: Double,
        val attribution: com.financedashboard.core.engine.AttributionEngine.Attribution?,
        val topCategories: List<Pair<String, Double>>,
    )

    val reviewYear = MutableStateFlow(java.time.LocalDate.now().year - 1)
    fun setReviewYear(y: Int) { reviewYear.value = y }

    val yearReview = combine(
        reviewYear, repo.allBalanceRecords, repo.allTransactionRecords, repo.accountTypeOf,
    ) { year, balances, txs, typeOf ->
        if (balances.isEmpty()) return@combine null
        val from = java.time.LocalDate.of(year, 1, 1)
        val to = java.time.LocalDate.of(year, 12, 31)
        val yearTxs = txs.filter { it.date.year == year }
        val income = yearTxs.filter { it.category in IncomeAggregator.PAYCHECK_CATEGORIES && it.amount > 0 }.sumOf { it.amount }
        val excluded = com.financedashboard.app.data.NON_SPENDING_CATEGORIES
        val spendTxs = yearTxs.filter { it.amount < 0 && it.category !in excluded }
        val spending = spendTxs.sumOf { -it.amount }
        val nw = com.financedashboard.core.engine.NetWorthAggregator.monthlySeries(balances)
        val nwStart = nw.firstOrNull { it.month.year == year }?.net ?: 0.0
        val nwEnd = nw.lastOrNull { it.month.year == year }?.net ?: nwStart
        YearReview(
            year = year,
            income = income,
            spending = spending,
            savingsRate = if (income > 0) (income - spending) / income * 100 else 0.0,
            netWorthStart = nwStart,
            netWorthEnd = nwEnd,
            attribution = com.financedashboard.core.engine.AttributionEngine.attribute(balances, txs, typeOf, from, to),
            topCategories = spendTxs.groupBy { it.category }.mapValues { (_, t) -> t.sumOf { -it.amount } }
                .entries.sortedByDescending { it.value }.take(6).map { it.key to it.value },
        )
    }.flowOn(Dispatchers.Default).asState(null)

    fun exportYearReviewPdf() = safeLaunch {
        val review = yearReview.value ?: return@safeLaunch
        com.financedashboard.app.data.YearReviewPdf.export(getApplication(), review)
    }

    // ---- Notifications ----
    val notificationsEnabled = settings.notificationsEnabled.asState(false)
    fun setNotificationsEnabled(v: Boolean) = safeLaunch {
        // Schedule first; only persist "on" if scheduling actually succeeded.
        val scheduled = com.financedashboard.app.notify.NotifyScheduler.setEnabled(getApplication(), v)
        if (v && !scheduled) {
            lastError.value = "Couldn't enable notifications on this device"
            settings.setNotificationsEnabled(false)
        } else {
            settings.setNotificationsEnabled(v)
        }
    }
    fun accountHistory(name: String) = repo.balanceHistory(name)

    // ---- Import & mutations ----
    val importStatus = MutableStateFlow<String?>(null)

    /** Preview shown before a replace-all import is committed. */
    data class PendingImport(
        val uri: Uri,
        val preview: CsvImporter.Preview,
        val currentRows: Int,
        val currentRange: Pair<java.time.LocalDate, java.time.LocalDate>?,
    )

    val pendingImport = MutableStateFlow<PendingImport?>(null)

    fun requestImportBalances(uri: Uri) = safeLaunch {
        importStatus.value = "Reading file…"
        val preview = importer.previewBalances(uri)
        if (preview.error != null) {
            importStatus.value = "Balances import failed: ${preview.error}"
        } else {
            val (rows, range) = importer.currentBalancesSummary()
            importStatus.value = null
            pendingImport.value = PendingImport(uri, preview, rows, range)
        }
    }

    fun requestImportTransactions(uri: Uri) = safeLaunch {
        importStatus.value = "Reading file…"
        val preview = importer.previewTransactions(uri)
        if (preview.error != null) {
            importStatus.value = "Transactions import failed: ${preview.error}"
        } else {
            importStatus.value = null
            pendingImport.value = PendingImport(uri, preview, transactionCount.value, null)
        }
    }

    fun confirmPendingImport() {
        val pending = pendingImport.value ?: return
        pendingImport.value = null
        safeLaunch {
            importStatus.value = "Importing…"
            importStatus.value = when (pending.preview.kind) {
                CsvImporter.Preview.Kind.BALANCES -> when (val r = importer.importBalances(pending.uri)) {
                    is CsvImporter.Result.Balances -> "Imported ${r.rows} balance rows across ${r.accounts} accounts"
                    is CsvImporter.Result.Error -> "Balances import failed: ${r.message}"
                    else -> null
                }
                CsvImporter.Preview.Kind.TRANSACTIONS -> when (val r = importer.importTransactions(pending.uri)) {
                    is CsvImporter.Result.Transactions -> "Imported ${r.rows} transactions"
                    is CsvImporter.Result.Error -> "Transactions import failed: ${r.message}"
                    else -> null
                }
            }
            com.financedashboard.app.widget.NetWorthWidget.refresh(getApplication())
        }
    }

    fun cancelPendingImport() {
        pendingImport.value = null
        importStatus.value = "Import cancelled — existing data unchanged"
    }

    fun setDebtAssumption(name: String, aprPct: Double, minPayment: Double, include: Boolean) =
        safeLaunch {
            repo.setDebtAssumption(DebtAssumptionEntity(name, aprPct, minPayment, include))
        }

    fun setManualIncome(year: Int, amount: Double) = safeLaunch { repo.setManualIncome(year, amount) }
    fun deleteManualIncome(year: Int) = safeLaunch { repo.deleteManualIncome(year) }
    fun setCpiOverride(year: Int, value: Double) = safeLaunch { repo.setCpiOverride(year, value) }
    fun deleteCpiOverride(year: Int) = safeLaunch { repo.deleteCpiOverride(year) }
    fun overrideAccountType(name: String, type: AccountType) =
        safeLaunch { repo.overrideAccountType(name, type) }
    fun wipeAll() = safeLaunch { repo.wipeAll() }
}
