package com.financedashboard.app.data

import com.financedashboard.app.data.db.AppDatabase
import com.financedashboard.app.data.db.CategoryTotal
import com.financedashboard.app.data.db.CpiOverrideEntity
import com.financedashboard.app.data.db.DebtAssumptionEntity
import com.financedashboard.app.data.db.ManualIncomeEntity
import com.financedashboard.core.engine.IncomeAggregator
import com.financedashboard.core.engine.InflationEngine
import com.financedashboard.core.model.AccountType
import java.time.LocalDate
import java.time.YearMonth
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

data class AccountUi(
    val name: String,
    val type: AccountType,
    val latestBalance: Double,
    val latestDate: LocalDate?,
    val history: List<Pair<LocalDate, Double>> = emptyList(),
)

data class MonthlyNetWorth(
    val month: YearMonth,
    val assets: Double,
    val debts: Double,
) {
    val net: Double get() = assets - debts
}

class FinanceRepository(private val db: AppDatabase) {

    val accounts: Flow<List<AccountUi>> =
        combine(db.accountDao().all(), db.balanceDao().latestPerAccount()) { accounts, latest ->
            val latestByName = latest.associateBy { it.accountName }
            accounts.map { acc ->
                val lb = latestByName[acc.name]
                AccountUi(
                    name = acc.name,
                    type = runCatching { AccountType.valueOf(acc.type) }.getOrDefault(AccountType.UNKNOWN),
                    latestBalance = lb?.balance ?: 0.0,
                    latestDate = lb?.let { LocalDate.ofEpochDay(it.epochDay) },
                )
            }
        }

    /**
     * Month-end assets and debts, delegated to the core aggregator so the same
     * closed-account and duplicate rules apply here as in debt identification —
     * a stale Affirm balance or a relinked loan must never inflate net worth.
     */
    val monthlyNetWorth: Flow<List<MonthlyNetWorth>> =
        db.balanceDao().all().map { balances ->
            com.financedashboard.core.engine.NetWorthAggregator.monthlySeries(
                balances.map {
                    com.financedashboard.core.model.BalanceRecord(
                        LocalDate.ofEpochDay(it.epochDay), it.balance, it.accountName,
                    )
                }
            ).map { MonthlyNetWorth(it.month, it.assets, it.debts) }
        }

    /** Strict-rules debt identification (latest-only, stale/paid-off excluded, duplicates flagged). */
    val debtExtraction: Flow<com.financedashboard.core.classify.DebtExtractor.Extraction> =
        db.balanceDao().all().map { rows ->
            com.financedashboard.core.classify.DebtExtractor.extract(
                rows.map { com.financedashboard.core.model.BalanceRecord(LocalDate.ofEpochDay(it.epochDay), it.balance, it.accountName) }
            )
        }

    fun balanceHistory(account: String): Flow<List<Pair<LocalDate, Double>>> =
        db.balanceDao().forAccount(account).map { rows ->
            rows.map { LocalDate.ofEpochDay(it.epochDay) to it.balance }
        }

    /** Net paycheck income per year derived from transactions, merged with manual gross entries. */
    val incomeByYear: Flow<Map<Int, Double>> =
        combine(
            db.transactionDao().incomeTransactions(IncomeAggregator.PAYCHECK_CATEGORIES.toList()),
            db.manualIncomeDao().all(),
        ) { txs, manual ->
            val derived = txs
                .groupBy { LocalDate.ofEpochDay(it.epochDay).year }
                .mapValues { (_, list) -> list.sumOf { it.amount } }
            IncomeAggregator.mergedIncomeByYear(derived, manual.associate { it.year to it.grossAmount })
        }

    val derivedNetIncomeByYear: Flow<Map<Int, Double>> =
        db.transactionDao().incomeTransactions(IncomeAggregator.PAYCHECK_CATEGORIES.toList()).map { txs ->
            txs.groupBy { LocalDate.ofEpochDay(it.epochDay).year }
                .mapValues { (_, list) -> list.sumOf { it.amount } }
        }

    val manualIncome: Flow<List<ManualIncomeEntity>> = db.manualIncomeDao().all()

    val cpiTable: Flow<Map<Int, Double>> = db.cpiOverrideDao().all().map { overrides ->
        InflationEngine.DEFAULT_CPI + overrides.associate { it.year to it.cpiIndex }
    }

    val debtAssumptions: Flow<Map<String, DebtAssumptionEntity>> =
        db.debtAssumptionDao().all().map { list -> list.associateBy { it.accountName } }

    /** Highest amount ever owed per account — the "original" for %-paid progress. */
    val maxOwedByAccount: Flow<Map<String, Double>> = db.balanceDao().all().map { rows ->
        rows.filter { it.balance < 0 }
            .groupBy { it.accountName }
            .mapValues { (_, list) -> -list.minOf { it.balance } }
    }

    /** Paycheck deposits per trailing full month (current partial month excluded). */
    fun monthlyPaychecks(months: Int): Flow<List<Pair<YearMonth, Double>>> =
        db.transactionDao().incomeTransactions(IncomeAggregator.PAYCHECK_CATEGORIES.toList()).map { txs ->
            val current = YearMonth.now()
            txs.groupBy { YearMonth.from(LocalDate.ofEpochDay(it.epochDay)) }
                .filterKeys { it < current }
                .mapValues { (_, list) -> list.sumOf { it.amount } }
                .toSortedMap().toList().takeLast(months)
        }

    /** Average take-home from paycheck deposits over the trailing 6 full months. */
    val avgMonthlyPaychecks: Flow<Double> =
        db.transactionDao().incomeTransactions(IncomeAggregator.PAYCHECK_CATEGORIES.toList()).map { txs ->
            val current = YearMonth.now()
            val byMonth = txs.groupBy { YearMonth.from(LocalDate.ofEpochDay(it.epochDay)) }
                .filterKeys { it < current }
                .mapValues { (_, list) -> list.sumOf { it.amount } }
                .toSortedMap().toList().takeLast(6)
            if (byMonth.isEmpty()) 0.0 else byMonth.sumOf { it.second } / byMonth.size
        }

    /** Latest positive balances across cash accounts — the emergency-fund base. */
    val liquidCash: Flow<Double> = accounts.map { accs ->
        accs.filter { it.type == AccountType.CASH }.sumOf { it.latestBalance.coerceAtLeast(0.0) }
    }

    /**
     * Total spending per month over the trailing [months] full months (current
     * partial month excluded). Transfers, card payments, and loan repayments are
     * excluded so purchases aren't double-counted against their payoffs.
     */
    fun monthlyExpenses(months: Int): Flow<List<Pair<YearMonth, Double>>> {
        val since = LocalDate.now().minusMonths(months + 1L).withDayOfMonth(1)
        val currentMonth = YearMonth.now()
        return db.transactionDao().expensesSince(since.toEpochDay()).map { txs ->
            txs.groupBy { YearMonth.from(LocalDate.ofEpochDay(it.epochDay)) }
                .filterKeys { it < currentMonth }
                .mapValues { (_, list) -> list.sumOf { -it.amount } }
                .toSortedMap()
                .toList()
                .takeLast(months)
        }
    }

    fun spendingByCategory(since: LocalDate): Flow<List<CategoryTotal>> =
        db.transactionDao().spendingByCategory(since.toEpochDay())

    val transactionCount: Flow<Int> = db.transactionDao().count()

    fun recentTransactions(limit: Int) = db.transactionDao().recent(limit)

    suspend fun setManualIncome(year: Int, amount: Double) =
        db.manualIncomeDao().upsert(ManualIncomeEntity(year, amount))

    suspend fun deleteManualIncome(year: Int) = db.manualIncomeDao().delete(year)

    suspend fun setCpiOverride(year: Int, value: Double) =
        db.cpiOverrideDao().upsert(CpiOverrideEntity(year, value))

    suspend fun deleteCpiOverride(year: Int) = db.cpiOverrideDao().delete(year)

    suspend fun setDebtAssumption(entity: DebtAssumptionEntity) =
        db.debtAssumptionDao().upsert(entity)

    suspend fun overrideAccountType(name: String, type: AccountType) =
        db.accountDao().overrideType(name, type.name)

    suspend fun wipeAll() {
        db.balanceDao().deleteAll()
        db.transactionDao().deleteAll()
        db.accountDao().deleteAll()
    }
}
