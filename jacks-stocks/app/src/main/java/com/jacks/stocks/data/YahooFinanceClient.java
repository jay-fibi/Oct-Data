package com.jacks.stocks.data;

import android.util.JsonReader;
import android.util.JsonToken;
import com.jacks.stocks.core.Portfolio.Candle;
import com.jacks.stocks.core.Portfolio.Quote;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.math.BigDecimal;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;
import javax.net.ssl.HttpsURLConnection;

/**
 * Explicit-refresh, read-only client for Yahoo's unofficial chart endpoint.
 * Availability and unadjusted historical prices are not guaranteed. No authentication,
 * scraping, endpoint fallback, automatic retries, or ledger/corporate-action mutations.
 */
public final class YahooFinanceClient {
    private static final ZoneId MARKET_ZONE = ZoneId.of("Asia/Kolkata");
    private static final int JSON_LIMIT = 8 * 1024 * 1024;
    private static final int ARRAY_LIMIT = 40_000;
    private static final Object REQUEST_LOCK = new Object();
    private static final long PACE_NANOS = TimeUnit.MILLISECONDS.toNanos(750);
    private static long lastRequestNanos, cooldownStartedNanos, cooldownNanos;
    private static boolean requested;
    private final Map<String, Long> firstTrades = new HashMap<>();
    private RequestException terminalFailure;

    public YahooFinanceClient() { }

    public static final class Result {
        public final Quote quote;
        public final List<Candle> candles;
        public final List<String> warnings;
        public final LocalDate latestSplit;

        private Result(Quote quote, List<Candle> candles, Set<String> warnings, LocalDate latestSplit) {
            this.quote = quote;
            this.candles = Collections.unmodifiableList(new ArrayList<>(candles));
            this.warnings = Collections.unmodifiableList(new ArrayList<>(warnings));
            this.latestSplit = latestSplit;
        }
    }

    /** Safe HTTP status only: never includes response bodies, headers, or request URLs. */
    public static final class RequestException extends IOException {
        private static final long serialVersionUID = 1L;
        public final int statusCode;
        private RequestException(int statusCode, String message) {
            super(message); this.statusCode = statusCode;
        }
    }

    /** Accepts an NSE trading symbol or its .NS ticker; never infers a BSE listing. */
    public static String ticker(String symbol) {
        if (symbol == null) throw new IllegalArgumentException("Enter a valid NSE trading symbol.");
        String value = symbol.trim().toUpperCase(Locale.ROOT);
        if (value.endsWith(".NS")) value = value.substring(0, value.length() - 3);
        if (!value.matches("[A-Z0-9][A-Z0-9&-]{0,39}"))
            throw new IllegalArgumentException("Enter a valid NSE trading symbol; only letters, numbers, & and - are supported.");
        return value + ".NS";
    }

    /**
     * Inclusive window: completed historical candles end no later than yesterday in India.
     * The request may end today to detect today's split; today's unfinished candle is never returned.
     */
    public Result fetch(String key, String symbol, LocalDate from, LocalDate to) throws IOException {
        checkKey(key);
        String name;
        try { name = ticker(symbol); }
        catch (IllegalArgumentException e) { throw new IOException("Enter a valid NSE trading symbol."); }
        checkWindow(from, to, System.currentTimeMillis());
        // Serialize actual requests, not merely reservations: a queued request cannot race a 429.
        synchronized (REQUEST_LOCK) {
            if (terminalFailure != null) throw terminalFailure;
            try {
                pace();
                LocalDate start = from;
                Long first = firstTrades.get(name);
                if (first != null) {
                    LocalDate listed = day(first);
                    // A wholly pre-listing range still requests one day to obtain current meta.
                    if (listed != null && listed.isAfter(start)) start = listed.isAfter(to) ? to : listed;
                }
                String address = chartUrl(name, start, to);
                String json = request(address);
                long fetchedMillis = System.currentTimeMillis();
                Payload payload = readPayload(json);
                Result result = build(payload, key, name, from, to, fetchedMillis);
                if (payload.firstTrade != null && day(payload.firstTrade) != null
                        && !day(payload.firstTrade).isAfter(Instant.ofEpochMilli(fetchedMillis).atZone(MARKET_ZONE).toLocalDate())
                        && firstTrades.size() < 50_000)
                    firstTrades.put(name, payload.firstTrade);
                return result;
            } catch (RequestException e) {
                if (e.statusCode == 401 || e.statusCode == 403 || e.statusCode == 429) terminalFailure = e;
                throw e;
            }
        }
    }

    static String chartUrl(String name, LocalDate from, LocalDate to) throws IOException {
        if (!name.equals(ticker(name))) throw new IOException("Invalid NSE ticker.");
        long period1 = from.atStartOfDay(MARKET_ZONE).toEpochSecond();
        long period2 = to.plusDays(1).atStartOfDay(MARKET_ZONE).toEpochSecond();
        return "https://query1.finance.yahoo.com/v8/finance/chart/"
                + URLEncoder.encode(name, "UTF-8").replace("+", "%20")
                + "?period1=" + period1 + "&period2=" + period2
                + "&interval=1d&events=splits&includeAdjustedClose=false";
    }

    /** Offline fixture entry point; numeric tokens are read as strings, never via double. */
    static Result parse(String json, String key, String name, LocalDate from, LocalDate to,
            long fetchedMillis) throws IOException {
        checkKey(key); checkWindow(from, to, fetchedMillis);
        try {
            if (!name.equals(ticker(name))) throw new IOException("Invalid NSE ticker.");
            return build(readPayload(json), key, name, from, to, fetchedMillis);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new IOException("Invalid NSE ticker or provider data.");
        }
    }

    private static Result build(Payload p, String key, String name, LocalDate from, LocalDate to,
            long fetchedMillis) throws IOException {
        if (!name.equals(p.symbol) || !"INR".equals(p.currency) || !"EQUITY".equals(p.instrumentType)
                || (!"NSI".equals(p.exchange) && !"NSE".equals(p.fullExchange)))
            throw new IOException("Yahoo did not confirm the requested NSE equity in INR. No data was accepted.");
        Set<String> warnings = new LinkedHashSet<>(p.warnings);
        TreeMap<LocalDate, Candle> candles = new TreeMap<>();
        Set<LocalDate> conflicts = new HashSet<>();
        LocalDate today = Instant.ofEpochMilli(fetchedMillis).atZone(MARKET_ZONE).toLocalDate();
        LocalDate listed = day(p.firstTrade);
        if (listed != null && listed.isAfter(today)) {
            listed = null;
            warnings.add("An invalid future first-trading date was ignored.");
        }
        if (listed != null && from.isBefore(listed))
            warnings.add("History before the provider's first trading date is unavailable.");
        if (p.timestamps == null || p.closes == null) {
            warnings.add("Yahoo returned no usable daily timestamp/close arrays; historical prices are unavailable.");
        } else {
            if (p.timestamps.size() != p.closes.size())
                warnings.add("Daily timestamp/close array lengths differ; unmatched entries were skipped.");
            for (int i = 0; i < p.timestamps.size(); i++) {
                LocalDate date = day(p.timestamps.get(i));
                BigDecimal close = i < p.closes.size() ? p.closes.get(i) : null;
                if (date == null || close == null) {
                    warnings.add("Missing or invalid daily closes/timestamps were skipped without shifting dates.");
                    continue;
                }
                if (date.isBefore(from) || date.isAfter(to) || !date.isBefore(today)
                        || (listed != null && date.isBefore(listed))) {
                    warnings.add("Daily values outside the requested completed-session/listing window were ignored.");
                    continue;
                }
                if (conflicts.contains(date)) continue;
                Candle old = candles.put(date, new Candle(key, date, close));
                if (old != null && old.close.compareTo(close) != 0) {
                    candles.remove(date); conflicts.add(date);
                    warnings.add("Conflicting daily closes for the same date were withheld.");
                }
            }
        }
        LocalDate latestSplit = null;
        for (Split split : p.splits) {
            LocalDate date = day(split.date);
            if (date == null || !validRatio(split) || date.isAfter(today)) {
                p.unreliableSplits = true;
            } else if (latestSplit == null || date.isAfter(latestSplit)) latestSplit = date;
        }
        if (p.unreliableSplits) {
            candles.clear();
            warnings.add("Unusable provider split event: historical closes were withheld because adjustment status is uncertain.");
        }
        if (latestSplit != null) {
            warnings.add("Yahoo reports a split on " + latestSplit
                    + "; historical prices may be split adjusted, not raw. The ledger has not been changed.");
            if (!latestSplit.isBefore(from) && !latestSplit.isAfter(to)) {
                candles.headMap(latestSplit, false).clear();
                warnings.add("Pre-split daily closes were withheld because raw/split-adjusted history is uncertain.");
            }
        }
        Quote quote = null;
        Long marketMillis = millis(p.marketTime);
        if (p.price != null && marketMillis != null && marketMillis > 0 && marketMillis <= fetchedMillis) {
            LocalDate quoteDate = Instant.ofEpochMilli(marketMillis).atZone(MARKET_ZONE).toLocalDate();
            Map.Entry<LocalDate, Candle> prior = candles.lowerEntry(quoteDate);
            quote = new Quote(key, p.price, prior == null ? null : prior.getValue().close, marketMillis, fetchedMillis);
            if (prior == null) warnings.add("Previous close is unavailable: no valid daily close strictly before the quote's India date.");
            if (quoteDate.isBefore(today)) warnings.add("The current quote carries an earlier provider market timestamp; it is not a live-time guarantee.");
        } else {
            warnings.add("Current quote unavailable: Yahoo omitted a valid positive price or actual nonfuture market timestamp.");
        }
        if (candles.isEmpty()) warnings.add("No usable historical daily closes were returned for this window.");
        return new Result(quote, new ArrayList<>(candles.values()), warnings, latestSplit);
    }

    private static boolean validRatio(Split s) {
        if (s.numerator != null && s.denominator != null)
            return s.numerator.compareTo(s.denominator) != 0;
        if (s.ratio == null) return false;
        String[] parts = s.ratio.split("[:/]", -1);
        if (parts.length != 2) return false;
        BigDecimal numerator = positive(parts[0].trim()), denominator = positive(parts[1].trim());
        return numerator != null && denominator != null && numerator.compareTo(denominator) != 0;
    }

    private static Payload readPayload(String json) throws IOException {
        checkTextSize(json, JSON_LIMIT);
        try (JsonReader r = new JsonReader(new StringReader(json))) {
            r.setLenient(false);
            Payload result = null;
            Set<String> names = new HashSet<>();
            r.beginObject();
            while (r.hasNext()) {
                String name = unique(r, names);
                if ("chart".equals(name)) result = chart(r); else r.skipValue();
            }
            r.endObject();
            if (r.peek() != JsonToken.END_DOCUMENT || result == null)
                throw new IOException("Invalid document.");
            return result;
        } catch (IOException | RuntimeException e) {
            // JsonReader errors can contain input excerpts. Never propagate those or provider error descriptions.
            throw new IOException("Yahoo returned unavailable, incomplete, oversized, or invalid chart data. Check the NSE symbol and try later.");
        }
    }

    private static Payload chart(JsonReader r) throws IOException {
        Payload result = null;
        boolean error = false;
        Set<String> names = new HashSet<>();
        r.beginObject();
        while (r.hasNext()) {
            switch (unique(r, names)) {
                case "error":
                    if (r.peek() == JsonToken.NULL) r.nextNull(); else { error = true; r.skipValue(); }
                    break;
                case "result":
                    if (r.peek() == JsonToken.NULL) { r.nextNull(); break; }
                    r.beginArray();
                    if (r.hasNext()) result = result(r);
                    if (r.hasNext()) throw new IOException("Ambiguous chart results.");
                    r.endArray(); break;
                default: r.skipValue();
            }
        }
        r.endObject();
        if (error || result == null) throw new IOException("No successful chart result.");
        return result;
    }

    private static Payload result(JsonReader r) throws IOException {
        Payload p = new Payload();
        Set<String> names = new HashSet<>();
        r.beginObject();
        while (r.hasNext()) {
            switch (unique(r, names)) {
                case "meta": meta(r, p); break;
                case "timestamp": p.timestamps = timestamps(r); break;
                case "indicators": indicators(r, p); break;
                case "events": events(r, p); break;
                default: r.skipValue();
            }
        }
        r.endObject(); return p;
    }

    private static void meta(JsonReader r, Payload p) throws IOException {
        Set<String> names = new HashSet<>();
        r.beginObject();
        while (r.hasNext()) {
            switch (unique(r, names)) {
                case "symbol": p.symbol = scalar(r); break;
                case "currency": p.currency = scalar(r); break;
                case "exchangeName": p.exchange = scalar(r); break;
                case "fullExchangeName": p.fullExchange = scalar(r); break;
                case "instrumentType": p.instrumentType = scalar(r); break;
                case "regularMarketPrice": p.price = positive(scalar(r)); break;
                case "regularMarketTime": p.marketTime = integer(scalar(r)); break;
                case "firstTradeDate": p.firstTrade = integer(scalar(r)); break;
                // chartPreviousClose is range-start, not yesterday. Never use it or adjusted fields.
                default: r.skipValue();
            }
        }
        r.endObject();
    }

    private static List<Long> timestamps(JsonReader r) throws IOException {
        if (r.peek() == JsonToken.NULL) { r.nextNull(); return null; }
        List<Long> values = new ArrayList<>();
        r.beginArray();
        while (r.hasNext()) {
            if (values.size() >= ARRAY_LIMIT) throw new IOException("Too many daily timestamps.");
            values.add(integer(scalar(r)));
        }
        r.endArray(); return values;
    }

    private static List<BigDecimal> closes(JsonReader r) throws IOException {
        if (r.peek() == JsonToken.NULL) { r.nextNull(); return null; }
        List<BigDecimal> values = new ArrayList<>();
        r.beginArray();
        while (r.hasNext()) {
            if (values.size() >= ARRAY_LIMIT) throw new IOException("Too many daily closes.");
            values.add(positive(scalar(r)));
        }
        r.endArray(); return values;
    }

    private static void indicators(JsonReader r, Payload p) throws IOException {
        if (r.peek() == JsonToken.NULL) { r.nextNull(); return; }
        Set<String> names = new HashSet<>();
        r.beginObject();
        while (r.hasNext()) {
            if (!"quote".equals(unique(r, names))) { r.skipValue(); continue; }
            if (r.peek() == JsonToken.NULL) { r.nextNull(); continue; }
            r.beginArray();
            if (r.hasNext()) {
                if (r.peek() == JsonToken.NULL) r.nextNull();
                else {
                    Set<String> fields = new HashSet<>();
                    r.beginObject();
                    while (r.hasNext()) {
                        if ("close".equals(unique(r, fields))) p.closes = closes(r); else r.skipValue();
                    }
                    r.endObject();
                }
            }
            if (r.hasNext()) throw new IOException("Ambiguous daily quote arrays.");
            r.endArray();
        }
        r.endObject();
    }

    private static void events(JsonReader r, Payload p) throws IOException {
        if (r.peek() == JsonToken.NULL) { r.nextNull(); return; }
        Set<String> names = new HashSet<>();
        r.beginObject();
        while (r.hasNext()) {
            if (!"splits".equals(unique(r, names))) { r.skipValue(); continue; }
            if (r.peek() == JsonToken.NULL) { r.nextNull(); continue; }
            Set<String> ids = new HashSet<>();
            r.beginObject();
            while (r.hasNext()) {
                if (p.splits.size() >= ARRAY_LIMIT) throw new IOException("Too many split events.");
                String id = unique(r, ids);
                if (r.peek() != JsonToken.BEGIN_OBJECT) { r.skipValue(); p.unreliableSplits = true; continue; }
                Split split = new Split();
                split.date = integer(id);
                Set<String> fields = new HashSet<>();
                r.beginObject();
                while (r.hasNext()) {
                    switch (unique(r, fields)) {
                        case "date": split.date = integer(scalar(r)); break;
                        case "numerator": split.numerator = positive(scalar(r)); break;
                        case "denominator": split.denominator = positive(scalar(r)); break;
                        case "splitRatio": split.ratio = scalar(r); break;
                        default: r.skipValue();
                    }
                }
                r.endObject(); p.splits.add(split);
            }
            r.endObject();
        }
        r.endObject();
    }

    private static String unique(JsonReader r, Set<String> fields) throws IOException {
        String name = r.nextName();
        if (name.length() > 256 || fields.size() >= 50_000 || !fields.add(name))
            throw new IOException("Duplicate or excessive provider fields.");
        return name;
    }

    private static String scalar(JsonReader r) throws IOException {
        if (r.peek() == JsonToken.NULL) { r.nextNull(); return null; }
        if (r.peek() != JsonToken.NUMBER && r.peek() != JsonToken.STRING) { r.skipValue(); return null; }
        String value = r.nextString();
        return value.length() <= 128 ? value : null;
    }

    private static BigDecimal positive(String text) {
        if (text == null || text.length() > 128) return null;
        try {
            BigDecimal value = new BigDecimal(text);
            return value.signum() > 0 && value.precision() + Math.max(0L, -(long)value.scale()) <= 64
                    && Math.abs((long)value.scale()) <= 64 ? value : null;
        } catch (NumberFormatException e) { return null; }
    }

    private static Long integer(String text) {
        if (text == null || !text.matches("-?[0-9]{1,18}")) return null;
        try { return Long.parseLong(text); } catch (NumberFormatException e) { return null; }
    }
    private static Long millis(Long seconds) {
        if (seconds == null) return null;
        try { return Math.multiplyExact(seconds, 1000L); } catch (ArithmeticException e) { return null; }
    }
    private static LocalDate day(Long seconds) {
        Long ms = millis(seconds);
        if (ms == null) return null;
        try { return Instant.ofEpochMilli(ms).atZone(MARKET_ZONE).toLocalDate(); }
        catch (RuntimeException e) { return null; }
    }
    private static void checkKey(String key) throws IOException {
        if (key == null || !key.matches("NSE_EQ\\|[A-Z]{2}[A-Z0-9]{9}[0-9]"))
            throw new IOException("Only valid NSE equity instrument keys are supported.");
    }
    private static void checkWindow(LocalDate from, LocalDate to, long fetchedMillis) throws IOException {
        if (from == null || to == null || fetchedMillis <= 0 || from.isAfter(to))
            throw new IOException("Enter a valid inclusive historical date window.");
        LocalDate today = Instant.ofEpochMilli(fetchedMillis).atZone(MARKET_ZONE).toLocalDate();
        if (to.isAfter(today) || from.isBefore(today.minusYears(100)) || to.isAfter(from.plusYears(100)))
            throw new IOException("Request dates must be within the last 100 years and end no later than today in India; candles end yesterday.");
    }

    private static String request(String address) throws IOException {
        URL url = new URL(address);
        if (!"https".equals(url.getProtocol()) || !"query1.finance.yahoo.com".equals(url.getHost())
                || (url.getPort() != -1 && url.getPort() != 443) || url.getUserInfo() != null || url.getRef() != null)
            throw new IOException("Only the approved Yahoo HTTPS chart endpoint is allowed.");
        HttpsURLConnection connection = (HttpsURLConnection)url.openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(15_000); connection.setReadTimeout(25_000);
        connection.setUseCaches(false); connection.setRequestMethod("GET");
        connection.setRequestProperty("User-Agent", "JacksStocks/1.0");
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("Accept-Encoding", "identity");
        try {
            int code;
            try { code = connection.getResponseCode(); }
            catch (IOException e) { throw new IOException("Unable to contact Yahoo securely. Check your connection and try Refresh later."); }
            if (code == 401 || code == 403)
                throw new RequestException(code, "Yahoo declined access (HTTP " + code + "). No access workaround or automatic retry will be attempted.");
            if (code == 429) {
                long seconds = retryAfterSeconds(connection.getHeaderField("Retry-After"), System.currentTimeMillis());
                cooldownStartedNanos = System.nanoTime(); cooldownNanos = TimeUnit.SECONDS.toNanos(seconds);
                throw new RequestException(code, "Yahoo rate limit reached. Wait at least " + seconds + " seconds before a new manual refresh.");
            }
            if (code >= 300 && code <= 399) throw new IOException("Yahoo redirected the request. Redirects are not followed.");
            if (code != 200) throw new RequestException(code, "Yahoo chart request failed (HTTP " + code + "). Check the NSE symbol or try later.");
            try { return readUtf8(connection, JSON_LIMIT); }
            catch (IOException | RuntimeException e) { throw new IOException("Yahoo returned incomplete, oversized, or invalid UTF-8 data. Try Refresh later."); }
        } finally { connection.disconnect(); }
    }

    /** Called only under REQUEST_LOCK. A cooldown is process-wide even for newly created clients. */
    private static void pace() throws IOException {
        if (Thread.currentThread().isInterrupted()) throw new IOException("Refresh was cancelled.");
        long cooldownLeft = cooldownNanos - (System.nanoTime() - cooldownStartedNanos);
        if (cooldownNanos > 0 && cooldownLeft > 0)
            throw new RequestException(429, "Yahoo rate-limit cooldown is active. Wait before a new manual refresh.");
        if (requested) {
            long remaining = PACE_NANOS - (System.nanoTime() - lastRequestNanos);
            if (remaining > 0) {
                try { TimeUnit.NANOSECONDS.sleep(remaining); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("Refresh was cancelled."); }
            }
        }
        lastRequestNanos = System.nanoTime(); requested = true;
    }

    static long retryAfterSeconds(String header, long nowMillis) {
        if (header == null || header.length() > 128) return 60;
        String value = header.trim();
        if (value.matches("[0-9]+")) {
            try { return Math.max(1, Math.min(3600, Long.parseLong(value))); }
            catch (NumberFormatException e) { return 3600; }
        }
        try {
            long target = ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli();
            long difference = Math.subtractExact(target, nowMillis);
            return difference <= 0 ? 1 : Math.min(3600, 1 + (difference - 1) / 1000);
        } catch (RuntimeException e) { return 60; }
    }

    /** Shared bounded UTF-8 reader; both compressed wire bytes and decoded bytes are capped. */
    static String readUtf8(HttpsURLConnection connection, int limit) throws IOException {
        if (connection.getContentLengthLong() > limit) throw new IOException("Response too large.");
        String encoding = connection.getContentEncoding();
        if (encoding != null && !encoding.isEmpty() && !"identity".equalsIgnoreCase(encoding) && !"gzip".equalsIgnoreCase(encoding))
            throw new IOException("Unsupported response encoding.");
        try (InputStream wire = new LimitedInputStream(connection.getInputStream(), limit)) {
            InputStream decoded = "gzip".equalsIgnoreCase(encoding) ? new GZIPInputStream(wire, 8192) : wire;
            try (InputStreamReader reader = new InputStreamReader(new LimitedInputStream(decoded, limit),
                    StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT))) {
                StringBuilder text = new StringBuilder();
                char[] buffer = new char[4096];
                int count;
                while ((count = reader.read(buffer)) != -1) text.append(buffer, 0, count);
                return text.toString();
            }
        }
    }

    static void checkTextSize(String text, int limit) throws IOException {
        if (text == null || text.length() > limit) throw new IOException("Missing or oversized provider data.");
        long bytes = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c < 0x80) bytes++;
            else if (c < 0x800) bytes += 2;
            else if (Character.isHighSurrogate(c)) {
                if (++i >= text.length() || !Character.isLowSurrogate(text.charAt(i))) throw new IOException("Invalid Unicode data.");
                bytes += 4;
            } else if (Character.isLowSurrogate(c)) throw new IOException("Invalid Unicode data.");
            else bytes += 3;
            if (bytes > limit) throw new IOException("Oversized provider data.");
        }
    }

    private static final class LimitedInputStream extends FilterInputStream {
        private long remaining;
        LimitedInputStream(InputStream in, long limit) { super(in); remaining = limit; }
        @Override public int read() throws IOException {
            if (Thread.currentThread().isInterrupted()) throw new IOException("Refresh was cancelled.");
            int value = in.read();
            if (value != -1 && --remaining < 0) throw new IOException("Response too large.");
            return value;
        }
        @Override public int read(byte[] bytes, int offset, int count) throws IOException {
            if (count == 0) return 0;
            if (Thread.currentThread().isInterrupted()) throw new IOException("Refresh was cancelled.");
            int n = in.read(bytes, offset, (int)Math.min(count, Math.max(1, remaining + 1)));
            if (n > 0 && (remaining -= n) < 0) throw new IOException("Response too large.");
            return n;
        }
    }

    private static final class Payload {
        String symbol, currency, exchange, fullExchange, instrumentType;
        Long marketTime, firstTrade;
        BigDecimal price;
        List<Long> timestamps;
        List<BigDecimal> closes;
        boolean unreliableSplits;
        final List<Split> splits = new ArrayList<>();
        final Set<String> warnings = new LinkedHashSet<>();
    }
    private static final class Split {
        Long date;
        BigDecimal numerator, denominator;
        String ratio;
    }
}