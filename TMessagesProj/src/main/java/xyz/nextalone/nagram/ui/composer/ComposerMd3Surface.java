package xyz.nextalone.nagram.ui.composer;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.AndroidUtilities.dpf2;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.graphics.ColorUtils;
import androidx.core.view.WindowInsetsCompat;

import org.telegram.messenger.LocaleController;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.ChatActivityEnterView;
import org.telegram.ui.Components.blur3.BlurredBackgroundDrawableViewFactory;
import org.telegram.ui.Components.blur3.drawable.BlurredBackgroundDrawable;
import org.telegram.ui.Components.blur3.drawable.color.BlurredBackgroundProvider;
import org.telegram.ui.Components.blur3.drawable.color.BlurredBackgroundProviderBuilder;
import org.telegram.ui.Components.blur3.drawable.color.impl.BlurredBackgroundProviderImpl;
import org.telegram.ui.Components.blur3.source.BlurredBackgroundSource;
import org.telegram.ui.Components.chat.ChatInputViewsContainer;
import org.telegram.ui.Components.chat.ViewPositionWatcher;

import me.vkryl.core.reference.ReferenceList;
import xyz.nextalone.nagram.NaConfig;
import xyz.nextalone.nagram.helpers.InterfaceStyleController;

/**
 * NagramX (#interface-style): the MD3 Composer. ChatActivity's ChatInputViewsContainer hands its draw pass
 * here instead of painting the glass island and under-keyboard drawables. The composer becomes one island,
 * frosted and lightly shadowed, docked as a sheet while it rests on the nav bar and floating once the keyboard
 * lifts it: a tonal field with the send column beside it and the tools row
 * under it, or a tonal island around whichever action run replaces the input. The under-keyboard panel
 * stays docked and opaque.
 * Colours are read from the chat's theme on every draw. Only the frost drawable's alpha and corner radius are kept
 * between frames, and changed only when they move, because each change re-records its render node.
 */
public final class ComposerMd3Surface {
    private static final float CONTAINER_ON_SURFACE_BLEND = 0.06f;
    // Laid over the island like the strip, one step above its 6%. On an opaque island this matches an opaque
    // blend of the surface to within alpha rounding; on the frost it tints what shows through instead of hiding it.
    private static final float FIELD_ON_SURFACE_BLEND = 0.08f;
    // Lightening reads weaker per percent than darkening, so a dark theme's field takes a bigger step.
    private static final float FIELD_ON_SURFACE_BLEND_DARK = 0.14f;
    // The composer keeps this share of the blur the Blur strength setting asks for, so a bright bubble passing
    // under it cannot glow through: the 0.5-1 alpha range becomes 0.65-1.
    private static final float FROST_SHARE = 0.7f;
    private static final int STRIP_RADIUS = 10;
    private static final int STRIP_ACCENT = 3;
    private static final int STRIP_ACCENT_INSET = 6;
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
    // The send slot is DEFAULT_HEIGHT square; its touch target reaches this far past it on every side, to 48dp.
    private static final int SEND_TARGET_REACH = 2;
    // The send circle's top sits about 5dp inside the island, which caps the radius near 13dp.
    private static final int ISLAND_RADIUS = 13;
    // Docked, the island's sides move 9dp out, so the send circle sits 14dp from the edge and a rounder top
    // corner still clears it.
    private static final int SHEET_RADIUS = 20;
    // How far the keyboard or emoji panel lifts the island before the sheet has fully turned back into it.
    private static final int SHEET_MORPH = 48;
    private static final int SHADOW_RADIUS = 4;
    private static final int SHADOW_DY = 2;
    private static final int SHADOW_ALPHA = 77;
    private static final int DARK_EDGE_TOP = 0x28FFFFFF;
    private static final int DARK_EDGE_BOTTOM = 0x14FFFFFF;
    // The stock pill is drawn this far in from its children's edges, which is how far the input, search and
    // overlay children already sit in from the container.
    private static final int PILL_INSET = 7;
    // The island sits this much further in from the screen sides than the stock pill, 9dp from each edge.
    private static final int ISLAND_EXTRA = 2;
    // Every island child is padded this far in from the container, so the send circle, drawn 3dp inside its slot,
    // clears the island's side by the same 5dp it clears its top.
    private static final int CHILD_SIDE_PADDING = 4;
    // The selection bar fills its padded parent edge to edge, so its island edge is measured from the bar itself:
    // the pill inset, plus the island's extra inset, less the padding the bar already sits inside.
    private static final int ACTION_RUN_SIDE_INSET = PILL_INSET + ISLAND_EXTRA - CHILD_SIDE_PADDING;
    // Added to the stock 9dp lift. Less the island's 2dp bottom padding, the island rests this far above the nav bar.
    private static final int EXTRA_LIFT = 5;
    // Measured on device: with the full trim the expanded island sat about 18dp under the header, twice its side gap.
    private static final int EXPANDED_HEADROOM_RETURN = 9;

    private final Theme.ResourcesProvider resourcesProvider;
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dividerPaint = new Paint();
    private final Paint edgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    {
        edgePaint.setStyle(Paint.Style.STROKE);
    }
    private final RectF rect = new RectF();
    private final RectF island = new RectF();
    private final Rect islandBounds = new Rect();
    private final float[] islandRadii = new float[8];
    private final Path islandPath = new Path();
    private float frostTopRadius = -1, frostBottomRadius = -1;
    private final RectF sendSlot = new RectF();
    private float touchShiftX, touchShiftY;
    private float appliedShiftX, appliedShiftY;

    private ChatActivityEnterView enterView;
    private View channelButtons;
    private View actionButtons;
    private boolean barVisible;
    private ChatInputViewsContainer host;
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
    public void bind(ChatInputViewsContainer host, ChatActivityEnterView enterView, View channelButtons, View actionButtons) {
        this.host = host;
        final FrameLayout island = host.getInputIslandBubbleContainer();
        island.setClipToPadding(false);
        island.setPadding(dp(CHILD_SIDE_PADDING), 0, dp(CHILD_SIDE_PADDING), 0);
        this.enterView = enterView;
        this.channelButtons = channelButtons;
        this.actionButtons = actionButtons;
    }

    /**
     * Frosts the island the way the MD3 chat header is frosted. It gets its own factory over the chat's frosted
     * source because the shared one hands out drawables with the Liquid Glass shader when that is enabled.
     * Without a source (below API 31, or chat blur off when the chat opened) the island stays opaque.
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

    // A soft shadow in the theme's own panel-shadow colour lifts the island. On a dark theme the shadow vanishes
    // into the wallpaper, so a faint light edge, brighter on top, traces it instead.
    private BlurredBackgroundProvider frostProvider() {
        return new BlurredBackgroundProviderBuilder(resourcesProvider)
                .setBackgroundColor((r, isDark) -> Theme.multAlpha(surfaceColor(), frostAlpha()))
                .setStrokeColorTop(0, DARK_EDGE_TOP)
                .setStrokeColorBottom(0, DARK_EDGE_BOTTOM)
                .setStrokeWidth(dpf2(1), dpf2(1))
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

    /** The field's on-surface tint laid over the island, one step above the strip's: darker in a light theme, lighter in a dark one. */
    private int fieldOverlay() {
        final int onSurface = Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider);
        final boolean dark = resourcesProvider != null ? resourcesProvider.isDark() : Theme.isCurrentThemeDark();
        return ColorUtils.setAlphaComponent(onSurface, Math.round(255 * (dark ? FIELD_ON_SURFACE_BLEND_DARK : FIELD_ON_SURFACE_BLEND)));
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
     * The send, mic and camera circle keeps the stock 44dp slot, but under MD3 its touch target is 48dp. A
     * press that starts in the 2dp band around the slot is shifted into it, and the rest of that gesture is
     * shifted by the same amount, so a hold-to-record slide still tracks the finger exactly.
     */
    public void retargetSendTouch(MotionEvent event) {
        final int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            touchShiftX = touchShiftY = 0;
            if (!sendSlot.isEmpty()) {
                final float x = event.getX(), y = event.getY();
                final float reach = dp(SEND_TARGET_REACH);
                if (!sendSlot.contains(x, y) && x >= sendSlot.left - reach && x <= sendSlot.right + reach
                        && y >= sendSlot.top - reach && y <= sendSlot.bottom + reach) {
                    touchShiftX = Math.max(sendSlot.left + 1, Math.min(sendSlot.right - 1, x)) - x;
                    touchShiftY = Math.max(sendSlot.top + 1, Math.min(sendSlot.bottom - 1, y)) - y;
                }
            }
        }
        appliedShiftX = touchShiftX;
        appliedShiftY = touchShiftY;
        if (appliedShiftX != 0 || appliedShiftY != 0) {
            event.offsetLocation(appliedShiftX, appliedShiftY);
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                touchShiftX = touchShiftY = 0;
            }
        }
    }

    /** Undoes {@link #retargetSendTouch} once the container has dispatched the event, for whoever reads it next. */
    public void restoreSendTouch(MotionEvent event) {
        if (appliedShiftX != 0 || appliedShiftY != 0) {
            event.offsetLocation(-appliedShiftX, -appliedShiftY);
            appliedShiftX = appliedShiftY = 0;
        }
    }

    /**
     * 1 while the island rests on the nav bar, where it docks as a sheet running to the screen's sides and bottom
     * so no edge cuts across the display's rounded corners; 0 once the keyboard or emoji panel has lifted it
     * {@link #SHEET_MORPH} clear, where it floats as an island again.
     */
    private float dockFactor() {
        if (host == null) {
            return 0;
        }
        final WindowInsets insets = host.getRootWindowInsets();
        if (insets == null) {
            return 0;
        }
        // The same resting inset WindowInsetsStateHolder builds the container's bottom inset from, so the difference
        // below is exactly the keyboard's or emoji panel's share of it.
        final int rest = WindowInsetsCompat.toWindowInsetsCompat(insets, host).getInsetsIgnoringVisibility(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout()).bottom;
        final float lifted = host.getMeasuredHeight() - rest - host.getInputBubbleBottomLift() - host.getInputBubbleBottom();
        return Math.max(0, Math.min(1, 1f - lifted / dp(SHEET_MORPH)));
    }

    private static float frostAlpha() {
        return 1f - (1f - NaConfig.interfaceStyleBlurAlpha()) * FROST_SHARE;
    }

    /** How much higher than the stock lift the island floats, so it keeps clear of the screen's curved corners. */
    public int extraLift() {
        return dp(EXTRA_LIFT);
    }

    /** How far the island reaches above the pill. */
    public int topOverhang() {
        return dp(ISLAND_PADDING);
    }

    /** How much higher the island's top sits than a Liquid Glass pill's: the extra lift plus its reach above the pill. */
    public int reachAboveStock() {
        return extraLift() + topOverhang();
    }

    /**
     * How much less room the expanded input gets than a Liquid Glass one: the extra lift, the island's reach
     * above its pill, and whatever panel the header shows under the action bar, so it never slides under it.
     * The stock budget already leaves more headroom above the pill than the island needs; this much of it is
     * handed back so the gap under the header matches the island's 9dp side gaps.
     */
    public int expandedInputTrim(int headerPanelHeight) {
        return reachAboveStock() + Math.max(0, headerPanelHeight) - dp(EXPANDED_HEADROOM_RETURN);
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
        // The pill is drawn from the container's edges; the island sits ISLAND_EXTRA inside it.
        final float pillLeft = pill.left + dp(ISLAND_EXTRA);
        final float pillRight = pill.right - dp(ISLAND_EXTRA);

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
            left += pillLeft * plainFactor;
            top += (pill.top - pad) * plainFactor;
            right += pillRight * plainFactor;
            bottom += (pill.bottom + pad) * plainFactor;
        }
        if (inputFactor > 0) {
            weight += inputFactor;
            left += pillLeft * inputFactor;
            top += (pill.top - pad) * inputFactor;
            right += pillRight * inputFactor;
            bottom += (pill.bottom + toolsInset + pad) * inputFactor;
        }
        if (channelFactor > 0) {
            weight += channelFactor;
            left += pillLeft * channelFactor;
            top += (pill.top - pad) * channelFactor;
            right += pillRight * channelFactor;
            bottom += (pill.bottom + pad) * channelFactor;
        }
        if (actionFactor > 0 && actionButtons != null) {
            weight += actionFactor;
            left += (actionButtons.getLeft() + dp(ACTION_RUN_SIDE_INSET)) * actionFactor;
            top += (pill.top - pillTranslation - pad) * actionFactor;
            right += (actionButtons.getRight() - dp(ACTION_RUN_SIDE_INSET)) * actionFactor;
            bottom += (pill.bottom - pillTranslation + pad) * actionFactor;
        }
        island.set(left / weight, top / weight, right / weight, bottom / weight);
        // Docked, only the top edge stays where the island's is: the sides and bottom run off the screen.
        final float dock = dockFactor();
        if (dock > 0) {
            island.left -= island.left * dock;
            island.right += (width - island.right) * dock;
            island.bottom += (height - island.bottom) * dock;
        }
        final float radius = Math.min(dp(ISLAND_RADIUS) + dp(SHEET_RADIUS - ISLAND_RADIUS) * dock, island.height() / 2f);
        final float bottomRadius = Math.min(dp(ISLAND_RADIUS) * (1f - dock), island.height() / 2f);
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
                if (frostTopRadius != radius || frostBottomRadius != bottomRadius) {
                    frostTopRadius = radius;
                    frostBottomRadius = bottomRadius;
                    frost.setRadius(radius, radius, bottomRadius, bottomRadius);
                }
                island.roundOut(islandBounds);
                frost.setBounds(islandBounds);
                frost.draw(canvas);
            } else {
                fillPaint.setColor(Theme.multAlpha(surface, barFactor));
                fillPaint.setShadowLayer(dp(SHADOW_RADIUS), 0, dp(SHADOW_DY), Theme.multAlpha(shadowColor(), barFactor));
                canvas.drawPath(islandPath, fillPaint);
                fillPaint.clearShadowLayer();
                if (resourcesProvider != null ? resourcesProvider.isDark() : Theme.isCurrentThemeDark()) {
                    final float edge = dpf2(1);
                    edgePaint.setStrokeWidth(edge);
                    edgePaint.setColor(Theme.multAlpha(DARK_EDGE_TOP, barFactor));
                    BlurredBackgroundDrawable.drawStroke(canvas, island.left, island.top, island.right, island.bottom, islandRadii, edge, true, edgePaint);
                    edgePaint.setColor(Theme.multAlpha(DARK_EDGE_BOTTOM, barFactor));
                    BlurredBackgroundDrawable.drawStroke(canvas, island.left, island.top, island.right, island.bottom, islandRadii, edge, false, edgePaint);
                }
            }
        }

        // The channel and selection runs have no field of their own, so the island itself takes the tone.
        final float runFactor = Math.max(actionFactor, drawPill ? alpha * channelFactor : 0);
        if (runFactor > 0) {
            fillPaint.setColor(Theme.multAlpha(fieldOverlay(), runFactor));
            canvas.drawPath(islandPath, fillPaint);
        }

        // The field and the reply strip share one column: both stop where the send and close controls begin.
        float columnLeft = pillLeft + dp(FIELD_SIDE_INSET);
        float columnRight = pillRight - dp(FIELD_SIDE_INSET);
        sendSlot.setEmpty();
        if (enterView != null) {
            final int primaryEndInset = enterView.getComposerPrimaryEndInset();
            if (primaryEndInset > 0) {
                // The send column follows LocaleController.isRTL, not the layout direction.
                if (LocaleController.isRTL) {
                    columnLeft = Math.max(columnLeft, enterView.getLeft() + primaryEndInset + dp(FIELD_SEND_GAP));
                } else {
                    columnRight = Math.min(columnRight, enterView.getRight() - primaryEndInset - dp(FIELD_SEND_GAP));
                }
                if (barVisible && drawPill && inputFactor >= 1f) {
                    // The slot is square at the bottom of the text row, flush with the enter view's send side.
                    final float slot = dp(ChatActivityEnterView.DEFAULT_HEIGHT);
                    final float slotLeft = LocaleController.isRTL ? enterView.getLeft() : enterView.getRight() - slot;
                    sendSlot.set(slotLeft, pill.bottom - slot, slotLeft + slot, pill.bottom);
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
            // The accent is its own rounded bar inside the strip, so the strip's corners stay as round as the field's
            // instead of being cut square by a sliver of accent colour.
            final float accentInset = dp(STRIP_ACCENT_INSET);
            rect.set(rect.left + accentInset, rect.top + accentInset, rect.left + accentInset + dp(STRIP_ACCENT), rect.bottom - accentInset);
            fillPaint.setColor(Theme.multAlpha(primaryColor(), stripAlpha));
            canvas.drawRoundRect(rect, rect.width() / 2f, rect.width() / 2f, fillPaint);
        }

        if (drawPill && inputFactor > 0) {
            rect.set(columnLeft, pill.top + topViewHeight + dp(FIELD_INSET), columnRight, pill.bottom - dp(FIELD_INSET));
            final float fieldRadius = Math.min(dp(FIELD_RADIUS), rect.height() / 2f);
            fillPaint.setColor(Theme.multAlpha(fieldOverlay(), alpha * inputFactor));
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
    public static Drawable previewIsland(@Nullable BlurredBackgroundDrawableViewFactory factory, @Nullable View view) {
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

    /** The layout editor's field, stopping the field-to-send gap short of the send slot, {@code sendReserve} from its end. */
    public static Drawable previewField(int sendReserve) {
        final ComposerMd3Surface surface = new ComposerMd3Surface(null);
        return new PreviewDrawable() {
            @Override
            public void draw(@NonNull Canvas canvas) {
                final Rect bounds = getBounds();
                // Gravity.END puts the preview's send circle on the right even in RTL: the app does not
                // declare supportsRtl.
                surface.rect.set(bounds.left + dp(FIELD_SIDE_INSET), bounds.top + dp(FIELD_INSET), bounds.right - sendReserve - dp(FIELD_SEND_GAP), bounds.bottom - dp(FIELD_INSET));
                final float radius = Math.min(dp(FIELD_RADIUS), surface.rect.height() / 2f);
                surface.fillPaint.setColor(surface.fieldOverlay());
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
