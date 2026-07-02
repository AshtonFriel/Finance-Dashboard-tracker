package com.financedashboard.core.classify

import com.financedashboard.core.model.BalanceRecord
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.max

/**
 * Identifies active debts from balance snapshots using strict rules:
 *
 * 1. Only the most recent balance row per account counts — snapshots are never
 *    summed and never treated as separate accounts.
 * 2. Accounts whose latest balance is >= 0 are paid off / closed: excluded.
 * 3. Accounts with no balance row in the last [staleDays] days (relative to the
 *    newest date in the dataset) are closed or refinanced away: excluded.
 * 4. Suspected duplicates (same masked last-4, same institution token, or two
 *    loans with near-identical balances) are never both included. The account
 *    with the most recent snapshot stays; the other is flagged for user review
 *    instead of being silently counted or dropped.
 * 5. Credit cards are flagged for review rather than auto-included: a statement
 *    balance snapshot cannot distinguish revolving debt from a card that is
 *    paid in full monthly. The user opts them in.
 */
object DebtExtractor {

    const val DEFAULT_STALE_DAYS = 60L
    private const val NEAR_BALANCE_TOLERANCE = 0.03

    data class Candidate(
        val accountName: String,
        val last4: String?,
        val institution: String?,
        val latestDate: LocalDate,
        /** Negative = amount owed. */
        val balance: Double,
    )

    enum class ExclusionReason { PAID_OFF, STALE }
    enum class ReviewReason { SUSPECTED_DUPLICATE, CARD_STATEMENT_BALANCE }

    data class Excluded(val candidate: Candidate, val reason: ExclusionReason)
    data class NeedsReview(
        val candidate: Candidate,
        val reason: ReviewReason,
        /** For duplicates: the account that was kept. */
        val duplicateOf: String? = null,
    )

    data class Extraction(
        val asOf: LocalDate,
        /** Confirmed-active debts, deduplicated. */
        val active: List<Candidate>,
        /** Suspects the user must confirm — never auto-counted. */
        val needsReview: List<NeedsReview>,
        val excluded: List<Excluded>,
    )

    private val last4Regex = Regex("""\(\.{3}(\w{2,4})\)""")
    private val cardHints = listOf("card", "visa", "mastercard", "amex", "express")
    private val genericTokens = setOf(
        "auto", "loan", "loans", "personal", "student", "credit", "card", "the",
        "bank", "account", "plan", "savings", "-", "–", "—",
    )

    fun last4Of(name: String): String? = last4Regex.find(name)?.groupValues?.get(1)

    /** First non-generic, non-numeric token — a rough institution/brand key. */
    fun institutionOf(name: String): String? =
        name.lowercase()
            .replace(last4Regex, " ")
            .split(Regex("[\\s(),.]+"))
            .firstOrNull { it.isNotBlank() && it !in genericTokens && !it.all { c -> c.isDigit() } }

    private fun isCard(name: String): Boolean {
        val n = name.lowercase()
        // "Auto Loan", "Personal Loan" etc. are loans even though masks look similar.
        if (n.contains("loan")) return false
        return cardHints.any { n.contains(it) }
    }

    private fun nearBalance(a: Double, b: Double): Boolean =
        abs(a - b) / max(abs(a), abs(b)).coerceAtLeast(1e-9) <= NEAR_BALANCE_TOLERANCE

    private fun suspectedDuplicates(a: Candidate, b: Candidate): Boolean = when {
        a.last4 != null && b.last4 != null ->
            // Distinct masks are distinct accounts unless balances are near-identical
            // (a refinanced/relinked loan keeps its balance but changes its mask).
            a.last4 == b.last4 || nearBalance(a.balance, b.balance)
        a.institution != null && a.institution == b.institution -> true
        else -> nearBalance(a.balance, b.balance)
    }

    fun extract(
        balances: List<BalanceRecord>,
        staleDays: Long = DEFAULT_STALE_DAYS,
    ): Extraction {
        if (balances.isEmpty()) return Extraction(LocalDate.MIN, emptyList(), emptyList(), emptyList())
        val asOf = balances.maxOf { it.date }
        val staleBefore = asOf.minusDays(staleDays)

        // Rule 1: single latest row per account.
        val latestPerAccount = balances.groupBy { it.account }
            .map { (name, rows) ->
                val latest = rows.maxBy { it.date }
                Candidate(
                    accountName = name,
                    last4 = last4Of(name),
                    institution = institutionOf(name),
                    latestDate = latest.date,
                    balance = latest.balance,
                )
            }

        val excluded = mutableListOf<Excluded>()
        val review = mutableListOf<NeedsReview>()

        // Rules 2 & 3: drop paid-off and stale liabilities.
        val liabilities = latestPerAccount.filter { it.balance < -0.005 }
        val fresh = liabilities.filter { c ->
            when {
                c.latestDate.isBefore(staleBefore) -> { excluded.add(Excluded(c, ExclusionReason.STALE)); false }
                else -> true
            }
        }
        // Any account that ever carried a negative balance but is now >= 0 is paid off.
        latestPerAccount.filter { it.balance >= -0.005 }
            .filter { balances.any { b -> b.account == it.accountName && b.balance < -0.005 } }
            .forEach { excluded.add(Excluded(it, ExclusionReason.PAID_OFF)) }

        // Rule 5: cards go to review, loans continue to dedup.
        val (cards, loans) = fresh.partition { isCard(it.accountName) }
        cards.forEach { review.add(NeedsReview(it, ReviewReason.CARD_STATEMENT_BALANCE)) }

        // Rule 4: aggressive dedup — keep the fresher snapshot, flag the other.
        val active = mutableListOf<Candidate>()
        for (c in loans.sortedWith(compareByDescending<Candidate> { it.latestDate }.thenByDescending { it.last4 != null })) {
            val dupOf = active.firstOrNull { suspectedDuplicates(it, c) }
            if (dupOf != null) {
                review.add(NeedsReview(c, ReviewReason.SUSPECTED_DUPLICATE, duplicateOf = dupOf.accountName))
            } else {
                active.add(c)
            }
        }

        return Extraction(
            asOf = asOf,
            active = active.sortedByDescending { -it.balance },
            needsReview = review,
            excluded = excluded,
        )
    }
}
