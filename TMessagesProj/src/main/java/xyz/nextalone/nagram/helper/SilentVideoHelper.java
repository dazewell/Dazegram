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
}
