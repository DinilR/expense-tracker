package lk.expensetracker.ui

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/**
 * Rule editor: edit a bank pack as JSON, test it live against the inbox, save, and share it.
 * [bankId] "new" opens the template prepared by [MainViewModel.prepareNewBank].
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BankEditorScreen(vm: MainViewModel, bankId: String, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val packs by vm.bankPacks.collectAsState()
    val entity = packs.firstOrNull { it.id == bankId }
    var json by remember { mutableStateOf<String?>(null) }
    var report by remember { mutableStateOf<PackTestReport?>(null) }
    var saveProblems by remember { mutableStateOf<List<String>>(emptyList()) }
    val sample = remember { vm.editorSample }

    LaunchedEffect(bankId) {
        json = if (bankId == "new") vm.editorTemplate ?: "" else vm.bankJson(bankId) ?: ""
        vm.editorTemplate = null
        vm.editorSample = null
    }
    val text = json ?: return

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            "Named groups in a pattern: amount (required), currency, direction, account, channel, merchant, date, balance, type. " +
                "Classification rules then set kind, channel and category.",
            style = MaterialTheme.typography.bodySmall,
        )
        sample?.let {
            Card {
                Column(Modifier.padding(12.dp)) {
                    Text("Message to support", style = MaterialTheme.typography.titleSmall)
                    Text(it, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        OutlinedTextField(
            value = text, onValueChange = { json = it; report = null; saveProblems = emptyList() },
            textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
            modifier = Modifier.fillMaxWidth().heightIn(min = 280.dp),
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { scope.launch { report = vm.testPack(text) } }) { Text("Test on inbox") }
            Button(onClick = {
                scope.launch {
                    saveProblems = vm.saveBankPack(text)
                    if (saveProblems.isEmpty()) onClose()
                }
            }) { Text("Save") }
            TextButton(onClick = {
                val send = Intent(Intent.ACTION_SEND).setType("application/json").putExtra(Intent.EXTRA_TEXT, text)
                context.startActivity(Intent.createChooser(send, "Share bank rules"))
            }) { Text("Share") }
            if (entity?.builtIn == true) TextButton(onClick = { vm.resetBank(bankId); onClose() }) { Text("Reset to default") }
            if (entity != null && !entity.builtIn) TextButton(onClick = { vm.deleteBank(bankId); onClose() }) { Text("Delete") }
        }

        if (saveProblems.isNotEmpty()) ProblemsCard(saveProblems)
        report?.let { r ->
            if (r.problems.isNotEmpty()) ProblemsCard(r.problems)
            else Card {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Checked ${r.checked} messages (samples + inbox): ${r.parsed} parsed, ${r.ignored} ignored, ${r.unrecognised.size} not recognised",
                        style = MaterialTheme.typography.titleSmall)
                    r.examples.forEach { Text("✓ $it", style = MaterialTheme.typography.bodySmall) }
                    r.unrecognised.forEach { Text("✗ $it", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
    }
}

@Composable
private fun ProblemsCard(problems: List<String>) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Column(Modifier.padding(12.dp)) {
            problems.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
        }
    }
}
