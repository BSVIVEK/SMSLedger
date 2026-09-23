# Architecture and operational guarantees

## Modules

`core` is pure Kotlin/JVM. It owns data models, classification, issuer adapters, merchant normalization, confidence, deterministic identities, accounting and XLSX serialization. It has no Android dependencies.

`app` owns Room, SMS ContentResolver access, WorkManager, local MediaStore Downloads publishing and Compose. `LedgerApplication.Graph` wires one database, exporter, runner and coroutine mutex per app process. No service runs continuously.

## Batch protocol

1. Hold the application gate shared by import workers, export workers and UI edits. The manifest uses a single process. If you add multiprocess workers, replace this gate with a database lease.
2. Load the successful `Checkpoint`. If a pending batch exists, resume it. Otherwise capture upper SMS ID/time and create a `BatchState`.
3. Query inbox messages satisfying `(ID > successfulID OR timestamp > successfulTime)`, bounded by that batch's upper ID/time. Within the batch, resume scanning after its persisted cursor. This catches ordinary new messages whose receipt timestamp is backdated but whose ID is new.
4. Read up to 200 messages. Filter and parse entirely in memory. In one Room transaction: insert unique parsed rows, persist financial source receipts, update counters and the pending scan cursor. Non-financial and OTP messages are not persisted.
5. Continue until the snapshot is exhausted. After roughly 60 seconds of scanning, return WorkManager retry to release the gate and stay below worker execution limits. A subsequent run resumes after the committed page. A process death before page commit rolls the page back; after commit it resumes from its cursor.
6. Mark `scanComplete`. The initial/manual worker returns success to its chained Excel worker. The daily worker calls the same export/commit routine directly because periodic work cannot itself be a WorkContinuation chain.
7. Read Room under the gate, build a temporary XLSX, fsync, and atomically rename to the internal workbook. Optionally refresh the same locally owned MediaStore Downloads document.
8. Only after every enabled export succeeds, atomically advance the successful checkpoint and mark the batch complete. If export fails, retain all rows/corrections and the prior checkpoint. Retrying regenerates the workbook and commits the checkpoint without re-inserting the page.

The pending scan cursor is progress inside an unfinished batch; it is not the successful checkpoint. Parsed rows become visible during import, so the review screen may show a partial import while a batch is active.

The internal workbook replacement is atomic. A MediaStore stream overwrite cannot promise atomic visibility to other apps. The document is marked pending during its rewrite; a failed write keeps the batch uncommitted and is repaired on retry. Keep Excel closed during exports. The intact internal workbook remains recoverable even if the Downloads copy is interrupted.

## Identity and audit

- `sourceKey = SHA256(SMS ID | SMS timestamp | normalized body hash)` identifies a source message while tolerating provider ID reuse.
- `duplicateKey` uses a hashed reference + bank + instrument last four + amount + currency + type when sufficient evidence exists. Refund/reversal/failure lifecycles have separate keys.
- Without a reference, the key uses sender + receipt timestamp + normalized body hash. Same-amount/time/merchant alone is deliberately insufficient to delete a row.
- The transactions table has unique indices on both identities. A source receipt maps each financial SMS identity to the saved transaction, including duplicate redeliveries. Only hashes, SMS IDs/times and parsed fields survive.
- Retry insertion uses IGNORE; it never overwrites a correction. Ignore also retains identity.
- Third-party SMS restores that rewrite IDs and timestamps are not a supported checkpoint reset mechanism. Restored messages without stable references may need manual deduplication. No full-rescan/reset UI is exposed, to avoid accidentally undoing the historical-import boundary.

## Confidence, date and amount

The minimum of evidence scores prevents a known merchant from hiding an unknown funding source. User confirmation leaves the original automatic score intact and changes status. Unknown amount rows cannot be confirmed until corrected; they have no numeric impact.

No exchange-rate conversion is attempted. Amounts are `Long` minor units with two decimal places; `BigDecimal` is used for parsing/display/export. Supported recognition prefixes: INR, Rs, ₹, USD, EUR and GBP. Parsing overflow or ambiguity becomes a review row.

Date-only matches are stored at midnight in the current device time zone, with a review note and confidence capped at 85%. Unrecognized/invalid dates use SMS receipt time with a review note. Matching requires recognized transaction-event words from an alphanumeric sender; messages that do not pass the filter cannot appear in Needs Review.

## Extending bank parsing

Implement `BankParser`, or extend `SenderBankParser` and override `extract`. Return only narrow issuer-specific merchant/last-four hints in `BankFields`. Central parsing remains responsible for money, type, funding, privacy, category and identities. Place the adapter ahead of generic sender adapters in `TransactionParser` and add redacted synthetic fixtures.

Never persist raw SMS to debug a template. Use synthetic or carefully redacted test text. Matching a bank name is not cryptographic authentication; use review and compare with your actual statements.

## Rules and exports

Merchant keys are normalized uppercase strings with collapsed whitespace and sanitized numbers. Persisted rule keys append transaction type, preventing expense rules from overwriting refund/income classification. Instrument rules match issuer and exactly four digits. Conflicting explicit funding stays in review. Deleting a rule affects future parsing only.

XLSX files are standard ZIP packages containing workbook relationships, styles, worksheets and inline text cells. No macros, formula evaluation, Apache POI dependency, or Excel-to-Room import is involved. Generation streams XML but currently snapshots all transaction objects and groups months in memory. The 1,048,576 worksheet row limit is checked. This is suitable for a personal SMS ledger; very large multi-year datasets may need paged export and UI paging. No benchmark or device memory claim is made.

Room schema export is configured. Its JSON schema is generated by KSP on the first successful Android build and should be committed before a version-2 migration is implemented. Destructive migrations are not enabled.
