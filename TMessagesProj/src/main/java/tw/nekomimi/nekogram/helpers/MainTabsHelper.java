package tw.nekomimi.nekogram.helpers;

import org.telegram.ui.MainTabsActivity;

import xyz.nextalone.nagram.NaConfig;

public final class MainTabsHelper {
    public static final int MAIN_TABS_HEIGHT = 56;
    public static final int MAIN_TABS_MARGIN = 8;
    public static final int MAIN_TABS_MARGIN_COMPACT = 4;
    public static final int FILTER_TABS_HEIGHT = 36;
    public static final int TAB_WIDTH = 80;
    public static final int TAB_PADDING = 4;
    // MD3 navigation: a 64dp panel either way. With titles the 32dp indicator sits 8dp from the top with the label
    // 2dp under it, M3's short-bar layout; icon-only panels keep 16dp around it.
    public static final int MD3_NAVIGATION_HEIGHT = 64;
    public static final int MD3_NAVIGATION_HEIGHT_COMPACT = 64;
    public static final int MD3_NAVIGATION_INDICATOR_TOP = 8;
    public static final int MD3_NAVIGATION_INDICATOR_TOP_COMPACT = 16;
    // MD3 navigation floats as a stadium this far above the nav inset. It is the outer margin every
    // consumer already reads, so lists, the Dialogs FAB and bulletins clear the panel through it.
    public static final int MD3_NAVIGATION_LIFT = 12;
    public static final int MD3_NAVIGATION_SIDE_GAP = 9;
    // The Liquid Glass pill's own cap.
    public static final int MD3_NAVIGATION_MAX_WIDTH = 328 + MAIN_TABS_MARGIN * 2;
    public static final int MD3_NAVIGATION_INDICATOR_WIDTH = 56;
    public static final int MD3_NAVIGATION_INDICATOR_WIDTH_COMPACT = 64;
    // The long-press card sits this far inside the panel, so its radius is the panel's less this and the two nest.
    public static final int MD3_NAVIGATION_SCRIM_INSET = 4;

    private MainTabsHelper() {
    }

    // The first and last indicators sit as far from the panel's side as the indicator sits from its top.
    // Tabs share the width equally, so with few tabs the panel narrows until that edge gap still holds.
    private static float md3NavigationEdgeGap() {
        return isMainTabsHideTitleStyle() ? MD3_NAVIGATION_INDICATOR_TOP_COMPACT : MD3_NAVIGATION_INDICATOR_TOP;
    }

    public static int getMd3NavigationIndicatorWidth() {
        return isMainTabsHideTitleStyle() ? MD3_NAVIGATION_INDICATOR_WIDTH_COMPACT : MD3_NAVIGATION_INDICATOR_WIDTH;
    }

    public static float getMd3NavigationWidth() {
        final float tab = 2 * md3NavigationEdgeGap() + getMd3NavigationIndicatorWidth();
        return Math.min(MD3_NAVIGATION_MAX_WIDTH, tab * getFragmentsCount());
    }

    /** Side padding inside the panel that puts the edge indicators {@link #md3NavigationEdgeGap()} from its side. */
    public static float getMd3NavigationContentPadding() {
        final int count = getFragmentsCount();
        if (count < 2) {
            return 0;
        }
        final float gap = md3NavigationEdgeGap();
        final int indicator = getMd3NavigationIndicatorWidth();
        final float tab = (getMd3NavigationWidth() - 2 * gap - indicator) / (count - 1);
        return Math.max(0, gap + indicator / 2f - tab / 2f);
    }

    public static boolean isMainTabsHideTitleStyle() {
        return NaConfig.INSTANCE.getMainTabsHideTitles().Bool();
    }

    public static int getMainTabsHeight() {
        if (xyz.nextalone.nagram.helpers.InterfaceStyleController.applyBottomNavigation()) {
            return isMainTabsHideTitleStyle() ? MD3_NAVIGATION_HEIGHT_COMPACT : MD3_NAVIGATION_HEIGHT;
        }
        return isMainTabsHideTitleStyle() ? FILTER_TABS_HEIGHT : MAIN_TABS_HEIGHT;
    }

    public static int getMainTabsMargin() {
        if (xyz.nextalone.nagram.helpers.InterfaceStyleController.applyBottomNavigation()) {
            return MD3_NAVIGATION_LIFT;
        }
        return isMainTabsHideTitleStyle() ? MAIN_TABS_MARGIN_COMPACT : MAIN_TABS_MARGIN;
    }

    public static int getMainTabsHeightWithMargins() {
        return getMainTabsHeight() + getMainTabsMargin() * 2;
    }

    public static boolean isContactsTabHidden() {
        return NaConfig.INSTANCE.getMainTabsHideContacts().Bool();
    }

    public static int getChatsPosition() {
        return 0;
    }

    public static int getContactsPosition() {
        return 1;
    }

    public static int getCallsOrSettingsPosition() {
        return isContactsTabHidden() ? 1 : 2;
    }

    public static int getProfilePosition() {
        return isContactsTabHidden() ? 2 : 3;
    }

    public static int getFragmentsCount() {
        return isContactsTabHidden() ? 3 : MainTabsActivity.TABS_COUNT;
    }

    public static int getTabsViewWidth() {
        if (xyz.nextalone.nagram.helpers.InterfaceStyleController.applyBottomNavigation()) {
            return -1;
        }
        return TAB_WIDTH * getFragmentsCount() + (getMainTabsMargin() + TAB_PADDING) * 2;
    }
}
