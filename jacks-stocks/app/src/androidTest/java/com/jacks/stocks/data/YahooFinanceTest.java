package com.jacks.stocks.data;

import android.test.InstrumentationTestCase;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

/** Offline platform JSON/CSV fixtures. No automatic network requests or real user data. */
@SuppressWarnings("deprecation")
public final class YahooFinanceTest extends InstrumentationTestCase {
    private static final String KEY = "NSE_EQ|INE002A01018";
    private static final String TICKER = "RELIANCE.NS";
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final LocalDate FROM = LocalDate.of(2026, 10, 1);
    private static final LocalDate TO = LocalDate.of(2026, 10, 7);
    private static final long FETCHED = Instant.parse("2026-10-08T06:00:00Z").toEpochMilli();
    private static final String CSV_HEADER = "SYMBOL,NAME OF COMPANY, SERIES, DATE OF LISTING, PAID UP VALUE, MARKET LOT, ISIN NUMBER, FACE VALUE\r\n";

    public void testMetaQuoteSurvivesHistoryEndingYesterdayAndUsesExactNumbers() throws Exception {
        long marketTime = seconds("2026-10-08T10:30:00+05:30");
        YahooFinanceClient.Result r = parse(payload(meta(marketTime, "1207.1234567890123456789"),
                timestamps("2026-10-06", "2026-10-07"), "[1190.1234567890123456789,1200.2]", ""));
        assertNotNull(r.quote);
        assertEquals(KEY, r.quote.key);
        assertEquals("1207.1234567890123456789", r.quote.price.toPlainString());
        assertEquals("1200.2", r.quote.previousClose.toPlainString());
        assertEquals(marketTime * 1000, r.quote.asOfMillis);
        assertEquals(FETCHED, r.quote.fetchedMillis);
        assertEquals("1190.1234567890123456789", r.candles.get(0).close.toPlainString());
        assertNull(r.latestSplit);
    }

    public void testRangeStartCloseAndAdjustedValuesAreNeverPreviousClose() throws Exception {
        String json = payload(meta(seconds("2026-10-07T15:30:00+05:30"), "1207.7"),
                timestamps("2026-10-06", "2026-10-07"), "[1100,1200]", "")
                .replace("\"regularMarketPrice\"", "\"chartPreviousClose\":7,\"previousClose\":8,\"regularMarketPrice\"")
                .replace("\"indicators\":{", "\"indicators\":{\"adjclose\":[{\"adjclose\":[1,2]}],");
        YahooFinanceClient.Result r = parse(json);
        assertEquals(new BigDecimal("1100"), r.quote.previousClose);
        assertEquals(new BigDecimal("1200"), r.candles.get(1).close);
        assertTrue(hasWarning(r, "earlier provider market timestamp"));
        YahooFinanceClient.Result noPrior = parse(payload(meta(seconds("2026-10-01T15:30:00+05:30"), "1207.7"),
                timestamps("2026-10-01"), "[99]", ""));
        assertNull(noPrior.quote.previousClose);
    }

    public void testPriorCloseUsesIndiaDateNotUtcDate() throws Exception {
        YahooFinanceClient.Result r = parse(payload(meta(seconds("2026-10-07T20:00:00Z"), "1207"),
                timestamps("2026-10-06", "2026-10-07"), "[1100,1200]", ""));
        assertEquals(new BigDecimal("1200"), r.quote.previousClose);
    }

    public void testNullAndNestedArrayElementsNeverShiftDates() throws Exception {
        String times = "[" + daily("2026-10-01") + "," + daily("2026-10-02") + ",null,"
                + daily("2026-10-05") + "," + daily("2026-10-06") + "," + daily("2026-10-07") + "]";
        YahooFinanceClient.Result r = parse(payload(meta(seconds("2026-10-08T10:30:00+05:30"), "1207"),
                times, "[101,null,999,[77],106,null]", ""));
        assertEquals(2, r.candles.size());
        assertEquals(LocalDate.of(2026, 10, 1), r.candles.get(0).date);
        assertEquals(LocalDate.of(2026, 10, 6), r.candles.get(1).date);
        assertEquals(new BigDecimal("106"), r.quote.previousClose);
        assertTrue(hasWarning(r, "without shifting dates"));
    }

    public void testUnequalArraysMissingArraysAndNonpositiveClose() throws Exception {
        YahooFinanceClient.Result r = parse(payload(meta(seconds("2026-10-08T10:30:00+05:30"), "1207"),
                timestamps("2026-10-01", "2026-10-02", "2026-10-05"), "[0,-5]", ""));
        assertTrue(r.candles.isEmpty()); assertNull(r.quote.previousClose);
        assertTrue(hasWarning(r, "array lengths differ"));
        for (String indicator : new String[]{"null", "{}", "{\"quote\":null}", "{\"quote\":[null]}", "{\"quote\":[{}]}"}) {
            YahooFinanceClient.Result missing = parse("{\"chart\":{\"result\":[{\"meta\":"
                    + meta(seconds("2026-10-08T10:30:00+05:30"), "1207") + ",\"timestamp\":null,\"indicators\":"
                    + indicator + "}],\"error\":null}}");
            assertNotNull(missing.quote); assertNull(missing.quote.previousClose); assertTrue(missing.candles.isEmpty());
        }
    }

    public void testMissingInvalidOrFutureMarketTimeDoesNotUseFetchTime() throws Exception {
        String good = payload(meta(seconds("2026-10-08T10:30:00+05:30"), "1207"), timestamps("2026-10-07"), "[1200]", "");
        for (String value : new String[]{"null", "0", "-1", "999999999999999999", "[]", "1e1000"}) {
            String json = good.replaceAll("\"regularMarketTime\":[0-9]+", "\"regularMarketTime\":" + value);
            YahooFinanceClient.Result r = parse(json);
            assertNull(r.quote); assertEquals(1, r.candles.size());
        }
        assertNull(parse(good.replaceAll("\"regularMarketTime\":[0-9]+,", "")).quote);
        assertNull(parse(good.replace("\"regularMarketPrice\":1207", "\"regularMarketPrice\":null")).quote);
        assertNull(parse(good.replace("\"regularMarketPrice\":1207", "\"regularMarketPrice\":0")).quote);
        assertNull(parse(payload(meta(FETCHED / 1000 + 1, "1207"), "[]", "[]", "")).quote);
    }

    public void testMismatchedMetadataRejectedAndFullExchangeAlternativeAccepted() throws Exception {
        String good = payload(meta(seconds("2026-10-08T10:30:00+05:30"), "1207"), "[]", "[]", "");
        for (String[] change : new String[][]{{TICKER, "INFY.NS"}, {"INR", "USD"}, {"NSI", "BSE"}, {"EQUITY", "ETF"}})
            expectIo(() -> parse(good.replace(change[0], change[1])));
        assertNotNull(parse(good.replace("\"exchangeName\":\"NSI\"", "\"fullExchangeName\":\"NSE\"")).quote);
        expectIo(() -> parse(good.replace("\"currency\":\"INR\",", "")));
    }

    public void testSymbolsAndUrlEncodingAreNseOnly() throws Exception {
        assertEquals("RELIANCE.NS", YahooFinanceClient.ticker(" reliance "));
        assertEquals("M&M.NS", YahooFinanceClient.ticker("m&m.ns"));
        assertEquals("BAJAJ-AUTO.NS", YahooFinanceClient.ticker("bajaj-auto"));
        for (String invalid : new String[]{"", "RELIANCE.BO", "NSE:INFY", "A/B", "^NSEI", "INFY?X", "A B", "A\nB", "A%26B", "-INFY", "INFY.NS.NS"}) {
            try { YahooFinanceClient.ticker(invalid); fail("Unsafe ticker accepted"); }
            catch (IllegalArgumentException expected) { /* Expected. */ }
        }
        try { YahooFinanceClient.ticker(null); fail("Missing ticker accepted"); }
        catch (IllegalArgumentException expected) { /* Expected. */ }
        String url = YahooFinanceClient.chartUrl("M&M.NS", FROM, TO);
        assertTrue(url.startsWith("https://query1.finance.yahoo.com/v8/finance/chart/M%26M.NS?"));
        assertTrue(url.contains("period1=" + FROM.atStartOfDay(IST).toEpochSecond()));
        assertTrue(url.contains("period2=" + TO.plusDays(1).atStartOfDay(IST).toEpochSecond()));
        assertTrue(url.endsWith("interval=1d&events=splits&includeAdjustedClose=false"));
    }

    public void testSplitEventsSuppressPreSplitClosesAndReportLatest() throws Exception {
        String splits = ",\"events\":{\"splits\":{\"first\":{\"date\":" + daily("2026-10-02")
                + ",\"numerator\":2,\"denominator\":1},\"second\":{\"date\":" + daily("2026-10-06")
                + ",\"splitRatio\":\"1:5\"}},\"dividends\":{\"x\":{\"amount\":999}}}";
        YahooFinanceClient.Result r = parse(payload(meta(seconds("2026-10-08T10:30:00+05:30"), "1207"),
                timestamps("2026-10-01", "2026-10-02", "2026-10-05", "2026-10-06", "2026-10-07"),
                "[100,50,60,300,310]", splits));
        assertEquals(LocalDate.of(2026, 10, 6), r.latestSplit);
        assertEquals(2, r.candles.size()); assertEquals(LocalDate.of(2026, 10, 6), r.candles.get(0).date);
        assertEquals(new BigDecimal("310"), r.quote.previousClose);
        assertTrue(hasWarning(r, "split-adjusted history is uncertain"));
        assertTrue(hasWarning(r, "ledger has not been changed"));
    }

    public void testSplitSameDayAsQuoteDoesNotUseSuppressedPriorClose() throws Exception {
        String event = ",\"events\":{\"splits\":{\"" + daily("2026-10-07") + "\":{\"splitRatio\":\"2:1\"}}}";
        YahooFinanceClient.Result r = parse(payload(meta(seconds("2026-10-07T15:30:00+05:30"), "1207"),
                timestamps("2026-10-06", "2026-10-07"), "[2000,1000]", event));
        assertEquals(LocalDate.of(2026, 10, 7), r.latestSplit);
        assertNull(r.quote.previousClose); assertEquals(1, r.candles.size());
    }

    public void testUnusableSplitEventsWithholdHistory() throws Exception {
        String json = payload(meta(seconds("2026-10-08T10:30:00+05:30"), "1207"), timestamps("2026-10-07"), "[100]",
                ",\"events\":{\"splits\":{\"x\":{\"date\":null,\"splitRatio\":\"0:1\"}}}");
        YahooFinanceClient.Result r = parse(json);
        assertNull(r.latestSplit); assertTrue(r.candles.isEmpty()); assertNull(r.quote.previousClose);
        assertTrue(hasWarning(r, "Unusable provider split"));
    }

    public void testTodaySplitRequestNeverReturnsUnfinishedCandleOrAdjustedPriorClose() throws Exception {
        String event = ",\"events\":{\"splits\":{\"" + daily("2026-10-08") + "\":{\"splitRatio\":\"2:1\"}}}";
        String json = payload(meta(seconds("2026-10-08T10:30:00+05:30"), "600"),
                timestamps("2026-10-06", "2026-10-07", "2026-10-08", "2026-10-09"), "[580,590,600,610]", event);
        YahooFinanceClient.Result r = YahooFinanceClient.parse(json, KEY, TICKER, FROM, TO.plusDays(1), FETCHED);
        assertEquals(LocalDate.of(2026, 10, 8), r.latestSplit);
        assertTrue(r.candles.isEmpty()); assertNull(r.quote.previousClose);
        assertEquals(new BigDecimal("600"), r.quote.price);
        YahooFinanceClient.Result noSplit = YahooFinanceClient.parse(json.replace(event, ""), KEY, TICKER, FROM, TO.plusDays(1), FETCHED);
        assertEquals(2, noSplit.candles.size());
        assertEquals(LocalDate.of(2026, 10, 7), noSplit.candles.get(1).date);
    }

    public void testHistoryWindowFirstTradeAndDuplicateConflicts() throws Exception {
        String json = payload(meta(seconds("2026-10-08T10:30:00+05:30"), "1207"),
                timestamps("2026-09-30", "2026-10-01", "2026-10-05", "2026-10-05", "2026-10-06", "2026-10-08", "2026-10-09"),
                "[90,100,105,106,110,115,120]", "");
        YahooFinanceClient.Result r = parse(json);
        assertEquals(2, r.candles.size()); assertTrue(hasWarning(r, "Conflicting daily closes"));
        YahooFinanceClient.Result listed = parse(json.replace("\"regularMarketPrice\"", "\"firstTradeDate\":" + daily("2026-10-06") + ",\"regularMarketPrice\""));
        assertEquals(1, listed.candles.size()); assertEquals(LocalDate.of(2026, 10, 6), listed.candles.get(0).date);
        assertTrue(hasWarning(listed, "first trading date"));
    }

    public void testErrorsNullResultsInvalidJsonAndDuplicatesAreSanitized() throws Exception {
        for (String json : new String[]{"null", "[]", "{}", "{\"chart\":null}", "{\"chart\":{\"result\":null,\"error\":null}}",
                "{\"chart\":{\"result\":[],\"error\":null}}", "{\"chart\":{\"result\":[null],\"error\":null}}",
                "{\"chart\":{\"result\":null,\"error\":{\"description\":\"private-provider-detail\"}}}",
                "{\"chart\":{private-provider-detail", "{\"chart\":{},\"chart\":{}}"}) {
            IOException e = expectIo(() -> parse(json));
            assertFalse(e.getMessage().contains("private-provider-detail")); assertNull(e.getCause());
        }
        String good = payload(meta(seconds("2026-10-08T10:30:00+05:30"), "1207"), "[]", "[]", "");
        expectIo(() -> parse(good + "{}"));
        expectIo(() -> parse(good.replace("\"currency\":\"INR\"", "\"currency\":\"INR\",\"currency\":\"INR\"")));
        expectIo(() -> parse(good.replace("\"error\":null", "\"error\":{\"description\":\"private-provider-detail\"}")));
    }

    public void testResultCollectionsAreImmutable() throws Exception {
        YahooFinanceClient.Result r = parse(payload(meta(seconds("2026-10-07T15:30:00+05:30"), "1207"),
                timestamps("2026-10-07"), "[100]", ""));
        try { r.candles.clear(); fail("Mutable candles"); } catch (UnsupportedOperationException expected) { /* Expected. */ }
        try { r.warnings.clear(); fail("Mutable warnings"); } catch (UnsupportedOperationException expected) { /* Expected. */ }
    }

    public void testDateAndSizeLimitsRejectInvalidRequestsBeforeNetwork() throws Exception {
        String good = payload(meta(seconds("2026-10-07T15:30:00+05:30"), "1207"), "[]", "[]", "");
        expectIo(() -> YahooFinanceClient.parse(good, KEY, TICKER, TO, FROM, FETCHED));
        expectIo(() -> YahooFinanceClient.parse(good, KEY, TICKER, LocalDate.of(1900, 1, 1), TO, FETCHED));
        expectIo(() -> YahooFinanceClient.parse(good, KEY, TICKER, FROM, TO.plusDays(2), FETCHED));
        expectIo(() -> YahooFinanceClient.parse(good, "BSE_EQ|INE002A01018", TICKER, FROM, TO, FETCHED));
        expectIo(() -> YahooFinanceClient.parse(good, KEY, "RELIANCE.BO", FROM, TO, FETCHED));
        expectIo(() -> YahooFinanceClient.checkTextSize("\uD800", 100));
        expectIo(() -> YahooFinanceClient.checkTextSize("\u20AC\u20AC", 5));
        YahooFinanceClient.checkTextSize("\uD83D\uDE00", 4);
        expectIo(() -> YahooFinanceClient.checkTextSize("\uD83D\uDE00", 3));
    }

    public void testRetryAfterSecondsAndHttpDateAreBounded() {
        assertEquals(60L, YahooFinanceClient.retryAfterSeconds(null, FETCHED));
        assertEquals(1L, YahooFinanceClient.retryAfterSeconds("0", FETCHED));
        assertEquals(90L, YahooFinanceClient.retryAfterSeconds("90", FETCHED));
        assertEquals(3600L, YahooFinanceClient.retryAfterSeconds("9999999999999999999999", FETCHED));
        assertEquals(120L, YahooFinanceClient.retryAfterSeconds("Thu, 8 Oct 2026 06:02:00 GMT", FETCHED));
        assertEquals(1L, YahooFinanceClient.retryAfterSeconds("Thu, 8 Oct 2026 05:59:00 GMT", FETCHED));
        assertEquals(3600L, YahooFinanceClient.retryAfterSeconds("Fri, 9 Oct 2026 06:00:00 GMT", FETCHED));
        assertEquals(60L, YahooFinanceClient.retryAfterSeconds("private-header-detail", FETCHED));
    }

    public void testNseCatalogueOfficialHeadersKnownKeysAndEqOnly() throws Exception {
        String csv = "\uFEFF" + CSV_HEADER
                + "INFY,Infosys Limited, EQ,08-FEB-1995,5,1, INE009A01021,5\r\n"
                + "RELIANCE,Reliance Industries Limited,EQ,29-NOV-1995,10,1,INE002A01018,10\r\n"
                + "TCS,Tata Consultancy Services Limited,EQ,25-AUG-2004,1,1,INE467B01029,1\r\n"
                + "IGNORED,Not an equity,BE,01-JAN-2000,1,1,invalid,1\r\n";
        List<Store.Instrument> instruments = NseCatalogue.parse(csv);
        assertEquals(3, instruments.size());
        assertEquals("NSE_EQ|INE009A01021", instruments.get(0).key);
        assertEquals("NSE_EQ|INE002A01018", instruments.get(1).key);
        assertEquals("NSE_EQ|INE467B01029", instruments.get(2).key);
        try { instruments.clear(); fail("Mutable catalogue"); } catch (UnsupportedOperationException expected) { /* Expected. */ }
    }

    public void testNseCatalogueQuotedCommaEscapedQuoteAndMultilineName() throws Exception {
        List<Store.Instrument> rows = NseCatalogue.parse("\uFEFF ISIN NUMBER , SERIES , NAME OF COMPANY , SYMBOL \n"
                + "INE009A01021,EQ,\"Infosys, \"\"Technology\"\"\nLimited\",INFY\n"
                + "INE002A01018,EQ,\"Reliance\r\nIndustries\",RELIANCE\n");
        assertEquals(2, rows.size());
        assertEquals("Infosys, \"Technology\"\nLimited", rows.get(0).name);
        assertEquals("Reliance\r\nIndustries", rows.get(1).name);
    }

    public void testNseCatalogueRejectsBadHeadersInvalidIsinAndMalformedRows() throws Exception {
        String row = "INFY,Infosys,EQ,08-FEB-1995,5,1,INE009A01021,5\n";
        for (String csv : new String[]{"", "<html>Access blocked</html>", CSV_HEADER,
                CSV_HEADER.replace("SYMBOL", "TICKER") + row,
                CSV_HEADER.replace("FACE VALUE", "SYMBOL") + row,
                CSV_HEADER + row.replace("INE009A01021", "INE009A01022"),
                CSV_HEADER + row.replace("Infosys", " "),
                CSV_HEADER + row.replace("INFY", "INFY.BO"),
                CSV_HEADER + row.replace(",5\n", "\n"),
                CSV_HEADER + row.replace("Infosys", "\"unterminated"),
                CSV_HEADER + row.replace("Infosys", "\"quoted\"x"),
                CSV_HEADER + row.replace("Infosys", "un\"quoted"),
                CSV_HEADER + row.replace(",EQ,", ",BE,")}) expectIo(() -> NseCatalogue.parse(csv));
        assertEquals(1, NseCatalogue.parse(CSV_HEADER + row + row).size());
        expectIo(() -> NseCatalogue.parse(CSV_HEADER + row + row.replace("INFY", "OTHER")));
        expectIo(() -> NseCatalogue.parse(CSV_HEADER + row + row.replace("INE009A01021", "INE002A01018")));
    }

    private static YahooFinanceClient.Result parse(String json) throws IOException {
        return YahooFinanceClient.parse(json, KEY, TICKER, FROM, TO, FETCHED);
    }
    private static String meta(long marketTime, String price) {
        return "{\"symbol\":\"" + TICKER + "\",\"currency\":\"INR\",\"exchangeName\":\"NSI\","
                + "\"instrumentType\":\"EQUITY\",\"regularMarketTime\":" + marketTime + ",\"regularMarketPrice\":" + price + "}";
    }
    private static String payload(String meta, String timestamps, String closes, String events) {
        return "{\"chart\":{\"error\":null,\"result\":[{\"meta\":" + meta + ",\"timestamp\":" + timestamps
                + ",\"indicators\":{\"quote\":[{\"close\":" + closes + "}]}" + events + "}]}}";
    }
    private static String timestamps(String... dates) {
        StringBuilder result = new StringBuilder("[");
        for (String date : dates) {
            if (result.length() > 1) result.append(',');
            result.append(daily(date));
        }
        return result.append(']').toString();
    }
    private static long daily(String date) { return LocalDate.parse(date).atTime(9, 15).atZone(IST).toEpochSecond(); }
    private static long seconds(String value) { return java.time.OffsetDateTime.parse(value).toEpochSecond(); }
    private static boolean hasWarning(YahooFinanceClient.Result result, String part) {
        for (String warning : result.warnings) if (warning.contains(part)) return true;
        return false;
    }
    private interface Operation { void run() throws Exception; }
    private static IOException expectIo(Operation operation) throws Exception {
        try { operation.run(); fail("Invalid data accepted"); }
        catch (IOException expected) { return expected; }
        throw new AssertionError("Expected IOException");
    }
}