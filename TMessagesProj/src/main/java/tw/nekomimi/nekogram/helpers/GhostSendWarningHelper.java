package tw.nekomimi.nekogram.helpers;

import static org.telegram.messenger.LocaleController.getString;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.R;
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
 * Warns the user, every time it happens, that a message they just sent while
 * Ghost Mode was on still went out over the network and exposed their online
 * status. Ghost Mode never held sends back -- this only informs.
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

    private static void onMessageRequestReadyUnsafe(int account, TLObject request) {
        if (!isMessageSendRequest(request)) {
            return;
        }

        if (!NekoConfig.isGhostModeActive()) {
            return;
        }

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
    // attempt itself didn't happen.
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

        Bulletin bulletin = resolveBulletinFactory(fragment)
                .createErrorBulletin(getString(R.string.GhostSendExposedWarning));
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

