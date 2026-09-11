package tw.nekomimi.nekogram.helpers;

import static org.telegram.messenger.LocaleController.getString;

import android.util.SparseArray;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.Components.Bulletin;
import org.telegram.ui.Components.BulletinFactory;

import java.util.HashSet;

import tw.nekomimi.nekogram.NekoConfig;

/**
 * Reminds the user, once per chat per Ghost Mode session, the first time they
 * start typing into an empty composer while Ghost Mode is on -- ahead of the
 * send, unlike {@link GhostSendWarningHelper} which fires at the last moment
 * before a message-producing request is actually dispatched to tgnet. Both
 * stay independently correct: this one is an early nudge and can be silently
 * missed (a covered fragment, a paused screen); the send-time warning is the
 * one accurate signal for every message type in every configuration.
 * <p>
 * NagramX: a future change should add a Hold-Messages-inactive condition here
 * (once the currently-unmerged Hold Messages setting lands) and update
 * {@code GhostTypingReminder}'s copy to mention it -- this build has no Hold
 * setting to check, so the reminder fires whenever Ghost Mode is active, full
 * stop.
 */
public class GhostTypingReminderHelper {

    private GhostTypingReminderHelper() {
    }

    // NagramX: bumped only by NekoConfig#setGhostMode, on an observed
    // false->true transition of the master Ghost Mode switch -- see the
    // docs/codemap/dead-ends.md entry for why this lives here rather than in
    // NekoConfig (isGhostModeActive() must stay a pure, side-effect-free
    // predicate: Ghost Hold's own PR #336 also calls it, from multiple
    // threads, and relies on exactly that purity) and for the narrower scope
    // this implies (individual per-signal toggle/lock rows in
    // GhostModeActivity that cycle the predicate without ever calling
    // setGhostMode do not advance this).
    private static int ghostSessionEpoch;

    // NagramX: called only by NekoConfig#setGhostMode, on the UI thread
    // (verified: every caller of setGhostMode/toggleGhostMode --
    // GhostModeActivity's menu action, DialogsActivity, MainTabsActivity,
    // LaunchActivity's launcher-shortcut handler -- is a UI-thread
    // click/action handler), so no synchronization is needed here.
    public static void onGhostModeMasterSwitchActivated() {
        ghostSessionEpoch++;
    }

    // NagramX: account -> this account's reminded-dialogs state for whichever
    // Ghost session epoch it was last touched under. remindedSetForEpoch below
    // is the single accessor both call sites (the synchronous check and the
    // posted runnable) must go through -- it replaces a stale entry with a
    // fresh one for the current epoch on the spot, so staleness is corrected
    // wherever/whenever it's noticed rather than needing a separate reset step
    // someone else must already have run first. That matters because Ghost
    // Mode can cycle off->on more than once between this being queued and the
    // posted runnable actually getting to run, with no composer callback ever
    // observing the intermediate cycle -- a plain "did the epoch move since I
    // queued this" check would still read a set some earlier, now-stale entry
    // left behind; going through this accessor on every access instead means
    // there is no separate stale set left lying around to read by mistake.
    // See docs/codemap/dead-ends.md for why a previous, differently-shaped
    // version of per-chat Ghost state was deleted, and why this one is not the
    // same mistake: every read and write here happens on the UI thread, from
    // ChatActivityEnterView's TextWatcher (itself only ever invoked on the main
    // looper) plus the UI-thread runnable it posts below -- there is no
    // background-thread writer, so no synchronization is needed, unlike the
    // deleted state that raced against the send path on Utilities.stageQueue.
    private static final SparseArray<PerAccountState> stateByAccount = new SparseArray<>();

    // NagramX: epoch is the ghostSessionEpoch value this account's reminded
    // set is valid for -- see remindedSetForEpoch, the only place that creates
    // or replaces one of these.
    private static final class PerAccountState {
        final int epoch;
        final HashSet<Long> reminded = new HashSet<>();

        PerAccountState(int epoch) {
            this.epoch = epoch;
        }
    }

    // NagramX: returns this account's reminded-dialogs set for the given Ghost
    // session epoch, replacing it with a fresh empty one first if the stored
    // entry belongs to an older epoch (including "no entry yet", epoch 0's
    // initial default). Every caller -- the synchronous check and the posted
    // runnable alike -- must read the current epoch and call this rather than
    // caching a set reference across a Ghost Mode toggle, so a set that turns
    // out to belong to an already-ended session is never mistaken for the
    // current one.
    private static HashSet<Long> remindedSetForEpoch(int account, int epoch) {
        PerAccountState state = stateByAccount.get(account);
        if (state == null || state.epoch != epoch) {
            state = new PerAccountState(epoch);
            stateByAccount.put(account, state);
        }
        return state.reminded;
    }

    /**
     * Call from the composer's own text-change callback, on the UI thread, on the
     * true empty-to-non-empty transition of the composer text. fragment is the
     * chat fragment hosting the composer -- callers must already have confirmed
     * it is non-null (i.e. this is the real chat composer, not one of the other
     * surfaces sharing the same enter-view widget) before calling this.
     */
    public static void onComposerTypingObserved(int account, long dialogId, BaseFragment fragment) {
        try {
            onComposerTypingObservedUnsafe(account, dialogId, fragment);
        } catch (Throwable t) {
            FileLog.e("GhostTypingReminderHelper: swallowed unexpected failure", t);
        }
    }

    private static void onComposerTypingObservedUnsafe(int account, long dialogId, BaseFragment fragment) {
        if (!NekoConfig.isGhostModeActive()) {
            return;
        }

        HashSet<Long> reminded = remindedSetForEpoch(account, ghostSessionEpoch);
        if (reminded.contains(dialogId)) {
            return;
        }

        // NagramX: post rather than show inline -- this runs from inside the
        // TextWatcher's own call stack (checkSendButton, delegate callbacks and
        // layout updates all happen around it), and showing a bulletin there would
        // interleave a view-hierarchy change with the composer's own in-flight
        // update. Posting lets that unwind first, mirroring how
        // GhostSendWarningHelper hops onto the UI thread from its own caller.
        AndroidUtilities.runOnUIThread(() -> {
            try {
                // NagramX: re-check here, not just above -- Ghost Mode can be toggled
                // off in the moment between posting this and it actually running.
                if (!NekoConfig.isGhostModeActive()) {
                    return;
                }
                // NagramX: re-read the epoch and go through remindedSetForEpoch again
                // rather than reusing the outer scope's `reminded` reference -- Ghost
                // Mode can cycle off->on any number of times, including more than
                // once, in the window between posting this and it running, with no
                // composer callback ever observing an intermediate cycle to have
                // reset anything. Re-deriving the set from the live epoch here means
                // it is always the right one for whatever session is current right
                // now, never a stale one left over from whichever session queued this.
                HashSet<Long> currentReminded = remindedSetForEpoch(account, ghostSessionEpoch);
                // NagramX: two qualifying transitions in the same chat (e.g. a fast
                // type-delete-retype) can each post one of these before either runs,
                // and both would have passed the membership check above against the
                // same not-yet-updated set. Recheck immediately before showing so at
                // most one of them actually spends the budget and displays a bulletin.
                if (currentReminded.contains(dialogId)) {
                    return;
                }
                if (tryShowBulletin(fragment, account)) {
                    currentReminded.add(dialogId);
                }
            } catch (Throwable t) {
                try {
                    FileLog.e("GhostTypingReminderHelper: swallowed unexpected failure showing bulletin", t);
                } catch (Throwable ignored) {
                }
            }
        });
    }

    // NagramX: mirrors GhostSendWarningHelper#tryShowBulletin's eligibility
    // checks (canShowBulletin, paused fragment, wrong account, empty-bulletin
    // outcome) -- kept as a separate copy rather than a shared call since that
    // class's own methods are private and it is out of scope to change here.
    // Returns whether a bulletin was actually attempted; the caller only spends
    // this chat's one-time reminder budget when this returns true.
    private static boolean tryShowBulletin(BaseFragment fragment, int account) {
        if (fragment == null || !BulletinFactory.canShowBulletin(fragment)) {
            return false;
        }
        if (fragment.isPaused()) {
            return false;
        }
        if (fragment.getCurrentAccount() != account) {
            return false;
        }

        Bulletin bulletin = resolveBulletinFactory(fragment)
                .createErrorBulletin(getString(R.string.GhostTypingReminder));
        if (bulletin instanceof Bulletin.EmptyBulletin) {
            return false;
        }
        bulletin.show();
        return true;
    }

    // NagramX: mirrors GhostSendWarningHelper#resolveBulletinFactory -- see that
    // method for why global() itself isn't used here.
    private static BulletinFactory resolveBulletinFactory(BaseFragment fragment) {
        if (fragment.visibleDialog instanceof BottomSheet) {
            return BulletinFactory.of(((BottomSheet) fragment.visibleDialog).container, fragment.getResourceProvider());
        }
        return BulletinFactory.of(fragment);
    }
}
