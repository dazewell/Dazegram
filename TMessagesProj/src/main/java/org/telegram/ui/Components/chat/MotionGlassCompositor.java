package org.telegram.ui.Components.chat;

import android.graphics.Bitmap;
import android.graphics.Canvas;
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
 *
 * Scope: this proxy is the wallpaper layer of the glass surfaces that keep the composite — the
 * refracted composer pills (as the render node's setUnderSource) and, drawn bare, the round-video
 * recording backdrop. Nothing blurs the wallpaper on any glass path — the Liquid Glass shader is a
 * single {@code img.eval(uv)} tap that displaces and tints but cannot soften, and the raw wallpaper
 * layer beneath it is drawn unblurred — so the proxy's only softening is the cover-scale upscale, which
 * turns the pattern into a coarse, enlarged, and geometrically approximate impression. Which surfaces
 * take this composite versus WallpaperBitmapProvider's gradient-only plain source, and why, is decided
 * there; see that class for the split.
 */
public class MotionGlassCompositor extends GlassCompositorBase {

    // Downsampled proxy: the glass upscales it, and its only softening is that upscale (nothing blurs
    // the wallpaper on any path), so the pattern is only ever as sharp as this bitmap. Screen aspect
    // (not the parent view's) so the pattern's cover-scale matches the wallpaper drawn right above the
    // panel. At 248 the pattern rasterised ~114px wide and cover-scaled ~9.5x on a 1080-wide screen,
    // which on device showed as visibly rough, pixelated pattern edges through the pill rather than as
    // soft texture. 768 puts the long edge at ~354px on that screen, roughly a 3x upscale, which reads
    // as texture rather than stair-stepped line-art. Cost is ~1MB per chat activity plus a bigger
    // off-screen composite on each recomposite, the accepted trade for legible pattern edges. The pill
    // is the surface whose needs bound this number: the round-video recording backdrop also samples this
    // composite, but it is a near-opaque scrim where upscale artefacts barely show, so raising the
    // resolution further only spends memory on detail the refracted, tinted pill cannot resolve.
    private static final int TARGET_LONG_EDGE_PX = 768;

    private final BitmapChangeTracker gradientTracker = new BitmapChangeTracker();
    private final BitmapChangeTracker patternTracker = new BitmapChangeTracker();

    private final Rect savedBounds = new Rect();

    private MotionBackgroundDrawable pendingMotion;

    /**
     * Records the drawable into the retained composite and points {@code target} at it. Returns true
     * when it actually re-composited (a new bitmap instance was set, or the content changed), so the
     * caller can reprime the glass render nodes; false when nothing moved and the call was a no-op.
     *
     * The pattern fades in over ~250ms by ramping alpha, and setPatternAlpha/setBackgroundAlpha/
     * setPatternColorFilter/setAlpha all change the rendered output while moving neither bitmap's
     * generation id, so the trackers cannot see them. Pass {@code force} to skip the content check and
     * re-record unconditionally: the notification-driven refresh does (while resumed and attached), the
     * attach path does not, so its tracker skip avoids a redundant recomposite at attach time. On the
     * attach path the callers ignore the return value — WallpaperBitmapProvider discards it and
     * ChatActivity reprimes the render nodes unconditionally — so there the boolean only decides whether
     * the draw happens, not whether a reprime follows. UI thread only.
     */
    public boolean compose(BlurredBackgroundSourceBitmap target, MotionBackgroundDrawable motion, boolean force) {
        return compose(target, motion, force, 0);
    }

    public boolean compose(BlurredBackgroundSourceBitmap target, MotionBackgroundDrawable motion, boolean force, int diagnosticsOwnerId) {
        final int targetW = targetWidth();
        final int targetH = targetHeight();
        if (targetW <= 0 || targetH <= 0) {
            return false;
        }

        final Bitmap gradient = motion.getBitmap();
        final Bitmap pattern = motion.getPatternBitmap();
        final boolean gradientChanged = gradientTracker.isInvalidated(gradient);
        final boolean patternChanged = patternTracker.isInvalidated(pattern);

        pendingMotion = motion;
        final boolean changed;
        final long composeStartedNs = diagnosticsOwnerId != 0 ? SystemClock.elapsedRealtimeNanos() : 0L;
        try {
            changed = composeInto(target, targetW, targetH, force || gradientChanged || patternChanged);
        } finally {
            pendingMotion = null;
        }
        if (diagnosticsOwnerId != 0) {
            xyz.nextalone.nagram.helper.GlassPatternSmokeDiagnostics.onComposeSample(
                    diagnosticsOwnerId,
                    gradientChanged,
                    SystemClock.elapsedRealtimeNanos() - composeStartedNs
            );
        }

        gradientTracker.set(gradient);
        patternTracker.set(pattern);
        return changed;
    }

    @Override
    protected void drawInto(Canvas canvas, int width, int height) {
        final MotionBackgroundDrawable motion = pendingMotion;
        savedBounds.set(motion.getBounds());
        motion.setBounds(0, 0, width, height);
        // Suppress the drawable's trailing updateAnimation() for this off-screen draw: it would advance
        // the animation clock and post invalidateMotionBackground, which drives another forced composite
        // and loops as fast as the queue drains. finally so a throw can't leave the flag stuck and freeze
        // the real wallpaper animation.
        motion.setSuppressAnimationAdvance(true);
        try {
            motion.draw(canvas);
        } finally {
            motion.setSuppressAnimationAdvance(false);
            motion.setBounds(savedBounds);
        }
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
