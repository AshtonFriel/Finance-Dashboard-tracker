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
        private const val DB_NAME = "finance-dashboard.db"

        @Volatile private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: build(context.applicationContext).also { instance = it }
            }

        /**
         * Prefers a SQLCipher-encrypted database (migrating any pre-existing
         * plaintext file once). Every failure path falls back to the plaintext
         * database so user data is never lost to an encryption problem.
         */
        private fun build(context: Context): AppDatabase {
            val encrypted = tryBuildEncrypted(context)
            if (encrypted != null) return encrypted
            return Room.databaseBuilder(context, AppDatabase::class.java, DB_NAME)
                .fallbackToDestructiveMigration()
                .build()
        }

        private fun tryBuildEncrypted(context: Context): AppDatabase? = try {
            val passphrase = DbCrypto.getOrCreatePassphrase(context)
            val dbFile = context.getDatabasePath(DB_NAME)
            when {
                passphrase == null -> null
                DbCrypto.isPlaintextDb(dbFile) && !DbCrypto.migratePlaintext(context, dbFile, passphrase) -> null
                else -> buildEncrypted(context, passphrase)
            }
        } catch (_: Throwable) {
            null
        }

        private fun buildEncrypted(context: Context, passphrase: String): AppDatabase {
            System.loadLibrary("sqlcipher")
            val db = Room.databaseBuilder(context, AppDatabase::class.java, DB_NAME)
                .openHelperFactory(
                    net.zetetic.database.sqlcipher.SupportOpenHelperFactory(passphrase.toByteArray())
                )
                .fallbackToDestructiveMigration()
                .build()
            db.openHelper.writableDatabase // force open so failures surface here
            return db
        }
    }
}
