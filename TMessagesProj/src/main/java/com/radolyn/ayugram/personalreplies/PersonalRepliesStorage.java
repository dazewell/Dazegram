package com.radolyn.ayugram.personalreplies;

import android.text.TextUtils;
import android.util.SparseIntArray;

import org.telegram.SQLite.SQLiteCursor;
import org.telegram.SQLite.SQLiteDatabase;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessageCustomParamsHelper;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.NativeByteBuffer;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_stories;

import java.util.ArrayList;
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
     * Ceiling on replies for one message, applied to both the count and the
     * view so the glyph, the menu label and the list can't disagree.
     */
    public static final int THREAD_LIMIT = 500;

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
     * Counts stored replies for each of {@code parentIds} in one pass.
     *
     * <p>The blob has to be parsed rather than counted in SQL: a message can
     * quote something from a different chat, and the parent id it stores then
     * belongs to that other peer's id space, where it can collide with a real
     * id in this chat. {@code reply_to_peer_id} is the only thing that tells
     * the two apart and it lives inside the serialized message.
     */
    static SparseIntArray countReplies(int account, long dialogId, ArrayList<Integer> parentIds) {
        SparseIntArray counts = new SparseIntArray();
        if (parentIds == null || parentIds.isEmpty()) {
            return counts;
        }
        SQLiteDatabase database = MessagesStorage.getInstance(account).getDatabase();
        if (database == null) {
            return counts;
        }
        SQLiteCursor cursor = null;
        try {
            cursor = database.queryFinalized(String.format(Locale.US,
                    "SELECT thread_reply_id, data FROM messages_v2 WHERE uid = %d AND thread_reply_id IN (%s) ORDER BY thread_reply_id, mid LIMIT %d",
                    dialogId, TextUtils.join(",", parentIds), CANDIDATE_LIMIT));
            while (cursor.next()) {
                int parentId = cursor.intValue(0);
                if (parentId == 0) {
                    continue;
                }
                NativeByteBuffer data = cursor.byteBufferValue(1);
                if (data == null) {
                    continue;
                }
                TLRPC.Message message = TLRPC.Message.TLdeserialize(data, data.readInt32(false), false);
                data.reuse();
                if (isReplyInDialog(message, dialogId)) {
                    counts.put(parentId, Math.min(THREAD_LIMIT, counts.get(parentId) + 1));
                }
            }
        } catch (Throwable t) {
            FileLog.e(t);
        } finally {
            if (cursor != null) {
                cursor.dispose();
            }
        }
        return counts;
    }

    /**
     * Loads the message being replied to plus its stored replies, oldest first.
     * Users and chats the messages point at are gathered so the caller can put
     * them into {@code MessagesController} before building message objects.
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
                    "SELECT m.read_state, m.data, m.send_state, m.mid, m.date, m.replydata, m.media, m.ttl, m.mention, m.imp, m.forwards, m.replies_data, m.custom_params "
                            + "FROM messages_v2 as m WHERE m.uid = %d AND (m.mid = %d OR m.thread_reply_id = %d) ORDER BY m.mid ASC LIMIT %d",
                    dialogId, topId, topId, THREAD_LIMIT + 1));            while (cursor.next()) {
                NativeByteBuffer data = cursor.byteBufferValue(1);
                if (data == null) {
                    continue;
                }
                TLRPC.Message message = TLRPC.Message.TLdeserialize(data, data.readInt32(false), false);
                if (message == null) {
                    data.reuse();
                    continue;
                }
                message.send_state = cursor.intValue(2);
                message.id = (int) cursor.longValue(3);
                if (message.id > 0 && message.send_state != 0 && message.send_state != 3) {
                    message.send_state = 0;
                }
                message.readAttachPath(data, selfId);
                data.reuse();
                if (message.id != topId && !isReplyInDialog(message, dialogId)) {
                    continue;
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
                result.add(message);
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
     * True when the message replies to something in this same dialog. A quote
     * of a message from another chat names that chat in
     * {@code reply_to_peer_id}, and its parent id means nothing here.
     */
    private static boolean isReplyInDialog(TLRPC.Message message, long dialogId) {
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
