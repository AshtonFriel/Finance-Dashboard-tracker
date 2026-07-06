package com.financedashboard.core.engine

import java.time.LocalDate

/**
 * Short-horizon cash-flow forecast: starting from today's liquid balance, walk
 * forward day by day applying scheduled inflows (paychecks) and outflows
 * (recurring bills), so an upcoming low-balance dip is visible before it lands.
 *
 * The engine is pure — the caller expands recurring charges and expected
 * paychecks into dated [Event]s (see [recurringEvents]) and passes them in.
 */
object CashFlowForecastEngine {

    /** A dated cash movement. Positive = money in, negative = money out. */
    data class Event(val date: LocalDate, val amount: Double, val label: String)

    data class DayPoint(val date: LocalDate, val balance: Double)

    data class Forecast(
        val from: LocalDate,
        val startingBalance: Double,
        val points: List<DayPoint>,
        val endingBalance: Double,
        val minBalance: Double,
        val minDate: LocalDate,
        /** True if the projected balance ever falls below zero in the window. */
        val goesNegative: Boolean,
        val totalIn: Double,
        val totalOut: Double,
    )

    fun project(
        startingBalance: Double,
        events: List<Event>,
        from: LocalDate,
        days: Int,
    ): Forecast {
        val end = from.plusDays(days.toLong())
        val byDay = events.filter { !it.date.isBefore(from) && !it.date.isAfter(end) }.groupBy { it.date }
        var balance = startingBalance
        var minBalance = startingBalance
        var minDate = from
        var totalIn = 0.0
        var totalOut = 0.0
        val points = ArrayList<DayPoint>(days + 1)
        for (i in 0..days) {
            val d = from.plusDays(i.toLong())
            byDay[d]?.forEach {
                balance += it.amount
                if (it.amount >= 0) totalIn += it.amount else totalOut += -it.amount
            }
            points.add(DayPoint(d, balance))
            if (balance < minBalance) { minBalance = balance; minDate = d }
        }
        return Forecast(
            from = from,
            startingBalance = startingBalance,
            points = points,
            endingBalance = balance,
            minBalance = minBalance,
            minDate = minDate,
            goesNegative = minBalance < -0.005,
            totalIn = totalIn,
            totalOut = totalOut,
        )
    }

    /**
     * Expand a repeating charge (last seen on [lastDate], recurring every
     * [everyDays]) into dated events covering [from]..[from]+[days]. Occurrences
     * are projected forward from the last known charge so the phase matches the
     * real billing date.
     */
    fun recurringEvents(
        lastDate: LocalDate,
        everyDays: Int,
        amount: Double,
        label: String,
        from: LocalDate,
        days: Int,
    ): List<Event> {
        if (everyDays <= 0) return emptyList()
        val end = from.plusDays(days.toLong())
        var d = lastDate
        while (d.isBefore(from)) d = d.plusDays(everyDays.toLong())
        val out = mutableListOf<Event>()
        while (!d.isAfter(end)) {
            out.add(Event(d, amount, label))
            d = d.plusDays(everyDays.toLong())
        }
        return out
    }
}
