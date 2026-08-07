package xyz.nextalone.nagram.ui.composer;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.util.SparseArray;

import androidx.core.content.ContextCompat;

import org.telegram.messenger.ApplicationLoader;

/**
 * Sizes composer icons by how much ink they actually carry rather than by their bounds.
 *
 * <p>Fitting every drawable into the same box only equalises bounds, and these assets disagree
 * about how much of their bounds they fill: some are drawn edge to edge, others float inside a wide
 * margin. Boxed to the same size they still read as different sizes, which is what this answers.
 *
 * <p>So the ink is measured rather than guessed: the drawable is rasterised once into a small
 * offscreen bitmap, its alpha bounding box is taken, and the result is cached. Swapping an asset
 * then costs nothing, where a per-icon constant would need re-tuning.
 *
 * <p>Extent alone would treat a solid glyph and a diagonal hairline as equals, and the eye does not:
 * a lighter shape has to run slightly larger to look the same size. How much of its own bounding box
 * the ink fills stands in for that weight, so the correction comes out of the same measurement.
 */
public final class ComposerIconMetrics {

    private ComposerIconMetrics() {
    }

    /** The live area every surface gives an icon: inside the toolbar cell, the list row, the preview. */
    private static final float BOX_DP = 24f;

    /** Ink extent every glyph is driven towards, measured against a solid rectangular shape. */
    private static final float TARGET_INK_DP = 18f;

    /** How much extra extent a fully hollow shape earns over a fully solid one. */
    private static final float MAX_OVERSHOOT = 0.08f;

    private static final float MIN_SCALE = 0.70f;
    private static final float MAX_SCALE = 1.75f;

    /** Fixed reference raster: independent of display density, so the fractions stay stable. */
    private static final int SAMPLE = 96;

    /** Below this a pixel counts as empty, so an antialiased edge cannot inflate the ink box. */
    private static final int ALPHA_FLOOR = 16;

    /** An ink box smaller than this reads as a failed measurement rather than a small glyph. */
    private static final float MIN_INK_FRACTION = 0.10f;

    /** Keyed by resource id alone: these glyphs have no config-qualified variant whose geometry
     *  differs, and a tint changes colour rather than the alpha the measurement reads. */
    private static final SparseArray<Float> CACHE = new SparseArray<>();

    /** Widest a measured icon may be driven, and the floor a manual override cannot push past. */
    public static float clamp(float scale) {
        return Math.min(MAX_SCALE, Math.max(MIN_SCALE, scale));
    }

    /**
     * Multiplier that brings this drawable's ink onto the common target. Returns 1 for anything
     * that cannot be measured, so an unreadable asset is left at its authored size rather than
     * being blown up on a guess.
     */
    public static float scaleFor(int drawableRes) {
        if (drawableRes == 0) {
            return 1f;
        }
        synchronized (CACHE) {
            Float cached = CACHE.get(drawableRes);
            if (cached != null) {
                return cached;
            }
        }
        Float scale = measure(drawableRes);
        if (scale == null) {
            // A missing context or a failed allocation says nothing about the asset, so it is left
            // uncached and measured again later rather than pinned at 1 for the life of the process.
            return 1f;
        }
        synchronized (CACHE) {
            CACHE.put(drawableRes, scale);
        }
        return scale;
    }

    /** Null when the measurement failed for a reason that may not hold next time. */
    private static Float measure(int drawableRes) {
        if (ApplicationLoader.applicationContext == null) {
            return null;
        }
        Drawable drawable;
        try {
            drawable = ContextCompat.getDrawable(ApplicationLoader.applicationContext, drawableRes);
        } catch (Exception ignored) {
            return null;
        }
        if (drawable == null) {
            return 1f;
        }
        int intrinsicWidth = Math.max(1, drawable.getIntrinsicWidth());
        int intrinsicHeight = Math.max(1, drawable.getIntrinsicHeight());
        // Rasterised at its own aspect inside the square sample, matching how the render sites fit
        // it into their box. Stretching it square instead would report a distorted ink extent.
        int renderWidth = SAMPLE;
        int renderHeight = SAMPLE;
        if (intrinsicWidth > intrinsicHeight) {
            renderHeight = Math.max(1, Math.round(SAMPLE * intrinsicHeight / (float) intrinsicWidth));
        } else if (intrinsicHeight > intrinsicWidth) {
            renderWidth = Math.max(1, Math.round(SAMPLE * intrinsicWidth / (float) intrinsicHeight));
        }
        Bitmap bitmap;
        try {
            bitmap = Bitmap.createBitmap(renderWidth, renderHeight, Bitmap.Config.ARGB_8888);
        } catch (Throwable ignored) {
            return null;
        }
        try {
            drawable = drawable.mutate();
            drawable.setBounds(0, 0, renderWidth, renderHeight);
            drawable.draw(new Canvas(bitmap));
            int[] pixels = new int[renderWidth * renderHeight];
            bitmap.getPixels(pixels, 0, renderWidth, 0, 0, renderWidth, renderHeight);
            int left = renderWidth, top = renderHeight, right = -1, bottom = -1;
            int inkPixels = 0;
            for (int y = 0; y < renderHeight; y++) {
                int rowOffset = y * renderWidth;
                for (int x = 0; x < renderWidth; x++) {
                    if ((pixels[rowOffset + x] >>> 24) < ALPHA_FLOOR) {
                        continue;
                    }
                    inkPixels++;
                    if (x < left) left = x;
                    if (x > right) right = x;
                    if (y < top) top = y;
                    if (y > bottom) bottom = y;
                }
            }
            if (right < left || bottom < top) {
                return 1f;
            }
            int inkWidth = right - left + 1;
            int inkHeight = bottom - top + 1;
            // Measured against the square the render sites actually reserve, not against the
            // drawable's own aspect, so a wide glyph is not quietly allowed to run larger.
            float extent = Math.max(inkWidth, inkHeight) / (float) SAMPLE;
            if (extent < MIN_INK_FRACTION) {
                return 1f;
            }
            float fill = inkPixels / (float) (inkWidth * inkHeight);
            float overshoot = 1f + MAX_OVERSHOOT * (1f - Math.min(1f, Math.max(0f, fill)));
            return clamp((TARGET_INK_DP / BOX_DP) * overshoot / extent);
        } catch (Throwable ignored) {
            return null;
        } finally {
            bitmap.recycle();
        }
    }
}
