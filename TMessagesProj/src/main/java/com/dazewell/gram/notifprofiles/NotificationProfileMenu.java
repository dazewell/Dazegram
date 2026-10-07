package com.dazewell.gram.notifprofiles;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.BaseFragment;

// NagramX: the chat header menu entry that shows a chat's notification profile and opens the picker, so changing it
// no longer takes four taps through the Notifications screen.
public final class NotificationProfileMenu {

    private NotificationProfileMenu() {
    }

    public static ActionBarMenuItem.Item add(ActionBarMenuItem headerItem, int id, int account, long dialogId) {
        int profile = NotificationProfiles.get(account, dialogId);
        return headerItem.lazilyAddSubItem(id, iconRes(profile), text(profile));
    }

    // The profile can also change from the Notifications screen, so this runs again whenever the menu opens.
    public static void refresh(ActionBarMenuItem.Item item, int account, long dialogId, boolean visible) {
        if (item == null) return;
        item.setVisibility(visible ? android.view.View.VISIBLE : android.view.View.GONE);
        int profile = NotificationProfiles.get(account, dialogId);
        item.setText(text(profile));
        item.setIcon(iconRes(profile));
    }

    public static void open(BaseFragment fragment, int account, long dialogId, ActionBarMenuItem.Item item) {
        if (fragment.getParentActivity() == null) return;
        fragment.showDialog(NotificationProfilePicker.create(fragment.getParentActivity(), account, dialogId, fragment.getResourceProvider(), () -> refresh(item, account, dialogId, true)));
    }

    private static CharSequence text(int profile) {
        return LocaleController.formatString(R.string.NaxNotifProfileMenu, LocaleController.getString(NotificationProfiles.labelRes(profile)));
    }

    private static int iconRes(int profile) {
        switch (profile) {
            case NotificationProfiles.QUIET:
                return R.drawable.msg_silent;
            case NotificationProfiles.PASSIVE:
                return R.drawable.msg_mute;
            default:
                return R.drawable.msg_unmute;
        }
    }
}
