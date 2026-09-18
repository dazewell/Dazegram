package org.telegram.ui.Components.chat;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Shader;

import androidx.annotation.Nullable;

import org.telegram.ui.Components.blur3.source.BlurredBackgroundSourceWrapped;

/**
 * Wraps the live glass wallpaper composite (the wrapper's own {@link #getSource()}/{@link #setSource})
 * and, while a crossfade is in flight, draws a retained snapshot of the composite it replaced underneath
 * it at full alpha, with the live composite layered on top ramping from transparent to opaque. This is
 * what lets a settle-triggered recompose land as a blend instead of the single-frame bitmap swap that
 * used to make the catch-up composite visibly pop in.
 *
 * ChatActivity owns the one {@code ValueAnimator} that drives {@link #setCrossfadeProgress} and reprimes
 * the glass render nodes on every tick (see startGlassCompositeCrossfade there) - this class only holds
 * the state a single tick needs and does no animating or scheduling of its own.
 *
 * Extends {@link BlurredBackgroundSourceWrapped} rather than implementing BlurredBackgroundSource
 * directly so WallpaperBitmapProvider.getStatusBarColor/getNavigationBarColor - which resolve colour by
 * recursing through the instanceof BlurredBackgroundSourceWrapped chain via getSource() - keep resolving
 * through this wrapper exactly as they did before it existed.
 */
public class CrossfadingMotionGlassSource extends BlurredBackgroundSourceWrapped {

    private final Paint previousPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Matrix previousMatrix = new Matrix();
    private @Nullable Bitmap previousBitmap;
    private float crossfadeProgress = 1f;

    /**
     * Snapshot to draw underneath the live composite for the duration of a fade. {@code matrix} should
     * be the composite source's own cover matrix at the moment it was replaced - outside a geometry
     * change (which cancels the fade instead, see ChatActivity's matrix-safety call sites) the previous
     * and live bitmaps always share the same dimensions, so one matrix serves both.
     */
    public void setPreviousBitmap(@Nullable Bitmap bitmap, @Nullable Matrix matrix) {
        previousBitmap = bitmap;
        previousPaint.setShader(null);
        if (bitmap == null || bitmap.isRecycled() || matrix == null) {
            previousBitmap = null;
            return;
        }
        previousPaint.setShader(new BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
        previousMatrix.set(matrix);
    }

    public void clearPreviousBitmap() {
        previousBitmap = null;
        previousPaint.setShader(null);
    }

    public void setCrossfadeProgress(float progress) {
        crossfadeProgress = progress;
    }

    @Override
    public void draw(Canvas canvas, float left, float top, float right, float bottom) {
        if (previousBitmap == null || previousBitmap.isRecycled() || crossfadeProgress >= 1f) {
            super.draw(canvas, left, top, right, bottom);
            return;
        }

        // Previous frame first, fully opaque - it is what was on screen a moment ago, so there is
        // nothing to blend it against below it. No postTranslate(left, top): BlurredBackgroundSourceBitmap
        // (the live source's actual draw path) sets its shader's local matrix from the bare cover matrix
        // with no left/top offset folded in either, so matching that here - rather than the offset this
        // class used to add on top - is what keeps the two layers registered at a non-zero left/top.
        previousPaint.getShader().setLocalMatrix(previousMatrix);
        canvas.drawRect(left, top, right, bottom, previousPaint);

        // Live composite on top, ramping in. A RecordingCanvas (the glass render nodes' display lists
        // are recorded into one) turns this into a GPU alpha-blend of the two already-rasterised layers,
        // not a re-rasterization of either - the wallpaper pixels themselves are not touched per tick.
        final int alpha = Math.round(255 * crossfadeProgress);
        if (alpha > 0) {
            final int saveCount = canvas.saveLayerAlpha(left, top, right, bottom, alpha);
            super.draw(canvas, left, top, right, bottom);
            canvas.restoreToCount(saveCount);
        }
    }
}
