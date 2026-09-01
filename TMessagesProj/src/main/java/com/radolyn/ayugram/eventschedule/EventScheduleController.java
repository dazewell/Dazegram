package com.radolyn.ayugram.eventschedule;

import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;

import androidx.annotation.NonNull;

import com.radolyn.ayugram.utils.AyuState;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;

/**
 * Runtime engine for event-triggered scheduled messages.
 *
 * <p>Everything hangs off the single new-message funnel ({@code onNewMessages}, called
 * from {@code MessagesController.updateInterfaceWithMessages}). A trigger arm is persisted
 * immediately (even before server ids are known), and later bound to server ids from the
 * scheduled-send ack metadata delivered through {@link NotificationCenter#messageReceivedByServer}.
 * Incoming messages are matched against bound arms and, on a hit, fire
 * {@code messages.sendScheduledMessages} early.
 */
public final class EventScheduleController {

    private static final class Pending {
        final int account;
        final EventScheduleEntry entry;
        final int scheduleDate;
        final long expiresAtElapsedMs;
        final long claimExpiresAtElapsedMs;
        long localGroupedId;

        Pending(int account, EventScheduleEntry entry, int scheduleDate,
                long expiresAtElapsedMs, long claimExpiresAtElapsedMs) {
            this.account = account;
            this.entry = entry;
            this.scheduleDate = scheduleDate;
            this.expiresAtElapsedMs = expiresAtElapsedMs;
            this.claimExpiresAtElapsedMs = claimExpiresAtElapsedMs;
        }
    }

    // Local scheduled echoes are posted inline from the send path, so claim eligibility is short.
    private static final long CLAIM_WINDOW_MS = 30000;
    // Bind window for associating send acks back to a just-armed trigger.
    private static final long BIND_WINDOW_SECONDS = 30L * 60L;

    // Written only on the UI thread (arm/kill/ack/pending GC all run there).
    private static final Map<String, Pending> PENDING = new HashMap<>();
    private static final Map<String, ArrayList<EventScheduleEntry>> QUEUES = new HashMap<>();
    private static final Map<String, Boolean> RUNNING_QUEUES = new HashMap<>();
    private static final Map<String, Integer> QUEUE_TOKENS = new HashMap<>();
    private static int nextQueueToken;
    private static volatile long pendingAccounts;
    private static volatile long warmedAccounts;
    private static final Object OBSERVED_LOCK = new Object();
    private static final ArrayList<Integer> OBSERVED = new ArrayList<>();

    private EventScheduleController() {}

    private static String pendingKey(int account, @NonNull String entryKey) {
        return account + "_" + entryKey;
    }

    private static String queueKey(int account, EventScheduleEntry entry) {
        return account + "_" + entry.dialogId + "_" + entry.triggerKey();
    }

    private static final Comparator<EventScheduleEntry> QUEUE_ORDER = (a, b) -> {
        int result = Integer.compare(a.fallbackDate, b.fallbackDate);
        if (result == 0) result = Long.compare(a.createdAt, b.createdAt);
        if (result == 0) {
            int aId = a.serverIds.isEmpty() ? Integer.MAX_VALUE : a.serverIds.get(0);
            int bId = b.serverIds.isEmpty() ? Integer.MAX_VALUE : b.serverIds.get(0);
            result = Integer.compare(aId, bId);
        }
        return result;
    };

    private static boolean hasState(int account) {
        return EventScheduleStore.hasAny(account) || (pendingAccounts & (1L << account)) != 0;
    }

    private static final Runnable PENDING_GC = () -> {
        pruneExpiredPending();
        schedulePendingGc();
    };

    // Rebuild account bits from the pending map so the hot path can bail cheaply when no arm is pending.
    private static void refreshPendingBits() {
        long bits = 0;
        for (Pending pending : PENDING.values()) {
            bits |= (1L << pending.account);
        }
        pendingAccounts = bits;
    }

    private static long nowSeconds() {
        return System.currentTimeMillis() / 1000L;
    }

    private static long toElapsedDeadline(long epochSeconds) {
        long remainingMs = epochSeconds * 1000L - System.currentTimeMillis();
        return SystemClock.elapsedRealtime() + Math.max(remainingMs, 0L);
    }

    private static void addPending(int account, @NonNull EventScheduleEntry entry, int scheduleDate, long claimWindowMs) {
        long claimExpiresAtElapsedMs = claimWindowMs <= 0 ? 0 : SystemClock.elapsedRealtime() + claimWindowMs;
        PENDING.put(pendingKey(account, entry.key()),
                new Pending(account, entry, scheduleDate, toElapsedDeadline(entry.bindExpiresAt), claimExpiresAtElapsedMs));
        refreshPendingBits();
    }

    private static void removePending(int account, @NonNull String entryKey) {
        PENDING.remove(pendingKey(account, entryKey));
        refreshPendingBits();
    }

    private static void schedulePendingGc() {
        AndroidUtilities.cancelRunOnUIThread(PENDING_GC);
        long nearestDelay = Long.MAX_VALUE;
        long now = SystemClock.elapsedRealtime();
        for (Pending pending : PENDING.values()) {
            nearestDelay = Math.min(nearestDelay, Math.max(0L, pending.expiresAtElapsedMs - now));
        }
        if (nearestDelay == Long.MAX_VALUE) return;
        AndroidUtilities.runOnUIThread(PENDING_GC, nearestDelay);
    }

    private static void pruneExpiredPending() {
        if (PENDING.isEmpty()) return;
        long nowElapsed = SystemClock.elapsedRealtime();
        long nowSec = nowSeconds();
        ArrayList<Pending> expired = new ArrayList<>();
        for (Pending pending : PENDING.values()) {
            if (pending.expiresAtElapsedMs <= nowElapsed) {
                expired.add(pending);
            }
        }
        if (expired.isEmpty()) return;
        for (Pending pending : expired) {
            EventScheduleEntry entry = pending.entry;
            removePending(pending.account, entry.key());
            if (!EventScheduleStore.contains(pending.account, entry.key())) continue;
            if (entry.serverIds.isEmpty()) {
                if (entry.localIds.isEmpty()) {
                    EventScheduleStore.remove(pending.account, entry.key());
                } else if (entry.bindExpiresAt <= nowSec) {
                    entry.bindExpiresAt = 0;
                    EventScheduleStore.persist(pending.account, entry);
                }
            } else if (entry.bindExpiresAt <= nowSec) {
                entry.bindExpiresAt = 0;
                EventScheduleStore.persist(pending.account, entry);
            }
        }
    }

    private static void restorePending(int account) {
        long nowSec = nowSeconds();
        ArrayList<EventScheduleEntry> entries = EventScheduleStore.forAccount(account);
        for (EventScheduleEntry entry : entries) {
            if (entry.bindExpiresAt <= 0) continue;
            if (entry.bindExpiresAt <= nowSec) {
                if (entry.serverIds.isEmpty()) {
                    if (entry.localIds.isEmpty()) {
                        EventScheduleStore.remove(account, entry.key());
                    } else {
                        entry.bindExpiresAt = 0;
                        EventScheduleStore.persist(account, entry);
                    }
                } else {
                    entry.bindExpiresAt = 0;
                    EventScheduleStore.persist(account, entry);
                }
                continue;
            }
            addPending(account, entry, entry.fallbackDate, 0);
        }
        schedulePendingGc();
    }

    // The observer serves every account (the callback carries the account), so one instance
    // registered per account covers id remap and scheduled deletes for its whole lifetime.
    private static final NotificationCenter.NotificationCenterDelegate OBSERVER = (id, account, args) -> {
        if (id == NotificationCenter.messageReceivedByServer) {
            boolean scheduled = args.length > 6 && args[6] instanceof Boolean && (Boolean) args[6];
            if (!scheduled) return;
            long dialogId = args.length > 3 && args[3] instanceof Long ? (Long) args[3] : 0L;
            if (dialogId == 0L) return;
            int oldId = args.length > 0 && args[0] instanceof Integer ? (Integer) args[0] : 0;
            int newId = args.length > 1 && args[1] instanceof Integer ? (Integer) args[1] : 0;
            TLRPC.Message message = args.length > 2 && args[2] instanceof TLRPC.Message ? (TLRPC.Message) args[2] : null;
            if (message == null) return;
            long groupedId = args.length > 4 && args[4] instanceof Long ? (Long) args[4] : 0L;
            onIdRemap(account, oldId, newId, dialogId, groupedId);
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
        restorePending(account);
        if (hasState(account)) ensureObserver(account);
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
    public static String armPending(int account, long dialogId, @NonNull EventScheduleEntry entry, int scheduleDate) {
        entry.dialogId = dialogId;
        entry.fallbackDate = scheduleDate;
        entry.bindGroupedId = 0;
        entry.bindExpiresAt = nowSeconds() + BIND_WINDOW_SECONDS;
        entry.state = EventScheduleEntry.STATE_ARMED;
        EventScheduleStore.persist(account, entry);
        addPending(account, entry, scheduleDate, CLAIM_WINDOW_MS);
        schedulePendingGc();
        ensureObserver(account);
        return entry.key();
    }

    /** Drops a pending bind that never got a message (trigger turned off, or a stale edit). */
    public static void killPending(int account, String entryKey) {
        if (TextUtils.isEmpty(entryKey)) return;
        removePending(account, entryKey);
        if (EventScheduleStore.contains(account, entryKey)) {
            EventScheduleStore.remove(account, entryKey);
        }
        schedulePendingGc();
    }

    /** Trigger explicitly turned off: drop only still-unclaimed pending arms for this dialog. */
    public static void killUnclaimedForDialog(int account, long dialogId) {
        ArrayList<String> keys = new ArrayList<>();
        for (Pending pending : PENDING.values()) {
            EventScheduleEntry entry = pending.entry;
            if (pending.account != account || entry.dialogId != dialogId) continue;
            if (!entry.serverIds.isEmpty() || !entry.localIds.isEmpty()) continue;
            keys.add(entry.key());
        }
        for (String key : keys) {
            removePending(account, key);
            if (EventScheduleStore.contains(account, key)) {
                EventScheduleStore.remove(account, key);
            }
        }
        schedulePendingGc();
    }

    /** Arms a trigger on a message whose server ids are already known (editing a message with no trigger yet). */
    public static void armExisting(int account, @NonNull EventScheduleEntry entry) {
        entry.bindGroupedId = 0;
        entry.bindExpiresAt = 0;
        entry.state = EventScheduleEntry.STATE_ARMED;
        EventScheduleStore.persist(account, entry);
        ensureObserver(account);
    }

    /** Replaces an already-armed (server-side) entry in place after an edit. */
    public static void updateForEdit(int account, @NonNull EventScheduleEntry entry, int types, String pattern, boolean regex, int delaySeconds, int fallbackDate) {
        entry.revision++;
        removeFromQueue(account, entry);
        entry.types = types;
        entry.pattern = pattern == null ? "" : pattern;
        entry.regex = regex;
        entry.delaySeconds = delaySeconds;
        entry.fallbackDate = fallbackDate;
        entry.bindGroupedId = 0;
        if (!entry.serverIds.isEmpty()) {
            entry.bindExpiresAt = 0;
        }
        entry.state = EventScheduleEntry.STATE_ARMED;
        entry.resetCompiled();
        EventScheduleStore.persist(account, entry);
        ensureObserver(account);
    }

    public static void onNewMessages(int account, long dialogId, ArrayList<MessageObject> messages, boolean scheduled) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            ArrayList<MessageObject> snapshot = messages == null ? null : new ArrayList<>(messages);
            AndroidUtilities.runOnUIThread(() -> onNewMessages(account, dialogId, snapshot, scheduled));
            return;
        }
        ensureWarm(account);
        if (!hasState(account) || messages == null || messages.isEmpty()) return;
        if (scheduled) {
            claim(account, dialogId, messages);
        } else {
            evaluate(account, dialogId, messages);
        }
    }

    private static void onIdRemap(int account, int oldId, int newId, long dialogId, long groupedId) {
        if (EventScheduleStore.findByMessage(account, dialogId, newId) != null) return;
        pruneExpiredPending();
        Pending pending = findPendingByLocalId(account, oldId);
        if (pending == null) return;
        EventScheduleEntry entry = pending.entry;
        if (!EventScheduleStore.contains(account, entry.key())) {
            removePending(account, entry.key());
            schedulePendingGc();
            return;
        }
        if (groupedId != 0) {
            if (entry.bindGroupedId == 0) {
                entry.bindGroupedId = groupedId;
            } else if (entry.bindGroupedId != groupedId) {
                return;
            }
        } else if (!entry.serverIds.isEmpty()) {
            return;
        }
        if (!entry.serverIds.contains(newId)) {
            entry.serverIds.add(newId);
            EventScheduleStore.persist(account, entry);
        }
    }

    private static void claim(int account, long dialogId, ArrayList<MessageObject> messages) {
        pruneExpiredPending();
        for (MessageObject message : messages) {
            if (!message.isOutOwner() || message.messageOwner == null) continue;
            int localId = message.getId();
            if (localId >= 0) continue;
            int scheduleDate = message.messageOwner.date;
            long groupedId = message.messageOwner.grouped_id;
            Pending pending = findPendingForClaim(account, dialogId, scheduleDate, groupedId);
            if (pending == null) continue;
            EventScheduleEntry entry = pending.entry;
            if (groupedId != 0 && pending.localGroupedId == 0) {
                pending.localGroupedId = groupedId;
            }
            if (!entry.localIds.contains(localId)) {
                entry.localIds.add(localId);
            }
        }
    }

    private static Pending findPendingByLocalId(int account, int localId) {
        if (localId >= 0) return null;
        for (Pending pending : PENDING.values()) {
            if (pending.account != account) continue;
            if (pending.entry.localIds.contains(localId)) {
                return pending;
            }
        }
        return null;
    }

    private static Pending findPendingForClaim(int account, long dialogId, int scheduleDate, long groupedId) {
        long nowElapsed = SystemClock.elapsedRealtime();
        ArrayList<Pending> candidates = new ArrayList<>();
        for (Pending pending : PENDING.values()) {
            EventScheduleEntry entry = pending.entry;
            if (pending.claimExpiresAtElapsedMs <= 0 || pending.claimExpiresAtElapsedMs <= nowElapsed) continue;
            if (pending.account != account || entry.dialogId != dialogId || pending.scheduleDate != scheduleDate) continue;
            if (!acceptsClaim(pending, groupedId)) continue;
            candidates.add(pending);
        }
        if (candidates.isEmpty()) return null;
        Collections.sort(candidates, (a, b) -> {
            int result = Long.compare(a.entry.createdAt, b.entry.createdAt);
            if (result == 0) result = a.entry.key().compareTo(b.entry.key());
            return result;
        });
        return candidates.get(0);
    }

    private static boolean acceptsClaim(@NonNull Pending pending, long groupedId) {
        EventScheduleEntry entry = pending.entry;
        if (groupedId == 0) {
            return pending.localGroupedId == 0 && entry.serverIds.isEmpty() && entry.localIds.isEmpty();
        }
        if (pending.localGroupedId == 0) {
            return entry.serverIds.isEmpty() && entry.localIds.isEmpty();
        }
        return pending.localGroupedId == groupedId;
    }

    private static void evaluate(int account, long dialogId, ArrayList<MessageObject> messages) {
        ArrayList<EventScheduleEntry> entries = EventScheduleStore.forDialog(account, dialogId);
        if (entries.isEmpty()) return;
        for (MessageObject message : messages) {
            if (message.isOutOwner() || message.getId() <= 0 || message.messageOwner == null) continue;
            final String text = matchableText(message);
            for (EventScheduleEntry entry : entries) {
                if (entry.state != EventScheduleEntry.STATE_ARMED || entry.serverIds.isEmpty()) continue;
                final String key = entry.key();
                boolean typeSet = entry.types != 0;
                boolean patternSet = !TextUtils.isEmpty(entry.pattern);
                // OR: a matching type is enough on its own; otherwise the pattern can still match.
                if (typeSet && entry.matchesType(message, text)) {
                    armWaiting(account, entry, key, entry.revision);
                    continue;
                }
                if (!patternSet || text == null) continue;
                final long revision = entry.revision;
                // A user regex has no timeout: keep it off the main thread (fork precedent: replace-text).
                Utilities.globalQueue.postRunnable(() -> {
                    if (!entry.matchesPattern(text)) return;
                    AndroidUtilities.runOnUIThread(() -> armWaiting(account, entry, key, revision));
                });
            }
        }
    }

    /**
     * The text a trigger matches against. A rich (post-format) message carries an empty
     * {@code message} and keeps everything in {@code rich_message}, which {@link MessageObject}
     * has already flattened into {@code messageText} -- anything else would be an app-synthesised
     * string (a service line, a restriction reason, a media title), which no user pattern should
     * be able to hit. Snapshotted to a String here on the UI thread, since the match runs on a
     * background queue and {@code messageText} is a mutable Spannable.
     */
    private static String matchableText(MessageObject message) {
        String text = message.messageOwner.message;
        if (!TextUtils.isEmpty(text)) return text;
        if (message.messageOwner.rich_message == null || message.messageText == null) return null;
        return message.messageText.toString();
    }

    private static void armWaiting(int account, EventScheduleEntry entry, String key, long revision) {
        if (entry.state != EventScheduleEntry.STATE_ARMED || entry.revision != revision
                || !EventScheduleStore.contains(account, key)) return;
        entry.state = EventScheduleEntry.STATE_WAITING;
        String queueKey = queueKey(account, entry);
        ArrayList<EventScheduleEntry> queue = QUEUES.get(queueKey);
        boolean queueRunning = Boolean.TRUE.equals(RUNNING_QUEUES.get(queueKey));
        if (queue == null) {
            queue = new ArrayList<>();
            QUEUES.put(queueKey, queue);
        }
        boolean inserted = false;
        if (!queue.contains(entry)) {
            queue.add(entry);
            Collections.sort(queue, QUEUE_ORDER);
            inserted = true;
        }
        if (inserted && queueRunning && !queue.isEmpty() && queue.get(0) == entry) {
            advanceQueue(account, queueKey);
            return;
        }
        startQueue(account, queueKey);
    }

    private static void startQueue(int account, String queueKey) {
        if (Boolean.TRUE.equals(RUNNING_QUEUES.get(queueKey))) return;
        RUNNING_QUEUES.put(queueKey, true);
        advanceQueue(account, queueKey);
    }

    private static void advanceQueue(int account, String queueKey) {
        ArrayList<EventScheduleEntry> queue = QUEUES.get(queueKey);
        while (queue != null && !queue.isEmpty()) {
            EventScheduleEntry entry = queue.get(0);
            if (entry.state == EventScheduleEntry.STATE_WAITING && EventScheduleStore.contains(account, entry.key())) {
                int token = ++nextQueueToken;
                QUEUE_TOKENS.put(queueKey, token);
                long revision = entry.revision;
                AndroidUtilities.runOnUIThread(() -> fire(account, entry, queueKey, token, revision), entry.delaySeconds * 1000L);
                return;
            }
            queue.remove(0);
        }
        QUEUES.remove(queueKey);
        RUNNING_QUEUES.remove(queueKey);
        QUEUE_TOKENS.remove(queueKey);
    }

    private static void releaseQueue(int account, String queueKey, EventScheduleEntry current, boolean removeCurrent) {
        ArrayList<EventScheduleEntry> queue = QUEUES.remove(queueKey);
        RUNNING_QUEUES.remove(queueKey);
        QUEUE_TOKENS.remove(queueKey);
        if (queue == null) return;
        for (EventScheduleEntry entry : queue) {
            if (entry == current && removeCurrent) {
                EventScheduleStore.remove(account, entry);
            } else if (entry.state == EventScheduleEntry.STATE_WAITING) {
                entry.state = EventScheduleEntry.STATE_ARMED;
            }
        }
    }

    private static void removeFromQueue(int account, EventScheduleEntry entry) {
        String queueKey = queueKey(account, entry);
        ArrayList<EventScheduleEntry> queue = QUEUES.get(queueKey);
        if (queue == null) return;
        boolean wasHead = !queue.isEmpty() && queue.get(0) == entry;
        queue.remove(entry);
        if (queue.isEmpty()) {
            QUEUES.remove(queueKey);
            RUNNING_QUEUES.remove(queueKey);
            QUEUE_TOKENS.remove(queueKey);
        } else if (wasHead) {
            advanceQueue(account, queueKey);
        }
    }

    static void onEntryRemoved(int account, EventScheduleEntry entry) {
        removeFromQueue(account, entry);
        removePending(account, entry.key());
        schedulePendingGc();
    }

    private static void fire(int account, EventScheduleEntry entry, String expectedQueueKey, int token, long revision) {
        // The delay window may have outlived the entry (edited, deleted, or the fallback already fired).
        ArrayList<EventScheduleEntry> queue = QUEUES.get(expectedQueueKey);
        if (!Integer.valueOf(token).equals(QUEUE_TOKENS.get(expectedQueueKey))) {
            return;
        }
        if (queue == null || queue.isEmpty()) {
            return;
        }
        if (entry.state != EventScheduleEntry.STATE_WAITING || entry.revision != revision
                || !EventScheduleStore.contains(account, entry.key())) {
            // Token still matches, so this callback is the live queue owner and must re-drive.
            advanceQueue(account, expectedQueueKey);
            return;
        }
        if (queue.get(0) != entry) {
            // Same token means this callback is still the live queue driver, so hand off to the new head.
            advanceQueue(account, expectedQueueKey);
            return;
        }
        if (entry.serverIds.isEmpty()) {
            entry.state = EventScheduleEntry.STATE_ARMED;
            releaseQueue(account, expectedQueueKey, entry, false);
            return;
        }
        entry.state = EventScheduleEntry.STATE_SENDING;
        final long dialogId = entry.dialogId;
        final long sendRevision = entry.revision;
        final TLRPC.TL_messages_sendScheduledMessages req = new TLRPC.TL_messages_sendScheduledMessages();
        req.peer = MessagesController.getInstance(account).getInputPeer(dialogId);
        req.id.addAll(entry.serverIds);
        // Scheduled-delete channelId is channel-only (matches MessagesController's own scheduled post):
        // passing -dialogId for a DM or basic group makes ChatActivity.processDeletedMessages skip the
        // removal, leaving the message stuck in the scheduled view until reload.
        final long channelId = DialogObject.isChatDialog(dialogId)
                && ChatObject.isChannel(MessagesController.getInstance(account).getChat(-dialogId)) ? -dialogId : 0L;
        ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> {
            if (error == null) {
                MessagesController.getInstance(account).processUpdates((TLRPC.Updates) response, false);
                for (int i = 0; i < req.id.size(); i++) {
                    AyuState.permitDeleteMessage(account, dialogId, req.id.get(i), true);
                }
                AndroidUtilities.runOnUIThread(() -> {
                    NotificationCenter.getInstance(account).postNotificationName(
                            NotificationCenter.messagesDeleted, req.id, channelId, true, true);
                    if (EventScheduleStore.contains(account, entry.key())) {
                        // An edit can change the revision while this request is in flight, but the
                        // same scheduled IDs are still no longer eligible for another trigger.
                        EventScheduleStore.remove(account, entry);
                        // Local heads-up: the trigger fired while the user may not be looking at the chat.
                        EventScheduleNotifier.notifySent(account, dialogId);
                    }
                });
            } else if (error.text != null && (error.text.startsWith("SLOWMODE_WAIT_")
                    || error.text.startsWith("FLOOD_WAIT_"))) {
                // Next matching message retries; keep the remaining queue in scheduled-page order.
                AndroidUtilities.runOnUIThread(() -> {
                    if (entry.revision != sendRevision) return;
                    entry.state = EventScheduleEntry.STATE_ARMED;
                    releaseQueue(account, expectedQueueKey, entry, false);
                });
            } else {
                // Already sent / deleted / still processing server-side: nothing left to do.
                AndroidUtilities.runOnUIThread(() -> {
                    if (entry.revision != sendRevision) return;
                    releaseQueue(account, expectedQueueKey, entry, true);
                });
            }
        });
    }
}
