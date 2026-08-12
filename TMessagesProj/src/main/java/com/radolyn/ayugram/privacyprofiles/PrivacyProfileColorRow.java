package com.radolyn.ayugram.privacyprofiles;

import android.content.Context;
import android.graphics.Color;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.ImageView;
import android.widget.LinearLayout;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.LayoutHelper;

/**
 * The colour swatches in the add/edit profile dialog: one row of the seven avatar hues and, below
 * it, the same seven in their deeper variant. Assembled from primitives already used by this
 * feature rather than a new cell class: a circle drawable per swatch, the same ripple mask the
 * icon button uses, and an outline ring to mark the selection instead of a checkmark.
 * <p>Colours come from {@code Theme.keys_avatar_background} and {@code keys_avatar_background2},
 * so the rows follow the active theme exactly as the profile glyphs themselves already do --
 * never a literal hex.
 */
public final class PrivacyProfileColorRow {

    private PrivacyProfileColorRow() {}

    private static final int SWATCH_DP = 28;
    /** Light row. Stored as 0, which is also what a profile saved before tones existed reads as. */
    public static final int TONE_LIGHT = 0;
    /** Deep row: the same hue drawn from {@code keys_avatar_background2}. */
    public static final int TONE_DEEP = 1;
    /**
     * Below this summed per-channel distance two swatches read as the same colour. Used to decide
     * whether the deep row is worth showing at all -- see {@link #hasDeepTone()}.
     */
    private static final int DISTINCT_CHANNEL_DISTANCE = 24;

    /**
     * Whether the deep row is offered. {@code keys_avatar_background} follows the user's accent
     * while {@code keys_avatar_background2} is accent-excluded (see Theme's themeAccentExclusionKeys),
     * so under some themes the two rows resolve to the same or near-same colours. Rather than offer
     * a second row of swatches indistinguishable from the first -- where two profiles would look
     * identical while storing different tones -- the row is dropped unless most hues actually
     * differ. Every read of a stored tone goes through {@link #colorFor}, which clamps to the light
     * colour when this returns false, so a profile saved as deep under one theme still renders, and
     * re-saves, as the colour the user is actually looking at.
     */
    public static boolean hasDeepTone() {
        int distinct = 0;
        for (int i = 0; i < Theme.keys_avatar_background.length; i++) {
            int light = Theme.getColor(Theme.keys_avatar_background[i]);
            int deep = Theme.getColor(Theme.keys_avatar_background2[i]);
            if (Math.abs(Color.red(light) - Color.red(deep))
                    + Math.abs(Color.green(light) - Color.green(deep))
                    + Math.abs(Color.blue(light) - Color.blue(deep)) >= DISTINCT_CHANNEL_DISTANCE) {
                distinct++;
            }
        }
        return distinct * 2 > Theme.keys_avatar_background.length;
    }

    /**
     * The tone a stored value actually resolves to under the current theme. Deliberately recomputed
     * rather than cached: the answer changes with the theme, and a static cache would go stale on a
     * theme switch. The short-circuit means a light-tone profile never resolves any colours at all.
     */
    public static int toneFor(int storedTone) {
        return storedTone == TONE_DEEP && hasDeepTone() ? TONE_DEEP : TONE_LIGHT;
    }

    /**
     * The one place a profile's circle colour is decided. Every visual instance -- settings row,
     * quick-switch row, Settings-tab badge, dialog preview, pinned shortcut -- resolves through
     * here, so none of them can drift from the others.
     */
    public static int colorFor(long colorSeed, int tone) {
        int[] keys = toneFor(tone) == TONE_DEEP ? Theme.keys_avatar_background2 : Theme.keys_avatar_background;
        return Theme.getColor(keys[indexOf(colorSeed)]);
    }

    /**
     * @param selectedHue  single-element holder carrying the chosen palette index; updated in place.
     * @param selectedTone single-element holder carrying the chosen tone; updated in place, and
     *                     clamped on entry to a tone this theme can actually show.
     * @param onChanged    run after a tap, so the caller can re-render its own preview.
     */
    public static LinearLayout create(Context context, int[] selectedHue, int[] selectedTone, Runnable onChanged) {
        final int count = Theme.keys_avatar_background.length;
        final boolean deepAvailable = hasDeepTone();
        selectedTone[0] = toneFor(selectedTone[0]);

        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);

        final int rows = deepAvailable ? 2 : 1;
        final ImageView[][] swatches = new ImageView[rows][count];
        final Runnable repaint = () -> {
            for (int t = 0; t < rows; t++) {
                for (int i = 0; i < count; i++) {
                    swatches[t][i].setImageDrawable(swatchDrawable(i, t, selectedHue[0] == i && selectedTone[0] == t));
                }
            }
        };

        for (int t = 0; t < rows; t++) {
            final int tone = t;
            LinearLayout row = new LinearLayout(context);
            row.setOrientation(LinearLayout.HORIZONTAL);
            for (int i = 0; i < count; i++) {
                final int hue = i;
                ImageView swatch = new ImageView(context);
                swatch.setScaleType(ImageView.ScaleType.CENTER);
                swatch.setContentDescription(swatchName(hue, tone, deepAvailable));
                // A 28dp circle would otherwise be a 28dp touch target; the view is padded out to a
                // comfortable one with the drawable still drawn at 28dp.
                swatch.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector), Theme.RIPPLE_MASK_CIRCLE_20DP));
                swatch.setAccessibilityDelegate(new View.AccessibilityDelegate() {
                    @Override
                    public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                        super.onInitializeAccessibilityNodeInfo(host, info);
                        info.setClassName("android.widget.RadioButton");
                        info.setCheckable(true);
                        info.setChecked(selectedHue[0] == hue && selectedTone[0] == tone);
                    }
                });
                swatch.setOnClickListener(v -> {
                    if (selectedHue[0] == hue && selectedTone[0] == tone) return;
                    selectedHue[0] = hue;
                    selectedTone[0] = tone;
                    repaint.run();
                    if (onChanged != null) onChanged.run();
                });
                swatches[tone][i] = swatch;
                // Weighted columns, not a fixed 40dp: seven fixed swatches need 280dp, but an
                // AlertDialog's content is only ~270dp wide on a 360dp phone, so the last colour
                // would be clipped off. Equal columns divide whatever width the dialog actually has.
                row.addView(swatch, LayoutHelper.createLinear(0, 48, 1f));
            }
            column.addView(row, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, tone == 0 ? 0 : 4, 0, 0));
        }
        repaint.run();
        return column;
    }

    /** The palette index a profile's stored seed resolves to. */
    public static int indexOf(long colorSeed) {
        return AvatarDrawable.getColorIndex(colorSeed);
    }

    /** Starting swatch for a brand-new profile, so they don't all open on the same colour. */
    public static int randomIndex() {
        return AvatarDrawable.getColorIndex(org.telegram.messenger.Utilities.random.nextLong());
    }

    private static CharSequence swatchName(int hue, int tone, boolean deepAvailable) {
        String name = AvatarDrawable.colorName(hue);
        if (!deepAvailable || tone != TONE_DEEP) {
            return name;
        }
        return LocaleController.formatString(R.string.PrivacyProfileColorDeep, name);
    }

    private static android.graphics.drawable.Drawable swatchDrawable(int hue, int tone, boolean selected) {
        int size = AndroidUtilities.dp(SWATCH_DP);
        int[] keys = tone == TONE_DEEP ? Theme.keys_avatar_background2 : Theme.keys_avatar_background;
        int color = Theme.getColor(keys[hue]);
        if (!selected) {
            return Theme.createCircleDrawable(size, color);
        }
        // Ring sits outside a shrunk circle so the gap -- not the stroke colour -- is what reads,
        // whichever of the colours is underneath.
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
