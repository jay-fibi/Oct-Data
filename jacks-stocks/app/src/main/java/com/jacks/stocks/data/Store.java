package com.jacks.stocks.data;

import android.content.Context;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import com.jacks.stocks.core.Portfolio;
import com.jacks.stocks.core.Portfolio.Tx;
import com.jacks.stocks.core.Portfolio.Quote;
import com.jacks.stocks.core.Portfolio.Candle;
import com.jacks.stocks.core.Portfolio.Type;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Map;
import java.util.LinkedHashMap;

/** SQLite ledger and rebuildable market cache. */
public final class Store implements AutoCloseable {
    private final Database helper;
    private static final int MAX_ROWS = 100_000;

    public static final class Instrument {
        public final String key, symbol, name;
        public Instrument(String key, String symbol, String name) {
            this.key = key; this.symbol = symbol; this.name = name;
        }
    }

    public Store(Context context) {
        helper = new Database(Objects.requireNonNull(context).getApplicationContext());
        helper.setWriteAheadLoggingEnabled(true);
    }

    public synchronized List<Tx> transactions() {
        return readTransactions(helper.getReadableDatabase());
    }

    private static List<Tx> readTransactions(SQLiteDatabase db) {
        List<Tx> result = new ArrayList<>();
        try (Cursor c = db.query("ledger", null, null, null, null, null, "date ASC, id ASC")) {
            while (c.moveToNext()) {
                if (result.size() >= MAX_ROWS) throw new IllegalArgumentException("Ledger exceeds 100,000 rows.");
                result.add(new Tx(c.getLong(c.getColumnIndexOrThrow("id")),
                        LocalDate.parse(text(c, "date")), text(c, "instrument_key"), text(c, "symbol"),
                        text(c, "name"), Type.valueOf(text(c, "type")), decimal(c, "quantity"),
                        decimal(c, "price"), decimal(c, "fees")));
            }
        }
        return result;
    }

    public synchronized List<Quote> quotes() {
        List<Quote> result = new ArrayList<>();
        try (Cursor c = helper.getReadableDatabase().query("quotes", null, null, null, null, null, "instrument_key")) {
            while (c.moveToNext()) result.add(new Quote(text(c, "instrument_key"), decimal(c, "price"),
                    decimal(c, "previous_close"), c.getLong(c.getColumnIndexOrThrow("as_of")),
                    c.getLong(c.getColumnIndexOrThrow("fetched"))));
        }
        return result;
    }

    public synchronized List<Candle> candles() {
        List<Candle> result = new ArrayList<>();
        try (Cursor c = helper.getReadableDatabase().query("candles", null, null, null, null, null, "date ASC, instrument_key ASC")) {
            while (c.moveToNext()) result.add(new Candle(text(c, "instrument_key"),
                    LocalDate.parse(text(c, "date")), decimal(c, "close")));
        }
        return result;
    }

    /** Edits are tentative until the complete ledger replays successfully; failure rolls back. */
    public synchronized void save(Tx tx) {
        Objects.requireNonNull(tx, "Transaction is required.");
        if (tx.id < 0) throw new IllegalArgumentException("Invalid transaction ID.");
        atomic(db -> {
            ContentValues values = values(tx);
            if (tx.id == 0) db.insertOrThrow("ledger", null, values);
            else if (db.update("ledger", values, "id = ?", new String[]{Long.toString(tx.id)}) != 1)
                throw new IllegalArgumentException("Transaction no longer exists.");
            Portfolio.validate(readTransactions(db));
        });
    }

    public synchronized void delete(long id) {
        if (id <= 0) throw new IllegalArgumentException("Invalid transaction ID.");
        atomic(db -> {
            if (db.delete("ledger", "id = ?", new String[]{Long.toString(id)}) != 1)
                throw new IllegalArgumentException("Transaction no longer exists.");
            Portfolio.validate(readTransactions(db));
        });
    }

    /** Imported IDs are remapped locally, preserving date/ID order, to avoid append collisions. */
    public synchronized void importTransactions(List<Tx> txs, boolean replace) {
        Objects.requireNonNull(txs, "Transactions are required.");
        if (txs.size() > MAX_ROWS) throw new IllegalArgumentException("Import exceeds 100,000 rows.");
        List<Tx> ordered = new ArrayList<>(txs);
        for (Tx tx : ordered) {
            if (tx == null || tx.date == null || tx.id < 0) throw new IllegalArgumentException("Invalid import row.");
        }
        ordered.sort(Comparator.comparing((Tx t) -> t.date).thenComparingLong(t -> t.id));
        atomic(db -> {
            if (replace) db.delete("ledger", null, null);
            for (Tx tx : ordered) db.insertOrThrow("ledger", null, values(tx));
            Portfolio.validate(readTransactions(db));
        });
    }

    /** Upsert only returned instruments/dates. Omitted quotes retain their original stale timestamps. */
    public synchronized void saveMarket(List<Quote> quotes, List<Candle> candles) {
        Objects.requireNonNull(quotes); Objects.requireNonNull(candles);
        if (quotes.size() > MAX_ROWS || candles.size() > 1_000_000) throw new IllegalArgumentException("Market batch too large.");
        atomic(db -> writeMarket(db, quotes, candles));
    }

    private static void writeMarket(SQLiteDatabase db,List<Quote> quotes,List<Candle> candles) {
            for (Quote q : quotes) {
                Objects.requireNonNull(q); checkText(q.key, "Instrument key", 128);
                if (q.asOfMillis < 0 || q.fetchedMillis < 0) throw new IllegalArgumentException("Invalid quote timestamp.");
                ContentValues v = new ContentValues();
                v.put("instrument_key", q.key); v.put("price", positiveDecimal(q.price));
                if (q.previousClose == null) v.putNull("previous_close");
                else v.put("previous_close", positiveDecimal(q.previousClose));
                v.put("as_of", q.asOfMillis); v.put("fetched", q.fetchedMillis);
                // A slower/older refresh must not overwrite a more recently fetched quote.
                try (Cursor c = db.query("quotes", new String[]{"fetched"}, "instrument_key = ?",
                        new String[]{q.key}, null, null, null)) {
                    if (c.moveToFirst() && c.getLong(0) > q.fetchedMillis) continue;
                }
                if (db.insertWithOnConflict("quotes", null, v, SQLiteDatabase.CONFLICT_REPLACE) == -1)
                    throw new IllegalStateException("Unable to save quote.");
            }
            for (Candle c : candles) {
                Objects.requireNonNull(c); checkText(c.key, "Instrument key", 128);
                ContentValues v = new ContentValues(); v.put("instrument_key", c.key);
                v.put("date", Objects.requireNonNull(c.date).toString()); v.put("close", positiveDecimal(c.close));
                if (db.insertWithOnConflict("candles", null, v, SQLiteDatabase.CONFLICT_REPLACE) == -1)
                    throw new IllegalStateException("Unable to save candle.");
            }
    }

    /** One-time provider transition: preserve the ledger, discard only rebuildable old prices. */
    public synchronized boolean useYahooMarketData() {
        final boolean[] changed={false};
        atomic(db->{
            try(Cursor c=db.query("settings",new String[]{"value"},"name = ?",new String[]{"price_provider"},null,null,null)) {
                if(c.moveToFirst() && "yahoo-v1".equals(c.getString(0)))return;
            }
            db.delete("quotes",null,null);db.delete("candles",null,null);
            db.delete("settings","name = ? OR name LIKE ? OR name LIKE ?",new String[]{"last_refresh","market_note:%","market_split:%"});
            setting(db,"price_provider","yahoo-v1");changed[0]=true;
        });
        return changed[0];
    }

    /** Replace a successful history window atomically, avoiding mixed split-adjustment vintages. */
    public synchronized void saveYahooMarket(String key,Quote quote,List<Candle> candles,
            LocalDate from,LocalDate to,LocalDate split,List<String> warnings) {
        Objects.requireNonNull(candles);Objects.requireNonNull(warnings);checkText(key,"Instrument key",128);
        if(from==null || to==null || from.isAfter(to) || candles.size()>100_000)throw new IllegalArgumentException("Invalid market window.");
        if(quote!=null && !key.equals(quote.key))throw new IllegalArgumentException("Quote instrument mismatch.");
        for(Candle c:candles)if(!key.equals(c.key) || c.date.isBefore(from) || c.date.isAfter(to))throw new IllegalArgumentException("History instrument/date mismatch.");
        atomic(db->{
            db.delete("candles","instrument_key = ? AND date >= ? AND date <= ?",new String[]{key,from.toString(),to.toString()});
            if(split!=null){
                db.delete("candles","instrument_key = ? AND date < ?",new String[]{key,split.toString()});
                setting(db,"market_split:"+key,split.toString());
            }
            writeMarket(db,quote==null?java.util.Collections.emptyList():java.util.Collections.singletonList(quote),candles);
            List<String> bounded=warnings.subList(0,Math.min(12,warnings.size()));
            String note=String.join("\n",bounded);if(note.length()>8000)note=note.substring(0,8000);
            setting(db,"market_note:"+key,note);
            if(quote!=null || !candles.isEmpty())setting(db,"last_refresh",Long.toString(System.currentTimeMillis()));
        });
    }

    public synchronized Map<String,LocalDate> marketSplits() {
        Map<String,LocalDate> result=new LinkedHashMap<>();
        try(Cursor c=helper.getReadableDatabase().query("settings",new String[]{"name","value"},"name LIKE ?",new String[]{"market_split:%"},null,null,null)){
            while(c.moveToNext())result.put(c.getString(0).substring("market_split:".length()),LocalDate.parse(c.getString(1)));
        }return result;
    }

    public synchronized Map<String,String> marketNotes() {
        Map<String,String> result=new LinkedHashMap<>();
        try(Cursor c=helper.getReadableDatabase().query("settings",new String[]{"name","value"},"name LIKE ?",new String[]{"market_note:%"},null,null,null)){
            while(c.moveToNext())if(!c.getString(1).isEmpty())result.put(c.getString(0).substring("market_note:".length()),c.getString(1));
        }return result;
    }

    private static void setting(SQLiteDatabase db,String name,String value) {
        ContentValues v=new ContentValues();v.put("name",name);v.put("value",value);
        if(db.insertWithOnConflict("settings",null,v,SQLiteDatabase.CONFLICT_REPLACE)==-1)throw new IllegalStateException("Unable to save market settings.");
    }

    public synchronized List<Instrument> instruments() {
        List<Instrument> result = new ArrayList<>();
        try (Cursor c = helper.getReadableDatabase().query("instruments", null, null, null, null, null, "symbol COLLATE NOCASE, instrument_key")) {
            while (c.moveToNext()) result.add(new Instrument(text(c, "instrument_key"), text(c, "symbol"), text(c, "name")));
        }
        return result;
    }

    public synchronized void saveInstruments(List<Instrument> instruments) {
        Objects.requireNonNull(instruments);
        if (instruments.size() > MAX_ROWS) throw new IllegalArgumentException("Instrument list too large.");
        atomic(db -> {
            db.delete("instruments", null, null);
            for (Instrument i : instruments) {
                Objects.requireNonNull(i); checkText(i.key, "Instrument key", 128);
                checkText(i.symbol, "Symbol", 256); checkText(i.name, "Name", 1024);
                ContentValues v = new ContentValues(); v.put("instrument_key", i.key);
                v.put("symbol", i.symbol); v.put("name", i.name); db.insertOrThrow("instruments", null, v);
            }
        });
    }

    public synchronized long lastRefresh() {
        try (Cursor c = helper.getReadableDatabase().query("settings", new String[]{"value"},
                "name = ?", new String[]{"last_refresh"}, null, null, null)) {
            return c.moveToFirst() ? Long.parseLong(c.getString(0)) : 0L;
        }
    }

    public synchronized void setLastRefresh(long millis) {
        if (millis < 0) throw new IllegalArgumentException("Invalid refresh time.");
        atomic(db -> {
            ContentValues v = new ContentValues(); v.put("name", "last_refresh"); v.put("value", Long.toString(millis));
            if (db.insertWithOnConflict("settings", null, v, SQLiteDatabase.CONFLICT_REPLACE) == -1)
                throw new IllegalStateException("Unable to save refresh time.");
        });
    }

    private static ContentValues values(Tx t) {
        checkText(t.key, "Instrument key", 128); checkText(t.symbol, "Symbol", 16_384); checkText(t.name, "Name", 16_384);
        ContentValues v = new ContentValues();
        v.put("date", Objects.requireNonNull(t.date, "Date is required.").toString());
        v.put("instrument_key", t.key); v.put("symbol", t.symbol); v.put("name", t.name);
        v.put("type", Objects.requireNonNull(t.type, "Type is required.").name());
        v.put("quantity", decimalText(t.quantity)); v.put("price", decimalText(t.price)); v.put("fees", decimalText(t.fees));
        return v;
    }

    private static String text(Cursor c, String name) { return c.getString(c.getColumnIndexOrThrow(name)); }
    private static BigDecimal decimal(Cursor c, String name) {
        String s = text(c, name); return s == null ? null : new BigDecimal(s);
    }
    private static String decimalText(BigDecimal value) {
        if (value == null || value.precision() + Math.max(0L, -(long)value.scale()) > 64 || Math.abs((long)value.scale()) > 64)
            throw new IllegalArgumentException("Decimal is missing or too large.");
        return value.toPlainString();
    }
    private static String positiveDecimal(BigDecimal value) {
        String result = decimalText(value);
        if (value.signum() <= 0) throw new IllegalArgumentException("Market price must be positive.");
        return result;
    }
    private static void checkText(String value, String field, int max) {
        if (value == null || value.trim().isEmpty() || value.length() > max || value.indexOf('\0') >= 0)
            throw new IllegalArgumentException(field + " is missing or too long.");
    }
    private interface Write { void run(SQLiteDatabase db); }
    private void atomic(Write operation) {
        SQLiteDatabase db = helper.getWritableDatabase(); db.beginTransaction();
        try { operation.run(db); db.setTransactionSuccessful(); }
        finally { db.endTransaction(); }
    }
    @Override public synchronized void close() { helper.close(); }

    private static final class Database extends SQLiteOpenHelper {
        Database(Context context) { super(context, "jacks-stocks.db", null, 1); }
        @Override public void onCreate(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE ledger (id INTEGER PRIMARY KEY AUTOINCREMENT, date TEXT NOT NULL, instrument_key TEXT NOT NULL, symbol TEXT NOT NULL, name TEXT NOT NULL, type TEXT NOT NULL, quantity TEXT NOT NULL, price TEXT NOT NULL, fees TEXT NOT NULL)");
            db.execSQL("CREATE INDEX ledger_order ON ledger(date, id)");
            db.execSQL("CREATE TABLE quotes (instrument_key TEXT PRIMARY KEY, price TEXT NOT NULL, previous_close TEXT, as_of INTEGER NOT NULL, fetched INTEGER NOT NULL)");
            db.execSQL("CREATE TABLE candles (instrument_key TEXT NOT NULL, date TEXT NOT NULL, close TEXT NOT NULL, PRIMARY KEY(instrument_key, date))");
            db.execSQL("CREATE TABLE instruments (instrument_key TEXT PRIMARY KEY, symbol TEXT NOT NULL, name TEXT NOT NULL)");
            db.execSQL("CREATE TABLE settings (name TEXT PRIMARY KEY, value TEXT NOT NULL)");
        }
        @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            throw new IllegalStateException("Unsupported database version; no destructive migration will be performed.");
        }
    }
}