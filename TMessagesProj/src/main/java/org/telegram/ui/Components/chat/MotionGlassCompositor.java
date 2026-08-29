package org.telegram.ui.Components.chat;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;

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
 *
 * Scope: this proxy is for the small refracted composer pills only. Nothing blurs the wallpaper on
 * any glass path — the Liquid Glass shader is a single {@code img.eval(uv)} tap that displaces and
 * tints but cannot soften, and the raw wallpaper layer beneath it is drawn unblurred. So the proxy's
 * only softening is the cover-scale upscale, which turns the pattern into a coarse, enlarged, and
 * geometrically approximate impression of the wallpaper. That reads fine inside a small tinted pill
 * but is wrong for any surface that must visually continue the real wallpaper (the round-video
 * backdrop, the top/bottom fade bands): there the pattern smears into huge blocks that do not line up
 * with the wallpaper beside them. Those full-screen consumers take WallpaperBitmapProvider's
 * gradient-only plain source instead, which has no line-art to smear and upscales invisibly.
 */
public class MotionGlassCompositor {

    // Downsampled proxy: the glass upscales it, and its only softening is that upscale (nothing blurs
    // the wallpaper on any path), so the long edge is matched to the liquid-glass content blur rather
    // than the screen. Screen aspect (not the parent view's) so the pattern's cover-scale matches the
    // wallpaper drawn right above the panel. At 248px this is a lossy, geometrically approximate
    // impression of the wallpaper — fine for a small tinted, refracted pill, wrong for any surface
    // that must continue the wallpaper, which is why the full-screen consumers take the gradient-only
    // plain source. Raising this sharpens the mismatch there rather than fixing it.
    private static final int TARGET_LONG_EDGE_PX = 248;

    // The pattern fades in over ~250ms by ramping alpha, and setPatternAlpha/setBackgroundAlpha/
    // setPatternColorFilter/setAlpha all change the rendered output while moving neither bitmap's
    // generation id, so the trackers cannot see them. The notification-driven refresh therefore
    // passes force=true to recomposite unconditionally while resumed and attached. The attach path
    // passes force=false so the tracker skip avoids a redundant recomposite at attach time; its
    // callers do not read the return value (WallpaperBitmapProvider discards it and ChatActivity's
    // onUpdateBackgroundDrawable reprimes the render nodes unconditionally), so on that path the
    // boolean only decides whether the draw happens, not whether a reprime follows.

    private Bitmap composite;
    private Canvas compositeCanvas;

    private final BitmapChangeTracker gradientTracker = new BitmapChangeTracker();
    private final BitmapChangeTracker patternTracker = new BitmapChangeTracker();

    private final Rect savedBounds = new Rect();

    /**
     * Records the drawable into the retained composite and points {@code target} at it. Returns true
     * when it actually re-composited (a new bitmap instance was set, or the content changed), so the
     * caller can reprime the glass render nodes; false when nothing moved and the call was a no-op.
     * When {@code force} is set the content check is skipped and it always re-records, because the
     * alpha/colour-filter setters change the output without moving a generation id. UI thread only.
     */
    public boolean compose(BlurredBackgroundSourceBitmap target, MotionBackgroundDrawable motion, boolean force) {
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

        if (!force && !realloc && !gradientChanged && !patternChanged
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
        // Suppress the drawable's trailing updateAnimation() for this off-screen draw: it would advance
        // the animation clock and post invalidateMotionBackground, which drives another forced composite
        // and loops as fast as the queue drains. finally so a throw can't leave the flag stuck and freeze
        // the real wallpaper animation.
        motion.setSuppressAnimationAdvance(true);
        try {
            motion.draw(compositeCanvas);
        } finally {
            motion.setSuppressAnimationAdvance(false);
            motion.setBounds(savedBounds);
        }

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
