package xyz.nextalone.nagram.helpers;

import org.telegram.messenger.LiteMode;

import xyz.nextalone.nagram.NaConfig;

public class InterfaceStyleController {

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

    public static boolean applyBottomNavigation() {
        return isMaterialDesign3() && NaConfig.INSTANCE.getInterfaceStyleApplyBottomNavigation().Bool();
    }
}
