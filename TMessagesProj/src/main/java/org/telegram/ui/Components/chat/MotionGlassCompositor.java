package org.telegram.ui.Components.chat;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.os.SystemClock;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.Components.MotionBackgroundDrawable;
import org.telegram.ui.Components.blur3.source.BlurredBackgroundSourceBitmap;
import org.telegram.ui.Components.blur3.utils.BitmapChangeTracker;

/**
 * Builds the glass wallpaper proxy for a {@link MotionBackgroundDrawable}.
 *
 * The proxy the glass would otherwise get is wrong for a pattern wallpaper: getBitmap() is the
 * gradient mesh only and the intensity&lt;0 branch hands over flat black, so the composer glass never
 * shows the pattern. Instead we record the drawable's real output (gradient + pattern, including the
 * intensity&lt;0 mask and the soft-light/legacy branches) into one retained bitmap and hand that to
 * the existing {@link BlurredBackgroundSourceBitmap}, re-recording in place so the shader and its
 * centre-crop matrix survive.
 *
 * Not thread-safe by design: every caller is on the UI thread (the background wallpaper drawable is
 * only ever drawn there), so the retained bitmap is never touched off the main thread and no locking
 * is needed.
 */
public class MotionGlassCompositor {

    // Downsampled proxy: the glass upscales it, and its only softening is that upscale, so the long
    // edge is matched to the liquid-glass content blur rather than the screen. Screen aspect (not the
    // parent view's) so the pattern's cover-scale matches the wallpaper drawn right above the panel.
    private static final int TARGET_LONG_EDGE_PX = 248;

    // The pattern fades in over ~250ms by ramping alpha, which moves neither bitmap's generation id,
    // so the trackers can't see it. For a short window after the pattern instance changes we
    // re-composite every driven frame to follow the fade.
    private static final long PATTERN_FADE_MS = 300;

    private Bitmap composite;
    private Canvas compositeCanvas;

    private final BitmapChangeTracker gradientTracker = new BitmapChangeTracker();
    private final BitmapChangeTracker patternTracker = new BitmapChangeTracker();
    private long patternFadeUntil;

    private final Rect savedBounds = new Rect();

    /**
     * Records the drawable into the retained composite and points {@code target} at it. Returns true
     * when it actually re-composited (a new bitmap instance was set, or the content changed), so the
     * caller can reprime the glass render nodes; false when nothing moved and the call was a no-op.
     * UI thread only.
     */
    public boolean compose(BlurredBackgroundSourceBitmap target, MotionBackgroundDrawable motion) {
        final int targetW = targetWidth();
        final int targetH = targetHeight();
        if (targetW <= 0 || targetH <= 0) {
            return false;
        }

        final boolean realloc = composite == null || composite.isRecycled()
                || composite.getWidth() != targetW || composite.getHeight() != targetH;

        final Bitmap gradient = motion.getBitmap();
        final Bitmap pattern = motion.getPatternBitmap();
        final boolean gradientChanged = gradientTracker.isInvalidated(gradient);
        final boolean patternChanged = patternTracker.isInvalidated(pattern);
        if (patternChanged) {
            patternFadeUntil = SystemClock.elapsedRealtime() + PATTERN_FADE_MS;
        }
        final boolean inFadeWindow = SystemClock.elapsedRealtime() < patternFadeUntil;

        if (!realloc && !gradientChanged && !patternChanged && !inFadeWindow
                && target.getBitmap() == composite) {
            return false;
        }

        if (realloc) {
            // Drop the old reference rather than recycle it: a baked glass display list may still hold
            // a paint whose shader references it, and HWUI throws on a recycled bitmap. Let GC take it.
            composite = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888);
            composite.setHasAlpha(false);
            compositeCanvas = new Canvas(composite);
        }

        // Opaque black, not transparent: setAlpha() on the drawable during a theme crossfade posts no
        // invalidation, so a refresh landing mid-crossfade would otherwise bake a semi-transparent
        // proxy with no signal to fix it. Black bounds the worst case to "briefly too dark".
        composite.eraseColor(Color.BLACK);
        savedBounds.set(motion.getBounds());
        motion.setBounds(0, 0, targetW, targetH);
        motion.draw(compositeCanvas);
        motion.setBounds(savedBounds);

        gradientTracker.set(gradient);
        patternTracker.set(pattern);

        // Same instance across re-records so setBitmap early-returns and the shader/matrix survive;
        // only a realloc or a wallpaper-type switch rebuilds the shader here.
        if (target.getBitmap() != composite) {
            target.setBitmap(composite);
        }
        return true;
    }

    private static int targetWidth() {
        final int sw = AndroidUtilities.displaySize.x;
        final int sh = AndroidUtilities.displaySize.y;
        if (sw <= 0 || sh <= 0) {
            return 0;
        }
        return sw >= sh ? TARGET_LONG_EDGE_PX : Math.max(1, Math.round((float) TARGET_LONG_EDGE_PX * sw / sh));
    }

    private static int targetHeight() {
        final int sw = AndroidUtilities.displaySize.x;
        final int sh = AndroidUtilities.displaySize.y;
        if (sw <= 0 || sh <= 0) {
            return 0;
        }
        return sh >= sw ? TARGET_LONG_EDGE_PX : Math.max(1, Math.round((float) TARGET_LONG_EDGE_PX * sh / sw));
    }
}
