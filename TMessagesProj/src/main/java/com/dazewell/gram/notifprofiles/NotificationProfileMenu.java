package com.dazewell.gram.notifprofiles;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBarMenu;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.BulletinFactory;

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

    // The header bell: one tap cycles Loud -> Quiet -> Passive and says what the new profile does, a long press opens
    // the picker. It starts hidden; updateBell() owns its visibility because a forum chat switches topics in place and
    // Telegram's own mute can flip while the chat is open.
    public static ActionBarMenuItem addBell(BaseFragment fragment, ActionBarMenu menu, int id, int account, long dialogId) {
        ActionBarMenuItem bell = menu.addItem(id, iconRes(NotificationProfiles.get(account, dialogId)), fragment.getResourceProvider());
        bell.setVisibility(android.view.View.GONE);
        bell.setOnClickListener(v -> cycle(fragment, bell, account, dialogId));
        bell.setOnLongClickListener(v -> {
            if (fragment.getParentActivity() != null) {
                fragment.showDialog(NotificationProfilePicker.create(fragment.getParentActivity(), account, dialogId, fragment.getResourceProvider(), () -> updateBell(bell, account, dialogId, false, true, false)));
            }
            return true;
        });
        return bell;
    }

    // Shown only where the Alerts menu item is (not on topics, which are keyed by the raw dialog id, and not while the user is
    // out of the chat) and not while Telegram's own mute is on, since a muted chat posts nothing for a profile to shape.
    public static void updateBell(ActionBarMenuItem bell, int account, long dialogId, boolean topic, boolean inChat, boolean muted) {
        if (bell == null) return;
        boolean visible = !topic && inChat && !muted;
        bell.setVisibility(visible ? android.view.View.VISIBLE : android.view.View.GONE);
        if (visible) {
            int profile = NotificationProfiles.get(account, dialogId);
            bell.setIcon(iconRes(profile));
            bell.setContentDescription(text(profile));
        }
    }

    private static void cycle(BaseFragment fragment, ActionBarMenuItem bell, int account, long dialogId) {
        int profile = (NotificationProfiles.get(account, dialogId) + 1) % 3;
        NotificationProfiles.set(account, dialogId, profile);
        NotificationProfiles.onChanged(account, dialogId);
        updateBell(bell, account, dialogId, false, true, false);
        if (BulletinFactory.canShowBulletin(fragment)) {
            int raw = profile == NotificationProfiles.PASSIVE ? R.raw.ic_mute : profile == NotificationProfiles.QUIET ? R.raw.sound_off : R.raw.ic_unmute;
            BulletinFactory.of(fragment).createSimpleBulletin(raw, text(profile), LocaleController.getString(NotificationProfiles.infoRes(profile))).show();
        }
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
