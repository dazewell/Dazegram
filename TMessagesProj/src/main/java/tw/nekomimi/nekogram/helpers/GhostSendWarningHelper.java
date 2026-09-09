package tw.nekomimi.nekogram.helpers;

import static org.telegram.messenger.LocaleController.getString;

import android.util.Log;

import com.radolyn.ayugram.utils.AyuGhostUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.BuildConfig;
import org.telegram.messenger.R;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_ephemeral;
import org.telegram.ui.ActionBar.BaseFragment;
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
    // classes, not a "TL_messages_send*" name-prefix test -- TL_messages_editMessage
    // and TL_messages_addPollAnswer also carry that prefix, but neither one sends a
    // new message, so a name-based test would wrongly fire this warning on a poll
    // vote or an edit (the defect the previous hook point had). Also excludes
    // reactions, typing/read requests, screenshot notifications, uploads, and
    // encrypted service actions.
    // Known gap, accepted for a warning-only feature: a future send RPC upstream
    // adds is missed here until this list is updated -- the actual protection
    // (holding sends back) is the local-hold feature's job, not this one's.
    private static boolean isMessageSendRequest(TLObject request) {
        return request instanceof TLRPC.TL_messages_sendMessage
                || request instanceof TLRPC.TL_messages_sendMedia
                || request instanceof TLRPC.TL_messages_sendMultiMedia
                || request instanceof TLRPC.TL_messages_forwardMessages
                || request instanceof TLRPC.TL_messages_sendInlineBotResult
                || request instanceof TLRPC.TL_messages_sendScheduledMessages
                || request instanceof TLRPC.TL_messages_sendQuickReplyMessages
                || request instanceof TL_ephemeral.TL_sendMessage
                || request instanceof TLRPC.TL_messages_sendEncrypted
                || request instanceof TLRPC.TL_messages_sendEncryptedFile
                || request instanceof TLRPC.TL_messages_sendEncryptedMultiMedia;
    }

    // NagramX: reuses AyuGhostUtils' existing InputPeer/InputEncryptedChat -> dialogId
    // conversion instead of duplicating it -- diagnostics-only, this dialogId is never
    // used to key any stored state.
    private static Long extractDialogId(TLObject request) {
        if (request instanceof TLRPC.TL_messages_sendMessage r) {
            return AyuGhostUtils.getDialogId(r.peer);
        } else if (request instanceof TLRPC.TL_messages_sendMedia r) {
            return AyuGhostUtils.getDialogId(r.peer);
        } else if (request instanceof TLRPC.TL_messages_sendMultiMedia r) {
            return AyuGhostUtils.getDialogId(r.peer);
        } else if (request instanceof TLRPC.TL_messages_forwardMessages r) {
            return AyuGhostUtils.getDialogId(r.to_peer);
        } else if (request instanceof TLRPC.TL_messages_sendInlineBotResult r) {
            return AyuGhostUtils.getDialogId(r.peer);
        } else if (request instanceof TLRPC.TL_messages_sendScheduledMessages r) {
            return AyuGhostUtils.getDialogId(r.peer);
        } else if (request instanceof TLRPC.TL_messages_sendQuickReplyMessages r) {
            return AyuGhostUtils.getDialogId(r.peer);
        } else if (request instanceof TL_ephemeral.TL_sendMessage r) {
            return AyuGhostUtils.getDialogId(r.peer);
        } else if (request instanceof TLRPC.TL_messages_sendEncrypted r) {
            return AyuGhostUtils.getDialogId(r.peer);
        } else if (request instanceof TLRPC.TL_messages_sendEncryptedFile r) {
            return AyuGhostUtils.getDialogId(r.peer);
        }
        // TL_messages_sendEncryptedMultiMedia carries no peer of its own.
        return null;
    }

    /**
     * account is the ConnectionsManager instance actually dispatching this
     * request, not UserConfig.selectedAccount -- correct for whichever of the
     * app's accounts is sending, regardless of which one is foregrounded.
     */
    public static void onMessageRequestReady(int account, TLObject request) {
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

        // NagramX: BulletinFactory.canShowBulletin(fragment) (BulletinFactory.java:75)
        // is the actual predicate for whether a fragment can safely host a bulletin
        // (parent activity and layout container both present) -- resolve the fragment
        // on the UI thread, where sendRequestInternal does not run (it's on
        // Utilities.stageQueue), test that exact instance, and show on it directly via
        // BulletinFactory.of(...) instead of re-resolving through .global() -- never
        // test one fragment instance and show on a different one.
        AndroidUtilities.runOnUIThread(() -> {
            BaseFragment fragment = LaunchActivity.getSafeLastFragment();
            boolean hostAvailable = BulletinFactory.canShowBulletin(fragment);

            if (hostAvailable) {
                BulletinFactory.of(fragment).createErrorBulletin(getString(R.string.GhostSendExposedWarning)).show();
                // Expected path: bulletin actually shown for this send.
                Log.i(SMOKE_TAG, SMOKE_TAG + " BULLETIN_SHOWN account=" + account + " dialogId=" + dialogId);
            } else {
                // Suppressed/competing path: no renderable bulletin host at show time.
                // There is no once-per-chat slot to preserve here -- this feature warns
                // every time, so an unshown warning here is simply a missed one, not a
                // deferred one.
                Log.w(SMOKE_TAG, SMOKE_TAG + " SUPPRESSED_NO_UI account=" + account + " dialogId=" + dialogId);
            }

            // END: decision handling for this request completed.
            Log.i(SMOKE_TAG, SMOKE_TAG + " END ghostActive=true shown=" + hostAvailable
                    + " account=" + account + " dialogId=" + dialogId);
        });
    }
}
