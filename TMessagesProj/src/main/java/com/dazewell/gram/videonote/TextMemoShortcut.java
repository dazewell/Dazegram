package com.dazewell.gram.videonote;

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
 * Launcher shortcuts that open a card to type a message to the video memo's recipient: TextMemoActivity over the chat
 * wallpaper, and TextMemoCardActivity floating over whatever is on screen. Both are always offered together, so which
 * to use is the user's call at the moment. Published through VideoNoteShortcut's MediaDataController hooks, right after
 * the video ones.
 */
public final class TextMemoShortcut {

    static final String ACTION = "nax_text_memo";
    // The wallpaper one keeps the id the single shortcut had, so a copy pinned before the second one existed stays put
    private static final String SHORTCUT_ID = "text_memo";
    private static final String SHORTCUT_ID_CARD = "text_memo_card";
    private static final String EXTRA_HASH = "hash";

    private TextMemoShortcut() {
    }

    public static boolean isEnabled() {
        return NaConfig.INSTANCE.getTextMemoShortcut().Bool();
    }

    /** One label for both lengths: a launcher picks by width, and two that differ read as two styles. */
    public static String getLabel(boolean card) {
        return LocaleController.getString(card ? R.string.TextMemoShortcutLabelCard : R.string.TextMemoShortcutLabelFull);
    }

    static void addShortcutId(List<String> wantedIds) {
        if (isEnabled()) {
            wantedIds.add(SHORTCUT_ID);
            wantedIds.add(SHORTCUT_ID_CARD);
        }
    }

    // Two ranks, one per shortcut
    static void publish(boolean recreate, List<String> existingIds, int rank) {
        if (!isEnabled() || SharedConfig.directShareHash == null) {
            return;
        }
        publish(false, recreate, existingIds, rank);
        publish(true, recreate, existingIds, rank + 1);
    }

    // Own try so a launcher rejecting one can't abort the other
    private static void publish(boolean card, boolean recreate, List<String> existingIds, int rank) {
        String id = card ? SHORTCUT_ID_CARD : SHORTCUT_ID;
        try {
            Intent intent = createIntent(card);
            ShortcutInfoCompat shortcut = new ShortcutInfoCompat.Builder(ApplicationLoader.applicationContext, id)
                    .setShortLabel(getLabel(card))
                    .setLongLabel(getLabel(card))
                    .setIcon(IconCompat.createWithBitmap(createIcon()))
                    .setRank(rank)
                    .setIntent(intent)
                    .build();
            if (recreate) {
                ShortcutManagerCompat.pushDynamicShortcut(ApplicationLoader.applicationContext, shortcut);
            } else if (existingIds.contains(id)) {
                ShortcutManagerCompat.updateShortcuts(ApplicationLoader.applicationContext, Collections.singletonList(shortcut));
            } else {
                ShortcutManagerCompat.addDynamicShortcuts(ApplicationLoader.applicationContext, Collections.singletonList(shortcut));
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    /** The intent a launcher shortcut or MemoPickerActivity starts, carrying the current hash. */
    static Intent createIntent(boolean card) {
        // The window theme can't change once an activity is open, so the card is its own activity
        Intent intent = new Intent(ApplicationLoader.applicationContext, card ? TextMemoCardActivity.class : TextMemoActivity.class);
        intent.setAction(ACTION);
        intent.putExtra(EXTRA_HASH, SharedConfig.directShareHash);
        return intent;
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
