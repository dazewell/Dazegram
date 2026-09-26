package xyz.nextalone.nagram.ui;

import androidx.core.graphics.ColorUtils;

import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.blur3.drawable.color.BlurredBackgroundColorProviderThemed;

import xyz.nextalone.nagram.helpers.InterfaceStyleController;

/**
 * NagramX: a themed glass button provider that goes flat when Interface Style's Buttons switch is on:
 * its own theme colour at full opacity, no stroke, no shadow. Otherwise it returns exactly what
 * BlurredBackgroundColorProviderThemed would.
 * It deliberately does not implement BlurredBackgroundProvider, so the drawable keeps its default stroke
 * widths and shadow layer and Liquid Glass renders unchanged. Only the four getters are overridden:
 * the super constructor runs updateColors()/isDark() before this class's fields are set.
 */
public class Md3ButtonColorProvider extends BlurredBackgroundColorProviderThemed {
    private final Theme.ResourcesProvider resourcesProvider;
    private final int backgroundColorId;

    public Md3ButtonColorProvider(Theme.ResourcesProvider resourcesProvider, int backgroundColorId) {
        super(resourcesProvider, backgroundColorId);
        this.resourcesProvider = resourcesProvider;
        this.backgroundColorId = backgroundColorId;
    }

    @Override
    public int getBackgroundColor() {
        if (InterfaceStyleController.applyButtons()) {
            return ColorUtils.setAlphaComponent(Theme.getColor(backgroundColorId, resourcesProvider), 255);
        }
        return super.getBackgroundColor();
    }

    @Override
    public int getShadowColor() {
        return InterfaceStyleController.applyButtons() ? 0 : super.getShadowColor();
    }

    @Override
    public int getStrokeColorTop() {
        return InterfaceStyleController.applyButtons() ? 0 : super.getStrokeColorTop();
    }

    @Override
    public int getStrokeColorBottom() {
        return InterfaceStyleController.applyButtons() ? 0 : super.getStrokeColorBottom();
    }

    /** The glyph tint flat MD3 buttons share, the one the chat's side buttons already draw with; stockColor otherwise. */
    public static int glyphColor(Theme.ResourcesProvider resourcesProvider, int stockColor) {
        return InterfaceStyleController.applyButtons() ? Theme.getColor(Theme.key_glass_defaultIcon, resourcesProvider) : stockColor;
    }

    /**
     * Gives a flat button the main Dialogs FAB's lift, since its own stroke and shadow are gone:
     * without them a white button on a white list has no edge. The outline follows the drawable's
     * padded shape rather than the view bounds, so the shadow sits under what is actually drawn.
     */
    public static void elevate(android.view.View view, org.telegram.ui.Components.blur3.drawable.BlurredBackgroundDrawable drawable) {
        if (InterfaceStyleController.applyButtons()) {
            view.setOutlineProvider(drawable.getViewOutlineProvider());
            view.setTranslationZ(org.telegram.messenger.AndroidUtilities.dpf2(0.5f));
        }
    }
}
