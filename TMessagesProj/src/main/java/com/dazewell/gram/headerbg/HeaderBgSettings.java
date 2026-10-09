package com.dazewell.gram.headerbg;

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

    /** How the fade's opacity falls from its start to its end. */
    public static final int CURVE_LINEAR = 0;
    public static final int CURVE_EASE_IN = 1;
    public static final int CURVE_EASE_OUT = 2;
    public static final int CURVE_SMOOTH = 3;
    /** The narrowest fade, in percent of its run, however close the start and end are set. */
    public static final int MIN_FADE_SPAN = 10;

    /** No tint; otherwise the theme's accent, or an index into {@code Theme.keys_avatar_background}. */
    public static final int TINT_AUTO = -1;
    public static final int TINT_THEME = -2;

    /** A text colour set for one surface in one theme; the values match HeaderBgForeground's THEME to LIGHT_PILL. */
    public static final int TEXT_AUTO = 0;
    public static final int TEXT_LIGHT = 1;
    public static final int TEXT_DARK = 2;
    public static final int TEXT_DARK_PILL = 3;
    public static final int TEXT_LIGHT_PILL = 4;

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
    /** Also paint the photo behind the strips under the header, such as the pinned message. */
    public boolean extendPanel = true;
    public int gradientCurve = CURVE_LINEAR;
    /** Where the fade starts and ends, in percent of its run; solid before the start, clear after the end. */
    public int gradientStart = 0;
    public int gradientEnd = 100;
    /** Softens and drains the photo so text over it reads more easily; both 0 to 100. */
    public int blur;
    public int desaturate;
    // Header and pinned bar text, each remembered separately for the light and the dark theme.
    // Index: 0 header light, 1 header dark, 2 pinned light, 3 pinned dark.
    private final int[] text = new int[4];

    public int text(boolean pin, boolean dark) {
        return text[(pin ? 2 : 0) + (dark ? 1 : 0)];
    }

    public void cycleText(boolean pin, boolean dark) {
        int i = (pin ? 2 : 0) + (dark ? 1 : 0);
        text[i] = (text[i] + 1) % (TEXT_LIGHT_PILL + 1);
    }

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
        extendPanel = true;
        gradientCurve = CURVE_LINEAR;
        gradientStart = 0;
        gradientEnd = 100;
        blur = 0;
        desaturate = 0;
        java.util.Arrays.fill(text, TEXT_AUTO);
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
        extendPanel = o.extendPanel;
        gradientCurve = o.gradientCurve;
        gradientStart = o.gradientStart;
        gradientEnd = o.gradientEnd;
        blur = o.blur;
        desaturate = o.desaturate;
        System.arraycopy(o.text, 0, text, 0, text.length);
    }

    /** Every field the drawer paints from, so a cache checked against it notices any change; keep in step with the fields. */
    public boolean sameAs(HeaderBgSettings o) {
        return enabled == o.enabled && offsetX == o.offsetX && offsetY == o.offsetY && zoom == o.zoom && opacity == o.opacity
                && tintHue == o.tintHue && tintStrength == o.tintStrength && gradient == o.gradient
                && gradientStrength == o.gradientStrength && gradientFrom == o.gradientFrom && extendPanel == o.extendPanel
                && gradientCurve == o.gradientCurve && gradientStart == o.gradientStart && gradientEnd == o.gradientEnd
                && blur == o.blur && desaturate == o.desaturate
                && java.util.Arrays.equals(text, o.text);
    }

    private boolean isDefaultLook() {
        return offsetX == 0 && offsetY == 0 && zoom == DEF_ZOOM && opacity == DEF_OPACITY
                && tintHue == TINT_AUTO && tintStrength == DEF_TINT_STRENGTH && gradient
                && gradientStrength == DEF_GRADIENT_STRENGTH && gradientFrom == FROM_TITLE && extendPanel
                && gradientCurve == CURVE_LINEAR && gradientStart == 0 && gradientEnd == 100
                && blur == 0 && desaturate == 0
                && text[0] == TEXT_AUTO && text[1] == TEXT_AUTO && text[2] == TEXT_AUTO && text[3] == TEXT_AUTO;
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
        s.extendPanel = !"0".equals(field(f, 10));
        s.gradientCurve = parse(f, 11, CURVE_LINEAR, CURVE_SMOOTH, CURVE_LINEAR);
        s.gradientStart = parse(f, 12, 0, 100 - MIN_FADE_SPAN, 0);
        s.gradientEnd = parse(f, 13, MIN_FADE_SPAN, 100, 100);
        s.gradientEnd = Math.max(s.gradientEnd, s.gradientStart + MIN_FADE_SPAN);
        s.blur = parse(f, 14, 0, 100, 0);
        s.desaturate = parse(f, 15, 0, 100, 0);
        // Slot 16 held the retired Alternate color; it is never read, and the next new field goes on slot 21.
        for (int i = 0; i < s.text.length; i++) {
            s.text[i] = parse(f, 17 + i, TEXT_AUTO, TEXT_LIGHT_PILL, TEXT_AUTO);
        }
        return s;
    }

    public void save(int account, long dialogId) {
        String key = String.valueOf(dialogId);
        SharedPreferences.Editor editor = prefs(account).edit();
        if (!enabled && isDefaultLook()) {
            editor.remove(key);
        } else {
            editor.putString(key, (enabled ? "1" : "0") + "|" + offsetX + "|" + offsetY + "|" + zoom + "|" + opacity + "|"
                    + tintHue + "|" + tintStrength + "|" + (gradient ? "1" : "0") + "|" + gradientStrength + "|" + gradientFrom
                    + "|" + (extendPanel ? "1" : "0") + "|" + gradientCurve + "|" + gradientStart + "|" + gradientEnd
                    + "|" + blur + "|" + desaturate + "|0|" + text[0] + "|" + text[1] + "|" + text[2] + "|" + text[3]);
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
