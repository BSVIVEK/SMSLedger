# SMS Ledger — local Android batch utility

Kotlin / Jetpack Compose / Room / WorkManager / XLSX. Designed for a Samsung Galaxy S24, with Android 10+ support. This is an Android Studio **source project**, not a prebuilt APK.

The phone processes financial SMS locally. It has no Internet permission, cloud service, analytics, advertisements, Accessibility Service or SMS listener. The small UI grants permission, reviews results and edits rules; WorkManager performs the batch work.

## Build and install

1. Install Android Studio with Android SDK **35**, build tools **35.0.0**, and **JDK 17**. The project pins AGP 8.9.2, Gradle 8.11.1, Kotlin 2.0.21, Compose BOM 2025.02.00, Room 2.7.0 and WorkManager 2.10.0.
2. Extract this archive. Open a terminal in `SmsLedger` and run `./gradlew --version` (Windows: `gradlew.bat --version`) once **before importing into Android Studio**. If necessary, set `JAVA_HOME` to JDK 17 or the Android Studio bundled JDK.
3. The first invocation downloads the **official Gradle wrapper JAR** using the included Java bootstrap, validates its pinned SHA-256, then downloads the checksum-pinned Gradle distribution. No wrapper binary is bundled because dependency downloads were unavailable in the generation environment. Internet is required on the development computer for build tools and dependencies, never for the installed utility.
4. Open the `SmsLedger` directory in Android Studio. Let Gradle sync. Android Studio will create `local.properties` with your SDK path.
5. On the S24, enable Developer options by tapping **Build number** seven times under **Settings → About phone → Software information**, then enable USB debugging. Connect the phone, approve your computer, choose it in Android Studio and click **Run**.
6. Open **SMS Ledger → Allow SMS & Import History**, then allow SMS access. Keep the first import open until progress appears. You do not need to keep the app open afterward.

Command-line build and tests:

```sh
./gradlew :core:test :app:assembleDebug :app:lintDebug
./gradlew :app:connectedDebugAndroidTest
```

On Windows, use `gradlew.bat`. The instrumentation command requires a connected device/emulator. Android Studio can run the same tests. The debug APK, after a successful build, is `app/build/outputs/apk/debug/app-debug.apk`.

**Verification status:** this project has source, XML, Java bootstrap and Java regex checks, plus authored Kotlin unit/instrumentation tests. The Kotlin/Android build and those test suites were **not run in the generation environment** because it had no Android SDK/Kotlin compiler and could not download dependencies. See `docs/VERIFICATION.md`. Build and run the supplied tests before relying on its totals.

## Galaxy S24 setup

- Keep **Settings → Daily batch** enabled. A unique 24-hour periodic request is scheduled; execution time is approximate, not an exact daily alarm.
- Under the phone's **Settings → Apps → SMS Ledger → Battery**, permit unrestricted background use if that option is available. In **Battery → Background usage limits**, remove SMS Ledger from **Sleeping apps** and **Deep sleeping apps**; add it to **Never auto sleeping apps** where available. One UI labels can differ by version.
- Do not force-stop the app. If you do, reopen it and tap **Run Batch Now**. Reboot scheduling is handled by WorkManager.
- Under app permissions, disable automatic permission removal for this utility if your One UI version offers it and you want unattended use. If SMS permission is revoked, grant it again and run a batch.
- SMS access is restricted by Android/installer policy. For personal use, install through Android Studio/ADB. An installer or managed phone may still refuse READ_SMS. This project does not bypass those restrictions or require becoming the default SMS app.
- Google Play distribution requires compliance with its restricted SMS permission policy and approval where applicable; sideload development instructions are not Play approval.

## First use and daily operation

1. Grant SMS access: historical **inbox SMS** is scanned; sent messages and RCS messages are outside this utility's scope.
2. Open **Needs Review**. Verify amount, date, merchant, funding source and category. Use **Confirm**, **Edit**, or **Ignore**. A missing/ambiguous amount must be edited before confirmation.
3. Optional: **Settings → Add instrument**, choose HDFC, enter only the last four digits, set `Tata Neu HDFC RuPay`, and choose **Credit Card**. A UPI message with a matching issuer/last-four can then use `Payment Mode = UPI` and `Funding Source = Credit Card` if the SMS leaves funding unspecified. Conflicting explicit SMS evidence always goes to review.
4. **Export Excel** enables an automatically updated local workbook at **My Files → Internal storage → Download → SMS Ledger → SMS-Ledger.xlsx**. Open it with Excel or another XLSX reader. Subsequent successful batches and corrections refresh it.
5. **Run Batch Now** queues a unique import/export chain. Repeated taps while it is queued/running do not create duplicate chains. Daily work runs independently and shares the same transaction gate.

The internal workbook is generated after every completed batch even before you enable the Downloads copy. Disable **Update local Downloads workbook** to stop updating that copy; this does not delete existing exported files. There is no cloud file picker or cloud upload. Opening or moving the export in another app may invoke that app's sync settings.

## What is included

| Requirement | Implementation |
|---|---|
| Historical/incremental SMS | `AndroidSmsInbox`, permission launcher, ID/time bounds |
| Filter and extraction | `FinancialFilter`, `TransactionParser`, issuer adapters |
| Bank-specific templates | HDFC `towards`, ICICI `Info:`, SBI `trf to`, generic fallback |
| Instrument classification | Separate mode/funding fields, issuer + last-four mappings |
| Category engine | Every requested category, built-in merchant mappings, local corrections |
| Exact Entry | Optional per transaction; reusable only with explicit Exact Entry rule |
| Review | Confidence bands, Confirm/Edit/Ignore, searchable transaction list |
| Local storage | Room, unique transaction keys and source receipts |
| Recovery | Page transactions, pending cursor, export-before-successful-checkpoint |
| Workers | InitialSmsImportWorker, DailySmsTransactionWorker, ExcelExportWorker |
| Excel | OOXML XLSX generation using Java ZIP/XML, no heavy Excel dependency |
| Screens | Dashboard, Transactions, Needs Review, Settings, batch and export actions |
| Tests | Parser, privacy, duplicates, accounting, OOXML, Room retry/incremental tests |

## Classification and corrections

The parser is a deterministic local rules engine, not a universal bank parser or an AI service. It supports representative English-language financial alerts and marks accepted but unparseable transaction messages for review. Unknown sender patterns, OTP, reminders, offers, personal-number messages and unrelated messages are discarded without persisting their content.

- Amounts use integer minor units with two decimal places for INR/USD/EUR/GBP. No floating-point money. Missing or multiple candidate transaction amounts become `Amount unknown`; they are excluded from totals pending correction.
- Dates support common numeric and English month formats after `on`. If no valid transaction date is recognized, receipt time is used with a note and confidence below 90%. Date-only alerts use midnight, record a note, and remain below 90% confidence. Currency, amount and transaction time can be corrected.
- The overall confidence is the **minimum** of sender, amount, merchant, funding and category evidence; missing dates/times cap it at 85%. It is a rules-based score, not a statistically calibrated probability. ≥90% Auto Confirmed; 60–89% Needs Review; <60% Unknown / Needs Review.
- UPI does not imply debit card or credit card. The source stays Unknown unless stated or resolved by an instrument rule. Merely seeing `RuPay` does not prove a credit card.
- A correction saves an exact normalized-merchant rule scoped by transaction type when **Learn this merchant/category** is checked. It affects future parsed messages, not already saved rows. Review unknown historical entries individually.
- Exact Entry is not copied into a rule unless **Also create an Exact Entry rule** is selected. Rules can be removed in Settings.
- Refund and reversal are distinct. A reversal notice does not automatically cancel a previously parsed expense because the matching evidence can be ambiguous. Ignore/correct the original expense after verifying it.
- `Ignore` preserves the audit identity, excludes the transaction from review/totals, and prevents a retry from re-importing it. You can edit/confirm it later in Transactions.
- Payment instrument names are aliases you supply. The utility does not infer the Tata Neu product name merely from four digits.

## Workbook and totals

The workbook is rebuilt from Room. **Make corrections in the utility; direct Excel edits are overwritten.** It contains the requested 15 columns plus Instrument, Confidence, SMS ID and SMS Timestamp for traceability.

Sheets: **Transactions**, **Needs Review**, monthly sheets such as **Sep-2026**, **Summary**, **Categories**, **Payment Sources**. Monthly sheets use transaction date and the phone's current time zone. Headers are frozen, filters are enabled, and amounts are numeric. User-supplied text is encoded as an XLSX string, never a formula.

- Dashboard: current calendar month, INR, confirmed rows only.
- Workbook: all currencies are kept separate; monthly, category, merchant and bank/card totals.
- Expense = confirmed `Expense` excluding ATM cash withdrawals. Income = confirmed `Income`.
- Refunds are shown separately. **Net Savings = Income − Expense + Refunds** for that month's recorded entries.
- Transfers, card bill payments, ATM withdrawals, failures, reversals, ignored rows and unconfirmed rows do not count as income/expense. All Transfers, Bank Transfers and Cash Withdrawals appear separately.
- Credit/debit/bank totals classify funding. UPI classifies payment mode, so a RuPay credit-card UPI expense belongs to **both** UPI and credit-card totals. Do not add these overlapping views.
- This is an SMS-based ledger, not a complete bank reconciliation. Missing SMS, cash purchases and unsupported alerts cannot be discovered. `Cash` is an editable mode/source but there is no invented cash transaction feed.

## Privacy and storage

- No Internet permission, network libraries, backend, analytics, or advertising SDKs.
- Runtime permissions: READ_SMS only; WorkManager may merge standard scheduling permissions. No permission to send SMS or read contacts.
- Raw SMS bodies and OTPs are never persisted or logged. Only parsed financial fields and message/reference hashes are kept. Last-four digits are the only stored card/account identifiers; free text is sanitized for long digit sequences.
- Room and the internal XLSX live in Android app-private storage, protected by Android's sandbox and device storage encryption. This is **not** an additional SQLCipher-encrypted database.
- Cloud backup and device-transfer backup are excluded in the manifest/rules. A Downloads copy is user-visible financial data; another app you authorize can read it. Uninstalling removes app-private data but may leave Downloads files.
- The UI never asks for a PIN, OTP, full card number, or full account number.

## Recovery and duplicate policy

See `docs/ARCHITECTURE.md` for the commit protocol, identities, failure cases and extension points.

Re-running the same SMS batch is idempotent. Shared transaction references also identify redeliveries when issuer, last four, amount, currency and type agree. Without a stable bank reference, identical message/sender/receipt-time hashes are deduplicated. Same-amount purchases at the same merchant are **not** merged heuristically: they can be genuine transactions. Different SMS notifications about the same economic event without a common stable reference can still require manual Ignore; no parser can guarantee cross-bank economic deduplication from absent evidence.

## Sources for platform choices

- [Android Gradle Plugin 8.9 compatibility](https://developer.android.com/build/releases/agp-8-9-0-release-notes): SDK 35, Gradle 8.11.1, JDK 17.
- [WorkManager periodic requests and retries](https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work): scheduling is inexact and subject to system optimizations.
- [Android storage use cases](https://developer.android.com/training/data-storage/use-cases): local shared-file exports.
- [Google Play SMS permission policy](https://support.google.com/googleplay/android-developer/answer/10208820).
- [Official Gradle checksums](https://gradle.org/release-checksums/): pinned distribution and wrapper hashes.

Room 2.7 targets Kotlin 2.0; see the [Room release notes](https://developer.android.com/jetpack/androidx/releases/room). KSP2 is enabled.
