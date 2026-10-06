package com.jayfibi.calculator;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.jayfibi.calculator.core.CalculatorEngine;
import com.jayfibi.calculator.core.HistoryEntry;
import com.jayfibi.calculator.core.HistoryStore;

import java.security.GeneralSecurityException;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;

/** Native, dependency-free UI. File I/O and PIN derivation run on one background worker. */
public final class MainActivity extends Activity {
    private CalculatorApp app;
    private SharedPreferences preferences;
    private LinearLayout root;
    private LinearLayout body;
    private TextView statusView;
    private EditText expressionView;
    private TextView resultView;
    private AlertDialog dialog;
    private List<HistoryEntry> entries = Collections.emptyList();
    private String expression = "";
    private String result = "0";
    private String page = "Calculator";
    private boolean protectedHistory;
    private boolean unlocked;
    private boolean ready;
    private boolean busy;
    private boolean foreground;
    private boolean justCalculated;
    private boolean safePausedDraft;
    private int generation;
    private int historyPage;
    private int background;
    private int surface;
    private int text;
    private int muted;
    private int accent;
    private int accentText;
    private int secondary;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        preferences = getSharedPreferences("preferences", MODE_PRIVATE);
        String theme = preferences.getString("theme", "System");
        boolean dark = "Dark".equals(theme) || "Ocean".equals(theme)
                || ("System".equals(theme) && (getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES);
        setTheme(dark ? R.style.AppTheme_Dark : R.style.AppTheme);
        super.onCreate(savedInstanceState);
        // Keeps PINs, history, and the recent-apps preview out of screenshots.
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        app = (CalculatorApp) getApplication();
        background = Color.parseColor(dark ? "#10131B" : "#F6F7FB");
        surface = Color.parseColor(dark ? "#1C2230" : "#FFFFFF");
        text = Color.parseColor(dark ? "#F3F5FF" : "#192139");
        muted = Color.parseColor(dark ? "#BAC3D9" : "#59647A");
        accent = Color.parseColor(dark ? "#B0BCFF" : "#4F46E5");
        accentText = Color.parseColor(dark ? "#151B3D" : "#FFFFFF");
        secondary = Color.parseColor(dark ? "#2C354B" : "#E9ECF7");
        if ("Ocean".equals(theme)) {
            background = Color.parseColor("#071F2A");
            surface = Color.parseColor("#103441");
            secondary = Color.parseColor("#194957");
            accent = Color.parseColor("#74E0D0");
            accentText = Color.parseColor("#06312F");
        }
        if (savedInstanceState != null) {
            expression = savedInstanceState.getString("expression", "");
            result = savedInstanceState.getString("result", "0");
            justCalculated = savedInstanceState.getBoolean("calculated", false);
            page = savedInstanceState.getString("page", "Calculator");
        }
        buildScreen();
    }

    @Override
    protected void onResume() {
        super.onResume();
        foreground = true;
        work(null, null);
    }

    @Override
    protected void onPause() {
        boolean hadPendingWork = busy;
        safePausedDraft = ready && !protectedHistory && !hadPendingWork;
        foreground = false;
        generation++;
        busy = false;
        if (dialog != null) {
            dialog.dismiss();
            dialog = null;
        }
        entries = Collections.emptyList();
        if (protectedHistory || !ready || hadPendingWork) {
            unlocked = false;
            expression = "";
            result = "0";
            justCalculated = false;
        }
        ready = false;
        buildScreen();
        app.disk.execute(() -> {
            try {
                app.history().lock();
            } catch (Exception ignored) {
                // The next load reports failures without replacing the history file.
            }
        });
        super.onPause();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        // Never put protected history-derived expressions into framework saved state.
        boolean safe = foreground ? ready && !busy && !protectedHistory : safePausedDraft;
        outState.putString("expression", safe ? expression : "");
        outState.putString("result", safe ? result : "0");
        outState.putBoolean("calculated", safe && justCalculated);
        outState.putString("page", page);
    }

    private void buildScreen() {
        expressionView = null;
        resultView = null;
        root = column();
        root.setBackgroundColor(background);
        root.setPadding(dp(16), dp(12), dp(16), dp(12));
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(dp(16) + insets.getSystemWindowInsetLeft(),
                    dp(12) + insets.getSystemWindowInsetTop(),
                    dp(16) + insets.getSystemWindowInsetRight(),
                    dp(12) + insets.getSystemWindowInsetBottom());
            return insets.consumeSystemWindowInsets();
        });
        setContentView(root);
        root.requestApplyInsets();

        TextView title = label("Pocket Calculator", 25, text);
        title.setTypeface(null, Typeface.BOLD);
        root.addView(title);
        root.addView(label("A little clarity, one calculation at a time.", 13, muted));
        LinearLayout navigation = row();
        for (String destination : new String[]{"Calculator", "History", "Settings"}) {
            Button tab = button(destination, destination.equals(page));
            tab.setTextSize(13);
            tab.setOnClickListener(view -> {
                page = destination;
                buildScreen();
            });
            navigation.addView(tab, weighted(48));
        }
        root.addView(navigation);
        statusView = label(busy ? "Working…" : ready
                ? protectedHistory ? unlocked ? "History unlocked · locks when you leave"
                : "History locked · calculations are not saved" : "History saved on this device"
                : "Loading history…", 12, muted);
        statusView.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        statusView.setPadding(dp(4), dp(8), dp(4), dp(8));
        root.addView(statusView);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        body = column();
        scroll.addView(body);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        switch (page) {
            case "History": showHistory(); break;
            case "Settings": showSettings(); break;
            default: showCalculator(); break;
        }
    }

    private void showCalculator() {
        LinearLayout display = card();
        display.addView(label("EXPRESSION", 12, muted));
        expressionView = input("Type an expression, e.g. (12 + 8) / 4", false);
        expressionView.setTextSize(24);
        expressionView.setGravity(Gravity.END);
        expressionView.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        expressionView.setSingleLine(true);
        expressionView.setImeOptions(EditorInfo.IME_ACTION_DONE | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        expressionView.setText(expression);
        expressionView.setSelection(expression.length());
        expressionView.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                expression = s.toString();
                justCalculated = false;
            }
            @Override public void afterTextChanged(Editable editable) { }
        });
        expressionView.setOnEditorActionListener((view, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                calculate();
                return true;
            }
            return false;
        });
        display.addView(expressionView);
        resultView = label(result, 38, text);
        resultView.setGravity(Gravity.END);
        resultView.setTextIsSelectable(true);
        resultView.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        display.addView(resultView);
        body.addView(display);
        String[][] keys = {
                {"C", "(", ")", "⌫"},
                {"7", "8", "9", "÷"},
                {"4", "5", "6", "×"},
                {"1", "2", "3", "−"},
                {"0", ".", "%", "+"}
        };
        for (String[] keyRow : keys) {
            LinearLayout line = row();
            for (String key : keyRow) {
                Button control = button(key, false);
                control.setTextSize(23);
                if ("⌫".equals(key)) control.setContentDescription("Delete previous character");
                if ("C".equals(key)) control.setContentDescription("Clear expression");
                control.setOnClickListener(view -> enter(key));
                line.addView(control, weighted(56));
            }
            body.addView(line);
        }
        Button equals = button("=", true);
        equals.setContentDescription("Calculate and save to unlocked history");
        equals.setTextSize(28);
        equals.setOnClickListener(view -> calculate());
        body.addView(equals, new LinearLayout.LayoutParams(-1, dp(56)));
        body.addView(label("% means divide by 100. Use parentheses to group operations.", 12, muted));
    }

    private void enter(String key) {
        if (expressionView == null) return;
        if ("C".equals(key)) {
            expressionView.setText("");
            result = "0";
            resultView.setText(result);
            return;
        }
        boolean operator = "+−×÷%".contains(key);
        if (justCalculated && !"⌫".equals(key)) {
            expressionView.setText(operator ? result : "");
            expressionView.setSelection(expressionView.length());
        }
        int start = Math.max(0, expressionView.getSelectionStart());
        int end = Math.max(start, expressionView.getSelectionEnd());
        Editable editable = expressionView.getText();
        if ("⌫".equals(key)) {
            if (start != end) editable.delete(start, end);
            else if (start > 0) editable.delete(start - 1, start);
        } else {
            if (editable.length() - (end - start) + key.length() > 4096) {
                message("Expression limit reached (4,096 characters).");
                return;
            }
            editable.replace(start, end, key);
        }
    }

    private void calculate() {
        if (busy) { message("Please wait for the current operation."); return; }
        try {
            String calculatedExpression = expression.trim();
            String calculatedResult = CalculatorEngine.evaluate(calculatedExpression);
            result = calculatedResult;
            resultView.setText(result);
            justCalculated = true;
            if (!ready) {
                message("History unavailable — result not saved. Retry loading in Settings.");
            } else if (!unlocked) {
                message("Result not saved. Unlock History, then tap = to save it.");
            } else {
                work(store -> store.add(calculatedExpression, calculatedResult),
                        () -> message("Calculation saved."));
            }
        } catch (IllegalArgumentException error) {
            message(error.getMessage());
        }
    }

    private void showHistory() {
        if (!ready) {
            body.addView(label("History is unavailable until it has loaded successfully.", 16, text));
            action(body, "Retry loading", () -> work(null, null), true);
            return;
        }
        if (!unlocked) {
            LinearLayout locked = card();
            locked.addView(label("Your history is locked", 23, text));
            locked.addView(label("Enter your numeric PIN to view or edit saved calculations.", 16, muted));
            action(locked, "Unlock history", () -> askPin("Unlock history", false, null), true);
            body.addView(locked);
            return;
        }
        LinearLayout controls = row();
        if (protectedHistory) action(controls, "Lock now", () -> work(HistoryStore::lock, null), false);
        if (!entries.isEmpty()) action(controls, "Clear history", this::confirmClear, false);
        body.addView(controls);
        body.addView(label(entries.size() + " saved calculations · newest first", 13, muted));
        if (entries.isEmpty()) {
            LinearLayout empty = card();
            empty.addView(label("A fresh start", 23, text));
            empty.addView(label("Calculate something and it will appear here. Tap Edit to change a saved expression using text.", 16, muted));
            body.addView(empty);
        }
        List<HistoryEntry> newestFirst = new ArrayList<>(entries);
        newestFirst.sort((left, right) -> Long.compare(right.timestamp, left.timestamp));
        // Bound the number of native views, even when all 1,000 slots are used.
        int pageCount = Math.max(1, (newestFirst.size() + 24) / 25);
        historyPage = Math.min(historyPage, pageCount - 1);
        if (pageCount > 1) {
            body.addView(label("Page " + (historyPage + 1) + " of " + pageCount, 13, muted));
            LinearLayout paging = row();
            if (historyPage > 0) action(paging, "Newer", () -> { historyPage--; buildScreen(); }, false);
            if (historyPage + 1 < pageCount) action(paging, "Older", () -> { historyPage++; buildScreen(); }, false);
            body.addView(paging);
        }
        int end = Math.min(newestFirst.size(), (historyPage + 1) * 25);
        for (HistoryEntry entry : newestFirst.subList(historyPage * 25, end)) {
            LinearLayout saved = card();
            saved.addView(label(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                    .format(new Date(entry.timestamp)), 12, muted));
            TextView expressionLabel = label(entry.expression, 19, text);
            expressionLabel.setTextIsSelectable(true);
            saved.addView(expressionLabel);
            TextView answer = label("= " + entry.result, 26, accent);
            answer.setTextIsSelectable(true);
            saved.addView(answer);
            LinearLayout actions = row();
            action(actions, "Use", () -> {
                expression = entry.expression;
                result = entry.result;
                justCalculated = false;
                page = "Calculator";
                buildScreen();
            }, false);
            action(actions, "Edit", () -> edit(entry), false);
            action(actions, "Delete", () -> {
                dialog = new AlertDialog.Builder(this).setTitle("Delete calculation?")
                        .setMessage("This cannot be undone.")
                        .setNegativeButton("Cancel", null)
                        .setPositiveButton("Delete", (d, which) -> work(store -> store.delete(entry.id), null))
                        .create();
                showSecureDialog(dialog);
            }, false);
            saved.addView(actions);
            body.addView(saved);
        }
    }

    private void edit(HistoryEntry entry) {
        if (busy || !unlocked) return;
        LinearLayout content = card();
        content.addView(label("Edit the expression below. The result is recalculated when saved.", 14, muted));
        EditText editor = input("Expression", false);
        editor.setText(entry.expression);
        editor.setSelectAllOnFocus(true);
        content.addView(editor);
        dialog = new AlertDialog.Builder(this).setTitle("Edit calculation").setView(content)
                .setNegativeButton("Cancel", null).setPositiveButton("Save", null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(view -> {
                    try {
                        String changed = editor.getText().toString().trim();
                        String answer = CalculatorEngine.evaluate(changed);
                        dialog.dismiss();
                        work(store -> store.update(entry.id, changed, answer),
                                () -> message("History updated."));
                    } catch (IllegalArgumentException error) {
                        editor.setError(error.getMessage());
                    }
                }));
        showSecureDialog(dialog);
    }

    private void confirmClear() {
        dialog = new AlertDialog.Builder(this).setTitle("Clear all history?")
                .setMessage("All saved calculations will be deleted. Your PIN, if enabled, stays in place.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Clear", (d, which) -> work(HistoryStore::clear, null)).create();
        showSecureDialog(dialog);
    }

    private void showSettings() {
        LinearLayout themes = card();
        themes.addView(label("Make it yours", 23, text));
        themes.addView(label("Choose a theme. Your choice is remembered.", 14, muted));
        for (String name : new String[]{"System", "Light", "Dark", "Ocean"}) {
            boolean selected = name.equals(preferences.getString("theme", "System"));
            action(themes, name + (selected ? " · selected" : ""), () -> {
                preferences.edit().putString("theme", name).apply();
                recreate();
            }, selected);
        }
        body.addView(themes);
        LinearLayout security = card();
        security.addView(label("History privacy", 23, text));
        if (!ready) {
            security.addView(label("Load history before changing its lock.", 14, muted));
            action(security, "Retry loading", () -> work(null, null), false);
        } else if (!protectedHistory) {
            security.addView(label("Add a 4–12 digit PIN to encrypt the history file on this device.", 14, muted));
            action(security, "Set history PIN", () -> askPin("Set history PIN", true, null), true);
        } else if (!unlocked) {
            security.addView(label("PIN protection is on. Unlock to change or remove the PIN.", 14, muted));
            action(security, "Unlock history", () -> askPin("Unlock history", false, null), true);
        } else {
            security.addView(label("PIN protection is on. History locks whenever you leave the app, including when changing themes.", 14, muted));
            action(security, "Lock now", () -> work(HistoryStore::lock, null), true);
            action(security, "Change PIN", () -> askPin("Change history PIN", true, null), false);
            action(security, "Remove PIN", () -> {
                dialog = new AlertDialog.Builder(this).setTitle("Remove PIN protection?")
                        .setMessage("History will be saved without encryption in the app's private storage.")
                        .setNegativeButton("Cancel", null)
                        .setPositiveButton("Remove", (d, which) -> work(HistoryStore::removePin, null)).create();
                showSecureDialog(dialog);
            }, false);
        }
        security.addView(label("There is no PIN recovery. Remember your PIN; clearing app data or uninstalling permanently deletes history. Prefer a longer PIN.", 13, muted));
        body.addView(security);
        LinearLayout about = card();
        about.addView(label("Only on your device", 21, text));
        about.addView(label("No account, network access, ads, or storage permissions. The latest 1,000 calculations are kept in a private file. Backups and screenshots are disabled for privacy.", 14, muted));
        body.addView(about);
    }

    // These commits run on the disk executor so attempt counters persist before returning.
    @SuppressLint("ApplySharedPref")
    private void askPin(String title, boolean setting, Runnable after) {
        if (busy) { message("Please wait for the current operation."); return; }
        long remaining = preferences.getLong("retry_after", 0) - System.currentTimeMillis();
        if (!setting && remaining > 0) {
            message("Too many attempts. Try again in " + ((remaining + 999) / 1000) + " seconds.");
            return;
        }
        LinearLayout content = card();
        content.addView(label(setting ? "Use 4–12 digits. A longer PIN gives better protection. There is no recovery if you forget it."
                : "Enter your history PIN.", 14, muted));
        EditText pin = input("Numeric PIN", true);
        content.addView(pin);
        EditText confirmation = input("Confirm PIN", true);
        if (setting) content.addView(confirmation);
        AlertDialog pinDialog = new AlertDialog.Builder(this).setTitle(title).setView(content)
                .setNegativeButton("Cancel", null)
                .setPositiveButton(setting ? "Save PIN" : "Unlock", null).create();
        dialog = pinDialog;
        pinDialog.setOnDismissListener(ignored -> {
            pin.getText().clear();
            confirmation.getText().clear();
        });
        pinDialog.setOnShowListener(ignored -> pinDialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(view -> {
                    char[] digits = new char[pin.length()];
                    pin.getText().getChars(0, pin.length(), digits, 0);
                    if (digits.length < 4 || digits.length > 12) {
                        Arrays.fill(digits, '\0');
                        pin.setError("Use 4–12 digits.");
                        return;
                    }
                    if (setting) {
                        char[] repeated = new char[confirmation.length()];
                        confirmation.getText().getChars(0, confirmation.length(), repeated, 0);
                        boolean matches = Arrays.equals(digits, repeated);
                        Arrays.fill(repeated, '\0');
                        if (!matches) {
                            Arrays.fill(digits, '\0');
                            confirmation.setError("PINs do not match.");
                            return;
                        }
                    }
                    pinDialog.dismiss();
                    work(store -> {
                        try {
                            if (setting) store.setPin(digits);
                            else {
                                try {
                                    store.unlock(digits);
                                    preferences.edit().remove("failed_attempts").remove("retry_after").commit();
                                } catch (GeneralSecurityException error) {
                                    int attempts = preferences.getInt("failed_attempts", 0) + 1;
                                    preferences.edit().putInt("failed_attempts", attempts)
                                            .putLong("retry_after", attempts >= 5
                                                    ? System.currentTimeMillis() + 30_000 : 0).commit();
                                    throw error;
                                }
                            }
                        } finally {
                            Arrays.fill(digits, '\0');
                        }
                    }, after);
                }));
        showSecureDialog(pinDialog);
    }

    private interface HistoryOperation {
        void run(HistoryStore store) throws Exception;
    }

    private void work(HistoryOperation operation, Runnable after) {
        if (busy) { message("Please wait for the current operation."); return; }
        busy = true;
        int requestGeneration = generation;
        statusView.setText(R.string.working);
        app.disk.execute(() -> {
            try {
                HistoryStore store = app.history();
                if (operation != null) operation.run(store);
                boolean hasPin = store.isLocked();
                boolean canRead = store.isUnlocked();
                List<HistoryEntry> loaded = canRead ? store.getEntries() : Collections.emptyList();
                runOnUiThread(() -> {
                    if (!foreground || generation != requestGeneration) return;
                    busy = false;
                    ready = true;
                    protectedHistory = hasPin;
                    unlocked = canRead;
                    entries = loaded;
                    if (hasPin && !canRead) {
                        if (dialog != null) {
                            dialog.dismiss();
                            dialog = null;
                        }
                        expression = "";
                        result = "0";
                        justCalculated = false;
                    }
                    buildScreen();
                    if (after != null) after.run();
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    if (!foreground || generation != requestGeneration) return;
                    busy = false;
                    String detail = error instanceof GeneralSecurityException
                            ? "Unable to unlock: incorrect PIN or damaged history file."
                            : error.getMessage();
                    message("History operation failed. " + (detail == null ? "Please try again." : detail));
                });
            }
        });
    }

    private EditText input(String hint, boolean pin) {
        EditText field = new EditText(this);
        field.setHint(hint);
        field.setContentDescription(hint);
        field.setTextColor(text);
        field.setHintTextColor(muted);
        field.setMinHeight(dp(52));
        field.setFilters(new InputFilter[]{new InputFilter.LengthFilter(pin ? 12 : 4096)});
        field.setSaveEnabled(false);
        field.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        if (pin) {
            field.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
            field.setSingleLine(true);
        } else {
            field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                    | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        }
        return field;
    }

    private LinearLayout column() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    private LinearLayout row() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        layout.setGravity(Gravity.CENTER_VERTICAL);
        return layout;
    }

    private LinearLayout card() {
        LinearLayout layout = column();
        layout.setPadding(dp(16), dp(16), dp(16), dp(16));
        layout.setBackground(rounded(surface, 18));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.setMargins(0, dp(6), 0, dp(10));
        layout.setLayoutParams(params);
        return layout;
    }

    private TextView label(String value, int size, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setPadding(0, dp(3), 0, dp(5));
        return view;
    }

    private Button button(String title, boolean primary) {
        Button control = new Button(this);
        control.setText(title);
        control.setAllCaps(false);
        control.setTextSize(14);
        control.setTextColor(primary ? accentText : text);
        control.setBackgroundTintList(null);
        control.setBackground(rounded(primary ? accent : secondary, 12));
        control.setMinHeight(dp(48));
        control.setMinimumWidth(0);
        control.setMinWidth(0);
        control.setPadding(dp(6), dp(4), dp(6), dp(4));
        return control;
    }

    private void action(LinearLayout parent, String title, Runnable callback, boolean primary) {
        Button control = button(title, primary);
        control.setOnClickListener(view -> {
            if (busy) message("Please wait for the current operation.");
            else callback.run();
        });
        LinearLayout.LayoutParams params = parent.getOrientation() == LinearLayout.HORIZONTAL
                ? weighted(48) : new LinearLayout.LayoutParams(-1, -2);
        params.setMargins(dp(3), dp(4), dp(3), dp(4));
        parent.addView(control, params);
    }

    private LinearLayout.LayoutParams weighted(int height) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(height), 1);
        params.setMargins(dp(3), dp(4), dp(3), dp(4));
        return params;
    }

    private GradientDrawable rounded(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radius));
        return drawable;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void showSecureDialog(AlertDialog alert) {
        if (alert.getWindow() != null) {
            alert.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        }
        alert.show();
    }

    private void message(String value) {
        statusView.setText(value);
        Toast.makeText(this, value, Toast.LENGTH_LONG).show();
    }
}