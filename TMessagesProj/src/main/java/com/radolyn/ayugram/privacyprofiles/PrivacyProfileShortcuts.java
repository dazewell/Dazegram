package com.radolyn.ayugram.privacyprofiles;

import android.content.Context;
import android.content.Intent;

import androidx.core.content.pm.ShortcutInfoCompat;
import androidx.core.content.pm.ShortcutManagerCompat;
import androidx.core.graphics.drawable.IconCompat;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.ui.LaunchActivity;

import java.util.Collections;

/**
 * Requests a PINNED launcher shortcut for a privacy profile ("Add to home screen"). Deliberately
 * never touches the dynamic shortcut set {@code MediaDataController.buildShortcuts()} owns and
 * rebuilds wholesale on every update -- pinned and dynamic shortcuts are separate OS-managed
 * lists, and {@code MediaDataController.installShortcut} already pins chat shortcuts the same
 * way (requestPinShortcut), never through buildShortcuts(). See MediaDataController.java:5893-5932.
 */
public final class PrivacyProfileShortcuts {

    public static final String ACTION_ACTIVATE = "com.radolyn.ayugram.privacyprofiles.ACTIVATE";
    public static final String EXTRA_PROFILE_ID = "privacyProfileId";
    public static final String EXTRA_TOKEN = "privacyProfileToken";

    private PrivacyProfileShortcuts() {}

    public static String shortcutId(long profileId) {
        return "alp_" + profileId;
    }

    /**
     * Requests the pinned shortcut, reusing the existing token if this profile was pinned before.
     * Returns false only when this launcher doesn't support pinned shortcuts at all -- a dismissed
     * system pin dialog, or any other post-check failure, still returns true (the request was
     * legitimately made; there's nothing further this method can or should report about it).
     */
    public static boolean requestPin(PrivacyProfile profile) {
        Context context = ApplicationLoader.applicationContext;
        if (!ShortcutManagerCompat.isRequestPinShortcutSupported(context)) return false;
        String id = shortcutId(profile.id);
        // The token is the secret embedded in the shortcut's own intent -- without it, any app
        // that fires this action could switch the auto-lock timeout. Same pattern as
        // SharedConfig.directShareHash for direct-share intents. Reuse the existing token on
        // re-pin so a second "Add to home screen" tap doesn't invalidate the shortcut already
        // sitting on the launcher.
        String existingToken = PrivacyProfilesController.getShortcutToken(profile.id);
        String token = existingToken != null ? existingToken : newToken();

        ShortcutInfoCompat shortcut = buildShortcut(context, profile, id, token);
        try {
            if (!ShortcutManagerCompat.requestPinShortcut(context, shortcut, null)) return true;
        } catch (Exception e) {
            FileLog.e(e);
            return true;
        }
        // Only remember the shortcut once the OS accepted the pin request -- otherwise a
        // dismissed pin dialog or an unsupported launcher would leave the controller pointing at
        // a shortcut that was never actually created.
        PrivacyProfilesController.rememberShortcut(profile.id, id, token);
        return true;
    }

    /** Updates the pinned shortcut's label after a rename; a no-op if it was never pinned. */
    public static void updateLabel(PrivacyProfile profile) {
        Context context = ApplicationLoader.applicationContext;
        String id = PrivacyProfilesController.getShortcutId(profile.id);
        String token = PrivacyProfilesController.getShortcutToken(profile.id);
        if (id == null || token == null) return;
        // ShortcutInfoCompat.Builder.build() requires an intent to be set (it throws otherwise),
        // so this has to rebuild the full shortcut, not just the label, using the same intent and
        // token the pin was created with -- a label-only builder can never pass build().
        try {
            ShortcutManagerCompat.updateShortcuts(context, Collections.singletonList(buildShortcut(context, profile, id, token)));
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    /** Disables a deleted profile's pinned shortcut; a pinned shortcut can only be disabled, not removed. */
    public static void disable(String shortcutId) {
        if (shortcutId == null) return;
        try {
            ShortcutManagerCompat.disableShortcuts(ApplicationLoader.applicationContext,
                    Collections.singletonList(shortcutId),
                    LocaleController.getString(R.string.PrivacyProfileDeleted));
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    private static String newToken() {
        byte[] bytes = new byte[16];
        Utilities.fastRandom.nextBytes(bytes);
        return Utilities.bytesToHex(bytes);
    }

    private static ShortcutInfoCompat buildShortcut(Context context, PrivacyProfile profile, String id, String token) {
        Intent intent = new Intent(context, LaunchActivity.class);
        intent.setAction(ACTION_ACTIVATE);
        intent.putExtra(EXTRA_PROFILE_ID, profile.id);
        intent.putExtra(EXTRA_TOKEN, token);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
        return new ShortcutInfoCompat.Builder(context, id)
                .setShortLabel(profile.name)
                .setLongLabel(profile.name)
                .setIcon(IconCompat.createWithBitmap(PrivacyProfileIcons.circleBitmap(context, profile, 72)))
                .setIntent(intent)
                .build();
    }
}
