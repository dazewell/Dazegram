package com.radolyn.ayugram.headerbg;

import android.content.SharedPreferences;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.ui.ActionBar.Theme;

/**
 * One chat's header background setup. Stored per account in {@code headerbg_<account>}, one
 * string per dialog id, and clamped field by field when read so a stale or garbled value falls
 * back to the default instead of failing.
 */
public final class HeaderBgSettings {

    public static final int FROM_TITLE = 0;
    public static final int FROM_TOP = 1;
    public static final int FROM_BOTTOM = 2;

    /** No tint; otherwise the theme's accent, or an index into {@code Theme.keys_avatar_background}. */
    public static final int TINT_AUTO = -1;
    public static final int TINT_THEME = -2;

    private static final int DEF_ZOOM = 100;
    private static final int DEF_OPACITY = 55;
    private static final int DEF_TINT_STRENGTH = 40;
    private static final int DEF_GRADIENT_STRENGTH = 70;

    public boolean enabled;
    public int offsetX;
    public int offsetY;
    public int zoom = DEF_ZOOM;
    public int opacity = DEF_OPACITY;
    public int tintHue = TINT_AUTO;
    public int tintStrength = DEF_TINT_STRENGTH;
    public boolean gradient = true;
    public int gradientStrength = DEF_GRADIENT_STRENGTH;
    public int gradientFrom = FROM_TITLE;

    /** Everything back to its default except {@link #enabled}. */
    public void resetLook() {
        offsetX = 0;
        offsetY = 0;
        zoom = DEF_ZOOM;
        opacity = DEF_OPACITY;
        tintHue = TINT_AUTO;
        tintStrength = DEF_TINT_STRENGTH;
        gradient = true;
        gradientStrength = DEF_GRADIENT_STRENGTH;
        gradientFrom = FROM_TITLE;
    }

    public void copyFrom(HeaderBgSettings o) {
        enabled = o.enabled;
        offsetX = o.offsetX;
        offsetY = o.offsetY;
        zoom = o.zoom;
        opacity = o.opacity;
        tintHue = o.tintHue;
        tintStrength = o.tintStrength;
        gradient = o.gradient;
        gradientStrength = o.gradientStrength;
        gradientFrom = o.gradientFrom;
    }

    private boolean isDefaultLook() {
        return offsetX == 0 && offsetY == 0 && zoom == DEF_ZOOM && opacity == DEF_OPACITY
                && tintHue == TINT_AUTO && tintStrength == DEF_TINT_STRENGTH && gradient
                && gradientStrength == DEF_GRADIENT_STRENGTH && gradientFrom == FROM_TITLE;
    }

    private static SharedPreferences prefs(int account) {
        return ApplicationLoader.applicationContext.getSharedPreferences("headerbg_" + account, 0);
    }

    public static boolean isEnabled(int account, long dialogId) {
        String raw = prefs(account).getString(String.valueOf(dialogId), null);
        return raw != null && raw.startsWith("1|");
    }

    public static HeaderBgSettings load(int account, long dialogId) {
        HeaderBgSettings s = new HeaderBgSettings();
        String raw = prefs(account).getString(String.valueOf(dialogId), null);
        if (raw == null) {
            return s;
        }
        String[] f = raw.split("\\|");
        s.enabled = "1".equals(field(f, 0));
        s.offsetX = parse(f, 1, -100, 100, 0);
        s.offsetY = parse(f, 2, -100, 100, 0);
        s.zoom = parse(f, 3, 100, 300, DEF_ZOOM);
        s.opacity = parse(f, 4, 10, 100, DEF_OPACITY);
        s.tintHue = parse(f, 5, TINT_THEME, Theme.keys_avatar_background.length - 1, TINT_AUTO);
        s.tintStrength = parse(f, 6, 0, 100, DEF_TINT_STRENGTH);
        s.gradient = !"0".equals(field(f, 7));
        s.gradientStrength = parse(f, 8, 0, 100, DEF_GRADIENT_STRENGTH);
        s.gradientFrom = parse(f, 9, FROM_TITLE, FROM_BOTTOM, FROM_TITLE);
        return s;
    }

    public void save(int account, long dialogId) {
        String key = String.valueOf(dialogId);
        SharedPreferences.Editor editor = prefs(account).edit();
        if (!enabled && isDefaultLook()) {
            editor.remove(key);
        } else {
            editor.putString(key, (enabled ? "1" : "0") + "|" + offsetX + "|" + offsetY + "|" + zoom + "|" + opacity + "|"
                    + tintHue + "|" + tintStrength + "|" + (gradient ? "1" : "0") + "|" + gradientStrength + "|" + gradientFrom);
        }
        editor.apply();
    }

    private static String field(String[] f, int i) {
        return i < f.length ? f[i] : null;
    }

    private static int parse(String[] f, int i, int min, int max, int def) {
        String v = field(f, i);
        if (v == null) {
            return def;
        }
        try {
            return Math.max(min, Math.min(max, Integer.parseInt(v)));
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
