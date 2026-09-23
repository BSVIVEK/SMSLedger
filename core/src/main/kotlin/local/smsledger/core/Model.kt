package local.smsledger.core

import java.math.BigDecimal
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

enum class TransactionType(val label: String) {
    EXPENSE("Expense"), INCOME("Income"), REFUND("Refund"), TRANSFER("Transfer"),
    REVERSAL("Reversal"), FAILED("Failed transaction")
}
enum class PaymentMode(val label: String) {
    CREDIT_CARD("Credit Card"), DEBIT_CARD("Debit Card"), UPI("UPI"), BANK_TRANSFER("Bank Transfer"),
    ATM("ATM"), WALLET("Wallet"), CASH("Cash"), OTHER("Other")
}
enum class FundingSource(val label: String) {
    CREDIT_CARD("Credit Card"), DEBIT_CARD("Debit Card"), BANK_ACCOUNT("Bank Account"),
    WALLET("Wallet"), CASH("Cash"), UNKNOWN("Unknown")
}
enum class ReviewStatus(val label: String) {
    AUTO_CONFIRMED("Auto Confirmed"), NEEDS_REVIEW("Needs Review"), UNKNOWN("Unknown / Needs Review"),
    CONFIRMED("Confirmed"), IGNORED("Ignored")
}
data class Sms(val id: Long, val timestamp: Long, val sender: String, val body: String)
data class MerchantRule(val key: String, val merchant: String, val category: String,
    val subcategory: String = "", val exactEntry: String? = null)
data class InstrumentRule(val bank: String, val last4: String, val name: String, val funding: FundingSource)
data class Transaction(
    val smsId: Long, val smsTimestamp: Long, val messageHash: String, val sourceKey: String,
    val duplicateKey: String, val timestamp: Long, val bank: String, val merchant: String,
    val originalMerchantKey: String, val amountMinor: Long?, val currency: String,
    val type: TransactionType, val mode: PaymentMode, val funding: FundingSource,
    val last4: String, val instrument: String, val category: String, val subcategory: String = "",
    val exactEntry: String = "", val confidence: Int, val status: ReviewStatus, val notes: String = ""
)
val Transaction.amount: BigDecimal? get() = amountMinor?.let { BigDecimal.valueOf(it, 2) }
val Transaction.sourceLabel: String get() = when {
        mode == PaymentMode.ATM -> "ATM Withdrawal"
        mode == PaymentMode.UPI && funding == FundingSource.CREDIT_CARD -> "UPI · Credit Card Expense"
        mode == PaymentMode.UPI -> "UPI Expense"
        funding == FundingSource.CREDIT_CARD -> "Credit Card Expense"
        funding == FundingSource.DEBIT_CARD -> "Debit Card Expense"
        funding == FundingSource.BANK_ACCOUNT -> "Bank Account Expense"
        funding == FundingSource.WALLET -> "Wallet Expense"
        else -> funding.label
    }
val Transaction.instrumentLabel: String get() = listOf(instrument.ifBlank { bank }, last4.takeIf { it.isNotBlank() }?.let { "•$it" }.orEmpty()).filter { it.isNotBlank() }.joinToString(" ")
object Privacy {
    // Remove long digit sequences even when separated with spaces/hyphens; no raw bodies are persisted.
    fun safe(text: String): String = text.replace(Regex("(?:\\d[ -]?){5,}"), "[redacted]").take(500)
}
fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
fun merchantKey(value: String): String = Privacy.safe(value).uppercase(Locale.ROOT)
    .replace(Regex("[^A-Z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()
fun statusFor(confidence: Int): ReviewStatus = when {
    confidence >= 90 -> ReviewStatus.AUTO_CONFIRMED
    confidence >= 60 -> ReviewStatus.NEEDS_REVIEW
    else -> ReviewStatus.UNKNOWN
}
fun Transaction.month(zone: ZoneId): String = Instant.ofEpochMilli(timestamp).atZone(zone).toLocalDate().toString().take(7)
