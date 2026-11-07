package lk.expensetracker.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import lk.expensetracker.data.db.TransactionEntity
import lk.expensetracker.data.db.displayName
import lk.expensetracker.engine.classification.LedgerMath
import lk.expensetracker.engine.insights.Period
import lk.expensetracker.engine.insights.PeriodType
import lk.expensetracker.engine.model.Direction
import lk.expensetracker.engine.model.LinkType
import lk.expensetracker.engine.model.TimeSource
import lk.expensetracker.engine.normalize.Money
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

object Fmt {
    private val day = DateTimeFormatter.ofPattern("EEE, d MMM yyyy", Locale.ENGLISH)
    private val dateTime = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", Locale.ENGLISH)
    private val time = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH)

    fun money(minor: Long, currency: String = "LKR") = "$currency ${Money.format(minor)}"

    /** Whole rupees for headlines: "LKR 11,969". */
    fun rounded(minor: Long, currency: String = "LKR") = "$currency " + "%,d".format(Locale.US, (minor + 50) / 100)

    /** Short form for chart labels: "4.3k", "1.2M". */
    fun compact(minor: Long): String {
        val v = minor / 100.0
        return when {
            v >= 1_000_000 -> "%.1fM".format(Locale.US, v / 1_000_000)
            v >= 1_000 -> "%.1fk".format(Locale.US, v / 1_000)
            else -> "%.0f".format(Locale.US, v)
        }
    }

    fun percent(fraction: Double) = "${(abs(fraction) * 100).roundToInt()}%"
    fun day(epoch: Long, zone: ZoneId): String = day.format(Instant.ofEpochMilli(epoch).atZone(zone))
    fun dateTime(epoch: Long, zone: ZoneId): String = dateTime.format(Instant.ofEpochMilli(epoch).atZone(zone))
    fun time(epoch: Long, zone: ZoneId): String = time.format(Instant.ofEpochMilli(epoch).atZone(zone))
    fun label(enumName: String) = enumName.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }

    /** "this week", "last month", "this day" → used in sentences. */
    fun periodNoun(type: PeriodType) = when (type) {
        PeriodType.DAY -> "day"
        PeriodType.WEEK -> "week"
        PeriodType.MONTH -> "month"
    }
}

/** Reversed debits and matched reversals net to zero, so they are shown struck through. */
fun TransactionEntity.isCancelled() = linkType == LinkType.REVERSED_BY || linkType == LinkType.REVERSES

/** Day | Week | Month switch with previous/next arrows and a jump back to today. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PeriodSelector(vm: MainViewModel, period: Period) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            PeriodType.entries.forEachIndexed { i, type ->
                SegmentedButton(
                    selected = period.type == type,
                    onClick = { vm.setPeriodType(type) },
                    shape = SegmentedButtonDefaults.itemShape(i, PeriodType.entries.size),
                ) { Text(Fmt.label(type.name)) }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { vm.shiftPeriod(-1) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Previous ${Fmt.periodNoun(period.type)}") }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(period.label, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                if (!vm.isCurrentPeriod(period)) {
                    TextButton(onClick = { vm.goToToday() }) { Text("Back to ${if (period.type == PeriodType.DAY) "today" else "this " + Fmt.periodNoun(period.type)}") }
                }
            }
            IconButton(onClick = { vm.shiftPeriod(1) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Next ${Fmt.periodNoun(period.type)}") }
        }
    }
}

@Composable
fun TransactionRow(t: TransactionEntity, zone: ZoneId, showDate: Boolean = false, onClick: () -> Unit) {
    val entry = t.toLedgerEntry()
    val color = when {
        t.isCancelled() || t.excluded -> NeutralColor
        LedgerMath.countsAsIncome(entry) -> IncomeColor
        LedgerMath.countsAsExpense(entry) -> ExpenseColor
        else -> NeutralColor
    }
    val decoration = if (t.isCancelled() || t.excluded) TextDecoration.LineThrough else null
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CategoryIcon(t.category)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(t.displayName(), maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium, textDecoration = decoration)
            val whenText = if (showDate) Fmt.dateTime(t.occurredAt, zone)
            else if (t.timeSource == TimeSource.SMS_TEXT) Fmt.time(t.occurredAt, zone) else "~" + Fmt.time(t.occurredAt, zone)
            val tags = buildList {
                add(t.category)
                add(whenText)
                add(t.bankId.uppercase())
                if (t.linkType == LinkType.SUGGESTED_INTERNAL_PAIR) add("transfer?")
                if (t.linkType == LinkType.INTERNAL_PAIR) add("own accounts")
                if (t.isCancelled()) add("reversed")
                if (t.needsReview) add("check")
            }
            Text(tags.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(8.dp))
        val sign = if (t.direction == Direction.DEBIT) "−" else "+"
        Text("$sign${Money.format(t.amountMinor)}", color = color, fontWeight = FontWeight.SemiBold, textDecoration = decoration)
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier, subtitle: String? = null) {
    Column(modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
        subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
fun LabeledValue(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(16.dp))
        Text(value, fontWeight = FontWeight.Medium)
    }
}
