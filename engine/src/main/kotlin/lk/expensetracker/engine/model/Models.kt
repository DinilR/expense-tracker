package lk.expensetracker.engine.model

/** An SMS as read from the inbox or received live. */
data class RawSms(
    val sender: String,
    val body: String,
    val receivedAt: Long,
    val providerId: Long? = null,
)

enum class Direction { DEBIT, CREDIT }

enum class Kind {
    EXPENSE, INCOME, FEE, CASH_WITHDRAWAL, TRANSFER_OUT, TRANSFER_IN, INTERNAL_TRANSFER, REVERSAL;

    companion object {
        val EXPENSE_KINDS = setOf(EXPENSE, FEE, CASH_WITHDRAWAL, TRANSFER_OUT)
        val INCOME_KINDS = setOf(INCOME, TRANSFER_IN)
        fun parse(value: String?): Kind? = entries.firstOrNull { it.name.equals(value?.trim(), ignoreCase = true) }
    }
}

enum class Channel {
    POS, ATM, ONLINE, TRANSFER, OTHER;

    companion object {
        fun parse(value: String?): Channel? = entries.firstOrNull { it.name.equals(value?.trim(), ignoreCase = true) }
    }
}

/** Where [TransactionDraft.occurredAt] came from: the SMS text, or the time the SMS arrived. */
enum class TimeSource { SMS_TEXT, SMS_RECEIVED }

/** Relationship between two transactions. Stored on both sides of the pair. */
enum class LinkType {
    /** This debit was cancelled by a later reversal. */
    REVERSED_BY,
    /** This reversal cancels an earlier debit. */
    REVERSES,
    /** Confirmed money movement between two of the user's own accounts. */
    INTERNAL_PAIR,
    /** Looks like an internal transfer; waiting for the user to confirm. */
    SUGGESTED_INTERNAL_PAIR;

    companion object {
        fun parse(value: String?): LinkType? = entries.firstOrNull { it.name == value }
    }
}

/** A transaction extracted from an SMS, before it is stored. */
data class TransactionDraft(
    val bankId: String,
    val accountMask: String?,
    val amountMinor: Long,
    val currency: String,
    val direction: Direction,
    val kind: Kind,
    val channel: Channel,
    val merchantRaw: String?,
    val merchantKey: String?,
    val category: String?,
    val occurredAt: Long,
    val timeSource: TimeSource,
    val balanceMinor: Long?,
    val transactionType: String?,
    val ruleName: String,
    val interpreter: String,
    val confidence: Float,
)

/** A stored transaction as the ledger algorithms (linking, totals, insights) see it. */
data class LedgerEntry(
    val id: Long,
    val bankId: String,
    val accountMask: String?,
    val amountMinor: Long,
    val direction: Direction,
    val kind: Kind,
    val channel: Channel,
    val category: String?,
    val merchantKey: String?,
    val occurredAt: Long,
    val linkType: LinkType? = null,
    val linkedId: Long? = null,
    val excluded: Boolean = false,
) {
    val accountKey: String get() = "$bankId:${accountMask.orEmpty()}"
}
