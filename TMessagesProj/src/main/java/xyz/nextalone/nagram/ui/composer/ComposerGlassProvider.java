package xyz.nextalone.nagram.ui.composer;

import static org.telegram.messenger.AndroidUtilities.dpf2;

import androidx.core.graphics.ColorUtils;

import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.blur3.drawable.color.BlurredBackgroundColorProviderThemed;
import org.telegram.ui.Components.blur3.drawable.color.BlurredBackgroundProvider;
import org.telegram.ui.Components.blur3.drawable.color.impl.BlurredBackgroundProviderImpl;

import xyz.nextalone.nagram.NaConfig;

/**
 * NagramX: color provider for the composer glass family (the input pill, the under-keyboard
 * panel, the toolbar row and everything else fed the one blurredBackgroundColorProvider instance
 * in ChatActivity, plus the settings-screen live preview in ComposerLayoutActivity). Named (not
 * anonymous) so it can implement BlurredBackgroundProvider on top of BlurredBackgroundColorProviderThemed -
 * only a concrete type can add methods beyond what its superclass declares, and BlurredBackgroundDrawable.
 * setColorProvider only picks up the shadow/stroke overrides below when the provider implements that
 * richer interface. Carries a stronger drop shadow than the base class default so the panels read as
 * floating above the chat (dazewell's ask), while pinning the stroke width so upgrading to the richer
 * interface doesn't also silently change stroke (setColorProvider applies both from the same block).
 */
public class ComposerGlassProvider extends BlurredBackgroundColorProviderThemed implements BlurredBackgroundProvider {
    private final int currentAccount;
    private final Theme.ResourcesProvider resourcesProvider;
    private final boolean gateOnBlurEnabled;

    /**
     * @param gateOnBlurEnabled whether getBackgroundColor() should fall back to an opaque panel
     *                          color when chat blur is disabled for this account/theme. The real
     *                          chat passes true; the settings-screen preview passes false, since it
     *                          always demonstrates the configured glass regardless of whether blur
     *                          happens to be off for the previewing account right now - it did not
     *                          gate on this before either, and gating it would be a behaviour change
     *                          nobody asked for in this pass.
     */
    public ComposerGlassProvider(int currentAccount, Theme.ResourcesProvider resourcesProvider, boolean gateOnBlurEnabled) {
        super(resourcesProvider, Theme.key_chat_messagePanelBackground);
        this.currentAccount = currentAccount;
        this.resourcesProvider = resourcesProvider;
        this.gateOnBlurEnabled = gateOnBlurEnabled;
    }

    // NagramX: theme-mode "dark", not perceived-brightness "dark" - shared by every isDark-branching
    // getter below so shadow/stroke can never disagree with getBackgroundColor()'s own theme read. The
    // inherited isDark() (BlurredBackgroundColorProviderThemed's, perceived brightness of the panel
    // background color) is deliberately not used here: under a custom theme the panel color's brightness
    // can disagree with the theme's actual day/night mode, which would let the "dark theme shadow stays
    // 0" requirement leak a shadow in an actual dark theme with a bright custom panel color.
    private boolean isDarkTheme() {
        return resourcesProvider != null ? resourcesProvider.isDark() : Theme.isCurrentThemeDark();
    }

    @Override
    public int getBackgroundColor() {
        if (gateOnBlurEnabled && !BlurredBackgroundProviderImpl.checkBlurEnabled(currentAccount, resourcesProvider)) {
            return ColorUtils.setAlphaComponent(Theme.getColor(Theme.key_chat_messagePanelBackground, resourcesProvider), 255);
        }

        // NagramX: dropped upstream's light-theme alpha 216 override — light and dark theme now each read their own configured pass-through (see NaConfig.composerGlassAlpha), read live rather than cached so an auto night mode flip picks up the right one without reopening the chat
        return Theme.multAlpha(Theme.getColor(Theme.key_chat_messagePanelBackground, resourcesProvider), NaConfig.composerGlassAlpha(isDarkTheme()));
    }

    // NagramX: light-theme shadow alpha bumped from the base class's 0x20000000 for the stronger 3D
    // read; dark theme stays disabled (0) - a deliberate choice, not an oversight, left for a separate
    // change once light theme has been judged on device.
    @Override
    public int getShadowColor() {
        return isDarkTheme() ? 0 : 0x30000000;
    }

    // NagramX: computed live from isDarkTheme() rather than inherited from the base class's cached
    // fields - getBackgroundColor() above already reads live so an auto night-mode flip picks up the
    // right theme without reopening the chat, and the stroke colors need the same treatment or a flip
    // could leave the background/shadow on the new theme while the stroke highlight is still painted
    // for the old one. Values match BlurredBackgroundColorProviderThemed.updateColors()'s own constants.
    @Override
    public int getStrokeColorTop() {
        return isDarkTheme() ? 0x28FFFFFF : 0xFFFFFFFF;
    }

    @Override
    public int getStrokeColorBottom() {
        return isDarkTheme() ? 0x14FFFFFF : 0xFFFFFFFF;
    }

    // NagramX: pinned to BlurredBackgroundDrawable's own upstream constructor defaults so implementing
    // BlurredBackgroundProvider (needed for the shadow below) doesn't also silently zero out the stroke -
    // a bare Java float field defaults to 0, which would delete the edge highlight on every surface this
    // provider feeds.
    @Override
    public float getStrokeWidthTop() {
        return dpf2(1);
    }

    @Override
    public float getStrokeWidthBottom() {
        return dpf2(2 / 3f);
    }

    // NagramX: stronger 3D shadow per dazewell's ask, architect-reviewed for the 75%-scale composer:
    // Paint.setShadowLayer's visible reach is ~2x radius (NinePatchBuilder's own blurPad budget), so
    // dpf2(3) stays inside the smallest real clearance at that scale (the row's own reserved band, dp(6)).
    @Override
    public float getShadowRadius() {
        return dpf2(3);
    }

    @Override
    public float getShadowDx() {
        return 0;
    }

    @Override
    public float getShadowDy() {
        return dpf2(2 / 3f);
    }
}
