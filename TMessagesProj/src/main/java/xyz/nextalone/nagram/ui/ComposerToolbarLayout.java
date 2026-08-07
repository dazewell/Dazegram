package xyz.nextalone.nagram.ui;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
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

import xyz.nextalone.nagram.ui.composer.ComposerButtons;
import xyz.nextalone.nagram.ui.composer.ComposerLayout;

/**
 * Presentation-only row for the chat composer controls.
 *
 * The controls keep their original ownership and state transitions in
 * ChatActivityEnterView; this class only gives them stable slots.
 */
public final class ComposerToolbarLayout extends FrameLayout {

    public static final int HEIGHT = 56;
    public static final int BUTTON_SIZE = 48;
    private static final int ICON_GLYPH = 24;
    private static final int BOUNDS_SETTLE_DELAY = 48;
    private static final int BOUNDS_SETTLE_MAX = 150;
    // Has to outlast the longest control fade, otherwise the row gets re-measured mid-animation.
    private static final int RELAYOUT_SETTLE_DELAY = 300;

    private final ControlsLayout controls;
    private final FrameLayout startSlot;
    private final HorizontalScrollView middleScrollView;
    private final CollapsingLinearLayout middleLeadingSlot;
    private final CollapsingLinearLayout orderedSlot;
    private final CollapsingLinearLayout endSlot;
    private final Map<View, String> configuredKeys = new HashMap<>();
    private View pinnedTrailingView;

    public ComposerToolbarLayout(Context context) {
        super(context);
        setClipChildren(false);
        setClipToPadding(false);

        controls = new ControlsLayout(context);
        controls.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        controls.setLayoutDirection(LocaleController.isRTL ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
        addView(controls, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, HEIGHT, Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM));

        startSlot = createFrameSlot(context);
        startSlot.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);

        middleScrollView = new HorizontalScrollView(context);
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

    public void attachGlass(BlurredBackgroundDrawableViewFactory factory, BlurredBackgroundColorProvider colorProvider) {
        controls.attachGlass(factory, colorProvider);
    }

    public void updateColors() {
        controls.updateColors();
    }

    public void addStart(View view) {
        addToFrame(startSlot, view, BUTTON_SIZE, BUTTON_SIZE, Gravity.CENTER);
        applyIconBox(null, view);
    }

    /**
     * Places a button the user is allowed to move. The zone and the position inside it come from the
     * saved layout, resolved as each view arrives: schedule, the send-as avatar and the bot pill are
     * all created long after the constructor, so there is no single moment where everything is present
     * and the whole row could be sorted in one pass.
     */
    public void addConfigurable(String key, View view) {
        AndroidUtilities.removeFromParent(view);
        configuredKeys.put(view, key);
        // One sizing gate for every configurable button, whatever zone it lands in: the source assets
        // range from 16dp to 32dp and the stock scale type draws each at its own intrinsic size, so
        // without this the row is a jumble of glyph sizes.
        applyIconBox(key, view);
        int zone = ComposerLayout.zoneOf(key);
        if (zone == ComposerButtons.ZONE_HIDDEN) {
            // Left without a parent rather than set GONE: the enter view reads these buttons' visibility
            // to decide its own geometry, so a removed button has to keep whatever visibility it had.
            return;
        }
        if (zone == ComposerButtons.ZONE_START) {
            addToFrame(startSlot, view, BUTTON_SIZE, BUTTON_SIZE, Gravity.CENTER);
            return;
        }
        if (zone == ComposerButtons.ZONE_END) {
            if (key.equals(ComposerLayout.trailingKey())) {
                pinnedTrailingView = view;
                endSlot.addView(view, LayoutHelper.createLinear(BUTTON_SIZE, BUTTON_SIZE));
            } else {
                endSlot.addView(view, insertIndex(endSlot, key, endContextIndex()), LayoutHelper.createLinear(BUTTON_SIZE, BUTTON_SIZE));
            }
            return;
        }
        orderedSlot.addView(view, insertIndex(orderedSlot, key, orderedSlot.getChildCount()), LayoutHelper.createLinear(BUTTON_SIZE, BUTTON_SIZE));
        middleScrollView.post(this::pinMiddleToStart);
    }

    // Walks the siblings already in the slot and stops in front of the first one the saved layout puts
    // after this button. Views with no key (the attach group) are left where they are.
    private int insertIndex(ViewGroup slot, String key, int limit) {
        int order = ComposerLayout.indexOf(key);
        for (int i = 0; i < limit && i < slot.getChildCount(); i++) {
            String other = configuredKeys.get(slot.getChildAt(i));
            if (other != null && ComposerLayout.indexOf(other) > order) {
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
    }

    private int endContextIndex() {
        int index = pinnedTrailingView != null ? endSlot.indexOfChild(pinnedTrailingView) : -1;
        return index < 0 ? endSlot.getChildCount() : index;
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

    private static FrameLayout createFrameSlot(Context context) {
        FrameLayout slot = new FrameLayout(context);
        slot.setClipChildren(false);
        slot.setClipToPadding(false);
        return slot;
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

    private static void addToFrame(FrameLayout parent, View view, int width, int height, int gravity) {
        AndroidUtilities.removeFromParent(view);
        parent.addView(view, LayoutHelper.createFrame(width, height, gravity));
    }

    // Every glyph gets the same 24dp visual box. Registry scales are authored optical corrections
    // for assets whose keyline is intentionally smaller or larger than that shared box.
    public static void applyIconBox(View view, int cellDp, float scale) {
        int inset = Math.max(0, AndroidUtilities.dp((cellDp - ICON_GLYPH * scale) / 2.0f));
        if (view instanceof RLottieImageView) {
            // Lottie renders into a fixed bitmap, so it responds to the box rather than a drawable
            // scale type.
            view.setPadding(inset, inset, inset, inset);
            return;
        }
        if (!(view instanceof ImageView)) {
            return;
        }
        ImageView icon = (ImageView) view;
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        icon.setPadding(inset, inset, inset, inset);
    }

    private static void applyIconBox(String key, View view) {
        ComposerButtons.Button button = key != null ? ComposerButtons.get(key) : null;
        float scale = button != null ? button.iconScale : 1f;
        // Expressed as a wider or narrower inset rather than a view scale: these buttons carry a press
        // animator that drives scaleX/scaleY, so a scale set here would be animated away on the
        // first tap.
        applyIconBox(view, BUTTON_SIZE, scale);
    }

    // The panel and its slots react to the same layout passes, so they share one settle schedule and start
    // moving on the same frame. Each further change pushes the start back, capped so a slot that keeps
    // resizing cannot hold everything frozen.
    private static long getSettleDelay(long holdStartTime) {
        long held = SystemClock.elapsedRealtime() - holdStartTime;
        return Math.max(0, Math.min(BOUNDS_SETTLE_DELAY, BOUNDS_SETTLE_MAX - held));
    }

    private static final class ControlsLayout extends FrameLayout {
        private FrameLayout startSlot;
        private HorizontalScrollView middleScrollView;
        private LinearLayout middleContent;
        private CollapsingLinearLayout endSlot;
        private BlurredBackgroundDrawable glass;
        private ValueAnimator boundsAnimator;
        private int measuredPanelWidth = -1;
        private boolean laidOut;
        private boolean pendingBoundsAnimation;
        private float pendingVisualLeft;
        private float pendingVisualWidth;
        private float animatedGlassWidth = -1;
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
            // 2dp of glass inset plus 2dp of breathing room: the press animation only overshoots by a
            // fraction of a dp, so anything wider is just dead space at both ends of the capsule.
            setPaddingRelative(AndroidUtilities.dp(4), AndroidUtilities.dp(4), AndroidUtilities.dp(4), AndroidUtilities.dp(4));
        }

        void setSlots(FrameLayout startSlot, HorizontalScrollView middleScrollView, LinearLayout middleContent, CollapsingLinearLayout endSlot) {
            this.startSlot = startSlot;
            this.middleScrollView = middleScrollView;
            this.middleContent = middleContent;
            this.endSlot = endSlot;

            addView(startSlot, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, HEIGHT, Gravity.LEFT));
            addView(middleScrollView, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, HEIGHT, Gravity.LEFT));
            addView(endSlot, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, HEIGHT, Gravity.LEFT));
        }

        void attachGlass(BlurredBackgroundDrawableViewFactory factory, BlurredBackgroundColorProvider colorProvider) {
            glass = factory.create(this, colorProvider);
            glass.setRadius(AndroidUtilities.dp(26));
            glass.setPadding(AndroidUtilities.dp(4));
            invalidate();
        }

        void updateColors() {
            if (glass != null) {
                glass.updateColors();
                invalidate();
            }
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
            int height = AndroidUtilities.dp(HEIGHT);
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
            int desiredWidth = horizontalPadding + startWidth + middleWidth + endWidth;
            int panelWidth = Math.min(desiredWidth, availableWidth);
            int middleViewportWidth = Math.min(middleWidth, Math.max(0, panelWidth - horizontalPadding - startWidth - endWidth));

            middleScrollView.measure(MeasureSpec.makeMeasureSpec(middleViewportWidth, MeasureSpec.EXACTLY), heightSpec);
            // A control fading out inside the middle group sits past the viewport edge, so only clip once the
            // group actually scrolls - that is the case the clip exists for.
            boolean clipMiddle = middleWidth > middleViewportWidth;
            if (middleScrollView.getClipChildren() != clipMiddle) {
                middleScrollView.setClipChildren(clipMiddle);
            }
            if (laidOut && measuredPanelWidth != panelWidth) {
                captureVisualState();
                pendingBoundsAnimation = true;
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
                endSlot.layout(contentLeft, contentTop, contentLeft + endWidth, contentBottom);
                middleScrollView.layout(contentLeft + endWidth, contentTop, contentLeft + endWidth + middleWidth, contentBottom);
                startSlot.layout(contentRight - startWidth, contentTop, contentRight, contentBottom);
            } else {
                startSlot.layout(contentLeft, contentTop, contentLeft + startWidth, contentBottom);
                middleScrollView.layout(contentLeft + startWidth, contentTop, contentLeft + startWidth + middleWidth, contentBottom);
                endSlot.layout(contentRight - endWidth, contentTop, contentRight, contentBottom);
            }
            // Whichever slot is pinned to the panel's right edge is laid out against the new width the
            // instant a control claims its slot, so it jumps ahead while the glass is still easing over.
            // Hand its catch-up to the bounds animator below rather than giving it its own, so the group
            // and the edge it is pinned to move on the same frames.
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
                animatedGlassWidth = getWidth();
            } else if (pendingBoundsAnimation) {
                pendingBoundsAnimation = false;
                holdCapturedVisualState(endShift);
                AndroidUtilities.runOnUIThread(boundsAnimationStarter, resumingMidAnimation ? 0 : getRemainingSettleDelay());
            } else if (!holdingBounds) {
                animatedGlassWidth = getWidth();
                trailingSlot.setTranslationX(0);
            }
        }

        @Override
        protected void dispatchDraw(Canvas canvas) {
            drawGlass(canvas);
            super.dispatchDraw(canvas);
        }

        private void drawGlass(Canvas canvas) {
            if (glass == null || getWidth() <= 0 || getHeight() <= 0) {
                return;
            }
            int inset = AndroidUtilities.dp(2);
            int width = Math.round(animatedGlassWidth < 0 ? getWidth() : animatedGlassWidth);
            if (width <= inset * 2 || getHeight() <= inset * 2) {
                return;
            }
            glass.setBounds(inset, inset, width - inset, getHeight() - inset);
            glass.draw(canvas);
        }

        private void captureVisualState() {
            pendingVisualLeft = getLeft() + getTranslationX();
            pendingVisualWidth = animatedGlassWidth >= 0 ? animatedGlassWidth : getWidth();
            // A slot that is already sliding restarts on the spot rather than waiting out the coalescing
            // window again, so the panel has to skip it too or the glass edge falls behind its own buttons.
            resumingMidAnimation = boundsAnimator != null;
            cancelBoundsAnimation();
        }

        // Hold the panel exactly where it was drawn instead of moving straight away. Erasing a draft flips two
        // slots a frame or two apart (attach arrives, the action beside it leaves), and each flip remeasures;
        // waiting for the width to settle turns what used to be two consecutive slides into one.
        private void holdCapturedVisualState(int endShift) {
            if (holdStartTime == 0) {
                holdStartTime = SystemClock.elapsedRealtime();
            }
            holdingBounds = true;
            setTranslationX(pendingVisualLeft - getLeft());
            animatedGlassWidth = pendingVisualWidth;
            // A hold arriving mid-animation stacks on whatever is left of the last one, so the group keeps
            // the distance it still had to cover instead of snapping back to the new edge.
            pendingEndShift = rightAnchoredSlot.getTranslationX() + endShift;
            rightAnchoredSlot.setTranslationX(pendingEndShift);
            invalidate();
        }

        // Each further change pushes the start back, so cap the wait: a slot that keeps resizing must not
        // leave the panel frozen.
        private long getRemainingSettleDelay() {
            return getSettleDelay(holdStartTime);
        }

        private void startBoundsAnimation() {
            holdingBounds = false;
            holdStartTime = 0;
            resumingMidAnimation = false;
            float finalWidth = getWidth();
            float initialTranslation = pendingVisualLeft - getLeft();
            float initialWidth = pendingVisualWidth;
            float initialEndShift = pendingEndShift;
            View anchored = rightAnchoredSlot;
            pendingEndShift = 0;
            if (initialTranslation == 0 && initialWidth == finalWidth && initialEndShift == 0) {
                setTranslationX(0);
                animatedGlassWidth = finalWidth;
                setAnchoredTranslation(anchored, 0);
                return;
            }
            setTranslationX(initialTranslation);
            animatedGlassWidth = initialWidth;
            setAnchoredTranslation(anchored, initialEndShift);
            boundsAnimator = ValueAnimator.ofFloat(0.0f, 1.0f);
            boundsAnimator.setDuration(220);
            boundsAnimator.setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT);
            boundsAnimator.addUpdateListener(animation -> {
                float progress = (float) animation.getAnimatedValue();
                setTranslationX(initialTranslation * (1.0f - progress));
                animatedGlassWidth = initialWidth + (finalWidth - initialWidth) * progress;
                setAnchoredTranslation(anchored, initialEndShift * (1.0f - progress));
                invalidate();
            });
            boundsAnimator.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    if (animation == boundsAnimator) {
                        boundsAnimator = null;
                        setTranslationX(0);
                        animatedGlassWidth = getWidth();
                        setAnchoredTranslation(anchored, 0);
                        invalidate();
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
                // the panel and the pinned group on their final bounds. A recapture reads what is left of
                // the current move, so it has to still be there. resetBounds is the path that clears them.
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

        private void resetBounds() {
            pendingBoundsAnimation = false;
            holdingBounds = false;
            holdStartTime = 0;
            pendingEndShift = 0;
            resumingMidAnimation = false;
            hasRightAnchoredLeft = false;
            setTranslationX(0);
            animatedGlassWidth = getWidth();
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
}
