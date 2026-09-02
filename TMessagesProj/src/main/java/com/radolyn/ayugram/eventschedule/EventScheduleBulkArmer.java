package com.radolyn.ayugram.eventschedule;

import android.util.SparseIntArray;

import androidx.annotation.NonNull;

import com.radolyn.ayugram.reschedule.RescheduleSpreadExecutor;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.BulletinFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/**
 * Arms one shared event-schedule trigger across a bulk reschedule, using deferred atomic
 * activation: nothing is armed while the reschedule is in flight; the whole selection is reconciled
 * and published in a single UI-thread pass once the run's closing scheduled-history read returns.
 * Progressive arming is deliberately avoided -- a trigger firing mid-run would enrol only the armed
 * prefix and leave the rest needing a second key phrase, which is the exact problem this feature
 * exists to remove.
 *
 * <p>One instance per run. The bulk-reschedule handler builds it on the UI thread with the shared
 * trigger config, the dialog, and the full album child-id set of every selected message, then hands
 * it to {@link RescheduleSpreadExecutor#run} as its
 * {@link RescheduleSpreadExecutor.TriggerArmingHooks}.
 */
public final class EventScheduleBulkArmer implements RescheduleSpreadExecutor.TriggerArmingHooks {

    private final int account;
    private final long dialogId;
    private final EventScheduleConfig config;
    // Full album child-id set per selected message, snapshotted at build time (never re-expanded from
    // a live fragment). Used to detach pre-existing entries at admission and to seed the createdAt
    // sequence above anything already persisted.
    private final List<int[]> selectionAlbumIds;

    // Independent JSON snapshots of the entries the selected messages already carried, detached at
    // admission so a live trigger can't fire mid-run and then read back as missing during
    // verification (C1). Kept for restore under I2.
    private final ArrayList<EventScheduleEntry> detached = new ArrayList<>();

    // Exact scheduled ids deleted while the run was in flight, collected on the UI thread. An entry
    // only survives finalization if none of its ids appear here (C2): a deletion can land after the
    // server snapshot, or before it but with its UI notification arriving before finalization, and
    // because no new entry exists yet the normal purgeIds path would remove nothing.
    private final HashSet<Integer> deletedDuringRun = new HashSet<>();
    private boolean collecting;

    // Built in the constructor, not as a field initializer, so its capture of account/dialogId reads
    // fields that are already assigned (a field initializer would run before the constructor body).
    private final NotificationCenter.NotificationCenterDelegate deletionCollector;

    public EventScheduleBulkArmer(int account, long dialogId, @NonNull EventScheduleConfig config, @NonNull List<int[]> selectionAlbumIds) {
        this.account = account;
        this.dialogId = dialogId;
        this.config = config;
        this.selectionAlbumIds = selectionAlbumIds;
        this.deletionCollector = (id, acc, args) -> {
            if (id != NotificationCenter.messagesDeleted || acc != account) return;
            boolean scheduled = args.length > 2 && args[2] instanceof Boolean && (Boolean) args[2];
            if (!scheduled) return;
            long channelId = args.length > 1 && args[1] instanceof Long ? (Long) args[1] : 0L;
            // Scheduled-delete channelId is channel-only; mirror EventScheduleStore.purgeIds' filter so
            // a delete in another chat can't invalidate this run.
            if (channelId != 0 && dialogId != -channelId) return;
            Object raw = args.length > 0 ? args[0] : null;
            if (!(raw instanceof ArrayList)) return;
            for (Object o : (ArrayList<?>) raw) {
                if (o instanceof Integer) deletedDuringRun.add((Integer) o);
            }
        };
    }

    @Override
    public void onAdmission() {
        EventScheduleStore.ensureLoaded(account);
        NotificationCenter.getInstance(account).addObserver(deletionCollector, NotificationCenter.messagesDeleted);
        collecting = true;
        for (int[] album : selectionAlbumIds) {
            for (EventScheduleEntry live : findExisting(album)) {
                EventScheduleEntry snapshot = EventScheduleEntry.fromJson(live.toJson());
                if (snapshot != null && !containsKey(detached, snapshot.key())) {
                    detached.add(snapshot);
                }
                // Removing clears the queue and pending state too, so the detached trigger can't fire
                // for the duration of the run. A restore under I2 re-arms it from the snapshot.
                EventScheduleStore.remove(account, live);
            }
        }
    }

    @Override
    public void onFinalize(List<RescheduleSpreadExecutor.TargetOutcome> outcomes, int[] scheduledIds, int[] scheduledDates,
                           boolean authoritative, boolean overlap, int rescheduleWrong, int rescheduleTotal, BaseFragment fragment) {
        AndroidUtilities.runOnUIThread(() ->
                finalizeOnUi(outcomes, scheduledIds, scheduledDates, authoritative, overlap, rescheduleWrong, rescheduleTotal, fragment));
    }

    private void finalizeOnUi(List<RescheduleSpreadExecutor.TargetOutcome> outcomes, int[] scheduledIds, int[] scheduledDates,
                              boolean authoritative, boolean overlap, int rescheduleWrong, int rescheduleTotal, BaseFragment fragment) {
        // C2 collector lifetime: removal happens here, before the fragment guard below, and this method
        // always runs. The executor's closing scheduled-history read always calls onFinalize (a tgnet
        // request always calls back, on success or error), and onFinalize hops here with runOnUIThread
        // regardless of fragment state, so removal is not gated on the run "succeeding" or on the
        // fragment being alive. A backgrounded process still runs the callback and removes the observer;
        // a killed process drops the in-memory observer with everything else. The `collecting` flag
        // makes the removal idempotent. Each run owns its own armer instance and its own collector
        // lambda, so this only ever unregisters this run's observer, never a concurrent run's.
        if (collecting) {
            NotificationCenter.getInstance(account).removeObserver(deletionCollector, NotificationCenter.messagesDeleted);
            collecting = false;
        }

        final SparseIntArray serverDates = new SparseIntArray(scheduledIds.length);
        for (int i = 0; i < scheduledIds.length; i++) {
            serverDates.put(scheduledIds[i], scheduledDates[i]);
        }

        // Fail closed on overlap (a concurrent run may have moved these dates under us, C3) or on a
        // failed authoritative read (we can't trust membership). Arm nothing new; restore what is
        // still valid.
        final boolean failClosed = overlap || !authoritative;

        int armed = 0;
        int notArmed = 0;
        int restoreFailed = 0;
        final ArrayList<int[]> survivorAlbums = new ArrayList<>();
        if (!failClosed) {
            // One batch-local createdAt sequence in milliseconds (createdAt is millis everywhere:
            // EventScheduleHelper assigns System.currentTimeMillis(), key() and QUEUE_ORDER both read it),
            // seeded above every live and detached key so a new entry can never collide with a persisted
            // or about-to-be-restored one.
            long nextCreatedAt = Math.max(System.currentTimeMillis(), maxCreatedAtIncludingDetached() + 1);
            for (RescheduleSpreadExecutor.TargetOutcome o : outcomes) {
                if (!o.applied) {
                    notArmed++;
                    continue;
                }
                // Every album child must be present at the applied date, and none deleted mid-run;
                // one missing child rejects the whole album target.
                if (!allScheduledAt(o.albumIds, o.scheduleDate, serverDates) || anyDeleted(o.albumIds)) {
                    notArmed++;
                    continue;
                }
                survivorAlbums.add(o.albumIds);
                EventScheduleEntry entry = buildEntry(o.albumIds, o.scheduleDate, nextCreatedAt++);
                // Consume the arming result: a rejection here is a not-armed target, never a silent
                // success (rejection is reachable on this path).
                if (armSurvivor(entry)) {
                    armed++;
                } else {
                    notArmed++;
                }
            }
        }

        // Detached existing entries: a survivor supersedes its old entry, so leave it removed; a
        // non-survivor whose full ids are still scheduled at its old fallback date is a working
        // trigger the reschedule didn't touch, so restore it (I2). Everything else stays removed.
        for (EventScheduleEntry e : detached) {
            boolean superseded = !failClosed && intersectsAnySurvivor(e, survivorAlbums);
            if (superseded) continue;
            if (stillValid(e, serverDates)) {
                // Restore re-arms the same entry we detached at admission, from its snapshot. It is not
                // a new create, so it carries no duplicate-ownership hazard and needs no ownership seam.
                // detach->send->restore: detach at admission cleared this entry from the store, queue
                // and pending, so it could not fire during the run (any in-flight send already bails on
                // the store's contains() going false). armExisting re-persists it as ARMED and ensures
                // the observer; the entry re-enters the fire queue lazily on the next matching message
                // via evaluate(), exactly as a store reload after an app restart does, so its
                // QUEUE_ORDER position, pattern-state (recompiled from the snapshot) and revision are
                // re-derived coherently rather than assumed carried across the detach.
                //
                // The result is consumed. Today it is always true (a detached entry was live, so its
                // serverIds are non-empty and positive). Once a commit-time ownership check exists it can
                // reject: the ids are then legitimately owned elsewhere, so leaving the entry removed is
                // the correct STATE -- but the user configured that trigger and we already deleted it at
                // admission, so a rejected restore is a trigger silently lost. Count and surface it: this
                // is the mirror of the not-armed summary (absent believed present), and it must not be
                // the one surface left uncovered.
                if (!EventScheduleController.armExisting(account, e)) {
                    restoreFailed++;
                }
            }
        }

        // Store work above is unconditional (I3); only the user-facing refresh and bulletin depend on
        // the fragment still being alive.
        if (fragment != null && fragment.getParentActivity() != null) {
            showBulletin(fragment, failClosed, armed, notArmed, restoreFailed, rescheduleWrong, rescheduleTotal);
        }
    }

    /**
     * The atomic, ownership-enforcing create-and-persist for a freshly armed target. It must admit a
     * new bound entry only when no other owner already holds its positive ids -- resolved at commit
     * time against the store, with the rejection propagated back here -- because a stale single-message
     * sheet can bind the same positive id in parallel and both ids pass sign validation. That
     * boundary is owned outside this change; until it exists this arms nothing and reports the target
     * as not armed, so no half-owned entry is ever written and the caller still counts the outcome.
     */
    private boolean armSurvivor(@NonNull EventScheduleEntry entry) {
        return false;
    }

    private ArrayList<EventScheduleEntry> findExisting(int[] albumIds) {
        ArrayList<EventScheduleEntry> found = new ArrayList<>();
        for (int id : albumIds) {
            EventScheduleEntry e = EventScheduleStore.findByMessage(account, dialogId, id);
            if (e != null && !found.contains(e)) found.add(e);
        }
        return found;
    }

    private EventScheduleEntry buildEntry(int[] albumIds, int scheduleDate, long createdAt) {
        EventScheduleEntry e = new EventScheduleEntry();
        e.dialogId = dialogId;
        for (int id : albumIds) e.serverIds.add(id);
        e.types = config.types;
        e.pattern = config.pattern;
        e.regex = config.regex;
        e.delaySeconds = config.delaySeconds;
        e.fallbackDate = scheduleDate;
        e.createdAt = createdAt;
        e.bindGroupedId = 0;
        e.bindExpiresAt = 0;
        e.state = EventScheduleEntry.STATE_ARMED;
        return e;
    }

    private long maxCreatedAtIncludingDetached() {
        long max = 0;
        for (EventScheduleEntry e : EventScheduleStore.forDialog(account, dialogId)) {
            if (e.createdAt > max) max = e.createdAt;
        }
        for (EventScheduleEntry e : detached) {
            if (e.createdAt > max) max = e.createdAt;
        }
        return max;
    }

    private boolean allScheduledAt(int[] albumIds, int scheduleDate, SparseIntArray serverDates) {
        for (int id : albumIds) {
            if (serverDates.get(id, -1) != scheduleDate) return false;
        }
        return true;
    }

    private boolean anyDeleted(int[] albumIds) {
        for (int id : albumIds) {
            if (deletedDuringRun.contains(id)) return true;
        }
        return false;
    }

    private boolean stillValid(EventScheduleEntry e, SparseIntArray serverDates) {
        if (e.serverIds.isEmpty()) return false;
        for (int id : e.serverIds) {
            if (deletedDuringRun.contains(id)) return false;
            if (serverDates.get(id, -1) != e.fallbackDate) return false;
        }
        return true;
    }

    private boolean intersectsAnySurvivor(EventScheduleEntry e, ArrayList<int[]> survivorAlbums) {
        for (int[] album : survivorAlbums) {
            for (int id : album) {
                if (e.serverIds.contains(id)) return true;
            }
        }
        return false;
    }

    private static boolean containsKey(ArrayList<EventScheduleEntry> list, String key) {
        for (EventScheduleEntry e : list) {
            if (e.key().equals(key)) return true;
        }
        return false;
    }

    private void showBulletin(BaseFragment fragment, boolean failClosed, int armed, int notArmed, int restoreFailed, int rescheduleWrong, int rescheduleTotal) {
        final String base = rescheduleWrong == 0
                ? LocaleController.formatPluralString("RescheduleApplied", rescheduleTotal)
                : LocaleController.formatString(R.string.RescheduleVerifyFailed, rescheduleWrong, rescheduleTotal);
        final String triggerLine;
        if (failClosed) {
            triggerLine = LocaleController.getString(R.string.EventScheduleBulkTriggerNotApplied);
        } else if (notArmed > 0) {
            triggerLine = LocaleController.formatString(R.string.EventScheduleBulkTriggerPartial, armed, armed + notArmed);
        } else {
            triggerLine = LocaleController.formatPluralString("EventScheduleBulkTriggerArmed", armed);
        }
        // A restore that was rejected means a trigger the user had before this run is now gone; it is a
        // distinct failure from "couldn't add the new trigger", so it gets its own line rather than being
        // folded into notArmed, and it always forces the error icon.
        String message = base + "\n" + triggerLine;
        if (restoreFailed > 0) {
            message += "\n" + LocaleController.formatPluralString("EventScheduleBulkTriggerRestoreFailed", restoreFailed);
        }
        final int icon = (rescheduleWrong == 0 && !failClosed && notArmed == 0 && restoreFailed == 0) ? R.raw.chats_infotip : R.raw.error;
        BulletinFactory.of(fragment).createSimpleBulletin(icon, message).show();
    }
}
