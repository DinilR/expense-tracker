package lk.expensetracker.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import lk.expensetracker.engine.model.Channel
import lk.expensetracker.engine.model.Direction
import lk.expensetracker.engine.model.Kind
import lk.expensetracker.engine.model.LedgerEntry
import lk.expensetracker.engine.model.LinkType
import lk.expensetracker.engine.model.TimeSource

@Entity(
    tableName = "transactions",
    indices = [Index(value = ["smsHash"], unique = true), Index("occurredAt"), Index("merchantKey")],
)
data class TransactionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bankId: String,
    val accountMask: String?,
    val amountMinor: Long,
    val currency: String,
    val direction: Direction,
    val kind: Kind,
    val channel: Channel,
    val merchantRaw: String?,
    val merchantKey: String?,
    val category: String,
    val occurredAt: Long,
    val timeSource: TimeSource,
    val balanceMinor: Long?,
    val transactionType: String?,
    val note: String? = null,
    val excluded: Boolean = false,
    val linkType: LinkType? = null,
    val linkedId: Long? = null,
    val interpreter: String,
    val ruleName: String,
    val confidence: Float,
    val needsReview: Boolean,
    val userEditedCategory: Boolean = false,
    val userEditedKind: Boolean = false,
    /** Set when the user removed an automatic link, so the linker leaves this transaction alone. */
    val autoLinkDisabled: Boolean = false,
    val smsSender: String,
    val smsBody: String,
    val smsReceivedAt: Long,
    val smsHash: String,
) {
    fun toLedgerEntry() = LedgerEntry(
        id = id, bankId = bankId, accountMask = accountMask, amountMinor = amountMinor, direction = direction,
        kind = kind, channel = channel, category = category, merchantKey = merchantKey, occurredAt = occurredAt,
        linkType = linkType, linkedId = linkedId, excluded = excluded,
    )
}

/** Text shown as the transaction title. BOC messages have no merchant, so fall back to the type. */
fun TransactionEntity.displayName(): String =
    merchantRaw ?: transactionType ?: "${channel.name.lowercase().replaceFirstChar { it.uppercase() }} ($bankId)"

enum class UnparsedStatus { NEW, IGNORED, RESOLVED }

@Entity(tableName = "unparsed_messages", indices = [Index(value = ["smsHash"], unique = true)])
data class UnparsedMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bankId: String?,
    val sender: String,
    val body: String,
    val receivedAt: Long,
    val smsHash: String,
    val status: UnparsedStatus = UnparsedStatus.NEW,
)

@Entity(tableName = "bank_packs")
data class BankPackEntity(
    @PrimaryKey val id: String,
    val json: String,
    val enabled: Boolean = true,
    val builtIn: Boolean = false,
    val builtInVersion: Int = 0,
    val updatedAt: Long = System.currentTimeMillis(),
)

/** The user's "always use this category for this merchant" choices. */
@Entity(tableName = "merchant_categories")
data class MerchantCategoryEntity(
    @PrimaryKey val merchantKey: String,
    val category: String,
)
