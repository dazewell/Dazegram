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
    // MD3 navigation bar: 80dp container, 12dp above the 32dp indicator; icon-only bars keep 16dp around it.
    public static final int MD3_NAVIGATION_HEIGHT = 80;
    public static final int MD3_NAVIGATION_HEIGHT_COMPACT = 64;
    public static final int MD3_NAVIGATION_INDICATOR_TOP = 12;
    public static final int MD3_NAVIGATION_INDICATOR_TOP_COMPACT = 16;

    private MainTabsHelper() {
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
            return 0;
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
