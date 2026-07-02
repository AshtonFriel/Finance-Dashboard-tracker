package com.financedashboard.app.data

import android.content.Context
import android.net.Uri
import com.financedashboard.app.data.db.AppDatabase
import com.financedashboard.app.data.db.AccountEntity
import com.financedashboard.app.data.db.BalanceEntity
import com.financedashboard.app.data.db.TransactionEntity
import com.financedashboard.core.classify.AccountClassifier
import com.financedashboard.core.csv.BalancesCsvParser
import com.financedashboard.core.csv.TransactionsCsvParser
import java.io.BufferedReader
import java.io.InputStreamReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Imports Monarch-style CSV exports. Each import replaces the corresponding
 * table wholesale: exports are cumulative snapshots, and identical-looking rows
 * can be legitimate duplicates (two same-priced charges the same day), so
 * replace-all is the only dedupe that is always correct. User-entered account
 * type overrides, rate assumptions, and manual income entries are preserved.
 */
class CsvImporter(private val context: Context, private val db: AppDatabase) {

    sealed class Result {
        data class Balances(val rows: Int, val accounts: Int) : Result()
        data class Transactions(val rows: Int) : Result()
        data class Error(val message: String) : Result()
    }

    suspend fun importBalances(uri: Uri): Result = withContext(Dispatchers.IO) {
        try {
            val records = context.contentResolver.openInputStream(uri)?.use { stream ->
                BalancesCsvParser().parse(BufferedReader(InputStreamReader(stream)))
            } ?: return@withContext Result.Error("Could not open file")
            if (records.isEmpty()) return@withContext Result.Error("No balance rows found — is this a Balances export?")

            db.balanceDao().replaceAll(
                records.map { BalanceEntity(epochDay = it.date.toEpochDay(), balance = it.balance, accountName = it.account) }
            )

            // Upsert accounts with classification suggestions; keep user overrides.
            val byAccount = records.groupBy { it.account }
            for ((name, recs) in byAccount) {
                val latest = recs.maxBy { it.date }
                val suggested = AccountClassifier.classify(
                    accountName = name,
                    latestBalance = latest.balance,
                    everNegative = recs.any { it.balance < 0 },
                )
                db.accountDao().insertIgnore(AccountEntity(name = name, type = suggested.name))
                db.accountDao().updateSuggestedType(name, suggested.name)
            }
            Result.Balances(rows = records.size, accounts = byAccount.size)
        } catch (e: Exception) {
            Result.Error(e.message ?: "Import failed")
        }
    }

    suspend fun importTransactions(uri: Uri): Result = withContext(Dispatchers.IO) {
        try {
            val records = context.contentResolver.openInputStream(uri)?.use { stream ->
                TransactionsCsvParser().parse(BufferedReader(InputStreamReader(stream)))
            } ?: return@withContext Result.Error("Could not open file")
            if (records.isEmpty()) return@withContext Result.Error("No transaction rows found — is this a Transactions export?")

            db.transactionDao().replaceAll(
                records.map {
                    TransactionEntity(
                        epochDay = it.date.toEpochDay(),
                        merchant = it.merchant,
                        category = it.category,
                        account = it.account,
                        statement = it.statement,
                        notes = it.notes,
                        amount = it.amount,
                        tags = it.tags,
                        owner = it.owner,
                    )
                }
            )
            Result.Transactions(rows = records.size)
        } catch (e: Exception) {
            Result.Error(e.message ?: "Import failed")
        }
    }
}
