package com.radolyn.ayugram.privacyprofiles;

import android.content.Context;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.ImageView;
import android.widget.LinearLayout;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.LayoutHelper;

/**
 * The row of colour swatches in the add/edit profile dialog. Assembled from primitives already
 * used by this feature rather than a new cell class: a circle drawable per swatch, the same ripple
 * mask the icon button uses, and an outline ring to mark the selection instead of a checkmark.
 * <p>Colours come from {@code Theme.keys_avatar_background}, so the row follows the active theme
 * exactly as the profile glyphs themselves already do -- never a literal hex.
 */
public final class PrivacyProfileColorRow {

    private PrivacyProfileColorRow() {}

    private static final int SWATCH_DP = 28;

    /**
     * @param selected  single-element holder carrying the chosen palette index; updated in place.
     * @param onChanged run after a tap, so the caller can re-render its own preview.
     */
    public static LinearLayout create(Context context, int[] selected, Runnable onChanged) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);

        final int count = Theme.keys_avatar_background.length;
        final ImageView[] swatches = new ImageView[count];
        for (int i = 0; i < count; i++) {
            final int index = i;
            ImageView swatch = new ImageView(context);
            swatch.setScaleType(ImageView.ScaleType.CENTER);
            swatch.setContentDescription(AvatarDrawable.colorName(index));
            // A 28dp circle would otherwise be a 28dp touch target; the view is padded out to a
            // comfortable one with the drawable still drawn at 28dp.
            swatch.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector), Theme.RIPPLE_MASK_CIRCLE_20DP));
            swatch.setAccessibilityDelegate(new View.AccessibilityDelegate() {
                @Override
                public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                    super.onInitializeAccessibilityNodeInfo(host, info);
                    info.setClassName("android.widget.RadioButton");
                    info.setCheckable(true);
                    info.setChecked(selected[0] == index);
                }
            });
            swatch.setOnClickListener(v -> {
                if (selected[0] == index) return;
                selected[0] = index;
                for (int j = 0; j < count; j++) {
                    swatches[j].setImageDrawable(swatchDrawable(j, selected[0] == j));
                }
                if (onChanged != null) onChanged.run();
            });
            swatches[i] = swatch;
            // Weighted columns, not a fixed 40dp: seven fixed swatches need 280dp, but an
            // AlertDialog's content is only ~270dp wide on a 360dp phone, so the last colour
            // would be clipped off. Equal columns divide whatever width the dialog actually has.
            row.addView(swatch, LayoutHelper.createLinear(0, 48, 1f));
        }
        for (int i = 0; i < count; i++) {
            swatches[i].setImageDrawable(swatchDrawable(i, selected[0] == i));
        }
        return row;
    }

    /** The palette index a profile's stored seed resolves to. */
    public static int indexOf(long colorSeed) {
        return AvatarDrawable.getColorIndex(colorSeed);
    }

    /** Starting swatch for a brand-new profile, so they don't all open on the same colour. */
    public static int randomIndex() {
        return AvatarDrawable.getColorIndex(org.telegram.messenger.Utilities.random.nextLong());
    }

    private static android.graphics.drawable.Drawable swatchDrawable(int index, boolean selected) {
        int size = AndroidUtilities.dp(SWATCH_DP);
        int color = Theme.getColor(Theme.keys_avatar_background[index]);
        if (!selected) {
            return Theme.createCircleDrawable(size, color);
        }
        // Ring sits outside a shrunk circle so the gap -- not the stroke colour -- is what reads,
        // whichever of the seven colours is underneath.
        int inner = AndroidUtilities.dp(SWATCH_DP - 8);
        android.graphics.drawable.LayerDrawable layered = new android.graphics.drawable.LayerDrawable(
                new android.graphics.drawable.Drawable[]{
                        Theme.createCircleDrawable(inner, color),
                        Theme.createOutlineCircleDrawable(size, Theme.getColor(Theme.key_featuredStickers_addButton), AndroidUtilities.dp(2))
                });
        int inset = (size - inner) / 2;
        layered.setLayerInset(0, inset, inset, inset, inset);
        return layered;
    }
}
