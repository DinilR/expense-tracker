package lk.expensetracker.engine

import lk.expensetracker.engine.insights.Period
import lk.expensetracker.engine.insights.PeriodReports
import lk.expensetracker.engine.insights.PeriodType
import lk.expensetracker.engine.model.Channel
import lk.expensetracker.engine.model.Direction
import lk.expensetracker.engine.model.Kind
import lk.expensetracker.engine.model.LedgerEntry
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PeriodTest {
    private val zone = ZoneId.of("Asia/Colombo")
    private fun t(s: String) = LocalDateTime.parse(s).atZone(zone).toInstant().toEpochMilli()
    private fun spend(id: Long, amount: Long, at: String, category: String = "Groceries", dir: Direction = Direction.DEBIT) =
        LedgerEntry(id, "sampath", "1234", amount, dir, if (dir == Direction.DEBIT) Kind.EXPENSE else Kind.INCOME, Channel.POS, category, null, t(at))

    @Test
    fun weeksStartOnMondayAndCrossMonthAndYearEnds() {
        val week = Period.Week(LocalDate.parse("2026-11-07")) // a Saturday
        assertEquals(LocalDate.parse("2026-11-02"), week.startDate)
        assertEquals(LocalDate.parse("2026-11-09"), week.endDateExclusive)
        assertEquals("2 – 8 Nov 2026", week.label)
        assertEquals(Period.Week(LocalDate.parse("2026-11-02")), week, "any day of the week gives the same week")

        val newYear = Period.Week(LocalDate.parse("2027-01-01"))
        assertEquals(LocalDate.parse("2026-12-28"), newYear.startDate)
        assertEquals("28 Dec 2026 – 3 Jan 2027", newYear.label)
        assertEquals(LocalDate.parse("2026-12-21"), newYear.previous().startDate)
    }

    @Test
    fun dayBoundariesFollowTheLocalTimezone() {
        val day = Period.Day(LocalDate.parse("2026-11-07"))
        assertTrue(day.contains(t("2026-11-07T00:00:00"), zone))
        assertTrue(day.contains(t("2026-11-07T23:59:59"), zone))
        assertFalse(day.contains(t("2026-11-08T00:00:00"), zone))
        assertEquals("Sat, 7 Nov 2026", day.label)
    }

    @Test
    fun monthReportHasADayBarPerDayAndComparesWithLastMonth() {
        val entries = listOf(
            spend(1, 100_000, "2026-10-10T10:00:00"),
            spend(2, 150_000, "2026-11-02T10:00:00"),
            spend(3, 50_000, "2026-11-05T18:00:00", "Transport"),
            spend(4, 1_000_000, "2026-11-01T09:00:00", "Salary", Direction.CREDIT),
        )
        val r = PeriodReports.build(entries, Period.Month(YearMonth.of(2026, 11)), zone, t("2026-11-10T12:00:00"))
        assertEquals(30, r.buckets.size)
        assertEquals(150_000, r.buckets[1].expenseMinor)
        assertEquals(1_000_000, r.buckets[0].incomeMinor)
        assertTrue(r.buckets[9].isToday)
        assertEquals(200_000, r.totals.expenseMinor)
        assertEquals(1.0, r.expenseChange!!, 1e-9, "200k vs 100k last month = +100%")
        assertEquals(20_000, r.dailyAverageMinor, "200k over 10 elapsed days")
        assertEquals(600_000, r.forecastExpenseMinor, "20k/day over 30 days")
        assertEquals(listOf("Groceries", "Transport"), r.byCategory.map { it.category })
        assertEquals(0.75f, r.byCategory[0].share)
        assertEquals(2L, r.largestExpense?.id)
    }

    @Test
    fun weekReportLabelsDaysAndDayReportHasNoChart() {
        val entries = listOf(spend(1, 10_000, "2026-11-04T10:00:00"))
        val week = PeriodReports.build(entries, Period.of(PeriodType.WEEK, LocalDate.parse("2026-11-04")), zone, t("2026-11-20T12:00:00"))
        assertEquals(listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"), week.buckets.map { it.label })
        assertEquals(10_000, week.buckets[2].expenseMinor)
        assertNull(week.forecastExpenseMinor, "a finished week has no forecast")

        val day = PeriodReports.build(entries, Period.Day(LocalDate.parse("2026-11-04")), zone, t("2026-11-04T12:00:00"))
        assertTrue(day.buckets.isEmpty())
        assertEquals(10_000, day.totals.expenseMinor)
        assertEquals(1, day.expenseCount)
    }
}
