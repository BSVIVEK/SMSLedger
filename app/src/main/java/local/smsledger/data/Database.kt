package local.smsledger.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import local.smsledger.core.*

@Entity(tableName = "transactions", indices = [Index(value = ["sourceKey"], unique = true), Index(value = ["duplicateKey"], unique = true), Index(value = ["timestamp"]), Index(value = ["status"])])
data class TransactionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @Embedded val entry: local.smsledger.core.Transaction
)
@Entity(tableName = "receipts")
data class Receipt(@PrimaryKey val sourceKey: String, val smsId: Long, val smsTimestamp: Long,
    val messageHash: String, val transactionId: Long)
@Entity(tableName = "merchant_rules")
data class MerchantRuleEntity(@PrimaryKey val key: String, val merchant: String, val category: String,
    val subcategory: String, val exactEntry: String?) {
    fun model() = MerchantRule(key, merchant, category, subcategory, exactEntry)
}
@Entity(tableName = "instruments", primaryKeys = ["bank", "last4"])
data class InstrumentEntity(val bank: String, val last4: String, val name: String, val funding: FundingSource) {
    fun model() = InstrumentRule(bank, last4, name, funding)
}
@Entity(tableName = "checkpoint")
data class Checkpoint(@PrimaryKey val id: Int = 1, val smsId: Long = 0, val timestamp: Long = 0, val initialized: Boolean = false)
@Entity(tableName = "batch")
data class BatchState(@PrimaryKey val id: Int = 1, val active: Boolean = false, val upperId: Long = 0,
    val upperTimestamp: Long = 0, val cursor: Long = 0, val scanComplete: Boolean = false,
    val checked: Int = 0, val found: Int = 0, val automatic: Int = 0, val review: Int = 0,
    val duplicates: Int = 0, val started: Long = 0, val completed: Long = 0,
    val excelUpdated: Boolean = false, val error: String = "")
@Entity(tableName = "export_state")
data class ExportState(@PrimaryKey val id: Int = 1, val dirty: Boolean = false, val updated: Long = 0, val error: String = "")

@Dao
interface LedgerDao {
    @Query("SELECT * FROM transactions ORDER BY timestamp DESC") fun observeTransactions(): Flow<List<TransactionEntity>>
    @Query("SELECT * FROM transactions ORDER BY timestamp DESC") suspend fun all(): List<TransactionEntity>
    @Query("SELECT * FROM transactions WHERE id=:id") suspend fun transaction(id: Long): TransactionEntity?
    @Query("SELECT * FROM transactions WHERE duplicateKey=:key") suspend fun duplicate(key: String): TransactionEntity?
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insert(t: TransactionEntity): Long
    @Update suspend fun update(t: TransactionEntity)
    @Query("SELECT * FROM receipts WHERE sourceKey=:key") suspend fun receipt(key: String): Receipt?
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertReceipt(receipt: Receipt)
    @Query("SELECT * FROM merchant_rules") suspend fun rules(): List<MerchantRuleEntity>
    @Query("SELECT * FROM merchant_rules ORDER BY merchant") fun observeRules(): Flow<List<MerchantRuleEntity>>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun saveRule(rule: MerchantRuleEntity)
    @Query("DELETE FROM merchant_rules WHERE `key`=:key") suspend fun deleteRule(key: String)
    @Query("SELECT * FROM instruments") suspend fun instruments(): List<InstrumentEntity>
    @Query("SELECT * FROM instruments ORDER BY bank, last4") fun observeInstruments(): Flow<List<InstrumentEntity>>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun saveInstrument(rule: InstrumentEntity)
    @Query("DELETE FROM instruments WHERE bank=:bank AND last4=:last4") suspend fun deleteInstrument(bank: String, last4: String)
    @Query("SELECT * FROM checkpoint WHERE id=1") suspend fun checkpoint(): Checkpoint?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun saveCheckpoint(c: Checkpoint)
    @Query("SELECT * FROM batch WHERE id=1") suspend fun batch(): BatchState?
    @Query("SELECT * FROM batch WHERE id=1") fun observeBatch(): Flow<BatchState?>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun saveBatch(b: BatchState)
    @Query("SELECT * FROM export_state WHERE id=1") suspend fun exportState(): ExportState?
    @Query("SELECT * FROM export_state WHERE id=1") fun observeExport(): Flow<ExportState?>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun saveExport(state: ExportState)
}
@Database(entities = [TransactionEntity::class, Receipt::class, MerchantRuleEntity::class,
    InstrumentEntity::class, Checkpoint::class, BatchState::class, ExportState::class], version = 1, exportSchema = true)
abstract class LedgerDatabase : RoomDatabase() {
    abstract fun dao(): LedgerDao
}
