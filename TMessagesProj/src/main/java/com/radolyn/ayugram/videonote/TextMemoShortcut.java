package com.radolyn.ayugram.videonote;

import android.content.Intent;
import android.graphics.Bitmap;

import androidx.core.content.pm.ShortcutInfoCompat;
import androidx.core.content.pm.ShortcutManagerCompat;
import androidx.core.graphics.drawable.IconCompat;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;

import java.util.Collections;
import java.util.List;

import xyz.nextalone.nagram.NaConfig;

/**
 * Launcher shortcut that opens TextMemoActivity, a bare box to type a message to the video memo's recipient. Published
 * through VideoNoteShortcut's MediaDataController hooks, right after the video ones.
 */
public final class TextMemoShortcut {

    static final String ACTION = "nax_text_memo";
    private static final String SHORTCUT_ID = "text_memo";
    private static final String EXTRA_HASH = "hash";

    private TextMemoShortcut() {
    }

    public static boolean isEnabled() {
        return NaConfig.INSTANCE.getTextMemoShortcut().Bool();
    }

    static void addShortcutId(List<String> wantedIds) {
        if (isEnabled()) {
            wantedIds.add(SHORTCUT_ID);
        }
    }

    static void publish(boolean recreate, List<String> existingIds, int rank) {
        if (!isEnabled() || SharedConfig.directShareHash == null) {
            return;
        }
        try {
            Intent intent = new Intent(ApplicationLoader.applicationContext, TextMemoActivity.class);
            intent.setAction(ACTION);
            intent.putExtra(EXTRA_HASH, SharedConfig.directShareHash);
            String label = LocaleController.getString(R.string.TextMemoShortcutLabel);
            ShortcutInfoCompat shortcut = new ShortcutInfoCompat.Builder(ApplicationLoader.applicationContext, SHORTCUT_ID)
                    .setShortLabel(label)
                    .setLongLabel(label)
                    .setIcon(IconCompat.createWithBitmap(createIcon()))
                    .setRank(rank)
                    .setIntent(intent)
                    .build();
            if (recreate) {
                ShortcutManagerCompat.pushDynamicShortcut(ApplicationLoader.applicationContext, shortcut);
            } else if (existingIds.contains(SHORTCUT_ID)) {
                ShortcutManagerCompat.updateShortcuts(ApplicationLoader.applicationContext, Collections.singletonList(shortcut));
            } else {
                ShortcutManagerCompat.addDynamicShortcuts(ApplicationLoader.applicationContext, Collections.singletonList(shortcut));
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    // The video memo's disc with a pencil, so the two read as a pair
    public static Bitmap createIcon() {
        return VideoNoteShortcut.createIcon(R.drawable.msg_edit);
    }

    /** The intent came from our own shortcut, the setting is still on, and the hash hasn't been rotated since. */
    static boolean isGenuine(Intent intent) {
        return intent != null && ACTION.equals(intent.getAction()) && isEnabled()
                && SharedConfig.directShareHash != null && SharedConfig.directShareHash.equals(intent.getStringExtra(EXTRA_HASH));
    }

    /** Strips what isGenuine reads, so nothing replays it. */
    static void consume(Intent intent) {
        if (intent != null) {
            intent.setAction(null);
            intent.removeExtra(EXTRA_HASH);
        }
    }
}
