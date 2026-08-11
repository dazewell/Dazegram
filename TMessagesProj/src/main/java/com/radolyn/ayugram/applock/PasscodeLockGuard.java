package com.radolyn.ayugram.applock;

import org.telegram.messenger.SharedConfig;
import org.telegram.ui.Components.ForegroundDetector;

/**
 * Backstop for the app passcode's auto-lock check.
 *
 * <p>{@code AndroidUtilities.needShowPasscode(true)} gates locking on
 * {@code ForegroundDetector}'s {@code wasInBackground} flag, which that same call
 * unconditionally consumes -- even when it returns false. The scheduled lock check in
 * {@code LaunchActivity} only runs once, so if that single read loses the race (the flag
 * already got cleared, or was never set for this pause), nothing else ever re-checks: the app
 * stays unlocked until the user taps the manual lock icon or force-stops it.
 *
 * <p>{@code ForegroundDetector.isBackground()} is a plain read of the current activity ref
 * count, not a one-shot flag -- checking it here can't itself be raced away by a competing
 * {@code needShowPasscode} caller. Combined with {@link SharedConfig#lastPauseTime} (stamped by
 * the pause site, and reset once the pause is resolved -- normally by resuming, but also by a
 * few other paths such as an intent handled with the passcode already entered) it tells us the
 * process is genuinely backgrounded and has stayed that way since the pause, which is what
 * should actually trigger a lock -- unlike {@code lastPauseTime} alone, which also gets set on
 * an in-app activity swap that never leaves the foreground.
 */
public final class PasscodeLockGuard {

    private PasscodeLockGuard() {
    }

    public static boolean missedLock() {
        return SharedConfig.passcodeHash.length() > 0 && SharedConfig.lastPauseTime != 0
                && ForegroundDetector.getInstance() != null && ForegroundDetector.getInstance().isBackground();
    }
}
