package xyz.nextalone.nagram.helper;

import android.content.Context;
import android.content.SharedPreferences;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.AutoDeleteMediaTask;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.VideoEditedInfo;

import java.io.File;
import java.util.Map;

// NagramX: durable record of an unsent round-video draft -- the finished-but-unsent clip sitting in the
// preview strip. Upstream already persists voice drafts (MediaController's 2voicedrafts_<account>) but a
// round video that was recorded and not yet sent is lost the moment the app is backgrounded or killed --
// worst with passcode lock = Immediately, which tears down and rebuilds the chat. This writes down just
// enough to rebuild that preview and re-send from disk: the plaintext mp4 path plus scalars. No view,
// recorder or upload-transport state crosses this boundary. The mp4 stays on disk (lockFile'd by the
// InstantCameraView adopt path) and re-uploads on send, so secret chats need no special handling -- the
// on-disk file is plaintext exactly like a normal round video, and encryption happens later in the upload.
public final class VideoDraftStore {

    private VideoDraftStore() {
    }

    private static final String PREFIX = "videodraft_";
    // dazewell's choice at the gate: 24h, not the 7 days originally floated. A draft older than this is
    // swept rather than resurrected -- a day-old accidental recording is noise, not something to restore.
    private static final long EXPIRY_MS = 24L * 60 * 60 * 1000;

    public static final class Entry {
        public final String path;
        public final int duration;
        public final boolean voiceOnce;
        public final long savedAt;

        Entry(String path, int duration, boolean voiceOnce, long savedAt) {
            this.path = path;
            this.duration = duration;
            this.voiceOnce = voiceOnce;
            this.savedAt = savedAt;
        }
    }

    private static SharedPreferences prefs(int account) {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFIX + account, Context.MODE_PRIVATE);
    }

    // One derivation, used by save/load/clear alike, so a key can never be written under one shape and read
    // under another. dialogId and topicId are both numeric, so "<dialog>_<topic>" never collides with the
    // "_path"/"_dur"/"_once"/"_at" field suffixes appended below.
    private static String key(long dialogId, long topicId) {
        return dialogId + "_" + topicId;
    }

    public static void save(int account, long dialogId, long topicId, String path, int duration, boolean voiceOnce) {
        if (path == null) {
            return;
        }
        String k = key(dialogId, topicId);
        prefs(account).edit()
                .putString(k + "_path", path)
                .putInt(k + "_dur", duration)
                .putBoolean(k + "_once", voiceOnce)
                .putLong(k + "_at", System.currentTimeMillis())
                .apply();
    }

    // Self-validating: a record whose file is gone (chat cleared, swept, half-written then collected) or
    // older than the expiry window is dropped on read, so a caller never restores a preview backed by a
    // missing or stale file. An expired/missing entry is removed and its file unlocked here.
    public static Entry load(int account, long dialogId, long topicId) {
        String k = key(dialogId, topicId);
        SharedPreferences p = prefs(account);
        String path = p.getString(k + "_path", null);
        if (path == null) {
            return null;
        }
        long savedAt = p.getLong(k + "_at", 0);
        File f = new File(path);
        if (System.currentTimeMillis() - savedAt > EXPIRY_MS || !f.exists() || f.length() == 0) {
            remove(account, dialogId, topicId, true);
            return null;
        }
        return new Entry(path, p.getInt(k + "_dur", 0), p.getBoolean(k + "_once", false), savedAt);
    }

    // Draft consumed by a send: drop the record but do NOT unlock or delete the file -- from here the
    // outgoing-message machinery owns its lifecycle, exactly as it would for a round video sent without
    // ever having been persisted.
    public static void clearOnSend(int account, long dialogId, long topicId) {
        remove(account, dialogId, topicId, false);
    }

    // User discarded the preview: drop the record, unlock and delete the file. cancel() can't be relied on
    // to delete it -- on a rebuilt InstantCameraView it bails early at its textureView==null guard -- so a
    // discarded draft would otherwise leave both a locked orphan file and a record that resurrects it on the
    // next open.
    public static void discard(int account, long dialogId, long topicId) {
        remove(account, dialogId, topicId, true);
    }

    // 24h sweep of one account's records, run on chat open. Unlocks (and deletes) each expired file so an
    // expired draft doesn't leave a permanently locked, invisible orphan.
    public static void sweepExpired(int account) {
        SharedPreferences p = prefs(account);
        Map<String, ?> all = p.getAll();
        long now = System.currentTimeMillis();
        SharedPreferences.Editor editor = null;
        for (Map.Entry<String, ?> en : all.entrySet()) {
            String kk = en.getKey();
            if (!kk.endsWith("_at")) {
                continue;
            }
            Object v = en.getValue();
            long savedAt = v instanceof Long ? (Long) v : 0;
            if (now - savedAt <= EXPIRY_MS) {
                continue;
            }
            String base = kk.substring(0, kk.length() - 3); // strip "_at" to recover "<dialog>_<topic>"
            String path = p.getString(base + "_path", null);
            unlockAndDelete(path);
            if (editor == null) {
                editor = p.edit();
            }
            editor.remove(base + "_path").remove(base + "_dur").remove(base + "_once").remove(base + "_at");
        }
        if (editor != null) {
            editor.apply();
        }
    }

    // The round-video VideoEditedInfo shape, single-sourced for both the InstantCameraView send path (adopt)
    // and the enter-view preview (setVideoDraft), so the two can't drift. Mirrors what handleStopRecording
    // builds for a live finalize: roundVideo, 360x360, framerate 25, full range (start/end = -1). file/key/iv
    // are left null -- that is the normal "background upload not started yet" state, and the send path
    // propagates nulls and re-uploads from the file.
    public static VideoEditedInfo buildInfo(String path, long size, int duration) {
        VideoEditedInfo info = new VideoEditedInfo();
        info.roundVideo = true;
        info.startTime = -1;
        info.endTime = -1;
        info.estimatedSize = Math.max(1, size);
        info.framerate = 25;
        info.resultWidth = info.originalWidth = 360;
        info.resultHeight = info.originalHeight = 360;
        info.originalPath = path;
        info.estimatedDuration = duration;
        return info;
    }

    private static void remove(int account, long dialogId, long topicId, boolean unlock) {
        String k = key(dialogId, topicId);
        SharedPreferences p = prefs(account);
        if (unlock) {
            unlockAndDelete(p.getString(k + "_path", null));
        }
        p.edit()
                .remove(k + "_path")
                .remove(k + "_dur")
                .remove(k + "_once")
                .remove(k + "_at")
                .apply();
    }

    private static void unlockAndDelete(String path) {
        if (path == null) {
            return;
        }
        try {
            AutoDeleteMediaTask.unlockFile(path);
            new File(path).delete();
        } catch (Exception e) {
            FileLog.e(e);
        }
    }
}
