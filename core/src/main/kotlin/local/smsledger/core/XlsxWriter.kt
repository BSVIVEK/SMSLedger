package local.smsledger.core

import java.io.OutputStream
import java.io.FilterOutputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Small, dependency-free OOXML writer. Strings are inline text, never executable formulas. */
class XlsxWriter(private val zone: ZoneId = ZoneId.systemDefault()) {
    private data class Sheet(val name: String, val rows: Sequence<List<Any?>>)
    private val headers = listOf("Date", "Time", "Exact Entry", "Merchant", "Category", "Subcategory", "Type",
        "Payment Mode", "Funding Source", "Bank", "Card/Account", "Amount", "Currency", "Status", "Notes", "Instrument", "Confidence", "SMS ID", "SMS Timestamp")
    private fun row(t: Transaction): List<Any?> {
        val date = Instant.ofEpochMilli(t.timestamp).atZone(zone)
        return listOf(date.toLocalDate().toString(), date.toLocalTime().format(DateTimeFormatter.ofPattern("HH:mm:ss")),
            t.exactEntry, t.merchant, t.category, t.subcategory, t.type.label, t.mode.label, t.funding.label,
            t.bank, t.last4.takeIf { it.isNotBlank() }?.let { "•$it" }.orEmpty(), t.amount, t.currency,
            t.status.label, t.notes, t.instrument, t.confidence, t.smsId.toString(),
            Instant.ofEpochMilli(t.smsTimestamp).atZone(zone).toString())
    }
    fun write(output: OutputStream, transactions: List<Transaction>, rules: List<MerchantRule>, instruments: List<InstrumentRule>) {
        require(transactions.size < 1_048_576) { "Workbook row limit exceeded" }
        val sorted = transactions.sortedByDescending { it.timestamp }
        val sheets = mutableListOf(
            Sheet("Transactions", sequenceOf(headers) + sorted.asSequence().map(::row)),
            Sheet("Needs Review", sequenceOf(headers) + sorted.asSequence().filter { it.status in setOf(ReviewStatus.NEEDS_REVIEW, ReviewStatus.UNKNOWN) }.map(::row)))
        sorted.groupBy { it.month(zone) }.toSortedMap().forEach { (month, rows) ->
            val name = java.time.YearMonth.parse(month).format(DateTimeFormatter.ofPattern("MMM-uuuu", Locale.ENGLISH))
            sheets += Sheet(name, sequenceOf(headers) + rows.asSequence().map(::row))
        }
        sheets += Sheet("Summary", summary(sorted).asSequence())
        sheets += Sheet("Categories", sequence {
            yield(listOf("Kind", "Category", "Merchant Key", "Display Merchant", "Subcategory", "Exact Entry Rule"))
            Categories.expenses.forEach { yield(listOf("Expense", it)) }
            Categories.incomes.forEach { yield(listOf("Income", it)) }
            rules.forEach { yield(listOf("Learned Rule", it.category, it.key, it.merchant, it.subcategory, it.exactEntry.orEmpty())) }
        })
        sheets += Sheet("Payment Sources", sequence {
            yield(listOf("Bank", "Last Four", "Instrument", "Funding Source"))
            instruments.forEach { yield(listOf(it.bank, "•${it.last4}", it.name, it.funding.label)) }
            sorted.distinctBy { "${it.bank}|${it.last4}|${it.instrument}|${it.funding}" }.forEach {
                yield(listOf(it.bank, it.last4.takeIf(String::isNotEmpty)?.let { v -> "•$v" }.orEmpty(), it.instrument, it.funding.label))
            }
        })
        ZipOutputStream(object : FilterOutputStream(output) { override fun close() { flush() } }).use { zip ->
        fun entry(name: String, content: String) {
            zip.putNextEntry(ZipEntry(name)); zip.write(content.toByteArray(Charsets.UTF_8)); zip.closeEntry()
        }
        entry("[Content_Types].xml", """<?xml version="1.0" encoding="UTF-8"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>${sheets.indices.joinToString("") { "<Override PartName=\"/xl/worksheets/sheet${it+1}.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>" }}</Types>""")
        entry("_rels/.rels", """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>""")
        entry("xl/workbook.xml", """<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets>${sheets.mapIndexed { i,s -> "<sheet name=\"${xml(s.name)}\" sheetId=\"${i+1}\" r:id=\"rId${i+1}\"/>" }.joinToString("")}</sheets></workbook>""")
        entry("xl/_rels/workbook.xml.rels", """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">${sheets.indices.joinToString("") { "<Relationship Id=\"rId${it+1}\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet${it+1}.xml\"/>" }}<Relationship Id="rId${sheets.size+1}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/></Relationships>""")
        entry("xl/styles.xml", """<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><fonts count="2"><font><sz val="11"/><name val="Calibri"/></font><font><b/><color rgb="FFFFFFFF"/><sz val="11"/><name val="Calibri"/></font></fonts><fills count="3"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill><fill><patternFill patternType="solid"><fgColor rgb="FF176A5A"/><bgColor indexed="64"/></patternFill></fill></fills><borders count="1"><border/></borders><cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs><cellXfs count="3"><xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/><xf numFmtId="0" fontId="1" fillId="2" borderId="0" xfId="0" applyFont="1" applyFill="1"/><xf numFmtId="4" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/></cellXfs><cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles></styleSheet>""")
        sheets.forEachIndexed { i, sheet ->
            zip.putNextEntry(ZipEntry("xl/worksheets/sheet${i+1}.xml"))
            fun write(s: String) { zip.write(s.toByteArray(Charsets.UTF_8)) }
            write("""<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetViews><sheetView workbookViewId="0"><pane ySplit="1" topLeftCell="A2" activePane="bottomLeft" state="frozen"/></sheetView></sheetViews><cols><col min="1" max="19" width="22" customWidth="1"/></cols><sheetData>""")
            var count = 0; var width = 1
            sheet.rows.forEachIndexed { r, values ->
                count++; width = maxOf(width, values.size)
                write("<row r=\"${r+1}\">")
                values.forEachIndexed { c, value ->
                    val ref = "${column(c)}${r+1}"
                    when {
                        value == null -> write("<c r=\"$ref\"/>")
                        value is Number -> write("<c r=\"$ref\" s=\"${if(r==0) 1 else 2}\"><v>$value</v></c>")
                        else -> write("<c r=\"$ref\" t=\"inlineStr\" s=\"${if(r==0) 1 else 0}\"><is><t xml:space=\"preserve\">${xml(value.toString().take(32767))}</t></is></c>")
                    }
                }
                write("</row>")
            }
            write("</sheetData><autoFilter ref=\"A1:${column(width-1)}$count\"/></worksheet>")
            zip.closeEntry()
        }
        zip.finish(); zip.flush() // Caller owns stream, allowing fsync before atomic rename.
        }
    }
    private fun summary(rows: List<Transaction>): List<List<Any?>> = buildList {
        add(listOf("Section", "Period / Name", "Currency", "Metric", "Amount"))
        add(listOf("Accounting", "Confirmed entries only. Transfers, cash withdrawals and reversals are excluded from income/expense."))
        add(listOf("Accounting", "Refunds are separate; net savings = income - gross expense + refunds. Refund date determines month."))
        add(listOf("Accounting", "UPI overlaps funding totals: UPI on credit card appears in both. Do not add them."))
        add(listOf("Accounting", "Reversal notices do not automatically cancel original expenses. Ignore/correct the original after verification."))
        rows.groupBy { it.month(zone) to it.currency }.toSortedMap(compareBy<Pair<String,String>> { it.first }.thenBy { it.second }).forEach { (key, values) ->
            val metrics: List<Pair<String, (Transaction) -> Long>> = listOf(
                "Monthly Income" to Totals::income, "Monthly Expense" to Totals::expense, "Refunds" to Totals::refunds,
                "Net Savings" to { t -> Totals.income(t) - Totals.expense(t) + Totals.refunds(t) },
                "Credit Card Expenses" to Totals::credit, "Debit Card Expenses" to Totals::debit,
                "UPI Expenses" to Totals::upi, "Bank Account Expenses" to Totals::bank,
                "All Transfers" to Totals::transfers, "Bank Transfers" to Totals::bankTransfers, "Cash Withdrawals" to Totals::cash)
            metrics.forEach { (label, calc) -> add(listOf("Monthly", key.first, key.second, label, java.math.BigDecimal.valueOf(values.sumOf(calc), 2))) }
        }
        fun grouped(label: String, key: (Transaction) -> String) {
            rows.groupBy { key(it) to it.currency }.toList().sortedBy { it.first.first }.forEach { (k, v) ->
                add(listOf(label, k.first, k.second, "Expense", java.math.BigDecimal.valueOf(v.sumOf(Totals::expense),2)))
                add(listOf(label, k.first, k.second, "Income", java.math.BigDecimal.valueOf(v.sumOf(Totals::income),2)))
                add(listOf(label, k.first, k.second, "Refund", java.math.BigDecimal.valueOf(v.sumOf(Totals::refunds),2)))
            }
        }
        grouped("Category") { it.category }; grouped("Merchant") { it.merchant }
        grouped("Bank/card") { "${it.bank} · ${it.instrumentLabel}" }
    }
    private fun column(index: Int): String { var n=index+1; var s=""; while(n>0) { n--; s=('A'+n%26)+s; n/=26 }; return s }
    private fun xml(value: String): String = value.filter { it == '\t' || it == '\n' || it == '\r' || it >= ' ' }
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")
}
