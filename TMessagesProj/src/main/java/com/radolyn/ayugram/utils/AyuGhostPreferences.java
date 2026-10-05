package com.radolyn.ayugram.utils;

import java.util.concurrent.ConcurrentHashMap;

import tw.nekomimi.nekogram.NekoConfig;

public class AyuGhostPreferences {
    public static final String ghostReadExclusionPrefix = "ghostModeReadExclusion_";
    public static final String ghostTypingExclusionPrefix = "ghostModeTypingExclusion_";
    private static final ConcurrentHashMap<Long, Boolean> ghostModeReadExclusions = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Long, Boolean> ghostModeTypingExclusions = new ConcurrentHashMap<>();

    /**
     * True when "private chats only" is on and {@code dialogId} is a group or channel, so Ghost
     * Mode does not apply there. Takes a correctly encoded dialog id only (user ids positive,
     * chats and channels negative, as {@link org.telegram.messenger.DialogObject} defines them);
     * never the raw secret-chat id that {@code AyuGhostUtils.getDialogId(InputEncryptedChat)}
     * returns, which can be negative. Saved Messages (0) and secret chats stay in scope.
     */
    public static boolean isOutOfGhostScope(long dialogId) {
        return NekoConfig.ghostPrivateChatsOnly.Bool() && org.telegram.messenger.DialogObject.isChatDialog(dialogId);
    }

    public static void setGhostModeReadExclusion(long chatId, boolean value) {
        long key = Math.abs(chatId);
        ghostModeReadExclusions.put(key, value);
        NekoConfig.getPreferences().edit().putBoolean(ghostReadExclusionPrefix + key, value).apply();
    }

    public static boolean getGhostModeReadExclusion(long chatId) {
        long key = Math.abs(chatId);
        return ghostModeReadExclusions.computeIfAbsent(key, k ->
                NekoConfig.getPreferences().getBoolean(ghostReadExclusionPrefix + k, false)
        );
    }

    public static void setGhostModeTypingExclusion(long chatId, boolean value) {
        long key = Math.abs(chatId);
        ghostModeTypingExclusions.put(key, value);
        NekoConfig.getPreferences().edit().putBoolean(ghostTypingExclusionPrefix + key, value).apply();
    }

    public static boolean getGhostModeTypingExclusion(long chatId) {
        long key = Math.abs(chatId);
        return ghostModeTypingExclusions.computeIfAbsent(key, k ->
                NekoConfig.getPreferences().getBoolean(ghostTypingExclusionPrefix + k, false)
        );
    }

}
