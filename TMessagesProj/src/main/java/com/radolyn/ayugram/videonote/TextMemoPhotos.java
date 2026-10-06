package com.radolyn.ayugram.videonote;

import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import android.text.TextUtils;

import org.telegram.messenger.AccountInstance;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MediaController;
import org.telegram.messenger.SendMessagesHelper;
import org.telegram.messenger.Utilities;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Photos for the text memo, picked with the system picker so no storage permission is asked and the app lock is never
 * involved. A picked uri is only readable while the memo lives, so each one is copied to the cache at pick time and the
 * memo holds plain file paths from then on. Photos are never part of the chat's draft: leaving without sending drops them.
 */
final class TextMemoPhotos {

    static final int MAX = 10;

    private TextMemoPhotos() {
    }

    static Intent pickIntent() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/*");
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        return intent;
    }

    /** Copies what the picker returned to the cache off the UI thread, then hands back the paths on it. Failures are skipped. */
    static void importAsync(Intent data, int room, java.util.function.Consumer<List<String>> done) {
        List<Uri> uris = new ArrayList<>();
        if (data != null) {
            ClipData clip = data.getClipData();
            if (clip != null) {
                for (int i = 0; i < clip.getItemCount() && uris.size() < room; i++) {
                    uris.add(clip.getItemAt(i).getUri());
                }
            } else if (data.getData() != null && room > 0) {
                uris.add(data.getData());
            }
        }
        Utilities.globalQueue.postRunnable(() -> {
            List<String> paths = new ArrayList<>();
            for (Uri uri : uris) {
                try {
                    String path = uri == null ? null : MediaController.copyFileToCache(uri, "jpg");
                    if (!TextUtils.isEmpty(path)) {
                        paths.add(path);
                    }
                } catch (Throwable e) {
                    FileLog.e(e);
                }
            }
            org.telegram.messenger.AndroidUtilities.runOnUIThread(() -> done.accept(paths));
        });
    }

    /** An album when there are several; the text rides on the first photo as its caption. The copies are removed once sent. */
    static void send(AccountInstance account, List<String> paths, String caption, long dialogId) {
        ArrayList<SendMessagesHelper.SendingMediaInfo> infos = new ArrayList<>();
        for (String path : paths) {
            if (!new File(path).exists()) {
                continue;
            }
            SendMessagesHelper.SendingMediaInfo info = new SendMessagesHelper.SendingMediaInfo();
            info.path = path;
            info.canDeleteAfter = true;
            infos.add(info);
        }
        if (infos.isEmpty()) {
            // Every copy is gone: the text still goes
            if (!TextUtils.isEmpty(caption)) {
                SendMessagesHelper.prepareSendingText(account, caption, dialogId, true, 0, 0, 0);
            }
            return;
        }
        infos.get(0).caption = caption;
        SendMessagesHelper.prepareSendingMedia(account, infos, dialogId, null, null, null, null, false, infos.size() > 1, null, true, 0, 0, 0, false, null, null, 0, false, 0, 0, null);
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
