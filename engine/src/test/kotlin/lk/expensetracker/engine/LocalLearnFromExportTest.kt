package lk.expensetracker.engine

import lk.expensetracker.engine.bank.BuiltInBanks
import lk.expensetracker.engine.interpretation.Interpretation
import lk.expensetracker.engine.interpretation.RuleBasedInterpreter
import lk.expensetracker.engine.learn.BankLearner
import lk.expensetracker.engine.learn.RuleGenerator
import lk.expensetracker.engine.model.RawSms
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Local-only: pretend Sampath and BOC are unknown, learn their rules from the raw export, and compare
 * with the hand-written rules. Run with -PsmsExport=… (see LocalExportCoverageTest).
 */
class LocalLearnFromExportTest {

    @Test
    fun learnedRulesAgreeWithHandWrittenRules() {
        val path = System.getProperty("smsExport") ?: return println("smsExport not set; skipping learning test")
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(path))
        val nodes = doc.getElementsByTagName("sms")
        val all = (0 until nodes.length).map { i ->
            val a = nodes.item(i).attributes
            RawSms(a.getNamedItem("address").nodeValue, a.getNamedItem("body").nodeValue, a.getNamedItem("date").nodeValue.toLong())
        }
        val hand = RuleBasedInterpreter(BuiltInBanks.load())

        for (sender in listOf("SAMPATHTXN", "BOC")) {
            val msgs = all.filter { it.sender == sender }
            val started = System.currentTimeMillis()
            val groups = BankLearner.analyze(msgs)
            val pack = RuleGenerator.pack(sender.lowercase(), sender, sender, groups)
            val learned = RuleBasedInterpreter(listOf(pack))
            val ms = System.currentTimeMillis() - started

            var handTx = 0
            var agree = 0
            var sameKind = 0
            val mismatches = mutableListOf<String>()
            for (sms in msgs) {
                val h = hand.interpretNow(sms) as? Interpretation.Parsed ?: continue
                handTx++
                val l = learned.interpretNow(sms) as? Interpretation.Parsed
                val ok = l != null && l.draft.amountMinor == h.draft.amountMinor && l.draft.direction == h.draft.direction &&
                    l.draft.occurredAt == h.draft.occurredAt && l.draft.accountMask == h.draft.accountMask
                if (l?.draft?.kind == h.draft.kind) sameKind++
                if (ok) agree++ else if (mismatches.size < 12) {
                    mismatches += "  ${if (l == null) "NOT PARSED" else "amt=${l.draft.amountMinor} dir=${l.draft.direction} t=${l.draft.occurredAt} acct=${l.draft.accountMask} vs hand amt=${h.draft.amountMinor} dir=${h.draft.direction} t=${h.draft.occurredAt}"}: ${sms.body.lineSequence().first()}"
                }
            }
            val share = agree.toDouble() / handTx
            println("$sender: ${groups.size} groups (${groups.count { it.isTransaction }} transaction), ${pack.rules.size} rules, learned in ${ms}ms; agreement $agree/$handTx = ${"%.1f".format(share * 100)}%; same kind ${sameKind * 100 / handTx}%")
            pack.rules.forEach { println("  rule [${it.label}] ${it.pattern.take(160)}") }
            mismatches.forEach(::println)
            assertTrue(share >= 0.98, "$sender agreement ${"%.1f".format(share * 100)}% is below 98%")
        }
    }
}
