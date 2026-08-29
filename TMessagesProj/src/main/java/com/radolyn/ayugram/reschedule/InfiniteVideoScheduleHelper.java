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
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AlertsCreator;
import org.telegram.ui.Components.LayoutHelper;

import java.util.Calendar;

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
     * The schedule picker's day wheel maxes at this many days out (AlertsCreator: dayPicker.setMaxValue).
     */
    private static final int MAX_SCHEDULE_DAYS = 365;

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
    public static BottomSheet.Builder showScheduleSheet(Context context, long dialogId, FirstSegmentDelegate delegate,
                                         Runnable onCancel, Theme.ResourcesProvider resourcesProvider) {
        return AlertsCreator.createScheduleDatePickerDialog(
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
     * The schedule time for segment {@code segmentIndex}, clamped into the valid scheduling window.
     * Segment 0 gets the base as chosen; each later segment adds {@link #SEGMENT_INTERVAL_SECONDS}.
     *
     * <p>Invariant: the result is monotonically non-decreasing in {@code segmentIndex} -- no later
     * segment ever carries an earlier date than an earlier one. Two things hold it. The upper clamp
     * uses {@link #pickerMaxScheduleSeconds()}, which is greater than or equal to any instant the
     * picker can return, so the base (segment 0) is never clamped down and later segments only ever
     * clamp up to a bound that is at least the base. The lower clamp uses a non-decreasing server
     * {@code now} across the ~60s-apart per-segment evaluations, so a pause-driven past-due push
     * can't invert two segments either. Keep both bounds satisfying this -- loosening either can
     * silently reintroduce an ordering inversion.
     *
     * <p>The clamps are otherwise defence in depth against the device clock being skewed: a past-due
     * slot is pushed to {@code now + 60}; a slot past the 1-year window is pinned to the maximum.
     * Successive segments are recorded ~60s apart, so {@code max(slot, now + 60)} can't fold two
     * segments onto one slot. At the far end several trailing segments could share the maximum, but
     * that is unreachable in practice -- it needs the base at the 1-year maximum plus ~720 further
     * segments (12h+ of unbroken recording on the Unlimited ceiling). Even in that degenerate case,
     * equal schedule_dates dispatch in submission order and segments are submitted in recording
     * order, so the send order the invariant protects survives regardless.
     */
    public static int segmentDate(int currentAccount, int baseDate, int segmentIndex) {
        long slot = (long) baseDate + (long) SEGMENT_INTERVAL_SECONDS * segmentIndex;
        final long now = ConnectionsManager.getInstance(currentAccount).getCurrentTime();
        final long min = now + 60;
        final long max = pickerMaxScheduleSeconds();
        if (slot < min) {
            slot = min;
        }
        if (slot > max) {
            slot = max;
        }
        return (int) slot;
    }

    /**
     * The latest instant the schedule sheet can produce: today + 365 days at 23:59, on the device
     * clock, mirroring AlertsCreator's Done button (now + DAY_OF_YEAR 365, HOUR_OF_DAY 23, MINUTE 59).
     * The base comes straight from that picker on the same clock, so deriving the upper clamp this
     * way -- rather than from {@code serverNow + 365*24h}, which can fall up to a day short when the
     * user picks a late time on the final day -- guarantees the base is never above the clamp and so
     * segment 0 is never pulled earlier than a later segment.
     */
    private static long pickerMaxScheduleSeconds() {
        final Calendar c = Calendar.getInstance();
        c.add(Calendar.DAY_OF_YEAR, MAX_SCHEDULE_DAYS);
        c.set(Calendar.HOUR_OF_DAY, 23);
        c.set(Calendar.MINUTE, 59);
        c.set(Calendar.SECOND, 59);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis() / 1000L;
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
