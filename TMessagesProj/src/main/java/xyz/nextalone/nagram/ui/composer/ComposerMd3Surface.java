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

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.graphics.ColorUtils;

import org.telegram.messenger.LocaleController;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.ChatActivityEnterView;

import xyz.nextalone.nagram.helpers.InterfaceStyleController;
import xyz.nextalone.nagram.ui.ComposerToolbarLayout;

/**
 * NagramX (#interface-style): the flat MD3 Composer. ChatActivity's ChatInputViewsContainer hands its
 * draw pass here instead of painting the glass island and under-keyboard drawables, so the whole bottom
 * region becomes one edge-attached bar: an outlined field with the send column outside it, a tonal tools
 * band under it, and a tonal host pill under whichever action run owns the island instead of the input.
 * Colours are read from the chat's theme on every draw; nothing here is cached between frames.
 */
public final class ComposerMd3Surface {
    private static final float CONTAINER_ON_SURFACE_BLEND = 0.06f;
    private static final int STRIP_RADIUS = 12;
    private static final int STRIP_ACCENT = 3;
    private static final int STRIP_FIELD_GAP = 2;
    private static final int FIELD_RADIUS = 24;
    private static final int FIELD_GROW = 4;
    private static final int FIELD_SEND_GAP = 6;
    private static final int BAR_TOP_PADDING = 6;
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

    public int surfaceColor() {
        return ColorUtils.setAlphaComponent(Theme.getColor(Theme.key_chat_messagePanelBackground, resourcesProvider), 255);
    }

    public int containerColor() {
        final int onSurface = ColorUtils.setAlphaComponent(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider), 255);
        return ColorUtils.blendARGB(surfaceColor(), onSurface, CONTAINER_ON_SURFACE_BLEND);
    }

    public int primaryColor() {
        return Theme.getColor(Theme.key_chat_messagePanelSend, resourcesProvider);
    }

    private int outlineVariantColor(int surface) {
        return InterfaceStyleController.panelDividerPaint(surface, resourcesProvider).getColor();
    }

    public boolean contains(float x, float y) {
        return y >= barTop;
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
        final int surface = surfaceColor();
        final int container = containerColor();
        final int outlineVariant = outlineVariantColor(surface);
        final float divider = Math.max(1, dp(0.66f));
        final boolean dividers = InterfaceStyleController.panelDividers();

        final float topViewProgress = enterView != null ? enterView.getTopViewEnterProgress() : 0;
        final float topViewHeight = enterView != null ? Math.max(0, enterView.getTopViewHeight()) * topViewProgress : 0;
        final float fieldTop = pill.top + topViewHeight - dp(FIELD_GROW) * inputFactor * (1f - topViewProgress);
        barTop = Math.min(pill.top - pillTranslation, fieldTop) - dp(BAR_TOP_PADDING);

        fillPaint.setColor(surface);
        canvas.drawRect(0, barTop, width, height, fillPaint);

        if (toolsInset > 0 && inputFactor > 0) {
            final float toolsTop = pill.bottom + toolsInset - dp(ComposerToolbarLayout.height());
            fillPaint.setColor(Theme.multAlpha(container, alpha * inputFactor));
            canvas.drawRect(0, toolsTop, width, height, fillPaint);
            if (dividers) {
                dividerPaint.setColor(Theme.multAlpha(outlineVariant, alpha * inputFactor));
                canvas.drawRect(0, toolsTop, width, toolsTop + divider, dividerPaint);
            }
        }
        if (dividers) {
            dividerPaint.setColor(outlineVariant);
            canvas.drawRect(0, barTop, width, barTop + divider, dividerPaint);
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
            final boolean focused = enterView.getEditField() != null && enterView.getEditField().isFocused();
            final float strokeWidth = dp(focused ? 2 : 1);
            rect.set(fieldLeft, fieldTop, fieldRight, pill.bottom);
            final float radius = Math.min(dp(FIELD_RADIUS), rect.height() / 2f);
            fillPaint.setColor(Theme.multAlpha(surface, alpha * inputFactor));
            canvas.drawRoundRect(rect, radius, radius, fillPaint);
            rect.inset(strokeWidth / 2f, strokeWidth / 2f);
            strokePaint.setStrokeWidth(strokeWidth);
            strokePaint.setColor(Theme.multAlpha(focused ? primaryColor() : outlineVariant, alpha * inputFactor));
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

    /** The layout editor's stand-in for the bar: surface above {@code toolsTop}, the tools band below. */
    public static Drawable previewBar(int toolsTop) {
        final ComposerMd3Surface surface = new ComposerMd3Surface(null);
        return new PreviewDrawable() {
            @Override
            public void draw(@NonNull Canvas canvas) {
                final Rect bounds = getBounds();
                surface.fillPaint.setColor(surface.surfaceColor());
                canvas.drawRect(bounds, surface.fillPaint);
                surface.fillPaint.setColor(surface.containerColor());
                canvas.drawRect(bounds.left, bounds.top + toolsTop, bounds.right, bounds.bottom, surface.fillPaint);
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
                surface.rect.set(bounds.left, bounds.top, bounds.right - endReserve, bounds.bottom);
                final float radius = Math.min(dp(FIELD_RADIUS), surface.rect.height() / 2f);
                surface.fillPaint.setColor(surfaceColor);
                canvas.drawRoundRect(surface.rect, radius, radius, surface.fillPaint);
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
