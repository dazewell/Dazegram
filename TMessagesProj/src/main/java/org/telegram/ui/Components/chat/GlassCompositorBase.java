package org.telegram.ui.Components.chat;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;

import org.telegram.ui.Components.blur3.source.BlurredBackgroundSourceBitmap;

/**
 * Shared plumbing for the glass wallpaper proxies. A subclass decides what pixels go into the proxy
 * ({@link #drawInto}) and how big it is; this base owns the retained bitmap they land in and the rules
 * that keep it safe to hand to a glass render node.
 *
 * The proxy is a small off-screen bitmap the glass cover-scales; the split of which surfaces take a
 * pattern-carrying composite versus a plain gradient/blur proxy lives in {@link WallpaperBitmapProvider}.
 *
 * Not thread-safe by design: every caller is on the UI thread (the wallpaper drawable is only ever
 * drawn there), so the retained bitmap is never touched off the main thread and no locking is needed.
 */
abstract class GlassCompositorBase {

    // NagramX: two retained buffers, not one. composeInto always draws into whichever one ISN'T
    // currently live on target, then swaps target onto it - the buffer that WAS live keeps its pixels
    // untouched instead of being eraseColor()'d in place, so a caller can read it (via the composite
    // source's own getBitmap(), before calling in for a refresh) and use it as a crossfade's "previous"
    // frame. Re-recording a single retained bitmap in place, which is what this used to do, destroys the
    // old frame's pixels before a blend could ever reference them - that was the actual blocker, not
    // anything about the crossfade animator itself.
    private Bitmap compositeA;
    private Canvas compositeCanvasA;
    private Bitmap compositeB;
    private Canvas compositeCanvasB;
    // NagramX: true while compositeA is the one currently live on target (the "front" buffer).
    private boolean frontIsA = true;

    /**
     * Draw the wallpaper content into {@code canvas} filling exactly {@code width} x {@code height}.
     * The subclass owns any bounds/state juggling its drawable needs and must restore it before
     * returning.
     */
    protected abstract void drawInto(Canvas canvas, int width, int height);

    /**
     * Records the subclass content into the back buffer at {@code width} x {@code height} and points
     * {@code target} at it, leaving the front buffer's pixels intact for the caller to have already read
     * off {@code target.getBitmap()} before calling this. Returns true when it actually re-recorded (a
     * realloc, or {@code contentChanged}), so the caller can reprime its glass render nodes; false when
     * nothing moved and the call was a no-op.
     */
    protected boolean composeInto(BlurredBackgroundSourceBitmap target, int width, int height, boolean contentChanged) {
        final Bitmap frontBitmap = frontIsA ? compositeA : compositeB;
        final boolean frontUpToDate = frontBitmap != null && !frontBitmap.isRecycled()
                && frontBitmap.getWidth() == width && frontBitmap.getHeight() == height
                && target.getBitmap() == frontBitmap;

        if (frontUpToDate && !contentChanged) {
            return false;
        }

        Bitmap backBitmap = frontIsA ? compositeB : compositeA;
        final boolean realloc = backBitmap == null || backBitmap.isRecycled()
                || backBitmap.getWidth() != width || backBitmap.getHeight() != height;

        if (realloc) {
            // Drop the old reference rather than recycle it: a baked glass display list, or this class's
            // own crossfade snapshot, may still hold a paint whose shader references it, and HWUI throws
            // on a recycled bitmap. Let GC take it.
            backBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            backBitmap.setHasAlpha(false);
            final Canvas backCanvas = new Canvas(backBitmap);
            if (frontIsA) {
                compositeB = backBitmap;
                compositeCanvasB = backCanvas;
            } else {
                compositeA = backBitmap;
                compositeCanvasA = backCanvas;
            }
        }
        final Canvas backCanvas = frontIsA ? compositeCanvasB : compositeCanvasA;

        // Opaque black, not transparent: a source can mutate its rendered output with no invalidation
        // signal (a drawable alpha ramp during a crossfade), so a refresh landing mid-change would
        // otherwise bake a semi-transparent proxy with no signal to fix it. Black bounds the worst case
        // to "briefly too dark".
        backBitmap.eraseColor(Color.BLACK);
        drawInto(backCanvas, width, height);

        target.setBitmap(backBitmap);
        frontIsA = !frontIsA;
        return true;
    }

    /**
     * Drops both retained buffers so an inactive compositor stops holding full-sized proxies after a
     * wallpaper-type switch — the provider owns one motion and one bitmap compositor, and without this
     * both would stay resident once the wallpaper flips between the two, breaking the two-buffer budget.
     * Never {@code recycle()}: a baked glass display list, or a crossfade snapshot, may still hold a
     * paint whose shader references one of these bitmaps, and HWUI throws on a recycled bitmap. Null the
     * fields and let GC take them once nothing references them. Cheap to rebuild — the next compose()
     * reallocs from null.
     */
    void release() {
        compositeA = null;
        compositeCanvasA = null;
        compositeB = null;
        compositeCanvasB = null;
        frontIsA = true;
    }
}
