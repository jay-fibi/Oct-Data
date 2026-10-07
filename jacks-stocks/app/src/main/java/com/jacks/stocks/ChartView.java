package com.jacks.stocks;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.accessibility.AccessibilityNodeInfo;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Canvas-only chart. Unknown prices remain gaps; floating point is used only for pixels. */
public final class ChartView extends View {
    public enum Mode { LINE, BAR, DONUT }

    private static final BigDecimal ZERO = BigDecimal.ZERO;
    private static final BigDecimal ONE = BigDecimal.ONE;
    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final MathContext DRAWING = MathContext.DECIMAL64;
    private final String title;
    private final Mode mode;
    private final List<String> labels = new ArrayList<>();
    private final List<BigDecimal> values = new ArrayList<>();
    private final List<Slice> slices = new ArrayList<>();
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint text = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF plot = new RectF();
    private final RectF ring = new RectF();
    private final NumberFormat number = NumberFormat.getNumberInstance(new Locale("en", "IN"));
    private final int foreground, muted, accent, loss, surface, grid;
    private final int[] palette;
    private final int touchSlop;
    private final boolean signed;
    private BigDecimal minimum, maximum, total = ZERO, low = ZERO, high = ONE;
    private int minIndex = -1, maxIndex = -1, latestKnown = -1, missing, negative, zeroCount;
    private int selected = -1, preparedWidth = -1;
    private String summary;
    private StaticLayout titleLayout, footerLayout;
    private float headerHeight, footerHeight, legendRow, legendTop, ringWidth;
    private float downX, downY;
    private boolean horizontalDrag, verticalDrag;

    private static final class Slice {
        final String label;
        final BigDecimal value;
        final int members;
        Slice(String label, BigDecimal value, int members) {
            this.label = label;
            this.value = value;
            this.members = members;
        }
    }

    /** Empty preview constructor for native layout tools; populated charts use the data constructor. */
    public ChartView(Context context) {
        this(context, "Chart", java.util.Collections.emptyList(), java.util.Collections.emptyList(), Mode.LINE, false);
    }

    public ChartView(Context context, String title, List<String> labels,
                     List<BigDecimal> values, Mode mode, boolean light) {
        super(context);
        this.title = title == null || title.trim().isEmpty() ? "Chart" : title.trim();
        this.mode = mode == null ? Mode.LINE : mode;
        surface = Color.parseColor(light ? "#FFFFFF" : "#152338");
        foreground = Color.parseColor(light ? "#12233C" : "#EEF4FF");
        muted = Color.parseColor(light ? "#51627A" : "#9EACC1");
        accent = Color.parseColor(light ? "#006F50" : "#40DDAC");
        loss = Color.parseColor(light ? "#B3233A" : "#FF7F88");
        grid = Color.parseColor(light ? "#D7DFEA" : "#34465F");
        palette = light
                ? new int[]{accent, 0xFF245CC2, 0xFF8942B2, 0xFF966000,
                    0xFFAB3554, 0xFF007A87, 0xFF596D20, 0xFF5E6878}
                : new int[]{accent, 0xFF80B4FF, 0xFFCAA0FF, 0xFFFFCA78,
                    0xFFFF94B5, 0xFF64D8E4, 0xFFC4D97D, 0xFFABBAD0};
        number.setMinimumFractionDigits(2);
        number.setMaximumFractionDigits(2);
        number.setRoundingMode(RoundingMode.HALF_UP);
        int count = Math.max(labels == null ? 0 : labels.size(), values == null ? 0 : values.size());
        for (int i = 0; i < count; i++) {
            String label = labels != null && i < labels.size() ? labels.get(i) : null;
            this.labels.add(label == null || label.trim().isEmpty() ? "Point " + (i + 1) : label.trim());
            BigDecimal value = values != null && i < values.size() ? values.get(i) : null;
            this.values.add(value);
            if (value == null) { missing++; continue; }
            latestKnown = i;
            if (value.signum() < 0) negative++;
            if (value.signum() == 0) zeroCount++;
            if (minimum == null || value.compareTo(minimum) < 0) { minimum = value; minIndex = i; }
            if (maximum == null || value.compareTo(maximum) > 0) { maximum = value; maxIndex = i; }
        }
        String lowerTitle = this.title.toLowerCase(Locale.ROOT);
        signed = this.mode == Mode.BAR || negative > 0 || lowerTitle.contains("p&l")
                || lowerTitle.contains("earn") || lowerTitle.contains("profit")
                || lowerTitle.contains("gain") || lowerTitle.contains("loss");
        if (this.mode == Mode.DONUT) buildSlices();
        else buildRange();
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        setFocusable(true);
        setClickable(true);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        // The parent owns the rounded card background; the view remains transparent.
        summary = buildSummary();
        updateDescription();
    }

    private boolean allocationValid() { return missing == 0 && negative == 0; }

    private void buildSlices() {
        // Never normalize a partial/invalid portfolio into a misleading 100% allocation.
        if (!allocationValid()) return;
        for (int i = 0; i < values.size(); i++) {
            BigDecimal value = values.get(i);
            if (value.signum() > 0) {
                slices.add(new Slice(labels.get(i), value, 1));
                total = total.add(value);
            }
        }
        slices.sort((a, b) -> b.value.compareTo(a.value));
        if (slices.size() > 8) {
            BigDecimal others = ZERO;
            int count = slices.size() - 7;
            for (int i = 7; i < slices.size(); i++) others = others.add(slices.get(i).value);
            slices.subList(7, slices.size()).clear();
            slices.add(new Slice("Others (" + count + " holdings)", others, count));
        }
    }

    private void buildRange() {
        if (minimum == null) return;
        low = signed ? minimum.min(ZERO) : minimum;
        high = signed ? maximum.max(ZERO) : maximum;
        BigDecimal span = high.subtract(low);
        BigDecimal padding = (span.signum() == 0 ? high.abs() : span)
                .multiply(new BigDecimal("0.08"), DRAWING);
        if (padding.signum() == 0) padding = ONE;
        if (low.signum() != 0) low = low.subtract(padding);
        if (!signed && minimum.signum() >= 0) low = low.max(ZERO);
        if (high.signum() != 0 || low.signum() == 0) high = high.add(padding);
        if (low.compareTo(high) == 0) high = low.add(ONE);
    }

    private float dp(float value) { return value * getResources().getDisplayMetrics().density; }
    private float sp(float value) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value,
                getResources().getDisplayMetrics());
    }

    private void textStyle(float size, int color, boolean bold) {
        text.setTextSize(sp(size));
        text.setColor(color);
        text.setTypeface(bold ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        text.setTextAlign(Paint.Align.LEFT);
    }

    private float lineHeight(float size) {
        textStyle(size, foreground, false);
        return text.getFontMetrics().descent - text.getFontMetrics().ascent;
    }

    private StaticLayout layout(String value, float width, float size, int color, boolean bold, int lines) {
        textStyle(size, color, bold);
        return StaticLayout.Builder.obtain(value, 0, value.length(), new TextPaint(text),
                        Math.max(1, (int) width))
                .setAlignment(Layout.Alignment.ALIGN_NORMAL).setIncludePad(false)
                .setMaxLines(lines).setEllipsize(TextUtils.TruncateAt.END).build();
    }

    private String footer() {
        if (mode == Mode.DONUT) {
            if (!allocationValid()) return "Weights unavailable: " + missing + " missing, " + negative
                    + " negative. No partial allocation shown.";
            return slices.isEmpty() ? "Only positive holdings occupy the ring."
                    : "Tap a slice or row; use arrow keys to explore."
                    + (zeroCount > 0 ? " " + zeroCount + " zero-value holdings omitted." : "");
        }
        return (missing > 0 ? "× " + missing + " missing: gaps, never zero. " : "")
                + "Tap or use arrows for values.";
    }

    private void prepare(float width) {
        int w = Math.max(1, (int) width);
        if (preparedWidth == w) return;
        preparedWidth = w;
        float available = Math.max(dp(1), width - getPaddingLeft() - getPaddingRight() - dp(24));
        titleLayout = layout(title, available, 16, foreground, true, 2);
        footerLayout = layout(footer(), available, 11, muted, false, 3);
        headerHeight = dp(12) + titleLayout.getHeight() + dp(10)
                + lineHeight(12) + lineHeight(16) + dp(12);
        footerHeight = footerLayout.getHeight() + dp(16);
        legendRow = Math.max(dp(48), lineHeight(13) + lineHeight(11) + dp(12));
    }

    @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = resolveSize(Math.max(getSuggestedMinimumWidth(), (int) dp(320)), widthMeasureSpec);
        prepare(width);
        float body = mode == Mode.DONUT ? dp(130) : dp(105) + lineHeight(11) + dp(16);
        float base = Math.max(dp(260), headerHeight + body + footerHeight);
        float legend = mode == Mode.DONUT ? slices.size() * legendRow + (slices.isEmpty() ? 0 : dp(12)) : 0;
        int wanted = (int) Math.ceil(base + legend + getPaddingTop() + getPaddingBottom());
        setMeasuredDimension(width, resolveSize(Math.max(getSuggestedMinimumHeight(), wanted), heightMeasureSpec));
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        prepare(getWidth());
        float left = getPaddingLeft() + dp(12);
        float right = getWidth() - getPaddingRight() - dp(12);
        float top = getPaddingTop() + dp(12);
        float bottom = getHeight() - getPaddingBottom();
        if (right <= left || bottom <= top) return;
        drawLayout(canvas, titleLayout, left, top);
        float infoTop = top + titleLayout.getHeight() + dp(10);
        drawText(canvas, detailLabel(), left, infoTop, right - left, 12, muted, false);
        drawText(canvas, detailValue(), left, infoTop + lineHeight(12), right - left,
                16, detailColor(), true);
        float chartTop = getPaddingTop() + headerHeight;
        float footerTop = bottom - footerHeight;
        drawLayout(canvas, footerLayout, left, Math.max(chartTop, footerTop) + dp(4));
        if (mode == Mode.DONUT) {
            float legendHeight = slices.size() * legendRow + (slices.isEmpty() ? 0 : dp(12));
            plot.set(left, chartTop, right, Math.max(chartTop, footerTop - legendHeight));
            legendTop = plot.bottom + dp(8);
            drawDonut(canvas);
        } else {
            drawCartesian(canvas, left, right, chartTop, footerTop);
        }
        if (isFocused()) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(2));
            paint.setColor(accent);
            canvas.drawRoundRect(dp(2), dp(2), getWidth() - dp(2), getHeight() - dp(2), dp(10), dp(10), paint);
            paint.setStyle(Paint.Style.FILL);
        }
    }

    private void drawLayout(Canvas canvas, StaticLayout layout, float x, float y) {
        canvas.save();
        canvas.translate(x, y);
        layout.draw(canvas);
        canvas.restore();
    }

    private void drawText(Canvas canvas, String value, float x, float top, float width,
                          float size, int color, boolean bold) {
        if (width <= 0) return;
        textStyle(size, color, bold);
        String fitted = TextUtils.ellipsize(value, text, width, TextUtils.TruncateAt.END).toString();
        canvas.drawText(fitted, x, top - text.getFontMetrics().ascent, text);
    }

    private int detailIndex() { return selected >= 0 ? selected : values.size() - 1; }

    private String detailLabel() {
        if (mode == Mode.DONUT) return selected >= 0 && selected < slices.size()
                ? (selected + 1) + ". " + slices.get(selected).label : "Total market value";
        int index = detailIndex();
        return index < 0 ? "No observations" : (selected < 0 ? "Latest: " : "Selected: ") + labels.get(index);
    }

    private String detailValue() {
        if (mode == Mode.DONUT) {
            if (!allocationValid()) return "Allocation unavailable";
            if (selected >= 0 && selected < slices.size()) {
                Slice slice = slices.get(selected);
                return fullCurrency(slice.value) + "  ·  " + percentage(slice.value);
            }
            return fullCurrency(total);
        }
        int index = detailIndex();
        return index < 0 ? "No data" : values.get(index) == null ? "Unavailable — not zero"
                : fullCurrency(values.get(index));
    }

    private int detailColor() {
        if (mode == Mode.DONUT) return foreground;
        int index = detailIndex();
        return index < 0 || values.get(index) == null ? muted
                : values.get(index).signum() < 0 ? loss : signed ? accent : foreground;
    }

    private void drawCartesian(Canvas canvas, float left, float right, float top, float bottom) {
        float axisHeight = lineHeight(11) + dp(14);
        float plotBottom = bottom - axisHeight;
        if (plotBottom <= top + dp(8)) return;
        if (minimum == null) {
            plot.set(left, top, right, plotBottom);
            drawEmpty(canvas, values.isEmpty() ? "No chart data yet" : "No available values",
                    missing > 0 ? "Missing prices are not zero." : "Add data to see this chart.");
            drawXLabels(canvas);
            return;
        }
        textStyle(10, muted, false);
        float axisWidth = Math.max(text.measureText(compact(low)), text.measureText(compact(high))) + dp(8);
        axisWidth = Math.min(axisWidth, (right - left) * 0.38f);
        plot.set(left + axisWidth, top + lineHeight(10) / 2, right - dp(4), plotBottom);
        if (plot.width() <= dp(8) || plot.height() <= dp(8)) return;
        int intervals = plot.height() > lineHeight(10) * 5 ? 4 : 2;
        for (int i = 0; i <= intervals; i++) {
            BigDecimal tick = high.subtract(high.subtract(low)
                    .multiply(BigDecimal.valueOf(i)).divide(BigDecimal.valueOf(intervals), DRAWING));
            float y = yFor(tick);
            stroke(canvas, plot.left, y, plot.right, y, grid, 1);
            boolean nearZero = low.signum() <= 0 && high.signum() >= 0
                    && Math.abs(y - yFor(ZERO)) < lineHeight(10) + dp(2);
            if (!nearZero)
                drawText(canvas, compact(tick), left, y - lineHeight(10) / 2, axisWidth - dp(6), 10, muted, false);
        }
        if (low.signum() <= 0 && high.signum() >= 0) {
            float y = yFor(ZERO);
            stroke(canvas, plot.left, y, plot.right, y, muted, 1.5f);
            // Baseline has a textual 0 label, independent of the red/green color encoding.
            paint.setColor(surface);
            paint.setStyle(Paint.Style.FILL);
            canvas.drawRect(left, y - lineHeight(10) / 2, plot.left - dp(2), y + lineHeight(10) / 2, paint);
            drawText(canvas, "₹0", left, y - lineHeight(10) / 2, axisWidth - dp(6), 10, foreground, true);
        }
        canvas.save();
        canvas.clipRect(plot.left - dp(4), plot.top - dp(4), plot.right + dp(4), plot.bottom + dp(7));
        if (mode == Mode.BAR) drawBars(canvas);
        else drawLine(canvas);
        if (selected >= 0 && selected < values.size()) drawSelection(canvas);
        canvas.restore();
        drawXLabels(canvas);
    }

    private float xFor(int index) {
        if (mode == Mode.BAR) return plot.left + plot.width() * (index + 0.5f) / Math.max(1, values.size());
        return values.size() <= 1 ? plot.centerX() : plot.left + plot.width() * index / (values.size() - 1f);
    }

    private float yFor(BigDecimal value) {
        // Normalize in decimal before converting; even enormous rupee amounts cannot overflow pixels.
        float fraction = value.subtract(low).divide(high.subtract(low), DRAWING).floatValue();
        return plot.bottom - Math.max(0, Math.min(1, fraction)) * plot.height();
    }

    private void drawLine(Canvas canvas) {
        path.reset();
        boolean connected = false;
        paint.setColor(accent);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(2.5f));
        paint.setStrokeJoin(Paint.Join.ROUND);
        for (int i = 0; i < values.size(); i++) {
            BigDecimal value = values.get(i);
            if (value == null) { connected = false; continue; }
            float x = xFor(i), y = yFor(value);
            if (connected) path.lineTo(x, y);
            else path.moveTo(x, y);
            connected = true;
        }
        canvas.drawPath(path, paint);
        paint.setStyle(Paint.Style.FILL);
        for (int i = 0; i < values.size(); i++) {
            BigDecimal value = values.get(i);
            if (value == null) { drawMissing(canvas, xFor(i)); continue; }
            // Isolated observations always have a marker; a single point is not an empty path.
            boolean isolated = (i == 0 || values.get(i - 1) == null)
                    && (i == values.size() - 1 || values.get(i + 1) == null);
            if (isolated || values.size() <= 32 || i == latestKnown) {
                paint.setColor(value.signum() < 0 ? loss : accent);
                canvas.drawCircle(xFor(i), yFor(value), dp(isolated ? 4 : 2.5f), paint);
            }
        }
    }

    private void drawBars(Canvas canvas) {
        float baseline = yFor(ZERO);
        float slot = plot.width() / Math.max(1, values.size());
        float width = Math.max(dp(0.6f), Math.min(dp(34), slot * 0.65f));
        for (int i = 0; i < values.size(); i++) {
            BigDecimal value = values.get(i);
            float x = xFor(i);
            if (value == null) { drawMissing(canvas, x); continue; }
            float y = yFor(value);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(value.signum() < 0 ? loss : accent);
            if (value.signum() == 0) {
                stroke(canvas, x - width / 2, baseline, x + width / 2, baseline, foreground, 2);
            } else {
                float start = Math.min(y, baseline), end = Math.max(y, baseline);
                if (end - start < dp(1.5f)) {
                    if (value.signum() > 0) start = end - dp(1.5f);
                    else end = start + dp(1.5f);
                }
                canvas.drawRect(x - width / 2, start, x + width / 2, end, paint);
                // Direction relative to zero and an explicit sign avoid color-only meaning.
                if (width >= sp(12) && Math.abs(y - baseline) >= lineHeight(10) + dp(4)) {
                    textStyle(10, surface, true);
                    text.setTextAlign(Paint.Align.CENTER);
                    canvas.drawText(value.signum() < 0 ? "−" : "+", x,
                            (start + end) / 2 - (text.ascent() + text.descent()) / 2, text);
                }
            }
        }
    }

    private void drawMissing(Canvas canvas, float x) {
        // An x in the bottom gutter is a missing-data marker, not a zero-valued datapoint.
        float y = plot.bottom + dp(5), r = dp(2);
        stroke(canvas, x - r, y - r, x + r, y + r, muted, 1);
        stroke(canvas, x - r, y + r, x + r, y - r, muted, 1);
    }

    private void drawSelection(Canvas canvas) {
        float x = xFor(selected);
        stroke(canvas, x, plot.top, x, plot.bottom, foreground, 1);
        BigDecimal value = values.get(selected);
        if (value == null) return;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(surface);
        canvas.drawCircle(x, yFor(value), dp(6), paint);
        paint.setColor(foreground);
        canvas.drawCircle(x, yFor(value), dp(4), paint);
    }

    private void drawXLabels(Canvas canvas) {
        if (values.isEmpty() || plot.width() <= 0) return;
        float y = plot.bottom + dp(10);
        if (values.size() == 1) {
            drawText(canvas, labels.get(0), plot.left, y, plot.width(), 11, muted, false);
            return;
        }
        float width = (plot.width() - dp(12)) / 2;
        drawText(canvas, labels.get(0), plot.left, y, width, 11, muted, false);
        textStyle(11, muted, false);
        String last = TextUtils.ellipsize(labels.get(labels.size() - 1), text,
                Math.max(1, width), TextUtils.TruncateAt.END).toString();
        canvas.drawText(last, plot.right - text.measureText(last), y - text.ascent(), text);
    }

    private void stroke(Canvas canvas, float x1, float y1, float x2, float y2, int color, float width) {
        paint.setColor(color);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(width));
        canvas.drawLine(x1, y1, x2, y2, paint);
        paint.setStyle(Paint.Style.FILL);
    }

    private void drawEmpty(Canvas canvas, String heading, String explanation) {
        float y = plot.centerY() - lineHeight(14);
        drawLayout(canvas, layout(heading + "\n" + explanation, plot.width(), 13, muted, false, 4), plot.left, y);
    }

    private void drawDonut(Canvas canvas) {
        if (plot.height() <= dp(8)) return;
        if (slices.isEmpty()) {
            drawEmpty(canvas, !allocationValid() ? "Allocation unavailable" : "No positive allocation",
                    !allocationValid() ? "All holdings need nonnegative known values." : "Zero values have no slice.");
            return;
        }
        float radius = Math.min(plot.width(), plot.height()) / 2 - dp(5);
        if (radius <= dp(8)) return;
        ringWidth = radius * 0.34f;
        radius -= ringWidth / 2;
        ring.set(plot.centerX() - radius, plot.centerY() - radius,
                plot.centerX() + radius, plot.centerY() + radius);
        float start = -90;
        for (int i = 0; i < slices.size(); i++) {
            float sweep = sweep(slices.get(i).value);
            paint.setStyle(Paint.Style.STROKE);
            if (selected == i) {
                paint.setColor(foreground);
                paint.setStrokeWidth(ringWidth + dp(5));
                canvas.drawArc(ring, start, sweep, false, paint);
            }
            paint.setColor(palette[i]);
            paint.setStrokeWidth(ringWidth);
            float gap = slices.size() == 1 ? 0 : Math.min(1.5f, sweep * 0.1f);
            canvas.drawArc(ring, start + gap / 2, sweep - gap, false, paint);
            if (sweep >= 20 && ringWidth >= lineHeight(11)) {
                double angle = Math.toRadians(start + sweep / 2);
                textStyle(11, surface, true);
                text.setTextAlign(Paint.Align.CENTER);
                canvas.drawText(Integer.toString(i + 1), plot.centerX() + (float) Math.cos(angle) * radius,
                        plot.centerY() + (float) Math.sin(angle) * radius - (text.ascent() + text.descent()) / 2, text);
            }
            start += sweep;
        }
        paint.setStyle(Paint.Style.FILL);
        float innerWidth = Math.max(1, (radius - ringWidth / 2) * 1.6f);
        float centerLeft = plot.centerX() - innerWidth / 2;
        drawText(canvas, "Total", centerLeft, plot.centerY() - lineHeight(11), innerWidth, 11, muted, false);
        drawText(canvas, compact(total), centerLeft, plot.centerY(), innerWidth, 13, foreground, true);
        for (int i = 0; i < slices.size(); i++) drawLegendRow(canvas, i);
    }

    private float sweep(BigDecimal value) { return value.divide(total, DRAWING).floatValue() * 360f; }

    private void drawLegendRow(Canvas canvas, int index) {
        Slice slice = slices.get(index);
        float top = legendTop + index * legendRow;
        if (top + legendRow > getHeight() - getPaddingBottom() - footerHeight + dp(1)) return;
        if (index == selected) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(1.5f));
            paint.setColor(foreground);
            canvas.drawRoundRect(plot.left, top, plot.right, top + legendRow - dp(2), dp(6), dp(6), paint);
            paint.setStyle(Paint.Style.FILL);
        }
        float markerSize = Math.max(dp(22), lineHeight(11) + dp(4));
        float x = plot.left + dp(4), markerY = top + legendRow / 2;
        paint.setColor(palette[index]);
        canvas.drawCircle(x + markerSize / 2, markerY, markerSize / 2, paint);
        textStyle(11, surface, true);
        text.setTextAlign(Paint.Align.CENTER);
        canvas.drawText(Integer.toString(index + 1), x + markerSize / 2,
                markerY - (text.ascent() + text.descent()) / 2, text);
        float labelX = x + markerSize + dp(8);
        textStyle(12, foreground, true);
        String percent = percentage(slice.value);
        float percentWidth = text.measureText(percent);
        float percentX = plot.right - dp(5) - percentWidth;
        drawText(canvas, slice.label, labelX, top + dp(5), Math.max(1, percentX - labelX - dp(6)), 13, foreground, index == selected);
        drawText(canvas, percent, percentX, top + dp(5), percentWidth + dp(1), 12, foreground, true);
        drawText(canvas, compact(slice.value), labelX, top + dp(6) + lineHeight(13),
                plot.right - labelX - dp(4), 11, muted, false);
    }

    private String percentage(BigDecimal value) {
        if (total.signum() == 0) return "0%";
        BigDecimal percent = value.multiply(HUNDRED).divide(total, 2, RoundingMode.HALF_UP);
        return percent.signum() == 0 && value.signum() > 0 ? "<0.01%" : percent.stripTrailingZeros().toPlainString() + "%";
    }

    private String compact(BigDecimal value) {
        BigDecimal magnitude = value.abs();
        String suffix = "";
        BigDecimal divisor = ONE;
        if (magnitude.compareTo(new BigDecimal("100000000000000")) >= 0)
            return sign(value) + "₹" + magnitude.round(new MathContext(3)).toEngineeringString();
        if (magnitude.compareTo(new BigDecimal("10000000")) >= 0) { divisor = new BigDecimal("10000000"); suffix = "Cr"; }
        else if (magnitude.compareTo(new BigDecimal("100000")) >= 0) { divisor = new BigDecimal("100000"); suffix = "L"; }
        else if (magnitude.compareTo(new BigDecimal("1000")) >= 0) { divisor = new BigDecimal("1000"); suffix = "K"; }
        BigDecimal rounded = magnitude.divide(divisor, 2, RoundingMode.HALF_UP).stripTrailingZeros();
        if (rounded.signum() == 0 && magnitude.signum() > 0) return sign(value) + "₹<0.01";
        return sign(value) + "₹" + rounded.toPlainString() + suffix;
    }

    private String sign(BigDecimal value) {
        return value.signum() < 0 ? "−" : value.signum() > 0 && signed && mode != Mode.DONUT ? "+" : "";
    }

    private String fullCurrency(BigDecimal value) {
        BigDecimal magnitude = value.abs();
        if (magnitude.precision() - magnitude.scale() > 15 || magnitude.scale() > 12)
            return sign(value) + "₹" + magnitude.stripTrailingZeros().toEngineeringString();
        if (magnitude.signum() > 0 && magnitude.compareTo(new BigDecimal("0.01")) < 0)
            return sign(value) + "₹" + magnitude.stripTrailingZeros().toPlainString();
        return sign(value) + "₹" + number.format(magnitude);
    }

    private String buildSummary() {
        StringBuilder result = new StringBuilder(title).append(". ")
                .append(mode == Mode.DONUT ? "Donut allocation chart. " : mode == Mode.BAR ? "Bar chart. " : "Line chart. ")
                .append(values.size()).append(" observations. ");
        if (values.isEmpty()) return result.append("No data available.").toString();
        int last = values.size() - 1;
        result.append("Latest ").append(labels.get(last)).append(": ")
                .append(values.get(last) == null ? "unavailable" : fullCurrency(values.get(last))).append(". ");
        if (latestKnown >= 0 && latestKnown != last)
            result.append("Latest known ").append(labels.get(latestKnown)).append(": ")
                    .append(fullCurrency(values.get(latestKnown))).append(". ");
        if (minimum != null) {
            result.append("Minimum ").append(fullCurrency(minimum)).append(" at ").append(labels.get(minIndex))
                    .append(". Maximum ").append(fullCurrency(maximum)).append(" at ").append(labels.get(maxIndex)).append(". ");
        } else result.append("All values unavailable. ");
        result.append(missing).append(" missing values, never treated as zero. ");
        if (missing > 0) {
            result.append("Missing at ");
            int shown = 0;
            for (int i = 0; i < values.size() && shown < 8; i++) if (values.get(i) == null) {
                if (shown++ > 0) result.append(", ");
                result.append(labels.get(i));
            }
            if (missing > shown) result.append(" and ").append(missing - shown).append(" more observations");
            result.append(". ");
        }
        if (mode == Mode.DONUT) {
            if (!allocationValid()) result.append(negative).append(" negative values. Allocation weights unavailable; no partial total shown. ");
            else {
                result.append("Total ").append(fullCurrency(total)).append(". ");
                if (slices.isEmpty()) result.append("No positive allocation. ");
                for (int i = 0; i < slices.size(); i++) {
                    Slice slice = slices.get(i);
                    result.append(i + 1).append(". ").append(slice.label).append(": ")
                            .append(fullCurrency(slice.value)).append(", ").append(percentage(slice.value)).append(". ");
                }
                if (zeroCount > 0) result.append(zeroCount).append(" zero-value holdings have no slice. ");
            }
        } else {
            result.append("Horizontal labels are observations; vertical values are Indian rupees. ");
            if (signed) result.append("Plus is positive and minus is negative; zero baseline shown. ");
            if (mode == Mode.LINE && missing > 0) result.append("The line breaks at missing observations. ");
        }
        if (selectionCount() > 0) result.append("Use forward or backward accessibility actions, or left and right arrow keys, to inspect each value.");
        return result.toString();
    }

    private int selectionCount() { return mode == Mode.DONUT ? slices.size() : values.size(); }

    private String selectionDescription() {
        return detailLabel() + ". " + detailValue() + ".";
    }

    private void updateDescription() {
        setContentDescription(summary + (selected >= 0 ? " Selected detail: " + selectionDescription() : ""));
    }

    private boolean select(int index) {
        if (index < 0 || index >= selectionCount() || index == selected) return false;
        selected = index;
        updateDescription();
        invalidate();
        return true;
    }

    private boolean moveSelection(int delta) {
        int count = selectionCount();
        if (count == 0) return false;
        int next = selected < 0 ? (delta < 0 ? count - 1 : 0) : selected + delta;
        if (!select(Math.max(0, Math.min(count - 1, next)))) return false;
        announceForAccessibility(selectionDescription());
        return true;
    }

    private void selectAt(float x, float y) {
        if (selectionCount() == 0 || plot.width() <= 0 || plot.height() <= 0) return;
        if (mode != Mode.DONUT) {
            if (y < plot.top - dp(8) || y > plot.bottom + dp(32)) return;
            float fraction = Math.max(0, Math.min(1, (x - plot.left) / plot.width()));
            int index = mode == Mode.BAR ? Math.min(values.size() - 1, (int) (fraction * values.size()))
                    : Math.round(fraction * (values.size() - 1));
            select(index);
        } else if (y >= legendTop && y < legendTop + slices.size() * legendRow) {
            select(Math.min(slices.size() - 1, (int) ((y - legendTop) / legendRow)));
        } else {
            double dx = x - ring.centerX(), dy = y - ring.centerY();
            double distance = Math.hypot(dx, dy), radius = ring.width() / 2;
            if (distance < radius - ringWidth / 2 - dp(8) || distance > radius + ringWidth / 2 + dp(8)) return;
            double angle = (Math.toDegrees(Math.atan2(dy, dx)) + 450) % 360;
            float end = 0;
            for (int i = 0; i < slices.size(); i++) {
                end += sweep(slices.get(i).value);
                if (angle < end || i == slices.size() - 1) { select(i); break; }
            }
        }
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (!isEnabled() || selectionCount() == 0) return super.onTouchEvent(event);
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = event.getX(); downY = event.getY();
                horizontalDrag = false; verticalDrag = false;
                return true;
            case MotionEvent.ACTION_MOVE:
                float dx = Math.abs(event.getX() - downX), dy = Math.abs(event.getY() - downY);
                if (!horizontalDrag && !verticalDrag && Math.max(dx, dy) > touchSlop) {
                    horizontalDrag = dx > dy;
                    verticalDrag = !horizontalDrag;
                    if (horizontalDrag && getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                }
                if (horizontalDrag) selectAt(event.getX(), event.getY());
                return true;
            case MotionEvent.ACTION_UP:
                if (!verticalDrag) {
                    selectAt(event.getX(), event.getY());
                    performClick();
                }
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
                return true;
            case MotionEvent.ACTION_CANCEL:
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
                horizontalDrag = false; verticalDrag = false;
                return true;
            default:
                return super.onTouchEvent(event);
        }
    }

    @Override public boolean performClick() {
        super.performClick();
        if (selected >= 0) announceForAccessibility(selectionDescription());
        return true;
    }

    @Override public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) return moveSelection(-1) || super.onKeyDown(keyCode, event);
        if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) return moveSelection(1) || super.onKeyDown(keyCode, event);
        if (keyCode == KeyEvent.KEYCODE_MOVE_HOME || keyCode == KeyEvent.KEYCODE_MOVE_END) {
            if (select(keyCode == KeyEvent.KEYCODE_MOVE_HOME ? 0 : selectionCount() - 1)) {
                announceForAccessibility(selectionDescription()); return true;
            }
        }
        if (keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_DPAD_CENTER)
            return moveSelection(1) || super.onKeyDown(keyCode, event);
        return super.onKeyDown(keyCode, event);
    }

    @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        info.setClassName(View.class.getName());
        int count = selectionCount();
        info.setScrollable(count > 1);
        if (count > 0) {
            info.addAction(new AccessibilityNodeInfo.AccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, "Next chart value"));
            if (selected < count - 1)
                info.addAction(new AccessibilityNodeInfo.AccessibilityAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, "Next chart value"));
            if (selected != 0)
                info.addAction(new AccessibilityNodeInfo.AccessibilityAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD, "Previous chart value"));
        }
    }

    @Override public boolean performAccessibilityAction(int action, Bundle arguments) {
        if (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) return moveSelection(1);
        if (action == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) return moveSelection(-1);
        if (action == AccessibilityNodeInfo.ACTION_CLICK && selectionCount() > 0) {
            select(selected < 0 || selected + 1 >= selectionCount() ? 0 : selected + 1);
            return performClick();
        }
        return super.performAccessibilityAction(action, arguments);
    }
}