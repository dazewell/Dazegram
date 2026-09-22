package xyz.nextalone.nagram.helpers;

import org.telegram.messenger.LiteMode;

import xyz.nextalone.nagram.NaConfig;

public class InterfaceStyleController {
    private static final int FILTER_TAB_SELECTOR_ALPHA_LEGACY = 31;
    private static final int FILTER_TAB_SELECTOR_ALPHA_MD3 = 46;
    private static final int FILTER_TAB_SELECTOR_ALPHA_STROKE = 255;

    public static boolean isMaterialDesign3() {
        return !(LiteMode.isLiquidGlassSupported() && LiteMode.isEnabledSetting(LiteMode.FLAG_LIQUID_GLASS));
    }

    public static boolean applyChatHeader() {
        return isMaterialDesign3() && NaConfig.INSTANCE.getInterfaceStyleApplyChatHeader().Bool();
    }

    public static boolean applyChatListTopBar() {
        return isMaterialDesign3() && NaConfig.INSTANCE.getInterfaceStyleApplyChatListTopBar().Bool();
    }

    public static boolean applyButtons() {
        return isMaterialDesign3() && NaConfig.INSTANCE.getInterfaceStyleApplyButtons().Bool();
    }

    // The flat MD3 Composer is being rebuilt as its own layout; until it lands the stored switch is ignored.
    public static final boolean COMPOSER_STYLE_AVAILABLE = false;

    public static boolean applyComposer() {
        return COMPOSER_STYLE_AVAILABLE && isMaterialDesign3() && NaConfig.INSTANCE.getInterfaceStyleApplyComposer().Bool();
    }

    public static boolean applyBottomNavigation() {
        return isMaterialDesign3() && NaConfig.INSTANCE.getInterfaceStyleApplyBottomNavigation().Bool();
    }

    public static boolean panelDividers() {
        return isMaterialDesign3() && NaConfig.INSTANCE.getInterfaceStylePanelDividers().Bool();
    }

    // Panel colors has no render consumer yet, so its section stays hidden.
    public static final boolean MATCH_CLASSIC_DAY_HEADER_AVAILABLE = false;

    public static int filterTabSelectorAlpha(boolean strokeStyle) {
        if (strokeStyle) {
            return FILTER_TAB_SELECTOR_ALPHA_STROKE;
        }
        return applyChatListTopBar() ? FILTER_TAB_SELECTOR_ALPHA_MD3 : FILTER_TAB_SELECTOR_ALPHA_LEGACY;
    }
}
