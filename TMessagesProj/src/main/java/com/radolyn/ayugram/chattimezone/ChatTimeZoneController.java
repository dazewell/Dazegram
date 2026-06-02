package com.radolyn.ayugram.chattimezone;

import android.text.TextUtils;

import androidx.annotation.Nullable;

import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.NotificationCenter;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;

import java.util.Locale;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads/writes a per-contact "chat time zone" embedded inside the existing
 * private Notes ({@link TLRPC.UserFull#note}). The marker is invisible to the
 * user when viewing the notes and consumes a minimal number of characters of
 * the server-imposed notes length budget.
 *
 * Marker format (stored at end of note): {@code U+200B} (ZWSP) followed by the
 * payload until end of text. Payload is one of:
 *   - {@code +HHMM} / {@code -HHMM} fixed offset (5 chars, e.g. {@code +0530})
 *   - {@code Z} / {@code UTC}
 *   - any IANA id (e.g. {@code Europe/Berlin})
 */
public final class ChatTimeZoneController {

    public static final char MARKER = '\u200B';
    private static final int IANA_MAX_LEN = 32;
    public static final int MARKER_RESERVED_BYTES = 1 + IANA_MAX_LEN;

    private static final Pattern OFFSET_PATTERN = Pattern.compile("^([+\\-])(\\d{2}):?(\\d{2})?$");

    private ChatTimeZoneController() {}

    /** Strip the time-zone marker and payload from a note text for user display. */
    public static CharSequence stripMarker(@Nullable CharSequence noteText) {
        if (noteText == null) return null;
        int idx = TextUtils.indexOf(noteText, MARKER);
        if (idx < 0) return noteText;
        // trim a leading newline before the marker if present
        int cut = idx;
        if (cut > 0 && noteText.charAt(cut - 1) == '\n') cut--;
        return noteText.subSequence(0, cut);
    }

    /** Extracts the raw payload after the marker; returns null if absent. */
    @Nullable
    public static String extractPayload(@Nullable CharSequence noteText) {
        if (noteText == null) return null;
        int idx = TextUtils.indexOf(noteText, MARKER);
        if (idx < 0) return null;
        String payload = noteText.subSequence(idx + 1, noteText.length()).toString().trim();
        // payload ends at first newline if any
        int nl = payload.indexOf('\n');
        if (nl >= 0) payload = payload.substring(0, nl);
        return payload.isEmpty() ? null : payload;
    }

    /**
     * Parse a payload to a {@link TimeZone}. Accepts IANA ids, {@code Z},
     * {@code UTC}, and {@code ±HHMM} / {@code ±HH:MM}.
     */
    @Nullable
    public static TimeZone parsePayload(@Nullable String payload) {
        if (payload == null) return null;
        payload = payload.trim();
        if (payload.isEmpty()) return null;
        if ("Z".equalsIgnoreCase(payload) || "UTC".equalsIgnoreCase(payload) || "GMT".equalsIgnoreCase(payload)) {
            return TimeZone.getTimeZone("UTC");
        }
        Matcher m = OFFSET_PATTERN.matcher(payload);
        if (m.matches()) {
            int sign = "-".equals(m.group(1)) ? -1 : 1;
            int hh = Integer.parseInt(m.group(2));
            int mm = m.group(3) != null ? Integer.parseInt(m.group(3)) : 0;
            if (hh > 14 || mm >= 60) return null;
            String id = String.format(Locale.US, "GMT%s%02d:%02d", sign < 0 ? "-" : "+", hh, mm);
            return TimeZone.getTimeZone(id);
        }
        // Try as IANA id; getTimeZone returns GMT if unknown -> reject that.
        TimeZone tz = TimeZone.getTimeZone(payload);
        if ("GMT".equals(tz.getID()) && !"GMT".equalsIgnoreCase(payload)) return null;
        return tz;
    }

    /**
     * Encodes a {@link TimeZone} for storage. Prefers fixed-offset form when
     * the zone has no DST, otherwise stores the IANA id (truncated if too long).
     */
    public static String encodePayload(TimeZone tz) {
        if (tz == null) return "";
        if (!tz.useDaylightTime()) {
            int offsetMinutes = tz.getRawOffset() / 60000;
            int sign = offsetMinutes < 0 ? -1 : 1;
            int abs = Math.abs(offsetMinutes);
            int hh = abs / 60;
            int mm = abs % 60;
            return String.format(Locale.US, "%s%02d%02d", sign < 0 ? "-" : "+", hh, mm);
        }
        String id = tz.getID();
        if (id.length() > IANA_MAX_LEN) id = id.substring(0, IANA_MAX_LEN);
        return id;
    }

    /** Look up the TZ for a user dialog id (peer userId). */
    @Nullable
    public static TimeZone getForUser(int currentAccount, long userId) {
        if (userId <= 0) return null; // 1:1 only
        TLRPC.UserFull full = MessagesController.getInstance(currentAccount).getUserFull(userId);
        if (full == null || full.note == null) return null;
        return parsePayload(extractPayload(full.note.text));
    }

    @Nullable
    public static TimeZone getForDialog(int currentAccount, long dialogId) {
        if (dialogId <= 0) return null;
        return getForUser(currentAccount, dialogId);
    }

    /**
     * Persists a time zone for the given user, rewriting the existing note's
     * marker. Pass {@code null} to remove. Returns true when a network request
     * was issued. The local userInfo is updated immediately.
     */
    public static boolean save(int currentAccount, long userId, @Nullable TimeZone tz) {
        TLRPC.UserFull full = MessagesController.getInstance(currentAccount).getUserFull(userId);
        if (full == null) return false;

        CharSequence existing = full.note != null && full.note.text != null ? full.note.text : "";
        CharSequence cleaned = stripMarker(existing);
        StringBuilder rebuilt = new StringBuilder(cleaned);
        if (tz != null) {
            if (rebuilt.length() > 0) rebuilt.append('\n');
            rebuilt.append(MARKER).append(encodePayload(tz));
        }

        TLRPC.TL_textWithEntities newNote;
        if (rebuilt.length() == 0) {
            newNote = null;
        } else {
            newNote = new TLRPC.TL_textWithEntities();
            newNote.text = rebuilt.toString();
            // entities of original note refer to original offsets; since we
            // only append/remove a trailing marker that has no entities of its
            // own, we keep the existing entities (offsets within cleaned text
            // are unchanged).
            if (full.note != null) {
                newNote.entities = full.note.entities;
            }
        }

        if (newNote != null) {
            full.flags2 |= TLObject.FLAG_22;
            full.note = newNote;
        } else {
            full.flags2 &= ~TLObject.FLAG_22;
            full.note = null;
        }
        MessagesStorage.getInstance(currentAccount).updateUserInfo(full, true);

        TLRPC.TL_updateContactNote req = new TLRPC.TL_updateContactNote();
        req.id = MessagesController.getInstance(currentAccount).getInputUser(userId);
        TLRPC.TL_textWithEntities sendNote = new TLRPC.TL_textWithEntities();
        if (newNote != null) {
            sendNote.text = newNote.text;
            sendNote.entities = newNote.entities != null ? newNote.entities : new java.util.ArrayList<>();
        }
        req.note = sendNote;
        org.telegram.tgnet.ConnectionsManager.getInstance(currentAccount).sendRequest(req, null);

        NotificationCenter.getInstance(currentAccount).postNotificationName(
                NotificationCenter.userInfoDidLoad, userId, full);
        return true;
    }

    /** Returns the number of bytes the marker currently consumes for a given TZ. */
    public static int markerLength(@Nullable TimeZone tz) {
        if (tz == null) return 0;
        return 1 /* ZWSP */ + 1 /* preceding newline if note non-empty */ + encodePayload(tz).length();
    }

    /**
     * Returns the maximum length the user-visible portion of notes may take,
     * given the server limit and the currently saved TZ for the user.
     */
    public static int adjustedNoteLimit(int currentAccount, long userId, int serverLimit) {
        TimeZone tz = getForUser(currentAccount, userId);
        return Math.max(0, serverLimit - markerLength(tz));
    }
}
