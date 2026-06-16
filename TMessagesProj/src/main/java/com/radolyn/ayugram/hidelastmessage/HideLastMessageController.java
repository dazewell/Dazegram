package com.radolyn.ayugram.hidelastmessage;

import android.content.SharedPreferences;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;

import java.util.HashMap;
import java.util.Map;

/**
 * Local, per-dialog store for the "hide last message" privacy feature.
 *
 * <p>When a dialog id is flagged here, {@link org.telegram.ui.Cells.DialogCell}
 * replaces the last-message preview text in the chat list with a user-chosen
 * placeholder, so a person glancing at the screen cannot read the content. The
 * real message is still shown once the chat is opened.
 *
 * <p>The setting is purely local -- it is never synced to Telegram -- and is
 * keyed by dialog id, so it covers users, groups and channels alike. Storage is
 * a per-account SharedPreferences file {@code hidelastmessage_<account>} mapping
 * {@code dialogId -> placeholder}. Presence of a key means "hidden"; the stored
 * value is the placeholder to render (never empty -- the default is persisted
 * when the user leaves the field blank).
 */
public final class HideLastMessageController {

    private static final Map<Integer, HashMap<Long, String>> CACHE = new HashMap<>();
    private static final Map<Integer, Boolean> LOADED = new HashMap<>();

    private HideLastMessageController() {}

    private static String prefsName(int account) {
        return "hidelastmessage_" + account;
    }

    /** The placeholder used when hiding is enabled without a custom string. */
    @NonNull
    public static String defaultPlaceholder() {
        return LocaleController.getString(R.string.HideLastMessageDefault);
    }

    private static synchronized HashMap<Long, String> cache(int account) {
        HashMap<Long, String> m = CACHE.get(account);
        if (m == null) {
            m = new HashMap<>();
            CACHE.put(account, m);
        }
        if (!Boolean.TRUE.equals(LOADED.get(account))) {
            try {
                SharedPreferences sp = ApplicationLoader.applicationContext
                        .getSharedPreferences(prefsName(account), 0);
                for (Map.Entry<String, ?> e : sp.getAll().entrySet()) {
                    Object v = e.getValue();
                    if (v instanceof String) {
                        try {
                            m.put(Long.parseLong(e.getKey()), (String) v);
                        } catch (NumberFormatException ignore) {
                        }
                    }
                }
            } catch (Throwable ignore) {
            }
            LOADED.put(account, Boolean.TRUE);
        }
        return m;
    }

    /** True when this dialog's last-message preview should be obfuscated in the chat list. */
    public static synchronized boolean isHidden(int account, long dialogId) {
        if (dialogId == 0) return false;
        return cache(account).containsKey(dialogId);
    }

    /** The placeholder text to render for a hidden dialog (falls back to the default). */
    @NonNull
    public static synchronized String getPlaceholder(int account, long dialogId) {
        String v = cache(account).get(dialogId);
        if (TextUtils.isEmpty(v)) return defaultPlaceholder();
        return v;
    }

    /**
     * Enables hiding for a dialog with the given placeholder (blank -> default),
     * or disables it when {@code enabled} is false. Persists the change and asks
     * the dialog list to redraw.
     */
    public static synchronized void setHidden(int account, long dialogId, boolean enabled, @Nullable String placeholder) {
        if (dialogId == 0) return;
        HashMap<Long, String> m = cache(account);
        SharedPreferences.Editor ed;
        try {
            ed = ApplicationLoader.applicationContext.getSharedPreferences(prefsName(account), 0).edit();
        } catch (Throwable t) {
            ed = null;
        }
        if (enabled) {
            String value = placeholder != null ? placeholder.trim() : "";
            if (value.isEmpty()) value = defaultPlaceholder();
            m.put(dialogId, value);
            if (ed != null) ed.putString(Long.toString(dialogId), value);
        } else {
            m.remove(dialogId);
            if (ed != null) ed.remove(Long.toString(dialogId));
        }
        if (ed != null) ed.apply();
        NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.dialogsNeedReload);
    }
}
