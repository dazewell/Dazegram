package com.radolyn.ayugram.bulk;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.SendMessagesHelper;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.BulletinFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Runs the two multi-select bulk actions (pin all, numbered replies) as strictly sequential
 * chains: fire one op, post the next after a delay. Sequential (not a fire-and-forget loop) so
 * the messages keep their order and the burst stays under server flood limits. Same
 * fire-one-then-post-next shape as RescheduleSpreadExecutor.
 */
public final class BulkMessageActions {

    // Pin is a plain network op; 4/sec clears 30 pins in ~7.5s. Replies are real message traffic
    // under flood control, so they need more headroom between sends.
    private static final long PIN_DELAY_MS = 250;
    private static final long REPLY_DELAY_MS = 700;

    // Re-entry guard: one run of each kind per (account, dialog) at a time. Keyed on the account too,
    // because the same dialog id exists across accounts and a run in one must not block the other.
    private static final Set<String> pinBusyKeys = ConcurrentHashMap.newKeySet();
    private static final Set<String> replyBusyKeys = ConcurrentHashMap.newKeySet();

    private BulkMessageActions() {
    }

    public static boolean runPin(BaseFragment fragment, int currentAccount, TLRPC.Chat chat, TLRPC.User user, long dialogId, ArrayList<Integer> mids, boolean oneSide, boolean notify) {
        if (mids == null || mids.isEmpty()) {
            return false;
        }
        // normalise here instead of trusting the caller: drop invalid ids, pin ascending so the newest lands on top
        final ArrayList<Integer> clean = new ArrayList<>(mids.size());
        for (Integer id : mids) {
            if (id != null && id > 0) {
                clean.add(id);
            }
        }
        if (clean.isEmpty()) {
            return false;
        }
        Collections.sort(clean);
        final String busyKey = currentAccount + ":" + dialogId;
        if (!pinBusyKeys.add(busyKey)) {
            return false;
        }
        pinNext(fragment, currentAccount, chat, user, clean, 0, oneSide, notify, busyKey);
        return true;
    }

    private static void pinNext(BaseFragment fragment, int currentAccount, TLRPC.Chat chat, TLRPC.User user, ArrayList<Integer> mids, int index, boolean oneSide, boolean notify, String busyKey) {
        if (fragment == null || fragment.getParentActivity() == null) {
            pinBusyKeys.remove(busyKey);
            return;
        }
        if (index >= mids.size()) {
            pinBusyKeys.remove(busyKey);
            BulletinFactory.of(fragment).createSimpleBulletin(R.raw.chats_infotip, LocaleController.formatPluralString("BulkPinned", mids.size())).show();
            return;
        }
        // ascending id order, so the newest selected message is pinned last and lands on top of the pin stack
        MessagesController.getInstance(currentAccount).pinMessage(chat, user, mids.get(index), false, oneSide, notify);
        AndroidUtilities.runOnUIThread(() -> pinNext(fragment, currentAccount, chat, user, mids, index + 1, oneSide, notify, busyKey), PIN_DELAY_MS);
    }

    public static boolean runNumberedReply(BaseFragment fragment, int currentAccount, long dialogId, MessageObject threadMessage, ArrayList<MessageObject> messages, int startNumber) {
        if (messages == null || messages.isEmpty()) {
            return false;
        }
        // normalise here instead of trusting the caller: drop invalid targets and number in ascending id order
        final ArrayList<MessageObject> clean = new ArrayList<>(messages.size());
        for (MessageObject m : messages) {
            if (m != null && m.getId() > 0) {
                clean.add(m);
            }
        }
        if (clean.isEmpty()) {
            return false;
        }
        Collections.sort(clean, (a, b) -> Integer.compare(a.getId(), b.getId()));
        final String busyKey = currentAccount + ":" + dialogId;
        if (!replyBusyKeys.add(busyKey)) {
            return false;
        }
        replyNext(fragment, currentAccount, dialogId, threadMessage, clean, 0, startNumber, 0, busyKey);
        return true;
    }

    private static void replyNext(BaseFragment fragment, int currentAccount, long dialogId, MessageObject threadMessage, ArrayList<MessageObject> messages, int index, int startNumber, int sent, String busyKey) {
        if (fragment == null || fragment.getParentActivity() == null) {
            replyBusyKeys.remove(busyKey);
            return;
        }
        if (index >= messages.size()) {
            replyBusyKeys.remove(busyKey);
            BulletinFactory.of(fragment).createSimpleBulletin(R.raw.chats_infotip, LocaleController.formatPluralString("NumberedRepliesSent", sent)).show();
            return;
        }
        final MessageObject target = messages.get(index);
        int nextSent = sent;
        if (target != null && target.getId() > 0) {
            // reply carries the running number (counting up from startNumber) as its whole body: the peer
            // taps the reply header to jump to that message, giving a table of contents that works on both
            // sides. Silent so a long index doesn't fire a notification per entry.
            SendMessagesHelper.SendMessageParams params = SendMessagesHelper.SendMessageParams.of(String.valueOf(startNumber + sent), dialogId, target, threadMessage, null, false, null, null, null, false, 0, 0, null, false);
            SendMessagesHelper.getInstance(currentAccount).sendMessage(params);
            nextSent++;
        }
        final int sentSoFar = nextSent;
        AndroidUtilities.runOnUIThread(() -> replyNext(fragment, currentAccount, dialogId, threadMessage, messages, index + 1, startNumber, sentSoFar, busyKey), REPLY_DELAY_MS);
    }
}
