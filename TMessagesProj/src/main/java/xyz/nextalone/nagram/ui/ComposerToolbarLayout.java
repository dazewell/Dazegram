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
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.blur3.BlurredBackgroundDrawableViewFactory;
import org.telegram.ui.Components.blur3.drawable.BlurredBackgroundDrawable;
import org.telegram.ui.Components.blur3.drawable.color.BlurredBackgroundColorProvider;

/**
 * Presentation-only row for the chat composer controls.
 *
 * The controls keep their original ownership and state transitions in
 * ChatActivityEnterView; this class only gives them stable slots.
 */
public final class ComposerToolbarLayout extends FrameLayout {

    public static final int HEIGHT = 56;
    public static final int BUTTON_SIZE = 48;
    private static final int BOUNDS_SETTLE_DELAY = 48;
    private static final int BOUNDS_SETTLE_MAX = 150;

    private final ControlsLayout controls;
    private final FrameLayout startSlot;
    private final HorizontalScrollView middleScrollView;
    private final CollapsingLinearLayout middleLeadingSlot;
    private final LinearLayout formattingSlot;
    private final CollapsingLinearLayout actionSlot;
    private final CollapsingLinearLayout endSlot;

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
        formattingSlot = createLinearSlot(context);
        middleLeadingSlot.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        formattingSlot.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        actionSlot = createCollapsingSlot(context);
        middle.addView(middleLeadingSlot, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.MATCH_PARENT));
        middle.addView(formattingSlot, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.MATCH_PARENT));
        middle.addView(actionSlot, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.MATCH_PARENT));

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
    }

    public void addMiddleLeading(View view, int width, int height, float horizontalMargin, float verticalMargin, int index) {
        AndroidUtilities.removeFromParent(view);
        middleLeadingSlot.addView(view, Math.min(index, middleLeadingSlot.getChildCount()),
                LayoutHelper.createLinear(width, height, horizontalMargin, verticalMargin, horizontalMargin, verticalMargin));
        middleScrollView.post(this::pinMiddleToStart);
    }

    public void addFormatting(View view) {
        AndroidUtilities.removeFromParent(view);
        formattingSlot.addView(view, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.MATCH_PARENT));
        middleScrollView.post(this::pinMiddleToStart);
    }

    public void addContextGroup(View view) {
        AndroidUtilities.removeFromParent(view);
        endSlot.addView(view, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.MATCH_PARENT));
    }

    public void addContextAction(View view) {
        AndroidUtilities.removeFromParent(view);
        endSlot.addView(view, LayoutHelper.createLinear(BUTTON_SIZE, BUTTON_SIZE));
    }

    public void addAction(View view) {
        addAction(view, actionSlot.getChildCount());
    }

    public void addAction(View view, int index) {
        AndroidUtilities.removeFromParent(view);
        actionSlot.addView(view, Math.min(index, actionSlot.getChildCount()), LayoutHelper.createLinear(BUTTON_SIZE, BUTTON_SIZE));
    }

    public void addQuickAction(View view) {
        addContextAction(view);
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

    private static LinearLayout createLinearSlot(Context context) {
        LinearLayout slot = new LinearLayout(context);
        slot.setOrientation(LinearLayout.HORIZONTAL);
        slot.setGravity(Gravity.CENTER_VERTICAL);
        slot.setClipChildren(false);
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
        private final Runnable boundsAnimationStarter = this::startBoundsAnimation;

        ControlsLayout(Context context) {
            super(context);
            setClipChildren(true);
            setClipToPadding(true);
            setPaddingRelative(AndroidUtilities.dp(8), AndroidUtilities.dp(4), AndroidUtilities.dp(8), AndroidUtilities.dp(4));
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
            if (!laidOut) {
                laidOut = true;
                animatedGlassWidth = getWidth();
            } else if (pendingBoundsAnimation) {
                pendingBoundsAnimation = false;
                holdCapturedVisualState();
                AndroidUtilities.runOnUIThread(boundsAnimationStarter, getRemainingSettleDelay());
            } else if (!holdingBounds) {
                animatedGlassWidth = getWidth();
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
            cancelBoundsAnimation();
        }

        // Hold the panel exactly where it was drawn instead of moving straight away. Erasing a draft flips two
        // slots a frame or two apart (attach arrives, the action beside it leaves), and each flip remeasures;
        // waiting for the width to settle turns what used to be two consecutive slides into one.
        private void holdCapturedVisualState() {
            if (holdStartTime == 0) {
                holdStartTime = SystemClock.elapsedRealtime();
            }
            holdingBounds = true;
            setTranslationX(pendingVisualLeft - getLeft());
            animatedGlassWidth = pendingVisualWidth;
            invalidate();
        }

        // Each further change pushes the start back, so cap the wait: a slot that keeps resizing must not
        // leave the panel frozen.
        private long getRemainingSettleDelay() {
            long held = SystemClock.elapsedRealtime() - holdStartTime;
            return Math.max(0, Math.min(BOUNDS_SETTLE_DELAY, BOUNDS_SETTLE_MAX - held));
        }

        private void startBoundsAnimation() {
            holdingBounds = false;
            holdStartTime = 0;
            float finalWidth = getWidth();
            float initialTranslation = pendingVisualLeft - getLeft();
            float initialWidth = pendingVisualWidth;
            if (initialTranslation == 0 && initialWidth == finalWidth) {
                setTranslationX(0);
                animatedGlassWidth = finalWidth;
                return;
            }
            setTranslationX(initialTranslation);
            animatedGlassWidth = initialWidth;
            boundsAnimator = ValueAnimator.ofFloat(0.0f, 1.0f);
            boundsAnimator.setDuration(220);
            boundsAnimator.setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT);
            boundsAnimator.addUpdateListener(animation -> {
                float progress = (float) animation.getAnimatedValue();
                setTranslationX(initialTranslation * (1.0f - progress));
                animatedGlassWidth = initialWidth + (finalWidth - initialWidth) * progress;
                invalidate();
            });
            boundsAnimator.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    if (animation == boundsAnimator) {
                        boundsAnimator = null;
                        setTranslationX(0);
                        animatedGlassWidth = getWidth();
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
                boundsAnimator.cancel();
                boundsAnimator = null;
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
            setTranslationX(0);
            animatedGlassWidth = getWidth();
        }
    }

    private static final class CollapsingLinearLayout extends LinearLayout {
        private int occupiedChildCount;
        private boolean swallowingTouch;

        CollapsingLinearLayout(Context context) {
            super(context);
            setOrientation(HORIZONTAL);
            setGravity(Gravity.CENTER_VERTICAL);
            setClipChildren(false);
            setClipToPadding(false);
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
            for (int i = 0; i < getChildCount(); i++) {
                View child = getChildAt(i);
                if (!isOccupied(child)) {
                    layoutReleasedChild(child, x);
                    continue;
                }
                LayoutParams layoutParams = (LayoutParams) child.getLayoutParams();
                int childHeight = child.getMeasuredHeight();
                int availableHeight = getHeight() - getPaddingTop() - getPaddingBottom() - layoutParams.topMargin - layoutParams.bottomMargin;
                int childTop = getPaddingTop() + layoutParams.topMargin + Math.max(0, (availableHeight - childHeight) / 2);
                if (rtl) {
                    x -= layoutParams.rightMargin + child.getMeasuredWidth();
                    child.layout(x, childTop, x + child.getMeasuredWidth(), childTop + childHeight);
                    x -= layoutParams.leftMargin;
                } else {
                    x += layoutParams.leftMargin;
                    child.layout(x, childTop, x + child.getMeasuredWidth(), childTop + childHeight);
                    x += child.getMeasuredWidth() + layoutParams.rightMargin;
                }
            }
        }

        // A control that gave up its slot keeps the box it last held until its fade actually ends. Collapsing
        // it the moment the row stops counting it wiped it off screen at half opacity, so every swap looked
        // like a pop rather than a fade.
        private void layoutReleasedChild(View child, int x) {
            if (child.getWidth() > 0 && child.getVisibility() == VISIBLE && child.getAlpha() > 0) {
                child.layout(child.getLeft(), child.getTop(), child.getRight(), child.getBottom());
                return;
            }
            child.layout(x, getPaddingTop(), x, getPaddingTop());
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
                if (isOccupied(child) || child.getWidth() == 0) {
                    continue;
                }
                if (x >= child.getLeft() && x < child.getRight() && y >= child.getTop() && y < child.getBottom()) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public void onDescendantInvalidated(View child, View target) {
            super.onDescendantInvalidated(child, target);
            if (needsRelayout()) {
                requestLayout();
            }
            invalidate();
        }

        // Occupancy changes reflow the row; a finished fade has to reflow too, otherwise the control that
        // just went invisible keeps its box (and its hit rect) forever.
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
            if (occupiedChildCount != occupied) {
                occupiedChildCount = occupied;
                return true;
            }
            return releasedChildVisible;
        }

        // A control claims its slot once its fade is past halfway and gives it up at the same point on
        // the way out. Releasing only at alpha 0 made a leaving control hold the row open for its whole
        // fade, so the panel reflowed twice - once when its replacement arrived, again when it finally
        // let go - and the neighbouring buttons crawled back into place.
        private static boolean isOccupied(View child) {
            return child.getVisibility() == VISIBLE && child.getAlpha() >= 0.5f;
        }
    }
}
