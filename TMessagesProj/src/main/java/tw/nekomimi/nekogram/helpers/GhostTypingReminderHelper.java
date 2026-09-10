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
 * send, unlike {@link GhostSendWarningHelper} which fires after a message
 * actually reaches the network. Both stay independently correct: this one is
 * an early nudge and can be silently missed (a covered fragment, a paused
 * screen); the send-time warning is the one accurate signal for every message
 * type in every configuration.
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

    // NagramX: account -> dialogIds already reminded this Ghost Mode session.
    // Reset lazily below on the next Ghost off->on edge observed at the one call
    // site that reads Ghost state, rather than via a hook into NekoConfig or
    // GhostModeActivity -- see docs/codemap/dead-ends.md for why a previous,
    // differently-shaped version of per-chat Ghost state was deleted, and why
    // this one is not the same mistake: every read and write here happens on the
    // UI thread, from ChatActivityEnterView's TextWatcher (itself only ever
    // invoked on the main looper) -- there is no background-thread writer, so
    // no synchronization is needed, unlike the deleted state that raced against
    // the send path on Utilities.stageQueue.
    private static final SparseArray<HashSet<Long>> remindedDialogs = new SparseArray<>();
    private static boolean wasGhostActive;

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
        boolean active = NekoConfig.isGhostModeActive();
        if (active && !wasGhostActive) {
            remindedDialogs.clear();
        }
        wasGhostActive = active;

        if (!active) {
            return;
        }

        HashSet<Long> reminded = remindedDialogs.get(account);
        if (reminded == null) {
            reminded = new HashSet<>();
            remindedDialogs.put(account, reminded);
        }
        if (reminded.contains(dialogId)) {
            return;
        }

        // NagramX: post rather than show inline -- this runs from inside the
        // TextWatcher's own call stack (checkSendButton, delegate callbacks and
        // layout updates all happen around it), and showing a bulletin there would
        // interleave a view-hierarchy change with the composer's own in-flight
        // update. Posting lets that unwind first, mirroring how
        // GhostSendWarningHelper hops onto the UI thread from its own caller.
        HashSet<Long> remindedForAccount = reminded;
        AndroidUtilities.runOnUIThread(() -> {
            try {
                if (tryShowBulletin(fragment, account)) {
                    remindedForAccount.add(dialogId);
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
