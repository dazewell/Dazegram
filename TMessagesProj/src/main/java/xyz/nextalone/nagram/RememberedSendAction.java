package xyz.nextalone.nagram;

import org.telegram.messenger.UserConfig;

/**
 * One armed alternate send action per account, held in memory only -- never written to disk.
 *
 * <p>Long-pressing Send in the composer and picking silent / send-when-online / schedule arms
 * that action here; a following plain tap on Send repeats it instead of a normal send. Keyed by
 * account (never the selected account -- see ChatActivityEnterView, which always reads/writes
 * through currentAccount) and, within an account, by dialog id only, so switching between topics
 * of the same forum keeps the armed action. The dialog id is kept alongside it purely so
 * ChatActivity.onFragmentDestroy can tell whether the slot still belongs to the chat being left,
 * for the optional "reset when you leave the chat" setting -- it plays no part in whether the
 * action is still eligible to fire, which ChatActivityEnterView's own predicates decide fresh
 * against the chat you're currently in.
 */
public final class RememberedSendAction {

    public static final int NONE = 0;
    public static final int SILENT = 1;
    public static final int SEND_WHEN_ONLINE = 2;
    public static final int SCHEDULE = 3;

    private static final int[] armedAction = new int[UserConfig.MAX_ACCOUNT_COUNT];
    private static final long[] armedDialogId = new long[UserConfig.MAX_ACCOUNT_COUNT];

    private RememberedSendAction() {
    }

    public static int getArmedAction(int account) {
        if (account < 0 || account >= armedAction.length) return NONE;
        return armedAction[account];
    }

    public static long getArmedDialogId(int account) {
        if (account < 0 || account >= armedDialogId.length) return 0;
        return armedDialogId[account];
    }

    public static void arm(int account, int action, long dialogId) {
        if (account < 0 || account >= armedAction.length) return;
        armedAction[account] = action;
        armedDialogId[account] = dialogId;
    }

    public static void disarm(int account) {
        if (account < 0 || account >= armedAction.length) return;
        armedAction[account] = NONE;
        armedDialogId[account] = 0;
    }

    /** Called from ChatActivity.onFragmentDestroy when the "reset when you leave the chat" setting is on. */
    public static void clearIfDialog(int account, long dialogId) {
        if (getArmedAction(account) != NONE && getArmedDialogId(account) == dialogId) {
            disarm(account);
        }
    }

    /**
     * Called from ChatActivity.onBecomeFullyVisible when the "reset when you leave the chat" setting is
     * on, so navigating into a different chat clears the slot right away. onFragmentDestroy alone is too
     * late here -- opening another chat from a mention/link/profile "Send message" usually pushes the new
     * ChatActivity on top of the back stack instead of destroying the one that armed the action, so that
     * fragment can stay alive (and its slot armed) for a long time. Comparing against the dialog id of the
     * chat now becoming visible instead catches present, replace and back-stack-pop alike, and leaves a
     * same-dialog subview (scheduled messages, etc.) alone since its dialog id matches.
     */
    public static void clearIfDifferentDialog(int account, long enteringDialogId) {
        if (getArmedAction(account) != NONE && getArmedDialogId(account) != enteringDialogId) {
            disarm(account);
        }
    }

    /** Called from MessagesController.performLogout so a reused account slot doesn't inherit the departed account's armed action. */
    public static void clearAccountState(int account) {
        disarm(account);
    }

    // NagramX (#remember-send-action): the master/child NaConfig toggles are global, not scoped to one
    // account, so a settings-page or menu-master off transition must clear every account's slot -- not
    // just the caller's currentAccount -- or a different account's armed action survives to resurrect
    // once the toggle is turned back on.
    public static void disarmAll() {
        for (int account = 0; account < armedAction.length; account++) {
            disarm(account);
        }
    }

    /** Same as disarmAll, but only clears a slot currently holding the given action -- used when a
     * single child remember-toggle (not the master) turns off, so an armed action of a different type
     * on another account is left alone. */
    public static void disarmAllIfArmed(int action) {
        if (action == NONE) return;
        for (int account = 0; account < armedAction.length; account++) {
            if (armedAction[account] == action) {
                disarm(account);
            }
        }
    }
}
