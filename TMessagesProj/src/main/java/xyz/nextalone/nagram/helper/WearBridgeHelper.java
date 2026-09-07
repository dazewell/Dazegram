package xyz.nextalone.nagram.helper;

import android.content.SharedPreferences;

import org.telegram.messenger.MessagesController;

// NagramX: owns the key format for the per-chat "Show on Watch" toggle so the two notification-build hooks
// can't disagree on it. The settings screen still repeats the "nax_wear_" + dialogId literal inline for its
// read and write, so a rename of the key has to touch it too, not just this helper. Keyed by raw dialogId,
// not the topic-aware getSharedPrefKey:
// a notification batch's DialogKey carries whichever topic pushed into the bucket first, so a topic-aware read
// would bridge non-deterministically -- which is also why the settings row is hidden on topic screens. Default
// true reproduces today's unconditional Wear bridging, so nobody's watch goes quiet on upgrade. The prefs
// instance is the same one MessagesController.getNotificationsSettings(account) hands the settings screen, so a
// toggle is visible to the next notification build with no sync step.
public final class WearBridgeHelper {

    private WearBridgeHelper() {
    }

    public static boolean isWatchEnabled(int account, long dialogId) {
        SharedPreferences preferences = MessagesController.getNotificationsSettings(account);
        return preferences.getBoolean("nax_wear_" + dialogId, true);
    }
}
