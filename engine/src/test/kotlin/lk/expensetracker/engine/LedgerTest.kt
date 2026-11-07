package lk.expensetracker.engine

import kotlinx.coroutines.runBlocking
import lk.expensetracker.engine.ai.LlmClient
import lk.expensetracker.engine.ai.LlmMessageInterpreter
import lk.expensetracker.engine.ai.SmsRedactor
import lk.expensetracker.engine.classification.LedgerLinker
import lk.expensetracker.engine.classification.LedgerMath
import lk.expensetracker.engine.insights.InsightContext
import lk.expensetracker.engine.insights.InsightType
import lk.expensetracker.engine.insights.StatisticalInsightProvider
import lk.expensetracker.engine.interpretation.Interpretation
import lk.expensetracker.engine.model.Channel
import lk.expensetracker.engine.model.Direction
import lk.expensetracker.engine.model.Kind
import lk.expensetracker.engine.model.LedgerEntry
import lk.expensetracker.engine.model.LinkType
import lk.expensetracker.engine.model.RawSms
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LedgerTest {
    private val zone = ZoneId.of("Asia/Colombo")
    private fun t(s: String) = LocalDateTime.parse(s).atZone(zone).toInstant().toEpochMilli()

    private fun entry(
        id: Long, amount: Long, dir: Direction, kind: Kind, at: String,
        channel: Channel = Channel.ONLINE, bank: String = "sampath", account: String = "1234", category: String? = null, merchant: String? = null,
    ) = LedgerEntry(id, bank, account, amount, dir, kind, channel, category, merchant, t(at))

    private fun List<LedgerEntry>.apply(links: List<lk.expensetracker.engine.classification.Link>) = map { e ->
        links.firstOrNull { it.firstId == e.id }?.let { return@map e.copy(linkType = it.firstType, linkedId = it.secondId) }
        links.firstOrNull { it.secondId == e.id }?.let { return@map e.copy(linkType = it.secondType, linkedId = it.firstId) }
        e
    }

    @Test
    fun reversalCancelsTheMatchingEarlierOnlineDebit() {
        val entries = listOf(
            entry(1, 137_368, Direction.DEBIT, Kind.EXPENSE, "2026-03-12T20:00:00", merchant = "UBER"),
            entry(2, 50_000, Direction.DEBIT, Kind.EXPENSE, "2026-03-12T20:30:00", merchant = "UBER"),
            entry(3, 137_368, Direction.CREDIT, Kind.REVERSAL, "2026-03-12T23:48:13", merchant = "UBER"),
        )
        val links = LedgerLinker.findReversals(entries)
        assertEquals(listOf(lk.expensetracker.engine.classification.Link(1, LinkType.REVERSED_BY, 3, LinkType.REVERSES)), links)

        val totals = LedgerMath.totals(entries.apply(links))
        assertEquals(50_000, totals.expenseMinor, "only the un-reversed debit counts")
        assertEquals(0, totals.incomeMinor, "a matched reversal is not income")
    }

    @Test
    fun reversalOutsideWindowOrOtherAccountIsNotMatched() {
        val entries = listOf(
            entry(1, 1000, Direction.DEBIT, Kind.EXPENSE, "2026-03-01T10:00:00"),
            entry(2, 1000, Direction.DEBIT, Kind.EXPENSE, "2026-03-12T10:00:00", account = "9999"),
            entry(3, 1000, Direction.CREDIT, Kind.REVERSAL, "2026-03-12T11:00:00"),
        )
        assertTrue(LedgerLinker.findReversals(entries).isEmpty())
        assertEquals(1000, LedgerMath.totals(entries).incomeMinor, "an unmatched reversal counts as income until reviewed")
    }

    @Test
    fun ownAccountTransferIsSuggestedAndExcludedOnceConfirmed() {
        val entries = listOf(
            entry(1, 2_000_000, Direction.DEBIT, Kind.TRANSFER_OUT, "2026-04-01T10:00:00", Channel.TRANSFER),
            entry(2, 2_000_000, Direction.CREDIT, Kind.TRANSFER_IN, "2026-04-01T10:05:00", Channel.TRANSFER, bank = "boc", account = "999"),
            entry(3, 2_000_000, Direction.CREDIT, Kind.TRANSFER_IN, "2026-04-01T13:00:00", Channel.TRANSFER, bank = "boc", account = "999"),
        )
        val links = LedgerLinker.findTransferPairs(entries)
        assertEquals(1, links.size)
        assertEquals(1L to 2L, links[0].firstId to links[0].secondId)

        val confirmed = entries.apply(links).map { if (it.linkType != null) it.copy(linkType = LinkType.INTERNAL_PAIR) else it }
        val totals = LedgerMath.totals(confirmed)
        assertEquals(0, totals.expenseMinor)
        assertEquals(2_000_000, totals.incomeMinor, "only the unpaired credit is income")
        assertEquals(2_000_000, totals.internalMinor)
    }

    @Test
    fun statisticalInsightsIncludeForecastAndSavingsRate() {
        val entries = listOf(
            entry(1, 28_190_892, Direction.CREDIT, Kind.INCOME, "2026-10-23T12:00:00", category = "Salary"),
            entry(2, 5_000_000, Direction.DEBIT, Kind.EXPENSE, "2026-10-05T12:00:00", category = "Groceries", merchant = "KEELLS SUPER"),
            entry(3, 3_000_000, Direction.DEBIT, Kind.EXPENSE, "2026-10-10T12:00:00", category = "Dining & food delivery", merchant = "UBER EATS"),
        )
        val insights = StatisticalInsightProvider().generateNow(InsightContext(entries, t("2026-10-25T12:00:00"), zone))
        assertTrue(insights.any { it.type == InsightType.FORECAST })
        assertTrue(insights.any { it.type == InsightType.SAVINGS_RATE && "72%" in it.title }, insights.joinToString { it.title })
        assertTrue(insights.any { it.type == InsightType.TOP_MERCHANT && "KEELLS SUPER" in it.title })
    }

    @Test
    fun redactorMasksAccountNumbersButKeepsAmounts() {
        val redacted = SmsRedactor.redact("LKR 281,908.92 credited to AC 1001234567 ref 000052050167 call 0112303050")
        assertTrue("281,908.92" in redacted, redacted)
        assertFalse("1001234567" in redacted, redacted)
        assertFalse("000052050167" in redacted, redacted)
    }

    @Test
    fun aiInterpreterSkipsPersonalMessagesAndParsesFinancialOnes() = runBlocking {
        var calls = 0
        val fake = object : LlmClient {
            override val id = "fake"
            override suspend fun complete(system: String, user: String): String {
                calls++
                return """Sure: {"isTransaction":true,"amount":"1,250.00","currency":"LKR","direction":"DEBIT","merchant":"CARGILLS","kind":"EXPENSE"}"""
            }
        }
        val ai = LlmMessageInterpreter(fake)
        assertTrue(ai.interpret(RawSms("+94771234567", "Lunch? I owe you Rs 500.00", 0)) is Interpretation.NotRecognized)
        assertEquals(0, calls, "personal numbers are never sent to the model")

        val result = ai.interpret(RawSms("NEWBANK", "Card charged LKR 1,250.00 at CARGILLS", 0))
        result as Interpretation.Parsed
        assertEquals(125_000L, result.draft.amountMinor)
        assertEquals("llm-fake", result.draft.interpreter)
        assertTrue(result.draft.confidence < 1f)
    }
}
