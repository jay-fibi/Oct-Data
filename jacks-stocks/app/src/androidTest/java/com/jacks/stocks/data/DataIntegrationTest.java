package com.jacks.stocks.data;

import android.content.Context;
import android.content.ContextWrapper;
import android.database.DatabaseErrorHandler;
import android.database.sqlite.SQLiteDatabase;
import android.test.InstrumentationTestCase;
import com.jacks.stocks.core.Portfolio.*;
import java.io.File;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

/** Offline tests in isolated databases. Never modifies a user's ledger or calls a provider. */
@SuppressWarnings("deprecation")
public final class DataIntegrationTest extends InstrumentationTestCase {
    private static final String KEY="NSE_EQ|INE009A01021";
    private IsolatedContext context;
    private Store store;
    @Override protected void setUp() throws Exception {
        super.setUp();context=new IsolatedContext(getInstrumentation().getTargetContext());store=new Store(context);
    }
    @Override protected void tearDown() throws Exception {
        try{if(store!=null)store.close();if(context!=null)remove(context.root);}finally{super.tearDown();}
    }
    private Tx tx(long id,Type type,String qty,String price,int day) {
        return new Tx(id,LocalDate.of(2020,1,day),KEY,"INFY","Infosys",type,new BigDecimal(qty),new BigDecimal(price),BigDecimal.ZERO);
    }
    private void failure(Runnable work){try{work.run();fail("Expected rejected write");}catch(IllegalArgumentException expected){}}
    public void testLedgerRollbackAndRestart() {
        store.save(tx(0,Type.BUY,"10.125","123.1234567890123456789",1));
        store.save(tx(0,Type.SELL,"2.125","140",2));
        List<Tx> saved=store.transactions();long buy=saved.get(0).id,sell=saved.get(1).id;
        failure(()->store.save(tx(sell,Type.SELL,"11","140",2)));
        failure(()->store.save(tx(buy,Type.BUY,"1","123",1)));
        failure(()->store.delete(buy));
        failure(()->store.importTransactions(Collections.singletonList(tx(9,Type.SELL,"100","140",3)),false));
        failure(()->store.importTransactions(Collections.singletonList(tx(9,Type.SELL,"1","140",3)),true));
        store.close();store=new Store(context);
        assertEquals(2,store.transactions().size());assertEquals("123.1234567890123456789",store.transactions().get(0).price.toPlainString());
        store.delete(sell);store.delete(buy);assertTrue(store.transactions().isEmpty());
    }
    public void testImportOrderAndIdRemapping() {
        store.importTransactions(Arrays.asList(tx(50,Type.SELL,"1","120",2),tx(20,Type.BUY,"2","100",1)),false);
        assertEquals(Type.BUY,store.transactions().get(0).type);
        assertEquals(Type.SELL,store.transactions().get(1).type);
        store.importTransactions(Collections.singletonList(tx(20,Type.BUY,"1","100",3)),false);
        assertEquals(3,store.transactions().size());
        store.importTransactions(Collections.singletonList(tx(10,Type.OPENING,"5","100",1)),true);
        assertEquals(1,store.transactions().size());assertEquals(Type.OPENING,store.transactions().get(0).type);
    }
    public void testProviderMigrationPreservesLedgerOnce() {
        store.save(tx(0,Type.BUY,"2","100",1));
        Quote quote=new Quote(KEY,new BigDecimal("120.001"),null,100,200);
        Candle candle=new Candle(KEY,LocalDate.of(2020,1,1),new BigDecimal("110.001"));
        store.saveMarket(Collections.singletonList(quote),Collections.singletonList(candle));store.setLastRefresh(200);
        assertTrue(store.useYahooMarketData());assertEquals(1,store.transactions().size());
        assertTrue(store.quotes().isEmpty());assertTrue(store.candles().isEmpty());assertEquals(0,store.lastRefresh());
        store.saveMarket(Collections.singletonList(quote),Collections.singletonList(candle));
        assertFalse(store.useYahooMarketData());assertEquals(1,store.quotes().size());assertEquals(1,store.candles().size());
    }
    public void testYahooCacheReplacementRollbackAndSplitBarrier() {
        store.useYahooMarketData();LocalDate from=LocalDate.of(2020,1,1),to=LocalDate.of(2020,1,3);
        Quote quote=new Quote(KEY,new BigDecimal("125.0123456789"),null,100,200);
        store.saveYahooMarket(KEY,quote,Arrays.asList(new Candle(KEY,from,new BigDecimal("110")),new Candle(KEY,to,new BigDecimal("120"))),from,to,null,Collections.singletonList("Sample data note"));
        assertNull(store.quotes().get(0).previousClose);assertEquals(2,store.candles().size());
        failure(()->store.saveYahooMarket(KEY,null,Collections.singletonList(new Candle(KEY,to,BigDecimal.ZERO)),from,to,null,Collections.emptyList()));
        assertEquals(2,store.candles().size());
        store.saveYahooMarket(KEY,null,Collections.singletonList(new Candle(KEY,to,new BigDecimal("60"))),from,to,to,Collections.singletonList("Split reported"));
        assertEquals(1,store.candles().size());assertEquals(to,store.marketSplits().get(KEY));
        assertEquals("Split reported",store.marketNotes().get(KEY));assertEquals(new BigDecimal("125.0123456789"),store.quotes().get(0).price);
        store.close();store=new Store(context);assertEquals(to,store.marketSplits().get(KEY));
    }
    public void testInstrumentCatalogueAtomicity() {
        store.saveInstruments(Collections.singletonList(new Store.Instrument(KEY,"INFY","Infosys")));
        failure(()->store.saveInstruments(Arrays.asList(new Store.Instrument(KEY,"INFY","Infosys"),new Store.Instrument("","BAD","Bad"))));
        assertEquals(1,store.instruments().size());assertEquals("INFY",store.instruments().get(0).symbol);
    }
    private static void remove(File file){File[] children=file.listFiles();if(children!=null)for(File child:children)remove(child);if(file.exists() && !file.delete())throw new AssertionError("Could not remove isolated test file");}
    private static final class IsolatedContext extends ContextWrapper {
        final File root;
        IsolatedContext(Context base){super(base);root=new File(base.getCacheDir(),"yahoo-store-test-"+UUID.randomUUID());if(!root.mkdirs())throw new AssertionError("Test directory unavailable");}
        @Override public Context getApplicationContext(){return this;}
        @Override public File getDatabasePath(String name){return new File(root,name);}
        @Override public SQLiteDatabase openOrCreateDatabase(String name,int mode,SQLiteDatabase.CursorFactory factory){return SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name),factory);}
        @Override public SQLiteDatabase openOrCreateDatabase(String name,int mode,SQLiteDatabase.CursorFactory factory,DatabaseErrorHandler handler){return SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name).getPath(),factory,handler);}
        @Override public boolean deleteDatabase(String name){return SQLiteDatabase.deleteDatabase(getDatabasePath(name));}
    }
}