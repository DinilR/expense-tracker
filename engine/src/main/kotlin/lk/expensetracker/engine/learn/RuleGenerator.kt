package lk.expensetracker.engine.learn

import lk.expensetracker.engine.bank.BankPack
import lk.expensetracker.engine.bank.ClassificationRuleDef
import lk.expensetracker.engine.bank.ParseRuleDef
import lk.expensetracker.engine.interpretation.Interpretation
import lk.expensetracker.engine.interpretation.RuleBasedInterpreter
import lk.expensetracker.engine.model.Channel
import lk.expensetracker.engine.model.Direction
import lk.expensetracker.engine.model.Kind
import lk.expensetracker.engine.model.RawSms
import lk.expensetracker.engine.model.TransactionDraft
import lk.expensetracker.engine.normalize.SmsDates
import java.time.ZoneId

/** How one learned group performs: what it reads from its own messages. */
data class GroupSummary(
    val label: String,
    val total: Int,
    val understood: Int,
    val examples: List<TransactionDraft>,
)

data class Evaluation(
    val total: Int,
    val parsed: List<Pair<RawSms, TransactionDraft>>,
    val ignored: Int,
    val notUnderstood: List<RawSms>,
)

/** Turns learned groups into ordinary bank-pack rules, so the normal interpreter handles them. */
object RuleGenerator {
    private val zone: ZoneId = ZoneId.of("Asia/Colombo")

    private val dateFormats = listOf(
        "d/M/yyyy H:mm:ss", "d/M/yyyy H:mm", "d/M/yyyy h:mm:ss a", "d/M/yyyy h:mm a", "d/M/yy H:mm:ss", "d/M/yy H:mm", "d/M/yy h:mm a",
        "d-M-yyyy H:mm:ss", "d-M-yyyy H:mm", "d-M-yyyy h:mm a", "d-M-yy H:mm", "d.M.yyyy H:mm:ss", "d.M.yyyy H:mm",
        "yyyy-M-d H:mm:ss", "yyyy-M-d H:mm", "yyyy-M-d'T'H:mm:ss",
        "d-MMM-yyyy H:mm:ss", "d-MMM-yyyy H:mm", "d-MMM-yy H:mm", "d MMM yyyy H:mm", "d MMM yyyy h:mm a", "d-MMM-yyyy h:mm a",
        "d/M/yyyy", "d-M-yyyy", "d.M.yyyy", "d/M/yy", "d-M-yy", "yyyy-M-d", "d-MMM-yyyy", "d-MMM-yy", "d MMM yyyy", "d MMM, yyyy",
        "M/d/yyyy H:mm:ss", "M/d/yyyy H:mm", "M/d/yyyy h:mm a", "M/d/yyyy",
    )

    /** Generic meaning rules added to every learned bank; the user's per-group choices override them. */
    val defaultClassification: List<ClassificationRuleDef> = listOf("merchant", "type").flatMap { f ->
        listOf(
            ClassificationRuleDef(f, "(?i)(?<![a-z])(transfer|tfr|ceft|slips|fund ?transfer)(?![a-z])", direction = "DEBIT", kind = "TRANSFER_OUT", channel = "TRANSFER"),
            ClassificationRuleDef(f, "(?i)(?<![a-z])(transfer|tfr|ceft|slips|fund ?transfer)(?![a-z])", direction = "CREDIT", kind = "TRANSFER_IN", channel = "TRANSFER"),
            ClassificationRuleDef(f, "(?i)(?<![a-z])(ecom|online|internet|ipg|web)(?![a-z])", channel = "ONLINE"),
            ClassificationRuleDef(f, "(?i)(atm|cash) withdrawal", direction = "DEBIT", kind = "CASH_WITHDRAWAL", channel = "ATM", category = "Cash"),
            ClassificationRuleDef(f, "(?i)(?<![a-z])(fees?|chrg|charges?|commission)(?![a-z])", direction = "DEBIT", kind = "FEE", category = "Bank charges"),
            ClassificationRuleDef(f, "(?i)(?<![a-z])(interest|int\\.?pd)(?![a-z])", direction = "CREDIT", kind = "INCOME", category = "Interest"),
            ClassificationRuleDef(f, "(?i)(?<![a-z])(salary|slips|payroll)(?![a-z])", direction = "CREDIT", kind = "INCOME", category = "Salary"),
            ClassificationRuleDef(f, "(?i)(?<![a-z])(rev|reversal|reversed|refund)(?![a-z])", direction = "CREDIT", kind = "REVERSAL"),
            ClassificationRuleDef(f, "(?i)(?<![a-z])(remit|remittance|cb rem)(?![a-z])", direction = "CREDIT", kind = "INCOME", category = "Remittance"),
        )
    } + ClassificationRuleDef("channel", "^ATM$", direction = "DEBIT", kind = "CASH_WITHDRAWAL", category = "Cash")

    private val merchantCleanup = listOf("\\s+\\d{6,}$", "-\\d{9,}$", "[\\s(]+$")

    // ---- One group → one rule ---------------------------------------------------------------

    private data class Part(val sep: String, val regex: String, val lazy: Boolean, val captures: Boolean, val fixedOnSameLine: Boolean = false)

    fun rule(t: GroupTemplate, priority: Int, label: String? = null): ParseRuleDef? {
        if (!t.isTransaction) return null
        val layout = BankLearner.layout(t)
        val ref = t.referenceTokens
        val used = mutableSetOf<String>()
        val parts = mutableListOf<Part>()
        val directionMap = mutableMapOf<String, String>()
        var dateFormat: String? = null

        fun group(name: String, body: String): String? = if (used.add(name)) "(?<$name>$body)" else null

        val els = layout.elements
        var i = 0
        var prevAnchorRef: Int? = null
        while (i < els.size) {
            val el = els[i]
            when (el) {
                is Element.Anchor -> {
                    val sep = when {
                        parts.isEmpty() -> ""
                        prevAnchorRef != null && prevAnchorRef == el.refIndex - 1 -> if (ref[el.refIndex].spaceBefore.isEmpty()) "\\s*" else "\\s+"
                        else -> "\\s*"
                    }
                    if (el.field == Field.DATE) {
                        // Merge consecutive date/time anchors into one date.
                        var j = i
                        val run = mutableListOf<Element.Anchor>()
                        while (j < els.size && (els[j] as? Element.Anchor)?.field == Field.DATE) { run += els[j] as Element.Anchor; j++ }
                        val refText = run.joinToString(" ") { ref[it.refIndex].text }
                        val values = layout.alignedMessages.indices.map { mi -> run.joinToString(" ") { it.values[mi] } }
                        val generic = generalizeDate(refText)
                        val name = group("date", generic)
                        if (name != null) dateFormat = detectDateFormat(values)
                        parts += Part(sep, name ?: "(?:$generic)", lazy = false, captures = name != null)
                        prevAnchorRef = run.last().refIndex
                        i = j
                        continue
                    }
                    val token = ref[el.refIndex]
                    val body = when (el.field) {
                        Field.AMOUNT -> {
                            val cur = group("currency", "[A-Z]{3}")
                            val amt = group("amount", NUMBER)
                            "(?:${cur ?: "[A-Z]{3}"}|Rs\\.?)?\\s*${amt ?: NUMBER}(?:/-)?"
                        }
                        Field.BALANCE -> "(?:[A-Z]{3}|Rs\\.?)?\\s*${group("balance", NUMBER) ?: NUMBER}(?:/-)?"
                        Field.ACCOUNT -> "[*Xx#\\d]*?${group("account", "\\d{2,6}") ?: "\\d{2,6}"}"
                        Field.FIXED -> escape(token.text)
                        else -> genericFor(token)
                    }
                    parts += Part(
                        sep, body, lazy = false, captures = el.field in setOf(Field.AMOUNT, Field.BALANCE, Field.ACCOUNT),
                        fixedOnSameLine = !token.newlineBefore && el.field in setOf(Field.FIXED, Field.OTHER),
                    )
                    prevAnchorRef = el.refIndex
                }
                is Element.Gap -> {
                    val values = el.values.map { it.trim().replace(Regex("\\s+"), " ") }.filter { it.isNotEmpty() }
                    val part = when (el.field) {
                        Field.MERCHANT -> group("merchant", "[^\\r\\n]+?")?.let { Part("\\s*", it, lazy = true, captures = true) }
                        Field.TYPE -> group("type", "[^\\r\\n\\d]+?")?.let { Part("\\s*", it, lazy = true, captures = true) }
                        Field.CHANNEL -> group("channel", "[A-Za-z/]+")?.let { Part("\\s*", it, lazy = false, captures = true) }
                        Field.DIRECTION -> {
                            val distinct = values.distinct().sortedByDescending { it.length }
                            distinct.forEach { v -> (DirectionWords.strong(v) ?: DirectionWords.weak(v))?.let { directionMap[v] = it } }
                            if (distinct.isEmpty()) null
                            else group("direction", distinct.joinToString("|") { escape(it).replace(" ", "\\s+") })
                                ?.let { Part("\\s*", it, lazy = false, captures = true) }
                        }
                        else -> null
                    } ?: Part("\\s*", "[\\s\\S]*?", lazy = true, captures = false)
                    parts += part
                    prevAnchorRef = null
                }
            }
            i++
        }

        // Stop after the last captured field, but keep the fixed words that follow on the same line so the
        // rule stays specific (and a lazy field is bounded by what comes after it).
        val last = parts.indexOfLast { it.captures }
        if (last < 0 || "amount" !in used) return null
        var keep = if (parts[last].lazy && last + 1 < parts.size) last + 1 else last
        while (keep + 1 < parts.size && parts[keep + 1].fixedOnSameLine && keep - last < 6) keep++
        val body = parts.take(keep + 1).mapIndexed { k, p ->
            val regex = if (k == keep && p.lazy) p.regex.replace("+?)", "+)").replace("*?", "*") else p.regex
            p.sep + regex
        }.joinToString("")

        // No channel part in the message, but fixed wording such as "via POS at" still tells the channel.
        val channelDefault = if ("channel" in used) null else {
            val fixed = t.anchors.filter { !ref[it].isTyped }.map { ref[it].text.lowercase() }.toSet()
            when {
                "atm" in fixed && "pos" !in fixed -> "ATM"
                "pos" in fixed -> "POS"
                fixed.any { it == "ecom" || it == "online" || it == "ipg" } -> "ONLINE"
                else -> null
            }
        }

        return ParseRuleDef(
            name = label ?: "Learned ${t.id + 1}",
            label = label,
            priority = priority,
            pattern = "^\\s*$body",
            channelDefault = channelDefault,
            directionMap = directionMap,
            dateFormat = dateFormat,
            merchantCleanup = if ("merchant" in used) merchantCleanup else emptyList(),
            direction = t.direction?.name,
            kind = t.kind?.name,
            category = t.category,
        )
    }

    /** One rule that marks the non-transaction groups (promotions, notices) as ignored. */
    fun ignoreRule(ignored: List<GroupTemplate>, transactions: List<GroupTemplate>, priority: Int): ParseRuleDef? {
        val phrases = ignored.mapNotNull { t ->
            val ref = t.referenceTokens
            val start = ref.indices.takeWhile { t.messages.size < 3 || it in t.anchors }.take(4)
            if (start.isEmpty()) return@mapNotNull null
            start.mapIndexed { k, i -> (if (k == 0) "" else if (ref[i].spaceBefore.isEmpty()) "\\s*" else "\\s+") + if (ref[i].isTyped) "\\S+" else escape(ref[i].text) }
                .joinToString("")
        }.distinct().filter { phrase ->
            val r = Regex("^\\s*(?:$phrase)")
            transactions.none { tx -> r.containsMatchIn(tx.reference.body) }
        }
        if (phrases.isEmpty()) return null
        return ParseRuleDef(name = "Not transactions", label = "Not transactions", priority = priority, ignore = true, pattern = "^\\s*(?:${phrases.joinToString("|")})")
    }

    // ---- Whole bank -------------------------------------------------------------------------

    /**
     * Builds a bank pack from the groups. With [existing], the learned rules are added after the existing
     * ones (for teaching an extra message format to a bank the app already knows).
     */
    fun pack(id: String, displayName: String, sender: String, groups: List<GroupTemplate>, existing: BankPack? = null): BankPack {
        val tx = groups.filter { it.isTransaction }.sortedByDescending { it.messages.size }
        val firstPriority = if (existing == null) 10 else 50
        val base = existing ?: BankPack(
            id = id, displayName = displayName, senderPatterns = listOf("^" + escape(sender.trim()) + "$"),
            rules = emptyList(), classification = defaultClassification, origin = "learned",
        )
        val classification = if (existing == null) base.classification else existing.classification + defaultClassification.filter { it !in existing.classification }
        val rules = base.rules.toMutableList()
        var priority = firstPriority
        for (t in tx) {
            val label = summarize(t, classification).label
            val rule = rule(t, priority, label) ?: continue
            // Skip a group whose messages the rules so far already understand.
            if (rules.isNotEmpty()) {
                val soFar = RuleBasedInterpreter(listOf(base.copy(rules = rules, classification = classification)))
                if (t.messages.all { soFar.interpretNow(it.withSender(base)) is Interpretation.Parsed }) continue
            }
            rules += rule
            priority++
        }
        ignoreRule(groups.filter { !it.isTransaction }, tx, if (existing == null) 99 else 98)?.let { rules += it }
        return base.copy(rules = rules, classification = classification)
    }

    /** Reads a single group's messages with just its own rule, to label it and show examples. */
    fun summarize(t: GroupTemplate, classification: List<ClassificationRuleDef> = defaultClassification): GroupSummary {
        if (!t.isTransaction) return GroupSummary("Not transactions", t.messages.size, 0, emptyList())
        val rule = rule(t, 10) ?: return GroupSummary("Needs an amount", t.messages.size, 0, emptyList())
        val pack = BankPack("tmp", "tmp", senderPatterns = listOf(".*"), rules = listOf(rule), classification = classification)
        val interpreter = RuleBasedInterpreter(listOf(pack))
        val drafts = t.messages.mapNotNull { (interpreter.interpretNow(it) as? Interpretation.Parsed)?.draft }
        return GroupSummary(labelFor(drafts), t.messages.size, drafts.size, drafts.take(5))
    }

    fun evaluate(pack: BankPack, messages: List<RawSms>): Evaluation {
        val interpreter = RuleBasedInterpreter(listOf(pack))
        val parsed = mutableListOf<Pair<RawSms, TransactionDraft>>()
        val failed = mutableListOf<RawSms>()
        var ignored = 0
        for (sms in messages) {
            when (val r = interpreter.interpretNow(sms.withSender(pack))) {
                is Interpretation.Parsed -> parsed += sms to r.draft
                is Interpretation.Ignored -> ignored++
                is Interpretation.NotRecognized -> failed += sms
            }
        }
        return Evaluation(messages.size, parsed, ignored, failed)
    }

    private fun labelFor(drafts: List<TransactionDraft>): String {
        if (drafts.isEmpty()) return "Transactions"
        val debit = drafts.count { it.direction == Direction.DEBIT }
        val mixed = debit in 1 until drafts.size && minOf(debit, drafts.size - debit) > drafts.size / 10
        if (mixed) return "Money in and out"
        val kind = drafts.groupingBy { it.kind }.eachCount().maxBy { it.value }.key
        val channel = drafts.groupingBy { it.channel }.eachCount().maxBy { it.value }.key
        return when {
            kind == Kind.CASH_WITHDRAWAL -> "Cash withdrawals"
            kind == Kind.FEE -> "Bank charges"
            kind == Kind.REVERSAL -> "Refunds & reversals"
            kind == Kind.TRANSFER_OUT -> "Transfers out"
            kind == Kind.TRANSFER_IN -> "Transfers in"
            kind == Kind.INCOME -> drafts.groupingBy { it.category }.eachCount().maxBy { it.value }
                .takeIf { it.value * 2 > drafts.size }?.key?.takeIf { it == "Interest" || it == "Salary" } ?: "Money received"
            channel == Channel.POS -> "Card payments"
            channel == Channel.ONLINE -> "Online payments"
            debit == drafts.size -> "Payments"
            else -> "Transactions"
        }
    }

    // ---- Helpers ----------------------------------------------------------------------------

    private const val NUMBER = "\\d[\\d,]*(?:\\.\\d{1,2})?"

    private fun genericFor(token: Token): String = when (token.type) {
        TokenType.AMOUNT -> "(?:[A-Z]{3}|Rs\\.?)?\\s*$NUMBER(?:/-)?"
        TokenType.ACCOUNT -> "[*Xx#]*\\d+"
        TokenType.DATE, TokenType.TIME -> generalizeDate(token.text)
        TokenType.NUMBER -> "\\d+"
        else -> escape(token.text)
    }

    /** "07/11/2026 14:05:58" → \d{1,2}/\d{1,2}/\d{4}\s+\d{1,2}:\d{1,2}:\d{1,2} */
    internal fun generalizeDate(text: String): String = buildString {
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c.isDigit() -> {
                    var j = i
                    while (j < text.length && text[j].isDigit()) j++
                    append(if (j - i == 4) "\\d{4}" else "\\d{1,2}")
                    i = j
                }
                c.isLetter() -> {
                    var j = i
                    while (j < text.length && text[j].isLetter()) j++
                    append("[A-Za-z]{1,9}")
                    i = j
                }
                c.isWhitespace() -> {
                    while (i < text.length && text[i].isWhitespace()) i++
                    append("\\s+")
                }
                else -> { append(escape(c.toString())); i++ }
            }
        }
    }

    internal fun detectDateFormat(values: List<String>): String? {
        val sample = values.filter { it.isNotBlank() }.take(60)
        if (sample.isEmpty()) return null
        return dateFormats.firstOrNull { SmsDates.fits(sample, it, zone) }
    }

    internal fun escape(text: String): String = text.replace(Regex("[\\\\^$.|?*+()\\[\\]{}]")) { "\\" + it.value }

    /** Learned packs match on the sender; evaluation messages may come from a sample, so pin the sender. */
    private fun RawSms.withSender(pack: BankPack): RawSms {
        val pattern = pack.senderPatterns.firstOrNull() ?: return this
        return if (Regex(pattern).containsMatchIn(sender)) this else copy(sender = pattern.trim('^', '$').replace("\\", ""))
    }
}
