package lk.expensetracker.data

import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import lk.expensetracker.data.db.AppDatabase
import lk.expensetracker.data.db.BankPackEntity
import lk.expensetracker.data.db.MerchantCategoryEntity
import lk.expensetracker.data.db.TransactionEntity
import lk.expensetracker.data.db.UnparsedMessageEntity
import lk.expensetracker.data.db.UnparsedStatus
import lk.expensetracker.data.db.displayName
import lk.expensetracker.engine.bank.BankPack
import lk.expensetracker.engine.bank.BankPacks
import lk.expensetracker.engine.bank.BuiltInBanks
import lk.expensetracker.engine.classification.Categorizer
import lk.expensetracker.engine.classification.LedgerLinker
import lk.expensetracker.engine.classification.Link
import lk.expensetracker.engine.interpretation.Interpretation
import lk.expensetracker.engine.interpretation.InterpretationPipeline
import lk.expensetracker.engine.interpretation.RuleBasedInterpreter
import lk.expensetracker.engine.model.Kind
import lk.expensetracker.engine.model.LedgerEntry
import lk.expensetracker.engine.model.LinkType
import lk.expensetracker.engine.model.RawSms
import lk.expensetracker.engine.normalize.Money
import lk.expensetracker.engine.normalize.SmsHash
import java.time.Instant
import java.time.ZoneId

data class IngestResult(
    val added: List<TransactionEntity>,
    val duplicates: Int,
    val ignored: Int,
    val unparsed: Int,
)

/** Single entry point for turning SMS into stored transactions and for all user edits. */
class LedgerRepository(private val db: AppDatabase) {
    private val tx = db.transactions()
    private val mutex = Mutex()

    val transactions: Flow<List<TransactionEntity>> = tx.observeAll()
    val unparsed: Flow<List<UnparsedMessageEntity>> = db.unparsed().observeNew()
    val bankPacks: Flow<List<BankPackEntity>> = db.bankPacks().observeAll()
    val reviewCount: Flow<Int> = combine(tx.observeReviewCount(), unparsed) { a, b -> a + b.size }

    fun transaction(id: Long): Flow<TransactionEntity?> = tx.observe(id)

    // ---- Bank packs ---------------------------------------------------------------------------

    /** Seeds built-in packs and upgrades them when the app ships a newer version. */
    suspend fun ensureBuiltInBanks() {
        val dao = db.bankPacks()
        for (pack in BuiltInBanks.load()) {
            val existing = dao.get(pack.id)
            if (existing == null || (existing.builtIn && existing.builtInVersion < pack.version)) {
                dao.upsert(
                    BankPackEntity(
                        id = pack.id, json = BankPacks.encode(pack), enabled = existing?.enabled ?: true,
                        builtIn = true, builtInVersion = pack.version,
                    ),
                )
            }
        }
    }

    suspend fun enabledPacks(): List<BankPack> {
        ensureBuiltInBanks()
        return db.bankPacks().getAll().filter { it.enabled }.mapNotNull { runCatching { BankPacks.parse(it.json) }.getOrNull() }
    }

    suspend fun interpreter(): RuleBasedInterpreter = RuleBasedInterpreter(enabledPacks())

    suspend fun bankPack(id: String): BankPackEntity? = db.bankPacks().get(id)

    suspend fun saveBankPack(pack: BankPack) {
        val existing = db.bankPacks().get(pack.id)
        db.bankPacks().upsert(
            BankPackEntity(
                id = pack.id, json = BankPacks.encode(pack), enabled = existing?.enabled ?: true,
                builtIn = existing?.builtIn ?: false, builtInVersion = existing?.builtInVersion ?: 0,
            ),
        )
    }

    suspend fun setBankEnabled(id: String, enabled: Boolean) {
        db.bankPacks().get(id)?.let { db.bankPacks().upsert(it.copy(enabled = enabled, updatedAt = System.currentTimeMillis())) }
    }

    suspend fun deleteCustomBank(id: String) = db.bankPacks().deleteCustom(id)

    suspend fun resetBuiltInBank(id: String) {
        val pack = BuiltInBanks.load().firstOrNull { it.id == id } ?: return
        saveBankPack(pack)
    }

    // ---- Ingestion ----------------------------------------------------------------------------

    suspend fun ingest(messages: List<RawSms>): IngestResult = mutex.withLock {
        withContext(Dispatchers.IO) {
            val pipeline = InterpretationPipeline(listOf(interpreter()))
            val categorizer = Categorizer(db.merchantCategories().getAll().associate { it.merchantKey to it.category })
            val added = mutableListOf<TransactionEntity>()
            var duplicates = 0
            var ignored = 0
            var unparsedCount = 0

            db.withTransaction {
                for (sms in messages) {
                    val hash = SmsHash.of(sms.sender, sms.receivedAt, sms.body)
                    when (val result = pipeline.interpret(sms)) {
                        is Interpretation.Parsed -> {
                            val d = result.draft
                            val entity = TransactionEntity(
                                bankId = d.bankId, accountMask = d.accountMask, amountMinor = d.amountMinor, currency = d.currency,
                                direction = d.direction, kind = d.kind, channel = d.channel, merchantRaw = d.merchantRaw,
                                merchantKey = d.merchantKey, category = categorizer.categorize(d), occurredAt = d.occurredAt,
                                timeSource = d.timeSource, balanceMinor = d.balanceMinor, transactionType = d.transactionType,
                                interpreter = d.interpreter, ruleName = d.ruleName, confidence = d.confidence,
                                needsReview = d.confidence < 1f || d.kind == Kind.REVERSAL,
                                smsSender = sms.sender, smsBody = sms.body, smsReceivedAt = sms.receivedAt, smsHash = hash,
                            )
                            val id = tx.insert(entity)
                            if (id == -1L) duplicates++ else added += entity.copy(id = id)
                            db.unparsed().resolveByHash(hash)
                        }
                        is Interpretation.Ignored -> ignored++
                        is Interpretation.NotRecognized -> if (result.bankId != null) {
                            if (db.unparsed().insert(UnparsedMessageEntity(bankId = result.bankId, sender = sms.sender, body = sms.body, receivedAt = sms.receivedAt, smsHash = hash)) != -1L) {
                                unparsedCount++
                            }
                        }
                    }
                }
            }
            if (added.isNotEmpty()) relink()
            IngestResult(added, duplicates, ignored, unparsedCount)
        }
    }

    /** Re-runs the current rules over messages that previously failed to parse. */
    suspend fun reprocessUnparsed(): IngestResult {
        val pending = db.unparsed().getNew().map { RawSms(it.sender, it.body, it.receivedAt) }
        return ingest(pending)
    }

    /** Links reversals to their debits and suggests internal-transfer pairs. User decisions are kept. */
    private suspend fun relink() {
        val all = tx.getAll()
        val linkable = all.filter { !it.autoLinkDisabled }.map { it.toLedgerEntry() }
        val reversalLinks = LedgerLinker.findReversals(linkable)
        val pairLinks = LedgerLinker.findTransferPairs(applyLinks(linkable, reversalLinks))
        val byId = (reversalLinks + pairLinks).flatMap { l ->
            listOf(l.firstId to (l.firstType to l.secondId), l.secondId to (l.secondType to l.firstId))
        }.toMap()
        if (byId.isEmpty()) return
        val updates = all.mapNotNull { e ->
            val (type, other) = byId[e.id] ?: return@mapNotNull null
            e.copy(
                linkType = type, linkedId = other,
                needsReview = type == LinkType.SUGGESTED_INTERNAL_PAIR,
            )
        }
        tx.updateAll(updates)
    }

    private fun applyLinks(entries: List<LedgerEntry>, links: List<Link>) = entries.map { e ->
        links.firstOrNull { it.firstId == e.id }?.let { return@map e.copy(linkType = it.firstType, linkedId = it.secondId) }
        links.firstOrNull { it.secondId == e.id }?.let { return@map e.copy(linkType = it.secondType, linkedId = it.firstId) }
        e
    }

    // ---- User edits ---------------------------------------------------------------------------

    suspend fun setCategory(id: Long, category: String, applyToMerchant: Boolean) = db.withTransaction {
        val e = tx.get(id) ?: return@withTransaction
        tx.update(e.copy(category = category, userEditedCategory = true))
        if (applyToMerchant && e.merchantKey != null) {
            db.merchantCategories().upsert(MerchantCategoryEntity(e.merchantKey, category))
            tx.recategorizeMerchant(e.merchantKey, category)
        }
    }

    suspend fun setKind(id: Long, kind: Kind) {
        tx.get(id)?.let { tx.update(it.copy(kind = kind, userEditedKind = true, needsReview = false)) }
    }

    suspend fun setExcluded(id: Long, excluded: Boolean) {
        tx.get(id)?.let { tx.update(it.copy(excluded = excluded)) }
    }

    suspend fun setNote(id: Long, note: String) {
        tx.get(id)?.let { tx.update(it.copy(note = note.ifBlank { null })) }
    }

    suspend fun markReviewed(id: Long) {
        tx.get(id)?.let { tx.update(it.copy(needsReview = false)) }
    }

    /** Confirms a suggested pair (or any linked pair) as a transfer between the user's own accounts. */
    suspend fun confirmInternalPair(id: Long) = db.withTransaction {
        val a = tx.get(id) ?: return@withTransaction
        val b = a.linkedId?.let { tx.get(it) } ?: return@withTransaction
        tx.updateAll(listOf(a, b).map { it.copy(linkType = LinkType.INTERNAL_PAIR, needsReview = false) })
    }

    /** Removes a link (reversal or pair) and stops the linker from re-creating it. */
    suspend fun unlink(id: Long) = db.withTransaction {
        val a = tx.get(id) ?: return@withTransaction
        val b = a.linkedId?.let { tx.get(it) }
        tx.updateAll(listOfNotNull(a, b).map { it.copy(linkType = null, linkedId = null, needsReview = false, autoLinkDisabled = true) })
    }

    suspend fun ignoreUnparsed(id: Long) = db.unparsed().setStatus(id, UnparsedStatus.IGNORED)

    suspend fun unparsedMessage(id: Long): UnparsedMessageEntity? = db.unparsed().get(id)

    // ---- Export -------------------------------------------------------------------------------

    suspend fun exportCsv(zone: ZoneId = ZoneId.systemDefault()): String = withContext(Dispatchers.IO) {
        val header = "date,bank,account,direction,kind,category,amount,currency,merchant,balance,linked,excluded,note"
        val rows = tx.exportAll().sortedBy { it.occurredAt }.map { e ->
            listOf(
                Instant.ofEpochMilli(e.occurredAt).atZone(zone).toLocalDateTime().toString(),
                e.bankId, e.accountMask.orEmpty(), e.direction.name, e.kind.name, e.category,
                Money.format(e.amountMinor).replace(",", ""), e.currency, e.displayName(),
                e.balanceMinor?.let { Money.format(it).replace(",", "") }.orEmpty(),
                e.linkType?.name.orEmpty(), e.excluded.toString(), e.note.orEmpty(),
            ).joinToString(",") { field -> "\"" + field.replace("\"", "\"\"") + "\"" }
        }
        (listOf(header) + rows).joinToString("\n")
    }
}
