package com.dazewell.gram.notifprofiles;

import android.app.Activity;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AlertsCreator;

import java.util.Calendar;
import java.util.Date;

// NagramX: the clock button beside a profile in the picker opens this: for how long (the Privacy Profiles presets) or
// Until… a time within the next 24 hours.
public final class NotificationTimerDialog {

    private static final int[] PRESET_MINUTES = {15, 30, 60, 90, 180, 480};

    private NotificationTimerDialog() {
    }

    public static void show(Activity activity, int account, long dialogId, Theme.ResourcesProvider rp, int profile, Runnable onApplied) {
        String[] items = new String[PRESET_MINUTES.length + 1];
        for (int i = 0; i < PRESET_MINUTES.length; i++) {
            int minutes = PRESET_MINUTES[i];
            items[i] = minutes % 60 == 0
                    ? LocaleController.formatPluralString("Hours", minutes / 60)
                    : LocaleController.formatPluralString("Minutes", minutes);
        }
        items[PRESET_MINUTES.length] = LocaleController.getString(R.string.NaxNotifTimerUntil);
        new AlertDialog.Builder(activity, rp)
                .setTitle(LocaleController.formatString(R.string.NaxNotifTimerFor, LocaleController.getString(NotificationProfiles.labelRes(profile))))
                .setItems(items, (d, which) -> {
                    if (which < PRESET_MINUTES.length) {
                        apply(account, dialogId, profile, System.currentTimeMillis() + PRESET_MINUTES[which] * 60_000L, onApplied);
                    } else {
                        pickTime(activity, account, dialogId, profile, onApplied);
                    }
                }).show();
    }

    // The wheel counts minutes from today's midnight and runs from the next minute to 24 hours ahead, so its hours past 23
    // read "Next day" and there is no guessing whether 4 PM means today or tomorrow. The sheet reports its value on any
    // dismissal, a swipe away included, so a swipe applies the time it shows; the Alerts row then says "until …" and the
    // picker offers Cancel timer.
    private static void pickTime(Activity activity, int account, long dialogId, int profile, Runnable onApplied) {
        Calendar now = Calendar.getInstance();
        int nowMinute = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE);
        int initial = nowMinute + 60;
        // the sheet reads a start in hour 24 as 23:59, so start an hour later than that
        if (initial / 60 == 24) initial += 60;
        AlertsCreator.createTimePickerDialog(activity, LocaleController.getString(R.string.NaxNotifTimerUntil), initial, nowMinute + 1, nowMinute + 24 * 60, minutes -> {
            Calendar until = Calendar.getInstance();
            until.set(Calendar.SECOND, 0);
            until.set(Calendar.MILLISECOND, 0);
            // lenient calendar: an hour past 23 rolls into tomorrow in wall-clock terms, so DST days stay right
            until.set(Calendar.HOUR_OF_DAY, minutes / 60);
            until.set(Calendar.MINUTE, minutes % 60);
            apply(account, dialogId, profile, until.getTimeInMillis(), onApplied);
        });
    }

    private static void apply(int account, long dialogId, int profile, long until, Runnable onApplied) {
        // a time already past by the time it is picked stores nothing, so don't report a change
        if (!NotificationProfiles.setTimer(account, dialogId, profile, until)) return;
        NotificationProfiles.onChanged(account, dialogId);
        if (onApplied != null) onApplied.run();
    }

    // Just the time while it is still today, else the date and time.
    static String untilText(long until) {
        Calendar now = Calendar.getInstance();
        Calendar end = Calendar.getInstance();
        end.setTimeInMillis(until);
        if (now.get(Calendar.YEAR) == end.get(Calendar.YEAR) && now.get(Calendar.DAY_OF_YEAR) == end.get(Calendar.DAY_OF_YEAR)) {
            return LocaleController.getInstance().getFormatterDay().format(new Date(until));
        }
        return LocaleController.formatDateTime(until / 1000, false);
    }
}
