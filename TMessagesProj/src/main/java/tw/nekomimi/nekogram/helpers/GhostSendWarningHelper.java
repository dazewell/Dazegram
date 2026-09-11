package tw.nekomimi.nekogram.helpers;

import static org.telegram.messenger.LocaleController.getString;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.utils.tlutils.TlUtils;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_ephemeral;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.Components.Bulletin;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.LaunchActivity;

import tw.nekomimi.nekogram.NekoConfig;

/**
 * Warns the user that a message they just sent while Ghost Mode was on still
 * went out over the network and exposed their online status. Ghost Mode never
 * held sends back -- this only informs.
 * <p>
 * It covers what {@link GhostTypingReminderHelper} cannot, and defers to it
 * where it can: in a chat already reminded during the current Ghost session,
 * this stays quiet, because the user was told in that chat and repeating it
 * adds nothing. The suppression is by destination chat, so where the chat can
 * be identified it applies to every send into it for the rest of that Ghost
 * session -- forwards and gallery media included, not only the typed message
 * that earned the reminder. Where it cannot, this fails open and warns: a send
 * whose destination doesn't resolve cannot be claimed to be one the user was
 * already told about. Allowlisted requests that carry no destination at all,
 * such as TL_messages_sendEncryptedMultiMedia and TL_messages_sendWebViewData,
 * therefore always warn, reminded chat or not.
 * <p>
 * In every chat the reminder has not covered, this still warns for everything
 * on the allowlist: forwards, gallery media, text shared in from another app,
 * stickers and GIFs, voice, bot keyboard buttons, story and
 * popup-notification replies, and the automatic retry of an unsent message.
 * That is where the real gap would otherwise be -- the composer reminder never
 * sees any of them, because only one of the five ChatActivityEnterView
 * instances passes a fragment.
 * <p>
 * The suppression is deliberately keyed on the destination chat rather than on
 * the request type. Classifying "the user typed this" from the outgoing TL
 * class was tried on paper first and fails in both directions at once:
 * TL_ephemeral.TL_sendMessage is not a text request but the merged wrapper for
 * both messages.sendMessage and messages.sendMedia whenever an ephemeral
 * receiver is set (EphemeralMessagesHelper#beforeSendingFinalRequest copies
 * request.media straight into it), so treating it as typed text would silence
 * ephemeral photo and poll sends; and a typed message carrying a resolved link
 * preview leaves as TL_messages_sendMedia with TL_inputMediaWebPage, so
 * treating that class as untyped would keep double-warning the commonest
 * message there is. The chat is the thing actually being asked about, so the
 * chat is what gets asked.
 * <p>
 * Call {@link #onMessageRequestReady(int, TLObject)} at the last point before a
 * request is actually handed to tgnet (ConnectionsManager#sendRequestInternal,
 * immediately before native_sendRequest), so a message a later hold/queue
 * feature diverts stays diverted -- that feature early-returns at
 * SendMessagesHelper#sendMessage(SendMessageParams), well upstream of request
 * construction, so a held message never reaches here at all.
 */
public class GhostSendWarningHelper {

    private GhostSendWarningHelper() {
    }

    // NagramX: deliberately an explicit allowlist of message-producing request
    // classes, rather than a naming-based test -- the previous hook point was a
    // shared dispatcher method that TL_messages_editMessage and
    // TL_messages_addPollAnswer also passed through despite neither one sending a
    // new message (the defect that hook point had); classifying by request type
    // here is what actually excludes them, not their names. Also excludes
    // reactions, typing/read requests, screenshot notifications, uploads, and
    // encrypted service actions.
    // Known gap, accepted for a warning-only feature: a future send RPC upstream
    // adds is missed here until this list is updated -- the actual protection
    // (holding sends back) is the local-hold feature's job, not this one's.
    // TL_messages_sendBotRequestedPeer (in-chat request-peer submission),
    // TL_messages_sendWebViewData (bot WebView data submission), and
    // TL_messages_startBot (ChatActivity -> MessagesController#sendBotStart) are
    // all known, named, real user-initiated sends today, not a future-RPC gap,
    // so they're included deliberately rather than left to that gap.
    private static boolean isMessageSendRequest(TLObject request) {
        return request instanceof TLRPC.TL_messages_sendMessage
                || request instanceof TLRPC.TL_messages_sendMedia
                || request instanceof TLRPC.TL_messages_sendMultiMedia
                || request instanceof TLRPC.TL_messages_forwardMessages
                || request instanceof TLRPC.TL_messages_sendInlineBotResult
                || request instanceof TLRPC.TL_messages_sendScheduledMessages
                || request instanceof TLRPC.TL_messages_sendQuickReplyMessages
                || request instanceof TLRPC.TL_messages_sendBotRequestedPeer
                || request instanceof TLRPC.TL_messages_sendWebViewData
                || request instanceof TLRPC.TL_messages_startBot
                || request instanceof TL_ephemeral.TL_sendMessage
                || request instanceof TLRPC.TL_messages_sendEncrypted
                || request instanceof TLRPC.TL_messages_sendEncryptedFile
                || request instanceof TLRPC.TL_messages_sendEncryptedMultiMedia;
    }

    /**
     * account is the ConnectionsManager instance actually dispatching this
     * request, not UserConfig.selectedAccount -- correct for whichever of the
     * app's accounts is sending, regardless of which one is foregrounded.
     * <p>
     * NagramX: called inline from ConnectionsManager#sendRequestInternal,
     * immediately before the request is actually dispatched to tgnet. The actual
     * structural guarantee that a bug here can never affect a send is established
     * at that call site (a try/catch(Throwable) around the call itself, with
     * native_sendRequest left outside it so it always runs), not by this method's
     * own guard below -- class loading and linkage of this class happen before
     * control ever reaches a method-level catch inside it, so a callee-side guard
     * alone cannot make that promise by construction. This guard stays anyway as
     * a second, redundant layer: it lets the rest of this class assume nothing
     * synchronous escapes past this point, without weakening the caller-side
     * guarantee that actually protects the send.
     */
    public static void onMessageRequestReady(int account, TLObject request) {
        try {
            onMessageRequestReadyUnsafe(account, request);
        } catch (Throwable t) {
            FileLog.e("GhostSendWarningHelper: swallowed unexpected failure, bulletin skipped for this send", t);
        }
    }

    // NagramX: 0 is never a real dialog id, so it doubles as "couldn't work out
    // which chat this is headed for". Resolution failing must mean the warning is
    // shown, never suppressed: suppression is only ever justified by a reminder
    // the user got for that exact chat, and an unidentified chat cannot support
    // that claim. Fail open, always.
    private static final long DIALOG_ID_UNRESOLVED = 0;

    // NagramX: runs synchronously on whichever thread called sendRequest --
    // usually Utilities.stageQueue, but sendRequestSync dispatches inline on the
    // caller's own thread. Nothing here depends on which: it reads plain fields
    // off an object that same thread is about to serialize, so there is nothing
    // to race either way.
    // TlUtils.getInputPeerFromSendMessageRequest handles the cloud sends it knows
    // and returns null for everything else, including four allowlisted requests
    // that do carry a destination peer -- scheduled sends, quick replies, bot
    // requested-peer replies and bot starts -- so those are read directly here.
    // Secret-chat sends aren't in it at all
    // and carry a TL_inputEncryptedChat rather than an InputPeer, so they're
    // mapped here onto the same encrypted dialog id ChatActivity uses -- that is
    // the id the typing reminder would have recorded for that chat, and matching
    // it is the whole point. TL_messages_sendEncryptedMultiMedia carries no peer
    // at all and so resolves to unresolved, which warns.
    // Saved Messages is the one case the InputPeer doesn't carry an id for:
    // MessagesController#getInputPeer builds a TL_inputPeerSelf for the client
    // user (MessagesController.java:6016-6019), which has no user_id/chat_id/
    // channel_id, so DialogObject#getPeerDialogId would return 0 and every typed
    // message to Saved Messages would keep double-warning. Map it back to this
    // account's own user id, which is the dialog id ChatActivity uses there.
    private static long resolveDialogId(int account, TLObject request) {
        if (request instanceof TLRPC.TL_messages_sendEncrypted) {
            TLRPC.TL_inputEncryptedChat peer = ((TLRPC.TL_messages_sendEncrypted) request).peer;
            return peer == null ? DIALOG_ID_UNRESOLVED : DialogObject.makeEncryptedDialogId(peer.chat_id);
        }
        if (request instanceof TLRPC.TL_messages_sendEncryptedFile) {
            TLRPC.TL_inputEncryptedChat peer = ((TLRPC.TL_messages_sendEncryptedFile) request).peer;
            return peer == null ? DIALOG_ID_UNRESOLVED : DialogObject.makeEncryptedDialogId(peer.chat_id);
        }
        TLRPC.InputPeer peer = TlUtils.getInputPeerFromSendMessageRequest(request);
        if (peer == null) {
            peer = inputPeerFromUnhandledSendRequest(request);
        }
        if (peer instanceof TLRPC.TL_inputPeerSelf) {
            // Still fails open while logged out, where this reads 0.
            return UserConfig.getInstance(account).getClientUserId();
        }
        return DialogObject.getPeerDialogId(peer);
    }

    // NagramX: the allowlisted requests TlUtils doesn't know about but that do
    // name their destination. Anything still unhandled returns null and so fails
    // open, which is the right default -- a send whose chat can't be identified
    // can't be claimed to be one the user was already warned about.
    private static TLRPC.InputPeer inputPeerFromUnhandledSendRequest(TLObject request) {
        if (request instanceof TLRPC.TL_messages_sendScheduledMessages) {
            return ((TLRPC.TL_messages_sendScheduledMessages) request).peer;
        }
        if (request instanceof TLRPC.TL_messages_sendQuickReplyMessages) {
            return ((TLRPC.TL_messages_sendQuickReplyMessages) request).peer;
        }
        if (request instanceof TLRPC.TL_messages_sendBotRequestedPeer) {
            return ((TLRPC.TL_messages_sendBotRequestedPeer) request).peer;
        }
        if (request instanceof TLRPC.TL_messages_startBot) {
            return ((TLRPC.TL_messages_startBot) request).peer;
        }
        return null;
    }

    private static void onMessageRequestReadyUnsafe(int account, TLObject request) {
        if (!isMessageSendRequest(request)) {
            return;
        }

        if (!NekoConfig.isGhostModeActive()) {
            return;
        }

        // NagramX: resolved on this thread and captured into the runnable rather
        // than re-derived inside it -- the request belongs to the caller and its
        // resources are freed once the send completes, so it must not be read
        // from a runnable that runs later.
        final long dialogId = resolveDialogId(account, request);

        // NagramX: resolve the fragment on the UI thread, where sendRequestInternal
        // does not run (it's on Utilities.stageQueue), and decide + show against
        // that exact instance -- never test one fragment instance and show on a
        // different one. The whole runnable body is guarded: it runs on the UI
        // thread's own dispatch, on a call stack the caller-side guard around
        // onMessageRequestReady never sees (that guard only covers the synchronous
        // call that posts this runnable), so an unguarded failure here would crash
        // the app on the main looper.
        AndroidUtilities.runOnUIThread(() -> {
            try {
                // NagramX: the typing reminder already told the user, in this chat,
                // during this Ghost session -- so this send is something they chose
                // knowing it wasn't covered, and saying it again is the noise this
                // check removes. Read here rather than above because the reminder's
                // set is UI-thread-only state; reading it from the stage queue would
                // reintroduce exactly the cross-thread access that got an earlier
                // per-chat design deleted (docs/codemap/dead-ends.md). It stays a
                // read-only query -- this path never records anything, so it cannot
                // consume a reminder the user has not actually been shown.
                if (dialogId != DIALOG_ID_UNRESOLVED
                        && GhostTypingReminderHelper.wasRemindedThisGhostSession(account, dialogId)) {
                    return;
                }
                BaseFragment fragment = LaunchActivity.getSafeLastFragment();
                tryShowBulletin(fragment, account);
            } catch (Throwable t) {
                // Best-effort: FileLog.e itself is not guaranteed not to throw
                // (allocation, stack-tag construction, Android logging), and this
                // runs on the main looper with nothing above it to catch a second
                // failure, so a failure logging the first one must not escape either.
                try {
                    FileLog.e("GhostSendWarningHelper: swallowed unexpected failure showing bulletin", t);
                } catch (Throwable ignored) {
                }
            }
        });
    }

    // NagramX: the result of tryShowBulletin. ATTEMPTED means a bulletin was
    // constructed and .show() was called against a non-empty instance -- it does
    // NOT mean the user necessarily saw it (see tryShowBulletin below for why that
    // can't be guaranteed here). Every other value names a specific reason the
    // attempt itself didn't happen. Suppression by an earlier typing reminder is
    // decided before this runs and so has no value here.
    private enum BulletinOutcome {
        ATTEMPTED, NO_HOST, PAUSED, WRONG_ACCOUNT, EMPTY_CONTAINER
    }

    // NagramX: single place answering "is there a plausible host to attempt this
    // on, for this account", collapsing what several review rounds each added as
    // one more clause directly in the runnable body (wrong account, bottom sheet,
    // paused fragment, a resolved-but-empty container). This is deliberately named
    // and documented as a best-effort eligibility check, not a visibility
    // guarantee: BulletinFactory.canShowBulletin(fragment) only checks that a
    // parent activity and layout container exist, and neither that nor
    // fragment.isPaused() rules out something else actually covering the fragment
    // on screen. Two known cases where a bulletin attempted here is never actually
    // seen: LaunchActivity#showPasscodeActivity (reached from DialogsActivity's
    // manual "Lock" action) sets the navigation layout's view to INVISIBLE without
    // pausing the fragment underneath it, and PhotoViewer installs its own
    // WindowManager window above the fragment without pausing it either. Both are
    // reachable during a real send (e.g. a completing upload dispatching its
    // result later). Building a delivery contract that could rule these out would
    // mean this hook owning presentation, which is out of proportion for a warning
    // -- so this stays a best-effort check, and a bulletin missed this way simply
    // isn't shown; with no per-chat state to consume, the next real send in that
    // chat warns again.
    private static BulletinOutcome tryShowBulletin(BaseFragment fragment, int account) {
        if (fragment == null || !BulletinFactory.canShowBulletin(fragment)) {
            return BulletinOutcome.NO_HOST;
        }
        if (fragment.isPaused()) {
            return BulletinOutcome.PAUSED;
        }
        if (fragment.getCurrentAccount() != account) {
            return BulletinOutcome.WRONG_ACCOUNT;
        }

        // NagramX: same longer duration as the typing reminder -- createErrorBulletin
        // builds at Bulletin.DURATION_SHORT (1.5s), and this warning now fires only
        // where no earlier heads-up was possible, which makes it the sole signal for
        // that send and the last one that should flash past unread.
        Bulletin bulletin = resolveBulletinFactory(fragment)
                .createErrorBulletin(getString(R.string.GhostSendExposedWarning))
                .setDuration(Bulletin.DURATION_PROLONG);
        if (bulletin instanceof Bulletin.EmptyBulletin) {
            return BulletinOutcome.EMPTY_CONTAINER;
        }
        bulletin.show();
        return BulletinOutcome.ATTEMPTED;
    }

    // NagramX: mirrors BulletinFactory.global()'s bottom-sheet handling (BulletinFactory.java:87-88)
    // for a fragment we already resolved and canShowBulletin-checked -- if the fragment has an open
    // BottomSheet, global()'s own of(fragment) call would attach the bulletin to the fragment's
    // layout behind the sheet, where the user never sees it. Deliberately not calling global()
    // itself: it re-resolves the fragment from scratch and falls back to
    // BulletinFactory.of(Bulletin.BulletinWindow.make(ApplicationLoader.applicationContext), null)
    // when that re-resolution returns null, which is the unguarded crash path this class exists to
    // avoid. fragment here is already known non-null.
    private static BulletinFactory resolveBulletinFactory(BaseFragment fragment) {
        if (fragment.visibleDialog instanceof BottomSheet) {
            return BulletinFactory.of(((BottomSheet) fragment.visibleDialog).container, fragment.getResourceProvider());
        }
        return BulletinFactory.of(fragment);
    }
}

