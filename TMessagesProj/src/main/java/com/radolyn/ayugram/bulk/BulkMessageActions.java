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

    // Best-effort re-entry guard: one run of each kind per dialog at a time.
    private static volatile long pinBusyDialogId;
    private static volatile long replyBusyDialogId;

    private BulkMessageActions() {
    }

    public static boolean runPin(BaseFragment fragment, int currentAccount, TLRPC.Chat chat, TLRPC.User user, long dialogId, ArrayList<Integer> mids, boolean oneSide, boolean notify) {
        if (pinBusyDialogId == dialogId || mids == null || mids.isEmpty()) {
            return false;
        }
        pinBusyDialogId = dialogId;
        pinNext(fragment, currentAccount, chat, user, dialogId, mids, 0, oneSide, notify);
        return true;
    }

    private static void pinNext(BaseFragment fragment, int currentAccount, TLRPC.Chat chat, TLRPC.User user, long dialogId, ArrayList<Integer> mids, int index, boolean oneSide, boolean notify) {
        if (fragment == null || fragment.getParentActivity() == null) {
            pinBusyDialogId = 0;
            return;
        }
        if (index >= mids.size()) {
            pinBusyDialogId = 0;
            BulletinFactory.of(fragment).createSimpleBulletin(R.raw.chats_infotip, LocaleController.formatPluralString("BulkPinned", mids.size())).show();
            return;
        }
        // ascending id order, so the newest selected message is pinned last and lands on top of the pin stack
        MessagesController.getInstance(currentAccount).pinMessage(chat, user, mids.get(index), false, oneSide, notify);
        AndroidUtilities.runOnUIThread(() -> pinNext(fragment, currentAccount, chat, user, dialogId, mids, index + 1, oneSide, notify), PIN_DELAY_MS);
    }

    public static boolean runNumberedReply(BaseFragment fragment, int currentAccount, long dialogId, MessageObject threadMessage, ArrayList<MessageObject> messages) {
        if (replyBusyDialogId == dialogId || messages == null || messages.isEmpty()) {
            return false;
        }
        replyBusyDialogId = dialogId;
        replyNext(fragment, currentAccount, dialogId, threadMessage, messages, 0, 0);
        return true;
    }

    private static void replyNext(BaseFragment fragment, int currentAccount, long dialogId, MessageObject threadMessage, ArrayList<MessageObject> messages, int index, int sent) {
        if (fragment == null || fragment.getParentActivity() == null) {
            replyBusyDialogId = 0;
            return;
        }
        if (index >= messages.size()) {
            replyBusyDialogId = 0;
            BulletinFactory.of(fragment).createSimpleBulletin(R.raw.chats_infotip, LocaleController.formatPluralString("NumberedRepliesSent", sent)).show();
            return;
        }
        final MessageObject target = messages.get(index);
        int nextSent = sent;
        if (target != null && target.getId() > 0) {
            // reply carries the running index (as keycap emoji, so it renders big and bright) as its
            // whole body: the peer taps the reply header to jump to that message, giving a table of
            // contents that works on both sides. Silent so a long index doesn't fire a notification per entry.
            nextSent++;
            SendMessagesHelper.SendMessageParams params = SendMessagesHelper.SendMessageParams.of(keycapNumber(nextSent), dialogId, target, threadMessage, null, false, null, null, null, false, 0, 0, null, false);
            SendMessagesHelper.getInstance(currentAccount).sendMessage(params);
        }
        final int sentSoFar = nextSent;
        AndroidUtilities.runOnUIThread(() -> replyNext(fragment, currentAccount, dialogId, threadMessage, messages, index + 1, sentSoFar), REPLY_DELAY_MS);
    }

    // e.g. 7 -> 7️⃣, 12 -> 1️⃣2️⃣: each digit becomes its keycap emoji so any count keeps the look, and a
    // leading LTR mark keeps multi-digit numbers reading left-to-right in RTL chats.
    private static String keycapNumber(int n) {
        String digits = Integer.toString(n);
        StringBuilder sb = new StringBuilder(1 + digits.length() * 3);
        sb.append('\u200E');
        for (int i = 0; i < digits.length(); i++) {
            sb.append(digits.charAt(i)).append('\uFE0F').append('\u20E3');
        }
        return sb.toString();
    }
}
