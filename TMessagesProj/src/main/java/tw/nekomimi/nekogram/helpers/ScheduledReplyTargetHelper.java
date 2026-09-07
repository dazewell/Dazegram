package tw.nekomimi.nekogram.helpers;

import android.util.Log;
import android.util.SparseArray;

import androidx.annotation.Nullable;

import com.radolyn.ayugram.eventschedule.EventScheduleStore;

import org.telegram.messenger.BaseController;
import org.telegram.messenger.BuildConfig;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.Components.Bulletin;
import org.telegram.ui.Components.BulletinFactory;

import java.lang.ref.WeakReference;
import java.util.ArrayList;

import xyz.nextalone.nagram.NaConfig;

/**
 * #scheduled-reply-target. Telegram's editMessage RPC has no reply_to field at all -- confirmed by
 * grepping every reply_to occurrence in TLRPC.java, see docs/codemap/upstream-traps.md -- so
 * changing which message a scheduled message replies to is a client-side cancel+resend requeue:
 * resend a copy of the original carrying the new reply target and the same text/media/schedule,
 * wait for that resend to actually be confirmed landed at the server, then cancel the original.
 * Never the other order: MessagesController's scheduled-delete branch
 * (MessagesController.java:9586-9618) removes the local copy and fires a fire-and-forget RPC with
 * no error path back to its caller, which is exactly why the cancel must always be the last,
 * structurally-unobservable step -- its failure degrades to a harmless visible duplicate, never a
 * hole where the user's queued send silently vanished.
 *
 * <p>All state here is transient and per-account: nothing is persisted, nothing resumes across a
 * process death or account switch. Both the resend and the cancel are looked up by
 * (account, dialogId, messageId), re-resolved at the moment they're used -- never a MessageObject
 * held across the picker fragment's lifetime.
 */
public final class ScheduledReplyTargetHelper extends BaseController {

    public static final int KIND_ADD = 0;
    public static final int KIND_CHANGE = 1;
    public static final int KIND_REMOVE = 2;

    private static final ScheduledReplyTargetHelper[] Instance = new ScheduledReplyTargetHelper[UserConfig.MAX_ACCOUNT_COUNT];

    public static ScheduledReplyTargetHelper getInstance(int num) {
        ScheduledReplyTargetHelper localInstance = Instance[num];
        if (localInstance == null) {
            synchronized (ScheduledReplyTargetHelper.class) {
                localInstance = Instance[num];
                if (localInstance == null) {
                    Instance[num] = localInstance = new ScheduledReplyTargetHelper(num);
                }
            }
        }
        return localInstance;
    }

    public ScheduledReplyTargetHelper(int num) {
        super(num);
    }

    /** One entry per in-flight resend, keyed by the local id the resend copy was assigned. */
    private final SparseArray<Pending> pendingByLocalId = new SparseArray<>();
    private boolean observing;

    private static final class Pending {
        final long dialogId;
        final int originalMessageId;
        final int kind;
        final WeakReference<ChatActivity> fragmentRef;

        Pending(long dialogId, int originalMessageId, int kind, ChatActivity fragment) {
            this.dialogId = dialogId;
            this.originalMessageId = originalMessageId;
            this.kind = kind;
            this.fragmentRef = new WeakReference<>(fragment);
        }
    }

    /** Transient identity of one picker session; lives only as long as the pushed picker fragment. */
    public static final class PickState {
        public final long dialogId;
        public final int originalMessageId;
        Bulletin activeBulletin;

        public PickState(long dialogId, int originalMessageId) {
            this.dialogId = dialogId;
            this.originalMessageId = originalMessageId;
        }
    }

    /**
     * Single source of truth for whether the three new menu rows may appear at all -- used both at
     * menu-build time (to hide, never show-then-refuse) and, for the file-availability half, again
     * at confirm time since local cache eviction is the one part of this that's genuinely
     * time-variant within one gesture.
     */
    public static boolean isEligible(int account, MessageObject message, MessageObject.GroupedMessages group) {
        if (!NaConfig.INSTANCE.getShowScheduledReplyTarget().Bool()) {
            return false;
        }
        if (message == null || message.messageOwner == null || group != null) {
            return false;
        }
        if (message.messageOwner.schedule_repeat_period != 0) {
            return false;
        }
        if (isImminentOrPast(account, message.messageOwner)) {
            return false;
        }
        if (EventScheduleStore.findByMessage(account, message.getDialogId(), message.getId()) != null) {
            return false;
        }
        return MessageHelper.getInstance(account).canSendMessageAsCopy(message, null);
    }

    // Mirrors just the staleness half of MessageObject.canEditMessage's scheduled gate
    // (MessageObject.java:11786), without that method's other edit-only restrictions (forwarded /
    // round-video / sticker exclusions don't belong here) -- this is the same "about to fire"
    // window the sibling Edit/Reschedule rows already refuse on.
    private static boolean isImminentOrPast(int account, TLRPC.Message message) {
        return message.date < ConnectionsManager.getInstance(account).getCurrentTime() - 60;
    }

    public static void showConfirmAffordance(ChatActivity fragment, PickState pick, Runnable onConfirm) {
        hideConfirmAffordance(pick);
        if (!BulletinFactory.canShowBulletin(fragment)) {
            return;
        }
        Bulletin bulletin = BulletinFactory.of(fragment).createSimpleBulletin(
                R.raw.chats_infotip,
                LocaleController.getString(R.string.ScheduledReplyTargetConfirmHint),
                LocaleController.getString(R.string.ScheduledReplyTargetConfirmButton),
                onConfirm);
        bulletin.show();
        pick.activeBulletin = bulletin;
    }

    public static void hideConfirmAffordance(PickState pick) {
        if (pick.activeBulletin != null) {
            pick.activeBulletin.hide();
            pick.activeBulletin = null;
        }
    }

    /**
     * Runs the requeue: re-resolves and re-checks the original, preflights local-file
     * availability, dispatches the resend, correlates the new local id, then registers it so the
     * account-scoped observer can cancel the original once (and only once) the resend is confirmed
     * landed at the server. replyTarget is null for a Remove; non-null for Add/Change, already
     * re-resolved by the caller from its own live window (finding 10 -- never a stale reference).
     */
    public static void requeue(ChatActivity scheduledFragment, int account, long dialogId, int originalMessageId, @Nullable MessageObject replyTarget, int kind) {
        // NAX_SMOKE_scheduled-reply-target BEGIN: unconditionally reached the moment the user
        // confirms Reply/Change reply/Remove reply -- to be removed once the smoke build confirms
        // reachability.
        Log.i("NagramX", "NAX_SMOKE_scheduled-reply-target BEGIN build=" + BuildConfig.BUILD_VERSION_STRING
                + " app=" + BuildConfig.APPLICATION_ID + " account=" + account + " kind=" + kind);
        MessageObject original = scheduledFragment.messagesDict[0].get(originalMessageId);
        if (original == null || original.messageOwner == null || isImminentOrPast(account, original.messageOwner)) {
            showError(scheduledFragment, R.string.ScheduledReplyTargetAlreadyGone);
            return;
        }
        MessageHelper helper = MessageHelper.getInstance(account);
        if (!helper.canResendLocally(original)) {
            showError(scheduledFragment, R.string.ScheduledReplyTargetUnsupported);
            return;
        }
        SparseArray<MessageObject> dict = scheduledFragment.messagesDict[0];
        ArrayList<Integer> beforeIds = new ArrayList<>(dict.size());
        for (int i = 0; i < dict.size(); i++) {
            beforeIds.add(dict.keyAt(i));
        }
        boolean dispatched = helper.sendMessageAsCopy(original, null, dialogId, replyTarget, null, null,
                false, !original.messageOwner.silent, original.messageOwner.date, ChatActivity.MODE_SCHEDULED,
                null, 0, 0, 0, null);
        if (!dispatched) {
            // Synchronous failure (MessageHelper.java:1374, e.g. media evicted between preflight and
            // this call) -- P2/P5: nothing has been sent or deleted yet, so this aborts clean.
            showError(scheduledFragment, R.string.ScheduledReplyTargetUnsupported);
            return;
        }
        Integer capturedLocalId = null;
        for (int i = 0; i < dict.size(); i++) {
            int key = dict.keyAt(i);
            if (!beforeIds.contains(key)) {
                capturedLocalId = key;
                break;
            }
        }
        if (capturedLocalId == null) {
            // Dispatched but we couldn't correlate a new local id to watch for confirmation. P1/P2
            // require the resend be confirmed before any cancel, and we can't confirm what we can't
            // identify -- fail safe: the original stays scheduled and untouched (P1), at the cost of
            // a duplicate the user has to notice and delete by hand (the accepted P3 degrade).
            showError(scheduledFragment, R.string.ScheduledReplyTargetDuplicateRisk);
            return;
        }
        ScheduledReplyTargetHelper instance = getInstance(account);
        instance.pendingByLocalId.put(capturedLocalId, new Pending(dialogId, original.getId(), kind, scheduledFragment));
        instance.ensureObserver();
    }

    private static void showError(ChatActivity fragment, int stringRes) {
        if (BulletinFactory.canShowBulletin(fragment)) {
            BulletinFactory.of(fragment).createErrorBulletin(LocaleController.getString(stringRes)).show();
        }
    }

    private void ensureObserver() {
        if (observing) {
            return;
        }
        observing = true;
        NotificationCenter nc = getNotificationCenter();
        nc.addObserver(this::onMessageReceivedByServer, NotificationCenter.messageReceivedByServer);
        nc.addObserver(this::onMessageSendError, NotificationCenter.messageSendError);
    }

    private void onMessageReceivedByServer(int id, int account, Object... args) {
        int oldId = (int) args[0];
        Pending pending = pendingByLocalId.get(oldId);
        if (pending == null) {
            return;
        }
        pendingByLocalId.remove(oldId);
        // NAX_SMOKE_scheduled-reply-target EXPECTED: resend confirmed landed at the server, about
        // to dispatch the cancel -- this is the required ordering (resend before cancel).
        Log.i("NagramX", "NAX_SMOKE_scheduled-reply-target EXPECTED resend-confirmed account=" + currentAccount + " originalId=" + pending.originalMessageId);
        // P1/P2: only cancel the original once the resend is actually confirmed landed at the
        // server -- never before. This RPC is fire-and-forget on failure
        // (MessagesController.java:9586-9618, no error path to us), which is exactly why it's last.
        ArrayList<Integer> toDelete = new ArrayList<>();
        toDelete.add(pending.originalMessageId);
        MessagesController.getInstance(currentAccount).deleteMessages(toDelete, null, null, pending.dialogId, 0, false, ChatActivity.MODE_SCHEDULED);
        // NAX_SMOKE_scheduled-reply-target END: full resend-then-cancel sequence completed.
        Log.i("NagramX", "NAX_SMOKE_scheduled-reply-target END cancel-dispatched account=" + currentAccount + " originalId=" + pending.originalMessageId);
        ChatActivity fragment = pending.fragmentRef.get();
        if (fragment != null && BulletinFactory.canShowBulletin(fragment)) {
            int textRes;
            switch (pending.kind) {
                case KIND_CHANGE:
                    textRes = R.string.ScheduledReplyTargetChanged;
                    break;
                case KIND_REMOVE:
                    textRes = R.string.ScheduledReplyTargetRemoved;
                    break;
                default:
                    textRes = R.string.ScheduledReplyTargetAdded;
                    break;
            }
            BulletinFactory.of(fragment).createSimpleBulletin(R.raw.contact_check, LocaleController.getString(textRes)).show();
        }
    }

    private void onMessageSendError(int id, int account, Object... args) {
        int localId = (int) args[0];
        Pending pending = pendingByLocalId.get(localId);
        if (pending == null) {
            return;
        }
        pendingByLocalId.remove(localId);
        // NAX_SMOKE_scheduled-reply-target FORBIDDEN: resend failed -- this path must never be
        // followed by a cancel dispatch (the competing outcome to the EXPECTED path above).
        Log.w("NagramX", "NAX_SMOKE_scheduled-reply-target FORBIDDEN resend-failed account=" + currentAccount + " originalId=" + pending.originalMessageId);
        // P2: resend failed (observed asynchronously) -- abort before any cancel. The original is
        // untouched; only the failed resend attempt itself is gone.
        ChatActivity fragment = pending.fragmentRef.get();
        if (fragment != null && BulletinFactory.canShowBulletin(fragment)) {
            BulletinFactory.of(fragment).createErrorBulletin(LocaleController.getString(R.string.ScheduledReplyTargetSendFailed)).show();
        }
    }
}
