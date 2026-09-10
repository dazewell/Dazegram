package com.radolyn.ayugram.ghosthold;

import androidx.annotation.Nullable;

import org.telegram.SQLite.SQLiteCursor;
import org.telegram.SQLite.SQLiteDatabase;
import org.telegram.SQLite.SQLiteException;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.DispatchQueue;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.NativeByteBuffer;

import java.io.File;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Fork-owned, per-account storage for Ghost Hold, kept deliberately outside the
 * stock Telegram database.
 *
 * <p>A held message lives as a single row in {@code ghost_held} inside this
 * account's own {@code ghosthold_<account>.db} file -- a table no stock query
 * ever names. That is the point: the no-leak invariant (a held message must
 * never reach the server while Ghost is on) becomes <em>structural</em> rather
 * than a set of guards scattered through upstream files. Stock cannot transmit a
 * row it cannot see. A separate file, not a table in {@code cache4.db}, is used
 * on purpose: a stock-DB table needs a schema-version bump, and upstream changes
 * its schema often, so every future merge would risk conflicting in exactly the
 * code holding the user's unsent messages.
 *
 * <p>Everything that touches the database runs on this store's single serial
 * {@link DispatchQueue}; {@link SQLiteDatabase} is not thread-safe and this is
 * the only thread that opens, reads or writes the file. The only cross-thread
 * surface is {@link #cachedForDialog(long)}, an immutable snapshot published
 * after every mutation and read lock-free on the message-load thread by the
 * render injection.
 *
 * <p>All keys are {@code (account, mid)}: local ids are minted from a per-account
 * counter and collide across accounts, so the account is fixed by which store
 * instance you hold and the mid keys within it.
 */
public final class GhostHoldStore {

    public static final int STATE_HELD = 0;
    public static final int STATE_FLUSHING = 1;

    private static final GhostHoldStore[] instances = new GhostHoldStore[UserConfig.MAX_ACCOUNT_COUNT];

    public static GhostHoldStore getInstance(int account) {
        GhostHoldStore local = instances[account];
        if (local == null) {
            synchronized (GhostHoldStore.class) {
                local = instances[account];
                if (local == null) {
                    local = new GhostHoldStore(account);
                    instances[account] = local;
                }
            }
        }
        return local;
    }

    private final int account;
    private final DispatchQueue queue;
    private final Object dbLock = new Object();
    @Nullable
    private SQLiteDatabase database;

    // Queue-confined master view of every row, mid -> record, insertion-ordered.
    private final LinkedHashMap<Integer, HeldRecord> master = new LinkedHashMap<>();
    // Immutable snapshot grouped by dialog, republished after each mutation and
    // read without locking by the injection on the message-load thread.
    private volatile Map<Long, List<HeldRecord>> byDialog = new HashMap<>();
    private volatile boolean loaded;

    private GhostHoldStore(int account) {
        this.account = account;
        this.queue = new DispatchQueue("ghostHold_" + account);
    }

    public DispatchQueue getQueue() {
        return queue;
    }

    // ---- immutable render snapshot (read off-queue) ----

    /**
     * Display snapshot of the held rows for a dialog, or null when there are none.
     * The returned list and its records are never mutated after publication, so the
     * render injection may iterate it on the load thread without locking.
     */
    @Nullable
    public List<HeldRecord> cachedForDialog(long dialogId) {
        return byDialog.get(dialogId);
    }

    // ---- queue-confined database access ----
    // Every method below must be called on getQueue(); each ensures the database is
    // open and the in-memory view is loaded first.

    private SQLiteDatabase db() throws SQLiteException {
        synchronized (dbLock) {
            if (database == null) {
                File dbFile = new File(ApplicationLoader.getFilesDirFixed(), "ghosthold_" + account + ".db");
                boolean create = !dbFile.exists();
                SQLiteDatabase db = new SQLiteDatabase(dbFile.getPath());
                db.executeFast("PRAGMA secure_delete = ON").stepThis().dispose();
                db.executeFast("PRAGMA temp_store = MEMORY").stepThis().dispose();
                db.executeFast("PRAGMA journal_mode = WAL").stepThis().dispose();
                db.executeFast("PRAGMA journal_size_limit = 10485760").stepThis().dispose();
                db.executeFast("CREATE TABLE IF NOT EXISTS ghost_held(mid INTEGER PRIMARY KEY, dialog_id INTEGER, date INTEGER, state INTEGER, data BLOB)").stepThis().dispose();
                if (create) {
                    db.executeFast("PRAGMA user_version = 1").stepThis().dispose();
                }
                database = db;
            }
            return database;
        }
    }

    private void ensureLoaded() throws SQLiteException {
        if (loaded) {
            return;
        }
        master.clear();
        SQLiteCursor cursor = null;
        try {
            cursor = db().queryFinalized("SELECT mid, dialog_id, date, state, data FROM ghost_held ORDER BY mid DESC");
            while (cursor.next()) {
                byte[] blob = cursor.byteArrayValue(4);
                if (blob == null) {
                    continue;
                }
                HeldRecord rec = new HeldRecord(cursor.intValue(0), cursor.longValue(1), cursor.intValue(2), cursor.intValue(3), blob);
                master.put(rec.mid, rec);
            }
        } finally {
            if (cursor != null) {
                cursor.dispose();
            }
        }
        loaded = true;
        publish();
    }

    /** Rebuilds the render snapshot from the queue-confined master view. */
    private void publish() {
        HashMap<Long, List<HeldRecord>> grouped = new HashMap<>();
        for (HeldRecord rec : master.values()) {
            List<HeldRecord> list = grouped.get(rec.dialogId);
            if (list == null) {
                list = new ArrayList<>();
                grouped.put(rec.dialogId, list);
            }
            list.add(rec);
        }
        byDialog = grouped;
    }

    public void insertOnQueue(HeldRecord rec) {
        try {
            ensureLoaded();
            SQLiteDatabase db = db();
            org.telegram.SQLite.SQLitePreparedStatement state =
                    db.executeFast("REPLACE INTO ghost_held(mid, dialog_id, date, state, data) VALUES(?, ?, ?, ?, ?)");
            NativeByteBuffer buffer = new NativeByteBuffer(rec.data.length);
            try {
                buffer.writeBytes(rec.data);
                state.bindInteger(1, rec.mid);
                state.bindLong(2, rec.dialogId);
                state.bindInteger(3, rec.date);
                state.bindInteger(4, rec.state);
                state.bindByteBuffer(5, buffer);
                state.step();
            } finally {
                state.dispose();
                buffer.reuse();
            }
            master.remove(rec.mid);
            master.put(rec.mid, rec);
            publish();
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    public void updateStateOnQueue(int mid, int newState) {
        try {
            ensureLoaded();
            HeldRecord old = master.get(mid);
            if (old == null) {
                return;
            }
            db().executeFast("UPDATE ghost_held SET state = " + newState + " WHERE mid = " + mid).stepThis().dispose();
            master.put(mid, old.withState(newState));
            publish();
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    public void deleteOnQueue(int mid) {
        try {
            ensureLoaded();
            db().executeFast("DELETE FROM ghost_held WHERE mid = " + mid).stepThis().dispose();
            if (master.remove(mid) != null) {
                publish();
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    public void deleteManyOnQueue(List<Integer> mids) {
        if (mids == null || mids.isEmpty()) {
            return;
        }
        try {
            ensureLoaded();
            boolean changed = false;
            StringBuilder in = new StringBuilder();
            for (int mid : mids) {
                if (in.length() > 0) {
                    in.append(',');
                }
                in.append(mid);
                if (master.remove(mid) != null) {
                    changed = true;
                }
            }
            db().executeFast("DELETE FROM ghost_held WHERE mid IN(" + in + ")").stepThis().dispose();
            if (changed) {
                publish();
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    /** Snapshot of every row; call on the queue. */
    public ArrayList<HeldRecord> selectAllOnQueue() {
        try {
            ensureLoaded();
        } catch (Exception e) {
            FileLog.e(e);
        }
        return new ArrayList<>(master.values());
    }

    /** Rows in a given state; call on the queue. */
    public ArrayList<HeldRecord> selectByStateOnQueue(int wantState) {
        ArrayList<HeldRecord> out = new ArrayList<>();
        try {
            ensureLoaded();
            for (HeldRecord rec : master.values()) {
                if (rec.state == wantState) {
                    out.add(rec);
                }
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        return out;
    }

    /** A single row by mid, or null; call on the queue. */
    @Nullable
    public HeldRecord selectOnQueue(int mid) {
        try {
            ensureLoaded();
        } catch (Exception e) {
            FileLog.e(e);
        }
        return master.get(mid);
    }

    /** Count of held rows for a dialog; safe to read off-queue (published snapshot). */
    public int cachedCountForDialog(long dialogId) {
        List<HeldRecord> list = byDialog.get(dialogId);
        return list == null ? 0 : list.size();
    }

    /**
     * Closes and deletes this account's database file (WAL/SHM sidecars included)
     * and clears the in-memory view. Called on logout so a re-login on the same
     * account never inherits a previous session's held messages. Call on the queue.
     */
    public void deleteDatabaseFileOnQueue() {
        synchronized (dbLock) {
            if (database != null) {
                try {
                    database.close();
                } catch (Exception e) {
                    FileLog.e(e);
                }
                database = null;
            }
        }
        File dir = ApplicationLoader.getFilesDirFixed();
        deleteQuietly(new File(dir, "ghosthold_" + account + ".db"));
        deleteQuietly(new File(dir, "ghosthold_" + account + ".db-wal"));
        deleteQuietly(new File(dir, "ghosthold_" + account + ".db-shm"));
        master.clear();
        loaded = false;
        publish();
    }

    private static void deleteQuietly(File f) {
        try {
            if (f.exists()) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    /**
     * Deserializes a held row's stored blob into a fresh {@link org.telegram.tgnet.TLRPC.Message}.
     * A fresh instance every call keeps the render injection's display objects fully
     * independent of the cache, so nothing the UI does to a shown message can ever be
     * written back. Returns null on a decode failure.
     */
    @Nullable
    public static org.telegram.tgnet.TLRPC.Message decode(byte[] blob, long selfId) {
        if (blob == null) {
            return null;
        }
        NativeByteBuffer nbb = null;
        try {
            nbb = new NativeByteBuffer(blob.length);
            nbb.writeBytes(blob);
            nbb.position(0);
            org.telegram.tgnet.TLRPC.Message message =
                    org.telegram.tgnet.TLRPC.Message.TLdeserialize(nbb, nbb.readInt32(false), false);
            if (message != null) {
                message.readAttachPath(nbb, selfId);
            }
            return message;
        } catch (Exception e) {
            FileLog.e(e);
            return null;
        } finally {
            if (nbb != null) {
                nbb.reuse();
            }
        }
    }

    /**
     * Serializes a {@link org.telegram.tgnet.TLRPC.Message} to the blob form stored
     * in {@code ghost_held} -- the TL object followed by its attach path, matching
     * what {@link #decode(byte[], long)} reads back.
     */
    public static byte[] encode(org.telegram.tgnet.TLRPC.Message message) throws Exception {
        int size = message.getObjectSize();
        NativeByteBuffer data = new NativeByteBuffer(size);
        try {
            message.serializeToStream(data);
            byte[] blob = new byte[size];
            ByteBuffer dup = data.buffer.duplicate();
            dup.position(0);
            dup.get(blob, 0, size);
            return blob;
        } finally {
            data.reuse();
        }
    }

    /** One held row: the durable unit of the queue. */
    public static final class HeldRecord {
        public final int mid;
        public final long dialogId;
        public final int date;
        public final int state;
        public final byte[] data;

        public HeldRecord(int mid, long dialogId, int date, int state, byte[] data) {
            this.mid = mid;
            this.dialogId = dialogId;
            this.date = date;
            this.state = state;
            this.data = data;
        }

        HeldRecord withState(int newState) {
            return new HeldRecord(mid, dialogId, date, newState, data);
        }
    }
}
