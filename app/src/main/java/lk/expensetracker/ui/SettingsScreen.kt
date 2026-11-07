package lk.expensetracker.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.FilterChip
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.time.LocalDate

@Composable
fun SettingsScreen(vm: MainViewModel) {
    val context = LocalContext.current
    val importing by vm.importing.collectAsState()
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        uri?.let { vm.exportCsv(it) { ok -> Toast.makeText(context, if (ok) "Exported" else "Export failed", Toast.LENGTH_SHORT).show() } }
    }

    val themeMode by vm.themeMode.collectAsState()

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Appearance", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(ThemeMode.SYSTEM to "Follow phone", ThemeMode.LIGHT to "Light", ThemeMode.DARK to "Dark").forEach { (mode, label) ->
                FilterChip(selected = themeMode == mode, onClick = { vm.setThemeMode(mode) }, label = { Text(label) })
            }
        }
        Text("Weeks start on Monday.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

        Text("Data", style = MaterialTheme.typography.titleMedium)
        OutlinedButton(onClick = { vm.importNow() }, enabled = !importing && vm.hasSmsPermission(), modifier = Modifier.fillMaxWidth()) {
            Text(if (importing) "Importing…" else "Re-import bank SMS from inbox")
        }
        OutlinedButton(onClick = { vm.reprocessUnparsed() }, modifier = Modifier.fillMaxWidth()) { Text("Re-check unrecognised messages") }
        OutlinedButton(onClick = { exportLauncher.launch("transactions-${LocalDate.now()}.csv") }, modifier = Modifier.fillMaxWidth()) {
            Text("Export transactions (CSV)")
        }
        if (!vm.hasSmsPermission()) {
            Text("SMS access is off. Enable it in Android Settings › Apps › Expense Tracker › Permissions to import messages.",
                style = MaterialTheme.typography.bodySmall)
        }

        Text("Privacy", style = MaterialTheme.typography.titleMedium)
        Card {
            Text(
                "This app has no internet permission. Only messages from enabled bank senders are stored; everything else, " +
                    "including OTPs and personal messages, is skipped. Data is excluded from cloud backup.",
                Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall,
            )
        }

        Text("AI (planned)", style = MaterialTheme.typography.titleMedium)
        Card {
            Text(
                "A later version adds an optional AI layer: reading messages the rules miss, and explaining your spending. " +
                    "Options will be Off (default), On-device, or Cloud (anonymised totals only).",
                Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall,
            )
        }
        Text("Version 0.1.0", style = MaterialTheme.typography.bodySmall)
    }
}
