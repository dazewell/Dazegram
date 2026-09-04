package com.radolyn.ayugram.eventschedule;

import android.content.SharedPreferences;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.ApplicationLoader;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Local, per-account seed for the next "Send on event" setup in the sheet.
 *
 * <p>Stores one successful Done submission per account in {@code eventschedule_last_<account>},
 * under the single key {@code setup}. This is UI seed state only (types/patterns/regex/delay),
 * never the armed/on-off trigger state.
 */
public final class EventScheduleLastSetup {

    private static final String KEY_SETUP = "setup";

    private static final Map<Integer, Setup> CACHE = new ConcurrentHashMap<>();
    private static final Map<Integer, Boolean> LOADED = new ConcurrentHashMap<>();
    private static final Map<Integer, Object> MONITORS = new ConcurrentHashMap<>();

    private EventScheduleLastSetup() {}

    public static final class Setup {
        public final int types;
        public final ArrayList<String> patterns;
        public final boolean regex;
        public final int delaySeconds;

        Setup(int types, List<String> patterns, boolean regex, int delaySeconds) {
            this.types = types;
            this.patterns = new ArrayList<>(patterns);
            this.regex = regex;
            this.delaySeconds = delaySeconds;
        }
    }

    private static String prefsName(int account) {
        return "eventschedule_last_" + account;
    }

    private static Object monitor(int account) {
        return MONITORS.computeIfAbsent(account, k -> new Object());
    }

    public static Setup get(int account) {
        synchronized (monitor(account)) {
            loadLocked(account);
            Setup setup = CACHE.get(account);
            return setup == null ? null : new Setup(setup.types, setup.patterns, setup.regex, setup.delaySeconds);
        }
    }

    public static void put(int account, int types, List<String> patterns, boolean regex, int delaySeconds) {
        int normalizedTypes = types & EventScheduleEntry.TYPE_MASK;
        ArrayList<String> normalizedPatterns = EventScheduleEntry.normalizeCommittedPatterns(patterns);
        int normalizedDelay = clampDelay(delaySeconds);
        synchronized (monitor(account)) {
            LOADED.put(account, Boolean.TRUE);
            if (normalizedTypes == 0 && normalizedPatterns.isEmpty()) {
                CACHE.remove(account);
                try {
                    ApplicationLoader.applicationContext.getSharedPreferences(prefsName(account), 0)
                            .edit().remove(KEY_SETUP).apply();
                } catch (Throwable ignore) {
                }
                return;
            }
            Setup setup = new Setup(normalizedTypes, normalizedPatterns, regex, normalizedDelay);
            CACHE.put(account, setup);
            String json = toJson(setup);
            if (json == null) return;
            try {
                ApplicationLoader.applicationContext.getSharedPreferences(prefsName(account), 0)
                        .edit().putString(KEY_SETUP, json).apply();
            } catch (Throwable ignore) {
            }
        }
    }

    private static void loadLocked(int account) {
        if (Boolean.TRUE.equals(LOADED.get(account))) return;
        Setup setup = null;
        try {
            SharedPreferences sp = ApplicationLoader.applicationContext
                    .getSharedPreferences(prefsName(account), 0);
            setup = fromJson(sp.getString(KEY_SETUP, null));
        } catch (Throwable ignore) {
        }
        if (setup == null) {
            CACHE.remove(account);
        } else {
            CACHE.put(account, setup);
        }
        LOADED.put(account, Boolean.TRUE);
    }

    private static Setup fromJson(String value) {
        if (TextUtils.isEmpty(value)) return null;
        try {
            JSONObject json = new JSONObject(value);
            if (!json.has("v") || !json.has("types") || !json.has("patterns")
                    || !json.has("regex") || !json.has("delay")) {
                return null;
            }
            Object versionValue = json.get("v");
            Object typesValue = json.get("types");
            Object regexValue = json.get("regex");
            Object delayValue = json.get("delay");
            Object patternsValue = json.get("patterns");
            if (!isJsonInt(versionValue) || !isJsonInt(typesValue) || !isJsonInt(delayValue)
                    || !(regexValue instanceof Boolean) || !(patternsValue instanceof JSONArray)) {
                return null;
            }
            if (((Number) versionValue).intValue() != 1) return null;
            ArrayList<String> rawPatterns = new ArrayList<>();
            JSONArray patterns = (JSONArray) patternsValue;
            for (int i = 0; i < patterns.length(); i++) {
                Object raw = patterns.get(i);
                if (!(raw instanceof String)) {
                    return null;
                }
                rawPatterns.add((String) raw);
            }
            int types = ((Number) typesValue).intValue() & EventScheduleEntry.TYPE_MASK;
            ArrayList<String> normalizedPatterns = EventScheduleEntry.normalizeCommittedPatterns(rawPatterns);
            boolean regex = (Boolean) regexValue;
            int delay = clampDelay(((Number) delayValue).intValue());
            if (types == 0 && normalizedPatterns.isEmpty()) return null;
            return new Setup(types, normalizedPatterns, regex, delay);
        } catch (Throwable t) {
            return null;
        }
    }

    private static String toJson(Setup setup) {
        try {
            JSONObject json = new JSONObject();
            json.put("v", 1);
            json.put("types", setup.types);
            JSONArray patterns = new JSONArray();
            for (int i = 0; i < setup.patterns.size(); i++) {
                patterns.put(setup.patterns.get(i));
            }
            json.put("patterns", patterns);
            json.put("regex", setup.regex);
            json.put("delay", setup.delaySeconds);
            return json.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    private static int clampDelay(int value) {
        return Math.max(0, Math.min(value, EventScheduleEntry.MAX_DELAY_SECONDS));
    }

    private static boolean isJsonInt(Object value) {
        return value instanceof Integer || value instanceof Long;
    }
}
