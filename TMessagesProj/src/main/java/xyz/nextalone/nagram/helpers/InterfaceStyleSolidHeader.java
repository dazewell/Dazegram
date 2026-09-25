package xyz.nextalone.nagram.helpers;

import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.Drawable;
import android.util.SparseArray;
import android.util.SparseIntArray;
import android.view.View;

import androidx.core.graphics.ColorUtils;

import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.SimpleTextView;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AnimatedTextView;
import org.telegram.ui.Components.ChatAvatarContainer;
import org.telegram.ui.Components.FragmentSearchField;

import xyz.nextalone.nagram.NaConfig;

// MD3 "Match Classic and Day header color": the chat header and the chat-list top surface go solid,
// and on Classic they get back the pre-12.4.0 blue header. The colours are only ever handed to those
// two consumers; the shared palette keeps its 12.4.0 values so nothing else on screen changes.
public final class InterfaceStyleSolidHeader {

    private static final int CLASSIC_SURFACE = 0xff527da3;
    private static final int CLASSIC_ARCHIVED_SURFACE = 0xff6f7a87;

    // Keys a chat header reads. key_actionBarDefault is left out on purpose: the surface comes from the
    // header's own provider, and ChatAvatarContainer also hands that key to ProfileActivity.
    private static final SparseIntArray CHAT_HEADER = new SparseIntArray();
    // Keys the chat-list top bar reads through DialogsActivity.getThemedColor. The title keys are left out
    // because the item-options popup reads key_actionBarDefaultTitle there too; titles are pushed directly.
    private static final SparseIntArray CHAT_LIST = new SparseIntArray();
    private static final SparseIntArray CHAT_LIST_TITLES = new SparseIntArray();

    static {
        CHAT_HEADER.put(Theme.key_actionBarDefaultIcon, 0xffffffff);
        CHAT_HEADER.put(Theme.key_actionBarDefaultTitle, 0xffffffff);
        CHAT_HEADER.put(Theme.key_actionBarDefaultSubtitle, 0xffd5e8f7);
        CHAT_HEADER.put(Theme.key_actionBarDefaultSelector, 0xff406d94);
        CHAT_HEADER.put(Theme.key_actionBarDefaultSearch, 0xffffffff);
        CHAT_HEADER.put(Theme.key_actionBarDefaultSearchPlaceholder, 0x88ffffff);
        CHAT_HEADER.put(Theme.key_chat_status, 0xffd5e8f7);
        CHAT_HEADER.put(Theme.key_chat_lockIcon, 0xffffffff);
        CHAT_HEADER.put(Theme.key_chat_muteIcon, 0xffb1cce3);

        CHAT_LIST.put(Theme.key_actionBarDefault, CLASSIC_SURFACE);
        CHAT_LIST.put(Theme.key_actionBarDefaultArchived, CLASSIC_ARCHIVED_SURFACE);
        CHAT_LIST.put(Theme.key_actionBarDefaultIcon, 0xffffffff);
        CHAT_LIST.put(Theme.key_actionBarDefaultSelector, 0xff406d94);
        CHAT_LIST.put(Theme.key_actionBarDefaultSearch, 0xffffffff);
        CHAT_LIST.put(Theme.key_actionBarDefaultSearchPlaceholder, 0x88ffffff);
        CHAT_LIST.put(Theme.key_actionBarTabActiveText, 0xffffffff);
        CHAT_LIST.put(Theme.key_actionBarTabUnactiveText, 0xffd5e8f7);
        CHAT_LIST.put(Theme.key_actionBarTabLine, 0xffffffff);
        CHAT_LIST.put(Theme.key_actionBarTabSelector, 0xff406d94);
        CHAT_LIST.put(Theme.key_actionBarDefaultArchivedIcon, 0xffffffff);
        CHAT_LIST.put(Theme.key_actionBarDefaultArchivedSelector, 0xff5e6772);
        CHAT_LIST.put(Theme.key_actionBarDefaultArchivedSearch, 0xffffffff);
        CHAT_LIST.put(Theme.key_actionBarDefaultArchivedSearchPlaceholder, 0x88ffffff);
        // 12.4.0 added this for the logo title; Telegram blue on the old blue bar is unreadable.
        CHAT_LIST.put(Theme.key_telegram_color_dialogsLogo, 0xffffffff);

        CHAT_LIST_TITLES.put(Theme.key_actionBarDefaultTitle, 0xffffffff);
        CHAT_LIST_TITLES.put(Theme.key_actionBarDefaultArchivedTitle, 0xffffffff);
    }

    private InterfaceStyleSolidHeader() {
    }

    // The theme actually on screen: getCurrentTheme() is the day-theme choice, so it stays Blue under auto-night.
    private static boolean activeThemeIs(String name) {
        final Theme.ThemeInfo theme = Theme.getActiveTheme();
        return theme != null && name.equals(theme.name);
    }

    public static boolean active() {
        return InterfaceStyleController.MATCH_CLASSIC_DAY_HEADER_AVAILABLE
                && InterfaceStyleController.isMaterialDesign3()
                && NaConfig.INSTANCE.getInterfaceStyleMatchClassicDayHeader().Bool()
                && (activeThemeIs("Blue") || activeThemeIs("Day"));
    }

    private static boolean classic() {
        return active() && activeThemeIs("Blue");
    }

    public static boolean chatHeader() {
        return InterfaceStyleController.applyChatHeader() && active();
    }

    // Theme-independent, so a delegate can also undo the Classic colours when the theme switches away.
    public static boolean chatHeaderOptedIn() {
        return InterfaceStyleController.MATCH_CLASSIC_DAY_HEADER_AVAILABLE
                && InterfaceStyleController.applyChatHeader()
                && NaConfig.INSTANCE.getInterfaceStyleMatchClassicDayHeader().Bool();
    }

    public static boolean chatListTopBar() {
        return InterfaceStyleController.applyChatListTopBar() && active();
    }

    private static boolean chatHeaderClassic() {
        return InterfaceStyleController.applyChatHeader() && classic();
    }

    private static boolean chatListTopBarClassic() {
        return InterfaceStyleController.applyChatListTopBar() && classic();
    }

    // Before 12.4.0 a Classic accent recoloured this header through the palette's own hue shift; reuse it.
    private static int accented(int color) {
        final Theme.ThemeInfo theme = Theme.getActiveTheme();
        final Theme.ThemeAccent accent = theme != null ? theme.getAccent(false) : null;
        if (accent == null) {
            return color;
        }
        return Theme.changeColorAccent(theme, accent.accentColor, color);
    }

    private static int lookup(SparseIntArray table, int key, int fallback) {
        final int index = table.indexOfKey(key);
        return index >= 0 ? accented(table.valueAt(index)) : fallback;
    }

    public static int chatHeaderSurface(int themedColor) {
        return chatHeaderClassic() ? accented(CLASSIC_SURFACE) : ColorUtils.setAlphaComponent(themedColor, 255);
    }

    public static int chatHeaderColor(int key, int themedColor) {
        return chatHeaderClassic() ? lookup(CHAT_HEADER, key, themedColor) : themedColor;
    }

    public static int chatListColor(int key, int themedColor) {
        return chatListTopBarClassic() ? lookup(CHAT_LIST, key, themedColor) : themedColor;
    }

    // DialogStoriesCell names sit on the surface too, but only it may map the title keys.
    public static int chatListStoriesColor(int key, int themedColor) {
        return chatListTopBarClassic() ? lookup(CHAT_LIST_TITLES, key, themedColor) : themedColor;
    }

    public static int chatListSurfaceKey(int defaultKey, boolean archived) {
        return archived && chatListTopBarClassic() ? Theme.key_actionBarDefaultArchived : defaultKey;
    }

    // Classic search used to turn the bar white, and DialogsActivity still blends the icons towards the
    // action-mode colour as search opens, so the surface follows them.
    public static int chatListSurface(int surfaceColor, float searchProgress) {
        if (searchProgress <= 0f || !chatListTopBarClassic()) {
            return surfaceColor;
        }
        return ColorUtils.blendARGB(surfaceColor, ColorUtils.setAlphaComponent(Theme.getColor(Theme.key_windowBackgroundWhite), 255), Math.min(1f, searchProgress));
    }

    // Theme descriptions re-run the chat delegate every animation frame, so the tinted copies are reused.
    private static final SparseArray<Drawable> tintedSources = new SparseArray<>();
    private static final SparseArray<Drawable> tintedCopies = new SparseArray<>();
    private static final SparseIntArray tintedColors = new SparseIntArray();

    public static Drawable chatHeaderIcon(Drawable icon, int colorKey) {
        if (icon == null || icon.getConstantState() == null || !chatHeaderClassic()) {
            return icon;
        }
        final int color = lookup(CHAT_HEADER, colorKey, Color.WHITE);
        Drawable copy = tintedCopies.get(colorKey);
        if (copy == null || tintedSources.get(colorKey) != icon || tintedColors.get(colorKey) != color) {
            copy = icon.getConstantState().newDrawable().mutate();
            copy.setColorFilter(new PorterDuffColorFilter(color, PorterDuff.Mode.MULTIPLY));
            tintedSources.put(colorKey, icon);
            tintedCopies.put(colorKey, copy);
            tintedColors.put(colorKey, color);
        }
        return copy;
    }

    // ActionBar colours are pushed once by the fragment and again by key-only ThemeDescriptions on every theme
    // change, so both fragments call this after createView and from their description delegate.
    public static void applyChatHeader(ActionBar actionBar, ChatAvatarContainer avatarContainer) {
        if (actionBar == null || !chatHeaderClassic()) {
            return;
        }
        actionBar.setItemsColor(lookup(CHAT_HEADER, Theme.key_actionBarDefaultIcon, 0), false);
        actionBar.setItemsBackgroundColor(lookup(CHAT_HEADER, Theme.key_actionBarDefaultSelector, 0), false);
        actionBar.setTitleColor(lookup(CHAT_HEADER, Theme.key_actionBarDefaultTitle, 0));
        actionBar.setSearchTextColor(lookup(CHAT_HEADER, Theme.key_actionBarDefaultSearch, 0), false);
        actionBar.setSearchTextColor(lookup(CHAT_HEADER, Theme.key_actionBarDefaultSearchPlaceholder, 0), true);
        actionBar.setSearchCursorColor(lookup(CHAT_HEADER, Theme.key_actionBarDefaultSearch, 0));
        if (avatarContainer != null) {
            avatarContainer.getTitleTextView().setTextColor(lookup(CHAT_HEADER, Theme.key_actionBarDefaultTitle, 0));
            // The subtitle is tagged with whichever key it is currently showing: online status or plain subtitle.
            final View subtitle = avatarContainer.getSubtitleTextView();
            final Object tag = subtitle != null ? subtitle.getTag() : null;
            if (tag instanceof Integer) {
                final int color = lookup(CHAT_HEADER, (Integer) tag, lookup(CHAT_HEADER, Theme.key_actionBarDefaultSubtitle, 0));
                if (subtitle instanceof SimpleTextView) {
                    ((SimpleTextView) subtitle).setTextColor(color);
                } else if (subtitle instanceof AnimatedTextView) {
                    ((AnimatedTextView) subtitle).setTextColor(color);
                }
            }
            avatarContainer.updateColors();
        }
    }

    public static void applyChatListTopBar(ActionBar actionBar, FragmentSearchField searchField, boolean archived) {
        final boolean classic = chatListTopBarClassic();
        // Runs on every theme change, so a switch away from Classic puts the normal pill back.
        if (searchField != null) {
            searchField.setWhiteBackground(classic);
        }
        if (actionBar == null || !classic) {
            return;
        }
        actionBar.setItemsColor(lookup(CHAT_LIST, archived ? Theme.key_actionBarDefaultArchivedIcon : Theme.key_actionBarDefaultIcon, 0), false);
        actionBar.setItemsBackgroundColor(lookup(CHAT_LIST, archived ? Theme.key_actionBarDefaultArchivedSelector : Theme.key_actionBarDefaultSelector, 0), false);
        actionBar.setTitleColor(lookup(CHAT_LIST_TITLES, archived ? Theme.key_actionBarDefaultArchivedTitle : Theme.key_actionBarDefaultTitle, 0));
        actionBar.setSearchTextColor(lookup(CHAT_LIST, archived ? Theme.key_actionBarDefaultArchivedSearch : Theme.key_actionBarDefaultSearch, 0), false);
        actionBar.setSearchTextColor(lookup(CHAT_LIST, archived ? Theme.key_actionBarDefaultArchivedSearchPlaceholder : Theme.key_actionBarDefaultSearchPlaceholder, 0), true);
    }

    // Filter tabs read their keys at draw time through their own provider, so they get a scoped one.
    public static Theme.ResourcesProvider wrapChatListTopBar(Theme.ResourcesProvider base) {
        return new Theme.ResourcesProvider() {
            @Override
            public int getColor(int key) {
                return chatListColor(key, Theme.getColor(key, base));
            }

            @Override
            public int getColorOrDefault(int key) {
                return chatListColor(key, base != null ? base.getColorOrDefault(key) : Theme.getColor(key));
            }

            @Override
            public int getCurrentColor(int key) {
                return chatListColor(key, base != null ? base.getCurrentColor(key) : Theme.getColor(key));
            }

            @Override
            public void setAnimatedColor(int key, int color) {
                if (base != null) {
                    base.setAnimatedColor(key, color);
                }
            }

            @Override
            public Drawable getDrawable(String drawableKey) {
                return base != null ? base.getDrawable(drawableKey) : null;
            }

            @Override
            public Paint getPaint(String paintKey) {
                return base != null ? base.getPaint(paintKey) : Theme.getThemePaint(paintKey);
            }

            @Override
            public boolean hasGradientService() {
                return base != null && base.hasGradientService();
            }

            @Override
            public boolean isDark() {
                return base != null ? base.isDark() : Theme.isCurrentThemeDark();
            }

            @Override
            public boolean isMonet() {
                return base != null ? base.isMonet() : Theme.isCurrentThemeMonet();
            }

            @Override
            public void applyServiceShaderMatrix(int w, int h, float translationX, float translationY) {
                if (base != null) {
                    base.applyServiceShaderMatrix(w, h, translationX, translationY);
                } else {
                    Theme.applyServiceShaderMatrix(w, h, translationX, translationY);
                }
            }

            @Override
            public ColorFilter getAnimatedEmojiColorFilter() {
                return base != null ? base.getAnimatedEmojiColorFilter() : Theme.chat_animatedEmojiTextColorFilter;
            }
        };
    }
}
