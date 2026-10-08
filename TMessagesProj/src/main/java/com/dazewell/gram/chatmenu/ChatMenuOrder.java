package com.dazewell.gram.chatmenu;

import android.text.TextUtils;

import org.telegram.ui.ActionBar.ActionBarMenuItem;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;

import xyz.nextalone.nagram.NaConfig;

// NagramX: the user's order for the configurable chat header buttons and "..." menu items. The menu is built
// in upstream order and then permuted here, so every item keeps its own visibility conditions and the
// upstream items between them stay where they are.
public final class ChatMenuOrder {

    public static final String BELL = "bell";
    public static final String CALL = "call";
    static final String[] HEADER_KEYS = {BELL, CALL};

    // Default order is the order ChatActivity registers them in; apply() takes their ids in this same order.
    static final String[] MENU_KEYS = {
            "admins", "recent", "stats", "permissions", "members", "boost", "linked",
            "beginning", "gotomsg", "hidetitle", "viewdeleted", "cleardeleted", "deleteown"
    };

    private ChatMenuOrder() {
    }

    // Unknown or duplicate saved keys are dropped and missing ones appended, so a stale or garbled value
    // falls back to the default rather than losing an item.
    static List<String> resolve(String saved, String[] defaults) {
        ArrayList<String> out = new ArrayList<>(defaults.length);
        if (!TextUtils.isEmpty(saved)) {
            for (String key : saved.split(",")) {
                key = key.trim();
                if (!out.contains(key) && indexOf(defaults, key) >= 0) out.add(key);
            }
        }
        for (String key : defaults) {
            if (!out.contains(key)) out.add(key);
        }
        return out;
    }

    static List<String> headerOrder() {
        return resolve(NaConfig.INSTANCE.getChatHeaderOrder().String(), HEADER_KEYS);
    }

    static List<String> menuOrder() {
        return resolve(NaConfig.INSTANCE.getChatMenuOrder().String(), MENU_KEYS);
    }

    public static boolean bellFirst() {
        List<String> order = headerOrder();
        return order.indexOf(BELL) < order.indexOf(CALL);
    }

    // Sorts the configurable items among the slots they already occupy in the not-yet-laid-out menu.
    public static void apply(ActionBarMenuItem headerItem, int... idsInDefaultOrder) {
        ArrayList<ActionBarMenuItem.Item> list = headerItem == null ? null : headerItem.getLazyItems();
        if (list == null || idsInDefaultOrder.length != MENU_KEYS.length) return;
        List<String> order = menuOrder();
        HashMap<Integer, Integer> rank = new HashMap<>();
        for (int i = 0; i < MENU_KEYS.length; i++) {
            rank.put(idsInDefaultOrder[i], order.indexOf(MENU_KEYS[i]));
        }
        ArrayList<Integer> slots = new ArrayList<>();
        ArrayList<ActionBarMenuItem.Item> items = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            ActionBarMenuItem.Item item = list.get(i);
            if (item.viewType == ActionBarMenuItem.VIEW_TYPE_SUBITEM && rank.containsKey(item.id)) {
                slots.add(i);
                items.add(item);
            }
        }
        items.sort(Comparator.comparingInt(item -> rank.get(item.id)));
        for (int i = 0; i < slots.size(); i++) {
            list.set(slots.get(i), items.get(i));
        }
    }

    static String join(List<String> keys) {
        return TextUtils.join(",", keys);
    }

    private static int indexOf(String[] keys, String key) {
        for (int i = 0; i < keys.length; i++) {
            if (keys[i].equals(key)) return i;
        }
        return -1;
    }
}
