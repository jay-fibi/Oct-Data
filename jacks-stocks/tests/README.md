# Portfolio core regression tests and public contract

Production source: `C:\Users\Dell\Desktop\7Oct-Desk\Oct-Data\jacks-stocks\app\src\main\java\com\jacks\stocks\core\Portfolio.java`.

Run from any PowerShell working directory:

```powershell
& 'C:\Users\Dell\Desktop\7Oct-Desk\Oct-Data\jacks-stocks\tests\run-tests.ps1'
```

The runner uses `JAVA_HOME` when set, otherwise `C:\Users\Dell\.jdks\ms-17.0.15`,
and accepts an explicit `-JavaHome`. It compiles with
`--release 8 -Xlint:all,-options -Werror` and deletes its temporary class
directory in `finally`. There are no Android, JUnit, Maven, or Gradle dependencies.
Only obsolete-option warnings are disabled to allow newer JDKs to target Java 8;
all source warnings still fail compilation.
Fixed 2024 dates test ledger math. Current-endpoint tests derive today's date in
Asia/Kolkata because that public API deliberately depends on the current day.
The randomized conservation suite uses a fixed seed.

Latest JDK 21 run: **4,161 assertions in 17 groups**. The completed-session baseline
regression was observed failing before the fix, then passing afterward. It covers
an earlier-session quote with missing closing history, recovered history, historical
mode, zero-length periods, invalid quotes, new holdings and fully sold holdings.
Current-value snapshots remain usable, but unsupported period earnings are null;
the last quote is never converted into an invented closing candle.

## Exact public contract

`com.jacks.stocks.core.Portfolio` is the sole public top-level production class.
It is `final`, with no public constructor. All seven nested data classes are
`public static final`; every field is `public final`. Constructor arguments below
are also the exact field names, types, and field order. No records are used.

```text
enum Type { BUY, SELL, DIVIDEND, SPLIT, OPENING }

Tx(long id, LocalDate date, String key, String symbol, String name,
   Type type, BigDecimal quantity, BigDecimal price, BigDecimal fees)

Quote(String key, BigDecimal price, BigDecimal previousClose,
      long asOfMillis, long fetchedMillis)

Candle(String key, LocalDate date, BigDecimal close)

Position(String key, String symbol, String name, BigDecimal quantity,
         BigDecimal cost, BigDecimal realized, BigDecimal dividends,
         BigDecimal price, BigDecimal value, BigDecimal unrealized)

Report(List<Position> positions, BigDecimal cost, BigDecimal realized,
       BigDecimal dividends, BigDecimal value, BigDecimal unrealized,
       BigDecimal totalGain, List<String> warnings)

Period(BigDecimal amount, String reason)

Point(LocalDate date, BigDecimal value, BigDecimal gain)
```

```java
public static void validate(List<Tx> transactions) throws IllegalArgumentException;
public static Report report(List<Tx> transactions, List<Quote> quotes,
        List<Candle> candles, LocalDate date, boolean useQuotes);
public static Period period(List<Tx> transactions, List<Quote> quotes,
        List<Candle> candles, LocalDate startExclusive, LocalDate endInclusive,
        boolean useQuotes);
public static List<Point> history(List<Tx> transactions, List<Quote> quotes,
        List<Candle> candles, LocalDate from, LocalDate to);
```

`LocalDate` is `java.time.LocalDate`, `BigDecimal` is `java.math.BigDecimal`, and
`List` is `java.util.List`. Android must provide java.time (API 26+ or existing
core-library desugaring); this implementation does not alter build configuration.

## Financial and integration semantics

- Inputs are copied and sorted by `(date,id)`. IDs are nonnegative and globally
  unique; key syntax is `NSE_EQ|[A-Z]{2}[A-Z0-9]{9}[0-9]` (ISIN format, not check-digit
  certification). Dates must have year >= 1 and not be future dates in Kolkata.
  Names and symbols cannot be blank or contain control characters.
- `validate` and calculation APIs reject malformed transactions with
  `IllegalArgumentException`; data constructors are immutable value carriers,
  not substitutes for whole-ledger validation. Lists cannot be null: use empty
  lists for unavailable quote/candle sources.
- BUY and SELL require positive price and quantity. Buy fees increase lot cost;
  sale fees reduce proceeds. Selling beyond the replayed holdings is rejected.
  Decimal inputs use the same bounds as CSV/storage:
  `precision + max(0, -scale) <= 64` and `abs(scale) <= 64`.
  This rejects hostile exponents before arithmetic/formatting. Computed results
  are not rounded to that input limit. Repeated splits are rejected if accumulated
  quantities exceed 4,096 digits/scale, preventing unbounded intermediate growth.
  Addition/multiplication are exact; partial FIFO allocations use DECIMAL128 and
  leave the rounded remainder in the lot. Full disposal consumes all residual cost.
- DIVIDEND requires quantity zero and positive price (the total gross receipt).
  Its fees reduce the dividend total; dividend cash can arrive after disposal.
- OPENING requires positive quantity, nonnegative average cost, zero fees, and
  must be the first transaction for its key. Existing average cost becomes basis;
  earlier holdings and tracking history are unknown, not assumed to be empty.
- SPLIT requires existing holdings, a positive new/old multiplier, and zero price
  and fees. Forward, reverse, and fractional splits preserve each FIFO lot's cost.
- Reports retain closed positions for realized/dividend history. `cost` is remaining
  basis; `realized` excludes dividends; `dividends` is net receipts. `totalGain`
  equals `unrealized + realized + dividends` exactly once. Empty/closed holdings
  have known zero value and unrealized gain, without requiring a market price.
- Nullable output fields: Position `price/value/unrealized`, Report
  `value/unrealized/totalGain`, Period `amount`, and Point `value/gain`. Unknown
  prices never become zero. Report aggregate value/gain is null if any required
  open holding cannot be valued. Known basis, realized profit, and dividends remain
  available. Report lists and the returned history list are defensive/unmodifiable.
- Quote `price/previousClose` and Candle `close` may be null. Zero market prices
  also mean unavailable. Negative market prices or timestamps are rejected.
  Unknown quote `asOfMillis=0` is never treated as fresh. `previousClose` is not
  used to fabricate a historical baseline. Conflicting duplicate daily closes fail.
- Historical prices use the latest positive close on/before the valuation date.
  Carry up to seven calendar days is permitted with a stale/holiday warning;
  anything older is unavailable. No interpolation or lookahead is performed.
- `useQuotes=true` allows quotes only for today's Kolkata snapshot. Usable quotes
  warn on >24h market/cache age, unknown fetch age, differing quote/valuation dates,
  different quote dates across holdings, or prices predating included transactions.
  Unknown/future/>7-day market timestamps are rejected as valuation sources;
  candles are attempted instead. Carried closes predating transactions also warn.
  Among an eligible quote and eligible close, the newer Kolkata market date wins;
  a same-date quote wins the tie. A newer close overriding an older cached quote
  produces an explicit source-selection warning, while retaining stale quote
  warnings. Fetch time does not make an older market price newer.
- Periods use delta cumulative `totalGain`: value delta minus purchases including
  fees, plus net sales and net dividends. The baseline is end-of-day
  `startExclusive`; the end is end-of-day `endInclusive`. A same-date interval is
  zero when its snapshot is known. Missing endpoint valuations return null.
  Periods crossing OPENING or SPLIT return null with an explanatory reason.
  `Period.reason` is empty on a warning-free success and otherwise describes
  warnings or why the amount is unavailable.
- Provider adjusted/raw candle status is unknown. Snapshots/history before the
  latest relevant recorded split are conservatively suppressed, and pre-split
  quotes/closes cannot value post-split holdings. A report always carries a split
  warning when the ledger contains one. History also suppresses points before
  the latest OPENING. `Point` intentionally has no extra warning field: display
  `report(...).warnings` or `period(...).reason` alongside chart gaps.
- History is inclusive daily sampling; only its endpoint may use quotes, and only
  when `to` is today in Kolkata. `Point.gain` is cumulative absolute gain, not a
  percent or profit since the chart start. No percent-return API exists.
- History currently replays transactions per day (O(days * transactions), plus
  per-position valuation); run charts off the Android UI thread. It indexes market
  data once per request. Date-only ledger entries cannot resolve intraday trade vs
  quote ordering. The API uses wall-clock time for current-day quote selection.
  Daily history ranges over 100 years are rejected before expanding dates. Dates
  in imported ledgers are not restricted to the past 100 years: the established
  valid-year/nonfuture rule remains compatible for report/period/validation.

## Coverage

The suite verifies immutable contracts, sorting, input stability, FIFO fees and
partial lots, precision and cost conservation, cash-flow-neutral purchases,
realized/unrealized non-duplication, dividend fees and post-sale dividends,
opening boundaries, missing baselines/prices, holiday carry and stale cutoffs,
forward/reverse/fractional splits and safe chart gaps, multi-instrument totals,
quote dates and staleness, rejected fields/oversells, inclusive history, and
fixed-seed randomized cash-flow conservation. Host-timezone independence is
additionally checked by running with UTC and America/Los_Angeles JVM timezones.