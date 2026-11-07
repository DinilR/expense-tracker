package lk.expensetracker.sms

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import lk.expensetracker.ExpenseApp
import lk.expensetracker.engine.model.RawSms
import java.util.concurrent.TimeUnit

/**
 * Imports bank SMS from the inbox.
 * - Full import (first run, after a bank or rule changes): reads everything from known bank senders.
 * - Incoming import (after an SMS arrives): reads the last two days and notifies about new transactions.
 *   If the message is not in the inbox yet it retries, and after a few attempts ingests the broadcast copy.
 */
class ImportWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as ExpenseApp).container
        val repo = container.repository
        val reader = container.inboxReader
        val incomingSender = inputData.getString(KEY_SENDER)
        val incomingBody = inputData.getString(KEY_BODY)
        val since = inputData.getLong(KEY_SINCE, 0L)

        val interpreter = repo.interpreter()
        val isBankSender: (String) -> Boolean = { interpreter.bankFor(it) != null }

        if (incomingSender != null && !isBankSender(incomingSender)) return Result.success()

        val messages = reader.read(since, isBankSender)
        val result = repo.ingest(messages)

        if (incomingSender != null && incomingBody != null) {
            val found = messages.any { it.sender == incomingSender && it.body == incomingBody }
            if (!found) {
                if (runAttemptCount < 3) return Result.retry()
                val fallback = RawSms(incomingSender, incomingBody, inputData.getLong(KEY_TIME, System.currentTimeMillis()))
                container.notifier.notifyNew(repo.ingest(listOf(fallback)).added)
                return Result.success()
            }
            container.notifier.notifyNew(result.added)
        }
        container.settings.lastImportAt = System.currentTimeMillis()
        return Result.success()
    }

    companion object {
        private const val KEY_SENDER = "sender"
        private const val KEY_BODY = "body"
        private const val KEY_TIME = "time"
        private const val KEY_SINCE = "since"
        const val FULL_IMPORT = "full-import"

        fun enqueueFullImport(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                FULL_IMPORT, ExistingWorkPolicy.REPLACE, OneTimeWorkRequestBuilder<ImportWorker>().build(),
            )
        }

        fun enqueueIncoming(context: Context, sender: String, body: String, time: Long) {
            val request = OneTimeWorkRequestBuilder<ImportWorker>()
                .setInitialDelay(5, TimeUnit.SECONDS)
                .setBackoffCriteria(BackoffPolicy.LINEAR, 10, TimeUnit.SECONDS)
                .setInputData(
                    workDataOf(
                        KEY_SENDER to sender, KEY_BODY to body, KEY_TIME to time,
                        KEY_SINCE to System.currentTimeMillis() - TimeUnit.DAYS.toMillis(2),
                    ),
                )
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork("incoming-$time-${sender.hashCode()}", ExistingWorkPolicy.KEEP, request)
        }
    }
}
