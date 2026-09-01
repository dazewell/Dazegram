package com.radolyn.ayugram.eventschedule;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.ItemOptions;

/**
 * The Armed Sends menu row shared by both places the Chats nav button's long-press menu is built
 * (MainTabsActivity when the bottom nav is visible, DialogsActivity's own overflow menu when it is
 * hidden). Lives here rather than duplicated in each base file, same reasoning as
 * PrivacyProfileQuickSwitch: one two-line hook per call site instead of copying this block twice.
 */
public final class ArmedSendsMenu {

    private ArmedSendsMenu() {}

    /**
     * Appends the row, or nothing at all. hasAny() alone is not a valid gate before the account's
     * store has loaded at least once (it starts false), so ensureWarm() runs first -- usually a
     * no-op, since the new-message funnel already calls it for any account with traffic.
     */
    public static void addTo(ItemOptions o, BaseFragment fragment) {
        int account = fragment.getCurrentAccount();
        EventScheduleController.ensureWarm(account);
        if (!EventScheduleStore.hasAny(account)) {
            return;
        }
        o.add(R.drawable.msg_calendar2, LocaleController.getString(R.string.ArmedSendsTitle), () ->
                fragment.presentFragment(new ArmedSendsActivity()));
    }
}
