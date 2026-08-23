package xyz.nextalone.nagram.helper;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;

import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.SvgHelper;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.Theme;

import java.io.File;
import java.io.FileOutputStream;

// NagramX: shared pattern for the live Monet-colour wallpaper tile (tile 0). One
// immutable global record + one mask file; the live colour is re-read on every
// wallpaper reload so the pattern rides on top of whatever Monet/Extera/Solid
// variant and day/night mode is active.
public final class MonetPatternHelper {

    // Immutable value object. Published wholesale into a volatile field, never
    // mutated field-by-field, so a single read on any thread is coherent.
    public static final class Record {
        public final long patternId;
        public final float intensity; // magnitude only, always >= 0
        public final boolean motion;
        public final String maskFileName;

        Record(long patternId, float intensity, boolean motion, String maskFileName) {
            this.patternId = patternId;
            // clamp to [0,1]: intensity is a flat-wallpaper fraction, and a bad
            // persisted value must not overflow the composite's 0..255 alpha
            this.intensity = Math.min(1f, Math.abs(intensity));
            this.motion = motion;
            this.maskFileName = maskFileName;
        }
    }

    private static final String PREFS_NAME = "monet_pattern";
    private static final String KEY_RECORD = "record";
    private static final String MASK_NAME = "monet_pattern_mask.png";
    private static final String MASK_TMP = "monet_pattern_mask.png.tmp";

    private static volatile Record record;
    private static volatile boolean loaded;

    private MonetPatternHelper() {
    }

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    private static void ensureLoaded() {
        if (loaded) {
            return;
        }
        synchronized (MonetPatternHelper.class) {
            if (loaded) {
                return;
            }
            try {
                String json = prefs().getString(KEY_RECORD, null);
                if (json != null) {
                    JSONObject o = new JSONObject(json);
                    record = new Record(o.getLong("patternId"), (float) o.getDouble("intensity"), o.getBoolean("motion"), o.getString("mask"));
                }
            } catch (Exception e) {
                FileLog.e(e);
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

    // Persist a freshly chosen pattern. Order: build the mask, atomically move it
    // into place, publish the immutable record, then write metadata — so no
    // published record ever points at an incomplete or missing mask.
    public static void apply(int account, TLRPC.TL_wallPaper pattern, float intensity, boolean motion) {
        if (pattern == null || pattern.document == null) {
            clear();
            return;
        }
        try {
            int w = Math.min(AndroidUtilities.displaySize.x, AndroidUtilities.displaySize.y);
            int h = Math.max(AndroidUtilities.displaySize.x, AndroidUtilities.displaySize.y);
            File doc = FileLoader.getInstance(account).getPathToAttach(pattern.document, true);
            Bitmap mask = SvgHelper.getBitmap(doc, w, h, false, SvgHelper.ScaleMode.ByWidth);
            if (mask == null) {
                return;
            }
            File dir = ApplicationLoader.getFilesDirFixed();
            File tmp = new File(dir, MASK_TMP);
            File finalMask = new File(dir, MASK_NAME);
            try (FileOutputStream stream = new FileOutputStream(tmp)) {
                Bitmap argb = mask.copy(Bitmap.Config.ARGB_8888, false);
                argb.compress(Bitmap.CompressFormat.PNG, 100, stream);
                argb.recycle();
            } finally {
                mask.recycle();
            }
            if (!tmp.renameTo(finalMask)) {
                finalMask.delete();
                if (!tmp.renameTo(finalMask)) {
                    tmp.delete();
                    return;
                }
            }
            Record r = new Record(pattern.id, intensity, motion, MASK_NAME);
            record = r;
            loaded = true;
            persist(r);
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    // Drop the pattern: publish the empty record first, persist it, then remove
    // the owned mask file. The composite null-checks the record, so a reload that
    // was already in flight simply falls back to the flat colour.
    public static void clear() {
        record = null;
        loaded = true;
        try {
            prefs().edit().remove(KEY_RECORD).apply();
        } catch (Exception e) {
            FileLog.e(e);
        }
        try {
            File mask = new File(ApplicationLoader.getFilesDirFixed(), MASK_NAME);
            if (mask.exists()) {
                mask.delete();
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    private static void persist(Record r) {
        try {
            JSONObject o = new JSONObject();
            o.put("patternId", r.patternId);
            o.put("intensity", r.intensity);
            o.put("motion", r.motion);
            o.put("mask", r.maskFileName);
            prefs().edit().putString(KEY_RECORD, o.toString()).apply();
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    // Composite the shared pattern mask over the live flat Monet colour at the
    // requested size. Returns null (fall back to the plain colour) whenever the
    // theme isn't Monet, no pattern is set, or the mask can't be read. The record
    // is read once into a local so a concurrent republish can't tear the snapshot.
    // No bitmap is cached: the live-chat caller builds one per wallpaper reload
    // (infrequent) and the tile-0 thumbnail caller asks for a small size, so there
    // is no shared cross-thread state and no stale-dimension risk.
    public static Drawable buildComposite(Theme.ThemeInfo theme, int backgroundColor, int width, int height) {
        if (theme == null || !theme.isMonet() || width <= 0 || height <= 0) {
            return null;
        }
        Record r = getRecord();
        if (r == null) {
            return null;
        }
        try {
            File maskFile = new File(ApplicationLoader.getFilesDirFixed(), r.maskFileName);
            if (!maskFile.exists()) {
                return null;
            }
            Bitmap mask = BitmapFactory.decodeFile(maskFile.getAbsolutePath());
            if (mask == null) {
                return null;
            }
            Bitmap result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(result);
            canvas.drawColor(backgroundColor);
            Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
            paint.setColorFilter(new PorterDuffColorFilter(AndroidUtilities.getPatternColor(backgroundColor), PorterDuff.Mode.SRC_IN));
            paint.setAlpha((int) (255 * r.intensity));
            canvas.drawBitmap(mask, null, new Rect(0, 0, width, height), paint);
            mask.recycle();
            BitmapDrawable drawable = new BitmapDrawable(ApplicationLoader.applicationContext.getResources(), result);
            drawable.setFilterBitmap(true);
            return drawable;
        } catch (Throwable e) {
            FileLog.e(e);
            return null;
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
