package com.jacks.stocks.core;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Platform-independent, long-only FIFO ledger. Money is not rounded to currency
 * units here. Additions and multiplications are exact; partial FIFO allocations
 * use DECIMAL128, keeping the remainder in the lot so full disposal conserves cost.
 * Dates and market timestamps use Asia/Kolkata. No percentage-return assumptions.
 */
public final class Portfolio {
    private static final BigDecimal ZERO = BigDecimal.ZERO;
    private static final MathContext PRECISION = MathContext.DECIMAL128;
    private static final ZoneId MARKET_ZONE = ZoneId.of("Asia/Kolkata");
    private static final Pattern KEY = Pattern.compile("NSE_EQ\\|[A-Z]{2}[A-Z0-9]{9}[0-9]");
    private static final long DAY_MILLIS = 24L * 60L * 60L * 1000L;
    private static final long MAX_PRICE_AGE_DAYS = 7L;
    private static final int MAX_INPUT_DIGITS = 64;
    private static final int MAX_WORKING_DIGITS = 4096;

    private Portfolio() {
    }

    public enum Type { BUY, SELL, DIVIDEND, SPLIT, OPENING }

    /** DIVIDEND price is total cash, SPLIT quantity is new/old, OPENING price is average cost. */
    public static final class Tx {
        public final long id;
        public final LocalDate date;
        public final String key;
        public final String symbol;
        public final String name;
        public final Type type;
        public final BigDecimal quantity;
        public final BigDecimal price;
        public final BigDecimal fees;

        public Tx(long id, LocalDate date, String key, String symbol, String name,
                Type type, BigDecimal quantity, BigDecimal price, BigDecimal fees) {
            this.id = id;
            this.date = date;
            this.key = key;
            this.symbol = symbol;
            this.name = name;
            this.type = type;
            this.quantity = quantity;
            this.price = price;
            this.fees = fees;
        }
    }

    /** Nullable price/previousClose represent an unavailable provider field, never zero. */
    public static final class Quote {
        public final String key;
        public final BigDecimal price;
        public final BigDecimal previousClose;
        public final long asOfMillis;
        public final long fetchedMillis;

        public Quote(String key, BigDecimal price, BigDecimal previousClose,
                long asOfMillis, long fetchedMillis) {
            this.key = key;
            this.price = price;
            this.previousClose = previousClose;
            this.asOfMillis = asOfMillis;
            this.fetchedMillis = fetchedMillis;
        }
    }

    public static final class Candle {
        public final String key;
        public final LocalDate date;
        public final BigDecimal close;

        public Candle(String key, LocalDate date, BigDecimal close) {
            this.key = key;
            this.date = date;
            this.close = close;
        }
    }

    public static final class Position {
        public final String key;
        public final String symbol;
        public final String name;
        public final BigDecimal quantity;
        public final BigDecimal cost;
        public final BigDecimal realized;
        public final BigDecimal dividends;
        public final BigDecimal price;
        public final BigDecimal value;
        public final BigDecimal unrealized;

        public Position(String key, String symbol, String name, BigDecimal quantity,
                BigDecimal cost, BigDecimal realized, BigDecimal dividends,
                BigDecimal price, BigDecimal value, BigDecimal unrealized) {
            this.key = key;
            this.symbol = symbol;
            this.name = name;
            this.quantity = quantity;
            this.cost = cost;
            this.realized = realized;
            this.dividends = dividends;
            this.price = price;
            this.value = value;
            this.unrealized = unrealized;
        }
    }

    /** Totals include closed positions. cost is remaining basis, not lifetime purchases. */
    public static final class Report {
        public final List<Position> positions;
        public final BigDecimal cost;
        public final BigDecimal realized;
        public final BigDecimal dividends;
        public final BigDecimal value;
        public final BigDecimal unrealized;
        public final BigDecimal totalGain;
        public final List<String> warnings;

        public Report(List<Position> positions, BigDecimal cost, BigDecimal realized,
                BigDecimal dividends, BigDecimal value, BigDecimal unrealized,
                BigDecimal totalGain, List<String> warnings) {
            this.positions = immutable(positions);
            this.cost = cost;
            this.realized = realized;
            this.dividends = dividends;
            this.value = value;
            this.unrealized = unrealized;
            this.totalGain = totalGain;
            this.warnings = immutable(warnings);
        }
    }

    /** A null amount means unavailable; reason also carries warnings for a usable amount. */
    public static final class Period {
        public final BigDecimal amount;
        public final String reason;

        public Period(BigDecimal amount, String reason) {
            this.amount = amount;
            this.reason = reason;
        }
    }

    /** gain is cumulative totalGain, not a percentage or an additional realized component. */
    public static final class Point {
        public final LocalDate date;
        public final BigDecimal value;
        public final BigDecimal gain;

        public Point(LocalDate date, BigDecimal value, BigDecimal gain) {
            this.date = date;
            this.value = value;
            this.gain = gain;
        }
    }

    /**
     * Validates the complete ledger in (date,id) order without changing its list.
     * IDs must be nonnegative and globally unique. Future dates, short selling,
     * blank names/symbols, malformed NSE ISIN keys, and negative decimals fail.
     * Input decimals have at most 64 digits including positive exponents and
     * absolute scale at most 64, matching the CSV/storage input contract.
     * OPENING must be the first entry for its key and has no fees. A split requires
     * existing holdings, positive new/old quantity, and zero price and fees.
     */
    public static void validate(List<Tx> transactions) throws IllegalArgumentException {
        checkedTransactions(transactions);
    }

    /**
     * Quotes may value only today's snapshot when requested. Otherwise use the
     * latest close on/before date, no more than seven calendar days old. Unknown
     * required prices propagate null through aggregate valuation and gain.
     * If both sources are eligible, the newer market date wins; a same-date
     * quote takes priority. Replacing an older quote with a close is warned.
     */
    public static Report report(List<Tx> transactions, List<Quote> quotes,
            List<Candle> candles, LocalDate date, boolean useQuotes) {
        checkDate(date, "Report date");
        List<Tx> sorted = checkedTransactions(transactions);
        MarketData market = new MarketData(quotes, candles, sorted);
        return snapshot(sorted, market, date, useQuotes, System.currentTimeMillis());
    }

    /**
     * Cash-flow-adjusted absolute profit in (startExclusive,endInclusive]. Delta
     * totalGain equals value delta - buys (with fees) + sales (net fees) + net
     * dividends. OPENING and uncertain split transitions are not cash flows.
     * If a dated quote proves a session occurred before the baseline but its
     * closing history is missing, suppress the period instead of shifting that
     * earlier session's move into today's earnings. Quotes never invent closes.
     */
    public static Period period(List<Tx> transactions, List<Quote> quotes,
            List<Candle> candles, LocalDate startExclusive, LocalDate endInclusive,
            boolean useQuotes) {
        checkRange(startExclusive, endInclusive);
        List<Tx> sorted = checkedTransactions(transactions);
        MarketData market = new MarketData(quotes, candles, sorted);
        for (Tx tx : sorted) {
            if (tx.date.isAfter(startExclusive) && !tx.date.isAfter(endInclusive)) {
                if (tx.type == Type.OPENING) {
                    return new Period(null, "Baseline predates OPENING for " + tx.symbol
                            + " on " + tx.date + "; earlier holdings and performance are unknown.");
                }
                if (tx.type == Type.SPLIT) {
                    return new Period(null, "Period spans SPLIT for " + tx.symbol + " on "
                            + tx.date + "; raw/adjusted provider prices are uncertain.");
                }
            }
        }
        long now = System.currentTimeMillis();
        Report before = snapshot(sorted, market, startExclusive,
                startExclusive.equals(endInclusive) && useQuotes, now);
        Report after = startExclusive.equals(endInclusive) ? before
                : snapshot(sorted, market, endInclusive, useQuotes, now);
        Set<String> warnings = new LinkedHashSet<>(before.warnings);
        warnings.addAll(after.warnings);
        if (before.totalGain == null || after.totalGain == null) {
            return new Period(null, "Missing reliable price history for period baseline or end. "
                    + join(warnings));
        }
        if (useQuotes && endInclusive.equals(marketDate(now)) && startExclusive.isBefore(endInclusive)) {
            for (Position position : before.positions) {
                if (position.quantity.signum() <= 0) continue;
                LocalDate missing = market.missingBaselineSession(position.key, startExclusive, now);
                if (missing != null) {
                    return new Period(null, "Missing completed-session close for " + position.symbol
                            + " on/after " + missing + " at period baseline " + startExclusive
                            + ". An earlier session's quote must not be counted as this period's move. "
                            + join(warnings));
                }
            }
        }
        return new Period(after.totalGain.subtract(before.totalGain), join(warnings));
    }

    /**
     * Inclusive daily samples. No interpolation or fabricated zero prices. Points
     * before the latest OPENING or SPLIT are suppressed (both value and gain null)
     * because prior holdings/price adjustment cannot be reconstructed safely.
     * The final point alone may use quotes, and only when to is today in Kolkata.
     * Use report(...).warnings or period(...).reason to explain unavailable points.
     * Requests spanning more than 100 years are rejected before daily expansion;
     * the age of individual imported ledger entries is not restricted by this cap.
     */
    public static List<Point> history(List<Tx> transactions, List<Quote> quotes,
            List<Candle> candles, LocalDate from, LocalDate to) {
        checkRange(from, to);
        if (to.isAfter(from.plusYears(100))) {
            throw new IllegalArgumentException("History range must not exceed 100 years.");
        }
        List<Tx> sorted = checkedTransactions(transactions);
        MarketData market = new MarketData(quotes, candles, sorted);
        LocalDate reliableFrom = null;
        for (Tx tx : sorted) {
            if ((tx.type == Type.OPENING || tx.type == Type.SPLIT)
                    && (reliableFrom == null || tx.date.isAfter(reliableFrom))) {
                reliableFrom = tx.date;
            }
        }
        long now = System.currentTimeMillis();
        LocalDate today = marketDate(now);
        List<Point> points = new ArrayList<>();
        for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
            if (reliableFrom != null && date.isBefore(reliableFrom)) {
                points.add(new Point(date, null, null));
            } else {
                Report result = snapshot(sorted, market, date,
                        date.equals(to) && to.equals(today), now);
                points.add(new Point(date, result.value, result.totalGain));
            }
        }
        return immutable(points);
    }

    private static Report snapshot(List<Tx> sorted, MarketData market, LocalDate date,
            boolean useQuotes, long now) {
        Set<String> warnings = new LinkedHashSet<>(market.warnings);
        Set<LocalDate> quoteDates = new HashSet<>();
        boolean currentQuotes = useQuotes && date.equals(marketDate(now));
        if (useQuotes && !currentQuotes) {
            warnings.add("Live/cached quotes ignored for historical date " + date + ".");
        }
        boolean complete = true;
        for (Tx tx : sorted) {
            if (tx.type == Type.OPENING && tx.date.isAfter(date)) {
                complete = false;
                warnings.add("Snapshot predates OPENING for " + tx.symbol + " on " + tx.date
                        + "; earlier holdings and performance are unknown.");
            }
        }
        for (Map.Entry<String, LocalDate> split : market.latestSplit.entrySet()) {
            warnings.add("SPLIT for " + split.getKey() + " on " + split.getValue()
                    + ": historical performance spanning split and history before latest split"
                    + " are suppressed because raw/adjusted provider prices are uncertain.");
            if (date.isBefore(split.getValue())) {
                complete = false;
            }
        }
        List<Position> positions = new ArrayList<>();
        BigDecimal cost = ZERO;
        BigDecimal realized = ZERO;
        BigDecimal dividends = ZERO;
        BigDecimal value = ZERO;
        for (Holding holding : replay(sorted, date).values()) {
            BigDecimal holdingCost = holding.cost();
            BigDecimal price = null;
            BigDecimal holdingValue = ZERO;
            BigDecimal unrealized = ZERO;
            if (holding.quantity.signum() > 0) {
                price = market.price(holding.key, date, holding.lastDate,
                        currentQuotes, now, warnings, quoteDates);
                if (price == null) {
                    holdingValue = null;
                    unrealized = null;
                    complete = false;
                } else {
                    holdingValue = holding.quantity.multiply(price);
                    unrealized = holdingValue.subtract(holdingCost);
                    value = value.add(holdingValue);
                }
            }
            positions.add(new Position(holding.key, holding.symbol, holding.name,
                    holding.quantity, holdingCost, holding.realized, holding.dividends,
                    price, holdingValue, unrealized));
            cost = cost.add(holdingCost);
            realized = realized.add(holding.realized);
            dividends = dividends.add(holding.dividends);
        }
        if (quoteDates.size() > 1) {
            warnings.add("Quotes have differing quote dates; portfolio valuation is not a synchronized snapshot.");
        }
        BigDecimal unrealized = complete ? value.subtract(cost) : null;
        BigDecimal gain = complete ? unrealized.add(realized).add(dividends) : null;
        return new Report(positions, cost, realized, dividends, complete ? value : null,
                unrealized, gain, new ArrayList<>(warnings));
    }

    private static final class MarketData {
        final Map<String, List<Quote>> quotes = new HashMap<>();
        final Map<String, TreeMap<LocalDate, BigDecimal>> closes = new HashMap<>();
        final Map<String, LocalDate> latestSplit = new TreeMap<>();
        final Set<String> warnings = new LinkedHashSet<>();

        MarketData(List<Quote> inputQuotes, List<Candle> candles, List<Tx> sorted) {
            if (inputQuotes == null || candles == null) {
                throw new IllegalArgumentException("Quote and candle lists must not be null.");
            }
            for (Tx tx : sorted) {
                if (tx.type == Type.SPLIT) {
                    latestSplit.put(tx.key, tx.date);
                }
            }
            for (Quote quote : inputQuotes) {
                if (quote == null) {
                    throw new IllegalArgumentException("Quote must not be null.");
                }
                checkKey(quote.key);
                nonnegative(quote.price, "Quote price", true);
                nonnegative(quote.previousClose, "Previous close", true);
                if (quote.asOfMillis < 0 || quote.fetchedMillis < 0) {
                    throw new IllegalArgumentException("Quote timestamps must be nonnegative.");
                }
                List<Quote> byKey = quotes.get(quote.key);
                if (byKey == null) {
                    byKey = new ArrayList<>();
                    quotes.put(quote.key, byKey);
                }
                byKey.add(quote);
            }
            for (List<Quote> byKey : quotes.values()) {
                byKey.sort(Comparator.comparingLong((Quote quote) -> quote.asOfMillis)
                        .thenComparingLong(quote -> quote.fetchedMillis).reversed());
            }
            for (Candle candle : candles) {
                if (candle == null) {
                    throw new IllegalArgumentException("Candle must not be null.");
                }
                checkKey(candle.key);
                checkDate(candle.date, "Candle date");
                nonnegative(candle.close, "Close", true);
                if (candle.close == null || candle.close.signum() == 0) {
                    warnings.add("Missing close for " + candle.key + " on " + candle.date + ".");
                    continue;
                }
                TreeMap<LocalDate, BigDecimal> byDate = closes.get(candle.key);
                if (byDate == null) {
                    byDate = new TreeMap<>();
                    closes.put(candle.key, byDate);
                }
                BigDecimal previous = byDate.put(candle.date, candle.close);
                if (previous != null && previous.compareTo(candle.close) != 0) {
                    throw new IllegalArgumentException("Conflicting closes for " + candle.key
                            + " on " + candle.date + ".");
                }
            }
        }

        LocalDate missingBaselineSession(String key, LocalDate baseline, long now) {
            List<Quote> candidates = quotes.get(key);
            if (candidates == null) return null;
            TreeMap<LocalDate, BigDecimal> byDate = closes.get(key);
            LocalDate closeDate = byDate == null ? null : byDate.floorKey(baseline);
            LocalDate split = latestSplit.get(key);
            for (Quote quote : candidates) {
                if (quote.price == null || quote.price.signum() <= 0 || quote.asOfMillis <= 0
                        || quote.asOfMillis > now || quote.fetchedMillis > now) continue;
                LocalDate session = marketDate(quote.asOfMillis);
                if (session.isAfter(baseline) || ChronoUnit.DAYS.between(session, baseline) > MAX_PRICE_AGE_DAYS
                        || (split != null && session.isBefore(split))) continue;
                if (closeDate == null || closeDate.isBefore(session)) return session;
            }
            return null;
        }

        BigDecimal price(String key, LocalDate date, LocalDate lastTransaction,
                boolean useQuotes, long now,
                Set<String> outputWarnings, Set<LocalDate> quoteDates) {
            LocalDate split = latestSplit.get(key);
            if (split != null && date.isBefore(split)) {
                outputWarnings.add("Missing reliable price history for " + key
                        + " before latest SPLIT " + split + ".");
                return null;
            }
            TreeMap<LocalDate, BigDecimal> byDate = closes.get(key);
            Map.Entry<LocalDate, BigDecimal> close = byDate == null ? null : byDate.floorEntry(date);
            boolean eligibleClose = close != null
                    && (split == null || !close.getKey().isBefore(split))
                    && ChronoUnit.DAYS.between(close.getKey(), date) <= MAX_PRICE_AGE_DAYS;
            if (useQuotes) {
                boolean replacedByClose = false;
                List<Quote> candidates = quotes.get(key);
                if (candidates != null) {
                    for (Quote quote : candidates) {
                        if (quote.price == null || quote.price.signum() == 0) {
                            outputWarnings.add("Missing quote price for " + key + ".");
                            continue;
                        }
                        if (quote.asOfMillis == 0) {
                            outputWarnings.add("Cached quote for " + key
                                    + " has unknown market timestamp; not used.");
                            continue;
                        }
                        if (quote.asOfMillis > now || quote.fetchedMillis > now) {
                            outputWarnings.add("Future-dated quote for " + key + " not used.");
                            continue;
                        }
                        LocalDate quoteDate = marketDate(quote.asOfMillis);
                        if (ChronoUnit.DAYS.between(quoteDate, date) > MAX_PRICE_AGE_DAYS) {
                            outputWarnings.add("Stale cached quote for " + key + " is more than 7 days old; not used.");
                            continue;
                        }
                        if (split != null && quoteDate.isBefore(split)) {
                            outputWarnings.add("Cached quote for " + key + " predates SPLIT; not used.");
                            continue;
                        }
                        if (now - quote.asOfMillis > DAY_MILLIS) {
                            outputWarnings.add("Stale cached quote for " + key
                                    + ": market price is more than 24h old (" + quoteDate + ").");
                        }
                        if (quote.fetchedMillis == 0) {
                            outputWarnings.add("Cached quote for " + key + " has unknown fetch age.");
                        } else if (now - quote.fetchedMillis > DAY_MILLIS) {
                            outputWarnings.add("Stale cached quote for " + key + ": fetched more than 24h ago.");
                        }
                        if (!quoteDate.equals(date)) {
                            outputWarnings.add("Cached quote date " + quoteDate + " for " + key
                                    + " differs from valuation date " + date + ".");
                        }
                        if (quoteDate.isBefore(lastTransaction)) {
                            outputWarnings.add("Cached quote for " + key + " predates latest transaction "
                                    + lastTransaction + "; valuation is stale relative to the ledger.");
                        }
                        if (eligibleClose && close.getKey().isAfter(quoteDate)) {
                            outputWarnings.add("Using newer historical close for " + key + " from "
                                    + close.getKey() + " instead of cached quote dated " + quoteDate + ".");
                            replacedByClose = true;
                            break;
                        }
                        quoteDates.add(quoteDate);
                        return quote.price;
                    }
                }
                if (!replacedByClose) {
                    outputWarnings.add("No usable current quote for " + key + "; trying last known close.");
                }
            }
            if (close == null) {
                outputWarnings.add("Missing price history for " + key + " on/before " + date + ".");
                return null;
            }
            if (split != null && close.getKey().isBefore(split)) {
                outputWarnings.add("Last close for " + key + " predates SPLIT; reliable post-split price is missing.");
                return null;
            }
            long age = ChronoUnit.DAYS.between(close.getKey(), date);
            if (age > MAX_PRICE_AGE_DAYS) {
                outputWarnings.add("Stale close for " + key + " from " + close.getKey()
                        + " is more than 7 days old; valuation unavailable.");
                return null;
            }
            if (age > 0) {
                outputWarnings.add("Carried/stale close for " + key + " from " + close.getKey()
                        + " (" + age + " calendar days old; market holiday/weekend or missing session).");
            }
            if (close.getKey().isBefore(lastTransaction)) {
                outputWarnings.add("Carried close for " + key + " predates latest transaction "
                        + lastTransaction + "; valuation is stale relative to the ledger.");
            }
            return close.getValue();
        }
    }

    private static <T> List<T> immutable(List<T> values) {
        if (values == null) {
            throw new IllegalArgumentException("List must not be null.");
        }
        return Collections.unmodifiableList(new ArrayList<>(values));
    }

    private static List<Tx> checkedTransactions(List<Tx> transactions) {
        if (transactions == null) {
            throw new IllegalArgumentException("Transactions must not be null.");
        }
        List<Tx> sorted = new ArrayList<>(transactions);
        Set<Long> ids = new HashSet<>();
        for (Tx tx : sorted) {
            if (tx == null) {
                throw new IllegalArgumentException("Transaction must not be null.");
            }
            if (tx.id < 0 || !ids.add(tx.id)) {
                throw invalid(tx, "ID must be nonnegative and unique");
            }
            checkDate(tx.date, "Transaction date");
            checkKey(tx.key);
            checkText(tx.symbol, "Symbol");
            checkText(tx.name, "Name");
            if (tx.type == null) {
                throw invalid(tx, "Type is required");
            }
            nonnegative(tx.quantity, "Quantity", false);
            nonnegative(tx.price, "Price", false);
            nonnegative(tx.fees, "Fees", false);
            if (tx.type == Type.DIVIDEND) {
                if (tx.quantity.signum() != 0 || tx.price.signum() <= 0) {
                    throw invalid(tx, "DIVIDEND requires zero quantity and positive total received");
                }
            } else if (tx.quantity.signum() <= 0) {
                throw invalid(tx, "Quantity or split multiplier must be positive");
            }
            if ((tx.type == Type.BUY || tx.type == Type.SELL) && tx.price.signum() <= 0) {
                throw invalid(tx, "BUY and SELL price must be positive");
            }
            if (tx.type == Type.SPLIT && (tx.price.signum() != 0 || tx.fees.signum() != 0)) {
                throw invalid(tx, "SPLIT price and fees must be zero");
            }
            if (tx.type == Type.OPENING && tx.fees.signum() != 0) {
                throw invalid(tx, "OPENING average cost already includes costs; fees must be zero");
            }
        }
        sorted.sort(Comparator.comparing((Tx tx) -> tx.date).thenComparingLong(tx -> tx.id));
        replay(sorted, null);
        return sorted;
    }

    private static Map<String, Holding> replay(List<Tx> sorted, LocalDate through) {
        Map<String, Holding> holdings = new TreeMap<>();
        for (Tx tx : sorted) {
            if (through != null && tx.date.isAfter(through)) {
                break;
            }
            Holding holding = holdings.get(tx.key);
            if (tx.type == Type.OPENING && holding != null) {
                throw invalid(tx, "OPENING must be the first transaction for its instrument");
            }
            if (holding == null) {
                holding = new Holding(tx);
                holdings.put(tx.key, holding);
            }
            holding.symbol = tx.symbol;
            holding.name = tx.name;
            holding.lastDate = tx.date;
            switch (tx.type) {
                case BUY:
                case OPENING:
                    holding.lots.addLast(new Lot(tx.quantity,
                            tx.quantity.multiply(tx.price).add(tx.fees)));
                    holding.quantity = holding.quantity.add(tx.quantity);
                    break;
                case SELL:
                    if (tx.quantity.compareTo(holding.quantity) > 0) {
                        throw invalid(tx, "SELL oversells available quantity " + holding.quantity);
                    }
                    BigDecimal remaining = tx.quantity;
                    BigDecimal removedCost = ZERO;
                    while (remaining.signum() > 0) {
                        Lot lot = holding.lots.getFirst();
                        BigDecimal taken = remaining.min(lot.quantity);
                        BigDecimal allocation;
                        if (taken.compareTo(lot.quantity) == 0) {
                            allocation = lot.cost;
                            holding.lots.removeFirst();
                        } else {
                            allocation = lot.cost.multiply(taken).divide(lot.quantity, PRECISION)
                                    .min(lot.cost);
                            lot.quantity = lot.quantity.subtract(taken);
                            lot.cost = lot.cost.subtract(allocation);
                        }
                        removedCost = removedCost.add(allocation);
                        remaining = remaining.subtract(taken);
                    }
                    holding.quantity = holding.quantity.subtract(tx.quantity);
                    holding.realized = holding.realized.add(tx.quantity.multiply(tx.price)
                            .subtract(tx.fees).subtract(removedCost));
                    break;
                case DIVIDEND:
                    holding.dividends = holding.dividends.add(tx.price.subtract(tx.fees));
                    break;
                case SPLIT:
                    if (holding.quantity.signum() <= 0) {
                        throw invalid(tx, "SPLIT requires an existing positive holding");
                    }
                    for (Lot lot : holding.lots) {
                        lot.quantity = lot.quantity.multiply(tx.quantity);
                        bounded(lot.quantity, "Accumulated split quantity", MAX_WORKING_DIGITS);
                    }
                    holding.quantity = holding.quantity.multiply(tx.quantity);
                    bounded(holding.quantity, "Accumulated split quantity", MAX_WORKING_DIGITS);
                    break;
                default:
                    throw invalid(tx, "Unsupported transaction type");
            }
        }
        return holdings;
    }

    private static final class Lot {
        BigDecimal quantity;
        BigDecimal cost;

        Lot(BigDecimal quantity, BigDecimal cost) {
            this.quantity = quantity;
            this.cost = cost;
        }
    }

    private static final class Holding {
        final String key;
        String symbol;
        String name;
        LocalDate lastDate;
        final Deque<Lot> lots = new ArrayDeque<>();
        BigDecimal quantity = ZERO;
        BigDecimal realized = ZERO;
        BigDecimal dividends = ZERO;

        Holding(Tx tx) {
            key = tx.key;
            symbol = tx.symbol;
            name = tx.name;
            lastDate = tx.date;
        }

        BigDecimal cost() {
            BigDecimal total = ZERO;
            for (Lot lot : lots) {
                total = total.add(lot.cost);
            }
            return total;
        }
    }

    private static IllegalArgumentException invalid(Tx tx, String message) {
        return new IllegalArgumentException("Transaction " + tx.id + ": " + message + ".");
    }

    private static void checkDate(LocalDate date, String field) {
        if (date == null || date.getYear() < 1 || date.isAfter(LocalDate.now(MARKET_ZONE))) {
            throw new IllegalArgumentException(field + " must be a valid, nonfuture date.");
        }
    }

    private static void checkRange(LocalDate from, LocalDate to) {
        checkDate(from, "Start date");
        checkDate(to, "End date");
        if (from.isAfter(to)) {
            throw new IllegalArgumentException("Start date must not be after end date.");
        }
    }

    private static void checkKey(String key) {
        if (key == null || !KEY.matcher(key).matches()) {
            throw new IllegalArgumentException("Instrument key must be NSE_EQ| followed by a 12-character ISIN.");
        }
    }

    private static void checkText(String value, String field) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank.");
        }
        for (int index = 0; index < value.length(); index++) {
            if (Character.isISOControl(value.charAt(index))) {
                throw new IllegalArgumentException(field + " must not contain control characters.");
            }
        }
    }

    private static void nonnegative(BigDecimal value, String field, boolean nullable) {
        if ((value == null && !nullable) || (value != null && value.signum() < 0)) {
            throw new IllegalArgumentException(field + " must be nonnegative" + (nullable ? " or null." : "."));
        }
        if (value != null) {
            bounded(value, field, MAX_INPUT_DIGITS);
        }
    }

    private static void bounded(BigDecimal value, String field, int digits) {
        long scale = value.scale();
        if (value.precision() + Math.max(0L, -scale) > digits || Math.abs(scale) > digits) {
            throw new IllegalArgumentException(field + " exceeds " + digits + " digits/scale.");
        }
    }

    private static LocalDate marketDate(long millis) {
        return Instant.ofEpochMilli(millis).atZone(MARKET_ZONE).toLocalDate();
    }

    private static String join(Iterable<String> messages) {
        StringBuilder result = new StringBuilder();
        for (String message : messages) {
            if (result.length() > 0) {
                result.append(' ');
            }
            result.append(message);
        }
        return result.toString();
    }
}