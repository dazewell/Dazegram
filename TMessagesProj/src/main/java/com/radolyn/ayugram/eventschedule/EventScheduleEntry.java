package com.radolyn.ayugram.eventschedule;

import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.R;

import java.util.ArrayList;
import java.util.regex.Pattern;

/**
 * One armed "send early on event" trigger attached to a scheduled message.
 *
 * <p>The scheduled message keeps a real, user-picked fallback date; if a matching
 * message arrives in the same chat while the app is running, the scheduled message is
 * sent early instead. Matching is type (voice / round / video / photo / text, a bit
 * mask) AND pattern (glob or regex over the incoming text/caption). An empty side of
 * the AND passes, but at least one condition must be set.
 *
 * <p>Persisted as JSON under {@link EventScheduleStore}; the compiled {@link Pattern}
 * and the transient runtime {@link #state} / {@link #localIds} are never stored.
 */
public final class EventScheduleEntry {

    public static final int TYPE_VOICE = 1;
    public static final int TYPE_ROUND = 2;
    public static final int TYPE_VIDEO = 4;
    public static final int TYPE_PHOTO = 8;
    public static final int TYPE_TEXT = 16;

    public static final int STATE_ARMED = 0;
    public static final int STATE_WAITING = 1;
    public static final int STATE_SENDING = 2;

    // A user-typed regex has no timeout; cap the input it runs against so a pathological
    // pattern on a huge caption can't stall the queue it's evaluated on.
    private static final int MAX_MATCH_LEN = 2048;

    public long dialogId;
    public final ArrayList<Integer> serverIds = new ArrayList<>();
    // Local echo ids seen before the server assigns real ones; never persisted, useless to
    // sendScheduledMessages, only used to recognise our own message during the pending bind.
    public final ArrayList<Integer> localIds = new ArrayList<>();
    public int types;
    public String pattern = "";
    public boolean regex;
    public int delaySeconds;
    public int fallbackDate;
    public long createdAt;
    public int state = STATE_ARMED;

    private volatile Pattern compiled;
    private volatile boolean compileFailed;

    public String key() {
        return dialogId + "_" + createdAt;
    }

    public boolean matchesType(MessageObject message) {
        if (types == 0) return true;
        if ((types & TYPE_VOICE) != 0 && message.isVoice()) return true;
        if ((types & TYPE_ROUND) != 0 && message.isRoundVideo()) return true;
        if ((types & TYPE_VIDEO) != 0 && message.isVideo() && !message.isRoundVideo()) return true;
        if ((types & TYPE_PHOTO) != 0 && message.isPhoto()) return true;
        if ((types & TYPE_TEXT) != 0 && message.isMediaEmpty()
                && message.messageOwner != null && !TextUtils.isEmpty(message.messageOwner.message)) {
            return true;
        }
        return false;
    }

    public boolean matchesPattern(CharSequence text) {
        if (TextUtils.isEmpty(pattern)) return true;
        if (text == null) return false;
        if (compileFailed) return false;
        if (compiled == null) {
            try {
                String p = regex ? pattern : globToRegex(pattern);
                compiled = Pattern.compile(p, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
            } catch (Throwable t) {
                compileFailed = true;
                return false;
            }
        }
        CharSequence input = text.length() > MAX_MATCH_LEN ? text.subSequence(0, MAX_MATCH_LEN) : text;
        // Regex is a substring search (anchoring is the user's job); glob must match the whole text.
        return regex ? compiled.matcher(input).find() : compiled.matcher(input).matches();
    }

    /** Forget the compiled pattern so a changed pattern/regex-mode recompiles on next match. */
    public void resetCompiled() {
        compiled = null;
        compileFailed = false;
    }

    private static String globToRegex(String glob) {
        StringBuilder sb = new StringBuilder(glob.length() + 8);
        StringBuilder literal = new StringBuilder();
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            if (c == '*' || c == '?') {
                if (literal.length() > 0) {
                    sb.append(Pattern.quote(literal.toString()));
                    literal.setLength(0);
                }
                sb.append(c == '*' ? ".*" : ".");
            } else {
                literal.append(c);
            }
        }
        if (literal.length() > 0) {
            sb.append(Pattern.quote(literal.toString()));
        }
        return sb.toString();
    }

    public CharSequence summary(boolean withDelay) {
        ArrayList<String> parts = new ArrayList<>();
        StringBuilder typeText = new StringBuilder();
        appendType(typeText, (types & TYPE_VOICE) != 0, R.string.AttachAudio);
        appendType(typeText, (types & TYPE_ROUND) != 0, R.string.AttachRound);
        appendType(typeText, (types & TYPE_VIDEO) != 0, R.string.AttachVideo);
        appendType(typeText, (types & TYPE_PHOTO) != 0, R.string.AttachPhoto);
        appendType(typeText, (types & TYPE_TEXT) != 0, R.string.EventScheduleTypeText);
        if (typeText.length() > 0) parts.add(typeText.toString());
        if (!TextUtils.isEmpty(pattern)) parts.add(pattern);
        if (withDelay && delaySeconds > 0) parts.add("+" + delaySeconds + "s");
        return TextUtils.join(" \u00b7 ", parts);
    }

    private static void appendType(StringBuilder sb, boolean on, int resId) {
        if (!on) return;
        if (sb.length() > 0) sb.append(", ");
        sb.append(LocaleController.getString(resId));
    }

    public String toJson() {
        try {
            JSONObject o = new JSONObject();
            o.put("v", 1);
            JSONArray ids = new JSONArray();
            for (int id : serverIds) ids.put(id);
            o.put("ids", ids);
            o.put("types", types);
            o.put("pattern", pattern == null ? "" : pattern);
            o.put("regex", regex);
            o.put("delay", delaySeconds);
            o.put("fallback", fallbackDate);
            o.put("created", createdAt);
            o.put("dialog", dialogId);
            return o.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    public static EventScheduleEntry fromJson(String s) {
        try {
            JSONObject o = new JSONObject(s);
            EventScheduleEntry e = new EventScheduleEntry();
            e.dialogId = o.optLong("dialog");
            JSONArray ids = o.optJSONArray("ids");
            if (ids != null) {
                for (int i = 0; i < ids.length(); i++) e.serverIds.add(ids.getInt(i));
            }
            e.types = o.optInt("types");
            e.pattern = o.optString("pattern", "");
            e.regex = o.optBoolean("regex");
            e.delaySeconds = o.optInt("delay");
            e.fallbackDate = o.optInt("fallback");
            e.createdAt = o.optLong("created");
            return e;
        } catch (Throwable t) {
            return null;
        }
    }
}
