package com.radolyn.ayugram.eventschedule;

import androidx.annotation.NonNull;

import com.radolyn.ayugram.utils.AyuState;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/**
 * Runtime engine for event-triggered scheduled messages.
 *
 * <p>Everything hangs off the single new-message funnel ({@code onNewMessages}, called
 * from {@code MessagesController.updateInterfaceWithMessages}). A scheduled batch claims
 * a freshly armed trigger; an incoming batch is matched against armed triggers and, on a
 * hit, fires {@code messages.sendScheduledMessages} early. Local echo ids are remapped to
 * server ids via a {@link NotificationCenter} observer, since the send pipeline has several
 * ack sites and the observer is provably registered before any of them fire.
 *
 * <p>Triggers only work while the process is alive; the message keeps its real fallback
 * date so the server sends it on time if nothing matched.
 */
public final class EventScheduleController {

    private static final class Pending {
        final EventScheduleEntry entry;
        final int scheduleDate;
        final long armedAtMs;
        boolean claimed;
        long groupedId;

        Pending(EventScheduleEntry entry, int scheduleDate) {
            this.entry = entry;
            this.scheduleDate = scheduleDate;
            this.armedAtMs = System.currentTimeMillis();
        }
    }

    // The local echo posts synchronously inside the send call, so the claim window is short.
    private static final long CLAIM_WINDOW_MS = 5000;

    // Written only on the UI thread (armPending/claim/onIdRemap/killPending all run there).
    private static final Map<String, Pending> PENDING = new HashMap<>();
    private static volatile long pendingAccounts;
    private static volatile long warmedAccounts;
    private static final Object OBSERVED_LOCK = new Object();
    private static final ArrayList<Integer> OBSERVED = new ArrayList<>();

    private EventScheduleController() {}

    private static String pendingKey(int account, long dialogId) {
        return account + "_" + dialogId;
    }

    private static boolean hasState(int account) {
        return EventScheduleStore.hasAny(account) || (pendingAccounts & (1L << account)) != 0;
    }

    // The observer serves every account (the callback carries the account), so one instance
    // registered per account covers id remap and scheduled deletes for its whole lifetime.
    private static final NotificationCenter.NotificationCenterDelegate OBSERVER = (id, account, args) -> {
        if (id == NotificationCenter.messageReceivedByServer) {
            int oldId = (Integer) args[0];
            int newId = (Integer) args[1];
            onIdRemap(account, oldId, newId);
        } else if (id == NotificationCenter.messagesDeleted) {
            boolean scheduled = args.length > 2 && args[2] instanceof Boolean && (Boolean) args[2];
            if (!scheduled) return;
            @SuppressWarnings("unchecked")
            ArrayList<Integer> ids = (ArrayList<Integer>) args[0];
            long channelId = args[1] instanceof Long ? (Long) args[1] : 0L;
            EventScheduleStore.purgeIds(account, channelId, ids);
        }
    };

    /**
     * Warms the on-disk store for an account once per process (lock-free after the first call), so
     * triggers armed in a previous session re-arm after a restart. Called from the message funnel and
     * from the scheduled-view time decorator, whichever runs first.
     */
    public static void ensureWarm(int account) {
        if ((warmedAccounts & (1L << account)) != 0) return;
        warmedAccounts |= (1L << account);
        EventScheduleStore.ensureLoaded(account);
        if (EventScheduleStore.hasAny(account)) ensureObserver(account);
    }

    static void ensureObserver(int account) {
        synchronized (OBSERVED_LOCK) {
            if (OBSERVED.contains(account)) return;
            OBSERVED.add(account);
        }
        AndroidUtilities.runOnUIThread(() -> {
            NotificationCenter nc = NotificationCenter.getInstance(account);
            nc.addObserver(OBSERVER, NotificationCenter.messageReceivedByServer);
            nc.addObserver(OBSERVER, NotificationCenter.messagesDeleted);
        });
    }

    /** Called from the schedule sheet's confirm, before the message is actually sent. */
    public static void armPending(int account, long dialogId, @NonNull EventScheduleEntry entry, int scheduleDate) {
        entry.dialogId = dialogId;
        entry.fallbackDate = scheduleDate;
        entry.state = EventScheduleEntry.STATE_ARMED;
        PENDING.put(pendingKey(account, dialogId), new Pending(entry, scheduleDate));
        pendingAccounts |= (1L << account);
        ensureObserver(account);
    }

    /** Drops a pending bind that never got a message (trigger turned off, or a stale edit). */
    public static void killPending(int account, long dialogId) {
        PENDING.remove(pendingKey(account, dialogId));
        if (!hasPending()) pendingAccounts = 0;
    }

    /** Arms a trigger on a message whose server ids are already known (editing a message with no trigger yet). */
    public static void armExisting(int account, @NonNull EventScheduleEntry entry) {
        entry.state = EventScheduleEntry.STATE_ARMED;
        EventScheduleStore.persist(account, entry);
        ensureObserver(account);
    }

    /** Replaces an already-armed (server-side) entry in place after an edit. */
    public static void updateForEdit(int account, @NonNull EventScheduleEntry entry, int types, String pattern, boolean regex, int delaySeconds, int fallbackDate) {
        entry.types = types;
        entry.pattern = pattern == null ? "" : pattern;
        entry.regex = regex;
        entry.delaySeconds = delaySeconds;
        entry.fallbackDate = fallbackDate;
        entry.state = EventScheduleEntry.STATE_ARMED;
        entry.resetCompiled();
        EventScheduleStore.persist(account, entry);
        ensureObserver(account);
    }

    private static boolean hasPending() {
        return !PENDING.isEmpty();
    }

    public static void onNewMessages(int account, long dialogId, ArrayList<MessageObject> messages, boolean scheduled) {
        ensureWarm(account);
        if (!hasState(account) || messages == null || messages.isEmpty()) return;
        if (scheduled) {
            claim(account, dialogId, messages);
        } else {
            evaluate(account, dialogId, messages);
        }
    }

    private static void claim(int account, long dialogId, ArrayList<MessageObject> messages) {
        Pending pending = PENDING.get(pendingKey(account, dialogId));
        if (pending == null) return;
        if (System.currentTimeMillis() - pending.armedAtMs > CLAIM_WINDOW_MS) {
            killPending(account, dialogId);
            return;
        }
        for (MessageObject message : messages) {
            if (!message.isOutOwner() || message.messageOwner == null) continue;
            if (message.messageOwner.date != pending.scheduleDate) continue;
            long grouped = message.messageOwner.grouped_id;
            if (!pending.claimed) {
                pending.claimed = true;
                pending.groupedId = grouped;
            } else if (grouped == 0 || grouped != pending.groupedId) {
                // A different message that happens to share the schedule minute: don't steal it.
                continue;
            }
            int localId = message.getId();
            if (!pending.entry.localIds.contains(localId)) {
                pending.entry.localIds.add(localId);
            }
        }
    }

    private static void onIdRemap(int account, int oldId, int newId) {
        EventScheduleEntry entry = findByLocalId(account, oldId);
        if (entry == null) return;
        entry.localIds.remove((Integer) oldId);
        // messageReceivedByServer fires twice for the same message; add the server id once.
        if (!entry.serverIds.contains(newId)) {
            entry.serverIds.add(newId);
        }
        EventScheduleStore.persist(account, entry);
        if (entry.localIds.isEmpty()) {
            for (Map.Entry<String, Pending> e : PENDING.entrySet()) {
                if (e.getValue().entry == entry) {
                    PENDING.remove(e.getKey());
                    break;
                }
            }
            if (!hasPending()) pendingAccounts = 0;
        }
    }

    private static EventScheduleEntry findByLocalId(int account, int oldId) {
        for (Pending p : PENDING.values()) {
            if (p.entry.localIds.contains(oldId)) return p.entry;
        }
        return null;
    }

    private static void evaluate(int account, long dialogId, ArrayList<MessageObject> messages) {
        ArrayList<EventScheduleEntry> entries = EventScheduleStore.forDialog(account, dialogId);
        if (entries.isEmpty()) return;
        for (MessageObject message : messages) {
            if (message.isOutOwner() || message.getId() <= 0 || message.messageOwner == null) continue;
            final CharSequence text = message.messageOwner.message;
            for (EventScheduleEntry entry : entries) {
                if (entry.state != EventScheduleEntry.STATE_ARMED || entry.serverIds.isEmpty()) continue;
                if (!entry.matchesType(message)) continue;
                final String key = entry.key();
                // A user regex has no timeout: keep it off the main thread (fork precedent: replace-text).
                Utilities.globalQueue.postRunnable(() -> {
                    if (!entry.matchesPattern(text)) return;
                    AndroidUtilities.runOnUIThread(() -> {
                        if (entry.state != EventScheduleEntry.STATE_ARMED || !EventScheduleStore.contains(account, key)) return;
                        entry.state = EventScheduleEntry.STATE_WAITING;
                        AndroidUtilities.runOnUIThread(() -> fire(account, entry), entry.delaySeconds * 1000L);
                    });
                });
            }
        }
    }

    private static void fire(int account, EventScheduleEntry entry) {
        // The delay window may have outlived the entry (edited, deleted, or the fallback already fired).
        if (entry.state != EventScheduleEntry.STATE_WAITING || !EventScheduleStore.contains(account, entry.key())) return;
        if (entry.serverIds.isEmpty()) {
            entry.state = EventScheduleEntry.STATE_ARMED;
            return;
        }
        entry.state = EventScheduleEntry.STATE_SENDING;
        final long dialogId = entry.dialogId;
        final TLRPC.TL_messages_sendScheduledMessages req = new TLRPC.TL_messages_sendScheduledMessages();
        req.peer = MessagesController.getInstance(account).getInputPeer(dialogId);
        req.id.addAll(entry.serverIds);
        final long clientUserId = UserConfig.getInstance(account).getClientUserId();
        ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> {
            if (error == null) {
                MessagesController.getInstance(account).processUpdates((TLRPC.Updates) response, false);
                for (int i = 0; i < req.id.size(); i++) {
                    AyuState.permitDeleteMessage(dialogId, req.id.get(i));
                }
                AndroidUtilities.runOnUIThread(() -> {
                    NotificationCenter.getInstance(account).postNotificationName(
                            NotificationCenter.messagesDeleted, req.id,
                            clientUserId == dialogId ? 0L : -dialogId, true, true);
                    EventScheduleStore.remove(account, entry);
                });
            } else if (error.text != null && error.text.startsWith("SLOWMODE_WAIT_")) {
                // Next matching message retries; the fallback date covers it regardless.
                AndroidUtilities.runOnUIThread(() -> entry.state = EventScheduleEntry.STATE_ARMED);
            } else {
                // Already sent / deleted / still processing server-side: nothing left to do.
                AndroidUtilities.runOnUIThread(() -> EventScheduleStore.remove(account, entry));
            }
        });
    }
}
