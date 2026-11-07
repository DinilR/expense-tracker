package lk.expensetracker.engine

import lk.expensetracker.engine.bank.BuiltInBanks
import lk.expensetracker.engine.classification.LedgerLinker
import lk.expensetracker.engine.interpretation.Interpretation
import lk.expensetracker.engine.interpretation.RuleBasedInterpreter
import lk.expensetracker.engine.model.LedgerEntry
import lk.expensetracker.engine.model.RawSms
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Local-only check against a real "SMS Backup & Restore" export. Skipped unless run with
 * `./gradlew :engine:test -PsmsExport=D:/path/to/sms-export.xml`. The export itself is never committed.
 */
class LocalExportCoverageTest {

    @Test
    fun everyBankTransactionInTheExportIsRecognised() {
        val path = System.getProperty("smsExport") ?: return println("smsExport not set; skipping local coverage test")
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(path))
        val nodes = doc.getElementsByTagName("sms")
        val interpreter = RuleBasedInterpreter(BuiltInBanks.load())

        val counts = mutableMapOf<String, Int>()
        val unrecognised = mutableListOf<String>()
        val entries = mutableListOf<LedgerEntry>()
        for (i in 0 until nodes.length) {
            val n = nodes.item(i).attributes
            val sms = RawSms(n.getNamedItem("address").nodeValue, n.getNamedItem("body").nodeValue, n.getNamedItem("date").nodeValue.toLong())
            when (val r = interpreter.interpretNow(sms)) {
                is Interpretation.Parsed -> {
                    counts.merge("${r.draft.bankId} parsed", 1, Int::plus)
                    val d = r.draft
                    entries += LedgerEntry(entries.size.toLong(), d.bankId, d.accountMask, d.amountMinor, d.direction, d.kind, d.channel, null, d.merchantKey, d.occurredAt)
                }
                is Interpretation.Ignored -> counts.merge("${r.bankId} ignored", 1, Int::plus)
                is Interpretation.NotRecognized -> if (r.bankId != null) {
                    counts.merge("${r.bankId} UNRECOGNISED", 1, Int::plus)
                    unrecognised += sms.body.lineSequence().first()
                }
            }
        }
        val reversals = entries.count { it.kind == lk.expensetracker.engine.model.Kind.REVERSAL }
        val paired = LedgerLinker.findReversals(entries).size
        println("Export coverage: $counts; reversals paired $paired/$reversals")
        unrecognised.take(20).forEach { println("  unrecognised: $it") }
        assertEquals(0, unrecognised.size, "bank messages not recognised")
        assertEquals(reversals, paired, "every reversal should pair with its original debit")
    }
}
