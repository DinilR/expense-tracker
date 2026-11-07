package lk.expensetracker.engine

import lk.expensetracker.engine.bank.BankPacks
import lk.expensetracker.engine.bank.BuiltInBanks
import lk.expensetracker.engine.interpretation.Interpretation
import lk.expensetracker.engine.interpretation.RuleBasedInterpreter
import lk.expensetracker.engine.model.RawSms
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BankPackTest {

    @Test
    fun builtInPacksAreValid() {
        BuiltInBanks.load().forEach { pack ->
            assertEquals(emptyList(), BankPacks.validate(pack), "problems in ${pack.id}")
        }
    }

    @Test
    fun everySampleMessageParsesWithItsOwnPack() {
        BuiltInBanks.load().forEach { pack ->
            val interpreter = RuleBasedInterpreter(listOf(pack))
            val sender = pack.senderPatterns.first().trim('^', '$')
            pack.rules.flatMap { it.sampleMessages }.forEach { body ->
                val result = interpreter.interpretNow(RawSms(sender, body, 0L))
                assertTrue(result is Interpretation.Parsed, "${pack.id} sample not parsed: $body")
            }
        }
    }

    @Test
    fun packsRoundTripThroughJson() {
        BuiltInBanks.load().forEach { pack ->
            assertEquals(pack, BankPacks.parse(BankPacks.encode(pack)))
        }
    }

    @Test
    fun validationReportsBrokenRegexAndMissingAmount() {
        val broken = BuiltInBanks.load().first().let { p ->
            p.copy(rules = listOf(p.rules.last().copy(pattern = "(?<x>unclosed"), p.rules.last().copy(name = "no amount", pattern = "LKR (?<merchant>.+)")))
        }
        val problems = BankPacks.validate(broken)
        assertTrue(problems.any { "unclosed" in it || "Unclosed" in it }, problems.toString())
        assertTrue(problems.any { "no (?<amount>" in it }, problems.toString())
    }

    @Test
    fun aUserDefinedBankWorksWithoutCodeChanges() {
        val pack = BankPacks.parse(
            """
            {
              "id": "examplebank", "displayName": "Example Bank", "senderPatterns": ["^EXBANK$"],
              "rules": [{ "name": "Card", "pattern": "Your card was charged (?<currency>[A-Z]{3}) (?<amount>[\\d,]+\\.\\d{2}) at (?<merchant>.+?)\\.", "directionMap": {} }]
            }
            """.trimIndent(),
        )
        val result = RuleBasedInterpreter(listOf(pack)).interpretNow(RawSms("EXBANK", "Your card was charged LKR 1,250.00 at CARGILLS FOOD CITY.", 0L))
        result as Interpretation.Parsed
        assertEquals(125_000L, result.draft.amountMinor)
        assertEquals("CARGILLS FOOD CITY", result.draft.merchantRaw)
    }
}
