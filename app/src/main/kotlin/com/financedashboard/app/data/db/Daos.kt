package com.financedashboard.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

data class LatestBalance(
    val accountName: String,
    val epochDay: Long,
    val balance: Double,
)

@Dao
interface AccountDao {
    @Query("SELECT * FROM accounts ORDER BY name")
    fun all(): Flow<List<AccountEntity>>

    @Query("SELECT * FROM accounts WHERE name = :name LIMIT 1")
    suspend fun byName(name: String): AccountEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(account: AccountEntity): Long

    @Query("UPDATE accounts SET type = :type, userOverridden = 1 WHERE name = :name")
    suspend fun overrideType(name: String, type: String)

    @Query("UPDATE accounts SET type = :type WHERE name = :name AND userOverridden = 0")
    suspend fun updateSuggestedType(name: String, type: String)

    @Query("DELETE FROM accounts")
    suspend fun deleteAll()
}

@Dao
interface BalanceDao {
    @Query("SELECT * FROM balances")
    fun all(): Flow<List<BalanceEntity>>

    @Query(
        """
        SELECT b.accountName AS accountName, b.epochDay AS epochDay, b.balance AS balance
        FROM balances b
        JOIN (SELECT accountName, MAX(epochDay) AS maxDay FROM balances GROUP BY accountName) m
          ON b.accountName = m.accountName AND b.epochDay = m.maxDay
        """
    )
    fun latestPerAccount(): Flow<List<LatestBalance>>

    @Query("SELECT * FROM balances WHERE accountName = :account ORDER BY epochDay")
    fun forAccount(account: String): Flow<List<BalanceEntity>>

    @Insert
    suspend fun insertAll(balances: List<BalanceEntity>)

    @Query("DELETE FROM balances")
    suspend fun deleteAll()

    @Transaction
    suspend fun replaceAll(balances: List<BalanceEntity>) {
        deleteAll()
        balances.chunked(2000).forEach { insertAll(it) }
    }
}

@Dao
interface TransactionDao {
    @Query("SELECT * FROM transactions ORDER BY epochDay DESC LIMIT :limit")
    fun recent(limit: Int): Flow<List<TransactionEntity>>

    @Query("SELECT * FROM transactions WHERE category IN (:categories) AND amount > 0")
    fun incomeTransactions(categories: List<String>): Flow<List<TransactionEntity>>

    @Query(
        """
        SELECT category, SUM(-amount) AS total FROM transactions
        WHERE epochDay >= :sinceEpochDay AND amount < 0
          AND category NOT IN ('Transfer', 'Credit Card Payment', 'Loan Repayment')
        GROUP BY category ORDER BY total DESC
        """
    )
    fun spendingByCategory(sinceEpochDay: Long): Flow<List<CategoryTotal>>

    @Query("SELECT COUNT(*) FROM transactions")
    fun count(): Flow<Int>

    @Insert
    suspend fun insertAll(transactions: List<TransactionEntity>)

    @Query("DELETE FROM transactions")
    suspend fun deleteAll()

    @Transaction
    suspend fun replaceAll(transactions: List<TransactionEntity>) {
        deleteAll()
        transactions.chunked(2000).forEach { insertAll(it) }
    }
}

data class CategoryTotal(val category: String, val total: Double)

@Dao
interface ManualIncomeDao {
    @Query("SELECT * FROM manual_income ORDER BY year")
    fun all(): Flow<List<ManualIncomeEntity>>

    @Upsert
    suspend fun upsert(entry: ManualIncomeEntity)

    @Query("DELETE FROM manual_income WHERE year = :year")
    suspend fun delete(year: Int)
}

@Dao
interface CpiOverrideDao {
    @Query("SELECT * FROM cpi_overrides ORDER BY year")
    fun all(): Flow<List<CpiOverrideEntity>>

    @Upsert
    suspend fun upsert(entry: CpiOverrideEntity)

    @Query("DELETE FROM cpi_overrides WHERE year = :year")
    suspend fun delete(year: Int)
}

@Dao
interface DebtAssumptionDao {
    @Query("SELECT * FROM debt_assumptions")
    fun all(): Flow<List<DebtAssumptionEntity>>

    @Upsert
    suspend fun upsert(entry: DebtAssumptionEntity)
}
