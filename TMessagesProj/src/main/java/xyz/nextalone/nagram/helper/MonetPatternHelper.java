package xyz.nextalone.nagram.helper;

import android.content.res.Resources;
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
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

// NagramX: shared pattern for the live Monet-colour wallpaper tile (tile 0). Pattern
// id, magnitude, motion and the mask all live in ONE android.util.AtomicFile
// ("monet_pattern.bin": header + PNG payload). AtomicFile gives the crash-safe swap,
// so there's no sidecar pointer to keep in step, no temp/rename of our own, nothing
// to roll back. The live Monet colour is re-read on every wallpaper reload, so the
// pattern rides on whatever Monet/Extera variant and day/night mode is active.
// Global, not per-account.
public final class MonetPatternHelper {

    // Immutable header tuple published wholesale into a volatile field — cheap UI
    // state only (generation, id, magnitude, motion); the mask is never carried here.
    public static final class Record {
        public final long generation; // per-write nonce; distinguishes otherwise-identical writes
        public final long patternId;
        public final float intensity; // magnitude only, always >= 0
        public final boolean motion;

        Record(long generation, long patternId, float intensity, boolean motion) {
            this.generation = generation;
            this.patternId = patternId;
            // clamp to [0,1]: intensity is a flat-wallpaper fraction, and a bad
            // persisted value must not overflow the composite's 0..255 alpha.
            // A corrupt artifact can decode to NaN/Infinity, which slip past
            // abs()/min(), so map any non-finite value to 0 (no pattern tint).
            this.intensity = Float.isFinite(intensity) ? Math.min(1f, Math.abs(intensity)) : 0f;
            this.motion = motion;
        }
    }

    public interface ResultCallback {
        void run(boolean success);
    }

    // NagramX: marker subclass identifies a pattern composite built by this helper (as
    // opposed to any other BitmapDrawable wallpaper source), so Theme.applyChatServiceMessageColor
    // can opt only Extera Dark's built-in pattern out of the wallpaper-sampled service
    // gradient without touching custom images, presets, server wallpapers, or base Monet's own
    // pattern. suppressServiceGradient is decided once, at build time, from the exact ThemeInfo
    // that produced this composite (see buildComposite below) — never recomputed at render time.
    public static final class MonetPatternDrawable extends BitmapDrawable {
        public final boolean suppressServiceGradient;

        MonetPatternDrawable(Resources resources, Bitmap bitmap, boolean suppressServiceGradient) {
            super(resources, bitmap);
            this.suppressServiceGradient = suppressServiceGradient;
            setFilterBitmap(true);
        }
    }

    private static final String FILE_NAME = "monet_pattern.bin";
    private static final int MAGIC = 0x4D4E5054; // 'MNPT'
    // v2 adds the generation nonce to the header; a v1 artifact from the unmerged
    // branch fails the version check and falls back to flat colour (no migration).
    private static final int VERSION = 2;
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

    // Fresh nonzero nonce stamped into every Apply and tombstone Clear. Utilities.random
    // is the app's process-global SecureRandom, so a repeat across writes is
    // cryptographically negligible; forcing nonzero keeps a zeroed/corrupt header region
    // from ever matching a value we wrote, so a restored older artifact can't validate.
    private static long newGeneration() {
        long g;
        do {
            g = Utilities.random.nextLong();
        } while (g == 0);
        return g;
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
        long generation = in.readLong();
        long patternId = in.readLong();
        float intensity = in.readFloat();
        boolean motion = in.readBoolean();
        return new Record(generation, patternId, intensity, motion);
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

    // The one artifact-read path: always go through AtomicFile.openRead() so legacy
    // (API 27-29) backup recovery runs — openRead restores a leftover <base>.bak over
    // base before reading, which a direct FileInputStream(getBaseFile()) would skip. A
    // genuinely absent artifact (no base, no backup) is the normal empty state, not an
    // error, so FileNotFoundException maps to null without logging. Caller holds the monitor.
    private static Record openReadHeader(AtomicFile af) {
        InputStream is;
        try {
            is = af.openRead();
        } catch (FileNotFoundException absent) {
            return null;
        } catch (Throwable e) {
            FileLog.e(e);
            return null;
        }
        return readHeader(is);
    }

    // First-use load of the volatile header cache via openRead(), so a crash's half-written
    // artifact is rolled back and a legacy leftover backup is recovered — we see a complete
    // tuple, a tombstone, or nothing. No getBaseFile().exists() pre-check: that would skip
    // the backup recovery when a crash left only <base>.bak. A tombstone (id == 0) means an
    // explicit clear, so the cache stays null. Under the class monitor so apply/clear can't
    // interleave with the open.
    private static void ensureLoaded() {
        if (loaded) {
            return;
        }
        synchronized (MonetPatternHelper.class) {
            if (loaded) {
                return;
            }
            Record r = openReadHeader(atomicFile());
            record = r != null && r.patternId != TOMBSTONE_ID ? r : null;
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
    // is void and swallows a failed rename, so we verify by re-reading through openRead()
    // (which also runs legacy backup recovery) and comparing the FULL tuple including the
    // generation nonce — so a silently-failed rename that leaves an older artifact, even one
    // with the same pattern/intensity/motion, is caught by its stale generation. Caller
    // holds the class monitor, so this openRead can't race our own in-flight write.
    private static boolean writeVerified(AtomicFile af, Record rec, Bitmap mask) {
        FileOutputStream fos = null;
        try {
            fos = af.startWrite();
            DataOutputStream out = new DataOutputStream(fos);
            out.writeInt(MAGIC);
            out.writeInt(VERSION);
            out.writeLong(rec.generation);
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

    // Confirm the artifact readable through openRead() equals the tuple we just wrote —
    // generation, id, exact magnitude bits, motion. Reading through openRead (not the raw
    // base file) means any leftover legacy backup is recovered first, so if finishWrite
    // silently failed to drop it, we validate against the artifact a real read would see.
    private static boolean headerMatches(AtomicFile af, Record rec) {
        Record actual = openReadHeader(af);
        return actual != null
                && actual.generation == rec.generation
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
        Record rec = new Record(newGeneration(), pattern.id, intensity, motion);
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
    // The tombstone carries its own fresh generation, so verification proves this exact
    // clear survived any legacy backup recovery. Publishes the empty record only after the
    // tombstone is durably written and verified. Runs on themeQueue.
    private static boolean doClear() {
        ensureLoaded();
        AtomicFile af = atomicFile();
        Record tombstone = new Record(newGeneration(), TOMBSTONE_ID, 0f, false);
        synchronized (MonetPatternHelper.class) {
            if (!writeVerified(af, tombstone, null)) {
                return false;
            }
            record = null;
            loaded = true;
            return true;
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
                // No exists() pre-check: openRead() runs legacy backup recovery, and any
                // absent/corrupt artifact surfaces as an exception handled below.
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
            // NagramX: suppression is Extera Dark only, decided from the exact ThemeInfo
            // that built this composite — never recomputed later from whatever theme happens
            // to be current when the drawable is rendered.
            boolean suppressServiceGradient = theme.isExteraFamily() && theme.isMonetDark();
            MonetPatternDrawable drawable = new MonetPatternDrawable(ApplicationLoader.applicationContext.getResources(), result, suppressServiceGradient);
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
                // openRead() so legacy backup recovery runs; a missing artifact throws and
                // falls back to sample 1 below.
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
