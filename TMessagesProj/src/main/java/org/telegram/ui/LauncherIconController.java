package org.telegram.ui;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.R;

public class LauncherIconController {
    // NagramX: which alias ships android:enabled="true" is decided in build.gradle and differs per package
    // variant, so a component still sitting at COMPONENT_ENABLED_STATE_DEFAULT does not always mean Blue.
    // Falls back to BLUE if the key ever stops matching an entry, so a mismatch costs the wrong default
    // rather than an app with no launcher icon at all.
    public static LauncherIcon getDefaultIcon() {
        for (LauncherIcon icon : LauncherIcon.values()) {
            if (icon.key.equals(org.telegram.messenger.BuildConfig.DEFAULT_LAUNCHER_ICON_KEY)) {
                return icon;
            }
        }
        return LauncherIcon.BLUE;
    }

    // NagramX: counts live aliases instead of stopping at the first, because setIcon() only ever wrote
    // explicit state for the icons that existed when it ran. An icon added later sits at DEFAULT forever,
    // and on the variant where DEFAULT means enabled that lands a second launcher entry beside the one the
    // user picked. Re-asserting their choice writes the explicit DISABLED the old setIcon() never could.
    public static void tryFixLauncherIconIfNeeded() {
        Context ctx = ApplicationLoader.applicationContext;
        PackageManager pm = ctx.getPackageManager();
        LauncherIcon chosen = null;
        int live = 0;
        for (LauncherIcon icon : LauncherIcon.values()) {
            int state = pm.getComponentEnabledSetting(icon.getComponentName(ctx));
            if (state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED) {
                chosen = icon;
                live++;
            } else if (state == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT && icon == getDefaultIcon()) {
                live++;
            }
        }
        if (live == 1) {
            return;
        }

        // live == 0 is the original repair case; live > 1 keeps the explicit pick and drops the rest
        setIcon(chosen != null ? chosen : getDefaultIcon());
    }

    public static boolean isEnabled(LauncherIcon icon) {
        Context ctx = ApplicationLoader.applicationContext;
        int i = ctx.getPackageManager().getComponentEnabledSetting(icon.getComponentName(ctx));
        return i == PackageManager.COMPONENT_ENABLED_STATE_ENABLED || i == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT && icon == getDefaultIcon();
    }

    public static void setIcon(LauncherIcon icon) {
        Context ctx = ApplicationLoader.applicationContext;
        PackageManager pm = ctx.getPackageManager();
        for (LauncherIcon i : LauncherIcon.values()) {
            pm.setComponentEnabledSetting(i.getComponentName(ctx), i == icon ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED :
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP);
        }
    }

    public enum LauncherIcon {
        DEFAULT("DefaultIcon", R.mipmap.ic_launcher_nagram, R.mipmap.icon_background_nagram, R.string.AppIconDefault),
        // NagramX: fork-drawn icons. Each entry's key must keep a matching
        // <activity-alias android:name="org.telegram.messenger.<key>"> in the
        // manifest - without it getComponentName() points at nothing and
        // setIcon() silently does nothing.
        RIBBON("RibbonIcon", R.drawable.ic_launcher_nagram_ribbon_background, R.drawable.ic_launcher_nagram_ribbon_foreground, R.string.AppIconRibbon),
        RIBBON_DAWN("RibbonDawnIcon", R.drawable.ic_launcher_nagram_ribbon_dawn_background, R.drawable.ic_launcher_nagram_ribbon_dawn_foreground, R.string.AppIconRibbonDawn),
        RIBBON_AMBER("RibbonAmberIcon", R.drawable.ic_launcher_nagram_ribbon_amber_background, R.drawable.ic_launcher_nagram_ribbon_amber_foreground, R.string.AppIconRibbonAmber),
        GOOGLE("GoogleIcon", R.drawable.ic_launcher_nagram_google_background, R.drawable.ic_launcher_nagram_google_foreground, R.string.AppIconGoogle),
        COLORFUL("ColorfulIcon", R.drawable.ic_launcher_nagram_colorful_background, R.drawable.ic_launcher_nagram_colorful_foreground, R.string.AppIconColorful),
        DARKGREEN("DarkGreenIcon", R.drawable.ic_launcher_nagram_darkgreen_background, R.drawable.ic_launcher_nagram_darkgreen_foreground, R.string.AppIconDarkGreen),
        NEON("NeonIcon", R.drawable.ic_launcher_nagram_neon_background, R.drawable.ic_launcher_nagram_neon_foreground, R.string.AppIconNeon),
        NIELLO("NielloIcon", R.drawable.ic_launcher_nagram_round_niello_background, R.drawable.ic_launcher_nagram_round_niello_foreground, R.string.AppIconNiello),
        BLUE("BlueIcon", R.color.nagram_block_round_background, R.drawable.ic_launcher_nagram_blue_foreground, R.string.AppIconBlue),
        DARKBLUE("DarkBlueIcon", R.color.nagram_dark_blue_background, R.drawable.ic_launcher_nagram_dark_blue_foreground, R.string.AppIconDarkBlue),
        BLURBLUE("BlurBlueIcon", R.drawable.ic_launcher_nagram_blur_blue_background, R.drawable.ic_launcher_nagram_blur_blue_foreground, R.string.AppIconBlurBlue),
        TELEGRAM("TelegramIcon", R.drawable.icon_background_sa, R.mipmap.icon_foreground_sa, R.string.AppIconTelegramOriginal),
        VINTAGE("VintageIcon", R.drawable.icon_6_background_sa, R.mipmap.icon_6_foreground_sa, R.string.AppIconVintage),
        AQUA("AquaIcon", R.drawable.icon_4_background_sa, R.mipmap.icon_foreground_sa, R.string.AppIconAqua),
        PREMIUM("PremiumIcon", R.drawable.icon_3_background_sa, R.mipmap.icon_3_foreground_sa, R.string.AppIconPremium),
        TURBO("TurboIcon", R.drawable.icon_5_background_sa, R.mipmap.icon_5_foreground_sa, R.string.AppIconTurbo),
        NOX("NoxIcon", R.mipmap.icon_2_background_sa, R.mipmap.icon_foreground_sa, R.string.AppIconNox);

        public final String key;
        public final int background;
        public final int foreground;
        public final int title;
        public final boolean premium;

        private ComponentName componentName;

        public ComponentName getComponentName(Context ctx) {
            if (componentName == null) {
                componentName = new ComponentName(ctx.getPackageName(), "org.telegram.messenger." + key);
            }
            return componentName;
        }

        LauncherIcon(String key, int background, int foreground, int title) {
            this(key, background, foreground, title, false);
        }

        LauncherIcon(String key, int background, int foreground, int title, boolean premium) {
            this.key = key;
            this.background = background;
            this.foreground = foreground;
            this.title = title;
            this.premium = premium;
        }

        public boolean isNekoX() {
            return this == DEFAULT;
        }
    }
}
