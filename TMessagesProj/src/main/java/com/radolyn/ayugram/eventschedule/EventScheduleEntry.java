package com.radolyn.ayugram.eventschedule;

import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.R;

import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

/**
 * One armed "send early on event" trigger attached to a scheduled message.
 *
 * <p>The scheduled message keeps a real, user-picked fallback date; if a matching
 * message arrives in the same chat while the app is running, the scheduled message is
 * sent early instead. Matching is type (voice / round / video / photo / text, a bit
 * mask) OR pattern (glob or regex over the incoming text/caption): a selected type that
 * matches fires on its own, otherwise the pattern is checked. At least one condition
 * must be set, and the pattern is evaluated on any message regardless of the type mask.
 * Both pattern modes look for a hit anywhere in the text, so a bare word fires on any
 * message containing it; anchoring is what regex mode's {@code ^} and {@code $} are for.
 *
 * <p>Persisted as JSON under {@link EventScheduleStore}; the compiled {@link Pattern}
 * and transient runtime {@link #state} are never stored.
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
    // Local echo ids (negative) claimed from scheduled-batch updates; used as the exact
    // remap key when messageReceivedByServer arrives.
    public final ArrayList<Integer> localIds = new ArrayList<>();
    public int types;
    public String pattern = "";
    public boolean regex;
    public int delaySeconds;
    public int fallbackDate;
    public long createdAt;
    // Correlation metadata: first non-zero grouped_id bound to this arm, and the arm bind window.
    public long bindGroupedId;
    public long bindExpiresAt;
    public int state = STATE_ARMED;
    // Bumped on the UI thread on every edit (through resolveAndClaimForEdit) to detect a stale in-flight
    // arm/fire against the queue below; always read and written on the UI thread (armWaiting,
    // fire, and their runOnUIThread callbacks), never from the background matcher queue --
    // see PatternState for the separate, atomically-published state the matcher itself reads.
    public long revision;

    public String key() {
        return dialogId + "_" + createdAt;
    }

    // A well-formed entry holds only strictly positive, server-addressable ids in serverIds and only
    // negative correlation echoes in localIds. A serverId <= 0 means an in-flight local id was bound as
    // if the server had issued it (the trigger reports live but can never fire); a localId >= 0 is the
    // same corruption from the other side. Such an entry is dropped whole at load, never repaired by
    // stripping ids -- stripping a mixed album would silently leave a partial trigger, the same bug.
    public boolean hasInvalidIds() {
        for (int id : serverIds) {
            if (id <= 0) return true;
        }
        for (int id : localIds) {
            if (id >= 0) return true;
        }
        return false;
    }

    public String triggerKey() {
        return types + ":" + regex + ":" + (pattern == null ? "" : pattern);
    }

    public boolean matchesType(MessageObject message, CharSequence text) {
        if (types == 0) return false;
        if ((types & TYPE_VOICE) != 0 && message.isVoice()) return true;
        if ((types & TYPE_ROUND) != 0 && message.isRoundVideo()) return true;
        if ((types & TYPE_VIDEO) != 0 && message.isVideo() && !message.isRoundVideo()) return true;
        if ((types & TYPE_PHOTO) != 0 && message.isPhoto()) return true;
        if ((types & TYPE_TEXT) != 0 && message.isMediaEmpty() && !TextUtils.isEmpty(text)) {
            return true;
        }
        return false;
    }

    /**
     * Immutable snapshot of one edit generation's pattern-matching state -- revision, the
     * pattern/regex it was set to, and (once available) the compiled result for that exact
     * pattern/regex -- published as a whole through the single {@link #patternState}
     * reference. Bundling all of it into one object, rather than tracking revision and the
     * compiled Pattern as separate fields, is what lets {@link #compileAndPublish} publish a
     * compile result with a single {@code compareAndSet}: the CAS can only succeed against
     * the exact object it was compiled from, so a background compile still in flight when an
     * edit lands ({@link #resetPatternState}) can never win a race and publish over the edit
     * -- there is no separate "is this still current" check that a newer edit could land
     * between, the swap itself is the only thing that decides it.
     */
    static final class PatternState {
        final long revision;
        final String pattern;
        final boolean regex;
        final Pattern compiled; // null until compiled, or if compilation failed
        final boolean failed;

        PatternState(long revision, String pattern, boolean regex, Pattern compiled, boolean failed) {
            this.revision = revision;
            this.pattern = pattern;
            this.regex = regex;
            this.compiled = compiled;
            this.failed = failed;
        }
    }

    // Starts null: capturePatternState() lazily seeds it from the pattern/regex/revision
    // fields on first access, which is always on the UI thread (see evaluate()), so that
    // lazy init never races a background compile. Every later generation only ever exists
    // because resetPatternState() (also UI-thread-only) put it there.
    private final AtomicReference<PatternState> patternState = new AtomicReference<>();

    /**
     * Returns this entry's current pattern-matching state, initializing it from the
     * pattern/regex/revision fields on first call if the entry has never been edited since
     * construction (armExisting/armPending/fromJson all fully set pattern/regex before the
     * entry is ever added to the store, so by the time anything can call this, they're
     * already final for this generation). Must only be called from the UI thread -- the only
     * caller is {@link EventScheduleController#evaluate}, which captures the returned state
     * before handing a match off to the background queue; matching and any needed compile
     * both then run against that one captured object (see {@link #matchesPattern}).
     */
    PatternState capturePatternState() {
        PatternState current = patternState.get();
        if (current == null) {
            current = new PatternState(revision, pattern, regex, null, false);
            if (!patternState.compareAndSet(null, current)) {
                current = patternState.get();
            }
        }
        return current;
    }

    /**
     * Called on the UI thread after an edit has updated pattern/regex/revision, to swap in a
     * fresh, uncompiled state for the new revision. The live edit path reaches here through
     * {@link EventScheduleStore#resolveAndClaimForEdit}, which mutates the selected entry and
     * then calls this; the retained {@code updateForEdit} compatibility path calls it too.
     * This is a plain reference replacement, not a CAS: every caller runs on the UI thread, so
     * this method is single-threaded by that precondition and there is nothing to race against
     * here -- note the {@code synchronized} on the store's claim method gives mutual exclusion,
     * not thread affinity, so it is the UI-thread-only callers, not that lock, that make the
     * plain {@code set} safe. What matters is what this replacement does to a background compile
     * still in flight against the *previous* PatternState object: once this call returns, that
     * object is no longer reachable from {@link #patternState}, so the compile's eventual
     * {@code compareAndSet(oldState, ...)} in {@link #compileAndPublish} is guaranteed to fail
     * no matter when it runs relative to this swap.
     */
    void resetPatternState() {
        patternState.set(new PatternState(revision, pattern, regex, null, false));
    }

    /**
     * @param state the exact PatternState the caller captured via {@link #capturePatternState}
     *              on the UI thread before posting this match to the background queue --
     *              matching and any needed compile both happen against this one immutable
     *              object, so the pattern that was matched and the compiled form used are
     *              always guaranteed to be the same generation.
     */
    public boolean matchesPattern(PatternState state, CharSequence text) {
        if (TextUtils.isEmpty(state.pattern)) return true;
        if (text == null) return false;

        if (state.compiled == null && !state.failed) {
            state = compileAndPublish(state);
        }
        if (state.failed) return false;

        CharSequence input = text.length() > MAX_MATCH_LEN ? text.subSequence(0, MAX_MATCH_LEN) : text;
        // Both modes hit anywhere in the text: a trigger word is nearly always part of a longer
        // message, and anchoring is what regex mode is for.
        return state.compiled.matcher(input).find();
    }

    /**
     * Compiles {@code state.pattern}/{@code state.regex} and tries to publish the compiled
     * result back onto {@link #patternState} with {@code compareAndSet(state, ...)} -- an
     * atomic swap against the exact object this compile started from, not a separate
     * check-then-write. If an edit has already replaced {@link #patternState} with a newer
     * generation in the meantime (via {@link #resetPatternState}), the object identity no
     * longer matches and the CAS simply fails: the freshly compiled result is still returned
     * so the caller (one in-flight match) gets a correct answer for the generation it asked
     * about, but it is never retried and never written anywhere else, so a stale compile can
     * never overwrite a newer edit's state -- there is no window between "check" and "write"
     * for a newer edit to land in, because the compareAndSet is the only write and it either
     * happens atomically against the captured object or not at all.
     */
    private PatternState compileAndPublish(PatternState state) {
        Pattern compiledPattern = null;
        boolean failed = false;
        try {
            int flags = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
            // A glob's * and ? have no way to opt into crossing line breaks, and code blocks
            // and quotes put text on its own line; a regex author can ask for the same with (?s).
            if (!state.regex) flags |= Pattern.DOTALL;
            compiledPattern = Pattern.compile(state.regex ? state.pattern : globToRegex(state.pattern), flags);
        } catch (Throwable t) {
            failed = true;
        }
        PatternState compiledState = new PatternState(state.revision, state.pattern, state.regex, compiledPattern, failed);
        patternState.compareAndSet(state, compiledState);
        return compiledState;
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
        appendType(typeText, (types & TYPE_ROUND) != 0, R.string.EventScheduleTypeRound);
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
            JSONArray local = new JSONArray();
            for (int id : localIds) local.put(id);
            o.put("local_ids", local);
            o.put("types", types);
            o.put("pattern", pattern == null ? "" : pattern);
            o.put("regex", regex);
            o.put("delay", delaySeconds);
            o.put("fallback", fallbackDate);
            o.put("created", createdAt);
            o.put("dialog", dialogId);
            o.put("bind_group", bindGroupedId);
            o.put("bind_expires_at", bindExpiresAt);
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
            JSONArray local = o.optJSONArray("local_ids");
            if (local != null) {
                for (int i = 0; i < local.length(); i++) e.localIds.add(local.getInt(i));
            }
            e.types = o.optInt("types");
            e.pattern = o.optString("pattern", "");
            e.regex = o.optBoolean("regex");
            e.delaySeconds = o.optInt("delay");
            e.fallbackDate = o.optInt("fallback");
            e.createdAt = o.optLong("created");
            e.bindGroupedId = o.optLong("bind_group");
            e.bindExpiresAt = o.optLong("bind_expires_at");
            return e;
        } catch (Throwable t) {
            return null;
        }
    }
}
