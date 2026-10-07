package com.jacks.stocks.data;

import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.net.ssl.HttpsURLConnection;

/** Official published NSE cash-equity CSV, downloaded only on explicit user request. */
public final class NseCatalogue {
    private static final String ADDRESS = "https://nsearchives.nseindia.com/content/equities/EQUITY_L.csv";
    private static final int BYTE_LIMIT = 4 * 1024 * 1024;
    private static final int ROW_LIMIT = 50_000;
    private static final int FIELD_LIMIT = 16_384;

    private NseCatalogue() { }

    public static List<Store.Instrument> download() throws IOException {
        URL url = new URL(ADDRESS);
        if (!"https".equals(url.getProtocol()) || !"nsearchives.nseindia.com".equals(url.getHost())
                || !"/content/equities/EQUITY_L.csv".equals(url.getPath())
                || (url.getPort() != -1 && url.getPort() != 443) || url.getUserInfo() != null
                || url.getQuery() != null || url.getRef() != null)
            throw new IOException("Only the approved NSE HTTPS catalogue is allowed.");
        HttpsURLConnection connection = (HttpsURLConnection)url.openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(15_000); connection.setReadTimeout(25_000);
        connection.setUseCaches(false); connection.setRequestMethod("GET");
        connection.setRequestProperty("User-Agent", "JacksStocks/1.0");
        connection.setRequestProperty("Accept", "text/csv,text/plain");
        connection.setRequestProperty("Accept-Encoding", "identity");
        try {
            int status;
            try { status = connection.getResponseCode(); }
            catch (IOException e) { throw new IOException("Unable to contact NSE securely. Check your connection and download later."); }
            if (status == 401 || status == 403)
                throw new IOException("NSE declined catalogue access (HTTP " + status + "). No access workaround is attempted.");
            if (status == 429) throw new IOException("NSE catalogue rate limit reached. Wait before another manual download.");
            if (status >= 300 && status <= 399) throw new IOException("NSE redirected the catalogue request. Redirects are not followed.");
            if (status != 200) throw new IOException("NSE catalogue request failed (HTTP " + status + "). Try downloading later.");
            try { return parse(YahooFinanceClient.readUtf8(connection, BYTE_LIMIT)); }
            catch (IOException | RuntimeException e) {
                throw new IOException("NSE returned incomplete, oversized, or invalid catalogue data. The existing catalogue is unchanged.");
            }
        } finally { connection.disconnect(); }
    }

    /** Offline fixture entry point, including BOM, quoted commas, escaped quotes and multiline names. */
    public static List<Store.Instrument> parse(String csv) throws IOException {
        YahooFinanceClient.checkTextSize(csv, BYTE_LIMIT);
        Csv parser = new Csv(csv);
        List<String> header = parser.row();
        if (header == null) throw new IOException("NSE catalogue is empty.");
        Map<String, Integer> columns = new HashMap<>();
        for (int i = 0; i < header.size(); i++) {
            String name = header.get(i).replace("\uFEFF", "").replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
            if (name.isEmpty() || columns.put(name, i) != null) throw new IOException("NSE catalogue has invalid or duplicate headers.");
        }
        int symbolIndex = required(columns, "SYMBOL");
        int nameIndex = required(columns, "NAMEOFCOMPANY");
        int seriesIndex = required(columns, "SERIES");
        int isinIndex = required(columns, "ISINNUMBER");
        Map<String, Store.Instrument> instruments = new LinkedHashMap<>();
        Map<String, String> symbols = new HashMap<>();
        List<String> row;
        int count = 0;
        while ((row = parser.row()) != null) {
            if (++count > ROW_LIMIT) throw new IOException("NSE catalogue exceeds 50,000 entries.");
            if (row.size() != header.size()) throw new IOException("NSE catalogue contains an incomplete row.");
            if (!"EQ".equals(row.get(seriesIndex).trim().toUpperCase(Locale.ROOT))) continue;
            String symbol = row.get(symbolIndex).trim().toUpperCase(Locale.ROOT);
            String name = row.get(nameIndex).trim();
            String isin = row.get(isinIndex).trim().toUpperCase(Locale.ROOT);
            if (!validIsin(isin) || name.isEmpty() || name.indexOf('\0') >= 0)
                throw new IOException("NSE equity catalogue contains an invalid ISIN or company name.");
            try {
                // The official catalogue stores trading symbols, not provider suffixes.
                if (!YahooFinanceClient.ticker(symbol).equals(symbol + ".NS")) throw new IllegalArgumentException();
            } catch (IllegalArgumentException e) { throw new IOException("NSE equity catalogue contains an unsupported trading symbol."); }
            String key = "NSE_EQ|" + isin;
            Store.Instrument existing = instruments.get(key);
            if (existing != null && (!existing.symbol.equals(symbol) || !existing.name.equals(name)))
                throw new IOException("NSE catalogue contains conflicting ISIN entries.");
            String previousKey = symbols.put(symbol, key);
            if (previousKey != null && !previousKey.equals(key))
                throw new IOException("NSE catalogue contains conflicting trading symbols.");
            instruments.put(key, new Store.Instrument(key, symbol, name));
        }
        if (instruments.isEmpty()) throw new IOException("No valid EQ-series NSE equities were returned.");
        return Collections.unmodifiableList(new ArrayList<>(instruments.values()));
    }

    private static int required(Map<String, Integer> columns, String name) throws IOException {
        Integer index = columns.get(name);
        if (index == null) throw new IOException("NSE catalogue is missing required headers.");
        return index;
    }

    /** ISO 6166 shape and decimal-expanded Luhn check digit. */
    private static boolean validIsin(String isin) {
        if (!isin.matches("[A-Z]{2}[A-Z0-9]{9}[0-9]")) return false;
        StringBuilder digits = new StringBuilder(24);
        for (int i = 0; i < isin.length(); i++) {
            char c = isin.charAt(i);
            if (c >= 'A' && c <= 'Z') digits.append(c - 'A' + 10); else digits.append(c);
        }
        int sum = 0;
        boolean twice = false;
        for (int i = digits.length() - 1; i >= 0; i--, twice = !twice) {
            int n = digits.charAt(i) - '0';
            if (twice) n *= 2;
            sum += n / 10 + n % 10;
        }
        return sum % 10 == 0;
    }

    /** Small bounded RFC-4180-style parser; does not split quoted records on line breaks. */
    private static final class Csv {
        private final String text;
        private int position;
        Csv(String text) { this.text = text; position = text.startsWith("\uFEFF") ? 1 : 0; }

        List<String> row() throws IOException {
            // Ignore physically blank lines only, never rows containing delimiters or empty quotes.
            while (position < text.length() && (text.charAt(position) == '\r' || text.charAt(position) == '\n')) newline();
            if (position >= text.length()) return null;
            List<String> values = new ArrayList<>();
            while (true) {
                if (values.size() >= 64) throw new IOException("Too many catalogue columns.");
                values.add(field());
                if (position >= text.length()) return values;
                char separator = text.charAt(position);
                if (separator == ',') { position++; continue; }
                if (separator == '\r' || separator == '\n') { newline(); return values; }
                throw new IOException("Invalid catalogue quoting.");
            }
        }

        private String field() throws IOException {
            StringBuilder value = new StringBuilder();
            boolean quoted = position < text.length() && text.charAt(position) == '"';
            if (quoted) position++;
            while (position < text.length()) {
                char c = text.charAt(position);
                if (c == '\0') throw new IOException("Invalid catalogue character.");
                if (quoted) {
                    position++;
                    if (c == '"') {
                        if (position < text.length() && text.charAt(position) == '"') { position++; append(value, '"'); }
                        else return value.toString();
                    } else append(value, c);
                } else {
                    if (c == ',' || c == '\r' || c == '\n') return value.toString();
                    if (c == '"') throw new IOException("Unexpected catalogue quote.");
                    position++; append(value, c);
                }
            }
            if (quoted) throw new IOException("Unterminated catalogue quote.");
            return value.toString();
        }

        private void newline() {
            char c = text.charAt(position++);
            if (c == '\r' && position < text.length() && text.charAt(position) == '\n') position++;
        }
        private void append(StringBuilder value, char c) throws IOException {
            if (value.length() >= FIELD_LIMIT) throw new IOException("Catalogue field is too long.");
            value.append(c);
        }
    }
}