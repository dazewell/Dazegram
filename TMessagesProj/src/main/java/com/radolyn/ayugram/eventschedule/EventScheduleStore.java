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
 * with a lazily loaded in-memory cache. Entries are durable from arm time: an arm can be
 * persisted before any server ids arrive, then bound later from send ack metadata. Expired
 * entries (fallback date well past, or an unbound arm whose bind window has expired) are
 * dropped on load. A per-account "has any" flag lets the hot new-message path bail without
 * touching the cache.
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
                    // Keep the serverIds.isEmpty() guard first: pre-bindExpires builds only persisted
                    // bound rows, so testing an absent bind_expires_at independently would drop legacy data.
                    // hasInvalidIds() migrates entries orphaned by an older build that bound an in-flight
                    // local id into serverIds -- they read as live armed triggers but can never fire, so
                    // drop the whole entry here rather than let it surface as a phantom armed row.
                    if (entry == null || entry.fallbackDate + 300 < now
                            || entry.hasInvalidIds()
                            || (entry.serverIds.isEmpty() && entry.localIds.isEmpty()
                            && (entry.bindExpiresAt <= 0 || entry.bindExpiresAt <= now))) {
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

    public static synchronized ArrayList<EventScheduleEntry> forAccount(int account) {
        return new ArrayList<>(cache(account).values());
    }

    public static synchronized void persist(int account, EventScheduleEntry entry) {
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
        EventScheduleEntry removed = m.remove(key);
        if (removed != null) {
            EventScheduleController.onEntryRemoved(account, removed);
        }
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

    /** Outcome of {@link #resolveAndClaimForEdit}; consumed by the controller, never by the UI directly. */
    public static final class EditClaim {
        public enum Status { REJECTED_MULTI, REJECTED_INVALID_IDS, UPDATED_EXISTING, CLAIMED_FRESH }
        public final Status status;
        public final EventScheduleEntry entry;      // live resolved entry (null on reject)
        public final String previousTriggerKey;     // the entry's triggerKey() BEFORE the edit; null on fresh/reject

        EditClaim(Status status, EventScheduleEntry entry, String previousTriggerKey) {
            this.status = status;
            this.entry = entry;
            this.previousTriggerKey = previousTriggerKey;
        }
    }

    // Distinct live entries in this dialog that own any of these ids by EITHER correlation space: a
    // positive serverId (bound) or a negative localId (a still-pending arm claimed from a scheduled
    // echo). Matching only serverIds -- as an earlier draft did -- misses a pending owner and lets a
    // second entry be armed beside it.
    private static ArrayList<EventScheduleEntry> collectExactCandidates(int account, long dialogId, int[] positiveIds, int[] negativeLocalIds) {
        ArrayList<EventScheduleEntry> out = new ArrayList<>();
        for (EventScheduleEntry e : cache(account).values()) {
            if (e.dialogId != dialogId) continue;
            boolean hit = false;
            if (positiveIds != null) {
                for (int id : positiveIds) {
                    if (id > 0 && e.serverIds.contains(id)) { hit = true; break; }
                }
            }
            // A bound entry keeps its old negative localIds, but they are no longer safe to match on.
            // Negative locals come from UserConfig.lastSendMessageId, which clearConfig() resets on
            // logout, so after a logout/login on the same slot the allocator hands the same negative
            // ids back out. An unrelated new scheduled message in this dialog can then be given an id a
            // stale bound entry still holds and would resolve to it -- absorbing it on Done or disarming
            // it on Clear. Once bound, an entry is owned only through positive server identity; local
            // identity resolves only entries that are still unbound.
            if (!hit && e.serverIds.isEmpty() && negativeLocalIds != null) {
                for (int id : negativeLocalIds) {
                    if (id < 0 && e.localIds.contains(id)) { hit = true; break; }
                }
            }
            if (hit) out.add(e);
        }
        return out;
    }

    /**
     * The single ownership gate for arming a trigger while editing a scheduled message. In one monitor
     * scope it re-resolves ownership across both id spaces, refuses a multi-owner state, and either
     * merges into the one existing owner or inserts a collision-free fresh entry -- then persists. The
     * check and the persist are inseparable, and the mutation happens on the live cached object here
     * rather than in the caller, so no caller-side alias can pre-empt a rejection. Fails closed on an
     * empty or non-positive id set (Required by the same in-flight-id invariant armExisting enforced):
     * arming a trigger against ids the server has not issued yields one that reports live but can never
     * fire, the #256 defect class.
     */
    public static synchronized EditClaim resolveAndClaimForEdit(
            int account, long dialogId, int[] positiveIds, int[] negativeLocalIds,
            int types, String pattern, boolean regex, int delaySeconds, int fallbackDate, long freshCreatedAt) {
        if (positiveIds == null || positiveIds.length == 0) {
            return new EditClaim(EditClaim.Status.REJECTED_INVALID_IDS, null, null);
        }
        for (int id : positiveIds) {
            if (id <= 0) {
                return new EditClaim(EditClaim.Status.REJECTED_INVALID_IDS, null, null);
            }
        }
        HashMap<String, EventScheduleEntry> m = cache(account);
        ArrayList<EventScheduleEntry> candidates = collectExactCandidates(account, dialogId, positiveIds, negativeLocalIds);
        if (candidates.size() > 1) {
            return new EditClaim(EditClaim.Status.REJECTED_MULTI, null, null);
        }
        if (candidates.size() == 1) {
            EventScheduleEntry target = candidates.get(0);
            String previousTriggerKey = target.triggerKey();
            for (int id : positiveIds) {
                if (!target.serverIds.contains(id)) target.serverIds.add(id);
            }
            target.revision++;
            target.types = types;
            target.pattern = pattern == null ? "" : pattern;
            target.regex = regex;
            target.delaySeconds = delaySeconds;
            target.fallbackDate = fallbackDate;
            target.bindGroupedId = 0;
            target.bindExpiresAt = 0;
            target.state = EventScheduleEntry.STATE_ARMED;
            target.resetPatternState();
            persist(account, target);
            return new EditClaim(EditClaim.Status.UPDATED_EXISTING, target, previousTriggerKey);
        }
        EventScheduleEntry fresh = new EventScheduleEntry();
        fresh.dialogId = dialogId;
        for (int id : positiveIds) {
            if (!fresh.serverIds.contains(id)) fresh.serverIds.add(id);
        }
        fresh.types = types;
        fresh.pattern = pattern == null ? "" : pattern;
        fresh.regex = regex;
        fresh.delaySeconds = delaySeconds;
        fresh.fallbackDate = fallbackDate;
        fresh.createdAt = freshCreatedAt;
        fresh.bindGroupedId = 0;
        fresh.bindExpiresAt = 0;
        fresh.state = EventScheduleEntry.STATE_ARMED;
        // key() is dialogId_createdAt at millisecond resolution, so two arms in the same millisecond can
        // collide; bump forward to an unused key before inserting rather than overwrite a live entry.
        while (m.containsKey(fresh.key())) {
            fresh.createdAt++;
        }
        persist(account, fresh);
        return new EditClaim(EditClaim.Status.CLAIMED_FRESH, fresh, null);
    }

    /**
     * Keeps a lone owner's derived schedule time in step when the user edits a scheduled message's send
     * time but never opens the trigger controls. fallbackDate is derived from the message, not user
     * trigger configuration, so it must track the new time: a stale value gets the entry pruned on
     * reload (fallbackDate + 300 < now) and mis-orders the fallback-time send queue. Resolves the owner
     * by exact id across both id spaces and only ever touches a single owner -- MULTI or NONE change
     * nothing, so the multi-owner conflict this change guards stays untouched. Does not create, remove,
     * or reconfigure a trigger; only the derived date moves. Returns the refreshed live entry so the
     * caller can re-sort its queue bucket, or null when there was no single owner.
     */
    public static synchronized EventScheduleEntry refreshFallbackForEdit(
            int account, long dialogId, int[] positiveIds, int[] negativeLocalIds, int fallbackDate) {
        ArrayList<EventScheduleEntry> candidates = collectExactCandidates(account, dialogId, positiveIds, negativeLocalIds);
        if (candidates.size() != 1) {
            return null;
        }
        EventScheduleEntry target = candidates.get(0);
        if (target.fallbackDate == fallbackDate) {
            return target;
        }
        target.fallbackDate = fallbackDate;
        persist(account, target);
        return target;
    }

    /** Which ownership state the edit sheet found for its message: nothing, exactly one owner, or a conflict. */
    public enum EditOwner { NONE, SINGLE, MULTI }

    /** Seed result for the edit sheet -- an explicit tri-state so a conflict can never read as "no trigger". */
    public static final class OwnerSeed {
        public final EditOwner kind;
        public final EventScheduleEntry entry;   // non-null only for SINGLE

        OwnerSeed(EditOwner kind, EventScheduleEntry entry) {
            this.kind = kind;
            this.entry = entry;
        }
    }

    /**
     * Read-only ownership resolution for seeding the edit sheet's controls, as an explicit tri-state.
     * Uses the same exact-id (both id spaces) scan as the arm path so a still-pending owner -- which a
     * positive-serverId lookup would miss -- shows as armed rather than off. MULTI is kept distinct from
     * NONE so a pre-existing multi-owner conflict is never seeded as an ordinary off, which would let an
     * untouched schedule-only edit destroy it. Selects only; mutates nothing.
     */
    public static synchronized OwnerSeed resolveOwnerSeedForEdit(int account, long dialogId, int[] positiveIds, int[] negativeLocalIds) {
        ArrayList<EventScheduleEntry> candidates = collectExactCandidates(account, dialogId, positiveIds, negativeLocalIds);
        if (candidates.isEmpty()) {
            return new OwnerSeed(EditOwner.NONE, null);
        }
        if (candidates.size() == 1) {
            return new OwnerSeed(EditOwner.SINGLE, candidates.get(0));
        }
        return new OwnerSeed(EditOwner.MULTI, null);
    }

    /** Owner keys to drop when a trigger is turned off while editing -- same exact-id resolution as the arm path. */
    public static synchronized ArrayList<String> resolveOwnerKeysForEdit(int account, long dialogId, int[] positiveIds, int[] negativeLocalIds) {
        ArrayList<String> keys = new ArrayList<>();
        for (EventScheduleEntry e : collectExactCandidates(account, dialogId, positiveIds, negativeLocalIds)) {
            keys.add(e.key());
        }
        return keys;
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
