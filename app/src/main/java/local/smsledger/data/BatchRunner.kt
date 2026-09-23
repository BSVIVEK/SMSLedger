package local.smsledger.data

import androidx.room.withTransaction
import kotlinx.coroutines.ensureActive
import local.smsledger.core.*
import kotlin.coroutines.coroutineContext

/** All entry points share Graph.gate. Pending cursor is NOT the successful batch checkpoint. */
class BatchRunner(private val db: LedgerDatabase, private val inbox: SmsInbox,
    private val export: suspend () -> Unit, private val parser: TransactionParser = TransactionParser()) {
    private val dao = db.dao()
    suspend fun scan(timeBudgetMillis: Long = 60_000): Boolean {
        val begin = System.nanoTime()
        val checkpoint = dao.checkpoint() ?: Checkpoint()
        var batch = dao.batch()?.takeIf { it.active } ?: inbox.upperBound().let { (id, time) ->
            BatchState(active = true, upperId = id, upperTimestamp = time, started = System.currentTimeMillis(), completed = dao.batch()?.completed ?: 0).also { dao.saveBatch(it) }
        }
        val rules = dao.rules().map { it.model() }
        val instruments = dao.instruments().map { it.model() }
        while (!batch.scanComplete) {
            coroutineContext.ensureActive()
            val page = inbox.page(checkpoint, batch)
            if (page.isEmpty()) {
                batch = batch.copy(scanComplete = true, error = "")
                dao.saveBatch(batch)
                break
            }
            db.withTransaction {
                for (sms in page) {
                    coroutineContext.ensureActive()
                    batch = batch.copy(checked = batch.checked+1, cursor = sms.id, error = "")
                    val parsed = parser.parse(sms, rules, instruments) ?: continue
                    if (dao.receipt(parsed.sourceKey) != null) {
                        batch = batch.copy(duplicates = batch.duplicates+1); continue
                    }
                    val existing = dao.duplicate(parsed.duplicateKey)
                    val newId = if (existing == null) dao.insert(TransactionEntity(entry = parsed)) else -1
                    val transactionId = if (newId != -1L) newId else existing?.id ?: dao.duplicate(parsed.duplicateKey)?.id
                        ?: error("Transaction identity conflict")
                    dao.insertReceipt(Receipt(parsed.sourceKey, sms.id, sms.timestamp, parsed.messageHash, transactionId))
                    batch = if (newId == -1L) batch.copy(duplicates = batch.duplicates+1) else batch.copy(
                        found = batch.found+1,
                        automatic = batch.automatic + if(parsed.status == ReviewStatus.AUTO_CONFIRMED) 1 else 0,
                        review = batch.review + if(parsed.status != ReviewStatus.AUTO_CONFIRMED) 1 else 0)
                }
                dao.saveBatch(batch)
                dao.saveExport((dao.exportState() ?: ExportState()).copy(dirty = true))
            }
            if ((System.nanoTime()-begin)/1_000_000 >= timeBudgetMillis) return false
        }
        return true
    }
    suspend fun exportAndCommit() {
        val batch = dao.batch()
        // Export may fail. Never change successful checkpoint before all requested outputs succeed.
        export()
        if (batch?.active == true && batch.scanComplete) db.withTransaction {
            dao.saveCheckpoint(Checkpoint(smsId = batch.upperId, timestamp = batch.upperTimestamp, initialized = true))
            dao.saveBatch(batch.copy(active = false, completed = System.currentTimeMillis(), excelUpdated = true, error = ""))
        }
    }
    suspend fun recordError(message: String) {
        val batch = dao.batch() ?: BatchState()
        dao.saveBatch(batch.copy(error = message, excelUpdated = false))
        dao.saveExport((dao.exportState() ?: ExportState()).copy(dirty = true, error = message))
    }
}
