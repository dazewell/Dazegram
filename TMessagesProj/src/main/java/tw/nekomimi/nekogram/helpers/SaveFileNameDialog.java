package tw.nekomimi.nekogram.helpers;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.MultilineTextCheckCell;

import tw.nekomimi.nekogram.utils.AndroidUtil;
import xyz.nextalone.nagram.NaConfig;

// The one setting row's dialog: a switch, the pattern field, a live preview, a helper paragraph and
// Cancel/Reset/OK. Every edit is scratch until OK - Cancel or back leaves the stored switch and
// pattern untouched.
public final class SaveFileNameDialog {

    private SaveFileNameDialog() {
    }

    public static void show(BaseFragment fragment, Runnable onSaved) {
        Context context = fragment.getParentActivity();
        if (context == null) {
            return;
        }
        Theme.ResourcesProvider resourcesProvider = fragment.getResourceProvider();

        AlertDialog.Builder builder = new AlertDialog.Builder(context, resourcesProvider);
        builder.setTitle(getString(R.string.CustomFileNames));

        LinearLayout linearLayout = new LinearLayout(context);
        linearLayout.setOrientation(LinearLayout.VERTICAL);

        // MultilineTextCheckCell has no isChecked() - the switch's own state has to live here.
        boolean[] switchChecked = {NaConfig.INSTANCE.getCustomFileNamesEnabled().Bool()};

        MultilineTextCheckCell switchCell = new MultilineTextCheckCell(context, resourcesProvider);
        switchCell.setTextAndCheck(getString(R.string.CustomFileNamesSwitch), switchChecked[0], false);
        // onMeasure only caps AT_MOST above 50dp, it never enforces its own floor - a one-line row can
        // otherwise collapse below a comfortable touch target on small-font devices.
        switchCell.setMinimumHeight(dp(50));
        // Symmetric 2dp: the cell's own label/switch insets are already 22dp on both physical sides
        // (regardless of RTL, only the gravity flips), so 2 + 22 = 24dp lines up with every other
        // element below in both directions.
        linearLayout.addView(switchCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 2, 8, 2, 0));

        // Same field styling as the Custom Storage Path input, but with dialog-scoped colours since
        // this view lives in a modal rather than a full-screen list.
        EditTextBoldCursor editText = new EditTextBoldCursor(context);
        editText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 18);
        editText.setTextColor(Theme.getColor(Theme.key_dialogTextBlack, resourcesProvider));
        editText.setHintTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteHintText, resourcesProvider));
        editText.setHandlesColor(Theme.getColor(Theme.key_chat_TextSelectionCursor, resourcesProvider));
        editText.setFocusable(true);
        editText.setBackground(null);
        editText.setLineColors(Theme.getColor(Theme.key_dialogInputField, resourcesProvider), Theme.getColor(Theme.key_dialogInputFieldActivated, resourcesProvider), Theme.getColor(Theme.key_text_RedRegular, resourcesProvider));
        editText.setPadding(0, 0, 0, dp(6));
        // A pasted newline used to be accepted and grow the field, breaking the one-line underline look.
        editText.setSingleLine(true);
        editText.setImeOptions(EditorInfo.IME_ACTION_DONE);
        editText.setText(NaConfig.INSTANCE.getCustomFileNamesPattern().String());
        editText.setHint(getString(R.string.CustomFileNamesHint));
        linearLayout.addView(editText, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 24, 8, 24, 4));

        // Live feedback on the pattern currently typed - never the persisted config value.
        TextView preview = new TextView(context);
        preview.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        linearLayout.addView(preview, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 24, 2, 24, 0));

        TextView description = new TextView(context);
        description.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        description.setTextColor(Theme.getColor(Theme.key_dialogIcon, resourcesProvider));
        description.setText(getString(R.string.CustomFileNamesDescription));
        linearLayout.addView(description, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 24, 8, 24, 0));

        builder.setView(linearLayout);
        builder.setNegativeButton(getString(R.string.Cancel), null);
        builder.setNeutralButton(getString(R.string.CustomFileNamesReset), null);
        builder.setPositiveButton(getString(R.string.OK), null);

        AlertDialog dialog = builder.create();

        Runnable updatePreview = () -> {
            if (!switchChecked[0]) {
                // GONE, not INVISIBLE - a dead gap for a feature doing nothing would look broken too.
                preview.setVisibility(View.GONE);
                return;
            }
            preview.setVisibility(View.VISIBLE);
            String typed = editText.getText().toString();
            if (SaveFileNameHelper.isPatternValid(typed)) {
                preview.setTextColor(Theme.getColor(Theme.key_dialogIcon, resourcesProvider));
                preview.setText(LocaleController.formatString(R.string.CustomFileNamesPreview, SaveFileNameHelper.renderForDisplay(typed) + ".mp4"));
            } else {
                // Same isPatternValid() check OK gates on, surfaced before the user taps OK and gets
                // shaken - this is what voice and round messages always render for {name}.
                preview.setTextColor(Theme.getColor(Theme.key_text_RedRegular, resourcesProvider));
                preview.setText(getString(R.string.CustomFileNamesPreviewInvalid));
            }
        };

        switchCell.setOnClickListener(v -> {
            switchChecked[0] = !switchChecked[0];
            switchCell.setChecked(switchChecked[0]);
            updatePreview.run();
        });

        // Set once the dialog is showing, so the text watcher below can keep it in sync too.
        View[] resetButtonRef = new View[1];
        Runnable updateResetButton = () -> {
            if (resetButtonRef[0] != null) {
                resetButtonRef[0].setEnabled(!editText.getText().toString().equals(SaveFileNameHelper.DEFAULT_PATTERN));
            }
        };

        editText.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                updatePreview.run();
                updateResetButton.run();
            }
        });

        dialog.setOnShowListener(d -> {
            resetButtonRef[0] = dialog.getButton(AlertDialog.BUTTON_NEUTRAL);
            resetButtonRef[0].setOnClickListener(v -> editText.setText(SaveFileNameHelper.DEFAULT_PATTERN));
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                boolean enabled = switchChecked[0];
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
            // Computed immediately against the already-saved pattern - a dialog reopened on a
            // customised pattern must show its preview and Reset state without a keystroke.
            updatePreview.run();
            updateResetButton.run();
        });
        // No auto-focus, no auto-raised keyboard - the raised keyboard is what covered the
        // description and buttons in the first place. The user taps the field when ready to edit.
        fragment.showDialog(dialog);
    }
}
