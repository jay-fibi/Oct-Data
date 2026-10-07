# Jacks stocks 1.0.1 validation

## Recorded results — 8 October 2026

| Check | Result |
|---|---|
| Portfolio host regression suite | **4,161 assertions / 17 groups passed**, final JDK 21 run |
| CSV/encrypted-backup host suite | **396 assertions passed in each of UTC and Pacific/Honolulu**, final persisted runner |
| Debug APK build | **Passed** |
| Unsigned release APK build | **Passed**; a personal release signing key is still required before using this unsigned artifact |
| Android test APK build | **Passed**; rebuilt APK installed and instrumentation rerun |
| Debug Android lint | **No issues found** |
| Release Android lint | **No issues found** |
| Android instrumentation | **30 tests passed** on Android 14/API 34, emulator-5554; the seed test safely returns without seeding unless explicitly opted in |
| Provider | Yahoo Finance unofficial `.NS` quotes/history; NSE public stock catalogue; no account or API key |

Build verification used Gradle 8.11.1, AGP 8.9.2, Android SDK 35 and JDK 21.0.7. The final incremental build/lint passed after review fixes. Gradle ran offline using the existing cache. Compilation emits a legacy Android-API deprecation note, not a lint error. Cold builds need network access and installed/accepted SDK components.

Build evidence: `C:\Users\Dell\Desktop\7Oct-Desk\Oct-Data\jacks-stocks\build-output.log` and `C:\Users\Dell\Desktop\7Oct-Desk\Oct-Data\jacks-stocks\build-error.log`. Final build completed successfully (116 tasks); debug and release lint both report no issues. SDK XML-version and deprecated-API warnings remain.

APK path: `C:\Users\Dell\Desktop\7Oct-Desk\Oct-Data\jacks-stocks\app\build\outputs\apk\debug\app-debug.apk`.

Verified debug metadata: version **1.0.1**, version code **2**, minimum API **26**. APK size: **171,609 bytes**. Signature verification passed using APK Signature Scheme v2. SHA-256:

```text
5A0938E43DA30CD2ACC2FCF76420C0942DE97391BB7EC46EF46693EAF27C6E33
```

At validation time the project was untracked in the parent repository. It was subsequently prepared in a separate worktree on branch `feature/jacks-stocks-1.0.1`, with the verified debug APK copied to the repository's `APK` directory as `Jacks stocks.apk`. Local log/build paths below refer to the original validation checkout; logs and generated build directories are not included in Git.

These results do not claim release-build security validation, production release signing, or exhaustive corporate-action accuracy. Instrumentation exercises Android JSON parsing, SQLite and chart rendering. The existing fictional review ledger was preserved, not reseeded or cleared.

## Missing completed-session baseline regression

The host regression verifies that an earlier-session quote with missing completed closing history cannot attribute that session's movement to today's earnings. Current portfolio value remains usable, but the unsupported period and its current earnings-chart bar are unavailable. Quotes are never fabricated into closing candles. Recovered history, invalid quotes, historical mode, zero-length periods, new holdings and fully sold holdings are covered.

## Final live and offline review

On the rebuilt debug APK, the NSE download returned **2,333 instruments** and Yahoo refresh saved **3 quotes and 193 completed closes**. These are observed results, not a future availability guarantee.

Thirteen numbered PNG/XML captures were regenerated in `C:\Users\Dell\Desktop\7Oct-Desk\Oct-Data\jacks-stocks\screenshots`. Dark/light Overview shows Today as **Unavailable** while retaining the demo valuation of **INR 86,794.00**. The daily chart and its accessibility description mark October 8 as unavailable, with one missing observation rather than zero. `current-check.png` is an older auxiliary capture, not one of the thirteen regenerated screens.

The capture helper passed assertions that both the displayed valuation and last-successful-refresh timestamp survived a normal restart, an offline restart, and a failed offline refresh. Wi-Fi/mobile-data settings were restored in `finally`. This verifies displayed cache persistence, not a byte-for-byte database comparison.

Evidence: `C:\Users\Dell\Desktop\7Oct-Desk\Oct-Data\jacks-stocks\capture-output.log`, `C:\Users\Dell\Desktop\7Oct-Desk\Oct-Data\jacks-stocks\capture-error.log`, and `C:\Users\Dell\Desktop\7Oct-Desk\Oct-Data\jacks-stocks\screenshots\capture-info.json`.

To repeat on this explicitly labelled, fictional review emulator without clearing or reseeding:

```powershell
& 'C:\Users\Dell\AppData\Local\Programs\Python\Python313\python.exe' 'C:\Users\Dell\Desktop\7Oct-Desk\Oct-Data\jacks-stocks\tests\capture-review.py' --adb 'C:\Users\Dell\AppData\Local\Android\Sdk\platform-tools\adb.exe' --serial emulator-5554 --out 'C:\Users\Dell\Desktop\7Oct-Desk\Oct-Data\jacks-stocks\screenshots' --offline-check
```

## Reproducible checks

Project root: `C:\Users\Dell\Desktop\7Oct-Desk\Oct-Data\jacks-stocks`.

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21'
$env:ANDROID_HOME = 'C:\Users\Dell\AppData\Local\Android\Sdk'
& 'C:\Users\Dell\Desktop\7Oct-Desk\Oct-Data\jacks-stocks\tests\run-tests.ps1' -JavaHome $env:JAVA_HOME
& 'C:\Users\Dell\Desktop\7Oct-Desk\Oct-Data\jacks-stocks\tests\data\run-tests.ps1' -JavaHome $env:JAVA_HOME
& 'C:\Users\Dell\Desktop\7Oct-Desk\Oct-Data\jacks-stocks\gradlew.bat' -p 'C:\Users\Dell\Desktop\7Oct-Desk\Oct-Data\jacks-stocks' assembleDebug lintDebug assembleDebugAndroidTest --console=plain
```

Core tests cover exact monetary arithmetic, buying/selling fees, partial FIFO sales, all-share disposal, dividends after sale, flows not counted as gains, missing quote/history values, baseline boundaries, openings, split/reverse-split cost conservation and conservative historical suppression, stale/unknown/future quotes, holiday carry limits, sorted replay, malformed fields, overselling, duplicate identifiers, immutability, timezone independence, and randomized conservation checks.

Android instrumentation tests require a disposable emulator/device and the platform test runner; compiling their APK alone does **not** execute platform assertions. No personal credentials are used by automated tests.

The normal run uses isolated test databases and does not seed the app ledger. There are 20 Yahoo/NSE tests, five store tests, four chart tests and one opt-in review-seed guard. Do not pass `seedReview=true` on the existing review emulator. To run after connecting a disposable emulator:

```powershell
& 'C:\Users\Dell\Desktop\7Oct-Desk\Oct-Data\jacks-stocks\gradlew.bat' -p 'C:\Users\Dell\Desktop\7Oct-Desk\Oct-Data\jacks-stocks' connectedDebugAndroidTest --console=plain
```

The backup host suite tests strict CSV roundtrip/Unicode/quoting, bounds, invalid ledger rejection, wrong password, truncated/tampered header/ciphertext, randomized encryption, and stream behavior in UTC and Pacific/Honolulu.

## Manual device acceptance checklist

1. Fresh install: name is **Jacks stocks**, icon has the white **JK** monogram and green rising-market arrow. Portfolio is empty with no mock prices. No network request on launch.
2. Add a BUY (10 shares at INR 100, INR 10 fees). Restart: transaction/cost basis of INR 1,010 survives. Edit quantity, cancel edits, delete with confirmation, and rotate an open form; unsaved fields should survive rotation.
3. Add a second buy and partial sale; compare FIFO cost/realized gain to hand calculations. An oversell or deletion of the only supporting purchase must be rejected without partial changes.
4. Add DIVIDEND with zero quantity and total received as price. Add OPENING in a different instrument and verify earlier periods are unavailable, not fabricated.
5. Explicitly download the NSE stock list and refresh Yahoo prices without credentials. Check at least three symbols against source values and timestamps and independently verify historical closes. Portfolio quantities must not appear in requests.
6. With networking disabled, last good cache remains; no zero-valued replacement. Test partial quote omissions, access errors, 429 and missing history. Status text opens full details. Live access restrictions must not be bypassed.
7. Check Today/week/month P&L after a new purchase and after a sale. New investments must not inflate profit. Weekend/holiday carried closes and stale data must be disclosed.
8. View every chart/range/grouping, all-null and single-point series, positive and negative P&L, zero holdings after liquidation, allocation weights, touch inspection and TalkBack summaries. Missing series points must remain gaps. Price transaction markers are a dated list below the stock chart.
9. Record SPLIT multiplier 2 and reverse split 0.2; cost stays unchanged, quantity updates, and uncertain pre-split historical charts/periods are suppressed.
10. CSV roundtrip with Unicode/comma/quote-containing names. Invalid import rolls back. Importing twice is explicitly warned as non-deduplicated. Export has no token.
11. Encrypted ledger backup/restore: correct password, wrong password, truncated/tampered file, cancelled picker, and a different device. Failed restore must preserve the current ledger. Passwords and token are excluded from instance state and backup.
12. System/light/dark themes, Android 8+ and Android 15+, landscape, large fonts, TalkBack, keyboard insets, narrow phones, and app switch/resume. Verify screenshot/recent-app protection, no background quote refresh, and no trading permissions/endpoints.

## Scope limitations

This is an informational personal tracker, not a tax engine or brokerage reconciliation system. Yahoo split events are checked conservatively, but event coverage and history adjustment conventions are not guaranteed. Quantities never change automatically. Yahoo's unofficial endpoint may be delayed or unavailable. Multi-version Android accessibility testing, full document-picker roundtrips, release screenshot protection and a signed upgrade from 1.0 remain acceptance work.