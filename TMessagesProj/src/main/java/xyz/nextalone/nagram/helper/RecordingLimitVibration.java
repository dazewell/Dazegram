package xyz.nextalone.nagram.helper;

import android.media.AudioAttributes;
import android.os.Build;
import android.os.VibrationAttributes;
import android.os.VibrationEffect;
import android.os.Vibrator;

import org.telegram.messenger.AndroidUtilities;

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

    public static void fire(int level) {
        if (level < LIGHT || level > STRONG) {
            return; // 0 = off, and anything out of range
        }
        if (NekoConfig.disableVibration.Bool()) {
            // BotWebViewVibrationEffect.vibrate() applied this gate for every caller that used it; now
            // that these two buzzes build their own VibrationEffect instead of going through it, it has
            // to be repeated here or the global off switch silently stops covering them.
            return;
        }
        Vibrator vibrator = AndroidUtilities.getVibrator();
        if (vibrator == null) {
            return;
        }
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
                vibrator.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_NOTIFICATION));
            } else {
                // USAGE_NOTIFICATION, not USAGE_ALARM: this is an in-app heads-up during an active recording,
                // not a system alarm, and it's the usage NotificationsController already vibrates message
                // notifications with -- unlike USAGE_UNKNOWN (what calling vibrate() with no attributes at
                // all gets you), it isn't tied to the touch-feedback setting that was eating these buzzes.
                vibrator.vibrate(effect, new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION).build());
            }
        } else {
            // dead code on this app's minSdk 27, kept anyway: the classic pattern below is off-first, so a
            // single-element array would be silent -- {0, duration} is off for 0ms then on for `duration`.
            vibrator.vibrate(new long[]{0, duration}, -1);
        }
    }
}
