package lk.expensetracker.engine

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import lk.expensetracker.engine.bank.BuiltInBanks
import lk.expensetracker.engine.classification.Categorizer
import lk.expensetracker.engine.interpretation.Interpretation
import lk.expensetracker.engine.interpretation.RuleBasedInterpreter
import lk.expensetracker.engine.model.RawSms
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail

/**
 * Runs every anonymised message in fixtures/sms-fixtures.json through the engine.
 * Supporting a new format or bank = adding fixtures, not test code.
 */
class FixtureTest {

    @Serializable
    data class Expect(
        val result: String,
        val bankId: String? = null,
        val amountMinor: Long? = null,
        val direction: String? = null,
        val kind: String? = null,
        val channel: String? = null,
        val merchant: String? = null,
        val merchantKey: String? = null,
        val account: String? = null,
        val category: String? = null,
        val occurredAt: String? = null,
        val timeSource: String? = null,
        val balanceMinor: Long? = null,
        val type: String? = null,
    )

    @Serializable
    data class Fixture(val name: String, val sender: String, val body: String, val expect: Expect)

    private val receivedAt = 1_762_573_587_961L
    private val zone = ZoneId.of("Asia/Colombo")
    private val interpreter = RuleBasedInterpreter(BuiltInBanks.load())
    private val categorizer = Categorizer()

    @Test
    fun allFixturesInterpretAsExpected() {
        val text = javaClass.getResourceAsStream("/fixtures/sms-fixtures.json")!!.bufferedReader().readText()
        val fixtures = Json.decodeFromString(ListSerializer(Fixture.serializer()), text)
        val failures = fixtures.mapNotNull { f -> runCatching { check(f) }.exceptionOrNull()?.let { "${f.name}: ${it.message}" } }
        if (failures.isNotEmpty()) fail("${failures.size}/${fixtures.size} fixtures failed:\n" + failures.joinToString("\n"))
        println("${fixtures.size} fixtures passed")
    }

    private fun check(f: Fixture) {
        val e = f.expect
        val result = interpreter.interpretNow(RawSms(f.sender, f.body, receivedAt))
        when (e.result) {
            "ignored" -> {
                if (result !is Interpretation.Ignored) error("expected Ignored, got $result")
                e.bankId?.let { assertEquals(it, result.bankId, "bankId") }
            }
            "unrecognized" -> {
                if (result !is Interpretation.NotRecognized) error("expected NotRecognized, got $result")
                assertEquals(e.bankId, result.bankId, "bankId")
            }
            "parsed" -> {
                if (result !is Interpretation.Parsed) error("expected Parsed, got $result")
                val d = result.draft
                e.bankId?.let { assertEquals(it, d.bankId, "bankId") }
                e.amountMinor?.let { assertEquals(it, d.amountMinor, "amountMinor") }
                e.direction?.let { assertEquals(it, d.direction.name, "direction") }
                e.kind?.let { assertEquals(it, d.kind.name, "kind") }
                e.channel?.let { assertEquals(it, d.channel.name, "channel") }
                e.merchant?.let { assertEquals(it, d.merchantRaw, "merchant") }
                e.merchantKey?.let { assertEquals(it, d.merchantKey, "merchantKey") }
                e.account?.let { assertEquals(it, d.accountMask, "account") }
                e.balanceMinor?.let { assertEquals(it, d.balanceMinor, "balanceMinor") }
                e.type?.let { assertEquals(it, d.transactionType, "type") }
                e.timeSource?.let { assertEquals(it, d.timeSource.name, "timeSource") }
                e.category?.let { assertEquals(it, categorizer.categorize(d), "category") }
                e.occurredAt?.let {
                    assertEquals(LocalDateTime.parse(it).atZone(zone).toInstant().toEpochMilli(), d.occurredAt, "occurredAt")
                }
                if (e.timeSource == "SMS_RECEIVED") assertEquals(receivedAt, d.occurredAt, "occurredAt falls back to receivedAt")
            }
            else -> error("unknown expected result ${e.result}")
        }
    }
}
