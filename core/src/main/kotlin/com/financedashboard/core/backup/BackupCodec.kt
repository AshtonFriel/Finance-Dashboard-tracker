package com.financedashboard.core.backup

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Versioned JSON backup of all on-device data. Now that the database is
 * SQLCipher-encrypted the user can no longer copy the raw file, so this is the
 * supported way to move or safeguard data. The DTOs are deliberately plain and
 * independent of the Room entities so the format stays stable across schema
 * tweaks.
 */
object BackupCodec {

    const val VERSION = 1

    @Serializable data class BalanceDto(val epochDay: Long, val balance: Double, val account: String)

    @Serializable data class TransactionDto(
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

    @Serializable data class AccountDto(val name: String, val type: String, val userOverridden: Boolean)
    @Serializable data class DebtAssumptionDto(val accountName: String, val aprPct: Double, val minPayment: Double, val includeInPlan: Boolean)
    @Serializable data class ManualIncomeDto(val year: Int, val grossAmount: Double)
    @Serializable data class CpiOverrideDto(val year: Int, val cpiIndex: Double)

    @Serializable
    data class Backup(
        val version: Int = VERSION,
        val exportedEpochMs: Long = 0,
        val balances: List<BalanceDto> = emptyList(),
        val transactions: List<TransactionDto> = emptyList(),
        val accounts: List<AccountDto> = emptyList(),
        val debtAssumptions: List<DebtAssumptionDto> = emptyList(),
        val manualIncome: List<ManualIncomeDto> = emptyList(),
        val cpiOverrides: List<CpiOverrideDto> = emptyList(),
    )

    // encodeDefaults so the version marker is always written (decode requires it).
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false; encodeDefaults = true }

    fun encode(backup: Backup): String = json.encodeToString(backup)

    /**
     * Returns null if the text isn't a recognizable backup rather than throwing.
     * Requires an explicit version marker so a random JSON file isn't silently
     * accepted as an empty backup (which would wipe data on a replace-restore).
     */
    fun decode(text: String): Backup? = try {
        if (!text.contains("\"version\"")) null
        else json.decodeFromString<Backup>(text).takeIf { it.version >= 1 }
    } catch (_: Exception) {
        null
    }
}
