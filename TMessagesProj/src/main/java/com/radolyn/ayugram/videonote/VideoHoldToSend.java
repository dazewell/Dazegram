package com.radolyn.ayugram.videonote;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.InsetDrawable;
import android.media.AudioAttributes;
import android.os.Build;
import android.os.SystemClock;
import android.os.VibrationAttributes;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.text.TextPaint;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.LinearInterpolator;
import android.widget.ImageView;

import androidx.core.graphics.ColorUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.VideoPlayer;

import tw.nekomimi.nekogram.NekoConfig;
import xyz.nextalone.nagram.NaConfig;

/**
 * Press and hold the round video circle to send a hands-free (locked) recording, or the paused preview it
 * stopped into, so the send doesn't need the small button in the corner. A disc fills the circle from its centre
 * while held, with a buzz that grows with it; once full, letting go sends. Letting go early, sliding off the
 * circle or a second finger (pinch zoom) cancels and nothing changes. The rim is left alone on purpose: it
 * already carries the recording-time arc.
 * A small label above the circle says how it works whenever it's available.
 * The preview used to toggle its sound on any tap, which the hold now owns, so the host's mute glyph becomes a
 * sound button on the circle's edge for as long as the preview is up.
 * UI thread only.
 */
public final class VideoHoldToSend {

    private static final long ARM_MS = 450;
    private static final long CANCEL_MS = 150;
    private static final float LABEL_FADE_PER_MS = 1f / 150f;

    private final Utilities.Callback0Return<Boolean> canSend;
    private final Runnable send;
    private final Utilities.Callback0Return<VideoPlayer> previewPlayer;
    private final Utilities.Callback0Return<Boolean> previewSends;
    private Drawable soundBackground;

    private View circle;
    private View host;
    private boolean tracking;
    private boolean armed;
    private boolean buzzing;
    private float progress;
    private ValueAnimator animator;
    private Paint discPaint;
    private Drawable arrow;

    private TextPaint labelPaint;
    private Paint labelBackground;
    private final RectF labelRect = new RectF();
    private float labelAlpha;
    private long labelLastDraw;

    private final Runnable armRunnable = this::arm;

    // previewPlayer is the paused preview's player, null while there's no preview. previewSends says whether the
    // host can send from its preview at all; story replies can't, and keep tap-to-toggle-sound.
    public VideoHoldToSend(Utilities.Callback0Return<Boolean> canSend, Runnable send,
                           Utilities.Callback0Return<VideoPlayer> previewPlayer, Utilities.Callback0Return<Boolean> previewSends) {
        this.canSend = canSend;
        this.send = send;
        this.previewPlayer = previewPlayer;
        this.previewSends = previewSends;
    }

    // True when a tap on the preview belongs to the hold rather than toggling its sound.
    public boolean ownsPreviewTaps() {
        return NaConfig.INSTANCE.getVideoMessagesHoldToSend().Bool() && previewSends.run();
    }

    // The host's mute glyph, which the host still styles as before. It only takes touches (and shows up for
    // accessibility) while there's a preview it owns, read live so it can't outlive the preview and sit
    // invisibly on the circle during the next recording.
    public ImageView createSoundButton(Context context) {
        final ImageView button = new ImageView(context) {
            @Override
            public boolean onTouchEvent(MotionEvent event) {
                return soundButtonActive() && super.onTouchEvent(event);
            }

            @Override
            public boolean performClick() {
                return soundButtonActive() && super.performClick();
            }

            @Override
            public boolean isImportantForAccessibility() {
                return soundButtonActive() && super.isImportantForAccessibility();
            }
        };
        button.setOnClickListener(v -> {
            final VideoPlayer player = previewPlayer.run();
            if (player != null) {
                player.setMute(!player.isMuted());
                setSoundIcon((ImageView) v, player.isMuted());
            }
        });
        return button;
    }

    // On entering the preview: a sound button on the circle's edge, or with the feature off, the host's own
    // centred glyph exactly as it was.
    public void showSoundButton(ImageView button, View circle) {
        if (ownsPreviewTaps()) {
            if (soundBackground == null) {
                soundBackground = new InsetDrawable(Theme.createSimpleSelectorCircleDrawable(dp(40), 0x4d000000, 0x33ffffff), dp(4));
            }
            button.setBackground(soundBackground);
            button.setScaleX(1f);
            button.setScaleY(1f);
            final VideoPlayer player = previewPlayer.run();
            setSoundIcon(button, player == null || player.isMuted());
        } else {
            button.setBackground(null);
            button.setImageResource(R.drawable.video_mute);
            button.setContentDescription(null);
        }
        layoutSoundButton(button, circle);
    }

    // Keeps the sound button on the circle's bottom-end edge, mostly outside it, in the corner of the circle's
    // bounds that isn't video. The button is laid out centred with a top margin, the circle centred and translated.
    public void layoutSoundButton(View button, View circle) {
        if (!ownsPreviewTaps()) {
            button.setTranslationX(0);
            button.setTranslationY(0);
            return;
        }
        final float offset = (circle.getLayoutParams().width / 2f + dp(12)) * 0.7071f;
        final int topMargin = ((ViewGroup.MarginLayoutParams) button.getLayoutParams()).topMargin;
        button.setTranslationX(circle.getTranslationX() + (LocaleController.isRTL ? -offset : offset));
        button.setTranslationY(circle.getTranslationY() + offset - topMargin);
    }

    private boolean soundButtonActive() {
        return previewPlayer.run() != null && ownsPreviewTaps();
    }

    // Same icon for the same state as the story editor's mute button.
    private static void setSoundIcon(ImageView button, boolean muted) {
        button.setImageResource(muted ? R.drawable.media_unmute : R.drawable.media_mute);
        button.setContentDescription(LocaleController.getString(muted ? R.string.Unmute : R.string.Mute));
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
                startFillBuzz();
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
                // a fast slide-off can report its last position only in the UP, with no MOVE outside first
                if (tracking && armed && contains(circle, ev) && canSend.run()) {
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
        stopFillBuzz();
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
        // the host stops redrawing itself once it stops recording, so let the label see that and fade out
        if (host != null) {
            host.invalidate();
        }
    }

    // The fill, drawn inside the circle so it sits over the camera.
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

    // The how-to label, drawn by the host above the circle, clear of the recording-time arc. The host keeps at
    // least 64dp free above the circle, which this fits in.
    public void drawLabel(Canvas canvas, View host, View circle) {
        this.host = host;
        final boolean visible = NaConfig.INSTANCE.getVideoMessagesHoldToSend().Bool() && canSend.run();
        final long now = SystemClock.elapsedRealtime();
        final long dt = labelLastDraw == 0 ? 16 : Math.min(64, now - labelLastDraw);
        labelLastDraw = now;
        final float target = visible ? 1f : 0f;
        if (labelAlpha != target) {
            labelAlpha = visible ? Math.min(1f, labelAlpha + dt * LABEL_FADE_PER_MS) : Math.max(0f, labelAlpha - dt * LABEL_FADE_PER_MS);
            host.invalidate();
        }
        final float alpha = labelAlpha * circle.getAlpha();
        if (alpha <= 0f) {
            labelLastDraw = 0;
            return;
        }
        if (labelPaint == null) {
            labelPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
            labelPaint.setTextSize(dp(13));
            labelPaint.setTypeface(AndroidUtilities.bold());
            labelPaint.setColor(Color.WHITE);
            labelBackground = new Paint(Paint.ANTI_ALIAS_FLAG);
            labelBackground.setColor(0x4d000000);
        }
        final String text = LocaleController.getString(armed ? R.string.VideoMessagesHoldToSendArmed : R.string.VideoMessagesHoldToSend);
        final float textWidth = labelPaint.measureText(text);
        final float cx = circle.getX() + circle.getWidth() / 2f;
        final float bottom = circle.getY() - dp(20);
        labelRect.set(cx - textWidth / 2f - dp(10), bottom - dp(24), cx + textWidth / 2f + dp(10), bottom);
        labelBackground.setAlpha((int) (0x4d * alpha));
        labelPaint.setAlpha((int) (255 * alpha));
        canvas.drawRoundRect(labelRect, dp(12), dp(12), labelBackground);
        canvas.drawText(text, cx - textWidth / 2f, labelRect.centerY() - (labelPaint.descent() + labelPaint.ascent()) / 2f, labelPaint);
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
        if (circle != null) {
            // the waveform ends in its own tap; only a dead vibrator route needs this
            if (!buzzing && !NekoConfig.disableVibration.Bool()) {
                try {
                    circle.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP, HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING);
                } catch (Exception ignored) {
                }
            }
            if (host != null) {
                host.invalidate(); // the label switches to its armed text
            }
        }
        // what's left of the waveform is that tap, which a quick release or slide-off shouldn't cut short
        buzzing = false;
    }

    private void cancel() {
        AndroidUtilities.cancelRunOnUIThread(armRunnable);
        stopFillBuzz();
        if (!tracking && !armed) {
            return;
        }
        tracking = false;
        armed = false;
        animateTo(0f, CANCEL_MS);
        if (host != null) {
            host.invalidate();
        }
    }

    // One waveform for the whole hold, so it tracks the fill without timers: rising strength over ARM_MS, then a
    // firm tap as the circle fills. Without amplitude control the same span is ticks that come closer together.
    private void startFillBuzz() {
        buzzing = false;
        if (NekoConfig.disableVibration.Bool()) {
            return;
        }
        final Vibrator vibrator = AndroidUtilities.getVibrator();
        if (vibrator == null || !vibrator.hasVibrator()) {
            return;
        }
        final long[] timings;
        final int[] amplitudes;
        if (vibrator.hasAmplitudeControl()) {
            timings = new long[]{50, 50, 50, 50, 50, 50, 50, 50, 50, 40};
            amplitudes = new int[]{8, 16, 26, 38, 52, 68, 86, 106, 128, 255};
        } else {
            timings = new long[]{90, 8, 70, 8, 55, 8, 42, 8, 32, 8, 24, 8, 18, 8, 12, 8, 40};
            amplitudes = new int[]{0, 255, 0, 255, 0, 255, 0, 255, 0, 255, 0, 255, 0, 255, 0, 255, 255};
        }
        try {
            final VibrationEffect effect = VibrationEffect.createWaveform(timings, amplitudes, -1);
            // same attributes as RecordingLimitVibration, for the same Do Not Disturb reason
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                vibrator.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_TOUCH));
            } else {
                vibrator.vibrate(effect, new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build());
            }
            buzzing = true;
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    private void stopFillBuzz() {
        if (!buzzing) {
            return;
        }
        buzzing = false;
        final Vibrator vibrator = AndroidUtilities.getVibrator();
        if (vibrator != null) {
            try {
                vibrator.cancel();
            } catch (Exception e) {
                FileLog.e(e);
            }
        }
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
}
