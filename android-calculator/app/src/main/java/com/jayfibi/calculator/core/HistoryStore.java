package com.jayfibi.calculator.core;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Single-owner, app-private history storage; use one instance per directory.
 * Entries are returned oldest-first and capped at 1000. Editing keeps an
 * entry's position and creation timestamp. All mutations commit to disk before
 * changing memory. The filesystem must support atomic moves (Android API 26+).
 * File data is synced before replacement. Portable Java has no guaranteed
 * directory fsync, so rename durability during sudden power loss is filesystem
 * dependent; ordinary interrupted writes leave the previous file intact.
 *
 * A PIN protects the entire payload with AES-256-GCM and PBKDF2-HMAC-SHA256.
 * There is deliberately no PIN reset or recovery path. PIN arrays belong to
 * callers, who should clear them after use. The store retains no PIN and clears
 * its key bytes and drops entry references on lock. JVM Strings/provider-owned
 * key copies cannot be guaranteed to be erased from managed memory.
 */
public final class HistoryStore {
    private static final byte[] MAGIC = {'J', 'F', 'C', 'H', 'I', 'S', 'T', '\n'};
    private static final int VERSION = 1;
    private static final int ITERATIONS = 210_000;
    private static final int SALT_BYTES = 16;
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BYTES = 16;
    private static final int MAX_ENTRIES = 1000;
    private static final int MAX_TEXT_CHARS = 4096;
    private static final int MAX_TEXT_BYTES = MAX_TEXT_CHARS * 4;
    private static final int MAX_ID_BYTES = 128;
    private static final int MAX_FILE_BYTES = 34 * 1024 * 1024;
    private static final String UNLOCK_FAILURE = "Incorrect PIN or damaged history.";
    private final File directory;
    private final File file;
    private final SecureRandom random = new SecureRandom();
    private List<HistoryEntry> entries = new ArrayList<>();
    private boolean protectedHistory;
    private byte[] key;
    private byte[] salt;

    /** Loads unprotected entries, or probes protected history without decrypting it. */
    public HistoryStore(File directory) throws IOException {
        if (directory == null) {
            throw new IllegalArgumentException("A history directory is required.");
        }
        this.directory = directory;
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IOException("Cannot create history directory.");
        }
        cleanupTemporaryFiles();
        file = new File(directory, "history.bin");
        if (file.exists()) {
            Envelope envelope = readEnvelope();
            try {
                protectedHistory = envelope.encrypted;
                if (!protectedHistory) {
                    entries = decodeEntries(envelope.payload);
                }
            } finally {
                envelope.clear();
            }
        }
    }

    /** Returns whether a PIN is configured, including during an unlocked session. */
    public synchronized boolean isLocked() {
        return protectedHistory;
    }

    /** Returns whether entries are accessible now; always true without a PIN. */
    public synchronized boolean isUnlocked() {
        return !protectedHistory || key != null;
    }

    /** Returns an immutable oldest-first snapshot. */
    public synchronized List<HistoryEntry> getEntries() {
        requireUnlocked();
        return Collections.unmodifiableList(new ArrayList<>(entries));
    }

    /** Wrong PINs and damaged protected payloads have the same public failure. */
    public synchronized void unlock(char[] pin) throws IOException, GeneralSecurityException {
        if (!protectedHistory || key != null) {
            return;
        }
        validatePin(pin);
        byte[] candidateKey = null;
        byte[] plaintext = null;
        Envelope envelope = null;
        try {
            envelope = readEnvelope();
            if (!envelope.encrypted) {
                throw new GeneralSecurityException(UNLOCK_FAILURE);
            }
            candidateKey = deriveKey(pin, envelope.salt);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(candidateKey, "AES"),
                    new GCMParameterSpec(TAG_BYTES * 8, envelope.nonce));
            cipher.updateAAD(envelope.header);
            plaintext = cipher.doFinal(envelope.payload);
            List<HistoryEntry> loaded = decodeEntries(plaintext);
            entries = loaded;
            salt = envelope.salt.clone();
            key = candidateKey;
            candidateKey = null;
        } catch (IOException | GeneralSecurityException exception) {
            // Do not expose authentication, parsing, or missing-file distinctions.
            throw new GeneralSecurityException(UNLOCK_FAILURE);
        } finally {
            wipe(candidateKey);
            wipe(plaintext);
            if (envelope != null) {
                envelope.clear();
            }
        }
    }

    /** Drops protected history and wipes this instance's key; no-op without a PIN. */
    public synchronized void lock() {
        if (protectedHistory) {
            wipe(key);
            wipe(salt);
            key = null;
            salt = null;
            entries = new ArrayList<>();
        }
    }

    public synchronized void add(String expression, String result)
            throws IOException, GeneralSecurityException {
        requireUnlocked();
        validateText(expression);
        validateText(result);
        List<HistoryEntry> candidate = new ArrayList<>(entries);
        if (candidate.size() == MAX_ENTRIES) {
            candidate.remove(0);
        }
        candidate.add(new HistoryEntry(UUID.randomUUID().toString(), expression, result,
                System.currentTimeMillis()));
        save(candidate, key, salt);
        entries = candidate;
    }

    public synchronized void update(String id, String expression, String result)
            throws IOException, GeneralSecurityException {
        requireUnlocked();
        validateText(expression);
        validateText(result);
        int index = indexOf(id);
        HistoryEntry old = entries.get(index);
        List<HistoryEntry> candidate = new ArrayList<>(entries);
        candidate.set(index, new HistoryEntry(old.id, expression, result, old.timestamp));
        save(candidate, key, salt);
        entries = candidate;
    }

    public synchronized void delete(String id) throws IOException, GeneralSecurityException {
        requireUnlocked();
        List<HistoryEntry> candidate = new ArrayList<>(entries);
        candidate.remove(indexOf(id));
        save(candidate, key, salt);
        entries = candidate;
    }

    public synchronized void clear() throws IOException, GeneralSecurityException {
        requireUnlocked();
        List<HistoryEntry> candidate = new ArrayList<>();
        save(candidate, key, salt);
        entries = candidate;
    }

    /** Adds or changes a PIN; changing an existing PIN requires an unlocked session. */
    public synchronized void setPin(char[] pin) throws IOException, GeneralSecurityException {
        requireUnlocked();
        validatePin(pin);
        // A crash during an earlier unprotected save may have left plaintext.
        // Never report PIN protection enabled while one of our orphan files remains.
        cleanupTemporaryFiles();
        byte[] candidateSalt = new byte[SALT_BYTES];
        random.nextBytes(candidateSalt);
        byte[] candidateKey = null;
        try {
            candidateKey = deriveKey(pin, candidateSalt);
            save(entries, candidateKey, candidateSalt);
            wipe(key);
            wipe(salt);
            key = candidateKey;
            salt = candidateSalt;
            candidateKey = null;
            candidateSalt = null;
            protectedHistory = true;
        } finally {
            wipe(candidateKey);
            wipe(candidateSalt);
        }
    }

    public synchronized void changePin(char[] pin) throws IOException, GeneralSecurityException {
        setPin(pin);
    }

    /** Converts to unprotected history only after a successful authenticated unlock. */
    public synchronized void removePin() throws IOException, GeneralSecurityException {
        requireUnlocked();
        if (!protectedHistory) {
            return;
        }
        save(entries, null, null);
        wipe(key);
        wipe(salt);
        key = null;
        salt = null;
        protectedHistory = false;
    }

    private void requireUnlocked() {
        if (!isUnlocked()) {
            throw new IllegalStateException("Unlock history first.");
        }
    }

    private int indexOf(String id) {
        for (int index = 0; index < entries.size(); index++) {
            if (entries.get(index).id.equals(id)) {
                return index;
            }
        }
        throw new IllegalArgumentException("History entry was not found.");
    }

    private static void validatePin(char[] pin) {
        if (pin == null || pin.length < 4 || pin.length > 12) {
            throw new IllegalArgumentException("PIN must contain 4 to 12 digits.");
        }
        for (char digit : pin) {
            if (digit < '0' || digit > '9') {
                throw new IllegalArgumentException("PIN must contain 4 to 12 digits.");
            }
        }
    }

    private static void validateText(String text) {
        if (text == null || text.trim().isEmpty() || text.length() > MAX_TEXT_CHARS) {
            throw new IllegalArgumentException("History text must contain 1 to 4096 characters.");
        }
    }

    private static byte[] deriveKey(char[] pin, byte[] salt) throws GeneralSecurityException {
        PBEKeySpec spec = new PBEKeySpec(pin, salt, ITERATIONS, 256);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                    .generateSecret(spec).getEncoded();
        } finally {
            spec.clearPassword();
        }
    }

    private void save(List<HistoryEntry> candidate, byte[] saveKey, byte[] saveSalt)
            throws IOException, GeneralSecurityException {
        byte[] plaintext = encodeEntries(candidate);
        byte[] payload = null;
        byte[] header = null;
        byte[] nonce = null;
        try {
            boolean encrypted = saveKey != null;
            if (encrypted) {
                nonce = new byte[NONCE_BYTES];
                random.nextBytes(nonce);
            }
            header = encodeHeader(encrypted, saveSalt, nonce,
                    plaintext.length + (encrypted ? TAG_BYTES : 0));
            if (encrypted) {
                Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(saveKey, "AES"),
                        new GCMParameterSpec(TAG_BYTES * 8, nonce));
                cipher.updateAAD(header);
                payload = cipher.doFinal(plaintext);
            } else {
                payload = plaintext;
            }
            atomicWrite(header, payload);
        } finally {
            wipe(plaintext);
            wipe(payload);
            wipe(header);
            wipe(nonce);
        }
    }

    private void atomicWrite(byte[] header, byte[] payload) throws IOException {
        if ((long) header.length + payload.length > MAX_FILE_BYTES) {
            throw new IOException("History file exceeds its size limit.");
        }
        File temporary = File.createTempFile("history-", ".tmp", directory);
        try {
            try (FileOutputStream output = new FileOutputStream(temporary)) {
                output.write(header);
                output.write(payload);
                output.flush();
                output.getFD().sync();
            }
            // Never fall back to delete-then-rename: losing the previous file is unsafe.
            Files.move(temporary.toPath(), file.toPath(),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            // If replacement succeeded, the temporary path no longer exists.
            // Cleanup failure must not report a committed save as uncommitted.
            temporary.delete();
        }
    }

    private void cleanupTemporaryFiles() throws IOException {
        File[] stale = directory.listFiles((parent, name) -> name.matches("history-[0-9]+\\.tmp"));
        if (stale == null) {
            throw new IOException("Cannot inspect history directory.");
        }
        for (File temporary : stale) {
            if (!temporary.delete()) {
                throw new IOException("Cannot remove an interrupted history save.");
            }
        }
    }

    private static byte[] encodeHeader(boolean encrypted, byte[] salt, byte[] nonce,
            int payloadLength) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.write(MAGIC);
            output.writeInt(VERSION);
            output.writeByte(encrypted ? 1 : 0);
            if (encrypted) {
                output.writeInt(ITERATIONS);
                output.write(salt);
                output.write(nonce);
            }
            output.writeInt(payloadLength);
        }
        return bytes.toByteArray();
    }

    private Envelope readEnvelope() throws IOException {
        byte[] bytes = null;
        try (FileInputStream stream = new FileInputStream(file);
                DataInputStream input = new DataInputStream(stream)) {
            long size = stream.getChannel().size();
            if (size < 21 || size > MAX_FILE_BYTES) {
                throw damaged();
            }
            bytes = new byte[(int) size];
            input.readFully(bytes);
            if (input.read() != -1) {
                throw damaged();
            }
            return parseEnvelope(bytes);
        } finally {
            wipe(bytes);
        }
    }

    private static Envelope parseEnvelope(byte[] bytes) throws IOException {
        Envelope envelope = new Envelope();
        boolean success = false;
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes))) {
            byte[] magic = new byte[MAGIC.length];
            input.readFully(magic);
            if (!Arrays.equals(magic, MAGIC) || input.readInt() != VERSION) {
                throw damaged();
            }
            int flag = input.readUnsignedByte();
            if (flag != 0 && flag != 1) {
                throw damaged();
            }
            envelope.encrypted = flag == 1;
            if (envelope.encrypted) {
                if (input.readInt() != ITERATIONS) {
                    throw damaged();
                }
                envelope.salt = new byte[SALT_BYTES];
                envelope.nonce = new byte[NONCE_BYTES];
                input.readFully(envelope.salt);
                input.readFully(envelope.nonce);
            }
            int payloadSize = input.readInt();
            if (payloadSize != input.available()
                    || payloadSize < (envelope.encrypted ? TAG_BYTES + 4 : 4)) {
                throw damaged();
            }
            envelope.header = Arrays.copyOf(bytes, bytes.length - payloadSize);
            envelope.payload = new byte[payloadSize];
            input.readFully(envelope.payload);
            success = true;
            return envelope;
        } finally {
            if (!success) {
                envelope.clear();
            }
        }
    }

    private static byte[] encodeEntries(List<HistoryEntry> entries) throws IOException {
        WipingBuffer bytes = new WipingBuffer();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeInt(entries.size());
            for (HistoryEntry entry : entries) {
                writeString(output, entry.id, MAX_ID_BYTES);
                writeString(output, entry.expression, MAX_TEXT_BYTES);
                writeString(output, entry.result, MAX_TEXT_BYTES);
                output.writeLong(entry.timestamp);
            }
            return bytes.toByteArray();
        } finally {
            bytes.wipe();
        }
    }

    private static List<HistoryEntry> decodeEntries(byte[] payload) throws IOException {
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(payload))) {
            int count = input.readInt();
            if (count < 0 || count > MAX_ENTRIES) {
                throw damaged();
            }
            List<HistoryEntry> loaded = new ArrayList<>(count);
            Set<String> ids = new HashSet<>();
            for (int index = 0; index < count; index++) {
                String id = readString(input, MAX_ID_BYTES);
                String expression = readString(input, MAX_TEXT_BYTES);
                String result = readString(input, MAX_TEXT_BYTES);
                long timestamp = input.readLong();
                if (id.isEmpty() || !ids.add(id) || timestamp < 0) {
                    throw damaged();
                }
                try {
                    validateText(expression);
                    validateText(result);
                } catch (IllegalArgumentException exception) {
                    throw damaged();
                }
                loaded.add(new HistoryEntry(id, expression, result, timestamp));
            }
            if (input.available() != 0) {
                throw damaged();
            }
            return loaded;
        }
    }

    private static void writeString(DataOutputStream output, String value, int maximum)
            throws IOException {
        ByteBuffer encoded = StandardCharsets.UTF_8.newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(value));
        try {
            if (encoded.remaining() > maximum) {
                throw new IOException("History text exceeds its size limit.");
            }
            output.writeInt(encoded.remaining());
            while (encoded.hasRemaining()) {
                output.writeByte(encoded.get());
            }
        } finally {
            if (encoded.hasArray()) {
                wipe(encoded.array());
            }
        }
    }

    private static String readString(DataInputStream input, int maximum) throws IOException {
        int length = input.readInt();
        if (length < 1 || length > maximum || length > input.available()) {
            throw damaged();
        }
        byte[] encoded = new byte[length];
        try {
            input.readFully(encoded);
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(encoded)).toString();
        } catch (CharacterCodingException exception) {
            throw damaged();
        } finally {
            wipe(encoded);
        }
    }

    private static IOException damaged() {
        return new IOException("History file is damaged or unsupported.");
    }

    private static void wipe(byte[] bytes) {
        if (bytes != null) {
            Arrays.fill(bytes, (byte) 0);
        }
    }

    private static final class WipingBuffer extends ByteArrayOutputStream {
        void wipe() {
            HistoryStore.wipe(buf);
            reset();
        }
    }

    private static final class Envelope {
        boolean encrypted;
        byte[] salt;
        byte[] nonce;
        byte[] header;
        byte[] payload;

        void clear() {
            wipe(salt);
            wipe(nonce);
            wipe(header);
            wipe(payload);
        }
    }
}