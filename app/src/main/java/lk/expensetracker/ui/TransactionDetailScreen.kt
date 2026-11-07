package lk.expensetracker.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import lk.expensetracker.data.db.displayName
import lk.expensetracker.engine.classification.Categories
import lk.expensetracker.engine.model.Direction
import lk.expensetracker.engine.model.Kind
import lk.expensetracker.engine.model.LinkType
import lk.expensetracker.engine.model.TimeSource

@Composable
fun TransactionDetailScreen(vm: MainViewModel, id: Long, onOpen: (Long) -> Unit) {
    val t by vm.transaction(id).collectAsState(initial = null)
    val txn = t ?: return
    var pickCategory by remember { mutableStateOf(false) }
    var pickKind by remember { mutableStateOf(false) }
    var note by remember(txn.id) { mutableStateOf(txn.note.orEmpty()) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val sign = if (txn.direction == Direction.DEBIT) "−" else "+"
        Text("$sign${Fmt.money(txn.amountMinor, txn.currency)}", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text(txn.displayName(), style = MaterialTheme.typography.titleMedium)

        Card {
            Column(Modifier.padding(12.dp)) {
                LabeledValue("When", Fmt.dateTime(txn.occurredAt, vm.zone) + if (txn.timeSource == TimeSource.SMS_RECEIVED) " (SMS time)" else "")
                LabeledValue("Bank / account", "${txn.bankId.uppercase()} ${txn.accountMask?.let { "••$it" }.orEmpty()}")
                LabeledValue("Channel", Fmt.label(txn.channel.name))
                txn.balanceMinor?.let { LabeledValue("Balance after", Fmt.money(it, txn.currency)) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Type", Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = { pickKind = true }) { Text(Fmt.label(txn.kind.name)) }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Category", Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = { pickCategory = true }) { Text(txn.category) }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Exclude from totals", Modifier.weight(1f))
                    Switch(checked = txn.excluded, onCheckedChange = { vm.setExcluded(txn.id, it) })
                }
            }
        }

        txn.linkType?.let { link ->
            Card {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        when (link) {
                            LinkType.REVERSED_BY -> "This payment was reversed. It is not counted as spending."
                            LinkType.REVERSES -> "This is a reversal of an earlier payment. Both cancel out."
                            LinkType.INTERNAL_PAIR -> "Transfer between your own accounts. Not counted as income or spending."
                            LinkType.SUGGESTED_INTERNAL_PAIR -> "Looks like a transfer between your own accounts (same amount on another account within 30 minutes)."
                        },
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        txn.linkedId?.let { other -> OutlinedButton(onClick = { onOpen(other) }) { Text("Open linked") } }
                        if (link == LinkType.SUGGESTED_INTERNAL_PAIR) Button(onClick = { vm.confirmPair(txn.id) }) { Text("Confirm") }
                        TextButton(onClick = { vm.unlink(txn.id) }) { Text(if (link == LinkType.SUGGESTED_INTERNAL_PAIR) "Not internal" else "Unlink") }
                    }
                }
            }
        }

        if (txn.needsReview) {
            Card {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (txn.kind == Kind.REVERSAL && txn.linkType == null) "Reversal with no matching payment found; counted as income."
                        else "Please check this transaction.",
                        Modifier.weight(1f),
                    )
                    TextButton(onClick = { vm.markReviewed(txn.id) }) { Text("Looks right") }
                }
            }
        }

        OutlinedTextField(
            value = note, onValueChange = { note = it }, label = { Text("Note") }, modifier = Modifier.fillMaxWidth(),
            trailingIcon = { if (note != txn.note.orEmpty()) TextButton(onClick = { vm.setNote(txn.id, note) }) { Text("Save") } },
        )

        Text("Original SMS (${txn.smsSender})", style = MaterialTheme.typography.titleSmall)
        Card { SelectionContainer { Text(txn.smsBody, Modifier.padding(12.dp), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) } }
        Text(
            "Read by ${txn.interpreter} · rule \"${txn.ruleName}\" · confidence ${(txn.confidence * 100).toInt()}%",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    if (pickCategory) CategoryDialog(txn.category, txn.merchantKey, onDismiss = { pickCategory = false }) { category, all ->
        vm.setCategory(txn.id, category, all)
        pickCategory = false
    }
    if (pickKind) OptionDialog("Transaction type", Kind.entries.map { it.name }, txn.kind.name, onDismiss = { pickKind = false }) {
        vm.setKind(txn.id, Kind.valueOf(it))
        pickKind = false
    }
}

@Composable
private fun CategoryDialog(current: String, merchantKey: String?, onDismiss: () -> Unit, onPick: (String, Boolean) -> Unit) {
    var applyToAll by remember { mutableStateOf(merchantKey != null) }
    var custom by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Category") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (merchantKey != null) Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(applyToAll, { applyToAll = it })
                    Text("Always use for $merchantKey")
                }
                Categories.ALL.forEach { c ->
                    TextButton(onClick = { onPick(c, applyToAll) }, modifier = Modifier.fillMaxWidth()) {
                        Text(if (c == current) "✓ $c" else c, Modifier.fillMaxWidth())
                    }
                }
                OutlinedTextField(custom, { custom = it }, label = { Text("Custom category") }, singleLine = true)
            }
        },
        confirmButton = { TextButton(enabled = custom.isNotBlank(), onClick = { onPick(custom.trim(), applyToAll) }) { Text("Use custom") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun OptionDialog(title: String, options: List<String>, current: String, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                options.forEach { o ->
                    TextButton(onClick = { onPick(o) }, modifier = Modifier.fillMaxWidth()) {
                        Text((if (o == current) "✓ " else "") + Fmt.label(o), Modifier.fillMaxWidth())
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
