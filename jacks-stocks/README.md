# Jacks stocks 1.0.1 — Yahoo Finance

A personal, local-first Android portfolio tracker for NSE equities, with a **JK monogram and rising-market logo**. Prices are fetched **only when you press Refresh prices**. There is no trading, streaming, background service, advertising, analytics SDK, or third-party runtime dependency.

## What is included

- Overview: holdings value, remaining cost basis, realized/unrealized earnings, dividends, and absolute INR day/week/month P&L.
- Dated BUY, SELL, DIVIDEND, SPLIT (also bonus/reverse split), and OPENING entries; edit/delete with whole-ledger validation.
- FIFO lot accounting with decimal arithmetic; buying fees included in cost, selling fees deducted from proceeds.
- Local stock search after an explicit **Download NSE stock list** action; existing holdings remain searchable offline. Manual NSE instrument keys are also supported.
- On-demand Yahoo Finance NSE quotes and daily historical prices, no login/API key, request pacing, terminal access/rate-limit handling, and partial-result warnings.
- Portfolio-value and cumulative-earnings lines, daily/weekly/monthly P&L bars, stock-allocation donut, unrealized P&L by stock, and individual stock price history. Transaction markers are presented as a dated list below stock charts.
- System/light/dark appearance; screen-reader summaries for charts; missing-value gaps, signed P&L, and timestamps.
- App-private SQLite persistence, CSV import/export, and password-encrypted ledger backup/restore. No provider credential is needed or stored.
- A separate app/package: `com.jacks.stocks`. The existing calculator is not changed.

## First use

1. Install the debug APK for personal testing, or build/sign your own release.
2. Open **Settings → Download NSE stock list**. This explicitly downloads NSE's public cash-equity catalogue; it does not fetch prices or require an account.
3. Choose **Add first transaction**. Search for a stock, select the result, and record a BUY. For existing holdings without a full trade history, choose OPENING, enter your present quantity and average cost, and set the tracking start date. Never label an opening balance as an invented historical purchase.
4. Tap **Refresh prices**. The app requests Yahoo prices/history for symbols such as `RELIANCE.NS`, `INFY.NS`, and `TCS.NS`. No login or API key. No price request occurs on app launch, navigation, transaction edits, or chart-range changes.
5. Record subsequent buys, sells, charges, dividends, and corporate actions. The app does not import brokerage holdings or execute orders.
6. Make an encrypted ledger backup before uninstalling or changing devices. Automatic Android backup/device transfer is disabled.

## Data-source limitations and upgrade

Yahoo Finance's chart endpoint is **unofficial**: it is not a documented supported API contract, may change or be blocked, and has no availability guarantee. Personal use is not a blanket permission grant; review Yahoo's applicable terms. The app does not scrape HTML, obtain cookies/crumbs, bypass login, spoof a browser, rotate hosts after denial, or evade rate limits. HTTP 401/403/429 ends the refresh; 429 also imposes a process-wide cooldown respecting bounded Retry-After. Existing cached prices remain on failed requests.

References:
- https://finance.yahoo.com/quote/RELIANCE.NS/
- https://legal.yahoo.com/us/en/yahoo/terms/otos/index.html
- https://nsearchives.nseindia.com/content/equities/EQUITY_L.csv

The app keeps stable `NSE_EQ|ISIN` ledger identifiers. It maps current catalogue symbols to `.NS` tickers and verifies Yahoo returns the expected symbol, NSE exchange, INR currency, and equity type. A missing Yahoo symbol is unavailable, never silently mapped to BSE. Download the catalogue again when symbols change. ISIN shape/checksum validation in the catalogue does not prove Yahoo's corporate identity; confirm renamed/merged listings against statements.

Upgrading from the token-based 1.0 preserves all transactions and clears only the rebuildable quote/history cache once to prevent mixing providers. The previous provider's token file and Keystore alias are removed locally; no old credential is read or sent. Tap Refresh to rebuild market data. Uninstalling is not needed when upgrading with the same signing key.

Google Finance pages are **not scraped**. `GOOGLEFINANCE` is a Sheets function whose historical outputs are restricted through Sheets API/Apps Script, so it is not used as this app's data API.

## Entry rules

| Type | Quantity | Price | Fees |
|---|---|---|---|
| BUY | Shares purchased | Price per share | Buying charges |
| SELL | Shares sold | Price per share | Selling charges |
| DIVIDEND | `0` | Total cash received | Charges, if applicable |
| SPLIT / bonus | New total shares divided by old total | `0` | `0` |
| OPENING | Existing shares | Average cost including past buying charges | `0` |

A 2-for-1 split or 1:1 bonus is multiplier `2`; a 1-for-5 reverse split is `0.2`. SPLIT preserves total remaining cost while adjusting each FIFO lot's quantity. It is not a tax-lot model for Indian bonus-share tax treatment. Corporate actions must be recorded manually; mergers, demergers, rights issues, and fractional cash settlements do not have dedicated accounting support in 1.0.

Transactions are processed by date then saved ID, including within one day. The user interface accepts dates from 2000-01-01 through today in Asia/Kolkata. The engine supports broader historical ledgers, but provider daily coverage starts in 2000. A sale may not exceed shares held at that point. An OPENING must be the first entry for that instrument. Invalid edits/deletions/imports roll back rather than corrupt later positions.

Use the correct `NSE_EQ|ISIN` ledger key and NSE trading symbol. The key preserves the ledger's identity; the symbol maps to Yahoo's `.NS` ticker. A downloaded stock selection supplies both automatically. Manual entries validate the ISIN's format; catalogue entries also validate its checksum. Neither proves ownership.

## Financial definitions and limitations

All amounts use INR. Quantities/costs are `BigDecimal`; partial FIFO cost allocation uses DECIMAL128, retaining the remainder so full disposal conserves the original cost. Display rounding happens only at the UI boundary.

- **Value:** remaining quantity × latest eligible price.
- **Unrealized P&L:** value − remaining FIFO cost basis.
- **Realized P&L:** net sale proceeds − FIFO cost of sold lots.
- **Total gain:** realized + unrealized + net recorded dividends.
- **Period P&L:** change in total gain between two valuation boundaries; equivalently ending holdings value − starting holdings value − purchase costs + net sale proceeds + net dividends during that period.

If a valid quote shows a market session on/before a period's baseline but completed closing history is older, that period and its current chart bar are **unavailable**. A last-trade quote is not silently inserted as a closing candle, and yesterday's move is not relabelled as today's earnings. Current portfolio valuation can remain available; tap calculation/data-quality notes for details. Without an exchange calendar or independent history source, other missing sessions cannot always be distinguished from holidays; carried prices remain explicitly warned.

Today means the India **calendar day**, using the last available close at or before yesterday as the baseline. Week begins Monday; month begins on the first calendar day. On a non-trading day with no ledger activity, the price movement is normally zero. Baseline carried-forward prices are marked; without a verified exchange calendar the app does not distinguish holidays from missing sessions. Periods ending today are only as fresh as your last successful refresh.

Buying more stock is not profit; selling is not a loss of invested value. Cash balances/deposits/withdrawals and unrecorded taxes are outside the portfolio. No cash-flow-adjusted percentage return (TWR/XIRR), tax filing, or trading advice is claimed. Allocation percentages are current holding weights, not investment returns.

An opening balance does not reconstruct earlier holdings. Periods whose baselines predate any OPENING are unavailable, and historical chart points before the latest opening are suppressed. Since-start total gain for an OPENING uses the supplied cost basis; this can include unrealized gain accrued before tracking started.

Yahoo daily closes may already be split-adjusted. `adjclose`/dividend-adjusted values are not used, but disabling adjusted-close output does **not** guarantee raw historical prices. Full requested history is replaced atomically after successful responses rather than mixing differently adjusted cached windows. The current incomplete daily candle is excluded.

Yahoo-reported split events are checked, but corporate-action coverage is not guaranteed. Pre-split closes/charts and periods crossing recorded splits/bonus entries are deliberately unavailable. If prior purchases/opening holdings exist but the reported split has not been recorded, valuation for that instrument is withheld until reconciled; share quantities are never changed automatically. Recording a split is not validation that its multiplier is correct. Post-split valuations require a post-split price. Verify corporate actions against broker statements; mergers, demergers and missing provider events remain limitations.

Missing prices never become zero. Quotes with unknown/future timestamps are not used; stale data and differing dates across holdings are disclosed. Eligible closes can carry forward at most seven calendar days with a warning. Longer gaps produce unavailable values. Do not infer real-time data from the act of refreshing: inspect each quote timestamp. A partially successful refresh keeps existing values for failed instruments, with their original timestamps, and reports partial status; tap the status text for full details.

## CSV and backups

Exact UTF-8 header (BOM input accepted):

```csv
id,date,instrument_key,symbol,name,type,quantity,price,fees
```

Export a ledger to obtain a template. IDs must be unique/nonnegative within an import; dates are ISO `YYYY-MM-DD`, types are uppercase, and decimal numbers use a period without thousands separators. RFC4180 quoted fields, commas, quotes and multiline names are supported subject to field validation. Maximum file size is 16 MiB and maximum ledger rows 100,000. This is a Jacks stocks interchange format, not an automatic parser for arbitrary broker exports.

CSV import **appends**, remapping IDs while preserving date/original-ID order. It is not deduplicated: re-importing a file repeats its entries. The entire proposed ledger is validated atomically. Exported CSV is unencrypted and should not be shared publicly. Text is preserved exactly; RFC4180 quoting does not neutralize spreadsheet formulas. Import name/symbol/key columns as text and never enable or evaluate formulas in untrusted CSV. Prefer encrypted backups for private transfer.

Encrypted `.jstocks` backup contains the **ledger only**. It excludes tokens, appearance preferences, instrument catalogue, and downloaded quotes/history. Restore **replaces** the current ledger only after successful password authentication and validation; the token is unchanged. Download data again on the destination phone. Backups use AES-256-GCM with a random nonce and salt, PBKDF2-HMAC-SHA256 with 210,000 iterations, and authenticated version/header fields. Passwords require 8–1,024 characters; longer strong passwords are recommended. There is no forgotten-password recovery. A failed destination write can leave an incomplete export file, but does not change the on-device ledger.

## Privacy and security

Only Yahoo tickers/date ranges are transmitted to `query1.finance.yahoo.com`. Portfolio quantities, cost basis, dividends, and ledger records stay local unless **you** export/backup them or choose a cloud-backed file destination. The public catalogue uses `nsearchives.nseindia.com`. HTTPS only, no redirects, bounded response sizes/timeouts and global request pacing. No provider credential is requested or stored. Opening external terms links launches your browser separately.

The ledger database is Android-app-private, not separately encrypted at rest; rely on a device screen lock and Android device encryption. Screenshots/recent-app previews are blocked by default. In a debug build only, **Settings → Debug review → Allow screenshots** explicitly enables capture; use fictional holdings for reviews. Release builds always block capture and have no toggle. These protections do not guarantee safety on a rooted/compromised device. Uninstalling removes local data.

## Build on Windows

Requirements: JDK 17 or compatible newer JDK, Android SDK Platform/Build Tools 35, Android SDK licences accepted through Android Studio/SDK Manager, and internet access on a cold build. The project pins Gradle 8.11.1 (SHA-256 verified) and Android Gradle Plugin 8.9.2, compatible with the toolchain already cached on this machine. Minimum Android version is 8.0/API 26. Compile/target is API 35; review current Play requirements before any future public release.

Open this standalone project in Android Studio:

`C:\Users\Dell\Desktop\7Oct-Desk\Oct-Data\jacks-stocks`

Or use PowerShell (set JAVA_HOME to your installed JDK):

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21'
$env:ANDROID_HOME = 'C:\Users\Dell\AppData\Local\Android\Sdk'
& 'C:\Users\Dell\Desktop\7Oct-Desk\Oct-Data\jacks-stocks\gradlew.bat' -p 'C:\Users\Dell\Desktop\7Oct-Desk\Oct-Data\jacks-stocks' assembleDebug lintDebug assembleDebugAndroidTest --console=plain
```

An untracked `local.properties` with your SDK path is an alternative to ANDROID_HOME. The Gradle wrapper and build do not need broker credentials.

Debug APK:

`C:\Users\Dell\Desktop\7Oct-Desk\Oct-Data\jacks-stocks\app\build\outputs\apk\debug\app-debug.apk`

Build outputs are deliberately not tracked in Git. A debug APK is appropriate for local testing; distribution requires a private release signing key (not provided or committed). Existing calculator data is unaffected by installing this separate package.

## Verification

Run the dependency-free calculation suite:

```powershell
& 'C:\Users\Dell\Desktop\7Oct-Desk\Oct-Data\jacks-stocks\tests\run-tests.ps1' -JavaHome 'C:\Program Files\Java\jdk-21'
```

Run the independent CSV/encrypted-backup suite:

```powershell
& 'C:\Users\Dell\Desktop\7Oct-Desk\Oct-Data\jacks-stocks\tests\data\run-tests.ps1' -JavaHome 'C:\Program Files\Java\jdk-21'
```

See `C:\Users\Dell\Desktop\7Oct-Desk\Oct-Data\jacks-stocks\TESTING.md` for actual build/test outcomes, screenshots and remaining validation limits. The UI is English-only. Historical chart requests are bounded to 100 years and large ledgers may take longer to calculate; Activity displays 100 records per page. Chart computation uses a separate worker so it does not block saving/refreshing, and obsolete queued chart requests are skipped. Yahoo availability today does not guarantee continued access or complete market/corporate-action data.