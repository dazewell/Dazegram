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
}
