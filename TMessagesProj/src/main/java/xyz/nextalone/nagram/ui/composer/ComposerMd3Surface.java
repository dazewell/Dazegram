package xyz.nextalone.nagram.ui.composer;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewGroup;

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
 * NagramX (#interface-style): the flat MD3 Composer. ChatActivity's ChatInputViewsContainer hands its
 * draw pass here instead of painting the glass island and under-keyboard drawables, so the whole bottom
 * region becomes one edge-attached bar: an outlined field with the send column outside it and the tools
 * row under it on the same surface, and a tonal host pill under whichever action run owns the island
 * instead of the input.
 * Colours are read from the chat's theme on every draw; nothing here is cached between frames.
 */
public final class ComposerMd3Surface {
    private static final float CONTAINER_ON_SURFACE_BLEND = 0.06f;
    private static final int STRIP_RADIUS = 12;
    private static final int STRIP_ACCENT = 3;
    private static final int STRIP_FIELD_GAP = 6;
    private static final int FIELD_RADIUS = 20;
    // The field is drawn this far inside the 44dp text row on each side, so it is 40dp and shares the row's
    // centre with the send circle.
    private static final int FIELD_INSET = 2;
    private static final int FIELD_SEND_GAP = 8;
    // The input section is the 44dp text row plus this much above and below it.
    private static final int BAR_TOP_PADDING = 2;
    private static final int HOST_RADIUS = 22;

    private final Theme.ResourcesProvider resourcesProvider;
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dividerPaint = new Paint();
    private final RectF rect = new RectF();

    private ChatActivityEnterView enterView;
    private View channelButtons;
    private View actionButtons;
    private float barTop = Float.MAX_VALUE;
    private boolean barVisible;
    private BlurredBackgroundDrawable frost;
    private int frostAccount;

    public ComposerMd3Surface(Theme.ResourcesProvider resourcesProvider) {
        this.resourcesProvider = resourcesProvider;
        strokePaint.setStyle(Paint.Style.STROKE);
    }

    public static ComposerMd3Surface createIfEnabled(Theme.ResourcesProvider resourcesProvider) {
        return InterfaceStyleController.applyComposer() ? new ComposerMd3Surface(resourcesProvider) : null;
    }

    // The island shows one of these at a time; their alpha is how ChatActivity cross-fades between them,
    // so it is also how this surface decides between the field and a host pill.
    public void bind(ChatActivityEnterView enterView, View channelButtons, View actionButtons) {
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
        frost.setRadius(0);
        frostAccount = account;
    }

    // Flat by construction: no stroke and no shadow, so only the blur and the tinted surface show.
    private BlurredBackgroundProvider frostProvider() {
        return new BlurredBackgroundProviderBuilder(resourcesProvider)
                .setBackgroundColor((r, isDark) -> Theme.multAlpha(surfaceColor(), NaConfig.interfaceStyleBlurAlpha()))
                .setStrokeColorTop(0, 0)
                .setStrokeColorBottom(0, 0)
                .setShadowColor(0, 0)
                .build();
    }

    private boolean frosted() {
        return frost != null && BlurredBackgroundProviderImpl.checkBlurEnabled(frostAccount, resourcesProvider);
    }

    public int surfaceColor() {
        return ColorUtils.setAlphaComponent(Theme.getColor(Theme.key_chat_messagePanelBackground, resourcesProvider), 255);
    }

    /** The 6% on-surface tint laid over the bar, so it tones a frosted bar and an opaque one alike. */
    private int containerOverlay() {
        final int onSurface = Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider);
        return ColorUtils.setAlphaComponent(onSurface, Math.round(255 * CONTAINER_ON_SURFACE_BLEND));
    }

    public int primaryColor() {
        return Theme.getColor(Theme.key_chat_messagePanelSend, resourcesProvider);
    }

    private int outlineVariantColor(int surface) {
        return InterfaceStyleController.panelDividerPaint(surface, resourcesProvider).getColor();
    }

    public boolean contains(float x, float y) {
        // Same rule as the island glass: only a fully shown bar takes touches.
        return barVisible && y >= barTop;
    }

    /** How far the bar reaches above the island's pill. */
    public int topOverhang() {
        return dp(BAR_TOP_PADDING);
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
            barTop = Float.MAX_VALUE;
            return;
        }
        final float alpha = pillAlpha / 255f;
        final float inputFactor = visibility(enterView);
        final float channelFactor = visibility(channelButtons);
        final float actionFactor = visibility(actionButtons);
        // ChatActivity fades the island out both for the selection bar, which the bar keeps hosting, and for
        // modes like adding a poll option, which hide the composer outright. Dividing the selection share out
        // of the island's alpha leaves only the second kind, so the cross-fade into selection never dims it.
        final float barFactor = actionFactor >= 1f ? 1f : Math.max(actionFactor, Math.min(1f, alpha / (1f - actionFactor)));
        barVisible = barFactor >= 1f;
        final int surface = surfaceColor();
        final int container = containerOverlay();
        final int outlineVariant = outlineVariantColor(surface);
        final float divider = Math.max(1, dp(0.66f));
        final boolean dividers = InterfaceStyleController.panelDividers();

        final float topViewProgress = enterView != null ? enterView.getTopViewEnterProgress() : 0;
        final float topViewHeight = enterView != null ? Math.max(0, enterView.getTopViewHeight()) * topViewProgress : 0;
        final float fieldTop = pill.top + topViewHeight + dp(FIELD_INSET) * inputFactor;
        final float fieldBottom = pill.bottom - dp(FIELD_INSET) * inputFactor;
        barTop = pill.top - pillTranslation - topOverhang();

        final int frostAlpha = Math.round(255 * barFactor);
        if (frost != null && frost.getAlpha() != frostAlpha) {
            // Only on a change: every setAlpha re-records the blur's display list.
            frost.setAlpha(frostAlpha);
        }
        if (barFactor > 0) {
            if (frosted()) {
                frost.setBounds(0, Math.round(barTop), width, height);
                frost.draw(canvas);
            } else {
                fillPaint.setColor(Theme.multAlpha(surface, barFactor));
                canvas.drawRect(0, barTop, width, height, fillPaint);
            }
            if (dividers) {
                dividerPaint.setColor(Theme.multAlpha(outlineVariant, barFactor));
                canvas.drawRect(0, barTop, width, barTop + divider, dividerPaint);
            }
        }

        if (actionFactor > 0 && actionButtons != null) {
            rect.set(actionButtons.getLeft() + dp(7), pill.top - pillTranslation, actionButtons.getRight() - dp(7), pill.bottom - pillTranslation);
            drawHost(canvas, container, actionFactor);
        }

        if (drawPill && channelFactor > 0) {
            rect.set(pill);
            drawHost(canvas, container, alpha * channelFactor);
        }

        if (drawPill && inputFactor > 0 && topViewHeight > dp(STRIP_FIELD_GAP)) {
            // Reply, edit, forward and link preview all share this top view, so one strip covers them.
            final float stripAlpha = alpha * inputFactor * topViewProgress;
            rect.set(pill.left, pill.top, pill.right, pill.top + topViewHeight - dp(STRIP_FIELD_GAP));
            final float radius = Math.min(dp(STRIP_RADIUS), rect.height() / 2f);
            fillPaint.setColor(Theme.multAlpha(container, stripAlpha));
            canvas.drawRoundRect(rect, radius, radius, fillPaint);
            canvas.save();
            canvas.clipRect(rect.left, rect.top, rect.left + dp(STRIP_ACCENT), rect.bottom);
            fillPaint.setColor(Theme.multAlpha(primaryColor(), stripAlpha));
            canvas.drawRoundRect(rect, radius, radius, fillPaint);
            canvas.restore();
        }

        if (drawPill && inputFactor > 0) {
            float fieldLeft = pill.left;
            float fieldRight = pill.right;
            final int primaryEndInset = enterView.getComposerPrimaryEndInset();
            if (primaryEndInset > 0) {
                // The send column follows LocaleController.isRTL, not the layout direction.
                if (LocaleController.isRTL) {
                    fieldLeft = Math.max(fieldLeft, enterView.getLeft() + primaryEndInset + dp(FIELD_SEND_GAP));
                } else {
                    fieldRight = Math.min(fieldRight, enterView.getRight() - primaryEndInset - dp(FIELD_SEND_GAP));
                }
            }
            final float strokeWidth = dp(1);
            rect.set(fieldLeft, fieldTop, fieldRight, fieldBottom);
            final float radius = Math.min(dp(FIELD_RADIUS), rect.height() / 2f);
            // No fill: the field sits on the bar's own surface, frosted or not, and its outline is the edge.
            rect.inset(strokeWidth / 2f, strokeWidth / 2f);
            strokePaint.setStrokeWidth(strokeWidth);
            strokePaint.setColor(Theme.multAlpha(outlineVariant, alpha * inputFactor));
            final float strokeRadius = Math.max(0, radius - strokeWidth / 2f);
            canvas.drawRoundRect(rect, strokeRadius, strokeRadius, strokePaint);
        }

        if (underKeyboard != null) {
            fillPaint.setColor(Theme.getColor(Theme.key_chat_emojiPanelBackground, resourcesProvider));
            canvas.drawRect(0, underKeyboard.top, width, height, fillPaint);
            if (dividers) {
                dividerPaint.setColor(outlineVariant);
                canvas.drawRect(0, underKeyboard.top, width, underKeyboard.top + divider, dividerPaint);
            }
        }
    }

    private void drawHost(Canvas canvas, int color, float alpha) {
        final float radius = Math.min(dp(HOST_RADIUS), rect.height() / 2f);
        fillPaint.setColor(Theme.multAlpha(color, alpha));
        canvas.drawRoundRect(rect, radius, radius, fillPaint);
    }

    /**
     * The layout editor's stand-in for the bar, frosted over the preview's wallpaper when a factory is
     * given. Like the Liquid Glass preview it shows the configured blur whatever the account's blur state.
     */
    public static Drawable previewBar(@Nullable BlurredBackgroundDrawableViewFactory factory, @Nullable View view) {
        final ComposerMd3Surface surface = new ComposerMd3Surface(null);
        final BlurredBackgroundDrawable frost = factory != null ? factory.create(view, surface.frostProvider()) : null;
        if (frost != null) {
            frost.setRadius(0);
        }
        return new PreviewDrawable() {
            @Override
            public void draw(@NonNull Canvas canvas) {
                final Rect bounds = getBounds();
                final int surfaceColor = surface.surfaceColor();
                if (frost != null) {
                    frost.setBounds(bounds);
                    frost.draw(canvas);
                } else {
                    surface.fillPaint.setColor(surfaceColor);
                    canvas.drawRect(bounds, surface.fillPaint);
                }
                if (InterfaceStyleController.panelDividers()) {
                    final float divider = Math.max(1, dp(0.66f));
                    surface.dividerPaint.setColor(surface.outlineVariantColor(surfaceColor));
                    canvas.drawRect(bounds.left, bounds.top, bounds.right, bounds.top + divider, surface.dividerPaint);
                }
            }
        };
    }

    /** The layout editor's outlined field, stopping {@code endReserve} short of its end for the send circle. */
    public static Drawable previewField(int endReserve) {
        final ComposerMd3Surface surface = new ComposerMd3Surface(null);
        return new PreviewDrawable() {
            @Override
            public void draw(@NonNull Canvas canvas) {
                final Rect bounds = getBounds();
                final int surfaceColor = surface.surfaceColor();
                final float strokeWidth = dp(1);
                // Gravity.END puts the preview's send circle on the right even in RTL: the app does not
                // declare supportsRtl.
                surface.rect.set(bounds.left, bounds.top + dp(FIELD_INSET), bounds.right - endReserve, bounds.bottom - dp(FIELD_INSET));
                final float radius = Math.min(dp(FIELD_RADIUS), surface.rect.height() / 2f);
                surface.rect.inset(strokeWidth / 2f, strokeWidth / 2f);
                surface.strokePaint.setStrokeWidth(strokeWidth);
                surface.strokePaint.setColor(surface.outlineVariantColor(surfaceColor));
                canvas.drawRoundRect(surface.rect, radius - strokeWidth / 2f, radius - strokeWidth / 2f, surface.strokePaint);
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
