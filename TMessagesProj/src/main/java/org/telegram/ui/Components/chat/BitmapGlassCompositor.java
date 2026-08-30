package org.telegram.ui.Components.chat;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;

import org.telegram.ui.Components.blur3.source.BlurredBackgroundSourceBitmap;
import org.telegram.ui.Components.blur3.utils.BitmapChangeTracker;

/**
 * Builds the glass wallpaper proxy for a {@link BitmapDrawable} wallpaper — a photo, a pattern baked
 * into an imported theme, a per-single-colour pattern override, or a Monet composite. The composer
 * pills would otherwise sample the ~90x120 stack-blurred proxy, which destroys pattern line-art before
 * the pill ever refracts it; the pattern only survives on the built-in gradient because that goes
 * through {@link MotionGlassCompositor} instead. This gives every bitmap wallpaper the same crisp
 * downsampled proxy the gradient already gets.
 *
 * The composite is a bitmap this class allocates and owns; the drawable's own bitmap is never aliased
 * into the glass shader. That bitmap can be ImageLoader-owned and recycled the moment a chat closes,
 * and a recycled bitmap baked into a render node's display list crashes HWUI with no draw() guard to
 * catch it. Drawing through the drawable into our own bitmap sidesteps that entirely.
 *
 * Not thread-safe by design: UI thread only, like the base.
 */
public class BitmapGlassCompositor extends GlassCompositorBase {

    // Match MotionGlassCompositor's proxy resolution so the pattern reads as texture rather than
    // stair-stepped line-art through the refracted pill. Sized to the SOURCE bitmap's aspect (not the
    // screen's): the wallpaper is cover-scaled preserving its own aspect, so a screen-aspect proxy
    // would stretch the pattern out of line with the wallpaper drawn right above the panel.
    private static final int TARGET_LONG_EDGE_PX = 768;

    private final BitmapChangeTracker sourceTracker = new BitmapChangeTracker();
    private final Rect savedBounds = new Rect();

    private BitmapDrawable pendingDrawable;

    /**
     * Records {@code drawable} into the retained composite and points {@code target} at it. Returns
     * true when it composited a crisp proxy the caller can use; false when it declined — a null or
     * recycled source bitmap, or one too small to be worth upscaling (a per-dialog wallpaper hands over
     * a 20x20 placeholder while the real image loads async) — in which case the caller falls back to the
     * blurred proxy rather than showing flat colour blocks through the glass. UI thread only.
     */
    public boolean compose(BlurredBackgroundSourceBitmap target, BitmapDrawable drawable) {
        final Bitmap source = drawable.getBitmap();
        if (source == null || source.isRecycled()) {
            return false;
        }
        final int sw = source.getWidth();
        final int sh = source.getHeight();
        if (sw <= 0 || sh <= 0) {
            return false;
        }

        final int targetW;
        final int targetH;
        if (sw >= sh) {
            targetW = TARGET_LONG_EDGE_PX;
            targetH = Math.max(1, Math.round((float) TARGET_LONG_EDGE_PX * sh / sw));
        } else {
            targetH = TARGET_LONG_EDGE_PX;
            targetW = Math.max(1, Math.round((float) TARGET_LONG_EDGE_PX * sw / sh));
        }

        // Size floor: only downscale, never upscale into the proxy. If the source is smaller than the
        // target in either dimension it is a thumbnail placeholder, and cover-scaling that through the
        // glass reads as coarse colour blocks. Decline and let the caller keep the blurred proxy, which
        // at least reads as a soft wallpaper.
        if (sw < targetW || sh < targetH) {
            return false;
        }

        final boolean contentChanged = sourceTracker.isInvalidated(source);
        pendingDrawable = drawable;
        try {
            composeInto(target, targetW, targetH, contentChanged);
        } finally {
            pendingDrawable = null;
        }
        sourceTracker.set(source);
        return true;
    }

    @Override
    protected void drawInto(Canvas canvas, int width, int height) {
        final BitmapDrawable drawable = pendingDrawable;
        savedBounds.set(drawable.getBounds());
        drawable.setBounds(0, 0, width, height);
        try {
            drawable.draw(canvas);
        } finally {
            drawable.setBounds(savedBounds);
        }
    }
}
