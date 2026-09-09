package com.radolyn.ayugram.ghosthold;

import android.content.SharedPreferences;

import androidx.annotation.Nullable;

import org.telegram.SQLite.SQLiteCursor;
import org.telegram.SQLite.SQLiteDatabase;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.SendMessagesHelper;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.NativeByteBuffer;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.LaunchActivity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import tw.nekomimi.nekogram.NekoConfig;

/**
 * "Hold Messages" for Ghost Mode.
 *
 * <p>When Ghost Mode is active and the Hold Messages preference is on, a plain
 * text send is not transmitted. Instead it is persisted as a held row in
 * {@code scheduled_messages_v2} (send_state = 1, negative id, marked in
 * {@link TLRPC.Message#params}), so it renders in that chat's Scheduled list and
 * survives an app kill. Nothing about a held message lives only in memory -- the
 * database row is the sole source of truth for queue membership.
 *
 * <p>The queue drains ("flush") only when Ghost Mode turns off. Ghost is a
 * derived predicate over five independently-flippable toggles
 * ({@link NekoConfig#isGhostModeActive()}), so the flush is triggered from an
 * edge detector that watches the derived active/inactive transition rather than
 * any single toggle, plus a convergence check at process start.
 *
 * <p>Everything here is global (Ghost is a single unsuffixed preference), but the
 * sending side is per-account: local message ids collide across accounts, so
 * every held row is addressed by {@code (account, mid, dialogId)}, never id alone.
 */
public final class GhostHoldController {

    // Distinct fork sentinel for an undated held message. Kept away from
    // upstream's 0x7FFFFFFE "send when online" value (ChatMessageCell), which
    // carries a live upstream meaning we must not collide with.
    public static final int GHOST_HELD_DATE_SENTINEL = 0x7FFFFFFD;

    private static final String PARAM_MARKER = "ghost_hold";
    private static final String PARAM_VALUE = "1";

    private static final String PREFS_NAME = "ghosthold_state";
    private static final String KEY_LAST_GHOST_ACTIVE = "last_ghost_active";

    // Gap between successive sends on flush, so an entire backlog does not leave
    // in the same instant -- a simultaneous burst is itself a signal Ghost ended.
    private static final long FLUSH_STAGGER_MS = 1500;

    private static volatile boolean flushInProgress;

    private GhostHoldController() {}

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE);
    }

    // ---- predicates ----

    public static boolean isHoldActive() {
        return NekoConfig.isGhostModeActive() && NekoConfig.holdMessagesWhileGhost.Bool();
    }

    public static boolean isHeld(@Nullable MessageObject mo) {
        return mo != null && isHeldMessage(mo.messageOwner);
    }

    public static boolean isHeldMessage(@Nullable TLRPC.Message m) {
        return m != null && m.params != null && PARAM_VALUE.equals(m.params.get(PARAM_MARKER));
    }

    // ---- the send-time hook ----

    /**
     * Consulted once, at the single {@code sendMessage(SendMessageParams)} funnel.
     * Reads Ghost state and the hold preference as one act and, when a plain text
     * message should be held, persists it and returns true so the caller returns
     * before any in-flight send state is created.
     */
    public static boolean maybeHold(int account, long peer, SendMessagesHelper.SendMessageParams params) {
        if (params == null || !isHoldActive()) {
            return false;
        }
        // A retry re-sends a message that already has a row; those are governed by
        // the defensive skip in retrySendMessage, not held here.
        if (params.retryMessageObject != null) {
            return false;
        }
        // Secret chats have their own send machinery and lifetime; leave them alone.
        if (DialogObject.isEncryptedDialog(peer)) {
            return false;
        }
        // Text only for v1. Anything carrying media sends as it does today rather
        // than risk silently swallowing a file we cannot re-derive on flush.
        if (params.message == null) {
            return false;
        }
        if (params.location != null || params.photo != null || params.videoEditedInfo != null
                || params.document != null || params.game != null || params.poll != null
                || params.pollSendParams != null || params.todo != null || params.invoice != null
                || params.mediaWebPage != null || params.user != null || params.richMessage != null
                || params.sendingStory != null) {
            return false;
        }

        persistHeld(account, peer, params);
        AndroidUtilities.runOnUIThread(GhostHoldController::showDivertBulletin);
        return true;
    }

    private static void persistHeld(int account, long peer, SendMessagesHelper.SendMessageParams params) {
        final MessagesController controller = MessagesController.getInstance(account);
        final UserConfig userConfig = UserConfig.getInstance(account);

        TLRPC.TL_message msg = new TLRPC.TL_message();
        msg.message = params.message;
        if (params.entities != null && !params.entities.isEmpty()) {
            msg.entities = params.entities;
            msg.flags |= TLRPC.MESSAGE_FLAG_HAS_ENTITIES;
        }
        msg.media = new TLRPC.TL_messageMediaEmpty();
        msg.flags |= TLRPC.MESSAGE_FLAG_HAS_MEDIA;
        msg.local_id = msg.id = userConfig.getNewMessageId();
        userConfig.saveConfig(false);
        msg.out = true;
        msg.from_id = new TLRPC.TL_peerUser();
        msg.from_id.user_id = userConfig.getClientUserId();
        msg.flags |= TLRPC.MESSAGE_FLAG_HAS_FROM_ID;
        msg.peer_id = controller.getPeer(peer);
        msg.dialog_id = peer;
        msg.random_id = SendMessagesHelper.getInstance(account).getNextRandomId();
        msg.silent = !params.notify || MessagesController.getNotificationsSettings(account).getBoolean("silent_" + peer, false);
        // A user-picked schedule date is recorded and shown but has no enforcement
        // power while held; an undated hold sorts under its own sentinel header.
        msg.date = params.scheduleDate != 0 ? params.scheduleDate : GHOST_HELD_DATE_SENTINEL;
        msg.send_state = MessageObject.MESSAGE_SEND_STATE_SENDING;
        msg.unread = true;
        msg.attachPath = "";

        if (params.replyToMsg != null) {
            msg.reply_to = new TLRPC.TL_messageReplyHeader();
            msg.reply_to.flags |= 16;
            msg.reply_to.reply_to_msg_id = params.replyToMsg.getId();
            msg.flags |= TLRPC.MESSAGE_FLAG_REPLY;
        }

        HashMap<String, String> stored = params.params != null ? new HashMap<>(params.params) : new HashMap<>();
        stored.put(PARAM_MARKER, PARAM_VALUE);
        msg.params = stored;

        ArrayList<TLRPC.Message> arr = new ArrayList<>();
        arr.add(msg);
        MessagesStorage.getInstance(account).putMessages(arr, false, true, false, 0, 1, 0);

        MessageObject mo = new MessageObject(account, msg, true, true);
        mo.scheduled = true;
        ArrayList<MessageObject> objArr = new ArrayList<>();
        objArr.add(mo);
        controller.updateInterfaceWithMessages(peer, objArr, 1);
        NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.dialogsNeedReload);
    }

    private static void showDivertBulletin() {
        BaseFragment fragment = LaunchActivity.getLastFragment();
        if (fragment == null || fragment.getParentActivity() == null) {
            return;
        }
        BulletinFactory.of(fragment).createSimpleBulletin(R.raw.chats_infotip, LocaleController.getString(R.string.GhostHoldDiverted)).show();
    }

    // ---- ghost-off edge detection ----

    /**
     * Invoked wherever Ghost state can change (the aggregate toggle and each of
     * the five individual toggles). Compares the persisted previous active state
     * against the current derived value and, on a true -> false edge, requests a
     * flush. Idempotent: a repeated call with no transition does nothing.
     */
    public static synchronized void onGhostStateMaybeChanged() {
        boolean nowActive = NekoConfig.isGhostModeActive();
        boolean wasActive = prefs().getBoolean(KEY_LAST_GHOST_ACTIVE, false);
        prefs().edit().putBoolean(KEY_LAST_GHOST_ACTIVE, nowActive).apply();
        if (wasActive && !nowActive) {
            AndroidUtilities.runOnUIThread(() -> promptFlush(true));
        }
    }

    /**
     * Convergence check at process start. If Ghost ended (or was never on) while a
     * backlog remained -- e.g. the app was killed mid-flush -- drain it silently so
     * the queue cannot be stranded across launches. Also seeds the edge baseline.
     */
    public static void checkOnProcessStart() {
        boolean nowActive = NekoConfig.isGhostModeActive();
        prefs().edit().putBoolean(KEY_LAST_GHOST_ACTIVE, nowActive).apply();
        if (!nowActive) {
            AndroidUtilities.runOnUIThread(() -> promptFlush(false));
        }
    }

    // ---- flush ----

    private static void promptFlush(boolean offerConfirmation) {
        if (flushInProgress) {
            return;
        }
        collectHeld(items -> {
            if (items.isEmpty()) {
                return;
            }
            BaseFragment fragment = LaunchActivity.getLastFragment();
            if (!offerConfirmation || fragment == null || fragment.getParentActivity() == null) {
                // No screen to ask on (process-start convergence, or a non-UI
                // transition): drain rather than skip -- nothing may be left held.
                performFlush(items, null);
                return;
            }

            int total = items.size();
            int chats = countDistinctChats(items);
            String body;
            if (total == 1) {
                body = LocaleController.getString(R.string.GhostHoldFlushConfirmOne);
            } else {
                body = LocaleController.formatString(R.string.GhostHoldFlushConfirmMany, total, chats);
            }
            AlertDialog.Builder builder = new AlertDialog.Builder(fragment.getParentActivity());
            builder.setTitle(LocaleController.getString(R.string.GhostHoldFlushConfirmTitle));
            builder.setMessage(body);
            builder.setPositiveButton(LocaleController.getString(R.string.MessageScheduleSend), (dialog, which) -> performFlush(items, fragment));
            builder.setNegativeButton(LocaleController.getString(R.string.Cancel), (dialog, which) -> restoreGhost());
            builder.setOnCancelListener(dialog -> restoreGhost());
            builder.show();
        });
    }

    private static void restoreGhost() {
        NekoConfig.setGhostMode(true);
        // Re-seed the baseline so the next genuine ghost-off edge still fires.
        prefs().edit().putBoolean(KEY_LAST_GHOST_ACTIVE, NekoConfig.isGhostModeActive()).apply();
        NotificationCenter.getInstance(UserConfig.selectedAccount).postNotificationName(NotificationCenter.mainUserInfoChanged);
    }

    private static void performFlush(ArrayList<HeldItem> items, @Nullable BaseFragment fragment) {
        if (items.isEmpty()) {
            return;
        }
        flushInProgress = true;
        final int total = items.size();
        for (int i = 0; i < total; i++) {
            HeldItem item = items.get(i);
            AndroidUtilities.runOnUIThread(() -> flushItem(item), i * FLUSH_STAGGER_MS);
        }
        AndroidUtilities.runOnUIThread(() -> {
            flushInProgress = false;
            // Re-count after every delete/re-hold has drained the storage queue
            // (same serial queue, so this runs after them). Anything still held
            // was re-held because Ghost came back on mid-flush -- report honestly.
            countHeld(remaining -> {
                BaseFragment f = LaunchActivity.getLastFragment();
                if (f == null || f.getParentActivity() == null) {
                    return;
                }
                CharSequence text;
                if (remaining <= 0) {
                    text = LocaleController.formatPluralString("GhostHoldFlushed", total);
                } else {
                    int sent = Math.max(0, total - remaining);
                    text = LocaleController.formatString(R.string.GhostHoldFlushedPartial, sent, total);
                }
                BulletinFactory.of(f).createSimpleBulletin(R.raw.chats_infotip, text).show();
            });
        }, total * FLUSH_STAGGER_MS);
    }

    private static void flushItem(HeldItem item) {
        final int account = item.account;
        final TLRPC.Message m = item.message;
        final long dialogId = item.dialogId;

        int now = ConnectionsManager.getInstance(account).getCurrentTime();
        boolean future = m.date != GHOST_HELD_DATE_SENTINEL && m.date > now;
        int scheduleDate = future ? m.date : 0;

        MessageObject replyStub = null;
        if (m.reply_to != null && m.reply_to.reply_to_msg_id != 0) {
            TLRPC.Message rm = new TLRPC.TL_message();
            rm.id = m.reply_to.reply_to_msg_id;
            rm.peer_id = m.peer_id;
            rm.dialog_id = dialogId;
            replyStub = new MessageObject(account, rm, false, false);
        }

        ArrayList<TLRPC.MessageEntity> entities = (m.entities != null && !m.entities.isEmpty()) ? m.entities : null;
        SendMessagesHelper.SendMessageParams p = SendMessagesHelper.SendMessageParams.of(
                m.message, dialogId, replyStub, null, null, true, entities, null, null,
                !m.silent, scheduleDate, 0, null, false);
        // Hand to the normal send path first (durable in the correct table), then
        // remove the held row. If we crash in between, the held row survives and is
        // re-flushed -- we fail toward "sent twice", never "lost". sendMessage
        // re-checks isHoldActive() atomically, so if Ghost came back on mid-flush
        // this message is re-held under a fresh row instead of leaking.
        SendMessagesHelper.getInstance(account).sendMessage(p);
        deleteHeldRow(account, item.mid, dialogId);
    }

    private static void deleteHeldRow(int account, int mid, long dialogId) {
        MessagesStorage storage = MessagesStorage.getInstance(account);
        storage.getStorageQueue().postRunnable(() -> {
            try {
                storage.getDatabase().executeFast("DELETE FROM scheduled_messages_v2 WHERE mid = " + mid + " AND uid = " + dialogId).stepThis().dispose();
            } catch (Exception e) {
                FileLog.e(e);
            }
            AndroidUtilities.runOnUIThread(() ->
                    NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.scheduledMessagesUpdated, dialogId, 0, false));
        });
    }

    // ---- held-queue reads ----

    /** Async count for the settings screen; result delivered on the UI thread. */
    public static void countHeld(Utilities.Callback<Integer> onDone) {
        collectHeld(items -> onDone.run(items.size()));
    }

    private static void collectHeld(Utilities.Callback<ArrayList<HeldItem>> onDone) {
        ArrayList<Integer> accounts = new ArrayList<>();
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            if (UserConfig.getInstance(a).isClientActivated()) {
                accounts.add(a);
            }
        }
        if (accounts.isEmpty()) {
            AndroidUtilities.runOnUIThread(() -> onDone.run(new ArrayList<>()));
            return;
        }
        final List<HeldItem> result = Collections.synchronizedList(new ArrayList<>());
        final AtomicInteger remaining = new AtomicInteger(accounts.size());
        for (int account : accounts) {
            MessagesStorage storage = MessagesStorage.getInstance(account);
            storage.getStorageQueue().postRunnable(() -> {
                result.addAll(queryHeld(account, storage));
                if (remaining.decrementAndGet() == 0) {
                    AndroidUtilities.runOnUIThread(() -> onDone.run(new ArrayList<>(result)));
                }
            });
        }
    }

    private static ArrayList<HeldItem> queryHeld(int account, MessagesStorage storage) {
        ArrayList<HeldItem> out = new ArrayList<>();
        SQLiteCursor cursor = null;
        try {
            SQLiteDatabase db = storage.getDatabase();
            cursor = db.queryFinalized("SELECT data, mid, uid, date FROM scheduled_messages_v2 WHERE mid < 0 AND send_state = 1");
            long selfId = UserConfig.getInstance(account).clientUserId;
            while (cursor.next()) {
                NativeByteBuffer data = cursor.byteBufferValue(0);
                if (data == null) {
                    continue;
                }
                TLRPC.Message message = TLRPC.Message.TLdeserialize(data, data.readInt32(false), false);
                if (message != null) {
                    message.readAttachPath(data, selfId);
                }
                data.reuse();
                if (!isHeldMessage(message)) {
                    continue;
                }
                message.id = cursor.intValue(1);
                message.dialog_id = cursor.longValue(2);
                message.date = cursor.intValue(3);
                out.add(new HeldItem(account, message.id, message.dialog_id, message));
            }
        } catch (Exception e) {
            FileLog.e(e);
        } finally {
            if (cursor != null) {
                cursor.dispose();
            }
        }
        return out;
    }

    private static int countDistinctChats(ArrayList<HeldItem> items) {
        ArrayList<Long> seen = new ArrayList<>();
        for (HeldItem item : items) {
            long key = ((long) item.account << 1) ^ item.dialogId;
            if (!seen.contains(key)) {
                seen.add(key);
            }
        }
        return seen.size();
    }

    private static final class HeldItem {
        final int account;
        final int mid;
        final long dialogId;
        final TLRPC.Message message;

        HeldItem(int account, int mid, long dialogId, TLRPC.Message message) {
            this.account = account;
            this.mid = mid;
            this.dialogId = dialogId;
            this.message = message;
        }
    }
}
