package com.jacks.stocks;

import android.content.Context;
import android.os.Bundle;
import android.test.InstrumentationTestCase;
import android.test.InstrumentationTestRunner;
import com.jacks.stocks.core.Portfolio.*;
import com.jacks.stocks.data.Store;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;

/** Opt-in screenshot setup only. Never seeds automatically or overwrites an existing ledger. */
@SuppressWarnings("deprecation")
public final class ReviewSeedTest extends InstrumentationTestCase {
    public void testSeedExplicitDisposableReview() {
        Bundle args=((InstrumentationTestRunner)getInstrumentation()).getArguments();
        if(!"true".equals(args.getString("seedReview")))return;
        assertTrue("Only a debug application may be seeded",BuildConfig.DEBUG);
        Context context=getInstrumentation().getTargetContext();
        try(Store store=new Store(context)){
            assertTrue("Refusing to overwrite any existing portfolio",store.transactions().isEmpty());
            store.useYahooMarketData();LocalDate now=LocalDate.now(ZoneId.of("Asia/Kolkata"));
            String reliance="NSE_EQ|INE002A01018",infy="NSE_EQ|INE009A01021",tcs="NSE_EQ|INE467B01029";
            List<Tx> ledger=new ArrayList<>();
            ledger.add(tx(1,now.minusMonths(3),reliance,"RELIANCE","Reliance Industries",Type.BUY,"25","1350","25"));
            ledger.add(tx(2,now.minusMonths(3).plusDays(2),infy,"INFY","Infosys Limited",Type.BUY,"30","1180","20"));
            ledger.add(tx(3,now.minusMonths(2),tcs,"TCS","Tata Consultancy Services",Type.BUY,"10","2350","20"));
            ledger.add(tx(4,now.minusMonths(1).minusDays(2),reliance,"RELIANCE","Reliance Industries",Type.BUY,"10","1310","15"));
            ledger.add(tx(5,now.minusWeeks(2),infy,"INFY","Infosys Limited",Type.DIVIDEND,"0","600","0"));
            ledger.add(tx(6,now.minusDays(5),reliance,"RELIANCE","Reliance Industries",Type.SELL,"5","1240","10"));
            store.importTransactions(ledger,false);
            store.saveInstruments(Arrays.asList(new Store.Instrument(reliance,"RELIANCE","Reliance Industries"),
                new Store.Instrument(infy,"INFY","Infosys Limited"),new Store.Instrument(tcs,"TCS","Tata Consultancy Services")));
            // Only fictional transactions: all market prices must arrive from an explicit Yahoo refresh.
            assertTrue(store.quotes().isEmpty());assertTrue(store.candles().isEmpty());
        }
        assertTrue(context.getSharedPreferences("appearance",Context.MODE_PRIVATE).edit()
            .putString("theme","Dark").putBoolean("review_screenshots",true).putBoolean("review_demo",true).commit());
    }
    private static Tx tx(long id,LocalDate date,String key,String symbol,String name,Type type,String qty,String price,String fees){
        return new Tx(id,date,key,symbol,name,type,new BigDecimal(qty),new BigDecimal(price),new BigDecimal(fees));
    }
}