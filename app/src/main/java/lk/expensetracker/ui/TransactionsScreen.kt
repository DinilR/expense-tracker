package lk.expensetracker.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import lk.expensetracker.data.db.TransactionEntity
import lk.expensetracker.data.db.displayName
import lk.expensetracker.engine.classification.LedgerMath
import lk.expensetracker.engine.insights.PeriodType
import lk.expensetracker.engine.model.LinkType

private enum class Filter(val label: String, val test: (TransactionEntity) -> Boolean) {
    ALL("All", { true }),
    EXPENSES("Money out", { LedgerMath.countsAsExpense(it.toLedgerEntry()) }),
    INCOME("Money in", { LedgerMath.countsAsIncome(it.toLedgerEntry()) }),
    TRANSFERS("Own accounts", { LedgerMath.isInternal(it.toLedgerEntry()) || it.linkType == LinkType.SUGGESTED_INTERNAL_PAIR }),
    REVERSED("Reversed", { it.isCancelled() }),
    REVIEW("To check", { it.needsReview }),
}

@Composable
fun TransactionsScreen(vm: MainViewModel, onOpen: (Long) -> Unit) {
    val all by vm.transactions.collectAsState()
    val period by vm.period.collectAsState()
    val category by vm.categoryFilter.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf(Filter.ALL) }
    var bank by rememberSaveable { mutableStateOf<String?>(null) }
    val banks = remember(all) { all.map { it.bankId }.distinct().sorted() }

    val shown = remember(all, period, query, filter, bank, category) {
        all.filter { t ->
            period.contains(t.occurredAt, vm.zone) && filter.test(t) && (bank == null || t.bankId == bank) &&
                (category == null || t.category == category) &&
                (query.isBlank() || listOf(t.displayName(), t.category, t.note.orEmpty()).any { it.contains(query, ignoreCase = true) })
        }
    }
    val totals = remember(shown) { LedgerMath.totals(shown.map { it.toLedgerEntry() }) }
    val grouped = remember(shown) { shown.groupBy { Fmt.day(it.occurredAt, vm.zone) } }

    LazyColumn(Modifier.fillMaxSize()) {
        item { PeriodSelector(vm, period) }
        item {
            OutlinedTextField(
                value = query, onValueChange = { query = it }, singleLine = true,
                leadingIcon = { Icon(Icons.Filled.Search, null) },
                placeholder = { Text("Search merchant, category or note") },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                category?.let { c ->
                    InputChip(
                        selected = true, onClick = { vm.categoryFilter.value = null },
                        label = { Text(c) }, leadingIcon = { CategoryIcon(c, 20.dp) },
                        trailingIcon = { Icon(Icons.Filled.Close, "Clear category") },
                    )
                }
                Filter.entries.forEach { f -> FilterChip(selected = filter == f, onClick = { filter = f }, label = { Text(f.label) }) }
                banks.forEach { b -> FilterChip(selected = bank == b, onClick = { bank = if (bank == b) null else b }, label = { Text(b.uppercase()) }) }
            }
        }
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${shown.size} transaction${if (shown.size == 1) "" else "s"}", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                Text("In ${Fmt.rounded(totals.incomeMinor)}", color = IncomeColor, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                Text("   Out ${Fmt.rounded(totals.expenseMinor)}", color = ExpenseColor, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
            }
        }
        if (shown.isEmpty()) item {
            Text(
                "Nothing here for this ${Fmt.periodNoun(period.type)}. Use the arrows or switch to Week or Month to see more.",
                Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        grouped.forEach { (day, list) ->
            if (period.type != PeriodType.DAY) item(key = "h-$day") {
                val dayOut = list.filter { LedgerMath.countsAsExpense(it.toLedgerEntry()) }.sumOf { it.amountMinor }
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp)) {
                    Text(day, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                    if (dayOut > 0) Text("spent ${Fmt.rounded(dayOut)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            items(list, key = { it.id }) { t ->
                TransactionRow(t, vm.zone) { onOpen(t.id) }
                HorizontalDivider(Modifier.padding(start = 68.dp))
            }
        }
    }
}
