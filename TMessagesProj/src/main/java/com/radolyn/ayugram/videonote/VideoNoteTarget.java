package com.radolyn.ayugram.videonote;

import android.content.SharedPreferences;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * The person a video memo is recorded for, per account. Unset, or anyone the memo can't be sent to, means Saved
 * Messages. Only read and written on the UI thread.
 */
public final class VideoNoteTarget {

    private static final String KEY_TARGET = "target";
    private static final long LOAD_TIMEOUT_MS = 1_000;

    private VideoNoteTarget() {
    }

    private static SharedPreferences prefs(int account) {
        return ApplicationLoader.applicationContext.getSharedPreferences("videonote_" + account, 0);
    }

    /** The stored user id, or 0 for Saved Messages. */
    public static long get(int account) {
        try {
            return Math.max(0, prefs(account).getLong(KEY_TARGET, 0));
        } catch (Throwable e) {
            return 0;
        }
    }

    public static void set(int account, long userId) {
        if (userId > 0) {
            prefs(account).edit().putLong(KEY_TARGET, userId).apply();
        } else {
            prefs(account).edit().remove(KEY_TARGET).apply();
        }
    }

    /** MessagesController.performLogout. */
    public static void clearAccountState(int account) {
        try {
            prefs(account).edit().clear().apply();
        } catch (Throwable ignore) {
        }
    }

    /** A person you can pick at all: not a bot, yourself, or one of Telegram's own accounts. */
    public static boolean isEligible(int account, TLRPC.User user) {
        return user != null && user.id > 0 && !UserObject.isDeleted(user) && !user.bot
                && user.id != UserConfig.getInstance(account).getClientUserId()
                && !UserObject.isService(user.id) && !UserObject.isReplyUser(user.id) && !MessagesController.isSupportUser(user);
    }

    /**
     * The user id the shortcut opens: the stored person when everything known about them says the memo can be sent,
     * otherwise Saved Messages. Never asks the network. When a person isn't cached in memory, which is every time on a
     * cold start, they are read from the local database with a bounded wait. ChatActivity would otherwise do the same
     * read itself, unbounded, and drop the chat if it found nothing.
     */
    static long resolve(int account) {
        long selfId = UserConfig.getInstance(account).getClientUserId();
        long id = get(account);
        if (id == 0 || id == selfId) {
            android.util.Log.i("NaxVideoNote", "NAX_SMOKE_video-memo-target resolve set=false result=saved");
            return selfId;
        }
        long smokeStart = android.os.SystemClock.elapsedRealtime();
        String smokeSource = "memory";
        MessagesController controller = MessagesController.getInstance(account);
        TLRPC.User user = controller.getUser(id);
        TLRPC.UserFull full = controller.getUserFull(id);
        if (user == null || full == null) {
            boolean needUser = user == null;
            boolean needFull = full == null;
            // The storage runnable only fills these; a late one after a timeout writes into arrays nobody reads again
            TLRPC.User[] dbUser = new TLRPC.User[1];
            TLRPC.UserFull[] dbFull = new TLRPC.UserFull[1];
            CountDownLatch latch = new CountDownLatch(1);
            MessagesStorage storage = MessagesStorage.getInstance(account);
            storage.getStorageQueue().postRunnable(() -> {
                try {
                    if (needUser) {
                        dbUser[0] = storage.getUser(id);
                    }
                    if (needFull) {
                        ArrayList<TLRPC.UserFull> infos = storage.loadUserInfos(new HashSet<>(Collections.singleton(id)));
                        if (!infos.isEmpty()) {
                            dbFull[0] = infos.get(0);
                        }
                    }
                } catch (Throwable e) {
                    FileLog.e(e);
                }
                latch.countDown();
            });
            boolean loaded;
            try {
                loaded = latch.await(LOAD_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                loaded = false;
            }
            smokeSource = "db needUser=" + needUser + " needFull=" + needFull + " waitMs=" + (android.os.SystemClock.elapsedRealtime() - smokeStart);
            if (!loaded) {
                android.util.Log.i("NaxVideoNote", "NAX_SMOKE_video-memo-target resolve set=true source=" + smokeSource + " timeout=true result=saved");
                return selfId;
            }
            if (needUser && dbUser[0] != null) {
                user = dbUser[0];
                controller.putUser(user, true);
            }
            if (needFull) {
                full = dbFull[0]; // for this check only, the chat loads its own
            }
        }
        boolean smokeOk = canReceive(account, controller, user, full);
        android.util.Log.i("NaxVideoNote", "NAX_SMOKE_video-memo-target resolve set=true source=" + smokeSource
                + " user=" + (user != null) + " full=" + (full != null) + " eligible=" + isEligible(account, user)
                + " blocked=" + (full != null && full.blocked || user != null && controller.blockePeers.indexOfKey(user.id) >= 0)
                + " voiceForbidden=" + (full != null && full.voice_messages_forbidden)
                + " paid=" + (user != null && user.send_paid_messages_stars > 0 || full != null && full.send_paid_messages_stars > 0)
                + " premiumGate=" + (full != null && full.contact_require_premium)
                + " result=" + (smokeOk ? "target" : "saved"));
        return smokeOk ? id : selfId;
    }

    // What we don't know counts as no: the recording happens behind the lock shield, where the user can't see which
    // chat they're in, so a send that would bounce or cost Stars must never be armed.
    private static boolean canReceive(int account, MessagesController controller, TLRPC.User user, TLRPC.UserFull full) {
        if (!isEligible(account, user) || full == null || controller.getRestrictionReason(user.restriction_reason) != null) {
            return false;
        }
        if (full.blocked || controller.blockePeers.indexOfKey(user.id) >= 0 || full.voice_messages_forbidden) {
            return false;
        }
        if (user.send_paid_messages_stars > 0 || full.send_paid_messages_stars > 0) {
            return false;
        }
        return controller.isUserContactBlocked(user.id, true) == null
                && !(full.contact_require_premium && !UserConfig.getInstance(account).isPremium());
    }
}
