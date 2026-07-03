package com.financedashboard.app.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "settings")

/**
 * Persisted user inputs: emergency-fund preferences and every scenario slider
 * or toggle, so plans survive app restarts.
 */
class SettingsStore(private val context: Context) {

    private object Keys {
        val efTargetMonths = intPreferencesKey("ef_target_months")
        val efMonthlySaving = doublePreferencesKey("ef_monthly_saving")
        val efFirstInPayoff = booleanPreferencesKey("ef_first_in_payoff")
        val extraMonthly = doublePreferencesKey("debt_extra_monthly")
        val strategy = stringPreferencesKey("debt_strategy")
        val monthlyContribution = doublePreferencesKey("invest_monthly_contribution")
        val expectedReturnPct = doublePreferencesKey("invest_expected_return")
        val horizonYears = intPreferencesKey("invest_horizon_years")
        val assumedInflationPct = doublePreferencesKey("invest_assumed_inflation")
        val showReal = booleanPreferencesKey("invest_show_real")
        val redirectDebtBudget = booleanPreferencesKey("invest_redirect_debt_budget")
        val extraGrowthPct = doublePreferencesKey("debt_extra_growth_pct")
        val lumpSums = stringPreferencesKey("debt_lump_sums")
        val customOrder = stringPreferencesKey("debt_custom_order")
        val stressEnabled = booleanPreferencesKey("invest_stress_enabled")
        val stressRatePct = doublePreferencesKey("invest_stress_rate_pct")
        val goals = stringPreferencesKey("invest_goals")
        val categoryInflation = stringPreferencesKey("inflation_category_rates")
        val futureRaisePct = doublePreferencesKey("inflation_future_raise_pct")
        val futureInflationPct = doublePreferencesKey("inflation_future_cpi_pct")
        val biometricLock = booleanPreferencesKey("security_biometric_lock")
        val scenarios = stringPreferencesKey("debt_saved_scenarios")
        val notificationsEnabled = booleanPreferencesKey("notify_enabled")
        val notifiedPayoffPct = intPreferencesKey("notify_payoff_pct")
        val notifiedEfFunded = booleanPreferencesKey("notify_ef_funded")
        val notifiedNetWorthHigh = doublePreferencesKey("notify_networth_high")
    }

    // Record separator / field separator for serialized lists (never appear in user text).
    private val RS = '\u001E'
    private val FS = '\u001F'

    val efTargetMonths: Flow<Int> = context.dataStore.data.map { it[Keys.efTargetMonths] ?: 6 }
    val efMonthlySaving: Flow<Double> = context.dataStore.data.map { it[Keys.efMonthlySaving] ?: 500.0 }
    val efFirstInPayoff: Flow<Boolean> = context.dataStore.data.map { it[Keys.efFirstInPayoff] ?: false }
    val extraMonthly: Flow<Double> = context.dataStore.data.map { it[Keys.extraMonthly] ?: 0.0 }
    val strategy: Flow<String> = context.dataStore.data.map { it[Keys.strategy] ?: "AVALANCHE" }
    val monthlyContribution: Flow<Double> = context.dataStore.data.map { it[Keys.monthlyContribution] ?: 500.0 }
    val expectedReturnPct: Flow<Double> = context.dataStore.data.map { it[Keys.expectedReturnPct] ?: 7.0 }
    val horizonYears: Flow<Int> = context.dataStore.data.map { it[Keys.horizonYears] ?: 20 }
    val assumedInflationPct: Flow<Double> = context.dataStore.data.map { it[Keys.assumedInflationPct] ?: 2.7 }
    val showReal: Flow<Boolean> = context.dataStore.data.map { it[Keys.showReal] ?: false }
    val redirectDebtBudget: Flow<Boolean> = context.dataStore.data.map { it[Keys.redirectDebtBudget] ?: false }

    suspend fun setEfTargetMonths(v: Int) = context.dataStore.edit { it[Keys.efTargetMonths] = v }
    suspend fun setEfMonthlySaving(v: Double) = context.dataStore.edit { it[Keys.efMonthlySaving] = v }
    suspend fun setEfFirstInPayoff(v: Boolean) = context.dataStore.edit { it[Keys.efFirstInPayoff] = v }
    suspend fun setExtraMonthly(v: Double) = context.dataStore.edit { it[Keys.extraMonthly] = v }
    suspend fun setStrategy(v: String) = context.dataStore.edit { it[Keys.strategy] = v }
    suspend fun setMonthlyContribution(v: Double) = context.dataStore.edit { it[Keys.monthlyContribution] = v }
    suspend fun setExpectedReturnPct(v: Double) = context.dataStore.edit { it[Keys.expectedReturnPct] = v }
    suspend fun setHorizonYears(v: Int) = context.dataStore.edit { it[Keys.horizonYears] = v }
    suspend fun setAssumedInflationPct(v: Double) = context.dataStore.edit { it[Keys.assumedInflationPct] = v }
    suspend fun setShowReal(v: Boolean) = context.dataStore.edit { it[Keys.showReal] = v }
    suspend fun setRedirectDebtBudget(v: Boolean) = context.dataStore.edit { it[Keys.redirectDebtBudget] = v }

    // ---- Extra growth, lump sums, custom order ----
    val extraGrowthPct: Flow<Double> = context.dataStore.data.map { it[Keys.extraGrowthPct] ?: 0.0 }
    suspend fun setExtraGrowthPct(v: Double) = context.dataStore.edit { it[Keys.extraGrowthPct] = v }

    /** Map of YearMonth -> amount. */
    val lumpSums: Flow<Map<java.time.YearMonth, Double>> = context.dataStore.data.map { prefs ->
        (prefs[Keys.lumpSums] ?: "").split(RS).filter { it.isNotBlank() }.mapNotNull { rec ->
            val parts = rec.split(FS)
            val month = runCatching { java.time.YearMonth.parse(parts[0]) }.getOrNull()
            val amount = parts.getOrNull(1)?.toDoubleOrNull()
            if (month != null && amount != null) month to amount else null
        }.toMap()
    }

    suspend fun setLumpSums(v: Map<java.time.YearMonth, Double>) = context.dataStore.edit { prefs ->
        prefs[Keys.lumpSums] = v.entries.joinToString(RS.toString()) { "${it.key}$FS${it.value}" }
    }

    val customOrder: Flow<List<String>> = context.dataStore.data.map { prefs ->
        (prefs[Keys.customOrder] ?: "").split(RS).filter { it.isNotBlank() }
    }

    suspend fun setCustomOrder(v: List<String>) = context.dataStore.edit { prefs ->
        prefs[Keys.customOrder] = v.joinToString(RS.toString())
    }

    // ---- Stress test ----
    val stressEnabled: Flow<Boolean> = context.dataStore.data.map { it[Keys.stressEnabled] ?: false }
    val stressRatePct: Flow<Double> = context.dataStore.data.map { it[Keys.stressRatePct] ?: 2.0 }
    suspend fun setStressEnabled(v: Boolean) = context.dataStore.edit { it[Keys.stressEnabled] = v }
    suspend fun setStressRatePct(v: Double) = context.dataStore.edit { it[Keys.stressRatePct] = v }

    // ---- Investment goals ----
    data class Goal(val name: String, val target: Double, val years: Int)

    val goals: Flow<List<Goal>> = context.dataStore.data.map { prefs ->
        (prefs[Keys.goals] ?: "").split(RS).filter { it.isNotBlank() }.mapNotNull { rec ->
            val p = rec.split(FS)
            val target = p.getOrNull(1)?.toDoubleOrNull()
            val years = p.getOrNull(2)?.toIntOrNull()
            if (p[0].isNotBlank() && target != null && years != null) Goal(p[0], target, years) else null
        }
    }

    suspend fun setGoals(v: List<Goal>) = context.dataStore.edit { prefs ->
        prefs[Keys.goals] = v.joinToString(RS.toString()) { "${it.name}$FS${it.target}$FS${it.years}" }
    }

    // ---- Personal (category) inflation rates ----
    val categoryInflation: Flow<Map<String, Double>> = context.dataStore.data.map { prefs ->
        (prefs[Keys.categoryInflation] ?: "").split(RS).filter { it.isNotBlank() }.mapNotNull { rec ->
            val p = rec.split(FS)
            val rate = p.getOrNull(1)?.toDoubleOrNull()
            if (p[0].isNotBlank() && rate != null) p[0] to rate else null
        }.toMap()
    }

    suspend fun setCategoryInflation(v: Map<String, Double>) = context.dataStore.edit { prefs ->
        prefs[Keys.categoryInflation] = v.entries.joinToString(RS.toString()) { "${it.key}$FS${it.value}" }
    }

    // ---- Forward-looking projection ----
    val futureRaisePct: Flow<Double> = context.dataStore.data.map { it[Keys.futureRaisePct] ?: 3.0 }
    val futureInflationPct: Flow<Double> = context.dataStore.data.map { it[Keys.futureInflationPct] ?: 2.7 }
    suspend fun setFutureRaisePct(v: Double) = context.dataStore.edit { it[Keys.futureRaisePct] = v }
    suspend fun setFutureInflationPct(v: Double) = context.dataStore.edit { it[Keys.futureInflationPct] = v }

    // ---- Security ----
    val biometricLock: Flow<Boolean> = context.dataStore.data.map { it[Keys.biometricLock] ?: false }
    suspend fun setBiometricLock(v: Boolean) = context.dataStore.edit { it[Keys.biometricLock] = v }

    // ---- Saved debt scenarios ----
    data class SavedScenario(
        val name: String,
        val strategy: String,
        val extra: Double,
        val growthPct: Double,
        val lumpSums: Map<java.time.YearMonth, Double>,
    )

    val scenarios: Flow<List<SavedScenario>> = context.dataStore.data.map { prefs ->
        (prefs[Keys.scenarios] ?: "").split(RS).filter { it.isNotBlank() }.mapNotNull { rec ->
            // name|strategy|extra|growth|month:amt;month:amt
            val p = rec.split(FS)
            if (p.size < 4) return@mapNotNull null
            val extra = p[2].toDoubleOrNull() ?: return@mapNotNull null
            val growth = p[3].toDoubleOrNull() ?: 0.0
            val lumps = p.getOrNull(4).orEmpty().split(";").filter { it.isNotBlank() }.mapNotNull { l ->
                val kv = l.split(":")
                val m = runCatching { java.time.YearMonth.parse(kv[0]) }.getOrNull()
                val amt = kv.getOrNull(1)?.toDoubleOrNull()
                if (m != null && amt != null) m to amt else null
            }.toMap()
            SavedScenario(p[0], p[1], extra, growth, lumps)
        }
    }

    suspend fun setScenarios(v: List<SavedScenario>) = context.dataStore.edit { prefs ->
        prefs[Keys.scenarios] = v.joinToString(RS.toString()) { s ->
            val lumps = s.lumpSums.entries.joinToString(";") { "${it.key}:${it.value}" }
            listOf(s.name, s.strategy, s.extra.toString(), s.growthPct.toString(), lumps).joinToString(FS.toString())
        }
    }

    // ---- Notifications ----
    val notificationsEnabled: Flow<Boolean> = context.dataStore.data.map { it[Keys.notificationsEnabled] ?: false }
    suspend fun setNotificationsEnabled(v: Boolean) = context.dataStore.edit { it[Keys.notificationsEnabled] = v }

    suspend fun notificationsEnabledNow(): Boolean = notificationsEnabled.first()

    val notifiedPayoffPct: Flow<Int> = context.dataStore.data.map { it[Keys.notifiedPayoffPct] ?: 0 }
    suspend fun setNotifiedPayoffPct(v: Int) = context.dataStore.edit { it[Keys.notifiedPayoffPct] = v }
    suspend fun getNotifiedPayoffPct(): Int = notifiedPayoffPct.first()

    suspend fun getNotifiedEfFunded(): Boolean =
        context.dataStore.data.map { it[Keys.notifiedEfFunded] ?: false }.first()
    suspend fun setNotifiedEfFunded(v: Boolean) = context.dataStore.edit { it[Keys.notifiedEfFunded] = v }

    suspend fun getNotifiedNetWorthHigh(): Double =
        context.dataStore.data.map { it[Keys.notifiedNetWorthHigh] ?: 0.0 }.first()
    suspend fun setNotifiedNetWorthHigh(v: Double) = context.dataStore.edit { it[Keys.notifiedNetWorthHigh] = v }
}
