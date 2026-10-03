package com.radolyn.ayugram.headerbg;

import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.DrawableWrapper;
import android.util.SparseIntArray;
import android.view.View;

import androidx.core.graphics.ColorUtils;

import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.SimpleTextView;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.Components.AnimatedTextView;
import org.telegram.ui.Components.ChatAvatarContainer;

import xyz.nextalone.nagram.helpers.InterfaceStyleSolidHeader;

/**
 * The chat header's foreground colours over a header photo. The Classic solid header is resolved first;
 * the photo only replaces the result when it fails to contrast with what the drawer sees behind the header.
 * Readers go through the colour the drawer last pushed ({@link HeaderBgDrawer#applied}), never its live
 * probe, so everything read between two pushes matches what is already on screen.
 */
public final class HeaderBgForeground {

    static final int THEME = 0;
    static final int LIGHT = 1;
    static final int DARK = 2;

    // Text this far apart from the band is left alone: WCAG's large-text floor.
    private static final double MIN_CONTRAST = 3.0;

    // The CHAT_HEADER key set. Online/typing stays at full strength so it still differs from "last seen".
    private static final SparseIntArray ON_DARK = new SparseIntArray();
    private static final SparseIntArray ON_LIGHT = new SparseIntArray();

    static {
        ON_DARK.put(Theme.key_actionBarDefaultIcon, 0xffffffff);
        ON_DARK.put(Theme.key_actionBarDefaultTitle, 0xffffffff);
        ON_DARK.put(Theme.key_actionBarDefaultSubtitle, 0xccffffff);
        ON_DARK.put(Theme.key_actionBarDefaultSelector, 0x33ffffff);
        ON_DARK.put(Theme.key_actionBarDefaultSearch, 0xffffffff);
        ON_DARK.put(Theme.key_actionBarDefaultSearchPlaceholder, 0x88ffffff);
        ON_DARK.put(Theme.key_chat_status, 0xffffffff);
        ON_DARK.put(Theme.key_chat_lockIcon, 0xffffffff);
        ON_DARK.put(Theme.key_chat_muteIcon, 0xb3ffffff);

        ON_LIGHT.put(Theme.key_actionBarDefaultIcon, 0xde000000);
        ON_LIGHT.put(Theme.key_actionBarDefaultTitle, 0xde000000);
        ON_LIGHT.put(Theme.key_actionBarDefaultSubtitle, 0x8a000000);
        ON_LIGHT.put(Theme.key_actionBarDefaultSelector, 0x1a000000);
        ON_LIGHT.put(Theme.key_actionBarDefaultSearch, 0xde000000);
        ON_LIGHT.put(Theme.key_actionBarDefaultSearchPlaceholder, 0x61000000);
        ON_LIGHT.put(Theme.key_chat_status, 0xde000000);
        ON_LIGHT.put(Theme.key_chat_lockIcon, 0xde000000);
        ON_LIGHT.put(Theme.key_chat_muteIcon, 0x61000000);
    }

    private HeaderBgForeground() {
    }

    /** The header's colour without the photo: the theme's, or the Classic header's when that is on. */
    static int base(int key, Theme.ResourcesProvider resourcesProvider) {
        return InterfaceStyleSolidHeader.chatHeaderColor(key, Theme.getColor(key, resourcesProvider));
    }

    /** Which foreground set reads best over the opaque band colour; THEME while the header's own one does. */
    static int choose(int band, Theme.ResourcesProvider resourcesProvider) {
        if (ColorUtils.calculateContrast(base(Theme.key_actionBarDefaultTitle, resourcesProvider), band) >= MIN_CONTRAST) {
            return THEME;
        }
        return ColorUtils.calculateContrast(ON_DARK.get(Theme.key_actionBarDefaultTitle), band)
                >= ColorUtils.calculateContrast(ON_LIGHT.get(Theme.key_actionBarDefaultTitle), band) ? LIGHT : DARK;
    }

    /** Whether the title ends up dark under that choice, which is when the status bar icons go dark too. */
    static boolean darkTitle(int mode, Theme.ResourcesProvider resourcesProvider) {
        if (mode != THEME) {
            return mode == DARK;
        }
        return ColorUtils.calculateLuminance(ColorUtils.setAlphaComponent(base(Theme.key_actionBarDefaultTitle, resourcesProvider), 255)) < 0.5;
    }

    private static int applied(ChatActivity fragment) {
        ActionBar actionBar = fragment != null ? fragment.getActionBar() : null;
        return actionBar != null && actionBar.naxHeaderBg != null ? actionBar.naxHeaderBg.applied : THEME;
    }

    private static int map(int mode, int key, int color) {
        SparseIntArray table = mode == LIGHT ? ON_DARK : mode == DARK ? ON_LIGHT : null;
        int index = table != null ? table.indexOfKey(key) : -1;
        return index >= 0 ? table.valueAt(index) : color;
    }

    /** A chat header colour: the Classic header's or the theme's, unless the header photo needs another. */
    public static int color(ChatActivity fragment, int key, int themedColor) {
        return map(applied(fragment), key, InterfaceStyleSolidHeader.chatHeaderColor(key, themedColor));
    }

    /** For the auto-delete timer, which reads the title key through its own provider at draw time. */
    public static Theme.ResourcesProvider wrapChatHeader(Theme.ResourcesProvider base, ChatActivity fragment) {
        return InterfaceStyleSolidHeader.wrap(base, (key, color) -> color(fragment, key, color));
    }

    /** The shared mute or lock icon as a private copy that picks its colour as it draws. */
    public static Drawable titleIcon(ChatActivity fragment, Drawable icon, int colorKey) {
        if (icon == null || icon.getConstantState() == null) {
            return icon;
        }
        return new TitleIcon(icon.getConstantState().newDrawable().mutate(), fragment, colorKey);
    }

    /**
     * Pushes the header's colours. Runs after createView's Classic push, from the theme delegate once the
     * key-only descriptions have reset them, and from the drawer when its choice no longer matches the last push.
     */
    public static void applyChatHeader(ChatActivity fragment) {
        ActionBar actionBar = fragment.getActionBar();
        ChatAvatarContainer avatarContainer = fragment.getAvatarContainer();
        HeaderBgDrawer drawer = actionBar != null ? actionBar.naxHeaderBg : null;
        if (drawer == null) {
            InterfaceStyleSolidHeader.applyChatHeader(actionBar, avatarContainer);
            return;
        }
        drawer.applied = drawer.wanted();
        Theme.ResourcesProvider rp = fragment.getResourceProvider();
        actionBar.setItemsColor(color(fragment, Theme.key_actionBarDefaultIcon, Theme.getColor(Theme.key_actionBarDefaultIcon, rp)), false);
        actionBar.setItemsBackgroundColor(color(fragment, Theme.key_actionBarDefaultSelector, Theme.getColor(Theme.key_actionBarDefaultSelector, rp)), false);
        int title = color(fragment, Theme.key_actionBarDefaultTitle, Theme.getColor(Theme.key_actionBarDefaultTitle, rp));
        actionBar.setTitleColor(title);
        int search = color(fragment, Theme.key_actionBarDefaultSearch, Theme.getColor(Theme.key_actionBarDefaultSearch, rp));
        actionBar.setSearchTextColor(search, false);
        actionBar.setSearchTextColor(color(fragment, Theme.key_actionBarDefaultSearchPlaceholder, Theme.getColor(Theme.key_actionBarDefaultSearchPlaceholder, rp)), true);
        actionBar.setSearchCursorColor(search);
        if (avatarContainer == null) {
            return;
        }
        avatarContainer.getTitleTextView().setTextColor(title);
        // The subtitle is tagged with whichever of the two keys it is showing; anything else reads as the plain subtitle.
        View subtitle = avatarContainer.getSubtitleTextView();
        Object tag = subtitle != null ? subtitle.getTag() : null;
        if (tag instanceof Integer) {
            int key = (Integer) tag == Theme.key_chat_status ? Theme.key_chat_status : Theme.key_actionBarDefaultSubtitle;
            int color = color(fragment, key, Theme.getColor(key, rp));
            if (subtitle instanceof SimpleTextView) {
                ((SimpleTextView) subtitle).setTextColor(color);
            } else if (subtitle instanceof AnimatedTextView) {
                ((AnimatedTextView) subtitle).setTextColor(color);
            }
        }
        avatarContainer.updateColors();
        avatarContainer.updateTimeZonePill();
        // Both draw their colour lazily: the title icons and the timer.
        avatarContainer.getTitleTextView().invalidate();
        if (avatarContainer.getTimeItem() != null) {
            avatarContainer.getTimeItem().invalidate();
        }
    }

    // Tint calls from outside (theme descriptions recolour the title's side drawables) are ignored.
    private static final class TitleIcon extends DrawableWrapper {
        private final ChatActivity fragment;
        private final int colorKey;
        private int color;
        private boolean tinted;

        TitleIcon(Drawable copy, ChatActivity fragment, int colorKey) {
            super(copy);
            this.fragment = fragment;
            this.colorKey = colorKey;
        }

        @Override
        public void draw(Canvas canvas) {
            int c = color(fragment, colorKey, Theme.getColor(colorKey, fragment.getResourceProvider()));
            if (!tinted || c != color) {
                tinted = true;
                color = c;
                getDrawable().setColorFilter(new PorterDuffColorFilter(c, PorterDuff.Mode.MULTIPLY));
            }
            super.draw(canvas);
        }

        @Override
        public void setColorFilter(ColorFilter colorFilter) {
        }

        @Override
        public void setTintList(ColorStateList tint) {
        }

        @Override
        public void setTintMode(PorterDuff.Mode tintMode) {
        }
    }
}
