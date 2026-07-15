package com.radolyn.ayugram.eventschedule;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationsController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.LaunchActivity;

/**
 * Posts a local heads-up to the sender when a trigger sends a scheduled message early, so the
 * auto-send isn't invisible when they're not looking at the chat. Uses the app's existing
 * "Other" notification channel and opens the chat on tap, the same way message notifications do.
 */
final class EventScheduleNotifier {

    // Per-account, per-dialog base so repeated early sends in the same chat collapse into one
    // notification instead of stacking, the way message notifications do.
    private static final int ID_BASE = 0x6E780000;

    private EventScheduleNotifier() {}

    static void notifySent(int account, long dialogId) {
        try {
            Context context = ApplicationLoader.applicationContext;
            if (context == null) return;

            String name = resolveName(account, dialogId);
            int id = ID_BASE + (account << 24) + (int) (dialogId ^ (dialogId >>> 32));

            Intent intent = new Intent(context, LaunchActivity.class);
            intent.setAction("com.tmessages.openchat" + Math.random() + Integer.MAX_VALUE);
            intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
            intent.putExtra("currentAccount", account);
            if (dialogId > 0) {
                intent.putExtra("userId", dialogId);
            } else {
                intent.putExtra("chatId", -dialogId);
            }
            PendingIntent contentIntent = PendingIntent.getActivity(context, id, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

            NotificationsController.checkOtherNotificationsChannel();
            NotificationCompat.Builder builder = new NotificationCompat.Builder(context, NotificationsController.OTHER_NOTIFICATIONS_CHANNEL)
                    .setSmallIcon(R.drawable.notification)
                    .setContentTitle(name)
                    .setContentText(LocaleController.getString(R.string.EventScheduleSentNotification))
                    .setAutoCancel(true)
                    .setContentIntent(contentIntent);

            NotificationManagerCompat.from(context).notify(id, builder.build());
        } catch (Throwable ignore) {
        }
    }

    private static String resolveName(int account, long dialogId) {
        MessagesController controller = MessagesController.getInstance(account);
        if (dialogId > 0) {
            TLRPC.User user = controller.getUser(dialogId);
            if (user != null) return UserObject.getUserName(user);
        } else if (!DialogObject.isEncryptedDialog(dialogId)) {
            TLRPC.Chat chat = controller.getChat(-dialogId);
            if (chat != null) return chat.title;
        }
        return LocaleController.getString(R.string.AppName);
    }
}
