package com.radolyn.ayugram.chattimezone;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.text.SpannableStringBuilder;
import android.text.TextPaint;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.telegram.ui.ActionBar.Theme;

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

    /** Width the pill would consume (used for layout reservations). Stable across minutes. */
    public static int measurePillForDialog(int currentAccount, long dialogId) {
        TimeZone tz = ChatTimeZoneController.getForDialog(currentAccount, dialogId);
        if (tz == null || sameAsLocal(tz)) return 0;
        return pillTextWidth() + dp(5) * 2 + dp(6);
    }

    // ---------- ChatMessageCell time ----------

    /**
     * Augments the per-message time string with the peer's local time.
     * Format: {@code HH:mm \u2219 HH:mm} (left = device-local, right = peer).
     */
    public static CharSequence augmentMessageTime(@NonNull CharSequence local,
                                                  int currentAccount,
                                                  long dialogId,
                                                  int unixSec) {
        TimeZone tz = ChatTimeZoneController.getForDialog(currentAccount, dialogId);
        if (tz == null || sameAsLocal(tz)) return local;
        SpannableStringBuilder ssb = new SpannableStringBuilder();
        ssb.append(local).append(" \u2219 ").append(formatAt(unixSec, tz));
        return ssb;
    }

    // ---------- "last seen" subtitle ----------

    /**
     * Augments the "last seen" subtitle with the peer's local time of the moment
     * they were last online, mirroring {@link #augmentMessageTime}.
     * Format: {@code last seen ... \u2219 HH:mm} (right = peer-local time of last seen).
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
        SpannableStringBuilder ssb = new SpannableStringBuilder();
        ssb.append(status).append(" \u2219 ").append(formatAt(expires, tz));
        return ssb;
    }
}
