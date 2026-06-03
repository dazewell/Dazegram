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

    private static final int CACHE_TTL_MS = 30_000;
    private static long lastCacheTimeMin = -1;
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

    private static boolean sameAsLocal(@Nullable TimeZone tz) {
        if (tz == null) return true;
        TimeZone local = TimeZone.getDefault();
        long now = System.currentTimeMillis();
        return tz.getOffset(now) == local.getOffset(now);
    }

    // ---------- DialogCell pill ----------

    private static final RectF pillRect = new RectF();
    private static TextPaint pillPaint;

    private static TextPaint pillPaint() {
        if (pillPaint == null) {
            pillPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
            pillPaint.setTextSize(dp(11));
            pillPaint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        }
        return pillPaint;
    }

    /**
     * Draws a small rounded "HH:mm" pill at the given anchor.
     * @return the width consumed (including margin) so callers can adjust layout. 0 if not drawn.
     */
    public static int drawPillForDialog(@NonNull Canvas canvas,
                                        int currentAccount,
                                        long dialogId,
                                        int leftPx,
                                        int textBaselinePx,
                                        @Nullable Theme.ResourcesProvider rp) {
        TimeZone tz = ChatTimeZoneController.getForDialog(currentAccount, dialogId);
        if (tz == null || sameAsLocal(tz)) return 0;
        String text = formatNow(tz);
        TextPaint p = pillPaint();
        int textW = (int) Math.ceil(p.measureText(text));
        int padH = dp(5);
        int padV = dp(2);
        int pillW = textW + padH * 2;
        int pillH = (int) (p.descent() - p.ascent()) + padV * 2;
        int top = textBaselinePx - (int) (-p.ascent()) - padV;
        pillRect.set(leftPx, top, leftPx + pillW, top + pillH);
        Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
        bg.setColor(Theme.getColor(Theme.key_chats_unreadCounterMuted, rp));
        canvas.drawRoundRect(pillRect, pillH / 2f, pillH / 2f, bg);
        p.setColor(Theme.getColor(Theme.key_chats_unreadCounterText, rp));
        canvas.drawText(text, leftPx + padH, textBaselinePx, p);
        return pillW + dp(6);
    }

    /** Width the pill would consume (used for layout reservations). */
    public static int measurePillForDialog(int currentAccount, long dialogId) {
        TimeZone tz = ChatTimeZoneController.getForDialog(currentAccount, dialogId);
        if (tz == null || sameAsLocal(tz)) return 0;
        TextPaint p = pillPaint();
        int textW = (int) Math.ceil(p.measureText(formatNow(tz)));
        return textW + dp(5) * 2 + dp(6);
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
}
