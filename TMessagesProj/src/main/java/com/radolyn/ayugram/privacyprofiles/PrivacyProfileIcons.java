package com.radolyn.ayugram.privacyprofiles;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.Drawable;

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
        Drawable icon = ContextCompat.getDrawable(context, FolderIconHelper.getTabIcon(profile.icon));
        icon = icon != null ? icon.mutate() : null;
        if (icon != null) {
            icon.setColorFilter(new PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN));
        }
        int size = AndroidUtilities.dp(sizeDp);
        CombinedDrawable combined = new CombinedDrawable(Theme.createCircleDrawable(size, AvatarDrawable.getColorForId(profile.colorSeed)), icon);
        int iconSize = AndroidUtilities.dp(Math.round(sizeDp * 0.52f));
        combined.setIconSize(iconSize, iconSize);
        combined.setCustomSize(size, size);
        return combined;
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
