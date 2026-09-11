package tw.nekomimi.nekogram.helpers;

import static org.telegram.messenger.LocaleController.getString;

import android.util.SparseArray;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
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
 * before a message-producing request is actually dispatched to tgnet.
 * <p>
 * This is the primary signal of the two, and the send-time warning defers to
 * it: once this has reminded a chat during the current Ghost session, the
 * send-time warning stays quiet in that chat (it asks via
 * {@link #wasRemindedThisGhostSession}). Sending after being reminded is a
 * deliberate act, and announcing it a second time is noise.
 * <p>
 * The send-time warning still fires in a chat this has never reminded, for
 * everything this reminder structurally cannot see. It only watches
 * ChatActivity's own composer, so forwards, media picked from the gallery,
 * text shared in from another app, bot keyboard buttons, story and
 * popup-notification replies, and the automatic retry of an unsent message all
 * reach the network without ever passing through here. In a chat that *has*
 * been reminded this Ghost session, the suppression is by destination chat and
 * so covers those sends too, for the rest of that session -- the user was told
 * in that chat and the second bulletin would repeat it. That holds only where
 * the send names a destination the helper can resolve; one that doesn't, such
 * as an encrypted multi-media send, fails open and warns anyway. Narrowing by
 * request
 * type instead, to keep them warning, was reviewed and rejected: it goes wrong
 * in both directions at once -- see GhostSendWarningHelper for why.
 * <p>
 * The deferral is keyed on a reminder that reached {@code show()} against an
 * eligible, non-paused host for the right account, not on one that was merely
 * due -- a reminder skipped because there was no host to show it on leaves the
 * send-time warning as the only signal, and it fires. That check is an
 * eligibility test, not proof the user's eyes were on it: the two known cases
 * where a bulletin is attempted but covered (the passcode screen's INVISIBLE
 * navigation layout, a PhotoViewer window) would suppress the later warning
 * too. Both need the chat to be off-screen, which cannot be true at the moment
 * a keystroke in that chat's own composer posts the reminder, so this is a
 * narrower residual than it is for the send-time warning -- whose own trigger
 * can arrive long after the fact, on a completing upload. Verifying real
 * on-screen delivery would mean this feature owning presentation, which is out
 * of proportion for a warning.
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

    // NagramX: bumped whenever an observation of NekoConfig#isGhostModeActive()
    // sees it go false->true. It lives here rather than in NekoConfig because
    // isGhostModeActive() must stay a pure, side-effect-free predicate: Ghost
    // Hold's own PR #336 also calls it, from multiple threads, and relies on
    // exactly that purity. See the docs/codemap/dead-ends.md entry for the two
    // earlier shapes this went through.
    private static int ghostSessionEpoch;

    // NagramX: last value of the derived Ghost predicate this has seen, or null
    // before the first observation. Null is deliberately not treated as false:
    // the first observation in a process must not count as an activating edge,
    // or opening Ghost settings while Ghost is already on would wipe reminders
    // earned earlier in the same session.
    private static Boolean lastObservedGhostActive;

    // NagramX: call after anything that may have changed the config the Ghost
    // predicate is derived from. It is deliberately an *observer* rather than a
    // "Ghost was just switched on" notification, because the predicate has no
    // single writer: NekoConfig#setGhostMode drives it from the master switch,
    // but GhostModeActivity's individual signal rows and their long-press lock
    // rows each move one input and can flip the derived value without going
    // near setGhostMode. Asking the predicate here and comparing means every
    // such path reports a real edge without each one having to know it caused
    // one, and an extra call that changed nothing is a no-op.
    // Callers are all UI-thread click/action handlers (every caller of
    // setGhostMode/toggleGhostMode -- GhostModeActivity's menu action,
    // DialogsActivity, MainTabsActivity, LaunchActivity's launcher-shortcut
    // handler -- plus GhostModeActivity's own row callbacks), so no
    // synchronization is needed here.
    public static void onGhostSignalsChanged() {
        boolean active = NekoConfig.isGhostModeActive();
        Boolean previous = lastObservedGhostActive;
        lastObservedGhostActive = active;
        if (active && previous != null && !previous) {
            ghostSessionEpoch++;
        }
    }

    // NagramX: account -> this account's reminded-dialogs state for whichever
    // Ghost session epoch it was last touched under. remindedSetForEpoch below
    // is the single accessor all three call sites (the synchronous check, the
    // posted runnable, and the send helper's read-only query) must go through -- it replaces a stale entry with a
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
    // same mistake. There are three access paths and every one of them is on
    // the UI thread: ChatActivityEnterView's TextWatcher (itself only ever
    // invoked on the main looper), the UI-thread runnable it posts below, and
    // -- read-only -- GhostSendWarningHelper asking wasRemindedThisGhostSession
    // from inside its own runOnUIThread block, never from the stage queue its
    // hook runs on. The first two write; the third never records anything. So
    // there is still no background-thread access and no synchronization is
    // needed, unlike the deleted state that raced against the send path on
    // Utilities.stageQueue.
    private static final SparseArray<PerAccountState> stateByAccount = new SparseArray<>();

    // NagramX: epoch is the ghostSessionEpoch value this account's reminded
    // set is valid for, and userId is the account slot's logged-in user at the
    // time it was created -- see remindedSetForEpoch, the only place that
    // creates or replaces one of these.
    private static final class PerAccountState {
        final int epoch;
        final long userId;
        final HashSet<Long> reminded = new HashSet<>();

        PerAccountState(int epoch, long userId) {
            this.epoch = epoch;
            this.userId = userId;
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
    // The stored user id is checked for the same reason: stateByAccount is keyed
    // by the account *slot*, which is reused across a logout and a fresh login,
    // while Ghost Mode is global and nothing about logging out advances the
    // epoch. Dialog ids are peer ids and two users in one slot can genuinely
    // share them (any group or channel both are in), so without this a chat the
    // previous user was reminded about would read as reminded for the new one --
    // and since the send-time warning now suppresses on exactly this answer,
    // that would cost the new user a warning they never had. Treating a changed
    // user id like a stale epoch keeps the fix inside this accessor, where
    // staleness is already corrected on the spot, rather than needing a teardown
    // hook to have run first.
    private static HashSet<Long> remindedSetForEpoch(int account, int epoch) {
        long userId = UserConfig.getInstance(account).getClientUserId();
        PerAccountState state = stateByAccount.get(account);
        if (state == null || state.epoch != epoch || state.userId != userId) {
            state = new PerAccountState(epoch, userId);
            stateByAccount.put(account, state);
        }
        return state.reminded;
    }

    // NagramX: the query GhostSendWarningHelper uses to stay quiet in a chat this
    // Ghost session has already reminded about. UI thread only, exactly like every
    // other access to this state -- that caller reads it from inside its own
    // runOnUIThread block, never from the stage queue its hook runs on. It goes
    // through remindedSetForEpoch with a freshly-read ghostSessionEpoch for the
    // same reason the posted runnable below does: a set that belongs to an
    // already-ended Ghost session must never answer for the current one, and
    // reading through the accessor is what guarantees there is no stale set left
    // lying around to read by mistake.
    // Returns true only for a chat where a bulletin reached show() against an
    // eligible, non-paused host -- that is the only thing added to the set -- so
    // a reminder that was due but had nowhere to show leaves this false and the
    // send-time warning still fires. See the class javadoc for why that is an
    // eligibility test rather than proof of delivery, and why the residual is
    // narrow on this path specifically.
    static boolean wasRemindedThisGhostSession(int account, long dialogId) {
        return remindedSetForEpoch(account, ghostSessionEpoch).contains(dialogId);
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

        // NagramX: createErrorBulletin builds at Bulletin.DURATION_SHORT (1.5s),
        // which is too short to read a full sentence and still have a moment to
        // act on it. DURATION_PROLONG (5s) is the codebase's existing long-form
        // value, set the same way at e.g. DialogsActivity.java:6303 -- no custom
        // timer. Note 5s is an upper bound, not a guarantee: the bulletin slot is
        // global, so anything shown after this replaces it.
        Bulletin bulletin = resolveBulletinFactory(fragment)
                .createErrorBulletin(getString(R.string.GhostTypingReminder))
                .setDuration(Bulletin.DURATION_PROLONG);
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
