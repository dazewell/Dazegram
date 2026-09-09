package tw.nekomimi.nekogram.helpers;

import static org.telegram.messenger.LocaleController.getString;

import android.util.Log;
import android.util.SparseArray;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.BuildConfig;
import org.telegram.messenger.R;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.LaunchActivity;

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
    // NekoConfig.sendReadMessagePackets.toggleConfigBool()), so both that write
    // path and NekoConfig#setGhostMode call onGhostStateChanged() eagerly, the
    // instant either can flip the predicate. onMessageReachingWire also calls it
    // as a fallback, so a boundary is still caught even if some future write
    // path forgets to.
    private static boolean wasGhostActive = false;

    // NagramX: onGhostStateChanged() runs on the UI thread (settings writes),
    // while onMessageReachingWire() runs on whatever thread is sending a message
    // (not guaranteed to be the UI thread) -- guard the two fields they share so
    // a toggle and a send can never race on the same HashSet/SparseArray.
    private static final Object LOCK = new Object();

    private GhostSendWarningHelper() {
    }

    /**
     * Re-checks {@link NekoConfig#isGhostModeActive()} and clears the per-chat
     * warned set on any false-to-true transition, treating that as the start of
     * a new Ghost session. Returns the freshly observed state.
     */
    public static boolean onGhostStateChanged() {
        synchronized (LOCK) {
            return onGhostStateChangedLocked();
        }
    }

    // NagramX: split out so onMessageReachingWire can call this while already
    // holding LOCK -- java.util.concurrent locks aren't needed here since a
    // plain synchronized block is already re-entrant, but keeping one method as
    // the single "must hold LOCK" entry point avoids acquiring it twice per send.
    private static boolean onGhostStateChangedLocked() {
        boolean ghostActive = NekoConfig.isGhostModeActive();
        if (ghostActive && !wasGhostActive) {
            // A new Ghost session started -- forget the previous session's warnings.
            warnedDialogsByAccount.clear();
        }
        wasGhostActive = ghostActive;
        return ghostActive;
    }

    public static void onMessageReachingWire(int account, long dialogId) {
        // BEGIN: unconditionally-reached liveness marker -- fires for every message
        // that gets this far, regardless of Ghost state, so reachability of this
        // hook can be confirmed even when Ghost happens to be off during a trace.
        Log.i(SMOKE_TAG, SMOKE_TAG + " BEGIN build=" + BuildConfig.BUILD_VERSION_STRING
                + " app=" + BuildConfig.APPLICATION_ID + " account=" + account + " dialogId=" + dialogId);

        boolean ghostActive;
        boolean warnedAtDecide = false;
        boolean maybeWarn;

        synchronized (LOCK) {
            ghostActive = onGhostStateChangedLocked();
            if (ghostActive) {
                HashSet<Long> warnedDialogs = warnedDialogsByAccount.get(account);
                warnedAtDecide = warnedDialogs != null && warnedDialogs.contains(dialogId);
                maybeWarn = !warnedAtDecide;
            } else {
                maybeWarn = false;
            }
        }

        if (!maybeWarn) {
            // Forbidden/competing path: send proceeded with Ghost off, or this
            // chat was already known warned this session -- decided entirely on
            // the sender thread, no need to touch the UI at all.
            Log.w(SMOKE_TAG, SMOKE_TAG + " NO_BULLETIN ghostActive=" + ghostActive
                    + " alreadyWarned=" + warnedAtDecide + " account=" + account + " dialogId=" + dialogId);
            Log.i(SMOKE_TAG, SMOKE_TAG + " END ghostActive=" + ghostActive + " shown=false"
                    + " account=" + account + " dialogId=" + dialogId);
            return;
        }

        // NagramX: BulletinFactory.global() (BulletinFactory.java:83-84) picks
        // between a live fragment and a crash-prone Dialog on the application
        // Context on exactly one predicate: LaunchActivity.getSafeLastFragment()
        // == null. Checking that same predicate here -- at the moment of actually
        // showing, on the UI thread, where its answer is stable -- excludes the
        // crash path exactly rather than approximately, and covers Bubble UI by
        // construction (getSafeLastFragment() already checks BubbleActivity
        // first). The slot is only consumed together with actually showing, under
        // the same LOCK used by the sender-thread decision above, so "shown" and
        // "consumed" are one atomic act instead of two separate guesses that could
        // disagree if the fragment appears or disappears in between.
        AndroidUtilities.runOnUIThread(() -> {
            boolean hostAvailable = LaunchActivity.getSafeLastFragment() != null;
            boolean alreadyWarned = false;
            boolean shown = false;

            synchronized (LOCK) {
                if (hostAvailable) {
                    HashSet<Long> warnedDialogs = warnedDialogsByAccount.get(account);
                    if (warnedDialogs == null) {
                        warnedDialogs = new HashSet<>();
                        warnedDialogsByAccount.put(account, warnedDialogs);
                    }
                    alreadyWarned = !warnedDialogs.add(dialogId);
                    shown = !alreadyWarned;
                }
            }

            if (shown) {
                BulletinFactory.global().createErrorBulletin(getString(R.string.GhostSendExposedWarning)).show();
                // Expected path: bulletin actually shown for this chat this session.
                Log.i(SMOKE_TAG, SMOKE_TAG + " BULLETIN_SHOWN account=" + account + " dialogId=" + dialogId);
            } else if (!hostAvailable) {
                // Suppressed/competing path: no bulletin host by the time this
                // reached the UI thread -- the slot is left unconsumed, so the
                // next send in this chat with a host available still warns.
                Log.w(SMOKE_TAG, SMOKE_TAG + " SUPPRESSED_NO_UI account=" + account + " dialogId=" + dialogId);
            } else {
                // Forbidden/competing path: a concurrent send for the same chat
                // won the warning slot first, between the sender-thread decision
                // and this UI-thread act.
                Log.w(SMOKE_TAG, SMOKE_TAG + " NO_BULLETIN ghostActive=" + ghostActive
                        + " alreadyWarned=true account=" + account + " dialogId=" + dialogId);
            }

            // END: decision handling for this send completed.
            Log.i(SMOKE_TAG, SMOKE_TAG + " END ghostActive=" + ghostActive + " shown=" + shown
                    + " account=" + account + " dialogId=" + dialogId);
        });
    }
}
