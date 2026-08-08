/*
 * This is the source code of AyuGram for Android.
 *
 * We do not and cannot prevent the use of our code,
 * but be respectful and credit the original author.
 *
 * Copyright @Radolyn, 2023
 */

package com.radolyn.ayugram.utils;

import android.os.SystemClock;
import android.util.LongSparseArray;

import tw.nekomimi.nekogram.NekoConfig;

public class AyuState {
    private static final AyuStateVariable allowReadPacket = new AyuStateVariable();
    private static final AyuStateVariable hideSelection = new AyuStateVariable();

    /**
     * How long a permit stays good for. A delete the user asked for is answered by the server with
     * an update that comes back through a different path, so the permit has to outlive the local
     * pass that first sees it; a minute covers a slow storage queue plus the round trip.
     */
    private static final long DELETE_PERMIT_TTL = 60_000L;

    private static final Object deleteSync = new Object();

    /**
     * Deletes the user asked for, keyed by dialog and then by account + message + whether the id is
     * a scheduled one, each stamped with the time it expires. Read from the storage queue and
     * written from the main thread, so every touch takes {@link #deleteSync}.
     */
    private static final LongSparseArray<LongSparseArray<Long>> deletePermitted = new LongSparseArray<>();

    /** Expiry is what bounds the map, but reads check their own entry, so sweeping is only upkeep. */
    private static long lastDeleteSweep;

    public static void setAllowReadPacket(boolean val, int resetAfter) {
        allowReadPacket.val = val;
        allowReadPacket.resetAfter = resetAfter;
    }

    public static boolean getAllowReadPacket() {
        return NekoConfig.sendReadMessagePackets.Bool() || allowReadPacket.process();
    }

    public static void setHideSelection(boolean val, int resetAfter) {
        hideSelection.val = val;
        hideSelection.resetAfter = resetAfter;
    }

    public static boolean getHideSelection() {
        return hideSelection.process();
    }

    public static void permitDeleteMessage(int account, long dialogId, int messageId) {
        permitDeleteMessage(account, dialogId, messageId, false);
    }

    public static void permitDeleteMessage(int account, long dialogId, int messageId, boolean scheduled) {
        synchronized (deleteSync) {
            long now = SystemClock.elapsedRealtime();
            sweepExpired(now);
            var messages = deletePermitted.get(dialogId);
            if (messages == null) {
                messages = new LongSparseArray<>();
                deletePermitted.put(dialogId, messages);
            }
            messages.put(messageKey(account, messageId, scheduled), now + DELETE_PERMIT_TTL);
        }
    }

    public static boolean isDeletePermitted(int account, long dialogId, int messageId) {
        return isDeletePermitted(account, dialogId, messageId, false);
    }

    public static boolean isDeletePermitted(int account, long dialogId, int messageId, boolean scheduled) {
        synchronized (deleteSync) {
            long now = SystemClock.elapsedRealtime();
            sweepExpired(now);
            var messages = deletePermitted.get(dialogId);
            if (messages == null) {
                return false;
            }
            Long expiresAt = messages.get(messageKey(account, messageId, scheduled));
            return expiresAt != null && expiresAt > now;
        }
    }

    /**
     * Message ids are handed out per account and local ones are negative, so the same id means
     * different messages on two accounts in one dialog. Scheduled messages are numbered separately
     * again, so they get their own space rather than shadowing a sent message sharing an id. The id
     * keeps the low 32 bits so nothing about it is lost.
     */
    private static long messageKey(int account, int messageId, boolean scheduled) {
        return ((long) messageId & 0xffffffffL)
                | ((long) account << 32)
                | (scheduled ? 1L << 62 : 0L);
    }

    /**
     * Housekeeping only: a permit is judged by its own stamp, so this just keeps the map from
     * holding onto what nobody will ask about again. Deleting a long selection grants one permit per
     * message, so it can't be a full walk per call.
     */
    private static void sweepExpired(long now) {
        if (now - lastDeleteSweep < DELETE_PERMIT_TTL) {
            return;
        }
        lastDeleteSweep = now;
        for (int i = deletePermitted.size() - 1; i >= 0; i--) {
            var messages = deletePermitted.valueAt(i);
            for (int j = messages.size() - 1; j >= 0; j--) {
                if (messages.valueAt(j) <= now) {
                    messages.removeAt(j);
                }
            }
            if (messages.size() == 0) {
                deletePermitted.removeAt(i);
            }
        }
    }
}
