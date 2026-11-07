package lk.expensetracker.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import lk.expensetracker.data.db.TransactionEntity
import lk.expensetracker.data.db.displayName
import lk.expensetracker.engine.insights.CategoryShare
import lk.expensetracker.engine.insights.PeriodReport
import lk.expensetracker.engine.insights.PeriodType
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun DashboardScreen(
    vm: MainViewModel,
    onOpen: (Long) -> Unit,
    onReview: () -> Unit,
    onCategory: (String) -> Unit,
    onSeeAll: () -> Unit,
) {
    val period by vm.period.collectAsState()
    val report by vm.periodReport.collectAsState()
    val transactions by vm.transactions.collectAsState()
    val insights by vm.insights.collectAsState()
    val reviewCount by vm.reviewCount.collectAsState()
    val importing by vm.importing.collectAsState()
    val inPeriod = remember(transactions, period) { transactions.filter { period.contains(it.occurredAt, vm.zone) } }
    val byId = remember(transactions) { transactions.associateBy { it.id } }

    // Swipe left / right to move to the next / previous period.
    val swipe = Modifier.pointerInput(period) {
        var dragged = 0f
        detectHorizontalDragGestures(
            onDragStart = { dragged = 0f },
            onDragEnd = { if (dragged > 150f) vm.shiftPeriod(-1) else if (dragged < -150f) vm.shiftPeriod(1) },
        ) { _, dx -> dragged += dx }
    }

    LazyColumn(Modifier.fillMaxSize().then(swipe)) {
        item { PeriodSelector(vm, period) }
        if (importing) item {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                Text("Reading your bank messages…", style = MaterialTheme.typography.bodySmall)
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }
        val r = report ?: return@LazyColumn
        if (r.period != period) return@LazyColumn

        item { SummaryCard(r, vm.isCurrentPeriod(period)) }
        if (reviewCount > 0) item {
            Card(
                Modifier.fillMaxWidth().padding(16.dp, 4.dp).clickable(onClick = onReview),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
            ) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (reviewCount == 1) "1 item needs a quick check" else "$reviewCount items need a quick check", Modifier.weight(1f))
                    Text("Review", color = MaterialTheme.colorScheme.onTertiaryContainer, fontWeight = FontWeight.SemiBold)
                }
            }
        }

        if (period.type == PeriodType.DAY) {
            item { SectionTitle(if (inPeriod.isEmpty()) "No transactions on this day" else "${inPeriod.size} transaction${if (inPeriod.size == 1) "" else "s"}") }
            items(inPeriod, key = { it.id }) { t ->
                TransactionRow(t, vm.zone) { onOpen(t.id) }
                HorizontalDivider(Modifier.padding(start = 68.dp))
            }
            return@LazyColumn
        }

        item { StoryCard(r, byId, vm.isCurrentPeriod(period)) }
        if (r.buckets.any { it.expenseMinor > 0 }) item { SpendingChart(r) { day -> vm.openDay(day) } }

        item { SectionTitle("Where your money went", subtitle = if (r.byCategory.isEmpty()) null else "Tap a category to see its payments") }
        if (r.byCategory.isEmpty()) item {
            Text("No spending recorded.", Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        items(r.byCategory, key = { "e-" + it.category }) { CategoryRow(it, ExpenseColor) { onCategory(it.category) } }

        if (r.incomeByCategory.isNotEmpty()) {
            item { SectionTitle("Money in") }
            items(r.incomeByCategory, key = { "i-" + it.category }) { CategoryRow(it, IncomeColor) { onCategory(it.category) } }
        }

        if (vm.isCurrentPeriod(period) && insights.isNotEmpty()) {
            item { SectionTitle("Worth knowing") }
            items(insights.take(3)) { InsightCard(it) }
        }

        if (inPeriod.isNotEmpty()) {
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    SectionTitle("Latest", Modifier.weight(1f))
                    TextButton(onClick = onSeeAll, modifier = Modifier.padding(end = 8.dp)) { Text("See all ${inPeriod.size}") }
                }
            }
            items(inPeriod.take(5), key = { it.id }) { t -> TransactionRow(t, vm.zone, showDate = true) { onOpen(t.id) } }
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
private fun SummaryCard(r: PeriodReport, isCurrent: Boolean) {
    val noun = Fmt.periodNoun(r.period.type)
    val heading = when {
        r.period.type == PeriodType.DAY -> if (isCurrent) "Spent today" else "Spent on this day"
        isCurrent -> "Spent this $noun"
        else -> "Spent in this $noun"
    }
    Card(
        Modifier.fillMaxWidth().padding(16.dp, 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(Modifier.padding(20.dp)) {
            Text(heading, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
            Text(Fmt.rounded(r.totals.expenseMinor), style = AmountStyle, color = MaterialTheme.colorScheme.onPrimaryContainer)
            if (r.period.type != PeriodType.DAY) {
                val change = r.expenseChange
                val (text, color) = when {
                    change == null && r.totals.expenseMinor > 0 -> "No spending recorded last $noun to compare with" to MaterialTheme.colorScheme.onPrimaryContainer
                    change == null -> "No spending yet" to MaterialTheme.colorScheme.onPrimaryContainer
                    change > 0.005 -> "▲ ${Fmt.percent(change)} more than last $noun" to ExpenseColor
                    change < -0.005 -> "▼ ${Fmt.percent(change)} less than last $noun" to IncomeColor
                    else -> "About the same as last $noun" to MaterialTheme.colorScheme.onPrimaryContainer
                }
                Text(text, color = color, fontWeight = FontWeight.Medium)
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Metric("Money in", Fmt.rounded(r.totals.incomeMinor), IncomeColor, Modifier.weight(1f))
                if (r.period.type == PeriodType.DAY) {
                    Metric("Money out", Fmt.rounded(r.totals.expenseMinor), ExpenseColor, Modifier.weight(1f))
                } else {
                    val net = r.totals.netMinor
                    val label = when {
                        net >= 0 -> "Saved"
                        r.totals.incomeMinor == 0L -> "Net"
                        else -> "Overspent"
                    }
                    val value = if (label == "Net") "−" + Fmt.rounded(-net) else Fmt.rounded(kotlin.math.abs(net))
                    Metric(label, value, if (net >= 0) IncomeColor else ExpenseColor, Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun Metric(label: String, value: String, color: Color, modifier: Modifier) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
        Text(value, style = MaterialTheme.typography.titleMedium, color = color)
    }
}

/** A few plain sentences describing the period. */
@Composable
private fun StoryCard(r: PeriodReport, byId: Map<Long, TransactionEntity>, isCurrent: Boolean) {
    val noun = Fmt.periodNoun(r.period.type)
    val lines = buildList {
        if (r.expenseCount == 0) {
            add(if (isCurrent) "No spending recorded this $noun yet." else "No spending recorded in this $noun.")
        } else {
            add("You made ${r.expenseCount} payment${if (r.expenseCount == 1) "" else "s"} totalling ${Fmt.money(r.totals.expenseMinor)}.")
            r.largestExpense?.let { big ->
                val t = byId[big.id]
                val day = t?.let { java.time.Instant.ofEpochMilli(it.occurredAt).atZone(java.time.ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)) }
                add("The biggest was ${Fmt.money(big.amountMinor)} at ${t?.displayName() ?: "a merchant"}" + (day?.let { " on $it" } ?: "") + ".")
            }
            r.byCategory.firstOrNull()?.let { add("Most of it went on ${it.category} (${Fmt.percent(it.share.toDouble())}).") }
            add("That's about ${Fmt.money(r.dailyAverageMinor)} a day.")
            r.forecastExpenseMinor?.let {
                val last = r.period.endDateExclusive.minusDays(1).format(DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH))
                add("At this pace you'll spend about ${Fmt.rounded(it)} by $last.")
            }
        }
        if (r.totals.incomeMinor > 0) {
            val top = r.incomeByCategory.firstOrNull()?.category
            add("${Fmt.money(r.totals.incomeMinor)} came in" + (top?.let { ", mostly $it" } ?: "") + ".")
        }
        if (r.totals.internalMinor > 0) add("${Fmt.money(r.totals.internalMinor)} moved between your own accounts and isn't counted.")
    }
    Card(Modifier.fillMaxWidth().padding(16.dp, 8.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("In short", style = MaterialTheme.typography.titleSmall)
            lines.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
        }
    }
}

/** Bars per day with axis labels and an average line. Tapping a bar opens that day. */
@Composable
private fun SpendingChart(r: PeriodReport, onDay: (java.time.LocalDate) -> Unit) {
    val max = (r.buckets.maxOfOrNull { it.expenseMinor } ?: 0L).coerceAtLeast(1L)
    val avg = r.dailyAverageMinor
    val barColor = MaterialTheme.colorScheme.primary
    val todayColor = TodayColor
    val emptyColor = MaterialTheme.colorScheme.outlineVariant
    val avgColor = MaterialTheme.colorScheme.onSurfaceVariant
    val isWeek = r.period.type == PeriodType.WEEK
    val lastDay = r.buckets.size

    Card(Modifier.fillMaxWidth().padding(16.dp, 8.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text("Spending per day", style = MaterialTheme.typography.titleSmall)
            Text("Tap a bar to see that day's transactions", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth().height(170.dp)) {
                Column(Modifier.fillMaxHeight().width(40.dp), verticalArrangement = Arrangement.SpaceBetween) {
                    Text(Fmt.compact(max), style = MaterialTheme.typography.labelSmall, color = avgColor)
                    Text(Fmt.compact(max / 2), style = MaterialTheme.typography.labelSmall, color = avgColor)
                    Text("0", style = MaterialTheme.typography.labelSmall, color = avgColor)
                }
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.Bottom) {
                        r.buckets.forEach { b ->
                            val fraction = (b.expenseMinor.toFloat() / max).coerceIn(0f, 1f)
                            Column(
                                Modifier.weight(1f).fillMaxHeight().clickable { onDay(b.period.startDate) },
                                verticalArrangement = Arrangement.Bottom,
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                if (isWeek && b.expenseMinor > 0) {
                                    Text(Fmt.compact(b.expenseMinor), fontSize = 10.sp, color = avgColor, textAlign = TextAlign.Center)
                                }
                                Box(
                                    Modifier
                                        .fillMaxWidth(if (isWeek) 0.55f else 0.7f)
                                        .fillMaxHeight(if (b.expenseMinor > 0) fraction.coerceAtLeast(0.02f) else 0.01f)
                                        .background(
                                            when {
                                                b.expenseMinor == 0L -> emptyColor
                                                b.isToday -> todayColor
                                                else -> barColor
                                            },
                                            RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp),
                                        ),
                                )
                            }
                        }
                    }
                    if (avg in 1..max) {
                        Canvas(Modifier.fillMaxSize()) {
                            val y = size.height * (1f - avg.toFloat() / max)
                            drawLine(avgColor, Offset(0f, y), Offset(size.width, y), strokeWidth = 2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f)))
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(start = 40.dp, top = 4.dp)) {
                r.buckets.forEachIndexed { i, b ->
                    val day = i + 1
                    val show = isWeek || day == 1 || day % 5 == 0 || day == lastDay || b.isToday
                    // Labels are wider than a month's bar, so let them spill over the neighbouring columns.
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        if (show) Text(
                            b.label,
                            Modifier.wrapContentWidth(unbounded = true),
                            fontSize = 10.sp, softWrap = false, maxLines = 1,
                            color = if (b.isToday) todayColor else avgColor,
                            fontWeight = if (b.isToday) FontWeight.Bold else FontWeight.Normal,
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Canvas(Modifier.width(24.dp).height(2.dp)) {
                    drawLine(avgColor, Offset(0f, 0f), Offset(size.width, 0f), strokeWidth = 4f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f)))
                }
                Spacer(Modifier.width(6.dp))
                Text("Daily average ${Fmt.money(avg)}", style = MaterialTheme.typography.bodySmall, color = avgColor)
                if (r.buckets.any { it.isToday }) {
                    Spacer(Modifier.width(12.dp))
                    Box(Modifier.width(10.dp).height(10.dp).background(todayColor, RoundedCornerShape(2.dp)))
                    Spacer(Modifier.width(4.dp))
                    Text("Today", style = MaterialTheme.typography.bodySmall, color = avgColor)
                }
            }
        }
    }
}

@Composable
private fun CategoryRow(share: CategoryShare, amountColor: Color, onClick: () -> Unit) {
    val color = categoryColor(share.category)
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CategoryIcon(share.category)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(share.category, Modifier.weight(1f), fontWeight = FontWeight.Medium)
                Text(Fmt.money(share.amountMinor), color = amountColor, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(4.dp))
            LinearProgressIndicator(
                progress = { share.share },
                modifier = Modifier.fillMaxWidth().height(6.dp),
                color = color,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
                drawStopIndicator = {},
                gapSize = 0.dp,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                "${share.count} transaction${if (share.count == 1) "" else "s"} · ${Fmt.percent(share.share.toDouble())} of total",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
