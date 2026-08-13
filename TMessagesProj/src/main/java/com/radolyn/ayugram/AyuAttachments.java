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

import tw.nekomimi.nekogram.utils.AndroidUtil;

// Single owner of the saved-attachments directory. Nobody else names the folder: every read,
// write, unlink and reset of it goes through one of the operations here. Operations fall into
// three bands. Band R (resolve/resolveExisting/listNames/dirSize/isUnder) is lock-free and
// never creates, deletes or writes — a lookup has no invariant to protect and every caller
// re-checks with exists()/length() at point of use. Band W holds the one monitor for the
// short, cheap steps that must be atomic against each other (file predicates, same-directory
// renames, and — in the controller, which shares this monitor — Room statements). Band L is
// long I/O (byte copies, decrypt loops, directory deletes) and holds nothing.
public abstract class AyuAttachments {
    private static final String SUBFOLDER = "Saved Attachments";
    // A wiped tree is renamed aside to this before the long delete runs; the sweep reclaims any
    // that a crash stranded between the rename and the delete.
    private static final String ASIDE_INFIX = ".old_";
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

    // The one process-wide attachment monitor. The controller synchronises its row-consistency
    // sequences on this same object so a file op and the row that records it can't be split by a
    // concurrent delete. A leaf in the lock order (attachmentMonitor -> AyuData.class), never
    // held across a wait on another queue.
    public static final Object LOCK = new Object();

    // One-shot reclaim of our own leftovers the first time the owner is touched. Two kinds outlive
    // a process that dies at the wrong moment: staged temps in the staging sibling, and an aside
    // tree stranded between renameAsideAndRecreate and its deleteTree (potentially gigabytes nothing
    // will ever look at again). Neither is retention or pruning of saved data - it only ever removes
    // our own staging/aside artifacts, once, on no schedule. Posted to globalQueue so the I/O is off
    // the main thread and holds no monitor (Band L).
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
    // separator, so a sibling like "Saved Attachments_old" doesn't match.
    public static boolean isUnder(File f) {
        if (f == null) {
            return false;
        }
        String prefix = DIR.getAbsolutePath();
        String p = f.getAbsolutePath();
        return p.equals(prefix) || p.startsWith(prefix + File.separator);
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
    // staging sibling, holding no lock. The temp is handed to promote(...) which places it into the
    // live folder under the monitor. Copy-not-move is preserved: deleteSource stays the only route
    // to renameTo, so an ordinary save leaves the source in Telegram's cache.
    public static File stage(File source, boolean deleteSource) {
        if (source == null || !source.exists()) {
            return null;
        }
        ensureStagingDir();
        File temp = newStagingTemp();
        if (AyuUtils.moveOrCopyFile(source, temp, deleteSource)) {
            return temp;
        }
        discard(temp);
        return null;
    }

    // Band L: a fresh unique temp File in the staging sibling for a caller to write into (a decrypt
    // loop), holding no lock. The written temp is handed to promote(...).
    public static File newTemp() {
        ensureStagingDir();
        return newStagingTemp();
    }

    // Band W: place a staged temp at finalName in the live folder under the monitor. If a complete
    // file already sits there (a concurrent save won the race, or the caller resolved a reuse), keep
    // it and drop the temp. The staging sibling shares the live folder's filesystem, so Os.rename is
    // an atomic same-filesystem move: it stays sub-millisecond even for a large file and replaces a
    // zero-length leftover in one step.
    public static File promote(File temp, String finalName) {
        synchronized (LOCK) {
            ensureDir();
            File finalFile = new File(DIR, finalName);
            if (finalFile.exists() && finalFile.length() > 0) {
                discard(temp);
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
                FileLog.e("AyuAttachments.promote", e);
                discard(temp);
                return null;
            }
        }
    }

    // Drop a staged temp we are not going to keep (reuse won, or a write failed). The temp name is
    // unique to one stage call, so no other thread can name it - no coordination needed.
    public static void discard(File temp) {
        if (temp != null && temp.exists() && !temp.delete()) {
            temp.deleteOnExit();
        }
    }

    // Band W: move the live folder aside to a unique name and put a fresh empty one in its place -
    // both cheap same-parent operations under the monitor. The caller deletes the returned aside
    // tree in Band L, unlocked, so a multi-gigabyte delete never blocks a thread waiting on the
    // monitor (a ChatMessageCell bind, another account's save). Returns the aside directory, or
    // null when there was nothing to move or the rename fell back to an in-place delete.
    public static File renameAsideAndRecreate() {
        synchronized (LOCK) {
            File aside = null;
            if (DIR.exists()) {
                File candidate = new File(DIR.getParentFile(), SUBFOLDER + ASIDE_INFIX + AyuUtils.generateRandomString(16));
                if (DIR.renameTo(candidate)) {
                    aside = candidate;
                } else {
                    // same-parent rename shouldn't fail, but if it does don't strand the tree:
                    // delete in place, still under the lock (the rare, unavoidable long hold)
                    tw.nekomimi.nekogram.utils.FileUtil.deleteDirectory(DIR);
                }
            }
            ensureDir();
            return aside;
        }
    }

    // Band L: delete an aside tree returned by renameAsideAndRecreate, holding no lock. The aside
    // name is unique to one reset, so nothing else can name it - no coordination needed.
    public static void deleteTree(File aside) {
        if (aside == null) {
            return;
        }
        tw.nekomimi.nekogram.utils.FileUtil.deleteDirectory(aside);
    }

    // Band L: the startup reclaim posted from the static initializer. Holds no monitor. Aside trees
    // go unconditionally - deleting one is exactly what its own deleteTree would have done, so racing
    // a concurrent reset is harmless. Staging temps go only when they carry another (dead) process's
    // token, so this can never unlink one this process still has in flight.
    private static void sweepLeftovers() {
        try {
            File parent = DIR.getParentFile();
            if (parent != null) {
                File[] siblings = parent.listFiles();
                if (siblings != null) {
                    for (File f : siblings) {
                        if (f.isDirectory() && f.getName().startsWith(SUBFOLDER + ASIDE_INFIX)) {
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
