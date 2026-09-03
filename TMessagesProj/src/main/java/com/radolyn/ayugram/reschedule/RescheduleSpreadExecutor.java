package com.radolyn.ayugram.reschedule;

import android.util.SparseIntArray;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.BulletinFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * Applies a bulk reschedule as a strictly sequential chain of messages.editMessage calls.
 * A fire-and-forget loop has a blind spot: with default request flags tgnet swallows
 * FLOOD_WAIT and silently retries minutes later, so the edit can land after the message
 * was already due (it then goes out late and off-order while the view looks correct).
 * Each request here runs with RequestFlagFailOnServerErrors so flood waits surface,
 * short ones are retried in place, and a closing messages.getScheduledHistory pass
 * checks the server really holds every new time before reporting the result.
 */
public final class RescheduleSpreadExecutor {

    public static final class Target {
        final int id;
        final int scheduleDate;
        final int repeatPeriod;
        // Full album child-id set captured when the target is built, while the fragment's group maps are
        // still valid. A later arm/verify pass must never re-expand the group from a live fragment: it can
        // mutate or become inaccessible after the fragment is destroyed, which would silently drop album
        // children from both the arm and the verification. For a non-grouped message this is just { id }.
        final int[] albumIds;

        public Target(int id, int scheduleDate, int repeatPeriod, int[] albumIds) {
            this.id = id;
            this.scheduleDate = scheduleDate;
            this.repeatPeriod = repeatPeriod;
            this.albumIds = albumIds;
        }
    }

    /**
     * One target's result, handed to the trigger finalization to reconcile against the server's
     * authoritative scheduled set. Neutral by design -- no eventschedule types cross this package
     * boundary. {@code applied} is false when the edit didn't demonstrably land, so the target is
     * not a candidate for arming. {@code repeatPeriod} is the message's native schedule-repeat period,
     * carried through so the finalization can refuse to arm a repeating message (the two features don't
     * compose, same rule the single-message path enforces).
     */
    public static final class TargetOutcome {
        public final int[] albumIds;
        public final int scheduleDate;
        public final int repeatPeriod;
        public final boolean applied;

        TargetOutcome(int[] albumIds, int scheduleDate, int repeatPeriod, boolean applied) {
            this.albumIds = albumIds;
            this.scheduleDate = scheduleDate;
            this.repeatPeriod = repeatPeriod;
            this.applied = applied;
        }
    }

    /**
     * Neutral seam for arming one shared event-schedule trigger across a bulk reschedule. Kept
     * primitive-only so the reschedule package never imports the eventschedule package: the executor
     * owns the request sequencing and hands over immutable outcome data; the implementation owns all
     * trigger state. Deferred atomic activation -- nothing is armed while the run is in flight; at
     * finalization the implementation reconciles first (a step that may yield to a storage hop) and
     * publishes the whole selection in one UI-thread turn only after that reconcile completes, so the
     * safety argument spans the hop rather than resting on a single synchronous turn.
     */
    public interface TriggerArmingHooks {
        /**
         * Once, on the UI thread, before the first edit request. The implementation holds back any
         * triggers the selected messages already carry, so a live trigger can't fire mid-run and then
         * read back as missing during verification. This alters runtime suppression ONLY and must never
         * durably remove an entry: suppression is process-local, so a process death before finalization
         * simply discards it and the durable trigger reloads armed. (An earlier design durably detached
         * the entry and kept restore snapshots to put it back; that lost the trigger outright on a
         * process death mid-run and was replaced by non-durable suppression -- do not reintroduce a
         * durable-removal step here.)
         */
        void onAdmission();

        /**
         * Once, after the closing scheduled-history read, on the request thread. {@code scheduledIds}
         * / {@code scheduledDates} are the server's current scheduled set (parallel arrays), empty
         * when {@code authoritative} is false. {@code generation} is this run's handle to its
         * (account, dialogId) generation: the implementation re-checks {@link RunGeneration#superseded()}
         * immediately before it commits the arm on the UI thread -- after the reconcile hop that can
         * yield, not frozen here where the window is still open -- and fails closed if a later run
         * overtook it (C3), then calls {@link RunGeneration#release()} once in its unconditional finally.
         * {@code rescheduleWrong}/{@code rescheduleTotal} carry the plain reschedule result so the
         * implementation can fold it into one bulletin. The implementation does its single
         * reconcile-create-refresh-notify pass on the UI thread.
         */
        void onFinalize(List<TargetOutcome> outcomes, int[] scheduledIds, int[] scheduledDates,
                        boolean authoritative, RunGeneration generation, int rescheduleWrong, int rescheduleTotal,
                        BaseFragment fragment);
    }

    /**
     * Neutral handle to one run's (account, dialogId) generation, handed to the trigger finalizer so
     * the overtaken-run comparison happens immediately before the arm commits rather than frozen early
     * in the executor. Primitive-only, so no eventschedule type crosses the package boundary.
     */
    public interface RunGeneration {
        /** True when a later run for this (account, dialogId) advanced the generation past this run. */
        boolean superseded();

        /** Release this run's generation. Call once, in finalization's unconditional finally. */
        void release();
    }

    // Best-effort re-entry guard: one bulk run per dialog at a time. Deliberately NOT a safety
    // property for trigger activation -- see armRunSerial for that. This is the ONLY thing that can
    // refuse a plain reschedule, and the trigger feature leaves it exactly as it was.
    private static volatile long busyDialogId;

    // (account, dialogId) run generation. busyDialogId is cleared the instant the scheduled-history
    // response arrives, before the UI finalization runs, so a second run can be admitted in that
    // window and move the server dates this run is about to arm against. EVERY reschedule run advances
    // this generation at admission (see run()); only a trigger-enabled finalizer ever reads it, and it
    // reads it right before it commits the arm, failing closed if it was overtaken. A plain run only
    // advances it -- it never reads or branches on it -- so plain reschedule behaviour is unchanged.
    // The reader is EventScheduleBulkArmer, via the RunGeneration handle. Do not delete this as
    // "write-only": from inside this file the write looks unread, but the read lives in that finalizer
    // and is load-bearing -- removing it makes an overtaken trigger run arm from a stale snapshot again.
    private static final HashMap<String, Long> armRunSerial = new HashMap<>();
    private static long armRunCounter;

    private RescheduleSpreadExecutor() {
    }

    /**
     * Returns false when a previous run for this dialog is still in flight.
     */
    public static boolean run(int currentAccount, long dialogId, ArrayList<Target> targets, BaseFragment fragment) {
        return run(currentAccount, dialogId, targets, fragment, null);
    }

    /**
     * Returns false when a previous run for this dialog is still in flight. When {@code hooks} is
     * non-null a shared trigger is armed over the whole selection at finalization (see
     * {@link TriggerArmingHooks}); when null this is the plain reschedule and behaves exactly as
     * the four-argument overload.
     */
    public static boolean run(int currentAccount, long dialogId, ArrayList<Target> targets, BaseFragment fragment, TriggerArmingHooks hooks) {
        if (busyDialogId == dialogId) {
            return false;
        }
        busyDialogId = dialogId;
        // NagramX: advance the (account, dialogId) generation on EVERY run, placed AFTER the
        // busyDialogId admission check above so it can never gate or refuse a plain run -- here it is
        // deliberately write-only. Its sole purpose is that a concurrent trigger-enabled finalizer
        // (EventScheduleBulkArmer) can detect this run overtook it and fail closed; a plain run never
        // reads it, so plain reschedule behaviour is unchanged. Do not delete this because it looks
        // unread in this file -- the read is in that finalizer, one file away, and is load-bearing.
        final long serial = claimArmSerial(currentAccount, dialogId);
        if (hooks != null) {
            hooks.onAdmission();
        }
        sendNext(currentAccount, dialogId, targets, 0, 1, new ArrayList<>(), hooks == null ? null : new ArrayList<>(), hooks, serial, fragment);
        return true;
    }

    private static String armKey(int account, long dialogId) {
        return account + "_" + dialogId;
    }

    private static synchronized long claimArmSerial(int account, long dialogId) {
        long serial = ++armRunCounter;
        armRunSerial.put(armKey(account, dialogId), serial);
        return serial;
    }

    private static synchronized boolean armRunSuperseded(int account, long dialogId, long serial) {
        Long current = armRunSerial.get(armKey(account, dialogId));
        return current == null || current != serial;
    }

    private static synchronized void clearArmSerial(int account, long dialogId, long serial) {
        String key = armKey(account, dialogId);
        Long current = armRunSerial.get(key);
        if (current != null && current == serial) {
            armRunSerial.remove(key);
        }
    }

    /**
     * True when every id in {@code albumIds} is server-assigned (positive). Null is treated as
     * invalid -- a target with no captured album identity can't be verified safe to send. Deliberately
     * a local copy of the same shape used elsewhere (album readiness, single-message editability) rather
     * than a call across it: this package must stay primitive-only, see {@link TriggerArmingHooks}.
     */
    private static boolean allPositive(int[] albumIds) {
        if (albumIds == null) {
            return false;
        }
        for (int id : albumIds) {
            if (id <= 0) {
                return false;
            }
        }
        return true;
    }

    private static void sendNext(int currentAccount, long dialogId, ArrayList<Target> targets, int index, int retriesLeft,
                                 ArrayList<Integer> failedIds, ArrayList<TargetOutcome> outcomes,
                                 TriggerArmingHooks hooks, long serial, BaseFragment fragment) {
        if (index >= targets.size()) {
            verify(currentAccount, dialogId, targets, failedIds, outcomes, hooks, serial, fragment);
            return;
        }
        final Target target = targets.get(index);
        // NagramX: fail this target closed rather than send a partial/invalid edit. Albums are
        // all-or-nothing here because a rejected child can't carry the new schedule time on its own --
        // a half-moved group is worse than a group that didn't move at all. Checks target.id explicitly
        // rather than assuming albumIds always contains it, and treats a null albumIds as invalid too.
        if (target.id <= 0 || !allPositive(target.albumIds)) {
            failedIds.add(target.id);
            if (outcomes != null) {
                outcomes.add(new TargetOutcome(target.albumIds, target.scheduleDate, target.repeatPeriod, false));
            }
            sendNext(currentAccount, dialogId, targets, index + 1, 1, failedIds, outcomes, hooks, serial, fragment);
            return;
        }
        final TLRPC.TL_messages_editMessage req = new TLRPC.TL_messages_editMessage();
        req.peer = MessagesController.getInstance(currentAccount).getInputPeer(dialogId);
        req.id = target.id;
        req.schedule_date = target.scheduleDate;
        req.flags |= 32768;
        if (target.repeatPeriod != 0) {
            req.schedule_repeat_period = target.repeatPeriod;
            req.flags |= TLObject.FLAG_18;
        }
        ConnectionsManager.getInstance(currentAccount).sendRequest(req, (response, error) -> {
            boolean applied = true;
            if (error == null) {
                if (response instanceof TLRPC.Updates) {
                    MessagesController.getInstance(currentAccount).processUpdates((TLRPC.Updates) response, false);
                } else {
                    // An unexpected type means the edit didn't demonstrably apply. Record it: if the
                    // verify pass can't read the schedule back it falls back to counting failedIds,
                    // and an unrecorded failure would report the whole run as applied.
                    failedIds.add(target.id);
                    applied = false;
                }
            } else if (error.text != null && error.text.startsWith("FLOOD_WAIT_")) {
                int wait = Utilities.parseInt(error.text);
                if (retriesLeft > 0 && wait <= 60) {
                    AndroidUtilities.runOnUIThread(() -> sendNext(currentAccount, dialogId, targets, index, retriesLeft - 1, failedIds, outcomes, hooks, serial, fragment), (wait + 1) * 1000L);
                    return;
                }
                failedIds.add(target.id);
                applied = false;
            } else if (error.text == null || !error.text.equals("MESSAGE_NOT_MODIFIED")) {
                // MESSAGE_NOT_MODIFIED means the date already matches: that's a success here.
                failedIds.add(target.id);
                applied = false;
            }
            if (outcomes != null) {
                outcomes.add(new TargetOutcome(target.albumIds, target.scheduleDate, target.repeatPeriod, applied));
            }
            sendNext(currentAccount, dialogId, targets, index + 1, 1, failedIds, outcomes, hooks, serial, fragment);
        }, ConnectionsManager.RequestFlagFailOnServerErrors);
    }

    private static void verify(int currentAccount, long dialogId, ArrayList<Target> targets, ArrayList<Integer> failedIds,
                               ArrayList<TargetOutcome> outcomes, TriggerArmingHooks hooks, long serial, BaseFragment fragment) {
        final TLRPC.TL_messages_getScheduledHistory req = new TLRPC.TL_messages_getScheduledHistory();
        req.peer = MessagesController.getInstance(currentAccount).getInputPeer(dialogId);
        req.hash = 0;
        ConnectionsManager.getInstance(currentAccount).sendRequest(req, (response, error) -> {
            busyDialogId = 0;
            final int total = targets.size();
            int wrong;
            final SparseIntArray serverDates = new SparseIntArray();
            final boolean authoritative = response instanceof TLRPC.messages_Messages && !(response instanceof TLRPC.TL_messages_messagesNotModified);
            if (authoritative) {
                // A scheduled message's date is its schedule time; a missing id means the
                // message already left the queue, which is just as off-plan as a stale date.
                for (TLRPC.Message message : ((TLRPC.messages_Messages) response).messages) {
                    serverDates.put(message.id, message.date);
                }
                wrong = 0;
                for (int i = 0; i < targets.size(); i++) {
                    Target t = targets.get(i);
                    if (serverDates.get(t.id, -1) != t.scheduleDate) {
                        wrong++;
                    }
                }
            } else {
                wrong = failedIds.size();
            }
            final int wrongFinal = wrong;
            if (hooks != null) {
                // Parse the authoritative set into flat immutable arrays off the UI thread; the
                // finalization implementation reconciles and publishes on the UI thread and owns the
                // single bulletin (folding in wrongFinal/total), so the executor posts none of its own.
                final int[] ids = new int[serverDates.size()];
                final int[] dates = new int[serverDates.size()];
                for (int i = 0; i < serverDates.size(); i++) {
                    ids[i] = serverDates.keyAt(i);
                    dates[i] = serverDates.valueAt(i);
                }
                // NagramX: hand the generation to the finalizer rather than comparing it here. The
                // supersession check must happen immediately before the arm commits, AFTER the reconcile
                // hop that can yield -- comparing it here froze the window open. The finalizer releases
                // it in its unconditional finally.
                final long capturedSerial = serial;
                final RunGeneration generation = new RunGeneration() {
                    @Override
                    public boolean superseded() {
                        return armRunSuperseded(currentAccount, dialogId, capturedSerial);
                    }

                    @Override
                    public void release() {
                        clearArmSerial(currentAccount, dialogId, capturedSerial);
                    }
                };
                hooks.onFinalize(outcomes, ids, dates, authoritative, generation, wrongFinal, total, fragment);
                return;
            }
            // NagramX: plain reschedule advanced the generation at admission purely so a concurrent
            // trigger run can detect it moved the dates; nothing on this path reads it, so release it now.
            clearArmSerial(currentAccount, dialogId, serial);
            AndroidUtilities.runOnUIThread(() -> {
                if (fragment == null || fragment.getParentActivity() == null) {
                    return;
                }
                if (wrongFinal == 0) {
                    BulletinFactory.of(fragment).createSimpleBulletin(R.raw.chats_infotip, LocaleController.formatPluralString("RescheduleApplied", total)).show();
                } else {
                    BulletinFactory.of(fragment).createSimpleBulletin(R.raw.error, LocaleController.formatString(R.string.RescheduleVerifyFailed, wrongFinal, total)).show();
                }
            });
        });
    }
}
