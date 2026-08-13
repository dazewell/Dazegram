/*
 * This is the source code of AyuGram for Android.
 *
 * We do not and cannot prevent the use of our code,
 * but be respectful and credit the original author.
 *
 * Copyright @Radolyn, 2023
 */

package com.radolyn.ayugram;

import android.os.Environment;
import android.system.ErrnoException;
import android.system.Os;
import android.text.TextUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.Utilities;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;

import tw.nekomimi.nekogram.utils.AndroidUtil;

// Single owner of the saved-attachments directory. Nobody else names the folder, holds the monitor,
// or touches a staged temp: every read, write, unlink and reset of it goes through one operation
// here, and the directory File, the staging File and the monitor are all private. Operations fall
// into three bands. Band R (resolve/resolveExisting/listNames/dirSize/isUnder/isStoredAttachmentPath)
// is lock-free and never creates, deletes or writes — a lookup has no invariant to protect and every
// caller re-checks with exists()/length() at point of use. Band W runs under the one monitor for the
// short, cheap steps that must be atomic against each other (file predicates, same-filesystem
// renames, and the Room statements a caller supplies through commit(...)); the monitor is never
// acquired by a caller directly, only entered by handing a body to commit(...). Band L is long I/O
// (byte copies, decrypt loops, directory deletes) and holds nothing. Every public method here is
// correct called from any thread with no lock held: the ones that need the monitor take it
// themselves, and a staged temp is only ever handed back as an opaque token, never as a writable
// File.
public abstract class AyuAttachments {
    private static final String SUBFOLDER = "Saved Attachments";
    // A wiped tree is renamed aside to this before the long delete runs; the sweep reclaims any
    // that a crash stranded between the rename and the delete.
    private static final String ASIDE_INFIX = ".old_";
    // A fresh empty replacement folder is prepared under this name in Band L, then renamed into place
    // during a reset; the sweep reclaims one a crash strands between prepare and swap.
    private static final String NEW_INFIX = ".new_";
    // Temps stage in a package-specific sibling of the live folder, OUTSIDE it, on the same
    // filesystem. Outside so a reset (which renames the live folder) can't turn an in-flight temp
    // into "absent", and so neither the history scanner nor the sweep can ever reach one; the
    // package suffix keeps the two installs, which share the public folder, off each other's temps.
    private static final String STAGING_PREFIX = ".attach_staging_";
    private static final File DIR = new File(new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), AyuConstants.APP_NAME), SUBFOLDER);
    private static final File STAGING_DIR = new File(DIR.getParentFile(), STAGING_PREFIX + AyuUtils.getPackageName());
    // Unique to this process. Every temp we stage this run is named with it, so the sweep can drop a
    // dead process's leftovers (a different token) without ever being able to unlink a stage this
    // process still has in flight. Replaces an age cap, which a stalled stage could outlive.
    private static final String INSTANCE_TOKEN = AyuUtils.generateRandomString(8);

    // The one process-wide attachment monitor, private so no caller can hold it directly. The only
    // way under it is to hand a body to commit(...); that body runs a file-op + row-write sequence
    // atomically, so a placed file and the row that records it can't be split by a concurrent delete.
    // A leaf in the lock order (monitor -> AyuData.class), never held across a wait on another queue.
    private static final Object LOCK = new Object();

    // An opaque handle to a temp already written into the staging sibling, holding no lock. The temp
    // File never escapes the owner: a caller receives one of these, then hands it to place(...) or a
    // commit transaction to have it moved into the live folder. It cannot write, read, or name the
    // staged file itself.
    public static final class StagedToken {
        private final File temp;

        private StagedToken(File temp) {
            this.temp = temp;
        }
    }

    // Writes the bytes of a staged temp into an owner-owned stream (a decrypt loop). The owner opens
    // and closes the stream and discards the temp on failure, so a caller never holds the temp File.
    public interface TempWriter {
        void write(OutputStream out) throws Exception;
    }

    // A body run under the monitor by commit(...). The Tx it is handed is the only way to move a
    // staged temp into place or drop it while the lock is held, next to the caller's Room statements.
    public interface Committer {
        void commit(Tx tx);
    }

    // The file primitives available to a commit body, valid only while the monitor is held. Each
    // guards against being called off-lock so a stashed reference can't move a file without the
    // monitor.
    public static final class Tx {
        private Tx() {
        }

        // Place a staged temp at finalName in the live folder, reusing an existing complete file if
        // one is already there. Returns the placed (or reused) file, or null.
        public File place(StagedToken token, String finalName) {
            if (!Thread.holdsLock(LOCK)) {
                FileLog.e("AyuAttachments.Tx.place called off-lock");
                return null;
            }
            return promoteLocked(token == null ? null : token.temp, finalName);
        }

        // Drop a staged temp we decided not to keep (a reuse won).
        public void discard(StagedToken token) {
            if (token != null) {
                discardTemp(token.temp);
            }
        }

        // Unlink a stored attachment path, but only when it canonically resolves to a direct child of
        // our folder. A raw Telegram cache path, or a poisoned "../" traversal, is rejected and left
        // alone. The unlink lives here so no caller ever deletes a stored path itself.
        public void deleteContained(String path) {
            if (!Thread.holdsLock(LOCK)) {
                FileLog.e("AyuAttachments.Tx.deleteContained called off-lock");
                return;
            }
            deleteContainedLocked(path);
        }

        // Swap a Band-L-prepared empty replacement folder in for the live one using renames only. The
        // live folder is renamed aside to a unique name and the replacement is moved into its place;
        // the returned aside handle is deleted by the caller in Band L, so the long delete never runs
        // under the monitor. Returns null when there was no live folder to move. If the live->aside
        // rename fails we abort - drop the (empty) replacement and leave the live folder untouched -
        // rather than recursively deleting the live tree while locked.
        public File swapIn(File replacement) {
            if (!Thread.holdsLock(LOCK)) {
                FileLog.e("AyuAttachments.Tx.swapIn called off-lock");
                return null;
            }
            return swapInLocked(replacement);
        }
    }

    private static final Tx TX = new Tx();

    // Enter the monitor and run body once. The only entry to Band W: body does the caller's reuse
    // lookup, place/discard through the Tx, and Room statements as one critical section a concurrent
    // delete can't split. Correct called from any thread with no lock held.
    public static void commit(Committer body) {
        synchronized (LOCK) {
            body.commit(TX);
        }
    }

    // One-shot reclaim of our own leftovers the first time the owner is touched. Three kinds outlive
    // a process that dies at the wrong moment: staged temps in the staging sibling, an aside tree
    // stranded between Tx.swapIn and its deleteTree (potentially gigabytes nothing will ever look at
    // again), and a prepared replacement stranded between prepareReplacement and the swap. None is
    // retention or pruning of saved data - it only ever removes our own staging/aside/replacement
    // artifacts, once, on no schedule. Posted to globalQueue so the I/O is off the main thread and
    // holds no monitor (Band L).
    static {
        Utilities.globalQueue.postRunnable(AyuAttachments::sweepLeftovers);
    }

    // Band R: resolve a name to a File in the folder. Pure, no I/O — the caller decides what to
    // do with it and re-checks existence itself.
    public static File resolve(String name) {
        return new File(DIR, name);
    }

    // Band R: the exact-name file if it is present and non-empty, else null. No mkdir — a lookup
    // that creates the directory is a mutation wearing a lookup's name.
    public static File resolveExisting(String name) {
        if (TextUtils.isEmpty(name)) {
            return null;
        }
        File f = new File(DIR, name);
        if (f.exists() && f.length() > 0) {
            return f;
        }
        return null;
    }

    // Band R: the folder's entries, or null when it doesn't exist yet.
    public static String[] listNames() {
        if (!DIR.exists()) {
            return null;
        }
        return DIR.list();
    }

    // Band R: total size on disk, 0 when the folder doesn't exist yet.
    public static long dirSize() {
        try {
            if (DIR.exists()) {
                return AndroidUtil.getDirectorySize(DIR);
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        return 0;
    }

    // Band R: is this file one of ours. Anchored on an exact match or the folder plus a
    // separator, so a sibling like "Saved Attachments_old" doesn't match. Cheap, lexical, and only
    // a filter: the destructive unlink re-checks canonically through Tx.deleteContained.
    public static boolean isUnder(File f) {
        if (f == null) {
            return false;
        }
        String prefix = DIR.getAbsolutePath();
        String p = f.getAbsolutePath();
        return p.equals(prefix) || p.startsWith(prefix + File.separator);
    }

    // Band R: fail-closed canonical containment. True only when f canonically resolves to a direct
    // child of the canonical folder, so a "Saved Attachments/../victim" traversal or a symlink that
    // escapes the folder resolves away and is rejected. getAbsolutePath() does not collapse "..",
    // which is why the startsWith test in isUnder can't gate a delete. On any canonicalisation error
    // we return false rather than risk deleting outside the folder.
    private static boolean isContainedChild(File f) {
        if (f == null) {
            return false;
        }
        try {
            File canonicalDir = DIR.getCanonicalFile();
            File parent = f.getCanonicalFile().getParentFile();
            return parent != null && parent.equals(canonicalDir);
        } catch (IOException e) {
            FileLog.e("AyuAttachments.isContainedChild", e);
            return false;
        }
    }

    // Band R: does this stored path already point at one of our saved files rather than a raw
    // Telegram cache path. A substring test on the folder name, matching the revision-backfill
    // guard's prior behaviour exactly.
    public static boolean isStoredAttachmentPath(String path) {
        return path != null && path.contains(SUBFOLDER);
    }

    // Establish a folder (and its .nomedia marker so the gallery skips it). An owner-internal
    // precondition of a write, established inside each write op, never by a caller.
    private static void ensureFolder(File dir) {
        try {
            File nomediaFile = new File(dir, ".nomedia");
            if (dir.exists() || dir.mkdirs()) {
                AndroidUtilities.createEmptyFile(nomediaFile);
            }
            if (!nomediaFile.exists()) {
                File randomFile = new File(dir, AyuUtils.generateRandomString(4));
                AndroidUtilities.createEmptyFile(randomFile);
                if (!randomFile.renameTo(nomediaFile)) {
                    if (!randomFile.delete()) {
                        randomFile.deleteOnExit();
                    }
                    FileLog.e("Failed to rename random .nomedia file to the correct name");
                }
            }
        } catch (Exception e) {
            FileLog.e("AyuAttachments.ensureFolder", e);
        }
    }

    private static void ensureDir() {
        ensureFolder(DIR);
    }

    private static void ensureStagingDir() {
        ensureFolder(STAGING_DIR);
    }

    // A staging temp's name, unique to one stage call and tagged with this process's token.
    private static File newStagingTemp() {
        return new File(STAGING_DIR, INSTANCE_TOKEN + "_" + AyuUtils.generateRandomString(16));
    }

    // Band L: copy (or, on the force/TTL path, move) `source` into a uniquely-named temp in the
    // staging sibling, holding no lock. Returns an opaque token handed to place(...) or a commit
    // transaction, which moves it into the live folder under the monitor. Copy-not-move is preserved:
    // deleteSource stays the only route to renameTo, so an ordinary save leaves the source in
    // Telegram's cache.
    public static StagedToken stage(File source, boolean deleteSource) {
        if (source == null || !source.exists()) {
            return null;
        }
        ensureStagingDir();
        // Ensure the live folder here, in Band L, so the later place under the monitor is renames
        // only and never creates a directory while a main-thread reader waits on the lock.
        ensureDir();
        File temp = newStagingTemp();
        if (AyuUtils.moveOrCopyFile(source, temp, deleteSource)) {
            return new StagedToken(temp);
        }
        discardTemp(temp);
        return null;
    }

    // Band L: write bytes into a fresh staging temp through an owner-owned stream, holding no lock.
    // The owner opens and closes the stream and drops the temp if the writer throws, so the temp File
    // never reaches the caller. Returns an opaque token, or null on failure.
    public static StagedToken stageViaWriter(TempWriter writer) {
        ensureStagingDir();
        // Ensure the live folder in Band L for the same reason as stage(): the place stays renames
        // only under the monitor.
        ensureDir();
        File temp = newStagingTemp();
        try (OutputStream out = new FileOutputStream(temp)) {
            writer.write(out);
            return new StagedToken(temp);
        } catch (Exception e) {
            FileLog.e("AyuAttachments.stageViaWriter", e);
            discardTemp(temp);
            return null;
        }
    }

    // Take the monitor and place a staged temp at finalName in the live folder. For callers with no
    // row to write (decrypt/download reuse); a caller with a row uses commit(...) instead so the
    // place and the insert share one critical section. Correct called from any thread with no lock
    // held.
    public static File place(StagedToken token, String finalName) {
        synchronized (LOCK) {
            return promoteLocked(token == null ? null : token.temp, finalName);
        }
    }

    // Band W: place a staged temp at finalName in the live folder, monitor already held. The live
    // folder was ensured in Band L at stage time, so this does no directory creation. If a complete
    // file already sits there (a concurrent save won the race, or the caller resolved a reuse), keep
    // it and drop the temp. The staging sibling shares the live folder's filesystem, so Os.rename is
    // an atomic same-filesystem move: it stays sub-millisecond even for a large file and replaces a
    // zero-length leftover in one step.
    private static File promoteLocked(File temp, String finalName) {
        File finalFile = new File(DIR, finalName);
        if (finalFile.exists() && finalFile.length() > 0) {
            discardTemp(temp);
            return finalFile;
        }
        if (temp == null || !temp.exists()) {
            return null;
        }
        try {
            Os.rename(temp.getAbsolutePath(), finalFile.getAbsolutePath());
            return finalFile;
        } catch (ErrnoException e) {
            // same-filesystem by construction, so this is exceptional; fall back to a plain
            // rename and, failing that, give up cleanly rather than leaving a half-placed file
            if (temp.renameTo(finalFile)) {
                return finalFile;
            }
            FileLog.e("AyuAttachments.promoteLocked", e);
            discardTemp(temp);
            return null;
        }
    }

    // Drop a staged temp we are not going to keep (reuse won, or a write failed). The temp name is
    // unique to one stage call, so no other thread can name it - no coordination needed.
    private static void discardTemp(File temp) {
        if (temp != null && temp.exists() && !temp.delete()) {
            temp.deleteOnExit();
        }
    }

    // Band W: the canonical-containment-checked unlink behind Tx.deleteContained, monitor already
    // held. Fails closed on anything that doesn't resolve to a direct child of our folder.
    private static void deleteContainedLocked(String path) {
        if (TextUtils.isEmpty(path)) {
            return;
        }
        File f = new File(path);
        if (!isContainedChild(f)) {
            return;
        }
        try {
            if (f.exists() && !f.delete()) {
                f.deleteOnExit();
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    // Band L: build a fresh empty replacement folder (with its .nomedia) alongside the live one, so
    // the reset's Band W step is renames only and creates no directory under the monitor. The name
    // carries NEW_INFIX so the sweep reclaims one a crash strands between here and the swap.
    public static File prepareReplacement() {
        File replacement = new File(DIR.getParentFile(), SUBFOLDER + NEW_INFIX + AyuUtils.generateRandomString(16));
        ensureFolder(replacement);
        return replacement;
    }

    // Band W: the swap behind Tx.swapIn, monitor already held. Renames only.
    private static File swapInLocked(File replacement) {
        File aside = null;
        if (DIR.exists()) {
            File candidate = new File(DIR.getParentFile(), SUBFOLDER + ASIDE_INFIX + AyuUtils.generateRandomString(16));
            if (DIR.renameTo(candidate)) {
                aside = candidate;
            } else {
                // same-parent rename shouldn't fail; if it does, abort rather than deleting the live
                // tree under the monitor. Drop the unused empty replacement, leave the live folder.
                discardEmptyDir(replacement);
                return null;
            }
        }
        if (replacement != null && !replacement.renameTo(DIR)) {
            // live folder is now absent; the next stage's ensureDir recreates it and the sweep
            // reclaims the stranded replacement by its NEW_INFIX name
            FileLog.e("AyuAttachments.swapIn: failed to move replacement into place");
        }
        return aside;
    }

    // Drop a freshly-prepared empty replacement (only its .nomedia inside), monitor held. Cheap: two
    // file deletes, never a recursive tree walk.
    private static void discardEmptyDir(File dir) {
        if (dir == null) {
            return;
        }
        File nomedia = new File(dir, ".nomedia");
        if (nomedia.exists() && !nomedia.delete()) {
            nomedia.deleteOnExit();
        }
        if (dir.exists() && !dir.delete()) {
            dir.deleteOnExit();
        }
    }

    // Band L: delete an aside tree returned by Tx.swapIn, holding no lock. The aside name is unique
    // to one reset, so nothing else can name it - no coordination needed.
    public static void deleteTree(File aside) {
        if (aside == null) {
            return;
        }
        tw.nekomimi.nekogram.utils.FileUtil.deleteDirectory(aside);
    }

    // Band L: the startup reclaim posted from the static initializer. Holds no monitor. Aside and
    // replacement trees go unconditionally - deleting an aside is exactly what its own deleteTree
    // would have done, and a stranded replacement is a fresh empty folder, so racing a concurrent
    // reset is harmless. Staging temps go only when they carry another (dead) process's token, so
    // this can never unlink one this process still has in flight.
    private static void sweepLeftovers() {
        try {
            File parent = DIR.getParentFile();
            if (parent != null) {
                File[] siblings = parent.listFiles();
                if (siblings != null) {
                    for (File f : siblings) {
                        if (f.isDirectory() && (f.getName().startsWith(SUBFOLDER + ASIDE_INFIX) || f.getName().startsWith(SUBFOLDER + NEW_INFIX))) {
                            tw.nekomimi.nekogram.utils.FileUtil.deleteDirectory(f);
                        }
                    }
                }
            }
            File[] entries = STAGING_DIR.listFiles();
            if (entries != null) {
                String ours = INSTANCE_TOKEN + "_";
                for (File f : entries) {
                    if (f.isFile() && !f.getName().startsWith(ours) && !f.getName().equals(".nomedia")) {
                        if (!f.delete()) {
                            f.deleteOnExit();
                        }
                    }
                }
            }
        } catch (Exception e) {
            FileLog.e("AyuAttachments.sweepLeftovers", e);
        }
    }
}
