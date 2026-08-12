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
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.CombinedDrawable;

import java.util.ArrayList;
import java.util.LinkedHashMap;

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

    /**
     * Privacy-flavoured glyphs offered ahead of the folder icons in this feature's picker. Keys are
     * feature-prefixed plain ASCII, so they can never collide with the emoji keys in
     * {@code FolderIconHelper.folderIcons} -- a profile stores one namespace or the other and
     * {@link #drawableFor} tries this map first, then falls back to the folder set (which itself
     * ends at {@code filter_custom} for anything it doesn't know). Every drawable here already
     * ships in the app; no new asset is added for this list.
     */
    private static final LinkedHashMap<String, Integer> privacyIcons = new LinkedHashMap<>();
    private static final LinkedHashMap<String, Integer> privacyIconNames = new LinkedHashMap<>();

    static {
        put("pp_lock", R.drawable.outline_header_lock_24, R.string.PrivacyProfileIconLock);
        put("pp_shield", R.drawable.outline_shield_plain_24, R.string.PrivacyProfileIconShield);
        put("pp_key", R.drawable.baseline_vpn_key_24, R.string.PrivacyProfileIconKey);
        put("pp_fingerprint", R.drawable.fingerprint_solar, R.string.PrivacyProfileIconFingerprint);
        put("pp_passcode", R.drawable.msg_pin_code_solar, R.string.PrivacyProfileIconPasscode);
        put("pp_phone", R.drawable.profile_phone_solar, R.string.PrivacyProfileIconPhone);
        put("pp_devices", R.drawable.msg_devices_solar, R.string.PrivacyProfileIconDevices);
        put("pp_proxy", R.drawable.proxy_on_solar, R.string.PrivacyProfileIconProxy);
        put("pp_wifi", R.drawable.baseline_wifi_24, R.string.PrivacyProfileIconWifi);
        put("pp_eye", R.drawable.msg_views_solar, R.string.PrivacyProfileIconEye);
        put("pp_blocked", R.drawable.msg_block_solar, R.string.PrivacyProfileIconBlocked);
        put("pp_night", R.drawable.msg_night_auto_solar, R.string.PrivacyProfileIconNight);
    }

    private static void put(String key, int drawableRes, int nameRes) {
        privacyIcons.put(key, drawableRes);
        privacyIconNames.put(key, nameRes);
    }

    /** Resolves a stored icon key: this feature's own glyphs first, then the shared folder set. */
    public static int drawableFor(String key) {
        Integer own = key != null ? privacyIcons.get(key) : null;
        return own != null ? own : FolderIconHelper.getTabIcon(key);
    }

    /** Spoken name for a picker cell; the folder glyphs have never had one and still don't. */
    @Nullable
    public static CharSequence nameFor(String key) {
        Integer nameRes = key != null ? privacyIconNames.get(key) : null;
        return nameRes != null ? LocaleController.getString(nameRes) : null;
    }

    /** The picker's order: this feature's glyphs, then the whole folder set behind them. */
    public static ArrayList<String> pickerKeys() {
        ArrayList<String> keys = new ArrayList<>(privacyIcons.keySet());
        keys.addAll(FolderIconHelper.folderIcons.keySet());
        return keys;
    }

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
        Drawable icon = ContextCompat.getDrawable(context, drawableFor(profile.icon));
        icon = icon != null ? icon.mutate() : null;
        if (icon != null) {
            icon.setColorFilter(new PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN));
        }
        int size = AndroidUtilities.dp(sizeDp);
        int circleColor = PrivacyProfileColorRow.colorFor(profile.colorSeed, profile.tone);
        if (ringed) {
            return new RingedProfileDrawable(size, circleColor, icon);
        }
        CombinedDrawable combined = new CombinedDrawable(Theme.createCircleDrawable(size, circleColor), icon);
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
