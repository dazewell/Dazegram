package com.radolyn.ayugram.chattimezone;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.text.SpannableStringBuilder;
import android.text.TextPaint;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AnimatedEmojiDrawable;
import org.telegram.ui.Components.ColoredImageSpan;

import java.util.Calendar;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Pure rendering helpers. All methods are no-ops when the peer has no time
 * zone configured or the configured zone equals the device's zone.
 */
public final class ChatTimeZoneRenderer {

    private static long lastCacheTimeMin = -1; // invalidated each minute
    private static final java.util.HashMap<TimeZone, String> NOW_CACHE = new java.util.HashMap<>();

    private ChatTimeZoneRenderer() {}

    /** Returns the current time in {@code HH:mm} (24h, peer locale-independent). */
    public static String formatNow(@NonNull TimeZone tz) {
        long minute = System.currentTimeMillis() / 60_000L;
        if (minute != lastCacheTimeMin) {
            NOW_CACHE.clear();
            lastCacheTimeMin = minute;
        }
        String cached = NOW_CACHE.get(tz);
        if (cached != null) return cached;
        Calendar c = Calendar.getInstance(tz);
        String s = String.format(Locale.US, "%02d:%02d", c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE));
        NOW_CACHE.put(tz, s);
        return s;
    }

    /** Format a unix-second timestamp in the given zone. */
    public static String formatAt(long unixSec, @NonNull TimeZone tz) {
        Calendar c = Calendar.getInstance(tz);
        c.setTimeInMillis(unixSec * 1000L);
        return String.format(Locale.US, "%02d:%02d", c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE));
    }

    /** Localized short weekday name ("Tue") of the calendar's current moment. */
    public static String weekday(@NonNull Calendar c) {
        String s = c.getDisplayName(Calendar.DAY_OF_WEEK, Calendar.SHORT,
                org.telegram.messenger.LocaleController.getInstance().getCurrentLocale());
        return s != null ? s : "";
    }

    /** "Tue 18:00" in the given calendar's zone. */
    public static String formatSide(@NonNull Calendar c) {
        return weekday(c) + String.format(Locale.US, " %02d:%02d",
                c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE));
    }

    /** -1, 0 or +1: calendar-date difference of {@code a} relative to {@code b}. */
    public static int compareDay(@NonNull Calendar a, @NonNull Calendar b) {
        int byYear = Integer.compare(a.get(Calendar.YEAR), b.get(Calendar.YEAR));
        if (byYear != 0) return byYear;
        return Integer.compare(a.get(Calendar.DAY_OF_YEAR), b.get(Calendar.DAY_OF_YEAR));
    }

    /**
     * Returns true when the configured zone is operationally identical to the
     * device zone (same id, or fully equivalent rules across the year).
     * Single-instant offset compares would mis-classify zones that diverge under DST.
     */
    public static boolean sameAsLocal(@Nullable TimeZone tz) {
        if (tz == null) return true;
        TimeZone local = TimeZone.getDefault();
        if (tz.getID().equals(local.getID())) return true;
        return tz.hasSameRules(local);
    }

    // ---------- DialogCell pill ----------

    private static final RectF pillRect = new RectF();
    private static TextPaint pillPaint;
    private static Paint pillBgPaint;

    private static TextPaint pillPaint() {
        if (pillPaint == null) {
            pillPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
            pillPaint.setTextSize(dp(11));
            pillPaint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        }
        return pillPaint;
    }

    private static Paint pillBgPaint() {
        if (pillBgPaint == null) {
            pillBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        }
        return pillBgPaint;
    }

    /**
     * Worst-case width of an "HH:mm" string with the current pill paint. Cached so we
     * don't re-measure on every draw, and re-derived when the cached text size changes
     * (defensive -- normally constant). Using a fixed sample ("00:00") keeps the
     * reserved layout width stable as minutes tick over -- proportional digits would
     * otherwise cause the pill to shift width and overlap the title.
     */
    private static int cachedPillTextWidth = -1;
    private static float cachedPillTextSize = -1f;
    private static int pillTextWidth() {
        TextPaint p = pillPaint();
        if (cachedPillTextWidth < 0 || cachedPillTextSize != p.getTextSize()) {
            cachedPillTextWidth = (int) Math.ceil(p.measureText("00:00"));
            cachedPillTextSize = p.getTextSize();
        }
        return cachedPillTextWidth;
    }

    /**
     * Draws a small rounded "HH:mm" pill at the given anchor.
     * @param leftPx left edge of the pill
     * @param centerYPx vertical center the pill should align to (typically the
     *                  vertical center of the rendered name text)
     * @return the width consumed (including margin) so callers can adjust layout. 0 if not drawn.
     */
    public static int drawPillForDialog(@NonNull Canvas canvas,
                                        int currentAccount,
                                        long dialogId,
                                        int leftPx,
                                        int centerYPx,
                                        @Nullable Theme.ResourcesProvider rp) {
        TimeZone tz = ChatTimeZoneController.getForDialog(currentAccount, dialogId);
        if (tz == null || sameAsLocal(tz)) return 0;
        String text = formatNow(tz);
        TextPaint p = pillPaint();
        int textW = pillTextWidth(); // fixed worst-case width to keep layout stable
        int actualW = (int) Math.ceil(p.measureText(text));
        int padH = dp(5);
        int padV = dp(2);
        int pillW = textW + padH * 2;
        int textH = (int) Math.ceil(p.descent() - p.ascent());
        int pillH = textH + padV * 2;
        int top = centerYPx - pillH / 2;
        int textBaselinePx = top + padV + (int) (-p.ascent());
        pillRect.set(leftPx, top, leftPx + pillW, top + pillH);
        Paint bg = pillBgPaint();
        bg.setColor(Theme.getColor(Theme.key_chats_unreadCounterMuted, rp));
        canvas.drawRoundRect(pillRect, pillH / 2f, pillH / 2f, bg);
        p.setColor(Theme.getColor(Theme.key_chats_unreadCounterText, rp));
        // Center the (possibly narrower) text within the fixed pill box.
        canvas.drawText(text, leftPx + padH + (textW - actualW) / 2f, textBaselinePx, p);
        return pillW + dp(6);
    }

    /**
     * Visual width of an emoji-status right-drawable, measured from the box's left edge to the
     * glyph's real right edge. A custom emoji status fills the whole fixed box, but the default
     * premium star (msg_premium_liststar, ~14dp) is a small static bitmap pinned to the left of a
     * dp(22)/dp(24) box, so anchoring the pill to the box's right edge leaves an ~8-10dp gap.
     * Mirrors AnimatedEmojiDrawable.SwapAnimatedEmojiDrawable.draw() -- re-check if that changes.
     */
    public static int emojiStatusGlyphWidth(@Nullable AnimatedEmojiDrawable.SwapAnimatedEmojiDrawable d) {
        if (d == null) return 0;
        int box = d.getIntrinsicWidth();
        Drawable inner = d.getDrawable();
        if (inner == null || inner instanceof AnimatedEmojiDrawable) return box; // empty or full-bleed emoji
        int iw = inner.getIntrinsicWidth();
        if (iw < 0 || iw >= box) return box;
        return d.center ? (box + iw) / 2 : iw; // static glyph centered or left-pinned inside the box
    }

    /** Width the pill would consume (used for layout reservations). Stable across minutes. */
    public static int measurePillForDialog(int currentAccount, long dialogId) {
        TimeZone tz = ChatTimeZoneController.getForDialog(currentAccount, dialogId);
        if (tz == null || sameAsLocal(tz)) return 0;
        return pillTextWidth() + dp(5) * 2 + dp(6);
    }

    // ---------- shared peer-time glyph ----------

    private static final int GLOBE_SIZE = dp(12);

    /**
     * Appends {@code \u2219 <globe> HH:mm} to a base time/status string: the mid-dot separator, a
     * small globe, then the peer's local time. The globe is a ColoredImageSpan with the default
     * usePaintColor, so it tints to whatever colour the surrounding text is drawn in (subtitle
     * grey, incoming-time grey, outgoing-time green, selected, ...). Each span gets its OWN
     * drawable: ColoredImageSpan only re-applies its colour filter when its own cached colour
     * changes, so a single shared drawable would get its filter stomped by another surface's span
     * and draw the wrong colour.
     */
    private static CharSequence withPeerTime(@NonNull CharSequence base, @NonNull String peerTime) {
        Drawable globe = ContextCompat.getDrawable(ApplicationLoader.applicationContext, R.drawable.baseline_language_24).mutate();
        SpannableStringBuilder ssb = new SpannableStringBuilder(base);
        ssb.append(" \u2219 ");
        int start = ssb.length();
        ssb.append("\u200B");
        ColoredImageSpan span = new ColoredImageSpan(globe);
        span.setSize(GLOBE_SIZE);
        ssb.setSpan(span, start, ssb.length(), 0);
        ssb.append(" ").append(peerTime);
        return ssb;
    }

    /**
     * Extra width the inline globe adds to a peer-time string. The globe rides on a zero-width
     * anchor char, so Paint.measureText (used by ChatMessageCell to size the time slot and the
     * status ticks after it) doesn't count it; callers that measure that way must add this so the
     * ticks don't collide with the globe and the time doesn't overflow the bubble. 0 when the
     * dialog has no distinct zone (matching augmentMessageTime, which then adds no globe). Layouts
     * built via StaticLayout (the subtitle, the message time layout itself) already account for
     * the span and don't need this.
     */
    public static int peerTimeGlyphReserve(int currentAccount, long dialogId) {
        TimeZone tz = ChatTimeZoneController.getForDialog(currentAccount, dialogId);
        return (tz == null || sameAsLocal(tz)) ? 0 : GLOBE_SIZE;
    }

    // ---------- ChatMessageCell time ----------

    /**
     * Augments the per-message time string with the peer's local time.
     * Format: {@code HH:mm \u2219 <globe> HH:mm} (left = device-local, right = peer).
     */
    public static CharSequence augmentMessageTime(@NonNull CharSequence local,
                                                  int currentAccount,
                                                  long dialogId,
                                                  int unixSec) {
        TimeZone tz = ChatTimeZoneController.getForDialog(currentAccount, dialogId);
        if (tz == null || sameAsLocal(tz)) return local;
        return withPeerTime(local, formatAt(unixSec, tz));
    }

    // ---------- "last seen" subtitle ----------

    /**
     * Augments the "last seen" subtitle with the peer's local time of the moment
     * they were last online, mirroring {@link #augmentMessageTime}.
     * Format: {@code last seen ... \u2219 <globe> HH:mm} (right = peer-local time of last seen).
     *
     * <p>Only a concrete offline timestamp carries a meaningful instant; "online",
     * "recently", "last week/month" and "invisible" statuses are left untouched.
     */
    public static CharSequence augmentLastSeen(@NonNull CharSequence status,
                                               int currentAccount,
                                               @Nullable org.telegram.tgnet.TLRPC.User user) {
        if (user == null || user.status == null || user.id <= 0) return status;
        TimeZone tz = ChatTimeZoneController.getForUser(currentAccount, user.id);
        if (tz == null || sameAsLocal(tz)) return status;
        // user.status.expires holds the last-seen unix time for a concrete offline status.
        // Sentinel/relative values (online, recently, week, month, invisible) are <= 0 here.
        int expires = user.status.expires;
        if (expires <= 0) return status;
        int currentTime = org.telegram.tgnet.ConnectionsManager.getInstance(currentAccount).getCurrentTime();
        if (expires > currentTime) return status; // currently online -> no past instant to show
        return withPeerTime(status, formatAt(expires, tz));
    }
}
