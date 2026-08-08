package com.radolyn.ayugram.personalreplies;

import android.text.TextUtils;
import android.util.SparseIntArray;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;

import xyz.nextalone.nagram.NaConfig;

/**
 * Keeps the locally derived reply counts for the private chat currently being
 * looked at, one instance per account.
 *
 * <p>Counts are pulled lazily: a cell asks for one, and an id nobody has
 * answered for yet goes into a pending set that gets resolved in a single
 * coalesced query. That way the work follows what is actually on screen, with
 * no page-load hooks and nothing scanning a whole chat.
 *
 * <p>The cache only ever holds the last dialog asked about, so it stays small,
 * and it is wiped whenever the history underneath it moves (new message,
 * delete, clear, or a sent message getting its server id).
 */
public final class PersonalRepliesController implements NotificationCenter.NotificationCenterDelegate {

    /** Guard against a very long scrollback filling the cache. */
    private static final int CACHE_LIMIT = 3000;

    private static final PersonalRepliesController[] instances = new PersonalRepliesController[UserConfig.MAX_ACCOUNT_COUNT];

    private final int currentAccount;
    private final SparseIntArray counts = new SparseIntArray();
    private final SparseIntArray pending = new SparseIntArray();
    private long cachedDialogId;
    private boolean requestScheduled;

    private PersonalRepliesController(int account) {
        currentAccount = account;
        // the cell bind path is what creates this, so registration has to be posted rather than
        // run inline: NotificationCenter only accepts observers from the main thread
        AndroidUtilities.runOnUIThread(() -> {
            NotificationCenter center = NotificationCenter.getInstance(currentAccount);
            center.addObserver(this, NotificationCenter.didReceiveNewMessages);
            center.addObserver(this, NotificationCenter.messagesDeleted);
            center.addObserver(this, NotificationCenter.historyCleared);
            center.addObserver(this, NotificationCenter.messageReceivedByServer);
        });
    }

    public static synchronized PersonalRepliesController getInstance(int account) {
        PersonalRepliesController instance = instances[account];
        if (instance == null) {
            instance = instances[account] = new PersonalRepliesController(account);
        }
        return instance;
    }

    public static boolean enabled() {
        return NaConfig.INSTANCE.getPersonalReplies().Bool();
    }

    /**
     * Ordinary one-to-one cloud chats only. Bots, Saved Messages, the Replies
     * service peer, groups and channels all either have their own thread UI or
     * no meaningful notion of a private reply. Secret chats fall out for free:
     * their dialog ids are encrypted-encoded, never a plain positive user id.
     */
    public static boolean isEligibleDialog(int account, long dialogId) {
        if (!enabled() || dialogId <= 0 || DialogObject.isEncryptedDialog(dialogId)) {
            return false;
        }
        if (dialogId == UserConfig.getInstance(account).getClientUserId() || UserObject.isReplyUser(dialogId)) {
            return false;
        }
        TLRPC.User user = MessagesController.getInstance(account).getUser(dialogId);
        return user != null && !user.bot && !user.deleted;
    }

    /** Locally derived reply count for a message, 0 while unknown or ineligible. */
    public static int getCount(int account, MessageObject message) {
        if (message == null || message.messageOwner == null || message.getId() <= 0 || message.scheduled) {
            return 0;
        }
        long dialogId = message.getDialogId();
        if (!isEligibleDialog(account, dialogId)) {
            return 0;
        }
        return getInstance(account).count(dialogId, message.getId());
    }

    private int count(long dialogId, int messageId) {
        if (dialogId != cachedDialogId) {
            cachedDialogId = dialogId;
            counts.clear();
            pending.clear();
        }
        int index = counts.indexOfKey(messageId);
        if (index >= 0) {
            return counts.valueAt(index);
        }
        if (pending.indexOfKey(messageId) < 0) {
            pending.put(messageId, 1);
            scheduleRequest();
        }
        return 0;
    }

    private void scheduleRequest() {
        if (requestScheduled) {
            return;
        }
        requestScheduled = true;
        // let the rest of the pass queue its ids before going to disk, so one
        // screenful of cells costs one query rather than one query per cell
        AndroidUtilities.runOnUIThread(this::runRequest);
    }

    private void runRequest() {
        requestScheduled = false;
        if (pending.size() == 0) {
            return;
        }
        final long dialogId = cachedDialogId;
        final ArrayList<Integer> ids = new ArrayList<>(pending.size());
        for (int i = 0; i < pending.size(); i++) {
            ids.add(pending.keyAt(i));
        }
        pending.clear();
        MessagesStorage.getInstance(currentAccount).getStorageQueue().postRunnable(() -> {
            SparseIntArray found = PersonalRepliesStorage.countReplies(currentAccount, dialogId, ids);
            AndroidUtilities.runOnUIThread(() -> {
                if (dialogId != cachedDialogId) {
                    return;
                }
                if (counts.size() > CACHE_LIMIT) {
                    counts.clear();
                }
                boolean changed = false;
                for (int i = 0; i < ids.size(); i++) {
                    int id = ids.get(i);
                    int value = found.get(id, 0);
                    if (counts.get(id, -1) != value) {
                        changed = true;
                    }
                    counts.put(id, value);
                }
                if (changed) {
                    NotificationCenter.getInstance(currentAccount)
                            .postNotificationName(NotificationCenter.personalRepliesCountsUpdated, dialogId);
                }
            });
        });
    }

    /**
     * Loads the message plus its stored replies for the in-place reply view,
     * oldest first, and hands back ready-to-render message objects.
     */
    public static void loadThread(int account, long dialogId, int topId, Utilities.Callback<ArrayList<MessageObject>> onDone) {
        MessagesStorage.getInstance(account).getStorageQueue().postRunnable(() -> {
            ArrayList<Long> usersToLoad = new ArrayList<>();
            ArrayList<Long> chatsToLoad = new ArrayList<>();
            ArrayList<TLRPC.Message> loaded = PersonalRepliesStorage.loadThread(account, dialogId, topId, usersToLoad, chatsToLoad);
            ArrayList<TLRPC.User> users = new ArrayList<>();
            ArrayList<TLRPC.Chat> chats = new ArrayList<>();
            try {
                if (!usersToLoad.isEmpty()) {
                    MessagesStorage.getInstance(account).getUsersInternal(usersToLoad, users);
                }
                if (!chatsToLoad.isEmpty()) {
                    MessagesStorage.getInstance(account).getChatsInternal(TextUtils.join(",", chatsToLoad), chats);
                }
            } catch (Exception e) {
                FileLog.e(e);
            }
            AndroidUtilities.runOnUIThread(() -> {
                MessagesController controller = MessagesController.getInstance(account);
                controller.putUsers(users, true);
                controller.putChats(chats, true);
                ArrayList<MessageObject> result = new ArrayList<>(loaded.size());
                for (int i = 0; i < loaded.size(); i++) {
                    result.add(new MessageObject(account, loaded.get(i), true, true));
                }
                onDone.run(result);
            });
        });
    }

    /** Drops what we know so the next bind re-reads it. */
    public void invalidate(long dialogId) {
        if (cachedDialogId == 0 || dialogId != 0 && dialogId != cachedDialogId) {
            return;
        }
        if (counts.size() == 0 && pending.size() == 0) {
            return;
        }
        counts.clear();
        pending.clear();
        NotificationCenter.getInstance(currentAccount)
                .postNotificationName(NotificationCenter.personalRepliesCountsUpdated, cachedDialogId);
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.didReceiveNewMessages || id == NotificationCenter.historyCleared) {
            invalidate((Long) args[0]);
        } else if (id == NotificationCenter.messagesDeleted || id == NotificationCenter.messageReceivedByServer) {
            // neither payload names the private dialog it belongs to, and the
            // cache only ever holds one, so drop it and let it refill
            invalidate(0);
        }
    }
}
