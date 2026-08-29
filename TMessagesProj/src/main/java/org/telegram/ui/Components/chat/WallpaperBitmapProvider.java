package org.telegram.ui.Components.chat;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;

import org.telegram.messenger.Utilities;
import org.telegram.ui.ChatBackgroundDrawable;
import org.telegram.ui.Components.MotionBackgroundDrawable;
import org.telegram.ui.Components.blur3.source.BlurredBackgroundSource;
import org.telegram.ui.Components.blur3.source.BlurredBackgroundSourceBitmap;
import org.telegram.ui.Components.blur3.source.BlurredBackgroundSourceColor;
import org.telegram.ui.Components.blur3.source.BlurredBackgroundSourceWrapped;
import org.telegram.ui.Components.blur3.utils.BitmapMemoizedMetadata;

import java.util.Arrays;

public class WallpaperBitmapProvider {

    private final BlurredBackgroundSourceColor sourceColor = new BlurredBackgroundSourceColor();
    private final BlurredBackgroundSourceBitmap sourceBitmap = new BlurredBackgroundSourceBitmap();
    private final MotionGlassCompositor motionGlassCompositor = new MotionGlassCompositor();

    // NagramX: the composited source (gradient + pattern) is a tiny screen-aspect proxy the glass
    // cover-scales ~9x with nothing softening it (see MotionGlassCompositor). That reads fine behind a
    // small refracted composer pill but smears the pattern into enormous blocks on a full-screen
    // surface. Full-screen consumers take this "plain" source instead: the pre-#230 gradient-only mesh
    // (or flat black when intensity<0), which upscales invisibly because it has no line-art to smear.
    private final BlurredBackgroundSourceColor plainSourceColor = new BlurredBackgroundSourceColor();
    private final BlurredBackgroundSourceBitmap plainSourceBitmap = new BlurredBackgroundSourceBitmap();
    private BlurredBackgroundSource plainSource;

    // NagramX: the keyboard pans the wallpaper vertically (SizeNotifierFrameLayout adds
    // backgroundTranslationY to the wallpaper's draw y). The composite proxy is a separate bitmap the
    // glass samples through its own cover matrix, so without this it stays put while the wallpaper
    // slides under it and the pattern behind the composer reads as misaligned. We keep the composite
    // source's matrix equal to base + backgroundTranslationY at all times. The source rebuilds its own
    // cover matrix inside setBitmap()/setParentSize() without telling us and setParentSize early-returns
    // on identical dims, so we can never assume whether a rebuild happened: detect it by value (below)
    // and always recompute base + current shift from scratch, which is idempotent and cannot double-
    // apply or lose the shift. The plain source is deliberately left alone (see getPlainSource): the
    // gradient it carries is smooth low-frequency colour where a few px of vertical drift is invisible.
    private final Matrix compositeBaseMatrix = new Matrix();
    private final Matrix compositeShiftedMatrix = new Matrix();
    private final float[] compositeLiveValues = new float[9];
    private final float[] compositeAppliedValues = new float[9];
    private boolean compositeMatrixTracked;
    private int backgroundTranslationY;

    private static final Rect tmpRect = new Rect();

    /**
     * Re-runs the motion-wallpaper composite (gradient + pattern) for an already-attached drawable.
     * Returns true when the proxy changed and the caller should reprime its glass render nodes.
     */
    public boolean refreshMotionComposite(MotionBackgroundDrawable motionDrawable) {
        final boolean changed = motionGlassCompositor.compose(sourceBitmap, motionDrawable, true);
        // NagramX: compose() may rebuild the source's cover matrix (setBitmap on a realloc), which drops
        // the keyboard shift back to identity, so re-assert it.
        applyBackgroundTranslationToComposite();
        return changed;
    }

    public BlurredBackgroundSource updateSourceFromBackgroundViewDrawable(
        Drawable drawable
    ) {
        final BlurredBackgroundSource result = updateSourceFromBackgroundViewDrawableInner(drawable);
        // NagramX: the branches above may rebuild sourceBitmap's cover matrix (setBitmap on a wallpaper
        // or type switch), which resets it to identity and loses the keyboard shift, so re-assert it.
        applyBackgroundTranslationToComposite();
        return result;
    }

    private BlurredBackgroundSource updateSourceFromBackgroundViewDrawableInner(
        Drawable drawable
    ) {
        if (drawable instanceof ColorDrawable) {
            final int color = ((ColorDrawable) drawable).getColor();
            sourceColor.setColor(color);
            plainSource = sourceColor;
            return sourceColor;
        }

        if (drawable instanceof MotionBackgroundDrawable) {
            final MotionBackgroundDrawable motionDrawable = (MotionBackgroundDrawable) drawable;
            // NagramX: getBitmap() is the gradient mesh only and the intensity<0 branch returns flat
            // black — neither carries the wallpaper pattern, so the glass behind the composer never
            // shows it. Composite the drawable's actual output (gradient + pattern, including the
            // intensity<0 mask) into a retained bitmap and hand that to the glass. See MotionGlassCompositor.
            motionGlassCompositor.compose(sourceBitmap, motionDrawable, false);
            // NagramX: the plain source is the pre-#230 gradient-only proxy for full-screen surfaces —
            // getBitmap() (the 60x80 gradient mesh, no pattern), or flat black when intensity<0.
            if (motionDrawable.getIntensity() < 0) {
                plainSourceColor.setColor(Color.BLACK);
                plainSource = plainSourceColor;
            } else {
                plainSourceBitmap.setBitmap(motionDrawable.getBitmap());
                plainSource = plainSourceBitmap;
            }
            return sourceBitmap;
        }

        if (drawable instanceof BitmapDrawable) {
            final Bitmap bitmap = blurredFromBitmap.get(((BitmapDrawable) drawable).getBitmap());
            sourceBitmap.setBitmap(bitmap);
            plainSource = sourceBitmap;
            return sourceBitmap;
        }

        if (drawable instanceof ChatBackgroundDrawable) {
            ChatBackgroundDrawable chatDrawable = (ChatBackgroundDrawable) drawable;
            return updateSourceFromBackgroundViewDrawable(chatDrawable.getDrawable(false));
        }

        if (drawable != null) {
            Canvas canvas = sourceBitmap.beginRecording(120, 160);
            tmpRect.set(drawable.getBounds());
            drawable.setBounds(0, 0, 120, 160);
            drawable.draw(canvas);
            drawable.setBounds(tmpRect);
            sourceBitmap.endRecording();
            sourceBitmap.setBitmap(blurredFromBitmap.get(sourceBitmap.getBitmap()));
        }

        plainSource = sourceBitmap;
        return sourceBitmap;
    }

    /**
     * The plain wallpaper proxy for full-screen glass surfaces, set as a side effect of the most
     * recent {@link #updateSourceFromBackgroundViewDrawable} call. For a motion wallpaper this is the
     * gradient-only mesh (or flat black), never the pattern composite; for every other drawable type
     * it is the same object that call returned.
     */
    public BlurredBackgroundSource getPlainSource() {
        return plainSource;
    }

    /**
     * Sizes both internal bitmap sources (composite and plain) so each has a valid cover matrix even
     * when it is not the one currently installed on a wrapper. A wallpaper switch installs whichever
     * source the new drawable maps to but does not force a fresh measure pass, so an unsized source
     * would otherwise draw its mesh 1:1 in the top-left corner until an unrelated relayout. Sizing both
     * unconditionally closes that for a bitmap-to-motion (plain) and a colour-to-motion (composite)
     * switch alike; the identical-dims early-return in setParentSize makes the redundant call free.
     */
    public void setParentSize(int width, int height, int actionBarHeight) {
        sourceBitmap.setParentSize(width, height, actionBarHeight);
        plainSourceBitmap.setParentSize(width, height, actionBarHeight);
        // NagramX: setParentSize rebuilds the composite source's cover matrix, dropping the keyboard
        // shift back to identity, so re-assert it (a no-op when the shift is 0).
        applyBackgroundTranslationToComposite();
    }

    /**
     * Vertical offset the keyboard pan applies to the wallpaper (SizeNotifierFrameLayout's
     * backgroundTranslationY). The composite glass proxy is a separate bitmap and must shift by the same
     * amount so the pattern behind the composer tracks the real wallpaper. Applied to the composite
     * source only; the plain full-screen source is left alone (its gradient hides the drift).
     */
    public void setBackgroundTranslationY(int translationY) {
        backgroundTranslationY = translationY;
        applyBackgroundTranslationToComposite();
    }

    private void applyBackgroundTranslationToComposite() {
        // getMatrix() hands back the source's live bitmapMatrix, so read its values out rather than
        // mutating it in place. If those values no longer match what we last wrote, the source rebuilt
        // its cover matrix (setBitmap/setParentSize) since our last pass, so adopt the live matrix as the
        // new base; otherwise the base we already hold is still current. Then always recompute
        // base + current shift, so there is no delta arithmetic to double-apply or lose across an
        // early-returned setParentSize.
        final Matrix live = sourceBitmap.getMatrix();
        live.getValues(compositeLiveValues);
        if (!compositeMatrixTracked || !Arrays.equals(compositeLiveValues, compositeAppliedValues)) {
            compositeBaseMatrix.set(live);
            compositeMatrixTracked = true;
        }
        compositeShiftedMatrix.set(compositeBaseMatrix);
        // Sign: the wallpaper draws at y = backgroundTranslationY + ... (SizeNotifierFrameLayout), so a
        // positive translation moves it down; shift the composite's output down by the same amount so the
        // glass keeps sampling the matching wallpaper region.
        compositeShiftedMatrix.postTranslate(0, backgroundTranslationY);
        sourceBitmap.setMatrix(compositeShiftedMatrix);
        compositeShiftedMatrix.getValues(compositeAppliedValues);
    }

    public int getNavigationBarColor(BlurredBackgroundSource source) {
        if (source instanceof BlurredBackgroundSourceColor) {
            return ((BlurredBackgroundSourceColor) source).getColor();
        }

        if (source instanceof BlurredBackgroundSourceBitmap) {
            final Bitmap bitmap = ((BlurredBackgroundSourceBitmap) source).getBitmap();
            return navbarColorFromBitmap.get(bitmap);
        }

        if (source instanceof BlurredBackgroundSourceWrapped) {
            return getNavigationBarColor(((BlurredBackgroundSourceWrapped) source).getSource());
        }

        return 0;
    }

    public int getStatusBarColor(BlurredBackgroundSource source) {
        if (source instanceof BlurredBackgroundSourceColor) {
            return ((BlurredBackgroundSourceColor) source).getColor();
        }

        if (source instanceof BlurredBackgroundSourceBitmap) {
            final Bitmap bitmap = ((BlurredBackgroundSourceBitmap) source).getBitmap();
            return statusBarColorFromBitmap.get(bitmap);
        }

        if (source instanceof BlurredBackgroundSourceWrapped) {
            return getStatusBarColor(((BlurredBackgroundSourceWrapped) source).getSource());
        }

        return 0;
    }

    private final BitmapMemoizedMetadata<Bitmap> blurredFromBitmap = new BitmapMemoizedMetadata<>(WallpaperBitmapProvider::blurBitmap);
    private final BitmapMemoizedMetadata<Integer> navbarColorFromBitmap = new BitmapMemoizedMetadata<>(WallpaperBitmapProvider::averageBottomColor);
    private final BitmapMemoizedMetadata<Integer> statusBarColorFromBitmap = new BitmapMemoizedMetadata<>(WallpaperBitmapProvider::averageTopColor);


    private static Bitmap blurBitmap(Bitmap bitmap) {
        if (bitmap == null || bitmap.isRecycled()) {
            return null;
        }

        final float scale = Math.max(bitmap.getWidth() / 90f, bitmap.getHeight() / 120f);
        final Bitmap result = Utilities.stackBlurBitmapWithScaleFactor(bitmap, scale);
        result.setHasAlpha(false);
        return result;
    }



    private static int averageTopColor(Bitmap bitmap) {
        if (bitmap == null || bitmap.isRecycled()) {
            return 0;
        }

        final int height = bitmap.getHeight();
        final int width = bitmap.getWidth();
        final int bottom = height / 10;
        return Utilities.averageBitmapColor(bitmap, 0, 0, width, bottom);
    }

    private static int averageBottomColor(Bitmap bitmap) {
        if (bitmap == null || bitmap.isRecycled()) {
            return 0;
        }

        final int height = bitmap.getHeight();
        final int width = bitmap.getWidth();
        final int top = height * 9 / 10;
        return Utilities.averageBitmapColor(bitmap, 0, top, width, height);
    }

}
