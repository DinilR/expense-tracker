package lk.expensetracker.engine.interpretation

import lk.expensetracker.engine.model.RawSms
import lk.expensetracker.engine.model.TransactionDraft

/**
 * Turns an SMS into a transaction. The rule-based interpreter runs first; AI interpreters can be
 * appended to the pipeline later to handle messages the rules do not recognise.
 */
interface MessageInterpreter {
    /** "rules", "llm-ondevice", "llm-cloud" … stored with every transaction it produces. */
    val id: String

    suspend fun interpret(sms: RawSms): Interpretation
}

sealed interface Interpretation {
    data class Parsed(val draft: TransactionDraft) : Interpretation

    /** A known message that is not a transaction (promotion, notice, OTP). */
    data class Ignored(val bankId: String, val ruleName: String) : Interpretation

    /** [bankId] is set when the sender belongs to a known bank but no rule matched. */
    data class NotRecognized(val bankId: String?) : Interpretation
}

/** Runs interpreters in order and returns the first definitive answer. */
class InterpretationPipeline(private val interpreters: List<MessageInterpreter>) {

    suspend fun interpret(sms: RawSms): Interpretation {
        var fallback: Interpretation = Interpretation.NotRecognized(null)
        for (interpreter in interpreters) {
            when (val result = interpreter.interpret(sms)) {
                is Interpretation.Parsed, is Interpretation.Ignored -> return result
                is Interpretation.NotRecognized -> if (result.bankId != null) fallback = result
            }
        }
        return fallback
    }
}
