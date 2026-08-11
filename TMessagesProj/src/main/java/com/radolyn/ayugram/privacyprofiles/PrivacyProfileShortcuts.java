package com.radolyn.ayugram.privacyprofiles;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Build;

import androidx.core.content.pm.ShortcutInfoCompat;
import androidx.core.content.pm.ShortcutManagerCompat;
import androidx.core.graphics.drawable.IconCompat;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.ui.Components.AvatarDrawable;
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

    /** Requests the pinned shortcut; no-op below API 26 (ShortcutManagerCompat requires it). */
    public static void requestPin(PrivacyProfile profile) {
        if (Build.VERSION.SDK_INT < 26) return;
        Context context = ApplicationLoader.applicationContext;
        String id = shortcutId(profile.id);
        // The token is the secret embedded in the shortcut's own intent -- without it, any app
        // that fires this action could switch the auto-lock timeout. Same pattern as
        // SharedConfig.directShareHash for direct-share intents.
        byte[] bytes = new byte[16];
        Utilities.fastRandom.nextBytes(bytes);
        String token = Utilities.bytesToHex(bytes);
        PrivacyProfilesController.rememberShortcut(profile.id, id, token);

        Intent intent = new Intent(context, LaunchActivity.class);
        intent.setAction(ACTION_ACTIVATE);
        intent.putExtra(EXTRA_PROFILE_ID, profile.id);
        intent.putExtra(EXTRA_TOKEN, token);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);

        ShortcutInfoCompat.Builder builder = new ShortcutInfoCompat.Builder(context, id)
                .setShortLabel(profile.name)
                .setLongLabel(profile.name)
                .setIcon(IconCompat.createWithBitmap(letterAvatarBitmap(profile)))
                .setIntent(intent);
        try {
            ShortcutManagerCompat.requestPinShortcut(context, builder.build(), null);
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    /** Updates the pinned shortcut's label after a rename; a no-op if it was never pinned. */
    public static void updateLabel(PrivacyProfile profile) {
        if (Build.VERSION.SDK_INT < 26) return;
        String id = PrivacyProfilesController.getShortcutId(profile.id);
        if (id == null) return;
        Context context = ApplicationLoader.applicationContext;
        ShortcutInfoCompat.Builder builder = new ShortcutInfoCompat.Builder(context, id)
                .setShortLabel(profile.name)
                .setLongLabel(profile.name);
        try {
            ShortcutManagerCompat.updateShortcuts(context, Collections.singletonList(builder.build()));
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    /** Disables a deleted profile's pinned shortcut; a pinned shortcut can only be disabled, not removed. */
    public static void disable(String shortcutId) {
        if (Build.VERSION.SDK_INT < 26 || shortcutId == null) return;
        try {
            ShortcutManagerCompat.disableShortcuts(ApplicationLoader.applicationContext,
                    Collections.singletonList(shortcutId),
                    LocaleController.getString(R.string.PrivacyProfileDeleted));
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    private static Bitmap letterAvatarBitmap(PrivacyProfile profile) {
        int size = org.telegram.messenger.AndroidUtilities.dp(72);
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        AvatarDrawable drawable = new AvatarDrawable();
        drawable.setInfo(profile.colorSeed, profile.name, null);
        drawable.setBounds(0, 0, size, size);
        drawable.draw(canvas);
        return bitmap;
    }
}
