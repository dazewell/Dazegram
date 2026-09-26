package com.radolyn.ayugram.personalreplies;

import android.text.TextUtils;
import android.util.SparseArray;
import android.util.SparseIntArray;

import org.telegram.SQLite.SQLiteCursor;
import org.telegram.SQLite.SQLiteDatabase;
import org.telegram.SQLite.SQLiteException;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessageCustomParamsHelper;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.NativeByteBuffer;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_stories;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Locale;

/**
 * Reads reply relationships for private chats straight out of the message cache.
 *
 * <p>Every stored message already carries its direct parent in
 * {@code messages_v2.thread_reply_id}
 * ({@code reply_to_top_id != 0 ? reply_to_top_id : reply_to_msg_id}). Private
 * chats never have a top id, so for them that column is exactly the message
 * being replied to. Nothing has to be backfilled or mirrored into a second
 * table: the only derived state is one index, and it can be dropped and
 * recreated at any time.
 *
 * <p>Everything here runs on {@link MessagesStorage#getStorageQueue()}.
 */
public final class PersonalRepliesStorage {

    private static final String INDEX_NAME = "nax_thread_reply_idx_messages_v2";

    /** Ceiling on rows examined for one page of counts. */
    private static final int CANDIDATE_LIMIT = 4000;

    /**
     * Ceiling on the direct replies counted for one message, and on the
     * messages the thread view loads below it. The two numbers measure
     * different things (the view walks the whole reply tree), so the glyph and
     * menu label can be lower than what the view shows.
     */
    public static final int THREAD_LIMIT = 500;

    /** Backstop on the queries one thread walk may put on the storage queue. */
    private static final int THREAD_QUERY_LIMIT = 1000;

    /** Ceiling on the parents one upward walk may look up, so a malformed chain can't hold the storage queue. */
    private static final int THREAD_ANCESTOR_LIMIT = 500;

    private PersonalRepliesStorage() {}

    /**
     * Asserts the index the count query rides on. Idempotent, so it can run on
     * every open: a fresh install, an install that predates the feature, or a
     * database that was just rebuilt after corruption.
     *
     * <p>A failure here is survivable (the queries still return the right
     * answer without the index, just slower), so it must never take
     * {@code openDatabase} down with it.
     */
    public static void ensureIndex(SQLiteDatabase database) {
        if (database == null) {
            return;
        }
        try {
            database.executeFast("CREATE INDEX IF NOT EXISTS " + INDEX_NAME
                    + " ON messages_v2(uid, thread_reply_id) WHERE thread_reply_id != 0;").stepThis().dispose();
        } catch (Throwable t) {
            FileLog.e(t);
        }
    }

    /**
     * Counts stored replies for each of {@code parentIds} in one pass, and
     * separately reports the exact set of child {@code mid}s this pass
     * counted for each parent.
     *
     * <p>The blob has to be parsed rather than counted in SQL: a message can
     * quote something from a different chat, and the parent id it stores then
     * belongs to that other peer's id space, where it can collide with a real
     * id in this chat. {@code reply_to_peer_id} is the only thing that tells
     * the two apart and it lives inside the serialized message.
     *
     * <p>{@code childIdsOut} exists because this query runs on the storage
     * queue and can be overtaken by a write for a reply that arrives while it
     * is still outstanding (see
     * {@link PersonalRepliesController#runRequest}): the caller needs to know
     * exactly which child ids this pass already counted, not just how many,
     * to tell "already reflected in the count above" apart from "genuinely
     * missed it" for one specific id without a second query. A count or a
     * highest-seen id can't answer that - only membership in the actual set
     * this pass scanned can.
     */
    static void countReplies(int account, long dialogId, ArrayList<Integer> parentIds, SparseIntArray counts,
                              SparseArray<ArrayList<Integer>> childIdsOut) {
        if (parentIds == null || parentIds.isEmpty()) {
            return;
        }
        SQLiteDatabase database = MessagesStorage.getInstance(account).getDatabase();
        if (database == null) {
            return;
        }
        SQLiteCursor cursor = null;
        try {
            cursor = database.queryFinalized(String.format(Locale.US,
                    "SELECT thread_reply_id, data, mid FROM messages_v2 WHERE uid = %d AND thread_reply_id IN (%s) ORDER BY thread_reply_id, mid LIMIT %d",
                    dialogId, TextUtils.join(",", parentIds), CANDIDATE_LIMIT));
            while (cursor.next()) {
                int parentId = cursor.intValue(0);
                if (parentId == 0) {
                    continue;
                }
                if (counts.get(parentId, 0) >= THREAD_LIMIT) {
                    // already capped for this parent - deserializing another row for it can't
                    // change either the displayed count or the reported child-id set, so skip
                    // the parse entirely instead of doing it for nothing
                    continue;
                }
                NativeByteBuffer data = cursor.byteBufferValue(1);
                if (data == null) {
                    continue;
                }
                TLRPC.Message message = TLRPC.Message.TLdeserialize(data, data.readInt32(false), false);
                data.reuse();
                if (isReplyInDialog(message, dialogId)) {
                    int updated = Math.min(THREAD_LIMIT, counts.get(parentId) + 1);
                    counts.put(parentId, updated);
                    if (updated >= THREAD_LIMIT) {
                        // already clamped for display; no point recording any more ids scanned
                        // for this parent, or a long-lived thread past THREAD_LIMIT would grow
                        // childIdsOut without bound for the rest of the scan
                        continue;
                    }
                    ArrayList<Integer> childIds = childIdsOut.get(parentId);
                    if (childIds == null) {
                        childIds = new ArrayList<>();
                        childIdsOut.put(parentId, childIds);
                    }
                    childIds.add((int) cursor.longValue(2));
                }
            }
        } catch (Throwable t) {
            FileLog.e(t);
        } finally {
            if (cursor != null) {
                cursor.dispose();
            }
        }
    }

    /** Columns every thread query reads, in the order {@link #readRow} expects them. */
    private static final String THREAD_COLUMNS = "m.read_state, m.data, m.send_state, m.mid, m.date, m.replydata, m.media, m.ttl, "
            + "m.mention, m.imp, m.forwards, m.replies_data, m.custom_params, m.thread_reply_id";

    /**
     * Loads the message being replied to plus every stored reply below it: its
     * replies, their replies, and so on. The root comes first, then one level at
     * a time, oldest first within a level, so when {@link #THREAD_LIMIT} cuts the
     * walk short every message kept still has its parent in the list. Users and
     * chats the messages point at are gathered so the caller can put them into
     * {@code MessagesController} before building message objects.
     *
     * <p>Indexed queries a level at a time rather than a recursive SQL query: whether a
     * row really replies to something in this chat is only settled by its
     * serialized {@code reply_to} (see {@link #countReplies}), and SQL would have
     * already descended into a wrong row's replies before anything could reject it.
     */
    static ArrayList<TLRPC.Message> loadThread(int account, long dialogId, int topId,
                                               ArrayList<Long> usersToLoad, ArrayList<Long> chatsToLoad) {
        ArrayList<TLRPC.Message> result = new ArrayList<>();
        SQLiteDatabase database = MessagesStorage.getInstance(account).getDatabase();
        if (database == null) {
            return result;
        }
        long selfId = UserConfig.getInstance(account).getClientUserId();
        SQLiteCursor cursor = null;
        try {
            cursor = database.queryFinalized(String.format(Locale.US,
                    "SELECT " + THREAD_COLUMNS + " FROM messages_v2 as m WHERE m.uid = %d AND m.mid = %d", dialogId, topId));
            TLRPC.Message root = cursor.next() ? readRow(cursor, dialogId, selfId, false, usersToLoad, chatsToLoad) : null;
            cursor.dispose();
            cursor = null;
            if (root == null) {
                return result;
            }
            result.add(root);

            HashSet<Integer> visited = new HashSet<>();
            visited.add(topId);
            ArrayList<Integer> frontier = new ArrayList<>();
            frontier.add(topId);
            int remaining = THREAD_LIMIT;
            int queries = 0;
            // each page either moves past the rows it read or finishes its level, and a level only
            // continues the walk with ids it hasn't visited, so it can't outlast the cap anyway; the query
            // bound is there so no shape of stored data can keep the storage queue, and every other query
            // waiting on it, busy
            while (!frontier.isEmpty() && remaining > 0 && queries < THREAD_QUERY_LIMIT) {
                String parents = TextUtils.join(",", frontier);
                ArrayList<Integer> next = new ArrayList<>();
                // rows the checks below reject still take up LIMIT slots, so page through the level until it
                // runs dry rather than moving on after one short read and losing the replies sorted after them
                long lastMid = Integer.MIN_VALUE - 1L;
                boolean more = true;
                while (more && remaining > 0 && queries < THREAD_QUERY_LIMIT) {
                    int limit = remaining;
                    queries++;
                    // the explicit != 0 is what lets SQLite pick the partial index, whose own WHERE it has to see
                    cursor = database.queryFinalized(String.format(Locale.US,
                            "SELECT " + THREAD_COLUMNS + " FROM messages_v2 as m WHERE m.uid = %d AND m.thread_reply_id != 0 AND m.thread_reply_id IN (%s) AND m.mid > %d ORDER BY m.mid ASC LIMIT %d",
                            dialogId, parents, lastMid, limit));
                    int read = 0;
                    while (cursor.next()) {
                        read++;
                        int id = (int) cursor.longValue(3);
                        lastMid = id;
                        if (visited.contains(id)) {
                            continue;
                        }
                        TLRPC.Message message = readRow(cursor, dialogId, selfId, true, usersToLoad, chatsToLoad);
                        if (message == null) {
                            continue;
                        }
                        visited.add(id);
                        next.add(id);
                        result.add(message);
                        remaining--;
                    }
                    cursor.dispose();
                    cursor = null;
                    more = read == limit;
                }
                frontier = next;
            }
        } catch (Throwable t) {
            FileLog.e(t);
        } finally {
            if (cursor != null) {
                cursor.dispose();
            }
        }
        return result;
    }

    /**
     * Climbs from {@code messageId} through the parents it replies to and returns
     * the highest one stored on this device: the first that isn't a reply in this
     * chat, or whose parent isn't stored. Returns {@code messageId} itself when
     * that isn't stored either.
     *
     * <p>Each step is held to the same rule {@link #readRow} applies on the way
     * down (a reply in this dialog whose {@code reply_to_msg_id} matches its
     * {@code thread_reply_id}), so {@link #loadThread} from the returned top
     * walks the same edges back to {@code messageId}.
     */
    static int findThreadTop(int account, long dialogId, int messageId) {
        SQLiteDatabase database = MessagesStorage.getInstance(account).getDatabase();
        if (database == null) {
            return messageId;
        }
        int top = messageId;
        int id = messageId;
        HashSet<Integer> visited = new HashSet<>();
        visited.add(messageId);
        SQLiteCursor cursor = null;
        try {
            for (int steps = 0; steps < THREAD_ANCESTOR_LIMIT; steps++) {
                cursor = database.queryFinalized(String.format(Locale.US,
                        "SELECT data, thread_reply_id FROM messages_v2 WHERE uid = %d AND mid = %d", dialogId, id));
                TLRPC.Message message = null;
                int threadReplyId = 0;
                if (cursor.next()) {
                    NativeByteBuffer data = cursor.byteBufferValue(0);
                    if (data != null) {
                        message = TLRPC.Message.TLdeserialize(data, data.readInt32(false), false);
                        data.reuse();
                    }
                    threadReplyId = cursor.intValue(1);
                }
                cursor.dispose();
                cursor = null;
                if (message == null) {
                    break;
                }
                top = id;
                if (!isReplyInDialog(message, dialogId) || message.reply_to.reply_to_msg_id != threadReplyId) {
                    break;
                }
                id = threadReplyId;
                if (!visited.add(id)) {
                    break;
                }
            }
        } catch (Throwable t) {
            FileLog.e(t);
        } finally {
            if (cursor != null) {
                cursor.dispose();
            }
        }
        return top;
    }

    /**
     * Rebuilds one stored message from a {@link #THREAD_COLUMNS} row. With
     * {@code asReply} set, a row that isn't really a reply to the parent it was
     * found under comes back null.
     */
    private static TLRPC.Message readRow(SQLiteCursor cursor, long dialogId, long selfId, boolean asReply,
                                         ArrayList<Long> usersToLoad, ArrayList<Long> chatsToLoad) throws SQLiteException {
        NativeByteBuffer data = cursor.byteBufferValue(1);
        if (data == null) {
            return null;
        }
        TLRPC.Message message = TLRPC.Message.TLdeserialize(data, data.readInt32(false), false);
        if (message == null) {
            data.reuse();
            return null;
        }
        message.send_state = cursor.intValue(2);
        message.id = (int) cursor.longValue(3);
        if (message.id > 0 && message.send_state != 0 && message.send_state != 3) {
            message.send_state = 0;
        }
        message.readAttachPath(data, selfId);
        data.reuse();
        // the column is only the direct parent while the chat has no top ids, so hold it against
        // the message itself: one wrong edge here would pull a whole wrong branch in under it
        if (asReply && (!isReplyInDialog(message, dialogId) || message.reply_to.reply_to_msg_id != cursor.intValue(13))) {
            return null;
        }
        MessageObject.setUnreadFlags(message, cursor.intValue(0));
        message.date = cursor.intValue(4);
        message.dialog_id = dialogId;
        if ((message.flags & TLRPC.MESSAGE_FLAG_HAS_VIEWS) != 0) {
            message.views = cursor.intValue(6);
            message.forwards = cursor.intValue(10);
        }
        NativeByteBuffer repliesData = cursor.byteBufferValue(11);
        if (repliesData != null) {
            TLRPC.MessageReplies replies = TLRPC.MessageReplies.TLdeserialize(repliesData, repliesData.readInt32(false), false);
            if (replies != null) {
                message.replies = replies;
            }
            repliesData.reuse();
        }
        if (message.ttl == 0) {
            message.ttl = cursor.intValue(7);
        }
        if (cursor.intValue(8) != 0) {
            message.mentioned = true;
        }
        int flags = cursor.intValue(9);
        if ((flags & 1) != 0) {
            message.stickerVerified = 0;
        } else if ((flags & 2) != 0) {
            message.stickerVerified = 2;
        }
        NativeByteBuffer customParams = cursor.byteBufferValue(12);
        if (customParams != null) {
            MessageCustomParamsHelper.readLocalParams(message, customParams);
            customParams.reuse();
        }
        if (message.reply_to != null && !cursor.isNull(5)) {
            NativeByteBuffer replyData = cursor.byteBufferValue(5);
            if (replyData != null) {
                if (message.reply_to.reply_to_msg_id != 0) {
                    message.replyMessage = TLRPC.Message.TLdeserialize(replyData, replyData.readInt32(false), false);
                    if (message.replyMessage != null) {
                        message.replyMessage.readAttachPath(replyData, selfId);
                        MessagesStorage.addUsersAndChatsFromMessage(message.replyMessage, usersToLoad, chatsToLoad, null);
                    }
                } else if (message.reply_to.story_id != 0) {
                    // the same blob holds the story a message replies to, and the top message can be one
                    message.replyStory = TL_stories.StoryItem.TLdeserialize(replyData, replyData.readInt32(false), false);
                }
                replyData.reuse();
            }
        }
        MessagesStorage.addUsersAndChatsFromMessage(message, usersToLoad, chatsToLoad, null);
        return message;
    }

    /**
     * True when the message replies to something in this same dialog. A quote
     * of a message from another chat names that chat in
     * {@code reply_to_peer_id}, and its parent id means nothing here.
     *
     * <p>Package-private so {@link PersonalRepliesController} can reuse the
     * exact same eligibility check when deciding whether a freshly arrived
     * message should increment a cached count instead of leaving that to a
     * hand-rolled "has reply_to" test.
     */
    static boolean isReplyInDialog(TLRPC.Message message, long dialogId) {
        if (message == null || message.reply_to == null || message.reply_to.reply_to_msg_id == 0) {
            return false;
        }
        // a pin names the message it pins in reply_to, so it inherits its thread_reply_id and lands
        // in the same bucket as real replies; no service message is ever one, so drop the whole class
        // rather than the actions that happen to carry reply_to today
        if (message instanceof TLRPC.TL_messageService || message.action != null) {
            return false;
        }
        if (message.reply_to.reply_to_peer_id == null) {
            return true;
        }
        return MessageObject.getPeerId(message.reply_to.reply_to_peer_id) == dialogId;
    }
}
