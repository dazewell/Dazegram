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
        final String busyKey = currentAccount + ":" + dialogId;
        if (!pinBusyKeys.add(busyKey)) {
            return false;
        }
        pinNext(fragment, currentAccount, chat, user, mids, 0, oneSide, notify, busyKey);
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

    public static boolean runNumberedReply(BaseFragment fragment, int currentAccount, long dialogId, MessageObject threadMessage, ArrayList<MessageObject> messages) {
        if (messages == null || messages.isEmpty()) {
            return false;
        }
        final String busyKey = currentAccount + ":" + dialogId;
        if (!replyBusyKeys.add(busyKey)) {
            return false;
        }
        replyNext(fragment, currentAccount, dialogId, threadMessage, messages, 0, 0, busyKey);
        return true;
    }

    private static void replyNext(BaseFragment fragment, int currentAccount, long dialogId, MessageObject threadMessage, ArrayList<MessageObject> messages, int index, int sent, String busyKey) {
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
            // reply carries the running index (one bright glyph) as its whole body: the peer taps the
            // reply header to jump to that message, giving a table of contents that works on both sides.
            // Silent so a long index doesn't fire a notification per entry.
            nextSent++;
            SendMessagesHelper.SendMessageParams params = SendMessagesHelper.SendMessageParams.of(glyphNumber(nextSent), dialogId, target, threadMessage, null, false, null, null, null, false, 0, 0, null, false);
            SendMessagesHelper.getInstance(currentAccount).sendMessage(params);
        }
        final int sentSoFar = nextSent;
        AndroidUtilities.runOnUIThread(() -> replyNext(fragment, currentAccount, dialogId, threadMessage, messages, index + 1, sentSoFar, busyKey), REPLY_DELAY_MS);
    }

    // one glyph per number: bright keycap emoji for 1-10 (7 -> 7️⃣, 10 -> 🔟), then single circled glyphs
    // for 11-30 (⑪ … ㉚), since no colourful single glyph exists past ten. Cap is 30, so the plain-number
    // fallback isn't normally reached.
    private static String glyphNumber(int n) {
        if (n >= 1 && n <= 9) {
            return "" + (char) ('0' + n) + '\uFE0F' + '\u20E3';
        }
        if (n == 10) {
            return "\uD83D\uDD1F";
        }
        if (n >= 11 && n <= 20) {
            return String.valueOf((char) (0x2460 + n - 1));
        }
        if (n >= 21 && n <= 30) {
            return String.valueOf((char) (0x3251 + n - 21));
        }
        return Integer.toString(n);
    }
}
