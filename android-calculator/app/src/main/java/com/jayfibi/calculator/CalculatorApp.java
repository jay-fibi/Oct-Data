package com.jayfibi.calculator;

import android.app.Application;

import com.jayfibi.calculator.core.HistoryStore;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Serializes file access across Activity recreation; no PIN is kept in preferences. */
public final class CalculatorApp extends Application {
    final ExecutorService disk = Executors.newSingleThreadExecutor();
    private HistoryStore history;

    HistoryStore history() throws IOException {
        if (history == null) {
            history = new HistoryStore(getFilesDir());
        }
        return history;
    }
}