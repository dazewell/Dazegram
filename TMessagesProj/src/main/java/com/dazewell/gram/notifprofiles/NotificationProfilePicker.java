package com.dazewell.gram.notifprofiles;

import android.app.Activity;
import android.app.Dialog;
import android.widget.LinearLayout;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.RadioColorCell;
import org.telegram.ui.Cells.TextSettingsCell;

// NagramX: the single-choice sheet behind the per-chat "Notification profile" row. Each option carries its one-line
// description, which is why this isn't AlertsCreator.createSingleChoiceDialog (names only).
public final class NotificationProfilePicker {

    private NotificationProfilePicker() {
    }

    public static Dialog create(Activity activity, int account, long dialogId, Theme.ResourcesProvider resourcesProvider, Runnable onChanged) {
        int selected = NotificationProfiles.effective(account, dialogId);
        LinearLayout layout = new LinearLayout(activity);
        layout.setOrientation(LinearLayout.VERTICAL);
        AlertDialog.Builder builder = new AlertDialog.Builder(activity, resourcesProvider);
        int[] profiles = {NotificationProfiles.LOUD, NotificationProfiles.QUIET, NotificationProfiles.PASSIVE};
        for (int profile : profiles) {
            RadioColorCell cell = radioCell(activity, resourcesProvider, profile, selected == profile);
            layout.addView(cell);
            cell.setOnClickListener(v -> {
                builder.getDismissRunnable().run();
                NotificationProfiles.set(account, dialogId, profile);
                NotificationProfiles.onChanged(account, dialogId);
                if (onChanged != null) {
                    onChanged.run();
                }
            });
        }
        TextSettingsCell schedule = new TextSettingsCell(activity, resourcesProvider);
        schedule.setBackground(Theme.getSelectorDrawable(false));
        int count = NotificationProfiles.rules(account, dialogId).size();
        schedule.setTextAndValue(LocaleController.getString(R.string.NaxNotifSchedule), count > 0 ? String.valueOf(count) : "", false);
        schedule.setOnClickListener(v -> {
            builder.getDismissRunnable().run();
            NotificationScheduleDialog.show(activity, account, dialogId, resourcesProvider, onChanged);
        });
        layout.addView(schedule);
        TextSettingsCell timer = new TextSettingsCell(activity, resourcesProvider);
        timer.setBackground(Theme.getSelectorDrawable(false));
        timer.setText(LocaleController.getString(R.string.NaxNotifTimer), false);
        timer.setOnClickListener(v -> {
            builder.getDismissRunnable().run();
            NotificationTimerDialog.show(activity, account, dialogId, resourcesProvider, onChanged);
        });
        layout.addView(timer);
        long timerUntil = NotificationProfiles.timerUntil(account, dialogId);
        if (timerUntil > 0) {
            // what is running, and a way to end it without picking another profile
            TextSettingsCell cancel = new TextSettingsCell(activity, resourcesProvider);
            cancel.setBackground(Theme.getSelectorDrawable(false));
            cancel.setTextAndValue(LocaleController.formatString(R.string.NaxNotifTimerActive, LocaleController.getString(NotificationProfiles.labelRes(selected)), NotificationTimerDialog.untilText(timerUntil)), LocaleController.getString(R.string.NaxNotifTimerCancel), false);
            cancel.setOnClickListener(v -> {
                builder.getDismissRunnable().run();
                NotificationProfiles.clearTimer(account, dialogId);
                NotificationProfiles.onChanged(account, dialogId);
                if (onChanged != null) {
                    onChanged.run();
                }
            });
            layout.addView(cancel);
        }
        builder.setTitle(LocaleController.getString(R.string.NaxNotifProfile));
        builder.setView(layout);
        builder.setPositiveButton(LocaleController.getString(R.string.Cancel), null);
        return builder.create();
    }

    // One profile option with its description, shared with the schedule rule editor.
    static RadioColorCell radioCell(Activity activity, Theme.ResourcesProvider resourcesProvider, int profile, boolean checked) {
        RadioColorCell cell = new RadioColorCell(activity, resourcesProvider);
        cell.setPadding(AndroidUtilities.dp(4), 0, AndroidUtilities.dp(4), 0);
        cell.setCheckColor(Theme.getColor(Theme.key_radioBackground, resourcesProvider), Theme.getColor(Theme.key_dialogRadioBackgroundChecked, resourcesProvider));
        cell.setTextAndText2AndValue(LocaleController.getString(NotificationProfiles.labelRes(profile)), LocaleController.getString(NotificationProfiles.infoRes(profile)), checked);
        return cell;
    }
}
