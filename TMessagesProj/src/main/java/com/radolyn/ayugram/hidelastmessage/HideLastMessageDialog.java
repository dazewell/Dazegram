package com.radolyn.ayugram.hidelastmessage;

import android.content.Context;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.inputmethod.EditorInfo;
import android.widget.LinearLayout;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;

/**
 * Placeholder editor for the per-chat "hide last message" feature, opened from
 * {@link com.radolyn.ayugram.chatprivacy.ChatPrivacySheet}. All state lives in
 * {@link HideLastMessageController}.
 */
public final class HideLastMessageDialog {

    private HideLastMessageDialog() {}

    public static void showPlaceholderEditor(BaseFragment fragment, long dialogId, Runnable onSaved) {
        if (fragment == null || fragment.getParentActivity() == null || dialogId == 0) {
            return;
        }
        final int account = fragment.getCurrentAccount();
        if (!HideLastMessageController.isHidden(account, dialogId)) {
            return;
        }
        final Context context = fragment.getParentActivity();

        final EditTextBoldCursor editText = new EditTextBoldCursor(context);
        editText.setBackground(null);
        editText.setLineColors(
                Theme.getColor(Theme.key_dialogInputField),
                Theme.getColor(Theme.key_dialogInputFieldActivated),
                Theme.getColor(Theme.key_text_RedBold));
        editText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        editText.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        editText.setHint(HideLastMessageController.defaultPlaceholder());
        editText.setHintTextColor(Theme.getColor(Theme.key_dialogTextHint));
        editText.setMaxLines(1);
        editText.setLines(1);
        editText.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        editText.setGravity(Gravity.START | Gravity.TOP);
        editText.setSingleLine(true);
        editText.setImeOptions(EditorInfo.IME_ACTION_DONE);
        editText.setCursorColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        editText.setCursorSize(AndroidUtilities.dp(20));
        editText.setCursorWidth(1.5f);
        editText.setPadding(0, AndroidUtilities.dp(4), 0, 0);
        editText.setText(HideLastMessageController.getPlaceholder(account, dialogId));
        editText.setSelection(editText.length());
        editText.setOnEditorActionListener((v, i, e) -> {
            AndroidUtilities.hideKeyboard(v);
            return false;
        });

        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle(LocaleController.getString(R.string.ChatPrivacyPlaceholderText));

        LinearLayout linearLayout = new LinearLayout(context);
        linearLayout.setOrientation(LinearLayout.VERTICAL);
        linearLayout.addView(editText, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 36, Gravity.TOP | Gravity.START, 24, 8, 24, 0));
        builder.setView(linearLayout);
        builder.setPositiveButton(LocaleController.getString(R.string.Save), (dialog, which) -> {
            AndroidUtilities.hideKeyboard(editText);
            HideLastMessageController.setHidden(account, dialogId, true, editText.getText().toString());
            if (onSaved != null) {
                onSaved.run();
            }
        });
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);

        AlertDialog alertDialog = builder.create();
        alertDialog.setOnShowListener(d -> AndroidUtilities.runOnUIThread(() -> {
            editText.requestFocus();
            AndroidUtilities.showKeyboard(editText);
        }));
        alertDialog.show();
    }
}
