package lk.expensetracker.ui

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    /** Transaction to open when launched from a notification. */
    private val openTransaction = MutableStateFlow<Long?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handle(intent)
        setContent {
            val mode by viewModel.themeMode.collectAsState()
            val dark = isDark(mode)
            // Status and navigation bar icons follow the app theme, not just the system setting.
            DisposableEffect(dark) {
                val style = if (dark) SystemBarStyle.dark(Color.TRANSPARENT) else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                onDispose {}
            }
            ExpenseTheme(dark) {
                AppRoot(viewModel, openTransaction)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        intent?.getLongExtra(EXTRA_TRANSACTION_ID, -1L)?.takeIf { it > 0 }?.let { openTransaction.value = it }
    }

    companion object {
        const val EXTRA_TRANSACTION_ID = "transactionId"
    }
}
