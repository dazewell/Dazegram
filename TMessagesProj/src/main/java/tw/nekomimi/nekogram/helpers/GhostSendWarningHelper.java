package tw.nekomimi.nekogram.helpers;

import static org.telegram.messenger.LocaleController.getString;

import android.util.Log;

import com.radolyn.ayugram.utils.AyuGhostUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.BuildConfig;
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

    // NAX_SMOKE_ghost-send-warning: temporary reachability diagnostics, removed
    // once the smoke build confirms this decision point is reached in practice.
    private static final String SMOKE_TAG = "NAX_SMOKE_ghost-send-warning";

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
    // TL_messages_sendBotRequestedPeer (in-chat request-peer submission) and
    // TL_messages_sendWebViewData (bot WebView data submission) are both known,
    // named, real user-initiated sends today, not a future-RPC gap, so they're
    // included deliberately rather than left to that gap.
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
                || request instanceof TL_ephemeral.TL_sendMessage
                || request instanceof TLRPC.TL_messages_sendEncrypted
                || request instanceof TLRPC.TL_messages_sendEncryptedFile
                || request instanceof TLRPC.TL_messages_sendEncryptedMultiMedia;
    }

    // NagramX: reuses AyuGhostUtils' existing InputPeer/InputEncryptedChat -> dialogId
    // conversion instead of duplicating it -- diagnostics-only, this dialogId is never
    // used to key any stored state. Null-checked here rather than in AyuGhostUtils
    // itself (that helper is shared fork code with other callers; widening its
    // contract is out of scope) -- e.g. TL_ephemeral.TL_sendMessage.peer is only
    // serialized when set (TL_ephemeral.java flag 8), so it can legitimately be null.
    // This is belt-and-braces: onMessageRequestReady never lets an exception from
    // here reach its caller either way, but the common case shouldn't rely on that.
    private static Long extractDialogId(TLObject request) {
        if (request instanceof TLRPC.TL_messages_sendMessage r) {
            return r.peer != null ? AyuGhostUtils.getDialogId(r.peer) : null;
        } else if (request instanceof TLRPC.TL_messages_sendMedia r) {
            return r.peer != null ? AyuGhostUtils.getDialogId(r.peer) : null;
        } else if (request instanceof TLRPC.TL_messages_sendMultiMedia r) {
            return r.peer != null ? AyuGhostUtils.getDialogId(r.peer) : null;
        } else if (request instanceof TLRPC.TL_messages_forwardMessages r) {
            return r.to_peer != null ? AyuGhostUtils.getDialogId(r.to_peer) : null;
        } else if (request instanceof TLRPC.TL_messages_sendInlineBotResult r) {
            return r.peer != null ? AyuGhostUtils.getDialogId(r.peer) : null;
        } else if (request instanceof TLRPC.TL_messages_sendScheduledMessages r) {
            return r.peer != null ? AyuGhostUtils.getDialogId(r.peer) : null;
        } else if (request instanceof TLRPC.TL_messages_sendQuickReplyMessages r) {
            return r.peer != null ? AyuGhostUtils.getDialogId(r.peer) : null;
        } else if (request instanceof TLRPC.TL_messages_sendBotRequestedPeer r) {
            return r.peer != null ? AyuGhostUtils.getDialogId(r.peer) : null;
        } else if (request instanceof TL_ephemeral.TL_sendMessage r) {
            return r.peer != null ? AyuGhostUtils.getDialogId(r.peer) : null;
        } else if (request instanceof TLRPC.TL_messages_sendEncrypted r) {
            return r.peer != null ? AyuGhostUtils.getDialogId(r.peer) : null;
        } else if (request instanceof TLRPC.TL_messages_sendEncryptedFile r) {
            return r.peer != null ? AyuGhostUtils.getDialogId(r.peer) : null;
        }
        // TL_messages_sendEncryptedMultiMedia and TL_messages_sendWebViewData carry
        // no peer of their own.
        return null;
    }

    /**
     * account is the ConnectionsManager instance actually dispatching this
     * request, not UserConfig.selectedAccount -- correct for whichever of the
     * app's accounts is sending, regardless of which one is foregrounded.
     * <p>
     * NagramX: this is called inline from ConnectionsManager#sendRequestInternal,
     * immediately before the request is actually dispatched to tgnet, inside that
     * method's own try block -- so anything this throws propagates into its catch
     * and the message never reaches native_sendRequest, i.e. a bug in a purely
     * informational helper would silently drop a real send. Never let that happen:
     * this entry point must be structurally incapable of affecting send behaviour,
     * so every path through it is wrapped and nothing above ever escapes. The worst
     * outcome from a defect in this class is a missing bulletin, never a dropped
     * message.
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
            // Filtered before any logging: typing, read, upload, edit, poll-vote and
            // other non-send RPCs pass through here constantly, and logging every one
            // of them would swamp the trace with noise unrelated to this feature.
            return;
        }

        // BEGIN: unconditionally-reached liveness marker -- fires for every real send
        // request reaching this hook, regardless of Ghost state, so reachability of
        // this decision point can be confirmed even when Ghost happens to be off
        // during a trace.
        Log.i(SMOKE_TAG, SMOKE_TAG + " BEGIN build=" + BuildConfig.BUILD_VERSION_STRING
                + " app=" + BuildConfig.APPLICATION_ID + " account=" + account
                + " request=" + request.getClass().getSimpleName());

        boolean ghostActive = NekoConfig.isGhostModeActive();
        Long dialogId = extractDialogId(request);

        if (!ghostActive) {
            // Forbidden/competing path: a real send request, but Ghost is off.
            Log.i(SMOKE_TAG, SMOKE_TAG + " NO_BULLETIN ghostActive=false account=" + account
                    + " dialogId=" + dialogId);
            Log.i(SMOKE_TAG, SMOKE_TAG + " END ghostActive=false shown=false account=" + account
                    + " dialogId=" + dialogId);
            return;
        }

        // NagramX: resolve the fragment on the UI thread, where sendRequestInternal
        // does not run (it's on Utilities.stageQueue), and decide + show against
        // that exact instance -- never test one fragment instance and show on a
        // different one. The whole runnable body is guarded: it runs on the UI
        // thread's own dispatch, outside onMessageRequestReady's synchronous
        // try/catch, so an unguarded failure here would crash the app on the main
        // looper -- worse than a dropped send, from a feature that's nothing but an
        // informational bulletin.
        AndroidUtilities.runOnUIThread(() -> {
            try {
                BaseFragment fragment = LaunchActivity.getSafeLastFragment();
                BulletinOutcome outcome = showBulletinIfVisible(fragment, account);
                boolean shown = outcome == BulletinOutcome.SHOWN;

                if (shown) {
                    // Expected path: bulletin actually shown for this send, in the
                    // sending account's own UI.
                    Log.i(SMOKE_TAG, SMOKE_TAG + " BULLETIN_SHOWN account=" + account + " dialogId=" + dialogId);
                } else if (outcome == BulletinOutcome.WRONG_ACCOUNT) {
                    // Suppressed/competing path: the foreground UI belongs to a different
                    // account than the one that actually sent. The sending account is
                    // already correct throughout (ConnectionsManager's own currentAccount,
                    // never UserConfig.selectedAccount) -- but this copy carries no account
                    // identifier, so showing it in a different account's foreground UI
                    // would tell that account's user their own status was exposed, which is
                    // simply false. Never show in the wrong account's UI; don't paper over
                    // this by adding a label to the copy instead.
                    Log.w(SMOKE_TAG, SMOKE_TAG + " SUPPRESSED_WRONG_ACCOUNT account=" + account + " dialogId=" + dialogId);
                } else {
                    // Suppressed/competing path: no bulletin actually rendered, for
                    // whichever reason showBulletinIfVisible found (no host, host
                    // paused, or a resolved container that turned out empty). There is
                    // no once-per-chat slot to preserve here -- this feature warns
                    // every time, so an unshown warning here is simply a missed one,
                    // not a deferred one.
                    Log.w(SMOKE_TAG, SMOKE_TAG + " SUPPRESSED_NO_UI reason=" + outcome + " account=" + account
                            + " dialogId=" + dialogId);
                }

                // END: decision handling for this request completed.
                Log.i(SMOKE_TAG, SMOKE_TAG + " END ghostActive=true shown=" + shown
                        + " account=" + account + " dialogId=" + dialogId);
            } catch (Throwable t) {
                FileLog.e("GhostSendWarningHelper: swallowed unexpected failure showing bulletin", t);
            }
        });
    }

    // NagramX: the result of showBulletinIfVisible -- SHOWN is the only outcome
    // where .show() actually ran against a non-empty bulletin; every other value
    // names the specific reason nothing rendered, for diagnostics.
    private enum BulletinOutcome {
        SHOWN, NO_HOST, PAUSED, WRONG_ACCOUNT, EMPTY_CONTAINER
    }

    // NagramX: single place answering "is there a host the user is genuinely
    // looking at right now, for this account -- and does a bulletin actually
    // render there", collapsing what three separate review rounds each added as
    // one more clause directly in the runnable body (wrong account, bottom sheet,
    // paused fragment). A fourth case makes clear the real question was never
    // "which conditions did we think to check" but "did anything actually
    // render": BulletinFactory.of(fragment) can resolve a fragment's attached
    // story-viewer container, and that container can itself be null, at which
    // point Bulletin.make(FrameLayout, ...) quietly returns a no-op
    // Bulletin.EmptyBulletin (Bulletin.java:108) instead of showing anything.
    // Rather than trying to predict that from the outside, this checks the
    // constructed Bulletin itself before calling show() -- callers must only log
    // BULLETIN_SHOWN when this returns SHOWN.
    private static BulletinOutcome showBulletinIfVisible(BaseFragment fragment, int account) {
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
        return BulletinOutcome.SHOWN;
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

