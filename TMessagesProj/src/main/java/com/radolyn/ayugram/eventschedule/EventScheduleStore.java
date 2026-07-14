package com.radolyn.ayugram.eventschedule;

import android.content.SharedPreferences;

import org.telegram.messenger.ApplicationLoader;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/**
 * Per-account, never-synced store for armed event-schedule triggers.
 *
 * <p>Mirrors {@code HideLastMessageController}: a SharedPreferences file
 * {@code eventschedule_<account>} mapping {@code dialogId_createdAt -> entry JSON},
 * with a lazily loaded in-memory cache. Only entries with at least one server id are
 * persisted (local echo ids are useless after a restart); expired ones (fallback date
 * well past) are dropped on load. A per-account "has any" flag lets the hot new-message
 * path bail without touching the cache.
 */
public final class EventScheduleStore {

    private static final Map<Integer, HashMap<String, EventScheduleEntry>> CACHE = new HashMap<>();
    private static final Map<Integer, Boolean> LOADED = new HashMap<>();

    // Read on every incoming message off the cache lock, so triggers with no state stay cheap.
    private static volatile long nonEmptyAccounts;

    private EventScheduleStore() {}

    private static String prefsName(int account) {
        return "eventschedule_" + account;
    }

    private static void markAccount(int account, boolean any) {
        if (any) {
            nonEmptyAccounts |= (1L << account);
        } else {
            nonEmptyAccounts &= ~(1L << account);
        }
    }

    /** Fast, lock-free check for the new-message funnel. */
    public static boolean hasAny(int account) {
        return (nonEmptyAccounts & (1L << account)) != 0;
    }

    /** Loads the on-disk cache for an account (sets {@link #hasAny}). Cheap no-op once loaded. */
    public static void ensureLoaded(int account) {
        cache(account);
    }

    private static synchronized HashMap<String, EventScheduleEntry> cache(int account) {
        HashMap<String, EventScheduleEntry> m = CACHE.get(account);
        if (m == null) {
            m = new HashMap<>();
            CACHE.put(account, m);
        }
        if (!Boolean.TRUE.equals(LOADED.get(account))) {
            SharedPreferences sp = null;
            try {
                sp = ApplicationLoader.applicationContext.getSharedPreferences(prefsName(account), 0);
                long now = System.currentTimeMillis() / 1000L;
                SharedPreferences.Editor ed = null;
                for (Map.Entry<String, ?> e : sp.getAll().entrySet()) {
                    Object v = e.getValue();
                    EventScheduleEntry entry = v instanceof String ? EventScheduleEntry.fromJson((String) v) : null;
                    if (entry == null || entry.serverIds.isEmpty() || entry.fallbackDate + 300 < now) {
                        if (ed == null) ed = sp.edit();
                        ed.remove(e.getKey());
                        continue;
                    }
                    m.put(entry.key(), entry);
                }
                if (ed != null) ed.apply();
            } catch (Throwable ignore) {
            }
            LOADED.put(account, Boolean.TRUE);
        }
        markAccount(account, !m.isEmpty());
        return m;
    }

    public static synchronized ArrayList<EventScheduleEntry> forDialog(int account, long dialogId) {
        ArrayList<EventScheduleEntry> out = new ArrayList<>();
        for (EventScheduleEntry e : cache(account).values()) {
            if (e.dialogId == dialogId) out.add(e);
        }
        return out;
    }

    public static synchronized void persist(int account, EventScheduleEntry entry) {
        if (entry.serverIds.isEmpty()) return;
        HashMap<String, EventScheduleEntry> m = cache(account);
        m.put(entry.key(), entry);
        try {
            String json = entry.toJson();
            if (json != null) {
                ApplicationLoader.applicationContext.getSharedPreferences(prefsName(account), 0)
                        .edit().putString(entry.key(), json).apply();
            }
        } catch (Throwable ignore) {
        }
        markAccount(account, !m.isEmpty());
    }

    public static synchronized void remove(int account, EventScheduleEntry entry) {
        remove(account, entry.key());
    }

    public static synchronized void remove(int account, String key) {
        HashMap<String, EventScheduleEntry> m = cache(account);
        m.remove(key);
        try {
            ApplicationLoader.applicationContext.getSharedPreferences(prefsName(account), 0)
                    .edit().remove(key).apply();
        } catch (Throwable ignore) {
        }
        markAccount(account, !m.isEmpty());
    }

    /** True when the given key still points at a live entry (used by the delayed fire path). */
    public static synchronized boolean contains(int account, String key) {
        return cache(account).containsKey(key);
    }

    public static synchronized EventScheduleEntry findByMessage(int account, long dialogId, int msgId) {
        for (EventScheduleEntry e : cache(account).values()) {
            if (e.dialogId == dialogId && e.serverIds.contains(msgId)) return e;
        }
        return null;
    }

    /** Drops scheduled ids that just left the queue (send-now, delete, or the fallback firing). */
    public static synchronized void purgeIds(int account, long channelId, ArrayList<Integer> ids) {
        boolean changed = false;
        for (EventScheduleEntry e : new ArrayList<>(cache(account).values())) {
            if (channelId != 0 && e.dialogId != -channelId) continue;
            boolean hit = false;
            for (int id : ids) {
                if (e.serverIds.contains(id)) {
                    hit = true;
                    break;
                }
            }
            if (hit) {
                remove(account, e.key());
                changed = true;
            }
        }
        if (changed) markAccount(account, !cache(account).isEmpty());
    }
}
