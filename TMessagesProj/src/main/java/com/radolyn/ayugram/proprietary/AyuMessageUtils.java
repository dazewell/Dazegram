package com.radolyn.ayugram.proprietary;

import android.text.TextUtils;
import android.util.Log;

import androidx.core.util.Pair;

import com.radolyn.ayugram.AyuAttachments;
import com.radolyn.ayugram.AyuConstants;
import com.radolyn.ayugram.AyuUtils;
import com.radolyn.ayugram.database.entities.AyuMessageBase;
import com.radolyn.ayugram.messages.AyuMessagesController;
import com.radolyn.ayugram.messages.AyuSavePreferences;
import com.radolyn.ayugram.utils.AyuFileLocation;

import org.telegram.messenger.BuildVars;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LiteMode;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.messenger.secretmedia.EncryptedFileInputStream;
import org.telegram.tgnet.NativeByteBuffer;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;

import java.io.File;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.function.Function;

import xyz.nextalone.nagram.NaConfig;

public abstract class AyuMessageUtils {
    private static final String TAG = "AyuMessageUtils";

    public static <T extends TLObject> ArrayList<T> deserializeMultiple(byte[] serializedData, Function<NativeByteBuffer, T> deserializer) {
        ArrayList<T> deserializedList = new ArrayList<>();
        if (serializedData == null || serializedData.length == 0) {
            return deserializedList;
        }
        NativeByteBuffer data = null;
        try {
            data = new NativeByteBuffer(serializedData.length);
            data.buffer.put(serializedData);
            data.rewind();

            while (data.buffer.hasRemaining()) {
                T item = deserializer.apply(data);
                if (item != null) {
                    deserializedList.add(item);
                } else {
                    break;
                }
            }
        } catch (Exception e) {
            FileLog.e("Failed to deserializeMultiple", e);
        } finally {
            if (data != null) {
                data.reuse();
            }
        }
        return deserializedList;
    }

    public static void map(AyuMessageBase source, TLRPC.Message target, int accountId) {
        MessagesController messagesController = MessagesController.getInstance(accountId);
        TLRPC.Chat dialogChat = source.dialogId < 0 ? messagesController.getChat(-source.dialogId) : null;
        int flags = source.flags;
        target.dialog_id = source.dialogId;
        target.grouped_id = source.groupedId;
        target.peer_id = messagesController.getPeer(source.peerId);
        target.from_id = getFromPeer(messagesController, source.fromId, dialogChat);
        int messageId = source.messageId;
        target.id = messageId;
        target.realId = messageId;
        target.date = source.date;
        target.flags = flags;
        target.unread = (flags & 1) != 0;
        target.out = (flags & 2) != 0;
        target.mentioned = (flags & 16) != 0;
        target.media_unread = (flags & 32) != 0;
        target.silent = (flags & LiteMode.FLAG_ANIMATED_EMOJI_REACTIONS_NOT_PREMIUM) != 0;
        target.post = (flags & 16384) != 0;
        target.from_scheduled = (262144 & flags) != 0;
        target.legacy = (524288 & flags) != 0;
        target.edit_hide = (2097152 & flags) != 0;
        target.pinned = (16777216 & flags) != 0;
        target.noforwards = false;
        target.edit_date = source.editDate;
        target.views = source.views;
        target.forwards = source.forwards;
        if ((flags & 4) != 0) {
            TLRPC.TL_messageFwdHeader forwardHeader = new TLRPC.TL_messageFwdHeader();
            target.fwd_from = forwardHeader;
            forwardHeader.flags = source.fwdFlags;
            if (source.fwdFromId != 0) {
                forwardHeader.from_id = messagesController.getPeer(source.fwdFromId);
            }
            forwardHeader.from_name = source.fwdName;
            forwardHeader.date = source.fwdDate;
            forwardHeader.post_author = source.fwdPostAuthor;
        }
        if ((target.flags & 8) != 0) {
            TLRPC.MessageReplyHeader replyHeader = new TLRPC.TL_messageReplyHeader();
            target.reply_to = replyHeader;
            replyHeader.flags = source.replyFlags;
            replyHeader.reply_to_msg_id = source.replyMessageId;
            if (source.replyPeerId != 0) {
                replyHeader.reply_to_peer_id = messagesController.getPeer(source.replyPeerId);
            }
            replyHeader.reply_to_top_id = source.replyTopId;
            replyHeader.forum_topic = source.replyForumTopic;
            replyHeader.quote = source.replyQuote;
            replyHeader.quote_text = source.replyQuoteText;
            replyHeader.quote_entities = deserializeMultiple(
                    source.replyQuoteEntities,
                    (NativeByteBuffer data) ->
                            TLRPC.MessageEntity.TLdeserialize(
                                    data,
                                    data.readInt32(false),
                                    false
                            )
            );
            // deserialize reply_from for quotes
            if (source.replyFromSerialized != null && source.replyFromSerialized.length > 0) {
                NativeByteBuffer data = null;
                try {
                    data = new NativeByteBuffer(source.replyFromSerialized.length);
                    data.put(ByteBuffer.wrap(source.replyFromSerialized));
                    data.rewind();
                    replyHeader.reply_from = TLRPC.MessageFwdHeader.TLdeserialize(data, data.readInt32(false), false);
                } catch (Exception e) {
                    FileLog.e("Failed to deserialize reply_from", e);
                } finally {
                    if (data != null) {
                        data.reuse();
                    }
                }
            }
        }
        target.message = source.text;
        target.entities = deserializeMultiple(
                source.textEntities,
                (NativeByteBuffer data) ->
                        TLRPC.MessageEntity.TLdeserialize(
                                data,
                                data.readInt32(false),
                                false
                        )
        );
        // deserialize reply_markup (inline keyboard)
        if (source.replyMarkupSerialized != null && source.replyMarkupSerialized.length > 0) {
            NativeByteBuffer data = null;
            try {
                data = new NativeByteBuffer(source.replyMarkupSerialized.length);
                data.put(ByteBuffer.wrap(source.replyMarkupSerialized));
                data.rewind();
                target.reply_markup = TLRPC.ReplyMarkup.TLdeserialize(data, data.readInt32(false), false);
            } catch (Exception e) {
                FileLog.e("Failed to deserialize reply_markup", e);
            } finally {
                if (data != null) {
                    data.reuse();
                }
            }
        }
    }

    public static void map(AyuSavePreferences prefs, AyuMessageBase out) {
        TLRPC.Message message = prefs.getMessage();
        out.userId = prefs.getUserId();
        out.dialogId = prefs.getDialogId();
        out.groupedId = message.grouped_id;
        out.peerId = MessageObject.getPeerId(message.peer_id);
        out.fromId = MessageObject.getPeerId(message.from_id);
        out.topicId = prefs.getTopicId();
        out.messageId = message.id;
        out.date = message.date;
        out.flags = message.flags;
        out.editDate = message.edit_date;
        out.views = message.views;
        out.forwards = message.forwards;
        TLRPC.MessageFwdHeader fwdHeader = message.fwd_from;
        if (fwdHeader != null) {
            out.fwdFlags = fwdHeader.flags;
            out.fwdFromId = MessageObject.getPeerId(fwdHeader.from_id);
            out.fwdName = fwdHeader.from_name;
            out.fwdDate = fwdHeader.date;
            out.fwdPostAuthor = fwdHeader.post_author;
        }
        TLRPC.MessageReplyHeader replyHeader = message.reply_to;
        if (replyHeader != null) {
            out.replyFlags = replyHeader.flags;
            out.replyMessageId = replyHeader.reply_to_msg_id;
            out.replyPeerId = MessageObject.getPeerId(replyHeader.reply_to_peer_id);
            out.replyTopId = replyHeader.reply_to_top_id;
            out.replyForumTopic = replyHeader.forum_topic;
            out.replyQuote = replyHeader.quote;
            out.replyQuoteText = replyHeader.quote_text;
            out.replyQuoteEntities = serializeMultiple(replyHeader.quote_entities);
            // serialize reply_from for quotes
            if (replyHeader.reply_from != null) {
                NativeByteBuffer data = null;
                try {
                    int size = replyHeader.reply_from.getObjectSize();
                    if (size > 0) {
                        data = new NativeByteBuffer(size);
                        replyHeader.reply_from.serializeToStream(data);
                        data.rewind();
                        byte[] serialized = new byte[data.buffer.remaining()];
                        data.buffer.get(serialized);
                        out.replyFromSerialized = serialized;
                    }
                } catch (Exception e) {
                    FileLog.e("Failed to serialize reply_from", e);
                } finally {
                    if (data != null) {
                        data.reuse();
                    }
                }
            }
        }
        out.entityCreateDate = prefs.getRequestCatchTime();
        out.text = message.message;
        out.textEntities = serializeMultiple(message.entities);
        // serialize reply_markup (inline keyboard)
        TLRPC.ReplyMarkup replyMarkup = message.reply_markup;
        if (replyMarkup != null) {
            NativeByteBuffer data = null;
            try {
                int size = replyMarkup.getObjectSize();
                if (size > 0) {
                    data = new NativeByteBuffer(size);
                    replyMarkup.serializeToStream(data);
                    data.rewind();
                    byte[] serialized = new byte[data.buffer.remaining()];
                    data.buffer.get(serialized);
                    out.replyMarkupSerialized = serialized;
                }
            } catch (Exception e) {
                FileLog.e("Failed to serialize reply_markup", e);
            } finally {
                if (data != null) {
                    data.reuse();
                }
            }
        }
    }

    public static void mapMedia(AyuMessageBase base, TLRPC.Message target, int accountId) {
        byte[] bytes;
        int documentType = base.documentType;
        byte[] serializedDocument = base.documentSerialized;
        String mediaPath = base.mediaPath;
        int messageDate = base.date;
        if (documentType != AyuConstants.DOCUMENT_TYPE_NONE) {
            // handle WebPage
            if (documentType == AyuConstants.DOCUMENT_TYPE_WEBPAGE && serializedDocument != null && serializedDocument.length > 0) {
                NativeByteBuffer data = null;
                try {
                    data = new NativeByteBuffer(serializedDocument.length);
                    data.put(ByteBuffer.wrap(serializedDocument));
                    data.rewind();
                    target.media = TLRPC.MessageMedia.TLdeserialize(data, data.readInt32(false), false);
                    if (BuildVars.LOGS_ENABLED) {
                        Log.d(TAG, "Restored webpage media for message " + target.id);
                    }
                } catch (Exception e) {
                    FileLog.e("Failed to deserialize webpage media", e);
                } finally {
                    if (data != null) {
                        data.reuse();
                    }
                }
                return;
            }
            // handle Story
            if (documentType == AyuConstants.DOCUMENT_TYPE_STORY && serializedDocument != null && serializedDocument.length > 0) {
                NativeByteBuffer data = null;
                try {
                    data = new NativeByteBuffer(serializedDocument.length);
                    data.put(ByteBuffer.wrap(serializedDocument));
                    data.rewind();
                    TLRPC.MessageMedia deserialized = TLRPC.MessageMedia.TLdeserialize(data, data.readInt32(false), false);
                    if (deserialized instanceof TLRPC.TL_messageMediaStory story) {
                        target.media = deserialized;
                        if (!TextUtils.isEmpty(mediaPath)) {
                            target.attachPath = mediaPath;
                            if (story.storyItem != null) {
                                story.storyItem.attachPath = mediaPath;
                                if (story.storyItem.media != null && story.storyItem.media.document != null) {
                                    story.storyItem.media.document.localPath = mediaPath;
                                }
                            }
                        } else {
                            String resolvedPath = ensureAttachmentAndUpdateMediaPath(base, target, accountId);
                            if (!TextUtils.isEmpty(resolvedPath)) {
                                target.attachPath = resolvedPath;
                                if (story.storyItem != null) {
                                    story.storyItem.attachPath = resolvedPath;
                                    if (story.storyItem.media != null && story.storyItem.media.document != null) {
                                        story.storyItem.media.document.localPath = resolvedPath;
                                    }
                                }
                            }
                        }
                        return;
                    }
                    target.media = deserialized;
                } catch (Exception e) {
                    FileLog.e("Failed to deserialize story media", e);
                } finally {
                    if (data != null) {
                        data.reuse();
                    }
                }
                return;
            }
            // If we have serialized media data (and no file path), deserialize it directly
            // This handles cases where the file wasn't downloaded when the message was deleted
            if (documentType != AyuConstants.DOCUMENT_TYPE_STICKER && serializedDocument != null && serializedDocument.length > 0 && TextUtils.isEmpty(mediaPath)) {
                NativeByteBuffer data = null;
                try {
                    data = new NativeByteBuffer(serializedDocument.length);
                    data.put(ByteBuffer.wrap(serializedDocument));
                    data.rewind();
                    target.media = TLRPC.MessageMedia.TLdeserialize(data, data.readInt32(false), false);
                    // handle legacy WebPage data saved as DOCUMENT_TYPE_FILE
                    if (target.media instanceof TLRPC.TL_messageMediaWebPage) {
                        if (BuildVars.LOGS_ENABLED) {
                            Log.d(TAG, "Restored legacy webpage media for message " + target.id);
                        }
                        return;
                    }
                    String resolvedPath = ensureAttachmentAndUpdateMediaPath(base, target, accountId);
                    if (!TextUtils.isEmpty(resolvedPath)) {
                        mediaPath = resolvedPath;
                        if (BuildVars.LOGS_ENABLED) {
                            Log.d(TAG, "mapMedia: found attachments copy for deserialized media: " + mediaPath);
                        }
                    }
                    if (BuildVars.LOGS_ENABLED) {
                        Log.d(TAG, "Restored media from serialized data for message " + target.id);
                    }
                    if (TextUtils.isEmpty(mediaPath)) {
                        return;
                    }
                } catch (Exception e) {
                    FileLog.e("Failed to deserialize media", e);
                } finally {
                    if (data != null) {
                        data.reuse();
                    }
                }
            }

            if (documentType == AyuConstants.DOCUMENT_TYPE_STICKER || !TextUtils.isEmpty(mediaPath)) {
                if (documentType == AyuConstants.DOCUMENT_TYPE_STICKER && serializedDocument != null && serializedDocument.length > 0) {
                    NativeByteBuffer data = null;
                    try {
                        data = new NativeByteBuffer(serializedDocument.length);
                        data.put(ByteBuffer.wrap(serializedDocument));
                        data.rewind();
                        target.media = TLRPC.MessageMedia.TLdeserialize(data, data.readInt32(false), false);
                    } catch (Exception e) {
                        FileLog.e("fake news sticker..", e);
                    } finally {
                        if (data != null) {
                            data.reuse();
                        }
                    }
                    target.stickerVerified = 1;
                    return;
                }
                if (TextUtils.isEmpty(mediaPath)) {
                    return;
                }
                target.attachPath = mediaPath;
                File file = new File(mediaPath);
                if (documentType == AyuConstants.DOCUMENT_TYPE_PHOTO) {
                    Pair<Integer, Integer> sizePair = AyuUtils.extractImageSizeFromName(file.getName());
                    if (sizePair == null) {
                        sizePair = AyuUtils.extractImageSizeFromFile(file.getAbsolutePath());
                    }
                    if (sizePair == null) {
                        sizePair = new Pair<>(500, 500);
                    }
                    TLRPC.TL_messageMediaPhoto mediaPhoto = new TLRPC.TL_messageMediaPhoto();
                    target.media = mediaPhoto;
                    mediaPhoto.flags = 1;
                    mediaPhoto.photo = new TLRPC.TL_photo();
                    TLRPC.Photo photo = target.media.photo;
                    photo.has_stickers = false;
                    photo.date = messageDate;
                    TLRPC.TL_photoSize photoSize = new TLRPC.TL_photoSize();
                    photoSize.size = (int) file.length();
                    photoSize.w = sizePair.first;
                    photoSize.h = sizePair.second;
                    photoSize.type = "y";
                    photoSize.location = new AyuFileLocation(mediaPath);
                    target.media.photo.sizes.add(photoSize);
                } else if (documentType == AyuConstants.DOCUMENT_TYPE_FILE) {
                    TLRPC.TL_messageMediaDocument mediaDocument = new TLRPC.TL_messageMediaDocument();
                    target.media = mediaDocument;
                    mediaDocument.flags = 1;
                    mediaDocument.document = new TLRPC.TL_document();
                    TLRPC.Document doc = target.media.document;
                    doc.date = messageDate;
                    doc.localPath = mediaPath;
                    doc.file_name = AyuUtils.getReadableFilename(file.getName());
                    doc.file_name_fixed = AyuUtils.getReadableFilename(file.getName());
                    doc.size = file.length();
                    doc.mime_type = base.mimeType;
                    doc.attributes = deserializeMultiple(
                        base.documentAttributesSerialized,
                        (NativeByteBuffer data) ->
                            TLRPC.DocumentAttribute.TLdeserialize(
                                data,
                                data.readInt32(false),
                                false
                            )
                    );
                    for (TLRPC.PhotoSize photoSize : deserializeMultiple(
                            base.thumbsSerialized,
                            (NativeByteBuffer data) ->
                                    TLRPC.PhotoSize.TLdeserialize(
                                            0L,
                                            0L,
                                            0L,
                                            data,
                                            data.readInt32(false),
                                            false
                                    )
                    )) {
                        if (photoSize != null) {
                            if ((photoSize instanceof TLRPC.TL_photoSize) && !TextUtils.isEmpty(base.hqThumbPath) && ((bytes = photoSize.bytes) == null || bytes.length == 0)) {
                                photoSize.location = new AyuFileLocation(base.hqThumbPath);
                            }
                            byte[] thumbBytes = photoSize.bytes;
                            if ((thumbBytes != null && thumbBytes.length != 0) || photoSize.location != null) {
                                target.media.document.thumbs.add(photoSize);
                            }
                        }
                    }
                }
            }
        }
    }

    public static long getMediaIdentity(TLRPC.Message message) {
        if (message == null || message.media == null) {
            return 0;
        }
        TLRPC.MessageMedia media = message.media;
        if (media instanceof TLRPC.TL_messageMediaStory story && story.storyItem != null && story.storyItem.media != null) {
            media = story.storyItem.media;
        }
        if (media.document != null) {
            return media.document.id;
        }
        if (media.photo != null) {
            return media.photo.id;
        }
        return 0;
    }

    // Carrier from stageMedia (Band L, no lock) to finalizeMediaLocked (Band W, monitor held). Holds
    // the opaque tokens for temps already written into the staging sibling and the names they should
    // take once the row is recorded. Nothing here is placed in the live folder yet.
    public static class StagedFile {
        final AyuAttachments.StagedToken token;
        final String finalName;

        StagedFile(AyuAttachments.StagedToken token, String finalName) {
            this.token = token;
            this.finalName = finalName;
        }
    }

    public static class StagedMedia {
        final boolean processFiles;
        final boolean copyToAttachments;
        final StagedFile main;
        final String directPath;
        final StagedFile thumb;

        StagedMedia(boolean processFiles, boolean copyToAttachments, StagedFile main, String directPath, StagedFile thumb) {
            this.processFiles = processFiles;
            this.copyToAttachments = copyToAttachments;
            this.main = main;
            this.directPath = directPath;
            this.thumb = thumb;
        }
    }

    // Band L: classify the media, fill the row's metadata, and — for file media we're copying —
    // stage the bytes into temp files in the attachments dir holding NO lock. The temps aren't at
    // their final names yet; finalizeMedia places them under the monitor. Safe from any thread.
    public static StagedMedia stageMedia(AyuSavePreferences prefs, AyuMessageBase out, boolean copyFileToAttachments, boolean deleteSource) {
        TLRPC.Message message = prefs.getMessage();
        if (!shouldSaveMedia(prefs)) {
            return new StagedMedia(false, copyFileToAttachments, null, null, null);
        }
        TLRPC.MessageMedia media = message.media;
        out.mediaId = getMediaIdentity(message);
        if (media == null) {
            out.documentType = AyuConstants.DOCUMENT_TYPE_NONE;
        } else if ((media instanceof TLRPC.TL_messageMediaPhoto) && media.photo != null) {
            out.documentType = AyuConstants.DOCUMENT_TYPE_PHOTO;
        } else if (media instanceof TLRPC.TL_messageMediaStory) {
            out.documentType = AyuConstants.DOCUMENT_TYPE_STORY;
        } else if ((media instanceof TLRPC.TL_messageMediaDocument) && media.document != null && (MessageObject.isStickerMessage(message) || (media.document.mime_type != null && media.document.mime_type.equals("application/x-tgsticker")))) {
            out.documentType = AyuConstants.DOCUMENT_TYPE_STICKER;
            out.mimeType = message.media.document.mime_type;
            NativeByteBuffer data = null;
            try {
                data = new NativeByteBuffer(message.media.getObjectSize());
                message.media.serializeToStream(data);
                data.buffer.rewind();
                byte[] serialized = new byte[data.buffer.remaining()];
                data.buffer.get(serialized);
                out.documentSerialized = serialized;
            } catch (Exception e) {
                FileLog.e("fake news sticker", e);
            } finally {
                if (data != null) {
                    data.reuse();
                }
            }
        } else if (media instanceof TLRPC.TL_messageMediaWebPage && media.webpage != null) {
            out.documentType = AyuConstants.DOCUMENT_TYPE_WEBPAGE;
            NativeByteBuffer data = null;
            try {
                data = new NativeByteBuffer(message.media.getObjectSize());
                message.media.serializeToStream(data);
                data.buffer.rewind();
                byte[] serialized = new byte[data.buffer.remaining()];
                data.buffer.get(serialized);
                out.documentSerialized = serialized;
                if (BuildVars.LOGS_ENABLED) {
                    Log.d(TAG, "Saved webpage media for message " + message.id);
                }
            } catch (Exception e) {
                FileLog.e("Failed to serialize webpage media", e);
            } finally {
                if (data != null) {
                    data.reuse();
                }
            }
            return new StagedMedia(false, copyFileToAttachments, null, null, null); // webPage doesn't need file processing
        } else {
            out.documentType = AyuConstants.DOCUMENT_TYPE_FILE;
        }
        int docType = out.documentType;
        if (docType == AyuConstants.DOCUMENT_TYPE_PHOTO || docType == AyuConstants.DOCUMENT_TYPE_FILE || docType == AyuConstants.DOCUMENT_TYPE_STORY) {
            StagedFile main = null;
            String directPath = null;
            StagedFile thumb = null;
            try {
                if (copyFileToAttachments) {
                    main = stageAttachment(prefs, deleteSource);
                    TLRPC.MessageMedia m = MessageObject.getMedia(prefs.getMessage());
                    if (m != null && MessageObject.isVideoDocument(m.document)) {
                        Iterator<TLRPC.PhotoSize> it = m.document.thumbs.iterator();
                        while (it.hasNext()) {
                            TLRPC.PhotoSize next = it.next();
                            if (next instanceof TLRPC.TL_photoSize) {
                                StagedFile st = stageAttachment(prefs.getAccountId(), next, deleteSource);
                                if (st != null) {
                                    thumb = st;
                                    break;
                                }
                            }
                        }
                    }
                } else {
                    directPath = FileLoader.getInstance(prefs.getAccountId()).getPathToMessage(prefs.getMessage()).getAbsolutePath();
                }
                TLRPC.Document doc = message.media.document;
                if (doc != null) {
                    out.documentAttributesSerialized = serializeMultiple(doc.attributes);
                    out.thumbsSerialized = serializeMultiple(doc.thumbs);
                    out.mimeType = doc.mime_type;
                }
            } catch (Exception e) {
                FileLog.e("failed to stage media", e);
            }
            return new StagedMedia(true, copyFileToAttachments, main, directPath, thumb);
        }
        return new StagedMedia(false, copyFileToAttachments, null, null, null);
    }

    // Band W: called through AyuAttachments.commit(...) with the monitor held, immediately before the
    // row insert, so the reuse lookup and the insert are one critical section a concurrent delete
    // can't split. Not callable off-lock: it needs the Tx, which only exists inside a commit body.
    // Only cheap file predicates, token places/discards and Room reads happen here; the bytes were
    // already copied in stageMedia.
    public static void finalizeMediaLocked(AyuSavePreferences prefs, AyuMessageBase out, StagedMedia staged, AyuAttachments.Tx tx) {
        if (staged == null || !staged.processFiles) {
            return;
        }
        TLRPC.Message message = prefs.getMessage();
        File finalFile = new File("/");
        if (staged.copyToAttachments) {
            File reuse = null;
            if (out.mediaId != 0) {
                String existing = AyuMessagesController.getInstance().findExistingAttachmentPath(prefs.getUserId(), prefs.getDialogId(), prefs.getMessageId(), out.mediaId);
                if (existing != null) {
                    File ef = new File(existing);
                    // re-check at point of use: a concurrent delete may have unlinked it since the lookup
                    if (ef.exists() && ef.length() > 0) {
                        reuse = ef;
                    }
                }
            }
            if (reuse != null) {
                // we already hold this exact media from an earlier revision or the delete path; reuse
                // it and drop the temps we speculatively staged rather than copying again
                if (staged.main != null) {
                    tx.discard(staged.main.token);
                }
                if (staged.thumb != null) {
                    tx.discard(staged.thumb.token);
                }
                finalFile = reuse;
                out.hqThumbPath = AyuMessagesController.getInstance().findExistingThumbPath(prefs.getUserId(), prefs.getDialogId(), prefs.getMessageId(), out.mediaId);
            } else {
                if (staged.main != null) {
                    File placed = tx.place(staged.main.token, staged.main.finalName);
                    if (placed != null) {
                        finalFile = placed;
                    }
                }
                if (staged.thumb != null) {
                    File placedThumb = tx.place(staged.thumb.token, staged.thumb.finalName);
                    if (placedThumb != null && placedThumb.exists()) {
                        out.hqThumbPath = placedThumb.getAbsolutePath();
                    }
                }
            }
        } else if (staged.directPath != null) {
            finalFile = new File(staged.directPath);
        }
        String absolutePath = finalFile.getAbsolutePath();
        if (absolutePath.equals("/")) {
            absolutePath = null;
        }
        out.mediaPath = absolutePath;

        // Serialize media object to preserve metadata even if file doesn't exist
        // This allows showing file info, thumbnails, and attributes even without the actual file
        if ((out.mediaPath == null || out.documentType == AyuConstants.DOCUMENT_TYPE_STORY) && message.media != null) {
            NativeByteBuffer data = null;
            try {
                int size = message.media.getObjectSize();
                if (size > 0) {
                    data = new NativeByteBuffer(size);
                    message.media.serializeToStream(data);
                    data.rewind();
                    byte[] serialized = new byte[data.buffer.remaining()];
                    data.buffer.get(serialized);
                    out.documentSerialized = serialized;
                    if (BuildVars.LOGS_ENABLED) {
                        Log.d(TAG, "Media file not found, saved metadata for message " + message.id);
                    }
                }
            } catch (Exception e) {
                FileLog.e("Failed to serialize media metadata", e);
            } finally {
                if (data != null) {
                    data.reuse();
                }
            }
        }
    }

    // Band L: resolve a TL object to its cache path and stage a temp copy holding no lock. Returns
    // the temp plus the name it should take, or null if the source couldn't be staged.
    private static StagedFile stageAttachment(int accountId, TLObject object, boolean deleteSource) {
        File pathToAttach = FileLoader.getInstance(accountId).getPathToAttach(object);
        if (!pathToAttach.exists()) {
            File pathToAttach2 = FileLoader.getInstance(accountId).getPathToAttach(object, true);
            if (!pathToAttach2.getAbsolutePath().endsWith("/cache")) {
                pathToAttach = pathToAttach2;
            }
        }
        return stageAttachment(pathToAttach, AyuUtils.getFilename(object, pathToAttach), deleteSource);
    }

    private static StagedFile stageAttachment(AyuSavePreferences prefs, boolean deleteSource) {
        TLRPC.Message message = prefs.getMessage();
        if (message == null) return null;
        if (message.media instanceof TLRPC.TL_messageMediaStory story && story.storyItem != null && story.storyItem.media != null) {
            TLRPC.MessageMedia storyMedia = story.storyItem.media;
            if (storyMedia.document != null) {
                return stageAttachment(prefs.getAccountId(), storyMedia.document, deleteSource);
            } else if (storyMedia.photo != null) {
                return stageAttachment(prefs.getAccountId(), storyMedia.photo, deleteSource);
            }
        }
        File pathToMessage = FileLoader.getInstance(prefs.getAccountId()).getPathToMessage(message);
        if (!pathToMessage.exists() && !pathToMessage.getAbsolutePath().endsWith("/cache")) {
            pathToMessage = FileLoader.getInstance(prefs.getAccountId()).getPathToMessage(message, false);
        }
        if (pathToMessage.exists() || message.media.document == null) {
            if (pathToMessage.exists() || message.media.photo == null) {
                return stageAttachment(pathToMessage, AyuUtils.getFilename(message, pathToMessage), deleteSource);
            }
            return stageAttachment(prefs.getAccountId(), message.media.photo, deleteSource);
        }
        return stageAttachment(prefs.getAccountId(), message.media.document, deleteSource);
    }

    // Band L core: copy (or, on the force/TTL path, move) an existing source into a staged temp, or
    // decrypt an encrypted cache file into one through an owner-owned stream. Holds no lock. The
    // token is placed at finalName later, under the monitor, by finalizeMediaLocked.
    private static StagedFile stageAttachment(File source, String finalName, boolean deleteSource) {
        if (source.exists()) {
            AyuAttachments.StagedToken token = AyuAttachments.stage(source, deleteSource);
            if (token == null) {
                if (BuildVars.LOGS_ENABLED) {
                    Log.e(TAG, "Failed to stage media file from " + source.getAbsolutePath());
                }
                return null;
            }
            return new StagedFile(token, finalName);
        }

        File directory = FileLoader.getDirectory(4);
        File encryptedFile = new File(directory, source.getName() + ".enc");
        if (encryptedFile.exists()) {
            File internalCacheDir = FileLoader.getInternalCacheDir();
            File keyFile = new File(internalCacheDir, encryptedFile.getName() + ".key");
            if (BuildVars.LOGS_ENABLED) {
                Log.d(TAG, "Found encrypted file, checking for key: " + keyFile.getAbsolutePath() + " exists=" + keyFile.exists());
            }
            if (keyFile.exists()) {
                AyuAttachments.StagedToken token = AyuAttachments.stageViaWriter(out -> {
                    try (EncryptedFileInputStream inputStream = new EncryptedFileInputStream(encryptedFile, keyFile)) {
                        byte[] buffer = new byte[4 * 1024];
                        int read;
                        while ((read = inputStream.read(buffer)) != -1) {
                            out.write(buffer, 0, read);
                        }
                    }
                });
                if (token == null) {
                    return null;
                }
                if (BuildVars.LOGS_ENABLED) {
                    Log.d(TAG, "Successfully decrypted and staged media for " + finalName);
                }
                return new StagedFile(token, finalName);
            }
        }

        if (BuildVars.LOGS_ENABLED) {
            Log.d(TAG, "Media file not found at " + source.getAbsolutePath() + ", will save metadata only");
        }
        return null;
    }

    public static byte[] serializeMultiple(ArrayList<? extends TLObject> arrayList) {
        if (arrayList == null || arrayList.isEmpty()) {
            return null;
        }
        NativeByteBuffer data = null;
        try {
            int totalSize = 0;
            for (TLObject obj : arrayList) {
                if (obj != null) {
                    totalSize += obj.getObjectSize();
                }
            }
            if (totalSize <= 0) {
                return null;
            }
            data = new NativeByteBuffer(totalSize);
            for (TLObject o : arrayList) {
                if (o != null) {
                    o.serializeToStream(data);
                }
            }
            data.rewind();
            byte[] serializedBytes = new byte[data.remaining()];
            data.buffer.get(serializedBytes);
            return serializedBytes;
        } catch (Exception e) {
            FileLog.e("Failed to allocate buffer for message entities", e);
            return null;
        } finally {
            if (data != null) {
                data.reuse();
            }
        }
    }

    private static boolean shouldSaveMedia(AyuSavePreferences prefs) {
        if (NaConfig.INSTANCE.getMessageSavingSaveMedia().Bool() && prefs.getMessage().media != null) {
            if (DialogObject.isUserDialog(prefs.getDialogId())) {
                return NaConfig.INSTANCE.getSaveMediaInPrivateChats().Bool();
            }
            TLRPC.Chat chat = MessagesController.getInstance(prefs.getAccountId()).getChat(Math.abs(prefs.getDialogId()));
            if (chat == null) {
                Log.d(TAG, "chat is null so saving media just in case");
                return true;
            }
            boolean isPublic = ChatObject.isPublic(chat);
            if (ChatObject.isChannelAndNotMegaGroup(chat)) {
                if (isPublic && NaConfig.INSTANCE.getSaveMediaInPublicChannels().Bool()) {
                    return true;
                }
                return !isPublic && NaConfig.INSTANCE.getSaveMediaInPrivateChannels().Bool();
            } else if (isPublic && NaConfig.INSTANCE.getSaveMediaInPublicGroups().Bool()) {
                return true;
            } else {
                return !isPublic && NaConfig.INSTANCE.getSaveMediaInPrivateGroups().Bool();
            }
        }
        return false;
    }

    public static boolean shouldSaveMedia(int accountId, long dialogId) {
        if (NaConfig.INSTANCE.getEnableSaveDeletedMessages().Bool() && NaConfig.INSTANCE.getMessageSavingSaveMedia().Bool()) {
            if (DialogObject.isUserDialog(dialogId)) {
                return NaConfig.INSTANCE.getSaveMediaInPrivateChats().Bool();
            }
            TLRPC.Chat chat = MessagesController.getInstance(accountId).getChat(Math.abs(dialogId));
            if (chat == null) {
                return true;
            }
            boolean isPublic = ChatObject.isPublic(chat);
            if (ChatObject.isChannelAndNotMegaGroup(chat)) {
                if (isPublic && NaConfig.INSTANCE.getSaveMediaInPublicChannels().Bool()) {
                    return true;
                }
                return !isPublic && NaConfig.INSTANCE.getSaveMediaInPrivateChannels().Bool();
            } else if (isPublic && NaConfig.INSTANCE.getSaveMediaInPublicGroups().Bool()) {
                return true;
            } else {
                return !isPublic && NaConfig.INSTANCE.getSaveMediaInPrivateGroups().Bool();
            }
        }
        return false;
    }

    public static File decryptAndSaveMedia(String fileName, File encryptedFile, MessageObject messageObject) {
        if (!NaConfig.INSTANCE.getEnableSaveDeletedMessages().Bool()) {
            return null;
        }
        if (TextUtils.isEmpty(fileName)) {
            if (encryptedFile == null || !encryptedFile.exists()) {
                return null;
            }
            fileName = encryptedFile.getName();
            if (fileName.endsWith(".enc")) {
                fileName = fileName.substring(0, fileName.length() - 4);
            }
        }
        long dialogId = messageObject != null ? messageObject.getDialogId() : 0;
        int messageId = messageObject != null ? messageObject.getId() : 0;
        String outputFileName = "ttl_" + dialogId + "_" + messageId + "_" + fileName;
        // Band R: if we already decrypted this file, hand it back without the lock or the disk
        File existing = AyuAttachments.resolveExisting(outputFileName);
        if (existing != null) {
            if (BuildVars.LOGS_ENABLED) {
                Log.d(TAG, "Decrypted file already exists: " + existing.getAbsolutePath());
            }
            return existing;
        }
        if (messageObject != null) {
            long userId = UserConfig.getInstance(messageObject.currentAccount).getClientUserId();
            String savedMediaPath = AyuMessagesController.getInstance().getMediaPath(userId, dialogId, messageId);
            if (!TextUtils.isEmpty(savedMediaPath)) {
                File savedMedia = new File(savedMediaPath);
                if (savedMedia.exists() && savedMedia.length() > 0) {
                    if (BuildVars.LOGS_ENABLED) {
                        Log.d(TAG, "Using saved media: " + savedMedia.getAbsolutePath());
                    }
                    return savedMedia;
                }
            }
        }
        File keyFile = new File(FileLoader.getInternalCacheDir(), encryptedFile.getName() + ".key");
        if (!keyFile.exists()) {
            if (BuildVars.LOGS_ENABLED) {
                Log.d(TAG, "Key file not found: " + keyFile.getAbsolutePath());
            }
            return null;
        }
        // Band L: decrypt into a staged temp through an owner-owned stream, holding no lock
        AyuAttachments.StagedToken token = AyuAttachments.stageViaWriter(out -> {
            try (EncryptedFileInputStream inputStream = new EncryptedFileInputStream(encryptedFile, keyFile)) {
                byte[] readBuffer = new byte[8 * 1024];
                int bytesRead;
                while ((bytesRead = inputStream.read(readBuffer)) != -1) {
                    out.write(readBuffer, 0, bytesRead);
                }
            }
        });
        if (token == null) {
            FileLog.e("Failed to decrypt and save media");
            return null;
        }
        // Band W: place it. If another caller decrypted the same file first, keep theirs and drop ours
        File placed = AyuAttachments.place(token, outputFileName);
        if (placed != null && BuildVars.LOGS_ENABLED) {
            Log.d(TAG, "Successfully decrypted and saved media to: " + placed.getAbsolutePath());
        }
        return placed;
    }

    public static File findExistingFileByBaseNameFast(String baseName) {
        return AyuAttachments.resolveExisting(baseName);
    }

    public static File getLargestNonEmpty(File[] files) {
        if (files == null || files.length == 0) {
            return null;
        }
        File best = null;
        long bestSize = -1;
        for (File f : files) {
            long len = f == null ? 0 : f.length();
            if (len > bestSize) {
                best = f;
                bestSize = len;
            }
        }
        return (bestSize > 0) ? best : null;
    }

    public static File saveDownloadedMedia(File downloadedFile) {
        if (!NaConfig.INSTANCE.getEnableSaveDeletedMessages().Bool()) {
            return null;
        }
        if (downloadedFile == null) {
            return null;
        }
        String filename = downloadedFile.getName();
        // Band R: already saved?
        File existing = AyuAttachments.resolveExisting(filename);
        if (existing != null) {
            return existing;
        }
        if (!downloadedFile.exists()) {
            return null;
        }
        // Band L: copy into a staged temp holding no lock; place it (copy-not-move, source stays)
        AyuAttachments.StagedToken staged = AyuAttachments.stage(downloadedFile, false);
        if (staged == null) {
            return null;
        }
        return AyuAttachments.place(staged, filename);
    }

    private static String ensureAttachmentAndUpdateMediaPath(AyuMessageBase base, TLRPC.Message message, int accountId) {
        try {
            final long userId = base.userId;
            final long dialogId = base.dialogId;
            final int messageId = base.messageId;
            String baseName = AyuUtils.getBaseFilename(message);
            if (TextUtils.isEmpty(baseName)) {
                return null;
            }
            String filePath = AyuUtils.getPathToMessage(accountId, message);
            // check if the file exists in the telegram cache folder (successfully downloaded after deserialization and saved by DELETED_MEDIA_LOADED_NOTIFICATION)
            if (!TextUtils.isEmpty(filePath)) {
                File from = new File(filePath);
                if (from.exists() && !AyuAttachments.isUnder(from)) {
                    File to = AyuAttachments.resolve(baseName);
                    if (to != null) {
                        // Copy into attachments and point the row at the result in one locked step, so a
                        // concurrent delete can't unlink the file between resolving it and recording it.
                        Utilities.globalQueue.postRunnable(() ->
                                AyuMessagesController.getInstance().adoptAttachment(userId, dialogId, messageId, from, to, baseName, null));
                    }
                    File found = findExistingFileByBaseNameFast(baseName);
                    return found != null ? found.getAbsolutePath() : null;
                }
            }
            File found = findExistingFileByBaseNameFast(baseName);
            String fallbackName = null;
            if (found == null && !TextUtils.isEmpty(filePath)) {
                fallbackName = new File(filePath).getName();
                found = findExistingFileByBaseNameFast(fallbackName);
            }
            if (found != null) {
                // update mediaPath in db when we discover an attachments copy
                final String newPath = found.getAbsolutePath();
                final String fb = fallbackName;
                Utilities.globalQueue.postRunnable(() ->
                        AyuMessagesController.getInstance().adoptAttachment(userId, dialogId, messageId, null, null, baseName, fb));
                return newPath;
            }
        } catch (Exception e) {
            FileLog.e("ensureAttachmentAndUpdateMediaPath", e);
            return null;
        }
        return null;
    }

    public static boolean isExpiredDocument(MessageObject msg) {
        if (msg == null || msg.messageOwner == null || msg.messageOwner.media == null) {
            return false;
        }
        return msg.messageOwner.media.document instanceof TLRPC.TL_documentEmpty
                || msg.messageOwner.media instanceof TLRPC.TL_messageMediaDocument
                && msg.messageOwner.media.document == null;
    }

    public static boolean isExpiredPhoto(MessageObject msg) {
        if (msg == null || msg.messageOwner == null || msg.messageOwner.media == null) {
            return false;
        }
        return msg.messageOwner.media instanceof TLRPC.TL_messageMediaPhoto
                && msg.messageOwner.media.photo instanceof TLRPC.TL_photoEmpty;
    }

    public static TLRPC.Peer getFromPeer(MessagesController messagesController, long peerId, TLRPC.Chat dialogChat) {
        if (peerId < 0) {
            TLRPC.Chat chat = messagesController.getChat(-peerId);
            if (chat == null && dialogChat != null) {
                boolean isGroup = ChatObject.isChannel(dialogChat) && dialogChat.megagroup;
                if (isGroup) {
                    TLRPC.TL_peerChannel peerChannel = new TLRPC.TL_peerChannel();
                    peerChannel.channel_id = -peerId;
                    return peerChannel;
                }
            }
        }
        return messagesController.getPeer(peerId);
    }

}
