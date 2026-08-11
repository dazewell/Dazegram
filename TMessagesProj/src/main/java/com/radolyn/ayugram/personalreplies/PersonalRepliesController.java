package com.radolyn.ayugram.personalreplies;

import android.text.TextUtils;
import android.util.SparseArray;
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
    /**
     * Exact set of child ids already reflected in the matching entry of
     * {@link #counts}, keyed by parent id: either applied directly by
     * {@link #increment}, or folded in by {@link #runRequest} when a reply
     * arrived while that parent's query was in flight. Checking exact
     * membership rather than a highest-seen id is what lets two distinct
     * replies be told apart from one reply announced twice, regardless of
     * which order their ids arrive in: {@code messageReceivedByServer} posts
     * twice for the same send, but two different outgoing replies can also
     * acknowledge out of id order, and neither case can be answered by
     * comparing against a maximum.
     */
    private final SparseArray<ArrayList<Integer>> appliedChildIds = new SparseArray<>();
    /** Ids of the query currently on the storage queue, or null when nothing is outstanding. */
    private ArrayList<Integer> inFlightIds;
    /**
     * Exact child ids of replies that landed for a parent in
     * {@link #inFlightIds} while its query was still running, reconciled in
     * {@link #runRequest} against the exact set of child ids that query
     * itself scanned - not against a maximum, and not by discarding the
     * query's result outright. Either of those breaks depending on which
     * side of a race wins: a producer can enqueue the write for one of these
     * ids before or after the query actually runs on the storage queue
     * (confirmed on {@code TL_updateShortMessage}, which posts the UI
     * notification before queuing its own write), so there is no ordering
     * this class can rely on to say whether the query already saw a given
     * id. Set membership sidesteps the question entirely: an id the query's
     * own result already contains is not added again, and one it does not
     * contain is - correct regardless of which thread got there first, and
     * every completed query commits a value instead of ever being thrown
     * away, so a run of replies to one busy parent can't stall its count
     * indefinitely.
     */
    private final SparseArray<ArrayList<Integer>> inFlightChildIds = new SparseArray<>();
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
            appliedChildIds.clear();
            inFlightIds = null;
            inFlightChildIds.clear();
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
    private void increment(long dialogId, int parentId, int childId) {
        if (dialogId != cachedDialogId || parentId <= 0 || childId <= 0) {
            return;
        }
        if (inFlightIds != null && inFlightIds.contains(parentId)) {
            // the query for this parent is already on the storage queue and may or may not
            // already reflect this exact reply, depending on which side of the race won (see
            // runRequest and inFlightChildIds); record the exact id here rather than guess, so
            // its completion can tell "the query already had this one" apart from "genuinely
            // missed it" by set membership instead of by ordering
            ArrayList<Integer> arrived = inFlightChildIds.get(parentId);
            if (arrived == null) {
                arrived = new ArrayList<>();
                inFlightChildIds.put(parentId, arrived);
            }
            if (arrived.size() < PersonalRepliesStorage.THREAD_LIMIT && !arrived.contains(childId)) {
                arrived.add(childId);
            }
            return;
        }
        int index = counts.indexOfKey(parentId);
        if (index < 0) {
            return;
        }
        int current = counts.valueAt(index);
        if (current >= PersonalRepliesStorage.THREAD_LIMIT) {
            // already clamped for display; nothing left to apply, and once a parent's count can
            // never rise again there is no point remembering any more ids against it - otherwise
            // an active thread that outlives THREAD_LIMIT would grow appliedChildIds forever
            return;
        }
        ArrayList<Integer> applied = appliedChildIds.get(parentId);
        if (applied != null && applied.contains(childId)) {
            // this exact reply already bumped the cached value once; this is what keeps
            // messageReceivedByServer's double post per send (see didReceivedNotification) from
            // counting the same reply twice, and does so regardless of what other child ids have
            // or haven't been applied since, unlike a highest-seen-id comparison
            return;
        }
        if (applied == null) {
            applied = new ArrayList<>();
            appliedChildIds.put(parentId, applied);
        }
        applied.add(childId);
        counts.put(parentId, current + 1);
        NotificationCenter.getInstance(currentAccount)
                .postNotificationName(NotificationCenter.personalRepliesCountsUpdated, dialogId);
    }

    private void scheduleRequest() {
        // a query already on the storage queue owns inFlightIds/inFlightChildIds until its
        // continuation runs; starting a second one here would let it clobber both, dropping
        // whatever the first query's in-flight replies were tracking (see runRequest's retrigger
        // below)
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
        inFlightChildIds.clear();
        MessagesStorage.getInstance(currentAccount).getStorageQueue().postRunnable(() -> {
            SparseIntArray found = new SparseIntArray();
            SparseArray<ArrayList<Integer>> foundChildIds = new SparseArray<>();
            PersonalRepliesStorage.countReplies(currentAccount, dialogId, ids, found, foundChildIds);
            AndroidUtilities.runOnUIThread(() -> {
                // a dialog switch or an invalidate() supersedes an outstanding query by nulling
                // inFlightIds without waiting for it (see count() and invalidate()), so a newer
                // request, or nothing at all, can already own this state by the time this runs; a
                // stale completion must not touch state that belongs to whatever superseded it -
                // including inFlightChildIds, which a newer request may already be populating
                if (inFlightIds != ids) {
                    return;
                }
                inFlightIds = null;
                if (dialogId == cachedDialogId) {
                    if (counts.size() > CACHE_LIMIT) {
                        counts.clear();
                        appliedChildIds.clear();
                    }
                    boolean changed = false;
                    for (int i = 0; i < ids.size(); i++) {
                        int id = ids.get(i);
                        int value = found.get(id, 0);
                        ArrayList<Integer> queried = foundChildIds.get(id);
                        ArrayList<Integer> arrived = inFlightChildIds.get(id);
                        if (arrived != null) {
                            for (int j = 0, size = arrived.size(); j < size; j++) {
                                int childId = arrived.get(j);
                                // an id the query's own result already scanned must not be added
                                // a second time; one it doesn't contain genuinely wasn't seen and
                                // is added - membership decides this, not which side of the
                                // storage queue the write actually landed on relative to the query
                                if (queried == null || !queried.contains(childId)) {
                                    value++;
                                }
                            }
                        }
                        value = Math.min(PersonalRepliesStorage.THREAD_LIMIT, value);
                        if (value < PersonalRepliesStorage.THREAD_LIMIT) {
                            // seed appliedChildIds with every id this value is actually built
                            // from - both what the query itself counted and whatever arrived
                            // during its flight - so a later duplicate announcement for any of
                            // them (not just the ones that happened to arrive while this query
                            // was outstanding) lands on increment()'s ordinary cached-path check
                            // instead of bumping the same reply twice; skipped once the value is
                            // already capped, since increment() blocks on the cap before ever
                            // consulting this set
                            ArrayList<Integer> applied = appliedChildIds.get(id);
                            if (queried != null) {
                                for (int k = 0, size = queried.size(); k < size; k++) {
                                    int childId = queried.get(k);
                                    if (applied == null) {
                                        applied = new ArrayList<>();
                                        appliedChildIds.put(id, applied);
                                    }
                                    if (!applied.contains(childId)) {
                                        applied.add(childId);
                                    }
                                }
                            }
                            if (arrived != null) {
                                for (int j = 0, size = arrived.size(); j < size; j++) {
                                    int childId = arrived.get(j);
                                    if (applied == null) {
                                        applied = new ArrayList<>();
                                        appliedChildIds.put(id, applied);
                                    }
                                    if (!applied.contains(childId)) {
                                        applied.add(childId);
                                    }
                                }
                            }
                        }
                        if (counts.get(id, -1) != value) {
                            changed = true;
                        }
                        counts.put(id, value);
                        // count() doesn't know an id is already in flight, so a rebind that
                        // happened while this query was outstanding can have queued it right back
                        // into pending even though it's the exact id this completion just settled;
                        // drop it here or a redundant follow-up query for it would run next and
                        // unconditionally overwrite the value just reconciled above with a stale one
                        pending.delete(id);
                    }
                    if (changed) {
                        NotificationCenter.getInstance(currentAccount)
                                .postNotificationName(NotificationCenter.personalRepliesCountsUpdated, dialogId);
                    }
                }
                inFlightChildIds.clear();
                // anything still pending here is a genuinely new id: one requested for the first
                // time while this query was outstanding, since every id this query resolved was
                // just removed above. scheduleRequest bailed on it earlier (inFlightIds != null),
                // so start its request now
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
        // a query can still be outstanding even when counts/pending are both empty (every id
        // could be in flight); left alone, its result would land after this clear and repopulate
        // pre-delete/pre-clear data (see runRequest's identity check), so it has to be superseded
        // here rather than allowed to complete on its own
        boolean hadOutstandingQuery = inFlightIds != null;
        inFlightIds = null;
        inFlightChildIds.clear();
        if (counts.size() == 0 && pending.size() == 0 && !hadOutstandingQuery) {
            return;
        }
        counts.clear();
        pending.clear();
        appliedChildIds.clear();
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
        } else if (id == NotificationCenter.messageReceivedByServer) {
            onMessageAcked((Long) args[3], (TLRPC.Message) args[2], (Boolean) args[6]);
        }
    }

    /**
     * Bumps the parent's cached count for each newly arrived message that is
     * an eligible reply, instead of the old clear-then-requery: that is what
     * produced a visible drop to zero before every real value returned.
     *
     * <p>{@code didReceiveNewMessages} is also re-used to re-announce messages
     * that are not freshly received at all: {@code mode}/{@code scheduled}
     * filter out scheduled and quick-reply batches, {@code getId() <= 0} skips
     * a reply still using its local temp id (picked up separately once it has
     * a real one, see {@link #onMessageAcked}), {@code ayuDeleted} skips Ghost
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
                // exact per-parent membership tracking in increment() doesn't care what order
                // same-parent replies in this batch arrive in, unlike a highest-seen-id watermark
                increment(dialogId, owner.reply_to.reply_to_msg_id, message.getId());
            }
        }
    }

    /**
     * A reply sent from this device is announced through
     * {@code didReceiveNewMessages} with its local temp id, which
     * {@link #onNewMessages} deliberately skips – a still-negative id can't be
     * looked back up once the server assigns the real one. This is what
     * counts it once that happens. Every producer of this notification names
     * the dialog in its fourth argument (see e.g. the send-completion
     * callbacks in {@code SendMessagesHelper}), so unlike {@code
     * messagesDeleted} there is always somewhere to increment against; a
     * channel or secret-chat dialog id simply never matches
     * {@link #cachedDialogId}, which only private chats ever populate.
     *
     * <p>Ordinary sends post this notification twice for the same message
     * (once right after the send call returns, again after the local database
     * write finishes), and the id-correction path for a channel send (a
     * {@code TL_updateMessageID} update, handled in {@code
     * MessagesController#processUpdates}) posts it with a {@code null}
     * message object since it only has raw ids there, not the full message.
     * Neither needs special-casing here: {@link #increment}'s own exact
     * per-parent membership check absorbs the repeat post - and, unlike a
     * highest-seen-id comparison, also handles two distinct outgoing replies
     * to the same parent acknowledging out of id order - and a {@code null}
     * message is simply dropped.
     */
    private void onMessageAcked(long dialogId, TLRPC.Message message, boolean scheduled) {
        if (scheduled || message == null || message.ayuDeleted || message.id <= 0 || MessageObject.isEphemeral(message)) {
            return;
        }
        if (PersonalRepliesStorage.isReplyInDialog(message, dialogId)) {
            increment(dialogId, message.reply_to.reply_to_msg_id, message.id);
        }
    }
}
