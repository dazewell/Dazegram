package com.radolyn.ayugram.privacyprofiles;

import static org.telegram.messenger.LocaleController.getString;

import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBarMenuSubItem;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.ItemOptions;
import org.telegram.ui.Components.LayoutHelper;

/**
 * The privacy-profile block of the Settings tab's long-press menu. Lives here rather than in
 * MainTabsActivity so the base file keeps a two-line hook, and so the block can be dropped into a
 * second menu later without copying it.
 */
public final class PrivacyProfileQuickSwitch {

    private PrivacyProfileQuickSwitch() {}

    /**
     * Appends the block to an already-built {@link ItemOptions}, including the gap that closes it.
     * Callers guard on there being a passcode and at least one profile.
     */
    public static void addTo(ItemOptions o, BaseFragment fragment) {
        // Flat block -- no submenu. ItemOptions.makeSwipeback() builds its submenu through a
        // constructor that never assigns lastLayout (ItemOptions:257), so putCheck() on a submenu
        // NPEs; addChecked() takes the state as an argument and never touches lastLayout, so the
        // whole class of bug is unreachable here by construction.
        // One getActiveProfile() snapshot for the entire block: it reconciles and can write config,
        // so calling it per row would both jank and risk two rows disagreeing.
        final PrivacyProfile active = PrivacyProfilesController.getActiveProfile();
        final Long activeDeadline = active != null ? PrivacyProfilesController.getActiveDeadline() : null;
        final CharSequence header;
        if (active == null) {
            header = getString(R.string.PrivacyProfileQuickSwitchTitle);
        } else if (activeDeadline != null) {
            header = LocaleController.formatString(R.string.PrivacyProfileIsOnUntil, active.name, LocaleController.formatDateTime(activeDeadline / 1000, true));
        } else {
            header = LocaleController.formatString(R.string.PrivacyProfileIsOn, active.name);
        }
        o.addText(header, 13);
        o.addChecked(active == null, getString(R.string.PrivacyProfileNone), () -> {
            if (active != null) {
                PrivacyProfilesController.deactivate();
            }
        });
        for (PrivacyProfile profile : PrivacyProfilesController.getProfiles()) {
            final boolean isActive = active != null && active.id == profile.id;
            // Two tap zones per row. The active one is marked with a ring around its icon rather
            // than a trailing checkmark, because the trailing edge now belongs to the duration
            // zone -- ActionBarMenuSubItem puts both at Gravity.RIGHT, so a checkmark and a right
            // icon would draw on top of each other.
            o.add(PrivacyProfileIcons.circleDrawable(fragment.getContext(), profile, 24, isActive), profile.name, () -> {
                if (isActive) {
                    PrivacyProfilesController.deactivate();
                } else {
                    PrivacyProfilesController.activate(profile.id, PrivacyProfilesController.ActivationMode.NOW, 0);
                }
            });
            final ActionBarMenuSubItem row = o.getLast();
            if (row != null) {
                if (isActive) {
                    // Only the active row needs this: an unchecked row already announces nothing
                    // extra, and a redundant description would suppress its text.
                    row.setContentDescription(LocaleController.formatString(R.string.PrivacyProfileIsOn, profile.name));
                }
                row.setRightIcon(R.drawable.msg_mute_period, v -> {
                    // The right icon's own listener bypasses ItemOptions' auto-dismissing click
                    // wrapper, so the popup has to be closed by hand.
                    o.dismiss();
                    PrivacyProfileDurationSheet.show(fragment, profile, null);
                });
                final ImageView clock = row.getRightIcon();
                if (clock != null) {
                    // Title form, not the menu-row label: the ellipsis belongs on a row that opens
                    // something, not in a spoken description.
                    clock.setContentDescription(getString(R.string.PrivacyProfileSetTimerTitle));
                    addTapZoneDivider(row);
                }
            }
        }
        o.addGap();
        o.add(R.drawable.msg_settings, getString(R.string.PrivacyProfilesManage), () ->
            fragment.presentFragment(new tw.nekomimi.nekogram.settings.NekoPasscodeSettingsActivity()));
        o.addGap();
    }

    /**
     * The hairline that tells you the clock is its own button, matching the switch rows in the
     * video-message camera popup. Sits just outside the dp(40) right-icon zone, which is the width
     * ActionBarMenuSubItem gives a right icon once it has a click listener.
     */
    private static void addTapZoneDivider(ActionBarMenuSubItem row) {
        final boolean isRtl = LocaleController.isRTL;
        View divider = new View(row.getContext());
        divider.setBackgroundColor(Theme.getColor(Theme.key_actionBarDefaultSubmenuSeparator));
        row.addView(divider, LayoutHelper.createFrame(1, 22f, Gravity.CENTER_VERTICAL | (isRtl ? Gravity.LEFT : Gravity.RIGHT),
            isRtl ? 40f : 0f, 0f, isRtl ? 0f : 40f, 0f));
        // setRightIcon() stops the label 32dp short of the edge, which is inside the divider now:
        // push it clear so a long profile name ellipsizes before the line instead of under it.
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) row.textView.getLayoutParams();
        if (isRtl) {
            lp.leftMargin = AndroidUtilities.dp(52);
        } else {
            lp.rightMargin = AndroidUtilities.dp(52);
        }
        row.textView.setLayoutParams(lp);
    }
}
