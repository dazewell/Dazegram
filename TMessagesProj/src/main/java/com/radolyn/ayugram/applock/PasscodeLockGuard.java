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
 * <p>{@link SharedConfig#lastPauseTime} plus {@code ForegroundDetector.isBackground()} tell the
 * same story without being consumable: a level read of the activity ref count, not a one-shot
 * flag, so it can't be raced away by a competing caller.
 */
public final class PasscodeLockGuard {

    private PasscodeLockGuard() {
    }

    public static boolean missedLock() {
        return SharedConfig.passcodeHash.length() > 0 && SharedConfig.lastPauseTime != 0
                && ForegroundDetector.getInstance() != null && ForegroundDetector.getInstance().isBackground();
    }
}
