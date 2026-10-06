package com.dazewell.gram.headerbg;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.core.view.ViewCompat;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import androidx.customview.widget.ExploreByTouchHelper;

import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.SeekBarView;

import java.util.List;

import xyz.nextalone.nagram.NaConfig;

/**
 * One track with a handle at each end of a range, drawn in the app's chosen slider style so it sits
 * with the {@link SeekBarView}s around it. Values are whole steps from 0 to {@code steps}, and the
 * handles stay at least {@code minSpan} steps apart.
 */
final class RangeSeekBar extends View {

    interface Delegate {
        void onRangeChanged(int start, int end, boolean stop);

        CharSequence describe(boolean endHandle, int value);
    }

    private final Theme.ResourcesProvider resourcesProvider;
    private final Paint activePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final Path path = new Path();
    private final float[] radii = new float[8];
    private final Handles handles;
    private final int steps;
    private final int minSpan;
    private int start;
    private int end;
    // -1 when no handle is held; otherwise 0 for the start and 1 for the end.
    private int dragging = -1;
    // Down but not yet a drag or a tap.
    private boolean pending;
    private float downX, downY;
    private final int touchSlop;
    private Delegate delegate;

    RangeSeekBar(Context context, int steps, int minSpan, Theme.ResourcesProvider resourcesProvider) {
        super(context);
        this.steps = steps;
        this.minSpan = minSpan;
        this.resourcesProvider = resourcesProvider;
        this.end = steps;
        this.touchSlop = android.view.ViewConfiguration.get(context).getScaledTouchSlop();
        handles = new Handles();
        ViewCompat.setAccessibilityDelegate(this, handles);
    }

    void setDelegate(Delegate delegate) {
        this.delegate = delegate;
    }

    boolean isDragging() {
        return dragging >= 0;
    }

    void setRange(int start, int end) {
        this.start = Math.max(0, Math.min(steps - minSpan, start));
        this.end = Math.max(this.start + minSpan, Math.min(steps, end));
        invalidate();
    }

    // The track runs between the same insets SeekBarView keeps for its thumb.
    private float trackLeft() {
        return dp(16);
    }

    private float trackRight() {
        return getMeasuredWidth() - dp(16);
    }

    private float xOf(int value) {
        return trackLeft() + (trackRight() - trackLeft()) * value / steps;
    }

    private int valueAt(float x) {
        float width = trackRight() - trackLeft();
        return width <= 0 ? 0 : Math.round(Math.max(0f, Math.min(1f, (x - trackLeft()) / width)) * steps);
    }

    // As in SeekBarView: a drag starts only once the finger has moved sideways past the touch slop, so a
    // vertical swipe that starts on the track still scrolls the sheet, and a tap places the nearer handle.
    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = event.getX();
                downY = event.getY();
                pending = true;
                return true;
            case MotionEvent.ACTION_MOVE:
                if (pending) {
                    float dx = Math.abs(event.getX() - downX);
                    float dy = Math.abs(event.getY() - downY);
                    if (dy > touchSlop && dy > dx) {
                        // Hand the gesture back to the sheet's scroll.
                        pending = false;
                        return false;
                    }
                    if (dx > touchSlop) {
                        pending = false;
                        dragging = nearest(downX);
                        getParent().requestDisallowInterceptTouchEvent(true);
                    }
                }
                if (dragging >= 0) {
                    moveTo(event.getX(), false);
                }
                return true;
            case MotionEvent.ACTION_UP:
                if (pending) {
                    dragging = nearest(event.getX());
                }
                if (dragging >= 0) {
                    moveTo(event.getX(), true);
                }
                finishTouch();
                return true;
            case MotionEvent.ACTION_CANCEL:
                // Keep wherever a drag had got to, without jumping to the cancelled position.
                if (dragging >= 0) {
                    set(dragging == 1, dragging == 1 ? end : start, true);
                }
                finishTouch();
                return true;
        }
        return false;
    }

    private void finishTouch() {
        pending = false;
        dragging = -1;
        invalidate();
    }

    // With the handles together, the side of the touch says which one is meant.
    private int nearest(float x) {
        float toStart = Math.abs(x - xOf(start));
        float toEnd = Math.abs(x - xOf(end));
        return toStart < toEnd || (toStart == toEnd && x < xOf(start)) ? 0 : 1;
    }

    private void moveTo(float x, boolean stop) {
        set(dragging == 1, valueAt(x), stop);
    }

    // The held handle stops at the other rather than pushing it.
    private void set(boolean endHandle, int value, boolean stop) {
        int s = start;
        int e = end;
        if (endHandle) {
            e = Math.max(start + minSpan, Math.min(steps, value));
        } else {
            s = Math.max(0, Math.min(end - minSpan, value));
        }
        boolean changed = s != start || e != end;
        start = s;
        end = e;
        if (changed) {
            invalidate();
            handles.invalidateVirtualView(endHandle ? 1 : 0);
        }
        if (delegate != null && (changed || stop)) {
            delegate.onRangeChanged(start, end, stop);
        }
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        activePaint.setColor(Theme.getColor(Theme.key_player_progress, resourcesProvider));
        trackPaint.setColor(Theme.getColor(Theme.key_player_progressBackground, resourcesProvider));
        float cy = getMeasuredHeight() / 2f;
        float left = trackLeft();
        float right = trackRight();
        float x0 = xOf(start);
        float x1 = xOf(end);
        int style = NaConfig.INSTANCE.getSliderStyle().Int();
        if (style != SeekBarView.SLIDER_STYLE_DEFAULT) {
            // SeekBarView's MD3 shape: a thick track broken by a gap around each bar-shaped handle. The
            // modern track is as thick and has no thumb of its own, so it borrows the shape to show the handles.
            float half = dp(style == SeekBarView.SLIDER_STYLE_MD3 ? 13 : 17) / 2f;
            float gap = dp(7);
            float thumb = dp(4);
            segment(canvas, left, x0 - thumb / 2f - gap, cy, half, dp(8), dp(3), trackPaint);
            segment(canvas, x0 + thumb / 2f + gap, x1 - thumb / 2f - gap, cy, half, dp(3), dp(3), activePaint);
            segment(canvas, x1 + thumb / 2f + gap, right, cy, half, dp(3), dp(8), trackPaint);
            handle(canvas, x0, cy, half, thumb);
            handle(canvas, x1, cy, half, thumb);
        } else {
            float half = dp(3) / 2f;
            rect.set(left, cy - half, right, cy + half);
            canvas.drawRoundRect(rect, half, half, trackPaint);
            rect.set(x0, cy - half, x1, cy + half);
            canvas.drawRect(rect, activePaint);
            canvas.drawCircle(x0, cy, dp(dragging == 0 ? 8 : 6), activePaint);
            canvas.drawCircle(x1, cy, dp(dragging == 1 ? 8 : 6), activePaint);
        }
    }

    private void handle(Canvas canvas, float x, float cy, float half, float thumb) {
        rect.set(x - thumb / 2f, cy - half - dp(5), x + thumb / 2f, cy + half + dp(5));
        canvas.drawRoundRect(rect, dp(10), dp(10), activePaint);
    }

    private void segment(Canvas canvas, float from, float to, float cy, float half, float leftRadius, float rightRadius, Paint paint) {
        if (to <= from) {
            return;
        }
        rect.set(from, cy - half, to, cy + half);
        float l = Math.min(leftRadius, (to - from) / 2f);
        float r = Math.min(rightRadius, (to - from) / 2f);
        radii[0] = radii[1] = radii[6] = radii[7] = l;
        radii[2] = radii[3] = radii[4] = radii[5] = r;
        path.reset();
        path.addRoundRect(rect, radii, Path.Direction.CW);
        canvas.drawPath(path, paint);
    }

    @Override
    protected boolean dispatchHoverEvent(MotionEvent event) {
        return handles.dispatchHoverEvent(event) || super.dispatchHoverEvent(event);
    }

    // Each handle is its own accessibility node, adjustable by one step at a time.
    private final class Handles extends ExploreByTouchHelper {

        Handles() {
            super(RangeSeekBar.this);
        }

        @Override
        protected int getVirtualViewAt(float x, float y) {
            return Math.abs(x - xOf(start)) <= Math.abs(x - xOf(end)) ? 0 : 1;
        }

        @Override
        protected void getVisibleVirtualViews(List<Integer> ids) {
            ids.add(0);
            ids.add(1);
        }

        @Override
        protected void onPopulateNodeForVirtualView(int id, @NonNull AccessibilityNodeInfoCompat node) {
            int value = id == 1 ? end : start;
            node.setClassName("android.widget.SeekBar");
            node.setContentDescription(delegate != null ? delegate.describe(id == 1, value) : String.valueOf(value));
            node.setRangeInfo(AccessibilityNodeInfoCompat.RangeInfoCompat.obtain(
                    AccessibilityNodeInfoCompat.RangeInfoCompat.RANGE_TYPE_INT, 0, steps, value));
            node.addAction(AccessibilityNodeInfoCompat.ACTION_SCROLL_FORWARD);
            node.addAction(AccessibilityNodeInfoCompat.ACTION_SCROLL_BACKWARD);
            float x = id == 1 ? xOf(end) : xOf(start);
            int half = dp(16);
            node.setBoundsInParent(new android.graphics.Rect((int) x - half, 0, (int) x + half, getMeasuredHeight()));
        }

        @Override
        protected boolean onPerformActionForVirtualView(int id, int action, Bundle arguments) {
            int delta = action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD ? 1
                    : action == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD ? -1 : 0;
            if (delta == 0) {
                return false;
            }
            set(id == 1, (id == 1 ? end : start) + delta, true);
            return true;
        }
    }
}
