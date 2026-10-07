package com.jacks.stocks.data;

import com.jacks.stocks.core.Portfolio.Tx;
import com.jacks.stocks.core.Portfolio.Type;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/** Persisted dependency-free executable regression tests; no Android runtime or credentials. */
public final class BackupTest {
    private static final String KEY = "NSE_EQ|INE009A01021";
    private static final String HEADER = "id,date,instrument_key,symbol,name,type,quantity,price,fees\r\n";
    private static int assertions;

    public static void main(String[] args) throws Exception {
        roundTrips(); malformedCsv(); limits(); encrypted(); streamOwnership();
        System.out.println("BackupTest: " + assertions + " assertions passed.");
    }

    private static Tx tx(long id, Type type, String quantity, String price, String fees, String name) {
        return new Tx(id, LocalDate.of(2020, 1, 1).plusDays(id), KEY, "INFY", name, type,
                new BigDecimal(quantity), new BigDecimal(price), new BigDecimal(fees));
    }

    private static List<Tx> ledger() {
        return Arrays.asList(
                tx(1, Type.OPENING, "10.0001", "100.1234567890123456789", "0", "Infosys, \"India\" ₹"),
                tx(2, Type.BUY, "2.005", "101.002", "0.05", "Infosys, \"India\" ₹"),
                tx(3, Type.DIVIDEND, "0", "30.01", "0.01", "Infosys, \"India\" ₹"),
                tx(4, Type.SPLIT, "2", "0", "0", "Infosys, \"India\" ₹"),
                tx(5, Type.SELL, "1.25", "65.01", "0.002", "Infosys, \"India\" ₹"));
    }

    private static byte[] csv(List<Tx> txs) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(); Backup.writeCsv(out, txs); return out.toByteArray();
    }
    private static List<Tx> read(String csv) throws IOException {
        return Backup.readCsv(new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)));
    }
    private static void roundTrips() throws Exception {
        byte[] data = csv(ledger());
        sameLedger(ledger(), Backup.readCsv(new ByteArrayInputStream(data)));
        String text = new String(data, StandardCharsets.UTF_8);
        check(text.startsWith(HEADER), "Exact, documented header");
        check(text.contains("\"Infosys, \"\"India\"\" ₹\""), "Commas, quotes and Unicode exported correctly");
        sameLedger(ledger(), read("\uFEFF" + text));
        sameLedger(ledger(), read(text.replace("\r\n", "\n")));
        sameLedger(ledger(), read(text.substring(0, text.length() - 2)));
        check(read(HEADER).isEmpty(), "Header-only empty ledger");
        sameLedger(ledger(), Backup.readCsv(new ZeroFirstRead(data)));
        List<Tx> formulaName = Collections.singletonList(tx(1, Type.BUY, "1", "1", "0", "=1+1"));
        sameLedger(formulaName, Backup.readCsv(new ByteArrayInputStream(csv(formulaName))));
        List<Tx> shuffled = new ArrayList<>(ledger()); Collections.reverse(shuffled);
        sameLedger(ledger(), Backup.readCsv(new ByteArrayInputStream(csv(shuffled))));
        Random random = new Random(21871);
        for (int i = 0; i < 150; i++) {
            StringBuilder name = new StringBuilder("Stock ");
            String alphabet = "abcXYZ 0123,\"₹é&=+-@";
            for (int j = 0; j < 90; j++) name.append(alphabet.charAt(random.nextInt(alphabet.length())));
            List<Tx> one = Collections.singletonList(tx(1, Type.BUY, "0.12345678", "12.34000", "0.010", name.toString()));
            sameLedger(one, Backup.readCsv(new ByteArrayInputStream(csv(one))));
        }
    }

    private static String row(String name) {
        return "1,2020-01-02," + KEY + ",INFY," + name + ",BUY,10,100,0\r\n";
    }
    private static void malformedCsv() throws Exception {
        rejects(() -> read(""), "Empty CSV");
        rejects(() -> read(HEADER.replace("instrument_key", "key") + row("Infosys")), "Wrong header");
        rejects(() -> read(HEADER + row("In\"fosys")), "Unquoted quote");
        rejects(() -> read(HEADER + row("\"Infosys\"bad")), "Text after closing quote");
        rejects(() -> read(HEADER + row("\"Infosys")), "Unclosed quote");
        rejects(() -> read(HEADER + row("Infosys").replace("\r\n", "\r")), "Bare CR record");
        rejects(() -> read(HEADER + row("Infosys").replace("BUY,10", "BUY,1e2")), "Scientific notation rejected");
        rejects(() -> read(HEADER + row("Infosys").replace("BUY,10", "BUY, 10")), "Untrimmed number rejected");
        rejects(() -> read(HEADER + row("Infosys").replace("BUY", "UNKNOWN")), "Unknown type");
        rejects(() -> read(HEADER + row("Infosys").replace("BUY", "SELL")), "Sell without holdings");
        rejects(() -> read(HEADER + row("Infosys").replace("10,100,0", "10,-100,0")), "Negative price");
        rejects(() -> read(HEADER + row("Infosys").replace("2020-01-02", "2020-02-30")), "Invalid calendar date");
        rejects(() -> read(HEADER + row("Infosys").replace(KEY, "NSE_EQ|INVALID")), "Invalid instrument key");
        rejects(() -> read(HEADER + row("Infosys") + row("Infosys")), "Duplicate IDs");
        rejects(() -> read(HEADER + row("Infosys") + "\r\n"), "Blank trailing record");
        rejects(() -> read(HEADER + row("Infosys").replace("0\r\n", "0,\r\n")), "Extra column");
        rejects(() -> read(HEADER + row("Infosys").replace(",0\r\n", "\r\n")), "Missing column");
        // RFC4180 multiline fields are parsed intact, then rejected by the financial domain's no-controls rule.
        IOException multiline = rejects(() -> read(HEADER + row("\"Info\r\nsys\"")), "Multiline domain validation");
        check(multiline.getMessage().startsWith("Ledger validation failed:"), "Multiline handled by CSV parser before semantic validation");
        rejects(() -> Backup.readCsv(new ByteArrayInputStream(new byte[]{(byte)0xc3, 0x28})), "Invalid UTF8");
        rejects(() -> csv(Collections.singletonList(tx(1, Type.BUY, "1", "1", "0", "\ud800"))), "Invalid Unicode export");
        rejects(() -> read(HEADER + row("Info\0sys")), "NUL rejected");
    }

    private static void limits() throws Exception {
        rejects(() -> read(HEADER + row(String.join("", Collections.nCopies(16_385, "A")))), "Oversized field");
        rejects(() -> Backup.readCsv(new RepeatingInput(16 * 1024 * 1024 + 1)), "Oversized input");
        rejects(() -> csv(Collections.nCopies(100_001, ledger().get(0))), "Too many export rows");
        rejects(() -> csv(Collections.singletonList(tx(1, Type.BUY, "1", "1e100000", "0", "Infosys"))), "Explosive decimal scale");
        rejects(() -> csv(Collections.singletonList(tx(1, Type.BUY, "1", "1e64", "0", "Infosys"))), "Oversized normalized decimal");
    }

    private static byte[] encryptedBytes(char[] password) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(); Backup.writeEncrypted(out, ledger(), password); return out.toByteArray();
    }
    private static void encrypted() throws Exception {
        char[] password = "correct horse battery ₹".toCharArray();
        char[] original = password.clone(); byte[] encoded = encryptedBytes(password);
        check(Arrays.equals(original, password), "Caller retains password ownership");
        sameLedger(ledger(), Backup.readEncrypted(new ByteArrayInputStream(encoded), password));
        byte[] second = encryptedBytes(password); check(!Arrays.equals(encoded, second), "Random salt and nonce on every backup");
        check(!new String(encoded, StandardCharsets.ISO_8859_1).contains(KEY), "Ledger content is encrypted");
        rejects(() -> Backup.readEncrypted(new ByteArrayInputStream(encoded), "wrong password".toCharArray()), "Wrong password");
        for (int offset : new int[]{0, 4, 8, 10, 25, 37, encoded.length - 1}) {
            byte[] tampered = encoded.clone(); tampered[offset] ^= 1;
            rejects(() -> Backup.readEncrypted(new ByteArrayInputStream(tampered), password), "Header/ciphertext authenticated at " + offset);
        }
        for (int length : new int[]{0, 36, encoded.length - 1}) {
            byte[] truncated = Arrays.copyOf(encoded, length);
            rejects(() -> Backup.readEncrypted(new ByteArrayInputStream(truncated), password), "Truncated encrypted backup " + length);
        }
        byte[] appended = Arrays.copyOf(encoded, encoded.length + 1);
        rejects(() -> Backup.readEncrypted(new ByteArrayInputStream(appended), password), "Appended ciphertext rejected");
        rejects(() -> encryptedBytes("short".toCharArray()), "Short password");
        rejects(() -> encryptedBytes(new char[1025]), "Oversized password");
        rejects(() -> Backup.readEncrypted(new ByteArrayInputStream(encoded), null), "Missing password");
        Arrays.fill(password, '\0'); Arrays.fill(original, '\0');
    }

    private static void streamOwnership() throws Exception {
        TrackingOutput out = new TrackingOutput(); Backup.writeCsv(out, ledger()); check(!out.closed, "CSV output left open");
        TrackingInput in = new TrackingInput(out.toByteArray()); Backup.readCsv(in); check(!in.closed, "CSV input left open");
        out = new TrackingOutput(); Backup.writeEncrypted(out, ledger(), "password123".toCharArray()); check(!out.closed, "Encrypted output left open");
        in = new TrackingInput(out.toByteArray()); Backup.readEncrypted(in, "password123".toCharArray()); check(!in.closed, "Encrypted input left open");
    }

    private interface Operation { void run() throws Exception; }
    private static IOException rejects(Operation operation, String label) throws Exception {
        try { operation.run(); } catch (IOException expected) { assertions++; return expected; }
        throw new AssertionError("Expected IOException: " + label);
    }
    private static void sameLedger(List<Tx> expected, List<Tx> actual) {
        check(expected.size() == actual.size(), "Ledger row count");
        for (int i = 0; i < expected.size(); i++) {
            Tx a = expected.get(i), b = actual.get(i);
            check(a.id == b.id && a.date.equals(b.date) && a.key.equals(b.key) && a.symbol.equals(b.symbol)
                    && a.name.equals(b.name) && a.type == b.type && a.quantity.equals(b.quantity)
                    && a.price.equals(b.price) && a.fees.equals(b.fees), "Exact row roundtrip " + i);
        }
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); assertions++; }
    private static final class RepeatingInput extends InputStream {
        private int remaining;
        RepeatingInput(int remaining) { this.remaining = remaining; }
        @Override public int read() { return remaining-- > 0 ? 'a' : -1; }
        @Override public int read(byte[] bytes, int off, int len) {
            if (remaining == 0) return -1;
            int n = Math.min(remaining, len); Arrays.fill(bytes, off, off + n, (byte)'a'); remaining -= n; return n;
        }
    }
    private static final class TrackingOutput extends ByteArrayOutputStream {
        boolean closed;
        @Override public void close() { closed = true; }
    }
    private static final class ZeroFirstRead extends ByteArrayInputStream {
        private boolean first = true;
        ZeroFirstRead(byte[] data) { super(data); }
        @Override public int read(byte[] data, int offset, int length) {
            if (first) { first = false; return 0; }
            return super.read(data, offset, length);
        }
    }
    private static final class TrackingInput extends ByteArrayInputStream {
        boolean closed;
        TrackingInput(byte[] data) { super(data); }
        @Override public void close() { closed = true; }
    }
}