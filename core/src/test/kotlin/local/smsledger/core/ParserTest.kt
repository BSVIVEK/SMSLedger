package local.smsledger.core

import org.junit.Assert.*
import org.junit.Test
import java.time.ZoneId

class ParserTest {
    private val parser = TransactionParser(ZoneId.of("Asia/Kolkata"))
    private fun sms(body: String, id: Long = 1, time: Long = 1789617600000, sender: String = "AD-HDFCBK") = Sms(id, time, sender, body)
    private fun card(merchant: String = "AMAZON") = "INR 1299.00 spent on HDFC credit card XX1234 at $merchant on 17-09-2026 14:30. Avl Bal INR 50000.00"
    @Test fun creditCardAndBalance() {
        val t = parser.parse(sms(card()))!!
        assertEquals(129900L, t.amountMinor); assertEquals("1234", t.last4)
        assertEquals("Shopping", t.category); assertEquals("AMAZON", t.merchant)
        assertEquals(FundingSource.CREDIT_CARD, t.funding); assertEquals(PaymentMode.CREDIT_CARD, t.mode)
        assertEquals(ReviewStatus.AUTO_CONFIRMED, t.status)
    }
    @Test fun rupayCreditOnUpiUsesTwoDimensions() {
        val t = parser.parse(sms("INR 250 paid using Tata Neu HDFC RuPay credit card XX1234 to SWIGGY via UPI on 17/09/2026 12:30. Ref 123456789012"))!!
        assertEquals(PaymentMode.UPI, t.mode); assertEquals(FundingSource.CREDIT_CARD, t.funding)
        assertEquals("Tata Neu HDFC RuPay", t.instrument); assertEquals("Food & Dining", t.category)
    }
    @Test fun cardInstrumentDoesNotAssumeFundingWithoutEvidence() {
        val body = "INR 100 paid using HDFC card XX1234 to AMAZON via UPI on 17/09/2026 12:30"
        val unknown = parser.parse(sms(body))!!
        assertEquals(FundingSource.UNKNOWN, unknown.funding); assertTrue(unknown.confidence < 60)
        val mapped = parser.parse(sms(body), instruments = listOf(InstrumentRule("HDFC", "1234", "Tata Neu HDFC RuPay", FundingSource.CREDIT_CARD)))!!
        assertEquals(FundingSource.CREDIT_CARD, mapped.funding); assertEquals(PaymentMode.UPI, mapped.mode)
    }
    @Test fun debitCardIsNotIncomeBecauseItMentionsCreditLimit() {
        val t = parser.parse(sms("INR 999 spent on HDFC debit card XX7777 at FLIPKART on 17-09-2026. Available credit limit INR 10000"))!!
        assertEquals(TransactionType.EXPENSE, t.type); assertEquals(FundingSource.DEBIT_CARD, t.funding)
        assertEquals(99900L, t.amountMinor)
    }
    @Test fun unclassifiedBankUpiNeedsReview() {
        val t = parser.parse(sms("A/c XX2345 debited INR 120 to MURUGAN STORES via UPI on 17-09-2026"))!!
        assertEquals(FundingSource.BANK_ACCOUNT, t.funding); assertEquals("Unknown", t.category)
        assertTrue(t.confidence < 60)
    }
    @Test fun learnedCategoryDoesNotInventExactEntry() {
        val body = "A/c XX2345 debited INR 120 to MURUGAN STORES via UPI on 17-09-2026"
        val rule = MerchantRule("MURUGAN STORES|EXPENSE", "Murugan Stores", "Groceries")
        val t = parser.parse(sms(body), listOf(rule))!!
        assertEquals("Groceries", t.category); assertEquals("", t.exactEntry)
        assertEquals("Monthly Provisions", parser.parse(sms(body), listOf(rule.copy(exactEntry = "Monthly Provisions")))!!.exactEntry)
    }
    @Test fun refundIsNotOverriddenByExpenseRule() {
        val t = parser.parse(sms("Refund INR 500 credited to HDFC account XX1234 from AMAZON on 17-09-2026"),
            listOf(MerchantRule("AMAZON|EXPENSE", "Amazon", "Shopping")))!!
        assertEquals(TransactionType.REFUND, t.type); assertEquals("Refund", t.category)
    }
    @Test fun incomeCategories() {
        for((purpose, category) in listOf("salary" to "Salary", "dividend" to "Dividend", "interest" to "Interest", "cashback" to "Cashback", "redemption" to "Investment Redemption")) {
            val t = parser.parse(sms("INR 100 credited to account XX1234 from COMPANY on 17-09-2026 for $purpose"))!!
            assertEquals(TransactionType.INCOME, t.type); assertEquals(category, t.category)
        }
    }
    @Test fun rejectedMessagesNeverBecomeRows() {
        listOf("OTP 987654 for INR 500 transaction on credit card XX1234. Do not share.",
            "INR 500 will be debited from account XX1234 tomorrow",
            "Reminder: credit card payment due INR 900",
            "Offer: INR 200 cashback credited when you apply now",
            "Your account balance is INR 80000",
            "UPI payment request received for INR 120").forEach { assertNull(it, parser.parse(sms(it))) }
        assertNull(parser.parse(sms("INR 500 paid from bank account to you", sender = "+919876543210")))
    }
    @Test fun failureAndReversalAreNotExpenses() {
        assertEquals(TransactionType.FAILED, parser.parse(sms("INR 500 transaction failed on HDFC credit card XX1234 at AMAZON"))!!.type)
        assertEquals(TransactionType.REVERSAL, parser.parse(sms("INR 500 reversed to HDFC account XX1234 from AMAZON"))!!.type)
    }
    @Test fun cardBillPaymentIsTransfer() {
        assertEquals(TransactionType.TRANSFER, parser.parse(sms("HDFC credit card payment received INR 5000 from account XX1234"))!!.type)
    }
    @Test fun neftTransferAndAtm() {
        val transfer = parser.parse(sms("INR 500 transferred from HDFC account XX1234 to FAMILY via NEFT on 17-09-2026"))!!
        assertEquals(TransactionType.TRANSFER, transfer.type); assertEquals(PaymentMode.BANK_TRANSFER, transfer.mode)
        val atm = parser.parse(sms("INR 500 withdrawn from HDFC account XX1234 at ATM on 17-09-2026"))!!
        assertEquals(PaymentMode.ATM, atm.mode); assertEquals("ATM Withdrawal", atm.category)
    }
    @Test fun merchantRules() {
        listOf("ZOMATO" to "Food & Dining", "IOCL" to "Fuel", "BPCL" to "Fuel", "HPCL" to "Fuel",
            "UBER" to "Travel", "OLA" to "Travel", "IRCTC" to "Travel", "APOLLO" to "Medical", "PHARMACY" to "Medical",
            "ZERODHA" to "Investment", "GROWW" to "Investment").forEach { (merchant, expected) ->
            assertEquals(merchant, expected, parser.parse(sms(card(merchant)))!!.category)
        }
    }
    @Test fun amountIsExactAndOverflowIsReviewable() {
        assertEquals(12345678L, parser.parse(sms(card().replace("1299.00", "1,23,456.78")))!!.amountMinor)
        assertNull(parser.parse(sms(card().replace("1299.00", "999999999999999999999.00")))!!.amountMinor)
    }
    @Test fun missingAmountOrMultipleAmountsAreReviewable() {
        assertNull(parser.parse(sms("HDFC credit card XX1234 debited at AMAZON on 17-09-2026"))!!.amountMinor)
        val t = parser.parse(sms("INR 100 spent on HDFC credit card XX1234 at AMAZON and INR 200 at FLIPKART"))!!
        assertNull(t.amountMinor); assertTrue(t.confidence < 60)
    }
    @Test fun receiptIdentityAndReferenceDedup() {
        val body = card()+" Ref 123456789012"
        val one = parser.parse(sms(body))!!; val retry = parser.parse(sms(body))!!
        assertEquals(one.sourceKey, retry.sourceKey)
        val redelivery = parser.parse(sms(body, id = 2, time = 1789617601000))!!
        assertNotEquals(one.sourceKey, redelivery.sourceKey); assertEquals(one.duplicateKey, redelivery.duplicateKey)
        val original = parser.parse(sms(card()))!!
        val repeatPurchase = parser.parse(sms(card(), id = 3, time = 1789617660000))!!
        assertNotEquals(original.duplicateKey, repeatPurchase.duplicateKey)
    }
    @Test fun reusedSmsIdDoesNotEraseANewTransaction() {
        val a = parser.parse(sms(card()))!!
        val b = parser.parse(sms(card().replace("1299.00", "99.00"), time = 1789617660000))!!
        assertNotEquals(a.sourceKey, b.sourceKey)
    }
    @Test fun fullAccountNumberNeverLeavesParser() {
        val t = parser.parse(sms("INR 500 debited from HDFC account 123456789012 to SHOP 9988776655 on 17-09-2026"))!!
        assertEquals("9012", t.last4)
        assertFalse(t.toString().contains("123456789012")); assertFalse(t.toString().contains("9988776655"))
        assertEquals("[redacted]", Privacy.safe("1234 5678 9012 3456"))
    }
    @Test fun invalidDateFallsBackToSmsTimeAndNeedsReview() {
        val input = sms(card().replace("17-09-2026", "31-02-2026"))
        val t = parser.parse(input)!!
        assertEquals(input.timestamp, t.timestamp); assertTrue(t.confidence < 90)
    }
    @Test fun bankSpecificInfoTemplates() {
        val icici=parser.parse(sms("ICICI account XX1234 debited INR 100 on 17-09-2026. Info: UPI/SWIGGY/123456789012", sender="AD-ICICIB"))!!
        assertEquals("SWIGGY",icici.merchant); assertEquals("Food & Dining",icici.category)
        val sbi=parser.parse(sms("SBI account XX1234 debited INR 100 trf to IRCTC on 17-09-2026",sender="AD-SBIINB"))!!
        assertEquals("IRCTC",sbi.merchant); assertEquals("SBI",sbi.bank)
    }
    @Test fun merchantSkipsAccountAndBankClauses() {
        val purchase = parser.parse(sms("INR 100 debited from HDFC account XX1234 to AMAZON via UPI on 17-09-2026 12:30"))!!
        assertEquals("AMAZON", purchase.merchant)
        val refund = parser.parse(sms("Refund INR 100 credited to account XX1234 from AMAZON on 17-09-2026 12:30"))!!
        assertEquals("AMAZON", refund.merchant)
    }
    @Test fun initiatedRefundCannotAutoConfirm() {
        val t=parser.parse(sms("Refund INR 100 initiated to HDFC credit card XX1234 from AMAZON on 17-09-2026 12:30"))!!
        assertTrue(t.confidence < 60)
    }
    @Test fun confidenceThresholds() {
        assertEquals(ReviewStatus.AUTO_CONFIRMED, statusFor(90)); assertEquals(ReviewStatus.NEEDS_REVIEW, statusFor(89))
        assertEquals(ReviewStatus.NEEDS_REVIEW, statusFor(60)); assertEquals(ReviewStatus.UNKNOWN, statusFor(59))
    }
}
