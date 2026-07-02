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
     * Month-end assets and debts across all accounts, carrying balances forward
     * across gaps. Balance sign, not account type, decides the side: a debt
     * account paid to zero contributes nothing; an overpaid card is an asset.
     */
    val monthlyNetWorth: Flow<List<MonthlyNetWorth>> =
        db.balanceDao().all().map { balances ->
            if (balances.isEmpty()) return@map emptyList()
            // Last snapshot per (account, month).
            val perAccountMonth = balances.groupBy { it.accountName to YearMonth.from(LocalDate.ofEpochDay(it.epochDay)) }
                .mapValues { (_, rows) -> rows.maxBy { it.epochDay }.balance }

            val months = perAccountMonth.keys.map { it.second }.distinct().sorted()
            val accountNames = perAccountMonth.keys.map { it.first }.distinct()
            val lastKnown = mutableMapOf<String, Double>()
            months.map { m ->
                var assets = 0.0
                var debts = 0.0
                for (name in accountNames) {
                    perAccountMonth[name to m]?.let { lastKnown[name] = it }
                    val bal = lastKnown[name] ?: continue
                    if (bal >= 0) assets += bal else debts += -bal
                }
                MonthlyNetWorth(m, assets, debts)
            }
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
