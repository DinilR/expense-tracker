package lk.expensetracker.engine.insights

import lk.expensetracker.engine.classification.LedgerMath
import lk.expensetracker.engine.model.LedgerEntry
import lk.expensetracker.engine.normalize.Money
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import kotlin.math.roundToInt
import kotlin.math.sqrt

data class InsightContext(
    val entries: List<LedgerEntry>,
    val now: Long,
    val zone: ZoneId,
    val currency: String = "LKR",
)

enum class InsightType { FORECAST, SAVINGS_RATE, CATEGORY_CHANGE, RECURRING, SPIKE, TOP_MERCHANT, AI_NARRATIVE }

enum class Severity { INFO, GOOD, WARNING }

data class Insight(
    val type: InsightType,
    val title: String,
    val detail: String,
    val severity: Severity = Severity.INFO,
    val amountMinor: Long? = null,
    val relatedIds: List<Long> = emptyList(),
    val source: String = "statistical",
)

/** Produces insights from the ledger. The statistical provider works offline; AI providers plug in later. */
interface InsightProvider {
    val id: String
    suspend fun generate(ctx: InsightContext): List<Insight>
}

/** An AI provider that can also answer free-form questions about the user's money. */
interface ConversationalInsightProvider : InsightProvider {
    suspend fun ask(question: String, ctx: InsightContext): String
}

data class MonthReport(
    val month: YearMonth,
    val totals: LedgerMath.Totals,
    /** Expense totals per category, largest first. */
    val byCategory: List<Pair<String, Long>>,
    /** Income totals per category, largest first. */
    val incomeByCategory: List<Pair<String, Long>>,
    /** Expense per day of month; index 0 is day 1. */
    val dailyExpense: LongArray,
    val topMerchants: List<Pair<String, Long>>,
    /** Projected month-end spending when [month] is the current month, otherwise null. */
    val forecastExpenseMinor: Long?,
)

object Reports {
    fun monthOf(epochMillis: Long, zone: ZoneId): YearMonth = YearMonth.from(Instant.ofEpochMilli(epochMillis).atZone(zone))

    fun entriesIn(entries: List<LedgerEntry>, month: YearMonth, zone: ZoneId): List<LedgerEntry> =
        entries.filter { monthOf(it.occurredAt, zone) == month }

    fun month(entries: List<LedgerEntry>, month: YearMonth, zone: ZoneId, now: Long): MonthReport {
        val inMonth = entriesIn(entries, month, zone)
        val expenses = inMonth.filter(LedgerMath::countsAsExpense)
        val income = inMonth.filter(LedgerMath::countsAsIncome)
        val daily = LongArray(month.lengthOfMonth())
        expenses.forEach { e ->
            val day = Instant.ofEpochMilli(e.occurredAt).atZone(zone).dayOfMonth
            daily[day - 1] += e.amountMinor
        }
        val totals = LedgerMath.totals(inMonth)
        val nowDate = Instant.ofEpochMilli(now).atZone(zone)
        val forecast = if (YearMonth.from(nowDate) == month && nowDate.dayOfMonth > 0) {
            totals.expenseMinor * month.lengthOfMonth() / nowDate.dayOfMonth
        } else null
        return MonthReport(
            month = month,
            totals = totals,
            byCategory = expenses.groupBy { it.category ?: "Other" }.mapValues { (_, v) -> v.sumOf { it.amountMinor } }
                .toList().sortedByDescending { it.second },
            incomeByCategory = income.groupBy { it.category ?: "Other income" }.mapValues { (_, v) -> v.sumOf { it.amountMinor } }
                .toList().sortedByDescending { it.second },
            dailyExpense = daily,
            topMerchants = expenses.filter { it.merchantKey != null }.groupBy { it.merchantKey!! }
                .mapValues { (_, v) -> v.sumOf { it.amountMinor } }.toList().sortedByDescending { it.second }.take(5),
            forecastExpenseMinor = forecast,
        )
    }
}

/** Offline insights computed from the numbers alone. */
class StatisticalInsightProvider : InsightProvider {
    override val id = "statistical"

    override suspend fun generate(ctx: InsightContext): List<Insight> = generateNow(ctx)

    fun generateNow(ctx: InsightContext): List<Insight> {
        val cur = Reports.monthOf(ctx.now, ctx.zone)
        val report = Reports.month(ctx.entries, cur, ctx.zone, ctx.now)
        val previous = (1..3).map { Reports.month(ctx.entries, cur.minusMonths(it.toLong()), ctx.zone, ctx.now) }
        val c = ctx.currency
        val out = mutableListOf<Insight>()

        report.forecastExpenseMinor?.takeIf { it > 0 }?.let { forecast ->
            val last = previous.first().totals.expenseMinor
            val severity = if (last > 0 && forecast > last * 11 / 10) Severity.WARNING else Severity.INFO
            out += Insight(
                InsightType.FORECAST,
                "On pace to spend $c ${Money.format(forecast)} this month",
                "Spent $c ${Money.format(report.totals.expenseMinor)} so far. Last month: $c ${Money.format(last)}.",
                severity, forecast,
            )
        }

        report.totals.savingsRate?.let { rate ->
            val pct = (rate * 100).roundToInt()
            out += if (rate >= 0) Insight(
                InsightType.SAVINGS_RATE, "You kept $pct% of this month's income",
                "Income $c ${Money.format(report.totals.incomeMinor)}, spending $c ${Money.format(report.totals.expenseMinor)}.",
                if (rate >= 0.2) Severity.GOOD else Severity.INFO,
            ) else Insight(
                InsightType.SAVINGS_RATE, "Spending is ${-pct}% above income this month",
                "Income $c ${Money.format(report.totals.incomeMinor)}, spending $c ${Money.format(report.totals.expenseMinor)}.",
                Severity.WARNING,
            )
        }

        out += categoryChanges(ctx, report, previous)
        out += recurring(ctx, cur)
        out += spikes(ctx)

        report.topMerchants.firstOrNull()?.let { (merchant, amount) ->
            out += Insight(InsightType.TOP_MERCHANT, "Top merchant this month: $merchant", "$c ${Money.format(amount)} spent at $merchant.", Severity.INFO, amount)
        }
        return out
    }

    private fun categoryChanges(ctx: InsightContext, report: MonthReport, previous: List<MonthReport>): List<Insight> {
        val nowDate = Instant.ofEpochMilli(ctx.now).atZone(ctx.zone)
        val elapsed = nowDate.dayOfMonth.toDouble() / report.month.lengthOfMonth()
        if (elapsed < 0.25) return emptyList() // too early in the month to project
        val minDiff = 200_000L // LKR 2,000
        return report.byCategory.mapNotNull { (category, spent) ->
            val avg = previous.sumOf { p -> p.byCategory.firstOrNull { it.first == category }?.second ?: 0L } / previous.size
            val projected = (spent / elapsed).toLong()
            when {
                avg > 0 && projected > avg * 5 / 4 && projected - avg > minDiff -> Insight(
                    InsightType.CATEGORY_CHANGE,
                    "$category is heading ${((projected - avg) * 100 / avg)}% above your 3-month average",
                    "Projected ${ctx.currency} ${Money.format(projected)} vs average ${ctx.currency} ${Money.format(avg)}.",
                    Severity.WARNING, projected,
                )
                avg > minDiff && projected < avg * 3 / 4 -> Insight(
                    InsightType.CATEGORY_CHANGE,
                    "$category is ${((avg - projected) * 100 / avg)}% below your 3-month average",
                    "Projected ${ctx.currency} ${Money.format(projected)} vs average ${ctx.currency} ${Money.format(avg)}.",
                    Severity.GOOD, projected,
                )
                else -> null
            }
        }.take(4)
    }

    private fun recurring(ctx: InsightContext, cur: YearMonth): List<Insight> {
        val months = (1..4).map { cur.minusMonths(it.toLong()) }.toSet()
        val expenses = ctx.entries.filter(LedgerMath::countsAsExpense).filter { it.merchantKey != null }
        return expenses.groupBy { it.merchantKey!! }
            .mapNotNull { (key, list) ->
                val byMonth = list.groupBy { Reports.monthOf(it.occurredAt, ctx.zone) }.filterKeys { it in months }
                if (byMonth.size < 3) return@mapNotNull null
                val monthly = byMonth.values.map { v -> v.sumOf { it.amountMinor } }.average().toLong()
                key to monthly
            }
            .sortedByDescending { it.second }
            .take(5)
            .map { (key, monthly) ->
                Insight(InsightType.RECURRING, "Recurring: $key", "About ${ctx.currency} ${Money.format(monthly)} per month over the last 4 months.", Severity.INFO, monthly)
            }
    }

    private fun spikes(ctx: InsightContext): List<Insight> {
        val day = 24L * 60 * 60 * 1000
        val window = ctx.entries.filter(LedgerMath::countsAsExpense).filter { ctx.now - it.occurredAt in 0..90 * day }
        if (window.size < 10) return emptyList()
        val amounts = window.map { it.amountMinor.toDouble() }
        val mean = amounts.average()
        val sd = sqrt(amounts.sumOf { (it - mean) * (it - mean) } / amounts.size)
        return window.filter { ctx.now - it.occurredAt <= 30 * day && it.amountMinor > mean + 3 * sd && it.amountMinor > 1_000_000L }
            .sortedByDescending { it.amountMinor }
            .take(3)
            .map {
                Insight(
                    InsightType.SPIKE, "Unusually large payment: ${it.merchantKey ?: it.category ?: "transaction"}",
                    "${ctx.currency} ${Money.format(it.amountMinor)} is far above your typical payment of ${ctx.currency} ${Money.format(mean.toLong())}.",
                    Severity.WARNING, it.amountMinor, listOf(it.id),
                )
            }
    }
}
