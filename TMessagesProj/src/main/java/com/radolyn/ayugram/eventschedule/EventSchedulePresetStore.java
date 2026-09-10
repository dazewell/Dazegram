package com.radolyn.ayugram.eventschedule;

import android.content.SharedPreferences;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Named, user-curated "Send on event" presets: a per-account list, separate from
 * {@link EventScheduleLastSetup}'s single auto-remembered slot. Stored as a versioned JSON
 * envelope (an object with a "v" field and one JSON array under one key) in
 * {@code eventschedule_presets_<account>}.
 *
 * <p>Unlike LastSetup's all-or-nothing parse (fine for a slot the app silently regenerates), this
 * parses the array element-by-element: a malformed or unknown-version element is skipped and
 * logged, the rest of a hand-curated list survives. See {@link #fromEnvelopeJson}.
 */
public final class EventSchedulePresetStore {

    private static final String KEY_PRESETS = "presets";
    private static final int ENVELOPE_VERSION = 1;
    private static final int ELEMENT_VERSION = 1;

    private static final Map<Integer, ArrayList<Preset>> CACHE = new ConcurrentHashMap<>();
    private static final Map<Integer, Boolean> LOADED = new ConcurrentHashMap<>();
    private static final Map<Integer, Object> MONITORS = new ConcurrentHashMap<>();
    // Bumped by clearAccountState under the same account monitor. A UI surface captures the
    // generation for its slot when it opens and passes it back into add()/remove(); a write from a
    // callback that outlived a logout (its account cleared and its slot possibly reused) gets
    // rejected instead of silently repopulating the slot for whoever logs into it next.
    private static final Map<Integer, Integer> GENERATION = new ConcurrentHashMap<>();

    private EventSchedulePresetStore() {}

    public static final class Preset {
        public final String id;
        public final String name;
        public final int types;
        public final ArrayList<String> patterns;
        public final boolean regex;
        public final int delaySeconds;
        public final long createdAt;

        Preset(String id, String name, int types, List<String> patterns, boolean regex, int delaySeconds, long createdAt) {
            this.id = id;
            this.name = name;
            this.types = types;
            this.patterns = new ArrayList<>(patterns);
            this.regex = regex;
            this.delaySeconds = delaySeconds;
            this.createdAt = createdAt;
        }
    }

    private static String prefsName(int account) {
        return "eventschedule_presets_" + account;
    }

    private static Object monitor(int account) {
        return MONITORS.computeIfAbsent(account, k -> new Object());
    }

    // Trims and caps to MAX_PRESET_NAME_LENGTH so the invariant holds even if a future caller
    // bypasses the naming dialog's own InputFilter, or a hand-edited JSON file has an oversized name.
    private static String normalizeName(String name) {
        if (name == null) return "";
        String trimmed = name.trim();
        if (trimmed.length() > EventScheduleEntry.MAX_PRESET_NAME_LENGTH) {
            trimmed = trimmed.substring(0, EventScheduleEntry.MAX_PRESET_NAME_LENGTH);
        }
        return trimmed;
    }

    /** Fresh defensive copy every call -- never the cached list itself. */
    public static ArrayList<Preset> getAll(int account) {
        synchronized (monitor(account)) {
            loadLocked(account);
            ArrayList<Preset> list = CACHE.get(account);
            ArrayList<Preset> copy = new ArrayList<>();
            if (list != null) {
                for (int i = 0; i < list.size(); i++) {
                    copy.add(copyOf(list.get(i)));
                }
            }
            return copy;
        }
    }

    /**
     * A UI surface for this account captures this once, when it opens, and passes it back into
     * {@link #add} / {@link #remove}. Reads and writes both go through {@code monitor(account)} so a
     * capture can never straddle a concurrent {@link #clearAccountState} bump.
     */
    public static int currentGeneration(int account) {
        synchronized (monitor(account)) {
            return GENERATION.getOrDefault(account, 0);
        }
    }

    public static boolean nameExists(int account, String name) {
        String normalizedName = normalizeName(name);
        synchronized (monitor(account)) {
            loadLocked(account);
            ArrayList<Preset> list = CACHE.get(account);
            if (list == null) return false;
            for (int i = 0; i < list.size(); i++) {
                if (list.get(i).name.equalsIgnoreCase(normalizedName)) return true;
            }
            return false;
        }
    }

    /**
     * Returns false (and adds nothing) once the account is already at {@link EventScheduleEntry#MAX_PRESET_COUNT},
     * the name is empty after normalization, no condition is set (no type and no pattern), a preset
     * with the same name (case-insensitive) already exists, or {@code generation} no longer matches
     * this slot's current generation (the account was logged out -- and the store cleared -- since the
     * caller captured it via {@link #currentGeneration}). The naming dialog already checks the first
     * three before showing this call, and {@link #nameExists} before that, but enforcing all of it again
     * here -- inside the same synchronized block as the mutation -- keeps the invariant atomic and true
     * for any future caller that skips the dialog, not just today's one call site.
     */
    public static boolean add(int account, int generation, String name, int types, List<String> patterns, boolean regex, int delaySeconds) {
        String normalizedName = normalizeName(name);
        int normalizedTypes = types & EventScheduleEntry.TYPE_MASK;
        ArrayList<String> normalizedPatterns = EventScheduleEntry.normalizeCommittedPatterns(patterns);
        int normalizedDelay = Math.max(0, Math.min(delaySeconds, EventScheduleEntry.MAX_DELAY_SECONDS));
        if (TextUtils.isEmpty(normalizedName) || (normalizedTypes == 0 && normalizedPatterns.isEmpty())) return false;
        synchronized (monitor(account)) {
            if (GENERATION.getOrDefault(account, 0) != generation) return false;
            loadLocked(account);
            ArrayList<Preset> list = CACHE.computeIfAbsent(account, k -> new ArrayList<>());
            if (list.size() >= EventScheduleEntry.MAX_PRESET_COUNT) return false;
            for (int i = 0; i < list.size(); i++) {
                if (list.get(i).name.equalsIgnoreCase(normalizedName)) return false;
            }
            Preset preset = new Preset(UUID.randomUUID().toString(), normalizedName, normalizedTypes, normalizedPatterns,
                    regex, normalizedDelay, System.currentTimeMillis());
            list.add(preset);
            persistLocked(account, list);
            return true;
        }
    }

    /** No-ops (nothing removed) if {@code generation} no longer matches this slot's current generation. */
    public static void remove(int account, int generation, String id) {
        if (TextUtils.isEmpty(id)) return;
        synchronized (monitor(account)) {
            if (GENERATION.getOrDefault(account, 0) != generation) return;
            loadLocked(account);
            ArrayList<Preset> list = CACHE.get(account);
            if (list == null) return;
            boolean removed = false;
            for (int i = list.size() - 1; i >= 0; i--) {
                if (id.equals(list.get(i).id)) {
                    list.remove(i);
                    removed = true;
                }
            }
            if (removed) {
                persistLocked(account, list);
            }
        }
    }

    private static Preset copyOf(Preset p) {
        return new Preset(p.id, p.name, p.types, p.patterns, p.regex, p.delaySeconds, p.createdAt);
    }

    /**
     * Called from {@code MessagesController#performLogout} for the departing account slot -- this
     * store keys off the reusable numeric slot, not a stable identity, so without this a fresh
     * login into the same slot would inherit the previous account's cached and persisted presets.
     * Drops the in-memory cache/loaded-flag for the slot and clears its SharedPreferences file so
     * the next {@link #getAll} for that slot starts empty, not just unloaded.
     *
     * <p>Deliberately does NOT remove the slot's entry from {@code MONITORS}: a thread that grabbed
     * the old lock object right before this ran could still be about to synchronize on it, and if a
     * concurrent caller then created a fresh lock for the same slot the two would no longer exclude
     * each other against the same {@code CACHE}/{@code LOADED} entries. Account slots are bounded by
     * {@code UserConfig.MAX_ACCOUNT_COUNT}, so keeping one monitor per slot for the process lifetime
     * costs nothing worth reclaiming.
     * <p>Also bumps the slot's generation ({@link #GENERATION}): any in-flight {@link #add}/{@link
     * #remove} call from a dialog that captured an older generation before this ran (e.g. a naming
     * dialog left open across a remote session revocation) is rejected instead of resurrecting data
     * into a slot this method just emptied.
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

    private static void loadLocked(int account) {
        if (Boolean.TRUE.equals(LOADED.get(account))) return;
        ArrayList<Preset> list;
        try {
            SharedPreferences sp = ApplicationLoader.applicationContext
                    .getSharedPreferences(prefsName(account), 0);
            list = fromEnvelopeJson(sp.getString(KEY_PRESETS, null));
        } catch (Throwable t) {
            list = new ArrayList<>();
        }
        CACHE.put(account, list);
        LOADED.put(account, Boolean.TRUE);
    }

    // Caller already holds monitor(account) and has just mutated the CACHE entry passed in; this
    // rewrites the file with exactly what's in memory, dropping nothing that was already valid.
    private static void persistLocked(int account, ArrayList<Preset> list) {
        String json = toEnvelopeJson(list);
        if (json == null) return;
        try {
            ApplicationLoader.applicationContext.getSharedPreferences(prefsName(account), 0)
                    .edit().putString(KEY_PRESETS, json).apply();
        } catch (Throwable ignore) {
        }
    }

    private static String toEnvelopeJson(ArrayList<Preset> list) {
        try {
            JSONObject envelope = new JSONObject();
            envelope.put("v", ENVELOPE_VERSION);
            JSONArray array = new JSONArray();
            for (int i = 0; i < list.size(); i++) {
                JSONObject element = toElementJson(list.get(i));
                if (element != null) {
                    array.put(element);
                }
            }
            envelope.put(KEY_PRESETS, array);
            return envelope.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    private static JSONObject toElementJson(Preset preset) {
        try {
            JSONObject o = new JSONObject();
            o.put("ev", ELEMENT_VERSION);
            o.put("id", preset.id);
            o.put("name", preset.name);
            o.put("types", preset.types);
            JSONArray patterns = new JSONArray();
            for (int i = 0; i < preset.patterns.size(); i++) {
                patterns.put(preset.patterns.get(i));
            }
            o.put("patterns", patterns);
            o.put("regex", preset.regex);
            o.put("delay", preset.delaySeconds);
            o.put("created", preset.createdAt);
            return o;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Per-element parse (C-1): the envelope itself must be well-formed, but each preset inside the
     * array is decoded independently -- a bad or future-versioned element is skipped and logged,
     * never treated as a reason to drop the rest of a user-curated list.
     */
    private static ArrayList<Preset> fromEnvelopeJson(String value) {
        ArrayList<Preset> out = new ArrayList<>();
        if (TextUtils.isEmpty(value)) return out;
        try {
            JSONObject envelope = new JSONObject(value);
            if (envelope.optInt("v", -1) != ENVELOPE_VERSION) {
                FileLog.d("EventSchedulePresetStore: unknown envelope version, discarding file");
                return out;
            }
            JSONArray array = envelope.optJSONArray(KEY_PRESETS);
            if (array == null) return out;
            for (int i = 0; i < array.length() && out.size() < EventScheduleEntry.MAX_PRESET_COUNT; i++) {
                Preset preset = fromElementJson(array.optJSONObject(i));
                if (preset != null) {
                    out.add(preset);
                } else {
                    FileLog.d("EventSchedulePresetStore: skipped malformed preset element at index " + i);
                }
            }
        } catch (Throwable t) {
            FileLog.d("EventSchedulePresetStore: envelope parse failed: " + t);
            return new ArrayList<>();
        }
        return out;
    }

    private static Preset fromElementJson(JSONObject o) {
        if (o == null) return null;
        try {
            if (o.optInt("ev", -1) != ELEMENT_VERSION) return null;
            String id = o.optString("id", "");
            String name = normalizeName(o.optString("name", ""));
            if (TextUtils.isEmpty(id) || TextUtils.isEmpty(name)) return null;
            int types = o.optInt("types", 0) & EventScheduleEntry.TYPE_MASK;
            ArrayList<String> patterns = new ArrayList<>();
            JSONArray patternsJson = o.optJSONArray("patterns");
            if (patternsJson != null) {
                for (int i = 0; i < patternsJson.length(); i++) {
                    Object raw = patternsJson.opt(i);
                    if (raw instanceof String) {
                        patterns.add((String) raw);
                    }
                }
            }
            ArrayList<String> normalizedPatterns = EventScheduleEntry.normalizeCommittedPatterns(patterns);
            boolean regex = o.optBoolean("regex", false);
            int delay = Math.max(0, Math.min(o.optInt("delay", 0), EventScheduleEntry.MAX_DELAY_SECONDS));
            long createdAt = o.optLong("created", 0);
            if (types == 0 && normalizedPatterns.isEmpty()) return null;
            return new Preset(id, name, types, normalizedPatterns, regex, delay, createdAt);
        } catch (Throwable t) {
            return null;
        }
    }
}
