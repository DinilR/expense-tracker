package lk.expensetracker.engine

import lk.expensetracker.engine.bank.BankPacks
import lk.expensetracker.engine.interpretation.Interpretation
import lk.expensetracker.engine.interpretation.RuleBasedInterpreter
import lk.expensetracker.engine.learn.BankLearner
import lk.expensetracker.engine.learn.Field
import lk.expensetracker.engine.learn.RuleGenerator
import lk.expensetracker.engine.learn.TokenType
import lk.expensetracker.engine.learn.Tokenizer
import lk.expensetracker.engine.model.Direction
import lk.expensetracker.engine.model.Kind
import lk.expensetracker.engine.model.RawSms
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LearnTest {
    private val zone = ZoneId.of("Asia/Colombo")
    private fun t(s: String) = LocalDateTime.parse(s).atZone(zone).toInstant().toEpochMilli()

    /** A made-up bank with a layout unlike Sampath or BOC. */
    private val demo: List<RawSms> = run {
        val merchants = listOf("CARGILLS FOOD CITY", "KEELLS SUPER", "PIZZA HUT COLOMBO 03", "DIALOG AXIATA", "UBER TRIP", "ARPICO SUPERCENTRE", "SPAR KOTTE")
        val out = mutableListOf<RawSms>()
        merchants.forEachIndexed { i, m ->
            out += RawSms("DEMOBANK", "Your A/C ***4521 debited LKR ${i + 1},${(i * 37) % 1000 + 100}.50 at $m on 0${i + 1}/11/2026 1${i}:15. Bal LKR 45,${i}00.00", t("2026-11-0${i + 1}T1$i:16:00"))
        }
        listOf("SALARY ACME LTD", "JOHN SILVA", "CEFT FROM NIMAL").forEachIndexed { i, m ->
            out += RawSms("DEMOBANK", "Your A/C ***4521 credited LKR 5${i},000.00 from $m on 1${i}/11/2026 09:0$i. Bal LKR 9${i},000.00", t("2026-11-1${i}T09:0$i:30"))
        }
        out += RawSms("DEMOBANK", "Win a trip to Maldives! Use your DEMOBANK card before 30/11 and get entries. T&C apply.", t("2026-11-05T10:00:00"))
        out += RawSms("DEMOBANK", "Win cashback up to LKR 5,000.00 this weekend with DEMOBANK cards.", t("2026-11-06T10:00:00"))
        out += RawSms("DEMOBANK", "Win big this Avurudu with DEMOBANK credit cards.", t("2026-11-07T10:00:00"))
        out
    }

    @Test
    fun tokenizerRecognisesAmountsDatesAndAccounts() {
        val tokens = Tokenizer.tokenize("LKR 2,649.36 debited from AC **1234 via POS at KEELLS 80401775\n07/11/2026 14:05:58")
        assertEquals(
            listOf(TokenType.AMOUNT, TokenType.WORD, TokenType.WORD, TokenType.WORD, TokenType.ACCOUNT, TokenType.WORD, TokenType.WORD, TokenType.WORD, TokenType.WORD, TokenType.NUMBER, TokenType.DATE, TokenType.TIME),
            tokens.map { it.type },
        )
        assertTrue(tokens[10].newlineBefore)
        assertEquals(TokenType.AMOUNT, Tokenizer.tokenize("Rs.1,000/-").single().type)
        assertEquals(TokenType.ACCOUNT, Tokenizer.tokenize("XXXXXXXXXX999").single().type)
    }

    @Test
    fun learnsANewBankFromItsMessages() {
        val groups = BankLearner.analyze(demo)
        val tx = groups.filter { it.isTransaction }
        assertTrue(tx.isNotEmpty())
        assertTrue(groups.any { !it.isTransaction }, "promotions are recognised as not transactions")

        val pack = RuleGenerator.pack("demobank", "Demo Bank", "DEMOBANK", groups)
        assertEquals(emptyList(), BankPacks.validate(pack))
        val eval = RuleGenerator.evaluate(pack, demo)
        assertEquals(10, eval.parsed.size, "all 10 transactions understood; not understood: ${eval.notUnderstood.map { it.body }}")
        assertEquals(3, eval.ignored)

        val byBody = eval.parsed.associate { it.first.body to it.second }
        val pizza = byBody.entries.first { "PIZZA" in it.key }.value
        assertEquals(Direction.DEBIT, pizza.direction)
        assertEquals("PIZZA HUT COLOMBO 03", pizza.merchantRaw)
        assertEquals("4521", pizza.accountMask)
        assertEquals(t("2026-11-03T12:15:00"), pizza.occurredAt)
        assertEquals(4_520_000L, pizza.balanceMinor)

        val salary = byBody.entries.first { "SALARY" in it.key }.value
        assertEquals(Direction.CREDIT, salary.direction)
        assertEquals(5_000_000L, salary.amountMinor)
        assertEquals(Kind.INCOME, salary.kind)
    }

    @Test
    fun learnsFromASingleMessage() {
        val one = listOf(RawSms("ONEBANK", "Your A/C ***4521 debited LKR 3,500.00 at CARGILLS FOOD CITY on 08/11/2026 10:15. Bal LKR 45,200.00", 0L))
        val groups = BankLearner.analyze(one)
        val g = groups.single()
        assertTrue(g.isTransaction)
        val merchantToken = g.referenceTokens.indexOfFirst { it.text == "CARGILLS" }
        assertEquals(Field.MERCHANT, BankLearner.fieldOf(g, merchantToken))

        val pack = RuleGenerator.pack("onebank", "One Bank", "ONEBANK", groups)
        val other = RawSms("ONEBANK", "Your A/C ***4521 debited LKR 120.00 at ODEL ALEXANDRA PLACE on 09/11/2026 18:40. Bal LKR 45,080.00", 0L)
        val r = RuleBasedInterpreter(listOf(pack)).interpretNow(other)
        r as Interpretation.Parsed
        assertEquals(12_000L, r.draft.amountMinor)
        assertEquals("ODEL ALEXANDRA PLACE", r.draft.merchantRaw)
        assertEquals(Direction.DEBIT, r.draft.direction)
    }

    @Test
    fun userCorrectionsChangeTheGeneratedRule() {
        val g = BankLearner.analyze(demo).first { it.isTransaction }
        val balanceToken = g.referenceTokens.indexOfLast { it.type == TokenType.AMOUNT }
        assertEquals(Field.BALANCE, BankLearner.fieldOf(g, balanceToken))
        val noBalance = BankLearner.setField(g, balanceToken, Field.OTHER)
        assertEquals(Field.OTHER, BankLearner.fieldOf(noBalance, balanceToken))
        val rule = assertNotNull(RuleGenerator.rule(noBalance, 10))
        assertFalse("balance" in rule.pattern)

        val forced = noBalance.copy(kind = Kind.TRANSFER_OUT, category = "Transfers out")
        val draft = RuleBasedInterpreter(listOf(RuleGenerator.pack("d", "D", "DEMOBANK", listOf(forced)))).interpretNow(forced.reference)
        draft as Interpretation.Parsed
        assertEquals(Kind.TRANSFER_OUT, draft.draft.kind)
        assertEquals("Transfers out", draft.draft.category)
    }
}
