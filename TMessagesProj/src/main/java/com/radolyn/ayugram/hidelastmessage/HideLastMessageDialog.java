package com.radolyn.ayugram.hidelastmessage;

import android.content.Context;
import android.content.DialogInterface;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.inputmethod.EditorInfo;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;

/**
 * Small configuration dialog for the per-chat "hide last message" feature,
 * opened from the in-chat overflow menu. Lets the user enable/disable hiding for
 * the current dialog and pick the placeholder shown in its place in the chat
 * list. All state lives in {@link HideLastMessageController}.
 */
public final class HideLastMessageDialog {

    private HideLastMessageDialog() {}

    public static void show(BaseFragment fragment, long dialogId) {
        if (fragment == null || fragment.getParentActivity() == null || dialogId == 0) {
            return;
        }
        final int account = fragment.getCurrentAccount();
        final Context context = fragment.getParentActivity();
        final boolean currentlyHidden = HideLastMessageController.isHidden(account, dialogId);

        final EditTextBoldCursor editText = new EditTextBoldCursor(context);
        editText.setBackground(null);
        editText.setLineColors(
                Theme.getColor(Theme.key_dialogInputField),
                Theme.getColor(Theme.key_dialogInputFieldActivated),
                Theme.getColor(Theme.key_text_RedBold));

        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle(LocaleController.getString(R.string.HideLastMessageTitle));

        LinearLayout linearLayout = new LinearLayout(context);
        linearLayout.setOrientation(LinearLayout.VERTICAL);
        builder.setView(linearLayout);

        final TextView message = new TextView(context);
        message.setText(LocaleController.getString(R.string.HideLastMessageInfo));
        message.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        message.setPadding(AndroidUtilities.dp(23), AndroidUtilities.dp(12), AndroidUtilities.dp(23), AndroidUtilities.dp(6));
        message.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        linearLayout.addView(message, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        editText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        editText.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        editText.setHint(HideLastMessageController.defaultPlaceholder());
        editText.setHintTextColor(Theme.getColor(Theme.key_dialogTextHint));
        editText.setMaxLines(1);
        editText.setLines(1);
        editText.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        editText.setGravity(Gravity.LEFT | Gravity.TOP);
        editText.setSingleLine(true);
        editText.setImeOptions(EditorInfo.IME_ACTION_DONE);
        editText.setCursorColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        editText.setCursorSize(AndroidUtilities.dp(20));
        editText.setCursorWidth(1.5f);
        editText.setPadding(0, AndroidUtilities.dp(4), 0, 0);
        if (currentlyHidden) {
            editText.setText(HideLastMessageController.getPlaceholder(account, dialogId));
            editText.setSelection(editText.length());
        }
        editText.setOnEditorActionListener((v, i, e) -> {
            AndroidUtilities.hideKeyboard(v);
            return false;
        });
        linearLayout.addView(editText, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 36, Gravity.TOP | Gravity.LEFT, 24, 6, 24, 0));

        builder.setPositiveButton(
                LocaleController.getString(currentlyHidden ? R.string.Save : R.string.HideLastMessageEnable),
                (dialog, which) -> {
                    AndroidUtilities.hideKeyboard(editText);
                    HideLastMessageController.setHidden(account, dialogId, true, editText.getText().toString());
                });
        if (currentlyHidden) {
            builder.setNeutralButton(
                    LocaleController.getString(R.string.HideLastMessageDisable),
                    (dialog, which) -> {
                        AndroidUtilities.hideKeyboard(editText);
                        HideLastMessageController.setHidden(account, dialogId, false, null);
                    });
        }
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);

        AlertDialog alertDialog = builder.create();
        alertDialog.setOnShowListener(d -> AndroidUtilities.runOnUIThread(() -> {
            editText.requestFocus();
            AndroidUtilities.showKeyboard(editText);
        }));
        fragment.showDialog(alertDialog);

        TextView neutral = (TextView) alertDialog.getButton(DialogInterface.BUTTON_NEUTRAL);
        if (neutral != null) {
            neutral.setTextColor(Theme.getColor(Theme.key_text_RedBold));
        }
    }
}
