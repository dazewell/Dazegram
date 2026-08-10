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
 * <p>The cache only ever holds the last dialog asked about, so it stays small.
 * A reply to an id already in the cache bumps that one count in place; the
 * whole per-dialog cache is only ever dropped for a change no single bump can
 * repair (a delete, a history clear, or switching to a different dialog).
 */
public final class PersonalRepliesController implements NotificationCenter.NotificationCenterDelegate {

    /** Guard against a very long scrollback filling the cache. */
    private static final int CACHE_LIMIT = 3000;

    private static final PersonalRepliesController[] instances = new PersonalRepliesController[UserConfig.MAX_ACCOUNT_COUNT];

    private final int currentAccount;
    private final SparseIntArray counts = new SparseIntArray();
    private final SparseIntArray pending = new SparseIntArray();
    /** Ids of the query currently on the storage queue, or null when nothing is outstanding. */
    private ArrayList<Integer> inFlightIds;
    /** Increments that landed for an in-flight id, applied on top of its query result. */
    private final SparseIntArray inFlightDeltas = new SparseIntArray();
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
            inFlightIds = null;
            inFlightDeltas.clear();
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

    /**
     * Bumps one cached count in place instead of clearing the whole dialog's
     * cache, so a new reply landing doesn't blank every visible glyph while it
     * is re-derived. Only ever touches an id already answered for: an absent
     * id is left for the ordinary lazy-load path in {@link #count} to pick up.
     */
    private void increment(long dialogId, int parentId) {
        if (dialogId != cachedDialogId || parentId <= 0) {
            return;
        }
        if (inFlightIds != null && inFlightIds.contains(parentId)) {
            // a query for this id is already on the storage queue; folding the bump into its
            // result (see runRequest) avoids counts.put racing the query and losing the +1
            inFlightDeltas.put(parentId, inFlightDeltas.get(parentId, 0) + 1);
            return;
        }
        int index = counts.indexOfKey(parentId);
        if (index < 0) {
            return;
        }
        int value = Math.min(PersonalRepliesStorage.THREAD_LIMIT, counts.valueAt(index) + 1);
        if (value == counts.valueAt(index)) {
            return;
        }
        counts.put(parentId, value);
        NotificationCenter.getInstance(currentAccount)
                .postNotificationName(NotificationCenter.personalRepliesCountsUpdated, dialogId);
    }

    private void scheduleRequest() {
        // a query already on the storage queue owns inFlightIds/inFlightDeltas until its
        // continuation runs; starting a second one here would let it clobber both, dropping
        // whatever the first query's deltas were tracking (see runRequest's retrigger below)
        if (requestScheduled || inFlightIds != null) {
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
        inFlightIds = ids;
        inFlightDeltas.clear();
        MessagesStorage.getInstance(currentAccount).getStorageQueue().postRunnable(() -> {
            SparseIntArray found = PersonalRepliesStorage.countReplies(currentAccount, dialogId, ids);
            AndroidUtilities.runOnUIThread(() -> {
                // a dialog switch nulls inFlightIds without waiting for this query (see count()),
                // so a newer request can already own inFlightIds/inFlightDeltas by the time this
                // runs; a stale completion must not touch state that belongs to that newer query
                if (inFlightIds != ids) {
                    return;
                }
                inFlightIds = null;
                if (dialogId == cachedDialogId) {
                    if (counts.size() > CACHE_LIMIT) {
                        counts.clear();
                    }
                    boolean changed = false;
                    for (int i = 0; i < ids.size(); i++) {
                        int id = ids.get(i);
                        // a bump that landed while this query was outstanding isn't in `found`
                        // yet, so fold it back in here rather than letting counts.put discard it
                        int value = Math.min(PersonalRepliesStorage.THREAD_LIMIT, found.get(id, 0) + inFlightDeltas.get(id, 0));
                        if (counts.get(id, -1) != value) {
                            changed = true;
                        }
                        counts.put(id, value);
                    }
                    if (changed) {
                        NotificationCenter.getInstance(currentAccount)
                                .postNotificationName(NotificationCenter.personalRepliesCountsUpdated, dialogId);
                    }
                }
                inFlightDeltas.clear();
                // a bind that queued new ids while this query was outstanding couldn't start its
                // own request (scheduleRequest bails while inFlightIds != null); pick it up now
                if (pending.size() > 0) {
                    scheduleRequest();
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
        // a query may still be outstanding even when counts/pending are both empty (every id
        // could be in flight); its result would otherwise fold a now-stale delta back in once
        // this returns early below, so this has to run unconditionally rather than after the guard
        inFlightDeltas.clear();
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
        if (id == NotificationCenter.didReceiveNewMessages) {
            //noinspection unchecked
            onNewMessages((Long) args[0], (ArrayList<MessageObject>) args[1], (Boolean) args[2], (Integer) args[3]);
        } else if (id == NotificationCenter.historyCleared) {
            invalidate((Long) args[0]);
        } else if (id == NotificationCenter.messagesDeleted) {
            long channelId = (Long) args[1];
            if (channelId > 0) {
                // a delete in a channel/supergroup (positive channelId) can never change a
                // private dialog's reply count; a zero or negative channelId (the latter is how
                // ephemeral deletes in a user dialog are encoded) still falls through below
                return;
            }
            // the payload names neither the message's dialog nor its parent, so there is nothing
            // to increment against; drop the whole cache and let it refill
            invalidate(0);
        }
        // messageReceivedByServer is deliberately not observed: it does carry the dialog id
        // (the fourth argument at every producer), but a message's own reply already increments
        // through didReceiveNewMessages above, so there is nothing left here worth a full clear
    }

    /**
     * Bumps the parent's cached count for each newly arrived message that is
     * an eligible reply, instead of the old clear-then-requery: that is what
     * produced a visible drop to zero before every real value returned.
     *
     * <p>{@code didReceiveNewMessages} is also re-used to re-announce messages
     * that are not freshly received at all: {@code mode}/{@code scheduled}
     * filter out scheduled and quick-reply batches, {@code getId() <= 0} skips
     * a reply still using its local temp id, {@code ayuDeleted} skips Ghost
     * Mode re-displaying a previously deleted message, and
     * {@link MessageObject#isEphemeral} skips the ephemeral-message replay –
     * none of those three ever land in {@code messages_v2}, so counting them
     * here would produce a value the SQL-backed query can never reproduce.
     */
    private void onNewMessages(long dialogId, ArrayList<MessageObject> messages, boolean scheduled, int mode) {
        if (scheduled || mode != 0 || messages == null || dialogId != cachedDialogId) {
            return;
        }
        for (int i = 0, size = messages.size(); i < size; i++) {
            MessageObject message = messages.get(i);
            TLRPC.Message owner = message != null ? message.messageOwner : null;
            if (owner == null || owner.ayuDeleted || message.getId() <= 0 || MessageObject.isEphemeral(owner)) {
                continue;
            }
            if (PersonalRepliesStorage.isReplyInDialog(owner, dialogId)) {
                increment(dialogId, owner.reply_to.reply_to_msg_id);
            }
        }
    }
}
