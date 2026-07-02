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
)

@OptIn(ExperimentalCoroutinesApi::class)
class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val db = AppDatabase.get(app)
    val repo = FinanceRepository(db)
    private val importer = CsvImporter(app, db)

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
    val debtInputs = combine(debtExtraction, repo.debtAssumptions) { extraction, assumptions ->
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

    val extraMonthly = MutableStateFlow(0.0)
    val strategy = MutableStateFlow(PayoffStrategy.AVALANCHE)

    private fun List<DebtInput>.toDebts() = filter { it.includeInPlan }
        .map { Debt(it.accountName, it.balance, it.aprPct, it.minPayment) }

    val debtPlan = combine(debtInputs, extraMonthly, strategy) { inputs, extra, strat ->
        val debts = inputs.toDebts()
        if (debts.isEmpty()) null
        else AmortizationEngine.computePlan(debts, strat, extra, YearMonth.now())
    }.flowOn(Dispatchers.Default).asState(null)

    /** Same debts with no extra payment, for the comparison overlay + delta banner. */
    val debtPlanBaseline = combine(debtInputs, strategy) { inputs, strat ->
        val debts = inputs.toDebts()
        if (debts.isEmpty()) null
        else AmortizationEngine.computePlan(debts, strat, 0.0, YearMonth.now())
    }.flowOn(Dispatchers.Default).asState(null)

    val strategyComparison = combine(debtInputs, extraMonthly) { inputs, extra ->
        val debts = inputs.toDebts()
        if (debts.isEmpty()) emptyList()
        else AmortizationEngine.compareStrategies(debts, extra, YearMonth.now())
    }.flowOn(Dispatchers.Default).asState(emptyList())

    // ---- Investments ----
    val investmentAccounts = accounts.map { accs ->
        accs.filter { it.type == AccountType.INVESTMENT && it.latestBalance > 0.005 }
    }.asState(emptyList())

    val horizonYears = MutableStateFlow(20)
    val monthlyContribution = MutableStateFlow(500.0)
    val expectedReturnPct = MutableStateFlow(7.0)
    val assumedInflationPct = MutableStateFlow(2.7)
    val showReal = MutableStateFlow(false)

    val investmentBand = combine(
        investmentAccounts, horizonYears, monthlyContribution, expectedReturnPct, assumedInflationPct,
    ) { accs, years, contrib, expected, inflation ->
        val principal = accs.sumOf { it.latestBalance }
        InvestmentEngine.scenarioBand(
            principal = principal,
            monthlyContribution = contrib,
            years = years,
            inflationPct = inflation,
            pessimisticPct = (expected - 4.0).coerceAtLeast(0.0),
            expectedPct = expected,
            optimisticPct = expected + 3.0,
        )
    }.flowOn(Dispatchers.Default).asState(null)

    // ---- Spending ----
    val spendingLastYear = repo.spendingByCategory(java.time.LocalDate.now().minusMonths(12)).asState(emptyList())
    fun accountHistory(name: String) = repo.balanceHistory(name)

    // ---- Import & mutations ----
    val importStatus = MutableStateFlow<String?>(null)

    fun importBalances(uri: Uri) = viewModelScope.launch {
        importStatus.value = "Importing balances…"
        importStatus.value = when (val r = importer.importBalances(uri)) {
            is CsvImporter.Result.Balances -> "Imported ${r.rows} balance rows across ${r.accounts} accounts"
            is CsvImporter.Result.Error -> "Balances import failed: ${r.message}"
            else -> null
        }
    }

    fun importTransactions(uri: Uri) = viewModelScope.launch {
        importStatus.value = "Importing transactions…"
        importStatus.value = when (val r = importer.importTransactions(uri)) {
            is CsvImporter.Result.Transactions -> "Imported ${r.rows} transactions"
            is CsvImporter.Result.Error -> "Transactions import failed: ${r.message}"
            else -> null
        }
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
