import com.jacks.stocks.core.Portfolio;
import com.jacks.stocks.core.Portfolio.Candle;
import com.jacks.stocks.core.Portfolio.Period;
import com.jacks.stocks.core.Portfolio.Point;
import com.jacks.stocks.core.Portfolio.Position;
import com.jacks.stocks.core.Portfolio.Quote;
import com.jacks.stocks.core.Portfolio.Report;
import com.jacks.stocks.core.Portfolio.Tx;
import com.jacks.stocks.core.Portfolio.Type;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/** Dependency-free regression suite: throws AssertionError without relying on -ea. */
public final class PortfolioTests {
    private static final String A = "NSE_EQ|INE009A01021";
    private static final String B = "NSE_EQ|INE002A01018";
    private static final LocalDate D = LocalDate.of(2024, 1, 1);
    private static final ZoneId MARKET = ZoneId.of("Asia/Kolkata");
    private static final List<Quote> NO_QUOTES = Collections.emptyList();
    private static final List<Candle> NO_CANDLES = Collections.emptyList();
    private static int assertions;
    private static int groups;

    private PortfolioTests() {
    }

    public static void main(String[] args) {
        run("empty ledger and immutable public contract", PortfolioTests::emptyAndContract);
        run("FIFO partial sales, fee accounting, closed positions", PortfolioTests::fifo);
        run("decimal precision and full-lot cost conservation", PortfolioTests::decimals);
        run("cash-flow-adjusted periods without double counting", PortfolioTests::cashFlows);
        run("OPENING boundaries and existing balance semantics", PortfolioTests::openings);
        run("missing prices are unknown, not zero", PortfolioTests::missingPrices);
        run("weekend/holiday carry and seven-day cutoff", PortfolioTests::carry);
        run("forward/reverse SPLIT accounting and chart safety", PortfolioTests::splits);
        run("sorted replay, FIFO order, and input stability", PortfolioTests::sorting);
        run("invalid transaction fields and oversell failures", PortfolioTests::invalidTransactions);
        run("invalid market data and API boundaries", PortfolioTests::invalidMarketData);
        run("daily history and current endpoint quote scope", PortfolioTests::history);
        run("current quotes, stale cache warnings, market dates", PortfolioTests::quotes);
        run("newest eligible quote/candle source selection", PortfolioTests::newestPriceSource);
        run("missing completed-session baseline is not today's earnings", PortfolioTests::missingSessionBaseline);
        run("bounded decimal inputs and chart date spans", PortfolioTests::numericBounds);
        run("randomized FIFO/cash-flow conservation", PortfolioTests::conservation);
        System.out.println("PASS: " + assertions + " assertions in " + groups + " test groups.");
    }

    private static void emptyAndContract() {
        Report report = report(Collections.emptyList(), NO_CANDLES, D);
        equal(0, report.positions.size());
        number("0", report.cost);
        number("0", report.value);
        number("0", report.realized);
        number("0", report.dividends);
        number("0", report.unrealized);
        number("0", report.totalGain);
        equal(0, report.warnings.size());
        number("0", Portfolio.period(Collections.emptyList(), NO_QUOTES, NO_CANDLES,
                D, D.plusDays(1), false).amount);
        Class<?>[] classes = {Tx.class, Quote.class, Candle.class, Position.class,
                Report.class, Period.class, Point.class};
        for (Class<?> type : classes) {
            check(Modifier.isPublic(type.getModifiers()), type + " public");
            check(Modifier.isStatic(type.getModifiers()), type + " static");
            check(Modifier.isFinal(type.getModifiers()), type + " final");
            equal(1, type.getConstructors().length);
            for (Field field : type.getDeclaredFields()) {
                check(Modifier.isPublic(field.getModifiers()), field + " public");
                check(Modifier.isFinal(field.getModifiers()), field + " final");
            }
        }
        List<Position> positions = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        Report copied = new Report(positions, b("0"), b("0"), b("0"), null, null, null, warnings);
        positions.add(new Position(A, "A", "Alpha", b("1"), b("1"), b("0"),
                b("0"), null, null, null));
        warnings.add("after construction");
        equal(0, copied.positions.size());
        equal(0, copied.warnings.size());
        unsupported(() -> copied.positions.clear());
        unsupported(() -> copied.warnings.add("mutation"));
    }

    private static void fifo() {
        List<Tx> txs = list(
                tx(1, 0, Type.BUY, "10", "100", "10"),
                tx(2, 1, Type.BUY, "5", "200", "5"),
                tx(3, 2, Type.SELL, "12", "150", "12"),
                tx(4, 2, Type.DIVIDEND, "0", "30", "2"));
        Report report = report(txs, candles(2, "160"), D.plusDays(2));
        Position position = only(report);
        number("3", position.quantity);
        number("603", position.cost);
        number("376", position.realized);
        number("28", position.dividends);
        number("160", position.price);
        number("480", position.value);
        number("-123", position.unrealized);
        number("281", report.totalGain);
        number("603", report.cost);
        number("376", report.realized);
        number("28", report.dividends);
        txs.add(tx(5, 3, Type.SELL, "3", "250", "3"));
        Report sold = report(txs, NO_CANDLES, D.plusDays(3));
        number("0", only(sold).quantity);
        number("0", sold.cost);
        number("520", sold.realized);
        number("0", sold.value);
        number("0", sold.unrealized);
        number("548", sold.totalGain);
        isNull(only(sold).price);
        equal(0, sold.warnings.size());
        txs.add(tx(6, 4, Type.DIVIDEND, "0", "10", "1"));
        Report paidLater = report(txs, NO_CANDLES, D.plusDays(4));
        number("37", paidLater.dividends);
        number("557", paidLater.totalGain);
        txs.add(tx(7, 5, Type.BUY, "1", "7", "0"));
        Report reopened = report(txs, candles(5, "8"), D.plusDays(5));
        number("7", reopened.cost);
        number("520", reopened.realized);
        number("558", reopened.totalGain);
    }

    private static void decimals() {
        List<Tx> txs = list(tx(1, 0, Type.BUY, "0.3", "0.1", "0.01"),
                tx(2, 1, Type.SELL, "0.1", "0.2", "0.002"));
        Report partial = report(txs, candles(1, "0.2"), D.plusDays(1));
        number("0.2", only(partial).quantity);
        number("0.04", partial.value);
        number("0.018", partial.totalGain);
        check(partial.cost.precision() >= 30, "FIFO allocation retains decimal precision");
        txs.add(tx(3, 2, Type.SELL, "0.1", "0.2", "0.002"));
        txs.add(tx(4, 3, Type.SELL, "0.1", "0.2", "0.002"));
        Report closed = report(txs, NO_CANDLES, D.plusDays(3));
        number("0", closed.cost);
        number("0.014", closed.realized);
        number("0.014", closed.totalGain);
        String large = "1000000000000000000000000000000.0000000001";
        Report exact = report(list(tx(1, 0, Type.BUY, "1", large, "0.0000000002")),
                candles(0, "1000000000000000000000000000000.0000000005"), D);
        number("0.0000000002", exact.totalGain);
        List<Tx> rebateFree = list(tx(1, 0, Type.DIVIDEND, "0", "1", "2"));
        number("-1", report(rebateFree, NO_CANDLES, D).totalGain);
    }

    private static void cashFlows() {
        List<Tx> txs = list(tx(1, 0, Type.BUY, "10", "100", "10"),
                tx(2, 2, Type.BUY, "5", "120", "5"),
                tx(3, 3, Type.SELL, "4", "130", "4"),
                tx(4, 3, Type.DIVIDEND, "0", "20", "1"));
        List<Candle> prices = list(new Candle(A, D.plusDays(1), b("110")),
                new Candle(A, D.plusDays(4), b("140")));
        Report baseline = report(txs, prices, D.plusDays(1));
        Report end = report(txs, prices, D.plusDays(4));
        number("90", baseline.totalGain);
        number("112", end.realized);
        number("329", end.unrealized);
        number("460", end.totalGain);
        Period period = Portfolio.period(txs, NO_QUOTES, prices, D.plusDays(1), D.plusDays(4), false);
        number("370", period.amount);
        number(end.value.subtract(baseline.value).subtract(b("605")).add(b("516"))
                .add(b("19")).toPlainString(), period.amount);
        equal("", period.reason);
        number("0", Portfolio.period(txs, NO_QUOTES, prices,
                D.plusDays(1), D.plusDays(1), false).amount);
        List<Tx> buyOnly = list(tx(1, 1, Type.BUY, "100", "10", "0"));
        number("0", Portfolio.period(buyOnly, NO_QUOTES, candles(1, "10"),
                D, D.plusDays(1), false).amount);
        List<Tx> roundTrip = list(tx(1, 1, Type.BUY, "10", "10", "1"),
                tx(2, 2, Type.SELL, "10", "11", "2"));
        number("7", Portfolio.period(roundTrip, NO_QUOTES, NO_CANDLES,
                D, D.plusDays(2), false).amount);
        List<Tx> startCash = list(tx(1, 0, Type.DIVIDEND, "0", "100", "1"),
                tx(2, 1, Type.DIVIDEND, "0", "50", "1"));
        number("49", Portfolio.period(startCash, NO_QUOTES, NO_CANDLES,
                D, D.plusDays(1), false).amount);
        List<Tx> liquidated = list(tx(1, 0, Type.BUY, "10", "100", "0"),
                tx(2, 2, Type.SELL, "10", "120", "5"));
        number("95", Portfolio.period(liquidated, NO_QUOTES, candles(1, "110"),
                D.plusDays(1), D.plusDays(2), false).amount);
    }

    private static void openings() {
        List<Tx> txs = list(tx(1, 2, Type.OPENING, "10", "100", "0"),
                tx(2, 3, Type.SELL, "2", "120", "2"));
        List<Candle> prices = list(new Candle(A, D.plusDays(2), b("110")),
                new Candle(A, D.plusDays(3), b("125")));
        Report opening = report(txs, prices, D.plusDays(2));
        number("1000", opening.cost);
        number("100", opening.totalGain);
        number("800", report(txs, prices, D.plusDays(3)).cost);
        number("38", report(txs, prices, D.plusDays(3)).realized);
        Period allowed = Portfolio.period(txs, NO_QUOTES, prices,
                D.plusDays(2), D.plusDays(3), false);
        number("138", allowed.amount);
        number("0", Portfolio.period(txs, NO_QUOTES, prices,
                D.plusDays(2), D.plusDays(2), false).amount);
        Period blocked = Portfolio.period(txs, NO_QUOTES, prices, D, D.plusDays(3), false);
        isNull(blocked.amount);
        contains(blocked.reason, "OPENING");
        Report earlier = report(txs, prices, D);
        isNull(earlier.value);
        isNull(earlier.totalGain);
        contains(earlier.warnings, "predates OPENING");
        isNull(Portfolio.period(txs, NO_QUOTES, prices, D, D.plusDays(1), false).amount);
        List<Point> history = Portfolio.history(txs, NO_QUOTES, prices, D, D.plusDays(3));
        isNull(history.get(0).value);
        isNull(history.get(1).gain);
        number("100", history.get(2).gain);
        number("238", history.get(3).gain);
        List<Tx> zeroCost = list(tx(1, 0, Type.OPENING, "1", "0", "0"));
        number("5", report(zeroCost, candles(0, "5"), D).totalGain);
        List<Tx> mixed = list(tx(1, 0, Type.BUY, "1", "1", "0"),
                new Tx(2, D.plusDays(2), B, "B", "Beta", Type.OPENING, b("1"), b("1"), b("0")));
        isNull(report(mixed, candles(0, "1"), D).value);
        isNull(Portfolio.period(mixed, NO_QUOTES, candles(0, "1"), D, D.plusDays(2), false).amount);
    }

    private static void missingPrices() {
        List<Tx> txs = list(tx(1, 0, Type.BUY, "2", "10", "1"),
                new Tx(2, D, B, "B", "Beta", Type.BUY, b("1"), b("5"), b("0")));
        Report partial = report(txs, candles(0, "12"), D);
        number("26", partial.cost);
        isNull(partial.value);
        isNull(partial.unrealized);
        isNull(partial.totalGain);
        number("24", byKey(partial, A).value);
        isNull(byKey(partial, B).price);
        isNull(byKey(partial, B).value);
        contains(partial.warnings, "Missing price history");
        List<Tx> one = list(tx(1, 0, Type.BUY, "1", "10", "0"));
        Report futureCandle = report(one, candles(1, "99"), D);
        isNull(futureCandle.value);
        isNull(report(one, list(new Candle(A, D, null)), D).value);
        isNull(report(one, candles(0, "0"), D).value);
        Report noSynthetic = Portfolio.report(one,
                list(new Quote(A, b("100"), b("90"), 1, 1)), NO_CANDLES, D, true);
        isNull(noSynthetic.value);
        contains(noSynthetic.warnings, "quotes ignored");
        Period blocked = Portfolio.period(one, NO_QUOTES, candles(1, "20"),
                D, D.plusDays(1), false);
        isNull(blocked.amount);
        contains(blocked.reason, "Missing reliable price history");
        List<Candle> sameDuplicate = list(new Candle(A, D, b("12")), new Candle(A, D, b("12.0")));
        number("12", report(one, sameDuplicate, D).value);
    }

    private static void carry() {
        List<Tx> txs = list(tx(1, 0, Type.BUY, "10", "100", "0"));
        // 2024-01-05 Friday to Jan 7 Sunday, then an absent Monday session.
        List<Candle> prices = candles(4, "110");
        Report sunday = report(txs, prices, D.plusDays(6));
        number("1100", sunday.value);
        contains(sunday.warnings, "2 calendar days");
        contains(sunday.warnings, "holiday/weekend");
        number("1100", report(txs, prices, D.plusDays(7)).value);
        number("1100", report(txs, prices, D.plusDays(11)).value);
        Report eighth = report(txs, prices, D.plusDays(12));
        isNull(eighth.value);
        contains(eighth.warnings, "more than 7 days");
        List<Candle> unsorted = list(new Candle(A, D.plusDays(5), b("120")),
                new Candle(A, D.plusDays(4), b("110")), new Candle(A, D.plusDays(7), b("999")));
        number("1200", report(txs, unsorted, D.plusDays(6)).value);
        Period weekend = Portfolio.period(txs, NO_QUOTES, prices, D.plusDays(4), D.plusDays(6), false);
        number("0", weekend.amount);
        contains(weekend.reason, "Carried/stale close");
        List<Tx> newerLedger = list(tx(1, 5, Type.BUY, "1", "100", "0"));
        Report staleLedger = report(newerLedger, prices, D.plusDays(6));
        number("110", staleLedger.value);
        contains(staleLedger.warnings, "Carried close");
        contains(staleLedger.warnings, "predates latest transaction");
    }

    private static void splits() {
        List<Tx> txs = list(tx(1, 0, Type.BUY, "10", "100", "10"),
                tx(2, 1, Type.BUY, "5", "200", "5"),
                tx(3, 2, Type.SPLIT, "2", "0", "0"),
                tx(4, 3, Type.SELL, "22", "80", "2"));
        List<Candle> prices = list(new Candle(A, D, b("105")),
                new Candle(A, D.plusDays(2), b("70")), new Candle(A, D.plusDays(3), b("80")));
        Report splitDay = report(txs, prices, D.plusDays(2));
        number("30", only(splitDay).quantity);
        number("2015", splitDay.cost);
        number("2100", splitDay.value);
        number("85", splitDay.totalGain);
        contains(splitDay.warnings, "raw/adjusted");
        Report sold = report(txs, prices, D.plusDays(3));
        number("8", only(sold).quantity);
        number("804", sold.cost);
        number("547", sold.realized);
        number("640", sold.value);
        number("-164", sold.unrealized);
        number("383", sold.totalGain);
        Report before = report(txs, prices, D);
        isNull(before.value);
        isNull(before.totalGain);
        contains(before.warnings, "before latest split");
        Period crossing = Portfolio.period(txs, NO_QUOTES, prices, D, D.plusDays(3), false);
        isNull(crossing.amount);
        contains(crossing.reason, "spans SPLIT");
        number("298", Portfolio.period(txs, NO_QUOTES, prices,
                D.plusDays(2), D.plusDays(3), false).amount);
        isNull(report(txs, candles(1, "140"), D.plusDays(2)).value);
        List<Point> points = Portfolio.history(txs, NO_QUOTES, prices, D, D.plusDays(3));
        isNull(points.get(0).value);
        isNull(points.get(1).gain);
        number("2100", points.get(2).value);
        txs.add(tx(5, 4, Type.SPLIT, "0.25", "0", "0"));
        Report reverse = report(txs, candles(4, "400"), D.plusDays(4));
        number("2", only(reverse).quantity);
        number("804", reverse.cost);
        number("547", reverse.realized);
        number("543", reverse.totalGain);
        txs.add(tx(6, 5, Type.SELL, "2", "410", "1"));
        Report closed = report(txs, NO_CANDLES, D.plusDays(5));
        number("0", closed.cost);
        number("562", closed.realized);
        number("562", closed.totalGain);
        List<Point> all = Portfolio.history(txs, NO_QUOTES, candles(4, "400"), D, D.plusDays(5));
        for (int index = 0; index < 4; index++) {
            isNull(all.get(index).value);
        }
        number("800", all.get(4).value);
        number("562", all.get(5).gain);
        List<Tx> fractional = list(tx(1, 0, Type.BUY, "3", "10", "0"),
                tx(2, 1, Type.SPLIT, "0.5", "0", "0"));
        Report half = report(fractional, candles(1, "20"), D.plusDays(1));
        number("1.5", only(half).quantity);
        number("30", half.cost);
        number("0", half.totalGain);
    }

    private static void sorting() {
        Tx first = tx(1, 0, Type.BUY, "1", "10", "0");
        Tx second = tx(2, 0, Type.BUY, "1", "20", "0");
        Tx third = tx(3, 0, Type.SELL, "1", "30", "0");
        List<Tx> shuffled = list(third, second, first);
        Portfolio.validate(shuffled);
        equal(third, shuffled.get(0));
        Report sorted = report(shuffled, candles(0, "25"), D);
        number("20", sorted.cost);
        number("20", sorted.realized);
        number("25", sorted.totalGain);
        equal(third, shuffled.get(0));
        // Date dominates ID: a later sale with smaller ID is still valid.
        List<Tx> dates = list(tx(1, 1, Type.SELL, "1", "30", "0"),
                tx(2, 0, Type.BUY, "1", "10", "0"));
        number("20", report(dates, NO_CANDLES, D.plusDays(1)).totalGain);
        Report beforeSale = report(dates, candles(0, "15"), D);
        number("1", only(beforeSale).quantity);
        number("0", beforeSale.realized);
        number("5", beforeSale.totalGain);
        invalid(() -> Portfolio.validate(list(tx(1, 0, Type.SELL, "1", "10", "0"),
                tx(2, 0, Type.BUY, "1", "10", "0"))));
        Tx renamed = new Tx(4, D.plusDays(1), A, "NEW", "New name", Type.DIVIDEND,
                b("0"), b("1"), b("0"));
        shuffled.add(renamed);
        equal("A", only(report(shuffled, candles(0, "25"), D)).symbol);
        equal("New name", only(report(shuffled, candles(1, "25"), D.plusDays(1))).name);
        List<Tx> twoKeys = list(tx(1, 0, Type.BUY, "1", "1", "0"),
                new Tx(2, D, B, "B", "Beta", Type.BUY, b("1"), b("1"), b("0")));
        List<Candle> twoPrices = list(new Candle(A, D, b("2")), new Candle(B, D, b("3")));
        Report both = report(twoKeys, twoPrices, D);
        equal(B, both.positions.get(0).key);
        equal(A, both.positions.get(1).key);
        number("5", both.value);
        number("3", both.totalGain);
    }

    private static void invalidTransactions() {
        invalid(() -> Portfolio.validate(null));
        invalid(() -> Portfolio.validate(list((Tx) null)));
        Tx good = tx(1, 0, Type.BUY, "1", "10", "0");
        invalid(() -> Portfolio.validate(list(good, good)));
        assertInvalid(new Tx(-1, D, A, "A", "Alpha", Type.BUY, b("1"), b("1"), b("0")));
        Portfolio.validate(list(tx(0, 0, Type.BUY, "1", "10", "0")));
        for (LocalDate date : Arrays.asList(null, LocalDate.of(0, 1, 1), today().plusDays(1))) {
            assertInvalid(new Tx(1, date, A, "A", "Alpha", Type.BUY, b("1"), b("1"), b("0")));
        }
        String[] badKeys = {null, "", " ", "NSE_EQ|", "BSE_EQ|INE009A01021", "NSE_EQ|bad",
                "NSE_EQ|ine009a01021", "NSE_EQ|INE009A0102A", " NSE_EQ|INE009A01021"};
        for (String key : badKeys) {
            assertInvalid(new Tx(1, D, key, "A", "Alpha", Type.BUY, b("1"), b("1"), b("0")));
        }
        for (String text : Arrays.asList(null, "", "   ", "A\nB", "\u0000")) {
            assertInvalid(new Tx(1, D, A, text, "Alpha", Type.BUY, b("1"), b("1"), b("0")));
            assertInvalid(new Tx(1, D, A, "A", text, Type.BUY, b("1"), b("1"), b("0")));
        }
        assertInvalid(new Tx(1, D, A, "A", "Alpha", null, b("1"), b("1"), b("0")));
        for (BigDecimal bad : Arrays.asList(null, b("-1"))) {
            assertInvalid(new Tx(1, D, A, "A", "Alpha", Type.BUY, bad, b("1"), b("0")));
            assertInvalid(new Tx(1, D, A, "A", "Alpha", Type.BUY, b("1"), bad, b("0")));
            assertInvalid(new Tx(1, D, A, "A", "Alpha", Type.BUY, b("1"), b("1"), bad));
        }
        assertInvalid(tx(1, 0, Type.BUY, "0", "1", "0"));
        assertInvalid(tx(1, 0, Type.BUY, "1", "0", "0"));
        assertInvalid(tx(1, 0, Type.SELL, "1", "10", "0"));
        assertInvalid(tx(1, 0, Type.DIVIDEND, "1", "10", "0"));
        assertInvalid(tx(1, 0, Type.DIVIDEND, "0", "0", "0"));
        assertInvalid(tx(1, 0, Type.OPENING, "0", "1", "0"));
        assertInvalid(tx(1, 0, Type.OPENING, "1", "1", "1"));
        assertInvalid(tx(1, 0, Type.SPLIT, "2", "0", "0"));
        invalid(() -> Portfolio.validate(list(good, tx(2, 1, Type.SELL, "2", "10", "0"))));
        invalid(() -> Portfolio.validate(list(good, tx(2, 1, Type.SELL, "0", "10", "0"))));
        invalid(() -> Portfolio.validate(list(good, tx(2, 1, Type.SELL, "1", "0", "0"))));
        for (Tx bad : list(tx(2, 1, Type.SPLIT, "0", "0", "0"),
                tx(2, 1, Type.SPLIT, "2", "1", "0"), tx(2, 1, Type.SPLIT, "2", "0", "1"),
                tx(2, 1, Type.OPENING, "1", "1", "0"))) {
            invalid(() -> Portfolio.validate(list(good, bad)));
        }
        invalid(() -> Portfolio.validate(list(tx(1, 0, Type.OPENING, "1", "1", "0"),
                tx(2, 1, Type.OPENING, "1", "1", "0"))));
        invalid(() -> Portfolio.validate(list(good, tx(2, 1, Type.SELL, "1", "1", "0"),
                tx(3, 2, Type.OPENING, "1", "1", "0"))));
        invalid(() -> Portfolio.validate(list(good, tx(2, 1, Type.SELL, "1", "1", "0"),
                tx(3, 2, Type.SPLIT, "2", "0", "0"))));
        invalid(() -> Portfolio.validate(list(good, new Tx(2, D, B, "B", "Beta", Type.SELL,
                b("1"), b("1"), b("0")))));
        invalid(() -> Portfolio.validate(list(good, tx(2, 1, Type.SPLIT, "0.5", "0", "0"),
                tx(3, 2, Type.SELL, "1", "1", "0"))));
        // A malformed later transaction must not be hidden by an earlier snapshot date.
        invalid(() -> report(list(good, tx(2, 2, Type.SELL, "2", "1", "0")), candles(0, "1"), D));
    }

    private static void invalidMarketData() {
        List<Tx> empty = Collections.emptyList();
        invalid(() -> Portfolio.report(empty, null, NO_CANDLES, D, false));
        invalid(() -> Portfolio.report(empty, NO_QUOTES, null, D, false));
        invalid(() -> Portfolio.report(empty, list((Quote) null), NO_CANDLES, D, false));
        invalid(() -> report(empty, list((Candle) null), D));
        List<Candle> badCandles = list(new Candle("bad", D, b("1")),
                new Candle(A, null, b("1")), new Candle(A, D, b("-1")),
                new Candle(A, today().plusDays(1), b("1")));
        for (Candle bad : badCandles) {
            invalid(() -> report(empty, list(bad), D));
        }
        invalid(() -> report(empty, list(new Candle(A, D, b("1")), new Candle(A, D, b("2"))), D));
        List<Quote> badQuotes = list(new Quote("bad", b("1"), b("1"), 1, 1),
                new Quote(A, b("-1"), b("1"), 1, 1), new Quote(A, b("1"), b("-1"), 1, 1),
                new Quote(A, b("1"), b("1"), -1, 1), new Quote(A, b("1"), b("1"), 1, -1));
        for (Quote bad : badQuotes) {
            invalid(() -> Portfolio.report(empty, list(bad), NO_CANDLES, D, false));
        }
        invalid(() -> report(empty, NO_CANDLES, null));
        invalid(() -> report(empty, NO_CANDLES, today().plusDays(1)));
        invalid(() -> Portfolio.period(empty, NO_QUOTES, NO_CANDLES, D.plusDays(1), D, false));
        invalid(() -> Portfolio.history(empty, NO_QUOTES, NO_CANDLES, D.plusDays(1), D));
        invalid(() -> Portfolio.history(empty, NO_QUOTES, NO_CANDLES, null, D));
        invalid(() -> Portfolio.period(empty, NO_QUOTES, NO_CANDLES, D, null, false));
    }

    private static void history() {
        List<Tx> txs = list(tx(1, 1, Type.BUY, "2", "100", "2"),
                tx(2, 3, Type.SELL, "1", "120", "1"));
        List<Candle> prices = list(new Candle(A, D.plusDays(1), b("110")),
                new Candle(A, D.plusDays(2), b("115")), new Candle(A, D.plusDays(3), b("120")));
        List<Point> points = Portfolio.history(txs, NO_QUOTES, prices, D, D.plusDays(4));
        equal(5, points.size());
        equal(D, points.get(0).date);
        equal(D.plusDays(4), points.get(4).date);
        number("0", points.get(0).value);
        number("0", points.get(0).gain);
        number("220", points.get(1).value);
        number("18", points.get(1).gain);
        number("28", points.get(2).gain);
        number("120", points.get(3).value);
        number("37", points.get(3).gain);
        number("37", points.get(4).gain);
        unsupported(() -> points.add(new Point(D, null, null)));
        equal(1, Portfolio.history(txs, NO_QUOTES, prices, D, D).size());
        List<Point> missing = Portfolio.history(txs, NO_QUOTES, NO_CANDLES, D, D.plusDays(1));
        number("0", missing.get(0).value);
        isNull(missing.get(1).value);
        isNull(missing.get(1).gain);
        long now = System.currentTimeMillis();
        LocalDate today = today();
        List<Tx> recent = list(new Tx(1, today.minusDays(1), A, "A", "Alpha", Type.BUY,
                b("1"), b("100"), b("0")));
        List<Candle> recentPrices = list(new Candle(A, today.minusDays(1), b("110")),
                new Candle(A, today, b("120")));
        List<Quote> current = list(new Quote(A, b("130"), b("110"), now, now));
        List<Point> currentPoints = Portfolio.history(recent, current, recentPrices,
                today.minusDays(1), today);
        number("110", currentPoints.get(0).value);
        number("130", currentPoints.get(1).value);
        number("30", currentPoints.get(1).gain);
        List<Point> historical = Portfolio.history(recent, current, recentPrices,
                today.minusDays(1), today.minusDays(1));
        number("110", historical.get(0).value);
        number("20", Portfolio.period(recent, current, recentPrices,
                today.minusDays(1), today, true).amount);
        number("0", Portfolio.period(recent, current, NO_CANDLES, today, today, true).amount);
    }

    private static void quotes() {
        long now = System.currentTimeMillis();
        long day = 24L * 60L * 60L * 1000L;
        LocalDate today = today();
        List<Tx> txs = list(new Tx(1, D, A, "A", "Alpha", Type.BUY, b("2"), b("100"), b("0")));
        List<Quote> latest = list(new Quote(A, b("130"), null, now, now));
        Report live = Portfolio.report(txs, latest, NO_CANDLES, today, true);
        number("260", live.value);
        number("60", live.totalGain);
        equal(0, live.warnings.size());
        List<Candle> todayClose = list(new Candle(A, today, b("110")));
        number("220", Portfolio.report(txs, latest, todayClose, today, false).value);
        Report stale = Portfolio.report(txs,
                list(new Quote(A, b("125"), b("100"), now - 2 * day, now - 2 * day)),
                NO_CANDLES, today, true);
        number("250", stale.value);
        contains(stale.warnings, "market price is more than 24h old");
        contains(stale.warnings, "fetched more than 24h ago");
        contains(stale.warnings, "differs from valuation date");
        List<Tx> newerLedger = list(new Tx(1, today, A, "A", "Alpha", Type.BUY,
                b("1"), b("100"), b("0")));
        Report oldMarketNewLedger = Portfolio.report(newerLedger,
                list(new Quote(A, b("125"), null, now - 2 * day, now)), NO_CANDLES, today, true);
        number("125", oldMarketNewLedger.value);
        contains(oldMarketNewLedger.warnings, "Cached quote");
        contains(oldMarketNewLedger.warnings, "predates latest transaction");
        List<Tx> oldAndNew = list(txs.get(0), new Tx(2, today, A, "A", "Alpha", Type.BUY,
                b("1"), b("120"), b("1")));
        Period stalePeriod = Portfolio.period(oldAndNew,
                list(new Quote(A, b("125"), null, now - 2 * day, now)),
                list(new Candle(A, today.minusDays(1), b("110"))), today.minusDays(1), today, true);
        number("-11", stalePeriod.amount);
        contains(stalePeriod.reason, "predates latest transaction");
        contains(stalePeriod.reason, "more than 24h old");
        contains(stalePeriod.reason, "Using newer historical close");
        Report oldFetch = Portfolio.report(txs,
                list(new Quote(A, b("125"), b("100"), now, now - 2 * day)), NO_CANDLES, today, true);
        contains(oldFetch.warnings, "fetched more than 24h ago");
        Report tooOld = Portfolio.report(txs,
                list(new Quote(A, b("125"), b("100"), now - 8 * day, now)), NO_CANDLES, today, true);
        isNull(tooOld.value);
        contains(tooOld.warnings, "more than 7 days");
        Report unknown = Portfolio.report(txs, list(new Quote(A, b("125"), b("100"), 0, now)),
                NO_CANDLES, today, true);
        isNull(unknown.value);
        contains(unknown.warnings, "unknown market timestamp");
        Report unknownFetch = Portfolio.report(txs, list(new Quote(A, b("125"), null, now, 0)),
                NO_CANDLES, today, true);
        number("250", unknownFetch.value);
        contains(unknownFetch.warnings, "unknown fetch age");
        List<Quote> gaps = list(new Quote(A, null, b("999"), now, now));
        isNull(Portfolio.report(txs, gaps, NO_CANDLES, today, true).value);
        number("220", Portfolio.report(txs, gaps, todayClose, today, true).value);
        isNull(Portfolio.report(txs, list(new Quote(A, b("0"), b("999"), now, now)),
                NO_CANDLES, today, true).value);
        List<Quote> future = list(new Quote(A, b("999"), b("100"), now + day, now + day));
        Report futurePrice = Portfolio.report(txs, future, todayClose, today, true);
        number("220", futurePrice.value);
        contains(futurePrice.warnings, "Future-dated quote");
        List<Quote> ordered = list(new Quote(A, b("110"), null, now - day, now),
                new Quote(A, b("130"), null, now, now));
        number("260", Portfolio.report(txs, ordered, NO_CANDLES, today, true).value);
        List<Tx> two = list(txs.get(0), new Tx(2, D, B, "B", "Beta", Type.BUY,
                b("1"), b("10"), b("0")));
        List<Quote> differing = list(latest.get(0), new Quote(B, b("12"), null, now - 2 * day, now));
        Report mixed = Portfolio.report(two, differing, NO_CANDLES, today, true);
        number("272", mixed.value);
        contains(mixed.warnings, "differing quote dates");
        // UTC previous evening is already today's market date in Kolkata.
        long marketStart = today.atStartOfDay(MARKET).toInstant().toEpochMilli();
        Report midnight = Portfolio.report(txs,
                list(new Quote(A, b("130"), null, marketStart, now)), NO_CANDLES, today, true);
        number("260", midnight.value);
        check(!String.join(" ", midnight.warnings).contains("differs from valuation"), "Kolkata quote date");
        List<Tx> splitToday = list(txs.get(0), new Tx(2, today, A, "A", "Alpha",
                Type.SPLIT, b("2"), b("0"), b("0")));
        Report preSplitQuote = Portfolio.report(splitToday,
                list(new Quote(A, b("125"), null, now - 2 * day, now)), NO_CANDLES, today, true);
        isNull(preSplitQuote.value);
        contains(preSplitQuote.warnings, "predates SPLIT");
        number("260", Portfolio.report(splitToday,
                list(new Quote(A, b("65"), null, now, now)), NO_CANDLES, today, true).value);
    }

    private static void newestPriceSource() {
        long now = System.currentTimeMillis();
        long day = 24L * 60L * 60L * 1000L;
        LocalDate today = today();
        List<Tx> txs = list(tx(1, 0, Type.BUY, "2", "100", "0"));
        List<Quote> stale = list(new Quote(A, b("125"), null, now - 2 * day, now));
        List<Candle> yesterday = list(new Candle(A, today.minusDays(1), b("140")));
        Report newer = Portfolio.report(txs, stale, yesterday, today, true);
        number("140", only(newer).price);
        number("280", newer.value);
        number("80", newer.totalGain);
        contains(newer.warnings, "Using newer historical close");
        contains(newer.warnings, "instead of cached quote dated " + today.minusDays(2));
        contains(newer.warnings, "Carried/stale close");
        contains(newer.warnings, "more than 24h old");
        check(!String.join(" ", newer.warnings).contains("No usable current quote"),
                "usable older quote overridden, not missing");
        // A candle fetched more recently still loses to an eligible same-day quote.
        List<Candle> sameDate = list(new Candle(A, today.minusDays(2), b("140")));
        Report tied = Portfolio.report(txs, stale, sameDate, today, true);
        number("250", tied.value);
        check(!String.join(" ", tied.warnings).contains("Using newer historical close"), "same-date quote wins");
        Report currentTie = Portfolio.report(txs, list(new Quote(A, b("150"), null, now, now)),
                list(new Candle(A, today, b("140"))), today, true);
        number("300", currentTie.value);
        // Market timestamp, not fetchedMillis or input order, controls recency.
        List<Quote> unsorted = list(stale.get(0), new Quote(A, b("150"), null, now - day, now - day));
        number("300", Portfolio.report(txs, unsorted, sameDate, today, true).value);
        number("250", Portfolio.report(txs, stale,
                list(new Candle(A, today.minusDays(1), null), new Candle(A, today, b("0"))), today, true).value);
        number("250", Portfolio.report(txs, stale,
                list(new Candle(B, today.minusDays(1), b("999"))), today, true).value);
        // Historical mode still forbids using any quote, even if it would be newer.
        number("280", Portfolio.report(txs, stale, sameDate, today, false).value);
        number("280", Portfolio.report(txs, stale, sameDate, today.minusDays(2), true).value);
        List<Point> points = Portfolio.history(txs, stale, yesterday, today.minusDays(1), today);
        number("280", points.get(0).value);
        number("280", points.get(1).value);
        number("80", points.get(1).gain);
        Period noFalseDrop = Portfolio.period(txs, stale, yesterday, today.minusDays(1), today, true);
        number("0", noFalseDrop.amount);
        contains(noFalseDrop.reason, "Using newer historical close");
        // Only selected quote dates count toward the cross-holding quote-date warning.
        List<Tx> twoKeys = list(txs.get(0), new Tx(2, D, B, "B", "Beta", Type.BUY,
                b("1"), b("10"), b("0")));
        Report mixed = Portfolio.report(twoKeys,
                list(stale.get(0), new Quote(B, b("12"), null, now, now)), yesterday, today, true);
        number("292", mixed.value);
        check(!String.join(" ", mixed.warnings).contains("differing quote dates"),
                "unselected old quote date excluded");
        // Latest split filtering is applied before source selection.
        List<Tx> split = list(txs.get(0), new Tx(2, today.minusDays(1), A, "A", "Alpha",
                Type.SPLIT, b("2"), b("0"), b("0")));
        number("560", Portfolio.report(split, stale, yesterday, today, true).value);
        List<Quote> invalidNewer = list(new Quote(A, b("999"), null, now + day, now + day), stale.get(0));
        number("280", Portfolio.report(txs, invalidNewer, yesterday, today, true).value);
    }

    private static void missingSessionBaseline() {
        LocalDate today = today(), yesterday = today.minusDays(1);
        long now = System.currentTimeMillis();
        long session = yesterday.atTime(15, 30).atZone(MARKET).toInstant().toEpochMilli();
        List<Tx> txs = list(new Tx(1, today.minusDays(10), A, "A", "Alpha",
                Type.BUY, b("2"), b("100"), b("0")));
        List<Quote> quotes = list(new Quote(A, b("120"), b("110"), session, now));
        List<Candle> incomplete = list(new Candle(A, today.minusDays(2), b("110")));
        // A last-trade quote is not a completed close and must not fill the baseline.
        Period blocked = Portfolio.period(txs, quotes, incomplete, yesterday, today, true);
        isNull(blocked.amount);
        contains(blocked.reason, "Missing completed-session close");
        contains(blocked.reason, yesterday.toString());
        // Current valuation stays usable, while historical mode still ignores quotes.
        number("240", Portfolio.report(txs, quotes, incomplete, today, true).value);
        number("0", Portfolio.period(txs, quotes, incomplete, yesterday, today, false).amount);
        number("0", Portfolio.period(txs, quotes, incomplete, today, today, true).amount);
        List<Candle> complete = list(incomplete.get(0), new Candle(A, yesterday, b("120")));
        number("0", Portfolio.period(txs, quotes, complete, yesterday, today, true).amount);
        number("20", Portfolio.period(txs, quotes, incomplete, today.minusDays(2), today, true).amount);
        // Unknown/future/zero quotes cannot establish evidence of a completed session.
        List<Quote> invalid = list(new Quote(A, b("120"), null, 0, now),
                new Quote(A, b("120"), null, now + 86_400_000L, now),
                new Quote(A, b("120"), null, session, now + 86_400_000L),
                new Quote(A, b("0"), null, session, now));
        number("0", Portfolio.period(txs, invalid, incomplete, yesterday, today, true).amount);
        // Do not demand a price for positions not yet owned at the baseline.
        List<Tx> newBuy = list(new Tx(1, today, A, "A", "Alpha", Type.BUY, b("1"), b("100"), b("0")));
        number("20", Portfolio.period(newBuy, quotes, incomplete, yesterday, today, true).amount);
        // A sale today still requires a reliable price for yesterday's opening holdings.
        List<Tx> sold = list(txs.get(0), new Tx(2, today, A, "A", "Alpha",
                Type.SELL, b("2"), b("125"), b("0")));
        isNull(Portfolio.period(sold, quotes, incomplete, yesterday, today, true).amount);
        number("10", Portfolio.period(sold, quotes, complete, yesterday, today, true).amount);
    }

    private static void numericBounds() {
        BigDecimal largest = new BigDecimal("9999999999999999999999999999999999999999999999999999999999999999");
        BigDecimal smallest = new BigDecimal("1E-64");
        equal(64, largest.precision());
        List<Tx> large = list(new Tx(1, D, A, "A", "Alpha", Type.BUY, largest, largest, largest));
        Portfolio.validate(large);
        Report largeReport = report(large, list(new Candle(A, D, largest)), D);
        number(largest.multiply(largest).toPlainString(), largeReport.value);
        number(largest.negate().toPlainString(), largeReport.totalGain);
        List<Tx> tiny = list(new Tx(1, D, A, "A", "Alpha", Type.BUY, smallest, smallest, b("0")));
        number("0", report(tiny, list(new Candle(A, D, smallest)), D).totalGain);
        Portfolio.validate(list(new Tx(1, D, A, "A", "Alpha", Type.BUY,
                new BigDecimal("1E63"), b("1"), b("0"))));
        assertions++;
        BigDecimal[] invalidValues = {new BigDecimal("1E64"), new BigDecimal("1E-65"),
                new BigDecimal("1E+2147483647"), new BigDecimal("1E-2147483647"),
                largest.multiply(b("10")), new BigDecimal("0E-2147483647")};
        for (BigDecimal value : invalidValues) {
            assertInvalid(new Tx(1, D, A, "A", "Alpha", Type.BUY, value, b("1"), b("0")));
            assertInvalid(new Tx(1, D, A, "A", "Alpha", Type.BUY, b("1"), value, b("0")));
            assertInvalid(new Tx(1, D, A, "A", "Alpha", Type.BUY, b("1"), b("1"), value));
            invalid(() -> report(Collections.emptyList(), list(new Candle(A, D, value)), D));
            invalid(() -> Portfolio.report(Collections.emptyList(),
                    list(new Quote(A, value, null, 1, 1)), NO_CANDLES, D, false));
            invalid(() -> Portfolio.report(Collections.emptyList(),
                    list(new Quote(A, b("1"), value, 1, 1)), NO_CANDLES, D, false));
        }
        // Preserve valid older imports; only daily chart expansion gets a span limit.
        LocalDate old = LocalDate.of(1800, 1, 1);
        List<Tx> oldLedger = list(new Tx(1, old, A, "A", "Alpha", Type.BUY, b("1"), b("10"), b("0")));
        Portfolio.validate(oldLedger);
        number("2", report(oldLedger, list(new Candle(A, old, b("12"))), old).totalGain);
        invalid(() -> Portfolio.history(Collections.emptyList(), NO_QUOTES, NO_CANDLES,
                old, old.plusYears(100).plusDays(1)));
        invalid(() -> Portfolio.history(Collections.emptyList(), NO_QUOTES, NO_CANDLES,
                LocalDate.of(1, 1, 1), today()));
        List<Point> century = Portfolio.history(Collections.emptyList(), NO_QUOTES, NO_CANDLES,
                old, old.plusYears(100));
        equal(old, century.get(0).date);
        equal(old.plusYears(100), century.get(century.size() - 1).date);
        number("0", century.get(century.size() - 1).gain);
        // Valid individual split multipliers must not produce unbounded accumulated decimals.
        List<Tx> growth = list(tx(1, 0, Type.BUY, "1", "1", "0"));
        List<Tx> shrinkage = list(tx(1, 0, Type.BUY, "1", "1", "0"));
        for (int index = 1; index <= 66; index++) {
            growth.add(tx(index + 1, index, Type.SPLIT, "1E63", "0", "0"));
            shrinkage.add(tx(index + 1, index, Type.SPLIT, "1E-64", "0", "0"));
        }
        invalid(() -> Portfolio.validate(growth));
        invalid(() -> Portfolio.validate(shrinkage));
    }

    private static void conservation() {
        Random random = new Random(470021);
        for (int seed = 0; seed < 25; seed++) {
            List<Tx> txs = new ArrayList<>();
            List<Candle> prices = new ArrayList<>();
            BigDecimal buys = b("0");
            BigDecimal proceeds = b("0");
            BigDecimal dividends = b("0");
            int units = 0;
            for (int index = 0; index < 35; index++) {
                int choice = units == 0 ? 0 : random.nextInt(3);
                BigDecimal price = BigDecimal.valueOf(random.nextInt(20000) + 1, 2);
                BigDecimal fees = BigDecimal.valueOf(random.nextInt(100), 2);
                int qty = choice == 1 ? random.nextInt(units) + 1 : random.nextInt(9) + 1;
                Type type = choice == 0 ? Type.BUY : choice == 1 ? Type.SELL : Type.DIVIDEND;
                BigDecimal quantity = type == Type.DIVIDEND ? b("0") : BigDecimal.valueOf(qty, 1);
                txs.add(new Tx(index, D.plusDays(index), A, "A", "Alpha", type, quantity, price, fees));
                if (type == Type.BUY) {
                    units += qty;
                    buys = buys.add(quantity.multiply(price)).add(fees);
                } else if (type == Type.SELL) {
                    units -= qty;
                    proceeds = proceeds.add(quantity.multiply(price)).subtract(fees);
                } else {
                    dividends = dividends.add(price).subtract(fees);
                }
                BigDecimal close = BigDecimal.valueOf(random.nextInt(20000) + 1, 2);
                prices.add(new Candle(A, D.plusDays(index), close));
                Report report = report(txs, prices, D.plusDays(index));
                BigDecimal value = BigDecimal.valueOf(units, 1).multiply(close);
                number(value.toPlainString(), report.value);
                number(value.subtract(buys).add(proceeds).add(dividends).toPlainString(), report.totalGain);
                number(report.unrealized.add(report.realized).add(report.dividends).toPlainString(), report.totalGain);
                check(report.cost.signum() >= 0, "remaining cost nonnegative");
            }
            Report baseline = report(txs, prices, D.plusDays(4));
            Report end = report(txs, prices, D.plusDays(34));
            number(end.totalGain.subtract(baseline.totalGain).toPlainString(),
                    Portfolio.period(txs, NO_QUOTES, prices, D.plusDays(4), D.plusDays(34), false).amount);
            Collections.shuffle(txs, random);
            number(end.totalGain.toPlainString(), report(txs, prices, D.plusDays(34)).totalGain);
        }
    }

    private static Tx tx(long id, int day, Type type, String quantity, String price, String fees) {
        return new Tx(id, D.plusDays(day), A, "A", "Alpha", type, b(quantity), b(price), b(fees));
    }

    private static BigDecimal b(String text) {
        return new BigDecimal(text);
    }

    private static LocalDate today() {
        return LocalDate.now(MARKET);
    }

    private static List<Candle> candles(int day, String price) {
        return list(new Candle(A, D.plusDays(day), b(price)));
    }

    private static Report report(List<Tx> transactions, List<Candle> candles, LocalDate date) {
        return Portfolio.report(transactions, NO_QUOTES, candles, date, false);
    }

    @SafeVarargs
    private static <T> List<T> list(T... values) {
        List<T> result = new ArrayList<>();
        for (T value : values) {
            result.add(value);
        }
        return result;
    }

    private static Position only(Report report) {
        equal(1, report.positions.size());
        return report.positions.get(0);
    }

    private static Position byKey(Report report, String key) {
        for (Position position : report.positions) {
            if (position.key.equals(key)) {
                return position;
            }
        }
        throw new AssertionError("Missing position " + key);
    }

    private static void run(String label, Runnable test) {
        test.run();
        groups++;
        System.out.println("PASS " + label);
    }

    private static void check(boolean condition, String message) {
        assertions++;
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void equal(Object expected, Object actual) {
        check(expected == null ? actual == null : expected.equals(actual),
                "Expected " + expected + ", got " + actual);
    }

    private static void number(String expected, BigDecimal actual) {
        check(actual != null && b(expected).compareTo(actual) == 0,
                "Expected decimal " + expected + ", got " + actual);
    }

    private static void isNull(Object actual) {
        check(actual == null, "Expected null, got " + actual);
    }

    private static void contains(String text, String wanted) {
        check(text != null && text.contains(wanted), "Expected '" + wanted + "' in " + text);
    }

    private static void contains(List<String> messages, String wanted) {
        contains(String.join(" ", messages), wanted);
    }

    private static void assertInvalid(Tx transaction) {
        invalid(() -> Portfolio.validate(list(transaction)));
    }

    private static void invalid(Runnable action) {
        assertions++;
        try {
            action.run();
        } catch (IllegalArgumentException expected) {
            check(expected.getMessage() != null && !expected.getMessage().isEmpty(), "validation explains failure");
            return;
        }
        throw new AssertionError("Expected IllegalArgumentException");
    }

    private static void unsupported(Runnable action) {
        assertions++;
        try {
            action.run();
        } catch (UnsupportedOperationException expected) {
            return;
        }
        throw new AssertionError("Expected immutable collection");
    }
}