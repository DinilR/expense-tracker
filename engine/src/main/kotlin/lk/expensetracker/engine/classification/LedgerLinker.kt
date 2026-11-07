package lk.expensetracker.engine.classification

import lk.expensetracker.engine.model.Channel
import lk.expensetracker.engine.model.Direction
import lk.expensetracker.engine.model.Kind
import lk.expensetracker.engine.model.LedgerEntry
import lk.expensetracker.engine.model.LinkType

data class Link(val firstId: Long, val firstType: LinkType, val secondId: Long, val secondType: LinkType)

/** Finds relationships between transactions: reversals of earlier debits, and internal transfers. */
object LedgerLinker {
    const val REVERSAL_WINDOW_MS = 7L * 24 * 60 * 60 * 1000
    const val TRANSFER_WINDOW_MS = 30L * 60 * 1000
    private const val CLOCK_SKEW_MS = 5L * 60 * 1000

    /**
     * Each unlinked REVERSAL is paired with the most recent unlinked debit on the same account with the
     * same amount within [REVERSAL_WINDOW_MS] before it. Online debits are preferred over other debits.
     */
    fun findReversals(entries: List<LedgerEntry>): List<Link> {
        val sorted = entries.sortedBy { it.occurredAt }
        val used = entries.filter { it.linkType != null }.map { it.id }.toMutableSet()
        val links = mutableListOf<Link>()
        for (rev in sorted) {
            if (rev.kind != Kind.REVERSAL || rev.direction != Direction.CREDIT || rev.id in used) continue
            val candidates = sorted.filter {
                it.id !in used &&
                    it.direction == Direction.DEBIT &&
                    it.accountKey == rev.accountKey &&
                    it.amountMinor == rev.amountMinor &&
                    it.occurredAt <= rev.occurredAt + CLOCK_SKEW_MS &&
                    rev.occurredAt - it.occurredAt <= REVERSAL_WINDOW_MS
            }
            val target = candidates.lastOrNull { it.channel == Channel.ONLINE }
                ?: candidates.lastOrNull()
                ?: continue
            used += target.id
            used += rev.id
            links += Link(target.id, LinkType.REVERSED_BY, rev.id, LinkType.REVERSES)
        }
        return links
    }

    /**
     * A debit on one account and a credit of the same amount on a different account within
     * [TRANSFER_WINDOW_MS] are suggested as an internal transfer for the user to confirm.
     */
    fun findTransferPairs(entries: List<LedgerEntry>): List<Link> {
        val transferish = setOf(Kind.TRANSFER_OUT, Kind.TRANSFER_IN, Kind.EXPENSE, Kind.INCOME, Kind.INTERNAL_TRANSFER)
        val open = entries.filter { it.linkType == null && it.kind in transferish && !it.excluded }
        val debits = open.filter { it.direction == Direction.DEBIT }.sortedBy { it.occurredAt }
        val credits = open.filter { it.direction == Direction.CREDIT }.toMutableList()
        val links = mutableListOf<Link>()
        for (debit in debits) {
            val match = credits
                .filter { it.amountMinor == debit.amountMinor && it.accountKey != debit.accountKey }
                .filter { kotlin.math.abs(it.occurredAt - debit.occurredAt) <= TRANSFER_WINDOW_MS }
                .minByOrNull { kotlin.math.abs(it.occurredAt - debit.occurredAt) }
                ?: continue
            credits.remove(match)
            links += Link(debit.id, LinkType.SUGGESTED_INTERNAL_PAIR, match.id, LinkType.SUGGESTED_INTERNAL_PAIR)
        }
        return links
    }
}

/** How each entry counts towards the totals. */
object LedgerMath {
    fun countsAsExpense(e: LedgerEntry): Boolean =
        !e.excluded && e.kind in Kind.EXPENSE_KINDS && e.linkType != LinkType.REVERSED_BY && e.linkType != LinkType.INTERNAL_PAIR

    fun countsAsIncome(e: LedgerEntry): Boolean {
        if (e.excluded || e.linkType == LinkType.INTERNAL_PAIR) return false
        return e.kind in Kind.INCOME_KINDS || (e.kind == Kind.REVERSAL && e.linkType != LinkType.REVERSES)
    }

    fun isInternal(e: LedgerEntry): Boolean =
        !e.excluded && (e.kind == Kind.INTERNAL_TRANSFER || e.linkType == LinkType.INTERNAL_PAIR)

    data class Totals(val incomeMinor: Long, val expenseMinor: Long, val internalMinor: Long) {
        val netMinor: Long get() = incomeMinor - expenseMinor
        val savingsRate: Double? get() = if (incomeMinor > 0) netMinor.toDouble() / incomeMinor else null
    }

    fun totals(entries: Iterable<LedgerEntry>): Totals {
        var income = 0L
        var expense = 0L
        var internal = 0L
        for (e in entries) {
            when {
                countsAsExpense(e) -> expense += e.amountMinor
                countsAsIncome(e) -> income += e.amountMinor
                isInternal(e) && e.direction == Direction.DEBIT -> internal += e.amountMinor
            }
        }
        return Totals(income, expense, internal)
    }
}
