package lk.expensetracker.engine.classification

import lk.expensetracker.engine.model.Kind
import lk.expensetracker.engine.model.TransactionDraft

object Categories {
    const val GROCERIES = "Groceries"
    const val DINING = "Dining & food delivery"
    const val FUEL = "Fuel"
    const val TRANSPORT = "Transport"
    const val BILLS = "Telco & bills"
    const val SHOPPING = "Shopping"
    const val SUBSCRIPTIONS = "Subscriptions & digital"
    const val HEALTH = "Health"
    const val GIFTS = "Gifts"
    const val CASH = "Cash"
    const val BANK_CHARGES = "Bank charges"
    const val TRANSFERS_OUT = "Transfers out"
    const val SALARY = "Salary"
    const val INTEREST = "Interest"
    const val TRANSFERS_IN = "Transfers in"
    const val OTHER_INCOME = "Other income"
    const val SAVINGS = "Savings"
    const val REFUNDS = "Refunds"
    const val OTHER = "Other"

    val ALL = listOf(
        GROCERIES, DINING, FUEL, TRANSPORT, BILLS, SHOPPING, SUBSCRIPTIONS, HEALTH, GIFTS, CASH,
        BANK_CHARGES, TRANSFERS_OUT, SALARY, INTEREST, TRANSFERS_IN, OTHER_INCOME, SAVINGS, REFUNDS, OTHER,
    )
}

/** A merchant-key pattern that assigns a category. Order matters: the first match wins. */
data class CategoryRule(val pattern: Regex, val category: String)

object DefaultCategoryRules {
    val rules: List<CategoryRule> = listOf(
        "KEELLS|CARGILLS|FOOD CITY|ARPICO|GLOMARK|SPAR\\b|LAUGFS SUPER|CARAVAN FRESH|SUPER ?MARKET" to Categories.GROCERIES,
        "UBER EATS|PICKME FOOD|FOOD|KFC|PIZZA|MCDONALD|BURGER|CAFE|RESTAURANT|BAKERY|STARBUCKS|DOMINO" to Categories.DINING,
        "FILLING|FUEL|CEYPETCO|LANKA IOC|LIOC|SINOPEC" to Categories.FUEL,
        "^UBER|^PICKME|KANGAROO|TAXI|RAILWAY|EXPRESSWAY" to Categories.TRANSPORT,
        "DIALOG|MOBITEL|HUTCH|AIRTEL|\\bSLT\\b|CEB\\b|WATER BOARD|LECO" to Categories.BILLS,
        "DARAZ|SHEIN|ALIEXPRESS|AMAZON|ODEL|NOLIMIT|FASHION BUG|CARNAGE|THYAGA|KAPRUKA|ABANS|SINGER" to Categories.SHOPPING,
        "GOOGLE|NETFLIX|SPOTIFY|YOUTUBE|APPLE|MICROSOFT|ADOBE|OPENAI|PATREON|ONLYFANS|STEAM" to Categories.SUBSCRIPTIONS,
        "PHARM|HOSPITAL|OSU|ASIRI|NAWALOKA|HEALTHGUARD|DURDANS|HEMAS" to Categories.HEALTH,
        "FLOWER" to Categories.GIFTS,
    ).map { (p, c) -> CategoryRule(Regex(p), c) }
}

/**
 * Picks a category for a draft. Precedence: the user's per-merchant choice, then the bank pack's
 * classification, then the default merchant rules, then a default by transaction kind.
 */
class Categorizer(
    private val userOverrides: Map<String, String> = emptyMap(),
    private val rules: List<CategoryRule> = DefaultCategoryRules.rules,
) {
    fun categorize(draft: TransactionDraft): String {
        draft.merchantKey?.let { key -> userOverrides[key]?.let { return it } }
        draft.category?.let { return it }
        if (draft.kind == Kind.EXPENSE) {
            draft.merchantKey?.let { key -> rules.firstOrNull { it.pattern.containsMatchIn(key) }?.let { return it.category } }
        }
        return byKind(draft.kind)
    }

    companion object {
        fun byKind(kind: Kind): String = when (kind) {
            Kind.EXPENSE -> Categories.OTHER
            Kind.INCOME -> Categories.OTHER_INCOME
            Kind.FEE -> Categories.BANK_CHARGES
            Kind.CASH_WITHDRAWAL -> Categories.CASH
            Kind.TRANSFER_OUT -> Categories.TRANSFERS_OUT
            Kind.TRANSFER_IN -> Categories.TRANSFERS_IN
            Kind.INTERNAL_TRANSFER -> Categories.SAVINGS
            Kind.REVERSAL -> Categories.REFUNDS
        }
    }
}
