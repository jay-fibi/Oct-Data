package com.jacks.stocks;

import android.app.Application;
import com.jacks.stocks.data.Store;
import com.jacks.stocks.data.LegacyCredentialCleanup;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** All disk, import and network operations share a serial queue, never the UI thread. */
public final class StocksApp extends Application {
    public final ExecutorService worker = Executors.newSingleThreadExecutor();
    public final ExecutorService chartWorker = Executors.newSingleThreadExecutor();
    public Store store;
    public volatile boolean busy;
    public volatile long revision;
    public volatile String lastSavedDraft = "";
    public volatile String status = "Prices update only when you tap Refresh prices.";

    @Override public void onCreate() {
        super.onCreate();
        store = new Store(this);
        worker.execute(()->{
            try { LegacyCredentialCleanup.clear(this); }
            catch(Exception ignored) { status="Yahoo needs no credentials. Could not remove an old provider key; clear app storage only after backing up your ledger."; }
        });
    }
}