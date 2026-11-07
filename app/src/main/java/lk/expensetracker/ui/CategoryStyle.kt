package lk.expensetracker.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.CallMade
import androidx.compose.material.icons.filled.CallReceived
import androidx.compose.material.icons.filled.CardGiftcard
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.LocalGasStation
import androidx.compose.material.icons.filled.LocalHospital
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Savings
import androidx.compose.material.icons.filled.ShoppingBag
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.Subscriptions
import androidx.compose.material.icons.filled.Work
import androidx.compose.material.icons.filled.AttachMoney
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import lk.expensetracker.engine.classification.Categories

data class CategoryStyle(val icon: ImageVector, val light: Color, val dark: Color)

/** A fixed colour and icon per category, used in lists, bars and charts. */
object CategoryStyles {
    private val styles = mapOf(
        Categories.GROCERIES to CategoryStyle(Icons.Filled.ShoppingCart, Color(0xFF2E7D32), Color(0xFF81C995)),
        Categories.DINING to CategoryStyle(Icons.Filled.Restaurant, Color(0xFFE65100), Color(0xFFFFB74D)),
        Categories.FUEL to CategoryStyle(Icons.Filled.LocalGasStation, Color(0xFF6D4C41), Color(0xFFBCAAA4)),
        Categories.TRANSPORT to CategoryStyle(Icons.Filled.DirectionsCar, Color(0xFF1565C0), Color(0xFF90CAF9)),
        Categories.BILLS to CategoryStyle(Icons.Filled.Receipt, Color(0xFF00838F), Color(0xFF80DEEA)),
        Categories.SHOPPING to CategoryStyle(Icons.Filled.ShoppingBag, Color(0xFFAD1457), Color(0xFFF48FB1)),
        Categories.SUBSCRIPTIONS to CategoryStyle(Icons.Filled.Subscriptions, Color(0xFF6A1B9A), Color(0xFFCE93D8)),
        Categories.HEALTH to CategoryStyle(Icons.Filled.LocalHospital, Color(0xFFC62828), Color(0xFFEF9A9A)),
        Categories.GIFTS to CategoryStyle(Icons.Filled.CardGiftcard, Color(0xFFD81B60), Color(0xFFF8BBD0)),
        Categories.CASH to CategoryStyle(Icons.Filled.Payments, Color(0xFF558B2F), Color(0xFFC5E1A5)),
        Categories.BANK_CHARGES to CategoryStyle(Icons.Filled.AccountBalance, Color(0xFF546E7A), Color(0xFFB0BEC5)),
        Categories.TRANSFERS_OUT to CategoryStyle(Icons.Filled.CallMade, Color(0xFF4527A0), Color(0xFFB39DDB)),
        Categories.SALARY to CategoryStyle(Icons.Filled.Work, Color(0xFF2E7D32), Color(0xFFA5D6A7)),
        Categories.INTEREST to CategoryStyle(Icons.AutoMirrored.Filled.TrendingUp, Color(0xFF00695C), Color(0xFF80CBC4)),
        Categories.TRANSFERS_IN to CategoryStyle(Icons.Filled.CallReceived, Color(0xFF00897B), Color(0xFF80CBC4)),
        Categories.OTHER_INCOME to CategoryStyle(Icons.Filled.AttachMoney, Color(0xFF388E3C), Color(0xFFA5D6A7)),
        Categories.SAVINGS to CategoryStyle(Icons.Filled.Savings, Color(0xFF0277BD), Color(0xFF81D4FA)),
        Categories.REFUNDS to CategoryStyle(Icons.AutoMirrored.Filled.Undo, Color(0xFF757575), Color(0xFFBDBDBD)),
        "Remittance" to CategoryStyle(Icons.Filled.Public, Color(0xFF00838F), Color(0xFF80DEEA)),
    )
    private val fallback = CategoryStyle(Icons.Filled.Category, Color(0xFF5C6BC0), Color(0xFF9FA8DA))
    private val customPalette = listOf(
        Color(0xFF5C6BC0) to Color(0xFF9FA8DA), Color(0xFF8D6E63) to Color(0xFFBCAAA4),
        Color(0xFF26A69A) to Color(0xFF80CBC4), Color(0xFFEC407A) to Color(0xFFF48FB1),
    )

    fun of(category: String): CategoryStyle = styles[category] ?: if (category == Categories.OTHER) fallback else {
        val (l, d) = customPalette[(category.hashCode() and Int.MAX_VALUE) % customPalette.size]
        fallback.copy(light = l, dark = d)
    }
}

@Composable
fun categoryColor(category: String): Color = CategoryStyles.of(category).let { if (LocalDarkTheme.current) it.dark else it.light }

@Composable
fun CategoryIcon(category: String, size: Dp = 40.dp) {
    val color = categoryColor(category)
    Box(Modifier.size(size).background(color.copy(alpha = 0.16f), CircleShape), contentAlignment = Alignment.Center) {
        Icon(CategoryStyles.of(category).icon, contentDescription = category, tint = color, modifier = Modifier.size(size * 0.55f))
    }
}
