package com.financedashboard.core.classify

import com.financedashboard.core.model.AccountType

/**
 * Heuristic classification of imported accounts. Every result is a suggestion;
 * the UI lets the user override per account.
 */
object AccountClassifier {

    private val investmentHints = listOf(
        "401(k)", "401k", "ira", "roth", "brokerage", "crypto", "robo", "invest", "hsa", "529",
    )
    // Common lenders/servicers so unlabeled accounts classify sensibly.
    private val debtHints = listOf(
        "loan", "credit card", "card", "visa", "mastercard", "amex", "klarna", "affirm",
        "afterpay", "mortgage", "student", "aidvantage", "nelnet", "mohela", "navient",
    )
    private val cashHints = listOf("checking", "savings", "cash management", "money market", "apple cash")

    /**
     * @param latestBalance most recent imported balance for the account
     * @param everNegative whether any imported snapshot was negative
     */
    fun classify(accountName: String, latestBalance: Double, everNegative: Boolean): AccountType {
        val n = accountName.lowercase()
        if (investmentHints.any { n.contains(it) }) return AccountType.INVESTMENT
        if (debtHints.any { n.contains(it) }) return AccountType.DEBT
        if (cashHints.any { n.contains(it) }) return AccountType.CASH
        if (latestBalance < 0 || everNegative) return AccountType.DEBT
        if (latestBalance > 0) return AccountType.ASSET
        return AccountType.UNKNOWN
    }
}
