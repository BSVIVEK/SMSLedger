package local.smsledger.data

import android.content.Context
import android.provider.Telephony
import local.smsledger.core.Sms

interface SmsInbox {
    fun upperBound(): Pair<Long, Long>
    fun page(checkpoint: Checkpoint, batch: BatchState): List<Sms>
}
class AndroidSmsInbox(private val context: Context) : SmsInbox {
    override fun upperBound(): Pair<Long, Long> {
        fun max(column: String): Long = context.contentResolver.query(Telephony.Sms.Inbox.CONTENT_URI,
            arrayOf(column), null, null, "$column DESC")?.use { if(it.moveToFirst()) it.getLong(0) else 0L }
            ?: error("SMS provider unavailable")
        return max(Telephony.Sms._ID) to max(Telephony.Sms.DATE)
    }
    override fun page(checkpoint: Checkpoint, batch: BatchState): List<Sms> {
        val result = mutableListOf<Sms>()
        // ID handles backdated arrivals; timestamp handles newer rows with reused/lower IDs between batches.
        val selection = "(_id > ? OR date > ?) AND _id <= ? AND date <= ? AND _id > ?"
        val args = arrayOf(checkpoint.smsId.toString(), checkpoint.timestamp.toString(), batch.upperId.toString(),
            batch.upperTimestamp.toString(), batch.cursor.toString())
        context.contentResolver.query(Telephony.Sms.Inbox.CONTENT_URI,
            arrayOf("_id", "date", "address", "body"), selection, args, "_id ASC")?.use { cursor ->
            while(result.size < 200 && cursor.moveToNext()) {
                result += Sms(cursor.getLong(0), cursor.getLong(1), cursor.getString(2).orEmpty(), cursor.getString(3).orEmpty())
            }
        } ?: error("SMS provider unavailable")
        return result
    }
}
