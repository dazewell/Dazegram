package com.radolyn.ayugram.eventschedule;

import android.util.SparseIntArray;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.radolyn.ayugram.reschedule.RescheduleSpreadExecutor;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.BulletinFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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

    /**
     * Reveals the bolt on the armed rows at finalization. The armer never touches the fragment itself --
     * the visible-rows refresh it needs is a private ChatActivity overload -- so the fragment hands in a
     * lambda that carries the armed message ids and forces a re-measure of just those rows. Called once,
     * on the UI thread, only when the fragment is still alive and at least one target armed.
     */
    public interface TriggerRefresh {
        void revealArmed(@NonNull Set<Integer> armedServerIds);
    }

    private final int account;
    private final long dialogId;
    private final EventScheduleConfig config;
    // Full album child-id set per selected message, snapshotted at build time (never re-expanded from
    // a live fragment). Used to suppress pre-existing entries at admission and to seed the createdAt
    // sequence above anything already persisted.
    private final List<int[]> selectionAlbumIds;
    // Reveals the bolt on the armed rows at finalization (nullable: an armer built without a fragment,
    // e.g. in a test, simply skips the refresh).
    @Nullable
    private final TriggerRefresh refresh;

    // Keys of the pre-existing entries this run is holding back, mapped to the entry revision seen at
    // admission. The key set is what finalization drains -- every hold this run placed is released,
    // driven by what we suppressed, not by what survived -- so a target that later dropped out of the
    // outcomes still gets released. The revision detects a single-message edit that landed mid-run, so
    // the bulk arm yields to that later explicit edit instead of overwriting it (C4). Nothing durable is
    // removed at admission: suppression is process-local, so a process death discards it and the durable
    // entry reloads armed, where a durable remove with only an in-memory copy would lose the trigger.
    private final HashMap<String, Long> suppressed = new HashMap<>();

    // Server ids of entries already SENDING at admission. An issued early send can't be recalled and
    // suppression can't hold it back, so its target is treated as linearized before the run and rejected
    // in verification rather than re-armed (I6).
    private final HashSet<Integer> sendingAtAdmissionIds = new HashSet<>();

    // Exact scheduled ids deleted while the run was in flight, collected on the UI thread. An entry
    // only survives finalization if none of its ids appear here (C2): a deletion can land after the
    // server snapshot, or before it but with its UI notification arriving before finalization.
    private final HashSet<Integer> deletedDuringRun = new HashSet<>();
    private boolean collecting;

    // Built in the constructor, not as a field initializer, so its capture of account/dialogId reads
    // fields that are already assigned (a field initializer would run before the constructor body).
    private final NotificationCenter.NotificationCenterDelegate deletionCollector;

    public EventScheduleBulkArmer(int account, long dialogId, @NonNull EventScheduleConfig config, @NonNull List<int[]> selectionAlbumIds, @Nullable TriggerRefresh refresh) {
        this.account = account;
        this.dialogId = dialogId;
        this.config = config;
        this.selectionAlbumIds = selectionAlbumIds;
        this.refresh = refresh;
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
                String key = live.key();
                if (suppressed.containsKey(key)) continue;
                // Hold the pre-existing trigger back for the run without touching durable state: it can't
                // fire mid-run and then read back missing during verification, but nothing is removed, so
                // a process death before finalization loses nothing (the entry reloads armed). A SENDING
                // entry can't be held back -- its send is already issued -- so record its ids instead, and
                // verification rejects that target rather than assuming the old trigger was suspended.
                if (EventScheduleController.suppressForBulk(account, live, this)) {
                    suppressed.put(key, live.revision);
                } else {
                    sendingAtAdmissionIds.addAll(live.serverIds);
                }
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
        // Reconcile this dialog's unbound durable orphans to current server ids before the dialog-wide gate
        // in finalizeAfterReconcile reads them (synchronous when there are none -- the common case, no
        // storage hop). The deletion collector stays registered across the hop -- it is removed only in
        // finalizeAfterReconcile -- so a scheduled-delete that lands during the hop is still recorded and
        // can still reject its target. reconcileDialogThen always runs its callback on the UI thread.
        EventScheduleController.reconcileDialogThen(account, dialogId, () ->
                finalizeAfterReconcile(outcomes, scheduledIds, scheduledDates, authoritative, overlap, rescheduleWrong, rescheduleTotal, fragment));
    }

    private void finalizeAfterReconcile(List<RescheduleSpreadExecutor.TargetOutcome> outcomes, int[] scheduledIds, int[] scheduledDates,
                                        boolean authoritative, boolean overlap, int rescheduleWrong, int rescheduleTotal, BaseFragment fragment) {
        // C2 collector lifetime: removal happens here, before the fragment guard below, and this method
        // always runs. The executor's closing scheduled-history read always calls onFinalize (a tgnet
        // request always calls back, on success or error), onFinalize hops to finalizeOnUi with
        // runOnUIThread, and the reconcile hop above always runs its callback here regardless of fragment
        // state, so removal is not gated on the run "succeeding" or on the fragment being alive. A
        // backgrounded process still runs the callback and removes the observer; a killed process drops
        // the in-memory observer with everything else. The `collecting` flag makes the removal idempotent.
        // Each run owns its own armer instance and its own collector lambda, so this only ever unregisters
        // this run's observer, never a concurrent run's.
        if (collecting) {
            NotificationCenter.getInstance(account).removeObserver(deletionCollector, NotificationCenter.messagesDeleted);
            collecting = false;
        }

        final SparseIntArray serverDates = new SparseIntArray(scheduledIds.length);
        for (int i = 0; i < scheduledIds.length; i++) {
            serverDates.put(scheduledIds[i], scheduledDates[i]);
        }

        // Fail closed on overlap (a concurrent run may have moved these dates under us) or on a failed
        // authoritative read (we can't trust membership). Arm nothing new; the finally below still
        // releases every hold this run placed, so the pre-existing triggers survive untouched.
        final boolean failClosed = overlap || !authoritative;

        // Dialog-wide decline: an unbound durable orphan that survived the reconcile above still sits in
        // this chat, and a bulk selection is dialog-scoped by definition, so it declines the WHOLE
        // selection -- nothing armed. This is a distinct outcome from every per-target not-armed reason
        // (which are permanent or message-specific): it is transient, retryable and dialog-wide, so it
        // carries its own bulletin line telling the user to try again shortly. Reporting "3 of 8 weren't
        // armed" when the truth is "none were, try again in a moment" is the exact UI-asserts-something-
        // -untrue failure this family of changes exists to prevent. See EventScheduleController's
        // single-message twin (finishCommitEdit) for why the block is dialog-wide and cannot narrow.
        final boolean dialogGate = !failClosed && EventScheduleStore.hasUnboundRandomEntry(account, dialogId);

        int armed = 0;
        int notArmed = 0;
        final HashSet<Integer> armedIds = new HashSet<>();
        try {
            if (!failClosed && !dialogGate) {
                // One batch-local createdAt sequence in milliseconds (createdAt is millis everywhere:
                // EventScheduleHelper assigns System.currentTimeMillis(), key() and QUEUE_ORDER both read
                // it), seeded above every persisted key so a freshly claimed entry can't collide.
                long nextCreatedAt = Math.max(System.currentTimeMillis(), maxCreatedAt() + 1);
                for (RescheduleSpreadExecutor.TargetOutcome o : outcomes) {
                    if (!o.applied) {
                        notArmed++;
                        continue;
                    }
                    // A repeating scheduled message can't carry a trigger -- the single-message path
                    // refuses it (armed = enabled && repeatPeriod == 0), because Premium repeat and
                    // early-trigger don't compose. The bulk path must not create a state the single path
                    // forbids, so a repeating target is left as a plain reschedule and counted not-armed.
                    if (o.repeatPeriod != 0) {
                        notArmed++;
                        continue;
                    }
                    // Every album child must be present at the applied date and none deleted mid-run; one
                    // missing child rejects the whole album target. A target whose old trigger was already
                    // SENDING at admission was linearized before the run -- its early send can't be
                    // recalled -- so it is rejected here rather than re-armed (I6).
                    if (!allScheduledAt(o.albumIds, o.scheduleDate, serverDates) || anyDeleted(o.albumIds)
                            || anySendingAtAdmission(o.albumIds)) {
                        notArmed++;
                        continue;
                    }
                    // A later single-message edit wins over this in-flight bulk arm (C4). If the message
                    // already carried a trigger whose revision changed since admission, the user
                    // re-authored it mid-run, so yield rather than overwrite it. Every path that edits an
                    // armed trigger bumps revision -- an author adding a new arming path must keep that
                    // true or this detection silently stops working.
                    if (editedMidRun(o.albumIds)) {
                        notArmed++;
                        continue;
                    }
                    EventScheduleEntry entry = buildEntry(o.albumIds, o.scheduleDate, nextCreatedAt++);
                    // armSurvivor merges the shared trigger into an existing owner in place, or claims a
                    // fresh entry when there is none -- it never removes an old entry, because on the
                    // merge path the "old" entry IS the survivor being armed. A contested id rejects and
                    // the target is counted not-armed. Consume the result either way.
                    if (armSurvivor(entry)) {
                        armed++;
                        // Reveal the bolt on exactly the rows that armed. albumIds are the message ids.
                        for (int id : o.albumIds) armedIds.add(id);
                    } else {
                        notArmed++;
                    }
                }
            }
        } finally {
            // I5: release every hold this run placed, unconditionally and driven by what we suppressed
            // (not by what survived), so a target dropped from the outcomes -- album collapse, dedup, a
            // per-target error, a fail-closed run, or the dialog-wide gate declining everything -- is
            // still released. Releasing is the safe direction: a wrongly-released trigger re-arms and
            // fires per its config, whereas a wrongly-retained hold silently never fires for the process
            // lifetime, the same invisible loss this redesign exists to prevent. A merged survivor keeps
            // its original key, so its hold is released here too -- correct, since the merged entry should
            // fire. armSurvivor already released the survivors it armed; this double release is a no-op.
            for (String key : suppressed.keySet()) {
                EventScheduleController.releaseSuppression(account, key, this);
            }
        }

        // Suppression release above is unconditional (I3/I5); only the user-facing refresh and bulletin
        // depend on the fragment still being alive.
        if (fragment != null && fragment.getParentActivity() != null) {
            // Bolt reveals only at finalization -- nothing is live until now, so this is the first honest
            // render. Refresh only the armed rows (forcing a re-measure so the bolt actually draws); skip
            // it entirely when nothing armed so no untouched row is needlessly rebuilt.
            if (refresh != null && !armedIds.isEmpty()) {
                refresh.revealArmed(armedIds);
            }
            showBulletin(fragment, failClosed, dialogGate, armed, notArmed, rescheduleWrong, rescheduleTotal);
        }
    }

    /**
     * The atomic, ownership-enforcing create-and-persist-with-ownership-check for one armed target. On an
     * existing owner of these ids it merges the shared trigger into that entry in place (never removing
     * it -- on the merge path the existing entry IS the survivor being armed, so a remove would delete
     * what was just armed); with no owner it claims a fresh entry; a contested id (multi-owner or an
     * empty/non-positive id set) rejects and the target is reported not armed. Ownership is resolved at
     * commit by exact identity across both id spaces over the full album child-id set, so no half-owned
     * entry is ever written and the caller still counts the outcome. Returns true only when the trigger is
     * now durably armed on the target.
     */
    private boolean armSurvivor(@NonNull EventScheduleEntry entry) {
        String resolvedKey = EventScheduleController.bulkArmSurvivor(account, dialogId, entry);
        if (resolvedKey == null) {
            return false;
        }
        // Release this run's hold on the resolved entry so it can fire per its config now that it is the
        // live shared trigger. On a merge the resolved key is the existing owner's -- an in-place merge
        // leaves createdAt, and thus key(), unchanged -- which is exactly the key suppressed at admission,
        // so this drops the right hold. A fresh claim was never suppressed, so it is a harmless no-op
        // there. Idempotent, and the finalize finally-block releases every held key again, so the double
        // release is safe.
        EventScheduleController.releaseSuppression(account, resolvedKey, this);
        return true;
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

    private long maxCreatedAt() {
        long max = 0;
        for (EventScheduleEntry e : EventScheduleStore.forDialog(account, dialogId)) {
            if (e.createdAt > max) max = e.createdAt;
        }
        return max;
    }

    private boolean anySendingAtAdmission(int[] albumIds) {
        for (int id : albumIds) {
            if (sendingAtAdmissionIds.contains(id)) return true;
        }
        return false;
    }

    private boolean editedMidRun(int[] albumIds) {
        for (int id : albumIds) {
            EventScheduleEntry e = EventScheduleStore.findByMessage(account, dialogId, id);
            if (e == null) continue;
            Long admissionRevision = suppressed.get(e.key());
            if (admissionRevision != null && e.revision != admissionRevision) return true;
        }
        return false;
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

    private void showBulletin(BaseFragment fragment, boolean failClosed, boolean dialogGate, int armed, int notArmed, int rescheduleWrong, int rescheduleTotal) {
        final String base = rescheduleWrong == 0
                ? LocaleController.formatPluralString("RescheduleApplied", rescheduleTotal)
                : LocaleController.formatString(R.string.RescheduleVerifyFailed, rescheduleWrong, rescheduleTotal);
        final String triggerLine;
        if (dialogGate) {
            // Transient, retryable, dialog-wide -- distinct from every other not-armed reason. The whole
            // selection was declined because the chat is still confirming an earlier trigger; nothing
            // armed, so the wording tells the user to try again shortly rather than implying a partial.
            triggerLine = LocaleController.getString(R.string.EventScheduleBulkTriggerDialogBusy);
        } else if (failClosed) {
            triggerLine = LocaleController.getString(R.string.EventScheduleBulkTriggerNotApplied);
        } else if (notArmed > 0) {
            triggerLine = LocaleController.formatString(R.string.EventScheduleBulkTriggerPartial, armed, armed + notArmed);
        } else {
            triggerLine = LocaleController.formatPluralString("EventScheduleBulkTriggerArmed", armed);
        }
        final String message = base + "\n" + triggerLine;
        final int icon = (rescheduleWrong == 0 && !failClosed && !dialogGate && notArmed == 0) ? R.raw.chats_infotip : R.raw.error;
        BulletinFactory.of(fragment).createSimpleBulletin(icon, message).show();
    }
}
