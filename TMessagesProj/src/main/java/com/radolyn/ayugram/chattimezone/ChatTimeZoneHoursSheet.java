package com.radolyn.ayugram.chattimezone;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.util.TypedValue;
import android.view.GestureDetector;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.core.graphics.ColorUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Business.TimezonesController;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Stories.recorder.ButtonWithCounterView;

import java.util.Calendar;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Bottom sheet visualizing upcoming hours side by side in the device's and the
 * peer's time zones (worldtimebuddy-style dual hour strip). Two horizontally
 * scrollable lanes share one scroll position; tapping an hour column pins it
 * and the readout above shows the exact local→peer mapping, including a
 * day-change badge when the dates differ.
 *
 * <p>Mirrors {@link ChatTimeZonePickerSheet}: the returned sheet is NOT shown;
 * callers present it via {@code fragment.showDialog(sheet)}.
 */
public final class ChatTimeZoneHoursSheet {

    /**
     * Columns start at today 00:00 device-local and span 3 days, guaranteeing
     * at least 48 hours ahead of "now" regardless of the time of day, while
     * keeping a few hours of scroll-back context before the "now" column.
     */
    private static final int HOURS = 72;
    private static final long HOUR_MS = 3_600_000L;

    private ChatTimeZoneHoursSheet() {}

    /**
     * @param insertHandler when non-null, an "insert into message" button is shown
     *                      that hands the selected mapping (e.g. "Tue 18:00 my time
     *                      (Wed 03:00 your time)") to the caller and closes the sheet.
     */
    @Nullable
    public static BottomSheet show(Context context, int currentAccount, long dialogId,
                                   @Nullable Theme.ResourcesProvider rp,
                                   @Nullable Utilities.Callback<CharSequence> insertHandler) {
        final TimeZone peerTz = ChatTimeZoneController.getForDialog(currentAccount, dialogId);
        if (peerTz == null || ChatTimeZoneRenderer.sameAsLocal(peerTz)) {
            return null;
        }
        String peerName = ChatTimeZoneController.getDialogName(currentAccount, dialogId);
        if (TextUtils.isEmpty(peerName)) {
            peerName = LocaleController.getString(R.string.ChatTimeZone);
        }

        // Column zero = today 00:00 device-local.
        Calendar day0 = Calendar.getInstance();
        day0.set(Calendar.HOUR_OF_DAY, 0);
        day0.set(Calendar.MINUTE, 0);
        day0.set(Calendar.SECOND, 0);
        day0.set(Calendar.MILLISECOND, 0);
        final long startMs = day0.getTimeInMillis();
        final int nowIndex = (int) Math.min(HOURS - 1, Math.max(0, (System.currentTimeMillis() - startMs) / HOUR_MS));

        LinearLayout container = new LinearLayout(context);
        container.setOrientation(LinearLayout.VERTICAL);

        // Title: peer name.
        TextView titleView = new TextView(context);
        titleView.setText(peerName);
        titleView.setTextColor(Theme.getColor(Theme.key_dialogTextBlack, rp));
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
        titleView.setTypeface(AndroidUtilities.bold());
        titleView.setSingleLine(true);
        titleView.setEllipsize(TextUtils.TruncateAt.END);
        container.addView(titleView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT,
                22, 12, 22, 0));

        // Subtitle: pretty zone name + relative offset, e.g. "Berlin, GMT+02:00 · +9h".
        TextView subtitleView = new TextView(context);
        subtitleView.setText(buildSubtitle(currentAccount, peerTz));
        subtitleView.setTextColor(Theme.getColor(Theme.key_dialogTextGray3, rp));
        subtitleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        subtitleView.setSingleLine(true);
        subtitleView.setEllipsize(TextUtils.TruncateAt.END);
        container.addView(subtitleView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT,
                22, 2, 22, 0));

        // Readout: "You · Tue 18:23 → Alex · Wed 03:23 (+1d)".
        TextView readoutView = new TextView(context);
        readoutView.setTextColor(Theme.getColor(Theme.key_dialogTextBlack, rp));
        readoutView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        readoutView.setTypeface(AndroidUtilities.bold());
        readoutView.setSingleLine(true);
        readoutView.setEllipsize(TextUtils.TruncateAt.END);
        container.addView(readoutView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT,
                22, 14, 22, 10));

        // Strip row: fixed lane labels on the left + shared horizontal scroller.
        LinearLayout stripRow = new LinearLayout(context);
        stripRow.setOrientation(LinearLayout.HORIZONTAL);

        final HourStripView strip = new HourStripView(context, startMs, nowIndex, peerTz, rp);

        LinearLayout labels = new LinearLayout(context);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.addView(makeLaneLabel(context, LocaleController.getString(R.string.FromYou), strip.cellH, rp),
                LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));
        labels.addView(makeLaneLabel(context, peerName, strip.cellH, rp),
                LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 0));
        stripRow.addView(labels, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT,
                android.view.Gravity.TOP, 16, 0, 6, 0));

        final HorizontalScrollView scroller = new HorizontalScrollView(context);
        scroller.setHorizontalScrollBarEnabled(false);
        scroller.addView(strip);
        stripRow.addView(scroller, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, 0, 0, 16, 0));

        container.addView(stripRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        // Legend.
        TextView legend = new TextView(context);
        legend.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        legend.setTextColor(Theme.getColor(Theme.key_dialogTextGray3, rp));
        legend.setText(buildLegend(strip));
        container.addView(legend, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT,
                22, 8, 22, 16));

        final String youLabel = LocaleController.getString(R.string.FromYou);
        final String peerLabel = peerName;
        strip.setOnHourSelected(index -> {
            CharSequence text = buildReadout(index, nowIndex, startMs, peerTz, youLabel, peerLabel,
                    Theme.getColor(Theme.key_dialogTextBlue, rp));
            readoutView.setText(text);
            strip.setContentDescription(text);
        });
        strip.select(nowIndex);

        // Open with the "now" column a bit in from the left edge, keeping some
        // already-passed hours visible for context. Deferred to the first layout
        // pass: View.post() fires on attach, before the scroller has any width
        // to clamp the scroll against.
        scroller.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View v, int l, int t, int r, int b, int ol, int ot, int or, int ob) {
                scroller.removeOnLayoutChangeListener(this);
                scroller.scrollTo(Math.max(0, (nowIndex - 2) * strip.pitch()), 0);
            }
        });

        final BottomSheet[] sheetRef = new BottomSheet[1];
        if (insertHandler != null) {
            ButtonWithCounterView button = new ButtonWithCounterView(context, rp);
            button.setText(LocaleController.getString(R.string.ChatTimeZoneInsertTime), false);
            button.setOnClickListener(v -> {
                insertHandler.run(buildInsertText(strip.getSelected(), nowIndex, startMs, peerTz));
                if (sheetRef[0] != null) {
                    sheetRef[0].dismiss();
                }
            });
            container.addView(button, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48, 0, 16, 4, 16, 12));
        }

        BottomSheet.Builder builder = new BottomSheet.Builder(context, false, rp);
        builder.setApplyBottomPadding(false);
        builder.setCustomView(container);
        BottomSheet sheet = builder.create();
        sheetRef[0] = sheet;
        sheet.setBackgroundColor(Theme.getColor(Theme.key_dialogBackground, rp));
        sheet.fixNavigationBar(Theme.getColor(Theme.key_dialogBackground, rp));
        return sheet;
    }

    private static TextView makeLaneLabel(Context context, String text, int laneHeightPx,
                                          @Nullable Theme.ResourcesProvider rp) {
        TextView label = new TextView(context);
        label.setText(text);
        label.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        label.setTextColor(Theme.getColor(Theme.key_dialogTextGray3, rp));
        label.setSingleLine(true);
        label.setEllipsize(TextUtils.TruncateAt.END);
        label.setMaxWidth(dp(72));
        label.setGravity(android.view.Gravity.CENTER_VERTICAL);
        label.setHeight(laneHeightPx);
        return label;
    }

    /** "Berlin, GMT+02:00 · +9h" — pretty zone name plus offset relative to the device zone. */
    private static String buildSubtitle(int currentAccount, TimeZone peerTz) {
        String name = null;
        String id = peerTz.getID();
        if (!id.startsWith("GMT")) {
            try {
                name = TimezonesController.getInstance(currentAccount).getTimezoneName(id, true);
            } catch (Throwable ignore) {
                // ZoneId.of can throw for exotic ids; fall back below.
            }
        }
        if (TextUtils.isEmpty(name)) {
            name = id;
        }
        long now = System.currentTimeMillis();
        int diffMin = (peerTz.getOffset(now) - TimeZone.getDefault().getOffset(now)) / 60_000;
        return name + " · " + formatRelativeOffset(diffMin);
    }

    /** +9h, −3h, +5:30 (the "h" suffix is dropped when minutes are present). */
    private static String formatRelativeOffset(int diffMin) {
        int abs = Math.abs(diffMin);
        String sign = diffMin < 0 ? "−" : "+";
        if (abs % 60 == 0) {
            return sign + (abs / 60) + "h";
        }
        return String.format(Locale.US, "%s%d:%02d", sign, abs / 60, abs % 60);
    }

    private static CharSequence buildReadout(int index, int nowIndex, long startMs, TimeZone peerTz,
                                             String youLabel, String peerLabel, int accentColor) {
        // The "now" column maps the actual current minute (18:23 → 03:23);
        // every other column maps its top of the hour.
        long t = index == nowIndex ? System.currentTimeMillis() : startMs + index * HOUR_MS;
        Calendar local = Calendar.getInstance();
        local.setTimeInMillis(t);
        Calendar peer = Calendar.getInstance(peerTz);
        peer.setTimeInMillis(t);

        SpannableStringBuilder ssb = new SpannableStringBuilder();
        ssb.append(youLabel).append(" ∙ ").append(ChatTimeZoneRenderer.formatSide(local))
                .append("  →  ")
                .append(peerLabel).append(" ∙ ").append(ChatTimeZoneRenderer.formatSide(peer));

        int dayDiff = ChatTimeZoneRenderer.compareDay(peer, local);
        if (dayDiff != 0) {
            int start = ssb.length();
            ssb.append(dayDiff > 0 ? "  +1d" : "  −1d");
            ssb.setSpan(new ForegroundColorSpan(accentColor), start, ssb.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return ssb;
    }

    /**
     * Text handed to the insert button's callback, phrased from the recipient's
     * perspective: "Tue 18:00 my time (Wed 03:00 your time)".
     */
    private static CharSequence buildInsertText(int index, int nowIndex, long startMs, TimeZone peerTz) {
        long t = index == nowIndex ? System.currentTimeMillis() : startMs + index * HOUR_MS;
        Calendar local = Calendar.getInstance();
        local.setTimeInMillis(t);
        Calendar peer = Calendar.getInstance(peerTz);
        peer.setTimeInMillis(t);
        return LocaleController.formatString(R.string.ChatTimeZoneInsertPattern, ChatTimeZoneRenderer.formatSide(local), ChatTimeZoneRenderer.formatSide(peer));
    }

    private static CharSequence buildLegend(HourStripView strip) {
        SpannableStringBuilder ssb = new SpannableStringBuilder();
        appendLegendItem(ssb, strip.nightColor, LocaleController.getString(R.string.ChatTimeZoneLegendNight));
        ssb.append("   ");
        appendLegendItem(ssb, strip.shoulderColor, LocaleController.getString(R.string.ChatTimeZoneLegendAwake));
        ssb.append("   ");
        appendLegendItem(ssb, strip.workColor, "9–18");
        return ssb;
    }

    private static void appendLegendItem(SpannableStringBuilder ssb, int color, String text) {
        int start = ssb.length();
        ssb.append("■");
        ssb.setSpan(new ForegroundColorSpan(color), start, ssb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        ssb.append(' ').append(text);
    }

    // ---------- the strip ----------

    /**
     * Canvas-drawn dual lane of hour cells: top lane = device-local, bottom
     * lane = peer zone, columns aligned by instant. ~144 cells are cheap to
     * draw with clip-rect culling, so no view recycling is needed.
     */
    private static final class HourStripView extends View {

        interface OnHourSelected {
            void onSelected(int index);
        }

        final int cellW = dp(46);
        final int cellH = dp(40);
        final int gap = dp(2);

        private final long startMs;
        private final int nowIndex;
        private final TimeZone peerTz;

        private final TextPaint hourPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF cellRect = new RectF();
        private final Rect clipRect = new Rect();
        private final Calendar cal = Calendar.getInstance();
        private final Calendar peerCal;

        final int nightColor, shoulderColor, workColor;
        private final int textColor, nightTextColor, accentColor, accentTextColor;

        private int selected = -1;
        private OnHourSelected listener;
        private final GestureDetector gestureDetector;

        HourStripView(Context context, long startMs, int nowIndex, TimeZone peerTz,
                      @Nullable Theme.ResourcesProvider rp) {
            super(context);
            this.startMs = startMs;
            this.nowIndex = nowIndex;
            this.peerTz = peerTz;
            this.peerCal = Calendar.getInstance(peerTz);

            int bgBase = Theme.getColor(Theme.key_dialogBackground, rp);
            // Blending fixed hues into the dialog background keeps the palette
            // readable in both light and dark themes without new theme keys.
            nightColor = Theme.getColor(Theme.key_graySection, rp);
            shoulderColor = ColorUtils.blendARGB(bgBase, 0xFFE6A23C, 0.22f);
            workColor = ColorUtils.blendARGB(bgBase, 0xFF40B373, 0.22f);
            textColor = Theme.getColor(Theme.key_dialogTextBlack, rp);
            nightTextColor = Theme.getColor(Theme.key_dialogTextGray3, rp);
            accentColor = Theme.getColor(Theme.key_featuredStickers_addButton, rp);
            accentTextColor = Theme.getColor(Theme.key_featuredStickers_buttonText, rp);

            hourPaint.setTypeface(AndroidUtilities.bold());
            ringPaint.setStyle(Paint.Style.STROKE);
            ringPaint.setStrokeWidth(dp(1.5f));
            ringPaint.setColor(accentColor);

            gestureDetector = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
                @Override
                public boolean onDown(MotionEvent e) {
                    return true;
                }

                @Override
                public boolean onSingleTapUp(MotionEvent e) {
                    int index = (int) (e.getX() / pitch());
                    if (index >= 0 && index < HOURS && index != selected) {
                        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                        select(index);
                    }
                    return true;
                }
            });
        }

        int pitch() {
            return cellW + gap;
        }

        void setOnHourSelected(OnHourSelected listener) {
            this.listener = listener;
        }

        void select(int index) {
            selected = index;
            if (listener != null) {
                listener.onSelected(index);
            }
            invalidate();
        }

        int getSelected() {
            return selected;
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            setMeasuredDimension(HOURS * pitch() - gap, cellH * 2 + gap);
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            return gestureDetector.onTouchEvent(event) || super.onTouchEvent(event);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if (!canvas.getClipBounds(clipRect)) {
                clipRect.set(0, 0, getMeasuredWidth(), getMeasuredHeight());
            }
            int first = Math.max(0, clipRect.left / pitch());
            int last = Math.min(HOURS - 1, clipRect.right / pitch());
            for (int i = first; i <= last; i++) {
                long t = startMs + i * HOUR_MS;
                int x = i * pitch();
                cal.setTimeInMillis(t);
                drawCell(canvas, x, 0, cal, i, false);
                peerCal.setTimeInMillis(t);
                drawCell(canvas, x, cellH + gap, peerCal, i, true);
            }
        }

        private void drawCell(Canvas canvas, int x, int y, Calendar c, int index, boolean peerLane) {
            int hour = c.get(Calendar.HOUR_OF_DAY);
            int minute = c.get(Calendar.MINUTE);
            boolean isSelected = index == selected;

            cellRect.set(x, y, x + cellW, y + cellH);
            bgPaint.setColor(isSelected ? accentColor : colorForHour(hour));
            canvas.drawRoundRect(cellRect, dp(6), dp(6), bgPaint);
            if (!isSelected && index == nowIndex) {
                // Mark the current hour with an accent ring so "now" stays
                // findable after scrolling away and picking other columns.
                cellRect.inset(dp(1), dp(1));
                canvas.drawRoundRect(cellRect, dp(5), dp(5), ringPaint);
            }

            String label;
            if (hour == 0 && minute == 0) {
                // Midnight cell shows the weekday: it doubles as a date separator.
                label = ChatTimeZoneRenderer.weekday(c);
            } else if (minute != 0) {
                // Half/quarter-hour zones (e.g. +05:30) surface their minutes.
                label = String.format(Locale.US, "%d:%02d", hour, minute);
            } else {
                label = Integer.toString(hour);
            }
            hourPaint.setTextSize(dp(hour == 0 && minute == 0 ? 11 : 13));
            hourPaint.setColor(isSelected ? accentTextColor
                    : isNight(hour) && !(hour == 0 && minute == 0) ? nightTextColor : textColor);
            float textW = hourPaint.measureText(label);
            float baseline = y + cellH / 2f - (hourPaint.descent() + hourPaint.ascent()) / 2f;
            canvas.drawText(label, x + (cellW - textW) / 2f, baseline, hourPaint);
        }

        private int colorForHour(int hour) {
            if (hour >= 9 && hour <= 18) return workColor;
            if (hour >= 7 && hour <= 21) return shoulderColor;
            return nightColor;
        }

        private boolean isNight(int hour) {
            return hour < 7 || hour > 21;
        }
    }
}
