package lk.expensetracker.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import lk.expensetracker.engine.model.Direction
import lk.expensetracker.engine.model.LinkType

@Composable
fun ReviewScreen(vm: MainViewModel, onOpen: (Long) -> Unit, onTeach: (String, String?) -> Unit, onEditRules: (String?) -> Unit) {
    val unparsed by vm.unparsed.collectAsState()
    val transactions by vm.transactions.collectAsState()
    val pairs = remember(transactions) {
        transactions.filter { it.linkType == LinkType.SUGGESTED_INTERNAL_PAIR && it.direction == Direction.DEBIT }
    }
    val flagged = remember(transactions) { transactions.filter { it.needsReview && it.linkType != LinkType.SUGGESTED_INTERNAL_PAIR } }

    LazyColumn(Modifier.fillMaxSize()) {
        if (unparsed.isEmpty() && pairs.isEmpty() && flagged.isEmpty()) item {
            Text("Nothing to review.", Modifier.padding(16.dp))
        }
        if (unparsed.isNotEmpty()) item { SectionTitle("Not recognised (${unparsed.size})") }
        items(unparsed, key = { "u${it.id}" }) { m ->
            Card(Modifier.fillMaxWidth().padding(16.dp, 4.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Text("${m.sender} · ${Fmt.dateTime(m.receivedAt, vm.zone)}", style = MaterialTheme.typography.labelMedium)
                    Text(m.body, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { onTeach(m.sender, m.bankId) }) { Text("Teach the app") }
                        TextButton(onClick = {
                            if (m.bankId == null) vm.prepareNewBank(m.sender, m.body) else vm.editorSample = m.body
                            onEditRules(m.bankId)
                        }) { Text("Edit rules") }
                        TextButton(onClick = { vm.ignoreUnparsed(m.id) }) { Text("Ignore") }
                    }
                }
            }
        }
        if (pairs.isNotEmpty()) item { SectionTitle("Possible transfers between your accounts (${pairs.size})") }
        items(pairs, key = { "p${it.id}" }) { debit ->
            val credit = transactions.firstOrNull { it.id == debit.linkedId }
            Card(Modifier.fillMaxWidth().padding(16.dp, 4.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Text("${Fmt.money(debit.amountMinor)}: ${debit.bankId.uppercase()} → ${credit?.bankId?.uppercase() ?: "?"}", style = MaterialTheme.typography.titleSmall)
                    Text(Fmt.dateTime(debit.occurredAt, vm.zone), style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { vm.confirmPair(debit.id) }) { Text("Yes, my own accounts") }
                        TextButton(onClick = { vm.unlink(debit.id) }) { Text("No") }
                    }
                }
            }
        }
        if (flagged.isNotEmpty()) item { SectionTitle("Please check (${flagged.size})") }
        items(flagged, key = { "f${it.id}" }) { t -> TransactionRow(t, vm.zone, showDate = true) { onOpen(t.id) } }
    }
}
