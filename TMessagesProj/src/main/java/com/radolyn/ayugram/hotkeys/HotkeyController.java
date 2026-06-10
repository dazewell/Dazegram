package com.radolyn.ayugram.hotkeys;

import android.os.Bundle;
import android.view.InputDevice;
import android.view.KeyEvent;

import org.telegram.messenger.DialogObject;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationsController;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ArticleViewer;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.Components.ChatActivityEnterView;
import org.telegram.ui.Components.EditTextCaption;
import org.telegram.ui.ContactsActivity;
import org.telegram.ui.DialogsActivity;
import org.telegram.ui.LaunchActivity;
import org.telegram.ui.PhotoViewer;

import java.util.ArrayList;
import java.util.List;

import xyz.nextalone.nagram.NaConfig;

/**
 * Telegram Desktop style hotkeys for physical keyboards.
 *
 * Global (LaunchActivity.dispatchKeyEvent):
 *   Esc                       - cancel reply/edit, close search, go back
 *   Ctrl+F                    - search in current chat / chat list
 *   Ctrl+W                    - close current chat
 *   Ctrl+PgDn / Alt+Down      - next chat
 *   Ctrl+PgUp / Alt+Up        - previous chat
 *   Ctrl+Alt+Home / End       - first / last chat
 *   Ctrl+Shift+Down / Up      - next / previous folder
 *   Ctrl+0                    - Saved Messages
 *   Ctrl+1..8                 - pinned chat 1..8
 *   Ctrl+9                    - archive
 *   Ctrl+J                    - contacts
 *   Ctrl+L                    - lock app (when passcode is set)
 *   Ctrl+M                    - minimize app
 *   Ctrl+R                    - mark current chat as read
 *   Alt+Enter                 - schedule message (NagramX custom)
 *   Up (empty input)          - edit last sent message
 *
 * Text formatting (EditTextCaption.onKeyShortcut, selection required):
 *   Ctrl+B / I / U / K        - bold / italic / underline / link
 *   Ctrl+Shift+X / M / P / N  - strikethrough / monospace / spoiler / plain
 */
public class HotkeyController {

    private HotkeyController() {
    }

    public static boolean enabled() {
        return NaConfig.INSTANCE.getPhysicalKeyboardHotkeys().Bool();
    }

    public static boolean isPhysicalKeyboard(KeyEvent event) {
        if ((event.getFlags() & KeyEvent.FLAG_SOFT_KEYBOARD) != 0) {
            return false;
        }
        InputDevice device = event.getDevice();
        return device != null && !device.isVirtual() && device.getKeyboardType() == InputDevice.KEYBOARD_TYPE_ALPHABETIC;
    }

    public static boolean handleGlobalKey(LaunchActivity activity, KeyEvent event) {
        if (!enabled() || event.getAction() != KeyEvent.ACTION_DOWN || event.getRepeatCount() != 0 || !isPhysicalKeyboard(event)) {
            return false;
        }
        if (SharedConfig.isWaitingForPasscodeEnter || SharedConfig.appLocked) {
            return false;
        }
        if (PhotoViewer.hasInstance() && PhotoViewer.getInstance().isVisible()) {
            return false;
        }
        if (ArticleViewer.hasInstance() && ArticleViewer.getInstance().isVisible()) {
            return false;
        }
        BaseFragment fragment = LaunchActivity.getLastFragment();
        if (fragment == null || fragment.getParentActivity() == null) {
            return false;
        }
        ChatActivity chat = fragment instanceof ChatActivity ? (ChatActivity) fragment : null;
        DialogsActivity dialogs = fragment instanceof DialogsActivity ? (DialogsActivity) fragment : null;

        int keyCode = event.getKeyCode();
        if (event.hasNoModifiers()) {
            if (keyCode == KeyEvent.KEYCODE_ESCAPE) {
                return handleEscape(activity, chat);
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP && chat != null) {
                return editLastMessage(chat);
            }
            return false;
        }
        if (event.hasModifiers(KeyEvent.META_CTRL_ON)) {
            switch (keyCode) {
                case KeyEvent.KEYCODE_F:
                    if (chat != null) {
                        return chat.hotkeyOpenSearch();
                    }
                    return dialogs != null && dialogs.hotkeyOpenSearch();
                case KeyEvent.KEYCODE_W:
                    if (chat != null) {
                        chat.finishFragment();
                        return true;
                    }
                    return false;
                case KeyEvent.KEYCODE_J:
                    fragment.presentFragment(new ContactsActivity(null));
                    return true;
                case KeyEvent.KEYCODE_L:
                    return lockApp(activity, fragment);
                case KeyEvent.KEYCODE_M:
                    activity.moveTaskToBack(true);
                    return true;
                case KeyEvent.KEYCODE_R:
                    if (chat != null && chat.getChatMode() == 0) {
                        chat.getMessagesController().markDialogAsReadNow(chat.getDialogId(), chat.getThreadId());
                        return true;
                    }
                    return false;
                case KeyEvent.KEYCODE_PAGE_DOWN:
                    return chat != null && openAdjacentChat(chat, 1, false);
                case KeyEvent.KEYCODE_PAGE_UP:
                    return chat != null && openAdjacentChat(chat, -1, false);
            }
            int digit = digitForKey(keyCode);
            if (digit == 0) {
                return openDialog(fragment, UserConfig.getInstance(fragment.getCurrentAccount()).getClientUserId());
            } else if (digit == 9) {
                return openArchive(fragment);
            } else if (digit > 0) {
                return openPinnedChat(fragment, digit);
            }
            return false;
        }
        if (event.hasModifiers(KeyEvent.META_ALT_ON)) {
            if (keyCode == KeyEvent.KEYCODE_ENTER && chat != null) {
                ChatActivityEnterView enterView = chat.getChatActivityEnterView();
                return enterView != null && enterView.scheduleMessageFromHotkey();
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                return chat != null && openAdjacentChat(chat, 1, false);
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                return chat != null && openAdjacentChat(chat, -1, false);
            }
            return false;
        }
        if (event.hasModifiers(KeyEvent.META_CTRL_ON | KeyEvent.META_SHIFT_ON)) {
            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                return dialogs != null && dialogs.hotkeySwitchFolder(true);
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                return dialogs != null && dialogs.hotkeySwitchFolder(false);
            }
            return false;
        }
        if (event.hasModifiers(KeyEvent.META_CTRL_ON | KeyEvent.META_ALT_ON)) {
            if (keyCode == KeyEvent.KEYCODE_MOVE_HOME) {
                return chat != null && openAdjacentChat(chat, -1, true);
            }
            if (keyCode == KeyEvent.KEYCODE_MOVE_END) {
                return chat != null && openAdjacentChat(chat, 1, true);
            }
            return false;
        }
        return false;
    }

    public static boolean handleTextStyleShortcut(EditTextCaption editText, int keyCode, KeyEvent event) {
        if (!enabled() || !isPhysicalKeyboard(event) || editText.getSelectionStart() == editText.getSelectionEnd()) {
            return false;
        }
        int itemId = 0;
        if (event.hasModifiers(KeyEvent.META_CTRL_ON)) {
            if (keyCode == KeyEvent.KEYCODE_B) {
                itemId = R.id.menu_bold;
            } else if (keyCode == KeyEvent.KEYCODE_I) {
                itemId = R.id.menu_italic;
            } else if (keyCode == KeyEvent.KEYCODE_U) {
                itemId = R.id.menu_underline;
            } else if (keyCode == KeyEvent.KEYCODE_K) {
                itemId = R.id.menu_link;
            }
        } else if (event.hasModifiers(KeyEvent.META_CTRL_ON | KeyEvent.META_SHIFT_ON)) {
            if (keyCode == KeyEvent.KEYCODE_X) {
                itemId = R.id.menu_strike;
            } else if (keyCode == KeyEvent.KEYCODE_M) {
                itemId = R.id.menu_mono;
            } else if (keyCode == KeyEvent.KEYCODE_P) {
                itemId = R.id.menu_spoiler;
            } else if (keyCode == KeyEvent.KEYCODE_N) {
                itemId = R.id.menu_regular;
            }
        }
        return itemId != 0 && editText.performMenuAction(itemId);
    }

    public static MessageObject findLastEditableOutgoingMessage(List<MessageObject> messages, TLRPC.Chat currentChat, long mergeDialogId) {
        if (messages == null) {
            return null;
        }
        // messages are ordered newest first
        for (int i = 0; i < messages.size(); i++) {
            MessageObject message = messages.get(i);
            if (message == null || !message.isOutOwner() || message.getId() <= 0 || message.isEditing()) {
                continue;
            }
            if (mergeDialogId != 0 && message.getDialogId() == mergeDialogId) {
                continue;
            }
            if (message.type == MessageObject.TYPE_STORY || message.type == MessageObject.TYPE_POLL) {
                continue;
            }
            if (message.canEditMessage(currentChat)) {
                return message;
            }
        }
        return null;
    }

    private static boolean handleEscape(LaunchActivity activity, ChatActivity chat) {
        if (chat != null && chat.getActionBar() != null && !chat.getActionBar().isSearchFieldVisible() && chat.hotkeyCancelFieldPanel()) {
            return true;
        }
        activity.onBackPressed();
        return true;
    }

    private static boolean editLastMessage(ChatActivity chat) {
        ChatActivityEnterView enterView = chat.getChatActivityEnterView();
        if (enterView == null || enterView.isPopupShowing()) {
            return false;
        }
        EditTextCaption editField = enterView.getEditField();
        if (editField == null || !editField.isFocused() || editField.length() > 0) {
            return false;
        }
        return chat.hotkeyEditLastOutgoingMessage();
    }

    private static boolean lockApp(LaunchActivity activity, BaseFragment fragment) {
        if (SharedConfig.passcodeHash.length() == 0) {
            return false;
        }
        SharedConfig.appLocked = true;
        SharedConfig.saveConfig();
        activity.showPasscodeActivity(false, true, -1, -1, null, null);
        NotificationsController.getInstance(fragment.getCurrentAccount()).showNotifications();
        return true;
    }

    private static boolean openAdjacentChat(ChatActivity chat, int direction, boolean jumpToEdge) {
        if (chat.getChatMode() != 0 || chat.isThreadChat()) {
            return false;
        }
        MessagesController controller = chat.getMessagesController();
        long currentDialogId = chat.getDialogId();
        TLRPC.Dialog currentDialog = controller.dialogs_dict.get(currentDialogId);
        int folderId = currentDialog != null ? currentDialog.folder_id : 0;
        ArrayList<Long> dialogIds = new ArrayList<>();
        for (TLRPC.Dialog dialog : controller.getDialogs(folderId)) {
            if (dialog instanceof TLRPC.TL_dialogFolder) {
                continue;
            }
            dialogIds.add(dialog.id);
        }
        if (dialogIds.isEmpty()) {
            return false;
        }
        int index = dialogIds.indexOf(currentDialogId);
        int target;
        if (jumpToEdge) {
            target = direction > 0 ? dialogIds.size() - 1 : 0;
        } else {
            if (index < 0) {
                return false;
            }
            target = index + direction;
        }
        if (target < 0 || target >= dialogIds.size() || target == index) {
            return false;
        }
        return openDialog(chat, dialogIds.get(target));
    }

    private static boolean openPinnedChat(BaseFragment fragment, int number) {
        if (!(fragment instanceof ChatActivity) && !(fragment instanceof DialogsActivity)) {
            return false;
        }
        MessagesController controller = fragment.getMessagesController();
        int found = 0;
        for (TLRPC.Dialog dialog : controller.getDialogs(0)) {
            if (dialog instanceof TLRPC.TL_dialogFolder || !dialog.pinned) {
                continue;
            }
            if (++found == number) {
                return openDialog(fragment, dialog.id);
            }
        }
        return false;
    }

    private static boolean openArchive(BaseFragment fragment) {
        if (!(fragment instanceof ChatActivity) && !(fragment instanceof DialogsActivity)) {
            return false;
        }
        if (fragment.getMessagesController().getDialogs(1).isEmpty()) {
            return false;
        }
        Bundle args = new Bundle();
        args.putInt("folderId", 1);
        fragment.presentFragment(new DialogsActivity(args));
        return true;
    }

    private static boolean openDialog(BaseFragment fragment, long dialogId) {
        if (dialogId == 0) {
            return false;
        }
        if (fragment instanceof ChatActivity && ((ChatActivity) fragment).getDialogId() == dialogId) {
            return true;
        }
        Bundle args = new Bundle();
        if (DialogObject.isEncryptedDialog(dialogId)) {
            args.putInt("enc_id", DialogObject.getEncryptedChatId(dialogId));
        } else if (DialogObject.isUserDialog(dialogId)) {
            args.putLong("user_id", dialogId);
        } else {
            args.putLong("chat_id", -dialogId);
        }
        if (!fragment.getMessagesController().checkCanOpenChat(args, fragment)) {
            return false;
        }
        return fragment.presentFragment(new ChatActivity(args), fragment instanceof ChatActivity);
    }

    private static int digitForKey(int keyCode) {
        if (keyCode >= KeyEvent.KEYCODE_0 && keyCode <= KeyEvent.KEYCODE_9) {
            return keyCode - KeyEvent.KEYCODE_0;
        }
        if (keyCode >= KeyEvent.KEYCODE_NUMPAD_0 && keyCode <= KeyEvent.KEYCODE_NUMPAD_9) {
            return keyCode - KeyEvent.KEYCODE_NUMPAD_0;
        }
        return -1;
    }
}
