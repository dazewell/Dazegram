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

    /** The leading slot is a single frame, so only one button can sit there. */
    public static final int START_CAPACITY = 1;

    public static final String EMOJI = "emoji";
    public static final String ATTACH = "attach";
    public static final String SCHEDULE = "schedule";
    public static final String EXPAND = "expand";
    public static final String RICH = "rich";
    public static final String AI = "ai";
    public static final String SELECT_ALL = "selectall";

    public static final class Button {
        public final String key;
        public final int titleRes;
        public final int iconRes;
        public final int kind;
        public final int defaultZone;
        /** Menu action forwarded to the edit field, or 0 when the button drives its own view. */
        public final int menuAction;
        /**
         * Schedule and attach carry translation geometry that assumes they sit at the trailing edge,
         * so they stay in the trailing zone.
         */
        public final boolean trailingOnly;
        /** Present for the whole life of the toolbar, so it can safely anchor the trailing edge. */
        public final boolean stable;
        /** Authored optical size inside the shared icon box. */
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
        register(new Button(RICH, R.string.ArticleEditor, R.drawable.iv_fullscreen, KIND_CORE, ZONE_MIDDLE, 0, false, false));
        register(new Button(AI, R.string.AIEditor, R.drawable.input_ai_star, KIND_CORE, ZONE_MIDDLE, 0, false, false));

        register(new Button("quote", R.string.Quote, R.drawable.formatting_quote, KIND_FORMAT, ZONE_MIDDLE, R.id.menu_quote, false, true));
        register(new Button("spoiler", R.string.Spoiler, R.drawable.formatting_spoiler, KIND_FORMAT, ZONE_MIDDLE, R.id.menu_spoiler, false, true, 0.85f));
        register(new Button(SELECT_ALL, R.string.SelectAll, R.drawable.nax_formatting_select_all, KIND_TEXT, ZONE_MIDDLE, 0, false, true));
        register(new Button("regular", R.string.Regular, R.drawable.nax_formatting_eraser, KIND_FORMAT, ZONE_MIDDLE, R.id.menu_regular, false, true));

        register(new Button("mono", R.string.Mono, R.drawable.formatting_code, KIND_FORMAT, ZONE_HIDDEN, R.id.menu_mono, false, true));
        register(new Button("bold", R.string.Bold, R.drawable.formatting_bold, KIND_FORMAT, ZONE_HIDDEN, R.id.menu_bold, false, true));
        register(new Button("italic", R.string.Italic, R.drawable.formatting_italic, KIND_FORMAT, ZONE_HIDDEN, R.id.menu_italic, false, true));
        register(new Button("code", R.string.MonoCode, R.drawable.iv_code, KIND_FORMAT, ZONE_HIDDEN, R.id.menu_code, false, true));
        register(new Button("strike", R.string.Strike, R.drawable.formatting_strikethrough, KIND_FORMAT, ZONE_HIDDEN, R.id.menu_strike, false, true));
        register(new Button("underline", R.string.Underline, R.drawable.formatting_underline, KIND_FORMAT, ZONE_HIDDEN, R.id.menu_underline, false, true));
        register(new Button("link", R.string.CreateLink, R.drawable.menu_link_create2, KIND_FORMAT, ZONE_HIDDEN, R.id.menu_link, false, true));
        register(new Button("mention", R.string.CreateMention, R.drawable.deproko_baseline_mention_24, KIND_FORMAT, ZONE_HIDDEN, R.id.menu_mention, false, true, 1.05f));
        register(new Button("date", R.string.FormattedDate, R.drawable.input_calendar_add_solar, KIND_FORMAT, ZONE_HIDDEN, R.id.menu_date, false, true));
        register(new Button("translate", R.string.TranslateMessage, R.drawable.msg_translate_solar, KIND_FORMAT, ZONE_HIDDEN, R.id.menu_translate, false, true));

        register(new Button(EXPAND, R.string.ExpandMessageField, R.drawable.baseline_fullscreen_24, KIND_CORE, ZONE_END, 0, false, true, 0.82f));
        register(new Button(SCHEDULE, R.string.ScheduledMessages, R.drawable.input_calendar_add_solar, KIND_CORE, ZONE_END, 0, true, false));
        register(new Button(ATTACH, R.string.AccDescrAttachButton, R.drawable.msg_input_attach2, KIND_CORE, ZONE_END, 0, true, true));
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

    public static boolean isFormat(String key) {
        Button button = get(key);
        return button != null && button.kind == KIND_FORMAT;
    }

    public static boolean isTextAction(String key) {
        Button button = get(key);
        return button != null && button.kind == KIND_TEXT;
    }
}
