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
        @Volatile private var instanceProfile: String? = null

        fun get(context: Context): AppDatabase {
            val profile = com.financedashboard.app.data.ProfileManager.activeId(context.applicationContext)
            val existing = instance
            if (existing != null && instanceProfile == profile) return existing
            return synchronized(this) {
                if (instance != null && instanceProfile == profile) instance!!
                else {
                    instance?.close()
                    build(context.applicationContext, profile).also {
                        instance = it
                        instanceProfile = profile
                    }
                }
            }
        }

        /** Close and forget the current instance so the next get() opens the active profile fresh. */
        fun reset() = synchronized(this) {
            instance?.close()
            instance = null
            instanceProfile = null
        }

        /**
         * Opens the database, encrypting it when SQLCipher is usable. The core
         * invariant is that the *opener must match the file's actual state*: an
         * encrypted file is never opened plaintext (that throws "file is not a
         * database"), and a plaintext file is never opened with the cipher
         * factory. State is re-checked after every step so a partial migration
         * can't leave a mismatch.
         */
        private fun build(context: Context, profileId: String): AppDatabase {
            val dbName = com.financedashboard.app.data.ProfileManager.dbFileName(profileId)
            val dbFile = context.getDatabasePath(dbName)
            val passphrase = runCatching { DbCrypto.getOrCreatePassphrase(context, profileId) }.getOrNull()
            val cipherReady = passphrase != null && loadCipherLib()

            if (!cipherReady) {
                if (fileIsEncrypted(dbFile)) dbFile.delete()
                return openPlaintext(context, dbName)
            }
            if (DbCrypto.isPlaintextDb(dbFile)) {
                val migrated = runCatching { DbCrypto.migratePlaintext(context, dbFile, passphrase!!) }.getOrDefault(false)
                if (!migrated && DbCrypto.isPlaintextDb(dbFile)) return openPlaintext(context, dbName)
            }
            return openEncrypted(context, dbName, passphrase!!)
        }

        private fun loadCipherLib(): Boolean = runCatching { System.loadLibrary("sqlcipher") }.isSuccess

        private fun fileIsEncrypted(dbFile: java.io.File): Boolean =
            dbFile.exists() && !DbCrypto.isPlaintextDb(dbFile)

        private fun openPlaintext(context: Context, dbName: String): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, dbName)
                .fallbackToDestructiveMigration()
                .build()

        private fun openEncrypted(context: Context, dbName: String, passphrase: String): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, dbName)
                .openHelperFactory(net.zetetic.database.sqlcipher.SupportOpenHelperFactory(passphrase.toByteArray()))
                .fallbackToDestructiveMigration()
                .build()
    }
}
