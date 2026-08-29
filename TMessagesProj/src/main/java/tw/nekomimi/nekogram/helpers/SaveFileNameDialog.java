package tw.nekomimi.nekogram.helpers;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;

import tw.nekomimi.nekogram.utils.AndroidUtil;
import xyz.nextalone.nagram.NaConfig;

// The one setting row's dialog: a switch, the pattern field, a helper paragraph and Cancel/Reset/OK.
// Every edit is scratch until OK - Cancel or back leaves the stored switch and pattern untouched.
public final class SaveFileNameDialog {

    private SaveFileNameDialog() {
    }

    public static void show(BaseFragment fragment, Runnable onSaved) {
        Context context = fragment.getParentActivity();
        if (context == null) {
            return;
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle(getString(R.string.CustomFileNames));

        LinearLayout linearLayout = new LinearLayout(context);
        linearLayout.setOrientation(LinearLayout.VERTICAL);

        TextCheckCell switchCell = new TextCheckCell(context);
        switchCell.setTextAndCheck(getString(R.string.CustomFileNamesSwitch), NaConfig.INSTANCE.getCustomFileNamesEnabled().Bool(), false);
        switchCell.setOnClickListener(v -> switchCell.setChecked(!switchCell.isChecked()));
        linearLayout.addView(switchCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, -dp(8), 0, -dp(8), 0));

        // Same field styling as the Custom Storage Path input so the two look identical.
        EditTextBoldCursor editText = new EditTextBoldCursor(context);
        editText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 18);
        editText.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        editText.setHintTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteHintText));
        editText.setHandlesColor(Theme.getColor(Theme.key_chat_TextSelectionCursor));
        editText.setFocusable(true);
        editText.setBackground(null);
        editText.setLineColors(Theme.getColor(Theme.key_windowBackgroundWhiteInputField), Theme.getColor(Theme.key_windowBackgroundWhiteInputFieldActivated), Theme.getColor(Theme.key_text_RedRegular));
        editText.setPadding(0, 0, 0, dp(6));
        editText.setText(NaConfig.INSTANCE.getCustomFileNamesPattern().String());
        editText.setHint(getString(R.string.CustomFileNamesHint));
        editText.requestFocus();
        linearLayout.addView(editText, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, dp(8), 0, dp(10), dp(4)));

        TextView description = new TextView(context);
        description.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        description.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        description.setText(getString(R.string.CustomFileNamesDescription));
        linearLayout.addView(description, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, dp(8), dp(4), dp(10), 0));

        builder.setView(linearLayout);
        builder.setNegativeButton(getString(R.string.Cancel), null);
        builder.setNeutralButton(getString(R.string.CustomFileNamesReset), null);
        builder.setPositiveButton(getString(R.string.OK), null);

        AlertDialog dialog = builder.create();
        dialog.setOnShowListener(d -> {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> editText.setText(SaveFileNameHelper.DEFAULT_PATTERN));
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                boolean enabled = switchCell.isChecked();
                String pattern = editText.getText().toString();
                // An unused pattern can never be invalid, so only validate when the feature is on.
                if (enabled && !SaveFileNameHelper.isPatternValid(pattern)) {
                    AndroidUtil.showInputError(editText);
                    return;
                }
                NaConfig.INSTANCE.getCustomFileNamesEnabled().setConfigBool(enabled);
                NaConfig.INSTANCE.getCustomFileNamesPattern().setConfigString(pattern);
                dialog.dismiss();
                if (onSaved != null) {
                    onSaved.run();
                }
            });
        });
        fragment.showDialog(dialog);
    }
}
