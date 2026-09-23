package local.smsledger.core

/** Only confirmed INR transactions affect the dashboard; the workbook groups every currency separately. */
object Totals {
    fun included(t: Transaction) = t.status in setOf(ReviewStatus.AUTO_CONFIRMED, ReviewStatus.CONFIRMED) && t.amountMinor != null
    fun income(t: Transaction): Long = if (included(t) && t.type == TransactionType.INCOME) t.amountMinor ?: 0 else 0
    fun expense(t: Transaction): Long = if (included(t) && t.type == TransactionType.EXPENSE && t.mode != PaymentMode.ATM) t.amountMinor ?: 0 else 0
    fun refunds(t: Transaction): Long = if (included(t) && t.type == TransactionType.REFUND) t.amountMinor ?: 0 else 0
    fun credit(t: Transaction) = if (t.funding == FundingSource.CREDIT_CARD) expense(t) else 0
    fun debit(t: Transaction) = if (t.funding == FundingSource.DEBIT_CARD) expense(t) else 0
    fun bank(t: Transaction) = if (t.funding == FundingSource.BANK_ACCOUNT) expense(t) else 0
    fun upi(t: Transaction) = if (t.mode == PaymentMode.UPI) expense(t) else 0
    fun transfers(t: Transaction) = if (included(t) && t.type == TransactionType.TRANSFER) t.amountMinor ?: 0 else 0
    fun bankTransfers(t: Transaction) = if (t.mode == PaymentMode.BANK_TRANSFER) transfers(t) else 0
    fun cash(t: Transaction) = if (included(t) && t.mode == PaymentMode.ATM && t.type == TransactionType.EXPENSE) t.amountMinor ?: 0 else 0
}
