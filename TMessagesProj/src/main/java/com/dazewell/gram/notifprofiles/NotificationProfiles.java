package com.dazewell.gram.notifprofiles;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

import androidx.collection.LongSparseArray;
import androidx.core.app.NotificationCompat;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.NotificationsController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;

import java.util.ArrayList;
import java.util.List;
import java.util.TimeZone;

// NagramX: per-chat notification profiles. Loud is "no override" (today's path, byte-identical), Quiet keeps the
// icon, vibration, lock screen and watch but drops the sound, Passive sits in the shade only. The assignment is
// one int per chat in the account's notifications prefs, next to an optional schedule and override string (see
// NotificationSchedule), so logout wipes it all, like the cover and Show on Watch state. Keyed by raw dialogId because a notification batch's DialogKey carries whichever topic pushed first.
// Presentation lives on a handful of fork-owned channels per account, not per chat: moving a chat between profiles
// only changes which existing channel its notification posts on, so no channel is ever recreated. The channel ids
// are fixed and versioned (never start with "<account>channel", which logout and upstream cleanup delete); a change
// to a preset's channel settings has to bump CHANNEL_VERSION because Android ignores edits to an existing channel.
public final class NotificationProfiles {

    public static final int LOUD = 0;
    public static final int QUIET = 1;
    public static final int PASSIVE = 2;

    private static final String KEY = "nax_np_";
    // Strings, so they can't collide with the int key above (a dialog id never starts with a letter).
    private static final String SCHEDULE_KEY = "nax_np_s_";
    private static final String OVERRIDE_KEY = "nax_np_o_";
    private static final String CHANNEL_VERSION = "v1";

    private NotificationProfiles() {
    }

    private static SharedPreferences prefs(int account) {
        return MessagesController.getNotificationsSettings(account);
    }

    private static int clamp(int value) {
        return value == QUIET || value == PASSIVE ? value : LOUD;
    }

    // An absent, stale or unparseable value reads as Loud, never throws.
    public static int get(int account, long dialogId) {
        if (dialogId == 0) return LOUD;
        try {
            return clamp(prefs(account).getInt(KEY + dialogId, LOUD));
        } catch (ClassCastException e) {
            return LOUD;
        }
    }

    // Loud removes the key, so a Loud chat stores nothing. On a chat with a schedule the choice is an override instead
    // (profile + the time it was set) and the stored base value is left alone; it lapses at the next schedule boundary.
    public static void set(int account, long dialogId, int profile) {
        if (dialogId == 0) return;
        if (rules(account, dialogId).isEmpty()) {
            setBase(account, dialogId, profile);
            return;
        }
        prefs(account).edit().putString(OVERRIDE_KEY + dialogId, clamp(profile) + ":" + System.currentTimeMillis()).apply();
    }

    // The stored profile itself: all there is on a chat without a schedule, and what applies outside every window on
    // one with a schedule.
    public static void setBase(int account, long dialogId, int profile) {
        if (dialogId == 0) return;
        SharedPreferences.Editor editor = prefs(account).edit();
        profile = clamp(profile);
        if (profile == LOUD) {
            editor.remove(KEY + dialogId);
        } else {
            editor.putInt(KEY + dialogId, profile);
        }
        editor.apply();
    }

    // The chat's schedule rules; absent or unparseable reads as none.
    public static List<NotificationSchedule.Rule> rules(int account, long dialogId) {
        if (dialogId == 0) return new ArrayList<>();
        try {
            return NotificationSchedule.parse(prefs(account).getString(SCHEDULE_KEY + dialogId, null));
        } catch (ClassCastException e) {
            return new ArrayList<>();
        }
    }

    // Saving a schedule means the schedule is in charge now, so the override goes in the same edit.
    public static void setRules(int account, long dialogId, List<NotificationSchedule.Rule> rules) {
        if (dialogId == 0) return;
        SharedPreferences.Editor editor = prefs(account).edit();
        editor.remove(OVERRIDE_KEY + dialogId);
        if (rules.isEmpty()) {
            editor.remove(SCHEDULE_KEY + dialogId);
        } else {
            editor.putString(SCHEDULE_KEY + dialogId, NotificationSchedule.serialize(rules));
        }
        editor.apply();
    }

    // The override's profile while it is still in force, else -1. In force until a boundary passes after it was set;
    // one stamped in the future (clock moved back) or unparseable counts as lapsed.
    private static int liveOverride(int account, long dialogId, List<NotificationSchedule.Rule> rules, long now, TimeZone zone) {
        String value;
        try {
            value = prefs(account).getString(OVERRIDE_KEY + dialogId, null);
        } catch (ClassCastException e) {
            return -1;
        }
        if (value == null) return -1;
        try {
            int colon = value.indexOf(':');
            int profile = Integer.parseInt(value.substring(0, colon));
            long setAt = Long.parseLong(value.substring(colon + 1));
            if (setAt > now || NotificationSchedule.nextBoundaryAfter(rules, setAt, zone) <= now) return -1;
            return clamp(profile);
        } catch (RuntimeException e) {
            return -1;
        }
    }

    // What the chat actually does right now: a live override, else the open schedule window, else the stored base.
    public static int effective(int account, long dialogId) {
        List<NotificationSchedule.Rule> rules = rules(account, dialogId);
        if (rules.isEmpty()) return get(account, dialogId);
        long now = System.currentTimeMillis();
        TimeZone zone = TimeZone.getDefault();
        int override = liveOverride(account, dialogId, rules, now, zone);
        if (override >= 0) return override;
        int active = NotificationSchedule.activeRule(rules, now, zone);
        return active >= 0 ? clamp(rules.get(active).profile) : get(account, dialogId);
    }

    // True while a schedule window, not the user's own choice, decides the profile.
    public static boolean isScheduleDeciding(int account, long dialogId) {
        List<NotificationSchedule.Rule> rules = rules(account, dialogId);
        if (rules.isEmpty()) return false;
        long now = System.currentTimeMillis();
        TimeZone zone = TimeZone.getDefault();
        return liveOverride(account, dialogId, rules, now, zone) < 0 && NotificationSchedule.activeRule(rules, now, zone) >= 0;
    }

    public static int labelRes(int profile) {
        switch (clamp(profile)) {
            case QUIET:
                return R.string.NaxNotifProfileQuiet;
            case PASSIVE:
                return R.string.NaxNotifProfilePassive;
            default:
                return R.string.NaxNotifProfileLoud;
        }
    }

    public static int infoRes(int profile) {
        switch (clamp(profile)) {
            case QUIET:
                return R.string.NaxNotifProfileQuietInfo;
            case PASSIVE:
                return R.string.NaxNotifProfilePassiveInfo;
            default:
                return R.string.NaxNotifProfileLoudInfo;
        }
    }

    // One snapshot per notification rebuild, taken in the preflight: only dialogs with a non-Loud profile are present, so the
    // summary decision and the per-chat children agree even if the user changes a profile mid-rebuild. Covered chats are
    // included; their cover posts through the same apply().
    public static LongSparseArray<Integer> collect(int account, LongSparseArray<ArrayList<MessageObject>> byDialog) {
        LongSparseArray<Integer> result = new LongSparseArray<>();
        if (Build.VERSION.SDK_INT < 26) return result;
        for (int i = 0; i < byDialog.size(); i++) {
            long did = byDialog.keyAt(i);
            int profile = effective(account, did);
            if (profile != LOUD) {
                result.put(did, profile);
            }
        }
        return result;
    }

    public static int of(LongSparseArray<Integer> profiles, long dialogId) {
        Integer profile = profiles.get(dialogId);
        return profile == null ? LOUD : profile;
    }

    public static boolean isPassive(LongSparseArray<Integer> profiles, long dialogId) {
        Integer profile = profiles.get(dialogId);
        return profile != null && profile == PASSIVE;
    }

    // Passive chats never join the notification group, so they don't count toward whether a summary is needed.
    public static int passiveCount(LongSparseArray<Integer> profiles) {
        int count = 0;
        for (int i = 0; i < profiles.size(); i++) {
            Integer profile = profiles.valueAt(i);
            if (profile != null && profile == PASSIVE) count++;
        }
        return count;
    }

    // Called after the user picks a profile: rebuild so an already-posted notification moves to its new channel now
    // (the rebuild is not "notify about last", so nothing alerts), and drop popups already queued for a Passive chat.
    public static void onChanged(int account, long dialogId) {
        NotificationsController controller = NotificationsController.getInstance(account);
        if (effective(account, dialogId) == PASSIVE) {
            AndroidUtilities.runOnUIThread(() -> {
                boolean changed = false;
                for (int i = controller.popupMessages.size() - 1; i >= 0; i--) {
                    MessageObject popup = controller.popupMessages.get(i);
                    if (popup != null && popup.getDialogId() == dialogId) {
                        controller.popupMessages.remove(i);
                        changed = true;
                    }
                }
                if (changed) {
                    NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.pushMessagesUpdated);
                }
            });
        }
        controller.showNotifications();
    }

    // Passive chats never open the in-app popup.
    public static boolean blocksPopup(int account, long dialogId) {
        return dialogId != 0 && effective(account, dialogId) == PASSIVE;
    }

    // Reroutes one per-chat notification onto its profile's channel. alert is true only for the newest message of
    // a rebuild that upstream considered audible, so a rebuild caused by a read or a delete never vibrates.
    public static void apply(int account, NotificationCompat.Builder builder, Integer profile, boolean alert, boolean isInApp) {
        if (profile == null || Build.VERSION.SDK_INT < 26) return;
        try {
            if (profile == QUIET) {
                boolean vibrate = alert && (!isInApp || prefs(account).getBoolean("EnableInAppVibrate", true));
                builder.setChannelId(vibrate ? quietAlertChannel(account) : quietChannel(account));
                builder.setOnlyAlertOnce(!vibrate);
                if (vibrate) {
                    // the group summary is posted silent for a Quiet last chat, so the child has to alert itself
                    builder.setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_ALL);
                }
            } else if (profile == PASSIVE) {
                builder.setChannelId(passiveChannel(account));
                builder.setGroup("nax_np_passive_" + account);
                builder.setVisibility(NotificationCompat.VISIBILITY_SECRET);
                builder.setPriority(NotificationCompat.PRIORITY_MIN);
                builder.setOnlyAlertOnce(true);
                builder.setLocalOnly(true);
            }
        } catch (Exception e) {
            FileLog.e("nax notification profile apply failed", e);
        }
    }

    private static String channelId(int account, String tier) {
        return "nax_np_" + CHANNEL_VERSION + "_" + account + "_" + tier;
    }

    private static String channelName(int account, String label) {
        if (UserConfig.getActivatedAccountsCount() > 1) {
            String name = UserObject.getFirstName(UserConfig.getInstance(account).getCurrentUser());
            if (name != null && name.length() > 0) {
                return label + " · " + name;
            }
        }
        return label;
    }

    private static String quietChannel(int account) {
        String id = channelId(account, "quiet");
        ensureChannel(id, channelName(account, LocaleController.getString(R.string.NaxNotifProfileQuiet)), NotificationManager.IMPORTANCE_LOW, false, false);
        return id;
    }

    private static String quietAlertChannel(int account) {
        String id = channelId(account, "quiet_alert");
        ensureChannel(id, channelName(account, LocaleController.getString(R.string.NaxNotifProfileQuietAlertChannel)), NotificationManager.IMPORTANCE_DEFAULT, true, false);
        return id;
    }

    private static String passiveChannel(int account) {
        String id = channelId(account, "passive");
        ensureChannel(id, channelName(account, LocaleController.getString(R.string.NaxNotifProfilePassive)), NotificationManager.IMPORTANCE_MIN, false, true);
        return id;
    }

    private static void ensureChannel(String id, String name, int importance, boolean vibrate, boolean secret) {
        NotificationManager manager = (NotificationManager) ApplicationLoader.applicationContext.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null || manager.getNotificationChannel(id) != null) return;
        NotificationChannel channel = new NotificationChannel(id, name, importance);
        channel.enableLights(false);
        channel.enableVibration(vibrate);
        channel.setSound(null, null);
        if (secret) {
            channel.setLockscreenVisibility(Notification.VISIBILITY_SECRET);
        }
        try {
            manager.createNotificationChannel(channel);
        } catch (Exception e) {
            FileLog.e(e);
        }
    }
}
