package xyz.nextalone.nagram.ui;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewConfiguration;
import android.view.ViewParent;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RLottieImageView;
import org.telegram.ui.Components.blur3.BlurredBackgroundDrawableViewFactory;
import org.telegram.ui.Components.blur3.drawable.BlurredBackgroundDrawable;
import org.telegram.ui.Components.blur3.drawable.color.BlurredBackgroundColorProvider;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

import xyz.nextalone.nagram.NaConfig;
import xyz.nextalone.nagram.ui.composer.ComposerButtons;
import xyz.nextalone.nagram.ui.composer.ComposerLayout;

/**
 * Presentation-only row for the chat composer controls.
 *
 * The controls keep their original ownership and state transitions in
 * ChatActivityEnterView; this class only gives them stable slots.
 */
public final class ComposerToolbarLayout extends FrameLayout {

    private static final int BASE_HEIGHT = 56;
    private static final int BASE_BUTTON_SIZE = 48;
    private static final int SCALE_MIN = 75;
    private static final int SCALE_MAX = 125;
    /**
     * The spacing slider is tighter-only on purpose, and that is geometry rather than taste:
     * ControlsLayout measures its slots at dp(height()) minus its own padding, which comes out at
     * exactly the unpacked cell - BASE_BUTTON_SIZE at the current scale, before any packing factor -
     * at every scale step (100%: 56-4-4=48; 125%: 70-5-5=60; 75%: 42-3-3=36). Packing tighter leaves
     * the cell inside that box, which is why it is safe; a factor above 100% would push it outside,
     * and the row clips its children, so every button's background and ripple would come out sliced
     * flat top and bottom.
     */
    private static final int SPACING_MIN = 85;
    private static final int SPACING_MAX = 100;
    /**
     * Smallest cell the row will draw, before the glyph is even considered. Telegram's own send/mic
     * circle is 44dp and Material's minimum target is 48dp; below 40dp the press ripple starts
     * painting over the neighbouring glyph, because every one of these buttons uses
     * Theme.createSelectorDrawable, which on API 23+ is an unbounded RippleDrawable with a fixed
     * dp(20) radius that does not shrink with the view, and the slots do not clip their children.
     */
    private static final int MIN_CELL_BASE = 40;
    private static final int MIN_CELL_FLOOR = 36;
    private static final int ICON_GLYPH = 24;
    private static final int GLASS_INSET = 4;
    private static final int GLASS_DRAW_INSET = 2;
    private static final int BOUNDS_SETTLE_DELAY = 48;
    private static final int BOUNDS_SETTLE_MAX = 150;
    private static final int CONFIGURATION_LONG_PRESS_MS = 1000;
    // Has to outlast the longest control fade, otherwise the row gets re-measured mid-animation.
    private static final int RELAYOUT_SETTLE_DELAY = 300;

    private final ControlsLayout controls;
    private final CollapsingLinearLayout startSlot;
    private final HorizontalScrollView middleScrollView;
    private final CollapsingLinearLayout middleLeadingSlot;
    private final CollapsingLinearLayout orderedSlot;
    private final CollapsingLinearLayout endSlot;
    private final Map<View, Integer> configuredOrder = new HashMap<>();
    private View pinnedTrailingView;
    /**
     * deleteRichDraftButton (see addStart) is not a registered, orderable button - it is a plain
     * child appended once and left there. Its own visibility is toggled elsewhere (see
     * updateRichDraftPreview in ChatActivityEnterView, left untouched by this change) so that at
     * most it or the emoji button is showing at a time; this class does not enforce that. What it
     * does need from this class is a fixed anchor position so a later configurable button never
     * inserts on the wrong side of it - the same role pinnedTrailingView plays for the trailing zone.
     */
    private View pinnedLeadingView;
    private Runnable configurationLongPress;
    private boolean configurationLongPressTriggered;
    private float configurationLongPressX;
    private float configurationLongPressY;
    private final Rect controlsHitRect = new Rect();
    private final Runnable configurationLongPressRunnable = () -> {
        configurationLongPressTriggered = true;
        if (configurationLongPress != null) {
            configurationLongPress.run();
        }
    };

    public ComposerToolbarLayout(Context context) {
        super(context);
        setClipChildren(false);
        setClipToPadding(false);

        controls = new ControlsLayout(context);
        controls.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        controls.setLayoutDirection(LocaleController.isRTL ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
        addView(controls, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, controls.rowHeightDp(), Gravity.BOTTOM));

        startSlot = createCollapsingSlot(context);
        startSlot.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);

        middleScrollView = new ComposerMiddleScrollView(context);
        middleScrollView.setHorizontalScrollBarEnabled(false);
        middleScrollView.setHorizontalFadingEdgeEnabled(true);
        middleScrollView.setFadingEdgeLength(AndroidUtilities.dp(12));
        middleScrollView.setFillViewport(false);
        middleScrollView.setFocusable(false);
        middleScrollView.setFocusableInTouchMode(false);
        middleScrollView.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        middleScrollView.setClipChildren(true);
        middleScrollView.setClipToPadding(true);
        middleScrollView.setLayoutDirection(LocaleController.isRTL ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);

        LinearLayout middle = createLinearSlot(context);
        middle.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        middle.setLayoutDirection(LocaleController.isRTL ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
        middleScrollView.addView(middle, new HorizontalScrollView.LayoutParams(LayoutHelper.WRAP_CONTENT, LayoutHelper.MATCH_PARENT));

        middleLeadingSlot = createCollapsingSlot(context);
        middleLeadingSlot.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        orderedSlot = createCollapsingSlot(context);
        orderedSlot.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        middle.addView(middleLeadingSlot, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.MATCH_PARENT));
        middle.addView(orderedSlot, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.MATCH_PARENT));

        endSlot = createCollapsingSlot(context);
        endSlot.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);

        controls.setSlots(startSlot, middleScrollView, middle, endSlot);
        middle.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            if (right - left != oldRight - oldLeft) {
                pinMiddleToStart();
            }
        });
        middleScrollView.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            if (right - left != oldRight - oldLeft) {
                pinMiddleToStart();
            }
        });
        middleScrollView.post(this::pinMiddleToStart);
    }

    public void setConfigurationLongPress(Runnable listener) {
        configurationLongPress = listener;
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        if (configurationLongPressTriggered && event.getActionMasked() != MotionEvent.ACTION_DOWN) {
            if (event.getActionMasked() == MotionEvent.ACTION_UP || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                removeCallbacks(configurationLongPressRunnable);
            }
            return true;
        }
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                configurationLongPressTriggered = false;
                configurationLongPressX = event.getX();
                configurationLongPressY = event.getY();
                removeCallbacks(configurationLongPressRunnable);
                boolean consumed = super.dispatchTouchEvent(event);
                // Only arm the timer once super confirms this DOWN actually became a
                // touch target here - that's the framework's own guarantee that an UP
                // or CANCEL will come back to cancel it. A DOWN nobody consumed never
                // gets redelivered, so arming on bounds alone left the timer stuck
                // armed whenever the tap missed every button (dead space, gaps between
                // slots, the transparent margin around the capsule).
                if (configurationLongPress != null && consumed) {
                    controls.getHitRect(controlsHitRect);
                    if (controls.getVisibility() == View.VISIBLE
                            && controlsHitRect.contains((int) configurationLongPressX, (int) configurationLongPressY)) {
                        postDelayed(configurationLongPressRunnable, CONFIGURATION_LONG_PRESS_MS);
                    }
                }
                return consumed;
            }
            case MotionEvent.ACTION_MOVE:
                if (!configurationLongPressTriggered
                        && (Math.abs(event.getX() - configurationLongPressX) > ViewConfiguration.get(getContext()).getScaledTouchSlop()
                        || Math.abs(event.getY() - configurationLongPressY) > ViewConfiguration.get(getContext()).getScaledTouchSlop())) {
                    removeCallbacks(configurationLongPressRunnable);
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                removeCallbacks(configurationLongPressRunnable);
                break;
        }
        return super.dispatchTouchEvent(event);
    }

    @Override
    protected void onDetachedFromWindow() {
        removeCallbacks(configurationLongPressRunnable);
        super.onDetachedFromWindow();
    }

    public void attachGlass(BlurredBackgroundDrawableViewFactory factory, BlurredBackgroundColorProvider colorProvider) {
        controls.attachGlass(factory, colorProvider);
    }

    public void updateColors() {
        controls.updateColors();
    }

    public void addStart(View view) {
        AndroidUtilities.removeFromParent(view);
        // Always appended, never re-sorted against the configurable buttons: it stands in for
        // whichever of them the user is not currently looking at (see updateRichDraftPreview in
        // ChatActivityEnterView), so it needs a fixed slot of its own rather than a config order.
        pinnedLeadingView = view;
        startSlot.addView(view, LayoutHelper.createLinear(buttonSize(), buttonSize()));
        applyIconBox(null, view);
    }

    /**
     * Places a button the user is allowed to move. The zone and the position inside it come from the
     * saved layout, resolved as each view arrives: schedule, the send-as avatar and the bot pill are
     * all created long after the constructor, so there is no single moment where everything is present
     * and the whole row could be sorted in one pass.
     */
    public void addConfigurable(String key, View view) {
        addConfigurable(key, view, ComposerLayout.zoneOf(key), ComposerLayout.indexOf(key), ComposerLayout.trailingKey());
    }

    /**
     * Places a button against a caller supplied layout rather than the saved one. The settings
     * preview needs this: it renders the arrangement being dragged, which is deliberately not
     * written to config until the drag ends, so resolving placement from the stored string there
     * would show the previous layout for the whole gesture. The order is local to the supplied
     * zone, matching ComposerLayout.indexOf().
     */
    public void addConfigurable(String key, View view, int zone, int order, String trailingKey) {
        AndroidUtilities.removeFromParent(view);
        configuredOrder.put(view, order);
        // One sizing gate for every configurable button, whatever zone it lands in. The assets are all
        // 24dp vectors, but their ink fills the canvas by different amounts and sits off-centre by a
        // fraction of a dp, so the box centers each glyph in its cell; the per-glyph optical
        // correction is baked into the fork-owned vector, not applied here.
        applyIconBox(key, view);
        if (zone == ComposerButtons.ZONE_HIDDEN) {
            // Left without a parent rather than set GONE: the enter view reads these buttons' visibility
            // to decide its own geometry, so a removed button has to keep whatever visibility it had.
            return;
        }
        if (zone == ComposerButtons.ZONE_START) {
            startSlot.addView(view, insertIndex(startSlot, order, startContextIndex()), LayoutHelper.createLinear(buttonSize(), buttonSize()));
            return;
        }
        if (zone == ComposerButtons.ZONE_END) {
            if (key.equals(trailingKey)) {
                pinnedTrailingView = view;
                controls.setTrailingPinnedView(view);
                endSlot.addView(view, LayoutHelper.createLinear(buttonSize(), buttonSize()));
            } else {
                endSlot.addView(view, insertIndex(endSlot, order, endContextIndex()), LayoutHelper.createLinear(buttonSize(), buttonSize()));
            }
            return;
        }
        orderedSlot.addView(view, insertIndex(orderedSlot, order, orderedSlot.getChildCount()), LayoutHelper.createLinear(buttonSize(), buttonSize()));
        middleScrollView.post(this::pinMiddleToStart);
    }

    // Walks the siblings already in the slot and stops in front of the first one the layout puts
    // after this button. Views with no recorded order (the attach group) are left where they are.
    private int insertIndex(ViewGroup slot, int order, int limit) {
        for (int i = 0; i < limit && i < slot.getChildCount(); i++) {
            Integer other = configuredOrder.get(slot.getChildAt(i));
            if (other != null && other > order) {
                return i;
            }
        }
        return limit;
    }

    public void addMiddleLeading(View view, int width, int height, float horizontalMargin, float verticalMargin, int index) {
        AndroidUtilities.removeFromParent(view);
        middleLeadingSlot.addView(view, Math.min(index, middleLeadingSlot.getChildCount()),
                LayoutHelper.createLinear(width, height, horizontalMargin, verticalMargin, horizontalMargin, verticalMargin));
        middleScrollView.post(this::pinMiddleToStart);
    }

    // The attach group is not user-placeable, so it stays at the head of the trailing zone and the
    // configured buttons order themselves after it.
    public void addContextGroup(View view) {
        AndroidUtilities.removeFromParent(view);
        endSlot.addView(view, 0, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.MATCH_PARENT));
        controls.setTrailingContextGroup(view);
    }

    private int endContextIndex() {
        int index = pinnedTrailingView != null ? endSlot.indexOfChild(pinnedTrailingView) : -1;
        return index < 0 ? endSlot.getChildCount() : index;
    }

    // Mirrors endContextIndex(): caps where a configurable leading button can land so it never
    // inserts past the delete-draft button's reserved slot (see addStart).
    private int startContextIndex() {
        int index = pinnedLeadingView != null ? startSlot.indexOfChild(pinnedLeadingView) : -1;
        return index < 0 ? startSlot.getChildCount() : index;
    }

    public void addReplacement(View view) {
        AndroidUtilities.removeFromParent(view);
        addView(view, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.BOTTOM));
    }

    public void setControlsVisible(boolean visible) {
        controls.setPanelVisible(visible);
    }

    private void pinMiddleToStart() {
        View child = middleScrollView.getChildAt(0);
        if (child == null) {
            return;
        }
        int scrollX = LocaleController.isRTL
                ? Math.max(0, child.getWidth() - middleScrollView.getWidth())
                : 0;
        if (middleScrollView.getScrollX() != scrollX) {
            middleScrollView.scrollTo(scrollX, 0);
        }
    }

    private static SlidingLinearLayout createLinearSlot(Context context) {
        SlidingLinearLayout slot = new SlidingLinearLayout(context);
        slot.setLayoutDirection(LocaleController.isRTL ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
        return slot;
    }

    private static CollapsingLinearLayout createCollapsingSlot(Context context) {
        CollapsingLinearLayout slot = new CollapsingLinearLayout(context);
        slot.setLayoutDirection(LocaleController.isRTL ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
        return slot;
    }

    /**
     * The user's panel scale as a factor, clamped to the slider's own range so a hand-edited config
     * value cannot produce a panel that no longer fits the composer.
     */
    public static float scale() {
        return scalePercent() / 100f;
    }

    /** The user's panel scale as a whole percent, clamped to the slider's own range. */
    public static int scalePercent() {
        int percent = NaConfig.INSTANCE.getComposerToolbarScale().Int();
        return Math.max(SCALE_MIN, Math.min(SCALE_MAX, percent));
    }

    /**
     * How densely the cells are packed, as a whole percent. Independent of {@link #scale()}: the
     * scale grows the row, the cells and the glyphs together and so can never change how much slack
     * a cell has around its glyph, which is the only thing that sets the gap between two icons.
     *
     * <p>Snapped up to the lowest step that is not swallowed by the cell floor. The settings slider
     * blocks the same steps through SlideIntChooseView.setMinValueAllowed, and that clamp never fires
     * the change callback, so a stored value below what is reachable legitimately survives in config -
     * the row and the slider both read it through this one method so they cannot disagree.
     */
    public static int spacingPercent() {
        int percent = NaConfig.INSTANCE.getComposerToolbarSpacing().Int();
        percent = Math.max(SPACING_MIN, Math.min(SPACING_MAX, percent));
        return Math.max(lowestUsableSpacing(scalePercent()), percent);
    }

    /**
     * Row height in dp for the <em>current</em> scale. This reads the live setting, so it can move
     * under an existing view the moment the slider changes. A view that has to hold one geometry for
     * its whole life - ControlsLayout, whose capsule radius and measured height must stay half-and-
     * whole of each other - snapshots this once at construction instead of calling it again per pass;
     * see ControlsLayout's geometry fields. The scale only changes while the settings screen is open
     * and closing it rebuilds every chat, so a fresh view picks up the new value together with the
     * layout params it is given.
     */
    public static int height() {
        return Math.round(BASE_HEIGHT * scale());
    }

    /**
     * Button cell size in dp - the row's scale times the packing factor, held above a floor. The
     * floor is what keeps the two independent: applyIconBox sizes the glyph from scale() alone but
     * the cell from both, then absorbs any shortfall with a max(0, ...) on the leftover padding, so
     * without it a tight enough cell would quietly render the widest glyph under its own keyline -
     * no crash, nothing logged.
     */
    public static int buttonSize() {
        float scale = scale();
        return Math.max(minCellDp(scale), packedCellDp(scale, spacingPercent()));
    }

    private static int packedCellDp(float scale, int spacingPercent) {
        return Math.round(BASE_BUTTON_SIZE * scale * (spacingPercent / 100f));
    }

    /**
     * Smallest cell allowed at a given row scale. Two terms: a proportional one that keeps the touch
     * target and its fixed-radius ripple sane as the row shrinks, and an absolute one that stops the
     * proportional term following a small row all the way down.
     */
    private static int minCellDp(float scale) {
        int floor = Math.max(MIN_CELL_FLOOR, Math.round(MIN_CELL_BASE * scale));
        // The glyph the cell has to hold is the real constraint, and it is read from the icon table
        // rather than typed here so that measuring a sparser glyph than today's widest moves this
        // floor with it instead of starving that glyph.
        int widestGlyph = (int) Math.ceil(ICON_GLYPH * ComposerButtons.maxIconScale() * scale);
        return Math.max(floor, widestGlyph);
    }

    /**
     * Whether a packing step actually reaches the row at this scale, or is swallowed at one end or
     * the other. A step that cannot move anything must not be offered: the slider would still slide
     * and still buzz while changing nothing on screen.
     *
     * <p>Two ways to be swallowed, and both have to be tested here rather than left to whatever
     * grid the caller happens to walk. Under the cell floor is the obvious one. The other is a step
     * that rounds straight back to the unpacked cell - at 75% scale the floor and the unpacked cell
     * are the same 36dp, so 99% packing rounds to 36 and passes the floor test while drawing exactly
     * what 100% draws.
     */
    private static boolean spacingIsUsable(int scalePercent, int spacingPercent) {
        float scale = scalePercent / 100f;
        int cell = packedCellDp(scale, spacingPercent);
        return cell >= minCellDp(scale) && cell < packedCellDp(scale, SPACING_MAX);
    }

    /**
     * The tightest packing that still reaches the row at this scale.
     *
     * <p>Walked a percent at a time, matching what the slider delivers. The cell is a rounded dp, so
     * the first packing that clears the floor is usually not a multiple of five - at 85% scale it is
     * 88 - and a coarser walk would grey out steps the row can actually draw.
     */
    public static int lowestUsableSpacing(int scalePercent) {
        for (int percent = SPACING_MIN; percent < SPACING_MAX; percent++) {
            if (spacingIsUsable(scalePercent, percent)) {
                return percent;
            }
        }
        return SPACING_MAX;
    }

    /**
     * Places a panel button in the shared cell box at natural size. For the configurable format and
     * text buttons whose glyph needs an optical correction, that correction is baked into the
     * fork-owned vector asset (a group transform), not applied here - so the box is the same for
     * every button and this is all the enter view has to call.
     */
    public static void applyPanelIconBox(View view) {
        applyPanelIconBox(view, scale());
    }

    /**
     * As {@link #applyPanelIconBox(View)}, but for the core buttons whose drawable is a raster or a
     * CrossOutDrawable that cannot be re-cropped as a vector. The correction is keyed on the drawable
     * resource in {@link ComposerButtons#iconScaleForResource(int)}, so no bare float is typed at the
     * call site.
     */
    public static void applyPanelIconBox(View view, int resourceId) {
        applyPanelIconBox(view, ComposerButtons.iconScaleForResource(resourceId) * scale());
    }

    private static void applyPanelIconBox(View view, float iconScale) {
        applyIconBox(view, buttonSize(), iconScale);
    }

    private static int glassInset() {
        return Math.max(1, Math.round(GLASS_INSET * scale()));
    }

    /**
     * Vertical inset of the drawn bubbles inside the row. Paired with the attached glass padding on
     * the vertical axis only: each bubble's painted top and bottom land at glassDrawInset() +
     * glassInset() from the row edge. The horizontal edges do not use this - a bubble's outer flush
     * edge reaches the row edge and its inner edges are set from live child geometry (see drawGlass),
     * so this term is vertical-only and does not decide left/right alignment with the input pill.
     */
    private static int glassDrawInset() {
        return Math.max(1, Math.round(GLASS_DRAW_INSET * scale()));
    }

    private static void applyIconBox(View view, int cellDp, float scale) {
        int cellPx = Math.round(AndroidUtilities.dpf2(cellDp));
        // Kept in pixel space rather than rounded to whole dp: at the odd steps the scaled glyph
        // lands on a fraction of a dp, and rounding it first would bias the two insets apart and
        // push the icon off center inside its cell.
        int glyphPx = Math.round(AndroidUtilities.dpf2(ICON_GLYPH) * scale);
        int remainingPx = Math.max(0, cellPx - glyphPx);
        int startInset = remainingPx / 2;
        int endInset = remainingPx - startInset;
        if (view instanceof RLottieImageView) {
            // RLottieImageView never sets its own scale type, so it inherits ImageView's FIT_CENTER
            // and the padding alone centers the composition - no setScaleType needed or wanted.
            view.setPadding(startInset, startInset, endInset, endInset);
            return;
        }
        if (!(view instanceof ImageView)) {
            return;
        }
        ImageView icon = (ImageView) view;
        // FIT_CENTER is what gives the padding below its meaning: it makes the drawable fill the box
        // the insets leave, so the box is the glyph's size. Under CENTER the drawable would draw at its
        // intrinsic size and every scale here would silently become a no-op - no crash, nothing logged.
        // Set it here, last, rather than trusting each call site to have chosen the right scale type.
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        icon.setPadding(startInset, startInset, endInset, endInset);
    }

    private static void applyIconBox(String key, View view) {
        // Two deliberate choices live here. First, the optical correction is expressed as a wider or
        // narrower inset rather than a view scale: these buttons carry a press animator that drives
        // scaleX/scaleY, so a scale set here would be animated away on the first tap. Second, the
        // resulting padding is absolute (setPadding, not setPaddingRelative) even though the slots are
        // laid out RTL. That is correct and must stay: none of these drawables is autoMirrored, so the
        // ink renders the same pixels in RTL, and the per-glyph offset baked into the fork vectors is
        // physical canvas geometry, not reading order. Switching to setPaddingRelative would mirror the
        // inset in RTL and reintroduce exactly the decentring this change removes.
        applyPanelIconBox(view, ComposerButtons.iconScaleForKey(key) * scale());
    }

    // The panel and its slots react to the same layout passes, so they share one settle schedule and start
    // moving on the same frame. Each further change pushes the start back, capped so a slot that keeps
    // resizing cannot hold everything frozen.
    private static long getSettleDelay(long holdStartTime) {
        long held = SystemClock.elapsedRealtime() - holdStartTime;
        return Math.max(0, Math.min(BOUNDS_SETTLE_DELAY, BOUNDS_SETTLE_MAX - held));
    }

    private static final class ControlsLayout extends FrameLayout {
        private CollapsingLinearLayout startSlot;
        private HorizontalScrollView middleScrollView;
        private LinearLayout middleContent;
        private CollapsingLinearLayout endSlot;
        // The row is drawn as up to five separate glass bubbles instead of one full-width capsule, one
        // per child group that can collapse or slide independently. Left to right in LTR: the leading
        // zone, the scrolling middle group, the attach context group, the configurable trailing buttons
        // and the pinned trailing anchor. Each has its own drawable (see attachGlass) so its own backdrop
        // sample and render node stay correct; a single drawable re-bounded across the rects would force a
        // chat-wide re-capture every frame.
        private static final int BUBBLE_LEADING = 0;
        private static final int BUBBLE_MIDDLE = 1;
        private static final int BUBBLE_CONTEXT = 2;
        private static final int BUBBLE_CONFIGURABLE = 3;
        private static final int BUBBLE_PINNED = 4;
        private static final int BUBBLE_COUNT = 5;
        private BlurredBackgroundDrawable[] bubbles;
        // The two trailing sub-groups the drawGlass split needs to tell apart inside endSlot: the attach
        // context group (endSlot child 0, its own chat-type lifecycle) and the pinned trailing anchor.
        // Everything else in endSlot is the configurable bubble.
        private View trailingContextGroup;
        private View trailingPinnedView;
        // Reused across dispatchDraw so no frame allocates: each bubble's content span in this view's
        // coordinates, whether it has anything to draw, and the alpha its group is currently faded to.
        private final float[] bubbleLeft = new float[BUBBLE_COUNT];
        private final float[] bubbleRight = new float[BUBBLE_COUNT];
        private final boolean[] bubbleOccupied = new boolean[BUBBLE_COUNT];
        private final int[] bubbleAlpha = new int[BUBBLE_COUNT];
        // Whether each bubble actually painted last frame. A bubble that stops painting has its bounds
        // cleared to empty once, so getVisiblePositions stops reporting a phantom backdrop region for it;
        // this fires only on the occupied->hidden edge, never per frame, so it does not churn render nodes.
        private final boolean[] bubbleDrawn = new boolean[BUBBLE_COUNT];
        // One scale sample for this view's whole life, captured in the constructor. Everything the row
        // draws with - its height, the button-box inset, both glass draw insets and the capsule radius -
        // comes off these three dp values, so a relayout can never pair a freshly read height with a
        // radius that was sampled at some other scale (which is how the end caps would stop being
        // semicircular in the deferred-rebuild window after the slider moves). The scale only moves
        // while the settings screen is open and closing it rebuilds every chat, so a live view keeps
        // one coherent geometry and a rebuilt view takes the new scale. It stays consistent with the
        // layout params ChatActivityEnterView sizes this toolbar with because composerToolbarHeight()
        // there reads height() in the same synchronous construction - no config write can land between
        // the two, and a rebuild replaces both together. These hold dp with only the user's scale
        // setting frozen in. onMeasure re-converts geometryHeightDp to px every pass, so the row height
        // still tracks a density change; the button-box padding, glass padding and draw inset are
        // converted to px once (in the constructor and attachGlass), exactly as they were before this
        // change - a config change that alters density recreates the view, which is what re-reads them.
        private final int geometryHeightDp;
        private final int geometryInsetDp;
        private final int geometryDrawInsetDp;
        // The exact pixel geometry handed to every bubble drawable at attachGlass, derived from the
        // snapshot above. glassPaddingPx is the value passed to each drawable's setPadding; drawGlass
        // cancels it only on the bubbles' outer flush edges by the same field - never a fresh
        // glassInset() - so the two cannot drift, and on the inner edges it is left in place, which is
        // what opens the 2 x glassPaddingPx gap between neighbouring bubbles.
        private int glassPaddingPx;
        private int glassDrawInsetPx;
        private ValueAnimator boundsAnimator;
        private int measuredPanelWidth = -1;
        private boolean laidOut;
        private boolean availableWidthChanged;
        private boolean holdingBounds;
        private long holdStartTime;
        private int previousRightAnchoredLeft;
        private boolean hasRightAnchoredLeft;
        private View rightAnchoredSlot;
        private float pendingEndShift;
        private boolean resumingMidAnimation;
        private final Runnable boundsAnimationStarter = this::startBoundsAnimation;

        ControlsLayout(Context context) {
            super(context);
            setClipChildren(true);
            setClipToPadding(true);
            // Snapshot the scale-derived geometry once, before anything measures, lays out or draws, so
            // every later read is the same sample (see the field comment).
            geometryHeightDp = height();
            geometryInsetDp = glassInset();
            geometryDrawInsetDp = glassDrawInset();
            // The button box: the first and last button sit this far inside the row, the same way the
            // input pill has padding before its "Message" hint. Kept at glassInset() (4dp at 100%) and
            // proportional so a button never renders under a bubble's rounded end. Each bubble's own
            // edges are set separately in drawGlass - outer edges flush, inner edges from live child
            // geometry, lifted vertically - so this padding is not the thing that decides where the
            // glass is drawn.
            setPaddingRelative(AndroidUtilities.dp(geometryInsetDp), AndroidUtilities.dp(geometryInsetDp), AndroidUtilities.dp(geometryInsetDp), AndroidUtilities.dp(geometryInsetDp));
        }

        int rowHeightDp() {
            return geometryHeightDp;
        }

        void setSlots(CollapsingLinearLayout startSlot, HorizontalScrollView middleScrollView, LinearLayout middleContent, CollapsingLinearLayout endSlot) {
            this.startSlot = startSlot;
            this.middleScrollView = middleScrollView;
            this.middleContent = middleContent;
            this.endSlot = endSlot;

            addView(startSlot, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, geometryHeightDp, Gravity.LEFT));
            addView(middleScrollView, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, geometryHeightDp, Gravity.LEFT));
            addView(endSlot, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, geometryHeightDp, Gravity.LEFT));
        }

        void attachGlass(BlurredBackgroundDrawableViewFactory factory, BlurredBackgroundColorProvider colorProvider) {
            glassPaddingPx = AndroidUtilities.dp(geometryInsetDp);
            glassDrawInsetPx = AndroidUtilities.dp(geometryDrawInsetDp);
            // One drawable per bubble, each created with this view so viewPositionWatcher.subscribe wires
            // it to the shared backdrop; multiple subscriptions of the same view are supported, and each
            // drawable then samples the region under its own bounds. Radius large enough that every
            // drawable clamps it to half its own painted height, so a one-item bubble comes out a circle
            // and a wider one keeps semicircular ends - all off the construction snapshot, so radius and
            // the height it is drawn against are always the same scale. Padding and drawInset are stored
            // in px here for drawGlass.
            int radius = AndroidUtilities.dp((geometryHeightDp - geometryInsetDp) / 2f);
            bubbles = new BlurredBackgroundDrawable[BUBBLE_COUNT];
            for (int i = 0; i < BUBBLE_COUNT; i++) {
                BlurredBackgroundDrawable drawable = factory.create(this, colorProvider);
                drawable.setRadius(radius);
                drawable.setPadding(glassPaddingPx);
                bubbles[i] = drawable;
            }
            invalidate();
        }

        void updateColors() {
            if (bubbles == null) {
                return;
            }
            for (BlurredBackgroundDrawable drawable : bubbles) {
                if (drawable != null) {
                    drawable.updateColors();
                }
            }
            invalidate();
        }

        void setTrailingContextGroup(View view) {
            trailingContextGroup = view;
        }

        void setTrailingPinnedView(View view) {
            trailingPinnedView = view;
        }

        void setPanelVisible(boolean visible) {
            int visibility = visible ? VISIBLE : GONE;
            if (getVisibility() != visibility) {
                if (!visible) {
                    cancelBoundsAnimation();
                    resetBounds();
                    SlidingLinearLayout.cancelSlidesIn(this);
                }
                setVisibility(visibility);
            }
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int availableWidth = MeasureSpec.getSize(widthMeasureSpec);
            int height = AndroidUtilities.dp(geometryHeightDp);
            int contentHeight = Math.max(0, height - getPaddingTop() - getPaddingBottom());
            int heightSpec = MeasureSpec.makeMeasureSpec(contentHeight, MeasureSpec.EXACTLY);
            int unboundedWidthSpec = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED);

            startSlot.measure(unboundedWidthSpec, heightSpec);
            endSlot.measure(unboundedWidthSpec, heightSpec);
            middleContent.measure(unboundedWidthSpec, heightSpec);

            int startWidth = startSlot.getMeasuredWidth();
            int endWidth = endSlot.getMeasuredWidth();
            int middleWidth = middleContent.getMeasuredWidth();
            int horizontalPadding = getPaddingLeft() + getPaddingRight();
            // Always take the full width the row offers instead of shrink-wrapping the content, so the
            // leading and trailing bubbles can reach the row edges and line up with the main input pill
            // above, and the trailing zone stays anchored right at every button count.
            int panelWidth = availableWidth;
            int middleViewportWidth = Math.min(middleWidth, Math.max(0, panelWidth - horizontalPadding - startWidth - endWidth));

            middleScrollView.measure(MeasureSpec.makeMeasureSpec(middleViewportWidth, MeasureSpec.EXACTLY), heightSpec);
            // A control fading out inside the middle group sits past the viewport edge, so only clip once the
            // group actually scrolls - that is the case the clip exists for.
            boolean clipMiddle = middleWidth > middleViewportWidth;
            if (middleScrollView.getClipChildren() != clipMiddle) {
                middleScrollView.setClipChildren(clipMiddle);
            }
            if (laidOut && measuredPanelWidth != panelWidth) {
                // The panel width only moves now when the row itself is resized (rotation, multi-window).
                // The layout pass settles the buttons on the new width, so snap the capsule to it rather
                // than easing a stale edge out behind buttons that have already arrived.
                availableWidthChanged = true;
            }
            measuredPanelWidth = panelWidth;
            setMeasuredDimension(panelWidth, height);
        }

        @Override
        protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
            int startWidth = startSlot.getMeasuredWidth();
            int middleWidth = middleScrollView.getMeasuredWidth();
            int endWidth = endSlot.getMeasuredWidth();
            int contentLeft = getPaddingLeft();
            int contentRight = getWidth() - getPaddingRight();
            int contentTop = getPaddingTop();
            int contentBottom = getHeight() - getPaddingBottom();
            if (getLayoutDirection() == LAYOUT_DIRECTION_RTL) {
                // Leading zone (startSlot) sits at the right edge, trailing (endSlot) at the left.
                // Anchor the scrolling middle group to the leading zone so the slack on a sparse row
                // falls between the middle group and the trailing zone, the same side it does in LTR.
                endSlot.layout(contentLeft, contentTop, contentLeft + endWidth, contentBottom);
                middleScrollView.layout(contentRight - startWidth - middleWidth, contentTop, contentRight - startWidth, contentBottom);
                startSlot.layout(contentRight - startWidth, contentTop, contentRight, contentBottom);
            } else {
                startSlot.layout(contentLeft, contentTop, contentLeft + startWidth, contentBottom);
                middleScrollView.layout(contentLeft + startWidth, contentTop, contentLeft + startWidth + middleWidth, contentBottom);
                endSlot.layout(contentRight - endWidth, contentTop, contentRight, contentBottom);
            }
            // Whichever slot is pinned to the trailing edge is laid out against its new anchor the
            // instant a control claims or frees a slot, so it jumps there in a single frame. Hand its
            // catch-up to the bounds animator below so it eases back over the settle window instead.
            View trailingSlot = getLayoutDirection() == LAYOUT_DIRECTION_RTL ? startSlot : endSlot;
            if (trailingSlot != rightAnchoredSlot) {
                if (rightAnchoredSlot != null) {
                    rightAnchoredSlot.setTranslationX(0);
                }
                rightAnchoredSlot = trailingSlot;
                hasRightAnchoredLeft = false;
            }
            int anchoredLeft = trailingSlot.getLeft();
            int endShift = hasRightAnchoredLeft ? previousRightAnchoredLeft - anchoredLeft : 0;
            previousRightAnchoredLeft = anchoredLeft;
            hasRightAnchoredLeft = true;
            if (!laidOut) {
                laidOut = true;
            } else if (availableWidthChanged) {
                // Rotation or multi-window: the row and the anchored slot are already at their new
                // positions, so settle the capsule and the slot on the new bounds without a slide.
                availableWidthChanged = false;
                snapBounds();
            } else if (endShift != 0) {
                // The trailing group jumped to a new anchor while the panel width held (a control
                // claimed or freed its slot). Ease it back so it and the buttons beside it settle
                // together instead of popping.
                holdTrailingSlide(endShift);
                AndroidUtilities.runOnUIThread(boundsAnimationStarter, resumingMidAnimation ? 0 : getRemainingSettleDelay());
            } else if (!holdingBounds) {
                trailingSlot.setTranslationX(0);
            }
        }

        @Override
        protected void dispatchDraw(Canvas canvas) {
            drawGlass(canvas);
            super.dispatchDraw(canvas);
        }

        private void drawGlass(Canvas canvas) {
            if (bubbles == null || getWidth() <= 0 || getHeight() <= 0) {
                return;
            }
            // Same global vertical guard the single capsule used: below this the painted band would
            // invert once each drawable insets its padding. Cheap to test once for the whole row.
            if (getHeight() <= (glassDrawInsetPx + glassPaddingPx) * 2) {
                return;
            }
            computeBubbleSpans();
            int top = glassDrawInsetPx;
            int bottom = getHeight() - glassDrawInsetPx;
            boolean rtl = getLayoutDirection() == LAYOUT_DIRECTION_RTL;
            // The leading zone owns the row's leading edge and the trailing anchor its trailing edge;
            // those two outer edges repeat the capsule's flush arithmetic so they still line up with the
            // input pill above. If the trailing zone is empty the row simply loses its trailing flush
            // (accepted), which trailingEdgeRole reports as -1.
            int trailingEdgeRole = trailingEdgeRole();
            for (int role = 0; role < BUBBLE_COUNT; role++) {
                if (!bubbleOccupied[role]) {
                    hideBubble(role);
                    continue;
                }
                boolean leadingEdge = role == BUBBLE_LEADING;
                boolean trailingEdge = role == trailingEdgeRole;
                boolean flushLeft = rtl ? trailingEdge : leadingEdge;
                boolean flushRight = rtl ? leadingEdge : trailingEdge;
                // Outer flush edge: -/+ glassPaddingPx exactly cancels the padding the drawable adds back
                // (boundsWithPadding.inset in BlurredBackgroundDrawable), so the painted edge reaches the
                // row edge by arithmetic. Inner edge: bounds sit at the content edge and the drawable's
                // own padding insets glassPaddingPx into it, which is the half-gap; two neighbours each
                // contribute one, opening a 2 x glassPaddingPx gap. Inflating an inner edge as well would
                // eat that gap.
                int left = flushLeft ? -glassPaddingPx : Math.round(bubbleLeft[role]);
                int right = flushRight ? getWidth() + glassPaddingPx : Math.round(bubbleRight[role]);
                if (right - left <= glassPaddingPx * 2) {
                    // Painted width would be zero or negative once padding is applied. Skip so a collapsed
                    // bubble never paints a sliver; not drawing also removes its side of the adjacent gap.
                    hideBubble(role);
                    continue;
                }
                BlurredBackgroundDrawable drawable = bubbles[role];
                drawable.setAlpha(bubbleAlpha[role]);
                drawable.setBounds(left, top, right, bottom);
                drawable.draw(canvas);
                bubbleDrawn[role] = true;
            }
        }

        // Drops a bubble that is not painting this frame back to empty bounds, but only on the frame it
        // stops - Drawable.setBounds short-circuits an unchanged rect, so a bubble that was already hidden
        // costs nothing and never re-triggers onBoundsChange.
        private void hideBubble(int role) {
            if (bubbleDrawn[role]) {
                bubbles[role].setBounds(0, 0, 0, 0);
                bubbleDrawn[role] = false;
            }
        }

        // Fills bubbleLeft/bubbleRight/bubbleOccupied/bubbleAlpha for this frame from the live child
        // geometry, so every bubble follows its buttons through the existing slot slides and the trailing
        // catch-up with no animator of its own. All reused fields, no allocation.
        private void computeBubbleSpans() {
            setBubbleSpan(BUBBLE_LEADING, startSlot);
            setBubbleSpan(BUBBLE_MIDDLE, middleScrollView);
            setBubbleSpan(BUBBLE_CONTEXT, trailingContextGroup);
            setBubbleSpan(BUBBLE_PINNED, trailingPinnedView);
            computeConfigurableSpan();
        }

        private void setBubbleSpan(int role, View view) {
            if (view == null || !isBubbleContent(view)) {
                bubbleOccupied[role] = false;
                return;
            }
            float left = descendantLeft(view);
            bubbleLeft[role] = left;
            bubbleRight[role] = left + view.getWidth();
            bubbleAlpha[role] = alphaOf(view);
            bubbleOccupied[role] = true;
        }

        // The configurable bubble spans every trailing button that is not the attach context group or the
        // pinned anchor, from the leftmost occupied one to the rightmost, so it hugs its content and
        // collapses with it.
        private void computeConfigurableSpan() {
            bubbleOccupied[BUBBLE_CONFIGURABLE] = false;
            if (endSlot == null) {
                return;
            }
            float min = Float.MAX_VALUE;
            float max = -Float.MAX_VALUE;
            int alpha = 0;
            for (int i = 0; i < endSlot.getChildCount(); i++) {
                View child = endSlot.getChildAt(i);
                if (child == trailingContextGroup || child == trailingPinnedView) {
                    continue;
                }
                if (!isBubbleContent(child)) {
                    continue;
                }
                float left = descendantLeft(child);
                float right = left + child.getWidth();
                if (left < min) {
                    min = left;
                }
                if (right > max) {
                    max = right;
                }
                // The most opaque button drives the pill: a single button fading in makes the pill fade
                // with it, while a button fading in beside an opaque one leaves the pill solid.
                alpha = Math.max(alpha, alphaOf(child));
            }
            if (max > min) {
                bubbleLeft[BUBBLE_CONFIGURABLE] = min;
                bubbleRight[BUBBLE_CONFIGURABLE] = max;
                bubbleAlpha[BUBBLE_CONFIGURABLE] = alpha;
                bubbleOccupied[BUBBLE_CONFIGURABLE] = true;
            }
        }

        // Whichever present bubble sits against the row's trailing edge: the pinned anchor, else the
        // configurable group, else the context group. -1 when the whole trailing zone is empty.
        private int trailingEdgeRole() {
            if (bubbleOccupied[BUBBLE_PINNED]) {
                return BUBBLE_PINNED;
            }
            if (bubbleOccupied[BUBBLE_CONFIGURABLE]) {
                return BUBBLE_CONFIGURABLE;
            }
            if (bubbleOccupied[BUBBLE_CONTEXT]) {
                return BUBBLE_CONTEXT;
            }
            return -1;
        }

        // The left edge of a descendant in this view's coordinates, following getLeft() + translationX up
        // to this layout. getLeft() already carries the slot slides (SlidingLinearLayout uses
        // offsetLeftAndRight), and endSlot's translationX carries the trailing catch-up, so a bubble
        // tracks exactly where its buttons are drawn.
        private float descendantLeft(View view) {
            float x = 0f;
            View current = view;
            while (current != null && current != this) {
                x += current.getLeft() + current.getTranslationX();
                ViewParent parent = current.getParent();
                current = parent instanceof View ? (View) parent : null;
            }
            return x;
        }

        private static boolean isBubbleContent(View view) {
            return view.getVisibility() == VISIBLE && view.getAlpha() > 0f && view.getWidth() > 0;
        }

        private static int alphaOf(View view) {
            float alpha = view.getAlpha();
            if (alpha <= 0f) {
                return 0;
            }
            if (alpha >= 1f) {
                return 255;
            }
            return Math.round(alpha * 255);
        }

        // Hold the pinned trailing group where it was drawn instead of letting it pop to its new anchor.
        // Erasing a draft flips two slots a frame or two apart (attach arrives, the action beside it
        // leaves), and each flip re-layouts; waiting for the anchor to settle turns what used to be two
        // consecutive slides into one. Only the slot is held and eased; the trailing bubbles are derived
        // from the slot's live geometry every frame, so they ride the same ease with no clock of their own.
        private void holdTrailingSlide(int endShift) {
            resumingMidAnimation = boundsAnimator != null;
            cancelBoundsAnimation();
            if (holdStartTime == 0) {
                holdStartTime = SystemClock.elapsedRealtime();
            }
            holdingBounds = true;
            // A hold arriving mid-animation stacks on whatever is left of the last one, so the group keeps
            // the distance it still had to cover instead of snapping back to the new anchor.
            pendingEndShift = rightAnchoredSlot.getTranslationX() + endShift;
            rightAnchoredSlot.setTranslationX(pendingEndShift);
        }

        // Each further change pushes the start back, so cap the wait: a slot that keeps resizing must not
        // leave the trailing group frozen.
        private long getRemainingSettleDelay() {
            return getSettleDelay(holdStartTime);
        }

        private void startBoundsAnimation() {
            holdingBounds = false;
            holdStartTime = 0;
            resumingMidAnimation = false;
            float initialEndShift = pendingEndShift;
            View anchored = rightAnchoredSlot;
            pendingEndShift = 0;
            if (initialEndShift == 0) {
                setAnchoredTranslation(anchored, 0);
                return;
            }
            setAnchoredTranslation(anchored, initialEndShift);
            boundsAnimator = ValueAnimator.ofFloat(0.0f, 1.0f);
            boundsAnimator.setDuration(220);
            boundsAnimator.setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT);
            boundsAnimator.addUpdateListener(animation -> {
                float progress = (float) animation.getAnimatedValue();
                setAnchoredTranslation(anchored, initialEndShift * (1.0f - progress));
            });
            boundsAnimator.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    if (animation == boundsAnimator) {
                        boundsAnimator = null;
                        setAnchoredTranslation(anchored, 0);
                    }
                }
            });
            boundsAnimator.start();
        }

        private void cancelBoundsAnimation() {
            holdingBounds = false;
            AndroidUtilities.cancelRunOnUIThread(boundsAnimationStarter);
            if (boundsAnimator != null) {
                // Drop the reference before cancelling: cancel() runs the end listener, which would settle
                // the pinned group on its final anchor. A re-hold reads what is left of the current move,
                // so it has to still be there. resetBounds is the path that clears it.
                ValueAnimator running = boundsAnimator;
                boundsAnimator = null;
                running.cancel();
            }
        }

        @Override
        protected void onDetachedFromWindow() {
            super.onDetachedFromWindow();
            cancelBoundsAnimation();
            resetBounds();
        }

        // Settle the pinned group on its current anchor with no slide. Used on a real width change
        // (rotation, multi-window), where the buttons have already moved to the new bounds.
        private void snapBounds() {
            cancelBoundsAnimation();
            holdStartTime = 0;
            resumingMidAnimation = false;
            pendingEndShift = 0;
            setAnchoredTranslation(rightAnchoredSlot, 0);
        }

        private void resetBounds() {
            availableWidthChanged = false;
            holdingBounds = false;
            holdStartTime = 0;
            pendingEndShift = 0;
            resumingMidAnimation = false;
            hasRightAnchoredLeft = false;
            setAnchoredTranslation(rightAnchoredSlot, 0);
        }

        private static void setAnchoredTranslation(View slot, float translation) {
            if (slot != null) {
                slot.setTranslationX(translation);
            }
        }
    }

    private static class SlidingLinearLayout extends LinearLayout {
        private final HashMap<View, int[]> slides = new HashMap<>();
        private final HashSet<View> hiddenFromAccessibility = new HashSet<>();
        private final Runnable slideStarter = this::startSlides;
        private ValueAnimator slideAnimator;
        private long slideHoldStart;
        protected int occupiedChildCount;
        private boolean swallowingTouch;

        SlidingLinearLayout(Context context) {
            super(context);
            setOrientation(HORIZONTAL);
            setGravity(Gravity.CENTER_VERTICAL);
            setClipChildren(false);
            setClipToPadding(false);
        }

        boolean isOccupied(View child) {
            return child.getVisibility() != GONE;
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int width = getPaddingLeft() + getPaddingRight();
            int height = getPaddingTop() + getPaddingBottom();
            int state = 0;
            int occupiedChildCount = 0;
            for (int i = 0; i < getChildCount(); i++) {
                View child = getChildAt(i);
                if (!isOccupied(child)) {
                    continue;
                }
                occupiedChildCount++;
                measureChildWithMargins(child, widthMeasureSpec, width, heightMeasureSpec, 0);
                LayoutParams layoutParams = (LayoutParams) child.getLayoutParams();
                width += layoutParams.leftMargin + child.getMeasuredWidth() + layoutParams.rightMargin;
                height = Math.max(height, getPaddingTop() + layoutParams.topMargin + child.getMeasuredHeight() + layoutParams.bottomMargin + getPaddingBottom());
                state = combineMeasuredStates(state, child.getMeasuredState());
            }
            this.occupiedChildCount = occupiedChildCount;
            setMeasuredDimension(
                    resolveSizeAndState(Math.max(width, getSuggestedMinimumWidth()), widthMeasureSpec, state),
                    resolveSizeAndState(Math.max(height, getSuggestedMinimumHeight()), heightMeasureSpec, state << MEASURED_HEIGHT_STATE_SHIFT)
            );
        }

        @Override
        protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
            boolean rtl = getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
            int x = rtl ? getWidth() - getPaddingRight() : getPaddingLeft();
            slides.clear();
            for (int i = 0; i < getChildCount(); i++) {
                View child = getChildAt(i);
                int previousLeft = child.getLeft();
                int previousWidth = child.getWidth();
                if (!isOccupied(child)) {
                    layoutReleasedChild(child, x);
                    continue;
                }
                LayoutParams layoutParams = (LayoutParams) child.getLayoutParams();
                int childWidth = child.getMeasuredWidth();
                int childHeight = child.getMeasuredHeight();
                int availableHeight = getHeight() - getPaddingTop() - getPaddingBottom() - layoutParams.topMargin - layoutParams.bottomMargin;
                int childTop = getPaddingTop() + layoutParams.topMargin + Math.max(0, (availableHeight - childHeight) / 2);
                int childLeft;
                if (rtl) {
                    x -= layoutParams.rightMargin + childWidth;
                    childLeft = x;
                    x -= layoutParams.leftMargin;
                } else {
                    x += layoutParams.leftMargin;
                    childLeft = x;
                    x += childWidth + layoutParams.rightMargin;
                }
                child.layout(childLeft, childTop, childLeft + childWidth, childTop + childHeight);
                setHiddenFromAccessibility(child, false);
                // Lay the control out where it belongs, push it back to where it was drawn, then ease it
                // across. It offsets rather than translates because the enter view already drives
                // translationX on several of these controls.
                int offset = previousLeft - childLeft;
                if (previousWidth > 0 && offset != 0) {
                    slides.put(child, new int[]{childLeft, offset});
                    child.offsetLeftAndRight(offset);
                }
            }
            if (slides.isEmpty()) {
                cancelSlides();
            } else {
                scheduleSlides();
            }
        }

        private void scheduleSlides() {
            // Something already moving should keep moving: only a settled row waits for the panel's
            // coalescing window.
            boolean animating = slideAnimator != null;
            if (slideHoldStart == 0) {
                slideHoldStart = SystemClock.elapsedRealtime();
            }
            // Children are already sitting where they were drawn, so drop a running animation instead of
            // letting it finish against the new targets at whatever progress it had reached.
            if (animating) {
                ValueAnimator running = slideAnimator;
                slideAnimator = null;
                running.cancel();
            }
            AndroidUtilities.cancelRunOnUIThread(slideStarter);
            AndroidUtilities.runOnUIThread(slideStarter, animating ? 0 : getSettleDelay(slideHoldStart));
        }

        private void startSlides() {
            slideHoldStart = 0;
            if (slides.isEmpty()) {
                return;
            }
            slideAnimator = ValueAnimator.ofFloat(0.0f, 1.0f);
            slideAnimator.setDuration(220);
            slideAnimator.setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT);
            slideAnimator.addUpdateListener(animation -> applySlides((float) animation.getAnimatedValue()));
            slideAnimator.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    if (animation == slideAnimator) {
                        slideAnimator = null;
                        applySlides(1.0f);
                        slides.clear();
                    }
                }
            });
            slideAnimator.start();
        }

        private void applySlides(float progress) {
            for (Map.Entry<View, int[]> entry : slides.entrySet()) {
                View child = entry.getKey();
                if (child.getParent() != this) {
                    continue;
                }
                int[] slide = entry.getValue();
                child.offsetLeftAndRight(slide[0] + Math.round(slide[1] * (1.0f - progress)) - child.getLeft());
            }
        }

        private void cancelSlides() {
            AndroidUtilities.cancelRunOnUIThread(slideStarter);
            slideHoldStart = 0;
            if (slideAnimator != null) {
                ValueAnimator running = slideAnimator;
                slideAnimator = null;
                running.cancel();
            }
            applySlides(1.0f);
            slides.clear();
        }

        @Override
        public void onViewRemoved(View child) {
            super.onViewRemoved(child);
            slides.remove(child);
            setHiddenFromAccessibility(child, false);
        }

        @Override
        protected void onDetachedFromWindow() {
            super.onDetachedFromWindow();
            cancelSlides();
        }

        // Hiding the row does not detach the slots, and a GONE -> VISIBLE round trip is not guaranteed to
        // lay out again, so a half-finished slide would come back stuck.
        static void cancelSlidesIn(View view) {
            if (view instanceof SlidingLinearLayout) {
                ((SlidingLinearLayout) view).cancelSlides();
            }
            if (view instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) view;
                for (int i = 0; i < group.getChildCount(); i++) {
                    cancelSlidesIn(group.getChildAt(i));
                }
            }
        }

        // A control that gave up its slot keeps the box it last held until its fade actually ends. Collapsing
        // it the moment the row stops counting it wiped it off screen at half opacity, so every swap looked
        // like a pop rather than a fade.
        private void layoutReleasedChild(View child, int x) {
            if (child.getWidth() > 0 && child.getVisibility() == VISIBLE && child.getAlpha() > 0) {
                child.layout(child.getLeft(), child.getTop(), child.getRight(), child.getBottom());
                setHiddenFromAccessibility(child, true);
                return;
            }
            setHiddenFromAccessibility(child, false);
            child.layout(x, getPaddingTop(), x, getPaddingTop());
        }

        // Touch is swallowed below, but a screen reader would still reach a control that is on its way out.
        private void setHiddenFromAccessibility(View child, boolean hidden) {
            if (hidden ? !hiddenFromAccessibility.add(child) : !hiddenFromAccessibility.remove(child)) {
                return;
            }
            child.setImportantForAccessibility(hidden
                    ? IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                    : IMPORTANT_FOR_ACCESSIBILITY_AUTO);
        }

        // A control still fading keeps its hit rect, and it overlaps whatever took its place. Swallow the tap
        // rather than firing the action the user can no longer see.
        @Override
        public boolean dispatchTouchEvent(MotionEvent event) {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                swallowingTouch = isInsideReleasedChild(event.getX(), event.getY());
            }
            if (swallowingTouch) {
                if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                    swallowingTouch = false;
                }
                return true;
            }
            return super.dispatchTouchEvent(event);
        }

        private boolean isInsideReleasedChild(float x, float y) {
            for (int i = 0; i < getChildCount(); i++) {
                View child = getChildAt(i);
                if (isOccupied(child) || child.getWidth() == 0 || child.getVisibility() != VISIBLE || child.getAlpha() <= 0) {
                    continue;
                }
                if (x >= child.getLeft() && x < child.getRight() && y >= child.getTop() && y < child.getBottom()) {
                    return true;
                }
            }
            return false;
        }
    }

    private static final class CollapsingLinearLayout extends SlidingLinearLayout {
        private final Runnable settleCheck = () -> {
            if (needsRelayout()) {
                requestLayout();
            }
        };

        CollapsingLinearLayout(Context context) {
            super(context);
        }

        // A control claims its slot once its fade is past halfway and gives it up at the same point on
        // the way out. Releasing only at alpha 0 made a leaving control hold the row open for its whole
        // fade, so the panel reflowed twice - once when its replacement arrived, again when it finally
        // let go - and the neighbouring buttons crawled back into place.
        @Override
        boolean isOccupied(View child) {
            return child.getVisibility() == VISIBLE && child.getAlpha() >= 0.5f;
        }

        @Override
        public void onDescendantInvalidated(View child, View target) {
            super.onDescendantInvalidated(child, target);
            if (needsRelayout()) {
                requestLayout();
                scheduleSettleCheck();
            }
            invalidate();
        }

        // Asking from here only gets as far as the first parent that is already waiting on a layout, and if
        // that parent's own measure is skipped its request never reaches the root, so nothing ever comes
        // back down to this row. Opening the keyboard on the way into edit mode is long enough for every
        // ask during a control's fade to land that way. Once the pass is over the chain is clear again, so
        // a look from outside it gets through: this runs after the fade, and only asks again if the row
        // still has not been measured.
        private void scheduleSettleCheck() {
            AndroidUtilities.cancelRunOnUIThread(settleCheck);
            AndroidUtilities.runOnUIThread(settleCheck, RELAYOUT_SETTLE_DELAY);
        }

        @Override
        protected void onDetachedFromWindow() {
            super.onDetachedFromWindow();
            AndroidUtilities.cancelRunOnUIThread(settleCheck);
        }

        // Occupancy changes reflow the row; a finished fade has to reflow too, otherwise the control that
        // just went invisible keeps its box (and its hit rect) forever.
        // Only onMeasure writes occupiedChildCount, so it records what the row actually laid out rather
        // than what it meant to. Recording the new count here instead used to strand a control: the panel
        // re-measures its slots with the same specs every pass, so a slot skips onMeasure unless a layout
        // was requested for it, and a request that got lost mid-pass left the count already updated. The
        // mismatch was gone, nothing asked again, and the control sat visible inside a zero-width box for
        // the rest of the session. Leaving the count alone keeps the mismatch until a measure clears it.
        private boolean needsRelayout() {
            int occupied = 0;
            boolean releasedChildVisible = false;
            for (int i = 0; i < getChildCount(); i++) {
                View child = getChildAt(i);
                if (isOccupied(child)) {
                    occupied++;
                } else if (child.getWidth() > 0 && (child.getVisibility() != VISIBLE || child.getAlpha() <= 0)) {
                    releasedChildVisible = true;
                }
            }
            return occupiedChildCount != occupied || releasedChildVisible;
        }
    }

    // ActionBarLayout's back-swipe arms from a right-drag on ACTION_MOVE and only backs off for a
    // horizontal child that already owns the touch. A plain HorizontalScrollView only claims that
    // ownership once its own onInterceptTouchEvent sees enough horizontal drag, which can be a frame
    // or two behind ActionBarLayout on a fast flick - so the swipe that was meant to scroll the middle
    // strip closes the chat instead. Claiming the touch on ACTION_DOWN, whenever the strip has content
    // to scroll to, closes that gap.
    private static final class ComposerMiddleScrollView extends HorizontalScrollView {
        private float downX;
        private float downY;
        private boolean guardArmed;

        ComposerMiddleScrollView(Context context) {
            super(context);
        }

        @Override
        public boolean onInterceptTouchEvent(MotionEvent ev) {
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = ev.getX();
                    downY = ev.getY();
                    guardArmed = canScrollHorizontally(-1) || canScrollHorizontally(1);
                    if (guardArmed && getParent() != null) {
                        getParent().requestDisallowInterceptTouchEvent(true);
                    }
                    break;
                case MotionEvent.ACTION_MOVE:
                    if (guardArmed) {
                        float dx = Math.abs(ev.getX() - downX);
                        float dy = Math.abs(ev.getY() - downY);
                        int slop = ViewConfiguration.get(getContext()).getScaledTouchSlop();
                        if (dy > dx && dy > slop) {
                            // The drag turned vertical (e.g. dragging the ComposerLayoutActivity preview
                            // row) - hand the gesture back instead of pinning ourselves as its owner for
                            // the rest of the touch stream.
                            guardArmed = false;
                            if (getParent() != null) {
                                getParent().requestDisallowInterceptTouchEvent(false);
                            }
                        }
                    }
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    guardArmed = false;
                    break;
                default:
                    break;
            }
            return super.onInterceptTouchEvent(ev);
        }
    }
}
