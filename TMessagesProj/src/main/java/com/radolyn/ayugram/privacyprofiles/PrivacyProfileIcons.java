package com.radolyn.ayugram.privacyprofiles;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;

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
     * in the quick-switch menu -- the same idiom {@code ItemOptions.addAccount} uses for the
     * current account. Deliberately NOT folded into the plain overload: the launcher bitmap is
     * static until the next edit (a profile pinned while active would keep a ring forever) and the
     * Settings-tab badge only ever renders the active profile, so a ring there would be permanent
     * and meaningless.
     * <p>The coloured circle is shrunk to leave a background-coloured gap inside the ring. That
     * gap, not the stroke colour, is what makes the ring read against a user-picked circle colour
     * -- drawn flush, an accent ring on an avatar-blue circle is invisible. Shrinking also keeps
     * the stroke (which straddles the circle edge) inside the bounds set by setCustomSize, instead
     * of being clipped to a ragged arc.
     */
    public static Drawable circleDrawable(Context context, PrivacyProfile profile, int sizeDp, boolean ringed) {
        Drawable icon = ContextCompat.getDrawable(context, FolderIconHelper.getTabIcon(profile.icon));
        icon = icon != null ? icon.mutate() : null;
        if (icon != null) {
            icon.setColorFilter(new PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN));
        }
        int size = AndroidUtilities.dp(sizeDp);
        int circleSize = ringed ? AndroidUtilities.dp(Math.round(sizeDp * 0.82f)) : size;
        CombinedDrawable combined = new CombinedDrawable(Theme.createCircleDrawable(circleSize, AvatarDrawable.getColorForId(profile.colorSeed)), icon);
        int iconSize = AndroidUtilities.dp(Math.round(sizeDp * (ringed ? 0.44f : 0.52f)));
        combined.setIconSize(iconSize, iconSize);
        combined.setCustomSize(circleSize, circleSize);
        if (!ringed) {
            return combined;
        }
        Drawable ring = Theme.createOutlineCircleDrawable(size, Theme.getColor(Theme.key_featuredStickers_addButton), AndroidUtilities.dp(2));
        LayerDrawable layered = new LayerDrawable(new Drawable[]{combined, ring});
        int inset = (size - circleSize) / 2;
        layered.setLayerInset(0, inset, inset, inset, inset);
        return layered;
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
