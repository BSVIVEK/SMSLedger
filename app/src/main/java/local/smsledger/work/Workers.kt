package local.smsledger.work

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.work.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import local.smsledger.graph
import java.util.concurrent.TimeUnit

abstract class LedgerWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    protected suspend fun perform(needsSms: Boolean, block: suspend () -> Result): Result = withContext(Dispatchers.IO) {
        val graph = applicationContext.graph
        graph.gate.withLock {
            if (needsSms && ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) {
                graph.runner.recordError("SMS permission is missing. Grant permission and run the batch again.")
                return@withLock Result.failure()
            }
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (e: SecurityException) {
                graph.runner.recordError("Permission denied. Check SMS permission and local export access, then retry.")
                Result.failure()
            }
            catch (e: Exception) {
                // Never log exception text: provider errors may contain private data.
                graph.runner.recordError("Batch/export failed (${e.javaClass.simpleName}). Retry scheduled; check free space and permissions.")
                Result.retry()
            }
        }
    }
}
class InitialSmsImportWorker(context: Context, params: WorkerParameters) : LedgerWorker(context, params) {
    override suspend fun doWork() = perform(true) {
        if(applicationContext.graph.runner.scan()) Result.success() else Result.retry()
    }
}
class DailySmsTransactionWorker(context: Context, params: WorkerParameters) : LedgerWorker(context, params) {
    override suspend fun doWork() = perform(true) {
        if (!applicationContext.graph.runner.scan()) Result.retry()
        else { applicationContext.graph.runner.exportAndCommit(); Result.success() }
    }
}
class ExcelExportWorker(context: Context, params: WorkerParameters) : LedgerWorker(context, params) {
    override suspend fun doWork() = perform(false) {
        applicationContext.graph.runner.exportAndCommit(); Result.success()
    }
}
object Scheduler {
    const val DAILY = "sms-ledger-daily"
    const val BATCH = "sms-ledger-manual"
    fun daily(context: Context, enabled: Boolean) {
        val manager = WorkManager.getInstance(context)
        if (!enabled) { manager.cancelUniqueWork(DAILY); return }
        val request = PeriodicWorkRequestBuilder<DailySmsTransactionWorker>(24, TimeUnit.HOURS)
            .setInitialDelay(24, TimeUnit.HOURS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()
        manager.enqueueUniquePeriodicWork(DAILY, ExistingPeriodicWorkPolicy.KEEP, request)
    }
    fun batch(context: Context) {
        val import = OneTimeWorkRequestBuilder<InitialSmsImportWorker>()
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()
        WorkManager.getInstance(context).beginUniqueWork(BATCH, ExistingWorkPolicy.KEEP, import)
            .then(OneTimeWorkRequestBuilder<ExcelExportWorker>().build()).enqueue()
    }
    fun export(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork("sms-ledger-export", ExistingWorkPolicy.APPEND_OR_REPLACE,
            OneTimeWorkRequestBuilder<ExcelExportWorker>().setBackoffCriteria(BackoffPolicy.EXPONENTIAL,30,TimeUnit.SECONDS).build())
    }
}
