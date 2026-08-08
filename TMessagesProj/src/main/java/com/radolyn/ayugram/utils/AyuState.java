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
     * Deletes the user asked for, keyed account + dialog + message and stamped with the time they
     * expire. Read from the storage queue and written from the main thread, so every touch takes
     * {@link #deleteSync} and drops whatever has expired, which is also what bounds the map.
     */
    private static final LongSparseArray<LongSparseArray<Long>> deletePermitted = new LongSparseArray<>();

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
        synchronized (deleteSync) {
            sweepExpired(SystemClock.elapsedRealtime());
            long key = permitKey(account, dialogId);
            var messages = deletePermitted.get(key);
            if (messages == null) {
                messages = new LongSparseArray<>();
                deletePermitted.put(key, messages);
            }
            messages.put(messageId, SystemClock.elapsedRealtime() + DELETE_PERMIT_TTL);
        }
    }

    public static boolean isDeletePermitted(int account, long dialogId, int messageId) {
        synchronized (deleteSync) {
            long now = SystemClock.elapsedRealtime();
            sweepExpired(now);
            var messages = deletePermitted.get(permitKey(account, dialogId));
            if (messages == null) {
                return false;
            }
            Long expiresAt = messages.get(messageId);
            return expiresAt != null && expiresAt > now;
        }
    }

    /**
     * Message ids are per account and local ones are negative, so an id can mean two different
     * messages on two accounts in the same dialog. The account has to be part of the key.
     */
    private static long permitKey(int account, long dialogId) {
        return dialogId * 31L + account;
    }

    private static void sweepExpired(long now) {
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
