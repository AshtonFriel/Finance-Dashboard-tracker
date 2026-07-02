package com.financedashboard.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        AccountEntity::class,
        BalanceEntity::class,
        TransactionEntity::class,
        ManualIncomeEntity::class,
        CpiOverrideEntity::class,
        DebtAssumptionEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun accountDao(): AccountDao
    abstract fun balanceDao(): BalanceDao
    abstract fun transactionDao(): TransactionDao
    abstract fun manualIncomeDao(): ManualIncomeDao
    abstract fun cpiOverrideDao(): CpiOverrideDao
    abstract fun debtAssumptionDao(): DebtAssumptionDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "finance-dashboard.db",
                ).fallbackToDestructiveMigration().build().also { instance = it }
            }
    }
}
