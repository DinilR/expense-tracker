package lk.expensetracker.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import lk.expensetracker.ExpenseApp
import lk.expensetracker.data.db.BankPackEntity
import lk.expensetracker.data.db.TransactionEntity
import lk.expensetracker.data.db.UnparsedMessageEntity
import lk.expensetracker.engine.bank.BankPack
import lk.expensetracker.engine.bank.BankPacks
import lk.expensetracker.engine.bank.ParseRuleDef
import lk.expensetracker.engine.insights.Insight
import lk.expensetracker.engine.insights.InsightContext
import lk.expensetracker.engine.insights.Period
import lk.expensetracker.engine.insights.PeriodReport
import lk.expensetracker.engine.insights.PeriodReports
import lk.expensetracker.engine.insights.PeriodType
import lk.expensetracker.engine.insights.StatisticalInsightProvider
import lk.expensetracker.engine.interpretation.Interpretation
import lk.expensetracker.engine.interpretation.RuleBasedInterpreter
import lk.expensetracker.engine.model.Kind
import lk.expensetracker.engine.model.RawSms
import lk.expensetracker.engine.normalize.Money
import lk.expensetracker.sms.ImportWorker
import java.time.LocalDate
import java.time.ZoneId

data class PackTestReport(
    val problems: List<String>,
    val checked: Int = 0,
    val parsed: Int = 0,
    val ignored: Int = 0,
    val unrecognised: List<String> = emptyList(),
    val examples: List<String> = emptyList(),
)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val container = (app as ExpenseApp).container
    private val repo = container.repository
    val zone: ZoneId = ZoneId.systemDefault()

    val transactions: StateFlow<List<TransactionEntity>> =
        repo.transactions.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val unparsed: StateFlow<List<UnparsedMessageEntity>> =
        repo.unparsed.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val bankPacks: StateFlow<List<BankPackEntity>> =
        repo.bankPacks.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val reviewCount: StateFlow<Int> = repo.reviewCount.stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    private fun today(): LocalDate = LocalDate.now(zone)

    private val _period = MutableStateFlow(
        Period.of(runCatching { PeriodType.valueOf(container.settings.periodType) }.getOrDefault(PeriodType.MONTH), today()),
    )
    /** The day, week or month shown on Overview and Transactions. */
    val period: StateFlow<Period> = _period.asStateFlow()

    val periodReport: StateFlow<PeriodReport?> = combine(transactions, _period) { list, p ->
        withContext(Dispatchers.Default) { PeriodReports.build(list.map { it.toLedgerEntry() }, p, zone, System.currentTimeMillis()) }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Category chosen on Overview; the Transactions screen shows only that category while set. */
    val categoryFilter = MutableStateFlow<String?>(null)

    private val _themeMode = MutableStateFlow(runCatching { ThemeMode.valueOf(container.settings.themeMode) }.getOrDefault(ThemeMode.SYSTEM))
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    fun setThemeMode(mode: ThemeMode) {
        _themeMode.value = mode
        container.settings.themeMode = mode.name
    }

    fun setPeriodType(type: PeriodType) {
        val current = _period.value
        val anchor = if (current.contains(today())) today() else current.startDate
        _period.value = Period.of(type, anchor)
        container.settings.periodType = type.name
    }

    fun shiftPeriod(delta: Int) {
        _period.value = if (delta < 0) _period.value.previous() else _period.value.next()
    }

    fun goToToday() { _period.value = Period.of(_period.value.type, today()) }

    fun openDay(date: LocalDate) { _period.value = Period.Day(date) }

    fun isCurrentPeriod(p: Period) = p.contains(today())

    val insights: StateFlow<List<Insight>> = transactions.map { list ->
        withContext(Dispatchers.Default) {
            StatisticalInsightProvider().generateNow(InsightContext(list.map { it.toLedgerEntry() }, System.currentTimeMillis(), zone))
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val importing: StateFlow<Boolean> = WorkManager.getInstance(app)
        .getWorkInfosForUniqueWorkFlow(ImportWorker.FULL_IMPORT)
        .map { infos -> infos.any { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** JSON pre-filled into the bank editor when creating a bank from an unrecognised message. */
    var editorTemplate: String? = null
    var editorSample: String? = null

    init {
        // Catch up on anything the live receiver missed (app updated, process killed). Duplicates are skipped.
        if (container.settings.onboardingDone && container.inboxReader.hasPermission()) importNow()
    }

    val onboardingDone get() = container.settings.onboardingDone
    fun hasSmsPermission() = container.inboxReader.hasPermission()

    fun completeOnboarding(startImport: Boolean) {
        container.settings.onboardingDone = true
        if (startImport) importNow()
    }

    fun importNow() = ImportWorker.enqueueFullImport(getApplication())

    fun reprocessUnparsed() = viewModelScope.launch { repo.reprocessUnparsed() }

    fun transaction(id: Long) = repo.transaction(id)

    fun setCategory(id: Long, category: String, applyToMerchant: Boolean) = viewModelScope.launch { repo.setCategory(id, category, applyToMerchant) }
    fun setKind(id: Long, kind: Kind) = viewModelScope.launch { repo.setKind(id, kind) }
    fun setExcluded(id: Long, excluded: Boolean) = viewModelScope.launch { repo.setExcluded(id, excluded) }
    fun setNote(id: Long, note: String) = viewModelScope.launch { repo.setNote(id, note) }
    fun markReviewed(id: Long) = viewModelScope.launch { repo.markReviewed(id) }
    fun confirmPair(id: Long) = viewModelScope.launch { repo.confirmInternalPair(id) }
    fun unlink(id: Long) = viewModelScope.launch { repo.unlink(id) }
    fun ignoreUnparsed(id: Long) = viewModelScope.launch { repo.ignoreUnparsed(id) }

    // ---- Banks --------------------------------------------------------------------------------

    fun setBankEnabled(id: String, enabled: Boolean) = viewModelScope.launch { repo.setBankEnabled(id, enabled) }

    suspend fun bankJson(id: String): String? = repo.bankPack(id)?.json

    suspend fun businessSenders(): List<Pair<String, Int>> = withContext(Dispatchers.IO) { container.inboxReader.businessSenders() }

    fun prepareNewBank(sender: String, sample: String?) {
        val id = sender.lowercase().filter { it.isLetterOrDigit() }.ifEmpty { "bank" }
        val pack = BankPack(
            id = id,
            displayName = sender,
            senderPatterns = listOf("^" + sender.replace(Regex("[\\\\^$.|?*+()\\[\\]{}]")) { "\\" + it.value } + "$"),
            rules = listOf(
                ParseRuleDef(
                    name = "Transaction",
                    priority = 10,
                    pattern = "(?<currency>LKR|Rs\\.?)\\s*(?<amount>[\\d,]+\\.\\d{2}).*?(?<direction>debited|credited)",
                    directionMap = mapOf("debited" to "DEBIT", "credited" to "CREDIT"),
                    sampleMessages = listOfNotNull(sample),
                ),
            ),
        )
        editorTemplate = BankPacks.encode(pack)
        editorSample = sample
    }

    /** Validates the JSON and runs it against every inbox message from the senders it matches. */
    suspend fun testPack(json: String): PackTestReport = withContext(Dispatchers.Default) {
        val pack = runCatching { BankPacks.parse(json) }.getOrElse { return@withContext PackTestReport(listOf("JSON error: ${it.message}")) }
        val problems = BankPacks.validate(pack)
        if (problems.isNotEmpty()) return@withContext PackTestReport(problems)
        val interpreter = RuleBasedInterpreter(listOf(pack))
        val messages = withContext(Dispatchers.IO) { container.inboxReader.read(0L) { interpreter.bankFor(it) != null } }
        val sampleSender = pack.senderPatterns.first().trim('^', '$').replace("\\", "")
        val samples = pack.rules.flatMap { it.sampleMessages }.map { RawSms(sampleSender, it, System.currentTimeMillis()) }
        var parsed = 0
        var ignored = 0
        val unrecognised = mutableListOf<String>()
        val examples = mutableListOf<String>()
        for (sms in samples + messages) {
            when (val r = interpreter.interpretNow(sms)) {
                is Interpretation.Parsed -> {
                    parsed++
                    if (examples.size < 6) examples += with(r.draft) {
                        "${direction.name} $currency ${Money.format(amountMinor)} · ${kind.name} · ${merchantRaw ?: transactionType ?: "-"}"
                    }
                }
                is Interpretation.Ignored -> ignored++
                is Interpretation.NotRecognized -> unrecognised += sms.body
            }
        }
        PackTestReport(emptyList(), samples.size + messages.size, parsed, ignored, unrecognised.take(5), examples)
    }

    /** Saves a pack and re-imports so past messages from that sender are picked up. */
    suspend fun saveBankPack(json: String): List<String> {
        val pack = runCatching { BankPacks.parse(json) }.getOrElse { return listOf("JSON error: ${it.message}") }
        val problems = BankPacks.validate(pack)
        if (problems.isNotEmpty()) return problems
        repo.saveBankPack(pack)
        repo.reprocessUnparsed()
        importNow()
        return emptyList()
    }

    fun deleteBank(id: String) = viewModelScope.launch { repo.deleteCustomBank(id) }
    fun resetBank(id: String) = viewModelScope.launch { repo.resetBuiltInBank(id); importNow() }

    // ---- Export -------------------------------------------------------------------------------

    fun exportCsv(uri: Uri, onDone: (Boolean) -> Unit) = viewModelScope.launch {
        val ok = runCatching {
            val csv = repo.exportCsv(zone)
            withContext(Dispatchers.IO) {
                getApplication<Application>().contentResolver.openOutputStream(uri)!!.use { it.write(csv.toByteArray()) }
            }
        }.isSuccess
        onDone(ok)
    }
}
