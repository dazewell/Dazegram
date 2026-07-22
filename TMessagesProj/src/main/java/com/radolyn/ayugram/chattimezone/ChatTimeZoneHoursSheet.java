package com.radolyn.ayugram.chattimezone;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.text.Editable;
import android.text.InputType;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.style.ForegroundColorSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

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
 * scrollable lanes share one scroll position and slide under a fixed center
 * cursor; the step under the cursor (15-minute precision) drives the readout
 * above, which shows the exact local→peer mapping, including a day-change badge
 * when the dates differ.
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
    private static final int STEP_MIN = 15;
    private static final int STEPS_PER_HOUR = 60 / STEP_MIN;
    private static final long STEP_MS = STEP_MIN * 60_000L;
    private static final int STEPS = HOURS * STEPS_PER_HOUR;

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
        final long nowMs = System.currentTimeMillis();
        final int nowHour = (int) Math.min(HOURS - 1, Math.max(0, (nowMs - startMs) / HOUR_MS));
        // Open with the pointer at "now" rounded to the nearest 15-minute step.
        final int nowStep = (int) Math.min(STEPS - 1, Math.max(0, Math.round((nowMs - startMs) / (double) STEP_MS)));
        final String peerNameF = peerName;

        LinearLayout container = new LinearLayout(context);
        container.setOrientation(LinearLayout.VERTICAL);

        // Title row: peer name + a trailing "Now" action that recenters the pointer.
        LinearLayout titleRow = new LinearLayout(context);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView titleView = new TextView(context);
        titleView.setText(peerName);
        titleView.setTextColor(Theme.getColor(Theme.key_dialogTextBlack, rp));
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
        titleView.setTypeface(AndroidUtilities.bold());
        titleView.setSingleLine(true);
        titleView.setEllipsize(TextUtils.TruncateAt.END);
        titleRow.addView(titleView, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f,
                Gravity.CENTER_VERTICAL, 0, 0, 8, 0));

        TextView nowButton = new TextView(context);
        nowButton.setText(LocaleController.getString(R.string.ChatTimeZoneNow));
        nowButton.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        nowButton.setTypeface(AndroidUtilities.bold());
        nowButton.setTextColor(Theme.getColor(Theme.key_featuredStickers_addButton, rp));
        nowButton.setPadding(dp(12), dp(6), dp(12), dp(6));
        nowButton.setBackground(Theme.createRoundRectDrawable(dp(14), Theme.getColor(Theme.key_graySection, rp)));
        titleRow.addView(nowButton, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT,
                Gravity.CENTER_VERTICAL));

        container.addView(titleRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT,
                22, 12, 16, 0));

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

        final Runnable[] previewUpdater = { null };
        final HourStripView strip = new HourStripView(context, startMs, nowHour, peerTz, rp);

        LinearLayout labels = new LinearLayout(context);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.addView(makeLaneLabel(context, LocaleController.getString(R.string.FromYou), strip.cellH, rp),
                LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));
        labels.addView(makeLaneLabel(context, peerName, strip.cellH, rp),
                LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 0));
        stripRow.addView(labels, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT,
                android.view.Gravity.TOP, 16, 0, 6, 0));

        final CenterScrollView scroller = new CenterScrollView(context);
        scroller.addView(strip);
        // Wrap the scroller so a fixed cursor can float over its horizontal
        // center while the plot pans beneath it.
        FrameLayout scrollerFrame = new FrameLayout(context);
        scrollerFrame.addView(scroller, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT));
        View cursor = new CursorView(context,
                Theme.getColor(Theme.key_featuredStickers_addButton, rp), strip.cellH, strip.gap);
        scrollerFrame.addView(cursor, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, strip.cellH * 2 + strip.gap, Gravity.TOP));
        stripRow.addView(scrollerFrame, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, 0, 0, 16, 0));

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
        // Reused across the readout and preview so scrolling the plot doesn't
        // allocate fresh calendars (each clones a TimeZone) on every step change.
        final Calendar calLocal = Calendar.getInstance();
        final Calendar calPeer = Calendar.getInstance(peerTz);
        final int readoutColor = Theme.getColor(Theme.key_dialogTextBlue, rp);
        // The 15-minute step currently under the fixed center cursor.
        final int[] selectedStep = { nowStep };
        final Runnable syncSelection = () -> {
            // Half-viewport padding on the scroller (see the layout listener) makes
            // the content x under the fixed center cursor equal the raw scroll offset.
            int step = strip.stepFromX(scroller.getScrollX());
            if (step == selectedStep[0]) return;
            selectedStep[0] = step;
            // Buzz only on user-driven scrolls, not fling settle or programmatic recenters.
            if (scroller.userDragging) {
                strip.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            }
            CharSequence text = buildReadout(step, startMs, calLocal, calPeer, youLabel, peerLabel, readoutColor);
            readoutView.setText(text);
            strip.setContentDescription(text);
            if (previewUpdater[0] != null) previewUpdater[0].run();
        };
        scroller.onScrollChanged = syncSelection;
        // Seed the readout for "now" before the first layout gives the scroller a width.
        CharSequence initReadout = buildReadout(nowStep, startMs, calLocal, calPeer, youLabel, peerLabel, readoutColor);
        readoutView.setText(initReadout);
        strip.setContentDescription(initReadout);

        // Pad the scroller by half its viewport on each side so every step — including
        // the very first and last — can be scrolled under the centered cursor, then
        // open with "now" centered. Deferred to the first layout pass, when the
        // scroller finally has a width; the padding change forces another layout, so
        // the centering is posted to run once the scroll range is final.
        scroller.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View v, int l, int t, int r, int b, int ol, int ot, int or, int ob) {
                scroller.removeOnLayoutChangeListener(this);
                int pad = scroller.getWidth() / 2;
                scroller.setClipToPadding(false);
                scroller.setPadding(pad, 0, pad, 0);
                scroller.post(() -> {
                    scroller.scrollTo(centerScrollFor(strip, nowStep), 0);
                    syncSelection.run();
                });
            }
        });

        nowButton.setOnClickListener(v -> scroller.smoothScrollTo(centerScrollFor(strip, nowStep), 0));

        final BottomSheet[] sheetRef = new BottomSheet[1];
        if (insertHandler != null) {
            // Message-format template editor: edit, preview live against the pinned
            // instant, choose account/global scope, and insert. Insert renders from
            // the current draft so what you preview is what gets inserted.
            final int[] scope = { ChatTimeZoneTemplate.hasAccountOverride(currentAccount) ? 1 : 0 };

            TextView tmplHeader = new TextView(context);
            tmplHeader.setText(LocaleController.getString(R.string.ChatTimeZoneTemplate));
            tmplHeader.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
            tmplHeader.setTypeface(AndroidUtilities.bold());
            tmplHeader.setTextColor(Theme.getColor(Theme.key_dialogTextBlack, rp));
            container.addView(tmplHeader, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT,
                    22, 14, 22, 0));

            LinearLayout scopeRow = new LinearLayout(context);
            scopeRow.setOrientation(LinearLayout.HORIZONTAL);
            final TextView accountChip = makeScopeChip(context, LocaleController.getString(R.string.ChatTimeZoneTemplateScopeAccount));
            final TextView globalChip = makeScopeChip(context, LocaleController.getString(R.string.ChatTimeZoneTemplateScopeGlobal));
            scopeRow.addView(accountChip, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 0, 8, 0));
            scopeRow.addView(globalChip, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));
            container.addView(scopeRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 22, 8, 22, 0));

            FrameLayout fieldBox = new FrameLayout(context);
            fieldBox.setBackground(Theme.createRoundRectDrawable(dp(10), Theme.getColor(Theme.key_graySection, rp)));
            final EditText field = new EditText(context);
            field.setBackground(null);
            field.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
            field.setTextColor(Theme.getColor(Theme.key_dialogTextBlack, rp));
            field.setHintTextColor(Theme.getColor(Theme.key_dialogSearchHint, rp));
            field.setHint(LocaleController.getString(R.string.ChatTimeZoneTemplateHint));
            field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                    | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
            field.setMaxLines(3);
            field.setPadding(dp(12), dp(10), dp(12), dp(10));
            fieldBox.addView(field, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
            container.addView(fieldBox, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 22, 8, 22, 0));

            HorizontalScrollView chipsScroller = new HorizontalScrollView(context);
            chipsScroller.setHorizontalScrollBarEnabled(false);
            LinearLayout chips = new LinearLayout(context);
            chips.setOrientation(LinearLayout.HORIZONTAL);
            for (String token : ChatTimeZoneTemplate.TOKENS) {
                TextView chip = makePlaceholderChip(context, token, rp);
                chip.setOnClickListener(v -> {
                    int s = Math.max(0, field.getSelectionStart());
                    int e = Math.max(s, field.getSelectionEnd());
                    field.getText().replace(s, e, token);
                });
                chips.addView(chip, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 0, 6, 0));
            }
            chipsScroller.addView(chips);
            container.addView(chipsScroller, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 22, 8, 22, 0));

            TextView previewView = new TextView(context);
            previewView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            previewView.setTextColor(Theme.getColor(Theme.key_dialogTextGray3, rp));
            previewView.setSingleLine(true);
            previewView.setEllipsize(TextUtils.TruncateAt.END);
            container.addView(previewView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 22, 8, 22, 0));

            LinearLayout actionRow = new LinearLayout(context);
            actionRow.setOrientation(LinearLayout.HORIZONTAL);
            actionRow.setGravity(Gravity.CENTER_VERTICAL);
            TextView resetView = new TextView(context);
            resetView.setText(LocaleController.getString(R.string.ChatTimeZoneTemplateReset));
            resetView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            resetView.setTextColor(Theme.getColor(Theme.key_dialogTextGray3, rp));
            resetView.setPadding(dp(4), dp(6), dp(4), dp(6));
            TextView saveView = new TextView(context);
            saveView.setText(LocaleController.getString(R.string.ChatTimeZoneTemplateSave));
            saveView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            saveView.setTypeface(AndroidUtilities.bold());
            saveView.setTextColor(Theme.getColor(Theme.key_dialogTextBlue, rp));
            saveView.setPadding(dp(8), dp(6), dp(4), dp(6));
            actionRow.addView(resetView, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL));
            actionRow.addView(saveView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL));
            container.addView(actionRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 20, 0, 20, 0));

            final Runnable updatePreview = () -> previewView.setText(
                    renderTemplate(field.getText().toString(), selectedStep[0], startMs, peerTz, peerNameF, calLocal, calPeer));
            previewUpdater[0] = updatePreview;

            final Runnable applyScope = () -> {
                styleScopeChip(accountChip, scope[0] == 1, rp);
                styleScopeChip(globalChip, scope[0] == 0, rp);
            };
            final Runnable loadScope = () -> {
                String v = scope[0] == 1 ? ChatTimeZoneTemplate.getAccountOverride(currentAccount) : null;
                if (TextUtils.isEmpty(v)) v = ChatTimeZoneTemplate.getGlobal();
                if (TextUtils.isEmpty(v)) v = ChatTimeZoneTemplate.defaultTemplate();
                field.setText(v);
                field.setSelection(field.getText().length());
            };

            field.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
                @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
                @Override public void afterTextChanged(Editable s) { updatePreview.run(); }
            });
            accountChip.setOnClickListener(v -> { scope[0] = 1; applyScope.run(); loadScope.run(); });
            globalChip.setOnClickListener(v -> { scope[0] = 0; applyScope.run(); loadScope.run(); });
            resetView.setOnClickListener(v -> {
                field.setText(ChatTimeZoneTemplate.defaultTemplate());
                field.setSelection(field.getText().length());
            });
            saveView.setOnClickListener(v -> {
                String v2 = field.getText().toString();
                if (scope[0] == 1) ChatTimeZoneTemplate.setAccountOverride(currentAccount, v2);
                else ChatTimeZoneTemplate.setGlobal(v2);
                Toast.makeText(context, LocaleController.getString(R.string.ChatTimeZoneTemplateSaved), Toast.LENGTH_SHORT).show();
            });

            applyScope.run();
            loadScope.run();

            ButtonWithCounterView insertButton = new ButtonWithCounterView(context, rp);
            insertButton.setText(LocaleController.getString(R.string.ChatTimeZoneInsertTime), false);
            insertButton.setOnClickListener(v -> {
                insertHandler.run(renderTemplate(field.getText().toString(), selectedStep[0], startMs, peerTz, peerNameF, calLocal, calPeer));
                if (sheetRef[0] != null) {
                    sheetRef[0].dismiss();
                }
            });
            container.addView(insertButton, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48, 0, 16, 10, 16, 12));
        }

        BottomSheet.Builder builder = new BottomSheet.Builder(context, insertHandler != null, rp);
        builder.setApplyBottomPadding(false);
        builder.setCustomView(container);
        BottomSheet sheet = builder.create();
        sheetRef[0] = sheet;
        sheet.setBackgroundColor(Theme.getColor(Theme.key_dialogBackground, rp));
        sheet.fixNavigationBar(Theme.getColor(Theme.key_dialogBackground, rp));
        if (insertHandler != null && sheet.getWindow() != null) {
            sheet.getWindow().setSoftInputMode(
                    android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                            | android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN);
        }
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
        return name + " · " + ChatTimeZoneTemplate.formatOffset(diffMin);
    }

    private static CharSequence buildReadout(int step, long startMs, Calendar local, Calendar peer,
                                             String youLabel, String peerLabel, int accentColor) {
        long t = startMs + (long) step * STEP_MS;
        local.setTimeInMillis(t);
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

    /** Renders the message-format template for the pinned 15-minute step. */
    private static String renderTemplate(String template, int step, long startMs, TimeZone peerTz,
                                         String peerName, Calendar local, Calendar peer) {
        long t = startMs + (long) step * STEP_MS;
        local.setTimeInMillis(t);
        peer.setTimeInMillis(t);
        int offsetMin = (peerTz.getOffset(t) - TimeZone.getDefault().getOffset(t)) / 60_000;
        return ChatTimeZoneTemplate.render(template, local, peer, peerName, offsetMin);
    }

    private static TextView makeScopeChip(Context context, String text) {
        TextView t = new TextView(context);
        t.setText(text);
        t.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(14), dp(6), dp(14), dp(6));
        return t;
    }

    private static void styleScopeChip(TextView t, boolean selected, @Nullable Theme.ResourcesProvider rp) {
        if (selected) {
            t.setBackground(Theme.createRoundRectDrawable(dp(14), Theme.getColor(Theme.key_featuredStickers_addButton, rp)));
            t.setTextColor(Theme.getColor(Theme.key_featuredStickers_buttonText, rp));
            t.setTypeface(AndroidUtilities.bold());
        } else {
            t.setBackground(Theme.createRoundRectDrawable(dp(14), Theme.getColor(Theme.key_graySection, rp)));
            t.setTextColor(Theme.getColor(Theme.key_dialogTextBlack, rp));
            t.setTypeface(null);
        }
    }

    private static TextView makePlaceholderChip(Context context, String token, @Nullable Theme.ResourcesProvider rp) {
        TextView t = new TextView(context);
        t.setText(token);
        t.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(10), dp(5), dp(10), dp(5));
        t.setBackground(Theme.createRoundRectDrawable(dp(12), Theme.getColor(Theme.key_graySection, rp)));
        t.setTextColor(Theme.getColor(Theme.key_dialogTextBlue, rp));
        return t;
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
    /**
     * Scroll offset that centers a given step under the fixed cursor. The scroller
     * is padded by half its viewport on each side (see the layout listener), so a
     * step's centered scroll position is simply its content x; scrollTo clamps the
     * upper bound to the scroll range.
     */
    private static int centerScrollFor(HourStripView strip, int step) {
        return Math.max(0, Math.round(strip.xForStep(step)));
    }

    private static final class HourStripView extends View {

        final int cellW = dp(46);
        final int cellH = dp(40);
        final int gap = dp(2);

        private final long startMs;
        private final int nowHour;
        private final TimeZone peerTz;

        private final TextPaint hourPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF cellRect = new RectF();
        private final Rect clipRect = new Rect();
        private final Calendar cal = Calendar.getInstance();
        private final Calendar peerCal;

        final int nightColor, shoulderColor, workColor;
        private final int textColor, nightTextColor;

        HourStripView(Context context, long startMs, int nowHour, TimeZone peerTz,
                      @Nullable Theme.ResourcesProvider rp) {
            super(context);
            this.startMs = startMs;
            this.nowHour = nowHour;
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

            hourPaint.setTypeface(AndroidUtilities.bold());
            ringPaint.setStyle(Paint.Style.STROKE);
            ringPaint.setStrokeWidth(dp(1.5f));
            // "Now" ring is grey so the accent cursor stays the one accent cue.
            ringPaint.setColor(Theme.getColor(Theme.key_dialogTextGray3, rp));
        }

        int pitch() {
            return cellW + gap;
        }

        private float stepPitch() {
            return pitch() / (float) STEPS_PER_HOUR;
        }

        private int stepFromX(float x) {
            int step = Math.round(x / stepPitch());
            return Math.max(0, Math.min(STEPS - 1, step));
        }

        private float xForStep(int step) {
            return step * stepPitch();
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            setMeasuredDimension(HOURS * pitch() - gap, cellH * 2 + gap);
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
                drawCell(canvas, x, 0, cal, i);
                peerCal.setTimeInMillis(t);
                drawCell(canvas, x, cellH + gap, peerCal, i);
            }
        }

        private void drawCell(Canvas canvas, int x, int y, Calendar c, int index) {
            int hour = c.get(Calendar.HOUR_OF_DAY);
            int minute = c.get(Calendar.MINUTE);

            cellRect.set(x, y, x + cellW, y + cellH);
            bgPaint.setColor(colorForHour(hour));
            canvas.drawRoundRect(cellRect, dp(6), dp(6), bgPaint);
            if (index == nowHour) {
                // Mark the current hour with a ring so "now" stays findable after
                // scrolling away and sliding the pointer elsewhere.
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
            hourPaint.setColor(isNight(hour) && !(hour == 0 && minute == 0) ? nightTextColor : textColor);
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

    /**
     * Horizontal scroller that reports every scroll position change and tracks
     * whether the user's finger is down, so the sheet can read the step under the
     * fixed cursor and only buzz on user-driven moves (not fling or programmatic
     * recenters).
     */
    private static final class CenterScrollView extends HorizontalScrollView {
        Runnable onScrollChanged;
        boolean userDragging;

        CenterScrollView(Context context) {
            super(context);
            setHorizontalScrollBarEnabled(false);
        }

        @Override
        protected void onScrollChanged(int l, int t, int oldl, int oldt) {
            super.onScrollChanged(l, t, oldl, oldt);
            if (onScrollChanged != null) {
                onScrollChanged.run();
            }
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    userDragging = true;
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    userDragging = false;
                    break;
            }
            return super.onTouchEvent(event);
        }
    }

    /**
     * Non-interactive overlay drawing the fixed selection cursor (accent line plus
     * a handle dot on the lane divider) at its own horizontal center. Touches fall
     * through to the scroller beneath it so the plot pans normally.
     */
    private static final class CursorView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int laneH;
        private final int gap;

        CursorView(Context context, int accent, int laneH, int gap) {
            super(context);
            this.laneH = laneH;
            this.gap = gap;
            paint.setColor(accent);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float cx = getWidth() / 2f;
            int bottom = laneH * 2 + gap;
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(2));
            canvas.drawLine(cx, 0, cx, bottom, paint);
            paint.setStyle(Paint.Style.FILL);
            canvas.drawCircle(cx, laneH + gap / 2f, dp(6), paint);
        }
    }
}
