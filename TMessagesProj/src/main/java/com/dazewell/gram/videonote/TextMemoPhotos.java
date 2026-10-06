package com.dazewell.gram.videonote;

import android.content.ClipData;
import android.content.ContentResolver;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.text.TextUtils;
import android.webkit.MimeTypeMap;

import org.telegram.messenger.AccountInstance;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MediaController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.SendMessagesHelper;
import org.telegram.messenger.Utilities;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.BiConsumer;

/**
 * Photos and videos for the text memo, picked with the system picker so no storage permission is asked and the app lock
 * is never involved. A picked uri is only readable while the memo lives, so each one is copied to the cache at pick time and
 * the memo holds plain file paths from then on. Media is never part of the chat's draft: leaving without sending drops it.
 */
final class TextMemoPhotos {

    // Photos and videos together
    static final int MAX = 10;
    // A video is copied whole before it is sent, so a very large one is refused rather than filling the cache
    private static final long MAX_VIDEO_BYTES = 100L * 1024 * 1024;

    private TextMemoPhotos() {
    }

    static Intent pickIntent() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/*", "video/*"});
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        return intent;
    }

    /** By the copy's extension, which keeps the picked file's own name. */
    static boolean isVideo(String path) {
        int dot = path == null ? -1 : path.lastIndexOf('.');
        if (dot < 0) {
            return false;
        }
        String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(path.substring(dot + 1).toLowerCase(Locale.US));
        return mime != null && mime.startsWith("video/");
    }

    /**
     * Copies what the picker returned to the cache off the UI thread, then hands back the paths and how many picks were
     * left out (past the limit, too large, unreadable) on it.
     */
    static void importAsync(Intent data, int room, BiConsumer<List<String>, Integer> done) {
        List<Uri> uris = new ArrayList<>();
        if (data != null) {
            ClipData clip = data.getClipData();
            if (clip != null) {
                for (int i = 0; i < clip.getItemCount(); i++) {
                    uris.add(clip.getItemAt(i).getUri());
                }
            } else if (data.getData() != null) {
                uris.add(data.getData());
            }
        }
        Utilities.globalQueue.postRunnable(() -> {
            List<String> paths = new ArrayList<>();
            int skipped = 0;
            for (Uri uri : uris) {
                if (paths.size() >= room) {
                    skipped++;
                    continue;
                }
                try {
                    String path = uri == null ? null : copy(uri);
                    if (TextUtils.isEmpty(path)) {
                        skipped++;
                    } else {
                        paths.add(path);
                    }
                } catch (Throwable e) {
                    FileLog.e(e);
                    skipped++;
                }
            }
            int left = skipped;
            AndroidUtilities.runOnUIThread(() -> done.accept(paths, left));
        });
    }

    private static String copy(Uri uri) {
        ContentResolver resolver = ApplicationLoader.applicationContext.getContentResolver();
        String type = resolver.getType(uri);
        boolean video = type != null && type.startsWith("video/");
        if (video && size(resolver, uri) > MAX_VIDEO_BYTES) {
            return null;
        }
        // The limit also bounds a provider that doesn't report a size
        String path = video ? MediaController.copyFileToCache(uri, "mp4", MAX_VIDEO_BYTES) : MediaController.copyFileToCache(uri, "jpg");
        if (video && path != null && !isVideo(path)) {
            // The copy keeps the provider's file name, and the flag is re-derived from its extension when sending
            File renamed = new File(path + ".mp4");
            if (new File(path).renameTo(renamed)) {
                return renamed.getAbsolutePath();
            }
        }
        return path;
    }

    // -1 when the provider doesn't say
    private static long size(ContentResolver resolver, Uri uri) {
        try (Cursor cursor = resolver.query(uri, new String[]{OpenableColumns.SIZE}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0)) {
                return cursor.getLong(0);
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
        return -1;
    }

    /** An album when there are several; the text rides on the first item as its caption. */
    static void send(AccountInstance account, List<String> paths, String caption, long dialogId) {
        ArrayList<SendMessagesHelper.SendingMediaInfo> infos = new ArrayList<>();
        for (String path : paths) {
            if (!new File(path).exists()) {
                continue;
            }
            SendMessagesHelper.SendingMediaInfo info = new SendMessagesHelper.SendingMediaInfo();
            info.path = path;
            info.isVideo = isVideo(path);
            infos.add(info);
        }
        if (infos.isEmpty()) {
            // Every copy is gone: the text still goes
            if (!TextUtils.isEmpty(caption)) {
                SendMessagesHelper.prepareSendingText(account, caption, dialogId, true, 0, 0, 0);
            }
            return;
        }
        // A caption has its own, shorter limit than a message
        boolean captionFits = caption == null || caption.length() <= MessagesController.getInstance(account.getCurrentAccount()).getCaptionMaxLengthLimit();
        infos.get(0).caption = captionFits ? caption : null;
        SendMessagesHelper.prepareSendingMedia(account, infos, dialogId, null, null, null, null, false, infos.size() > 1, null, true, 0, 0, 0, false, null, null, 0, false, 0, 0, null);
        if (!captionFits) {
            SendMessagesHelper.prepareSendingText(account, caption, dialogId, true, 0, 0, 0);
        }
    }

    static void discard(List<String> paths) {
        for (String path : paths) {
            try {
                new File(path).delete();
            } catch (Throwable ignore) {
            }
        }
    }
}
