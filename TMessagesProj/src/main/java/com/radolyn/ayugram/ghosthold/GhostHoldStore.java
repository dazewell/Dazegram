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
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
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

    public static synchronized GhostHoldStore getInstance(int account) {
        // Fully synchronized rather than double-checked: getInstance is called from
        // the UI/init path and the message-load thread, and the per-account slot lives
        // in a plain (non-volatile) array whose element reads carry no happens-before.
        // A lock-free fast path could publish or read a half-constructed store, giving
        // two callers two stores over one account file -- two queues, two SQLite
        // connections, racing writes and snapshots. Publish and look up under the one
        // class monitor. Not a hot path (init, send, load), so the lock is free.
        GhostHoldStore local = instances[account];
        if (local == null) {
            local = new GhostHoldStore(account);
            instances[account] = local;
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
    // Queue-confined private-photo ownership rows, keyed by the held local id.
    private final HashMap<Integer, AssetRecord> assets = new HashMap<>();
    // Immutable snapshot grouped by dialog, republished after each mutation and
    // read without locking by the injection on the message-load thread.
    private volatile Map<Long, List<HeldRecord>> byDialog = new HashMap<>();
    private volatile Map<Integer, AssetRecord> assetSnapshot = new HashMap<>();
    private volatile boolean loaded;
    // The user id this file's rows belong to, mirrored out of the DB so the
    // off-queue readers (render injection, count post) can refuse a snapshot that
    // still belongs to a previous user of this reusable account slot. 0 until the
    // first activated open stamps it.
    private volatile long snapshotOwner;
    // Bumped once each time the database is torn down (logout deletes the file and
    // drops the in-memory view). A queued batch collected against one generation must
    // never be applied after a teardown -- even a reopen for the same user, which would
    // resurrect messages the logout was meant to destroy, or a reopen for a different
    // user, which would be a cross-account leak. The batch carries the generation it was
    // collected at; the compare and this increment both run on the fork queue, so they
    // are atomic with respect to each other, and the off-queue capture reads it volatile.
    private volatile int generation;

    private GhostHoldStore(int account) {
        this.account = account;
        this.queue = new DispatchQueue("ghostHold_" + account);
    }

    /**
     * The single entry point for fork-queue work (the render snapshot aside, which is
     * read off-queue). Posts {@code op} and runs it only if the store still matches the
     * {@code epoch} the caller pinned when it decided to act -- i.e. the store has not
     * been torn down by a logout since. If it has, {@code op} is abandoned and
     * {@code onInvalidated} runs in its place, so a caller threading a completion
     * counter still terminates instead of hanging. This carries the caller-intent half
     * of the ownership precondition; the current-user half is enforced unavoidably
     * inside every db method by {@link #ensureLoaded()}. Together they are the whole
     * precondition, and because the queue is private no work can bypass them.
     */
    public void runOwned(int epoch, Owned op, @Nullable Runnable onInvalidated) {
        queue.postRunnable(() -> {
            if (generation != epoch) {
                if (onInvalidated != null) {
                    onInvalidated.run();
                }
                return;
            }
            try {
                op.run();
            } catch (Exception e) {
                FileLog.e(e);
            }
        });
    }

    public void runOwned(int epoch, Owned op) {
        runOwned(epoch, op, null);
    }

    /**
     * Pins the current generation at post time. For single-shot ops with no earlier
     * decision point of their own, where "the caller intended" is simply "now".
     */
    public void runOwned(Owned op) {
        runOwned(generation, op, null);
    }

    /**
     * Posts the logout teardown. The one queue op that must bypass the ownership gate,
     * because it is the teardown that invalidates it.
     */
    public void postTeardown() {
        queue.postRunnable(this::deleteDatabaseFileOnQueue);
    }

    /**
     * The store's teardown generation, incremented on each logout DB deletion. A caller
     * -- a migration batch, or a flush -- pins this when it begins and revalidates
     * against it (via {@link #runOwned}) at each queue step, so work decided against one
     * open store is dropped once the store is torn down and reopened, for the same user
     * (which would resurrect messages the logout destroyed) or a different one (a
     * cross-account leak). Read off-queue for the pin; compared and incremented on the
     * fork queue, so the two are atomic with respect to each other.
     */
    public int currentGeneration() {
        return generation;
    }

    /** A unit of fork-queue work run under the ownership gate; see {@link #runOwned}. */
    public interface Owned {
        void run() throws Exception;
    }

    // ---- immutable render snapshot (read off-queue) ----

    /**
     * Display snapshot of the held rows for a dialog, or null when there are none.
     * The returned list and its records are never mutated after publication, so the
     * render injection may iterate it on the load thread without locking.
     */
    @Nullable
    public List<HeldRecord> cachedForDialog(long dialogId) {
        List<HeldRecord> list = byDialog.get(dialogId);
        // NagramX: hand back a read-only view so the never-mutated-after-publication
        // guarantee is enforced at the boundary, not just by caller convention.
        return list == null ? null : Collections.unmodifiableList(list);
    }

    /**
     * True when this store's rows are known to belong to {@code userId}. The
     * off-queue readers gate on this so that, in the window between a re-login and
     * the queue re-opening the file, a stale snapshot from the previous slot owner
     * is never rendered or counted. Fails toward showing nothing (returns false
     * until an activated open stamps the owner), never toward showing another
     * user's held messages.
     */
    public boolean ownsUser(long userId) {
        return userId != 0 && snapshotOwner == userId;
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
                db.executeFast("CREATE TABLE IF NOT EXISTS ghost_held_assets(mid INTEGER PRIMARY KEY, name TEXT NOT NULL, size INTEGER NOT NULL, local_id INTEGER NOT NULL DEFAULT 0, handed_off INTEGER NOT NULL DEFAULT 0)").stepThis().dispose();
                db.executeFast("CREATE TABLE IF NOT EXISTS ghost_meta(k INTEGER PRIMARY KEY, v INTEGER)").stepThis().dispose();
                if (create) {
                    db.executeFast("PRAGMA user_version = 1").stepThis().dispose();
                }
                database = db;
            }
            return database;
        }
    }

    /**
     * Binds this file to the currently logged-in user and purges it if it turns out
     * to belong to someone else. The account slot is reused across different users;
     * if logout deletion did not complete (crash, force-stop, a delete that failed)
     * the next user in the same slot must never inherit the previous user's held
     * rows -- rendering them would leak, flushing them would send one user's message
     * from another user's account. A zero id means the account is not activated yet,
     * so nothing is stamped or purged on that transient state. Runs inside {@link #db()}
     * on the store queue, before any read or flush can observe a row.
     */
    private void enforceOwner(SQLiteDatabase db) throws SQLiteException {
        long current = UserConfig.getInstance(account).getClientUserId();
        if (current == 0) {
            return;
        }
        long stored = 0;
        SQLiteCursor c = null;
        try {
            c = db.queryFinalized("SELECT v FROM ghost_meta WHERE k = 1");
            if (c.next()) {
                stored = c.longValue(0);
            }
        } finally {
            if (c != null) {
                c.dispose();
            }
        }
        if (stored != current) {
            // The file does not belong to the current user. Two cases, both untrusted:
            // a different user owned it (stored != 0 -- a slot reused after a logout
            // whose file delete never ran), or the owner stamp is missing entirely
            // (stored == 0) while ghost_held rows survive -- a teardown that cleared the
            // stamp but could not delete the rows (deleteDatabaseFileOnQueue clears the
            // stamp first for exactly this reason). Either way, binding without purging
            // would render or flush another session's rows -- the leak this gate exists
            // to stop -- so purge before stamping. current != 0 here (early return
            // above), so stored == 0 lands in this branch. A fresh first open hits it
            // too, but ghost_held is empty then, so the delete is a no-op.
            //
            // Drop the in-memory view first: if the DELETE below throws (a locked or
            // corrupt db), the on-disk rows survive, but we must not keep serving them
            // from master. Clearing and publishing an empty snapshot before the delete,
            // and leaving snapshotOwner unset until the delete succeeds, means a thrown
            // delete leaves an empty view and an un-bound owner, so the next access
            // re-runs this gate rather than leaking the previous owner's rows.
            master.clear();
            assets.clear();
            loaded = false;
            publish();
            db.executeFast("DELETE FROM ghost_held_assets").stepThis().dispose();
            db.executeFast("DELETE FROM ghost_held").stepThis().dispose();
            deleteAssetDirectoryOnQueue();
            db.executeFast("REPLACE INTO ghost_meta(k, v) VALUES(1, " + current + ")").stepThis().dispose();
        }
        snapshotOwner = current;
    }

    private void ensureLoaded() throws SQLiteException {
        // Single, unavoidable ownership gate: every queue read and write funnels
        // through here, so re-binding the file to the current logged-in user on every
        // access -- not just the first open -- is what keeps a slot reused by a
        // different user (a re-login, even one the async logout observer somehow
        // missed) from ever serving or writing the previous owner's rows. enforceOwner
        // purges and sets loaded=false on a user change, so the reload below rebuilds
        // master under the new owner.
        enforceOwner(db());
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
        assets.clear();
        SQLiteCursor assetCursor = null;
        try {
            assetCursor = db().queryFinalized("SELECT mid, name, size, local_id, handed_off FROM ghost_held_assets");
            while (assetCursor.next()) {
                int mid = assetCursor.intValue(0);
                String name = assetCursor.stringValue(1);
                long size = assetCursor.longValue(2);
                int localId = assetCursor.intValue(3);
                boolean handedOff = assetCursor.intValue(4) != 0;
                AssetRecord asset = new AssetRecord(mid, name, size, localId, handedOff, false);
                assets.put(mid, asset.withValid(isAssetFileValid(asset)));
            }
        } finally {
            if (assetCursor != null) {
                assetCursor.dispose();
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
        assetSnapshot = new HashMap<>(assets);
    }

    public boolean isAssetUsable(int mid) {
        AssetRecord asset = assetSnapshot.get(mid);
        return asset != null && asset.valid;
    }

    @Nullable
    public AssetRecord copyPrivatePhotoOnQueue(int mid, File source, long expectedSize) {
        try {
            ensureLoaded();
            if (source == null || expectedSize <= 0 || !source.isFile() || source.length() != expectedSize) {
                return null;
            }
            File dir = assetDirectory();
            if (!dir.exists() && !dir.mkdirs() || !dir.isDirectory()) {
                return null;
            }
            String name = "photo_" + Math.abs((long) mid) + ".asset";
            File target = resolveAssetFile(name);
            if (target == null || target.exists()) {
                return null;
            }
            File temp = resolveAssetFile(name + ".tmp");
            if (temp == null) {
                return null;
            }
            if (temp.exists() && !temp.delete() || !temp.createNewFile()) {
                return null;
            }
            boolean copied = false;
            try (FileInputStream input = new FileInputStream(source);
                 FileOutputStream output = new FileOutputStream(temp, false)) {
                byte[] buffer = new byte[64 * 1024];
                long written = 0;
                int read;
                while ((read = input.read(buffer)) != -1) {
                    output.write(buffer, 0, read);
                    written += read;
                }
                output.flush();
                output.getFD().sync();
                copied = written == expectedSize && temp.length() == expectedSize;
            } finally {
                if (!copied) {
                    deleteQuietly(temp);
                }
            }
            if (!copied || target.exists()) {
                deleteQuietly(temp);
                return null;
            }
            if (!temp.renameTo(target)) {
                deleteQuietly(temp);
                return null;
            }
            if (target.length() != expectedSize) {
                deleteQuietly(target);
                return null;
            }
            return new AssetRecord(mid, name, expectedSize, 0, false, true);
        } catch (Exception e) {
            FileLog.e(e);
            return null;
        }
    }

    public boolean insertAssetOnQueue(AssetRecord asset) {
        try {
            ensureLoaded();
            if (asset == null || resolveAssetFile(asset.name) == null || assets.containsKey(asset.mid)) {
                return false;
            }
            org.telegram.SQLite.SQLitePreparedStatement state =
                    db().executeFast("INSERT INTO ghost_held_assets(mid, name, size, local_id, handed_off) VALUES(?, ?, ?, ?, ?)");
            try {
                state.bindInteger(1, asset.mid);
                state.bindString(2, asset.name);
                state.bindLong(3, asset.size);
                state.bindInteger(4, asset.localId);
                state.bindInteger(5, asset.handedOff ? 1 : 0);
                state.step();
            } finally {
                state.dispose();
            }
            assets.put(asset.mid, asset.withValid(isAssetFileValid(asset)));
            publish();
            return true;
        } catch (Exception e) {
            FileLog.e(e);
            return false;
        }
    }

    @Nullable
    public AssetRecord selectAssetOnQueue(int mid) {
        try {
            ensureLoaded();
            AssetRecord asset = assets.get(mid);
            if (asset == null) {
                return null;
            }
            AssetRecord refreshed = asset.withValid(isAssetFileValid(asset));
            assets.put(mid, refreshed);
            publish();
            return refreshed;
        } catch (Exception e) {
            FileLog.e(e);
            return null;
        }
    }

    public ArrayList<AssetRecord> selectAllAssetsOnQueue() {
        try {
            ensureLoaded();
            ArrayList<AssetRecord> result = new ArrayList<>();
            for (AssetRecord asset : assets.values()) {
                AssetRecord refreshed = asset.withValid(isAssetFileValid(asset));
                assets.put(asset.mid, refreshed);
                result.add(refreshed);
            }
            publish();
            return result;
        } catch (Exception e) {
            FileLog.e(e);
            return new ArrayList<>();
        }
    }

    public boolean updateAssetLocalIdOnQueue(int mid, int localId) {
        try {
            ensureLoaded();
            AssetRecord asset = assets.get(mid);
            if (asset == null) {
                return false;
            }
            db().executeFast("UPDATE ghost_held_assets SET local_id = " + localId + " WHERE mid = " + mid).stepThis().dispose();
            assets.put(mid, asset.withLocalId(localId));
            publish();
            return true;
        } catch (Exception e) {
            FileLog.e(e);
            return false;
        }
    }

    public boolean markAssetHandedOffOnQueue(int mid) {
        try {
            ensureLoaded();
            AssetRecord asset = assets.get(mid);
            if (asset == null) {
                return true;
            }
            db().executeFast("UPDATE ghost_held_assets SET handed_off = 1 WHERE mid = " + mid).stepThis().dispose();
            assets.put(mid, asset.withHandedOff(true));
            publish();
            return true;
        } catch (Exception e) {
            FileLog.e(e);
            return false;
        }
    }

    public void deleteHeldWithAssetOnQueue(int mid) {
        deleteOnQueue(mid);
        deleteAssetOnQueue(mid);
    }

    public void deleteAssetOnQueue(int mid) {
        try {
            ensureLoaded();
            AssetRecord asset = assets.get(mid);
            db().executeFast("DELETE FROM ghost_held_assets WHERE mid = " + mid).stepThis().dispose();
            assets.remove(mid);
            if (asset != null) {
                File file = resolveAssetFile(asset.name);
                if (file != null && !deleteQuietly(file)) {
                    FileLog.e("ghostHold: could not delete private asset " + asset.name + " for account " + account);
                }
            }
            publish();
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    public void discardUncommittedAssetOnQueue(AssetRecord asset) {
        if (asset == null) {
            return;
        }
        File file = resolveAssetFile(asset.name);
        if (file != null) {
            deleteQuietly(file);
        }
    }

    public void sweepAssetsOnQueue() {
        try {
            ensureLoaded();
            ArrayList<Integer> aborted = new ArrayList<>();
            for (AssetRecord asset : new ArrayList<>(assets.values())) {
                HeldRecord held = master.get(asset.mid);
                if (held == null && !asset.handedOff) {
                    aborted.add(asset.mid);
                }
            }
            for (int mid : aborted) {
                deleteAssetOnQueue(mid);
            }
            File dir = assetDirectory();
            File[] files = dir.listFiles();
            if (files == null) {
                return;
            }
            HashSet<String> owned = new HashSet<>();
            for (AssetRecord asset : assets.values()) {
                owned.add(asset.name);
            }
            for (File file : files) {
                if (file.isFile() && !owned.contains(file.getName())) {
                    deleteQuietly(file);
                }
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    @Nullable
    public File assetFileOnQueue(AssetRecord asset) {
        return asset == null ? null : resolveAssetFile(asset.name);
    }

    private File assetDirectory() {
        return new File(new File(ApplicationLoader.getFilesDirFixed(), "ghosthold"), Integer.toString(account));
    }

    @Nullable
    private File resolveAssetFile(String name) {
        if (name == null || name.isEmpty() || name.contains("/") || name.contains("\\") || name.contains("..")) {
            return null;
        }
        try {
            File dir = assetDirectory().getCanonicalFile();
            File file = new File(dir, name).getCanonicalFile();
            return dir.equals(file.getParentFile()) ? file : null;
        } catch (Exception e) {
            FileLog.e(e);
            return null;
        }
    }

    private boolean isAssetFileValid(AssetRecord asset) {
        File file = resolveAssetFile(asset.name);
        return file != null && file.isFile() && asset.size > 0 && file.length() == asset.size;
    }

    private void deleteAssetDirectoryOnQueue() {
        File dir = assetDirectory();
        File[] files = dir.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isFile()) {
                    deleteQuietly(file);
                }
            }
        }
        deleteQuietly(dir);
        File parent = dir.getParentFile();
        if (parent != null) {
            File[] remaining = parent.listFiles();
            if (remaining != null && remaining.length == 0) {
                deleteQuietly(parent);
            }
        }
    }

    public boolean insertOnQueue(HeldRecord rec) {
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
            return true;
        } catch (Exception e) {
            FileLog.e(e);
            return false;
        }
    }

    public boolean updateStateOnQueue(int mid, int newState) {
        try {
            ensureLoaded();
            HeldRecord old = master.get(mid);
            if (old == null) {
                return false;
            }
            db().executeFast("UPDATE ghost_held SET state = " + newState + " WHERE mid = " + mid).stepThis().dispose();
            master.put(mid, old.withState(newState));
            publish();
            return true;
        } catch (Exception e) {
            FileLog.e(e);
            return false;
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
            StringBuilder in = new StringBuilder();
            for (int mid : mids) {
                if (in.length() > 0) {
                    in.append(',');
                }
                in.append(mid);
            }
            // Durable delete first, then drop from the in-memory snapshot. If the SQL
            // throws we keep master intact so the rows stay visible and consistent
            // with what is still on disk, rather than vanishing until the next launch.
            db().executeFast("DELETE FROM ghost_held WHERE mid IN(" + in + ")").stepThis().dispose();
            boolean changed = false;
            for (int mid : mids) {
                if (master.remove(mid) != null) {
                    changed = true;
                }
            }
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
            return new ArrayList<>(master.values());
        } catch (Exception e) {
            // Fail closed: if the ownership gate could not run (e.g. the purge DELETE
            // threw), never return the in-memory view -- it may still hold the previous
            // owner's rows. An empty snapshot is safe; a stale one leaks.
            FileLog.e(e);
            return new ArrayList<>();
        }
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
            return master.get(mid);
        } catch (Exception e) {
            // Fail closed, as in selectAllOnQueue: a failed ownership gate must return
            // null, not a row that may belong to the previous owner.
            FileLog.e(e);
            return null;
        }
    }

    /**
     * True if a row for {@code mid} is present; call on the queue. Unlike
     * {@link #selectOnQueue}, this does not swallow a store/ownership error -- it lets
     * {@link #ensureLoaded()} throw so the caller can tell a row that is genuinely
     * absent from one it simply could not read. A caller that would take a destructive
     * action on "absent" (cancelling a handed-off twin) must not treat a transient read
     * failure as a deletion.
     */
    public boolean isPresentOnQueue(int mid) throws SQLiteException {
        ensureLoaded();
        return master.containsKey(mid);
    }

    /** Count of held rows for a dialog; safe to read off-queue (published snapshot). */
    public int cachedCountForDialog(long dialogId) {
        List<HeldRecord> list = byDialog.get(dialogId);
        if (list == null) {
            return 0;
        }
        // Count only HELD rows, matching the render injection: a FLUSHING row is
        // mid-handoff and is not shown as held, so counting it would leave the
        // Scheduled badge one ahead of the visible held items.
        int n = 0;
        for (HeldRecord r : list) {
            if (r.state == STATE_HELD) {
                n++;
            }
        }
        return n;
    }

    /**
     * Closes and deletes this account's database file and clears the in-memory view.
     * The rows and owner stamp are purged in place first and the WAL is checkpointed
     * into the main file, so the logout invalidation holds even if the file cannot be
     * unlinked; the WAL/SHM sidecars are removed only once the main file is gone.
     * Called on logout so a re-login on the same account never inherits a previous
     * session's held messages. Posted via {@link #postTeardown()}; it is the one queue
     * op that bypasses the ownership gate.
     */
    private void deleteDatabaseFileOnQueue() {
        File dir = ApplicationLoader.getFilesDirFixed();
        File dbFile = new File(dir, "ghosthold_" + account + ".db");
        synchronized (dbLock) {
            if (database == null && dbFile.exists()) {
                // The file survives from a session this store instance never opened (a
                // teardown that never ran before this one, a crash between sessions). We
                // still have to purge it in place: if the unlink below then fails, a
                // same-user relogin would otherwise load its rows (enforceOwner only
                // purges on a mismatch, and re-login as the same user is not one). Open
                // it on demand so the purge has a connection to work with.
                try {
                    db();
                } catch (Exception e) {
                    // Accepted residual: if the DB is BOTH unopenable here AND the unlink
                    // below then fails, neither the in-place purge nor the file removal
                    // runs, so the owner stamp and rows survive and a same-user relogin
                    // can reload them. Closing it needs an out-of-band tombstone forcing a
                    // purge on next open regardless of owner -- storage-redesign scope the
                    // safety bundle leaves out, and doubly rare on top. Documented, not fixed.
                    FileLog.e(e);
                }
            }
            if (database != null) {
                // Purge the owner stamp and the rows in-place before we close and unlink.
                // If the unlink below fails (WAL/SHM lock, permission), a later login as
                // the SAME user must still find nothing held. Clear ghost_meta FIRST, in
                // its own try: if the ghost_held delete then throws, a reopen still sees
                // an empty owner (stored == 0) and enforceOwner treats the surviving rows
                // as untrusted and purges them. Sharing one try let a failed first delete
                // skip the second, leaving a valid stamp on top of live rows -- the leak.
                try {
                    database.executeFast("DELETE FROM ghost_meta").stepThis().dispose();
                } catch (Exception e) {
                    FileLog.e(e);
                }
                try {
                    database.executeFast("DELETE FROM ghost_held").stepThis().dispose();
                } catch (Exception e) {
                    FileLog.e(e);
                }
                try {
                    database.executeFast("DELETE FROM ghost_held_assets").stepThis().dispose();
                } catch (Exception e) {
                    FileLog.e(e);
                }
                try {
                    // WAL mode (see db(): journal_mode = WAL): the DELETEs above are
                    // written to the -wal sidecar, not the main file, and this SQLite
                    // wrapper's close() does not checkpoint. Merge them into the main file
                    // now, so the purge survives even if the unlink below fails and the
                    // sidecars are then dropped. Matches the Ayu database cleanup, which
                    // checkpoints FULL before removing sidecars. If this throws (the same
                    // lock that would fail the unlink), the sidecar guard below is the
                    // backstop: it keeps the -wal so a reopen still replays the DELETEs.
                    database.executeFast("PRAGMA wal_checkpoint(FULL)").stepThis().dispose();
                } catch (Exception e) {
                    FileLog.e(e);
                }
                try {
                    database.close();
                } catch (Exception e) {
                    FileLog.e(e);
                }
                database = null;
            }
        }
        boolean gone = deleteQuietly(dbFile);
        if (gone) {
            // Only drop the sidecars once the main file is gone. If it survived (a WAL/SHM
            // lock or permission failure) the -wal may still carry DELETEs the checkpoint
            // above could not merge; removing it then would leave the old rows and owner
            // stamp in the surviving main file, which a same-user reopen would reload --
            // the exact leak this teardown exists to prevent. Keeping the sidecars lets
            // that reopen replay the purge instead.
            deleteQuietly(new File(dir, "ghosthold_" + account + ".db-wal"));
            deleteQuietly(new File(dir, "ghosthold_" + account + ".db-shm"));
        }
        deleteAssetDirectoryOnQueue();
        master.clear();
        assets.clear();
        loaded = false;
        // Drop ownership so no off-queue reader trusts the snapshot until the next
        // activated open re-stamps it. If the main file could not be removed, the
        // stale rows still cannot leak: they were already purged in-place above, and
        // the owner stamp is gone too, so neither a same-user nor a different-user
        // reopen can load them.
        snapshotOwner = 0;
        // Invalidate any batch collected before this teardown. Runs on the fork queue,
        // so it is serialised with the migration insert that reads currentGeneration().
        generation++;
        if (!gone && dbFile.exists()) {
            FileLog.e("ghostHold: could not delete db file for account " + account + " on logout; rows already purged in-place");
        }
        publish();
    }

    private static boolean deleteQuietly(File f) {
        try {
            if (f.exists()) {
                return f.delete();
            }
            return true;
        } catch (Exception e) {
            FileLog.e(e);
            return false;
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

    public static final class AssetRecord {
        public final int mid;
        public final String name;
        public final long size;
        public final int localId;
        public final boolean handedOff;
        public final boolean valid;

        public AssetRecord(int mid, String name, long size, int localId, boolean handedOff, boolean valid) {
            this.mid = mid;
            this.name = name;
            this.size = size;
            this.localId = localId;
            this.handedOff = handedOff;
            this.valid = valid;
        }

        AssetRecord withLocalId(int value) {
            return new AssetRecord(mid, name, size, value, handedOff, valid);
        }

        AssetRecord withHandedOff(boolean value) {
            return new AssetRecord(mid, name, size, localId, value, valid);
        }

        AssetRecord withValid(boolean value) {
            return new AssetRecord(mid, name, size, localId, handedOff, value);
        }
    }
}
