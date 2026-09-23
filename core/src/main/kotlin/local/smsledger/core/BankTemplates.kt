package local.smsledger.core

/** Issuer-specific hints are deliberately narrow; unrecognized formats use generic parsing. */
class HdfcParser : SenderBankParser("HDFC", "HDFCBK|HDFC") {
    override fun extract(body: String): BankFields {
        val merchant = Regex("(?i)\\btowards\\s+(.+?)(?=\\s+(?:on|via|ref)\\b|[.;]|$)").find(body)?.groupValues?.get(1)
        return BankFields(merchant = merchant)
    }
}
class IciciParser : SenderBankParser("ICICI", "ICICIB|ICICI") {
    override fun extract(body: String): BankFields {
        // ICICI debit alerts often contain Info: UPI/MERCHANT/reference.
        val merchant = Regex("(?i)\\bInfo\\s*:\\s*(?:UPI/)?([A-Z][A-Z .&_-]+)(?=/|[.;]|$)").find(body)?.groupValues?.get(1)
        return BankFields(merchant = merchant)
    }
}
class SbiParser : SenderBankParser("SBI", "SBIINB|SBIPSG|SBI") {
    override fun extract(body: String): BankFields {
        val merchant = Regex("(?i)\\btrf to\\s+(.+?)(?=\\s+(?:on|ref|UPI)\\b|[.;]|$)").find(body)?.groupValues?.get(1)
        return BankFields(merchant = merchant)
    }
}
