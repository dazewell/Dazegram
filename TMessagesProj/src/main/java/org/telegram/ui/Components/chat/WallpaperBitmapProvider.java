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
    private final BitmapGlassCompositor bitmapGlassCompositor = new BitmapGlassCompositor();

    // NagramX: THE SPLIT — this class is the single home for the rationale; the copies in ChatActivity,
    // ChannelAdminLogActivity and MotionGlassCompositor point here.
    // The composited source (gradient + pattern) is a small screen-aspect proxy the glass cover-scales
    // several times over with nothing softening it (see MotionGlassCompositor). That reads fine behind a
    // small refracted composer pill, but a surface that draws the bare wallpaper straight from a source
    // shows the pattern smeared into coarse, enlarged blocks wherever it sits beside the real wallpaper.
    // So the split is by source, not by size: surfaces that composite blurred message content over the
    // wallpaper (the render-node factories — the composer pills) keep the composite; surfaces that draw
    // the bare wallpaper (navbarContentDrawableFactory — the fade bands and the search list) take this
    // "plain" source: the pre-#230 gradient-only mesh (or flat black when intensity<0), which has no
    // line-art to smear and upscales invisibly. Within ChatActivity the round-video recording backdrop is
    // the one bare-wallpaper surface that deliberately keeps the composite instead: a near-opaque
    // full-screen scrim with no adjacent wallpaper to smear against.
    //
    // plainSourceColor and sourceColor must stay separate objects and never be merged: sourceColor holds
    // a colour wallpaper's fill and plainSourceColor an intensity<0 motion wallpaper's flat black, and one
    // field cannot carry both without cross-writing them.
    private final BlurredBackgroundSourceColor plainSourceColor = new BlurredBackgroundSourceColor();
    private final BlurredBackgroundSourceBitmap plainSourceBitmap = new BlurredBackgroundSourceBitmap();
    private BlurredBackgroundSource plainSource;

    // NagramX: the keyboard pans the wallpaper vertically (SizeNotifierFrameLayout adds
    // backgroundTranslationY to the wallpaper's draw y). Each bitmap glass proxy is a separate bitmap the
    // glass samples through its own cover matrix, so without this it stays put while the wallpaper slides
    // under it and the pattern behind the composer reads as misaligned. We keep the composite source's
    // matrix — and, for a bare-bitmap wallpaper, the plain source's matrix — equal to base +
    // backgroundTranslationY at all times. The source rebuilds its own cover matrix inside
    // setBitmap()/setParentSize() without telling us and setParentSize early-returns on identical dims,
    // so we can never assume whether a rebuild happened: the tracker detects it by value and recomputes
    // base + current shift from scratch, which is idempotent and cannot double-apply or lose the shift.
    // compositeShift always drives sourceBitmap; plainShift drives plainSourceBitmap only when the plain
    // source is a bare-wallpaper bitmap (shiftPlainSource) — a motion gradient mesh is deliberately left
    // unshifted, see setBackgroundTranslationY.
    private final KeyboardShiftTracker compositeShift = new KeyboardShiftTracker();
    private final KeyboardShiftTracker plainShift = new KeyboardShiftTracker();
    private boolean shiftPlainSource;
    private int backgroundTranslationY;

    private static final Rect tmpRect = new Rect();

    /**
     * Re-runs the motion-wallpaper composite (gradient + pattern) for an already-attached drawable.
     * Returns true when the proxy changed and the caller should reprime its glass render nodes.
     */
    public boolean refreshMotionComposite(MotionBackgroundDrawable motionDrawable, int diagnosticsOwnerId) {
        final boolean changed = motionGlassCompositor.compose(sourceBitmap, motionDrawable, true, diagnosticsOwnerId);
        // NagramX: compose() may rebuild the source's cover matrix (setBitmap on a realloc), which drops
        // the keyboard shift back to identity, so re-assert it.
        applyBackgroundTranslation();
        return changed;
    }

    public BlurredBackgroundSource updateSourceFromBackgroundViewDrawable(
        Drawable drawable
    ) {
        final BlurredBackgroundSource result = updateSourceFromBackgroundViewDrawableInner(drawable);
        // NagramX: the branches above may rebuild sourceBitmap's cover matrix (setBitmap on a wallpaper
        // or type switch), which resets it to identity and loses the keyboard shift, so re-assert it.
        applyBackgroundTranslation();
        return result;
    }

    private BlurredBackgroundSource updateSourceFromBackgroundViewDrawableInner(
        Drawable drawable
    ) {
        // NagramX: default off; only the bare-bitmap wallpaper branch below opts the plain source into
        // the keyboard shift. Reset here (not in each other branch) so the ColorDrawable, motion and
        // canvas-record branches stay untouched and a photo→motion switch clears it.
        shiftPlainSource = false;

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
            // NagramX: motion is now the active proxy on sourceBitmap; drop the bitmap compositor's
            // retained proxy so the two don't both stay resident after a bitmap→motion switch. Target was
            // switched above, so releasing the inactive one now can't drop a bitmap we're about to draw.
            bitmapGlassCompositor.release();
            // NagramX: the plain source is the pre-#230 gradient-only proxy for full-screen surfaces —
            // getBitmap() (the 60x80 gradient mesh, no pattern), or flat black when intensity<0.
            if (motionDrawable.getIntensity() < 0) {
                plainSourceColor.setColor(Color.BLACK);
                plainSource = plainSourceColor;
            } else {
                // NagramX: setBitmap here aliases the drawable's live mesh on purpose. getBitmap() returns
                // currentBitmap, allocated once in the drawable's init and thereafter mutated in place by
                // generateGradient, so the plain source animates for free. A defensive Bitmap copy here
                // would silently freeze the gradient behind the fade bands.
                plainSourceBitmap.setBitmap(motionDrawable.getBitmap());
                plainSource = plainSourceBitmap;
            }
            return sourceBitmap;
        }

        if (drawable instanceof BitmapDrawable) {
            final BitmapDrawable bitmapDrawable = (BitmapDrawable) drawable;
            // NagramX: a bitmap wallpaper (photo, imported-theme pattern, single-colour pattern override,
            // Monet composite) used to hand the ~90x120 stack-blurred proxy to BOTH the composer pills
            // and the bare-wallpaper surfaces, which destroyed any pattern before the pill sampled it —
            // the pattern only survived on the built-in gradient because that goes through the motion
            // compositor. Give the composite (pills) a crisp source-aspect proxy the same way, and keep
            // the plain source on the blurred proxy for the bare-wallpaper surfaces (fade bands, search
            // list) where a low-res composite would smear into coarse blocks beside the real wallpaper.
            // The two are separate objects sharing the same blurred bitmap on the fallback path, never
            // the drawable's own bitmap: that can be ImageLoader-owned and recycled when a chat closes,
            // and a recycled bitmap baked into a glass render node crashes HWUI.
            final Bitmap blurred = blurredFromBitmap.get(bitmapDrawable.getBitmap());
            plainSourceBitmap.setBitmap(blurred);
            plainSource = plainSourceBitmap;
            // NagramX: the plain source is now a bare-wallpaper bitmap, so it must track the keyboard pan
            // like the composite does (SizeNotifierFrameLayout pans the wallpaper by backgroundTranslationY).
            shiftPlainSource = true;
            if (bitmapGlassCompositor.compose(sourceBitmap, bitmapDrawable)) {
                // NagramX: bitmap is now the active proxy; drop the motion compositor's retained proxy so
                // both don't stay resident after a motion→bitmap switch (target already switched above).
                motionGlassCompositor.release();
                return sourceBitmap;
            }
            // NagramX: composite declined (null/recycled or a thumbnail-sized source) — fall back to the
            // blurred proxy for the pills too, exactly as before this change, rather than upscaling a
            // thumbnail into visible colour blocks.
            sourceBitmap.setBitmap(blurred);
            // NagramX: neither compositor is active on the fallback path (both pill and bare surfaces draw
            // the blurred proxy), so release both retained proxies. setBitmap above already moved the
            // target off any composite, so this can't drop a bitmap still in use here.
            bitmapGlassCompositor.release();
            motionGlassCompositor.release();
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
     * gradient-only mesh (or flat black) and for a bare-bitmap wallpaper the stack-blurred proxy, never
     * the pattern composite the call returns; for a colour wallpaper and the canvas-record fallback it is
     * the same object that call returned.
     *
     * Not every consumer that keeps the composite is a bare full-screen surface. GiftMessageBottomSheet
     * is the other one that draws this composite bare across its whole container and deliberately keeps
     * the pattern, so don't "finish the job" by pointing it at the plain source. ComposerLayoutActivity's
     * preview capsules also keep the composite: they sample it sized to the preview cell, not full-screen,
     * so there is no adjacent wallpaper to smear against.
     *
     * NekoDelegateFragment is the exception the other way. Its glass action bar samples the composite
     * through a render node like the pills, but it feeds the SAME source to a full-screen
     * ChatActivityFadeView drawn bare over the real wallpaper, and the two cannot be split without
     * restructuring its sizing. So for a bare-bitmap wallpaper it installs this plain source into that
     * shared source and gives up the action bar's crisp pattern to keep the fade band from smearing —
     * see resolveGlassWallpaperSource there.
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
        // NagramX: setParentSize rebuilds each source's cover matrix, dropping the keyboard shift back to
        // identity, so re-assert it on both (a no-op when the shift is 0).
        applyBackgroundTranslation();
    }

    /**
     * Vertical offset the keyboard pan applies to the wallpaper (SizeNotifierFrameLayout's
     * backgroundTranslationY). Each bitmap glass proxy is a separate bitmap and must shift by the same
     * amount so what shows through tracks the real wallpaper. This always shifts the composite source
     * (sourceBitmap). What happens to the plain source depends on the wallpaper type: for a motion
     * wallpaper the plain source is a separate gradient bitmap (or flat black), left unshifted because a
     * few px of vertical drift is invisible in smooth low-frequency colour. For a bare-bitmap wallpaper
     * (photo, imported-theme or single-colour pattern, Monet) the plain source is the blurred proxy of
     * that wallpaper, a separate object from the composite, so it must be shifted too (shiftPlainSource) —
     * SizeNotifierFrameLayout pans that wallpaper by the same backgroundTranslationY, so the fade bands
     * track it where before they did not.
     */
    public void setBackgroundTranslationY(int translationY) {
        backgroundTranslationY = translationY;
        applyBackgroundTranslation();
    }

    private void applyBackgroundTranslation() {
        compositeShift.apply(sourceBitmap, backgroundTranslationY);
        if (shiftPlainSource) {
            plainShift.apply(plainSourceBitmap, backgroundTranslationY);
        }
    }

    /**
     * Keeps a bitmap source's cover matrix at base + a vertical keyboard shift. Instantiated once per
     * source rather than copied inline: the re-base-by-value detection below is the subtle part and must
     * live in exactly one place.
     */
    private static final class KeyboardShiftTracker {
        private final Matrix baseMatrix = new Matrix();
        private final Matrix shiftedMatrix = new Matrix();
        private final float[] liveValues = new float[9];
        private final float[] appliedValues = new float[9];
        private boolean tracked;

        void apply(BlurredBackgroundSourceBitmap source, int translationY) {
            // getMatrix() hands back the source's live bitmapMatrix, so read its values out rather than
            // mutating it in place. If those values no longer match what we last wrote, the source rebuilt
            // its cover matrix (setBitmap/setParentSize) since our last pass, so adopt the live matrix as
            // the new base; otherwise the base we already hold is still current. Then always recompute
            // base + current shift, so there is no delta arithmetic to double-apply or lose across an
            // early-returned setParentSize.
            final Matrix live = source.getMatrix();
            live.getValues(liveValues);
            if (!tracked || !Arrays.equals(liveValues, appliedValues)) {
                baseMatrix.set(live);
                tracked = true;
            }
            shiftedMatrix.set(baseMatrix);
            // Sign: the wallpaper draws at y = backgroundTranslationY + ... (SizeNotifierFrameLayout), so
            // a positive translation moves it down; shift the source's output down by the same amount so
            // the glass keeps sampling the matching wallpaper region.
            shiftedMatrix.postTranslate(0, translationY);
            source.setMatrix(shiftedMatrix);
            shiftedMatrix.getValues(appliedValues);
        }
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
