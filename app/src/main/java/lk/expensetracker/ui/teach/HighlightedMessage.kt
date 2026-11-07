package lk.expensetracker.ui.teach

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import lk.expensetracker.engine.learn.BankLearner
import lk.expensetracker.engine.learn.Field
import lk.expensetracker.engine.learn.GroupTemplate
import lk.expensetracker.ui.LocalDarkTheme

/** Plain-language name of each part of a message. */
fun Field.label(): String = when (this) {
    Field.FIXED -> "Same in every message"
    Field.AMOUNT -> "Amount"
    Field.BALANCE -> "Balance after"
    Field.ACCOUNT -> "Account number"
    Field.DATE -> "Date & time"
    Field.MERCHANT -> "Shop / description"
    Field.DIRECTION -> "Money in or out"
    Field.TYPE -> "Transaction type"
    Field.CHANNEL -> "Card / ATM / online"
    Field.OTHER -> "Changes, not needed"
}

@Composable
fun Field.color(): Color {
    val dark = LocalDarkTheme.current
    return when (this) {
        Field.FIXED -> Color.Transparent
        Field.AMOUNT -> if (dark) Color(0xFF81C995) else Color(0xFF2E7D32)
        Field.BALANCE -> if (dark) Color(0xFF80CBC4) else Color(0xFF00796B)
        Field.ACCOUNT -> if (dark) Color(0xFFB0BEC5) else Color(0xFF546E7A)
        Field.DATE -> if (dark) Color(0xFFFFB74D) else Color(0xFFE65100)
        Field.MERCHANT -> if (dark) Color(0xFF90CAF9) else Color(0xFF1565C0)
        Field.DIRECTION -> if (dark) Color(0xFFF48FB1) else Color(0xFFAD1457)
        Field.TYPE -> if (dark) Color(0xFFCE93D8) else Color(0xFF6A1B9A)
        Field.CHANNEL -> if (dark) Color(0xFFFFF176) else Color(0xFF9E7700)
        Field.OTHER -> if (dark) Color(0xFF9E9E9E) else Color(0xFF9E9E9E)
    }
}

/**
 * Shows the group's example message with each part coloured by its meaning. Tapping a part calls
 * [onTokenClick] with the token index so the caller can ask what it means.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HighlightedMessage(group: GroupTemplate, onTokenClick: ((Int) -> Unit)? = null) {
    val tokens = group.referenceTokens
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        tokens.forEachIndexed { i, token ->
            if (token.newlineBefore && i > 0) Spacer(Modifier.fillMaxWidth().height(0.dp))
            val field = BankLearner.fieldOf(group, i)
            val color = field.color()
            val clickable = if (onTokenClick != null) Modifier.clickable { onTokenClick(i) } else Modifier
            Box(
                Modifier
                    .background(if (field == Field.FIXED) Color.Transparent else color.copy(alpha = 0.18f), RoundedCornerShape(4.dp))
                    .then(clickable)
                    .padding(horizontal = 3.dp, vertical = 2.dp),
            ) {
                Text(
                    token.text,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                    color = if (field == Field.FIXED) MaterialTheme.colorScheme.onSurfaceVariant else color,
                    fontWeight = if (field == Field.FIXED || field == Field.OTHER) FontWeight.Normal else FontWeight.SemiBold,
                )
            }
        }
    }
}

/** Colour key for the fields that appear in [group]. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FieldLegend(group: GroupTemplate) {
    val used = group.referenceTokens.indices.map { BankLearner.fieldOf(group, it) }.filter { it != Field.FIXED }.distinct()
    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        used.forEach { f ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).background(f.color(), RoundedCornerShape(2.dp)))
                Spacer(Modifier.size(4.dp))
                Text(f.label(), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
