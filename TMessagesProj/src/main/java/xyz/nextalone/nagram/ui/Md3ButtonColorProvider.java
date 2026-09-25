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
}
