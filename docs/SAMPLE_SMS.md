# Synthetic SMS examples

These are synthetic templates, not a guarantee that every message from an issuer uses these formats. Unit tests contain the executable expectations. Use alphanumeric bank senders as shown; personal-number messages are excluded.

| Sender | Synthetic body | Expected result |
|---|---|---|
| AD-HDFCBK | INR 1299.00 spent on HDFC credit card XX1234 at AMAZON on 17-09-2026 14:30. Avl Bal INR 50000.00 | 1299.00, Expense, Credit Card, Shopping |
| AD-HDFCBK | INR 250 paid using Tata Neu HDFC RuPay credit card XX1234 to SWIGGY via UPI on 17/09/2026 12:30. Ref 123456789012 | UPI + Credit Card, Food & Dining |
| AD-HDFCBK | INR 100 paid using HDFC card XX1234 to AMAZON via UPI on 17/09/2026 12:30 | Unknown funding unless an instrument rule resolves it; review |
| AD-HDFCBK | A/c XX2345 debited INR 120 to MURUGAN STORES via UPI on 17-09-2026 | UPI + Bank Account, Unknown category until taught |
| AD-ICICIB | ICICI account XX1234 debited INR 100 on 17-09-2026. Info: UPI/SWIGGY/123456789012 | ICICI, SWIGGY, Food & Dining |
| AD-SBIINB | SBI account XX1234 debited INR 100 trf to IRCTC on 17-09-2026 | SBI, IRCTC, Travel |
| AD-HDFCBK | INR 100 credited to account XX1234 from COMPANY on 17-09-2026 for salary | Income, Salary |
| AD-HDFCBK | Refund INR 500 credited to HDFC account XX1234 from AMAZON on 17-09-2026 | Refund |
| AD-HDFCBK | INR 500 transaction failed on HDFC credit card XX1234 at AMAZON | Failed transaction, excluded from expense totals |
| AD-HDFCBK | INR 500 reversed to HDFC account XX1234 from AMAZON | Reversal, review, no automatic cancellation of the original |
| AD-HDFCBK | INR 500 withdrawn from HDFC account XX1234 at ATM on 17-09-2026 | ATM Withdrawal, excluded from expense totals |
| AD-HDFCBK | OTP 987654 for INR 500 transaction on credit card XX1234. Do not share. | Discarded, no persisted row/hash |
| AD-HDFCBK | Reminder: credit card payment due INR 900 | Discarded |
| AD-HDFCBK | UPI payment request received for INR 120 | Discarded |
