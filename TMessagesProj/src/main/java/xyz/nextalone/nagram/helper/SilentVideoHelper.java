package xyz.nextalone.nagram.helper;

import org.telegram.messenger.VideoEditedInfo;

// NagramX (#silent-video): the single predicate for "the user chose GIF (not a silent video) in the gallery
// editor for this muted clip". This is the one meaning the send path needs, and it is deliberately NOT the same
// as "muted". VideoEditedInfo.muted has several producers, and ChatActivityEnterView force-sets muted = true when
// sending an edited GIF from the GIF panel; keying the send-path GIF-vs-video decisions on muted alone would
// regress those edited-GIF sends into silent videos. Keying them on this predicate keeps every non-editor
// producer on today's behaviour by construction: naxSilentVideo is only ever set in the gallery editor, so
// everywhere else isGifSend() == muted, unchanged.
//
// Called fully-qualified from the base files so no import enters them. Some call sites use it negated (the
// nosound_video hint), so it is phrased as the positive "is this a GIF send" to keep both readings plain.
public final class SilentVideoHelper {

    private SilentVideoHelper() {
    }

    public static boolean isGifSend(VideoEditedInfo videoEditedInfo) {
        return videoEditedInfo != null && videoEditedInfo.muted && !videoEditedInfo.naxSilentVideo;
    }

    // NAX_SMOKE_silent-video: temporary reachability diagnostics for the smoke build, removed in its own follow-up
    // commit. Non-sensitive operands only — booleans, an account index and build identity, never any message
    // content. Answers whether a muted clip switched to Video reaches the silent-video branch and never the GIF
    // branch (and vice versa). Uses Log.i/.e on purpose: Log.v/.d are stripped from the release build.
    public static void naxSmokeDecision(int account, boolean muteVideo, boolean sendSilentVideo, boolean silentVideo, boolean avatar) {
        android.util.Log.i("NagramX", "NAX_SMOKE_silent-video BEGIN build=" + org.telegram.messenger.BuildConfig.BUILD_VERSION_STRING
                + " app=" + org.telegram.messenger.BuildConfig.APPLICATION_ID + " account=" + account
                + " muteVideo=" + muteVideo + " sendSilentVideo=" + sendSilentVideo + " avatar=" + avatar);
        if (silentVideo) {
            android.util.Log.i("NagramX", "NAX_SMOKE_silent-video EXPECTED path=silent-video account=" + account);
        } else if (muteVideo && !avatar) {
            android.util.Log.i("NagramX", "NAX_SMOKE_silent-video path=gif account=" + account);
        }
        if (sendSilentVideo && !silentVideo && !avatar) {
            android.util.Log.e("NagramX", "NAX_SMOKE_silent-video FORBIDDEN gif-branch-while-video-chosen account=" + account);
        } else if (!sendSilentVideo && silentVideo) {
            android.util.Log.e("NagramX", "NAX_SMOKE_silent-video FORBIDDEN silent-branch-while-gif-chosen account=" + account);
        }
    }

    public static void naxSmokeResult(int account, VideoEditedInfo videoEditedInfo) {
        android.util.Log.i("NagramX", "NAX_SMOKE_silent-video END account=" + account
                + " muted=" + (videoEditedInfo != null && videoEditedInfo.muted)
                + " naxSilentVideo=" + (videoEditedInfo != null && videoEditedInfo.naxSilentVideo)
                + " isGifSend=" + isGifSend(videoEditedInfo));
    }
}
