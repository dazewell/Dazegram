package xyz.nextalone.nagram.ui.composer;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.AndroidUtilities.dpf2;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;

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
    private static final float FIELD_ON_SURFACE_BLEND = 0.10f;
    private static final int STRIP_RADIUS = 12;
    private static final int STRIP_ACCENT = 3;
    private static final int STRIP_FIELD_GAP = 6;
    private static final int FIELD_RADIUS = 21;
    // The field is drawn this far inside the 44dp text row top and bottom, so it is 42dp and shares the
    // row's centre with the send circle.
    private static final int FIELD_INSET = 1;
    // The island reaches this far past the text row and the tools row, and the field sits the same 3dp in
    // from its leading edge, so the field has an even margin on three sides.
    private static final int ISLAND_PADDING = 2;
    private static final int FIELD_SIDE_INSET = 3;
    private static final int FIELD_SEND_GAP = 8;
    private static final float FOCUS_RING = 1.5f;
    // The send circle's top corner sits about 4dp inside the island, which caps the radius near 13dp.
    private static final int ISLAND_RADIUS = 13;
    private static final int SHADOW_RADIUS = 4;
    private static final int SHADOW_DY = 2;
    private static final int SHADOW_ALPHA = 77;
    // The pill's padded bounds already sit this far in from the container, so a selection island uses it too.
    private static final int SIDE_INSET = 7;

    private final Theme.ResourcesProvider resourcesProvider;
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dividerPaint = new Paint();
    private final RectF rect = new RectF();
    private final RectF island = new RectF();
    private final Rect islandBounds = new Rect();

    private ChatActivityEnterView enterView;
    private View channelButtons;
    private View actionButtons;
    private boolean barVisible;
    private View host;
    private BlurredBackgroundDrawable frost;
    private int frostAccount;
    // The focus ring is painted by the host, and a focus change only redraws the edit text itself.
    private final ViewTreeObserver.OnGlobalFocusChangeListener focusListener = (oldFocus, newFocus) -> {
        if (host != null) {
            host.invalidate();
        }
    };

    public ComposerMd3Surface(Theme.ResourcesProvider resourcesProvider) {
        this.resourcesProvider = resourcesProvider;
        strokePaint.setStyle(Paint.Style.STROKE);
    }

    public static ComposerMd3Surface createIfEnabled(Theme.ResourcesProvider resourcesProvider) {
        return InterfaceStyleController.applyComposer() ? new ComposerMd3Surface(resourcesProvider) : null;
    }

    // The island shows one of these at a time; their alpha is how ChatActivity cross-fades between them,
    // so it is also how this surface decides which run the island wraps.
    public void bind(View host, ChatActivityEnterView enterView, View channelButtons, View actionButtons) {
        this.host = host;
        this.enterView = enterView;
        this.channelButtons = channelButtons;
        this.actionButtons = actionButtons;
        host.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(@NonNull View v) {
                v.getViewTreeObserver().addOnGlobalFocusChangeListener(focusListener);
            }

            @Override
            public void onViewDetachedFromWindow(@NonNull View v) {
                v.getViewTreeObserver().removeOnGlobalFocusChangeListener(focusListener);
            }
        });
        if (host.isAttachedToWindow()) {
            host.getViewTreeObserver().addOnGlobalFocusChangeListener(focusListener);
        }
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
            left += pill.left * inputFactor;
            top += (pill.top - pad) * inputFactor;
            right += pill.right * inputFactor;
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

        final int frostAlpha = Math.round(255 * barFactor);
        if (frost != null && frost.getAlpha() != frostAlpha) {
            // Only on a change: every setAlpha re-records the blur's display list.
            frost.setAlpha(frostAlpha);
        }
        if (barFactor > 0) {
            if (frosted()) {
                island.roundOut(islandBounds);
                frost.setBounds(islandBounds);
                frost.draw(canvas);
            } else {
                fillPaint.setColor(Theme.multAlpha(surface, barFactor));
                fillPaint.setShadowLayer(dp(SHADOW_RADIUS), 0, dp(SHADOW_DY), Theme.multAlpha(shadowColor(), barFactor));
                canvas.drawRoundRect(island, radius, radius, fillPaint);
                fillPaint.clearShadowLayer();
            }
        }

        // The channel and selection runs have no field of their own, so the island itself takes the tone.
        final float runFactor = Math.max(actionFactor, drawPill ? alpha * channelFactor : 0);
        if (runFactor > 0) {
            fillPaint.setColor(Theme.multAlpha(fieldColor(), runFactor));
            canvas.drawRoundRect(island, radius, radius, fillPaint);
        }

        if (drawPill && inputFactor > 0 && topViewHeight > dp(STRIP_FIELD_GAP)) {
            // Reply, edit, forward and link preview all share this top view, so one strip covers them.
            final float stripAlpha = alpha * inputFactor * topViewProgress;
            rect.set(pill.left + dp(FIELD_SIDE_INSET), pill.top + dp(FIELD_INSET), pill.right - dp(FIELD_SIDE_INSET), pill.top + topViewHeight - dp(STRIP_FIELD_GAP));
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
            float fieldLeft = pill.left + dp(FIELD_SIDE_INSET);
            float fieldRight = pill.right - dp(FIELD_SIDE_INSET);
            final int primaryEndInset = enterView.getComposerPrimaryEndInset();
            if (primaryEndInset > 0) {
                // The send column follows LocaleController.isRTL, not the layout direction.
                if (LocaleController.isRTL) {
                    fieldLeft = Math.max(fieldLeft, enterView.getLeft() + primaryEndInset + dp(FIELD_SEND_GAP));
                } else {
                    fieldRight = Math.min(fieldRight, enterView.getRight() - primaryEndInset - dp(FIELD_SEND_GAP));
                }
            }
            final float fieldAlpha = alpha * inputFactor;
            rect.set(fieldLeft, pill.top + topViewHeight + dp(FIELD_INSET), fieldRight, pill.bottom - dp(FIELD_INSET));
            final float fieldRadius = Math.min(dp(FIELD_RADIUS), rect.height() / 2f);
            fillPaint.setColor(Theme.multAlpha(fieldColor(), fieldAlpha));
            canvas.drawRoundRect(rect, fieldRadius, fieldRadius, fillPaint);
            final boolean focused = enterView.getEditField() != null && enterView.getEditField().isFocused();
            if (focused) {
                final float ring = dpf2(FOCUS_RING);
                rect.inset(ring / 2f, ring / 2f);
                strokePaint.setStrokeWidth(ring);
                strokePaint.setColor(Theme.multAlpha(primaryColor(), fieldAlpha));
                final float ringRadius = Math.max(0, fieldRadius - ring / 2f);
                canvas.drawRoundRect(rect, ringRadius, ringRadius, strokePaint);
            }
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
