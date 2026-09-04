package com.radolyn.ayugram.chatprivacy;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.text.TextUtils;

import androidx.collection.LongSparseArray;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.pm.ShortcutManagerCompat;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationsController;
import org.telegram.messenger.OpenChatReceiver;
import org.telegram.messenger.R;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Disguised notifications ("cover") for the per-chat privacy sheet.
 *
 * <p>When a dialog is covered, notification content for that dialog is built only from generic
 * personas. Real identity-bearing surfaces are never reused for covered paths.
 *
 * <p>Persistence contract (all values are per-account notifications preferences):
 * <br>- Namespace: {@code nax_cover_v1_*}.
 * <br>- Suppression key is scoped by parent dialog id ({@code nax_cover_v1_suppress_state_<dialogId>});
 * forum topics share the same parent dialog scope.
 * <br>- Suppression JSON envelope v1: {@code {"v":1,"items":[...],"aliases":[[alias,canonical],...]}}.
 * <br>- Decode is read-only: {@code v>1} is treated as version-ahead (suppression not applied, never rewritten);
 * malformed payload is decode-failed (suppression not applied, only an interaction-driven suppression write may
 * replace it with a fresh v1 payload).
 * <br>- Bounds: canonical suppression ids keep newest-first capped at 100, alias mappings capped at 200
 * so per-dialog state stays bounded while preserving remap continuity.
 * <br>- Interaction tokens and active-token pointers are persisted, survive process restart, and are cleaned by
 * this controller during interaction handling, reconciliation, cancel-all, disable, and account teardown paths.
 */
public final class NotificationCoverController {

    private static final String KEY_ENABLED = "nax_cover_v1_enabled_";
    private static final String KEY_PERSONA = "nax_cover_v1_persona_";
    private static final String KEY_TAP_ACTION = "nax_cover_v1_tap_";
    private static final String KEY_SUPPRESSION_STATE = "nax_cover_v1_suppress_state_";
    private static final String KEY_CHANNEL = "nax_cover_v1_channel_";
    private static final String KEY_SUMMARY_CHANNEL = "nax_cover_v1_summary_channel";
    private static final String KEY_TOKEN_RECORD = "nax_cover_v1_token_";
    private static final String KEY_ACTIVE_CHILD_TAP = "nax_cover_v1_active_child_tap_";
    private static final String KEY_ACTIVE_CHILD_DISMISS = "nax_cover_v1_active_child_dismiss_";
    private static final String KEY_ACTIVE_SUMMARY_TAP = "nax_cover_v1_active_summary_tap";
    private static final String KEY_ACTIVE_SUMMARY_DISMISS = "nax_cover_v1_active_summary_dismiss";
    private static final String KEY_ACTIVE_PREVIEW_TAP = "nax_cover_v1_active_preview_tap";
    private static final String KEY_PREVIEW_DIALOG = "nax_cover_v1_preview_dialog";

    public static final String EXTRA_COVER_TOKEN = "nax_cover_token";
    public static final String EXTRA_COVER_EVENT = "nax_cover_event";
    public static final int INTERACTION_EVENT_TAP = 1;
    public static final int INTERACTION_EVENT_DISMISS = 2;

    public static final int TAP_ACTION_HOLLOW = 0;
    public static final int TAP_ACTION_OPEN_CHAT = 1;

    private static final String TAG_PREFIX = "naxcover_";
    private static final int SUMMARY_REQUEST_CODE = 0x7A00;
    private static final int PREVIEW_ID_BASE = 0x7A40;
    private static final int SUPPRESSION_LIMIT = 100;
    private static final int SUPPRESSION_ALIAS_LIMIT = 200;
    private static final Object COVER_STATE_LOCK = new Object();

    private static final int TOKEN_KIND_CHILD_TAP = 1;
    private static final int TOKEN_KIND_CHILD_DISMISS = 2;
    private static final int TOKEN_KIND_SUMMARY_TAP = 3;
    private static final int TOKEN_KIND_SUMMARY_DISMISS = 4;
    private static final int TOKEN_KIND_PREVIEW_TAP = 5;

    private NotificationCoverController() {}

    private static final class Persona {
        final int id;
        final int labelRes;
        final int bodyRes;

        Persona(int id, int labelRes, int bodyRes) {
            this.id = id;
            this.labelRes = labelRes;
            this.bodyRes = bodyRes;
        }
    }

    private static final class MemberIdentity {
        final String canonicalCandidate;
        final String randomAlias;
        final String messageAlias;

        MemberIdentity(String canonicalCandidate, String randomAlias, String messageAlias) {
            this.canonicalCandidate = canonicalCandidate;
            this.randomAlias = randomAlias;
            this.messageAlias = messageAlias;
        }
    }

    private static final class SuppressionState {
        final ArrayList<String> canonical = new ArrayList<>();
        final LinkedHashMap<String, String> aliases = new LinkedHashMap<>();
        final boolean decodeFailed;
        final boolean versionAhead;

        SuppressionState(boolean decodeFailed, boolean versionAhead) {
            this.decodeFailed = decodeFailed;
            this.versionAhead = versionAhead;
        }

        boolean containsCanonical(String id) {
            return canonical.contains(id);
        }
    }

    private static final class InteractionRecord {
        int kind;
        int mode;
        int date;
        long dialogId;
        final LongSparseArray<ArrayList<String>> snapshots = new LongSparseArray<>();
    }

    public static final class CoverPostPlan {
        public final int displayCount;
        public final ArrayList<String> representedIds;

        CoverPostPlan(int displayCount, ArrayList<String> representedIds) {
            this.displayCount = displayCount;
            this.representedIds = representedIds;
        }

        public boolean hasRepresentedMembers() {
            return representedIds != null && !representedIds.isEmpty();
        }
    }

    private static final Persona[] POOL = {
            new Persona(1, R.string.NaxCoverPersonaBackgroundService, R.string.NaxCoverBodyBackgroundService),
            new Persona(2, R.string.NaxCoverPersonaBuildStatus, R.string.NaxCoverBodyBuildStatus),
            new Persona(3, R.string.NaxCoverPersonaDeviceMaintenance, R.string.NaxCoverBodyDeviceMaintenance),
            new Persona(4, R.string.NaxCoverPersonaSystemUpdate, R.string.NaxCoverBodySystemUpdate),
            new Persona(5, R.string.NaxCoverPersonaStorageCleanup, R.string.NaxCoverBodyStorageCleanup),
            new Persona(6, R.string.NaxCoverPersonaBatteryOptimizer, R.string.NaxCoverBodyBatteryOptimizer),
            new Persona(7, R.string.NaxCoverPersonaSyncService, R.string.NaxCoverBodySyncService),
            new Persona(8, R.string.NaxCoverPersonaSecurityCheck, R.string.NaxCoverBodySecurityCheck),
    };

    private static final int SAFE_PERSONA_ID = 1;
    private static final int[] TAP_ACTION_IDS = {TAP_ACTION_HOLLOW, TAP_ACTION_OPEN_CHAT};

    private static SharedPreferences prefs(int account) {
        return MessagesController.getNotificationsSettings(account);
    }

    private static Persona personaById(int id) {
        for (Persona p : POOL) {
            if (p.id == id) return p;
        }
        return null;
    }

    // ---- Config ----

    public static boolean isCovered(int account, long dialogId) {
        return dialogId != 0 && prefs(account).getBoolean(KEY_ENABLED + dialogId, false);
    }

    public static int resolvePersonaId(int account, long dialogId) {
        int id;
        try {
            id = prefs(account).getInt(KEY_PERSONA + dialogId, -1);
        } catch (ClassCastException e) {
            return SAFE_PERSONA_ID;
        }
        return personaById(id) != null ? id : SAFE_PERSONA_ID;
    }

    public static int assignPersonaIfAbsent(int account, long dialogId) {
        SharedPreferences p = prefs(account);
        if (p.contains(KEY_PERSONA + dialogId)) {
            return resolvePersonaId(account, dialogId);
        }
        int picked = POOL[(int) Math.floorMod(dialogId, POOL.length)].id;
        p.edit().putInt(KEY_PERSONA + dialogId, picked).apply();
        return picked;
    }

    public static int resolveTapAction(int account, long dialogId) {
        int mode;
        try {
            mode = prefs(account).getInt(KEY_TAP_ACTION + dialogId, TAP_ACTION_HOLLOW);
        } catch (ClassCastException e) {
            return TAP_ACTION_HOLLOW;
        }
        return mode == TAP_ACTION_OPEN_CHAT ? TAP_ACTION_OPEN_CHAT : TAP_ACTION_HOLLOW;
    }

    public static int[] tapActionIds() {
        return TAP_ACTION_IDS.clone();
    }

    public static String tapActionLabel(int mode) {
        return mode == TAP_ACTION_OPEN_CHAT
                ? LocaleController.getString(R.string.NaxCoverTapActionOpenChat)
                : LocaleController.getString(R.string.NaxCoverTapActionHollow);
    }

    public static String activeTapActionLabel(int account, long dialogId) {
        return tapActionLabel(resolveTapAction(account, dialogId));
    }

    public static void setTapAction(int account, long dialogId, int mode) {
        if (dialogId == 0) return;
        int safe = mode == TAP_ACTION_OPEN_CHAT ? TAP_ACTION_OPEN_CHAT : TAP_ACTION_HOLLOW;
        prefs(account).edit().putInt(KEY_TAP_ACTION + dialogId, safe).apply();
    }

    public static void setEnabled(int account, long dialogId, boolean enabled) {
        if (dialogId == 0) return;
        SharedPreferences p = prefs(account);
        boolean clearPreviewNotification = false;
        synchronized (COVER_STATE_LOCK) {
            SharedPreferences.Editor ed = p.edit().putBoolean(KEY_ENABLED + dialogId, enabled);
            if (enabled) {
                if (!p.contains(KEY_PERSONA + dialogId)) {
                    int picked = POOL[(int) Math.floorMod(dialogId, POOL.length)].id;
                    ed.putInt(KEY_PERSONA + dialogId, picked);
                }
                if (!p.contains(KEY_TAP_ACTION + dialogId)) {
                    ed.putInt(KEY_TAP_ACTION + dialogId, TAP_ACTION_HOLLOW);
                }
            } else {
                clearDialogInteractionState(account, dialogId, p, ed);
                if (p.getLong(KEY_PREVIEW_DIALOG, 0) == dialogId) {
                    clearPreviewStateLocked(p, ed);
                    clearPreviewNotification = true;
                }
            }
            ed.apply();
        }
        if (enabled) {
            clearConversationArtifacts(dialogId);
            NotificationManagerCompat.from(ApplicationLoader.applicationContext).cancel(internalId(dialogId));
        } else {
            NotificationManagerCompat.from(ApplicationLoader.applicationContext).cancel(coverTag(account, dialogId), internalId(dialogId));
        }
        if (clearPreviewNotification) {
            cancelPreviewNotification(account);
        }
    }

    public static void setPersona(int account, long dialogId, int personaId) {
        if (dialogId == 0 || personaById(personaId) == null) return;
        prefs(account).edit().putInt(KEY_PERSONA + dialogId, personaId).apply();
    }

    public static int[] personaIds() {
        int[] ids = new int[POOL.length];
        for (int i = 0; i < POOL.length; i++) ids[i] = POOL[i].id;
        return ids;
    }

    public static String personaLabel(int personaId) {
        Persona p = personaById(personaId);
        if (p == null) p = personaById(SAFE_PERSONA_ID);
        return LocaleController.getString(p.labelRes);
    }

    public static String activePersonaLabel(int account, long dialogId) {
        return personaLabel(resolvePersonaId(account, dialogId));
    }

    // ---- Membership and identity ----

    private static String randomIdentity(long randomId) {
        return randomId == 0 ? null : "r:" + randomId;
    }

    private static String messageIdentity(int messageId) {
        return messageId == 0 ? null : "m:" + messageId;
    }

    private static boolean validIdentity(String id) {
        if (TextUtils.isEmpty(id) || id.length() < 3 || id.charAt(1) != ':') {
            return false;
        }
        char kind = id.charAt(0);
        if (kind != 'r' && kind != 'm') {
            return false;
        }
        for (int i = 2; i < id.length(); i++) {
            char c = id.charAt(i);
            if (c == '-') {
                if (i != 2) return false;
            } else if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }

    private static boolean isRepresentableMember(MessageObject messageObject) {
        if (messageObject == null) return false;
        if (messageObject.isStoryPush || messageObject.isStoryMentionPush || messageObject.isLiveStoryPush || messageObject.isStoryReactionPush) {
            return false;
        }
        return true;
    }

    private static MemberIdentity identityOf(MessageObject messageObject) {
        if (!isRepresentableMember(messageObject)) {
            return null;
        }
        if (messageObject.messageOwner == null) {
            return null;
        }
        String random = randomIdentity(messageObject.messageOwner.random_id);
        String message = messageIdentity(messageObject.getId());
        if (random != null) {
            return new MemberIdentity(random, random, message);
        }
        if (message != null) {
            return new MemberIdentity(message, null, message);
        }
        return null;
    }

    public static HashSet<Long> collectCovered(int account, LongSparseArray<ArrayList<MessageObject>> byDialog) {
        HashSet<Long> set = new HashSet<>();
        for (int i = 0; i < byDialog.size(); i++) {
            long did = byDialog.keyAt(i);
            if (isCovered(account, did)) {
                set.add(did);
            }
        }
        return set;
    }

    public static boolean blocksPopupMessage(int account, long dialogId) {
        return dialogId != 0 && isCovered(account, dialogId);
    }

    // ---- Suppression state ----

    private static String suppressionStateKey(long dialogId) {
        return KEY_SUPPRESSION_STATE + dialogId;
    }

    private static String resolveCanonical(SuppressionState state, String id) {
        if (state == null || !validIdentity(id)) {
            return id;
        }
        String cur = id;
        for (int i = 0; i < 8; i++) {
            String next = state.aliases.get(cur);
            if (!validIdentity(next) || TextUtils.equals(cur, next)) {
                break;
            }
            cur = next;
        }
        return cur;
    }

    private static SuppressionState loadSuppressionState(int account, long dialogId) {
        String raw = prefs(account).getString(suppressionStateKey(dialogId), null);
        if (TextUtils.isEmpty(raw)) {
            return new SuppressionState(false, false);
        }
        try {
            JSONObject root = new JSONObject(raw);
            int version = root.optInt("v", -1);
            if (version > 1) {
                return new SuppressionState(false, true);
            }
            if (version != 1) {
                return new SuppressionState(true, false);
            }
            SuppressionState state = new SuppressionState(false, false);
            JSONArray items = root.optJSONArray("items");
            if (items != null) {
                for (int i = 0; i < items.length(); i++) {
                    String id = items.optString(i, null);
                    if (validIdentity(id) && !state.canonical.contains(id)) {
                        state.canonical.add(id);
                    }
                }
            }
            JSONArray aliases = root.optJSONArray("aliases");
            if (aliases != null) {
                for (int i = 0; i < aliases.length(); i++) {
                    JSONArray pair = aliases.optJSONArray(i);
                    if (pair == null || pair.length() != 2) {
                        continue;
                    }
                    String alias = pair.optString(0, null);
                    String canonical = pair.optString(1, null);
                    if (validIdentity(alias) && validIdentity(canonical)) {
                        state.aliases.put(alias, canonical);
                    }
                }
            }
            trimSuppressionState(state);
            return state;
        } catch (Exception e) {
            return new SuppressionState(true, false);
        }
    }

    private static String serializeSuppressionState(SuppressionState state) throws JSONException {
        JSONObject root = new JSONObject();
        root.put("v", 1);
        JSONArray items = new JSONArray();
        for (int i = 0; i < state.canonical.size(); i++) {
            items.put(state.canonical.get(i));
        }
        root.put("items", items);
        JSONArray aliases = new JSONArray();
        for (Map.Entry<String, String> e : state.aliases.entrySet()) {
            JSONArray pair = new JSONArray();
            pair.put(e.getKey());
            pair.put(e.getValue());
            aliases.put(pair);
        }
        root.put("aliases", aliases);
        return root.toString();
    }

    private static void saveSuppressionState(SharedPreferences.Editor ed, long dialogId, SuppressionState state) {
        if (state == null || state.decodeFailed || state.versionAhead) {
            return;
        }
        trimSuppressionState(state);
        try {
            ed.putString(suppressionStateKey(dialogId), serializeSuppressionState(state));
        } catch (JSONException e) {
            FileLog.e(e);
        }
    }

    private static void saveSuppressionState(int account, long dialogId, SuppressionState state) {
        SharedPreferences.Editor ed = prefs(account).edit();
        saveSuppressionState(ed, dialogId, state);
        ed.apply();
    }

    private static void trimSuppressionState(SuppressionState state) {
        if (state == null) return;
        while (state.canonical.size() > SUPPRESSION_LIMIT) {
            state.canonical.remove(state.canonical.size() - 1);
        }
        HashSet<String> allowedCanonical = new HashSet<>(state.canonical);
        ArrayList<String> removeAlias = new ArrayList<>();
        for (Map.Entry<String, String> e : state.aliases.entrySet()) {
            if (!validIdentity(e.getKey()) || !validIdentity(e.getValue()) || !allowedCanonical.contains(e.getValue())) {
                removeAlias.add(e.getKey());
            }
        }
        for (int i = 0; i < removeAlias.size(); i++) {
            state.aliases.remove(removeAlias.get(i));
        }
        while (state.aliases.size() > SUPPRESSION_ALIAS_LIMIT) {
            String first = null;
            for (String key : state.aliases.keySet()) {
                first = key;
                break;
            }
            if (first == null) break;
            state.aliases.remove(first);
        }
    }

    private static boolean upsertSuppressedIdentity(SuppressionState state, MemberIdentity identity) {
        if (state == null || state.decodeFailed || state.versionAhead || identity == null || !validIdentity(identity.canonicalCandidate)) {
            return false;
        }
        String canonical = resolveCanonical(state, identity.canonicalCandidate);
        if (!validIdentity(canonical)) {
            canonical = identity.canonicalCandidate;
        }
        boolean changed = false;
        int existing = state.canonical.indexOf(canonical);
        if (existing != 0) {
            if (existing > 0) {
                state.canonical.remove(existing);
            }
            state.canonical.add(0, canonical);
            changed = true;
        }
        if (validIdentity(identity.randomAlias)) {
            changed |= putAlias(state, identity.randomAlias, canonical);
        }
        if (validIdentity(identity.messageAlias)) {
            changed |= putAlias(state, identity.messageAlias, canonical);
        }
        trimSuppressionState(state);
        return changed;
    }

    private static boolean putAlias(SuppressionState state, String alias, String canonical) {
        String cur = state.aliases.get(alias);
        if (TextUtils.equals(cur, canonical)) {
            return false;
        }
        state.aliases.remove(alias);
        state.aliases.put(alias, canonical);
        return true;
    }

    private static boolean suppressCanonicalListFromInteractionLocked(int account, long dialogId, List<String> represented, SharedPreferences.Editor ed) {
        if (dialogId == 0 || represented == null || represented.isEmpty()) {
            return false;
        }
        SuppressionState state = loadSuppressionState(account, dialogId);
        if (state.versionAhead) {
            return false;
        }
        if (state.decodeFailed) {
            state = new SuppressionState(false, false);
        }
        boolean changed = false;
        for (int i = represented.size() - 1; i >= 0; i--) {
            String id = represented.get(i);
            if (!validIdentity(id)) {
                continue;
            }
            changed |= upsertSuppressedIdentity(state, new MemberIdentity(id, null, null));
        }
        if (changed) {
            saveSuppressionState(ed, dialogId, state);
        }
        return changed;
    }

    public static CoverPostPlan buildPostPlan(int account, long dialogId, ArrayList<MessageObject> messages) {
        if (messages == null || messages.isEmpty()) {
            return new CoverPostPlan(0, new ArrayList<>());
        }
        synchronized (COVER_STATE_LOCK) {
            SuppressionState state = loadSuppressionState(account, dialogId);
            LinkedHashSet<String> visible = new LinkedHashSet<>();
            boolean aliasChanged = false;
            for (int i = 0; i < messages.size(); i++) {
                MemberIdentity id = identityOf(messages.get(i));
                if (id == null) continue;
                String canonical = resolveCanonical(state, id.canonicalCandidate);
                if (!validIdentity(canonical)) {
                    canonical = id.canonicalCandidate;
                }
                if (!state.decodeFailed && !state.versionAhead) {
                    aliasChanged |= upsertAliasOnly(state, id, canonical);
                }
                if ((!state.decodeFailed && !state.versionAhead) && state.containsCanonical(canonical)) {
                    continue;
                }
                visible.add(canonical);
            }
            if (aliasChanged) {
                saveSuppressionState(account, dialogId, state);
            }
            int displayCount = visible.size();
            return new CoverPostPlan(displayCount, new ArrayList<>(visible));
        }
    }

    private static boolean upsertAliasOnly(SuppressionState state, MemberIdentity id, String canonical) {
        if (state == null || state.decodeFailed || state.versionAhead || id == null) return false;
        boolean changed = false;
        if (validIdentity(id.randomAlias)) {
            changed |= putAlias(state, id.randomAlias, canonical);
        }
        if (validIdentity(id.messageAlias)) {
            changed |= putAlias(state, id.messageAlias, canonical);
        }
        if (changed) {
            trimSuppressionState(state);
        }
        return changed;
    }

    public static boolean suppressVisibleDialog(int account, long dialogId, ArrayList<MessageObject> pushMessages) {
        if (dialogId == 0 || pushMessages == null || !isCovered(account, dialogId)) {
            return false;
        }
        synchronized (COVER_STATE_LOCK) {
            SuppressionState state = loadSuppressionState(account, dialogId);
            if (state.decodeFailed || state.versionAhead) {
                return false;
            }
            LinkedHashMap<String, MemberIdentity> members = new LinkedHashMap<>();
            for (int i = 0; i < pushMessages.size(); i++) {
                MessageObject messageObject = pushMessages.get(i);
                if (messageObject == null || messageObject.getDialogId() != dialogId) {
                    continue;
                }
                MemberIdentity id = identityOf(messageObject);
                if (id == null) continue;
                String canonical = resolveCanonical(state, id.canonicalCandidate);
                if (!validIdentity(canonical)) canonical = id.canonicalCandidate;
                members.put(canonical, new MemberIdentity(canonical, id.randomAlias, id.messageAlias));
            }
            if (members.isEmpty()) {
                return false;
            }
            boolean changed = false;
            for (MemberIdentity id : members.values()) {
                changed |= upsertSuppressedIdentity(state, id);
            }
            if (changed) {
                saveSuppressionState(account, dialogId, state);
            }
            return true;
        }
    }

    public static void onMessageRemapped(int account, long dialogId, long randomId, int messageId) {
        if (dialogId == 0 || randomId == 0 || messageId == 0) {
            return;
        }
        synchronized (COVER_STATE_LOCK) {
            SuppressionState state = loadSuppressionState(account, dialogId);
            if (state.decodeFailed || state.versionAhead) {
                return;
            }
            String random = randomIdentity(randomId);
            String mid = messageIdentity(messageId);
            if (!validIdentity(random) || !validIdentity(mid)) {
                return;
            }
            String canonical = resolveCanonical(state, random);
            if (!validIdentity(canonical)) {
                canonical = resolveCanonical(state, mid);
            }
            if (!validIdentity(canonical)) {
                canonical = random;
            }
            boolean changed = putAlias(state, random, canonical) | putAlias(state, mid, canonical);
            if (changed) {
                trimSuppressionState(state);
                saveSuppressionState(account, dialogId, state);
            }
        }
    }

    // ---- IDs/channels ----

    public static String coverTag(int account, long dialogId) {
        return TAG_PREFIX + account + "_" + dialogId;
    }

    public static int internalId(long dialogId) {
        return (int) dialogId + (int) (dialogId >> 32);
    }

    private static NotificationManager systemManager() {
        return (NotificationManager) ApplicationLoader.applicationContext.getSystemService(Context.NOTIFICATION_SERVICE);
    }

    private static void ensureChannel(String id, String name) {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = systemManager();
        if (nm == null) return;
        if (nm.getNotificationChannel(id) != null) return;
        NotificationChannel channel = new NotificationChannel(id, name, NotificationManager.IMPORTANCE_LOW);
        channel.enableLights(false);
        channel.enableVibration(false);
        channel.setSound(null, null);
        try {
            nm.createNotificationChannel(channel);
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    private static String childChannelId(int account, int personaId) {
        SharedPreferences p = prefs(account);
        String key = KEY_CHANNEL + personaId;
        String id = p.getString(key, null);
        if (id == null) {
            id = TAG_PREFIX + account + "_p" + personaId + "_" + Math.abs(java.util.UUID.randomUUID().getLeastSignificantBits());
            p.edit().putString(key, id).apply();
        }
        ensureChannel(id, personaLabel(personaId));
        return id;
    }

    private static String summaryChannelId(int account) {
        SharedPreferences p = prefs(account);
        String id = p.getString(KEY_SUMMARY_CHANNEL, null);
        if (id == null) {
            id = TAG_PREFIX + account + "_summary_" + Math.abs(java.util.UUID.randomUUID().getLeastSignificantBits());
            p.edit().putString(KEY_SUMMARY_CHANNEL, id).apply();
        }
        ensureChannel(id, LocaleController.getString(R.string.NaxCoverSummaryChannelName));
        return id;
    }

    // ---- Notification build helpers ----

    public static String coverLine(int account, long dialogId, int count) {
        int personaId = resolvePersonaId(account, dialogId);
        Persona p = personaById(personaId);
        if (p == null) p = personaById(SAFE_PERSONA_ID);
        return LocaleController.getString(p.labelRes) + ": " + LocaleController.formatString(p.bodyRes, count);
    }

    public static boolean postChild(int account, long dialogId, int count, boolean grouped, String group, ArrayList<String> representedIds) {
        Context ctx = ApplicationLoader.applicationContext;
        SharedPreferences p = prefs(account);
        try {
            clearConversationArtifacts(dialogId);
            if (representedIds == null || representedIds.isEmpty() || count <= 0) {
                synchronized (COVER_STATE_LOCK) {
                    SharedPreferences.Editor clearEditor = p.edit();
                    clearDialogInteractionState(account, dialogId, p, clearEditor);
                    clearEditor.apply();
                }
                return false;
            }
            int personaId = resolvePersonaId(account, dialogId);
            Persona persona = personaById(personaId);
            if (persona == null) persona = personaById(SAFE_PERSONA_ID);
            int internalId = internalId(dialogId);
            int tapMode = resolveTapAction(account, dialogId);

            LongSparseArray<ArrayList<String>> snapshots = new LongSparseArray<>();
            snapshots.put(dialogId, new ArrayList<>(representedIds));
            String tapToken;
            String dismissToken;
            synchronized (COVER_STATE_LOCK) {
                SharedPreferences.Editor ed = p.edit();
                tapToken = replaceActiveToken(ed, p, activeChildTapKey(dialogId), buildRecord(TOKEN_KIND_CHILD_TAP, tapMode, 0, dialogId, snapshots));
                dismissToken = replaceActiveToken(ed, p, activeChildDismissKey(dialogId), buildRecord(TOKEN_KIND_CHILD_DISMISS, TAP_ACTION_HOLLOW, 0, dialogId, snapshots));
                ed.apply();
            }

            NotificationCompat.Builder b = new NotificationCompat.Builder(ctx, childChannelId(account, personaId))
                    .setContentTitle(LocaleController.getString(persona.labelRes))
                    .setContentText(LocaleController.formatString(persona.bodyRes, count))
                    .setSmallIcon(R.drawable.nax_cover_notification)
                    .setNumber(count)
                    // NagramX: in Open chat mode we own dismissal in handleInteraction(); letting the system auto-cancel
                    // can dispatch delete before tap on some devices, which clears tap token state and drops open-chat.
                    .setAutoCancel(tapMode != TAP_ACTION_OPEN_CHAT)
                    .setOnlyAlertOnce(true)
                    .setShowWhen(false)
                    .setContentIntent(interactionIntent(account, tapToken, INTERACTION_EVENT_TAP, internalId))
                    .setDeleteIntent(interactionIntent(account, dismissToken, INTERACTION_EVENT_DISMISS, internalId + 0x31))
                    .setCategory(NotificationCompat.CATEGORY_STATUS)
                    .setPriority(NotificationCompat.PRIORITY_LOW);
            if (grouped) {
                b.setGroup(group);
                b.setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_SUMMARY);
            }
            NotificationManagerCompat.from(ctx).notify(coverTag(account, dialogId), internalId, b.build());
            return true;
        } catch (Exception t) {
            synchronized (COVER_STATE_LOCK) {
                SharedPreferences.Editor clearEditor = p.edit();
                clearDialogInteractionState(account, dialogId, p, clearEditor);
                clearEditor.apply();
            }
            FileLog.e("nax cover child post failed", t);
            return false;
        }
    }

    public static Notification buildCoverSummary(int account, String group, String title, List<String> lines, String subText, LongSparseArray<ArrayList<String>> representedByDialog, int summaryDate) {
        if (lines == null || lines.isEmpty()) {
            clearSummaryInteractionState(account);
            return null;
        }
        Context ctx = ApplicationLoader.applicationContext;
        NotificationCompat.InboxStyle inbox = new NotificationCompat.InboxStyle();
        inbox.setBigContentTitle(title);
        for (int i = 0; i < lines.size(); i++) {
            inbox.addLine(lines.get(i));
        }
        inbox.setSummaryText(subText);

        String tapToken;
        String dismissToken;
        try {
            SharedPreferences p = prefs(account);
            synchronized (COVER_STATE_LOCK) {
                SharedPreferences.Editor ed = p.edit();
                tapToken = replaceActiveToken(ed, p, KEY_ACTIVE_SUMMARY_TAP, buildRecord(TOKEN_KIND_SUMMARY_TAP, TAP_ACTION_HOLLOW, summaryDate, 0, representedByDialog));
                dismissToken = replaceActiveToken(ed, p, KEY_ACTIVE_SUMMARY_DISMISS, buildRecord(TOKEN_KIND_SUMMARY_DISMISS, TAP_ACTION_HOLLOW, summaryDate, 0, representedByDialog));
                ed.apply();
            }
        } catch (JSONException e) {
            SharedPreferences p = prefs(account);
            synchronized (COVER_STATE_LOCK) {
                SharedPreferences.Editor ed = p.edit();
                clearSummaryInteractionState(p, ed);
                ed.apply();
            }
            FileLog.e(e);
            return null;
        }

        return new NotificationCompat.Builder(ctx, summaryChannelId(account))
                .setContentTitle(title)
                .setContentText(subText)
                .setSmallIcon(R.drawable.nax_cover_notification)
                .setGroup(group)
                .setGroupSummary(true)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setContentIntent(interactionIntent(account, tapToken, INTERACTION_EVENT_TAP, SUMMARY_REQUEST_CODE))
                .setDeleteIntent(interactionIntent(account, dismissToken, INTERACTION_EVENT_DISMISS, SUMMARY_REQUEST_CODE + 0x11))
                .setStyle(inbox)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    public static boolean postPreview(int account, long dialogId) {
        if (dialogId == 0 || !isCovered(account, dialogId)) {
            return false;
        }
        Context ctx = ApplicationLoader.applicationContext;
        NotificationManagerCompat notificationManager = NotificationManagerCompat.from(ctx);
        if (!notificationManager.areNotificationsEnabled()) {
            return false;
        }
        try {
            int personaId = resolvePersonaId(account, dialogId);
            Persona persona = personaById(personaId);
            if (persona == null) persona = personaById(SAFE_PERSONA_ID);
            int count = 1 + (int) Math.floorMod(dialogId, 4);

            String token;
            SharedPreferences p = prefs(account);
            synchronized (COVER_STATE_LOCK) {
                SharedPreferences.Editor ed = p.edit();
                token = replaceActiveToken(ed, p, KEY_ACTIVE_PREVIEW_TAP, buildRecord(TOKEN_KIND_PREVIEW_TAP, TAP_ACTION_HOLLOW, 0, dialogId, new LongSparseArray<>()));
                ed.putLong(KEY_PREVIEW_DIALOG, dialogId);
                ed.apply();
            }

            NotificationCompat.Builder b = new NotificationCompat.Builder(ctx, childChannelId(account, personaId))
                    .setContentTitle(LocaleController.getString(persona.labelRes))
                    .setContentText(LocaleController.formatString(persona.bodyRes, count))
                    .setSmallIcon(R.drawable.nax_cover_notification)
                    .setNumber(count)
                    .setAutoCancel(true)
                    .setOnlyAlertOnce(true)
                    .setShowWhen(false)
                    .setContentIntent(interactionIntent(account, token, INTERACTION_EVENT_TAP, PREVIEW_ID_BASE + account))
                    .setCategory(NotificationCompat.CATEGORY_STATUS)
                    .setPriority(NotificationCompat.PRIORITY_LOW);
            notificationManager.notify(previewTag(account), PREVIEW_ID_BASE + account, b.build());
            return true;
        } catch (Exception e) {
            SharedPreferences p = prefs(account);
            synchronized (COVER_STATE_LOCK) {
                SharedPreferences.Editor ed = p.edit();
                clearPreviewStateLocked(p, ed);
                ed.apply();
            }
            cancelPreviewNotification(account);
            FileLog.e(e);
            return false;
        }
    }

    // ---- Interaction handling ----

    private static String previewTag(int account) {
        return TAG_PREFIX + account + "_preview";
    }

    private static String activeChildTapKey(long dialogId) {
        return KEY_ACTIVE_CHILD_TAP + dialogId;
    }

    private static String activeChildDismissKey(long dialogId) {
        return KEY_ACTIVE_CHILD_DISMISS + dialogId;
    }

    private static PendingIntent interactionIntent(int account, String token, int event, int requestCode) {
        Intent intent = new Intent(ApplicationLoader.applicationContext, org.telegram.messenger.NotificationDismissReceiver.class);
        intent.setAction("nax.cover.interaction." + token + "." + event);
        intent.putExtra("currentAccount", account);
        intent.putExtra(EXTRA_COVER_TOKEN, token);
        intent.putExtra(EXTRA_COVER_EVENT, event);
        return PendingIntent.getBroadcast(
                ApplicationLoader.applicationContext,
                requestCode ^ token.hashCode() ^ (event << 12),
                intent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
        );
    }

    private static InteractionRecord buildRecord(int kind, int mode, int date, long dialogId, LongSparseArray<ArrayList<String>> snapshots) {
        InteractionRecord record = new InteractionRecord();
        record.kind = kind;
        record.mode = mode;
        record.date = date;
        record.dialogId = dialogId;
        if (snapshots != null) {
            for (int i = 0; i < snapshots.size(); i++) {
                long did = snapshots.keyAt(i);
                ArrayList<String> ids = snapshots.valueAt(i);
                if (did == 0 || ids == null || ids.isEmpty()) {
                    continue;
                }
                ArrayList<String> safe = new ArrayList<>();
                LinkedHashSet<String> uniq = new LinkedHashSet<>(ids);
                for (String id : uniq) {
                    if (validIdentity(id)) {
                        safe.add(id);
                        if (safe.size() >= SUPPRESSION_LIMIT) {
                            break;
                        }
                    }
                }
                if (!safe.isEmpty()) {
                    record.snapshots.put(did, safe);
                }
            }
        }
        return record;
    }

    private static String newToken() {
        long hi = Math.abs(java.util.UUID.randomUUID().getMostSignificantBits());
        long lo = Math.abs(java.util.UUID.randomUUID().getLeastSignificantBits());
        return Long.toHexString(hi) + Long.toHexString(lo);
    }

    private static String replaceActiveToken(SharedPreferences.Editor ed, SharedPreferences p, String activeKey, InteractionRecord record) throws JSONException {
        String old = p.getString(activeKey, null);
        if (!TextUtils.isEmpty(old)) {
            ed.remove(KEY_TOKEN_RECORD + old);
        }
        String token = newToken();
        ed.putString(activeKey, token);
        ed.putString(KEY_TOKEN_RECORD + token, serializeRecord(record));
        return token;
    }

    private static String serializeRecord(InteractionRecord record) throws JSONException {
        JSONObject root = new JSONObject();
        root.put("v", 1);
        root.put("kind", record.kind);
        root.put("mode", record.mode);
        root.put("date", record.date);
        root.put("dialog", record.dialogId);
        JSONArray snapshots = new JSONArray();
        for (int i = 0; i < record.snapshots.size(); i++) {
            long did = record.snapshots.keyAt(i);
            ArrayList<String> ids = record.snapshots.valueAt(i);
            if (did == 0 || ids == null || ids.isEmpty()) continue;
            JSONObject entry = new JSONObject();
            entry.put("dialog", did);
            JSONArray idArr = new JSONArray();
            for (int j = 0; j < ids.size(); j++) {
                idArr.put(ids.get(j));
            }
            entry.put("ids", idArr);
            snapshots.put(entry);
        }
        root.put("snapshots", snapshots);
        return root.toString();
    }

    private static InteractionRecord parseRecord(String raw) {
        if (TextUtils.isEmpty(raw)) return null;
        try {
            JSONObject root = new JSONObject(raw);
            if (root.optInt("v", -1) != 1) {
                return null;
            }
            InteractionRecord record = new InteractionRecord();
            record.kind = root.optInt("kind", 0);
            record.mode = root.optInt("mode", TAP_ACTION_HOLLOW);
            record.date = root.optInt("date", 0);
            record.dialogId = root.optLong("dialog", 0);
            JSONArray snapshots = root.optJSONArray("snapshots");
            if (snapshots != null) {
                for (int i = 0; i < snapshots.length(); i++) {
                    JSONObject entry = snapshots.optJSONObject(i);
                    if (entry == null) continue;
                    long did = entry.optLong("dialog", 0);
                    JSONArray ids = entry.optJSONArray("ids");
                    if (did == 0 || ids == null) continue;
                    ArrayList<String> safe = new ArrayList<>();
                    for (int j = 0; j < ids.length(); j++) {
                        String id = ids.optString(j, null);
                        if (validIdentity(id) && !safe.contains(id)) {
                            safe.add(id);
                            if (safe.size() >= SUPPRESSION_LIMIT) {
                                break;
                            }
                        }
                    }
                    if (!safe.isEmpty()) {
                        record.snapshots.put(did, safe);
                    }
                }
            }
            return record;
        } catch (Exception e) {
            return null;
        }
    }

    private static String activeKeyForKind(InteractionRecord record) {
        if (record == null) return null;
        if (record.kind == TOKEN_KIND_CHILD_TAP) return activeChildTapKey(record.dialogId);
        if (record.kind == TOKEN_KIND_CHILD_DISMISS) return activeChildDismissKey(record.dialogId);
        if (record.kind == TOKEN_KIND_SUMMARY_TAP) return KEY_ACTIVE_SUMMARY_TAP;
        if (record.kind == TOKEN_KIND_SUMMARY_DISMISS) return KEY_ACTIVE_SUMMARY_DISMISS;
        if (record.kind == TOKEN_KIND_PREVIEW_TAP) return KEY_ACTIVE_PREVIEW_TAP;
        return null;
    }

    public static boolean handleInteraction(int account, String token, int event) {
        if (TextUtils.isEmpty(token) || (event != INTERACTION_EVENT_TAP && event != INTERACTION_EVENT_DISMISS)) {
            return false;
        }
        SharedPreferences p = prefs(account);
        InteractionRecord record;
        boolean cancelChild = false;
        boolean cancelPreview = false;
        boolean openChat = false;
        long openDialogId = 0;
        boolean shouldRebuild = false;
        synchronized (COVER_STATE_LOCK) {
            record = parseRecord(p.getString(KEY_TOKEN_RECORD + token, null));
            if (record == null) {
                return false;
            }
            String activeKey = activeKeyForKind(record);
            if (TextUtils.isEmpty(activeKey)) {
                return true;
            }
            String active = p.getString(activeKey, null);
            if (!TextUtils.equals(active, token)) {
                return true;
            }
            if ((record.kind == TOKEN_KIND_CHILD_TAP || record.kind == TOKEN_KIND_SUMMARY_TAP || record.kind == TOKEN_KIND_PREVIEW_TAP)
                    && event != INTERACTION_EVENT_TAP) {
                return true;
            }
            if ((record.kind == TOKEN_KIND_CHILD_DISMISS || record.kind == TOKEN_KIND_SUMMARY_DISMISS) && event != INTERACTION_EVENT_DISMISS) {
                return true;
            }

            SharedPreferences.Editor ed = p.edit();
            if (record.kind == TOKEN_KIND_PREVIEW_TAP) {
                clearPreviewStateLocked(p, ed);
                ed.commit();
                cancelPreview = true;
            } else {
                for (int i = 0; i < record.snapshots.size(); i++) {
                    long did = record.snapshots.keyAt(i);
                    ArrayList<String> ids = record.snapshots.valueAt(i);
                    suppressCanonicalListFromInteractionLocked(account, did, ids, ed);
                }
                if (record.kind == TOKEN_KIND_SUMMARY_DISMISS && record.date > 0) {
                    int currentDismissDate = p.getInt("dismissDate", 0);
                    if (record.date > currentDismissDate) {
                        ed.putInt("dismissDate", record.date);
                    }
                }

                if (record.kind == TOKEN_KIND_CHILD_TAP || record.kind == TOKEN_KIND_CHILD_DISMISS) {
                    clearDialogInteractionState(account, record.dialogId, p, ed);
                } else {
                    clearSummaryInteractionState(p, ed);
                }
                ed.commit();

                cancelChild = record.kind == TOKEN_KIND_CHILD_TAP || record.kind == TOKEN_KIND_CHILD_DISMISS;
                openChat = record.kind == TOKEN_KIND_CHILD_TAP && record.mode == TAP_ACTION_OPEN_CHAT;
                openDialogId = record.dialogId;
                shouldRebuild = record.kind == TOKEN_KIND_CHILD_TAP || record.kind == TOKEN_KIND_SUMMARY_TAP;
            }
        }

        if (cancelPreview) {
            cancelPreviewNotification(account);
            return true;
        }
        if (cancelChild) {
            NotificationManagerCompat.from(ApplicationLoader.applicationContext).cancel(coverTag(account, record.dialogId), internalId(record.dialogId));
        }
        if (openChat) {
            openDialog(account, openDialogId);
        }
        if (shouldRebuild) {
            NotificationsController.getInstance(account).showNotifications();
        }
        return true;
    }

    private static void openDialog(int account, long dialogId) {
        if (dialogId == 0) return;
        Intent intent = new Intent(ApplicationLoader.applicationContext, OpenChatReceiver.class);
        intent.setAction("com.tmessages.openchat" + Math.random() + Integer.MAX_VALUE);
        if (DialogObject.isUserDialog(dialogId)) {
            intent.putExtra("userId", dialogId);
        } else if (DialogObject.isChatDialog(dialogId)) {
            intent.putExtra("chatId", -dialogId);
        } else if (DialogObject.isEncryptedDialog(dialogId)) {
            intent.putExtra("encId", DialogObject.getEncryptedChatId(dialogId));
        } else {
            return;
        }
        intent.putExtra("currentAccount", account);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        try {
            ApplicationLoader.applicationContext.startActivity(intent);
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    public static void clearSummaryInteractionState(int account) {
        synchronized (COVER_STATE_LOCK) {
            SharedPreferences p = prefs(account);
            SharedPreferences.Editor ed = p.edit();
            clearSummaryInteractionState(p, ed);
            ed.apply();
        }
    }

    private static void clearSummaryInteractionState(SharedPreferences p, SharedPreferences.Editor ed) {
        removeTokenByActiveKey(p, ed, KEY_ACTIVE_SUMMARY_TAP);
        removeTokenByActiveKey(p, ed, KEY_ACTIVE_SUMMARY_DISMISS);
    }

    private static SharedPreferences.Editor clearDialogInteractionState(int account, long dialogId, SharedPreferences p, SharedPreferences.Editor ed) {
        removeTokenByActiveKey(p, ed, activeChildTapKey(dialogId));
        removeTokenByActiveKey(p, ed, activeChildDismissKey(dialogId));
        return ed;
    }

    private static void removeTokenByActiveKey(SharedPreferences p, SharedPreferences.Editor ed, String activeKey) {
        String token = p.getString(activeKey, null);
        if (!TextUtils.isEmpty(token)) {
            ed.remove(KEY_TOKEN_RECORD + token);
        }
        ed.remove(activeKey);
    }

    private static void clearPreviewStateLocked(SharedPreferences p, SharedPreferences.Editor ed) {
        removeTokenByActiveKey(p, ed, KEY_ACTIVE_PREVIEW_TAP);
        ed.remove(KEY_PREVIEW_DIALOG);
    }

    private static void cancelPreviewNotification(int account) {
        NotificationManagerCompat.from(ApplicationLoader.applicationContext).cancel(previewTag(account), PREVIEW_ID_BASE + account);
    }

    private static void clearConversationArtifacts(long dialogId) {
        if (Build.VERSION.SDK_INT < 26 || dialogId == 0) {
            return;
        }
        ArrayList<String> ids = new ArrayList<>();
        ids.add("ndid_" + dialogId);
        try {
            ShortcutManagerCompat.removeDynamicShortcuts(ApplicationLoader.applicationContext, ids);
            if (Build.VERSION.SDK_INT >= 30) {
                android.content.pm.ShortcutManager shortcutManager = ApplicationLoader.applicationContext.getSystemService(android.content.pm.ShortcutManager.class);
                if (shortcutManager != null) {
                    shortcutManager.removeLongLivedShortcuts(ids);
                }
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    // ---- Reconciliation / teardown ----

    private static long parseDialogId(String key, String prefix) {
        try {
            return Long.parseLong(key.substring(prefix.length()));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public static void reconcile(int account, LongSparseArray<Integer> oldPosted, LongSparseArray<Integer> nowPosted, SharedPreferences preferences, boolean coverSummaryPosted) {
        HashSet<Long> candidates = new HashSet<>();
        for (int i = 0; i < oldPosted.size(); i++) {
            candidates.add(oldPosted.keyAt(i));
        }
        for (Map.Entry<String, ?> e : preferences.getAll().entrySet()) {
            String k = e.getKey();
            if (k.startsWith(KEY_ENABLED)) {
                long did = parseDialogId(k, KEY_ENABLED);
                if (did != 0) candidates.add(did);
            }
        }
        NotificationManagerCompat nm = NotificationManagerCompat.from(ApplicationLoader.applicationContext);
        SharedPreferences.Editor ed = preferences.edit();
        ArrayList<Long> staleDialogs = new ArrayList<>();
        for (Long did : candidates) {
            if (nowPosted.indexOfKey(did) >= 0) continue;
            staleDialogs.add(did);
            nm.cancel(coverTag(account, did), internalId(did));
        }
        synchronized (COVER_STATE_LOCK) {
            for (int i = 0; i < staleDialogs.size(); i++) {
                clearDialogInteractionState(account, staleDialogs.get(i), preferences, ed);
            }
            if (!coverSummaryPosted) {
                clearSummaryInteractionState(preferences, ed);
            }
            ed.apply();
        }
    }

    public static void cancelAll(int account, SharedPreferences preferences) {
        NotificationManagerCompat nm = NotificationManagerCompat.from(ApplicationLoader.applicationContext);
        ArrayList<Long> coveredDialogs = new ArrayList<>();
        for (Map.Entry<String, ?> e : preferences.getAll().entrySet()) {
            String k = e.getKey();
            if (k.startsWith(KEY_ENABLED)) {
                long did = parseDialogId(k, KEY_ENABLED);
                if (did != 0) {
                    coveredDialogs.add(did);
                    nm.cancel(coverTag(account, did), internalId(did));
                }
            }
        }
        synchronized (COVER_STATE_LOCK) {
            SharedPreferences.Editor ed = preferences.edit();
            for (int i = 0; i < coveredDialogs.size(); i++) {
                clearDialogInteractionState(account, coveredDialogs.get(i), preferences, ed);
            }
            clearSummaryInteractionState(preferences, ed);
            clearPreviewStateLocked(preferences, ed);
            ed.apply();
        }
        cancelPreviewNotification(account);
    }

    public static void deleteChannels(int account, SharedPreferences preferences) {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = systemManager();
        if (nm == null) return;
        ArrayList<String> ids = new ArrayList<>();
        for (Map.Entry<String, ?> e : preferences.getAll().entrySet()) {
            String k = e.getKey();
            if (k.startsWith(KEY_CHANNEL) || k.equals(KEY_SUMMARY_CHANNEL)) {
                Object v = e.getValue();
                if (v instanceof String) ids.add((String) v);
            }
        }
        for (int i = 0; i < ids.size(); i++) {
            try {
                nm.deleteNotificationChannel(ids.get(i));
            } catch (Exception ex) {
                FileLog.e(ex);
            }
        }
    }
}
