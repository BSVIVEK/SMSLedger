package local.smsledger.core

import java.math.BigDecimal
import java.time.*
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import java.util.Locale

/** Templates extend this interface; generic extraction is shared and deliberately conservative. */
data class BankFields(val merchant: String? = null, val last4: String? = null)
interface BankParser {
    val bank: String
    fun matches(sender: String, body: String): Boolean
    fun extract(body: String): BankFields = BankFields()
}
open class SenderBankParser(override val bank: String, private val pattern: String) : BankParser {
    override fun matches(sender: String, body: String) = Regex(pattern, RegexOption.IGNORE_CASE).containsMatchIn(sender)
        || Regex("\\b${Regex.escape(bank)}\\b", RegexOption.IGNORE_CASE).containsMatchIn(body)
}
object FinancialFilter {
    private val ignore = Regex("\\b(otp|one[ -]?time (?:password|code|pin)|verification code|authentication code|passcode|secure code|offer|offers|cashback offer|reminder|due date|payment due|minimum due|statement generated|pre[ -]?approved|apply now|win cash|collect request|payment request)\\b", RegexOption.IGNORE_CASE)
    private val event = Regex("\\b(debited|credited|spent|paid|purchased|withdrawn|withdrawal|transferred|received|refund|refunded|reversed|reversal|declined|failed|unsuccessful|charged|deducted)\\b", RegexOption.IGNORE_CASE)
    private val context = Regex("\\b(bank|card|a/c|account|acct|upi|neft|imps|rtgs|wallet|atm|txn|transaction)\\b", RegexOption.IGNORE_CASE)
    private val sender = Regex("^(?:[A-Z0-9]{2}-)?[A-Z][A-Z0-9]{2,}(?:-[A-Z])?$", RegexOption.IGNORE_CASE)
    fun accepts(sms: Sms, knownBank: Boolean): Boolean {
        if (ignore.containsMatchIn(sms.body)) return false
        if (Regex("\\b(will be|to be|scheduled|please pay|kindly pay)\\b", RegexOption.IGNORE_CASE).containsMatchIn(sms.body)) return false
        // Personal numbers are never interpreted as financial senders.
        return sender.matches(sms.sender) && event.containsMatchIn(sms.body) && (knownBank || context.containsMatchIn(sms.body))
    }
}
class TransactionParser(private val zone: ZoneId = ZoneId.systemDefault(),
    private val banks: List<BankParser> = listOf(
        HdfcParser(), IciciParser(), SbiParser(), SenderBankParser("Axis", "AXISBK|AXIS"),
        SenderBankParser("Kotak", "KOTAK"), SenderBankParser("IDFC", "IDFC"),
        SenderBankParser("Indian Bank", "INDBNK"), SenderBankParser("Canara", "CANBNK"),
        SenderBankParser("PNB", "PNBSMS"))) {
    private val money = Regex("(?i)(INR|Rs\\.?|₹|USD|EUR|GBP)\\s*([0-9]+(?:,[0-9]{2,3})*(?:\\.[0-9]{1,2})?)(?![0-9.])")
    private val account = Regex("(?i)(?:credit\\s*card|debit\\s*card|card|a/c|acct|account)(?:\\s*(?:no\\.?|number|ending(?:\\s+in)?|ending with))?\\s*[:#.-]?\\s*([Xx*•\\d-]{4,})")
    fun parse(sms: Sms, rules: List<MerchantRule> = emptyList(), instruments: List<InstrumentRule> = emptyList()): Transaction? {
        val bankParser = banks.firstOrNull { it.matches(sms.sender, sms.body) }
        if (!FinancialFilter.accepts(sms, bankParser != null)) return null
        val bank = bankParser?.bank ?: "Unknown"
        val body = sms.body
        val bankFields = bankParser?.extract(body) ?: BankFields()
        val b = body.lowercase(Locale.ROOT)
        val amounts = money.findAll(body).filterNot { m ->
            Regex("(?i)(?:avl|available|avail|balance|bal|limit)\\s*[:.=-]?\\s*$").containsMatchIn(body.take(m.range.first).takeLast(35))
        }.toList()
        // Multiple transaction amounts cannot safely be treated as a single expense.
        val amount = amounts.singleOrNull()?.groupValues?.get(2)?.replace(",", "")?.let {
            runCatching { BigDecimal(it).movePointRight(2).longValueExact() }.getOrNull()
        }
        val currency = amounts.firstOrNull()?.groupValues?.get(1)?.uppercase(Locale.ROOT)?.let {
            if (it in listOf("RS", "RS.", "₹")) "INR" else it
        } ?: "INR"
        val last4 = (bankFields.last4 ?: account.find(body)?.groupValues?.get(1)).orEmpty().filter(Char::isDigit).takeLast(4).takeIf { it.length == 4 }.orEmpty()
        val mode = when {
            Regex("\\bupi\\b").containsMatchIn(b) -> PaymentMode.UPI
            Regex("\\batm\\b|cash withdrawal|cash withdrawn").containsMatchIn(b) -> PaymentMode.ATM
            "credit card" in b || "creditcard" in b -> PaymentMode.CREDIT_CARD
            "debit card" in b || "debitcard" in b -> PaymentMode.DEBIT_CARD
            Regex("\\b(neft|imps|rtgs)\\b").containsMatchIn(b) -> PaymentMode.BANK_TRANSFER
            "wallet" in b -> PaymentMode.WALLET
            else -> PaymentMode.OTHER
        }
        val explicitFunding = when {
            "credit card" in b || "creditcard" in b -> FundingSource.CREDIT_CARD
            "debit card" in b || "debitcard" in b -> FundingSource.DEBIT_CARD
            "wallet" in b -> FundingSource.WALLET
            Regex("\\b(a/c|account|acct)\\b").containsMatchIn(b) || mode == PaymentMode.ATM -> FundingSource.BANK_ACCOUNT
            else -> FundingSource.UNKNOWN
        }
        val instrumentRule = instruments.singleOrNull { it.bank.equals(bank, true) && it.last4 == last4 && last4.isNotEmpty() }
        val funding = if (explicitFunding == FundingSource.UNKNOWN) instrumentRule?.funding ?: explicitFunding else explicitFunding
        val conflict = instrumentRule != null && explicitFunding != FundingSource.UNKNOWN && instrumentRule.funding != explicitFunding
        val instrument = instrumentRule?.name ?: when {
            "tata neu" in b && "rupay" in b -> "Tata Neu $bank RuPay"
            "rupay" in b -> "$bank RuPay"
            funding != FundingSource.UNKNOWN -> "$bank ${funding.label}"
            else -> bank
        }
        val type = when {
            Regex("\\b(failed|declined|unsuccessful)\\b").containsMatchIn(b) -> TransactionType.FAILED
            Regex("\\b(reversed|reversal)\\b").containsMatchIn(b) -> TransactionType.REVERSAL
            Regex("\\b(refund|refunded)\\b").containsMatchIn(b) -> TransactionType.REFUND
            Regex("credit card (?:bill )?payment|payment (?:received|credited).*credit card|own account|self transfer").containsMatchIn(b) -> TransactionType.TRANSFER
            Regex("\\b(credited|received)\\b").containsMatchIn(b) -> TransactionType.INCOME
            Regex("\\b(neft|imps|rtgs|transferred)\\b").containsMatchIn(b) -> TransactionType.TRANSFER
            else -> TransactionType.EXPENSE
        }
        val rawMerchant = bankFields.merchant?.let(::merchantKey) ?: extractMerchant(body)
        val originalKey = merchantKey(rawMerchant)
        val rule = rules.firstOrNull { it.key == "$originalKey|${type.name}" }
        val merchant = rule?.merchant ?: rawMerchant
        val (autoCategory, categoryConfidence) = Categories.assign(merchant, body, type, mode)
        val category = rule?.category ?: autoCategory
        val parsedDate = parseDate(body)
        var confidence = minOf(if (bankParser != null) 97 else 65,
            if (amount != null) 99 else 25,
            if (funding != FundingSource.UNKNOWN) 96 else 55,
            if (rawMerchant != "Unknown") 96 else 70,
            if (rule != null) 98 else categoryConfidence)
        if (conflict) confidence = minOf(confidence, 40)
        if (parsedDate?.hasTime != true) confidence = minOf(confidence, 85)
        val timestamp = parsedDate?.timestamp ?: sms.timestamp
        val hash = sha256(body.replace(Regex("\\s+"), " ").trim())
        val sourceKey = sha256("${sms.id}|${sms.timestamp}|$hash")
        // Reference-based identity: amount/merchant/time alone can merge genuine repeat purchases.
        val ref = Regex("(?i)(?:UPI\\s*(?:ref(?:erence)?|txn(?:id)?)|UTR|RRN|ref(?:erence)?(?:\\s*(?:no|id))?)\\s*[:.#-]?\\s*([A-Z0-9]{6,})")
            .find(body)?.groupValues?.get(1)
        val duplicateKey = if (ref != null && amount != null && last4.isNotEmpty() && bank != "Unknown")
            sha256("ref|$bank|$last4|$ref|$amount|$currency|$type")
        else sha256("message|${sms.sender}|${sms.timestamp}|$hash")
        val pending = Regex("(?i)\\b(pending|initiated|processing|requested)\\b").containsMatchIn(body)
        if (pending) confidence = minOf(confidence, 40)
        val notes = buildList {
            if (pending) add("Transaction may not be settled; verify before confirming.")
            if (parsedDate == null) add("Date/time uses SMS receipt time; verify transaction date.")
            else if (!parsedDate.hasTime) add("SMS date has no valid time; stored at 00:00. Verify if needed.")
            if (amount == null) add("Amount missing or ambiguous; inspect original SMS in Messages.")
            if (funding == FundingSource.UNKNOWN) add("Funding source not stated. Add an instrument mapping or correct manually.")
            if (conflict) add("Instrument mapping conflicts with SMS funding source.")
            if (type == TransactionType.TRANSFER) add("Review transfer purpose; own transfers and card bill payments are excluded from spending.")
        }.joinToString(" ")
        return Transaction(sms.id, sms.timestamp, hash, sourceKey, duplicateKey, timestamp, bank,
            Privacy.safe(merchant), originalKey, amount, currency, type, mode, funding, last4,
            Privacy.safe(instrument), category, rule?.subcategory.orEmpty(), rule?.exactEntry.orEmpty(),
            confidence, statusFor(confidence), notes)
    }
    private fun extractMerchant(body: String): String {
        val pattern = Regex("(?i)\\b(?:at|to|from|towards)\\s+(.+?)(?=\\s+(?:on|via|using|through|ref|UPI|txn|avl|avail|balance|bal|a/c|account|at|to|from|towards)\\b|[.;]|$)")
        return pattern.findAll(body).map { it.groupValues[1].trim() }
            .firstOrNull { candidate ->
                !Regex("(?i)^(?:INR|Rs|₹|your|a/c|account|card|[0-9])").containsMatchIn(candidate) &&
                    !Regex("(?i)(credit|debit)\\s*card").containsMatchIn(candidate) &&
                    banks.none { it.bank.equals(candidate, true) || "${it.bank} Bank".equals(candidate, true) }
            }
            ?.let { merchantKey(it).ifBlank { "Unknown" } } ?: "Unknown"
    }
    private data class ParsedDate(val timestamp: Long, val hasTime: Boolean)
    private fun parseDate(body: String): ParsedDate? {
        val match = Regex("(?i)\\bon\\s+(\\d{4}-\\d{2}-\\d{2}|\\d{1,2}[-/]\\d{1,2}[-/]\\d{2,4}|\\d{1,2}[- ](?:Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)[- ]\\d{2,4})(?:[ T]+(?:at\\s+)?(\\d{1,2}:\\d{2}(?::\\d{2})?))?").find(body) ?: return null
        val dateText = match.groupValues[1]
        val date = listOf("uuuu-MM-dd", "d/M/uuuu", "d-M-uuuu", "d/M/uu", "d-M-uu", "d-MMM-uuuu", "d-MMM-uu", "d MMM uuuu").firstNotNullOfOrNull { format ->
            runCatching { LocalDate.parse(dateText, java.time.format.DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern(format).toFormatter(Locale.ENGLISH).withResolverStyle(ResolverStyle.STRICT)) }.getOrNull()
        } ?: return null
        val time = match.groupValues[2].takeIf { it.isNotBlank() }?.let {
            runCatching { LocalTime.parse(it, DateTimeFormatter.ofPattern(if (it.count { c -> c == ':' } == 2) "H:mm:ss" else "H:mm")) }.getOrNull()
        }
        return ParsedDate(date.atTime(time ?: LocalTime.MIDNIGHT).atZone(zone).toInstant().toEpochMilli(), time != null)
    }
}
