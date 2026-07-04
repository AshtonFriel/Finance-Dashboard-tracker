package com.financedashboard.app.data

import com.financedashboard.app.data.db.AppDatabase
import com.financedashboard.app.data.db.CategoryTotal
import com.financedashboard.app.data.db.CpiOverrideEntity
import com.financedashboard.app.data.db.DebtAssumptionEntity
import com.financedashboard.app.data.db.ManualIncomeEntity
import com.financedashboard.core.backup.BackupCodec
import com.financedashboard.core.backup.BackupCodec.Backup
import com.financedashboard.core.engine.IncomeAggregator
import com.financedashboard.core.engine.InflationEngine
import com.financedashboard.core.model.AccountType
import java.time.LocalDate
import java.time.YearMonth
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
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

/** Categories that aren't real discretionary spending (transfers, debt payments, reconciliations). */
val NON_SPENDING_CATEGORIES = setOf(
    "Transfer", "Credit Card Payment", "Loan Repayment",
    "Balance Adjustments", "Balance Adjustment", "Adjustment",
)

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

    /** Discretionary spending in the current calendar month so far. */
    val currentMonthSpending: Flow<Double> =
        db.transactionDao().expensesSince(LocalDate.now().withDayOfMonth(1).toEpochDay()).map { rows ->
            rows.sumOf { -it.amount }
        }

    val transactionCount: Flow<Int> = db.transactionDao().count()

    fun recentTransactions(limit: Int) = db.transactionDao().recent(limit)

    fun searchTransactions(query: String, category: String, limit: Int) =
        db.transactionDao().search(query, category, limit)

    /** Recurring charges (subscriptions, memberships, regular bills). */
    val recurringCharges: Flow<List<com.financedashboard.core.engine.RecurringDetector.Subscription>> =
        db.transactionDao().allExpenses().map { rows ->
            com.financedashboard.core.engine.RecurringDetector.detect(
                rows.map { it.toRecord() }, LocalDate.now(),
            )
        }

    /** Top month-over-month category changes (current full month vs the prior). */
    data class CategoryMove(val category: String, val current: Double, val previous: Double) {
        val delta: Double get() = current - previous
    }

    val topMovers: Flow<List<CategoryMove>> =
        db.transactionDao().expensesSince(LocalDate.now().minusMonths(3).withDayOfMonth(1).toEpochDay())
            .map { rows ->
                val currentMonth = YearMonth.now().minusMonths(1) // last full month
                val prevMonth = currentMonth.minusMonths(1)
                fun totals(m: YearMonth) = rows
                    .filter { YearMonth.from(LocalDate.ofEpochDay(it.epochDay)) == m }
                    .groupBy { it.category }
                    .mapValues { (_, txs) -> txs.sumOf { -it.amount } }
                val cur = totals(currentMonth)
                val prev = totals(prevMonth)
                (cur.keys + prev.keys).map { CategoryMove(it, cur[it] ?: 0.0, prev[it] ?: 0.0) }
                    .filter { kotlin.math.abs(it.delta) > 1.0 }
                    .sortedByDescending { kotlin.math.abs(it.delta) }
                    .take(5)
            }

    /** Inferred minimum payments for the active debts, keyed by account name. */
    val inferredPayments: Flow<Map<String, com.financedashboard.core.engine.PaymentInference.InferredPayment>> =
        combine(debtExtraction, db.transactionDao().allExpenses()) { extraction, expenses ->
            com.financedashboard.core.engine.PaymentInference.inferMonthlyPayments(
                extraction.active.map { it.accountName } + extraction.needsReview.map { it.candidate.accountName },
                expenses.map { it.toRecord() },
            ).associateBy { it.debtAccountName }
        }

    /** Account-name → type classifier reflecting user overrides, for engines that need it. */
    val accountTypeOf: Flow<(String) -> com.financedashboard.core.model.AccountType> = accounts.map { list ->
        val byName = list.associate { it.name to it.type }
        val fn: (String) -> com.financedashboard.core.model.AccountType =
            { byName[it] ?: com.financedashboard.core.model.AccountType.UNKNOWN }
        fn
    }

    /** Full balance + transaction records for engines (import health, attribution, crypto). */
    val allBalanceRecords: Flow<List<com.financedashboard.core.model.BalanceRecord>> =
        db.balanceDao().all().map { rows ->
            rows.map { com.financedashboard.core.model.BalanceRecord(LocalDate.ofEpochDay(it.epochDay), it.balance, it.accountName) }
        }

    val allTransactionRecords: Flow<List<com.financedashboard.core.model.TransactionRecord>> =
        db.transactionDao().allFlow().map { it.map { e -> e.toRecord() } }

    /** Distinct transaction owners (for the household split). */
    val owners: Flow<List<String>> = db.transactionDao().allExpenses().map { rows ->
        rows.map { it.owner }.filter { it.isNotBlank() }.distinct().sorted()
    }

    /** Spending by category filtered to one owner (empty = all owners). */
    fun spendingByOwner(owner: String, since: LocalDate): Flow<List<CategoryTotal>> =
        db.transactionDao().expensesSince(since.toEpochDay()).map { rows ->
            rows.filter { owner.isBlank() || it.owner == owner }
                .groupBy { it.category }
                .map { (c, txs) -> CategoryTotal(c, txs.sumOf { -it.amount }) }
                .filter { it.total > 0 }
                .sortedByDescending { it.total }
        }

    private fun com.financedashboard.app.data.db.TransactionEntity.toRecord() =
        com.financedashboard.core.model.TransactionRecord(
            LocalDate.ofEpochDay(epochDay), merchant, category, account, statement, notes, amount, tags, owner,
        )

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

    /** Applies imported rate entries to the matching debts; returns how many matched. */
    suspend fun applyRateEntries(
        entries: List<com.financedashboard.core.csv.RatesCsvParser.RateEntry>,
    ): Int {
        val parser = com.financedashboard.core.csv.RatesCsvParser()
        val debtNames = debtExtraction.first().let { ex ->
            ex.active.map { it.accountName } + ex.needsReview.map { it.candidate.accountName }
        }
        val existing = db.debtAssumptionDao().all().first().associateBy { it.accountName }
        var applied = 0
        for (entry in entries) {
            val account = parser.matchToAccount(entry, debtNames) ?: continue
            val prior = existing[account]
            db.debtAssumptionDao().upsert(
                com.financedashboard.app.data.db.DebtAssumptionEntity(
                    accountName = account,
                    aprPct = entry.aprPct,
                    minPayment = entry.minPayment ?: prior?.minPayment ?: 0.0,
                    includeInPlan = prior?.includeInPlan ?: true,
                )
            )
            applied++
        }
        return applied
    }

    // ---- Backup / restore ----
    suspend fun buildBackup(): Backup {
        return Backup(
            exportedEpochMs = System.currentTimeMillis(),
            balances = db.balanceDao().allOnce().map { BackupCodec.BalanceDto(it.epochDay, it.balance, it.accountName) },
            transactions = db.transactionDao().allOnce().map {
                BackupCodec.TransactionDto(it.epochDay, it.merchant, it.category, it.account, it.statement, it.notes, it.amount, it.tags, it.owner)
            },
            accounts = db.accountDao().all().first().map { BackupCodec.AccountDto(it.name, it.type, it.userOverridden) },
            debtAssumptions = db.debtAssumptionDao().all().first().map {
                BackupCodec.DebtAssumptionDto(it.accountName, it.aprPct, it.minPayment, it.includeInPlan)
            },
            manualIncome = db.manualIncomeDao().all().first().map { BackupCodec.ManualIncomeDto(it.year, it.grossAmount) },
            cpiOverrides = db.cpiOverrideDao().all().first().map { BackupCodec.CpiOverrideDto(it.year, it.cpiIndex) },
        )
    }

    /** A wide monthly analytics table (month × derived metric) for spreadsheet use. */
    suspend fun analyticsRows(): Pair<List<String>, List<List<String>>> {
        val nw = com.financedashboard.core.engine.NetWorthAggregator.monthlySeries(allBalanceRecords.first())
        val expenses = db.transactionDao().allFlow().first()
        val incomeCats = IncomeAggregator.PAYCHECK_CATEGORIES
        fun monthKey(epochDay: Long) = YearMonth.from(LocalDate.ofEpochDay(epochDay))
        val spendByMonth = expenses.filter {
            it.amount < 0 && it.category !in NON_SPENDING_CATEGORIES
        }.groupBy { monthKey(it.epochDay) }.mapValues { (_, t) -> t.sumOf { -it.amount } }
        val incomeByMonth = expenses.filter { it.category in incomeCats && it.amount > 0 }
            .groupBy { monthKey(it.epochDay) }.mapValues { (_, t) -> t.sumOf { it.amount } }

        val header = listOf("Month", "Assets", "Debts", "NetWorth", "Income", "Spending", "NetCashFlow", "SavingsRatePct")
        val rows = nw.map { m ->
            val income = incomeByMonth[m.month] ?: 0.0
            val spend = spendByMonth[m.month] ?: 0.0
            val savings = if (income > 0) ((income - spend) / income * 100) else 0.0
            listOf(
                m.month.toString(),
                "%.2f".format(m.assets), "%.2f".format(m.debts), "%.2f".format(m.net),
                "%.2f".format(income), "%.2f".format(spend), "%.2f".format(income - spend),
                "%.1f".format(savings),
            )
        }
        return header to rows
    }

    suspend fun restoreBackup(backup: Backup) {
        db.balanceDao().replaceAll(
            backup.balances.map {
                com.financedashboard.app.data.db.BalanceEntity(epochDay = it.epochDay, balance = it.balance, accountName = it.account)
            }
        )
        db.transactionDao().replaceAll(
            backup.transactions.map {
                com.financedashboard.app.data.db.TransactionEntity(
                    epochDay = it.epochDay, merchant = it.merchant, category = it.category, account = it.account,
                    statement = it.statement, notes = it.notes, amount = it.amount, tags = it.tags, owner = it.owner,
                )
            }
        )
        db.accountDao().deleteAll()
        for (a in backup.accounts) {
            db.accountDao().insertIgnore(
                com.financedashboard.app.data.db.AccountEntity(name = a.name, type = a.type, userOverridden = a.userOverridden)
            )
            if (a.userOverridden) db.accountDao().overrideType(a.name, a.type)
        }
        for (d in backup.debtAssumptions) {
            db.debtAssumptionDao().upsert(
                com.financedashboard.app.data.db.DebtAssumptionEntity(d.accountName, d.aprPct, d.minPayment, d.includeInPlan)
            )
        }
        for (m in backup.manualIncome) db.manualIncomeDao().upsert(
            com.financedashboard.app.data.db.ManualIncomeEntity(m.year, m.grossAmount)
        )
        for (c in backup.cpiOverrides) db.cpiOverrideDao().upsert(
            com.financedashboard.app.data.db.CpiOverrideEntity(c.year, c.cpiIndex)
        )
    }
}
