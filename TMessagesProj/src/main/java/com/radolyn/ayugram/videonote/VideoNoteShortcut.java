package com.radolyn.ayugram.videonote;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;

import androidx.core.content.pm.ShortcutInfoCompat;
import androidx.core.content.pm.ShortcutManagerCompat;
import androidx.core.graphics.drawable.IconCompat;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MediaDataController;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.UserConfig;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.Components.ChatActivityEnterView;
import org.telegram.ui.LaunchActivity;

import java.util.Collections;
import java.util.List;

import xyz.nextalone.nagram.NaConfig;

/**
 * Launcher shortcut that opens Saved Messages and starts a hands-free round video right away.
 * All state here is touched on the UI thread only.
 */
public final class VideoNoteShortcut {

    public static final String ACTION = "nax_video_note";
    private static final String SHORTCUT_ID = "video_note";
    private static final String EXTRA_HASH = "hash";
    private static final long PENDING_TTL_MS = 10_000;

    // Armed when the intent is accepted, consumed by the Saved Messages chat it opens. Static on purpose: it dies with
    // the process, so a restored activity or fragment can never replay the camera start.
    private static int pendingAccount = -1;
    private static long pendingDialogId;
    private static long pendingSince;

    private VideoNoteShortcut() {
    }

    public static boolean isEnabled() {
        return NaConfig.INSTANCE.getVideoNoteShortcut().Bool();
    }

    public static void onSettingChanged(int account) {
        MediaDataController.getInstance(account).buildShortcuts();
    }

    /** MediaDataController.buildShortcuts: the ids it keeps when pruning stale shortcuts. */
    public static void addShortcutId(List<String> wantedIds) {
        if (isEnabled()) {
            wantedIds.add(SHORTCUT_ID);
        }
    }

    /** MediaDataController.buildShortcuts, on its queue after directShareHash is set. Own try so a launcher rejecting it can't abort the rest. */
    public static void publish(boolean recreate, List<String> existingIds, int rank) {
        if (!isEnabled() || SharedConfig.directShareHash == null) {
            return;
        }
        try {
            Intent intent = new Intent(ApplicationLoader.applicationContext, LaunchActivity.class);
            intent.setAction(ACTION);
            intent.putExtra(EXTRA_HASH, SharedConfig.directShareHash);
            ShortcutInfoCompat shortcut = new ShortcutInfoCompat.Builder(ApplicationLoader.applicationContext, SHORTCUT_ID)
                    .setShortLabel(LocaleController.getString(R.string.VideoNoteShortcutLabel))
                    .setLongLabel(LocaleController.getString(R.string.VideoNoteShortcutLabel))
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

    // Same look as the Ayu Mode shortcut: white glyph on the Telegram-blue disc, rasterized for picky OEM launchers.
    private static Bitmap createIcon() {
        int size = AndroidUtilities.dp(48);
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(0xff3390ec);
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint);
        Drawable glyph = ApplicationLoader.applicationContext.getResources().getDrawable(R.drawable.input_video, null).mutate();
        glyph.setColorFilter(new PorterDuffColorFilter(0xffffffff, PorterDuff.Mode.SRC_IN));
        int inset = size / 5;
        glyph.setBounds(inset, inset, size - inset, size - inset);
        glyph.draw(canvas);
        return bitmap;
    }

    private static boolean isGenuine(Intent intent) {
        return intent != null && ACTION.equals(intent.getAction()) && isEnabled()
                && SharedConfig.directShareHash != null && SharedConfig.directShareHash.equals(intent.getStringExtra(EXTRA_HASH));
    }

    /**
     * LaunchActivity.handleIntent's action chain. Consumes the action so a recreate can't replay it, and returns the
     * Saved Messages user id to open, or 0 to fall through to a plain launch (forged intent, or the setting is off).
     */
    public static long accept(Intent intent, int account) {
        boolean genuine = isGenuine(intent);
        intent.setAction(null);
        intent.removeExtra(EXTRA_HASH);
        if (!genuine || !UserConfig.getInstance(account).isClientActivated()) {
            return 0;
        }
        long selfId = UserConfig.getInstance(account).getClientUserId();
        pendingAccount = account;
        pendingDialogId = selfId;
        pendingSince = SystemClock.elapsedRealtime();
        return selfId;
    }

    /** ChatActivity.onTransitionAnimationEnd, forward open. */
    public static void onChatOpened(ChatActivity chat) {
        if (pendingAccount != chat.getCurrentAccount() || pendingDialogId != chat.getDialogId() || chat.getChatMode() != 0) {
            return;
        }
        boolean fresh = SystemClock.elapsedRealtime() - pendingSince < PENDING_TTL_MS;
        pendingAccount = -1;
        pendingDialogId = 0;
        if (!fresh) {
            return;
        }
        // A no-animation present fires this synchronously from inside presentFragment, before the chat is laid out or
        // resumed, so start one frame later.
        AndroidUtilities.runOnUIThread(() -> {
            ChatActivityEnterView enterView = chat.getChatActivityEnterView();
            if (enterView == null || chat.isFinished || chat.getParentActivity() == null) {
                return;
            }
            boolean front = NaConfig.INSTANCE.getVideoNoteShortcutCamera().Int() != 1;
            enterView.startRoundVideoFromShortcut(front, true);
        });
    }
}
