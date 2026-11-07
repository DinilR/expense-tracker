package lk.expensetracker.engine.learn

import java.util.regex.Pattern

enum class TokenType { AMOUNT, DATE, TIME, ACCOUNT, NUMBER, WORD, PUNCT }

/** A piece of an SMS. [spaceBefore] is the whitespace between the previous token and this one. */
data class Token(val type: TokenType, val text: String, val start: Int, val end: Int, val spaceBefore: String) {
    val newlineBefore: Boolean get() = '\n' in spaceBefore

    /** Types like amounts and dates vary in every message, so they compare by type, words by text. */
    val isTyped: Boolean get() = type == TokenType.AMOUNT || type == TokenType.DATE || type == TokenType.TIME || type == TokenType.ACCOUNT

    val symbol: String
        get() = when (type) {
            TokenType.AMOUNT -> "<A>"
            TokenType.DATE -> "<D>"
            TokenType.TIME -> "<T>"
            TokenType.ACCOUNT -> "<C>"
            TokenType.NUMBER -> "#$text"
            TokenType.WORD -> text.lowercase()
            TokenType.PUNCT -> text
        }
}

/** Splits SMS text into typed tokens. Order of the patterns matters: the first that matches wins. */
object Tokenizer {
    private const val MONTHS = "(?i:jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*"
    private const val CURRENCY = "(?:LKR|Rs\\.?|INR|USD|EUR|GBP|AUD|SGD|AED)"

    private val patterns: List<Pair<TokenType, Pattern>> = listOf(
        TokenType.DATE to "\\d{4}-\\d{1,2}-\\d{1,2}(?!\\d)|\\d{1,2}[/.-]\\d{1,2}[/.-]\\d{2,4}(?!\\d)|\\d{1,2}[- ]$MONTHS[- ,]+\\d{2,4}(?!\\d)",
        TokenType.TIME to "\\d{1,2}:\\d{2}(?::\\d{2})?(?:\\s?(?i:[ap]\\.?m\\.?)(?![a-z]))?",
        TokenType.AMOUNT to "$CURRENCY\\s?\\d[\\d,]*(?:\\.\\d{1,2})?(?:/-)?|\\d{1,3}(?:,\\d{3})+(?:\\.\\d{2})?(?!\\d)|\\d+\\.\\d{2}(?![\\d.])",
        TokenType.ACCOUNT to "[*Xx#]{2,}\\d{2,6}(?!\\d)",
        TokenType.NUMBER to "\\d+",
        TokenType.WORD to "[\\p{L}_][\\p{L}\\p{N}_'&]*",
        TokenType.PUNCT to "\\S",
    ).map { (type, p) -> type to Pattern.compile(p) }

    fun tokenize(text: String): List<Token> {
        val out = mutableListOf<Token>()
        var i = 0
        while (i < text.length) {
            val wsStart = i
            while (i < text.length && text[i].isWhitespace()) i++
            if (i >= text.length) break
            val space = text.substring(wsStart, i)
            for ((type, pattern) in patterns) {
                val m = pattern.matcher(text).region(i, text.length).useTransparentBounds(true)
                if (m.lookingAt() && m.end() > i) {
                    out += Token(type, text.substring(i, m.end()), i, m.end(), space)
                    i = m.end()
                    break
                }
            }
        }
        return out
    }
}

/** Words that tell which way money moved. */
internal object DirectionWords {
    private val debit = setOf(
        "debited", "debit", "dr", "withdrawn", "withdrawal", "withdraw", "paid", "payment", "purchase", "purchased",
        "spent", "sent", "charged", "deducted", "transferred",
    )
    private val credit = setOf("credited", "credit", "cr", "received", "deposited", "deposit", "refund", "refunded", "reversed", "reversal", "added")
    private val weak = mapOf("from" to "DEBIT", "to" to "CREDIT")
    private val letters = Regex("[\\p{L}]+")

    /** "DEBIT" / "CREDIT" from a clear word such as "debited", or null. */
    fun strong(text: String): String? {
        for (w in letters.findAll(text.lowercase()).map { it.value }) {
            if (w in debit) return "DEBIT"
            if (w in credit) return "CREDIT"
        }
        return null
    }

    /** "From" → DEBIT, "To" → CREDIT (as in "From A/C" / "To A/C"). */
    fun weak(text: String): String? = letters.findAll(text.lowercase()).map { weak[it.value] }.firstOrNull { it != null }
}
