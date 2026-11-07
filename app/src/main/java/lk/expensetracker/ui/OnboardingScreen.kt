package lk.expensetracker.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun OnboardingScreen(vm: MainViewModel, onDone: () -> Unit) {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        vm.completeOnboarding(startImport = result[Manifest.permission.READ_SMS] == true)
        onDone()
    }
    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Spacer(Modifier.height(24.dp))
            AppLogo(64.dp)
            Text("Track spending from your bank SMS", style = MaterialTheme.typography.headlineMedium)
            Text(
                "The app reads transaction alerts from your banks (Sampath, BOC, and any bank you add) and turns them " +
                    "into a ledger of expenses, income and transfers.",
            )
            Text("Your data stays on this phone", style = MaterialTheme.typography.titleMedium)
            Text(
                "• The app has no internet permission, so nothing can be uploaded.\n" +
                    "• Only messages from known bank senders are stored. OTPs, promotions and personal messages are skipped.\n" +
                    "• Data is excluded from cloud backups.",
            )
            Text("Permissions", style = MaterialTheme.typography.titleMedium)
            Text(
                "• Read SMS: import your existing bank messages.\n" +
                    "• Receive SMS: record new transactions as they arrive.\n" +
                    "• Notifications (optional): tell you when a transaction is recorded.",
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    val perms = buildList {
                        add(Manifest.permission.READ_SMS)
                        add(Manifest.permission.RECEIVE_SMS)
                        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
                    }
                    launcher.launch(perms.toTypedArray())
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Allow SMS access and import") }
            TextButton(onClick = { vm.completeOnboarding(false); onDone() }, modifier = Modifier.fillMaxWidth()) {
                Text("Continue without SMS access")
            }
        }
    }
}
