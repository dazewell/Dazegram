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

    private Bitmap composite;
    private Canvas compositeCanvas;

    /**
     * Draw the wallpaper content into {@code canvas} filling exactly {@code width} x {@code height}.
     * The subclass owns any bounds/state juggling its drawable needs and must restore it before
     * returning.
     */
    protected abstract void drawInto(Canvas canvas, int width, int height);

    /**
     * Records the subclass content into the retained composite at {@code width} x {@code height} and
     * points {@code target} at it. Returns true when it actually re-recorded (a realloc, or
     * {@code contentChanged}), so the caller can reprime its glass render nodes; false when nothing
     * moved and the call was a no-op.
     */
    protected boolean composeInto(BlurredBackgroundSourceBitmap target, int width, int height, boolean contentChanged) {
        final boolean realloc = composite == null || composite.isRecycled()
                || composite.getWidth() != width || composite.getHeight() != height;

        if (!realloc && !contentChanged && target.getBitmap() == composite) {
            return false;
        }

        if (realloc) {
            // Drop the old reference rather than recycle it: a baked glass display list may still hold
            // a paint whose shader references it, and HWUI throws on a recycled bitmap. Let GC take it.
            composite = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            composite.setHasAlpha(false);
            compositeCanvas = new Canvas(composite);
        }

        // Opaque black, not transparent: a source can mutate its rendered output with no invalidation
        // signal (a drawable alpha ramp during a crossfade), so a refresh landing mid-change would
        // otherwise bake a semi-transparent proxy with no signal to fix it. Black bounds the worst case
        // to "briefly too dark".
        composite.eraseColor(Color.BLACK);
        drawInto(compositeCanvas, width, height);

        // Same instance across re-records so setBitmap early-returns and the shader/matrix survive;
        // only a realloc or a wallpaper-type switch rebuilds the shader here.
        if (target.getBitmap() != composite) {
            target.setBitmap(composite);
        }
        return true;
    }
}
