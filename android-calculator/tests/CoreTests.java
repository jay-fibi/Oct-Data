import com.jayfibi.calculator.core.CalculatorEngine;
import com.jayfibi.calculator.core.HistoryEntry;
import com.jayfibi.calculator.core.HistoryStore;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/** Plain Java assertion suite. No Android, test framework, or external library. */
public final class CoreTests {
    private static int assertions;
    private static int groups;

    private CoreTests() {
    }

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("calculator-core-tests-");
        try {
            run("calculator arithmetic and precedence", CoreTests::arithmetic);
            run("calculator invalid inputs and bounds", CoreTests::invalidExpressions);
            run("history persistence, edits, and snapshots", () -> persistence(root));
            run("history encryption, PIN lifecycle, and key wiping", () -> encryption(root));
            run("history malformed file bounds and authentication", () -> corruption(root));
            run("history atomic write failures", () -> atomicFailures(root));
            run("history interrupted-save cleanup", () -> temporaryCleanup(root));
            run("history predictable 1000-entry limit", () -> retention(root));
            System.out.println("PASS: " + assertions + " assertions in " + groups + " test groups.");
        } finally {
            try (Stream<Path> paths = Files.walk(root)) {
                paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.delete(path);
                    } catch (IOException exception) {
                        throw new IllegalStateException("Could not clean test path " + path, exception);
                    }
                });
            }
        }
    }

    private static void arithmetic() {
        result("2+3*4", "14");
        result("(2+3)*4", "20");
        result("18/3/2", "3");
        result("9-3-2", "4");
        result("2*-3 + +4", "-2");
        result("2--3", "5");
        result("---2", "-2");
        result("--(-(+3))", "-3");
        result("\n ( .5 + 2. ) \t", "2.5");
        result("0.1+0.2", "0.3");
        result("1/8", "0.125");
        result("1/3", "0.3333333333333333333333333333333333");
        result("2/3", "0.6666666666666666666666666666666667");
        result("1000000000000000000000000000000+1", "1000000000000000000000000000001");
        result("0.00000000000000000001*0.00000000000000000001",
                "0.0000000000000000000000000000000000000001");
        result("1.230000", "1.23");
        result("-0.000", "0");
        result("-2+2", "0");
        result("50%", "0.5");
        result("200+10%", "200.1");
        result("200*10%", "20");
        result("(25+25)%", "0.5");
        result("100%%", "0.01");
        result("-50%", "-0.5");
        result("2/50%", "4");
        result("8\u00f72\u00d73\u22121", "11");
        result("0007.5000 + .5000", "8");
        result(repeat("(", 128) + "1" + repeat(")", 128), "1");
        result(repeat("+", 4095) + "1", "1");
        result(repeat("0", 4095) + "1", "1");
    }

    private static void invalidExpressions() {
        String[] invalid = {null, "", " ", ".", "+", "-", "()", "1+", "*2", "/2",
                "1..2", "1.2.3", "1 2", "1e3", "NaN", "Infinity", "1/0", "0/0",
                "1/(-0)", "1/(2-2)", "1/0%", "2(3)", "(2)3", "2**3", "2//3",
                "(1+2", "1+2)", "1,2", "1;2", "%1", "\u0661+2", "1\u0000+2",
                repeat("1", 4097), repeat("(", 129) + "1" + repeat(")", 129),
                "1" + repeat("%", 2048)};
        for (String expression : invalid) {
            expect(IllegalArgumentException.class, () -> CalculatorEngine.evaluate(expression));
        }
        // A compact expression whose reciprocal exceeds the output magnitude limit.
        expect(IllegalArgumentException.class,
                () -> CalculatorEngine.evaluate("1/(1" + repeat("%", 2048) + ")"));
    }

    private static void persistence(Path root) throws Exception {
        File directory = root.resolve("plain").toFile();
        HistoryStore store = new HistoryStore(directory);
        check(!store.isLocked() && store.isUnlocked(), "New history is unprotected and accessible");
        equal(0, store.getEntries().size(), "New history is empty");
        store.lock();
        check(store.isUnlocked(), "Lock is a no-op without a PIN");
        store.unlock(null);
        store.add("2+2", "4");
        store.add("8\u00f72", "4");
        List<HistoryEntry> snapshot = store.getEntries();
        HistoryEntry first = snapshot.get(0);
        check(first.timestamp > 0, "Timestamp is populated");
        check(!first.id.equals(snapshot.get(1).id), "IDs are unique");
        expect(UnsupportedOperationException.class, () -> snapshot.clear());
        store.update(first.id, "2+3", "5");
        equal("2+2", snapshot.get(0).expression, "Snapshots are not mutated");
        equal(first.id, store.getEntries().get(0).id, "Edit preserves ID");
        equal(first.timestamp, store.getEntries().get(0).timestamp, "Edit preserves timestamp");
        HistoryStore reloaded = new HistoryStore(directory);
        equal("2+3", reloaded.getEntries().get(0).expression, "Edit persists");
        equal("8\u00f72", reloaded.getEntries().get(1).expression, "UTF-8 persists");
        reloaded.delete(first.id);
        equal(1, new HistoryStore(directory).getEntries().size(), "Delete persists");
        expect(IllegalArgumentException.class, () -> reloaded.delete("absent"));
        expect(IllegalArgumentException.class, () -> reloaded.update("absent", "1", "1"));
        expect(IllegalArgumentException.class, () -> reloaded.add(null, "1"));
        expect(IllegalArgumentException.class, () -> reloaded.add("", "1"));
        expect(IllegalArgumentException.class, () -> reloaded.add("1", " \t"));
        expect(IllegalArgumentException.class, () -> reloaded.add(repeat("1", 4097), "1"));
        byte[] beforeBadText = Files.readAllBytes(history(directory));
        expect(IOException.class, () -> reloaded.add("\ud800", "1"));
        check(Arrays.equals(beforeBadText, Files.readAllBytes(history(directory))),
                "Invalid UTF-16 cannot replace the file");
        reloaded.clear();
        equal(0, new HistoryStore(directory).getEntries().size(), "Clear persists");
        assertSingleFile(directory);
        expect(IllegalArgumentException.class, () -> new HistoryEntry(null, "1", "1", 0));
        expect(IllegalArgumentException.class, () -> new HistoryStore(null));
        expect(IOException.class, () -> new HistoryStore(history(directory).toFile()));
    }

    private static void encryption(Path root) throws Exception {
        File directory = root.resolve("encrypted").toFile();
        HistoryStore store = new HistoryStore(directory);
        String secret = "943857209384750928347509823475+1";
        store.add(secret, "943857209384750928347509823476");
        HistoryEntry first = store.getEntries().get(0);
        check(contains(Files.readAllBytes(history(directory)), secret), "Unprotected file is plaintext");
        for (String invalid : new String[]{"", "123", "1234567890123", "12x4", " 1234", "\u0661\u0662\u0663\u0664"}) {
            expect(IllegalArgumentException.class, () -> store.setPin(invalid.toCharArray()));
        }
        expect(IllegalArgumentException.class, () -> store.setPin(null));
        check(!store.isLocked(), "Invalid PINs do not protect history");
        char[] pin = "0123".toCharArray();
        store.setPin(pin);
        check(Arrays.equals("0123".toCharArray(), pin), "Caller owns PIN array");
        Arrays.fill(pin, '\0');
        check(store.isLocked() && store.isUnlocked(), "Protected session remains unlocked");
        byte[] encrypted = Files.readAllBytes(history(directory));
        check(!contains(encrypted, secret), "Protected file contains no visible expression");
        check(!contains(encrypted, first.id), "IDs are encrypted");
        check(!contains(encrypted, first.result), "Results are encrypted");
        equal(210_000, intAt(encrypted, 13), "PBKDF2 iterations are 210000");
        equal(1, (int) encrypted[12], "File protection flag is set");
        assertSingleFile(directory);

        HistoryStore reloaded = new HistoryStore(directory);
        check(reloaded.isLocked() && !reloaded.isUnlocked(), "Protected reload starts inaccessible");
        expect(IllegalStateException.class, reloaded::getEntries);
        expect(IllegalStateException.class, () -> reloaded.add("1", "1"));
        expect(IllegalStateException.class, () -> reloaded.update(first.id, "1", "1"));
        expect(IllegalStateException.class, () -> reloaded.delete(first.id));
        expect(IllegalStateException.class, reloaded::clear);
        expect(IllegalStateException.class, () -> reloaded.setPin("9999".toCharArray()));
        expect(IllegalStateException.class, () -> reloaded.changePin("9999".toCharArray()));
        expect(IllegalStateException.class, reloaded::removePin);
        GeneralSecurityException wrong = expect(GeneralSecurityException.class,
                () -> reloaded.unlock("0000".toCharArray()));
        equal("Incorrect PIN or damaged history.", wrong.getMessage(), "Generic authentication error");
        check(!reloaded.isUnlocked(), "Wrong PIN leaves history inaccessible");
        check(Arrays.equals(encrypted, Files.readAllBytes(history(directory))),
                "Failed unlock leaves disk unchanged");
        reloaded.unlock("0123".toCharArray());
        equal(secret, reloaded.getEntries().get(0).expression, "Correct PIN restores history");
        reloaded.update(first.id, "7*8", "56");
        byte[] afterEdit = Files.readAllBytes(history(directory));
        check(!Arrays.equals(Arrays.copyOfRange(encrypted, 33, 45),
                Arrays.copyOfRange(afterEdit, 33, 45)), "Every encrypted save has a fresh nonce");
        check(Arrays.equals(Arrays.copyOfRange(encrypted, 17, 33),
                Arrays.copyOfRange(afterEdit, 17, 33)), "A session retains its derivation salt");
        reloaded.add("100-1", "99");
        reloaded.delete(reloaded.getEntries().get(1).id);

        Field keyField = HistoryStore.class.getDeclaredField("key");
        keyField.setAccessible(true);
        byte[] retainedKey = (byte[]) keyField.get(reloaded);
        check(retainedKey.length == 32, "AES key has 256 bits");
        reloaded.lock();
        check(allZero(retainedKey), "Lock wipes the retained key bytes");
        check(keyField.get(reloaded) == null, "Lock releases the retained key");
        reloaded.lock();
        expect(IllegalStateException.class, reloaded::getEntries);
        reloaded.unlock("0123".toCharArray());
        equal("7*8", reloaded.getEntries().get(0).expression, "Protected edit persists");
        byte[] oldKey = (byte[]) keyField.get(reloaded);
        reloaded.changePin("123456789012".toCharArray());
        check(allZero(oldKey), "Changing PIN wipes the previous key");
        check(!Arrays.equals(Arrays.copyOfRange(afterEdit, 17, 33),
                Arrays.copyOfRange(Files.readAllBytes(history(directory)), 17, 33)),
                "PIN change has a fresh salt");
        reloaded.lock();
        expect(GeneralSecurityException.class, () -> reloaded.unlock("0123".toCharArray()));
        reloaded.unlock("123456789012".toCharArray());
        equal(1, reloaded.getEntries().size(), "PIN changes preserve history");
        byte[] removalKey = (byte[]) keyField.get(reloaded);
        reloaded.removePin();
        check(allZero(removalKey), "Removing PIN wipes the previous key");
        check(!reloaded.isLocked() && reloaded.isUnlocked(), "PIN removal restores unprotected state");
        check(contains(Files.readAllBytes(history(directory)), "7*8"), "PIN removal writes unprotected data");
        HistoryStore unprotected = new HistoryStore(directory);
        equal("56", unprotected.getEntries().get(0).result, "PIN removal persists");
        unprotected.setPin("9876".toCharArray());
        unprotected.clear();
        unprotected.lock();
        HistoryStore emptyProtected = new HistoryStore(directory);
        check(emptyProtected.isLocked(), "Clearing history retains PIN protection");
        emptyProtected.unlock("9876".toCharArray());
        equal(0, emptyProtected.getEntries().size(), "Protected clear persists");
        assertSingleFile(directory);
    }

    private static void corruption(Path root) throws Exception {
        File directory = root.resolve("corrupt").toFile();
        HistoryStore store = new HistoryStore(directory);
        store.add("40+2", "42");
        byte[] plain = Files.readAllBytes(history(directory));
        int[] offsets = {0, 11, 12, 13, 17, 21};
        for (int offset : offsets) {
            byte[] changed = plain.clone();
            changed[offset] = (byte) 0xff;
            Files.write(history(directory), changed);
            expect(IOException.class, () -> new HistoryStore(directory));
        }
        for (int length : new int[]{0, 1, 8, 12, 17, plain.length - 1}) {
            Files.write(history(directory), Arrays.copyOf(plain, length));
            expect(IOException.class, () -> new HistoryStore(directory));
        }
        Files.write(history(directory), Arrays.copyOf(plain, plain.length + 1));
        expect(IOException.class, () -> new HistoryStore(directory));
        Files.write(history(directory), plain);
        try (RandomAccessFile oversized = new RandomAccessFile(history(directory).toFile(), "rw")) {
            oversized.setLength(34L * 1024 * 1024 + 1);
        }
        expect(IOException.class, () -> new HistoryStore(directory));
        Files.write(history(directory), plain);

        byte[] malformedUtf8 = plain.clone();
        malformedUtf8[25] = (byte) 0xff;
        Files.write(history(directory), malformedUtf8);
        expect(IOException.class, () -> new HistoryStore(directory));
        Files.write(history(directory), plainEnvelope(entryPayload(2, "same", 1)));
        expect(IOException.class, () -> new HistoryStore(directory));
        Files.write(history(directory), plainEnvelope(entryPayload(1, "id", -1)));
        expect(IOException.class, () -> new HistoryStore(directory));
        byte[] trailingPayload = Arrays.copyOf(entryPayload(1, "id", 1), 36);
        Files.write(history(directory), plainEnvelope(trailingPayload));
        expect(IOException.class, () -> new HistoryStore(directory));
        Files.write(history(directory), plain);

        store.setPin("1234".toCharArray());
        byte[] protectedBytes = Files.readAllBytes(history(directory));
        byte[] tampered = protectedBytes.clone();
        tampered[tampered.length - 1] ^= 1;
        Files.write(history(directory), tampered);
        HistoryStore damaged = new HistoryStore(directory);
        GeneralSecurityException corrupt = expect(GeneralSecurityException.class,
                () -> damaged.unlock("1234".toCharArray()));
        equal("Incorrect PIN or damaged history.", corrupt.getMessage(), "Corruption hides authentication detail");
        check(corrupt.getCause() == null, "Authentication failure does not expose nested errors");
        check(!damaged.isUnlocked(), "Corruption cannot unlock history");
        check(Arrays.equals(tampered, Files.readAllBytes(history(directory))),
                "Corrupt protected data is never silently replaced");

        for (int offset : new int[]{17, 33}) {
            byte[] changed = protectedBytes.clone();
            changed[offset] ^= 1;
            Files.write(history(directory), changed);
            HistoryStore alteredHeader = new HistoryStore(directory);
            expect(GeneralSecurityException.class, () -> alteredHeader.unlock("1234".toCharArray()));
        }
        byte[] hugeIterations = protectedBytes.clone();
        hugeIterations[13] = 0x7f;
        Files.write(history(directory), hugeIterations);
        expect(IOException.class, () -> new HistoryStore(directory));
        Files.write(history(directory), protectedBytes);
        HistoryStore probed = new HistoryStore(directory);
        Files.write(history(directory), new byte[]{0});
        expect(GeneralSecurityException.class, () -> probed.unlock("1234".toCharArray()));
        Files.write(history(directory), plain);
        expect(GeneralSecurityException.class, () -> probed.unlock("1234".toCharArray()));
    }

    private static void atomicFailures(Path root) throws Exception {
        File directory = root.resolve("atomic").toFile();
        HistoryStore store = new HistoryStore(directory);
        store.add("10+2", "12");
        HistoryEntry old = store.getEntries().get(0);
        Path original = history(directory);
        Path backup = directory.toPath().resolve("saved-for-test.bin");
        Files.move(original, backup);
        Files.createDirectory(original);
        Files.write(original.resolve("obstruction"), new byte[]{1});
        expect(IOException.class, () -> store.add("3", "3"));
        equal(1, store.getEntries().size(), "Failed add preserves entries");
        expect(IOException.class, () -> store.update(old.id, "4", "4"));
        equal("10+2", store.getEntries().get(0).expression, "Failed edit preserves memory");
        expect(IOException.class, () -> store.delete(old.id));
        expect(IOException.class, store::clear);
        expect(IOException.class, () -> store.setPin("1234".toCharArray()));
        check(!store.isLocked() && store.isUnlocked(), "Failed PIN conversion preserves state");
        equal(1, store.getEntries().size(), "Failed deletion and clear preserve memory");
        check(noTemporaryFiles(directory), "Failed atomic moves clean up temporary files");
        restoreFile(original, backup);
        equal("12", new HistoryStore(directory).getEntries().get(0).result,
                "Original saved file is intact after failed writes");

        store.setPin("1234".toCharArray());
        byte[] protectedBytes = Files.readAllBytes(original);
        Files.move(original, backup);
        Files.createDirectory(original);
        Files.write(original.resolve("obstruction"), new byte[]{1});
        expect(IOException.class, () -> store.add("5", "5"));
        expect(IOException.class, () -> store.update(old.id, "6", "6"));
        expect(IOException.class, () -> store.changePin("5678".toCharArray()));
        expect(IOException.class, store::removePin);
        check(store.isLocked() && store.isUnlocked(), "Failed PIN operations retain protection and access");
        equal("10+2", store.getEntries().get(0).expression, "Failed protected edit preserves memory");
        check(noTemporaryFiles(directory), "Failed protected writes clean up temporary files");
        check(Arrays.equals(protectedBytes, Files.readAllBytes(backup)), "Previous encrypted file is untouched");
        restoreFile(original, backup);
        store.lock();
        expect(GeneralSecurityException.class, () -> store.unlock("5678".toCharArray()));
        store.unlock("1234".toCharArray());
        equal("12", store.getEntries().get(0).result, "Old PIN still works after failed PIN change");
        assertSingleFile(directory);

        // A missing parent also fails before committing any state.
        File missing = root.resolve("missing-parent").toFile();
        HistoryStore neverSaved = new HistoryStore(missing);
        Files.delete(missing.toPath());
        expect(IOException.class, () -> neverSaved.add("1", "1"));
        equal(0, neverSaved.getEntries().size(), "Missing parent leaves memory unchanged");
    }

    private static void retention(Path root) throws Exception {
        File directory = root.resolve("retention").toFile();
        HistoryStore store = new HistoryStore(directory);
        store.add("oldest", "0");
        String oldest = store.getEntries().get(0).id;
        for (int index = 1; index < 1000; index++) {
            store.add(Integer.toString(index), Integer.toString(index));
        }
        store.update(oldest, "edited oldest", "0");
        store.add("1000", "1000");
        List<HistoryEntry> retained = store.getEntries();
        equal(1000, retained.size(), "Retention is capped at 1000");
        equal("1", retained.get(0).expression, "Oldest insertion drops, even after an edit");
        equal("1000", retained.get(999).expression, "Newest insertion is retained");
        List<HistoryEntry> reloaded = new HistoryStore(directory).getEntries();
        equal(1000, reloaded.size(), "Retention cap persists");
        equal("1", reloaded.get(0).expression, "Retention order persists");
        assertSingleFile(directory);
    }

    private static void temporaryCleanup(Path root) throws Exception {
        File directory = root.resolve("cleanup").toFile();
        check(directory.mkdir(), "Cleanup test directory created");
        Path orphan = directory.toPath().resolve("history-12345.tmp");
        Path unrelated = directory.toPath().resolve("history-not-ours.tmp");
        Files.write(orphan, "sensitive orphan plaintext".getBytes(StandardCharsets.UTF_8));
        Files.write(unrelated, new byte[]{1});
        HistoryStore store = new HistoryStore(directory);
        check(!Files.exists(orphan), "Constructor removes orphaned write files");
        check(Files.exists(unrelated), "Cleanup does not remove unrelated files");
        store.add("1+2", "3");
        Files.write(orphan, "sensitive orphan plaintext".getBytes(StandardCharsets.UTF_8));
        store.setPin("1234".toCharArray());
        check(!Files.exists(orphan), "PIN setup also removes orphaned write files");
        store.removePin();
        Files.createDirectory(orphan);
        Files.write(orphan.resolve("cannot-delete"), new byte[]{1});
        byte[] saved = Files.readAllBytes(history(directory));
        expect(IOException.class, () -> store.setPin("1234".toCharArray()));
        check(!store.isLocked(), "PIN setup fails closed when orphan cleanup fails");
        check(Arrays.equals(saved, Files.readAllBytes(history(directory))),
                "Cleanup failure does not change the history file");
        expect(IOException.class, () -> new HistoryStore(directory));
    }

    private static void restoreFile(Path original, Path backup) throws IOException {
        Files.delete(original.resolve("obstruction"));
        Files.delete(original);
        Files.move(backup, original);
    }

    private static byte[] entryPayload(int count, String id, long timestamp) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeInt(count);
            for (int index = 0; index < count; index++) {
                for (String field : new String[]{id, "1", "1"}) {
                    byte[] encoded = field.getBytes(StandardCharsets.UTF_8);
                    output.writeInt(encoded.length);
                    output.write(encoded);
                }
                output.writeLong(timestamp);
            }
        }
        return bytes.toByteArray();
    }

    private static byte[] plainEnvelope(byte[] payload) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.write("JFCHIST\n".getBytes(StandardCharsets.US_ASCII));
            output.writeInt(1);
            output.writeByte(0);
            output.writeInt(payload.length);
            output.write(payload);
        }
        return bytes.toByteArray();
    }

    private static boolean noTemporaryFiles(File directory) {
        File[] files = directory.listFiles((ignored, name) -> name.endsWith(".tmp"));
        return files != null && files.length == 0;
    }

    private static int intAt(byte[] bytes, int offset) {
        return ((bytes[offset] & 255) << 24) | ((bytes[offset + 1] & 255) << 16)
                | ((bytes[offset + 2] & 255) << 8) | (bytes[offset + 3] & 255);
    }

    private static boolean allZero(byte[] bytes) {
        for (byte value : bytes) {
            if (value != 0) {
                return false;
            }
        }
        return true;
    }

    private static boolean contains(byte[] bytes, String text) {
        return new String(bytes, StandardCharsets.ISO_8859_1).contains(text);
    }

    private static Path history(File directory) {
        return directory.toPath().resolve("history.bin");
    }

    private static void assertSingleFile(File directory) {
        String[] files = directory.list();
        check(files != null && files.length == 1 && files[0].equals("history.bin"),
                "Only the versioned history file remains");
    }

    private static String repeat(String value, int count) {
        StringBuilder result = new StringBuilder(value.length() * count);
        for (int index = 0; index < count; index++) {
            result.append(value);
        }
        return result.toString();
    }

    private static void result(String expression, String expected) {
        equal(expected, CalculatorEngine.evaluate(expression), "evaluate(" + expression + ")");
    }

    private static void check(boolean condition, String message) {
        assertions++;
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void equal(Object expected, Object actual, String message) {
        check(expected.equals(actual), message + ": expected <" + expected + "> but was <" + actual + ">");
    }

    private static <T extends Throwable> T expect(Class<T> type, CheckedRunnable action) {
        assertions++;
        try {
            action.run();
        } catch (Throwable failure) {
            if (type.isInstance(failure)) {
                return type.cast(failure);
            }
            throw new AssertionError("Expected " + type.getSimpleName() + " but got " + failure, failure);
        }
        throw new AssertionError("Expected " + type.getSimpleName() + " but action succeeded");
    }

    private static void run(String name, CheckedRunnable action) throws Exception {
        long start = System.nanoTime();
        action.run();
        groups++;
        System.out.println("PASS " + name + " (" + ((System.nanoTime() - start) / 1_000_000) + " ms)");
    }

    private interface CheckedRunnable {
        void run() throws Exception;
    }
}