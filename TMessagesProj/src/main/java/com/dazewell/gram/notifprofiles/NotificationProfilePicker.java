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

// NagramX: the single-choice sheet behind the per-chat "Notification profile" row. Each option carries its one-line
// description, which is why this isn't AlertsCreator.createSingleChoiceDialog (names only).
public final class NotificationProfilePicker {

    private NotificationProfilePicker() {
    }

    public static Dialog create(Activity activity, int account, long dialogId, Theme.ResourcesProvider resourcesProvider, Runnable onChanged) {
        int selected = NotificationProfiles.get(account, dialogId);
        LinearLayout layout = new LinearLayout(activity);
        layout.setOrientation(LinearLayout.VERTICAL);
        AlertDialog.Builder builder = new AlertDialog.Builder(activity, resourcesProvider);
        int[] profiles = {NotificationProfiles.LOUD, NotificationProfiles.QUIET, NotificationProfiles.PASSIVE};
        for (int profile : profiles) {
            RadioColorCell cell = new RadioColorCell(activity, resourcesProvider);
            cell.setPadding(AndroidUtilities.dp(4), 0, AndroidUtilities.dp(4), 0);
            cell.setCheckColor(Theme.getColor(Theme.key_radioBackground, resourcesProvider), Theme.getColor(Theme.key_dialogRadioBackgroundChecked, resourcesProvider));
            cell.setTextAndText2AndValue(LocaleController.getString(NotificationProfiles.labelRes(profile)), LocaleController.getString(NotificationProfiles.infoRes(profile)), selected == profile);
            layout.addView(cell);
            cell.setOnClickListener(v -> {
                builder.getDismissRunnable().run();
                NotificationProfiles.set(account, dialogId, profile);
                if (onChanged != null) {
                    onChanged.run();
                }
            });
        }
        builder.setTitle(LocaleController.getString(R.string.NaxNotifProfile));
        builder.setView(layout);
        builder.setPositiveButton(LocaleController.getString(R.string.Cancel), null);
        return builder.create();
    }
}
