package com.radolyn.ayugram.applock;

import org.telegram.messenger.SharedConfig;

/**
 * Backstop for the app passcode's auto-lock check.
 *
 * <p>{@code AndroidUtilities.needShowPasscode(true)} gates locking on
 * {@code ForegroundDetector}'s process-wide {@code wasInBackground} flag, which that same
 * call unconditionally consumes -- even when it returns false. The scheduled lock check in
 * {@code LaunchActivity} only runs once, so if that single read loses the race (the flag
 * already got cleared, or was never set for this pause), nothing else ever re-checks: the app
 * stays unlocked until the user taps the manual lock icon or force-stops it.
 *
 * <p>{@link SharedConfig#lastPauseTime} is a truthful, narrower signal for the same question
 * ("did we pause and never come back") -- it's stamped by the pause site itself and cleared
 * only on resume, so it can't be eaten by a competing {@code needShowPasscode} caller. Using it
 * as a second, ORed condition means a missed {@code wasInBackground} read no longer permanently
 * skips the lock.
 */
public final class PasscodeLockGuard {

    private PasscodeLockGuard() {
    }

    public static boolean missedLock() {
        return SharedConfig.passcodeHash.length() > 0 && SharedConfig.lastPauseTime != 0;
    }
}
