package lk.expensetracker.engine.insights

import lk.expensetracker.engine.classification.LedgerMath
import lk.expensetracker.engine.model.LedgerEntry
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.TemporalAdjusters
import java.util.Locale

enum class PeriodType { DAY, WEEK, MONTH }

/** A calendar period in the user's timezone. Weeks start on Monday. */
sealed interface Period {
    val type: PeriodType
    /** First day of the period. */
    val startDate: LocalDate
    /** Day after the last day of the period (exclusive). */
    val endDateExclusive: LocalDate
    val label: String

    fun previous(): Period
    fun next(): Period

    fun startMillis(zone: ZoneId): Long = startDate.atStartOfDay(zone).toInstant().toEpochMilli()
    fun endMillis(zone: ZoneId): Long = endDateExclusive.atStartOfDay(zone).toInstant().toEpochMilli()
    fun contains(epochMillis: Long, zone: ZoneId): Boolean = epochMillis >= startMillis(zone) && epochMillis < endMillis(zone)
    fun contains(date: LocalDate): Boolean = !date.isBefore(startDate) && date.isBefore(endDateExclusive)
    val days: Int get() = (endDateExclusive.toEpochDay() - startDate.toEpochDay()).toInt()

    data class Day(val date: LocalDate) : Period {
        override val type = PeriodType.DAY
        override val startDate: LocalDate get() = date
        override val endDateExclusive: LocalDate get() = date.plusDays(1)
        override val label: String get() = date.format(DateTimeFormatter.ofPattern("EEE, d MMM yyyy", Locale.ENGLISH))
        override fun previous() = Day(date.minusDays(1))
        override fun next() = Day(date.plusDays(1))
    }

    /** Any day of the week can be passed; [monday] is the Monday it belongs to. */
    class Week(anyDay: LocalDate) : Period {
        val monday: LocalDate = anyDay.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        override val type = PeriodType.WEEK
        override val startDate: LocalDate get() = monday
        override val endDateExclusive: LocalDate get() = monday.plusDays(7)
        override val label: String
            get() {
                val sunday = monday.plusDays(6)
                val startFmt = if (monday.month == sunday.month) "d" else if (monday.year == sunday.year) "d MMM" else "d MMM yyyy"
                return "${monday.format(DateTimeFormatter.ofPattern(startFmt, Locale.ENGLISH))} – " +
                    sunday.format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH))
            }
        override fun previous() = Week(monday.minusWeeks(1))
        override fun next() = Week(monday.plusWeeks(1))
        override fun equals(other: Any?) = other is Week && other.monday == monday
        override fun hashCode() = monday.hashCode()
        override fun toString() = "Week($monday)"
    }

    data class Month(val month: YearMonth) : Period {
        override val type = PeriodType.MONTH
        override val startDate: LocalDate get() = month.atDay(1)
        override val endDateExclusive: LocalDate get() = month.plusMonths(1).atDay(1)
        override val label: String get() = month.format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH))
        override fun previous() = Month(month.minusMonths(1))
        override fun next() = Month(month.plusMonths(1))
    }

    companion object {
        fun of(type: PeriodType, date: LocalDate): Period = when (type) {
            PeriodType.DAY -> Day(date)
            PeriodType.WEEK -> Week(date)
            PeriodType.MONTH -> Month(YearMonth.from(date))
        }
    }
}

/** One bar in a period chart. [period] is the day it represents, for drilling down. */
data class ChartBucket(val label: String, val expenseMinor: Long, val incomeMinor: Long, val period: Period, val isToday: Boolean)

data class CategoryShare(val category: String, val amountMinor: Long, val count: Int, val share: Float)

data class PeriodReport(
    val period: Period,
    val totals: LedgerMath.Totals,
    val previousTotals: LedgerMath.Totals,
    val expenseCount: Int,
    val incomeCount: Int,
    val byCategory: List<CategoryShare>,
    val incomeByCategory: List<CategoryShare>,
    /** Day bars for week and month periods; empty for a single day. */
    val buckets: List<ChartBucket>,
    val largestExpense: LedgerEntry?,
    /** Average spending per day over the elapsed part of the period. */
    val dailyAverageMinor: Long,
    /** Projected spending at period end, only while the period is in progress. */
    val forecastExpenseMinor: Long?,
) {
    /** Change in spending versus the previous period as a fraction (0.12 = 12% more), or null without a baseline. */
    val expenseChange: Double?
        get() = previousTotals.expenseMinor.takeIf { it > 0 }?.let { (totals.expenseMinor - it).toDouble() / it }
}

object PeriodReports {

    fun build(entries: List<LedgerEntry>, period: Period, zone: ZoneId, now: Long): PeriodReport {
        val inPeriod = entries.filter { period.contains(it.occurredAt, zone) }
        val prev = period.previous()
        val expenses = inPeriod.filter(LedgerMath::countsAsExpense)
        val income = inPeriod.filter(LedgerMath::countsAsIncome)
        val totals = LedgerMath.totals(inPeriod)
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()

        val buckets = if (period.type == PeriodType.DAY) emptyList() else {
            (0 until period.days).map { offset ->
                val date = period.startDate.plusDays(offset.toLong())
                val day = Period.Day(date)
                val label = when (period.type) {
                    PeriodType.WEEK -> date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)
                    else -> date.dayOfMonth.toString()
                }
                ChartBucket(
                    label = label,
                    expenseMinor = expenses.filter { day.contains(it.occurredAt, zone) }.sumOf { it.amountMinor },
                    incomeMinor = income.filter { day.contains(it.occurredAt, zone) }.sumOf { it.amountMinor },
                    period = day,
                    isToday = date == today,
                )
            }
        }

        val elapsedDays = when {
            period.contains(today) -> (today.toEpochDay() - period.startDate.toEpochDay() + 1).toInt()
            today.isBefore(period.startDate) -> 0
            else -> period.days
        }
        val inProgress = period.contains(today) && period.type != PeriodType.DAY
        return PeriodReport(
            period = period,
            totals = totals,
            previousTotals = LedgerMath.totals(entries.filter { prev.contains(it.occurredAt, zone) }),
            expenseCount = expenses.size,
            incomeCount = income.size,
            byCategory = shares(expenses, totals.expenseMinor) { it.category ?: "Other" },
            incomeByCategory = shares(income, totals.incomeMinor) { it.category ?: "Other income" },
            buckets = buckets,
            largestExpense = expenses.maxByOrNull { it.amountMinor },
            dailyAverageMinor = if (elapsedDays > 0) totals.expenseMinor / elapsedDays else 0L,
            forecastExpenseMinor = if (inProgress && totals.expenseMinor > 0) totals.expenseMinor * period.days / elapsedDays else null,
        )
    }

    private fun shares(list: List<LedgerEntry>, total: Long, key: (LedgerEntry) -> String): List<CategoryShare> =
        list.groupBy(key).map { (category, items) ->
            val sum = items.sumOf { it.amountMinor }
            CategoryShare(category, sum, items.size, if (total > 0) sum.toFloat() / total else 0f)
        }.sortedByDescending { it.amountMinor }
}
