package org.telegram.ui;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.R;

public class LauncherIconController {
    // NagramX: the alias the manifest ships enabled. Exactly one alias may carry android:enabled="true",
    // permanently and on both package variants - a second one becomes a second launcher entry for anyone
    // who has ever picked an icon, and it appears at install time where no code can intercept it. A
    // per-variant or per-version default has to come from setIcon() instead, never from this attribute.
    private static final LauncherIcon MANIFEST_DEFAULT = LauncherIcon.BLUE;

    // NagramX: the icon this package variant wants to start on, which is not the same question - the
    // manifest cannot vary per user, and making a second alias enabled there gave anyone with an explicit
    // pick two launcher entries from install time onwards. Applied from code instead, below.
    public static LauncherIcon getDefaultIcon() {
        for (LauncherIcon icon : LauncherIcon.values()) {
            if (icon.key.equals(org.telegram.messenger.BuildConfig.DEFAULT_LAUNCHER_ICON_KEY)) {
                return icon;
            }
        }
        return MANIFEST_DEFAULT;
    }

    // NagramX: runs from ApplicationLoader.onCreate. Two jobs, and both have to leave exactly one alias
    // live, because the launcher reads component state at install time and shows one entry per live alias.
    public static void tryFixLauncherIconIfNeeded() {
        Context ctx = ApplicationLoader.applicationContext;
        PackageManager pm = ctx.getPackageManager();
        LauncherIcon chosen = null;
        boolean untouched = true;
        int live = 0;
        for (LauncherIcon icon : LauncherIcon.values()) {
            int state = pm.getComponentEnabledSetting(icon.getComponentName(ctx));
            if (state != PackageManager.COMPONENT_ENABLED_STATE_DEFAULT) {
                untouched = false;
            }
            if (state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED) {
                chosen = icon;
                live++;
            } else if (state == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT && icon == MANIFEST_DEFAULT) {
                live++;
            }
        }

        // Nobody has ever picked an icon, so moving them costs no choice. This is the only path that
        // applies the variant preference, which is why an existing pick survives an update untouched.
        if (untouched) {
            if (getDefaultIcon() != MANIFEST_DEFAULT) {
                setIcon(getDefaultIcon());
            }
            return;
        }

        // live == 0 is the original repair case: nothing is launchable, so put something back.
        //
        // live > 1 is NOT reachable from here and this branch must not be read as covering it. The count
        // treats a DEFAULT component as live only for MANIFEST_DEFAULT, so a second alias shipped
        // android:enabled="true" would be invisible to it. Counting it properly would not help anyway -
        // the launcher reads component state at install time, so the duplicate entry is on screen before
        // this method ever runs. The only real defence is the invariant above: exactly one alias enabled
        // in the manifest, permanently. See docs/codemap/upstream-traps.md.
        if (live != 1) {
            setIcon(chosen != null ? chosen : getDefaultIcon());
        }
    }

    public static boolean isEnabled(LauncherIcon icon) {
        Context ctx = ApplicationLoader.applicationContext;
        int i = ctx.getPackageManager().getComponentEnabledSetting(icon.getComponentName(ctx));
        return i == PackageManager.COMPONENT_ENABLED_STATE_ENABLED || i == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT && icon == MANIFEST_DEFAULT;
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
