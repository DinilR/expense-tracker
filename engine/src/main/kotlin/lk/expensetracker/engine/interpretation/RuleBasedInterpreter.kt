package lk.expensetracker.engine.interpretation

import lk.expensetracker.engine.bank.BankPack
import lk.expensetracker.engine.bank.ClassificationRuleDef
import lk.expensetracker.engine.bank.ParseRuleDef
import lk.expensetracker.engine.bank.RegexGroups
import lk.expensetracker.engine.model.Channel
import lk.expensetracker.engine.model.Direction
import lk.expensetracker.engine.model.Kind
import lk.expensetracker.engine.model.RawSms
import lk.expensetracker.engine.model.TimeSource
import lk.expensetracker.engine.model.TransactionDraft
import lk.expensetracker.engine.normalize.MerchantKeys
import lk.expensetracker.engine.normalize.Money
import lk.expensetracker.engine.normalize.SmsDates
import java.time.ZoneId
import java.util.regex.Matcher
import java.util.regex.Pattern

/** Interprets SMS using the regex rules in [BankPack]s. Invalid patterns are skipped, not fatal. */
class RuleBasedInterpreter(packs: List<BankPack>) : MessageInterpreter {

    override val id: String = ID

    private val banks: List<CompiledBank> = packs.mapNotNull { runCatching { CompiledBank(it) }.getOrNull() }

    fun bankFor(sender: String): BankPack? = banks.firstOrNull { it.matchesSender(sender) }?.pack

    override suspend fun interpret(sms: RawSms): Interpretation = interpretNow(sms)

    /** Non-suspending variant for tests, previews and the rule editor's live test. */
    fun interpretNow(sms: RawSms): Interpretation {
        val bank = banks.firstOrNull { it.matchesSender(sms.sender) } ?: return Interpretation.NotRecognized(null)
        for (rule in bank.rules) {
            val matcher = rule.pattern.matcher(sms.body)
            if (!matcher.find()) continue
            if (rule.def.ignore) return Interpretation.Ignored(bank.pack.id, rule.def.name)
            val draft = bank.buildDraft(rule, matcher, sms) ?: continue
            return Interpretation.Parsed(draft)
        }
        return Interpretation.NotRecognized(bank.pack.id)
    }

    private class CompiledRule(val def: ParseRuleDef) {
        val pattern: Pattern = Pattern.compile(def.pattern)
        val groups: Set<String> = RegexGroups.namesIn(def.pattern)
        val cleanup: List<Regex> = def.merchantCleanup.mapNotNull { runCatching { Regex(it) }.getOrNull() }

        fun group(m: Matcher, name: String): String? =
            if (name in groups) m.group(name)?.trim()?.takeIf { it.isNotEmpty() } else null
    }

    private class CompiledClassification(val def: ClassificationRuleDef) {
        val regex = Regex(def.match)
        val direction = def.direction?.let { d -> Direction.entries.firstOrNull { it.name.equals(d, true) } }
    }

    private class CompiledBank(val pack: BankPack) {
        val senders = pack.senderPatterns.map { Regex(it) }
        val rules = pack.rules.sortedBy { it.priority }.map { CompiledRule(it) }
        val classification = pack.classification.map { CompiledClassification(it) }
        val zone: ZoneId = runCatching { ZoneId.of(pack.timezone) }.getOrDefault(ZoneId.systemDefault())

        fun matchesSender(sender: String) = senders.any { it.containsMatchIn(sender.trim()) }

        fun buildDraft(rule: CompiledRule, m: Matcher, sms: RawSms): TransactionDraft? {
            val def = rule.def
            val amount = Money.parseMinor(rule.group(m, "amount")) ?: return null
            val type = rule.group(m, "type")
            val typeMapping = type?.let { t -> def.typeMap.entries.firstOrNull { it.key.equals(t, true) }?.value }

            val rawDirection = rule.group(m, "direction")?.replace(spaces, " ")
            val mappedDirection = rawDirection?.let { d -> def.directionMap.entries.firstOrNull { it.key.replace(spaces, " ").equals(d, true) }?.value }
            val direction = parseDirection(mappedDirection ?: rawDirection) ?: parseDirection(typeMapping?.direction) ?: parseDirection(def.direction)
            var confidence = if (direction == null) 0.5f else 1f

            val dir = direction ?: Direction.DEBIT
            var channel = Channel.parse(rule.group(m, "channel"))
                ?: Channel.parse(typeMapping?.channel)
                ?: Channel.parse(def.channelDefault)
                ?: Channel.OTHER
            var kind = Kind.parse(typeMapping?.kind) ?: if (dir == Direction.DEBIT) Kind.EXPENSE else Kind.INCOME
            var category: String? = null

            val merchant = rule.group(m, "merchant")?.let { raw ->
                rule.cleanup.fold(raw) { acc, regex -> regex.replace(acc, "") }.trim().ifEmpty { null }
            }

            for (c in classification) {
                if (c.direction != null && c.direction != dir) continue
                val value = when (c.def.field.lowercase()) {
                    "merchant" -> merchant
                    "type" -> type
                    "channel" -> channel.name
                    "body" -> sms.body
                    else -> null
                } ?: continue
                if (!c.regex.containsMatchIn(value)) continue
                Kind.parse(c.def.kind)?.let { kind = it }
                Channel.parse(c.def.channel)?.let { channel = it }
                c.def.category?.let { category = it }
            }
            // Rule-level choices (made by the user when teaching a bank) win over generic classification.
            Kind.parse(def.kind)?.let { kind = it }
            def.category?.let { category = it }

            val textTime = SmsDates.parse(rule.group(m, "date"), def.dateFormat, zone, sms.receivedAt)
            if (rule.group(m, "date") != null && textTime == null) confidence = minOf(confidence, 0.8f)

            return TransactionDraft(
                bankId = pack.id,
                accountMask = rule.group(m, "account"),
                amountMinor = amount,
                currency = rule.group(m, "currency") ?: pack.currencyDefault,
                direction = dir,
                kind = kind,
                channel = channel,
                merchantRaw = merchant,
                merchantKey = MerchantKeys.of(merchant),
                category = category,
                occurredAt = textTime ?: sms.receivedAt,
                timeSource = if (textTime != null) TimeSource.SMS_TEXT else TimeSource.SMS_RECEIVED,
                balanceMinor = Money.parseMinor(rule.group(m, "balance")),
                transactionType = type,
                ruleName = def.name,
                interpreter = ID,
                confidence = confidence,
            )
        }

        private fun parseDirection(value: String?): Direction? =
            Direction.entries.firstOrNull { it.name.equals(value?.trim(), ignoreCase = true) }

        private val spaces = Regex("\\s+")
    }

    companion object {
        const val ID = "rules"
    }
}
