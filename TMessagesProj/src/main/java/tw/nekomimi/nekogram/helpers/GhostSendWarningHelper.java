package tw.nekomimi.nekogram.helpers;

import static org.telegram.messenger.LocaleController.getString;

import android.util.Log;
import android.util.SparseArray;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.BuildConfig;
import org.telegram.messenger.R;
import org.telegram.ui.Components.BulletinFactory;

import java.util.HashSet;

import tw.nekomimi.nekogram.NekoConfig;

/**
 * Warns the user, at most once per chat per Ghost session, that a message they
 * just sent while Ghost Mode was on still went out over the network and exposed
 * their online status. Ghost Mode never held sends back -- this only informs.
 * <p>
 * Call {@link #onMessageReachingWire(int, long)} at the last point before a
 * message request is actually handed to the network, so a message that a later
 * hold/queue feature diverts before that point never trips this warning.
 */
public class GhostSendWarningHelper {

    // NAX_SMOKE_ghost-send-warning: temporary reachability diagnostics, removed
    // once the smoke build confirms this decision point is reached in practice.
    private static final String SMOKE_TAG = "NAX_SMOKE_ghost-send-warning";

    // NagramX: keyed by account first because Ghost Mode is a single app-wide
    // predicate (NekoConfig#isGhostModeActive) shared by every account, but a
    // dialogId is only unique within one account -- without the account key two
    // different accounts' chat 12345 would wrongly share one warned flag.
    private static final SparseArray<HashSet<Long>> warnedDialogsByAccount = new SparseArray<>();

    // NagramX: Ghost Mode has no single "turned off" call -- GhostModeActivity
    // also flips the five underlying toggles individually (see e.g.
    // NekoConfig.sendReadMessagePackets.toggleConfigBool()), any of which can
    // break or restore isGhostModeActive() without going through setGhostMode().
    // Watching the live predicate itself at send time, instead of one write path,
    // catches a session boundary no matter which toggle caused it.
    private static boolean wasGhostActive = false;

    private GhostSendWarningHelper() {
    }

    public static void onMessageReachingWire(int account, long dialogId) {
        // BEGIN: unconditionally-reached liveness marker -- fires for every message
        // that gets this far, regardless of Ghost state, so reachability of this
        // hook can be confirmed even when Ghost happens to be off during a trace.
        Log.i(SMOKE_TAG, SMOKE_TAG + " BEGIN build=" + BuildConfig.BUILD_VERSION_STRING
                + " app=" + BuildConfig.APPLICATION_ID + " account=" + account + " dialogId=" + dialogId);

        boolean ghostActive = NekoConfig.isGhostModeActive();
        if (ghostActive && !wasGhostActive) {
            // A new Ghost session started since the last send we saw -- forget last session's warnings.
            warnedDialogsByAccount.clear();
        }
        wasGhostActive = ghostActive;

        boolean alreadyWarned = false;
        boolean shown = false;

        if (ghostActive) {
            HashSet<Long> warnedDialogs = warnedDialogsByAccount.get(account);
            if (warnedDialogs == null) {
                warnedDialogs = new HashSet<>();
                warnedDialogsByAccount.put(account, warnedDialogs);
            }
            alreadyWarned = !warnedDialogs.add(dialogId);
            if (!alreadyWarned) {
                shown = true;
                AndroidUtilities.runOnUIThread(() ->
                        BulletinFactory.global().createErrorBulletin(getString(R.string.GhostSendExposedWarning)).show());
                // Expected path: bulletin actually shown for this chat this session.
                Log.i(SMOKE_TAG, SMOKE_TAG + " BULLETIN_SHOWN account=" + account + " dialogId=" + dialogId);
            }
        }

        if (!shown) {
            // Forbidden/competing path: send proceeded with Ghost on (or Ghost off
            // entirely) but no bulletin fired this time, with the reason why.
            Log.w(SMOKE_TAG, SMOKE_TAG + " NO_BULLETIN ghostActive=" + ghostActive
                    + " alreadyWarned=" + alreadyWarned + " account=" + account + " dialogId=" + dialogId);
        }

        // END: decision handling for this send completed.
        Log.i(SMOKE_TAG, SMOKE_TAG + " END ghostActive=" + ghostActive + " shown=" + shown
                + " account=" + account + " dialogId=" + dialogId);
    }
}
