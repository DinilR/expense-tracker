package lk.expensetracker.ui

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import kotlinx.coroutines.flow.MutableStateFlow
import lk.expensetracker.R
import lk.expensetracker.ui.teach.TeachScreen

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab("dashboard", "Overview", Icons.Filled.Dashboard),
    Tab("transactions", "Transactions", Icons.AutoMirrored.Filled.ReceiptLong),
    Tab("insights", "Insights", Icons.Filled.Insights),
    Tab("banks", "Banks", Icons.Filled.AccountBalance),
)

/** The launcher icon, used in the top bar and on onboarding. */
@Composable
fun AppLogo(size: Dp = 36.dp) {
    Box(Modifier.size(size).background(Color(0xFF1B6E5A), RoundedCornerShape(size / 4)), contentAlignment = Alignment.Center) {
        Icon(painterResource(R.drawable.ic_launcher_foreground), contentDescription = null, tint = Color.Unspecified, modifier = Modifier.size(size * 1.5f))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRoot(vm: MainViewModel, openTransaction: MutableStateFlow<Long?>) {
    var onboarded by remember { mutableStateOf(vm.onboardingDone) }
    if (!onboarded) {
        OnboardingScreen(vm) { onboarded = true }
        return
    }

    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route
    val reviewCount by vm.reviewCount.collectAsState()
    val pendingOpen by openTransaction.collectAsState()

    LaunchedEffect(pendingOpen) {
        pendingOpen?.let { nav.navigate("txn/$it"); openTransaction.value = null }
    }

    val isTab = tabs.any { it.route == route }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (isTab) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            AppLogo()
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleMedium)
                                Text(titleFor(route), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    } else {
                        Text(titleFor(route))
                    }
                },
                navigationIcon = {
                    if (!isTab) IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
                actions = {
                    IconButton(onClick = { nav.navigate("review") }) {
                        BadgedBox(badge = { if (reviewCount > 0) Badge { Text("$reviewCount") } }) {
                            Icon(Icons.Filled.Inbox, "Review")
                        }
                    }
                    IconButton(onClick = { nav.navigate("settings") }) { Icon(Icons.Filled.Settings, "Settings") }
                },
            )
        },
        bottomBar = {
            if (isTab) NavigationBar {
                tabs.forEach { tab ->
                    NavigationBarItem(
                        selected = route == tab.route,
                        onClick = { nav.navigateTab(tab.route) },
                        icon = { Icon(tab.icon, null) },
                        label = { Text(tab.label) },
                    )
                }
            }
        },
    ) { padding ->
        NavHost(nav, startDestination = "dashboard", modifier = Modifier.padding(padding).fillMaxSize()) {
            composable("dashboard") {
                DashboardScreen(
                    vm,
                    onOpen = { nav.navigate("txn/$it") },
                    onReview = { nav.navigate("review") },
                    onCategory = { vm.categoryFilter.value = it; nav.navigateTab("transactions") },
                    onSeeAll = { vm.categoryFilter.value = null; nav.navigateTab("transactions") },
                )
            }
            composable("transactions") { TransactionsScreen(vm) { nav.navigate("txn/$it") } }
            composable("insights") { InsightsScreen(vm) }
            composable("banks") {
                BanksScreen(vm, onEdit = { nav.navigate("bank/$it") }, onNew = { nav.navigate("teach") }, onReview = { nav.navigate("review") })
            }
            composable("review") {
                ReviewScreen(
                    vm,
                    onOpen = { nav.navigate("txn/$it") },
                    onTeach = { sender, bankId -> nav.navigate("teach?sender=${Uri.encode(sender)}&bank=${Uri.encode(bankId.orEmpty())}") },
                    onEditRules = { bankId -> nav.navigate("bank/${bankId ?: "new"}") },
                )
            }
            composable("settings") { SettingsScreen(vm) }
            composable("txn/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) { entry ->
                TransactionDetailScreen(vm, entry.arguments!!.getLong("id")) { nav.navigate("txn/$it") }
            }
            composable("bank/{id}") { entry ->
                BankEditorScreen(vm, entry.arguments?.getString("id") ?: "new") { nav.popBackStack() }
            }
            composable(
                "teach?sender={sender}&bank={bank}",
                arguments = listOf(
                    navArgument("sender") { type = NavType.StringType; nullable = true; defaultValue = null },
                    navArgument("bank") { type = NavType.StringType; nullable = true; defaultValue = null },
                ),
            ) { entry ->
                TeachScreen(
                    sender = entry.arguments?.getString("sender")?.takeIf { it.isNotBlank() },
                    existingBankId = entry.arguments?.getString("bank")?.takeIf { it.isNotBlank() },
                    onExit = { nav.popBackStack() },
                    onAdvanced = { vm.prepareNewBank("NEWBANK", null); nav.navigate("bank/new") },
                )
            }
        }
    }
}

private fun NavHostController.navigateTab(route: String) = navigate(route) {
    popUpTo("dashboard") { saveState = true }
    launchSingleTop = true
    restoreState = true
}

private fun titleFor(route: String?) = when {
    route == null || route == "dashboard" -> "Overview"
    route == "transactions" -> "Transactions"
    route == "insights" -> "Insights"
    route == "banks" -> "Banks"
    route == "review" -> "Review"
    route == "settings" -> "Settings"
    route.startsWith("txn") -> "Transaction"
    route.startsWith("teach") -> "Teach the app a bank"
    route.startsWith("bank") -> "Bank rules (advanced)"
    else -> ""
}
