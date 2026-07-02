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
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

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

    fun setExtraMonthly(v: Double) = viewModelScope.launch { settings.setExtraMonthly(v) }
    fun setStrategy(v: PayoffStrategy) = viewModelScope.launch { settings.setStrategy(v.name) }

    val extraGrowthPct = settings.extraGrowthPct.asState(0.0)
    val lumpSums = settings.lumpSums.asState(emptyMap())
    val customOrder = settings.customOrder.asState(emptyList())

    fun setExtraGrowthPct(v: Double) = viewModelScope.launch { settings.setExtraGrowthPct(v) }
    fun addLumpSum(month: java.time.YearMonth, amount: Double) = viewModelScope.launch {
        settings.setLumpSums(lumpSums.value + (month to amount))
    }
    fun removeLumpSum(month: java.time.YearMonth) = viewModelScope.launch {
        settings.setLumpSums(lumpSums.value - month)
    }
    fun moveInCustomOrder(name: String, up: Boolean) = viewModelScope.launch {
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
    val avgMonthlyExpenses = repo.monthlyExpenses(6).map { months ->
        com.financedashboard.core.engine.EmergencyFundEngine.averageMonthlyExpenses(months.map { it.second })
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

    fun setEfTargetMonths(v: Int) = viewModelScope.launch { settings.setEfTargetMonths(v) }
    fun setEfMonthlySaving(v: Double) = viewModelScope.launch { settings.setEfMonthlySaving(v) }
    fun setEfFirstInPayoff(v: Boolean) = viewModelScope.launch { settings.setEfFirstInPayoff(v) }

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

    fun answerCardQuestion(name: String, revolves: Boolean) = viewModelScope.launch {
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

    fun setHorizonYears(v: Int) = viewModelScope.launch { settings.setHorizonYears(v) }
    fun setMonthlyContribution(v: Double) = viewModelScope.launch { settings.setMonthlyContribution(v) }
    fun setExpectedReturnPct(v: Double) = viewModelScope.launch { settings.setExpectedReturnPct(v) }
    fun setAssumedInflationPct(v: Double) = viewModelScope.launch { settings.setAssumedInflationPct(v) }
    fun setShowReal(v: Boolean) = viewModelScope.launch { settings.setShowReal(v) }
    fun setRedirectDebtBudget(v: Boolean) = viewModelScope.launch { settings.setRedirectDebtBudget(v) }

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
    fun setStressEnabled(v: Boolean) = viewModelScope.launch { settings.setStressEnabled(v) }
    fun setStressRatePct(v: Double) = viewModelScope.launch { settings.setStressRatePct(v) }

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

    fun addGoal(name: String, target: Double, years: Int) = viewModelScope.launch {
        settings.setGoals(goals.value.filter { it.name != name } + com.financedashboard.app.data.SettingsStore.Goal(name, target, years))
    }

    fun removeGoal(name: String) = viewModelScope.launch {
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

    // ---- Spending ----
    val spendingLastYear = repo.spendingByCategory(java.time.LocalDate.now().minusMonths(12)).asState(emptyList())

    // ---- Personal (spending-weighted) inflation ----
    data class CategoryRate(val category: String, val share: Double, val ratePct: Double)
    data class PersonalInflation(val ratePct: Double, val headlinePct: Double, val categories: List<CategoryRate>)

    val categoryInflation = settings.categoryInflation.asState(emptyMap())
    fun setCategoryRate(category: String, ratePct: Double) = viewModelScope.launch {
        settings.setCategoryInflation(categoryInflation.value + (category to ratePct))
    }
    fun clearCategoryRate(category: String) = viewModelScope.launch {
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
    fun setFutureRaisePct(v: Double) = viewModelScope.launch { settings.setFutureRaisePct(v) }
    fun setFutureInflationPct(v: Double) = viewModelScope.launch { settings.setFutureInflationPct(v) }

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

    // ---- Security ----
    val biometricLock = settings.biometricLock.asState(false)
    fun setBiometricLock(v: Boolean) = viewModelScope.launch { settings.setBiometricLock(v) }
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

    fun requestImportBalances(uri: Uri) = viewModelScope.launch {
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

    fun requestImportTransactions(uri: Uri) = viewModelScope.launch {
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
        viewModelScope.launch {
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
        }
    }

    fun cancelPendingImport() {
        pendingImport.value = null
        importStatus.value = "Import cancelled — existing data unchanged"
    }

    fun setDebtAssumption(name: String, aprPct: Double, minPayment: Double, include: Boolean) =
        viewModelScope.launch {
            repo.setDebtAssumption(DebtAssumptionEntity(name, aprPct, minPayment, include))
        }

    fun setManualIncome(year: Int, amount: Double) = viewModelScope.launch { repo.setManualIncome(year, amount) }
    fun deleteManualIncome(year: Int) = viewModelScope.launch { repo.deleteManualIncome(year) }
    fun setCpiOverride(year: Int, value: Double) = viewModelScope.launch { repo.setCpiOverride(year, value) }
    fun deleteCpiOverride(year: Int) = viewModelScope.launch { repo.deleteCpiOverride(year) }
    fun overrideAccountType(name: String, type: AccountType) =
        viewModelScope.launch { repo.overrideAccountType(name, type) }
    fun wipeAll() = viewModelScope.launch { repo.wipeAll() }
}
