package lk.expensetracker.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import lk.expensetracker.engine.insights.Insight
import lk.expensetracker.engine.insights.Severity

@Composable
fun InsightsScreen(vm: MainViewModel) {
    val insights by vm.insights.collectAsState()
    LazyColumn(Modifier.fillMaxSize()) {
        item { SectionTitle("From your numbers (on device)") }
        if (insights.isEmpty()) item {
            Text("Insights appear once a few weeks of transactions are recorded.", Modifier.padding(horizontal = 16.dp))
        }
        items(insights) { InsightCard(it) }
        item { SectionTitle("AI insights") }
        item {
            Card(Modifier.fillMaxWidth().padding(16.dp, 4.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Text("Coming in the AI phase", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Explanations of spending changes, suggestions and a question box (\"Why was October higher?\"). " +
                            "It will be off by default. When enabled, it receives monthly totals per category only, never your SMS or account numbers.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

@Composable
fun InsightCard(insight: Insight) {
    val colors = when (insight.severity) {
        Severity.WARNING -> CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
        Severity.GOOD -> CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
        Severity.INFO -> CardDefaults.cardColors()
    }
    Card(Modifier.fillMaxWidth().padding(16.dp, 4.dp), colors = colors) {
        Column(Modifier.padding(12.dp)) {
            Text(insight.title, style = MaterialTheme.typography.titleSmall)
            Text(insight.detail, style = MaterialTheme.typography.bodySmall)
        }
    }
}
