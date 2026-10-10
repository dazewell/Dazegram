package com.dazewell.gram.notifprofiles;

import android.app.Activity;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AlertsCreator;

import java.util.Calendar;
import java.util.Date;

// NagramX: "Apply for a time…" behind the profile picker. Three small steps built from existing dialogs: which profile,
// for how long (the Privacy Profiles presets, or "Until…" a time of day), and for Until a confirmation.
public final class NotificationTimerDialog {

    private static final int[] PRESET_MINUTES = {15, 30, 60, 90, 180, 480};

    private NotificationTimerDialog() {
    }

    public static void show(Activity activity, int account, long dialogId, Theme.ResourcesProvider rp, Runnable onApplied) {
        String[] names = new String[3];
        for (int i = 0; i < 3; i++) names[i] = LocaleController.getString(NotificationProfiles.labelRes(i));
        new AlertDialog.Builder(activity, rp)
                .setTitle(LocaleController.getString(R.string.NaxNotifTimer))
                .setItems(names, (d, profile) -> pickDuration(activity, account, dialogId, rp, profile, onApplied))
                .show();
    }

    private static void pickDuration(Activity activity, int account, long dialogId, Theme.ResourcesProvider rp, int profile, Runnable onApplied) {
        String[] items = new String[PRESET_MINUTES.length + 1];
        for (int i = 0; i < PRESET_MINUTES.length; i++) {
            int minutes = PRESET_MINUTES[i];
            items[i] = minutes % 60 == 0
                    ? LocaleController.formatPluralString("Hours", minutes / 60)
                    : LocaleController.formatPluralString("Minutes", minutes);
        }
        items[PRESET_MINUTES.length] = LocaleController.getString(R.string.NaxNotifTimerUntil);
        new AlertDialog.Builder(activity, rp)
                .setTitle(LocaleController.getString(NotificationProfiles.labelRes(profile)))
                .setItems(items, (d, which) -> {
                    if (which < PRESET_MINUTES.length) {
                        apply(account, dialogId, profile, System.currentTimeMillis() + PRESET_MINUTES[which] * 60_000L, onApplied);
                    } else {
                        pickTime(activity, account, dialogId, rp, profile, onApplied);
                    }
                }).show();
    }

    private static void pickTime(Activity activity, int account, long dialogId, Theme.ResourcesProvider rp, int profile, Runnable onApplied) {
        Calendar soon = Calendar.getInstance();
        soon.add(Calendar.HOUR_OF_DAY, 1);
        int initial = soon.get(Calendar.HOUR_OF_DAY) * 60 + soon.get(Calendar.MINUTE);
        // The sheet reports its value on any dismissal, a swipe away included, with no way to tell that from Select. A
        // timer silences a chat, so the pick is confirmed (and shows whether the time means today or tomorrow) before it counts.
        AlertsCreator.createTimePickerDialog(activity, LocaleController.getString(R.string.NaxNotifTimerUntil), initial, 0, 1439, minutes -> {
            long until = nextOccurrence(Math.max(0, Math.min(1439, minutes)));
            new AlertDialog.Builder(activity, rp)
                    .setTitle(LocaleController.getString(R.string.NaxNotifTimer))
                    .setMessage(LocaleController.formatString(R.string.NaxNotifTimerConfirm, LocaleController.getString(NotificationProfiles.labelRes(profile)), untilText(until)))
                    .setPositiveButton(LocaleController.getString(R.string.OK), (d, w) -> apply(account, dialogId, profile, until, onApplied))
                    .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                    .show();
        });
    }

    private static void apply(int account, long dialogId, int profile, long until, Runnable onApplied) {
        NotificationProfiles.setTimer(account, dialogId, profile, until);
        NotificationProfiles.onChanged(account, dialogId);
        if (onApplied != null) onApplied.run();
    }

    // The next time the clock reads this minute of the day: today if it is still ahead, else tomorrow.
    private static long nextOccurrence(int minuteOfDay) {
        long now = System.currentTimeMillis();
        Calendar c = Calendar.getInstance();
        c.set(Calendar.HOUR_OF_DAY, minuteOfDay / 60);
        c.set(Calendar.MINUTE, minuteOfDay % 60);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        if (c.getTimeInMillis() <= now) c.add(Calendar.DAY_OF_YEAR, 1);
        return c.getTimeInMillis();
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
