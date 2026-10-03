package com.radolyn.ayugram.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;

import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.LayoutHelper;


/**
 * Reveals its single body child by animating its own measured height, so the sheet grows and
 * shrinks instead of snapping. A FrameLayout on purpose: SectionsScrollView.gatherChildren recurses
 * into full-width vertical LinearLayouts and would paint the card from the body's full height, past
 * the part that is actually revealed; a FrameLayout is gathered as one child at its animated size.
 */
public final class CollapsibleFrame extends FrameLayout {
    private final View body;
    private float progress;
    private boolean expanded;
    private ValueAnimator animator;

    public CollapsibleFrame(Context context, View body) {
        super(context);
        this.body = body;
        addView(body, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP | Gravity.LEFT));
        setVisibility(GONE);
    }

    public void setExpanded(boolean expanded, boolean animated) {
        if (this.expanded == expanded && animator == null) return;
        this.expanded = expanded;
        if (animator != null) {
            // Null the field first: cancel() runs onAnimationEnd synchronously, which would
            // otherwise snap to the old target before reversing.
            ValueAnimator running = animator;
            animator = null;
            running.cancel();
        }
        float target = expanded ? 1f : 0f;
        // Attachment, not isLaidOut(): a frame that starts GONE is never laid out before its first
        // expand, but it is attached once the sheet shows; the pre-show initial sync is not.
        if (!animated || !isAttachedToWindow()) {
            setProgress(target);
            return;
        }
        setVisibility(VISIBLE);
        animator = ValueAnimator.ofFloat(progress, target);
        animator.setDuration(340);
        animator.setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT);
        animator.addUpdateListener(a -> setProgress((float) a.getAnimatedValue()));
        animator.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                if (animator == animation) {
                    animator = null;
                    setProgress(target);
                }
            }
        });
        animator.start();
    }

    private void setProgress(float value) {
        progress = value;
        setVisibility(value <= 0f ? GONE : VISIBLE);
        requestLayout();
        // The section clip paths are recorded into the content layout's own display list, so it
        // has to re-record on every frame, even once the sheet stops resizing at its max height.
        if (getParent() instanceof View) {
            ((View) getParent()).invalidate();
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
        int full = body.getMeasuredHeight();
        setMeasuredDimension(getMeasuredWidth(), progress >= 1f ? full : Math.round(full * progress));
    }
}
