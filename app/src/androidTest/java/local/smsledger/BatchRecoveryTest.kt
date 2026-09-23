package local.smsledger

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import local.smsledger.core.*
import local.smsledger.data.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

@RunWith(AndroidJUnit4::class)
class BatchRecoveryTest {
    private lateinit var db: LedgerDatabase
    private val body="INR 100 spent on HDFC credit card XX1234 at AMAZON on 17-09-2026 12:30. Ref 123456789012"
    private class Inbox(val items: MutableList<Sms>) : SmsInbox {
        override fun upperBound() = (items.maxOfOrNull { it.id } ?: 0L) to (items.maxOfOrNull { it.timestamp } ?: 0L)
        override fun page(checkpoint: Checkpoint, batch: BatchState) = items.filter {
            (it.id>checkpoint.smsId || it.timestamp>checkpoint.timestamp) && it.id<=batch.upperId && it.timestamp<=batch.upperTimestamp && it.id>batch.cursor
        }.sortedBy { it.id }.take(200)
    }
    @Before fun setup() {
        db=Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), LedgerDatabase::class.java).build()
    }
    @After fun close() { db.close() }
    @Test fun exportFailureKeepsCheckpointAndRetryKeepsCorrection() = runBlocking {
        val inbox=Inbox(mutableListOf(Sms(1,1000,"AD-HDFCBK",body)))
        var failExport=true
        val runner=BatchRunner(db,inbox,{ if(failExport) throw IOException("synthetic failure") })
        assertTrue(runner.scan())
        try { runner.exportAndCommit(); fail("Export must fail") } catch(_: IOException) {}
        assertNull(db.dao().checkpoint())
        val saved=db.dao().all().single()
        db.dao().update(saved.copy(entry=saved.entry.copy(exactEntry="Samsung Charger",status=ReviewStatus.CONFIRMED)))
        failExport=false
        assertTrue(runner.scan()); runner.exportAndCommit()
        assertEquals(1L, db.dao().checkpoint()!!.smsId)
        assertEquals("Samsung Charger",db.dao().all().single().entry.exactEntry)
        assertTrue(runner.scan()); runner.exportAndCommit()
        assertEquals(1,db.dao().all().size)
    }
    @Test fun receiptAndReferenceUniquenessSurviveReplay() = runBlocking {
        val inbox=Inbox(mutableListOf(Sms(1,1000,"AD-HDFCBK",body),Sms(2,2000,"AD-HDFCBK",body)))
        val runner=BatchRunner(db,inbox,{})
        runner.scan(); runner.exportAndCommit()
        assertEquals(1,db.dao().all().size); assertEquals(1,db.dao().batch()!!.duplicates)
        db.dao().saveCheckpoint(Checkpoint())
        runner.scan(); runner.exportAndCommit()
        assertEquals(1,db.dao().all().size); assertEquals(2,db.dao().batch()!!.duplicates)
    }
    @Test fun interruptedPageResumesAndLateArrivalsWaitForNextBatch() = runBlocking {
        val inbox=Inbox((1L..201L).map { Sms(it,it*1000,"AD-HDFCBK",body.replace("123456789012", "123456${it.toString().padStart(6,'0')}")) }.toMutableList())
        val runner=BatchRunner(db,inbox,{})
        assertFalse(runner.scan(0))
        assertNull(db.dao().checkpoint()); assertEquals(200, db.dao().all().size)
        inbox.items.add(Sms(202,202000,"AD-HDFCBK",body.replace("123456789012", "999999999999")))
        runner.scan(); runner.exportAndCommit()
        assertEquals(201, db.dao().all().size); assertEquals(201L,db.dao().checkpoint()!!.smsId)
        runner.scan(); runner.exportAndCommit()
        assertEquals(202, db.dao().all().size)
    }
    @Test fun backdatedNewSmsUsesIdAndNonfinancialMessagesAreNotStored() = runBlocking {
        val inbox=Inbox(mutableListOf(Sms(1,10000,"AD-HDFCBK","OTP 654321 for credit card payment INR 100")))
        val runner=BatchRunner(db,inbox,{})
        runner.scan(); runner.exportAndCommit()
        assertTrue(db.dao().all().isEmpty()); assertEquals(1L,db.dao().checkpoint()!!.smsId)
        inbox.items.add(Sms(2,5000,"AD-HDFCBK",body))
        runner.scan(); runner.exportAndCommit()
        assertEquals(1,db.dao().all().size)
    }
}
