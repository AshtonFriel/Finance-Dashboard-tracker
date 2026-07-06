package com.financedashboard.core.engine

/**
 * Per-category monthly budgets: compares spend-so-far this month against a
 * user-set limit and projects where the month will land at the current pace.
 * Pure and deterministic; the caller supplies the day-of-month so "on pace"
 * can be judged partway through the month.
 */
object BudgetEngine {

    data class CategoryBudget(val category: String, val limit: Double)

    enum class Status { UNDER, NEAR, OVER }

    data class Line(
        val category: String,
        val limit: Double,
        val spent: Double,
        /** limit - spent; negative once over budget. */
        val remaining: Double,
        /** spent / limit (0..∞). */
        val fractionUsed: Double,
        /** Spend extrapolated to the full month at the current daily pace. */
        val projectedSpend: Double,
        val status: Status,
    )

    data class Summary(
        val lines: List<Line>,
        val totalLimit: Double,
        val totalSpent: Double,
        val overCount: Int,
        val nearCount: Int,
    )

    /**
     * @param spentByCategory positive spend totals for the current month
     * @param dayOfMonth today's day (1-based); used for pace projection
     * @param daysInMonth length of the current month
     * @param nearThreshold fraction of the limit that counts as "close" (default 90%)
     */
    fun evaluate(
        budgets: List<CategoryBudget>,
        spentByCategory: Map<String, Double>,
        dayOfMonth: Int,
        daysInMonth: Int,
        nearThreshold: Double = 0.9,
    ): Summary {
        val lines = budgets
            .filter { it.limit > 0.005 }
            .map { b ->
                val spent = (spentByCategory[b.category] ?: 0.0).coerceAtLeast(0.0)
                val frac = spent / b.limit
                val projected = if (dayOfMonth in 1 until daysInMonth) spent / dayOfMonth * daysInMonth else spent
                val status = when {
                    spent > b.limit + 0.005 -> Status.OVER
                    frac >= nearThreshold || projected > b.limit + 0.005 -> Status.NEAR
                    else -> Status.UNDER
                }
                Line(
                    category = b.category,
                    limit = b.limit,
                    spent = spent,
                    remaining = b.limit - spent,
                    fractionUsed = frac,
                    projectedSpend = projected,
                    status = status,
                )
            }
            .sortedByDescending { it.fractionUsed }
        return Summary(
            lines = lines,
            totalLimit = lines.sumOf { it.limit },
            totalSpent = lines.sumOf { it.spent },
            overCount = lines.count { it.status == Status.OVER },
            nearCount = lines.count { it.status == Status.NEAR },
        )
    }
}
