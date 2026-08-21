package xyz.nextalone.nagram.helper;

import android.media.AudioAttributes;
import android.os.Build;
import android.os.VibrationAttributes;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.view.HapticFeedbackConstants;
import android.view.View;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;

import tw.nekomimi.nekogram.NekoConfig;

// NagramX: shared single-pulse buzz for the round-video recording-limit warning (ChatActivityEnterView's
// TimerView, fired twice ~78ms apart) and cutoff (InstantCameraView's rollover and auto-stop, fired once),
// so their durations, amplitudes and attributes can't drift apart between the two call sites.
//
// Deliberately not added to BotWebViewVibrationEffect: its IMPACT_LIGHT/MEDIUM/HEAVY constants are 7ms
// mini-app touch-feedback taps, felt only because a finger is already on the glass when they fire -- far
// too short for a put-the-phone-down-and-keep-talking alert. That enum is also shared bot-mini-app
// surface; every constant added there is merge surface for something this feature doesn't touch.
public final class RecordingLimitVibration {

    private RecordingLimitVibration() {
    }

    public static final int OFF = 0;
    public static final int LIGHT = 1;
    public static final int MEDIUM = 2;
    public static final int STRONG = 3;

    // on-duration in ms and amplitude (1-255) per level, indexed by level - 1. Tens of ms, not
    // BotWebViewVibrationEffect's 7ms -- that duration jump is what actually fixes the "felt nothing"
    // report, amplitude is secondary polish on top of it.
    private static final long[] DURATION_MS = {30, 50, 80};
    private static final int[] AMPLITUDE = {120, 180, 255};

    // fallback constants for when the Vibrator route comes back dead. No per-level duration/amplitude
    // control this way -- just three progressively more assertive HapticFeedbackConstants -- but a real
    // buzz beats silence.
    private static final int[] HAPTIC_FEEDBACK_CONSTANT = {
            HapticFeedbackConstants.CLOCK_TICK,
            HapticFeedbackConstants.KEYBOARD_TAP,
            HapticFeedbackConstants.LONG_PRESS,
    };

    // NagramX: gap between the warning's two taps. Shared with ChatActivityEnterView.TimerView's guarded
    // scheduling (see fireWarningPreview below) so the real rhythm and the settings preview can't drift apart.
    public static final long WARNING_DOUBLE_TAP_GAP_MS = 78;

    // NagramX: both current callers (InstantCameraView's fireCutVibration and ChatActivityEnterView's
    // TimerView.fireLimitWarningVibration) already run on the UI thread, which performHapticFeedback
    // requires -- this doesn't dispatch anywhere itself, so calling it off the UI thread is on the caller.
    public static void fire(int level, View view) {
        if (level < LIGHT || level > STRONG) {
            return; // 0 = off, and anything out of range
        }
        if (NekoConfig.disableVibration.Bool()) {
            // BotWebViewVibrationEffect.vibrate() applied this gate for every caller that used it; now
            // that this builds its own VibrationEffect instead of going through it, it has to be repeated
            // here or the global off switch silently stops covering both buzzes.
            return;
        }
        if (fireVibrator(level)) {
            return;
        }
        // NagramX: the Vibrator route came back dead -- no vibrator, hasVibrator() false, or vibrate()
        // itself threw. Confirmed on a real device that performHapticFeedback with
        // FLAG_IGNORE_VIEW_SETTING still fires under a condition (Do Not Disturb) where a bare
        // Vibrator.vibrate() call silently didn't, so falling back to it beats going silent. Loses the
        // per-level duration/amplitude control above -- HAPTIC_FEEDBACK_CONSTANT is just three
        // progressively stronger constants, not tuned to the same ms/amplitude pairs.
        if (view != null) {
            view.performHapticFeedback(HAPTIC_FEEDBACK_CONSTANT[level - 1], HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING);
        }
    }

    // NagramX: settings-screen preview for the warning -- reproduces the same ~78ms two-tap rhythm as
    // ChatActivityEnterView.TimerView.fireLimitWarningVibration() but without its capturedStartTime guard,
    // since there's no recording here for that guard to protect against a stop/pause landing mid-gap.
    public static void fireWarningPreview(int level, View view) {
        fire(level, view);
        AndroidUtilities.runOnUIThread(() -> fire(level, view), WARNING_DOUBLE_TAP_GAP_MS);
    }

    // level is already validated 1-3 by fire() above. Returns true if the vibrator ran without error,
    // false if the caller should fall back to performHapticFeedback instead.
    private static boolean fireVibrator(int level) {
        Vibrator vibrator = AndroidUtilities.getVibrator();
        if (vibrator == null || !vibrator.hasVibrator()) {
            return false;
        }
        try {
            long duration = DURATION_MS[level - 1];
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // hasAmplitudeControl() == false doesn't need a separate fallback array here: createWaveform's
                // 3-arg (timings + amplitudes) overload maps each timing straight to its amplitude rather than
                // assuming an off-first on/off pattern, so on a device without amplitude control it still
                // vibrates for the full `duration` at that device's default strength -- it just can't scale the
                // strength. Duration is what makes these perceptible over the old 7ms pulses, so this degrades
                // sensibly without any extra branching.
                VibrationEffect effect = VibrationEffect.createWaveform(new long[]{duration}, new int[]{AMPLITUDE[level - 1]}, -1);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    // USAGE_TOUCH, not USAGE_NOTIFICATION: confirmed on a real device that notification-class
                    // vibration is muted by Do Not Disturb while performHapticFeedback -- which the platform
                    // itself issues as USAGE_TOUCH -- isn't. That's also the right category on its own merits,
                    // not just the one DND leaves alone: this is app-driven interaction feedback during an
                    // active recording, not a notification. USAGE_HARDWARE_FEEDBACK is for things like
                    // fingerprint-sensor acknowledgement and USAGE_PHYSICAL_EMULATION for simulated hardware
                    // controls (e.g. an edge squeeze) -- neither matches on-screen interaction feedback.
                    vibrator.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_TOUCH));
                } else {
                    // AudioAttributes has no touch/haptic-feedback usage. USAGE_ASSISTANCE_SONIFICATION with
                    // CONTENT_TYPE_SONIFICATION is the conventional pairing for app-generated haptics on these
                    // API levels, and USAGE_NOTIFICATION is out for the same DND reason as above.
                    vibrator.vibrate(effect, new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build());
                }
            } else {
                // dead code on this app's minSdk 27, kept anyway: the classic pattern below is off-first, so a
                // single-element array would be silent -- {0, duration} is off for 0ms then on for `duration`.
                vibrator.vibrate(new long[]{0, duration}, -1);
            }
            return true;
        } catch (Exception e) {
            FileLog.e(e);
            return false;
        }
    }
}
