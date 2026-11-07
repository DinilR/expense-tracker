package lk.expensetracker.engine

import lk.expensetracker.engine.learn.BankLearner
import lk.expensetracker.engine.learn.RuleGenerator
import lk.expensetracker.engine.model.RawSms
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test

class LearnDebugTest {
    @Test
    fun printGroups() {
        val path = System.getProperty("smsExport") ?: return
        if (System.getProperty("learnDebug") == null) return
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(path))
        val nodes = doc.getElementsByTagName("sms")
        val msgs = (0 until nodes.length).map { i -> nodes.item(i).attributes }
            .filter { it.getNamedItem("address").nodeValue == "SAMPATHTXN" }
            .map { RawSms("SAMPATHTXN", it.getNamedItem("body").nodeValue, it.getNamedItem("date").nodeValue.toLong()) }
        BankLearner.analyze(msgs).forEach { g ->
            val layout = BankLearner.layout(g)
            val fields = layout.elements.joinToString(" ") { e -> if (e.field == lk.expensetracker.engine.learn.Field.FIXED) (e as lk.expensetracker.engine.learn.Element.Anchor).let { g.referenceTokens[it.refIndex].text } else "[${e.field}]" }
            println("G${g.id} n=${g.messages.size} tx=${g.isTransaction} aligned=${layout.alignedMessages.size}: $fields")
            println("   ref: ${g.reference.body.replace("\n", " ⏎ ").take(110)}")
            println("   rule: ${RuleGenerator.rule(g, 1)?.pattern?.takeLast(90)}")
        }
    }
}
