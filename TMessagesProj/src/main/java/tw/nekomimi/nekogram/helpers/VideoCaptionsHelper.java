package tw.nekomimi.nekogram.helpers;

import android.text.TextUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.UserConfig;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

// Closed captions for round video messages. Remembers which messages the user asked captions for,
// holds the timed transcriptions they've already paid for this session, and hands the right line to
// the caption strip as playback moves.
//
// Captions are per play, not a mode: the CC button on a video message captions the play it starts
// and then lets go, so nothing is ever transcribed behind the user's back and no video quietly
// spends API credit.
public class VideoCaptionsHelper {

    // Cheap to hold (a few kilobytes of text each) but there's no reason to keep every video the
    // user ever scrolled past, and this is deliberately session-only: nothing goes to disk.
    private static final int MAX_CACHED = 32;

    private static final LinkedHashMap<String, List<Segment>> cache = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, List<Segment>> eldest) {
            return size() > MAX_CACHED;
        }
    };

    // CC is a one-shot: it captions the play it started and nothing else, so this is a single slot
    // rather than a set of messages. Cleared when that playback ends or another message takes over.
    private static volatile String armedKey;

    // The play MediaController actually has running (or about to have running) muted, no-focus,
    // once-through. Deliberately a separate slot from armedKey: armedKey is set the moment CC is
    // pressed, which can be well before real playback starts (transcription still running) or can
    // outlive a cancelled request (cancelPlayback only clears pendingPlayback). If isQuiet aliased
    // armedKey, an ordinary tap on a message CC still had armed - after leaving and reopening the
    // chat mid-transcription, say - would inherit a mute it never asked for.
    private static volatile String quietKey;

    private static final List<Runnable> listeners = new CopyOnWriteArrayList<>();

    // Set when CC is pressed on a message that still has to be transcribed, so playback can start
    // once the text lands instead of running the first half of the video with an empty strip.
    private static volatile String pendingPlayback;

    public static class Segment {
        public final long startMs;
        public final long endMs;
        public final String text;

        public Segment(long startMs, long endMs, String text) {
            this.startMs = startMs;
            this.endMs = endMs;
            this.text = text;
        }
    }

    private static void notifyChanged() {
        // A listener can detach while it's being told (a chat closing mid-toggle); the copy-on-write
        // list iterates a snapshot, so whoever came after it still gets told.
        for (Runnable listener : listeners) {
            listener.run();
        }
    }

    public static void addListener(Runnable listener) {
        listeners.add(listener);
    }

    public static void removeListener(Runnable listener) {
        listeners.remove(listener);
    }

    // Local message ids are per-account, so the account has to be part of the key or two accounts
    // can hand each other the wrong transcription.
    private static String key(int account, MessageObject messageObject) {
        return account + "_" + messageObject.getDialogId() + "_" + messageObject.getId();
    }

    // Only worth offering where a transcript can actually come from, so the button doesn't sit on
    // every video promising something the account can't do.
    public static boolean canCaption(int account, MessageObject messageObject) {
        if (messageObject == null || !messageObject.isRoundVideo() || !messageObject.isSent()) {
            return false;
        }
        if (hasFinalText(messageObject)) {
            return true;
        }
        return UserConfig.getInstance(account).isPremium() || TranscribeHelper.useTranscribeAI(account);
    }

    public static boolean hasFinalText(MessageObject messageObject) {
        return messageObject != null && messageObject.messageOwner != null
                && messageObject.messageOwner.voiceTranscriptionFinal
                && !TextUtils.isEmpty(messageObject.messageOwner.voiceTranscription);
    }

    public static boolean isArmed(int account, MessageObject messageObject) {
        if (messageObject == null || !messageObject.isRoundVideo()) {
            return false;
        }
        return key(account, messageObject).equals(armedKey);
    }

    // Whether the play MediaController currently has on this message is the one CC started: muted,
    // no audio focus, no loop. Checked against quietKey, not armedKey - see the field comment for
    // why those two have to be able to disagree.
    public static boolean isQuiet(int account, MessageObject messageObject) {
        if (messageObject == null || !messageObject.isRoundVideo()) {
            return false;
        }
        return key(account, messageObject).equals(quietKey);
    }

    // Marks this exact message as the one whose upcoming/current play is the CC-quiet pass. Called
    // right before the immediate-path playMessage call, and from consumePlayback for the deferred
    // one, so quietKey is always set before MediaController's playMessage/onPlaybackStarting sees
    // this message - checkVolumeBarUI and the audio-focus guard both need the answer on that first
    // call, not one call later.
    public static void markQuiet(int account, MessageObject messageObject) {
        if (messageObject == null) {
            return;
        }
        quietKey = key(account, messageObject);
    }

    public static void arm(int account, MessageObject messageObject) {
        if (messageObject == null) {
            return;
        }
        armedKey = key(account, messageObject);
        notifyChanged();
    }

    // That play is over, so the next one only gets captions if CC is pressed again.
    public static void disarm() {
        if (armedKey == null && quietKey == null && pendingPlayback == null) {
            return;
        }
        armedKey = null;
        quietKey = null;
        pendingPlayback = null;
        notifyChanged();
    }

    // Playback ended. Only give up the slot when it's the message that was armed or quiet: a video
    // that wasn't downloaded yet gets torn down and restarted, and that isn't the play ending.
    public static void disarmMessage(MessageObject messageObject) {
        if (messageObject == null) {
            return;
        }
        String k = key(messageObject.currentAccount, messageObject);
        if (k.equals(armedKey) || k.equals(quietKey)) {
            disarm();
        }
    }

    // Something else is taking the player. Anything CC was holding belongs to a play that's over,
    // including a transcription still waiting to start one: it would otherwise land later and yank
    // playback away from whatever the user chose in the meantime.
    public static void onPlaybackStarting(MessageObject incoming) {
        if (incoming == null) {
            return;
        }
        String incomingKey = key(incoming.currentAccount, incoming);
        if (pendingPlayback != null && !pendingPlayback.equals(incomingKey)) {
            pendingPlayback = null;
        }
        if (quietKey != null && !quietKey.equals(incomingKey)) {
            quietKey = null;
        }
        // A foreign, non-CC play (an ordinary tap) is taking the player for a message CC still has
        // armed - e.g. the transcription is still running, or cancelPlayback already dropped
        // pendingPlayback when the chat was left but armedKey outlived it. markQuiet always runs
        // immediately before the CC flow's own playMessage call, so if this is genuinely that call
        // quietKey already equals incomingKey by now and this leaves armedKey alone. Otherwise this
        // play was never CC's to caption or mute, so the stale request is dropped rather than
        // reattaching itself to whatever the user just played normally.
        if (armedKey != null && armedKey.equals(incomingKey) && !incomingKey.equals(quietKey)) {
            armedKey = null;
            pendingPlayback = null;
            notifyChanged();
        } else if (armedKey != null && !armedKey.equals(incomingKey)) {
            armedKey = null;
            notifyChanged();
        }
    }

    // The spinner belongs on CC while CC is the one waiting, so this is deliberately narrower than
    // TranscribeButton.isTranscribing: a transcription the A button started is A's to report.
    // Paired with the live check so a request that dies without a result can't spin forever.
    public static boolean isTranscribingForCaptions(int account, MessageObject messageObject) {
        return messageObject != null
                && pendingPlayback != null
                && pendingPlayback.equals(key(account, messageObject))
                && org.telegram.ui.Components.TranscribeButton.isTranscribing(messageObject);
    }

    public static void awaitPlayback(int account, MessageObject messageObject) {
        pendingPlayback = messageObject == null ? null : key(account, messageObject);
    }

    // The chat that was waiting on a transcription is gone, so nothing should start playing when
    // the result eventually lands. Scoped to that chat: another one's pending CC press is not ours
    // to cancel, and this also runs when a chat view is merely rebuilt.
    public static void cancelPlayback(int account, long dialogId) {
        if (pendingPlayback != null && pendingPlayback.startsWith(account + "_" + dialogId + "_")) {
            pendingPlayback = null;
        }
    }

    // One-shot: the transcription notification fires more than once per request, and the video
    // should not restart every time.
    public static boolean consumePlayback(int account, MessageObject messageObject) {
        if (messageObject == null || pendingPlayback == null || !pendingPlayback.equals(key(account, messageObject))) {
            return false;
        }
        pendingPlayback = null;
        // NagramX: this is the deferred path's equivalent of markQuiet - the caller plays the
        // message right after this returns true, so quietKey has to be set before that happens.
        markQuiet(account, messageObject);
        return true;
    }

    public static void put(int account, MessageObject messageObject, TranscribeHelper.TimedResult result) {
        if (messageObject == null) {
            return;
        }
        if (result != null && result.isTimed()) {
            List<Segment> segments = new ArrayList<>(result.segments.size());
            for (TranscribeHelper.TimedSegment segment : result.segments) {
                segments.add(new Segment(segment.startMs, segment.endMs, segment.text));
            }
            synchronized (cache) {
                cache.put(key(account, messageObject), segments);
            }
        } else {
            // A retry against a provider that doesn't do timings replaces the transcript, so the
            // old segments would caption the new text.
            synchronized (cache) {
                cache.remove(key(account, messageObject));
            }
        }
        // Transcriptions come back off the network thread, and a strip that's already on screen has
        // to pick the result up without waiting for the next playback.
        AndroidUtilities.runOnUIThread(VideoCaptionsHelper::notifyChanged);
    }

    public static List<Segment> getSegments(int account, MessageObject messageObject) {
        if (messageObject == null) {
            return null;
        }
        synchronized (cache) {
            return cache.get(key(account, messageObject));
        }
    }

    // The transcription itself survives on the message, so a video transcribed in an earlier
    // session still has text to show even though its timings are long gone.
    public static String getUntimedText(MessageObject messageObject) {
        if (messageObject == null || messageObject.messageOwner == null) {
            return null;
        }
        if (messageObject.messageOwner.voiceTranscriptionFinal && !messageObject.isVoiceTranscriptionOpen()) {
            String text = messageObject.messageOwner.voiceTranscription;
            if (!TextUtils.isEmpty(text)) {
                return text;
            }
        }
        return null;
    }

    // Roughly a comfortable two lines under the circle.
    private static final int CHUNK_MAX_CHARS = 80;
    // Below this a sentence break isn't worth taking: it leaves a line on screen for a blink.
    private static final int CHUNK_MIN_CHARS = 32;

    // Half the providers only hand back prose, and any transcript from an earlier session has lost
    // its timings, so without this the strip would park on the opening line for the whole video.
    // People speak at a fairly even rate, so splitting the text up and giving each piece a share of
    // the duration proportional to its length tracks the speech closely enough to read along with.
    public static List<Segment> approximateSegments(String text, long durationMs) {
        if (TextUtils.isEmpty(text) || durationMs <= 0) {
            return null;
        }
        String trimmed = text.trim();
        List<String> chunks = new ArrayList<>();
        int start = 0, lastSpace = -1;
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            int length = i - start + 1;
            if (length >= CHUNK_MIN_CHARS && (c == '.' || c == '!' || c == '?' || c == '\u2026' || c == '\u3002')) {
                addChunk(chunks, trimmed.substring(start, i + 1));
                start = i + 1;
                lastSpace = -1;
                continue;
            }
            if (Character.isWhitespace(c)) {
                lastSpace = i;
            }
            if (length >= CHUNK_MAX_CHARS) {
                int cut = lastSpace > start ? lastSpace : i + 1;
                addChunk(chunks, trimmed.substring(start, cut));
                start = cut;
                lastSpace = -1;
            }
        }
        addChunk(chunks, trimmed.substring(start));
        if (chunks.isEmpty()) {
            return null;
        }
        int total = 0;
        for (String chunk : chunks) {
            total += chunk.length();
        }
        if (total == 0) {
            return null;
        }
        List<Segment> segments = new ArrayList<>(chunks.size());
        long from = 0;
        int consumed = 0;
        for (int i = 0; i < chunks.size(); i++) {
            consumed += chunks.get(i).length();
            long to = i == chunks.size() - 1 ? Math.max(durationMs, from + 1) : Math.max(durationMs * consumed / total, from + 1);
            segments.add(new Segment(from, to, chunks.get(i)));
            from = to;
        }
        return segments;
    }

    private static void addChunk(List<String> chunks, String chunk) {
        chunk = chunk.trim();
        if (!chunk.isEmpty()) {
            chunks.add(chunk);
        }
    }

    // Whisper pads the tail of a segment out to the start of the next one, so a line would hang on
    // screen through a long pause. Anything past this much silence blanks the strip instead.
    private static final long MAX_TRAILING_MS = 500;
    // Showing a line a beat early reads better than showing it a beat late.
    private static final long LEAD_IN_MS = 150;

    public static int findSegment(List<Segment> segments, long positionMs) {
        if (segments == null || segments.isEmpty()) {
            return -1;
        }
        long position = positionMs + LEAD_IN_MS;
        int low = 0, high = segments.size() - 1, found = -1;
        while (low <= high) {
            int mid = (low + high) >>> 1;
            Segment segment = segments.get(mid);
            if (position < segment.startMs) {
                high = mid - 1;
            } else if (position > segment.endMs) {
                found = mid;
                low = mid + 1;
            } else {
                return mid;
            }
        }
        if (found >= 0 && position - segments.get(found).endMs <= MAX_TRAILING_MS) {
            return found;
        }
        return -1;
    }
}
