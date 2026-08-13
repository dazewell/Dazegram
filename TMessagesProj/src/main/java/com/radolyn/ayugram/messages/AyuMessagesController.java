/*
 * This is the source code of AyuGram for Android.
 *
 * We do not and cannot prevent the use of our code,
 * but be respectful and credit the original author.
 *
 * Copyright @Radolyn, 2023
 */

package com.radolyn.ayugram.messages;

import android.os.Environment;
import android.text.TextUtils;

import com.radolyn.ayugram.AyuConstants;
import com.radolyn.ayugram.AyuUtils;
import com.radolyn.ayugram.database.AyuData;
import com.radolyn.ayugram.database.dao.DeletedMessageDao;
import com.radolyn.ayugram.database.dao.EditedMessageDao;
import com.radolyn.ayugram.database.entities.DeletedMessage;
import com.radolyn.ayugram.database.entities.DeletedMessageFull;
import com.radolyn.ayugram.database.entities.DeletedMessageReaction;
import com.radolyn.ayugram.database.entities.EditedMessage;
import com.radolyn.ayugram.proprietary.AyuMessageUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.TLRPC;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

import tw.nekomimi.nekogram.utils.FileUtil;

public class AyuMessagesController {
    public static final String attachmentsSubfolder = "Saved Attachments";
    public static final File attachmentsPath = new File(new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), AyuConstants.APP_NAME), attachmentsSubfolder);
    private static AyuMessagesController instance;
    private EditedMessageDao editedMessageDao;
    private DeletedMessageDao deletedMessageDao;

    private AyuMessagesController() {
        initializeAttachmentsFolder();
        AyuSavePreferences.loadAllExclusions();

        refreshDaos();
    }

    public void refreshDaos() {
        editedMessageDao = AyuData.getEditedMessageDao();
        deletedMessageDao = AyuData.getDeletedMessageDao();
    }

    private <T> T withDaoRetry(String tag, Callable<T> callable) {
        try {
            return callable.call();
        } catch (Exception e) {
            FileLog.e(tag, e);
        }

        try {
            refreshDaos();
            return callable.call();
        } catch (Exception e) {
            FileLog.e(tag, e);
        }

        return null;
    }

    private static void initializeAttachmentsFolder() {
        try {
            File nomediaFile = new File(attachmentsPath, ".nomedia");
            if (attachmentsPath.exists() || attachmentsPath.mkdirs()) {
                AndroidUtilities.createEmptyFile(nomediaFile);
            }
            if (!nomediaFile.exists()) {
                File randomFile = new File(attachmentsPath, AyuUtils.generateRandomString(4));
                AndroidUtilities.createEmptyFile(randomFile);
                if (!randomFile.renameTo(nomediaFile)) {
                    if (!randomFile.delete()) {
                        randomFile.deleteOnExit();
                    }
                    FileLog.e("Failed to rename random .nomedia file to the correct name");
                } else {
                    FileLog.d("Created .nomedia file in attachments folder by renaming a random file");
                }
            } else {
                FileLog.d(".nomedia file already exists in attachments folder");
            }
        } catch (Exception e) {
            FileLog.e("initializeAttachmentsFolder", e);
        }
    }

    public static AyuMessagesController getInstance() {
        if (instance == null) {
            instance = new AyuMessagesController();
        }
        return instance;
    }

    public void onMessageEdited(AyuSavePreferences prefs, TLRPC.Message newMessage) {
        Utilities.globalQueue.postRunnable(() -> {
            try {
                onMessageEditedInner(prefs, newMessage, false);
            } catch (Exception e) {
                FileLog.e("onMessageEdited", e);
            }
        });
    }

    public void onMessageEditedForce(AyuSavePreferences prefs) {
        Utilities.globalQueue.postRunnable(() -> {
            try {
                onMessageEditedInner(prefs, prefs.getMessage(), true);
            } catch (Exception e) {
                FileLog.e("onMessageEditedForce", e);
            }
        });
    }

    private void onMessageEditedInner(AyuSavePreferences prefs, TLRPC.Message newMessage, boolean force) {
        var oldMessage = prefs.getMessage();

        boolean sameMedia = isSameMedia(newMessage, force, oldMessage);

        if (sameMedia && TextUtils.equals(oldMessage.message, newMessage.message)) {
            return;
        }

        var revision = new EditedMessage();
        AyuMessageUtils.map(prefs, revision);
        AyuMessageUtils.mapMedia(prefs, revision, !sameMedia, force);

        if (!sameMedia && !TextUtils.isEmpty(revision.mediaPath)) {
            var lastRevision = withDaoRetry(
                    "onMessageEditedInner#getLastRevision",
                    () -> editedMessageDao.getLastRevision(prefs.getUserId(), prefs.getDialogId(), prefs.getMessageId())
            );

            if (lastRevision != null && !TextUtils.equals(revision.mediaPath, lastRevision.mediaPath) && lastRevision.mediaPath != null && !lastRevision.mediaPath.contains(attachmentsSubfolder)) {
                // update previous revisions to reflect media change
                // like, there's no previous file, so replace it with one we copied before...
                withDaoRetry(
                        "onMessageEditedInner#updateAttachmentForRevisionsBetweenDates",
                        () -> {
                            editedMessageDao.updateAttachmentForRevisionsBetweenDates(prefs.getUserId(), prefs.getDialogId(), prefs.getMessageId(), lastRevision.mediaPath, revision.mediaPath);
                            return null;
                        }
                );
            }
        }

        withDaoRetry(
                "onMessageEditedInner#insert",
                () -> {
                    editedMessageDao.insert(revision);
                    return null;
                }
        );

        AndroidUtilities.runOnUIThread(() -> NotificationCenter.getInstance(prefs.getAccountId()).postNotificationName(AyuConstants.MESSAGE_EDITED_NOTIFICATION, prefs.getDialogId(), prefs.getMessageId()));
    }

    private static boolean isSameMedia(TLRPC.Message newMessage, boolean force, TLRPC.Message oldMessage) {
        boolean sameMedia = oldMessage.media == newMessage.media ||
                (oldMessage.media != null && newMessage.media != null && oldMessage.media.getClass() == newMessage.media.getClass());
        if (oldMessage.media instanceof TLRPC.TL_messageMediaPhoto && newMessage.media instanceof TLRPC.TL_messageMediaPhoto && oldMessage.media.photo != null && newMessage.media.photo != null) {
            sameMedia = oldMessage.media.photo.id == newMessage.media.photo.id;
        } else if (oldMessage.media instanceof TLRPC.TL_messageMediaDocument && newMessage.media instanceof TLRPC.TL_messageMediaDocument && oldMessage.media.document != null && newMessage.media.document != null) {
            sameMedia = oldMessage.media.document.id == newMessage.media.document.id;
        }

        if (force) {
            sameMedia = false;
        }
        return sameMedia;
    }

    public void onMessageDeleted(AyuSavePreferences prefs) {
        onMessageDeleted(prefs, true);
    }

    public void onMessageDeleted(AyuSavePreferences prefs, boolean useQueue) {
        if (prefs.getMessage() == null) {
            return;
        }
        try {
            if (useQueue) {
                Utilities.globalQueue.postRunnable(() -> onMessageDeletedInner(prefs));
            } else {
                onMessageDeletedInner(prefs);
            }
        } catch (Exception e) {
            FileLog.e("onMessageDeleted", e);
        }
    }

    private void onMessageDeletedInner(AyuSavePreferences prefs) {
        if (!AyuSavePreferences.saveDeletedMessageFor(prefs.getAccountId(), prefs.getDialogId(), prefs.getFromUserId())) {
            return;
        }

        Boolean exists = withDaoRetry(
                "onMessageDeletedInner#exists",
                () -> deletedMessageDao.exists(prefs.getUserId(), prefs.getDialogId(), prefs.getTopicId(), prefs.getMessageId())
        );

        if (exists == null || exists) {
            return;
        }

        var deletedMessage = new DeletedMessage();
        deletedMessage.userId = prefs.getUserId();
        deletedMessage.dialogId = prefs.getDialogId();
        deletedMessage.messageId = prefs.getMessageId();
        deletedMessage.entityCreateDate = prefs.getRequestCatchTime();

        var msg = prefs.getMessage();

        FileLog.d("saving message " + prefs.getMessageId() + " for " + prefs.getDialogId() + " with topic " + prefs.getTopicId());

        AyuMessageUtils.map(prefs, deletedMessage);
        AyuMessageUtils.mapMedia(prefs, deletedMessage, true);

        Long fakeMsgId = withDaoRetry(
                "onMessageDeletedInner#insert",
                () -> deletedMessageDao.insert(deletedMessage)
        );

        if (fakeMsgId == null) {
            return;
        }

        if (msg != null && msg.reactions != null) {
            processDeletedReactions(fakeMsgId, msg.reactions);
        }
    }

    private void processDeletedReactions(long fakeMessageId, TLRPC.TL_messageReactions reactions) {
        for (var reaction : reactions.results) {
            if (reaction.reaction instanceof TLRPC.TL_reactionEmpty) {
                continue;
            }

            var deletedReaction = new DeletedMessageReaction();
            deletedReaction.deletedMessageId = fakeMessageId;
            deletedReaction.count = reaction.count;
            deletedReaction.selfSelected = reaction.chosen;

            if (reaction.reaction instanceof TLRPC.TL_reactionEmoji) {
                deletedReaction.emoticon = ((TLRPC.TL_reactionEmoji) reaction.reaction).emoticon;
            } else if (reaction.reaction instanceof TLRPC.TL_reactionCustomEmoji) {
                deletedReaction.documentId = ((TLRPC.TL_reactionCustomEmoji) reaction.reaction).document_id;
                deletedReaction.isCustom = true;
            } else {
                continue;
            }

            withDaoRetry(
                    "processDeletedReactions#insertReaction",
                    () -> {
                        deletedMessageDao.insertReaction(deletedReaction);
                        return null;
                    }
            );
        }
    }

    public boolean hasAnyRevisions(long userId, long dialogId, int messageId) {
        return editedMessageDao.hasAnyRevisions(userId, dialogId, messageId);
    }

    public List<EditedMessage> getRevisions(long userId, long dialogId, int messageId) {
        return editedMessageDao.getAllRevisions(userId, dialogId, messageId);
    }

    public DeletedMessageFull getMessage(long userId, long dialogId, int messageId) {
        return deletedMessageDao.getMessage(userId, dialogId, messageId);
    }

    public String getMediaPath(long userId, long dialogId, int messageId) {
        return deletedMessageDao.getMediaPath(userId, dialogId, messageId);
    }

    public List<DeletedMessageFull> getMessages(long userId, long dialogId, long startId, long endId, int limit) {
        return deletedMessageDao.getMessages(userId, dialogId, startId, endId, limit);
    }

    public List<DeletedMessageFull> getTopicMessages(long userId, long dialogId, long topicId, long startId, long endId, int limit) {
        return deletedMessageDao.getTopicMessages(userId, dialogId, topicId, startId, endId, limit);
    }

    public List<DeletedMessageFull> getThreadMessages(long userId, long dialogId, long threadMessageId, long startId, long endId, int limit) {
        return deletedMessageDao.getThreadMessages(userId, dialogId, threadMessageId, startId, endId, limit);
    }

    public List<DeletedMessageFull> getMessagesGroupedIn(long userId, long dialogId, List<Long> groupedIds) {
        if (groupedIds == null || groupedIds.isEmpty()) {
            return new ArrayList<>();
        }
        return deletedMessageDao.getMessagesGroupedIn(userId, dialogId, groupedIds);
    }

    public List<Integer> getExistingMessageIds(long userId, long dialogId, List<Integer> messageIds) {
        if (messageIds == null || messageIds.isEmpty()) {
            return new ArrayList<>();
        }
        return deletedMessageDao.getExistingMessageIds(userId, dialogId, messageIds);
    }

    public List<DeletedMessageFull> getMessagesByIds(long userId, long dialogId, List<Integer> messageIds) {
        if (messageIds == null || messageIds.isEmpty()) {
            return new ArrayList<>();
        }
        return deletedMessageDao.getMessagesByIds(userId, dialogId, messageIds);
    }

    public String findExistingAttachmentPath(long userId, long dialogId, int messageId, long mediaId) {
        if (mediaId == 0) {
            return null;
        }
        List<String> candidates = new ArrayList<>();
        List<String> deleted = withDaoRetry(
                "findExistingAttachmentPath#deleted",
                () -> deletedMessageDao.getAttachmentMediaPaths(userId, dialogId, messageId, mediaId)
        );
        if (deleted != null) {
            candidates.addAll(deleted);
        }
        List<String> edited = withDaoRetry(
                "findExistingAttachmentPath#edited",
                () -> editedMessageDao.getAttachmentMediaPaths(userId, dialogId, messageId, mediaId)
        );
        if (edited != null) {
            candidates.addAll(edited);
        }
        for (String path : candidates) {
            if (TextUtils.isEmpty(path)) {
                continue;
            }
            File f = new File(path);
            if (isUnderAttachments(f) && f.exists() && f.length() > 0) {
                return path;
            }
        }
        return null;
    }

    public String findExistingThumbPath(long userId, long dialogId, int messageId, long mediaId) {
        if (mediaId == 0) {
            return null;
        }
        List<String> candidates = new ArrayList<>();
        List<String> deleted = withDaoRetry(
                "findExistingThumbPath#deleted",
                () -> deletedMessageDao.getAttachmentThumbPaths(userId, dialogId, messageId, mediaId)
        );
        if (deleted != null) {
            candidates.addAll(deleted);
        }
        List<String> edited = withDaoRetry(
                "findExistingThumbPath#edited",
                () -> editedMessageDao.getAttachmentThumbPaths(userId, dialogId, messageId, mediaId)
        );
        if (edited != null) {
            candidates.addAll(edited);
        }
        for (String path : candidates) {
            if (TextUtils.isEmpty(path)) {
                continue;
            }
            File f = new File(path);
            if (isUnderAttachments(f) && f.exists() && f.length() > 0) {
                return path;
            }
        }
        return null;
    }

    // startsWith on the bare path would also match a sibling like "Saved Attachments_old",
    // so anchor on an exact match or the folder plus a separator.
    private boolean isUnderAttachments(File f) {
        String prefix = attachmentsPath.getAbsolutePath();
        String p = f.getAbsolutePath();
        return p.equals(prefix) || p.startsWith(prefix + File.separator);
    }

    // Unlink a saved-media file only if it is one we own (under attachmentsPath) and no
    // remaining row still points at it. mediaPath can be a path straight into Telegram's
    // own cache (copyFileToAttachments off), and dedup / revision backfill deliberately
    // share one file across rows, so blindly deleting would take out the user's own media
    // or a file another row still needs.
    private void safeUnlinkAttachment(String path) {
        if (TextUtils.isEmpty(path)) {
            return;
        }
        File f = new File(path);
        if (!isUnderAttachments(f)) {
            return;
        }
        if (isPathReferenced(path)) {
            return;
        }
        try {
            if (f.exists() && !f.delete()) {
                f.deleteOnExit();
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    private boolean isPathReferenced(String path) {
        Boolean inDeleted = withDaoRetry(
                "isPathReferenced#deleted",
                () -> deletedMessageDao.isPathReferenced(path)
        );
        Boolean inEdited = withDaoRetry(
                "isPathReferenced#edited",
                () -> editedMessageDao.isPathReferenced(path)
        );
        // Fail closed: a failed lookup reads as "still referenced", never as "safe to unlink".
        // Treating a null (query error) as unreferenced would delete a file another row needs.
        if (inDeleted == null || inEdited == null) {
            return true;
        }
        return inDeleted || inEdited;
    }

    public void delete(long userId, long dialogId, int messageId) {
        var msg = getMessage(userId, dialogId, messageId);
        if (msg == null) {
            return;
        }

        deletedMessageDao.delete(userId, dialogId, messageId);

        safeUnlinkAttachment(msg.message.mediaPath);
    }

    public void deleteMessages(long userId, long dialogId, List<Integer> messageIds) {
        if (messageIds == null || messageIds.isEmpty()) {
            return;
        }

        List<String> mediaPaths = new ArrayList<>();
        for (int messageId : messageIds) {
            var msg = getMessage(userId, dialogId, messageId);
            if (msg != null && !TextUtils.isEmpty(msg.message.mediaPath)) {
                mediaPaths.add(msg.message.mediaPath);
            }
        }

        deletedMessageDao.deleteMessages(userId, dialogId, messageIds);
        editedMessageDao.deleteByDialogIdAndMessageIds(dialogId, messageIds);

        for (String mediaPath : mediaPaths) {
            safeUnlinkAttachment(mediaPath);
        }
    }

    public void deleteRevision(long fakeId) {
        String mediaPath = editedMessageDao.getMediaPathByFakeId(fakeId);
        int deleted = editedMessageDao.deleteByFakeId(fakeId);
        if (deleted == 0) {
            return;
        }
        safeUnlinkAttachment(mediaPath);
    }

    public void deleteCurrent(long dialogId, long mergeDialogId, Runnable callback) {
        List<DeletedMessageFull> messages = deletedMessageDao.getMessagesByDialog(dialogId);

        if (mergeDialogId != 0) {
            List<DeletedMessageFull> mergeMessages = deletedMessageDao.getMessagesByDialog(mergeDialogId);
            messages.addAll(mergeMessages);
        }

        // Delete messages and their edit history from database
        deletedMessageDao.delete(dialogId);
        editedMessageDao.delete(dialogId);

        if (mergeDialogId != 0) {
            deletedMessageDao.delete(mergeDialogId);
            editedMessageDao.delete(mergeDialogId);
        }

        // Clean up media files
        for (DeletedMessageFull msg : messages) {
            safeUnlinkAttachment(msg.message.mediaPath);
        }

        if (callback != null) {
            callback.run();
        }
    }

    public boolean isAyuDeletedMessageId(long userId, long dialogId, int messageId) {
        if (userId == 0 || dialogId == 0 || messageId == 0) {
            return false;
        }
        return AyuMessagesController.getInstance().getMessage(userId, dialogId, messageId) != null;
    }

    public int getDeletedCount(long userId, long dialogId) {
        return deletedMessageDao.countByDialog(userId, dialogId);
    }

    public List<DeletedMessageFull> getLatestMessages(long userId, long dialogId, int limit) {
        return deletedMessageDao.getLatestMessages(userId, dialogId, limit);
    }

    public List<DeletedMessageFull> getOlderMessagesBefore(long userId, long dialogId, int before, int limit) {
        return deletedMessageDao.getOlderMessagesBefore(userId, dialogId, before, limit);
    }

    public void updateMediaPath(long userId, long dialogId, int messageId, String newPath) {
        deletedMessageDao.updateMediaPathIfEmpty(userId, dialogId, messageId, newPath);
    }

    public void clean() {
        AyuData.clean();
        AyuData.create();

        refreshDaos();

        cleanAttachmentsFolder();

        // force to recreate a database to avoid crash
        instance = null;
    }

    private void cleanAttachmentsFolder() {
        FileUtil.deleteDirectory(attachmentsPath);
        initializeAttachmentsFolder();
    }
}
