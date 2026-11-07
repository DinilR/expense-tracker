package lk.expensetracker.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import lk.expensetracker.engine.bank.BankPacks

@Composable
fun BanksScreen(vm: MainViewModel, onEdit: (String) -> Unit, onNew: () -> Unit, onReview: () -> Unit) {
    val packs by vm.bankPacks.collectAsState()
    val transactions by vm.transactions.collectAsState()
    val unparsed by vm.unparsed.collectAsState()
    val counts = remember(transactions) { transactions.groupingBy { it.bankId }.eachCount() }
    val unparsedCounts = remember(unparsed) { unparsed.groupingBy { it.bankId }.eachCount() }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize()) {
            item {
                Text(
                    "Add a bank by picking its messages; the app learns the format by itself. Tap a bank to see or share its rules (advanced).",
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(16.dp),
                )
            }
            items(packs, key = { it.id }) { entity ->
                val pack = remember(entity.json) { runCatching { BankPacks.parse(entity.json) }.getOrNull() }
                Card(Modifier.fillMaxWidth().padding(16.dp, 4.dp).clickable { onEdit(entity.id) }) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(pack?.displayName ?: entity.id, style = MaterialTheme.typography.titleMedium)
                            Text(
                                listOfNotNull(
                                    "senders: ${pack?.senderPatterns?.joinToString() ?: "invalid JSON"}",
                                    "${counts[entity.id] ?: 0} transactions",
                                    unparsedCounts[entity.id]?.let { "$it unrecognised" },
                                    if (entity.builtIn) "built-in" else "custom",
                                ).joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Switch(checked = entity.enabled, onCheckedChange = { vm.setBankEnabled(entity.id, it) })
                    }
                }
            }
            if (unparsed.isNotEmpty()) item {
                TextButton(onClick = onReview, modifier = Modifier.padding(horizontal = 8.dp)) {
                    Text("${unparsed.size} message(s) from known banks were not recognised. Review")
                }
            }
            item { Box(Modifier.heightIn(min = 88.dp)) }
        }
        ExtendedFloatingActionButton(
            onClick = onNew,
            icon = { Icon(Icons.Filled.Add, null) },
            text = { Text("Add bank") },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        )
    }
}
