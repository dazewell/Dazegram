package xyz.nextalone.nagram.helper;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.util.AtomicFile;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.SvgHelper;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.Theme;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

// NagramX: shared pattern for the live Monet-colour wallpaper tile (tile 0). Pattern
// id, magnitude, motion and the mask all live in ONE android.util.AtomicFile
// ("monet_pattern.bin": header + PNG payload). AtomicFile gives the crash-safe swap,
// so there's no sidecar pointer to keep in step, no temp/rename of our own, nothing
// to roll back. The live Monet colour is re-read on every wallpaper reload, so the
// pattern rides on whatever Monet/Extera/Solid variant and day/night mode is active.
// Global, not per-account.
public final class MonetPatternHelper {

    // Immutable header tuple published wholesale into a volatile field — cheap UI
    // state only (id, magnitude, motion); the mask is never carried here.
    public static final class Record {
        public final long patternId;
        public final float intensity; // magnitude only, always >= 0
        public final boolean motion;

        Record(long patternId, float intensity, boolean motion) {
            this.patternId = patternId;
            // clamp to [0,1]: intensity is a flat-wallpaper fraction, and a bad
            // persisted value must not overflow the composite's 0..255 alpha
            this.intensity = Math.min(1f, Math.abs(intensity));
            this.motion = motion;
        }
    }

    public interface ResultCallback {
        void run(boolean success);
    }

    private static final String FILE_NAME = "monet_pattern.bin";
    private static final int MAGIC = 0x4D4E5054; // 'MNPT'
    private static final int VERSION = 1;
    // Reserved patternId that marks a cleared record. Server pattern ids used here are
    // always positive, so 0 can never collide with a real pattern. A tombstone is a
    // valid, fully-written artifact (header, no PNG) that means "no pattern" — so Clear
    // never has to delete backing files and can't half-remove AtomicFile's own .new/.bak.
    private static final long TOMBSTONE_ID = 0;

    private static volatile Record record;
    private static volatile boolean loaded;

    private MonetPatternHelper() {
    }

    private static AtomicFile atomicFile() {
        return new AtomicFile(new File(ApplicationLoader.getFilesDirFixed(), FILE_NAME));
    }

    // Read+validate the header off a stream, leaving it at the PNG payload.
    // DataInputStream passes these reads straight through (no readahead), so the
    // caller keeps decoding the mask from the same stream. Null on bad magic/version.
    private static Record parseHeader(InputStream is) throws IOException {
        DataInputStream in = new DataInputStream(is);
        if (in.readInt() != MAGIC) {
            return null;
        }
        if (in.readInt() != VERSION) {
            return null;
        }
        long patternId = in.readLong();
        float intensity = in.readFloat();
        boolean motion = in.readBoolean();
        return new Record(patternId, intensity, motion);
    }

    // Header-only read that always closes the stream; for cheap UI state and verify.
    private static Record readHeader(InputStream is) {
        try {
            return parseHeader(is);
        } catch (Throwable e) {
            FileLog.e(e);
            return null;
        } finally {
            try {
                is.close();
            } catch (Exception ignore) {
            }
        }
    }

    // First-use load of the volatile header cache. openRead() rolls back any
    // half-written artifact from a crash, so we see a complete tuple, a tombstone, or
    // nothing. A tombstone (id == 0) means an explicit clear, so the cache stays null.
    // Under the class monitor so apply/clear can't interleave with the open.
    private static void ensureLoaded() {
        if (loaded) {
            return;
        }
        synchronized (MonetPatternHelper.class) {
            if (loaded) {
                return;
            }
            AtomicFile af = atomicFile();
            if (af.getBaseFile().exists()) {
                try {
                    Record r = readHeader(af.openRead());
                    record = r != null && r.patternId != TOMBSTONE_ID ? r : null;
                } catch (Throwable e) {
                    FileLog.e(e);
                }
            }
            loaded = true;
        }
    }

    public static Record getRecord() {
        ensureLoaded();
        return record;
    }

    public static boolean isMotion() {
        Record r = getRecord();
        return r != null && r.motion;
    }

    // Persist a chosen pattern (or clear when pattern is null) off the UI thread on
    // themeQueue — the serial queue the wallpaper loader uses, so apply/clear stay
    // ordered — then report success/failure back on the UI thread.
    public static void applyAsync(int account, TLRPC.TL_wallPaper pattern, float intensity, boolean motion, ResultCallback callback) {
        Utilities.themeQueue.postRunnable(() -> {
            boolean ok = pattern == null || pattern.document == null
                    ? doClear()
                    : doApply(account, pattern, intensity, motion);
            AndroidUtilities.runOnUIThread(() -> callback.run(ok));
        });
    }

    // Write header + optional PNG mask as one AtomicFile and confirm it landed. Apply
    // passes the mask; tombstone Clear passes null (header only). startWrite() stages a
    // new file and keeps the current one until finishWrite() atomically swaps it, so any
    // failure leaves the previous artifact untouched. We fsync the payload ourselves via
    // getFD().sync() before finishWrite (AtomicFile's own sync only logs), so a durability
    // failure throws and fails the write instead of masquerading as success. finishWrite()
    // is void and swallows a failed rename, so we verify by re-reading the base header and
    // comparing the FULL tuple — accepting on patternId alone would treat a silently-failed
    // reapply of the same pattern with new intensity/motion as success. Caller holds the
    // class monitor.
    private static boolean writeVerified(AtomicFile af, Record rec, Bitmap mask) {
        FileOutputStream fos = null;
        try {
            fos = af.startWrite();
            DataOutputStream out = new DataOutputStream(fos);
            out.writeInt(MAGIC);
            out.writeInt(VERSION);
            out.writeLong(rec.patternId);
            out.writeFloat(rec.intensity);
            out.writeBoolean(rec.motion);
            out.flush();
            if (mask != null) {
                Bitmap argb = mask.copy(Bitmap.Config.ARGB_8888, false);
                // copy can fail under memory pressure and compress can return false;
                // either way fail the staged write so the old artifact survives
                boolean ok = argb != null && argb.compress(Bitmap.CompressFormat.PNG, 100, fos);
                if (argb != null) {
                    argb.recycle();
                }
                if (!ok) {
                    af.failWrite(fos);
                    return false;
                }
            }
            fos.flush();
            fos.getFD().sync(); // observable fsync: throws if the payload isn't durable
            af.finishWrite(fos); // close + atomic swap
        } catch (Throwable e) {
            FileLog.e(e);
            if (fos != null) {
                try {
                    af.failWrite(fos);
                } catch (Throwable ignore) {
                }
            }
            return false;
        }
        return headerMatches(af, rec);
    }

    // Read the base header straight off the file (bypassing openRead()'s rollback, which
    // on older devices could restore a leftover backup over our fresh write) and confirm
    // it equals the tuple we just wrote — id, exact magnitude bits, motion.
    private static boolean headerMatches(AtomicFile af, Record rec) {
        Record actual = readBaseHeader(af);
        return actual != null
                && actual.patternId == rec.patternId
                && Float.floatToIntBits(actual.intensity) == Float.floatToIntBits(rec.intensity)
                && actual.motion == rec.motion;
    }

    // Persist a freshly chosen pattern. Publishes only after the artifact is durably
    // written and verified, so a failed write leaves the previous working pattern intact.
    // Runs on themeQueue.
    private static boolean doApply(int account, TLRPC.TL_wallPaper pattern, float intensity, boolean motion) {
        ensureLoaded();
        int w = Math.min(AndroidUtilities.displaySize.x, AndroidUtilities.displaySize.y);
        int h = Math.max(AndroidUtilities.displaySize.x, AndroidUtilities.displaySize.y);
        File doc = FileLoader.getInstance(account).getPathToAttach(pattern.document, true);
        Bitmap mask = SvgHelper.getBitmap(doc, w, h, false, SvgHelper.ScaleMode.ByWidth);
        if (mask == null) {
            return false;
        }
        Record rec = new Record(pattern.id, intensity, motion);
        AtomicFile af = atomicFile();
        try {
            synchronized (MonetPatternHelper.class) {
                if (!writeVerified(af, rec, mask)) {
                    return false;
                }
                record = rec;
                loaded = true;
                return true;
            }
        } finally {
            mask.recycle();
        }
    }

    // Clear the pattern by writing a TOMBSTONE (id 0, header only) through the same
    // AtomicFile transaction — never a delete. Process death then leaves either the old
    // pattern or a valid tombstone, never a half-removed set of AtomicFile backing files.
    // Publishes the empty record only after the tombstone is durably written and verified.
    // Runs on themeQueue.
    private static boolean doClear() {
        ensureLoaded();
        AtomicFile af = atomicFile();
        Record tombstone = new Record(TOMBSTONE_ID, 0f, false);
        synchronized (MonetPatternHelper.class) {
            if (!writeVerified(af, tombstone, null)) {
                return false;
            }
            record = null;
            loaded = true;
            return true;
        }
    }

    // Header read straight off the base file, bypassing openRead()'s rollback. Only
    // safe right after finishWrite() has put the complete content in the base file.
    private static Record readBaseHeader(AtomicFile af) {
        try {
            return readHeader(new FileInputStream(af.getBaseFile()));
        } catch (Throwable e) {
            FileLog.e(e);
            return null;
        }
    }

    // Composite the shared pattern mask over the live flat Monet colour at the given
    // size. Null (fall back to plain colour) when the theme isn't Monet, no pattern
    // is set, or the mask can't be read. The intensity used for alpha and the mask
    // bytes come from the SAME open descriptor, so an apply that swaps the file
    // mid-render never pairs one tuple's metadata with another's mask. The monitor is
    // held only around open + header read; the PNG is decoded lock-free from that
    // descriptor (an open FD keeps reading the old inode even if the path is swapped).
    public static Drawable buildComposite(Theme.ThemeInfo theme, int backgroundColor, int width, int height) {
        if (theme == null || !theme.isMonet() || width <= 0 || height <= 0) {
            return null;
        }
        if (getRecord() == null) {
            return null;
        }
        int sampleSize = computeSampleSize(width);
        FileInputStream fis = null;
        Bitmap mask = null;
        Bitmap result = null;
        try {
            Record tuple;
            AtomicFile af = atomicFile();
            synchronized (MonetPatternHelper.class) {
                if (!af.getBaseFile().exists()) {
                    return null;
                }
                fis = af.openRead();
                tuple = parseHeader(fis);
            }
            if (tuple == null || tuple.patternId == TOMBSTONE_ID) {
                return null;
            }
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sampleSize;
            mask = BitmapFactory.decodeStream(fis, null, opts);
            if (mask == null) {
                return null;
            }
            result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(result);
            canvas.drawColor(backgroundColor);
            Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
            paint.setColorFilter(new PorterDuffColorFilter(AndroidUtilities.getPatternColor(backgroundColor), PorterDuff.Mode.SRC_IN));
            paint.setAlpha((int) (255 * tuple.intensity));
            canvas.drawBitmap(mask, null, new Rect(0, 0, width, height), paint);
            BitmapDrawable drawable = new BitmapDrawable(ApplicationLoader.applicationContext.getResources(), result);
            drawable.setFilterBitmap(true);
            result = null; // ownership handed to the drawable; don't recycle below
            return drawable;
        } catch (Throwable e) {
            FileLog.e(e);
            return null;
        } finally {
            if (mask != null) {
                mask.recycle();
            }
            if (result != null) {
                result.recycle();
            }
            if (fis != null) {
                try {
                    fis.close();
                } catch (Exception ignore) {
                }
            }
        }
    }

    // Largest power-of-two subsample that still leaves the decoded mask at least as
    // wide as the target, so the tile-0 thumbnail never decodes the full-screen mask.
    // The bounds-only pass reads no pixels.
    private static int computeSampleSize(int targetWidth) {
        FileInputStream fis = null;
        try {
            AtomicFile af = atomicFile();
            synchronized (MonetPatternHelper.class) {
                if (!af.getBaseFile().exists()) {
                    return 1;
                }
                fis = af.openRead();
                parseHeader(fis); // advance past the header to the PNG bounds
            }
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inJustDecodeBounds = true;
            BitmapFactory.decodeStream(fis, null, opts);
            int maskWidth = opts.outWidth;
            int sample = 1;
            while (maskWidth / (sample * 2) >= targetWidth) {
                sample *= 2;
            }
            return sample;
        } catch (Throwable e) {
            return 1;
        } finally {
            if (fis != null) {
                try {
                    fis.close();
                } catch (Exception ignore) {
                }
            }
        }
    }

    // Full-screen live-chat composite, built on wallpaper reload.
    public static Drawable buildComposite(Theme.ThemeInfo theme, int backgroundColor) {
        int w = Math.min(AndroidUtilities.displaySize.x, AndroidUtilities.displaySize.y);
        int h = Math.max(AndroidUtilities.displaySize.x, AndroidUtilities.displaySize.y);
        return buildComposite(theme, backgroundColor, w, h);
    }

    // Small composite for the wallpaper-list tile-0 thumbnail: keeps the screen
    // aspect ratio but caps the width so binding the cell doesn't allocate a
    // full-screen bitmap.
    public static Drawable buildThumbComposite(Theme.ThemeInfo theme, int backgroundColor) {
        int dispW = Math.min(AndroidUtilities.displaySize.x, AndroidUtilities.displaySize.y);
        int dispH = Math.max(AndroidUtilities.displaySize.x, AndroidUtilities.displaySize.y);
        if (dispW <= 0 || dispH <= 0) {
            return null;
        }
        int w = AndroidUtilities.dp(180);
        int h = (int) ((long) w * dispH / dispW);
        return buildComposite(theme, backgroundColor, w, h);
    }
}
