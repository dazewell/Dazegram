package xyz.nextalone.nagram.ui;

import android.content.Context;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.text.Editable;
import android.text.Spanned;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.ChatActivityEnterView;
import org.telegram.ui.Components.EditTextCaption;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.QuoteSpan;
import org.telegram.ui.Components.ScaleStateListAnimator;

public final class ComposerFormattingActions {

    private final ChatActivityEnterView enterView;
    private final LinearLayout group;
    private final Theme.ResourcesProvider resourcesProvider;
    private final boolean isChat;
    private final ImageView quoteAction;
    private final ImageView spoilerAction;
    private final ImageView monoAction;
    private final ImageView boldAction;
    private final ImageView clearAction;
    private boolean updating;
    private boolean destroyed;
    private int selectionStart = -1;
    private int selectionEnd = -1;

    public ComposerFormattingActions(ChatActivityEnterView enterView, Theme.ResourcesProvider resourcesProvider, boolean isChat) {
        this.enterView = enterView;
        this.resourcesProvider = resourcesProvider;
        this.isChat = isChat;
        Context context = enterView.getContext();

        group = new LinearLayout(context);
        group.setOrientation(LinearLayout.HORIZONTAL);
        group.setGravity(android.view.Gravity.CENTER_VERTICAL);

        quoteAction = addAction(R.drawable.formatting_quote, R.string.Quote, R.id.menu_quote);
        spoilerAction = addAction(R.drawable.formatting_spoiler, R.string.Spoiler, R.id.menu_spoiler);
        monoAction = addAction(R.drawable.formatting_code, R.string.Mono, R.id.menu_mono);
        boldAction = addAction(R.drawable.formatting_bold, R.string.Bold, R.id.menu_bold);
        clearAction = addAction(R.drawable.nax_formatting_clear, R.string.Regular, R.id.menu_regular);
        setActionEnabled(quoteAction, false);
        setActionEnabled(spoilerAction, false);
        setActionEnabled(monoAction, false);
        setActionEnabled(boldAction, false);
        setActionEnabled(clearAction, false);
        group.setVisibility(View.GONE);
    }

    public View getView() {
        return group;
    }

    public void onSelectionChanged() {
        refresh();
    }

    public void onTextChanged() {
        refresh();
    }

    public void refresh() {
        if (updating) {
            return;
        }
        updating = true;
        try {
            EditTextCaption editText = enterView.getEditField();
            int rawStart = editText != null ? editText.getSelectionStart() : -1;
            int rawEnd = editText != null ? editText.getSelectionEnd() : -1;
            int start = Math.min(rawStart, rawEnd);
            int end = Math.max(rawStart, rawEnd);
            boolean composerAvailable = !destroyed
                    && enterView.isComposerToolbarEnabled()
                    && editText != null
                    && editText.getVisibility() == View.VISIBLE
                    && !enterView.isRichDraftActive();
            boolean hasSelection = composerAvailable && start >= 0 && end > start;
            if (hasSelection) {
                selectionStart = start;
                selectionEnd = end;
            } else {
                clearSelection();
            }
            boolean quoteVisible = composerAvailable && isChat && xyz.nextalone.nagram.NaConfig.INSTANCE.getShowTextQuote().Bool();
            boolean spoilerVisible = composerAvailable && xyz.nextalone.nagram.NaConfig.INSTANCE.getShowTextSpoiler().Bool();
            boolean monoVisible = composerAvailable && xyz.nextalone.nagram.NaConfig.INSTANCE.getShowTextMono().Bool();
            boolean boldVisible = composerAvailable && xyz.nextalone.nagram.NaConfig.INSTANCE.getShowTextBold().Bool();
            boolean clearVisible = composerAvailable && xyz.nextalone.nagram.NaConfig.INSTANCE.getShowTextRegular().Bool();
            quoteAction.setVisibility(quoteVisible ? View.VISIBLE : View.GONE);
            spoilerAction.setVisibility(spoilerVisible ? View.VISIBLE : View.GONE);
            monoAction.setVisibility(monoVisible ? View.VISIBLE : View.GONE);
            boldAction.setVisibility(boldVisible ? View.VISIBLE : View.GONE);
            clearAction.setVisibility(clearVisible ? View.VISIBLE : View.GONE);
            setActionEnabled(quoteAction, hasSelection && isQuoteAvailable(editText.getText(), start, end));
            setActionEnabled(spoilerAction, hasSelection);
            setActionEnabled(monoAction, hasSelection);
            setActionEnabled(boldAction, hasSelection);
            setActionEnabled(clearAction, hasSelection);
            group.setVisibility(quoteVisible
                    || spoilerVisible
                    || monoVisible
                    || boldVisible
                    || clearVisible ? View.VISIBLE : View.GONE);
        } finally {
            updating = false;
        }
    }

    public void onDestroy() {
        destroyed = true;
        clearSelection();
        group.setVisibility(View.GONE);
    }

    public void updateColors() {
        int iconColor = Theme.getColor(Theme.key_glass_defaultIcon, resourcesProvider);
        int selectorColor = Theme.getColor(Theme.key_listSelector, resourcesProvider);
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child instanceof ImageView) {
                ImageView action = (ImageView) child;
                action.setColorFilter(new PorterDuffColorFilter(iconColor, PorterDuff.Mode.MULTIPLY));
                action.setBackground(Theme.createSelectorDrawable(selectorColor, Theme.RIPPLE_MASK_CIRCLE_20DP, AndroidUtilities.dp(16)));
            }
        }
    }

    private ImageView addAction(int drawable, int string, int menuAction) {
        ImageView action = new ImageView(group.getContext());
        action.setImageResource(drawable);
        action.setScaleType(ImageView.ScaleType.CENTER);
        action.setColorFilter(new PorterDuffColorFilter(Theme.getColor(Theme.key_glass_defaultIcon, resourcesProvider), PorterDuff.Mode.MULTIPLY));
        action.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector, resourcesProvider), Theme.RIPPLE_MASK_CIRCLE_20DP, AndroidUtilities.dp(16)));
        action.setContentDescription(LocaleController.getString(string));
        ScaleStateListAnimator.apply(action);
        action.setOnClickListener(v -> apply(menuAction));
        group.addView(action, LayoutHelper.createLinear(ComposerToolbarLayout.BUTTON_SIZE, ComposerToolbarLayout.BUTTON_SIZE));
        return action;
    }

    private void apply(int menuAction) {
        if (updating || destroyed) {
            return;
        }
        updating = true;
        try {
            EditTextCaption editText = enterView.getEditField();
            Editable editable = editText != null ? editText.getText() : null;
            if (editable == null || selectionStart < 0 || selectionEnd <= selectionStart || selectionEnd > editable.length()) {
                clearSelection();
                return;
            }
            editText.setSelectionOverride(selectionStart, selectionEnd);
            try {
                editText.performMenuAction(menuAction);
            } finally {
                editText.setSelectionOverride(-1, -1);
            }
        } finally {
            updating = false;
            refresh();
        }
    }

    private void clearSelection() {
        selectionStart = -1;
        selectionEnd = -1;
    }

    private static void setActionEnabled(ImageView action, boolean enabled) {
        action.setEnabled(enabled);
        action.setAlpha(enabled ? 1.0f : 0.45f);
    }

    private static boolean isQuoteAvailable(Editable editable, int start, int end) {
        if (!(editable instanceof Spanned)) {
            return false;
        }
        QuoteSpan.QuoteStyleSpan[] spans = ((Spanned) editable).getSpans(start, end, QuoteSpan.QuoteStyleSpan.class);
        return spans == null || spans.length == 0;
    }
}
