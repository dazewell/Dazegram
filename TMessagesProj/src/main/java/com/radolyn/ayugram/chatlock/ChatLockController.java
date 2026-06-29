package com.radolyn.ayugram.chatlock;

import android.content.SharedPreferences;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.NotificationCenter;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Local, per-dialog store for the "require password" chat lock.
 *
 * <p>When a dialog id is flagged here, {@link org.telegram.ui.ChatActivity} refuses
 * to reveal its content until the app passcode (or fingerprint) is entered, no matter
 * how the chat was opened -- a tap in the list, a notification, a shortcut, a deep
 * link or a search result. The long-press preview is suppressed entirely so a peek
 * cannot leak the conversation either.
 *
 * <p>The lock is purely local -- never synced to Telegram -- and keyed by dialog id,
 * so it covers users, groups and channels alike. Storage is a per-account
 * SharedPreferences file {@code chatlock_<account>}; presence of a key means "locked".
 *
 * <p>Once a locked chat is unlocked it stays unlocked until the app is sent to the
 * background, mirroring the app passcode's own session: the in-memory unlocked set is
 * dropped whenever the passcode pause runs.
 */
public final class ChatLockController {

    private static final Map<Integer, Set<Long>> LOCKED = new HashMap<>();
    private static final Map<Integer, Boolean> LOADED = new HashMap<>();
    private static final Map<Integer, Set<Long>> UNLOCKED = new HashMap<>();

    private ChatLockController() {}

    private static String prefsName(int account) {
        return "chatlock_" + account;
    }

    private static synchronized Set<Long> locked(int account) {
        Set<Long> s = LOCKED.get(account);
        if (s == null) {
            s = new HashSet<>();
            LOCKED.put(account, s);
        }
        if (!Boolean.TRUE.equals(LOADED.get(account))) {
            try {
                SharedPreferences sp = ApplicationLoader.applicationContext
                        .getSharedPreferences(prefsName(account), 0);
                for (String key : sp.getAll().keySet()) {
                    try {
                        s.add(Long.parseLong(key));
                    } catch (NumberFormatException ignore) {
                    }
                }
            } catch (Throwable ignore) {
            }
            LOADED.put(account, Boolean.TRUE);
        }
        return s;
    }

    /** True when this dialog must be unlocked with the passcode before it can be opened. */
    public static synchronized boolean isLocked(int account, long dialogId) {
        if (dialogId == 0) return false;
        return locked(account).contains(dialogId);
    }

    /** Locks or unlocks a dialog and persists the change. */
    public static synchronized void setLocked(int account, long dialogId, boolean enabled) {
        if (dialogId == 0) return;
        Set<Long> s = locked(account);
        SharedPreferences.Editor ed;
        try {
            ed = ApplicationLoader.applicationContext.getSharedPreferences(prefsName(account), 0).edit();
        } catch (Throwable t) {
            ed = null;
        }
        if (enabled) {
            s.add(dialogId);
            if (ed != null) ed.putBoolean(Long.toString(dialogId), true);
        } else {
            s.remove(dialogId);
            if (ed != null) ed.remove(Long.toString(dialogId));
            markLocked(account, dialogId);
        }
        if (ed != null) ed.apply();
        NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.dialogsNeedReload);
    }

    /** True when the chat has already been unlocked for the current foreground session. */
    public static synchronized boolean isUnlocked(int account, long dialogId) {
        Set<Long> s = UNLOCKED.get(account);
        return s != null && s.contains(dialogId);
    }

    /** Remembers that the user passed the passcode for this chat (until the app is backgrounded). */
    public static synchronized void markUnlocked(int account, long dialogId) {
        UNLOCKED.computeIfAbsent(account, a -> new HashSet<>()).add(dialogId);
    }

    private static synchronized void markLocked(int account, long dialogId) {
        Set<Long> s = UNLOCKED.get(account);
        if (s != null) s.remove(dialogId);
    }

    /** Drops every remembered unlock; called when the app goes to the background. */
    public static synchronized void clearUnlocked() {
        UNLOCKED.clear();
    }
}
