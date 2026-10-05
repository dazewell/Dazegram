package com.radolyn.ayugram.messages;

import org.telegram.messenger.ChatObject;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.MessagesController;
import org.telegram.tgnet.TLRPC;

import tw.nekomimi.nekogram.config.ConfigItem;
import xyz.nextalone.nagram.NaConfig;

/**
 * Per-chat-type scope shared by Save Media, Save Deleted Messages and Save Edits History.
 * The sheet only narrows where a feature applies; each feature's own master switch is checked elsewhere.
 */
public class SaveScope {
    public static final int PRIVATE_CHATS = 0;
    public static final int PUBLIC_CHANNELS = 1;
    public static final int PRIVATE_CHANNELS = 2;
    public static final int PUBLIC_GROUPS = 3;
    public static final int PRIVATE_GROUPS = 4;

    // indexed by the bucket constants above
    public static final ConfigItem[] MEDIA = {
            NaConfig.INSTANCE.getSaveMediaInPrivateChats(),
            NaConfig.INSTANCE.getSaveMediaInPublicChannels(),
            NaConfig.INSTANCE.getSaveMediaInPrivateChannels(),
            NaConfig.INSTANCE.getSaveMediaInPublicGroups(),
            NaConfig.INSTANCE.getSaveMediaInPrivateGroups(),
    };
    public static final ConfigItem[] DELETED = {
            NaConfig.INSTANCE.getSaveDeletedMessagesInPrivateChats(),
            NaConfig.INSTANCE.getSaveDeletedMessagesInPublicChannels(),
            NaConfig.INSTANCE.getSaveDeletedMessagesInPrivateChannels(),
            NaConfig.INSTANCE.getSaveDeletedMessagesInPublicGroups(),
            NaConfig.INSTANCE.getSaveDeletedMessagesInPrivateGroups(),
    };
    public static final ConfigItem[] EDITS = {
            NaConfig.INSTANCE.getSaveEditsHistoryInPrivateChats(),
            NaConfig.INSTANCE.getSaveEditsHistoryInPublicChannels(),
            NaConfig.INSTANCE.getSaveEditsHistoryInPrivateChannels(),
            NaConfig.INSTANCE.getSaveEditsHistoryInPublicGroups(),
            NaConfig.INSTANCE.getSaveEditsHistoryInPrivateGroups(),
    };

    /**
     * @return a bucket constant, or -1 when the chat can't be classified (not cached, folder id, 0).
     */
    public static int bucket(int accountId, long dialogId) {
        // secret chat ids are positive, so they must be told apart before the sign-based checks
        if (DialogObject.isEncryptedDialog(dialogId) || DialogObject.isUserDialog(dialogId)) {
            return PRIVATE_CHATS;
        }
        TLRPC.Chat chat = MessagesController.getInstance(accountId).getChat(Math.abs(dialogId));
        if (chat == null) {
            return -1;
        }
        boolean isPublic = ChatObject.isPublic(chat);
        if (ChatObject.isChannelAndNotMegaGroup(chat)) {
            return isPublic ? PUBLIC_CHANNELS : PRIVATE_CHANNELS;
        }
        return isPublic ? PUBLIC_GROUPS : PRIVATE_GROUPS;
    }

    /**
     * An unclassifiable chat is allowed, so saving fails open rather than silently dropping a message.
     */
    public static boolean allows(int accountId, long dialogId, ConfigItem[] items) {
        int bucket = bucket(accountId, dialogId);
        return bucket < 0 || items[bucket].Bool();
    }

    public static boolean allowsDeleted(int accountId, long dialogId) {
        return allows(accountId, dialogId, DELETED);
    }

    public static boolean allowsEdits(int accountId, long dialogId) {
        return allows(accountId, dialogId, EDITS);
    }
}
