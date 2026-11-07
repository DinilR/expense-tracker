package lk.expensetracker.engine.normalize

import java.math.BigDecimal
import java.math.RoundingMode
import java.security.MessageDigest
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object Money {
    /** "2,649.36" -> 264936. Returns null for anything that is not a number. */
    fun parseMinor(text: String?): Long? {
        val cleaned = text?.replace(",", "")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return runCatching { BigDecimal(cleaned).setScale(2, RoundingMode.HALF_UP).movePointRight(2).longValueExact() }.getOrNull()
    }

    /** 264936 -> "2,649.36" */
    fun format(minor: Long): String {
        val value = BigDecimal.valueOf(minor).movePointLeft(2)
        return "%,.2f".format(java.util.Locale.US, value)
    }
}

object SmsDates {
    private val whitespace = Regex("\\s+")
    private val amPmDots = Regex("(?i)([ap])\\.?m\\.?")
    private val formatters = java.util.concurrent.ConcurrentHashMap<String, DateTimeFormatter>()

    private fun formatter(format: String): DateTimeFormatter = formatters.getOrPut(format) {
        java.time.format.DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern(format).toFormatter(java.util.Locale.ENGLISH)
    }

    private fun hasTime(format: String) = format.any { it == 'H' || it == 'h' }

    /**
     * Parses a date written in the SMS in the bank's timezone. Returns epoch millis or null.
     * Date-only formats use [fallback] (the SMS time) when it is on the same day, otherwise noon.
     */
    fun parse(text: String?, format: String?, zone: ZoneId, fallback: Long? = null): Long? {
        if (text.isNullOrBlank() || format.isNullOrBlank()) return null
        val normalized = text.trim().replace(whitespace, " ").replace(amPmDots) { it.groupValues[1].uppercase() + "M" }
        return runCatching {
            if (hasTime(format)) {
                LocalDateTime.parse(normalized, formatter(format)).atZone(zone).toInstant().toEpochMilli()
            } else {
                val date = java.time.LocalDate.parse(normalized, formatter(format))
                val fallbackDate = fallback?.let { java.time.Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }
                if (fallback != null && fallbackDate == date) fallback
                else date.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
            }
        }.getOrNull()
    }

    /** True when every value parses with [format]. */
    fun fits(values: List<String>, format: String, zone: ZoneId): Boolean =
        values.isNotEmpty() && values.all { parse(it, format, zone) != null }
}

/**
 * Turns raw merchant text into a stable key used for categorising and grouping:
 * "eCom UBER EATS" -> "UBER EATS", "KEELLS SUPER - PILIYANDAL" -> "KEELLS SUPER".
 */
object MerchantKeys {
    private val prefixes = Regex("^(ECOM/REV|ECOM|REV\\s+\\d+|TFR)\\s+")
    private val branchSuffix = Regex("\\s+-\\s+.*$")
    private val referenceToken = Regex("\\S*\\d{4,}\\S*")
    private val nonKeyChars = Regex("[^A-Z0-9 .&*]")
    private val spaces = Regex("\\s+")

    fun of(merchant: String?): String? {
        if (merchant.isNullOrBlank()) return null
        var s = merchant.uppercase()
        s = prefixes.replace(s, "")
        s = branchSuffix.replace(s, "")
        s = referenceToken.replace(s, " ")
        s = nonKeyChars.replace(s, " ")
        s = spaces.replace(s, " ").trim()
        return s.ifEmpty { null }
    }
}

object SmsHash {
    /** Identity of an SMS used to prevent duplicates between inbox import, live receipt and re-imports. */
    fun of(sender: String, receivedAt: Long, body: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest("$sender|$receivedAt|$body".toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
