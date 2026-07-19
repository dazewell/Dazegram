package tw.nekomimi.nekogram.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.view.MotionEvent;
import android.view.View;

import androidx.core.graphics.ColorUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.ui.Components.CubicBezierInterpolator;

/**
 * Zoom control for the round video message recorder: a long precision slider with a paired -/+ rocker
 * and a camera-flip button to their left, all kept off the track ends. Has two layouts blended by a
 * single fraction: roomy (slider row on top, buttons centered below) and compact for when the soft
 * keyboard eats the vertical space (one row, buttons to the right of a shorter slider). The view is
 * always 104dp tall; the rows move inside it. The flip button stays even when the camera has no zoom.
 */
public class InstantZoomControlView extends View {

    // the -/+ and flip buttons sit on the recorder's message-panel chip, the same background the flash
    // button uses; a faint rim and a dark-on-light / white-on-dark glyph, brightening a touch on press
    private static final int CHIP_RIM_COLOR_LIGHT = 0x22000000;
    private static final int GLYPH_COLOR_LIGHT = 0xCC000000;
    private static final int CHIP_RIM_COLOR_DARK = 0x22FFFFFF;
    private static final int GLYPH_COLOR_DARK = 0xFFFFFFFF;

    private final int chipColor;
    private final int chipColorPressed;
    private final int chipRimColor;
    private final int glyphColor;

    public interface Delegate {
        void didSetZoom(float zoom);
        void onButtonDown(int direction);
        void onButtonUp(int direction, boolean cancelled);
        void onSwitchCamera();
    }

    private final Drawable minusDrawable;
    private final Drawable plusDrawable;
    private final Drawable switchDrawable;
    private final Drawable knobDrawable;
    private final Drawable pressedKnobDrawable;
    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint chipPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF trackRect = new RectF();

    private Delegate delegate;
    private float zoom;

    // 0 = roomy two-row layout, 1 = compact single row; animated on keyboard open/close
    private float compact;
    private boolean compactTarget;
    private ValueAnimator compactAnimator;

    // geometry recomputed each draw from width + compact fraction
    private float trackLeft, trackRight, trackY;
    private float minusCx, plusCx, switchCx, buttonCy, buttonRadius, glyphHalf, switchGlyphHalf;

    // the slider + -/+ rocker hide when the current camera has no zoom range; the flip button stays
    private boolean zoomEnabled = true;

    private boolean knobPressed;
    private float knobOffsetX;
    private boolean trackPressed;
    private float trackDownX;

    private int buttonPointerId = -1;
    private int buttonDirection;
    private int switchPointerId = -1;

    private final class ButtonAccent {
        float scale = 1f;
        float fill;
        float ring;
        boolean held;
        private ValueAnimator animator;

        void animateTo(float toScale, float toFill, float toRing, long duration) {
            if (animator != null) {
                animator.cancel();
            }
            final float fromScale = scale, fromFill = fill, fromRing = ring;
            animator = ValueAnimator.ofFloat(0f, 1f);
            animator.setDuration(duration);
            animator.setInterpolator(CubicBezierInterpolator.EASE_OUT);
            animator.addUpdateListener(a -> {
                final float t = (float) a.getAnimatedValue();
                scale = fromScale + (toScale - fromScale) * t;
                fill = fromFill + (toFill - fromFill) * t;
                ring = fromRing + (toRing - fromRing) * t;
                invalidate();
            });
            animator.start();
        }
    }

    private final ButtonAccent minusAccent = new ButtonAccent();
    private final ButtonAccent plusAccent = new ButtonAccent();
    private final ButtonAccent switchAccent = new ButtonAccent();

    public InstantZoomControlView(Context context, boolean dark, int chipBackgroundColor) {
        super(context);
        // same background the flash button rides on (the chat's message-panel color), opaque here since
        // this view can't blur behind itself; pressing lifts it toward the glyph for a bit of feedback
        chipColor = ColorUtils.setAlphaComponent(chipBackgroundColor, 0xFF);
        chipRimColor = dark ? CHIP_RIM_COLOR_DARK : CHIP_RIM_COLOR_LIGHT;
        glyphColor = dark ? GLYPH_COLOR_DARK : GLYPH_COLOR_LIGHT;
        chipColorPressed = ColorUtils.blendARGB(chipColor, glyphColor, 0.12f);
        minusDrawable = context.getResources().getDrawable(R.drawable.zoom_minus).mutate();
        minusDrawable.setColorFilter(glyphColor, PorterDuff.Mode.SRC_IN);
        plusDrawable = context.getResources().getDrawable(R.drawable.zoom_plus).mutate();
        plusDrawable.setColorFilter(glyphColor, PorterDuff.Mode.SRC_IN);
        switchDrawable = context.getResources().getDrawable(R.drawable.camera_revert1).mutate();
        switchDrawable.setColorFilter(glyphColor, PorterDuff.Mode.SRC_IN);
        knobDrawable = context.getResources().getDrawable(R.drawable.zoom_round);
        pressedKnobDrawable = context.getResources().getDrawable(R.drawable.zoom_round_b);
        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setStrokeWidth(AndroidUtilities.dpf2(1.5f));
    }

    public void setDelegate(Delegate delegate) {
        this.delegate = delegate;
    }

    public void setZoom(float value, boolean notify) {
        value = value < 0 ? 0 : Math.min(value, 1f);
        if (value == zoom) {
            return;
        }
        zoom = value;
        if (notify && delegate != null) {
            delegate.didSetZoom(zoom);
        }
        invalidate();
    }

    public boolean isTouch() {
        return knobPressed || trackPressed;
    }

    public boolean isDragging() {
        return knobPressed;
    }

    public boolean isCompact() {
        return compactTarget;
    }

    public boolean isZoomEnabled() {
        return zoomEnabled;
    }

    // the slider + -/+ rocker draw and respond only when the current camera can zoom; the flip button
    // stays regardless, so switching cameras still works on a fixed-focus (no-zoom) camera
    public void setZoomEnabled(boolean enabled) {
        if (zoomEnabled == enabled) {
            return;
        }
        zoomEnabled = enabled;
        if (!enabled) {
            knobPressed = false;
            trackPressed = false;
            if (buttonPointerId != -1) {
                releaseButton(true);
            }
        }
        invalidate();
    }

    // decides the layout from the free space between the camera circle and the record controls;
    // ~14dp hysteresis so the keyboard slide animation doesn't flap it back and forth. two rows fit
    // from ~124dp of gap, so roomy is the main keyboard case and compact only kicks in when it's tight.
    public void setAvailableGap(float gap) {
        final boolean target = compactTarget ? gap < AndroidUtilities.dp(126) : gap < AndroidUtilities.dp(112);
        if (target == compactTarget) {
            return;
        }
        compactTarget = target;
        if (compactAnimator != null) {
            compactAnimator.cancel();
            compactAnimator = null;
        }
        if (getAlpha() <= 0f) {
            compact = target ? 1f : 0f;
            invalidate();
            return;
        }
        compactAnimator = ValueAnimator.ofFloat(compact, target ? 1f : 0f);
        compactAnimator.setDuration(150);
        compactAnimator.setInterpolator(CubicBezierInterpolator.EASE_OUT);
        compactAnimator.addUpdateListener(a -> {
            compact = (float) a.getAnimatedValue();
            invalidate();
        });
        compactAnimator.start();
    }

    // called by the recorder when a hold actually starts zooming, to switch the button into its held look
    public void setButtonHeld(int direction) {
        final ButtonAccent accent = accent(direction);
        accent.held = true;
        accent.animateTo(1.15f, 1f, 1f, 120);
    }

    private ButtonAccent accent(int direction) {
        return direction < 0 ? minusAccent : plusAccent;
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    private void updateGeometry() {
        final float w = getMeasuredWidth();
        // roomy centers on the full width: the slider sits a row above the recorder's view-once "(1)"
        // button and the rocker is a centered pair, so nothing reaches the right edge the button lives on.
        // compact keeps a right column clear of that button (the recorder draws it hard against the right
        // edge, centered at width - 26dp) since its -/+ dock on the right of the single row.
        final float compactW = w - AndroidUtilities.dp(56);
        // roomy: slider row on top (centerline 24dp), 48dp rocker pair centered at 68dp, 12dp apart.
        // trim the line ~20dp so it doesn't run edge to edge; roomyLeft keeps it centered.
        final float roomyWidth = Math.min(w - AndroidUtilities.dp(64), AndroidUtilities.dp(300)) - AndroidUtilities.dp(20);
        final float roomyLeft = (w - roomyWidth) / 2f;
        // compact: one row at 52dp, 20dp side margins, [slider] 16dp [flip] 10dp [-] 10dp [+], 40dp buttons
        final float compactPlusCx = compactW - AndroidUtilities.dp(20 + 20);
        final float compactMinusCx = compactPlusCx - AndroidUtilities.dp(50);
        // flip button sits one slot left of the rocker in both layouts
        final float compactSwitchCx = compactMinusCx - AndroidUtilities.dp(50);
        trackLeft = lerp(roomyLeft, AndroidUtilities.dp(20), compact);
        // in compact the slider stops short of the flip button, not the minus button
        trackRight = lerp(roomyLeft + roomyWidth, compactSwitchCx - AndroidUtilities.dp(20 + 16), compact);
        trackY = lerp(AndroidUtilities.dp(24), AndroidUtilities.dp(52), compact);
        // roomy centers the [flip][-][+] trio (60dp apart) so the group stays under the circle's center
        switchCx = lerp(w / 2f - AndroidUtilities.dp(60), compactSwitchCx, compact);
        minusCx = lerp(w / 2f, compactMinusCx, compact);
        plusCx = lerp(w / 2f + AndroidUtilities.dp(60), compactPlusCx, compact);
        // roomy row sits at 68dp (was 76): pulling it up shortens the two-row block so it still fits
        // above the input island when a reply's top view eats into the space below the camera circle
        buttonCy = lerp(AndroidUtilities.dp(68), AndroidUtilities.dp(52), compact);
        buttonRadius = lerp(AndroidUtilities.dp(24), AndroidUtilities.dp(20), compact);
        glyphHalf = lerp(AndroidUtilities.dp(11), AndroidUtilities.dp(9), compact);
        switchGlyphHalf = lerp(AndroidUtilities.dp(13), AndroidUtilities.dp(11), compact);
    }

    // inset the knob travel by its own radius so it reaches right up to each track end without spilling past it
    private float knobInset() {
        return knobDrawable.getIntrinsicWidth() / 2f;
    }

    private float travelLeft() {
        return trackLeft + knobInset();
    }

    private float travelWidth() {
        return Math.max(1f, trackRight - trackLeft - knobInset() * 2f);
    }

    private boolean insideButton(float x, float y, int direction) {
        final float cx = direction < 0 ? minusCx : plusCx;
        final float radius = AndroidUtilities.dp(28);
        return x >= cx - radius && x <= cx + radius && y >= buttonCy - radius && y <= buttonCy + radius;
    }

    private boolean insideSwitch(float x, float y) {
        final float radius = AndroidUtilities.dp(28);
        return x >= switchCx - radius && x <= switchCx + radius && y >= buttonCy - radius && y <= buttonCy + radius;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        updateGeometry();
        final int action = event.getActionMasked();
        if (buttonPointerId != -1) {
            final int index = event.findPointerIndex(buttonPointerId);
            if (action == MotionEvent.ACTION_CANCEL || index == -1) {
                releaseButton(true);
            } else if (action == MotionEvent.ACTION_MOVE) {
                final float radius = AndroidUtilities.dp(40);
                final float cx = buttonDirection < 0 ? minusCx : plusCx;
                if (Math.abs(event.getX(index) - cx) > radius || Math.abs(event.getY(index) - buttonCy) > radius) {
                    releaseButton(true);
                }
            } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP && event.getPointerId(event.getActionIndex()) == buttonPointerId) {
                releaseButton(!insideButton(event.getX(index), event.getY(index), buttonDirection));
            }
            return true;
        }
        if (switchPointerId != -1) {
            final int index = event.findPointerIndex(switchPointerId);
            if (action == MotionEvent.ACTION_CANCEL || index == -1) {
                cancelSwitch();
            } else if (action == MotionEvent.ACTION_MOVE) {
                final float radius = AndroidUtilities.dp(40);
                if (Math.abs(event.getX(index) - switchCx) > radius || Math.abs(event.getY(index) - buttonCy) > radius) {
                    cancelSwitch();
                }
            } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP && event.getPointerId(event.getActionIndex()) == switchPointerId) {
                final boolean inside = insideSwitch(event.getX(index), event.getY(index));
                cancelSwitch();
                if (inside && delegate != null) {
                    delegate.onSwitchCamera();
                }
            }
            return true;
        }
        final float x = event.getX();
        final float y = event.getY();
        final float knobX = travelLeft() + travelWidth() * zoom;
        if (action == MotionEvent.ACTION_DOWN) {
            // the flip / -/+ buttons share a row and their 28dp hit boxes overlap; the nearest present
            // center wins. the flip button is always live; the rocker responds only when zoom is enabled.
            if (Math.abs(y - buttonCy) <= AndroidUtilities.dp(28)) {
                int hit = 0; // 2 = flip, -1 = minus, 1 = plus
                float best = AndroidUtilities.dp(28);
                float d = Math.abs(x - switchCx);
                if (d < best) { best = d; hit = 2; }
                if (zoomEnabled) {
                    d = Math.abs(x - minusCx);
                    if (d < best) { best = d; hit = -1; }
                    d = Math.abs(x - plusCx);
                    if (d < best) { best = d; hit = 1; }
                }
                if (hit == 2) {
                    switchPointerId = event.getPointerId(0);
                    switchAccent.fill = 1f;
                    switchAccent.animateTo(0.92f, 1f, 0f, 80);
                    return true;
                }
                if (hit != 0) {
                    buttonPointerId = event.getPointerId(0);
                    buttonDirection = hit;
                    final ButtonAccent accent = accent(hit);
                    accent.held = false;
                    accent.fill = 1f;
                    accent.animateTo(0.92f, 1f, 0f, 80);
                    if (delegate != null) {
                        delegate.onButtonDown(hit);
                    }
                    return true;
                }
            }
            if (zoomEnabled) {
                if (Math.abs(x - knobX) <= AndroidUtilities.dp(22) && Math.abs(y - trackY) <= AndroidUtilities.dp(24)) {
                    knobPressed = true;
                    knobOffsetX = knobX - x;
                    invalidate();
                    return true;
                }
                if (x >= trackLeft && x <= trackRight && Math.abs(y - trackY) <= AndroidUtilities.dp(24)) {
                    trackPressed = true;
                    trackDownX = x;
                    return true;
                }
            }
            return false;
        } else if (action == MotionEvent.ACTION_MOVE) {
            if (knobPressed) {
                setZoomFromTouch(x + knobOffsetX);
                return true;
            }
            return trackPressed;
        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            if (trackPressed && action == MotionEvent.ACTION_UP && Math.abs(x - trackDownX) <= AndroidUtilities.dp(10)) {
                // a tap on the rail: report the target and let the recorder animate the jump
                setZoomFromTouch(x);
            }
            final boolean handled = knobPressed || trackPressed;
            knobPressed = false;
            trackPressed = false;
            invalidate();
            return handled;
        }
        return knobPressed || trackPressed;
    }

    private void setZoomFromTouch(float x) {
        zoom = Math.max(0f, Math.min(1f, (x - travelLeft()) / travelWidth()));
        if (delegate != null) {
            delegate.didSetZoom(zoom);
        }
        invalidate();
    }

    private void releaseButton(boolean cancelled) {
        buttonPointerId = -1;
        final ButtonAccent accent = accent(buttonDirection);
        accent.animateTo(1f, 0f, 0f, accent.held ? 180 : 150);
        accent.held = false;
        if (delegate != null) {
            delegate.onButtonUp(buttonDirection, cancelled);
        }
    }

    private void cancelSwitch() {
        switchPointerId = -1;
        switchAccent.animateTo(1f, 0f, 0f, 150);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        updateGeometry();
        if (zoomEnabled) {
            final float half = AndroidUtilities.dpf2(2f);
            // the line spans only the knob's center travel, so at the ends the dot caps it and no stub shows past it
            final float lineLeft = travelLeft();
            final float lineRight = travelLeft() + travelWidth();
            final float knobX = lineLeft + (lineRight - lineLeft) * zoom;

            trackPaint.setColor(0x4DFFFFFF);
            trackRect.set(lineLeft, trackY - half, lineRight, trackY + half);
            canvas.drawRoundRect(trackRect, half, half, trackPaint);
            trackPaint.setColor(0xFFFFFFFF);
            trackRect.set(lineLeft, trackY - half, knobX, trackY + half);
            canvas.drawRoundRect(trackRect, half, half, trackPaint);

            final Drawable knob = knobPressed ? pressedKnobDrawable : knobDrawable;
            final int knobHalf = knob.getIntrinsicWidth() / 2;
            knob.setBounds((int) knobX - knobHalf, (int) trackY - knobHalf, (int) knobX + knobHalf, (int) trackY + knobHalf);
            knob.draw(canvas);

            drawButton(canvas, minusDrawable, minusAccent, minusCx, glyphHalf);
            drawButton(canvas, plusDrawable, plusAccent, plusCx, glyphHalf);
        }
        drawButton(canvas, switchDrawable, switchAccent, switchCx, switchGlyphHalf);
    }

    private void drawButton(Canvas canvas, Drawable glyph, ButtonAccent accent, float cx, float glyphHalfPx) {
        final float radius = buttonRadius * accent.scale;
        chipPaint.setColor(ColorUtils.blendARGB(chipColor, chipColorPressed, accent.fill));
        canvas.drawCircle(cx, buttonCy, radius, chipPaint);
        ringPaint.setColor(chipRimColor);
        canvas.drawCircle(cx, buttonCy, radius, ringPaint);
        if (accent.ring > 0f) {
            ringPaint.setColor(ColorUtils.setAlphaComponent(glyphColor, (int) (0x99 * accent.ring)));
            canvas.drawCircle(cx, buttonCy, radius, ringPaint);
        }
        final int gh = (int) (glyphHalfPx * accent.scale);
        glyph.setBounds((int) cx - gh, (int) (buttonCy - gh), (int) cx + gh, (int) (buttonCy + gh));
        glyph.draw(canvas);
    }
}
