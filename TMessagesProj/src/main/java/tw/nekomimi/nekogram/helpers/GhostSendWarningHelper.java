package tw.nekomimi.nekogram.helpers;

import static org.telegram.messenger.LocaleController.getString;

import android.util.SparseArray;

import org.telegram.messenger.AndroidUtilities;
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

    // NagramX: keyed by account first because Ghost Mode is a single app-wide
    // predicate (NekoConfig#isGhostModeActive) shared by every account, but a
    // dialogId is only unique within one account -- without the account key two
    // different accounts' chat 12345 would wrongly share one warned flag.
    private static final SparseArray<HashSet<Long>> warnedDialogsByAccount = new SparseArray<>();

    private GhostSendWarningHelper() {
    }

    // Called from NekoConfig#setGhostMode when Ghost turns off, ending the
    // session the "already warned" set belongs to.
    public static void resetWarnings() {
        warnedDialogsByAccount.clear();
    }

    public static void onMessageReachingWire(int account, long dialogId) {
        if (!NekoConfig.isGhostModeActive()) {
            return;
        }
        HashSet<Long> warnedDialogs = warnedDialogsByAccount.get(account);
        if (warnedDialogs == null) {
            warnedDialogs = new HashSet<>();
            warnedDialogsByAccount.put(account, warnedDialogs);
        }
        if (!warnedDialogs.add(dialogId)) {
            return;
        }
        AndroidUtilities.runOnUIThread(() ->
                BulletinFactory.global().createErrorBulletin(getString(R.string.GhostSendExposedWarning)).show());
    }
}
