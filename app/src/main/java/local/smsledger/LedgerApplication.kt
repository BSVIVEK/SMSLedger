package local.smsledger

import android.app.Application
import android.content.Context
import androidx.room.Room
import kotlinx.coroutines.sync.Mutex
import local.smsledger.data.*

class LedgerApplication : Application() {
    val graph by lazy { Graph(this) }
}
class Graph(context: Context) {
    val gate = Mutex()
    val db = Room.databaseBuilder(context, LedgerDatabase::class.java, "sms-ledger.db").build()
    val exporter = ExcelExporter(context, db)
    val runner = BatchRunner(db, AndroidSmsInbox(context), { exporter.export() })
}
val Context.graph: Graph get() = (applicationContext as LedgerApplication).graph
