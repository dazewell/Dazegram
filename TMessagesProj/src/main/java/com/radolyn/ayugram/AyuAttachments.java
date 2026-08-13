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
import android.text.TextUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;

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
    private static final File DIR = new File(new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), AyuConstants.APP_NAME), SUBFOLDER);

    // The one process-wide attachment monitor. The controller synchronises its row-consistency
    // sequences on this same object so a file op and the row that records it can't be split by a
    // concurrent delete. A leaf in the lock order (attachmentMonitor -> AyuData.class), never
    // held across a wait on another queue.
    public static final Object LOCK = new Object();

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

    // Establish the folder (and its .nomedia marker so the gallery skips it). A precondition of
    // any write. Commit 2 moves the only callers inside owner write ops; until then the
    // controller calls it once at construction so the ordinary save path (not yet owner-routed)
    // has its directory.
    public static void ensureDir() {
        try {
            File nomediaFile = new File(DIR, ".nomedia");
            if (DIR.exists() || DIR.mkdirs()) {
                AndroidUtilities.createEmptyFile(nomediaFile);
            }
            if (!nomediaFile.exists()) {
                File randomFile = new File(DIR, AyuUtils.generateRandomString(4));
                AndroidUtilities.createEmptyFile(randomFile);
                if (!randomFile.renameTo(nomediaFile)) {
                    if (!randomFile.delete()) {
                        randomFile.deleteOnExit();
                    }
                    FileLog.e("Failed to rename random .nomedia file to the correct name");
                }
            }
        } catch (Exception e) {
            FileLog.e("AyuAttachments.ensureDir", e);
        }
    }

    // Band W (transitional): copy a source file into the folder under the monitor, leaving the
    // source alone. Callers pre-check that {@code from} exists. Commit 2 replaces this with
    // stage-and-rename so the byte copy holds no lock.
    public static File copyInto(File from, File to) {
        synchronized (LOCK) {
            ensureDir();
            if (from == null || !from.exists()) {
                return null;
            }
            try {
                if (AndroidUtilities.copyFile(from, to)) {
                    return to;
                }
            } catch (Exception e) {
                FileLog.e("AyuAttachments.copyInto", e);
            }
            return null;
        }
    }

    // Band W: wipe the folder and recreate it empty. Commit 3 replaces this with rename-aside so
    // the long delete runs unlocked.
    public static void wipe() {
        synchronized (LOCK) {
            tw.nekomimi.nekogram.utils.FileUtil.deleteDirectory(DIR);
            ensureDir();
        }
    }
}
