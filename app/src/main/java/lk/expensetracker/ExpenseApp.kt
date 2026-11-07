package lk.expensetracker

import android.app.Application
import android.content.Context
import lk.expensetracker.data.LedgerRepository
import lk.expensetracker.data.db.AppDatabase
import lk.expensetracker.sms.Notifier
import lk.expensetracker.sms.SmsInboxReader

class ExpenseApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.notifier.createChannel()
    }
}

/** Manual dependency wiring; small enough that a DI framework is not needed yet. */
class AppContainer(context: Context) {
    val database = AppDatabase.create(context)
    val repository = LedgerRepository(database)
    val inboxReader = SmsInboxReader(context)
    val notifier = Notifier(context)
    val settings = Settings(context)
}

class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var onboardingDone: Boolean
        get() = prefs.getBoolean("onboardingDone", false)
        set(value) = prefs.edit().putBoolean("onboardingDone", value).apply()

    var themeMode: String
        get() = prefs.getString("themeMode", "SYSTEM") ?: "SYSTEM"
        set(value) = prefs.edit().putString("themeMode", value).apply()

    var periodType: String
        get() = prefs.getString("periodType", "MONTH") ?: "MONTH"
        set(value) = prefs.edit().putString("periodType", value).apply()

    var lastImportAt: Long
        get() = prefs.getLong("lastImportAt", 0L)
        set(value) = prefs.edit().putLong("lastImportAt", value).apply()
}
