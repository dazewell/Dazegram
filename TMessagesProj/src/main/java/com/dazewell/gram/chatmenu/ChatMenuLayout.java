package com.dazewell.gram.chatmenu;

import android.text.TextUtils;

import org.telegram.messenger.R;

import java.util.ArrayList;
import java.util.List;

import tw.nekomimi.nekogram.config.ConfigItem;
import xyz.nextalone.nagram.NaConfig;

/**
 * Where each configurable chat button lives: as a header icon, as a row in the chat's ⋮ menu, or
 * nowhere. Stored as one "header|menu|hidden" string of comma-joined keys, read through a cache.
 *
 * An empty value means the layout was never edited, and is derived from the per-item switches the
 * old Chat menu dialog wrote, so an upgrade shows the same buttons. Every edit and Reset writes the
 * full string, so from then on those switches are never read again.
 */
public final class ChatMenuLayout {

    public static final int HEADER = 0;
    public static final int MENU = 1;
    public static final int HIDDEN = 2;
    public static final int SECTIONS = 3;
    public static final int HEADER_CAPACITY = 2;

    public static final String ALERTS = "alerts";
    public static final String ADMINS = "admins";
    public static final String RECENT = "recent";
    public static final String STATS = "stats";
    public static final String PERMISSIONS = "permissions";
    public static final String MEMBERS = "members";
    public static final String CALL = "call";
    public static final String VIDEO = "video";
    public static final String BOOST = "boost";
    public static final String LINKED = "linked";
    public static final String BEGINNING = "beginning";
    public static final String GOTOMSG = "gotomsg";
    public static final String HIDETITLE = "hidetitle";
    public static final String VIEWDELETED = "viewdeleted";
    public static final String CLEARDELETED = "cleardeleted";
    public static final String DELETEOWN = "deleteown";

    /** Every key, in the order ChatActivity registers their ⋮ rows; ChatMenuController takes ids in this order. */
    public static final String[] KEYS = {
            ALERTS, ADMINS, RECENT, STATS, PERMISSIONS, MEMBERS, CALL, VIDEO, BOOST, LINKED,
            BEGINNING, GOTOMSG, HIDETITLE, VIEWDELETED, CLEARDELETED, DELETEOWN
    };

    private static final String[] DEFAULT_HEADER = {ALERTS, CALL};

    private static String parsedFrom;
    private static List<List<String>> sections;

    private ChatMenuLayout() {
    }

    public static int titleRes(String key) {
        switch (key) {
            case ALERTS: return R.string.NaxChatMenuAlerts;
            case ADMINS: return R.string.ChannelAdministrators;
            case RECENT: return R.string.EventLog;
            case STATS: return R.string.Statistics;
            case PERMISSIONS: return R.string.ChannelPermissions;
            case MEMBERS: return R.string.GroupMembers;
            case CALL: return R.string.Call;
            case VIDEO: return R.string.VideoCall;
            case BOOST: return R.string.BoostingBoostGroupMenu;
            case LINKED: return R.string.LinkedGroupChat;
            case BEGINNING: return R.string.ToTheBeginning;
            case GOTOMSG: return R.string.ToTheMessage;
            case HIDETITLE: return R.string.HideTitle;
            case VIEWDELETED: return R.string.ViewDeleted;
            case CLEARDELETED: return R.string.ClearDeleted;
            default: return R.string.DeleteAllFromSelf;
        }
    }

    /** The ⋮ row's icon, also used for the editor rows. */
    public static int menuIcon(String key) {
        switch (key) {
            case ALERTS: return R.drawable.msg_unmute;
            case ADMINS: return R.drawable.msg_admins;
            case RECENT: return R.drawable.msg_log;
            case STATS: return R.drawable.msg_stats;
            case PERMISSIONS: return R.drawable.msg_permissions;
            case MEMBERS: return R.drawable.msg_groups;
            case CALL: return R.drawable.msg_callback;
            case VIDEO: return R.drawable.msg_videocall;
            case BOOST: return R.drawable.boost_channel_solar;
            case LINKED: return R.drawable.msg_discussion;
            case BEGINNING: return R.drawable.ic_upward;
            case GOTOMSG: return R.drawable.msg_go_up;
            case HIDETITLE: return R.drawable.hide_title;
            case VIEWDELETED: return R.drawable.msg_view_file;
            case CLEARDELETED: return R.drawable.msg_clear;
            default: return R.drawable.msg_delete;
        }
    }

    /** Action-bar sized icon; the ⋮ boost row animates a Lottie that a header icon would never start. */
    static int headerIcon(String key) {
        if (VIDEO.equals(key)) return R.drawable.profile_video;
        return menuIcon(key);
    }

    /** A fresh copy the editor can shuffle. */
    public static synchronized List<List<String>> snapshot() {
        parse();
        return copy(sections);
    }

    public static synchronized void save(List<List<String>> next) {
        NaConfig.INSTANCE.getChatMenuLayout().setConfigString(serialize(normalize(next)));
        parsedFrom = null;
    }

    public static List<List<String>> defaults() {
        List<List<String>> out = empty();
        for (String key : DEFAULT_HEADER) out.get(HEADER).add(key);
        for (String key : KEYS) {
            if (!out.get(HEADER).contains(key)) out.get(MENU).add(key);
        }
        return out;
    }

    private static void parse() {
        String raw = NaConfig.INSTANCE.getChatMenuLayout().String();
        if (sections != null && TextUtils.equals(raw, parsedFrom)) return;
        parsedFrom = raw;
        List<List<String>> parsed = null;
        if (TextUtils.isEmpty(raw)) {
            parsed = legacy();
        } else {
            String[] parts = raw.split("\\|", -1);
            if (parts.length == SECTIONS) {
                parsed = empty();
                for (int i = 0; i < SECTIONS; i++) {
                    for (String key : parts[i].split(",")) {
                        parsed.get(i).add(key.trim());
                    }
                }
            }
        }
        sections = normalize(parsed == null ? defaults() : parsed);
    }

    /**
     * Drops unknown and repeated keys, trims the header to its capacity (the overflow goes to the
     * front of the menu), and puts any key the value never mentions back in its default section.
     */
    static List<List<String>> normalize(List<List<String>> in) {
        List<List<String>> out = empty();
        ArrayList<String> seen = new ArrayList<>();
        for (int i = 0; i < SECTIONS && i < in.size(); i++) {
            for (String key : in.get(i)) {
                if (indexOf(key) < 0 || seen.contains(key)) continue;
                seen.add(key);
                out.get(i).add(key);
            }
        }
        List<String> header = out.get(HEADER);
        while (header.size() > HEADER_CAPACITY) {
            out.get(MENU).add(0, header.remove(header.size() - 1));
        }
        List<List<String>> defaults = defaults();
        for (String key : KEYS) {
            if (seen.contains(key)) continue;
            boolean toHeader = defaults.get(HEADER).contains(key) && header.size() < HEADER_CAPACITY;
            out.get(toHeader ? HEADER : MENU).add(key);
        }
        return out;
    }

    /** The layout the old per-item switches described: a switched-off item is hidden. */
    private static List<List<String>> legacy() {
        List<List<String>> out = empty();
        List<String> defaultHeader = defaults().get(HEADER);
        for (String key : KEYS) {
            int section = defaultHeader.contains(key) ? HEADER : MENU;
            if (ALERTS.equals(key) && !NaConfig.INSTANCE.getChatMenuItemNotifProfileBell().Bool()) {
                // The bell switch only ever hid the bell; the ⋮ Alerts row stayed.
                section = MENU;
            } else {
                ConfigItem toggle = legacyToggle(key);
                if (toggle != null && !toggle.Bool()) section = HIDDEN;
            }
            out.get(section).add(key);
        }
        return out;
    }

    private static ConfigItem legacyToggle(String key) {
        switch (key) {
            case ADMINS: return NaConfig.INSTANCE.getShortcutsAdministrators();
            case RECENT: return NaConfig.INSTANCE.getShortcutsRecentActions();
            case STATS: return NaConfig.INSTANCE.getShortcutsStatistics();
            case PERMISSIONS: return NaConfig.INSTANCE.getShortcutsPermissions();
            case MEMBERS: return NaConfig.INSTANCE.getShortcutsMembers();
            case BOOST: return NaConfig.INSTANCE.getChatMenuItemBoostGroup();
            case LINKED: return NaConfig.INSTANCE.getChatMenuItemLinkedChat();
            case BEGINNING: return NaConfig.INSTANCE.getChatMenuItemToBeginning();
            case GOTOMSG: return NaConfig.INSTANCE.getChatMenuItemGoToMessage();
            case HIDETITLE: return NaConfig.INSTANCE.getChatMenuItemHideTitle();
            case VIEWDELETED: return NaConfig.INSTANCE.getChatMenuItemViewDeleted();
            case CLEARDELETED: return NaConfig.INSTANCE.getChatMenuItemClearDeleted();
            case DELETEOWN: return NaConfig.INSTANCE.getChatMenuItemDeleteOwnMessages();
            default: return null;
        }
    }

    static String serialize(List<List<String>> layout) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < SECTIONS; i++) {
            if (i > 0) builder.append('|');
            builder.append(TextUtils.join(",", layout.get(i)));
        }
        return builder.toString();
    }

    static int indexOf(String key) {
        for (int i = 0; i < KEYS.length; i++) {
            if (KEYS[i].equals(key)) return i;
        }
        return -1;
    }

    private static List<List<String>> empty() {
        List<List<String>> out = new ArrayList<>(SECTIONS);
        for (int i = 0; i < SECTIONS; i++) out.add(new ArrayList<>());
        return out;
    }

    private static List<List<String>> copy(List<List<String>> in) {
        List<List<String>> out = new ArrayList<>(SECTIONS);
        for (List<String> section : in) out.add(new ArrayList<>(section));
        return out;
    }
}
