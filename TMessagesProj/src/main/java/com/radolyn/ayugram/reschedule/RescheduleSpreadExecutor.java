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

        public Target(int id, int scheduleDate, int repeatPeriod) {
            this.id = id;
            this.scheduleDate = scheduleDate;
            this.repeatPeriod = repeatPeriod;
        }
    }

    // Best-effort re-entry guard: one bulk run per dialog at a time.
    private static volatile long busyDialogId;

    private RescheduleSpreadExecutor() {
    }

    /**
     * Returns false when a previous run for this dialog is still in flight.
     */
    public static boolean run(int currentAccount, long dialogId, ArrayList<Target> targets, BaseFragment fragment) {
        if (busyDialogId == dialogId) {
            return false;
        }
        busyDialogId = dialogId;
        sendNext(currentAccount, dialogId, targets, 0, 1, new ArrayList<>(), fragment);
        return true;
    }

    private static void sendNext(int currentAccount, long dialogId, ArrayList<Target> targets, int index, int retriesLeft, ArrayList<Integer> failedIds, BaseFragment fragment) {
        if (index >= targets.size()) {
            verify(currentAccount, dialogId, targets, failedIds, fragment);
            return;
        }
        final Target target = targets.get(index);
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
            if (error == null) {
                MessagesController.getInstance(currentAccount).processUpdates((TLRPC.Updates) response, false);
            } else if (error.text != null && error.text.startsWith("FLOOD_WAIT_")) {
                int wait = Utilities.parseInt(error.text);
                if (retriesLeft > 0 && wait <= 60) {
                    AndroidUtilities.runOnUIThread(() -> sendNext(currentAccount, dialogId, targets, index, retriesLeft - 1, failedIds, fragment), (wait + 1) * 1000L);
                    return;
                }
                failedIds.add(target.id);
            } else if (error.text == null || !error.text.equals("MESSAGE_NOT_MODIFIED")) {
                // MESSAGE_NOT_MODIFIED means the date already matches: that's a success here.
                failedIds.add(target.id);
            }
            sendNext(currentAccount, dialogId, targets, index + 1, 1, failedIds, fragment);
        }, ConnectionsManager.RequestFlagFailOnServerErrors);
    }

    private static void verify(int currentAccount, long dialogId, ArrayList<Target> targets, ArrayList<Integer> failedIds, BaseFragment fragment) {
        final TLRPC.TL_messages_getScheduledHistory req = new TLRPC.TL_messages_getScheduledHistory();
        req.peer = MessagesController.getInstance(currentAccount).getInputPeer(dialogId);
        req.hash = 0;
        ConnectionsManager.getInstance(currentAccount).sendRequest(req, (response, error) -> {
            busyDialogId = 0;
            final int total = targets.size();
            int wrong;
            if (response instanceof TLRPC.messages_Messages && !(response instanceof TLRPC.TL_messages_messagesNotModified)) {
                // A scheduled message's date is its schedule time; a missing id means the
                // message already left the queue, which is just as off-plan as a stale date.
                final SparseIntArray serverDates = new SparseIntArray();
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
