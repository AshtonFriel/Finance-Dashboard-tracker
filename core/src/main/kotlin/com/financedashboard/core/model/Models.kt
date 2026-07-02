package com.financedashboard.core.model

import java.time.LocalDate

enum class AccountType { CASH, DEBT, INVESTMENT, ASSET, UNKNOWN }

data class BalanceRecord(
    val date: LocalDate,
    val balance: Double,
    val account: String,
)

data class TransactionRecord(
    val date: LocalDate,
    val merchant: String,
    val category: String,
    val account: String,
    val statement: String,
    val notes: String,
    val amount: Double,
    val tags: String,
    val owner: String,
)

/** A debt as fed to the amortization engine. Rates are user-supplied (CSV exports carry none). */
data class Debt(
    val name: String,
    val balance: Double,
    val annualRatePct: Double,
    val minPayment: Double,
)

data class InvestmentHolding(
    val name: String,
    val balance: Double,
    val monthlyContribution: Double,
)
