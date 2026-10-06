package com.jayfibi.calculator.core;

/** An immutable history value. Edits retain their original id and timestamp. */
public final class HistoryEntry {
    public final String id;
    public final String expression;
    public final String result;
    public final long timestamp;

    public HistoryEntry(String id, String expression, String result, long timestamp) {
        if (id == null || expression == null || result == null) {
            throw new IllegalArgumentException("History fields cannot be null.");
        }
        this.id = id;
        this.expression = expression;
        this.result = result;
        this.timestamp = timestamp;
    }
}