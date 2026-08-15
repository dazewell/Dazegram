package xyz.nextalone.nagram.ui.composer;

import org.telegram.messenger.R;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * The set of composer toolbar buttons a user is allowed to place, and where each one starts.
 *
 * Contextual controls (the send-as avatar, the bot commands pill, the rich draft delete button, the
 * bot web view) are deliberately absent: they come and go with chat state and own their own slots.
 */
public final class ComposerButtons {

    public static final int ZONE_START = 0;
    public static final int ZONE_MIDDLE = 1;
    public static final int ZONE_END = 2;
    public static final int ZONE_HIDDEN = 3;
    public static final int ZONE_COUNT = 4;

    /** Held by a view the enter view creates and owns. */
    public static final int KIND_CORE = 0;
    /** Applies a text style, so it only enables while there is a selection. */
    public static final int KIND_FORMAT = 1;
    /** Acts on the field without needing a selection. */
    public static final int KIND_TEXT = 2;
    /** Cut/copy/paste: forwarded straight to EditTextCaption's own menu handling, not performMenuAction. */
    public static final int KIND_CLIPBOARD = 3;

    /** The leading zone holds at most this many buttons, ordered start to end. */
    public static final int START_CAPACITY = 2;

    public static final String EMOJI = "emoji";
    public static final String ATTACH = "attach";
    public static final String SCHEDULE = "schedule";
    public static final String EXPAND = "expand";
    public static final String RICH = "rich";
    public static final String AI = "ai";
    public static final String SELECT_ALL = "selectall";
    public static final String CUT = "cut";
    public static final String COPY = "copy";
    public static final String PASTE = "paste";

    public static final class Button {
        public final String key;
        public final int titleRes;
        public final int iconRes;
        public final int kind;
        public final int defaultZone;
        /** Menu action forwarded to the edit field, or 0 when the button drives its own view. */
        public final int menuAction;
        /**
         * Attach carries translation geometry that only makes sense at the trailing edge (see
         * {@code attachLayoutTranslationX} in ChatActivityEnterView), so it stays trailing-only.
         * Schedule used to be lumped in here too, but that translation math is guarded behind
         * {@code !composerToolbarEnabled} - with the composer toolbar on, Schedule's position comes
         * entirely from {@code ComposerToolbarLayout.addConfigurable}'s ordered slot, same as any
         * other button, so it carries no such constraint and can sit anywhere.
         */
        public final boolean trailingOnly;
        /** Present for the whole life of the toolbar, so it can safely anchor the trailing edge. */
        public final boolean stable;
        /**
         * View scale applied to the settings-screen preview row (see ComposerLayoutActivity). The
         * toolbar itself no longer scales the box per button - each glyph's optical size is baked
         * into its fork-owned vector asset instead - so every registered button now carries 1f here.
         */
        public final float iconScale;

        Button(String key, int titleRes, int iconRes, int kind, int defaultZone, int menuAction, boolean trailingOnly, boolean stable) {
            this(key, titleRes, iconRes, kind, defaultZone, menuAction, trailingOnly, stable, 1f);
        }

        Button(String key, int titleRes, int iconRes, int kind, int defaultZone, int menuAction, boolean trailingOnly, boolean stable, float iconScale) {
            this.key = key;
            this.titleRes = titleRes;
            this.iconRes = iconRes;
            this.kind = kind;
            this.defaultZone = defaultZone;
            this.menuAction = menuAction;
            this.trailingOnly = trailingOnly;
            this.stable = stable;
            this.iconScale = iconScale;
        }

        public boolean canSitIn(int zone) {
            if (zone == ZONE_HIDDEN) {
                return true;
            }
            return !trailingOnly || zone == ZONE_END;
        }
    }

    private static final LinkedHashMap<String, Button> REGISTRY = new LinkedHashMap<>();

    private static void register(Button button) {
        if (REGISTRY.put(button.key, button) != null) {
            // A second registration under the same key silently replaces the first, so the button
            // would quietly take the later entry's zone and constraints. Fail while it is obvious.
            throw new IllegalStateException("duplicate composer button: " + button.key);
        }
    }

    static {
        register(new Button(EMOJI, R.string.AccDescrEmojiButton, R.drawable.input_smile_solar, KIND_CORE, ZONE_START, 0, false, true));
        register(new Button(RICH, R.string.ArticleEditor, R.drawable.nax_iv_fullscreen, KIND_CORE, ZONE_MIDDLE, 0, false, false));
        register(new Button(AI, R.string.AIEditor, R.drawable.input_ai_star, KIND_CORE, ZONE_MIDDLE, 0, false, false));

        register(new Button("quote", R.string.Quote, R.drawable.nax_formatting_quote, KIND_FORMAT, ZONE_MIDDLE, R.id.menu_quote, false, true));
        register(new Button("spoiler", R.string.Spoiler, R.drawable.nax_formatting_spoiler, KIND_FORMAT, ZONE_MIDDLE, R.id.menu_spoiler, false, true));
        register(new Button(SELECT_ALL, R.string.SelectAll, R.drawable.nax_formatting_select_all, KIND_TEXT, ZONE_MIDDLE, 0, false, true));
        register(new Button("regular", R.string.Regular, R.drawable.nax_formatting_eraser, KIND_FORMAT, ZONE_MIDDLE, R.id.menu_regular, false, true));

        register(new Button(CUT, R.string.Cut, R.drawable.nax_formatting_cut, KIND_CLIPBOARD, ZONE_HIDDEN, android.R.id.cut, false, true));
        register(new Button(COPY, R.string.Copy, R.drawable.msg_copy, KIND_CLIPBOARD, ZONE_HIDDEN, android.R.id.copy, false, true));
        register(new Button(PASTE, R.string.Paste, R.drawable.baseline_content_paste_24, KIND_CLIPBOARD, ZONE_HIDDEN, android.R.id.paste, false, true));

        register(new Button("mono", R.string.Mono, R.drawable.formatting_code, KIND_FORMAT, ZONE_HIDDEN, R.id.menu_mono, false, true));
        register(new Button("bold", R.string.Bold, R.drawable.formatting_bold, KIND_FORMAT, ZONE_HIDDEN, R.id.menu_bold, false, true));
        register(new Button("italic", R.string.Italic, R.drawable.formatting_italic, KIND_FORMAT, ZONE_HIDDEN, R.id.menu_italic, false, true));
        register(new Button("code", R.string.MonoCode, R.drawable.iv_code, KIND_FORMAT, ZONE_HIDDEN, R.id.menu_code, false, true));
        register(new Button("strike", R.string.Strike, R.drawable.formatting_strikethrough, KIND_FORMAT, ZONE_HIDDEN, R.id.menu_strike, false, true));
        register(new Button("underline", R.string.Underline, R.drawable.formatting_underline, KIND_FORMAT, ZONE_HIDDEN, R.id.menu_underline, false, true));
        register(new Button("link", R.string.CreateLink, R.drawable.menu_link_create2, KIND_FORMAT, ZONE_HIDDEN, R.id.menu_link, false, true));
        register(new Button("mention", R.string.CreateMention, R.drawable.deproko_baseline_mention_24, KIND_FORMAT, ZONE_HIDDEN, R.id.menu_mention, false, true));
        register(new Button("date", R.string.FormattedDate, R.drawable.input_calendar_add_solar, KIND_FORMAT, ZONE_HIDDEN, R.id.menu_date, false, true));
        register(new Button("translate", R.string.TranslateMessage, R.drawable.msg_translate_solar, KIND_FORMAT, ZONE_HIDDEN, R.id.menu_translate, false, true));

        register(new Button(EXPAND, R.string.ExpandMessageField, R.drawable.nax_composer_expand, KIND_CORE, ZONE_END, 0, false, true));
        // Schedule and Attach's live toolbar icons used to come from full-bleed raster assets with no
        // baked-in padding, which no amount of iconScale could visually match against the padded vector
        // icons around them - ChatActivityEnterView now renders the same vector assets the opt-in Solar
        // icon theme already ships (ayu_input_attach, input_calendar1/2_solar) for the composer toolbar,
        // so these scales are the ordinary vector default, same as their neighbors.
        register(new Button(SCHEDULE, R.string.ScheduledMessages, R.drawable.input_calendar_add_solar, KIND_CORE, ZONE_END, 0, false, false, 1f));
        register(new Button(ATTACH, R.string.AccDescrAttachButton, R.drawable.ayu_input_attach, KIND_CORE, ZONE_END, 0, true, true, 1f));
    }

    private static final List<Button> ALL = Collections.unmodifiableList(new ArrayList<>(REGISTRY.values()));

    private ComposerButtons() {
    }

    public static List<Button> all() {
        return ALL;
    }

    public static Button get(String key) {
        return key == null ? null : REGISTRY.get(key);
    }

    /**
     * CrossOutDrawable wraps input_notify_on with its own internal bitmap padding and a diagonal
     * slash that overhangs the glyph, so the composer's shared 24dp box would draw it slightly large.
     * Held down to sit with its neighbours. There is no vector to re-crop here, so the constant stays.
     */
    private static final float NOTIFY_ICON_SCALE = 0.85f;
    /**
     * ic_ab_other is the raster menu-open state of the attach button, out of scope for the vector
     * re-crop, so it keeps its own authored scale rather than a baked asset geometry.
     */
    private static final float MENU_ICON_SCALE = 1.10f;

    public static float iconScaleForResource(int resource) {
        // input_attach/ic_ab_other are the same physical button as ATTACH, just swapped in
        // post-construction by checkAttachButton() for its resting/menu-open state - the resting one
        // tracks the registry value (now the vector default 1f), the menu-open raster keeps its own.
        if (resource == R.drawable.ayu_input_attach || resource == R.drawable.input_attach) {
            return get(ATTACH).iconScale;
        }
        if (resource == R.drawable.ic_ab_other) {
            return MENU_ICON_SCALE;
        }
        if (resource == R.drawable.input_notify_on) {
            return NOTIFY_ICON_SCALE;
        }
        return 1f;
    }

    public static boolean isFormat(String key) {
        Button button = get(key);
        return button != null && button.kind == KIND_FORMAT;
    }

    public static boolean isTextAction(String key) {
        Button button = get(key);
        return button != null && button.kind == KIND_TEXT;
    }

    public static boolean isClipboardAction(String key) {
        Button button = get(key);
        return button != null && button.kind == KIND_CLIPBOARD;
    }
}
