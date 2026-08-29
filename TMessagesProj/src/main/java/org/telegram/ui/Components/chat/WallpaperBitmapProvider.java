package org.telegram.ui.Components.chat;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
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

    private static final Rect tmpRect = new Rect();

    /**
     * Re-runs the motion-wallpaper composite (gradient + pattern) for an already-attached drawable.
     * Returns true when the proxy changed and the caller should reprime its glass render nodes.
     */
    public boolean refreshMotionComposite(MotionBackgroundDrawable motionDrawable) {
        return motionGlassCompositor.compose(sourceBitmap, motionDrawable, true);
    }

    public BlurredBackgroundSource updateSourceFromBackgroundViewDrawable(
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
