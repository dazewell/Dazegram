package com.radolyn.ayugram.privacyprofiles;

import android.content.SharedPreferences;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.Utilities;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Device-wide "Privacy Profiles" -- named, saved app auto-lock timeouts a user can switch
 * between. A profile only ever touches {@link SharedConfig#autoLockIn}; it never changes the
 * passcode itself, biometric settings, per-account passcode controls, the panic code, screen
 * capture, or per-chat locks.
 *
 * <p>Unlike {@code ChatLockController} / {@code HideLastMessageController} this store is not
 * per-account: the timeout it manages ({@code SharedConfig.autoLockIn}) is itself a single
 * device-wide field, so a profile is device-wide too. Storage is one SharedPreferences file,
 * {@code privacyprofiles}.
 *
 * <p>There is no timer, alarm, or scheduled work anywhere in this class. A timed activation is
 * just a stored epoch deadline; every place that reads whether the app should currently be
 * locked -- {@code AndroidUtilities.needShowPasscode} and {@code TelegramMediaSession.isPasscodeLocked}
 * -- calls {@link #reconcile()} first, which is where a stale timer gets noticed and settled. This
 * keeps the whole feature to "a bit of bookkeeping on every lock read" rather than a background
 * scheduler, which is the deliberate trade-off here.
 *
 * <p>{@link #reconcile()} is also how the controller notices that something else changed
 * {@code SharedConfig.autoLockIn} out from under it -- the stock auto-lock picker, a settings
 * backup restore, or {@code SharedConfig#clearConfig()} on last logout. It compares the live
 * value against the value this controller itself last applied; a mismatch means somebody else
 * wrote it, so any active profile is dropped and the new value becomes the baseline to restore
 * to. This is the only detection mechanism -- deliberately, so a future writer of
 * {@code autoLockIn} doesn't need to know this feature exists.
 *
 * <p>All state lives under {@link #LOCK}. The lock only ever guards this class's own fields and
 * the in-memory {@code SharedConfig.autoLockIn} write -- never the disk-heavy
 * {@code SharedConfig#saveConfig()} call, which happens after the lock is released. Reconcile
 * runs from the main UI thread, the notification queue, and the media session thread, so keep it
 * that way: nothing in here should ever block waiting on something that itself waits on this lock.
 */
public final class PrivacyProfilesController {

    /** The only auto-lock values a profile (or the stock picker) can carry. */
    private static final int[] SUPPORTED_TIMEOUTS = {0, 1, 60, 300, 3600, 18000};

    public static final int MAX_PROFILES = 20;

    private static final Object LOCK = new Object();
    private static final String PREFS_NAME = "privacyprofiles";
    private static final long NO_ID = Long.MIN_VALUE;

    private static boolean loaded;
    private static final List<PrivacyProfile> profiles = new ArrayList<>();
    private static long baselineTimeout;
    private static int lastAppliedValue;
    private static long activeProfileId = NO_ID;
    private static long deadlineEpochMillis = NO_ID;
    private static long activationEpochMillis;
    private static long restoreTimeout = NO_ID;
    private static final Map<Long, String> shortcutIds = new LinkedHashMap<>();
    private static final Map<Long, String> shortcutTokens = new LinkedHashMap<>();
    // Per-profile "last successfully-activated custom duration" for the Activate-for picker's
    // prefill (item 2, round 2). Keyed by profile id, never by anything account-scoped -- this
    // feature is device-wide like the rest of the controller. A profile with no entry here has
    // never had a custom "Activate for" duration applied; the picker prefills 1 hour in that case.
    private static final Map<Long, Long> lastCustomDurationMillis = new LinkedHashMap<>();

    public enum ActivationMode { NOW, FOR, UNTIL }

    private PrivacyProfilesController() {}

    private static boolean isSupportedTimeout(int value) {
        for (int t : SUPPORTED_TIMEOUTS) {
            if (t == value) return true;
        }
        return false;
    }

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS_NAME, 0);
    }

    // Call only while holding LOCK.
    private static void loadIfNeeded() {
        if (loaded) return;
        loaded = true;
        SharedPreferences sp = prefs();
        profiles.clear();
        try {
            JSONArray arr = new JSONArray(sp.getString("profiles", "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                int timeout = o.getInt("timeout");
                // A profile whose stored timeout isn't one of the six stock values is corrupt --
                // drop just that entry rather than let it silently apply an unsupported timeout.
                if (!isSupportedTimeout(timeout)) continue;
                // Round-1 profiles have no "icon" key; backfill the default rather than persist
                // an empty icon that FolderIconHelper.folderIcons can't resolve later.
                String icon = o.has("icon") ? o.getString("icon") : PrivacyProfile.DEFAULT_ICON;
                if (icon == null || icon.isEmpty()) icon = PrivacyProfile.DEFAULT_ICON;
                profiles.add(new PrivacyProfile(o.getLong("id"), o.getString("name"), timeout, o.getLong("colorSeed"), o.getLong("createdAt"), icon));
            }
        } catch (JSONException e) {
            FileLog.e(e);
            profiles.clear();
        }
        // Activation state fails closed on any parse trouble: treat as "no active profile" and
        // adopt the live SharedConfig value as baseline, same as an ordinary external-write
        // detection would. A stuck, unparseable "active forever" profile is the one shape of bug
        // this feature must never produce.
        boolean activationOk = true;
        long loadedActive = NO_ID;
        long loadedDeadline = NO_ID;
        long loadedActivation = 0;
        long loadedRestore = NO_ID;
        int loadedLastApplied;
        long loadedBaseline;
        try {
            loadedActive = sp.getLong("activeProfileId", NO_ID);
            loadedDeadline = sp.getLong("deadlineEpochMillis", NO_ID);
            loadedActivation = sp.getLong("activationEpochMillis", 0);
            loadedRestore = sp.getLong("restoreTimeout", NO_ID);
            loadedLastApplied = sp.getInt("lastAppliedValue", SharedConfig.autoLockIn);
            loadedBaseline = sp.getLong("baselineTimeout", SharedConfig.autoLockIn);
            if (!isSupportedTimeout(loadedLastApplied) || (loadedRestore != NO_ID && !isSupportedTimeout((int) loadedRestore))
                    || (loadedBaseline != NO_ID && !isSupportedTimeout((int) loadedBaseline))) {
                activationOk = false;
            }
        } catch (ClassCastException e) {
            FileLog.e(e);
            activationOk = false;
            loadedLastApplied = SharedConfig.autoLockIn;
            loadedBaseline = SharedConfig.autoLockIn;
        }
        Map<Long, String> loadedShortcuts = new LinkedHashMap<>();
        try {
            JSONObject sc = new JSONObject(sp.getString("shortcuts", "{}"));
            java.util.Iterator<String> keys = sc.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                loadedShortcuts.put(Long.parseLong(key), sc.getString(key));
            }
        } catch (JSONException | NumberFormatException e) {
            FileLog.e(e);
            loadedShortcuts.clear();
        }
        shortcutIds.clear();
        shortcutIds.putAll(loadedShortcuts);
        Map<Long, String> loadedTokens = new LinkedHashMap<>();
        try {
            JSONObject tk = new JSONObject(sp.getString("shortcutTokens", "{}"));
            java.util.Iterator<String> keys = tk.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                loadedTokens.put(Long.parseLong(key), tk.getString(key));
            }
        } catch (JSONException | NumberFormatException e) {
            FileLog.e(e);
            loadedTokens.clear();
        }
        shortcutTokens.clear();
        shortcutTokens.putAll(loadedTokens);
        Map<Long, Long> loadedDurations = new LinkedHashMap<>();
        try {
            JSONObject cd = new JSONObject(sp.getString("lastCustomDurationMillis", "{}"));
            java.util.Iterator<String> keys = cd.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                loadedDurations.put(Long.parseLong(key), cd.getLong(key));
            }
        } catch (JSONException | NumberFormatException e) {
            FileLog.e(e);
            loadedDurations.clear();
        }
        lastCustomDurationMillis.clear();
        lastCustomDurationMillis.putAll(loadedDurations);
        // A profile entry can be dropped above (unsupported timeout) while the activation state
        // still points at its id -- treat that the same as any other malformed-activation case,
        // rather than leaving a phantom "active" profile nothing in the UI can find or turn off.
        if (activationOk && loadedActive != NO_ID) {
            boolean referencedProfileExists = false;
            for (PrivacyProfile p : profiles) {
                if (p.id == loadedActive) {
                    referencedProfileExists = true;
                    break;
                }
            }
            if (!referencedProfileExists) {
                activationOk = false;
            }
        }
        if (activationOk) {
            activeProfileId = loadedActive;
            deadlineEpochMillis = loadedDeadline;
            activationEpochMillis = loadedActivation;
            restoreTimeout = loadedRestore;
            lastAppliedValue = loadedLastApplied;
            baselineTimeout = loadedBaseline;
        } else {
            activeProfileId = NO_ID;
            deadlineEpochMillis = NO_ID;
            activationEpochMillis = 0;
            restoreTimeout = NO_ID;
            lastAppliedValue = SharedConfig.autoLockIn;
            baselineTimeout = SharedConfig.autoLockIn;
            persistLocked();
        }
    }

    // Call only while holding LOCK.
    private static void persistLocked() {
        SharedPreferences.Editor ed = prefs().edit();
        JSONArray arr = new JSONArray();
        try {
            for (PrivacyProfile p : profiles) {
                JSONObject o = new JSONObject();
                o.put("id", p.id);
                o.put("name", p.name);
                o.put("timeout", p.timeout);
                o.put("colorSeed", p.colorSeed);
                o.put("createdAt", p.createdAt);
                o.put("icon", p.icon);
                arr.put(o);
            }
        } catch (JSONException e) {
            FileLog.e(e);
        }
        ed.putString("profiles", arr.toString());
        ed.putLong("baselineTimeout", baselineTimeout);
        ed.putInt("lastAppliedValue", lastAppliedValue);
        ed.putLong("activeProfileId", activeProfileId);
        ed.putLong("deadlineEpochMillis", deadlineEpochMillis);
        ed.putLong("activationEpochMillis", activationEpochMillis);
        ed.putLong("restoreTimeout", restoreTimeout);
        JSONObject sc = new JSONObject();
        try {
            for (Map.Entry<Long, String> e : shortcutIds.entrySet()) {
                sc.put(Long.toString(e.getKey()), e.getValue());
            }
        } catch (JSONException e) {
            FileLog.e(e);
        }
        ed.putString("shortcuts", sc.toString());
        JSONObject tk = new JSONObject();
        try {
            for (Map.Entry<Long, String> e : shortcutTokens.entrySet()) {
                tk.put(Long.toString(e.getKey()), e.getValue());
            }
        } catch (JSONException e) {
            FileLog.e(e);
        }
        ed.putString("shortcutTokens", tk.toString());
        JSONObject cd = new JSONObject();
        try {
            for (Map.Entry<Long, Long> e : lastCustomDurationMillis.entrySet()) {
                cd.put(Long.toString(e.getKey()), e.getValue());
            }
        } catch (JSONException e) {
            FileLog.e(e);
        }
        ed.putString("lastCustomDurationMillis", cd.toString());
        ed.apply();
    }

    @Nullable
    private static PrivacyProfile findLocked(long id) {
        for (PrivacyProfile p : profiles) {
            if (p.id == id) return p;
        }
        return null;
    }

    /** Snapshot of the saved profiles, in creation order. */
    public static List<PrivacyProfile> getProfiles() {
        synchronized (LOCK) {
            loadIfNeeded();
            return new ArrayList<>(profiles);
        }
    }

    public static int getProfileCount() {
        synchronized (LOCK) {
            loadIfNeeded();
            return profiles.size();
        }
    }

    @Nullable
    public static PrivacyProfile addProfile(String name, int timeout, String icon) {
        if (!isSupportedTimeout(timeout)) return null;
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty()) return null;
        if (trimmed.length() > 24) trimmed = trimmed.substring(0, 24);
        String safeIcon = icon == null || icon.isEmpty() ? PrivacyProfile.DEFAULT_ICON : icon;
        synchronized (LOCK) {
            loadIfNeeded();
            if (profiles.size() >= MAX_PROFILES) return null;
            long id;
            do {
                id = Utilities.fastRandom.nextLong() & Long.MAX_VALUE;
            } while (id == NO_ID || findLocked(id) != null);
            PrivacyProfile profile = new PrivacyProfile(id, trimmed, timeout, id, System.currentTimeMillis(), safeIcon);
            profiles.add(profile);
            persistLocked();
            return profile;
        }
    }

    /** Renames, changes the timeout, and/or changes the icon of an existing profile; applies immediately if active. */
    public static boolean editProfile(long id, String name, int timeout, String icon) {
        if (!isSupportedTimeout(timeout)) return false;
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty()) return false;
        if (trimmed.length() > 24) trimmed = trimmed.substring(0, 24);
        String safeIcon = icon == null || icon.isEmpty() ? PrivacyProfile.DEFAULT_ICON : icon;
        boolean needsSave;
        boolean found;
        synchronized (LOCK) {
            loadIfNeeded();
            needsSave = reconcileLocked();
            PrivacyProfile existing = findLocked(id);
            found = existing != null;
            if (found) {
                PrivacyProfile updated = existing.withName(trimmed).withTimeout(timeout).withIcon(safeIcon);
                int idx = profiles.indexOf(existing);
                profiles.set(idx, updated);
                if (activeProfileId == id) {
                    SharedConfig.autoLockIn = timeout;
                    lastAppliedValue = timeout;
                    needsSave = true;
                }
                persistLocked();
            }
        }
        if (needsSave) {
            SharedConfig.saveConfig();
        }
        return found;
    }

    /** Deletes a profile; if it was active, restores the baseline first. Disables its shortcut. */
    public static void deleteProfile(long id) {
        boolean needsSave;
        String shortcutId;
        synchronized (LOCK) {
            loadIfNeeded();
            needsSave = reconcileLocked();
            if (activeProfileId == id) {
                needsSave = endActiveAndRestoreLocked() || needsSave;
            }
            PrivacyProfile existing = findLocked(id);
            if (existing != null) profiles.remove(existing);
            shortcutId = shortcutIds.remove(id);
            shortcutTokens.remove(id);
            lastCustomDurationMillis.remove(id);
            persistLocked();
        }
        if (needsSave) {
            SharedConfig.saveConfig();
        }
        if (shortcutId != null) {
            PrivacyProfileShortcuts.disable(shortcutId);
        }
    }

    /** Activates a profile indefinitely, for a duration, or until an absolute deadline. */
    public static boolean activate(long id, ActivationMode mode, long durationOrDeadlineMillis) {
        boolean needsSave;
        boolean ok = true;
        synchronized (LOCK) {
            loadIfNeeded();
            needsSave = reconcileLocked();
            PrivacyProfile profile = findLocked(id);
            if (profile == null) {
                ok = false;
            } else {
                long now = System.currentTimeMillis();
                if (mode == ActivationMode.UNTIL && durationOrDeadlineMillis <= now) {
                    ok = false;
                } else {
                    // Captured after reconcileLocked() above (which may itself have just cleared a
                    // stale activation and already posted for that transition via
                    // clearActiveLocked()) and before this activation overwrites them -- so the
                    // comparison below only fires for what THIS call actually changes: a genuinely
                    // new activation, a switch between two profiles, or a changed deadline. A
                    // re-activation of the same profile with the same deadline is a no-op here.
                    long beforeId = activeProfileId;
                    long beforeDeadline = deadlineEpochMillis;
                    if (activeProfileId == NO_ID) {
                        // Only the first activation out of an inactive state captures the restore
                        // target. A later switch between profiles (or a re-activation) never
                        // recaptures it, and it is always a resolved timeout value -- never a
                        // profile id -- so deleting whatever profile happened to hold that value
                        // later can't leave a dangling reference.
                        restoreTimeout = baselineTimeout;
                    }
                    activeProfileId = id;
                    activationEpochMillis = now;
                    switch (mode) {
                        case NOW:
                            deadlineEpochMillis = NO_ID;
                            break;
                        case FOR:
                            deadlineEpochMillis = now + durationOrDeadlineMillis;
                            // Persist this profile's own last-used custom duration for the
                            // Activate-for picker's prefill next time (round 2, item 2).
                            lastCustomDurationMillis.put(id, durationOrDeadlineMillis);
                            break;
                        case UNTIL:
                            deadlineEpochMillis = durationOrDeadlineMillis;
                            break;
                    }
                    SharedConfig.autoLockIn = profile.timeout;
                    lastAppliedValue = profile.timeout;
                    needsSave = true;
                    if (activeProfileId != beforeId || deadlineEpochMillis != beforeDeadline) {
                        postActiveStateChanged();
                    }
                }
            }
            // Only persist the controller's own prefs when the activation actually took, or when
            // reconcileLocked() above already found something to correct -- a rejected activation
            // (unknown id, past UNTIL deadline) is a no-op and shouldn't write to disk.
            if (ok || needsSave) {
                persistLocked();
            }
        }
        if (needsSave) {
            SharedConfig.saveConfig();
        }
        return ok;
    }

    /** Turns off the active profile, restoring the baseline immediately. */
    public static void deactivate() {
        boolean needsSave;
        synchronized (LOCK) {
            loadIfNeeded();
            needsSave = reconcileLocked();
            // endActiveAndRestoreLocked() -> clearActiveLocked() is the single hook that posts
            // privacyProfileActiveStateChanged (see clearActiveLocked()) -- nothing further needed
            // here, including for the case where reconcileLocked() above already ended a stale
            // activation itself (that already posted for its own transition).
            if (activeProfileId != NO_ID) {
                needsSave = endActiveAndRestoreLocked() || needsSave;
            }
        }
        if (needsSave) {
            SharedConfig.saveConfig();
        }
    }

    /** Drops the timer on a timed activation, leaving the profile active indefinitely. */
    public static void cancelTimer() {
        boolean needsSave;
        synchronized (LOCK) {
            loadIfNeeded();
            needsSave = reconcileLocked();
            if (activeProfileId != NO_ID && deadlineEpochMillis != NO_ID) {
                deadlineEpochMillis = NO_ID;
                persistLocked();
                // Only the controller's own SharedPreferences change here -- SharedConfig.autoLockIn
                // is untouched, so this alone never needs a SharedConfig.saveConfig() disk write.
                // Still an active-state-relevant change (indefinite now, not timed) so the
                // quick-switch submenu / badge consumers should refresh.
                postActiveStateChanged();
            }
        }
        if (needsSave) {
            SharedConfig.saveConfig();
        }
    }

    @Nullable
    public static PrivacyProfile getActiveProfile() {
        boolean needsSave;
        PrivacyProfile result;
        synchronized (LOCK) {
            loadIfNeeded();
            needsSave = reconcileLocked();
            result = activeProfileId == NO_ID ? null : findLocked(activeProfileId);
        }
        if (needsSave) SharedConfig.saveConfig();
        return result;
    }

    /** Null when the active profile (if any) is indefinite. */
    @Nullable
    public static Long getActiveDeadline() {
        boolean needsSave;
        Long result;
        synchronized (LOCK) {
            loadIfNeeded();
            needsSave = reconcileLocked();
            result = deadlineEpochMillis == NO_ID ? null : deadlineEpochMillis;
        }
        if (needsSave) SharedConfig.saveConfig();
        return result;
    }

    /**
     * The duration (in ms) this profile last used with "Activate for", for the picker's prefill
     * (round 2, item 2). Returns -1 if this profile has never had a custom duration activated --
     * the caller prefills 1 hour / 0 minutes in that case, not any value read from here.
     */
    public static long getLastCustomDurationMillis(long profileId) {
        synchronized (LOCK) {
            loadIfNeeded();
            Long v = lastCustomDurationMillis.get(profileId);
            return v != null ? v : -1;
        }
    }

    /** Per-profile secret embedded in its pinned shortcut's intent, keyed by profile id. */
    public static void rememberShortcut(long profileId, String shortcutId, String token) {
        synchronized (LOCK) {
            loadIfNeeded();
            shortcutIds.put(profileId, shortcutId);
            shortcutTokens.put(profileId, token);
            persistLocked();
        }
    }

    @Nullable
    public static String getShortcutId(long profileId) {
        synchronized (LOCK) {
            loadIfNeeded();
            return shortcutIds.get(profileId);
        }
    }

    /** The token already embedded in a profile's pinned shortcut intent, or null if never pinned. */
    @Nullable
    public static String getShortcutToken(long profileId) {
        synchronized (LOCK) {
            loadIfNeeded();
            return shortcutTokens.get(profileId);
        }
    }


    /**
     * Resolves a profile from its pinned-shortcut id and activates it indefinitely. The token
     * must match what was embedded in the shortcut's intent when it was created -- this is what
     * stops an arbitrary intent from a third-party app from switching the auto-lock timeout, the
     * same secret-token pattern SharedConfig.directShareHash uses for direct-share intents.
     * Returns false (a safe no-op) on an unknown profile or a mismatched token.
     */
    public static boolean activateFromShortcut(long profileId, String token) {
        if (token == null) return false;
        synchronized (LOCK) {
            loadIfNeeded();
            String expected = shortcutTokens.get(profileId);
            if (expected == null || !expected.equals(token)) return false;
        }
        return activate(profileId, ActivationMode.NOW, 0);
    }

    /**
     * The one entry point every lock-state read must call first. Detects an external write to
     * {@code SharedConfig.autoLockIn} (the stock picker, a settings restore, or last-logout
     * {@code clearConfig()}) and an expired or clock-rolled-back timed activation, and settles
     * either by restoring/adopting the right baseline. Safe to call from any thread.
     */
    public static void reconcile() {
        boolean needsSave;
        synchronized (LOCK) {
            loadIfNeeded();
            needsSave = reconcileLocked();
        }
        if (needsSave) {
            SharedConfig.saveConfig();
        }
    }

    // Call only while holding LOCK. Returns true if SharedConfig.saveConfig() should be called
    // after the lock is released (a value changed and needs to reach disk).
    private static boolean reconcileLocked() {
        // clearConfig() always empties the passcode hash, which the plain value-comparison below
        // can't reliably catch (it clears autoLockIn back to its own default, 3600 -- a supported
        // stock value a profile could equally be set to, so "no change" and "cleared" can look
        // identical to a value compare alone). Passcode gone means nothing is protecting a
        // profile's meaning anyway, so treat it as an unconditional external reset.
        if (SharedConfig.passcodeHash.length() == 0) {
            if (activeProfileId == NO_ID && baselineTimeout == SharedConfig.autoLockIn && lastAppliedValue == SharedConfig.autoLockIn) {
                return false;
            }
            clearActiveLocked();
            baselineTimeout = SharedConfig.autoLockIn;
            lastAppliedValue = SharedConfig.autoLockIn;
            persistLocked();
            return false;
        }
        int live = SharedConfig.autoLockIn;
        if (live != lastAppliedValue) {
            // Something outside this controller changed the timeout: the stock picker or a
            // settings restore. That value is now the truth; whatever was active no longer applies.
            clearActiveLocked();
            baselineTimeout = live;
            lastAppliedValue = live;
            persistLocked();
            return false;
        }
        if (activeProfileId == NO_ID) return false;
        long now = System.currentTimeMillis();
        if (deadlineEpochMillis != NO_ID && now < activationEpochMillis) {
            // Clock moved backward under a timed activation: fail closed rather than let a
            // corrected clock re-extend a countdown that already should have ended.
            return endActiveAndRestoreLocked();
        }
        if (deadlineEpochMillis != NO_ID && now >= deadlineEpochMillis) {
            return endActiveAndRestoreLocked();
        }
        return false;
    }

    // Call only while holding LOCK. The single chokepoint every active->inactive transition
    // funnels through -- reconcileLocked()'s passcode-gone/external-override branches, and
    // endActiveAndRestoreLocked() (itself called from reconcileLocked()'s expiry/clock-rollback
    // branches, deactivate(), and deleteProfile()). Posting here, guarded on there having actually
    // been an active profile, is what makes the badge/quick-switch refresh correct by construction
    // instead of relying on every call site remembering to check. Safe to call under LOCK:
    // postNotificationNameOnUIThread only enqueues onto the main-thread handler and returns (see
    // postActiveStateChanged()), it never runs the observers inline.
    private static void clearActiveLocked() {
        if (activeProfileId != NO_ID) {
            postActiveStateChanged();
        }
        activeProfileId = NO_ID;
        deadlineEpochMillis = NO_ID;
        activationEpochMillis = 0;
        restoreTimeout = NO_ID;
    }

    // Call only while holding LOCK. Returns true if the in-memory autoLockIn actually changed
    // and SharedConfig.saveConfig() should run once the lock is released.
    private static boolean endActiveAndRestoreLocked() {
        long restore = restoreTimeout != NO_ID ? restoreTimeout : baselineTimeout;
        boolean changed = SharedConfig.autoLockIn != restore;
        SharedConfig.autoLockIn = (int) restore;
        lastAppliedValue = (int) restore;
        clearActiveLocked();
        persistLocked();
        return changed;
    }

    // Fires NotificationCenter.privacyProfileActiveStateChanged on the global instance (this
    // controller is device-wide, not per-account). Must use postNotificationNameOnUIThread, never
    // the plain postNotificationName -- reconcile() (and therefore this) can run off the UI thread
    // (e.g. from the media session thread per this class's own javadoc), and plain
    // postNotificationName hard-asserts the UI thread in debug builds. Safe to call while holding
    // LOCK: this only enqueues a Runnable on the main-thread handler and returns immediately, it
    // never executes observers inline, so there is no lock-ordering risk.
    private static void postActiveStateChanged() {
        org.telegram.messenger.NotificationCenter.getGlobalInstance()
                .postNotificationNameOnUIThread(org.telegram.messenger.NotificationCenter.privacyProfileActiveStateChanged);
    }
}
