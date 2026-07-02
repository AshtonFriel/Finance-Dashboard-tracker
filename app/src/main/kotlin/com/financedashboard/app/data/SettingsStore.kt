package com.financedashboard.app.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
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
    }

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
}
