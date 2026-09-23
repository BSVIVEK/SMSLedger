package local.smsledger.core

object Categories {
    val expenses = listOf("Food & Dining", "Groceries", "Fuel", "Travel", "Shopping", "Electronics",
        "Household", "Bills & Utilities", "Rent", "Medical", "Education", "Entertainment", "EMI / Loan",
        "Insurance", "Investment", "Transfer", "ATM Withdrawal", "Fees & Charges", "Personal Care",
        "Subscriptions", "Gifts", "Other", "Unknown")
    val incomes = listOf("Salary", "Refund", "Interest", "Dividend", "Cashback", "Investment Redemption", "Transfer Received", "Other Income")
    val all = (expenses + incomes).distinct()
    private val merchantMappings = listOf(
        "SWIGGY|ZOMATO" to "Food & Dining", "IOCL|BPCL|HPCL|INDIAN OIL|BHARAT PETROLEUM|HINDUSTAN PETROLEUM" to "Fuel",
        "AMAZON|FLIPKART" to "Shopping", "UBER|OLA|IRCTC" to "Travel",
        "APOLLO|PHARMACY" to "Medical", "ZERODHA|GROWW" to "Investment")
    fun assign(merchant: String, body: String, type: TransactionType, mode: PaymentMode): Pair<String, Int> {
        val b = body.lowercase()
        if (type == TransactionType.REFUND) return "Refund" to 96
        if (type == TransactionType.REVERSAL || type == TransactionType.FAILED) return "Unknown" to 55
        if (mode == PaymentMode.ATM) return "ATM Withdrawal" to 97
        if (type == TransactionType.INCOME) return when {
            "salary" in b || "payroll" in b -> "Salary" to 96
            "dividend" in b -> "Dividend" to 96
            "interest" in b -> "Interest" to 92
            "cashback" in b -> "Cashback" to 96
            "redemption" in b || "redeemed" in b -> "Investment Redemption" to 94
            else -> "Other Income" to 65
        }
        if (type == TransactionType.TRANSFER) return "Transfer" to 75
        for ((pattern, category) in merchantMappings) {
            if (Regex("\\b(?:$pattern)\\b", RegexOption.IGNORE_CASE).containsMatchIn(merchant)) return category to 94
        }
        return "Unknown" to 45
    }
}
