package tw.nekomimi.nekogram.ui.components;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import org.telegram.messenger.LocaleController;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;

/**
 * The hairline that splits a popup row into two tap zones, e.g. a label that activates something
 * next to a trailing switch or clock button that does something else.
 * <p>Deliberately NOT {@code ActionBarPopupWindow.GapView}: that draws a full-width separator
 * <i>between</i> rows and its colour is upstream's business. This is a short vertical rule
 * <i>inside</i> one row, and it is drawn from the submenu text colour at low alpha rather than
 * {@code key_actionBarDefaultSubmenuSeparator}, which is only a few percent away from the popup
 * background and effectively invisible at 1dp.
 */
public final class PopupRowDivider {

    private PopupRowDivider() {}

    private static final int WIDTH_DP = 1;
    private static final int HEIGHT_DP = 22;
    private static final float ALPHA = 0.12f;

    private static View create(Context context, Theme.ResourcesProvider resourcesProvider) {
        View divider = new View(context);
        divider.setBackgroundColor(Theme.multAlpha(Theme.getColor(Theme.key_actionBarDefaultSubmenuItem, resourcesProvider), ALPHA));
        return divider;
    }

    /**
     * Pins the rule to the trailing edge of a frame-laid-out row (an {@code ActionBarMenuSubItem}),
     * {@code edgeMarginDp} away from it -- normally the width of the trailing zone it fences off.
     */
    public static void addTo(FrameLayout parent, Theme.ResourcesProvider resourcesProvider, float edgeMarginDp) {
        final boolean isRtl = LocaleController.isRTL;
        parent.addView(create(parent.getContext(), resourcesProvider), LayoutHelper.createFrame(WIDTH_DP, (float) HEIGHT_DP,
                Gravity.CENTER_VERTICAL | (isRtl ? Gravity.LEFT : Gravity.RIGHT),
                isRtl ? edgeMarginDp : 0f, 0f, isRtl ? 0f : edgeMarginDp, 0f));
    }

    /**
     * Appends the rule in reading order inside a horizontal row, with {@code startDp} before it and
     * {@code endDp} after it. Both margins are given in reading order and mirrored for RTL.
     */
    public static void addTo(LinearLayout parent, Theme.ResourcesProvider resourcesProvider, float startDp, float endDp) {
        final boolean isRtl = LocaleController.isRTL;
        parent.addView(create(parent.getContext(), resourcesProvider), LayoutHelper.createLinear(WIDTH_DP, HEIGHT_DP, Gravity.CENTER_VERTICAL,
                (int) (isRtl ? endDp : startDp), 0, (int) (isRtl ? startDp : endDp), 0));
    }
}
