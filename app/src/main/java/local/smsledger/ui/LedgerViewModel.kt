package local.smsledger.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import local.smsledger.core.*
import local.smsledger.data.*
import local.smsledger.graph
import local.smsledger.work.Scheduler

class LedgerViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = app.graph
    private val dao = graph.db.dao()
    val transactions = dao.observeTransactions().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val batch = dao.observeBatch().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val export = dao.observeExport().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val rules = dao.observeRules().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val instruments = dao.observeInstruments().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val message = MutableStateFlow("")
    private fun mutate(block: suspend () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try { graph.gate.withLock { block() }; Scheduler.export(getApplication()) }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { message.value = "Could not save changes. Please retry." }
        }
    }
    fun save(original: TransactionEntity, edited: Transaction, learn: Boolean, learnExact: Boolean) = mutate {
        require((edited.amountMinor ?: -1L) >= 0)
        graph.db.withTransaction {
            val safe = edited.copy(merchant = Privacy.safe(edited.merchant), exactEntry = Privacy.safe(edited.exactEntry),
                subcategory = Privacy.safe(edited.subcategory), notes = Privacy.safe(edited.notes),
                instrument = Privacy.safe(edited.instrument), bank = Privacy.safe(edited.bank),
                last4 = edited.last4.filter(Char::isDigit).takeLast(4), status = ReviewStatus.CONFIRMED)
            dao.update(original.copy(entry = safe))
            if (learn && original.entry.originalMerchantKey != "UNKNOWN" && safe.category != "Unknown") {
                dao.saveRule(MerchantRuleEntity("${original.entry.originalMerchantKey}|${safe.type.name}", safe.merchant,
                    safe.category, safe.subcategory, if(learnExact) safe.exactEntry else null))
            }
            dao.saveExport((dao.exportState() ?: ExportState()).copy(dirty = true))
        }
    }
    fun status(row: TransactionEntity, status: ReviewStatus) = mutate {
        if (status == ReviewStatus.CONFIRMED && row.entry.amountMinor == null) {
            message.value = "Edit the amount before confirming."; return@mutate
        }
        graph.db.withTransaction {
            // Fetch latest to avoid overwriting edits with a stale list item.
            dao.transaction(row.id)?.let { dao.update(it.copy(entry = it.entry.copy(status = status))) }
            dao.saveExport((dao.exportState() ?: ExportState()).copy(dirty = true))
        }
    }
    fun instrument(bank: String, last4: String, name: String, funding: FundingSource) = mutate {
        require(last4.matches(Regex("[0-9]{4}")))
        dao.saveInstrument(InstrumentEntity(bank, last4, Privacy.safe(name), funding))
    }
    fun removeRule(rule: MerchantRuleEntity) = mutate { dao.deleteRule(rule.key) }
    fun removeInstrument(rule: InstrumentEntity) = mutate { dao.deleteInstrument(rule.bank, rule.last4) }
}
