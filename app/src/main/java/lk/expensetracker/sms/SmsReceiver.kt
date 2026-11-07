package lk.expensetracker.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony

/**
 * Wakes up on every incoming SMS and schedules an inbox import shortly afterwards. The import reads
 * the message back from the inbox (written by the default SMS app) so its timestamp, and therefore
 * its duplicate-check hash, matches the one a full inbox import would produce.
 */
class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val parts = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        parts.groupBy { it.originatingAddress.orEmpty() }.forEach { (sender, messages) ->
            val body = messages.joinToString("") { it.messageBody.orEmpty() }
            ImportWorker.enqueueIncoming(context, sender, body, messages.first().timestampMillis)
        }
    }
}
