package com.jacks.stocks.data;

import com.jacks.stocks.core.Portfolio.Tx;
import com.jacks.stocks.core.Portfolio.Type;
import com.jacks.stocks.core.Portfolio;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/** Portable ledger-only CSV and authenticated encrypted backups. */
public final class Backup {
    private static final int MAX_BYTES = 16 * 1024 * 1024;
    private static final int MAX_ROWS = 100_000;
    private static final int MAX_FIELD = 16_384;
    private static final int ITERATIONS = 210_000;
    private static final int HEADER_BYTES = 37;
    private static final byte[] MAGIC = {'J', 'S', 'B', 'K', 1};
    private static final String[] COLUMNS = {"id", "date", "instrument_key", "symbol", "name", "type", "quantity", "price", "fees"};
    private Backup() { }

    /** Writes UTF-8 RFC4180 CSV with CRLF records; does not close the caller's stream.
     * Exact text is preserved, not spreadsheet-formula escaped. Import untrusted CSV as text,
     * never evaluate its cells. Prefer encrypted backups for confidential portable storage. */
    public static void writeCsv(OutputStream out, List<Tx> txs) throws IOException {
        byte[] csv = csvBytes(txs);
        try { out.write(csv); out.flush(); }
        finally { Arrays.fill(csv, (byte)0); }
    }

    private static byte[] csvBytes(List<Tx> txs) throws IOException {
        validate(txs);
        List<Tx> ordered = new ArrayList<>(txs);
        ordered.sort(Comparator.comparing((Tx t) -> t.date).thenComparingLong(t -> t.id));
        StringBuilder text = new StringBuilder();
        appendRecord(text, COLUMNS);
        for (Tx t : ordered) appendRecord(text, new String[]{Long.toString(t.id), t.date.toString(), t.key,
                t.symbol, t.name, t.type.name(), decimal(t.quantity), decimal(t.price), decimal(t.fees)});
        try {
            ByteBuffer encoded = StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(text));
            if (encoded.remaining() > MAX_BYTES) throw new IOException("Backup exceeds the 16 MiB limit.");
            byte[] result = new byte[encoded.remaining()]; encoded.get(result); return result;
        } catch (CharacterCodingException e) { throw new IOException("Ledger contains invalid Unicode."); }
    }

    private static void appendRecord(StringBuilder text, String[] fields) throws IOException {
        for (int i = 0; i < fields.length; i++) {
            String s = fields[i];
            if (s == null || s.length() > MAX_FIELD || s.indexOf('\0') >= 0) throw new IOException("Missing, invalid, or oversized CSV field.");
            if (i > 0) text.append(',');
            boolean quote = s.indexOf(',') >= 0 || s.indexOf('"') >= 0 || s.indexOf('\r') >= 0 || s.indexOf('\n') >= 0;
            if (quote) text.append('"');
            for (int j = 0; j < s.length(); j++) {
                char c = s.charAt(j); if (c == '"') text.append('"'); text.append(c);
            }
            if (quote) text.append('"');
        }
        text.append("\r\n");
        if (text.length() > MAX_BYTES) throw new IOException("Backup exceeds the 16 MiB limit.");
    }

    /** Accepts an optional UTF-8 BOM and CRLF/LF records; malformed quotes and trailing fields are rejected. */
    public static List<Tx> readCsv(InputStream in) throws IOException {
        byte[] bytes = readBounded(in, MAX_BYTES);
        try { return parseCsv(bytes); }
        finally { Arrays.fill(bytes, (byte)0); }
    }

    private static List<Tx> parseCsv(byte[] bytes) throws IOException {
        final String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) { throw new IOException("CSV must be valid UTF-8."); }
        Csv parser = new Csv(text);
        List<String> header = parser.record();
        if (header == null || !header.equals(Arrays.asList(COLUMNS)))
            throw new IOException("CSV header must be: " + String.join(",", COLUMNS));
        List<Tx> result = new ArrayList<>();
        List<String> row;
        while ((row = parser.record()) != null) {
            if (result.size() >= MAX_ROWS) throw new IOException("CSV exceeds 100,000 transactions.");
            int line = result.size() + 2;
            if (row.size() != COLUMNS.length) throw new IOException("CSV record " + line + " must contain exactly 9 fields.");
            try {
                if (!row.get(0).matches("0|[1-9][0-9]{0,18}") || !row.get(1).matches("[0-9]{4}-[0-9]{2}-[0-9]{2}"))
                    throw new IllegalArgumentException();
                result.add(new Tx(Long.parseLong(row.get(0)), LocalDate.parse(row.get(1)), row.get(2), row.get(3),
                        row.get(4), Type.valueOf(row.get(5)), parseDecimal(row.get(6)), parseDecimal(row.get(7)), parseDecimal(row.get(8))));
            } catch (RuntimeException e) { throw new IOException("Invalid transaction fields in CSV record " + line + "."); }
        }
        validate(result);
        result.sort(Comparator.comparing((Tx t) -> t.date).thenComparingLong(t -> t.id));
        return result;
    }

    private static void validate(List<Tx> txs) throws IOException {
        if (txs == null || txs.size() > MAX_ROWS) throw new IOException("Missing ledger or more than 100,000 transactions.");
        Set<Long> ids = new HashSet<>();
        for (Tx t : txs) {
            if (t == null || t.id < 0 || (t.id > 0 && !ids.add(t.id))) throw new IOException("Invalid or duplicate transaction ID.");
            decimal(t.quantity); decimal(t.price); decimal(t.fees);
        }
        try { Portfolio.validate(txs); }
        catch (RuntimeException e) { throw new IOException("Ledger validation failed: " + safeMessage(e)); }
    }

    private static String safeMessage(RuntimeException e) {
        String s = e.getMessage(); return s == null ? "invalid transaction sequence." : s;
    }

    private static String decimal(BigDecimal value) throws IOException {
        if (value == null || value.precision() + Math.max(0L, -(long)value.scale()) > 64 || Math.abs((long)value.scale()) > 64)
            throw new IOException("Decimal is missing or too large.");
        return value.toPlainString();
    }
    private static BigDecimal parseDecimal(String text) throws IOException {
        if (text.length() > 130 || !text.matches("-?[0-9]+(?:\\.[0-9]+)?")) throw new IOException("Invalid decimal format.");
        BigDecimal value = new BigDecimal(text); decimal(value); return value;
    }

    /** Version 1: magic/version + fixed KDF iterations + 16-byte salt + 12-byte nonce + AES-256-GCM payload/tag.
     * All header bytes are authenticated. Only CSV ledger data is encrypted; credentials and cache are absent.
     * Caller owns and should erase the supplied password after use. */
    public static void writeEncrypted(OutputStream out, List<Tx> txs, char[] password) throws IOException {
        checkPassword(password);
        byte[] plain = csvBytes(txs), derived = null;
        try {
            byte[] salt = new byte[16], nonce = new byte[12]; SecureRandom random = new SecureRandom();
            random.nextBytes(salt); random.nextBytes(nonce);
            byte[] header = ByteBuffer.allocate(HEADER_BYTES).put(MAGIC).putInt(ITERATIONS).put(salt).put(nonce).array();
            derived = derive(password, salt);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(derived, "AES"), new GCMParameterSpec(128, nonce));
            cipher.updateAAD(header); byte[] encrypted = cipher.doFinal(plain);
            out.write(header); out.write(encrypted); out.flush();
        } catch (GeneralSecurityException e) { throw new IOException("Encrypted backup could not be created."); }
        finally { Arrays.fill(plain, (byte)0); if (derived != null) Arrays.fill(derived, (byte)0); }
    }

    public static List<Tx> readEncrypted(InputStream in, char[] password) throws IOException {
        checkPassword(password);
        byte[] encoded = readBounded(in, MAX_BYTES + HEADER_BYTES + 16), derived = null, plain = null;
        try {
            if (encoded.length < HEADER_BYTES + 16 || !Arrays.equals(MAGIC, Arrays.copyOf(encoded, MAGIC.length)))
                throw new IOException("Not a supported Jacks stocks encrypted backup.");
            ByteBuffer header = ByteBuffer.wrap(encoded, 0, HEADER_BYTES); header.position(MAGIC.length);
            // Fixed per-version cost avoids attacker-controlled KDF denial of service.
            if (header.getInt() != ITERATIONS) throw new IOException("Unsupported backup encryption parameters.");
            byte[] salt = new byte[16], nonce = new byte[12]; header.get(salt); header.get(nonce);
            derived = derive(password, salt);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(derived, "AES"), new GCMParameterSpec(128, nonce));
            cipher.updateAAD(encoded, 0, HEADER_BYTES);
            plain = cipher.doFinal(encoded, HEADER_BYTES, encoded.length - HEADER_BYTES);
            return parseCsv(plain);
        } catch (GeneralSecurityException e) { throw new IOException("Wrong password or damaged encrypted backup; nothing was imported."); }
        finally {
            Arrays.fill(encoded, (byte)0); if (derived != null) Arrays.fill(derived, (byte)0);
            if (plain != null) Arrays.fill(plain, (byte)0);
        }
    }

    private static void checkPassword(char[] password) throws IOException {
        if (password == null || password.length < 8 || password.length > 1024)
            throw new IOException("Backup password must contain 8 to 1,024 characters.");
    }
    private static byte[] derive(char[] password, byte[] salt) throws GeneralSecurityException {
        PBEKeySpec spec = new PBEKeySpec(password, salt, ITERATIONS, 256);
        try { return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded(); }
        finally { spec.clearPassword(); }
    }
    private static byte[] readBounded(InputStream in, int maximum) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] buffer = new byte[8192]; int n;
        while ((n = in.read(buffer)) != -1) {
            if (n == 0) { int one = in.read(); if (one == -1) break; if (out.size() == maximum) throw new IOException("Backup exceeds the file size limit."); out.write(one); continue; }
            if (n > maximum - out.size()) throw new IOException("Backup exceeds the file size limit.");
            out.write(buffer, 0, n);
        }
        return out.toByteArray();
    }

    private static final class Csv {
        private final String text;
        private int pos;
        Csv(String text) { this.text = text; if (!text.isEmpty() && text.charAt(0) == '\uFEFF') pos = 1; }
        List<String> record() throws IOException {
            if (pos == text.length()) return null;
            List<String> fields = new ArrayList<>();
            while (true) {
                StringBuilder field = new StringBuilder();
                if (pos < text.length() && text.charAt(pos) == '"') {
                    pos++; boolean closed = false;
                    while (pos < text.length()) {
                        char c = text.charAt(pos++);
                        if (c == '"') {
                            if (pos < text.length() && text.charAt(pos) == '"') { pos++; append(field, '"'); }
                            else { closed = true; break; }
                        } else append(field, c);
                    }
                    if (!closed) throw new IOException("Unterminated quoted CSV field.");
                } else {
                    while (pos < text.length()) {
                        char c = text.charAt(pos);
                        if (c == ',' || c == '\r' || c == '\n') break;
                        if (c == '"') throw new IOException("Quote inside an unquoted CSV field.");
                        pos++; append(field, c);
                    }
                }
                fields.add(field.toString());
                if (fields.size() > COLUMNS.length) throw new IOException("Too many CSV columns.");
                if (pos == text.length()) return fields;
                char separator = text.charAt(pos++);
                if (separator == ',') continue;
                if (separator == '\n') return fields;
                if (separator == '\r' && pos < text.length() && text.charAt(pos++) == '\n') return fields;
                throw new IOException("Invalid CSV record separator or text after closing quote.");
            }
        }
        private static void append(StringBuilder field, char c) throws IOException {
            if (c == '\0' || field.length() >= MAX_FIELD) throw new IOException("CSV field is invalid or too large.");
            field.append(c);
        }
    }
}