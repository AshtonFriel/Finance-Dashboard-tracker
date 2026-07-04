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
         * Opens the database, encrypting it when SQLCipher is usable. The core
         * invariant is that the *opener must match the file's actual state*: an
         * encrypted file is never opened plaintext (that throws "file is not a
         * database"), and a plaintext file is never opened with the cipher
         * factory. State is re-checked after every step so a partial migration
         * can't leave a mismatch.
         */
        private fun build(context: Context): AppDatabase {
            val dbFile = context.getDatabasePath(DB_NAME)
            val passphrase = runCatching { DbCrypto.getOrCreatePassphrase(context) }.getOrNull()
            val cipherReady = passphrase != null && loadCipherLib()

            if (!cipherReady) {
                // Can't encrypt. Only safe if the existing file is plaintext or absent.
                if (fileIsEncrypted(dbFile)) {
                    // Encrypted file but no working cipher: nothing safe to do but
                    // recreate. Surfaced via the crash recorder if it ever happens.
                    dbFile.delete()
                }
                return openPlaintext(context)
            }

            // Cipher is ready. Migrate a pre-existing plaintext file once.
            if (DbCrypto.isPlaintextDb(dbFile)) {
                val migrated = runCatching { DbCrypto.migratePlaintext(context, dbFile, passphrase!!) }.getOrDefault(false)
                if (!migrated && DbCrypto.isPlaintextDb(dbFile)) {
                    // Migration failed but left the file plaintext: keep using it unencrypted.
                    return openPlaintext(context)
                }
            }
            // File is absent or encrypted — open with the cipher factory.
            return openEncrypted(context, passphrase!!)
        }

        private fun loadCipherLib(): Boolean = runCatching { System.loadLibrary("sqlcipher") }.isSuccess

        private fun fileIsEncrypted(dbFile: java.io.File): Boolean =
            dbFile.exists() && !DbCrypto.isPlaintextDb(dbFile)

        private fun openPlaintext(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, DB_NAME)
                .fallbackToDestructiveMigration()
                .build()

        private fun openEncrypted(context: Context, passphrase: String): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, DB_NAME)
                .openHelperFactory(net.zetetic.database.sqlcipher.SupportOpenHelperFactory(passphrase.toByteArray()))
                .fallbackToDestructiveMigration()
                .build()
    }
}
