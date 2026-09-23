package local.smsledger.core

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.ZoneId
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

class WorkbookTest {
    private fun transaction() = TransactionParser(ZoneId.of("Asia/Kolkata")).parse(Sms(1,1789617600000,"AD-HDFCBK",
        "INR 100.25 spent on HDFC credit card XX1234 at AMAZON on 17-09-2026 12:30"))!!
    @Test fun allWorksheetsAreValidXmlAndStringsCannotBecomeFormulas() {
        val out = ByteArrayOutputStream()
        val t = transaction().copy(exactEntry = "=HYPERLINK(\"bad\") & <test>")
        XlsxWriter(ZoneId.of("Asia/Kolkata")).write(out,listOf(t),emptyList(),emptyList())
        val entries = mutableMapOf<String,String>()
        ZipInputStream(ByteArrayInputStream(out.toByteArray())).use { zip ->
            while(true) {
                val e = zip.nextEntry ?: break
                val bytes = zip.readBytes()
                DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(ByteArrayInputStream(bytes))
                entries[e.name] = bytes.toString(Charsets.UTF_8)
            }
        }
        val book = entries.getValue("xl/workbook.xml")
        listOf("Transactions", "Needs Review", "Sep-2026", "Summary", "Categories", "Payment Sources").forEach { assertTrue(book.contains(it)) }
        val sheet = entries.getValue("xl/worksheets/sheet1.xml")
        assertTrue(sheet.contains("inlineStr")); assertTrue(sheet.contains("=HYPERLINK")); assertFalse(sheet.contains("<f>"))
        assertTrue(sheet.contains("<v>100.25</v>")); assertTrue(sheet.contains("&amp; &lt;test&gt;"))
    }
    @Test fun accountingAvoidsTransfersWithdrawalsAndFundingOverlap() {
        val t=transaction()
        assertEquals(10025L, Totals.expense(t)); assertEquals(10025L, Totals.credit(t))
        val upi=t.copy(mode=PaymentMode.UPI)
        assertEquals(10025L, Totals.upi(upi)); assertEquals(10025L, Totals.credit(upi))
        assertEquals(0L, Totals.expense(t.copy(type=TransactionType.TRANSFER)))
        assertEquals(0L, Totals.expense(t.copy(type=TransactionType.FAILED)))
        assertEquals(0L, Totals.expense(t.copy(type=TransactionType.REVERSAL)))
        assertEquals(0L, Totals.expense(t.copy(mode=PaymentMode.ATM)))
        assertEquals(10025L, Totals.cash(t.copy(mode=PaymentMode.ATM)))
        assertEquals(0L, Totals.expense(t.copy(status=ReviewStatus.NEEDS_REVIEW)))
        assertEquals(0L, Totals.expense(t.copy(status=ReviewStatus.IGNORED)))
        assertEquals(10025L, Totals.refunds(t.copy(type=TransactionType.REFUND)))
    }
}
