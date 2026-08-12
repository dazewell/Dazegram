package com.radolyn.ayugram.privacyprofiles;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.Drawable;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.CombinedDrawable;

import tw.nekomimi.nekogram.folder.FolderIconHelper;

/**
 * A profile's composite icon: its colorSeed-tinted circle background plus its chosen
 * folder-icon-grid glyph, tinted white on top. Same idiom SessionCell/LocationCell already use
 * for a stub avatar (CombinedDrawable over Theme.createCircleDrawable) -- just swapping in this
 * feature's own background color and icon glyph. This is the one place that decides what a
 * profile "looks like", reused by the profile row, the add/edit dialog's icon preview button, and
 * the pinned-shortcut bitmap, so none of the three can drift from the other two.
 */
public final class PrivacyProfileIcons {

    private PrivacyProfileIcons() {}

    public static Drawable circleDrawable(Context context, PrivacyProfile profile, int sizeDp) {
        return circleDrawable(context, profile, sizeDp, false);
    }

    /**
     * {@code ringed} draws an outline ring around the circle, marking "this is the active profile"
     * in the quick-switch menu. Deliberately NOT folded into the plain overload: the launcher
     * bitmap is static until the next edit (a profile pinned while active would keep a ring
     * forever) and the Settings-tab badge only ever renders the active profile, so a ring there
     * would be permanent and meaningless.
     */
    public static Drawable circleDrawable(Context context, PrivacyProfile profile, int sizeDp, boolean ringed) {
        Drawable icon = ContextCompat.getDrawable(context, FolderIconHelper.getTabIcon(profile.icon));
        icon = icon != null ? icon.mutate() : null;
        if (icon != null) {
            icon.setColorFilter(new PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN));
        }
        int size = AndroidUtilities.dp(sizeDp);
        if (ringed) {
            return new RingedProfileDrawable(size, AvatarDrawable.getColorForId(profile.colorSeed), icon);
        }
        CombinedDrawable combined = new CombinedDrawable(Theme.createCircleDrawable(size, AvatarDrawable.getColorForId(profile.colorSeed)), icon);
        int iconSize = AndroidUtilities.dp(Math.round(sizeDp * 0.52f));
        combined.setIconSize(iconSize, iconSize);
        combined.setCustomSize(size, size);
        return combined;
    }

    /**
     * The active-profile row icon, drawn entirely from its own bounds. A LayerDrawable can't do
     * this job here: {@code ActionBarMenuSubItem} lays the icon out at its intrinsic size and then
     * pushes an SRC_IN colour filter onto the whole drawable, so composed insets are computed
     * against the wrong box and the ring comes out flush and submenu-grey instead of accented.
     * Deriving every radius from {@code getBounds()} makes the geometry correct at whatever size
     * the container picks, and swallowing the filter for everything but the glyph (the same trick
     * {@code CombinedDrawable} already uses for its background) keeps the ring and circle colours.
     */
    private static final class RingedProfileDrawable extends Drawable {

        private final int size;
        private final Paint circlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Drawable glyph;

        RingedProfileDrawable(int size, int circleColor, Drawable glyph) {
            this.size = size;
            this.glyph = glyph;
            circlePaint.setColor(circleColor);
            ringPaint.setStyle(Paint.Style.STROKE);
            ringPaint.setStrokeWidth(AndroidUtilities.dp(2));
            ringPaint.setColor(Theme.getColor(Theme.key_featuredStickers_addButton));
        }

        @Override
        public void draw(@NonNull Canvas canvas) {
            android.graphics.Rect b = getBounds();
            float cx = b.centerX();
            float cy = b.centerY();
            float half = Math.min(b.width(), b.height()) / 2f;
            float stroke = ringPaint.getStrokeWidth();
            // Ring hugs the inside of the bounds, so it can never be clipped whatever box the
            // container hands us; the circle then backs off by a visible gap. The gap, not the
            // stroke colour, is what makes the ring read against any of the seven circle colours.
            float ringRadius = half - stroke / 2f;
            float circleRadius = ringRadius - stroke / 2f - AndroidUtilities.dp(2);
            canvas.drawCircle(cx, cy, circleRadius, circlePaint);
            canvas.drawCircle(cx, cy, ringRadius, ringPaint);
            if (glyph != null) {
                int glyphSize = (int) (circleRadius * 1.1f);
                glyph.setBounds((int) (cx - glyphSize / 2f), (int) (cy - glyphSize / 2f),
                        (int) (cx + glyphSize / 2f), (int) (cy + glyphSize / 2f));
                glyph.draw(canvas);
            }
        }

        @Override
        public void setColorFilter(@Nullable ColorFilter colorFilter) {
            // Only the glyph follows the menu's icon tint; the circle and ring keep their own
            // colours, which is the whole point of this drawable.
            if (glyph != null) {
                glyph.setColorFilter(new PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN));
            }
        }

        @Override
        public void setAlpha(int alpha) {
            circlePaint.setAlpha(alpha);
            ringPaint.setAlpha(alpha);
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }

        @Override
        public int getIntrinsicWidth() {
            return size;
        }

        @Override
        public int getIntrinsicHeight() {
            return size;
        }
    }

    /** Renders the same composite icon into a square bitmap, for the pinned launcher shortcut. */
    public static Bitmap circleBitmap(Context context, PrivacyProfile profile, int sizeDp) {
        int size = AndroidUtilities.dp(sizeDp);
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        Drawable drawable = circleDrawable(context, profile, sizeDp);
        drawable.setBounds(0, 0, size, size);
        drawable.draw(canvas);
        return bitmap;
    }
}
