package lk.expensetracker.ui.teach

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import lk.expensetracker.ExpenseApp
import lk.expensetracker.engine.bank.BankPack
import lk.expensetracker.engine.bank.BankPacks
import lk.expensetracker.engine.interpretation.Interpretation
import lk.expensetracker.engine.interpretation.RuleBasedInterpreter
import lk.expensetracker.engine.learn.BankLearner
import lk.expensetracker.engine.learn.Evaluation
import lk.expensetracker.engine.learn.Field
import lk.expensetracker.engine.learn.GroupSummary
import lk.expensetracker.engine.learn.GroupTemplate
import lk.expensetracker.engine.learn.RuleGenerator
import lk.expensetracker.engine.model.Direction
import lk.expensetracker.engine.model.Kind
import lk.expensetracker.engine.model.RawSms
import lk.expensetracker.sms.ImportWorker
import lk.expensetracker.sms.SmsInboxReader

enum class TeachStep { CHOOSE, KINDS, ADJUST, PREVIEW, SAVED }

data class TeachState(
    val step: TeachStep = TeachStep.CHOOSE,
    val loading: Boolean = false,
    val conversations: List<SmsInboxReader.Conversation> = emptyList(),
    val knownSenders: Set<String> = emptySet(),
    val showPhoneNumbers: Boolean = false,
    val sender: String? = null,
    /** Set when teaching a new message format to a bank the app already has. */
    val existing: BankPack? = null,
    val messages: List<RawSms> = emptyList(),
    val groups: List<GroupTemplate> = emptyList(),
    val summaries: Map<Int, GroupSummary> = emptyMap(),
    val selectedGroup: Int? = null,
    val bankName: String = "",
    val evaluation: Evaluation? = null,
    val pack: BankPack? = null,
)

/** State for the "teach the app a bank" wizard. All learning runs on the device. */
class TeachViewModel(app: Application) : AndroidViewModel(app) {
    private val container = (app as ExpenseApp).container
    private val _state = MutableStateFlow(TeachState())
    val state: StateFlow<TeachState> = _state.asStateFlow()

    fun loadConversations() = viewModelScope.launch {
        _state.update { it.copy(loading = true) }
        val (conversations, known) = withContext(Dispatchers.IO) {
            val interpreter = container.repository.interpreter()
            val list = container.inboxReader.conversations()
            list to list.filter { interpreter.bankFor(it.sender) != null }.map { it.sender }.toSet()
        }
        _state.update { it.copy(loading = false, conversations = conversations, knownSenders = known) }
    }

    fun setShowPhoneNumbers(show: Boolean) = _state.update { it.copy(showPhoneNumbers = show) }

    /**
     * Starts learning from one conversation. For a sender that already belongs to a bank, only the
     * messages the current rules do not understand are used, and the result is added to that bank.
     */
    fun choose(sender: String, existingBankId: String? = null) = viewModelScope.launch {
        _state.update { it.copy(loading = true, sender = sender) }
        val (messages, existing) = withContext(Dispatchers.IO) {
            val all = container.inboxReader.readBySender(sender)
            val repo = container.repository
            val bankEntity = existingBankId?.let { repo.bankPack(it) }
                ?: repo.interpreter().bankFor(sender)?.let { repo.bankPack(it.id) }
            val pack = bankEntity?.let { runCatching { BankPacks.parse(it.json) }.getOrNull() }
            if (pack == null) all to null else {
                val interpreter = RuleBasedInterpreter(listOf(pack))
                all.filter { interpreter.interpretNow(it) is Interpretation.NotRecognized } to pack
            }
        }
        val groups = withContext(Dispatchers.Default) { BankLearner.analyze(messages) }
        _state.update {
            it.copy(
                loading = false, step = TeachStep.KINDS, messages = messages, groups = groups, existing = existing,
                bankName = existing?.displayName ?: friendlyName(sender), selectedGroup = null,
            )
        }
        refreshSummaries()
    }

    fun openGroup(id: Int) = _state.update { it.copy(step = TeachStep.ADJUST, selectedGroup = id) }

    fun back(): Boolean {
        val s = _state.value
        val prev = when (s.step) {
            TeachStep.ADJUST, TeachStep.PREVIEW -> TeachStep.KINDS
            TeachStep.KINDS -> if (s.existing != null) null else TeachStep.CHOOSE
            else -> null
        } ?: return false
        _state.update { it.copy(step = prev) }
        return true
    }

    // ---- Corrections --------------------------------------------------------------------------

    private fun updateGroup(id: Int, change: (GroupTemplate) -> GroupTemplate) {
        _state.update { s -> s.copy(groups = s.groups.map { if (it.id == id) change(it) else it }) }
        refreshSummaries(id)
    }

    fun setTransaction(id: Int, isTransaction: Boolean) = updateGroup(id) { it.copy(isTransaction = isTransaction) }
    fun setField(id: Int, tokenIndex: Int, field: Field) = updateGroup(id) { BankLearner.setField(it, tokenIndex, field) }
    fun setDirection(id: Int, direction: Direction?) = updateGroup(id) { it.copy(direction = direction) }
    fun setKind(id: Int, kind: Kind?) = updateGroup(id) { it.copy(kind = kind) }
    fun setCategory(id: Int, category: String?) = updateGroup(id) { it.copy(category = category) }
    fun setBankName(name: String) = _state.update { it.copy(bankName = name) }

    private fun refreshSummaries(only: Int? = null) {
        viewModelScope.launch {
            val groups = _state.value.groups.filter { only == null || it.id == only }
            val result = withContext(Dispatchers.Default) { groups.associate { it.id to RuleGenerator.summarize(it) } }
            _state.update { it.copy(summaries = it.summaries + result) }
        }
    }

    // ---- Preview and save -----------------------------------------------------------------------

    fun preview() = viewModelScope.launch {
        _state.update { it.copy(loading = true) }
        val s = _state.value
        val pack = withContext(Dispatchers.Default) { buildPack(s) }
        val evaluation = withContext(Dispatchers.Default) { RuleGenerator.evaluate(pack, s.messages) }
        _state.update { it.copy(loading = false, step = TeachStep.PREVIEW, pack = pack, evaluation = evaluation) }
    }

    private suspend fun buildPack(s: TeachState): BankPack {
        val sender = s.sender ?: ""
        val name = s.bankName.ifBlank { friendlyName(sender) }
        val id = s.existing?.id ?: uniqueId(name)
        return RuleGenerator.pack(id, name, sender, s.groups, s.existing).let { if (s.existing != null) it.copy(displayName = name) else it }
    }

    private suspend fun uniqueId(name: String): String {
        val base = name.lowercase().filter { it.isLetterOrDigit() }.ifEmpty { "bank" }
        var id = base
        var n = 2
        while (container.repository.bankPack(id) != null) id = base + n++
        return id
    }

    fun save(onDone: () -> Unit) = viewModelScope.launch {
        val s = _state.value
        val pack = s.pack?.copy(displayName = s.bankName.ifBlank { s.pack.displayName }) ?: buildPack(s)
        withContext(Dispatchers.IO) {
            container.repository.saveBankPack(pack)
            container.repository.reprocessUnparsed()
        }
        ImportWorker.enqueueFullImport(getApplication())
        _state.update { it.copy(step = TeachStep.SAVED) }
        onDone()
    }

    private fun friendlyName(sender: String) = sender.trim().lowercase().replaceFirstChar { it.uppercase() }
        .replace(Regex("(?i)txn$|alerts?$|sms$"), "").trim().ifEmpty { sender }
}
