package com.radolyn.ayugram.reschedule;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.R;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AlertsCreator;
import org.telegram.ui.Components.LayoutHelper;

/**
 * Scheduled infinite video messages (#infinite-video). When infinite recording is armed in a
 * scheduled chat, the first 60s segment can't just go out live like it does in a normal chat — the
 * user has to say when it lands. This asks once (a plain schedule sheet with a fixed title and a
 * raised minimum lead), captures that base time, and derives every following stitched segment as
 * {@code base + 120s × index}.
 *
 * <p>Only the base time and the captured notify flag live in ChatActivityEnterView; the sheet, the
 * spacing constant and the forward clamp live here so the base file's footprint stays tiny.
 */
public final class InfiniteVideoScheduleHelper {

    /**
     * Each stitched segment after the first is scheduled this many seconds past the one before it.
     * Fixed by product decision, not configurable.
     */
    public static final int SEGMENT_INTERVAL_SECONDS = 120;

    /**
     * The sheet's earliest pickable slot. 180s (vs the stock 60s) keeps segment 0 far enough ahead
     * that the +120s spacing stays honest and the first segment isn't routinely past-due.
     */
    public static final int MIN_LEAD_SECONDS = 180;

    /**
     * Telegram only schedules up to ~1 year out (the day picker maxes at 365).
     */
    private static final long MAX_SCHEDULE_SECONDS = 365L * 24 * 60 * 60;

    private InfiniteVideoScheduleHelper() {
    }

    public interface FirstSegmentDelegate {
        /**
         * @param notify       the silent-send toggle the user left the sheet on
         * @param scheduleDate the chosen unix time for segment 0, or a non-positive/sentinel value the
         *                     caller must treat as "not armed" (see the sentinel note in the writer guard)
         */
        void onSelected(boolean notify, int scheduleDate);
    }

    /**
     * Shows the "schedule the first message" sheet. Reuses the stock schedule sheet so the base-time
     * wheels and past-time validation are identical, then hands the chosen time + notify flag back.
     *
     * @param onCancel runs when the sheet is dismissed without a choice — the caller uses it to disarm.
     */
    public static void showScheduleSheet(Context context, long dialogId, FirstSegmentDelegate delegate,
                                         Runnable onCancel, Theme.ResourcesProvider resourcesProvider) {
        AlertsCreator.createScheduleDatePickerDialog(
                context,
                getString(R.string.InfiniteRecordingScheduleTitle),
                dialogId,
                -1,
                0,
                true,
                (notify, scheduleDate, scheduleRepeatPeriod) -> delegate.onSelected(notify, scheduleDate),
                onCancel,
                new AlertsCreator.ScheduleDatePickerColors(resourcesProvider),
                resourcesProvider,
                null,
                false,
                MIN_LEAD_SECONDS);
    }

    /**
     * The schedule time for segment {@code segmentIndex}, clamped into the valid scheduling window
     * against server time (not the device clock, which can be skewed). Segment 0 gets the base as
     * chosen; each later segment adds {@link #SEGMENT_INTERVAL_SECONDS}.
     *
     * <p>The clamps are defence in depth. A past-due slot is pushed to {@code now + 60}; a slot past
     * the 1-year limit is pinned to it. Successive segments are recorded ~60s apart, so their clamp
     * evaluations run ~60s apart too and {@code max(slot, now + 60)} can never fold two segments onto
     * one slot. At the far end several trailing segments could share the maximum, but that degenerate
     * case is unreachable in practice -- it needs the base at the 1-year maximum plus ~720 further
     * segments, i.e. 12h+ of unbroken recording on the Unlimited ceiling. Unreachability is the actual
     * guarantee: segments pinned to the same maximum would carry equal schedule_dates, and nothing here
     * establishes their relative send order, so the design keeps two segments off one slot rather than
     * relying on how the server breaks a tie.
     */
    public static int segmentDate(int currentAccount, int baseDate, int segmentIndex) {
        long slot = (long) baseDate + (long) SEGMENT_INTERVAL_SECONDS * segmentIndex;
        final long now = ConnectionsManager.getInstance(currentAccount).getCurrentTime();
        final long min = now + 60;
        final long max = now + MAX_SCHEDULE_SECONDS;
        if (slot < min) {
            slot = min;
        }
        if (slot > max) {
            slot = max;
        }
        return (int) slot;
    }

    /**
     * Appends the two explainer lines under the pickers: what the +2min spacing does, and a red
     * caveat that Telegram's scheduler is best-effort so tightly spaced sends can run out of order.
     */
    public static void installHints(Context context, LinearLayout container, int textColor, Theme.ResourcesProvider resourcesProvider) {
        final TextView spacing = new TextView(context);
        spacing.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        spacing.setTextColor(Theme.multAlpha(textColor, 0.75f));
        spacing.setGravity(Gravity.CENTER_HORIZONTAL);
        spacing.setPadding(dp(22), 0, dp(22), 0);
        spacing.setText(getString(R.string.InfiniteRecordingScheduleHint));
        container.addView(spacing, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 22, 6, 22, 0));

        final TextView ordering = new TextView(context);
        ordering.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        ordering.setGravity(Gravity.CENTER_HORIZONTAL);
        ordering.setPadding(dp(22), 0, dp(22), 0);
        ordering.setTextColor(Theme.getColor(Theme.key_text_RedRegular, resourcesProvider));
        ordering.setText(getString(R.string.InfiniteRecordingScheduleOrderingHint));
        container.addView(ordering, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 6));
    }
}
