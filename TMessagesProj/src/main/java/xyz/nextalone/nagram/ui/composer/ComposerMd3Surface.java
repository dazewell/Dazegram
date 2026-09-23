package xyz.nextalone.nagram.ui.composer;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.view.RoundedCorner;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.graphics.ColorUtils;

import org.telegram.messenger.LocaleController;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.ChatActivityEnterView;
import org.telegram.ui.Components.blur3.BlurredBackgroundDrawableViewFactory;
import org.telegram.ui.Components.blur3.drawable.BlurredBackgroundDrawable;
import org.telegram.ui.Components.blur3.drawable.color.BlurredBackgroundProvider;
import org.telegram.ui.Components.blur3.drawable.color.BlurredBackgroundProviderBuilder;
import org.telegram.ui.Components.blur3.drawable.color.impl.BlurredBackgroundProviderImpl;
import org.telegram.ui.Components.blur3.source.BlurredBackgroundSource;
import org.telegram.ui.Components.chat.ViewPositionWatcher;

import me.vkryl.core.reference.ReferenceList;
import xyz.nextalone.nagram.NaConfig;
import xyz.nextalone.nagram.helpers.InterfaceStyleController;

/**
 * NagramX (#interface-style): the MD3 Composer. ChatActivity's ChatInputViewsContainer hands its draw pass
 * here instead of painting the glass island and under-keyboard drawables. The composer becomes one floating
 * island, frosted and lightly shadowed: a tonal field with the send column beside it and the tools row
 * under it, or a tonal island around whichever action run replaces the input. The under-keyboard panel
 * stays docked and opaque.
 * Colours are read from the chat's theme on every draw; nothing here is cached between frames.
 */
public final class ComposerMd3Surface {
    private static final float CONTAINER_ON_SURFACE_BLEND = 0.06f;
    // Painted opaque, so the field reads one step above the 6% strip whatever the frost behind it.
    private static final float FIELD_ON_SURFACE_BLEND = 0.08f;
    private static final int STRIP_RADIUS = 10;
    private static final int STRIP_ACCENT = 3;
    // Inset top and bottom inside the 48dp top view, which centres the strip on the upstream close button.
    private static final int STRIP_INSET = 3;
    // Island radius minus the 3dp margin, so the field and the reply strip sit concentric in the island.
    private static final int FIELD_RADIUS = 10;
    // The field is drawn this far inside the 44dp text row top and bottom, so it is 42dp and shares the
    // row's centre with the send circle.
    private static final int FIELD_INSET = 1;
    // The island reaches this far past the text row and the tools row, and the field sits the same 3dp in
    // from its leading edge, so the field has an even margin on three sides.
    private static final int ISLAND_PADDING = 2;
    private static final int FIELD_SIDE_INSET = 3;
    private static final int FIELD_SEND_GAP = 8;
    // The send circle's top sits about 5dp inside the island, which caps the radius near 13dp.
    private static final int ISLAND_RADIUS = 13;
    // Resting on the nav bar the lower corners grow toward the display curve, but past this the tools row's
    // first and last ripples would clip.
    private static final int BOTTOM_RADIUS_MAX = 15;
    // The island's send side reaches this far past the pill, so the send circle clears its edge.
    private static final int SEND_OVERHANG = 4;
    private static final int SHADOW_RADIUS = 4;
    private static final int SHADOW_DY = 2;
    private static final int SHADOW_ALPHA = 77;
    // The pill's padded bounds already sit this far in from the container, so a selection island uses it too.
    private static final int SIDE_INSET = 7;
    // The stock 9dp lift less the island's 2dp bottom padding: the gap the island rests at over the nav bar.
    private static final int ISLAND_LIFT = 7;

    private final Theme.ResourcesProvider resourcesProvider;
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dividerPaint = new Paint();
    private final RectF rect = new RectF();
    private final RectF island = new RectF();
    private final Rect islandBounds = new Rect();
    private final float[] islandRadii = new float[8];
    private final Path islandPath = new Path();
    private float frostBottomRadius = -1;

    private ChatActivityEnterView enterView;
    private View channelButtons;
    private View actionButtons;
    private boolean barVisible;
    private View host;
    private BlurredBackgroundDrawable frost;
    private int frostAccount;

    public ComposerMd3Surface(Theme.ResourcesProvider resourcesProvider) {
        this.resourcesProvider = resourcesProvider;
    }

    public static ComposerMd3Surface createIfEnabled(Theme.ResourcesProvider resourcesProvider) {
        return InterfaceStyleController.applyComposer() ? new ComposerMd3Surface(resourcesProvider) : null;
    }

    // The island shows one of these at a time; their alpha is how ChatActivity cross-fades between them,
    // so it is also how this surface decides which run the island wraps. The host supplies the window insets.
    public void bind(View host, ChatActivityEnterView enterView, View channelButtons, View actionButtons) {
        this.host = host;
        this.enterView = enterView;
        this.channelButtons = channelButtons;
        this.actionButtons = actionButtons;
    }

    /**
     * Frosts the bar the way the MD3 chat header is frosted. It gets its own factory over the chat's frosted
     * source because the shared one hands out drawables with the Liquid Glass shader when that is enabled.
     * Without a source (below API 31, or chat blur off when the chat opened) the bar stays opaque.
     */
    public void attachFrost(@Nullable BlurredBackgroundSource source, ViewPositionWatcher watcher, ViewGroup root,
                            ReferenceList<View> linkedViews, ReferenceList<BlurredBackgroundDrawable> linkedDrawables,
                            View view, int account) {
        if (source == null) {
            return;
        }
        final BlurredBackgroundDrawableViewFactory factory = new BlurredBackgroundDrawableViewFactory(watcher, root, source);
        factory.setLinkedViewsRef(linkedViews);
        factory.setLinkedDrawablesRef(linkedDrawables);
        frost = factory.create(view, frostProvider());
        frost.setRadius(dp(ISLAND_RADIUS));
        frostAccount = account;
    }

    // No stroke, and a soft shadow in the theme's own panel-shadow colour, so the island reads as lifted.
    private BlurredBackgroundProvider frostProvider() {
        return new BlurredBackgroundProviderBuilder(resourcesProvider)
                .setBackgroundColor((r, isDark) -> Theme.multAlpha(surfaceColor(), NaConfig.interfaceStyleBlurAlpha()))
                .setStrokeColorTop(0, 0)
                .setStrokeColorBottom(0, 0)
                .setShadowColor((r, isDark) -> shadowColor())
                .setShadowLayer(dp(SHADOW_RADIUS), 0, dp(SHADOW_DY))
                .build();
    }

    private boolean frosted() {
        return frost != null && BlurredBackgroundProviderImpl.checkBlurEnabled(frostAccount, resourcesProvider);
    }

    public int surfaceColor() {
        return ColorUtils.setAlphaComponent(Theme.getColor(Theme.key_chat_messagePanelBackground, resourcesProvider), 255);
    }

    /** The 6% on-surface tint laid over the island, so it tones a frosted island and an opaque one alike. */
    private int containerOverlay() {
        final int onSurface = Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider);
        return ColorUtils.setAlphaComponent(onSurface, Math.round(255 * CONTAINER_ON_SURFACE_BLEND));
    }

    /** An opaque tonal container one step above the surface: darker in a light theme, lighter in a dark one. */
    private int fieldColor() {
        final int onSurface = ColorUtils.setAlphaComponent(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider), 255);
        return ColorUtils.blendARGB(surfaceColor(), onSurface, FIELD_ON_SURFACE_BLEND);
    }

    private int shadowColor() {
        return ColorUtils.setAlphaComponent(Theme.getColor(Theme.key_chat_messagePanelShadow, resourcesProvider), SHADOW_ALPHA);
    }

    public int primaryColor() {
        return Theme.getColor(Theme.key_chat_messagePanelSend, resourcesProvider);
    }

    private int outlineVariantColor(int surface) {
        return InterfaceStyleController.panelDividerPaint(surface, resourcesProvider).getColor();
    }

    public boolean contains(float x, float y) {
        // Same rule as the island glass: only a fully shown island takes touches.
        return barVisible && island.contains(x, y);
    }

    /**
     * Resting on the nav bar, the island's lower corners nest in the display's own rounded corners instead of
     * cutting across the screen curve. As the keyboard or emoji panel lifts it away they ease back to the top
     * radius, over the same distance as the curve is tall.
     */
    private float bottomRadius(int height) {
        final float base = dp(ISLAND_RADIUS);
        if (Build.VERSION.SDK_INT < 31 || host == null) {
            return base;
        }
        final WindowInsets insets = host.getRootWindowInsets();
        if (insets == null) {
            return base;
        }
        final RoundedCorner left = insets.getRoundedCorner(RoundedCorner.POSITION_BOTTOM_LEFT);
        final RoundedCorner right = insets.getRoundedCorner(RoundedCorner.POSITION_BOTTOM_RIGHT);
        final int display = Math.max(left == null ? 0 : left.getRadius(), right == null ? 0 : right.getRadius());
        final float nested = Math.min(display - island.left, dp(BOTTOM_RADIUS_MAX));
        if (display <= 0 || nested <= base) {
            return base;
        }
        final float restingGap = insets.getInsets(WindowInsets.Type.navigationBars()).bottom + dp(ISLAND_LIFT);
        final float lifted = Math.max(0, height - island.bottom - restingGap);
        final float t = Math.max(0, 1f - lifted / display);
        return base + (nested - base) * t;
    }

    /** How far the island reaches above the pill. */
    public int topOverhang() {
        return dp(ISLAND_PADDING);
    }

    /**
     * @param pill            the island's visible pill in container coordinates, including its slide offset
     * @param pillTranslation the vertical slide the pill carries while selection mode takes the island over
     * @param pillAlpha       the island's own fade, 0-255
     * @param toolsInset      the part of the pill the tools row occupies, already subtracted from {@code pill}
     * @param underKeyboard   bounds of the in-app keyboard panel, or null while it is not showing
     */
    public void draw(Canvas canvas, int width, int height, Rect pill, float pillTranslation, int pillAlpha,
                     float toolsInset, boolean drawPill, Rect underKeyboard) {
        if (pill.isEmpty()) {
            island.setEmpty();
            barVisible = false;
            return;
        }
        final float alpha = pillAlpha / 255f;
        final float inputFactor = visibility(enterView);
        final float channelFactor = visibility(channelButtons);
        final float actionFactor = visibility(actionButtons);
        // ChatActivity fades the island out both for the selection bar, which the island keeps hosting, and
        // for modes like adding a poll option, which hide the composer outright. Dividing the selection share
        // out of the island's alpha leaves only the second kind, so the cross-fade into selection never dims it.
        final float barFactor = actionFactor >= 1f ? 1f : Math.max(actionFactor, Math.min(1f, alpha / (1f - actionFactor)));
        barVisible = barFactor >= 1f;
        final int surface = surfaceColor();
        final int container = containerOverlay();
        final float pad = dp(ISLAND_PADDING);

        final float topViewProgress = enterView != null ? enterView.getTopViewEnterProgress() : 0;
        final float topViewHeight = enterView != null ? Math.max(0, enterView.getTopViewHeight()) * topViewProgress : 0;

        // One island, shaped by whichever run is showing and eased between them as ChatActivity cross-fades.
        // The input wraps the text row and the tools row under it; the channel run is the pill that
        // setInputBubbleOffsets already shrank to the buttons; the selection bar keeps the pill's height.
        // Search and the bottom overlay text have no view bound here, so whatever share the three runs leave
        // goes to the plain pill, which is what the island is sized to while they show.
        float weight = 0, left = 0, top = 0, right = 0, bottom = 0;
        final float plainFactor = Math.max(0, 1f - inputFactor - channelFactor - actionFactor);
        if (plainFactor > 0) {
            weight += plainFactor;
            left += pill.left * plainFactor;
            top += (pill.top - pad) * plainFactor;
            right += pill.right * plainFactor;
            bottom += (pill.bottom + pad) * plainFactor;
        }
        if (inputFactor > 0) {
            weight += inputFactor;
            left += (pill.left - (LocaleController.isRTL ? dp(SEND_OVERHANG) : 0)) * inputFactor;
            top += (pill.top - pad) * inputFactor;
            right += (pill.right + (LocaleController.isRTL ? 0 : dp(SEND_OVERHANG))) * inputFactor;
            bottom += (pill.bottom + toolsInset + pad) * inputFactor;
        }
        if (channelFactor > 0) {
            weight += channelFactor;
            left += pill.left * channelFactor;
            top += (pill.top - pad) * channelFactor;
            right += pill.right * channelFactor;
            bottom += (pill.bottom + pad) * channelFactor;
        }
        if (actionFactor > 0 && actionButtons != null) {
            weight += actionFactor;
            left += (actionButtons.getLeft() + dp(SIDE_INSET)) * actionFactor;
            top += (pill.top - pillTranslation - pad) * actionFactor;
            right += (actionButtons.getRight() - dp(SIDE_INSET)) * actionFactor;
            bottom += (pill.bottom - pillTranslation + pad) * actionFactor;
        }
        island.set(left / weight, top / weight, right / weight, bottom / weight);
        final float radius = Math.min(dp(ISLAND_RADIUS), island.height() / 2f);
        final float bottomRadius = Math.min(bottomRadius(height), island.height() / 2f);
        islandRadii[0] = islandRadii[1] = islandRadii[2] = islandRadii[3] = radius;
        islandRadii[4] = islandRadii[5] = islandRadii[6] = islandRadii[7] = bottomRadius;
        islandPath.rewind();
        islandPath.addRoundRect(island, islandRadii, Path.Direction.CW);

        final int frostAlpha = Math.round(255 * barFactor);
        if (frost != null && frost.getAlpha() != frostAlpha) {
            // Only on a change: every setAlpha re-records the blur's display list.
            frost.setAlpha(frostAlpha);
        }
        final boolean frosted = frosted();
        if (barFactor > 0) {
            if (frosted) {
                if (frostBottomRadius != bottomRadius) {
                    frostBottomRadius = bottomRadius;
                    frost.setRadius(dp(ISLAND_RADIUS), dp(ISLAND_RADIUS), bottomRadius, bottomRadius);
                }
                island.roundOut(islandBounds);
                frost.setBounds(islandBounds);
                frost.draw(canvas);
            } else {
                fillPaint.setColor(Theme.multAlpha(surface, barFactor));
                fillPaint.setShadowLayer(dp(SHADOW_RADIUS), 0, dp(SHADOW_DY), Theme.multAlpha(shadowColor(), barFactor));
                canvas.drawPath(islandPath, fillPaint);
                fillPaint.clearShadowLayer();
            }
        }

        // The channel and selection runs have no field of their own, so the island itself takes the tone.
        final float runFactor = Math.max(actionFactor, drawPill ? alpha * channelFactor : 0);
        if (runFactor > 0) {
            fillPaint.setColor(Theme.multAlpha(fieldColor(), runFactor));
            canvas.drawPath(islandPath, fillPaint);
        }

        // The field and the reply strip share one column: both stop where the send and close controls begin.
        float columnLeft = pill.left + dp(FIELD_SIDE_INSET);
        float columnRight = pill.right - dp(FIELD_SIDE_INSET);
        if (enterView != null) {
            final int primaryEndInset = enterView.getComposerPrimaryEndInset();
            if (primaryEndInset > 0) {
                // The send column follows LocaleController.isRTL, not the layout direction.
                if (LocaleController.isRTL) {
                    columnLeft = Math.max(columnLeft, enterView.getLeft() + primaryEndInset + dp(FIELD_SEND_GAP));
                } else {
                    columnRight = Math.min(columnRight, enterView.getRight() - primaryEndInset - dp(FIELD_SEND_GAP));
                }
            }
        }

        if (drawPill && inputFactor > 0 && topViewHeight > dp(STRIP_INSET * 2)) {
            // Reply, edit, forward and link preview all share this top view, so one strip covers them.
            final float stripAlpha = alpha * inputFactor * topViewProgress;
            rect.set(columnLeft, pill.top + dp(STRIP_INSET), columnRight, pill.top + topViewHeight - dp(STRIP_INSET));
            final float stripRadius = Math.min(dp(STRIP_RADIUS), rect.height() / 2f);
            fillPaint.setColor(Theme.multAlpha(container, stripAlpha));
            canvas.drawRoundRect(rect, stripRadius, stripRadius, fillPaint);
            canvas.save();
            canvas.clipRect(rect.left, rect.top, rect.left + dp(STRIP_ACCENT), rect.bottom);
            fillPaint.setColor(Theme.multAlpha(primaryColor(), stripAlpha));
            canvas.drawRoundRect(rect, stripRadius, stripRadius, fillPaint);
            canvas.restore();
        }

        if (drawPill && inputFactor > 0) {
            rect.set(columnLeft, pill.top + topViewHeight + dp(FIELD_INSET), columnRight, pill.bottom - dp(FIELD_INSET));
            final float fieldRadius = Math.min(dp(FIELD_RADIUS), rect.height() / 2f);
            fillPaint.setColor(Theme.multAlpha(fieldColor(), alpha * inputFactor));
            canvas.drawRoundRect(rect, fieldRadius, fieldRadius, fillPaint);
        }
        if (underKeyboard != null) {
            fillPaint.setColor(Theme.getColor(Theme.key_chat_emojiPanelBackground, resourcesProvider));
            canvas.drawRect(0, underKeyboard.top, width, height, fillPaint);
            if (InterfaceStyleController.panelDividers()) {
                dividerPaint.setColor(outlineVariantColor(surface));
                canvas.drawRect(0, underKeyboard.top, width, underKeyboard.top + Math.max(1, dp(0.66f)), dividerPaint);
            }
        }
    }
    /**
     * The layout editor's stand-in for the island, frosted over the preview's wallpaper when a factory is
     * given. Like the Liquid Glass preview it shows the configured blur whatever the account's blur state.
     */
    public static Drawable previewBar(@Nullable BlurredBackgroundDrawableViewFactory factory, @Nullable View view) {
        final ComposerMd3Surface surface = new ComposerMd3Surface(null);
        final BlurredBackgroundDrawable frost = factory != null ? factory.create(view, surface.frostProvider()) : null;
        if (frost != null) {
            frost.setRadius(dp(ISLAND_RADIUS));
        }
        return new PreviewDrawable() {
            @Override
            public void draw(@NonNull Canvas canvas) {
                final Rect bounds = getBounds();
                if (frost != null) {
                    frost.setBounds(bounds);
                    frost.draw(canvas);
                } else {
                    surface.island.set(bounds);
                    surface.fillPaint.setColor(surface.surfaceColor());
                    surface.fillPaint.setShadowLayer(dp(SHADOW_RADIUS), 0, dp(SHADOW_DY), surface.shadowColor());
                    canvas.drawRoundRect(surface.island, dp(ISLAND_RADIUS), dp(ISLAND_RADIUS), surface.fillPaint);
                    surface.fillPaint.clearShadowLayer();
                }
            }
        };
    }

    /** How far the preview island reaches past the mock input and toolbar, matching the chat. */
    public static int previewPadding() {
        return dp(ISLAND_PADDING);
    }

    /** The layout editor's field, stopping {@code endReserve} short of its end for the send circle. */
    public static Drawable previewField(int endReserve) {
        final ComposerMd3Surface surface = new ComposerMd3Surface(null);
        return new PreviewDrawable() {
            @Override
            public void draw(@NonNull Canvas canvas) {
                final Rect bounds = getBounds();
                // Gravity.END puts the preview's send circle on the right even in RTL: the app does not
                // declare supportsRtl.
                surface.rect.set(bounds.left + dp(FIELD_SIDE_INSET), bounds.top + dp(FIELD_INSET), bounds.right - endReserve, bounds.bottom - dp(FIELD_INSET));
                final float radius = Math.min(dp(FIELD_RADIUS), surface.rect.height() / 2f);
                surface.fillPaint.setColor(surface.fieldColor());
                canvas.drawRoundRect(surface.rect, radius, radius, surface.fillPaint);
            }
        };
    }

    private abstract static class PreviewDrawable extends Drawable {
        @Override
        public void setAlpha(int alpha) {
        }

        @Override
        public void setColorFilter(@Nullable ColorFilter colorFilter) {
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }

    private static float visibility(View view) {
        return view != null && view.getVisibility() == View.VISIBLE ? view.getAlpha() : 0;
    }
}
