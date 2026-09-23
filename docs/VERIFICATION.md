# Verification report

Prepared 17 September 2026.

## Executed in the generation environment

Command: `python3 scripts/check_source.py`

- Five Android XML files parse successfully.
- The source application manifest requests only READ_SMS, disables backup, and declares no custom service.
- All static regex literals extracted from the core Kotlin code compile in the JDK regular-expression engine. Interpolated patterns are excluded from this check.
- Fifteen Java assertions exercise the extracted amount/account/filter/sender/merchant regexes: Indian grouped amounts, integer minor units, masked and full-account suffix extraction, OTP/code/collect rejection, accepted purchase text, sender suffixes, personal-number rejection and preserving a merchant after account clauses.
- `scripts/GradleBootstrap.java` compiles using JDK 17.
- `gradlew` passes shell syntax validation.

These are structural and targeted regex checks. They do **not** establish Kotlin compilation, Compose correctness, Room code generation, XLSX runtime output, WorkManager behavior or handset functionality.

## Authored but not executed here

**25 Kotlin/JUnit unit tests** in `core/src/test`:

- Credit/debit/UPI/RuPay classification and explicit card mappings.
- Merchant rules, safe Exact Entry learning and refund rule isolation.
- Salary/dividend/interest/cashback/redemption categories.
- OTP/ads/reminders/personal/collect rejection.
- Failure/reversal/card-bill/NEFT/ATM handling.
- Every requested built-in merchant mapping.
- Indian amounts, overflow and multi-amount ambiguity.
- Same-SMS idempotence, reference redeliveries, distinct repeat purchases and reused IDs.
- Account redaction, invalid dates and confidence boundaries.
- HDFC/ICICI/SBI patterns, merchant-clause extraction and unsettled refunds.
- OOXML ZIP/XML contents, required sheets, numeric amounts and formula-safe text.
- Accounting exclusions and UPI/card overlap.

**4 Room instrumentation tests** in `app/src/androidTest`:

- Export failure does not advance checkpoint; retry preserves corrections.
- Unique receipts and references survive replay.
- Page continuation and bounded snapshots handle late arrivals.
- Backdated arrivals are found by ID, and OTP rows are not stored.

## Why full verification is pending

The environment contained a Java runtime/compiler but no Android SDK, Gradle distribution or Kotlin compiler. Maven/Gradle dependency network probes failed. Therefore no APK was produced and no Kotlin, Room, Compose, Android lint, emulator or physical-device test result is claimed.

The archive includes the source, pinned build configuration, a checksum-verifying Gradle bootstrap, and all test code. In a normal development environment run:

```sh
./gradlew :core:test :app:assembleDebug :app:lintDebug
./gradlew :app:connectedDebugAndroidTest
```

After building, perform the device checks below before relying on it for financial tracking.

## Galaxy S24 acceptance checks

1. Deny SMS permission; verify the explanation and absence of an import. Grant it and import history.
2. Verify representative real, redacted bank formats against statements. Unsupported financial candidates should need review; unrelated messages must not appear.
3. Run twice; transaction count and corrections should remain stable.
4. Correct an unknown merchant to Groceries with Exact Entry `Monthly Provisions`. Later matching expense SMS should learn the category but leave Exact Entry blank unless its separate learning checkbox was enabled.
5. Map an HDFC RuPay card by last four; verify UPI as payment mode and Credit Card as funding. Inspect explicit-source conflicts.
6. Export, open in Excel, and inspect every sheet and numeric totals. Correct a transaction and verify the same workbook updates.
7. Confirm that missing amounts cannot be confirmed, while Ignore removes a row from review/totals without re-importing it.
8. Revoke SMS access; run a batch and confirm the error, then restore permission and retry.
9. Observe daily scheduling through Android Studio Background Task Inspector with the phone unplugged. Verify recovery after a normal reboot. Do not expect an exact clock-time run.
10. Check the merged manifest for unwanted permissions. Use an offline device to verify the full import/review/export workflow.
