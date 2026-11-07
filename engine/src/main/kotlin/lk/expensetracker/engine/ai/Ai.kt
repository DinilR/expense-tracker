package lk.expensetracker.engine.ai

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import lk.expensetracker.engine.insights.ConversationalInsightProvider
import lk.expensetracker.engine.insights.Insight
import lk.expensetracker.engine.insights.InsightContext
import lk.expensetracker.engine.insights.InsightType
import lk.expensetracker.engine.insights.Reports
import lk.expensetracker.engine.insights.Severity
import lk.expensetracker.engine.interpretation.Interpretation
import lk.expensetracker.engine.interpretation.MessageInterpreter
import lk.expensetracker.engine.model.Channel
import lk.expensetracker.engine.model.Direction
import lk.expensetracker.engine.model.Kind
import lk.expensetracker.engine.model.RawSms
import lk.expensetracker.engine.model.TimeSource
import lk.expensetracker.engine.model.TransactionDraft
import lk.expensetracker.engine.normalize.MerchantKeys
import lk.expensetracker.engine.normalize.Money

/**
 * A text-completion back-end. Implementations: on-device (Gemini Nano / MediaPipe) or a cloud API.
 * Nothing in the app implements this yet; the AI phase plugs in here.
 */
interface LlmClient {
    val id: String
    suspend fun complete(system: String, user: String): String
}

/** Removes details an AI back-end does not need: long digit runs (account/card/reference numbers). */
object SmsRedactor {
    private val longDigits = Regex("(?<![\\d,.])\\d{5,}(?![\\d,]*\\.\\d{2})")
    private val email = Regex("[\\w.+-]+@[\\w-]+\\.[\\w.]+")

    fun redact(body: String): String = email.replace(longDigits.replace(body) { "#".repeat(it.value.length) }, "<email>")

    private val amountLike = Regex("(?i)(LKR|Rs\\.?|USD)\\s*[\\d,]+\\.\\d{2}")
    private val phoneNumber = Regex("^\\+?\\d{7,}$")

    /** Only alphanumeric bank-style senders with an amount in the body are ever offered to an AI back-end. */
    fun looksFinancial(sms: RawSms): Boolean = !phoneNumber.matches(sms.sender.trim()) && amountLike.containsMatchIn(sms.body)
}

/** Interprets messages the rules did not recognise. Results carry low confidence and are marked for review. */
class LlmMessageInterpreter(private val llm: LlmClient) : MessageInterpreter {
    override val id: String get() = "llm-${llm.id}"

    @Serializable
    private data class Answer(
        val isTransaction: Boolean = false,
        val amount: String? = null,
        val currency: String? = null,
        val direction: String? = null,
        val merchant: String? = null,
        val account: String? = null,
        val kind: String? = null,
    )

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    override suspend fun interpret(sms: RawSms): Interpretation {
        if (!SmsRedactor.looksFinancial(sms)) return Interpretation.NotRecognized(null)
        val reply = llm.complete(SYSTEM, "Sender: ${sms.sender}\nMessage:\n${SmsRedactor.redact(sms.body)}")
        val start = reply.indexOf('{')
        val end = reply.lastIndexOf('}')
        if (start < 0 || end <= start) return Interpretation.NotRecognized(null)
        val answer = runCatching { json.decodeFromString(Answer.serializer(), reply.substring(start, end + 1)) }
            .getOrNull() ?: return Interpretation.NotRecognized(null)
        if (!answer.isTransaction) return Interpretation.NotRecognized(null)
        val amount = Money.parseMinor(answer.amount) ?: return Interpretation.NotRecognized(null)
        val direction = Direction.entries.firstOrNull { it.name.equals(answer.direction, true) } ?: Direction.DEBIT
        return Interpretation.Parsed(
            TransactionDraft(
                bankId = "sender:${sms.sender.trim().uppercase()}",
                accountMask = answer.account,
                amountMinor = amount,
                currency = answer.currency ?: "LKR",
                direction = direction,
                kind = Kind.parse(answer.kind) ?: if (direction == Direction.DEBIT) Kind.EXPENSE else Kind.INCOME,
                channel = Channel.OTHER,
                merchantRaw = answer.merchant,
                merchantKey = MerchantKeys.of(answer.merchant),
                category = null,
                occurredAt = sms.receivedAt,
                timeSource = TimeSource.SMS_RECEIVED,
                balanceMinor = null,
                transactionType = null,
                ruleName = "ai",
                interpreter = id,
                confidence = 0.6f,
            ),
        )
    }

    companion object {
        val SYSTEM = """
            You extract bank transactions from SMS messages. Reply with JSON only:
            {"isTransaction":true|false,"amount":"1234.56","currency":"LKR","direction":"DEBIT|CREDIT",
             "merchant":"text or null","account":"last digits or null",
             "kind":"EXPENSE|INCOME|FEE|CASH_WITHDRAWAL|TRANSFER_OUT|TRANSFER_IN|REVERSAL"}
            Promotions, OTPs and notices are not transactions.
        """.trimIndent()
    }
}

/**
 * AI narrative insights. The model receives pre-computed, anonymised aggregates (no raw SMS, no
 * account numbers) and is told to quote only those numbers.
 */
class LlmInsightProvider(private val llm: LlmClient) : ConversationalInsightProvider {
    override val id: String get() = "llm-${llm.id}"

    fun aggregates(ctx: InsightContext): String {
        val cur = Reports.monthOf(ctx.now, ctx.zone)
        return (0..3).joinToString("\n") { back ->
            val r = Reports.month(ctx.entries, cur.minusMonths(back.toLong()), ctx.zone, ctx.now)
            buildString {
                append("${r.month}: income=${Money.format(r.totals.incomeMinor)} expenses=${Money.format(r.totals.expenseMinor)}")
                r.forecastExpenseMinor?.let { append(" forecast=${Money.format(it)}") }
                append(" categories=")
                append(r.byCategory.joinToString(",") { "${it.first}:${Money.format(it.second)}" })
                append(" topMerchants=")
                append(r.topMerchants.joinToString(",") { "${it.first}:${Money.format(it.second)}" })
            }
        }
    }

    override suspend fun generate(ctx: InsightContext): List<Insight> {
        val reply = llm.complete(SYSTEM, "Currency ${ctx.currency}. Monthly aggregates:\n${aggregates(ctx)}\nGive up to 4 insights, one per line, as 'title | detail'.")
        return reply.lines().mapNotNull { line ->
            val parts = line.split("|").map { it.trim().trimStart('-', '*', ' ') }
            if (parts.size < 2 || parts[0].isBlank()) null
            else Insight(InsightType.AI_NARRATIVE, parts[0], parts[1], Severity.INFO, source = id)
        }
    }

    override suspend fun ask(question: String, ctx: InsightContext): String =
        llm.complete(SYSTEM, "Currency ${ctx.currency}. Monthly aggregates:\n${aggregates(ctx)}\n\nQuestion: $question")

    companion object {
        val SYSTEM = """
            You are a personal finance assistant. Use ONLY the numbers provided; never invent or recompute totals.
            Be specific and practical: explain changes, point out recurring costs, and suggest realistic actions.
        """.trimIndent()
    }
}
