/*
 * This is the source code of AyuGram for Android.
 *
 * We do not and cannot prevent the use of our code,
 * but be respectful and credit the original author.
 *
 * Copyright @Radolyn, 2023
 */

package com.radolyn.ayugram.messages;

import android.text.TextUtils;

import com.radolyn.ayugram.AyuAttachments;
import com.radolyn.ayugram.AyuConstants;
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
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.LaunchActivity;

import java.io.File;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.Callable;

public class AyuMessagesController {
    private static AyuMessagesController instance;
    private EditedMessageDao editedMessageDao;
    private DeletedMessageDao deletedMessageDao;

    private AyuMessagesController() {
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
        // TTL / view-once path. The caller (MessagesStorage.emptyMessagesMedia, on the storage
        // queue) blanks this row's media and deletes its file the moment we return, so the save
        // has to finish before then. Run it inline on the caller's hop instead of handing off to
        // globalQueue, or the copy loses the race and we save metadata with no file.
        try {
            onMessageEditedInner(prefs, prefs.getMessage(), true);
        } catch (Exception e) {
            FileLog.e("onMessageEditedForce", e);
        }
    }

    private void onMessageEditedInner(AyuSavePreferences prefs, TLRPC.Message newMessage, boolean force) {
        var oldMessage = prefs.getMessage();

        boolean sameMedia = isSameMedia(newMessage, force, oldMessage);

        if (sameMedia && TextUtils.equals(oldMessage.message, newMessage.message)) {
            return;
        }

        var revision = new EditedMessage();
        AyuMessageUtils.map(prefs, revision);

        // Band L: stage the media copy holding no lock. The commit below places it and records the
        // row under the monitor as one critical section, so the long copy never blocks another
        // account's globalQueue work or a main-thread op waiting on the same lock.
        AyuMessageUtils.StagedMedia staged = AyuMessageUtils.stageMedia(prefs, revision, !sameMedia, force);

        AyuAttachments.commit(tx -> {
            AyuMessageUtils.finalizeMediaLocked(prefs, revision, staged, tx);

            if (!sameMedia && !TextUtils.isEmpty(revision.mediaPath)) {
                var lastRevision = withDaoRetry(
                        "onMessageEditedInner#getLastRevision",
                        () -> editedMessageDao.getLastRevision(prefs.getUserId(), prefs.getDialogId(), prefs.getMessageId())
                );

                if (lastRevision != null && !TextUtils.equals(revision.mediaPath, lastRevision.mediaPath) && lastRevision.mediaPath != null && !AyuAttachments.isStoredAttachmentPath(lastRevision.mediaPath)) {
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
        });

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

        // Band L: stage the media copy holding no lock, then hold the gate only across the dedup
        // lookup, the promote-into-place and the insert, so a concurrent delete can't unlink the
        // resolved file between them. saveDeletedMessageFor above stays outside it: it awaits a
        // latch on the storage queue, and holding this lock across that wait is how a deleter and
        // the storage-queue force save could deadlock.
        AyuMessageUtils.StagedMedia staged = AyuMessageUtils.stageMedia(prefs, deletedMessage, true, false);

        Long[] fakeMsgIdHolder = new Long[1];
        AyuAttachments.commit(tx -> {
            AyuMessageUtils.finalizeMediaLocked(prefs, deletedMessage, staged, tx);

            fakeMsgIdHolder[0] = withDaoRetry(
                    "onMessageDeletedInner#insert",
                    () -> deletedMessageDao.insert(deletedMessage)
            );
        });
        Long fakeMsgId = fakeMsgIdHolder[0];

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
            if (AyuAttachments.isUnder(f) && f.exists() && f.length() > 0) {
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
            if (AyuAttachments.isUnder(f) && f.exists() && f.length() > 0) {
                return path;
            }
        }
        return null;
    }

    // Unlink a saved-media file only if it is one we own (under the attachments folder) and no
    // remaining row still points at it. mediaPath can be a path straight into Telegram's
    // own cache (copyFileToAttachments off), and dedup / revision backfill deliberately
    // share one file across rows, so blindly deleting would take out the user's own media
    // or a file another row still needs.
    private void safeUnlinkAttachment(String path) {
        if (TextUtils.isEmpty(path)) {
            return;
        }
        File f = new File(path);
        if (!AyuAttachments.isUnder(f)) {
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
        AyuAttachments.commit(tx -> {
            var msg = getMessage(userId, dialogId, messageId);
            if (msg == null) {
                return;
            }

            deletedMessageDao.delete(userId, dialogId, messageId);

            safeUnlinkAttachment(msg.message.mediaPath);
            safeUnlinkAttachment(msg.message.hqThumbPath);
        });
    }

    public void deleteMessages(long userId, long dialogId, List<Integer> messageIds) {
        if (messageIds == null || messageIds.isEmpty()) {
            return;
        }

        AyuAttachments.commit(tx -> {
            LinkedHashSet<String> mediaPaths = new LinkedHashSet<>();
            for (int messageId : messageIds) {
                var msg = getMessage(userId, dialogId, messageId);
                if (msg != null && msg.message != null) {
                    if (!TextUtils.isEmpty(msg.message.mediaPath)) {
                        mediaPaths.add(msg.message.mediaPath);
                    }
                    if (!TextUtils.isEmpty(msg.message.hqThumbPath)) {
                        mediaPaths.add(msg.message.hqThumbPath);
                    }
                }
            }

            deletedMessageDao.deleteMessages(userId, dialogId, messageIds);
            editedMessageDao.deleteByDialogIdAndMessageIds(dialogId, messageIds);

            for (String mediaPath : mediaPaths) {
                safeUnlinkAttachment(mediaPath);
            }
        });
    }

    public void deleteRevision(long fakeId) {
        AyuAttachments.commit(tx -> {
            String mediaPath = editedMessageDao.getMediaPathByFakeId(fakeId);
            String thumbPath = editedMessageDao.getThumbPathByFakeId(fakeId);
            int deleted = editedMessageDao.deleteByFakeId(fakeId);
            if (deleted == 0) {
                return;
            }
            safeUnlinkAttachment(mediaPath);
            safeUnlinkAttachment(thumbPath);
        });
    }

    public void deleteCurrent(long dialogId, long mergeDialogId, Runnable callback) {
        // Clearing a whole chat's history walks two growing tables and unlinks their files. That
        // is off-main-thread work; the button that calls this runs it on the UI thread, so move
        // it here rather than touching the caller. Capture the originating fragment now, on the UI
        // thread, as a weak identity token: the completion callback finishes the current fragment
        // and buzzes it, so only run it if the very same fragment is still the one on screen with
        // a live, attached view. The reference is weak so holding the token can't keep a closed
        // screen alive; the Runnable stays strongly referenced because the lambda is its only
        // owner and it would otherwise be collectable before it runs.
        WeakReference<BaseFragment> origin = new WeakReference<>(LaunchActivity.getLastFragment());
        Utilities.globalQueue.postRunnable(() -> {
            deleteCurrentInner(dialogId, mergeDialogId);
            if (callback != null) {
                AndroidUtilities.runOnUIThread(() -> {
                    BaseFragment f = origin.get();
                    if (f != null
                            && f == LaunchActivity.getLastFragment()
                            && !f.isRemovingFromStack()
                            && f.getFragmentView() != null
                            && f.getFragmentView().isAttachedToWindow()) {
                        callback.run();
                    }
                });
            }
        });
    }

    private void deleteCurrentInner(long dialogId, long mergeDialogId) {
        AyuAttachments.commit(tx -> {
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

            // Clean up media files, one reference check per distinct path rather than per row
            LinkedHashSet<String> paths = new LinkedHashSet<>();
            for (DeletedMessageFull msg : messages) {
                if (msg.message != null) {
                    if (!TextUtils.isEmpty(msg.message.mediaPath)) {
                        paths.add(msg.message.mediaPath);
                    }
                    if (!TextUtils.isEmpty(msg.message.hqThumbPath)) {
                        paths.add(msg.message.hqThumbPath);
                    }
                }
            }
            for (String path : paths) {
                safeUnlinkAttachment(path);
            }
        });
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

    // Delayed adoption: a deleted-message row's media finished downloading into Telegram's cache
    // after the fact, or an attachments copy already exists. Copy it in (when a source is given)
    // and point the row at the resulting file. The copy stages to a temp holding no lock; only the
    // promote-into-place and the row update run under the monitor, so a concurrent delete can't
    // unlink the file between resolving it and recording its path. Mirrors the reader's resolution
    // order: prefer the just-copied file, then the exact base name, then the fallback.
    public void adoptAttachment(long userId, long dialogId, int messageId, File from, File to, String baseName, String fallbackName) {
        // Band L: the byte copy holds no lock. Only the place-and-record below runs under it.
        AyuAttachments.StagedToken staged = (from != null && to != null && from.exists()) ? AyuAttachments.stage(from, false) : null;
        AyuAttachments.commit(tx -> {
            File resolved = null;
            if (staged != null) {
                File placed = tx.place(staged, to.getName());
                if (placed != null && placed.exists() && placed.length() > 0) {
                    resolved = placed;
                }
            }
            if (resolved == null && !TextUtils.isEmpty(baseName)) {
                resolved = AyuMessageUtils.findExistingFileByBaseNameFast(baseName);
            }
            if (resolved == null && !TextUtils.isEmpty(fallbackName)) {
                resolved = AyuMessageUtils.findExistingFileByBaseNameFast(fallbackName);
            }
            if (resolved != null && resolved.exists() && resolved.length() > 0) {
                final String newPath = resolved.getAbsolutePath();
                withDaoRetry("adoptAttachment", () -> {
                    deletedMessageDao.updateMediaPathIfEmpty(userId, dialogId, messageId, newPath);
                    return null;
                });
            }
        });
    }

    public void clean() {
        File[] asideHolder = new File[1];
        AyuAttachments.commit(tx -> {
            AyuData.clean();
            AyuData.create();

            refreshDaos();

            // Band W: swap the folder for a fresh empty one under the monitor. The old tree's
            // deletion is the long part and is handed off below rather than held here.
            asideHolder[0] = AyuAttachments.renameAsideAndRecreate();

            // force to recreate a database to avoid crash
            instance = null;
        });

        // Band L: delete the old tree unlocked, off the monitor a media bind could be waiting on.
        File aside = asideHolder[0];
        if (aside != null) {
            Utilities.globalQueue.postRunnable(() -> AyuAttachments.deleteTree(aside));
        }
    }
}
