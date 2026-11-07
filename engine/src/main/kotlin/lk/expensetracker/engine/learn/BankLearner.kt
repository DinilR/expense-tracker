package lk.expensetracker.engine.learn

import lk.expensetracker.engine.model.Direction
import lk.expensetracker.engine.model.Kind
import lk.expensetracker.engine.model.RawSms
import kotlin.math.ceil
import kotlin.math.max

/** What a part of a message means. FIXED parts are the same in every message of the group. */
enum class Field { FIXED, AMOUNT, BALANCE, ACCOUNT, DATE, MERCHANT, DIRECTION, TYPE, CHANNEL, OTHER }

/**
 * One "kind of message" from a sender, e.g. card payments. The reference message is shown to the user;
 * [anchors] are reference tokens that appear in (almost) every message of the group. Everything between
 * anchors is a gap whose content varies. Immutable so the UI can hold it in state.
 */
data class GroupTemplate(
    val id: Int,
    val messages: List<RawSms>,
    val tokens: List<List<Token>>,
    val ref: Int,
    val anchors: Set<Int>,
    /** Meaning of anchor tokens (typed tokens: amount, date, …). Word anchors default to FIXED. */
    val anchorFields: Map<Int, Field>,
    /** Meaning of each gap, keyed by the anchor index before it (-1 for the start). */
    val gapFields: Map<Int, Field>,
    val isTransaction: Boolean,
    /** Used when no part of the message says which way the money moved. */
    val direction: Direction? = null,
    /** User/auto choice applied to every message of the group. */
    val kind: Kind? = null,
    val category: String? = null,
) {
    val reference: RawSms get() = messages[ref]
    val referenceTokens: List<Token> get() = tokens[ref]
}

/** The group laid out as alternating anchors and gaps, with the values seen in each aligned message. */
data class Layout(val elements: List<Element>, val alignedMessages: List<Int>)

sealed interface Element {
    val field: Field
    val values: List<String>

    data class Anchor(val refIndex: Int, override val field: Field, override val values: List<String>) : Element

    data class Gap(val key: Int, val refRange: IntRange, override val field: Field, override val values: List<String>) : Element
}

/** Learns message formats from the SMS of one sender. Pure and deterministic: no network, no AI. */
object BankLearner {
    private const val ANCHOR_SHARE = 0.9
    private const val SPLIT_SHARE = 0.15

    private val promoWords = Regex(
        "(?i)(?<![a-z])(win|won|winner|draw|offer|offers|promo|promotion|congratulations|earned|entry|entries|discount|visit|download|vote|apply now|reminder)(?![a-z])",
    )
    /** Money did not actually move, so these are not counted by default. */
    private val failedWords = Regex("(?i)(?<![a-z])(declined|failed|unsuccessful|rejected|insufficient|blocked)(?![a-z])")
    private val balanceWords = setOf("bal", "balance", "avl", "available", "avail")
    private val channelWords = setOf("pos", "atm", "ecom", "online", "card", "cdm", "crm", "ipg", "qr", "pos/atm")
    private val prepositions = setOf("at", "for", "to", "from", "@", "by", "towards", "merchant")
    private val notMerchant = setOf("ac", "a", "c", "account", "acct", "card", "no", "number", "your", "the", "ending", "a/c", "/")

    // ---- Analysis ---------------------------------------------------------------------------

    fun analyze(messages: List<RawSms>): List<GroupTemplate> {
        if (messages.isEmpty()) return emptyList()
        val tokenized = messages.map { Tokenizer.tokenize(it.body) }
        val byStructure = messages.indices.groupBy { structuralKey(tokenized[it]) }
        return byStructure.values
            .flatMap { build(it, messages, tokenized, depth = 0) }
            .sortedByDescending { it.messages.size }
            .mapIndexed { i, t -> t.copy(id = i) }
    }

    /** Amounts, dates, accounts and line breaks in order: messages of one format share this. */
    private fun structuralKey(tokens: List<Token>): String = buildString {
        tokens.forEach { t ->
            if (t.newlineBefore) append('|')
            if (t.isTyped) append(t.symbol)
        }
    }

    private fun build(idx: List<Int>, all: List<RawSms>, tokenized: List<List<Token>>, depth: Int): List<GroupTemplate> {
        val msgs = idx.map { all[it] }
        val toks = idx.map { tokenized[it] }
        val ref = toks.indices.minByOrNull { toks[it].size } ?: return emptyList()
        val base = GroupTemplate(0, msgs, toks, ref, chooseAnchors(toks, ref), emptyMap(), emptyMap(), true)
        val layout = layout(base)

        if (depth < 3) {
            // Messages that do not fit the group's skeleton get a group of their own.
            val unaligned = toks.indices - layout.alignedMessages.toSet()
            if (unaligned.isNotEmpty() && layout.alignedMessages.isNotEmpty()) {
                val aligned = layout.alignedMessages.map { idx[it] }
                return build(aligned, all, tokenized, depth + 1) + build(unaligned.map { idx[it] }, all, tokenized, depth + 1)
            }
            // A free-text part that starts with a few different words ("via POS at …" / "for eCom …") → split.
            if (msgs.size >= 6) splitByLeadingWord(layout, toks)?.let { parts ->
                return parts.flatMap { part -> build(part.map { idx[it] }, all, tokenized, depth + 1) }
            }
        }
        return listOf(autoAssign(base, layout))
    }

    private fun chooseAnchors(toks: List<List<Token>>, ref: Int): Set<Int> {
        val refTokens = toks[ref]
        if (toks.size < 3) return heuristicAnchors(refTokens)
        val refSymbols = refTokens.map { it.symbol }
        val counts = IntArray(refSymbols.size)
        toks.forEach { other -> lcsPairs(refSymbols, other.map { it.symbol }).forEach { counts[it.first]++ } }
        val needed = ceil(ANCHOR_SHARE * toks.size).toInt()
        return refSymbols.indices.filter { counts[it] >= needed }.toSet()
    }

    /** With one or two examples, treat text after "at"/"for"/… as the varying merchant, everything else as fixed. */
    private fun heuristicAnchors(tokens: List<Token>): Set<Int> {
        val variable = mutableSetOf<Int>()
        var i = 0
        while (i < tokens.size) {
            val t = tokens[i]
            if (t.type == TokenType.WORD && t.text.lowercase() in prepositions) {
                var j = i + 1
                val run = mutableListOf<Int>()
                while (j < tokens.size && !tokens[j].isTyped && !(tokens[j].newlineBefore && run.isNotEmpty()) &&
                    tokens[j].text.lowercase() !in setOf("on", ".", ",") && tokens[j].text.lowercase() !in prepositions
                ) {
                    run += j
                    j++
                }
                if (run.isNotEmpty() && run.any { tokens[it].text.lowercase() !in notMerchant }) variable += run
                i = j
            } else {
                i++
            }
        }
        return tokens.indices.filter { it !in variable || tokens[it].isTyped }.toSet()
    }

    private fun splitByLeadingWord(layout: Layout, toks: List<List<Token>>): List<List<Int>>? {
        val n = layout.alignedMessages.size
        for (gap in layout.elements.filterIsInstance<Element.Gap>()) {
            if (isCategorical(gap.values, n)) continue
            val firstWord = gap.values.map { v -> Tokenizer.tokenize(v).firstOrNull()?.symbol ?: "" }
            val groups = layout.alignedMessages.indices.groupBy { firstWord[it] }
            val big = groups.filter { (word, members) -> word.isNotEmpty() && members.size >= SPLIT_SHARE * n }
            // Split only on a few structural words ("via …" / "for …") that cover nearly every message,
            // never because one merchant happens to be common.
            val topCover = groups.values.map { it.size }.sortedDescending().take(4).sum()
            if (big.size >= 2 && topCover >= 0.9 * n) {
                val bigMembers = big.values.map { members -> members.map { layout.alignedMessages[it] } }
                val rest = layout.alignedMessages.indices.filter { firstWord[it] !in big.keys }.map { layout.alignedMessages[it] }
                return bigMembers + listOfNotNull(rest.takeIf { it.isNotEmpty() })
            }
        }
        return null
    }

    // ---- Layout -----------------------------------------------------------------------------

    /** Aligns every message to the anchors and collects the text of each anchor and gap. */
    fun layout(t: GroupTemplate): Layout {
        val ref = t.referenceTokens
        val anchors = t.anchors.sorted()
        val anchorSymbols = anchors.map { ref[it].symbol }
        val aligned = mutableListOf<Int>()
        val anchorValues = anchors.map { mutableListOf<String>() }
        val gapValues = (0..anchors.size).map { mutableListOf<String>() }

        t.tokens.forEachIndexed { mi, tokens ->
            val pairs = lcsPairs(anchorSymbols, tokens.map { it.symbol })
            if (pairs.size != anchors.size) return@forEachIndexed
            aligned += mi
            val pos = pairs.map { it.second }
            val body = t.messages[mi].body
            pos.forEachIndexed { k, p -> anchorValues[k] += tokens[p].text }
            for (g in 0..anchors.size) {
                val from = if (g == 0) 0 else pos[g - 1] + 1
                val to = if (g == anchors.size) tokens.size else pos[g]
                gapValues[g] += if (from < to) body.substring(tokens[from].start, tokens[to - 1].end) else ""
            }
        }

        val elements = mutableListOf<Element>()
        for (g in 0..anchors.size) {
            val refFrom = if (g == 0) 0 else anchors[g - 1] + 1
            val refTo = if (g == anchors.size) ref.size else anchors[g]
            val key = if (g == 0) -1 else anchors[g - 1]
            if (refFrom < refTo || gapValues[g].any { it.isNotBlank() }) {
                elements += Element.Gap(key, refFrom until refTo, t.gapFields[key] ?: Field.OTHER, gapValues[g])
            }
            if (g < anchors.size) {
                val i = anchors[g]
                val default = if (ref[i].isTyped) Field.OTHER else Field.FIXED
                elements += Element.Anchor(i, t.anchorFields[i] ?: default, anchorValues[g])
            }
        }
        return Layout(elements, aligned)
    }

    private fun isCategorical(values: List<String>, n: Int): Boolean {
        val nonBlank = values.map { it.trim().replace(Regex("\\s+"), " ") }.filter { it.isNotEmpty() }
        if (nonBlank.size < 3) return false
        val distinct = nonBlank.toSet()
        return distinct.size <= 8 && distinct.size <= max(2, n / 3) && distinct.all { it.split(' ').size <= 4 }
    }

    // ---- Automatic meaning ------------------------------------------------------------------

    private fun autoAssign(t: GroupTemplate, layout: Layout): GroupTemplate {
        val ref = t.referenceTokens
        val anchors = t.anchors.sorted()
        val anchorFields = mutableMapOf<Int, Field>()

        // Amounts: the first is the amount, one after "Bal"/"available" is the balance.
        val amounts = anchors.filter { ref[it].type == TokenType.AMOUNT }
        amounts.forEachIndexed { n, i ->
            val before = (maxOf(0, i - 3) until i).map { ref[it].text.lowercase() }
            anchorFields[i] = when {
                n == 0 -> Field.AMOUNT
                before.any { it in balanceWords } && Field.BALANCE !in anchorFields.values -> Field.BALANCE
                else -> Field.OTHER
            }
        }
        anchors.filter { ref[it].type == TokenType.ACCOUNT }.forEachIndexed { n, i -> anchorFields[i] = if (n == 0) Field.ACCOUNT else Field.OTHER }
        // Dates: prefer one followed by a time; otherwise the last date (earlier ones are usually periods like "Int.Pd").
        val dates = anchors.filter { ref[it].type == TokenType.DATE }
        val chosenDate = dates.firstOrNull { (it + 1) in t.anchors && ref[it + 1].type == TokenType.TIME } ?: dates.lastOrNull()
        dates.forEach { anchorFields[it] = if (it == chosenDate) Field.DATE else Field.OTHER }
        anchors.filter { ref[it].type == TokenType.TIME }.forEach {
            anchorFields[it] = if (chosenDate != null && it == chosenDate + 1) Field.DATE else Field.OTHER
        }

        // Gaps: a handful of short repeated values is a category (direction / channel / type); long varied text is the merchant.
        val n = layout.alignedMessages.size
        val gaps = layout.elements.filterIsInstance<Element.Gap>().filter { g -> g.values.any { it.isNotBlank() } }
        val gapFields = mutableMapOf<Int, Field>()
        val categorical = gaps.filter { isCategorical(it.values, n) }
        val directionGap = categorical.firstOrNull { g -> g.values.filter { it.isNotBlank() }.all { DirectionWords.strong(it) != null } }
            ?: categorical.firstOrNull { g -> g.values.filter { it.isNotBlank() }.all { DirectionWords.weak(it) != null } }
        directionGap?.let { gapFields[it.key] = Field.DIRECTION }
        categorical.filter { it !== directionGap }.forEach { g ->
            val words = g.values.filter { it.isNotBlank() }.map { it.trim().lowercase() }
            gapFields[g.key] = when {
                words.all { it in channelWords } && Field.CHANNEL !in gapFields.values -> Field.CHANNEL
                Field.TYPE !in gapFields.values -> Field.TYPE
                else -> Field.OTHER
            }
        }
        gaps.filter { it.key !in gapFields }
            .filter { g -> g.values.count { v -> v.any { it.isLetter() } } >= g.values.count { it.isNotBlank() } / 2 }
            .maxByOrNull { g -> g.values.sumOf { it.length }.toDouble() / g.values.size.coerceAtLeast(1) }
            ?.let { gapFields[it.key] = Field.MERCHANT }

        val fixedText = anchors.filter { !ref[it].isTyped }.joinToString(" ") { ref[it].text }
        val direction = if (directionGap == null) DirectionWords.strong(fixedText)?.let { Direction.valueOf(it) } else null
        val hasAmount = Field.AMOUNT in anchorFields.values
        val isTransaction = hasAmount && !promoWords.containsMatchIn(t.reference.body) && !failedWords.containsMatchIn(fixedText) &&
            (Field.ACCOUNT in anchorFields.values || directionGap != null || direction != null)

        val (kind, category) = kindFromText(fixedText, direction)
        return t.copy(
            anchorFields = anchorFields, gapFields = gapFields, isTransaction = isTransaction,
            direction = direction, kind = kind, category = category,
        )
    }

    /** Messages whose fixed wording names the transaction ("Interest", "ATM withdrawal") get that kind up front. */
    private fun kindFromText(text: String, direction: Direction?): Pair<Kind?, String?> {
        val t = text.lowercase()
        fun has(vararg w: String) = w.any { Regex("(?<![a-z])$it(?![a-z])").containsMatchIn(t) }
        return when {
            has("interest") && direction != Direction.DEBIT -> Kind.INCOME to "Interest"
            has("salary", "payroll") -> Kind.INCOME to "Salary"
            has("atm withdrawal", "cash withdrawal") -> Kind.CASH_WITHDRAWAL to "Cash"
            has("fee", "fees", "charge", "charges", "commission") && direction != Direction.CREDIT -> Kind.FEE to "Bank charges"
            has("refund", "reversal", "reversed") -> Kind.REVERSAL to null
            else -> null to null
        }
    }

    // ---- Editing (used by the teach-a-bank screens) -----------------------------------------

    /** Which gap a reference token belongs to (key = previous anchor index, -1 for the start), or null for anchors. */
    fun gapKeyOf(t: GroupTemplate, refIndex: Int): Int? =
        if (refIndex in t.anchors) null else t.anchors.filter { it < refIndex }.maxOrNull() ?: -1

    fun fieldOf(t: GroupTemplate, refIndex: Int): Field {
        val token = t.referenceTokens[refIndex]
        return if (refIndex in t.anchors) t.anchorFields[refIndex] ?: if (token.isTyped) Field.OTHER else Field.FIXED
        else t.gapFields[gapKeyOf(t, refIndex)] ?: Field.OTHER
    }

    /** Changes the meaning of one reference token, keeping the rest of the template consistent. */
    fun setField(t: GroupTemplate, refIndex: Int, field: Field): GroupTemplate {
        val token = t.referenceTokens[refIndex]
        val keepAsAnchor = field == Field.FIXED || (token.isTyped && field in setOf(Field.AMOUNT, Field.BALANCE, Field.ACCOUNT, Field.DATE, Field.OTHER))
        // A field can only be used once per message: clear it elsewhere. Dates may span a date and a time token.
        fun Map<Int, Field>.without(f: Field) = if (f == Field.OTHER || f == Field.FIXED || f == Field.DATE) this else filterValues { it != f }
        return if (keepAsAnchor) {
            val oldGap = gapKeyOf(t, refIndex)
            val anchors = t.anchors + refIndex
            // Splitting a gap: both halves keep the old gap's meaning.
            val gapFields = t.gapFields.toMutableMap()
            if (oldGap != null) t.gapFields[oldGap]?.let { gapFields[refIndex] = it }
            t.copy(
                anchors = anchors,
                anchorFields = t.anchorFields.without(field) + (refIndex to if (field == Field.FIXED && token.isTyped) Field.FIXED else field),
                gapFields = gapFields,
            )
        } else {
            val anchors = t.anchors - refIndex
            val tmp = t.copy(anchors = anchors, anchorFields = t.anchorFields - refIndex)
            val key = gapKeyOf(tmp, refIndex)!!
            // Removing an anchor merges two gaps; drop the meaning of the right-hand one.
            tmp.copy(gapFields = (tmp.gapFields - refIndex).without(field) + (key to field))
        }
    }

    // ---- LCS --------------------------------------------------------------------------------

    /** Longest common subsequence as (index in a, index in b) pairs. */
    internal fun lcsPairs(a: List<String>, b: List<String>): List<Pair<Int, Int>> {
        val n = a.size
        val m = b.size
        if (n == 0 || m == 0) return emptyList()
        val dp = Array(n + 1) { IntArray(m + 1) }
        for (i in n - 1 downTo 0) for (j in m - 1 downTo 0) {
            dp[i][j] = if (a[i] == b[j]) dp[i + 1][j + 1] + 1 else max(dp[i + 1][j], dp[i][j + 1])
        }
        val out = ArrayList<Pair<Int, Int>>(dp[0][0])
        var i = 0
        var j = 0
        while (i < n && j < m) {
            when {
                a[i] == b[j] -> { out += i to j; i++; j++ }
                dp[i + 1][j] >= dp[i][j + 1] -> i++
                else -> j++
            }
        }
        return out
    }
}
