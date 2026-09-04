package com.radolyn.ayugram.eventschedule;

import android.content.SharedPreferences;

import androidx.annotation.NonNull;

import org.telegram.messenger.ApplicationLoader;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.TreeSet;

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
                            || (entry.types == 0 && !entry.hasAnyPattern())
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
        // NagramX: the single runtime enforcement point for the delay cap. persist() is the common
        // durability boundary every live writer funnels through -- resolveAndClaimForEdit's both branches,
        // armPending (before it ever reaches addPending/pending state), and the bulk armer's claim-and-persist
        // -- so clamping here once covers all of them without a clamp duplicated at each call site (and
        // without a future writer being able to forget one, the way a per-branch clamp could).
        entry.delaySeconds = Math.min(entry.delaySeconds, EventScheduleEntry.MAX_DELAY_SECONDS);
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
     * empty or non-positive id set (the same in-flight-id invariant this gate is the sole live enforcer
     * of; the no-caller armExisting compat path checks it too):
     * arming a trigger against ids the server has not issued yields one that reports live but can never
     * fire, the #256 defect class.
     *
     * <p>Neither branch below clamps {@code delaySeconds} itself -- the delay cap is enforced once, in
     * {@link #persist}, the common durability boundary both branches (and every other live writer) funnel
     * through, so it can't drift out of sync between them.
     */
    public static synchronized EditClaim resolveAndClaimForEdit(
            int account, long dialogId, int[] positiveIds, int[] negativeLocalIds,
            @NonNull EventScheduleConfig config, int fallbackDate, long freshCreatedAt) {
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
            target.types = config.types;
            target.setPatterns(config.patterns);
            target.regex = config.regex;
            target.delaySeconds = config.delaySeconds;
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
        fresh.types = config.types;
        fresh.setPatterns(config.patterns);
        fresh.regex = config.regex;
        fresh.delaySeconds = config.delaySeconds;
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

    // --- Durable correlation reconcile (#261) -------------------------------------------------------
    // Kept factorable from the single-edit flow: #249 shares this same immutable-snapshot -> pure
    // classification -> atomic heal pipeline for its batch reschedule. Nothing here holds a live entry,
    // fragment, or delegate across the storage hop the controller runs between collect and apply.

    /**
     * Immutable snapshot of one unbound entry handed to the async storage lookup. Only these fields
     * cross the thread boundary; the live {@link EventScheduleEntry} is re-fetched by {@link #key} and
     * re-verified against {@link #revision} + {@link #randomIds} before any mutation, so a concurrent
     * edit or bind cannot be overwritten by a stale continuation.
     */
    public static final class EntrySnapshot {
        public final String key;
        public final long dialogId;
        public final long revision;
        public final long[] randomIds;
        // How many distinct local children this entry claimed. Every one of them must have contributed a
        // distinct nonzero random id, so a randomIds shorter than this count means a child was dropped at
        // capture (zero or duplicate id) and the album can only be partially healed -- fail closed.
        public final int localChildCount;

        EntrySnapshot(String key, long dialogId, long revision, long[] randomIds, int localChildCount) {
            this.key = key;
            this.dialogId = dialogId;
            this.revision = revision;
            this.randomIds = randomIds;
            this.localChildCount = localChildCount;
        }
    }

    /** Per-entry verdict from {@link #classifyDurable}; the controller applies heal or policy from it. */
    public static final class DurableResolution {
        public enum Verdict {
            // Every distinct random_id resolved one-to-one to a distinct positive server mid.
            HEAL,
            // At least one random_id resolved to a negative mid: the unsent scheduled row still exists,
            // so the live resend/remap path (or ordinary expiry) owns it, not the heal.
            PENDING_UNSENT,
            // A random_id has no joined row: the scheduled message was fired or locally deleted. Never
            // resurrect it -- fail-closed expiry cleanup applies.
            MISSING,
            // A cardinality/collision failure (within-entry non-injective, a random_id or healed mid
            // shared across entries, or >1 mid for one random_id). Deliberately never healed -- distinct
            // from a genuine resolved-but-contested multi-owner conflict, hence not REJECTED_MULTI.
            REJECTED_UNRESOLVED,
            // The read failed (query/enqueue/construction). Non-destructive: retain the entry inert for a
            // later process to retry; never expiry-remove on a failed read.
            LOOKUP_ERROR
        }

        public final Verdict verdict;
        public final int[] healMids;   // sorted ascending; non-null only for HEAL

        DurableResolution(Verdict verdict, int[] healMids) {
            this.verdict = verdict;
            this.healMids = healMids;
        }
    }

    private static EntrySnapshot snapshot(EventScheduleEntry e) {
        long[] r = new long[e.randomIds.size()];
        for (int i = 0; i < r.length; i++) r[i] = e.randomIds.get(i);
        return new EntrySnapshot(e.key(), e.dialogId, e.revision, r, e.localIds.size());
    }

    /** Immutable snapshots of every unbound entry across the account that carries durable keys. */
    public static synchronized ArrayList<EntrySnapshot> collectUnboundRandomSnapshots(int account) {
        ArrayList<EntrySnapshot> out = new ArrayList<>();
        for (EventScheduleEntry e : cache(account).values()) {
            if (e.serverIds.isEmpty() && !e.randomIds.isEmpty()) out.add(snapshot(e));
        }
        return out;
    }

    /** As above, scoped to one dialog -- the commit-time fallback only ever reconciles its own dialog. */
    public static synchronized ArrayList<EntrySnapshot> collectUnboundRandomSnapshots(int account, long dialogId) {
        ArrayList<EntrySnapshot> out = new ArrayList<>();
        for (EventScheduleEntry e : cache(account).values()) {
            if (e.dialogId == dialogId && e.serverIds.isEmpty() && !e.randomIds.isEmpty()) out.add(snapshot(e));
        }
        return out;
    }

    /** Synchronous in-memory short-circuit: is there an unbound durable orphan in this dialog at all? */
    public static synchronized boolean hasUnboundRandomEntry(int account, long dialogId) {
        for (EventScheduleEntry e : cache(account).values()) {
            if (e.dialogId == dialogId && e.serverIds.isEmpty() && !e.randomIds.isEmpty()) return true;
        }
        return false;
    }

    public static synchronized EventScheduleEntry findByKey(int account, String key) {
        return cache(account).get(key);
    }

    /**
     * Pure classification of the joined {@code random_id -> mids} rows against the entry snapshots. No
     * cache or storage access, so it is trivially reusable and testable. {@code resolvedByRandom} maps
     * each queried random_id to the distinct mids the JOIN returned for it (empty/absent means no joined
     * row -- a fired or locally deleted scheduled message, never healed). The within-entry injectivity
     * and cross-entry collision guards are applied here so a partial album or a shared correlator can
     * never arm.
     */
    public static Map<String, DurableResolution> classifyDurable(
            ArrayList<EntrySnapshot> snaps, Map<Long, ArrayList<Integer>> resolvedByRandom, boolean lookupError) {
        HashMap<String, DurableResolution> out = new HashMap<>();
        if (lookupError) {
            for (EntrySnapshot s : snaps) out.put(s.key, new DurableResolution(DurableResolution.Verdict.LOOKUP_ERROR, null));
            return out;
        }
        // Cross-entry prep: which entries claim each random_id. A random_id in more than one entry is a
        // corrupt precondition -- both entries fail closed.
        HashMap<Long, HashSet<String>> randomOwners = new HashMap<>();
        for (EntrySnapshot s : snaps) {
            for (long r : distinctOf(s.randomIds)) {
                HashSet<String> owners = randomOwners.get(r);
                if (owners == null) { owners = new HashSet<>(); randomOwners.put(r, owners); }
                owners.add(s.key);
            }
        }
        HashMap<String, int[]> healCandidate = new HashMap<>();
        for (EntrySnapshot s : snaps) {
            DurableResolution.Verdict v = classifyOne(s, resolvedByRandom, randomOwners);
            if (v == DurableResolution.Verdict.HEAL) {
                TreeSet<Integer> mids = new TreeSet<>();
                for (long r : distinctOf(s.randomIds)) {
                    mids.add(resolvedByRandom.get(r).get(0));
                }
                int[] arr = new int[mids.size()];
                int i = 0;
                for (int m : mids) arr[i++] = m;
                healCandidate.put(s.key, arr);
                out.put(s.key, new DurableResolution(DurableResolution.Verdict.HEAL, arr));
            } else {
                out.put(s.key, new DurableResolution(v, null));
            }
        }
        // Cross-entry heal collision: the same scheduled message healing into more than one entry demotes
        // them all -- among these heal candidates, two of them resolving to one message is corrupt, so
        // neither is allowed to claim it. (This checks only the heal set, not already-bound serverIds, so
        // it catches restart collisions, not a pre-existing multi-owner already in the store.) A scheduled
        // message's identity is (dialogId, mid), not the bare mid: unrelated dialogs routinely reuse the
        // same local id, so keying on mid alone would wrongly collide -- and thus wrongly reject -- two
        // healthy orphans in different chats. Key on (dialogId, mid) so only a genuine same-dialog clash is
        // caught.
        HashMap<String, HashSet<String>> midOwners = new HashMap<>();
        for (EntrySnapshot s : snaps) {
            int[] mids = healCandidate.get(s.key);
            if (mids == null) continue;
            for (int mid : mids) {
                String midKey = s.dialogId + ":" + mid;
                HashSet<String> owners = midOwners.get(midKey);
                if (owners == null) { owners = new HashSet<>(); midOwners.put(midKey, owners); }
                owners.add(s.key);
            }
        }
        for (HashSet<String> owners : midOwners.values()) {
            if (owners.size() > 1) {
                for (String k : owners) {
                    out.put(k, new DurableResolution(DurableResolution.Verdict.REJECTED_UNRESOLVED, null));
                }
            }
        }
        return out;
    }

    private static DurableResolution.Verdict classifyOne(
            EntrySnapshot s, Map<Long, ArrayList<Integer>> resolved, Map<Long, HashSet<String>> randomOwners) {
        HashSet<Long> distinct = distinctOf(s.randomIds);
        // Capture-time cardinality (the relation upstream of everything else here): every claimed local
        // child must have contributed exactly one distinct nonzero random id. claim() drops a child with a
        // zero id and de-duplicates, so fewer keys than children means the child -> random_id map already
        // lost a member before this lookup ran. Healing that resolves only the surviving keys and arms a
        // partial album, so reject unless the count matches. This guards the one relation the within- and
        // cross-entry checks below cannot see, because it was collapsed at capture, not here.
        if (s.randomIds.length != s.localChildCount) return DurableResolution.Verdict.REJECTED_UNRESOLVED;
        // Within-entry: a duplicate random_id in the persisted list is itself a many-to-one violation.
        if (distinct.size() != s.randomIds.length) return DurableResolution.Verdict.REJECTED_UNRESOLVED;
        // Cross-entry: a random_id shared with another entry.
        for (long r : distinct) {
            HashSet<String> owners = randomOwners.get(r);
            if (owners != null && owners.size() > 1) return DurableResolution.Verdict.REJECTED_UNRESOLVED;
        }
        boolean anyMissing = false, anyNegative = false;
        HashSet<Integer> positiveMids = new HashSet<>();
        for (long r : distinct) {
            ArrayList<Integer> mids = resolved.get(r);
            HashSet<Integer> dm = new HashSet<>();
            if (mids != null) dm.addAll(mids);
            if (dm.isEmpty()) {
                anyMissing = true;
            } else if (dm.size() > 1) {
                // One random_id resolving to more than one distinct mid: ambiguous, fail closed.
                return DurableResolution.Verdict.REJECTED_UNRESOLVED;
            } else {
                int mid = dm.iterator().next();
                if (mid > 0) positiveMids.add(mid);
                else anyNegative = true;
            }
        }
        if (anyMissing) return DurableResolution.Verdict.MISSING;
        if (anyNegative) return DurableResolution.Verdict.PENDING_UNSENT;
        // All distinct randoms resolved to exactly one positive mid; require strict injectivity so two
        // children collapsing onto one server message can never arm a partial trigger.
        if (positiveMids.size() != distinct.size()) return DurableResolution.Verdict.REJECTED_UNRESOLVED;
        return DurableResolution.Verdict.HEAL;
    }

    private static HashSet<Long> distinctOf(long[] ids) {
        HashSet<Long> set = new HashSet<>();
        for (long id : ids) set.add(id);
        return set;
    }

    /**
     * Atomically heals one entry to its resolved server ids, under the monitor and only if the live
     * entry still matches the snapshot it was classified from (same revision, same random_id set, still
     * unbound). Any mismatch -- an edit, a bind by the live path, a removal -- makes this a no-op so the
     * stale continuation cannot resurrect or overwrite. {@code sortedMids} arrive ascending so
     * {@code serverIds.get(0)} (the QUEUE_ORDER tie-break and overview preview) is deterministic.
     */
    public static synchronized boolean healDurable(int account, EntrySnapshot snap, int[] sortedMids) {
        EventScheduleEntry e = cache(account).get(snap.key);
        if (e == null) return false;
        if (e.revision != snap.revision) return false;
        if (!e.serverIds.isEmpty()) return false;
        if (!sameRandomIds(e, snap.randomIds)) return false;
        for (int mid : sortedMids) {
            if (mid > 0 && !e.serverIds.contains(mid)) e.serverIds.add(mid);
        }
        if (e.serverIds.isEmpty()) return false;
        e.bindExpiresAt = 0;
        e.state = EventScheduleEntry.STATE_ARMED;
        persist(account, e);
        return true;
    }

    private static boolean sameRandomIds(EventScheduleEntry e, long[] snap) {
        if (e.randomIds.size() != snap.length) return false;
        HashSet<Long> a = new HashSet<>(e.randomIds);
        HashSet<Long> b = new HashSet<>();
        for (long x : snap) b.add(x);
        return a.equals(b);
    }

    /**
     * Snapshot-guarded expiry removal: drops an entry only if the live entry still matches the snapshot
     * it was classified from (same revision and random-id set), is still unbound, and its bind window has
     * elapsed. The guards stop a slow reconcile result from removing an entry that was edited, bound, or
     * replaced in the meantime -- the delete must be authorized by the same generation that was looked up,
     * never by a stale one. Returns whether it removed anything.
     */
    public static synchronized boolean removeIfExpiredUnbound(int account, EntrySnapshot snap, long nowSec) {
        EventScheduleEntry e = cache(account).get(snap.key);
        if (e == null) return false;
        if (e.revision != snap.revision) return false;
        if (!e.serverIds.isEmpty()) return false;
        if (e.randomIds.isEmpty()) return false;
        if (!sameRandomIds(e, snap.randomIds)) return false;
        if (e.bindExpiresAt <= 0 || e.bindExpiresAt > nowSec) return false;
        remove(account, snap.key);
        return true;
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
