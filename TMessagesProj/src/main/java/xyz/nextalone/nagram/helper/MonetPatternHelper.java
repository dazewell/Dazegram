package xyz.nextalone.nagram.helper;

import android.graphics.Color;
import android.util.SparseIntArray;

import androidx.core.graphics.ColorUtils;

import org.telegram.ui.ActionBar.Theme;

// NagramX: chat-background patterns for the seven Monet-derived themes (Monet Light/Dark/AMOLED,
// Extera Light/Dark, Solid Light/Dark), which have no accent and whose palette is regenerated from
// the phone. Upstream stores an override wallpaper per ThemeInfo under "<name>_owp" and, without a
// live gradient in the .attheme, composites the pattern against colours frozen at pick time -- so a
// pattern set under one variant is absent under the others and its colour never follows the palette.
//
// This helper does two things and nothing else:
//   1. Storage identity. getKey()/loadWallpapers() in Theme route every isMonet() theme to one
//      shared record instead of a per-name key, and this class keeps the seven in-memory
//      ThemeInfo.overrideWallpaper references pointing at one live instance across theme switches
//      (loadWallpapers only runs at cold start, so the others would otherwise hold stale copies).
//   2. Live compositing. buildRenderSnapshot() hands loadWallpaper a throwaway OverrideWallpaperInfo
//      whose gradient stops and intensity sign are derived from the active palette. The persisted
//      record is never touched, so no derived sign or lifted colour can reach disk.
public final class MonetPatternHelper {

    private MonetPatternHelper() {
    }

    // One "themeconfig" SharedPreferences record shared by all seven Monet-derived themes. A pattern
    // picked under any of them shows under all of them, and day/night auto-switch (which swaps the
    // active ThemeInfo pointer) can no longer drop it.
    public static final String SHARED_OWP_KEY = "monet_shared_owp";

    // The single live instance the seven ThemeInfo objects converge on at runtime. At cold start each
    // Monet ThemeInfo deserializes its own copy from SHARED_OWP_KEY; they are all equal (same JSON),
    // so the first Monet apply adopts one and later switches republish it.
    private static Theme.OverrideWallpaperInfo shared;
    private static boolean initialized;

    // Luminance at/under which the live wallpaper colour is treated as black enough that the positive
    // soft-light rail renders the pattern invisible (soft-light against a black backdrop is black for
    // every source value). Below it we force the negative mask rail, which hard-draws its own black
    // backdrop and paints the pattern in a lifted tint. Derived from the live colour, never from
    // isMonetAmoled()/isDark(): a user's Monet palette can push the Dark variants arbitrarily dark,
    // so a name-based rule would drift. Monet Dark measures ~0.10 (stays positive, soft-light lifts a
    // white pattern to ~0.30); AMOLED is 0.0 (falls to the negative rail).
    private static final double BLACK_RAIL_LUMINANCE = 0.05;

    // How far a near-black stop is lifted toward white so the pattern is visible over the negative
    // rail's black backdrop. High on purpose: today's frozen-JPEG AMOLED rail already draws a
    // near-white pattern on black, and this keeps that look rather than regressing it to a faint grey.
    private static final float BLACK_RAIL_LIFT = 0.85f;

    // Called from Theme.applyTheme after currentTheme is set and before refreshThemeColors, so the
    // active variant already points at the live record by the time loadWallpaper reads it.
    public static void onApplyMonetTheme(Theme.ThemeInfo themeInfo) {
        if (themeInfo == null || !themeInfo.isMonet()) {
            return;
        }
        if (initialized) {
            themeInfo.overrideWallpaper = shared;
            if (shared != null) {
                // re-parent so getKey() (constant for any isMonet() theme) and any future parentTheme
                // read see the active variant rather than whichever one was adopted first.
                shared.parentTheme = themeInfo;
            }
        } else {
            // Adopt the cold-start copy. All seven load the same JSON under SHARED_OWP_KEY, so which
            // Monet theme applies first does not matter -- the copies are identical.
            shared = themeInfo.overrideWallpaper;
            initialized = true;
        }
    }

    // Called from Theme.ThemeInfo.setOverrideWallpaper when the user picks or clears a pattern under a
    // Monet theme, so the shared instance and the other six variants stay in step at runtime.
    public static void onSetOverrideWallpaper(Theme.ThemeInfo themeInfo, Theme.OverrideWallpaperInfo info) {
        if (themeInfo == null || !themeInfo.isMonet()) {
            return;
        }
        // info == null is a clear. Keep initialized true so the next Monet apply republishes null
        // instead of re-adopting a stale sibling copy and resurrecting the pattern the user just
        // cleared -- that is the one way this mechanism could visibly undo a user action.
        shared = info;
        initialized = true;
    }

    // True only for a genuine masked pattern composited over a base colour. Upstream's own pattern test
    // (createBackgroundDrawable, Theme.java:9587) is color != 0 && !isDefault() && !isColor(): a pattern
    // carries a non-zero base colour, while every full-image override -- a gallery FileWallpaper (empty
    // slug) and a non-pattern server wallpaper from Telegram's own collection (real slug) -- stores
    // color == 0 and only its file. Without the color guard either image, if picked under a Monet theme,
    // would run through buildRenderSnapshot as if it were a pattern and be misrendered. A non-empty-slug
    // test can't separate them, since a server image has a real slug too; the base colour is what
    // actually distinguishes a pattern from a full-bleed image (ThemePreviewActivity.java:2644-2689).
    public static boolean isMonetPattern(Theme.ThemeInfo themeInfo, Theme.OverrideWallpaperInfo owp) {
        return themeInfo != null && owp != null && themeInfo.isMonet()
                && owp.color != 0 && !owp.isDefault() && !owp.isColor();
    }

    // The single luminance decision. Below the threshold the positive soft-light rail renders the
    // pattern invisible on a near-black backdrop, so the pattern goes on the negative mask rail
    // instead. Both the render path (buildRenderSnapshot) and the preview path call these three so
    // they can never disagree about a colour's rail, its lifted stop or its intensity sign.
    public static boolean isNegativeRail(int bg) {
        return Color.luminance(bg) < BLACK_RAIL_LUMINANCE;
    }

    // The gradient stop fed to the drawable: a near-black colour is lifted toward neutral so the
    // pattern is visible over the negative rail's black backdrop; anything lighter is passed through.
    public static int liftedStop(int bg) {
        return isNegativeRail(bg) ? ColorUtils.blendARGB(bg, Color.WHITE, BLACK_RAIL_LIFT) : bg;
    }

    // Magnitude is always abs so a sign that may have leaked into a stored record from an older build
    // cannot invert the pattern; the rail then picks the sign. >= 0 soft-light, < 0 the mask rail.
    public static float signedIntensity(int bg, float magnitude) {
        float m = Math.abs(magnitude);
        return isNegativeRail(bg) ? -m : m;
    }

    // Build a throwaway render-only OverrideWallpaperInfo from a SINGLE read of the live palette. The
    // persisted record is never mutated, so the sign computed here can never reach disk (a stored sign
    // desyncs in a shared light/dark model), and each loadWallpaper call hands its own snapshot to the
    // themeQueue runnable -- the sign and the four gradient stops always travel as one consistent unit
    // instead of four independently-read fields that a concurrent sync could tear.
    public static Theme.OverrideWallpaperInfo buildRenderSnapshot(Theme.ThemeInfo themeInfo, SparseIntArray currentColors, Theme.OverrideWallpaperInfo source) {
        int bg = currentColors.get(Theme.key_chat_wallpaper);

        Theme.OverrideWallpaperInfo snap = new Theme.OverrideWallpaperInfo();
        snap.parentTheme = themeInfo;
        snap.fileName = source.fileName;
        snap.originalFileName = source.originalFileName;
        snap.slug = source.slug;
        snap.isBlurred = source.isBlurred;
        snap.isMotion = source.isMotion;
        snap.rotation = source.rotation;

        int stop = liftedStop(bg);

        // Degenerate 4-equal-colour gradient: trips createBackgroundDrawable's gradient gate (which
        // wants gradientColor2 != 0) so it rebuilds the MotionBackgroundDrawable from the live palette
        // and re-applies the saved mask, instead of redrawing a file baked at pick time. Four equal
        // stops produce a flat one-colour bitmap with no banding and no animation.
        snap.color = stop;
        snap.gradientColor1 = stop;
        snap.gradientColor2 = stop;
        snap.gradientColor3 = stop;
        snap.intensity = signedIntensity(bg, source.intensity);
        return snap;
    }
}
