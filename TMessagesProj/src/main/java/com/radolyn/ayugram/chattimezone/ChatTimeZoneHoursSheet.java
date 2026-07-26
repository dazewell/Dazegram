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
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Business.TimezonesController;
import org.telegram.ui.Cells.CollapseTextCell;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Stories.recorder.ButtonWithCounterView;

import java.util.Calendar;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Bottom sheet visualizing the hours around now side by side in the device's and
 * the peer's time zones (worldtimebuddy-style dual hour strip). Two horizontally
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
     * Today sits in the middle with two days of scroll-back and two ahead: enough to
     * reach "same time on Monday" either way without making the strip a chore to
     * scroll. Counted in flat hours off a midnight origin, so a week with a DST
     * change runs an hour long or short at the far end.
     */
    private static final int DAYS_EACH_SIDE = 2;
    private static final int HOURS = (DAYS_EACH_SIDE * 2 + 1) * 24;
    private static final long HOUR_MS = 3_600_000L;
    private static final int STEP_MIN = 15;
    private static final int STEPS_PER_HOUR = 60 / STEP_MIN;
    private static final long STEP_MS = STEP_MIN * 60_000L;
    private static final int STEPS = HOURS * STEPS_PER_HOUR;

    private ChatTimeZoneHoursSheet() {}

    /**
     * Curated shortlist for the "Message language" picker. First entry ("") means
     * "app language". Kept small and self-contained so it survives upstream merges;
     * weekday names render for any of these via {@code Calendar.getDisplayName},
     * while the default prose is only translated where a resource override exists
     * (currently Russian) and gracefully falls back to English elsewhere.
     */
    private static final String[] LANG_TAGS = {
            "", "en", "ru-RU", "uk", "es", "pt-BR", "de", "fr", "it",
            "tr", "pl", "nl", "ar", "fa", "id", "ja", "ko", "zh-CN"
    };

    /** Autonym display name for a language tag ("Русский"), or the "app language" label for "". */
    private static String languageDisplayName(String tag) {
        if (tag == null || tag.isEmpty()) {
            return LocaleController.getString(R.string.ChatTimeZoneLanguageDefault);
        }
        Locale l = Locale.forLanguageTag(tag);
        String name = l.getDisplayLanguage(l);
        if (TextUtils.isEmpty(name)) return tag;
        return name.substring(0, 1).toUpperCase(l) + name.substring(1);
    }

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

        // Column zero = 00:00 device-local, two days back. Stepping the day after
        // zeroing the clock keeps it on real midnight through a DST change.
        Calendar day0 = Calendar.getInstance();
        day0.set(Calendar.HOUR_OF_DAY, 0);
        day0.set(Calendar.MINUTE, 0);
        day0.set(Calendar.SECOND, 0);
        day0.set(Calendar.MILLISECOND, 0);
        day0.add(Calendar.DAY_OF_YEAR, -DAYS_EACH_SIDE);
        final long startMs = day0.getTimeInMillis();
        final long nowMs = System.currentTimeMillis();
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

        TextView rangeButton = new TextView(context);
        rangeButton.setText(LocaleController.getString(R.string.ChatTimeZoneRange));
        rangeButton.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        rangeButton.setTypeface(AndroidUtilities.bold());
        rangeButton.setPadding(dp(12), dp(6), dp(12), dp(6));
        titleRow.addView(rangeButton, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT,
                Gravity.CENTER_VERTICAL, 0, 0, 6, 0));

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

        // The range readout takes the same line twice, one per edge, each behind a
        // Start/End chip that selects which edge the cursor drives. Two full-width rows
        // rather than two side-by-side columns: the columns would ellipsize into
        // uselessness on a narrow screen, and this keeps the mapping people already read.
        final TextView startChip = makeEdgeChip(context, LocaleController.getString(R.string.ChatTimeZoneRangeStart));
        final TextView endChip = makeEdgeChip(context, LocaleController.getString(R.string.ChatTimeZoneRangeEnd));
        final TextView startText = makeEdgeReadout(context, rp);
        final TextView endText = makeEdgeReadout(context, rp);
        final TextView durationText = new TextView(context);
        durationText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        durationText.setTextColor(Theme.getColor(Theme.key_dialogTextGray3, rp));
        durationText.setSingleLine(true);

        final LinearLayout rangeRows = new LinearLayout(context);
        rangeRows.setOrientation(LinearLayout.VERTICAL);
        rangeRows.setVisibility(View.GONE);
        LinearLayout startRow = new LinearLayout(context);
        startRow.setOrientation(LinearLayout.HORIZONTAL);
        startRow.setGravity(Gravity.CENTER_VERTICAL);
        startRow.addView(startChip, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT,
                Gravity.CENTER_VERTICAL, 0, 0, 8, 0));
        startRow.addView(startText, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL));
        LinearLayout endRow = new LinearLayout(context);
        endRow.setOrientation(LinearLayout.HORIZONTAL);
        endRow.setGravity(Gravity.CENTER_VERTICAL);
        endRow.addView(endChip, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT,
                Gravity.CENTER_VERTICAL, 0, 0, 8, 0));
        endRow.addView(endText, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL));
        endRow.addView(durationText, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT,
                Gravity.CENTER_VERTICAL, 8, 0, 0, 0));
        rangeRows.addView(startRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT,
                0, 0, 0, 4));
        rangeRows.addView(endRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        container.addView(rangeRows, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT,
                22, 10, 22, 10));

        // Strip row: fixed lane labels on the left + shared horizontal scroller.
        LinearLayout stripRow = new LinearLayout(context);
        stripRow.setOrientation(LinearLayout.HORIZONTAL);

        final Runnable[] previewUpdater = { null };
        final HourStripView strip = new HourStripView(context, startMs, peerTz, rp);

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
        // The second pair only comes into play for a range's other edge.
        final Calendar calLocal = Calendar.getInstance();
        final Calendar calPeer = Calendar.getInstance(peerTz);
        final Calendar calLocal2 = Calendar.getInstance();
        final Calendar calPeer2 = Calendar.getInstance(peerTz);
        final int readoutColor = Theme.getColor(Theme.key_dialogTextBlue, rp);
        // The 15-minute step currently under the fixed center cursor. In range mode it
        // is the edge the cursor drives; pinnedStep is the other one (-1 = point mode).
        final int[] selectedStep = { nowStep };
        final int[] pinnedStep = { -1 };
        // Locale the readout / strip weekdays / preview render in; null = app language.
        // Driven by the "Message language" selector in the editor block below.
        final Locale[] outputLocale = { null };
        // Set by the editor block below to relabel/reload itself when the mode flips.
        final Runnable[] modeUpdater = { null };
        final Runnable refreshReadout = () -> {
            boolean range = pinnedStep[0] >= 0;
            readoutView.setVisibility(range ? View.GONE : View.VISIBLE);
            rangeRows.setVisibility(range ? View.VISIBLE : View.GONE);
            if (!range) {
                CharSequence text = buildReadout(selectedStep[0], startMs, calLocal, calPeer,
                        youLabel, peerLabel, readoutColor, outputLocale[0]);
                readoutView.setText(text);
                strip.setContentDescription(text);
                strip.setBand(-1, -1);
                return;
            }
            // Which edge is "start" follows the clock, not which one the user grabbed:
            // dragging the active edge past the other one renames them rather than
            // letting an end sit before its start.
            boolean activeIsStart = selectedStep[0] <= pinnedStep[0];
            int startStep = Math.min(selectedStep[0], pinnedStep[0]);
            int endStep = Math.max(selectedStep[0], pinnedStep[0]);
            CharSequence startLine = buildReadout(startStep, startMs, calLocal, calPeer,
                    youLabel, peerLabel, readoutColor, outputLocale[0]);
            CharSequence endLine = buildReadout(endStep, startMs, calLocal, calPeer,
                    youLabel, peerLabel, readoutColor, outputLocale[0]);
            startText.setText(startLine);
            endText.setText(endLine);
            durationText.setText(ChatTimeZoneRenderer.formatDuration((long) (endStep - startStep) * STEP_MS));
            styleScopeChip(startChip, activeIsStart, rp);
            styleScopeChip(endChip, !activeIsStart, rp);
            startChip.setContentDescription(edgeDescription(
                    LocaleController.getString(R.string.ChatTimeZoneRangeStart), startLine, activeIsStart));
            endChip.setContentDescription(edgeDescription(
                    LocaleController.getString(R.string.ChatTimeZoneRangeEnd), endLine, !activeIsStart));
            strip.setContentDescription(startLine + " — " + endLine + ", " + durationText.getText());
            strip.setBand(selectedStep[0], pinnedStep[0]);
        };
        final Runnable syncSelection = () -> {
            int step = strip.stepFromX(centerContentX(strip, scroller));
            if (step == selectedStep[0]) return;
            selectedStep[0] = step;
            // Buzz on user-driven moves — the drag and the fling it throws — but
            // not on programmatic recenters (the Now button, the initial centering).
            if (scroller.userScrolling) {
                strip.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            }
            refreshReadout.run();
            if (previewUpdater[0] != null) previewUpdater[0].run();
        };
        scroller.onScrollChanged = syncSelection;
        // Seed the readout for "now" before the first layout gives the scroller a width.
        refreshReadout.run();

        // Open with "now" under the cursor. Deferred to layout, when the scroller
        // finally has a width to center against, and only marked done once the
        // scroll sticks; re-centering after that would yank the plot out from under
        // a drag.
        final boolean[] centered = { false };
        scroller.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (!centered[0] && scroller.getWidth() > 0) {
                int target = centerScrollFor(strip, scroller, nowStep);
                scroller.scrollTo(target, 0);
                centered[0] = scroller.getScrollX() == target;
            }
            syncSelection.run();
        });

        // Recompute "now" at click time so a long-open sheet returns to the real
        // current moment, not the step captured when the sheet was first shown.
        // In range mode it moves the edge under the cursor and leaves the other pinned.
        nowButton.setOnClickListener(v -> {
            int liveNowStep = (int) Math.min(STEPS - 1, Math.max(0,
                    Math.round((System.currentTimeMillis() - startMs) / (double) STEP_MS)));
            scroller.cancelUserScrolling();
            scroller.smoothScrollTo(centerScrollFor(strip, scroller, liveNowStep), 0);
        });

        styleModeChip(rangeButton, false, rp);
        rangeButton.setContentDescription(LocaleController.getString(R.string.ChatTimeZoneAccRangeOff));
        rangeButton.setOnClickListener(v -> {
            if (pinnedStep[0] >= 0) {
                // Leaving range mode: the edge already under the cursor becomes the
                // point, so nothing moves and the strip doesn't jump.
                pinnedStep[0] = -1;
            } else {
                pinnedStep[0] = selectedStep[0];
                // Seed an hour-long range and put its other edge under the cursor: the
                // scroll shows what just happened and leaves the finger where it needs
                // to be to stretch it. Runs backwards at the very end of the strip.
                int target = selectedStep[0] + STEPS_PER_HOUR;
                if (target > STEPS - 1) target = Math.max(0, selectedStep[0] - STEPS_PER_HOUR);
                scroller.cancelUserScrolling();
                scroller.smoothScrollTo(centerScrollFor(strip, scroller, target), 0);
            }
            boolean range = pinnedStep[0] >= 0;
            styleModeChip(rangeButton, range, rp);
            rangeButton.setContentDescription(LocaleController.getString(
                    range ? R.string.ChatTimeZoneAccRangeOn : R.string.ChatTimeZoneAccRangeOff));
            nowButton.setContentDescription(range
                    ? LocaleController.getString(R.string.ChatTimeZoneAccNowEdge) : null);
            refreshReadout.run();
            if (modeUpdater[0] != null) modeUpdater[0].run();
            if (previewUpdater[0] != null) previewUpdater[0].run();
        });

        // Tapping the other edge hands it the cursor. The jump is deliberate: the
        // range itself doesn't change, only which end you're holding, and animating
        // it would drag the active edge across every step in between.
        final Runnable swapEdges = () -> {
            if (pinnedStep[0] < 0) return;
            int target = pinnedStep[0];
            pinnedStep[0] = selectedStep[0];
            selectedStep[0] = target;
            scroller.cancelUserScrolling();
            scroller.scrollTo(centerScrollFor(strip, scroller, target), 0);
            refreshReadout.run();
            if (previewUpdater[0] != null) previewUpdater[0].run();
        };
        startChip.setOnClickListener(v -> { if (selectedStep[0] > pinnedStep[0]) swapEdges.run(); });
        endChip.setOnClickListener(v -> { if (selectedStep[0] < pinnedStep[0]) swapEdges.run(); });

        final BottomSheet[] sheetRef = new BottomSheet[1];
        if (insertHandler != null) {
            // Message-format template editor: edit, preview live against the pinned
            // instant, choose account/global scope, and insert. Insert renders from
            // the current draft so what you preview is what gets inserted.
            //
            // Everything that configures the format sits in a section that starts
            // collapsed, so the sheet opens on the time comparison and the insert
            // button; the live preview stays out of it since that's what the button
            // will actually produce.
            final int[] scope = { ChatTimeZoneTemplate.hasAccountOverride(currentAccount) ? 1 : 0 };

            final CollapseTextCell tmplHeader = new CollapseTextCell(context, rp);
            tmplHeader.setColor(Theme.key_dialogTextBlack);
            tmplHeader.setBackground(Theme.createSelectorDrawable(
                    Theme.getColor(Theme.key_dialogButtonSelector, rp), Theme.RIPPLE_MASK_ALL));
            // CollapseTextCell hides its own text from accessibility, so the row has to
            // carry the label and its open/closed state itself. The label also names
            // which of the two templates is in the field, so the section can follow the
            // strip's mode without a second mode selector inside it.
            final boolean[] expanded = { false };
            final Utilities.Callback<Boolean> setTmplHeader = open -> {
                String tmplLabel = LocaleController.getString(pinnedStep[0] >= 0
                        ? R.string.ChatTimeZoneTemplateRange : R.string.ChatTimeZoneTemplate);
                tmplHeader.set(tmplLabel, !open);
                tmplHeader.setContentDescription(tmplLabel + ", " + LocaleController.getString(
                        open ? R.string.AccDescrExpanded : R.string.AccDescrCollapsed));
            };
            setTmplHeader.run(false);
            container.addView(tmplHeader, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT,
                    0, 4, 0, 0));

            final LinearLayout tmplBody = new LinearLayout(context);
            tmplBody.setOrientation(LinearLayout.VERTICAL);
            tmplBody.setVisibility(View.GONE);
            container.addView(tmplBody, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

            LinearLayout scopeRow = new LinearLayout(context);
            scopeRow.setOrientation(LinearLayout.HORIZONTAL);
            final TextView accountChip = makeScopeChip(context, LocaleController.getString(R.string.ChatTimeZoneTemplateScopeAccount));
            final TextView globalChip = makeScopeChip(context, LocaleController.getString(R.string.ChatTimeZoneTemplateScopeGlobal));
            scopeRow.addView(accountChip, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 0, 8, 0));
            scopeRow.addView(globalChip, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));
            tmplBody.addView(scopeRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 22, 8, 22, 0));

            // "Message language" row: a label plus a tappable chip that opens a
            // shortlist picker. Drives weekday rendering (and, where translated,
            // the default prose) so a user writing in another language than the
            // app UI gets output in that language.
            final String[] langTag = { "" };
            LinearLayout langRow = new LinearLayout(context);
            langRow.setOrientation(LinearLayout.HORIZONTAL);
            langRow.setGravity(Gravity.CENTER_VERTICAL);
            TextView langLabel = new TextView(context);
            langLabel.setText(LocaleController.getString(R.string.ChatTimeZoneMessageLanguage));
            langLabel.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            langLabel.setTextColor(Theme.getColor(Theme.key_dialogTextBlack, rp));
            final TextView langValue = makeScopeChip(context, languageDisplayName(langTag[0]));
            styleScopeChip(langValue, false, rp);
            langRow.addView(langLabel, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL));
            langRow.addView(langValue, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL));
            tmplBody.addView(langRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 22, 8, 22, 0));

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
            tmplBody.addView(fieldBox, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 22, 8, 22, 0));

            // One chip row per template, swapped with the mode: the range tokens are a
            // different set, and offering both at once would let you tap a token the
            // active template can't fill.
            final HorizontalScrollView pointChips = makeTokenChips(context, rp, ChatTimeZoneTemplate.TOKENS, field);
            final HorizontalScrollView rangeChips = makeTokenChips(context, rp, ChatTimeZoneTemplate.RANGE_TOKENS, field);
            rangeChips.setVisibility(View.GONE);
            tmplBody.addView(pointChips, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 22, 8, 22, 0));
            tmplBody.addView(rangeChips, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 22, 8, 22, 0));

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
            tmplBody.addView(actionRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 20, 0, 20, 0));

            // Preview of what the insert button will produce: stays outside the
            // collapsible body so the sheet still says what it's about to send.
            TextView previewView = new TextView(context);
            previewView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            previewView.setTextColor(Theme.getColor(Theme.key_dialogTextGray3, rp));
            previewView.setSingleLine(true);
            previewView.setEllipsize(TextUtils.TruncateAt.END);
            container.addView(previewView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 22, 8, 22, 0));

            // In-memory draft per template so flipping the strip's mode swaps the field
            // without throwing away what you typed in the other one.
            final boolean[] editorRange = { pinnedStep[0] >= 0 };
            final String[] drafts = { null, null };

            final Runnable updatePreview = () -> previewView.setText(
                    renderSelection(field.getText().toString(), selectedStep[0], pinnedStep[0], startMs,
                            peerTz, peerNameF, calLocal, calPeer, calLocal2, calPeer2, outputLocale[0]));
            previewUpdater[0] = updatePreview;

            final Runnable applyScope = () -> {
                styleScopeChip(accountChip, scope[0] == 1, rp);
                styleScopeChip(globalChip, scope[0] == 0, rp);
            };
            // Apply a language choice: swap the resolved locale, refresh the chip
            // label, strip weekdays, readout and preview. When {@code replaceDefault}
            // and the field still holds the previous language's default, retranslate
            // it too — so picking a language updates the prose while preserving any
            // custom edits the user made.
            final Utilities.Callback2<String, Boolean> applyLanguage = (tag, replaceDefault) -> {
                Locale newLocale = ChatTimeZoneTemplate.localeFor(tag);
                if (replaceDefault) {
                    String oldDefault = ChatTimeZoneTemplate.defaultTemplate(editorRange[0], outputLocale[0]);
                    if (field.getText().toString().equals(oldDefault)) {
                        field.setText(ChatTimeZoneTemplate.defaultTemplate(editorRange[0], newLocale));
                        field.setSelection(field.getText().length());
                    }
                    // The mode that isn't on screen gets the same treatment, or it would
                    // come back still written in the language you just switched away from.
                    int other = editorRange[0] ? 0 : 1;
                    if (drafts[other] != null
                            && drafts[other].equals(ChatTimeZoneTemplate.defaultTemplate(other == 1, outputLocale[0]))) {
                        drafts[other] = ChatTimeZoneTemplate.defaultTemplate(other == 1, newLocale);
                    }
                }
                langTag[0] = tag == null ? "" : tag;
                outputLocale[0] = newLocale;
                langValue.setText(languageDisplayName(langTag[0]));
                strip.setWeekdayLocale(newLocale);
                refreshReadout.run();
                updatePreview.run();
            };
            final Runnable loadScope = () -> {
                String tag = scope[0] == 1 ? ChatTimeZoneTemplate.getAccountLanguage(currentAccount) : null;
                if (tag == null) tag = ChatTimeZoneTemplate.getGlobalLanguage();
                langTag[0] = tag == null ? "" : tag;
                outputLocale[0] = ChatTimeZoneTemplate.localeFor(langTag[0]);
                langValue.setText(languageDisplayName(langTag[0]));
                strip.setWeekdayLocale(outputLocale[0]);

                // Both drafts belong to the scope they were read from, so a scope switch
                // drops them and the other template reloads when it's next shown.
                drafts[0] = drafts[1] = null;
                field.setText(storedTemplate(currentAccount, scope[0] == 1, editorRange[0], outputLocale[0]));
                field.setSelection(field.getText().length());
                refreshReadout.run();
                updatePreview.run();
            };
            modeUpdater[0] = () -> {
                boolean range = pinnedStep[0] >= 0;
                if (range == editorRange[0]) return;
                drafts[editorRange[0] ? 1 : 0] = field.getText().toString();
                editorRange[0] = range;
                String next = drafts[range ? 1 : 0];
                if (next == null) {
                    next = storedTemplate(currentAccount, scope[0] == 1, range, outputLocale[0]);
                }
                field.setText(next);
                field.setSelection(field.getText().length());
                field.setHint(LocaleController.getString(range
                        ? R.string.ChatTimeZoneRangeTemplateHint : R.string.ChatTimeZoneTemplateHint));
                pointChips.setVisibility(range ? View.GONE : View.VISIBLE);
                rangeChips.setVisibility(range ? View.VISIBLE : View.GONE);
                setTmplHeader.run(expanded[0]);
            };

            field.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
                @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
                @Override public void afterTextChanged(Editable s) { updatePreview.run(); }
            });
            tmplHeader.setOnClickListener(v -> {
                expanded[0] = !expanded[0];
                setTmplHeader.run(expanded[0]);
                tmplBody.setVisibility(expanded[0] ? View.VISIBLE : View.GONE);
                if (!expanded[0]) {
                    // Collapsing out from under the keyboard would leave it up over a
                    // sheet that no longer has a field.
                    AndroidUtilities.hideKeyboard(field);
                    field.clearFocus();
                }
            });
            langValue.setOnClickListener(v -> {
                String[] names = new String[LANG_TAGS.length];
                for (int i = 0; i < LANG_TAGS.length; i++) names[i] = languageDisplayName(LANG_TAGS[i]);
                AlertDialog.Builder b = new AlertDialog.Builder(context, rp);
                b.setTitle(LocaleController.getString(R.string.ChatTimeZoneMessageLanguage));
                b.setItems(names, (dialog, which) -> applyLanguage.run(LANG_TAGS[which], true));
                b.show();
            });
            accountChip.setOnClickListener(v -> { scope[0] = 1; applyScope.run(); loadScope.run(); });
            globalChip.setOnClickListener(v -> { scope[0] = 0; applyScope.run(); loadScope.run(); });
            resetView.setOnClickListener(v -> {
                field.setText(ChatTimeZoneTemplate.defaultTemplate(editorRange[0], outputLocale[0]));
                field.setSelection(field.getText().length());
            });
            saveView.setOnClickListener(v -> {
                String v2 = field.getText().toString();
                if (scope[0] == 1) {
                    ChatTimeZoneTemplate.setAccountOverride(currentAccount, editorRange[0], v2);
                    ChatTimeZoneTemplate.setAccountLanguage(currentAccount, langTag[0]);
                } else {
                    ChatTimeZoneTemplate.setGlobal(editorRange[0], v2);
                    ChatTimeZoneTemplate.setGlobalLanguage(langTag[0]);
                }
                Toast.makeText(context, LocaleController.getString(editorRange[0]
                                ? R.string.ChatTimeZoneRangeTemplateSaved : R.string.ChatTimeZoneTemplateSaved),
                        Toast.LENGTH_SHORT).show();
            });

            applyScope.run();
            loadScope.run();

            ButtonWithCounterView insertButton = new ButtonWithCounterView(context, rp);
            insertButton.setText(LocaleController.getString(R.string.ChatTimeZoneInsertTime), false);
            insertButton.setOnClickListener(v -> {
                insertHandler.run(renderSelection(field.getText().toString(), selectedStep[0], pinnedStep[0],
                        startMs, peerTz, peerNameF, calLocal, calPeer, calLocal2, calPeer2, outputLocale[0]));
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
                                             String youLabel, String peerLabel, int accentColor,
                                             @Nullable Locale locale) {
        long t = startMs + (long) step * STEP_MS;
        local.setTimeInMillis(t);
        peer.setTimeInMillis(t);

        SpannableStringBuilder ssb = new SpannableStringBuilder();
        ssb.append(youLabel).append(" ∙ ").append(ChatTimeZoneRenderer.formatSide(local, locale))
                .append("  →  ")
                .append(peerLabel).append(" ∙ ").append(ChatTimeZoneRenderer.formatSide(peer, locale));

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
                                         String peerName, Calendar local, Calendar peer,
                                         @Nullable Locale locale) {
        long t = startMs + (long) step * STEP_MS;
        local.setTimeInMillis(t);
        peer.setTimeInMillis(t);
        int offsetMin = (peerTz.getOffset(t) - TimeZone.getDefault().getOffset(t)) / 60_000;
        return ChatTimeZoneTemplate.render(template, local, peer, peerName, offsetMin, locale);
    }

    /**
     * Renders whatever is currently selected: the point template for a single step, the
     * range template when a second edge is pinned. Duration comes from the two instants,
     * so a zone change inside the span reports the time that actually passes rather than
     * the difference between two clock faces.
     */
    private static String renderSelection(String template, int activeStep, int pinnedStep, long startMs,
                                          TimeZone peerTz, String peerName,
                                          Calendar local, Calendar peer, Calendar localEnd, Calendar peerEnd,
                                          @Nullable Locale locale) {
        if (pinnedStep < 0) {
            return renderTemplate(template, activeStep, startMs, peerTz, peerName, local, peer, locale);
        }
        long t0 = startMs + (long) Math.min(activeStep, pinnedStep) * STEP_MS;
        long t1 = startMs + (long) Math.max(activeStep, pinnedStep) * STEP_MS;
        local.setTimeInMillis(t0);
        peer.setTimeInMillis(t0);
        localEnd.setTimeInMillis(t1);
        peerEnd.setTimeInMillis(t1);
        int offsetMin = (peerTz.getOffset(t0) - TimeZone.getDefault().getOffset(t0)) / 60_000;
        return ChatTimeZoneTemplate.renderRange(template, local, localEnd, peer, peerEnd,
                peerName, offsetMin, t1 - t0, locale);
    }

    /** The template the given scope resolves to, falling back the same way rendering does. */
    private static String storedTemplate(int account, boolean accountScope, boolean range,
                                         @Nullable Locale locale) {
        String v = accountScope ? ChatTimeZoneTemplate.getAccountOverride(account, range) : null;
        if (TextUtils.isEmpty(v)) v = ChatTimeZoneTemplate.getGlobal(range);
        if (TextUtils.isEmpty(v)) v = ChatTimeZoneTemplate.defaultTemplate(range, locale);
        return v;
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

    /** A scrollable row of token chips that tap themselves into the template field. */
    private static HorizontalScrollView makeTokenChips(Context context, @Nullable Theme.ResourcesProvider rp,
                                                       String[] tokens, EditText field) {
        HorizontalScrollView scroller = new HorizontalScrollView(context);
        scroller.setHorizontalScrollBarEnabled(false);
        LinearLayout chips = new LinearLayout(context);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        for (String token : tokens) {
            TextView chip = makePlaceholderChip(context, token, rp);
            chip.setOnClickListener(v -> {
                int s = Math.max(0, field.getSelectionStart());
                int e = Math.max(s, field.getSelectionEnd());
                field.getText().replace(s, e, token);
            });
            chips.addView(chip, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 0, 6, 0));
        }
        scroller.addView(chips);
        return scroller;
    }

    /** The Range pill: an accent-filled toggle that reads as a sibling of "Now" when off. */
    private static void styleModeChip(TextView t, boolean on, @Nullable Theme.ResourcesProvider rp) {
        if (on) {
            t.setBackground(Theme.createRoundRectDrawable(dp(14), Theme.getColor(Theme.key_featuredStickers_addButton, rp)));
            t.setTextColor(Theme.getColor(Theme.key_featuredStickers_buttonText, rp));
        } else {
            t.setBackground(Theme.createRoundRectDrawable(dp(14), Theme.getColor(Theme.key_graySection, rp)));
            t.setTextColor(Theme.getColor(Theme.key_featuredStickers_addButton, rp));
        }
    }

    /** Start/End selector: narrower than a scope chip so the readout beside it keeps its width. */
    private static TextView makeEdgeChip(Context context, String text) {
        TextView t = new TextView(context);
        t.setText(text);
        t.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(10), dp(4), dp(10), dp(4));
        return t;
    }

    private static TextView makeEdgeReadout(Context context, @Nullable Theme.ResourcesProvider rp) {
        TextView t = new TextView(context);
        t.setTextColor(Theme.getColor(Theme.key_dialogTextBlack, rp));
        t.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        t.setTypeface(AndroidUtilities.bold());
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        return t;
    }

    private static String edgeDescription(String label, CharSequence line, boolean selected) {
        return LocaleController.formatString(selected
                        ? R.string.ChatTimeZoneAccEdgeSelected : R.string.ChatTimeZoneAccEdgeUnselected,
                label, line.toString());
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
     * Content x sitting under the fixed cursor, measured from the strip's own
     * laid-out origin. Reading the raw scroll offset instead only holds while the
     * scroller's half-viewport padding is really in place, and puts every reading
     * half a viewport off the line the user is aiming with the moment it isn't.
     */
    private static float centerContentX(HourStripView strip, CenterScrollView scroller) {
        return scroller.getScrollX() + scroller.getWidth() / 2f - strip.getLeft();
    }

    /** Scroll offset that puts a given step under the cursor. */
    private static int centerScrollFor(HourStripView strip, CenterScrollView scroller, int step) {
        return Math.max(0, Math.round(strip.getLeft() + strip.xForStep(step) - scroller.getWidth() / 2f));
    }

    /**
     * Canvas-drawn dual lane of hour cells: top lane = device-local, bottom
     * lane = peer zone, columns aligned by instant. Only the cells inside the
     * clip rect are drawn, so the off-screen end of the span costs nothing and
     * no view recycling is needed.
     */
    private static final class HourStripView extends View {

        final int cellW = dp(46);
        final int cellH = dp(40);
        final int gap = dp(2);

        private final long startMs;

        private final TextPaint hourPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint bandPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint edgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF cellRect = new RectF();
        private final Rect clipRect = new Rect();
        private final Calendar cal = Calendar.getInstance();
        private final Calendar peerCal;
        // Moves the ring along when the hour rolls over under an idle sheet.
        private final Runnable hourTick = () -> {
            invalidate();
            scheduleHourTick();
        };

        final int nightColor, shoulderColor, workColor;
        private final int textColor, nightTextColor;
        // Locale for the midnight weekday labels; null = app language.
        private Locale weekdayLocale;
        // The selected span in steps; -1/-1 in point mode. bandPinned is the edge the
        // cursor isn't holding, so it's the only one that needs its own line drawn.
        private int bandActive = -1, bandPinned = -1;

        HourStripView(Context context, long startMs, TimeZone peerTz,
                      @Nullable Theme.ResourcesProvider rp) {
            super(context);
            this.startMs = startMs;
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

            int accent = Theme.getColor(Theme.key_featuredStickers_addButton, rp);
            // Translucent so the hour colours and their labels still read through the span.
            bandPaint.setColor(ColorUtils.setAlphaComponent(accent, 56));
            edgePaint.setColor(accent);
            edgePaint.setStrokeWidth(dp(2));
        }

        /** Marks the selected span; pass -1 for either edge to clear it. */
        void setBand(int activeStep, int pinnedStep) {
            if (bandActive == activeStep && bandPinned == pinnedStep) return;
            bandActive = activeStep;
            bandPinned = pinnedStep;
            invalidate();
        }

        int pitch() {
            return cellW + gap;
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            scheduleHourTick();
        }

        @Override
        protected void onDetachedFromWindow() {
            super.onDetachedFromWindow();
            removeCallbacks(hourTick);
        }

        private void scheduleHourTick() {
            postDelayed(hourTick, HOUR_MS - Math.floorMod(System.currentTimeMillis() - startMs, HOUR_MS));
        }

        /** Re-render midnight weekday labels in {@code locale} (null = app language). */
        void setWeekdayLocale(@Nullable Locale locale) {
            this.weekdayLocale = locale;
            invalidate();
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
            int nowHour = (int) Math.floorDiv(System.currentTimeMillis() - startMs, HOUR_MS);
            for (int i = first; i <= last; i++) {
                long t = startMs + i * HOUR_MS;
                int x = i * pitch();
                cal.setTimeInMillis(t);
                drawCell(canvas, x, 0, cal, i == nowHour);
                peerCal.setTimeInMillis(t);
                drawCell(canvas, x, cellH + gap, peerCal, i == nowHour);
            }
            if (bandActive >= 0 && bandPinned >= 0) {
                // Drawn over the cells rather than under them, and across both lanes at
                // once: the span is one instant range, and the two lanes are the same
                // instants read on two clocks. Off-screen edges cost nothing -- the
                // canvas clips the rect, and the step-to-x math doesn't need the cell.
                int bottom = cellH * 2 + gap;
                canvas.drawRect(xForStep(Math.min(bandActive, bandPinned)), 0,
                        xForStep(Math.max(bandActive, bandPinned)), bottom, bandPaint);
                float px = xForStep(bandPinned);
                canvas.drawLine(px, 0, px, bottom, edgePaint);
            }
        }

        private void drawCell(Canvas canvas, int x, int y, Calendar c, boolean now) {
            int hour = c.get(Calendar.HOUR_OF_DAY);
            int minute = c.get(Calendar.MINUTE);

            cellRect.set(x, y, x + cellW, y + cellH);
            bgPaint.setColor(colorForHour(hour));
            canvas.drawRoundRect(cellRect, dp(6), dp(6), bgPaint);
            if (now) {
                // Mark the current hour with a ring so "now" stays findable after
                // scrolling away and sliding the pointer elsewhere.
                cellRect.inset(dp(1), dp(1));
                canvas.drawRoundRect(cellRect, dp(5), dp(5), ringPaint);
            }

            String label;
            if (hour == 0 && minute == 0) {
                // Midnight cell shows the weekday: it doubles as a date separator.
                label = ChatTimeZoneRenderer.weekday(c, weekdayLocale);
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
     * whether the current motion is user-initiated — the finger drag and the
     * fling it launches, but not programmatic recenters — so the sheet can read
     * the step under the fixed cursor and buzz only on those user-driven moves.
     */
    private static final class CenterScrollView extends HorizontalScrollView {
        // Grace period after the last fling frame before the motion counts as
        // settled; comfortably longer than a 60fps frame so it never trips mid-fling.
        private static final long FLING_SETTLE_MS = 90;

        Runnable onScrollChanged;
        // True while the motion is user-initiated: the finger drag and the fling it
        // launches. Stays false for programmatic scrolls (smoothScrollTo/scrollTo).
        boolean userScrolling;
        private boolean fingerDown;
        private final Runnable clearScrolling = () -> userScrolling = false;

        CenterScrollView(Context context) {
            super(context);
            setHorizontalScrollBarEnabled(false);
        }

        /**
         * Carry half a viewport of padding on each side so the first and last steps
         * can still be scrolled under the center cursor. It's taken here, off the
         * measured width, because the strip then gets laid out against it in the same
         * pass: setting it from a layout callback instead asks for a relayout that
         * this view's own layout() throws away, leaving the padding counted but the
         * strip still sitting at x=0.
         */
        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
            int pad = getMeasuredWidth() / 2;
            if (pad > 0 && getPaddingLeft() != pad) {
                setClipToPadding(false);
                setPadding(pad, 0, pad, 0);
            }
        }

        /** Hands the next motion over to code: recentering on top of a live fling shouldn't buzz. */
        void cancelUserScrolling() {
            removeCallbacks(clearScrolling);
            userScrolling = false;
            // Stop the scroller itself, not just the flag. A fling still in flight would
            // otherwise carry on past whatever position the caller is about to scroll to,
            // dragging the selected edge along with it. A zero-velocity fling is the only
            // public way to abort one that doesn't also disturb smoothScrollTo's timing.
            fling(0);
        }

        @Override
        protected void onScrollChanged(int l, int t, int oldl, int oldt) {
            super.onScrollChanged(l, t, oldl, oldt);
            // During the fling (finger already up) keep the flag alive frame by
            // frame; once the scroll stops, the last-armed runnable clears it.
            if (userScrolling && !fingerDown) {
                removeCallbacks(clearScrolling);
                postDelayed(clearScrolling, FLING_SETTLE_MS);
            }
            if (onScrollChanged != null) {
                onScrollChanged.run();
            }
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    removeCallbacks(clearScrolling);
                    fingerDown = true;
                    userScrolling = true;
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    fingerDown = false;
                    // Arm the settle timer: with no fling it clears the flag shortly;
                    // a fling reposts it each frame until the glide actually stops.
                    removeCallbacks(clearScrolling);
                    postDelayed(clearScrolling, FLING_SETTLE_MS);
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
