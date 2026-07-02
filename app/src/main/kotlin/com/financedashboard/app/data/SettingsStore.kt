package com.financedashboard.app.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "settings")

/** Small persisted preferences that don't belong in the relational store. */
class SettingsStore(private val context: Context) {

    private object Keys {
        val efTargetMonths = intPreferencesKey("ef_target_months")
        val efMonthlySaving = doublePreferencesKey("ef_monthly_saving")
        val efFirstInPayoff = booleanPreferencesKey("ef_first_in_payoff")
    }

    val efTargetMonths: Flow<Int> = context.dataStore.data.map { it[Keys.efTargetMonths] ?: 6 }
    val efMonthlySaving: Flow<Double> = context.dataStore.data.map { it[Keys.efMonthlySaving] ?: 500.0 }
    val efFirstInPayoff: Flow<Boolean> = context.dataStore.data.map { it[Keys.efFirstInPayoff] ?: false }

    suspend fun setEfTargetMonths(v: Int) = context.dataStore.edit { it[Keys.efTargetMonths] = v }
    suspend fun setEfMonthlySaving(v: Double) = context.dataStore.edit { it[Keys.efMonthlySaving] = v }
    suspend fun setEfFirstInPayoff(v: Boolean) = context.dataStore.edit { it[Keys.efFirstInPayoff] = v }
}
