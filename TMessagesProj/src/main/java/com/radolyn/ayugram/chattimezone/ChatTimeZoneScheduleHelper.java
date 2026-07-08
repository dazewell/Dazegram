package com.radolyn.ayugram.chattimezone;

import android.content.Context;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.NumberPicker;

import java.util.Calendar;
import java.util.TimeZone;

/**
 * Hook for the schedule-message date picker
 * ({@code AlertsCreator.createScheduleDatePickerDialog}): a one-line readout
 * under the day/hour/minute pickers showing the selected moment in the peer's
 * time zone, e.g. "Alex's time: Wed 03:30 +1d". Follows the formatting
 * conventions of {@link ChatTimeZoneHoursSheet}.
 */
public final class ChatTimeZoneScheduleHelper {

    private ChatTimeZoneScheduleHelper() {}

    /**
     * Appends the readout line to {@code container}. No-op (returns null) when
     * the dialog has no configured time zone (1:1 user chats and groups are
     * supported) or the zone matches the device's.
     *
     * @return runnable that re-renders the line from the pickers' current
     *         values; invoke it whenever a picker value may have changed.
     */
    @Nullable
    public static Runnable addPeerTimeLine(Context context, LinearLayout container,
                                           int currentAccount, long dialogId,
                                           int textColor, int accentColor,
                                           NumberPicker dayPicker, NumberPicker hourPicker, NumberPicker minutePicker) {
        final TimeZone tz = ChatTimeZoneController.getForDialog(currentAccount, dialogId);
        if (tz == null || ChatTimeZoneRenderer.sameAsLocal(tz)) {
            return null;
        }
        String name = ChatTimeZoneController.getDialogName(currentAccount, dialogId);
        if (TextUtils.isEmpty(name)) {
            name = LocaleController.getString(R.string.ChatTimeZone);
        }
        final String peerName = name;

        final TextView line = new TextView(context);
        line.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        line.setTextColor(Theme.multAlpha(textColor, 0.75f));
        line.setGravity(Gravity.CENTER_HORIZONTAL);
        line.setSingleLine(true);
        line.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        container.addView(line, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 22, 0, 22, 4));

        final Runnable update = () -> {
            Calendar local = Calendar.getInstance();
            local.add(Calendar.DAY_OF_YEAR, dayPicker.getValue());
            local.set(Calendar.HOUR_OF_DAY, hourPicker.getValue());
            local.set(Calendar.MINUTE, minutePicker.getValue());
            Calendar peer = Calendar.getInstance(tz);
            peer.setTimeInMillis(local.getTimeInMillis());

            SpannableStringBuilder ssb = new SpannableStringBuilder();
            ssb.append(LocaleController.formatString(R.string.ChatTimeZoneScheduleTime,
                    peerName, ChatTimeZoneRenderer.formatSide(peer)));
            int dayDiff = ChatTimeZoneRenderer.compareDay(peer, local);
            if (dayDiff != 0) {
                int start = ssb.length();
                ssb.append(dayDiff > 0 ? "  +1d" : "  −1d");
                ssb.setSpan(new ForegroundColorSpan(accentColor), start, ssb.length(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            line.setText(ssb);
        };
        update.run();
        return update;
    }
}
