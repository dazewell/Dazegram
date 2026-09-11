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
    // Bumped by clearAccountState under the same account monitor. The Row captures the generation for
    // its slot at construction (before the schedule picker opens) and passes it back into put(); a Done
    // submission from a sheet that outlived a logout (its account cleared and its slot possibly reused)
    // gets rejected instead of re-seeding this slot for whoever logs into it next. Mirrors
    // EventSchedulePresetStore's own generation guard, kept independent per store.
    private static final Map<Integer, Integer> GENERATION = new ConcurrentHashMap<>();

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

    /**
     * The sheet captures this once, when it opens, and passes it back into {@link #put}. Both the
     * capture and the write go through {@code monitor(account)} so a capture can never straddle a
     * concurrent {@link #clearAccountState} bump.
     */
    public static int currentGeneration(int account) {
        synchronized (monitor(account)) {
            return GENERATION.getOrDefault(account, 0);
        }
    }

    /**
     * Called from {@code MessagesController#performLogout} for the departing account slot -- this
     * store keys off the reusable numeric slot, not a stable identity, so without this a fresh login
     * into the same slot would inherit the previous account's remembered trigger setup as the seed
     * for its own trigger sheet. Drops the in-memory cache and loaded flag for the slot and clears
     * its SharedPreferences file.
     *
     * <p>Removing LOADED matters as much as removing CACHE here: {@link #loadLocked} treats a
     * loaded-with-no-cache-entry slot as "no setup", so leaving LOADED=true would hide the leak
     * in-process while the file survived on disk for the next cold start.
     *
     * <p>Deliberately keeps the slot's MONITORS entry (same reasoning as EventSchedulePresetStore):
     * a thread that grabbed the old lock right before this ran could still be about to synchronize on
     * it, and a fresh lock for the same slot would no longer exclude it. Also bumps GENERATION so an
     * in-flight Done submission from a sheet that captured an older generation is rejected by
     * {@link #put} instead of resurrecting data into a slot this method just emptied.
     */
    public static void clearAccountState(int account) {
        synchronized (monitor(account)) {
            CACHE.remove(account);
            LOADED.remove(account);
            GENERATION.merge(account, 1, Integer::sum);
            try {
                ApplicationLoader.applicationContext.getSharedPreferences(prefsName(account), 0)
                        .edit().clear().apply();
            } catch (Throwable ignore) {
            }
        }
    }

    /**
     * No-ops (writes nothing) if {@code generation} no longer matches this slot's current generation
     * -- the account was logged out, and the store cleared, since the sheet captured it via
     * {@link #currentGeneration}. The check runs inside the same synchronized block as the write, so
     * there is no check-then-act gap against a concurrent {@link #clearAccountState}.
     */
    public static void put(int account, int generation, int types, List<String> patterns, boolean regex, int delaySeconds) {
        int normalizedTypes = types & EventScheduleEntry.TYPE_MASK;
        ArrayList<String> normalizedPatterns = EventScheduleEntry.normalizeCommittedPatterns(patterns);
        int normalizedDelay = clampDelay(delaySeconds);
        synchronized (monitor(account)) {
            if (GENERATION.getOrDefault(account, 0) != generation) return;
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
            Integer decodedVersion = decodeJsonIntExact(versionValue);
            Integer decodedTypes = decodeJsonIntExact(typesValue);
            Integer decodedDelay = decodeJsonIntExact(delayValue);
            if (decodedVersion == null || decodedTypes == null || decodedDelay == null
                    || !(regexValue instanceof Boolean) || !(patternsValue instanceof JSONArray)) {
                return null;
            }
            if (decodedVersion != 1) return null;
            ArrayList<String> rawPatterns = new ArrayList<>();
            JSONArray patterns = (JSONArray) patternsValue;
            for (int i = 0; i < patterns.length(); i++) {
                Object raw = patterns.get(i);
                if (!(raw instanceof String)) {
                    return null;
                }
                rawPatterns.add((String) raw);
            }
            int types = decodedTypes & EventScheduleEntry.TYPE_MASK;
            ArrayList<String> normalizedPatterns = EventScheduleEntry.normalizeCommittedPatterns(rawPatterns);
            boolean regex = (Boolean) regexValue;
            int delay = clampDelay(decodedDelay);
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

    private static Integer decodeJsonIntExact(Object value) {
        if (!(value instanceof Number)) return null;
        if (value instanceof Integer) return (Integer) value;
        if (value instanceof Long) {
            long longValue = (Long) value;
            if (longValue < Integer.MIN_VALUE || longValue > Integer.MAX_VALUE) return null;
            return (int) longValue;
        }
        double numericValue = ((Number) value).doubleValue();
        if (!Double.isFinite(numericValue)) return null;
        if (numericValue != Math.rint(numericValue)) return null;
        if (numericValue < Integer.MIN_VALUE || numericValue > Integer.MAX_VALUE) return null;
        return (int) numericValue;
    }
}
