package lk.expensetracker.sms

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import lk.expensetracker.R
import lk.expensetracker.data.db.TransactionEntity
import lk.expensetracker.data.db.displayName
import lk.expensetracker.engine.model.Direction
import lk.expensetracker.engine.normalize.Money
import lk.expensetracker.ui.MainActivity

class Notifier(private val context: Context) {

    fun createChannel() {
        val channel = NotificationChannel(CHANNEL, context.getString(R.string.notification_channel_transactions), NotificationManager.IMPORTANCE_DEFAULT)
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    fun notifyNew(transactions: List<TransactionEntity>) {
        if (transactions.isEmpty()) return
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val manager = NotificationManagerCompat.from(context)
        transactions.takeLast(5).forEach { t ->
            val sign = if (t.direction == Direction.DEBIT) "−" else "+"
            val intent = Intent(context, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_TRANSACTION_ID, t.id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            val pending = PendingIntent.getActivity(context, t.id.toInt(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val notification = NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_menu_agenda)
                .setContentTitle("$sign${t.currency} ${Money.format(t.amountMinor)} · ${t.displayName()}")
                .setContentText("${t.category} — tap to review or recategorise")
                .setContentIntent(pending)
                .setAutoCancel(true)
                .build()
            try {
                manager.notify(t.id.toInt(), notification)
            } catch (_: SecurityException) {
                // Permission revoked between the check and the call.
            }
        }
    }

    companion object {
        const val CHANNEL = "transactions"
    }
}
