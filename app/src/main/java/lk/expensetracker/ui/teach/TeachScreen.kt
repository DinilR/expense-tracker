package lk.expensetracker.ui.teach

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import lk.expensetracker.engine.classification.Categories
import lk.expensetracker.engine.learn.BankLearner
import lk.expensetracker.engine.learn.Field
import lk.expensetracker.engine.learn.GroupTemplate
import lk.expensetracker.engine.model.Direction
import lk.expensetracker.engine.model.Kind
import lk.expensetracker.engine.model.TransactionDraft
import lk.expensetracker.engine.normalize.Money
import lk.expensetracker.ui.ExpenseColor
import lk.expensetracker.ui.Fmt
import lk.expensetracker.ui.IncomeColor
import java.time.ZoneId

/**
 * The "teach the app a bank" wizard. [sender] skips the conversation list (used from the Review screen);
 * [existingBankId] adds the learned formats to that bank instead of creating a new one.
 */
@Composable
fun TeachScreen(sender: String?, existingBankId: String?, onExit: () -> Unit, onAdvanced: () -> Unit) {
    val vm: TeachViewModel = viewModel()
    val state by vm.state.collectAsState()

    LaunchedEffect(sender) {
        if (sender != null) vm.choose(sender, existingBankId) else vm.loadConversations()
    }
    BackHandler(enabled = state.step != TeachStep.CHOOSE) { if (!vm.back()) onExit() }

    Box(Modifier.fillMaxSize()) {
        when (state.step) {
            TeachStep.CHOOSE -> ChooseConversation(state, vm, onAdvanced)
            TeachStep.KINDS -> MessageKinds(state, vm)
            TeachStep.ADJUST -> state.groups.firstOrNull { it.id == state.selectedGroup }?.let { AdjustGroup(it, state, vm) }
            TeachStep.PREVIEW -> Preview(state, vm, onExit)
            TeachStep.SAVED -> Unit
        }
        if (state.loading) {
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator()
                Spacer(Modifier.height(8.dp))
                Text(if (state.step == TeachStep.CHOOSE) "Reading messages…" else "Working out the patterns…")
            }
        }
    }
}

@Composable
private fun StepHeader(step: Int, title: String, subtitle: String) {
    Column(Modifier.padding(16.dp)) {
        Text("Step $step of 3", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        Text(title, style = MaterialTheme.typography.titleLarge)
        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ---- Step 1: choose the conversation -----------------------------------------------------------

@Composable
private fun ChooseConversation(state: TeachState, vm: TeachViewModel, onAdvanced: () -> Unit) {
    var query by remember { mutableStateOf("") }
    val shown = state.conversations
        .filter { state.showPhoneNumbers || !it.isPhoneNumber }
        .filter { query.isBlank() || it.sender.contains(query, true) || it.lastBody.contains(query, true) }
    LazyColumn(Modifier.fillMaxSize()) {
        item { StepHeader(1, "Pick your bank's messages", "Choose the conversation your bank sends transaction alerts from. The app reads them on this phone only.") }
        item {
            OutlinedTextField(
                query, { query = it }, singleLine = true, leadingIcon = { Icon(Icons.Filled.Search, null) },
                placeholder = { Text("Search sender or text") }, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
            Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                FilterChip(state.showPhoneNumbers, { vm.setShowPhoneNumbers(!state.showPhoneNumbers) }, label = { Text("Show phone numbers too") })
            }
        }
        if (!state.loading && shown.isEmpty()) item {
            Text("No conversations found. Check that SMS access is allowed in Settings.", Modifier.padding(16.dp))
        }
        items(shown, key = { it.sender }) { c ->
            val known = c.sender in state.knownSenders
            Column(Modifier.fillMaxWidth().clickable { vm.choose(c.sender) }.padding(horizontal = 16.dp, vertical = 10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(c.sender, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    Text("${c.count} messages", style = MaterialTheme.typography.bodySmall)
                }
                if (known) Text("Already set up · teaching adds any message types it doesn't understand yet", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                Text(c.lastBody.replace('\n', ' '), maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            HorizontalDivider()
        }
        item {
            TextButton(onClick = onAdvanced, modifier = Modifier.padding(8.dp)) { Text("Advanced: write the rules by hand (JSON)") }
        }
    }
}

// ---- Step 2: kinds of message -----------------------------------------------------------------

@Composable
private fun MessageKinds(state: TeachState, vm: TeachViewModel) {
    Column(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f)) {
            item {
                val txCount = state.groups.count { it.isTransaction }
                StepHeader(
                    2, "We found ${state.groups.size} kind${if (state.groups.size == 1) "" else "s"} of message",
                    if (state.messages.isEmpty()) "There are no messages from ${state.sender} that the app doesn't already understand."
                    else "From ${state.messages.size} message${if (state.messages.size == 1) "" else "s"} by ${state.sender}. " +
                        "$txCount ${if (txCount == 1) "looks" else "look"} like transactions. Tap one to check or correct what the app understood.",
                )
            }
            items(state.groups, key = { it.id }) { g ->
                val summary = state.summaries[g.id]
                Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).clickable { vm.openGroup(g.id) }) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(if (g.isTransaction) summary?.label ?: "…" else "Not transactions", style = MaterialTheme.typography.titleMedium)
                                Text("${g.messages.size} message${if (g.messages.size == 1) "" else "s"}" +
                                    (summary?.takeIf { g.isTransaction }?.let { " · understood ${it.understood}" } ?: ""), style = MaterialTheme.typography.bodySmall)
                            }
                            Text("Transaction", style = MaterialTheme.typography.labelSmall)
                            Spacer(Modifier.width(6.dp))
                            Switch(g.isTransaction, { vm.setTransaction(g.id, it) })
                        }
                        HighlightedMessage(g)
                        if (g.isTransaction) summary?.examples?.firstOrNull()?.let { DraftLine(it) }
                        Text("Tap to check or correct", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
            item { Spacer(Modifier.height(80.dp)) }
        }
        Button(
            onClick = { vm.preview() }, enabled = state.groups.any { it.isTransaction } && !state.loading,
            modifier = Modifier.fillMaxWidth().padding(16.dp),
        ) { Text("Next: preview on all messages") }
    }
}

// ---- Step 2b: correct one kind ----------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AdjustGroup(g: GroupTemplate, state: TeachState, vm: TeachViewModel) {
    var tokenDialog by remember { mutableStateOf<Int?>(null) }
    val summary = state.summaries[g.id]
    val hasDirectionPart = g.referenceTokens.indices.any { BankLearner.fieldOf(g, it) == Field.DIRECTION }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(summary?.label ?: "Message type", style = MaterialTheme.typography.titleLarge)
        Text("Coloured parts change from message to message. Tap any part to tell the app what it is.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                HighlightedMessage(g) { tokenDialog = it }
                FieldLegend(g)
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("This is a transaction", Modifier.weight(1f), fontWeight = FontWeight.Medium)
            Switch(g.isTransaction, { vm.setTransaction(g.id, it) })
        }

        if (g.isTransaction) {
            if (!hasDirectionPart) {
                Text("Is money going out or coming in?", fontWeight = FontWeight.Medium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(g.direction == Direction.DEBIT, { vm.setDirection(g.id, Direction.DEBIT) }, label = { Text("Money out") })
                    FilterChip(g.direction == Direction.CREDIT, { vm.setDirection(g.id, Direction.CREDIT) }, label = { Text("Money in") })
                }
            }
            Text("Treat these as", fontWeight = FontWeight.Medium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                FilterChip(g.kind == null, { vm.setKind(g.id, null) }, label = { Text("Work it out") })
                listOf(Kind.EXPENSE, Kind.INCOME, Kind.TRANSFER_OUT, Kind.TRANSFER_IN, Kind.CASH_WITHDRAWAL, Kind.FEE, Kind.REVERSAL).forEach { k ->
                    FilterChip(g.kind == k, { vm.setKind(g.id, k) }, label = { Text(Fmt.label(k.name)) })
                }
            }
            Text("Category", fontWeight = FontWeight.Medium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                FilterChip(g.category == null, { vm.setCategory(g.id, null) }, label = { Text("By merchant") })
                Categories.ALL.forEach { c -> FilterChip(g.category == c, { vm.setCategory(g.id, c) }, label = { Text(c) }) }
            }

            Text("What the app reads", style = MaterialTheme.typography.titleMedium)
            summary?.let { s ->
                Text("Understood ${s.understood} of ${s.total} messages of this kind", style = MaterialTheme.typography.bodySmall)
                LinearProgressIndicator(progress = { if (s.total > 0) s.understood.toFloat() / s.total else 0f }, modifier = Modifier.fillMaxWidth())
                s.examples.forEach { DraftLine(it) }
            }
        }
        Button(onClick = { vm.back() }, modifier = Modifier.fillMaxWidth()) { Text("Done") }
    }

    tokenDialog?.let { index ->
        val token = g.referenceTokens[index]
        val current = BankLearner.fieldOf(g, index)
        val options = buildList {
            add(Field.FIXED)
            if (token.isTyped) {
                add(Field.AMOUNT); add(Field.BALANCE); add(Field.ACCOUNT); add(Field.DATE)
            } else {
                add(Field.MERCHANT); add(Field.DIRECTION); add(Field.TYPE); add(Field.CHANNEL); add(Field.AMOUNT); add(Field.DATE)
            }
            add(Field.OTHER)
        }.distinct()
        AlertDialog(
            onDismissRequest = { tokenDialog = null },
            title = { Text("What is \"${token.text}\"?") },
            text = {
                Column {
                    options.forEach { f ->
                        Row(
                            Modifier.fillMaxWidth().clickable { vm.setField(g.id, index, f); tokenDialog = null }.padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                Modifier.width(12.dp).height(12.dp).background(
                                    if (f == Field.FIXED) MaterialTheme.colorScheme.outlineVariant else f.color(), CircleShape,
                                ),
                            )
                            Spacer(Modifier.width(10.dp))
                            Text((if (f == current) "✓ " else "") + f.label(), color = if (f == Field.FIXED) MaterialTheme.colorScheme.onSurface else f.color())
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { tokenDialog = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun DraftLine(d: TransactionDraft) {
    val zone = ZoneId.systemDefault()
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(d.merchantRaw ?: d.transactionType ?: "—", maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
            Text("${Fmt.dateTime(d.occurredAt, zone)} · ${Fmt.label(d.kind.name)}" + (d.accountMask?.let { " · ••$it" } ?: ""), style = MaterialTheme.typography.bodySmall)
        }
        Text(
            (if (d.direction == Direction.DEBIT) "−" else "+") + Money.format(d.amountMinor),
            color = if (d.direction == Direction.DEBIT) ExpenseColor else IncomeColor, fontWeight = FontWeight.SemiBold,
        )
    }
}

// ---- Step 3: preview and save -----------------------------------------------------------------

@Composable
private fun Preview(state: TeachState, vm: TeachViewModel, onExit: () -> Unit) {
    val eval = state.evaluation ?: return
    val txMessages = state.groups.filter { it.isTransaction }.sumOf { it.messages.size }
    Column(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f)) {
            item { StepHeader(3, "Check and save", "This is how the app will read ${state.sender}'s messages.") }
            item {
                OutlinedTextField(
                    state.bankName, vm::setBankName, label = { Text("Bank name") }, singleLine = true,
                    enabled = state.existing == null, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                )
            }
            item {
                Card(Modifier.fillMaxWidth().padding(16.dp)) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("${eval.parsed.size} transactions understood", style = MaterialTheme.typography.titleMedium)
                        LinearProgressIndicator(
                            progress = { if (txMessages > 0) (eval.parsed.size.toFloat() / txMessages).coerceAtMost(1f) else 0f },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text("${eval.ignored} skipped as not transactions · ${eval.notUnderstood.size} not understood", style = MaterialTheme.typography.bodySmall)
                        if (eval.notUnderstood.isNotEmpty()) Text("Messages that aren't understood go to Review, where you can teach them later.", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            item { Text("Examples", Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.titleSmall) }
            items(eval.parsed.take(25)) { (_, d) -> Box(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) { DraftLine(d) } }
            if (eval.notUnderstood.isNotEmpty()) {
                item { Text("Not understood", Modifier.padding(16.dp, 12.dp, 16.dp, 4.dp), style = MaterialTheme.typography.titleSmall) }
                items(eval.notUnderstood.take(8)) { m ->
                    Text(m.body.replace('\n', ' '), Modifier.padding(horizontal = 16.dp, vertical = 4.dp), fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
            }
            item { Spacer(Modifier.height(16.dp)) }
        }
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = { vm.back() }, modifier = Modifier.weight(1f)) { Text("Back") }
            Button(onClick = { vm.save(onExit) }, enabled = eval.parsed.isNotEmpty(), modifier = Modifier.weight(1f)) {
                Text(if (state.existing != null) "Add to ${state.existing.displayName}" else "Save bank")
            }
        }
    }
}
