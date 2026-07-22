package com.radolyn.ayugram.chattimezone;

import android.content.SharedPreferences;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;

import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import xyz.nextalone.nagram.NaConfig;

/**
 * Renders the "insert into message" text from a user-editable template and owns
 * where that template is persisted. Resolution order when picking the effective
 * template: per-account override -> global default -> the built-in localized
 * pattern ({@link R.string#ChatTimeZoneInsertPattern}).
 *
 * <p>The global template rides on the existing global {@link NaConfig} string
 * surface; a per-account override lives in the same {@code chattimezone_<account>}
 * SharedPreferences file the rest of the feature already uses, under a
 * non-numeric key so it never collides with the dialog-id cache (which parses
 * numeric keys only).
 *
 * <p>Supported {@code {token}} placeholders:
 * <ul>
 *   <li>{@code {my_side}} / {@code {peer_side}} — "Tue 18:15"</li>
 *   <li>{@code {my_time}} / {@code {peer_time}} — "18:15"</li>
 *   <li>{@code {my_day}} / {@code {peer_day}} — "Tue"</li>
 *   <li>{@code {peer_name}} — the counterpart's name</li>
 *   <li>{@code {offset}} — relative zone offset, e.g. "+9h"</li>
 *   <li>{@code {daydiff}} — "+1d" / "−1d" / empty</li>
 * </ul>
 * Unknown tokens are left in place verbatim.
 */
public final class ChatTimeZoneTemplate {

    private static final String ACCOUNT_TEMPLATE_KEY = "insert_template";

    private ChatTimeZoneTemplate() {}

    private static String prefsName(int account) {
        return "chattimezone_" + account;
    }

    /** The built-in default, derived from the localized pattern so it stays translated. */
    public static String defaultTemplate() {
        String pattern = LocaleController.getString(R.string.ChatTimeZoneInsertPattern);
        // Pattern is "%1$s my time (%2$s your time)" -> token form the editor can show/edit.
        return pattern.replace("%1$s", "{my_side}").replace("%2$s", "{peer_side}");
    }

    public static String getGlobal() {
        return NaConfig.INSTANCE.getChatTimeZoneInsertTemplate().String();
    }

    /** Pass {@code null}/blank to clear the global template and fall back to the built-in default. */
    public static void setGlobal(@Nullable String template) {
        String normalized = (template == null || template.trim().isEmpty()) ? "" : template;
        NaConfig.INSTANCE.getChatTimeZoneInsertTemplate().setConfigString(normalized);
    }

    /** Per-account override, or {@code null} when none is set. */
    @Nullable
    public static String getAccountOverride(int account) {
        try {
            SharedPreferences sp = ApplicationLoader.applicationContext
                    .getSharedPreferences(prefsName(account), 0);
            String v = sp.getString(ACCOUNT_TEMPLATE_KEY, null);
            return TextUtils.isEmpty(v) ? null : v;
        } catch (Throwable ignore) {
            return null;
        }
    }

    public static boolean hasAccountOverride(int account) {
        return getAccountOverride(account) != null;
    }

    /** Pass {@code null}/blank to clear the override and fall back to the global default. */
    public static void setAccountOverride(int account, @Nullable String template) {
        try {
            SharedPreferences.Editor ed = ApplicationLoader.applicationContext
                    .getSharedPreferences(prefsName(account), 0).edit();
            if (template == null || template.trim().isEmpty()) {
                ed.remove(ACCOUNT_TEMPLATE_KEY);
            } else {
                ed.putString(ACCOUNT_TEMPLATE_KEY, template);
            }
            ed.apply();
        } catch (Throwable ignore) {}
    }

    /** "+9h", "−3h", "+5:30" — relative offset of peer vs local in minutes. */
    public static String formatOffset(int diffMin) {
        int abs = Math.abs(diffMin);
        String sign = diffMin < 0 ? "−" : "+";
        if (abs % 60 == 0) {
            return sign + (abs / 60) + "h";
        }
        return String.format(Locale.US, "%s%d:%02d", sign, abs / 60, abs % 60);
    }

    /**
     * Substitutes tokens for the given moment. A blank template falls back to the
     * built-in default so we never insert an empty string.
     */
    public static String render(@Nullable String template, @NonNull Calendar local, @NonNull Calendar peer,
                                @NonNull String peerName, int offsetMin) {
        if (template == null || template.trim().isEmpty()) {
            template = defaultTemplate();
        }
        int dayDiff = ChatTimeZoneRenderer.compareDay(peer, local);
        String dayDiffStr = dayDiff > 0 ? "+1d" : dayDiff < 0 ? "−1d" : "";

        Map<String, String> vals = new LinkedHashMap<>();
        vals.put("my_side", ChatTimeZoneRenderer.formatSide(local));
        vals.put("peer_side", ChatTimeZoneRenderer.formatSide(peer));
        vals.put("my_time", hhmm(local));
        vals.put("peer_time", hhmm(peer));
        vals.put("my_day", ChatTimeZoneRenderer.weekday(local));
        vals.put("peer_day", ChatTimeZoneRenderer.weekday(peer));
        vals.put("peer_name", peerName);
        vals.put("offset", formatOffset(offsetMin));
        vals.put("daydiff", dayDiffStr);
        return applyTokens(template, vals);
    }

    private static String hhmm(Calendar c) {
        return String.format(Locale.US, "%02d:%02d", c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE));
    }

    /** Single pass so a substituted value can't accidentally match another token. */
    private static String applyTokens(String template, Map<String, String> vals) {
        StringBuilder sb = new StringBuilder(template.length() + 16);
        int i = 0, n = template.length();
        while (i < n) {
            char c = template.charAt(i);
            if (c == '{') {
                int end = template.indexOf('}', i + 1);
                if (end > i) {
                    String key = template.substring(i + 1, end);
                    if (vals.containsKey(key)) {
                        sb.append(vals.get(key));
                        i = end + 1;
                        continue;
                    }
                }
            }
            sb.append(c);
            i++;
        }
        return sb.toString();
    }
}
