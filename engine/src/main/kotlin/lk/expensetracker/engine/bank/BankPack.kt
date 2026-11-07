package lk.expensetracker.engine.bank

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import lk.expensetracker.engine.model.Channel
import lk.expensetracker.engine.model.Kind

/**
 * A bank definition: which senders belong to the bank, how to parse its messages and how to
 * classify the parsed result. Built-in packs ship as JSON resources; users can add or edit packs
 * in the app and share them as JSON.
 */
@Serializable
data class BankPack(
    val id: String,
    val displayName: String,
    val version: Int = 1,
    val senderPatterns: List<String>,
    val currencyDefault: String = "LKR",
    val timezone: String = "Asia/Colombo",
    val rules: List<ParseRuleDef>,
    val classification: List<ClassificationRuleDef> = emptyList(),
    /** "builtIn", "learned" (made by the teach-a-bank wizard) or "manual". */
    val origin: String? = null,
)

@Serializable
data class ParseRuleDef(
    val name: String,
    val priority: Int = 50,
    /** Regex with named groups: amount, currency, direction, account, channel, merchant, date, balance, type. */
    val pattern: String,
    /** When true, a match means "known message, not a transaction" (promotions, OTPs). */
    val ignore: Boolean = false,
    val directionMap: Map<String, String> = emptyMap(),
    val typeMap: Map<String, TypeMapping> = emptyMap(),
    val channelDefault: String? = null,
    val dateFormat: String? = null,
    val merchantCleanup: List<String> = emptyList(),
    val sampleMessages: List<String> = emptyList(),
    /** Friendly name shown to the user, e.g. "Card payments". */
    val label: String? = null,
    /** Direction to use when the pattern has no direction group (e.g. every message says "debited"). */
    val direction: String? = null,
    /** Forces the kind for every message this rule matches; applied after classification. */
    val kind: String? = null,
    /** Forces the category for every message this rule matches; applied after classification. */
    val category: String? = null,
)

@Serializable
data class TypeMapping(
    val direction: String? = null,
    val channel: String? = null,
    val kind: String? = null,
)

/**
 * Applied in order after parsing. A rule fires when [field] (merchant, type, channel, body) matches
 * [match] and, if given, the direction equals [direction]. Later rules override earlier ones.
 */
@Serializable
data class ClassificationRuleDef(
    val field: String,
    val match: String,
    val direction: String? = null,
    val kind: String? = null,
    val channel: String? = null,
    val category: String? = null,
)

object BankPacks {
    val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = false
    }

    fun parse(text: String): BankPack = json.decodeFromString(BankPack.serializer(), text)

    fun encode(pack: BankPack): String = json.encodeToString(BankPack.serializer(), pack)

    /** Returns human-readable problems; an empty list means the pack is usable. */
    fun validate(pack: BankPack): List<String> {
        val problems = mutableListOf<String>()
        if (pack.id.isBlank()) problems += "id is empty"
        if (pack.senderPatterns.isEmpty()) problems += "senderPatterns is empty"
        if (pack.rules.isEmpty()) problems += "rules is empty"
        runCatching { java.time.ZoneId.of(pack.timezone) }.onFailure { problems += "unknown timezone '${pack.timezone}'" }
        pack.senderPatterns.forEach { p -> regexProblem(p)?.let { problems += "senderPattern '$p': $it" } }
        pack.rules.forEach { rule ->
            regexProblem(rule.pattern)?.let { problems += "rule '${rule.name}': $it" }
            rule.merchantCleanup.forEach { p -> regexProblem(p)?.let { problems += "rule '${rule.name}' cleanup '$p': $it" } }
            if (!rule.ignore && "amount" !in RegexGroups.namesIn(rule.pattern)) {
                problems += "rule '${rule.name}' has no (?<amount>...) group"
            }
        }
        pack.classification.forEach { c ->
            regexProblem(c.match)?.let { problems += "classification '${c.match}': $it" }
            if (c.kind != null && Kind.parse(c.kind) == null) problems += "unknown kind '${c.kind}'"
            if (c.channel != null && Channel.parse(c.channel) == null) problems += "unknown channel '${c.channel}'"
        }
        return problems
    }

    private fun regexProblem(pattern: String): String? =
        runCatching { java.util.regex.Pattern.compile(pattern) }.exceptionOrNull()?.message?.lineSequence()?.firstOrNull()
}

/** Loads the bank packs bundled with the engine (src/main/resources/banks). */
object BuiltInBanks {
    private val files = listOf("sampath.json", "boc.json")

    fun load(): List<BankPack> = files.map { name ->
        val text = BuiltInBanks::class.java.getResourceAsStream("/banks/$name")
            ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
            ?: error("Missing built-in bank pack $name")
        BankPacks.parse(text)
    }
}

internal object RegexGroups {
    private val groupName = Regex("""\(\?<([a-zA-Z][a-zA-Z0-9]*)>""")
    fun namesIn(pattern: String): Set<String> = groupName.findAll(pattern).map { it.groupValues[1] }.toSet()
}
