package com.radolyn.ayugram.videonote;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.animation.ValueAnimator;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.Drawable;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.LinearInterpolator;

import androidx.core.graphics.ColorUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.HintsController;
import org.telegram.ui.Components.InstantCameraView;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Stories.recorder.HintView2;

import tw.nekomimi.nekogram.NekoConfig;
import xyz.nextalone.nagram.NaConfig;

/**
 * Press and hold the round video circle to send a hands-free (locked) recording, so the send doesn't need the
 * small button in the corner. A disc fills the circle from its centre while held; once full, letting go sends.
 * Letting go early, sliding off the circle or a second finger (pinch zoom) cancels and the recording carries on.
 * The rim is left alone on purpose: it already carries the recording-time arc.
 * UI thread only.
 */
public final class VideoHoldToSend {

    private static final long ARM_MS = 450;
    private static final long CANCEL_MS = 150;
    private static final String HINT_TAG = "nax_video_hold_send_hint";

    private final Utilities.Callback0Return<Boolean> canSend;
    private final Runnable send;

    private View circle;
    private boolean tracking;
    private boolean armed;
    private float progress;
    private ValueAnimator animator;
    private Paint discPaint;
    private Drawable arrow;

    private final Runnable armRunnable = this::arm;

    public VideoHoldToSend(Utilities.Callback0Return<Boolean> canSend, Runnable send) {
        this.canSend = canSend;
        this.send = send;
    }

    // Observes the host's touch stream and never consumes it, so the host's own pinch-to-zoom keeps working.
    public void onTouchEvent(MotionEvent ev, View circle) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                reset();
                if (!NaConfig.INSTANCE.getVideoMessagesHoldToSend().Bool() || !contains(circle, ev) || !canSend.run()) {
                    return;
                }
                this.circle = circle;
                tracking = true;
                hideHint(circle);
                animateTo(1f, ARM_MS);
                AndroidUtilities.runOnUIThread(armRunnable, ARM_MS);
                break;
            case MotionEvent.ACTION_POINTER_DOWN:
                cancel();
                break;
            case MotionEvent.ACTION_MOVE:
                if (tracking && !contains(circle, ev)) {
                    cancel();
                }
                break;
            case MotionEvent.ACTION_UP:
                if (tracking && armed && canSend.run()) {
                    // cleared before sending: a paid-message confirmation pauses into the preview instead of
                    // closing the camera, and the disc must not stay painted over it
                    reset();
                    send.run();
                } else {
                    cancel();
                }
                break;
            case MotionEvent.ACTION_CANCEL:
                cancel();
                break;
        }
    }

    // Drops any hold at once, without the shrink animation. For the host's camera open/close, pause and
    // segment rollover.
    public void reset() {
        AndroidUtilities.cancelRunOnUIThread(armRunnable);
        tracking = false;
        armed = false;
        if (animator != null) {
            animator.cancel();
            animator = null;
        }
        if (progress != 0f) {
            progress = 0f;
            if (circle != null) {
                circle.invalidate();
            }
        }
    }

    public void draw(Canvas canvas, View circle) {
        if (progress <= 0f) {
            return;
        }
        final float cx = circle.getWidth() / 2f;
        final float cy = circle.getHeight() / 2f;
        final float radius = Math.min(cx, cy) * CubicBezierInterpolator.EASE_OUT.getInterpolation(progress);
        if (discPaint == null) {
            discPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        }
        discPaint.setColor(ColorUtils.setAlphaComponent(Theme.getColor(Theme.key_chat_messagePanelVoiceBackground), 0x99));
        canvas.drawCircle(cx, cy, radius, discPaint);

        if (arrow == null) {
            arrow = circle.getContext().getResources().getDrawable(R.drawable.attach_send).mutate();
            arrow.setColorFilter(new PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN));
        }
        final float arrowAlpha = Utilities.clamp((progress - 0.3f) / 0.7f, 1f, 0f);
        final int half = (int) (dp(24) * (0.8f + 0.2f * arrowAlpha));
        arrow.setAlpha((int) (255 * arrowAlpha));
        arrow.setBounds((int) cx - half, (int) cy - half, (int) cx + half, (int) cy + half);
        arrow.draw(canvas);
    }

    // Called once per lock of a round video recording; shows the "hold to send" hint above the circle a few times.
    public static void onRecordLocked(InstantCameraView cameraView) {
        if (cameraView == null || !NaConfig.INSTANCE.getVideoMessagesHoldToSend().Bool()) {
            return;
        }
        final View circle = cameraView.getCameraContainer();
        if (circle == null || circle.getHeight() == 0 || cameraView.findViewWithTag(HINT_TAG) != null) {
            return;
        }
        final HintsController.Hint counter = HintsController.Hint.VideoHoldToSendHint;
        if (!counter.show()) {
            return;
        }
        final HintView2 hint = new HintView2(cameraView.getContext(), HintView2.DIRECTION_BOTTOM);
        hint.setTag(HINT_TAG);
        hint.setRounding(13);
        hint.setJoint(0.5f, 0);
        hint.setText(LocaleController.getString(R.string.VideoMessagesHoldToSendHint));
        hint.setDuration(4000L);
        hint.setOnHiddenListener(() -> cameraView.removeView(hint));
        cameraView.addView(hint, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 200, Gravity.TOP));
        hint.setTranslationY(circle.getY() - dp(200) + dp(4));
        hint.show();
        counter.increment();
    }

    private void arm() {
        if (!tracking) {
            return;
        }
        if (!canSend.run()) {
            cancel();
            return;
        }
        armed = true;
        if (circle != null && !NekoConfig.disableVibration.Bool()) {
            try {
                circle.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP, HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING);
            } catch (Exception ignored) {
            }
        }
    }

    private void cancel() {
        AndroidUtilities.cancelRunOnUIThread(armRunnable);
        if (!tracking && !armed) {
            return;
        }
        tracking = false;
        armed = false;
        animateTo(0f, CANCEL_MS);
    }

    private void animateTo(float target, long duration) {
        if (animator != null) {
            animator.cancel();
        }
        final View view = circle;
        animator = ValueAnimator.ofFloat(progress, target);
        animator.setDuration(duration);
        animator.setInterpolator(new LinearInterpolator());
        animator.addUpdateListener(a -> {
            progress = (float) a.getAnimatedValue();
            if (view != null) {
                view.invalidate();
            }
        });
        animator.start();
    }

    private static boolean contains(View circle, MotionEvent ev) {
        // same bounds the host's pinch-to-zoom uses
        AndroidUtilities.rectTmp.set(circle.getX(), circle.getY(), circle.getX() + circle.getMeasuredWidth(), circle.getY() + circle.getMeasuredHeight());
        return AndroidUtilities.rectTmp.contains(ev.getX(), ev.getY());
    }

    private static void hideHint(View circle) {
        if (circle.getParent() instanceof View) {
            final View hint = ((View) circle.getParent()).findViewWithTag(HINT_TAG);
            if (hint instanceof HintView2) {
                ((HintView2) hint).hide();
            }
        }
    }
}
