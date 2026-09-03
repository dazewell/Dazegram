package com.radolyn.ayugram.chatprivacy;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

import androidx.collection.LongSparseArray;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.ui.LaunchActivity;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/**
 * Disguised notifications ("cover") for the per-chat privacy sheet.
 *
 * <p>When a dialog is covered, its notification is never built from the real chat: not the
 * name, sender, avatar or message text. Instead a fixed generic persona (a plausible
 * system-service line with a real unread count) is shown in its place, on its own generic
 * channel. State is per (account, parent dialog); topics share the parent's state.
 *
 * <p>Config lives in the account's notifications {@link SharedPreferences}
 * ({@link MessagesController#getNotificationsSettings(int)}), never a bespoke file, so it is
 * cleared with everything else on logout. Keys:
 * <ul>
 *   <li>{@code nax_cover_v1_enabled_<dialogId>} - covered on/off, default false;</li>
 *   <li>{@code nax_cover_v1_persona_<dialogId>} - stable persona id, assigned once;</li>
 *   <li>{@code nax_cover_v1_open_<dialogId>} - reserved for PR3, not read here;</li>
 *   <li>{@code nax_cover_v1_channel_<personaId>} - the generic child channel id;</li>
 *   <li>{@code nax_cover_v1_summary_channel} - the generic summary channel id.</li>
 * </ul>
 *
 * <p>An unknown or missing persona while enabled reads as the fixed safe persona without
 * rewriting the preference; the assignment survives disabling. Generic channels are created
 * lazily and, once created, only their stored id is reused - Android Settings stay
 * authoritative for importance/sound, and channels are never recreated on a toggle.
 */
public final class NotificationCoverController {

    private static final String KEY_ENABLED = "nax_cover_v1_enabled_";
    private static final String KEY_PERSONA = "nax_cover_v1_persona_";
    private static final String KEY_CHANNEL = "nax_cover_v1_channel_";
    private static final String KEY_SUMMARY_CHANNEL = "nax_cover_v1_summary_channel";

    private static final String TAG_PREFIX = "naxcover_";
    private static final int SUMMARY_REQUEST_CODE = 0x7A00;

    private NotificationCoverController() {}

    /** A fixed, flat generic persona. Title doubles as the notification content title. */
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

    // Fixed pool. Ids are stable and persisted; never renumber an existing entry.
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

    private static SharedPreferences prefs(int account) {
        return MessagesController.getNotificationsSettings(account);
    }

    private static Persona personaById(int id) {
        for (Persona p : POOL) {
            if (p.id == id) return p;
        }
        return null;
    }

    // ---- Config surface (read/write) ----

    public static boolean isCovered(int account, long dialogId) {
        return dialogId != 0 && prefs(account).getBoolean(KEY_ENABLED + dialogId, false);
    }

    /** Stored persona id, or the safe fallback for an unknown/missing/wrong-typed value. Never writes. */
    public static int resolvePersonaId(int account, long dialogId) {
        int id;
        try {
            id = prefs(account).getInt(KEY_PERSONA + dialogId, -1);
        } catch (ClassCastException e) {
            // a wrong-typed or future-format stored value reads as the safe persona, without rewriting it
            return SAFE_PERSONA_ID;
        }
        return personaById(id) != null ? id : SAFE_PERSONA_ID;
    }

    /**
     * Assigns a stable persona only when none is stored yet, and returns the effective id. A stored
     * value is never overwritten: a valid one is honored, and an invalid/wrong-typed/future one falls
     * back to the safe persona at read time (via {@link #resolvePersonaId}) without mutation, so a
     * downgrade-then-re-enable keeps the user's stored choice.
     */
    public static int assignPersonaIfAbsent(int account, long dialogId) {
        SharedPreferences p = prefs(account);
        if (p.contains(KEY_PERSONA + dialogId)) {
            return resolvePersonaId(account, dialogId);
        }
        int picked = POOL[(int) Math.floorMod(dialogId, POOL.length)].id;
        p.edit().putInt(KEY_PERSONA + dialogId, picked).apply();
        return picked;
    }

    public static void setEnabled(int account, long dialogId, boolean enabled) {
        if (dialogId == 0) return;
        prefs(account).edit().putBoolean(KEY_ENABLED + dialogId, enabled).apply();
        if (enabled) {
            assignPersonaIfAbsent(account, dialogId);
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

    /** Label of the persona currently in effect for a dialog. */
    public static String activePersonaLabel(int account, long dialogId) {
        return personaLabel(resolvePersonaId(account, dialogId));
    }

    // ---- Membership helpers ----

    /** True when the batch has at least one message that is not a story / story-reaction push. */
    public static boolean hasCoverableMessage(ArrayList<MessageObject> messages) {
        if (messages == null) return false;
        for (int i = 0; i < messages.size(); i++) {
            MessageObject m = messages.get(i);
            if (m.isStoryPush || m.isStoryReactionPush) continue;
            if (m.isLiveStoryPush) continue;
            return true;
        }
        return false;
    }

    /** Exact set of covered dialogs among the current per-dialog push members. */
    public static HashSet<Long> collectCovered(int account, LongSparseArray<ArrayList<MessageObject>> byDialog) {
        HashSet<Long> set = new HashSet<>();
        for (int i = 0; i < byDialog.size(); i++) {
            long did = byDialog.keyAt(i);
            if (isCovered(account, did) && hasCoverableMessage(byDialog.valueAt(i))) {
                set.add(did);
            }
        }
        return set;
    }

    // ---- Tag / id ----

    public static String coverTag(int account, long dialogId) {
        return TAG_PREFIX + account + "_" + dialogId;
    }

    /** Same integer id the upstream child would have carried; the tag keeps it distinct. */
    public static int internalId(long dialogId) {
        return (int) dialogId + (int) (dialogId >> 32);
    }

    // ---- Channels ----

    private static NotificationManager systemManager() {
        return (NotificationManager) ApplicationLoader.applicationContext.getSystemService(Context.NOTIFICATION_SERVICE);
    }

    private static void ensureChannel(String id, String name) {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = systemManager();
        if (nm == null) return;
        // Only create when missing: an existing channel's user settings stay authoritative.
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

    // ---- Fresh, allow-listed builders ----

    private static PendingIntent inertIntent(int account, int requestCode) {
        // Opens the app to no particular chat; PR3 owns the "when tapped" behaviour.
        Intent intent = new Intent(ApplicationLoader.applicationContext, LaunchActivity.class);
        intent.setAction("nax.cover.open." + account + "." + requestCode);
        intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        intent.putExtra("currentAccount", account);
        return PendingIntent.getActivity(ApplicationLoader.applicationContext, requestCode, intent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    /** One generic InboxStyle line for a covered dialog: "Persona: body with count". */
    public static String coverLine(int account, long dialogId, int count) {
        int personaId = resolvePersonaId(account, dialogId);
        Persona p = personaById(personaId);
        if (p == null) p = personaById(SAFE_PERSONA_ID);
        return LocaleController.getString(p.labelRes) + ": " + LocaleController.formatString(p.bodyRes, count);
    }

    /**
     * Builds and posts the fresh disguised child. The COMPLETE operation — cover channel, safe intent,
     * builder, build and tagged notify — runs inside one try, so a preparation failure is caught the same
     * as a {@code notify} failure and reported to the caller, never leaving a half-built state or falling
     * back to the real builder. Returns true only when the tagged post actually landed, so the caller
     * records the cover as live only then and a failed post is reconciled away rather than masking a stale
     * cover at the same tag. Catches {@link Exception} (not {@link Throwable}): an {@link Error} unwinds the
     * covered branch and still cannot reach the real-child path, so masking VM-level failures would help
     * nothing while hiding ordinary preparation failures.
     */
    public static boolean postChild(int account, long dialogId, int count, boolean grouped, String group) {
        Context ctx = ApplicationLoader.applicationContext;
        try {
            int personaId = resolvePersonaId(account, dialogId);
            Persona p = personaById(personaId);
            if (p == null) p = personaById(SAFE_PERSONA_ID);
            int internalId = internalId(dialogId);

            NotificationCompat.Builder b = new NotificationCompat.Builder(ctx, childChannelId(account, personaId))
                    .setContentTitle(LocaleController.getString(p.labelRes))
                    .setContentText(LocaleController.formatString(p.bodyRes, count))
                    .setSmallIcon(R.drawable.nax_cover_notification)
                    .setNumber(count)
                    .setAutoCancel(true)
                    .setOnlyAlertOnce(true)
                    .setShowWhen(false)
                    .setContentIntent(inertIntent(account, internalId))
                    .setCategory(NotificationCompat.CATEGORY_STATUS)
                    .setPriority(NotificationCompat.PRIORITY_LOW);
            if (grouped) {
                b.setGroup(group);
                b.setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_SUMMARY);
            }
            NotificationManagerCompat.from(ctx).notify(coverTag(account, dialogId), internalId, b.build());
            return true;
        } catch (Exception t) {
            // Fail closed: a covered chat must never fall back to the real notification.
            FileLog.e("nax cover child post failed", t);
            return false;
        }
    }

    /** Fresh generic group summary that shows generic lines for covered dialogs. */
    public static Notification buildCoverSummary(int account, String group, String title, List<String> lines, String subText) {
        Context ctx = ApplicationLoader.applicationContext;
        NotificationCompat.InboxStyle inbox = new NotificationCompat.InboxStyle();
        inbox.setBigContentTitle(title);
        for (int i = 0; i < lines.size(); i++) {
            inbox.addLine(lines.get(i));
        }
        inbox.setSummaryText(subText);
        NotificationCompat.Builder b = new NotificationCompat.Builder(ctx, summaryChannelId(account))
                .setContentTitle(title)
                .setContentText(subText)
                .setSmallIcon(R.drawable.nax_cover_notification)
                .setGroup(group)
                .setGroupSummary(true)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setContentIntent(inertIntent(account, SUMMARY_REQUEST_CODE))
                .setStyle(inbox)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .setPriority(NotificationCompat.PRIORITY_LOW);
        return b.build();
    }

    // ---- Reconciliation / teardown ----

    private static long parseDialogId(String key, String prefix) {
        try {
            return Long.parseLong(key.substring(prefix.length()));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * Cancels every disguised handle that should no longer be posted: anything posted last
     * rebuild or configured in preferences, minus what is posted now. Restart-safe because the
     * tag+id is derived deterministically from the dialog id.
     */
    public static void reconcile(int account, LongSparseArray<Integer> oldPosted, LongSparseArray<Integer> nowPosted, SharedPreferences preferences) {
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
        for (Long did : candidates) {
            if (nowPosted.indexOfKey(did) >= 0) continue;
            nm.cancel(coverTag(account, did), internalId(did));
        }
    }

    /** Cancels all disguised handles known from preferences (logout, hide, dismiss). */
    public static void cancelAll(int account, SharedPreferences preferences) {
        NotificationManagerCompat nm = NotificationManagerCompat.from(ApplicationLoader.applicationContext);
        for (Map.Entry<String, ?> e : preferences.getAll().entrySet()) {
            String k = e.getKey();
            if (k.startsWith(KEY_ENABLED)) {
                long did = parseDialogId(k, KEY_ENABLED);
                if (did != 0) nm.cancel(coverTag(account, did), internalId(did));
            }
        }
    }

    /** Deletes the generic cover channels for an account (logout only). */
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
