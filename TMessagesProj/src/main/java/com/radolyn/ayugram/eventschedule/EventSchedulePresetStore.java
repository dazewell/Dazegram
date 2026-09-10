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
 * {@link EventScheduleLastSetup}'s single auto-remembered slot. Stored as one JSON array under one
 * key in {@code eventschedule_presets_<account>}.
 *
 * <p>Unlike LastSetup's all-or-nothing parse (fine for a slot the app silently regenerates), this
 * parses the array element-by-element: a malformed or unknown-version element is skipped and
 * logged, the rest of a hand-curated list survives. See {@link #fromJsonArray}.
 */
public final class EventSchedulePresetStore {

    private static final String KEY_PRESETS = "presets";
    private static final int ENVELOPE_VERSION = 1;
    private static final int ELEMENT_VERSION = 1;

    private static final Map<Integer, ArrayList<Preset>> CACHE = new ConcurrentHashMap<>();
    private static final Map<Integer, Boolean> LOADED = new ConcurrentHashMap<>();
    private static final Map<Integer, Object> MONITORS = new ConcurrentHashMap<>();

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

    public static boolean nameExists(int account, String name) {
        synchronized (monitor(account)) {
            loadLocked(account);
            ArrayList<Preset> list = CACHE.get(account);
            if (list == null) return false;
            for (int i = 0; i < list.size(); i++) {
                if (list.get(i).name.equalsIgnoreCase(name)) return true;
            }
            return false;
        }
    }

    /** Returns false (and adds nothing) once the account is already at {@link EventScheduleEntry#MAX_PRESET_COUNT}. */
    public static boolean add(int account, String name, int types, List<String> patterns, boolean regex, int delaySeconds) {
        int normalizedTypes = types & EventScheduleEntry.TYPE_MASK;
        ArrayList<String> normalizedPatterns = EventScheduleEntry.normalizeCommittedPatterns(patterns);
        int normalizedDelay = Math.max(0, Math.min(delaySeconds, EventScheduleEntry.MAX_DELAY_SECONDS));
        synchronized (monitor(account)) {
            loadLocked(account);
            ArrayList<Preset> list = CACHE.computeIfAbsent(account, k -> new ArrayList<>());
            if (list.size() >= EventScheduleEntry.MAX_PRESET_COUNT) return false;
            Preset preset = new Preset(UUID.randomUUID().toString(), name, normalizedTypes, normalizedPatterns,
                    regex, normalizedDelay, System.currentTimeMillis());
            list.add(preset);
            persistLocked(account, list);
            return true;
        }
    }

    public static void remove(int account, String id) {
        if (TextUtils.isEmpty(id)) return;
        synchronized (monitor(account)) {
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
            for (int i = 0; i < array.length(); i++) {
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
            String name = o.optString("name", "");
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
