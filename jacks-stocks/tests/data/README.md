# Data-layer regression tests and contracts

No external runtime or test libraries, no live credentials, and no network calls are required by these tests.

## Executed JVM backup tests

Requirements: JDK 17 or newer. The runner uses `JAVA_HOME`, an explicit `-JavaHome` argument, or Java on `PATH`. It compiles into a unique temporary directory and cleans that directory afterward.

```powershell
& 'C:\Users\Dell\Desktop\7Oct-Desk\Oct-Data\jacks-stocks\tests\data\run-tests.ps1'
```

Source:

`C:\Users\Dell\Desktop\7Oct-Desk\Oct-Data\jacks-stocks\tests\data\BackupTest.java`

396 assertions pass in each of UTC and Pacific/Honolulu host timezones. Coverage includes exact BigDecimal/text round trips; all transaction kinds; sorted records; UTF-8/BOM; commas and escaped quotes; multiline CSV syntax followed by domain rejection of control characters; malformed records/headers/dates/IDs/decimals/Unicode; oversells; size/row/field bounds; zero-length stream reads; authenticated encryption round trips; random salt/nonce; wrong password; header/ciphertext tampering; truncated/appended ciphertext; password limits; and caller-owned stream/password semantics.

Compilation is Java 17 with `-Xlint:all -Werror`.

## Android-only offline integration suite

Source:

`C:\Users\Dell\Desktop\7Oct-Desk\Oct-Data\jacks-stocks\app\src\androidTest\java\com\jacks\stocks\data\DataIntegrationTest.java`

Five store platform tests use `android.test.InstrumentationTestCase` and the configured `android.test.InstrumentationTestRunner`. SQLite and Android JsonReader require an actual Android runtime; SDK stub jars cannot execute these tests.

- Invalid edit/delete/append/replace rolls back the entire ledger.
- Database reopen preserves precise decimals, date/ID order, and imported ID remapping.
- Cache writes are atomic; partial refresh retains stale quotes; null previous close is preserved; older fetches cannot overwrite newer ones.
- One-time Yahoo migration clears old market caches but preserves the ledger, and is idempotent.
- Atomic Yahoo window replacement, split markers and provider notes persist; invalid prices roll back.

Each store test uses a UUID-scoped isolated database/file context. It never touches the real ledger. No network requests occur. The provider-token implementation was removed in 1.0.1; migration-only cleanup deletes old credential files/keys without reading them.

Twenty additional offline Yahoo/NSE fixtures are in `C:\Users\Dell\Desktop\7Oct-Desk\Oct-Data\jacks-stocks\app\src\androidTest\java\com\jacks\stocks\data\YahooFinanceTest.java`. They cover strict metadata, exact decimal preservation, null/aligned daily arrays, prior-close semantics, ignored adjclose/range-start close, timestamp validation, today split detection, suppressed split-adjusted history, bounded input, symbol encoding, NSE CSV/BOM/quoting/headers and ISIN checks.

Build with `assembleDebugAndroidTest`; execute with `connectedDebugAndroidTest`. API 35 compilation uses SDK optional `android.test.base`, `android.test.runner`, and `android.test.mock` compile-only libraries. See `C:\Users\Dell\Desktop\7Oct-Desk\Oct-Data\jacks-stocks\TESTING.md` for the latest actual device results; compilation alone is not execution.

## Data contracts and deliberate limits

- SQLite stores decimal values as TEXT, never floating point. Transactions are ordered by date then ID. Every ledger write is tentative inside a SQLite transaction until `Portfolio.validate` accepts the whole proposed ledger. Exceptions roll it back.
- Import remaps IDs to local AUTOINCREMENT IDs, preserving input date/original-ID order. Append is not a deduplication operation: appending the same file twice can duplicate transactions. Replace is atomic.
- Backups contain ledger rows only; quotes, candles, catalogue, refresh time, settings, and credentials are not included.
- CSV header is exactly `id,date,instrument_key,symbol,name,type,quantity,price,fees`. UTF-8, optional input BOM, RFC4180 quoting and CRLF output; LF input accepted. Fields retain literal text.
- CSV is **not spreadsheet-formula escaped** because exact reversible text is preserved. Import untrusted CSV as text; never evaluate formulas. CSV is unencrypted financial information; encrypted backups are preferable.
- CSV/encrypted plaintext limit: 16 MiB, 100,000 rows, 16,384 characters per field. Decimals allow up to 64 normalized precision digits and absolute scale up to 64. Passwords require 8–1,024 characters. Caller must erase its password array after use.
- Encrypted version 1 header: ASCII `JSBK`, byte version `1`, 32-bit big-endian KDF iterations `210000`, 16 random salt bytes, 12 random nonce bytes, then AES-256-GCM ciphertext with 128-bit tag. Header is authenticated as AAD. Key derivation is PBKDF2WithHmacSHA256. A wrong password and modified ciphertext are indistinguishable failures. Never reuse a nonce/salt pair.
- Yahoo requests are explicit only, with no constructor-triggered network access or trading endpoints. Fixed HTTPS hosts, platform TLS verification, redirects disabled, 15-second connect /25-second read timeouts, bounded8MiB JSON, and requests spaced by at least750ms. The unofficial source is not guaranteed or represented as a licensed API.
- Each instrument request returns quote metadata plus daily history. Invalid/missing quote values remain unavailable; valid history can still be used. Unknown quote timestamps are never replaced with fetch time. Previous close is derived strictly before the quote's India date, not Yahoo's range-start chartPreviousClose.
- Full history windows are replaced after valid responses, to avoid mixing adjustment vintages. Today can be requested for split events, but unfinished daily candles are not stored. Pre-split history is withheld; unrecorded reported splits block valuations until reconciled.
- HTTP401/403/429 returns `YahooFinanceClient.RequestException` with statusCode and a safe message, blocks further calls on that client, and does not trigger fallback/bypass. 429 also applies a process-wide cooldown across client instances.