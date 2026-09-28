package com.radolyn.ayugram.videonote;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;

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
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.Components.ChatActivityEnterView;
import org.telegram.ui.LaunchActivity;

import java.lang.ref.WeakReference;
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

    private static void smoke(String what) {
        android.util.Log.i("NaxVideoNote", "NAX_SMOKE_video-note-shortcut " + what + " phase=" + phase);
    }

    private static void smokeTrace(String what) {
        android.util.Log.i("NaxVideoNote", "NAX_SMOKE_video-note-shortcut " + what + " phase=" + phase, new Throwable());
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
        smoke("BEGIN accept genuine=" + genuine + " enabled=" + isEnabled() + " account=" + account + " build=" + org.telegram.messenger.BuildConfig.BUILD_VERSION_STRING + " app=" + org.telegram.messenger.BuildConfig.APPLICATION_ID);
        intent.setAction(null);
        intent.removeExtra(EXTRA_HASH);
        if (!genuine || !UserConfig.getInstance(account).isClientActivated()) {
            if (phase != IDLE) {
                lockNow();
            }
            return 0;
        }
        long selfId = UserConfig.getInstance(account).getClientUserId();
        pendingAccount = account;
        pendingDialogId = selfId;
        pendingSince = SystemClock.elapsedRealtime();
        return selfId;
    }

    private static boolean isPendingChat(ChatActivity chat) {
        return pendingAccount == chat.getCurrentAccount() && pendingDialogId == chat.getDialogId() && chat.getChatMode() == 0;
    }

    // The navigation hooks see the fragment before onFragmentCreate has read its arguments, so its dialog id is still 0
    private static boolean isPendingChatPush(BaseFragment fragment) {
        if (!(fragment instanceof ChatActivity) || fragment.getArguments() == null || pendingAccount != fragment.getCurrentAccount()) {
            return false;
        }
        android.os.Bundle args = fragment.getArguments();
        return args.getLong("user_id", 0) == pendingDialogId && args.getInt("chatMode", 0) == 0;
    }

    /** ChatActivity.onTransitionAnimationEnd, forward open. */
    public static void onChatOpened(ChatActivity chat) {
        if (!isPendingChat(chat)) {
            return;
        }
        boolean fresh = SystemClock.elapsedRealtime() - pendingSince < PENDING_TTL_MS;
        pendingAccount = -1;
        pendingDialogId = 0;
        if (!fresh) {
            return;
        }
        boolean locked = phase == STARTING;
        smoke("chatOpened locked=" + locked);
        if (locked) {
            sessionChat = new WeakReference<>(chat);
        }
        // A no-animation present fires this synchronously from inside presentFragment, before the chat is laid out or
        // resumed, so start one frame later.
        AndroidUtilities.runOnUIThread(() -> {
            if (locked && !isSessionChat(chat)) {
                return; // the lock already came down in between
            }
            ChatActivityEnterView enterView = chat.getChatActivityEnterView();
            boolean front = NaConfig.INSTANCE.getVideoNoteShortcutCamera().Int() != 1;
            boolean started = enterView != null && !chat.isFinished && chat.getParentActivity() != null
                    && enterView.startRoundVideoFromShortcut(front, !locked);
            smoke("start started=" + started + " front=" + front + " enterView=" + (enterView != null));
            if (locked && !started) {
                lockNow();
            }
        });
    }

    // --- Recording while the app is locked ---
    //
    // The passcode is deferred for exactly one recording. It fails closed: before anything opens, appLocked is set and
    // saved, so a crash, a kill or a trip to the background still lands on the passcode, and isWaitingForPasscodeEnter
    // sends every other intent through the passcode gate, which ends the session. Any showPasscodeActivity, whoever
    // calls it, ends the session too (onPasscodeShown). Everything else below only decides when to lock early.

    private static final int IDLE = 0;
    private static final int STARTING = 1;   // gate passed, the Saved Messages chat is opening
    private static final int RECORDING = 2;  // camera open, or its clip sitting in the preview
    private static final int FINALIZING = 3; // stopped by us, waiting for the clip to land in the preview (and the draft store)
    private static final int SENDING = 4;    // send requested, waiting for the clip to reach SendMessagesHelper

    private static final long POLL_MS = 250;
    private static final long START_TIMEOUT_MS = 5_000;
    private static final long IDLE_GRACE_MS = 2_000;
    private static final long FINALIZE_TIMEOUT_MS = 3_000;
    private static final long SEND_TIMEOUT_MS = 10_000;

    private static int phase = IDLE;
    private static long phaseSince;
    private static long idleSince;
    private static WeakReference<ChatActivity> sessionChat;
    private static final Runnable poll = VideoNoteShortcut::poll;

    private static void setPhase(int newPhase) {
        phase = newPhase;
        phaseSince = SystemClock.elapsedRealtime();
        idleSince = 0;
    }

    private static boolean isSessionChat(ChatActivity chat) {
        return phase != IDLE && sessionChat != null && sessionChat.get() == chat;
    }

    /** LaunchActivity.handleIntent's passcode gate. True lets this one intent through while the app stays locked. */
    public static boolean bypassLock(LaunchActivity activity, Intent intent) {
        if (phase != IDLE || !isGenuine(intent)) {
            return false;
        }
        SharedConfig.appLocked = true;
        SharedConfig.isWaitingForPasscodeEnter = true;
        SharedConfig.saveConfig();
        setPhase(STARTING);
        smoke("bypass");
        AndroidUtilities.cancelRunOnUIThread(poll);
        AndroidUtilities.runOnUIThread(poll, POLL_MS);
        activity.hidePasscodeForVideoNote();
        return true;
    }

    /** First thing in LaunchActivity.showPasscodeActivity. */
    public static void onPasscodeShown() {
        if (phase == IDLE) {
            return;
        }
        ChatActivity chat = sessionChat != null ? sessionChat.get() : null;
        smokeTrace("END passcodeShown chat=" + (chat != null));
        setPhase(IDLE);
        sessionChat = null;
        pendingAccount = -1;
        pendingDialogId = 0;
        AndroidUtilities.cancelRunOnUIThread(poll);
        if (chat != null) {
            // a lock raised in the foreground gets no onPause, so stop a live recording into the preview here or the
            // unlock rebuild throws it away
            chat.finalizeRoundVideoForLock();
        }
    }

    private static void lockNow() {
        smokeTrace("lockNow");
        LaunchActivity activity = LaunchActivity.instance;
        if (activity != null && !activity.isFinishing()) {
            activity.showPasscodeActivity(true, false, -1, -1, null, null);
        }
        onPasscodeShown(); // no-op if showPasscodeActivity got that far; appLocked is saved either way
    }

    // Back while the camera is live: stop into the preview first and lock once the clip is safely bound, so a quick
    // fingerprint unlock can't race the finalize.
    private static void lockKeepingClip(ChatActivity chat) {
        ChatActivityEnterView enterView = chat.getChatActivityEnterView();
        if (phase == RECORDING && enterView != null && enterView.isRecordingAudioVideo()) {
            chat.finalizeRoundVideoForLock();
            setPhase(FINALIZING);
        } else if (phase != FINALIZING) {
            lockNow();
        }
    }

    /** ChatActivity.createView, right after the input container is added. */
    public static void onChatViewCreated(ChatActivity chat, ViewGroup contentView, View inputContainer) {
        if (!(phase == STARTING && isPendingChat(chat)) && !isSessionChat(chat)) {
            return;
        }
        // Keeps the history, the action bar and every button on them out of sight and out of reach. The input container
        // and the round camera (added later, just above the input container) stay on top. Not persisted: the unlock
        // rebuild re-runs createView after the session has ended, and the shield is gone.
        smoke("shield index=" + contentView.indexOfChild(inputContainer));
        View shield = new View(contentView.getContext()) {
            // The chat view lays its ordinary children out below the action bar, which would leave the header drawn and
            // tappable (its menu can clear the history). Stretch to the container's top edge so the shield covers it
            // for both drawing and hit-testing.
            @Override
            public void layout(int l, int t, int r, int b) {
                super.layout(0, 0, ((View) getParent()).getWidth(), b);
            }
        };
        shield.setBackgroundColor(0xff000000);
        shield.setClickable(true);
        int index = contentView.indexOfChild(inputContainer);
        contentView.addView(shield, index < 0 ? contentView.getChildCount() : index, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    /** End of ChatActivity's needStartRecordVideo delegate. */
    public static void onRecordVideoState(ChatActivity chat, int state) {
        if (!isSessionChat(chat)) {
            return;
        }
        smoke("recordState state=" + state);
        if (state == 0 && phase == STARTING) {
            setPhase(RECORDING);
        } else if ((state == 1 || state == 4) && phase == RECORDING) {
            setPhase(SENDING);
        } else if (state == 2 || state == 5) {
            lockNow();
        }
    }

    /** ChatActivity.sendMedia, once the clip has been handed to SendMessagesHelper. */
    public static void onMediaSent(ChatActivity chat) {
        smoke("mediaSent session=" + isSessionChat(chat));
        if (isSessionChat(chat) && (phase == RECORDING || phase == SENDING)) {
            lockNow();
        }
    }

    /** ChatActivity.onBackPressed. True means the back was taken. */
    public static boolean onBackPressed(ChatActivity chat, boolean invoked) {
        if (!isSessionChat(chat) && !(phase == STARTING && isPendingChat(chat))) {
            return false;
        }
        if (invoked) {
            lockKeepingClip(chat);
        }
        return true;
    }

    public static boolean blocksSwipeBack(ChatActivity chat) {
        return isSessionChat(chat) || phase == STARTING && isPendingChat(chat);
    }

    /** LaunchActivity.needPresentFragment / needAddFragmentToStack. Opening anything but our own chat locks. */
    public static void onNavigation(BaseFragment fragment) {
        if (phase == IDLE || phase == STARTING && isPendingChatPush(fragment)) {
            return;
        }
        smoke("navigation fragment=" + (fragment != null ? fragment.getClass().getSimpleName() : "null"));
        lockNow();
    }

    private static void poll() {
        if (phase == IDLE) {
            return;
        }
        long now = SystemClock.elapsedRealtime();
        long age = now - phaseSince;
        ChatActivity chat = sessionChat != null ? sessionChat.get() : null;
        if (phase == STARTING) {
            if (age > START_TIMEOUT_MS) {
                lockNow();
                return;
            }
        } else if (chat == null || chat.isFinished || chat.getParentActivity() == null || !chat.isLastFragment()) {
            lockNow();
            return;
        } else {
            ChatActivityEnterView enterView = chat.getChatActivityEnterView();
            boolean recording = enterView != null && enterView.isRecordingAudioVideo();
            boolean preview = enterView != null && enterView.hasVideoToSend();
            if (phase == FINALIZING && (preview || !recording && age > FINALIZE_TIMEOUT_MS)) {
                lockNow();
                return;
            }
            if (phase == SENDING && age > SEND_TIMEOUT_MS) {
                lockNow();
                return;
            }
            if (phase == RECORDING) {
                // stop -> preview passes briefly through neither state, hence the grace
                if (recording || preview) {
                    idleSince = 0;
                } else if (idleSince == 0) {
                    idleSince = now;
                } else if (now - idleSince > IDLE_GRACE_MS) {
                    lockNow();
                    return;
                }
            }
        }
        AndroidUtilities.runOnUIThread(poll, POLL_MS);
    }
}
