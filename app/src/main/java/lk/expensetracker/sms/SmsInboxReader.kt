package lk.expensetracker.sms

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Telephony
import androidx.core.content.ContextCompat
import lk.expensetracker.engine.model.RawSms

/** Reads SMS from the device inbox. Only messages whose sender passes [senderFilter] are returned. */
class SmsInboxReader(private val context: Context) {

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED

    fun read(since: Long = 0L, senderFilter: (String) -> Boolean): List<RawSms> {
        if (!hasPermission()) return emptyList()
        val out = mutableListOf<RawSms>()
        context.contentResolver.query(
            Telephony.Sms.Inbox.CONTENT_URI,
            arrayOf(Telephony.Sms._ID, Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE),
            "${Telephony.Sms.DATE} >= ?",
            arrayOf(since.toString()),
            "${Telephony.Sms.DATE} ASC",
        )?.use { c ->
            val id = c.getColumnIndexOrThrow(Telephony.Sms._ID)
            val address = c.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
            val body = c.getColumnIndexOrThrow(Telephony.Sms.BODY)
            val date = c.getColumnIndexOrThrow(Telephony.Sms.DATE)
            while (c.moveToNext()) {
                val sender = c.getString(address) ?: continue
                if (!senderFilter(sender)) continue
                out += RawSms(sender, c.getString(body).orEmpty(), c.getLong(date), c.getLong(id))
            }
        }
        return out
    }

    data class Conversation(val sender: String, val count: Int, val lastBody: String, val lastAt: Long) {
        val isPhoneNumber: Boolean get() = sender.none { it.isLetter() }
    }

    /** One entry per sender with the latest message, newest first. */
    fun conversations(): List<Conversation> {
        if (!hasPermission()) return emptyList()
        val out = linkedMapOf<String, Conversation>()
        context.contentResolver.query(
            Telephony.Sms.Inbox.CONTENT_URI,
            arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE),
            null, null, "${Telephony.Sms.DATE} DESC",
        )?.use { c ->
            val address = c.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
            val body = c.getColumnIndexOrThrow(Telephony.Sms.BODY)
            val date = c.getColumnIndexOrThrow(Telephony.Sms.DATE)
            while (c.moveToNext()) {
                val sender = c.getString(address)?.trim() ?: continue
                val existing = out[sender]
                out[sender] = existing?.copy(count = existing.count + 1)
                    ?: Conversation(sender, 1, c.getString(body).orEmpty(), c.getLong(date))
            }
        }
        return out.values.toList()
    }

    fun readBySender(sender: String): List<RawSms> = read(0L) { it.trim() == sender.trim() }

    /**
     * Alphanumeric senders (bank-style IDs such as SAMPATHTXN) with message counts, most frequent first.
     * Used when adding a new bank. Personal phone numbers are left out.
     */
    fun businessSenders(): List<Pair<String, Int>> {
        if (!hasPermission()) return emptyList()
        val counts = mutableMapOf<String, Int>()
        context.contentResolver.query(
            Telephony.Sms.Inbox.CONTENT_URI, arrayOf(Telephony.Sms.ADDRESS), null, null, null,
        )?.use { c ->
            val address = c.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
            while (c.moveToNext()) {
                val sender = c.getString(address)?.trim() ?: continue
                if (sender.any { it.isLetter() }) counts.merge(sender, 1, Int::plus)
            }
        }
        return counts.toList().sortedByDescending { it.second }
    }
}
