package com.financedashboard.app.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "accounts", indices = [Index(value = ["name"], unique = true)])
data class AccountEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    /** AccountType name; user-overridable after import. */
    val type: String,
    val userOverridden: Boolean = false,
)

@Entity(
    tableName = "balances",
    indices = [Index(value = ["accountName", "epochDay"])],
)
data class BalanceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val epochDay: Long,
    val balance: Double,
    val accountName: String,
)

@Entity(tableName = "transactions", indices = [Index(value = ["epochDay"]), Index(value = ["category"])])
data class TransactionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val epochDay: Long,
    val merchant: String,
    val category: String,
    val account: String,
    val statement: String,
    val notes: String,
    val amount: Double,
    val tags: String,
    val owner: String,
)

/** User-entered gross annual earnings (e.g. from an SSA statement). */
@Entity(tableName = "manual_income")
data class ManualIncomeEntity(
    @PrimaryKey val year: Int,
    val grossAmount: Double,
)

/** User override of a bundled CPI annual-average index value. */
@Entity(tableName = "cpi_overrides")
data class CpiOverrideEntity(
    @PrimaryKey val year: Int,
    val cpiIndex: Double,
)

/** Per-debt assumptions the CSV exports don't carry. */
@Entity(tableName = "debt_assumptions")
data class DebtAssumptionEntity(
    @PrimaryKey val accountName: String,
    val aprPct: Double,
    val minPayment: Double,
    val includeInPlan: Boolean = true,
)
