package com.radolyn.ayugram.chattimezone;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Business.TimezonesController;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.NumberPicker;
import org.telegram.ui.Stories.recorder.ButtonWithCounterView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Locale;

/**
 * Bottom-sheet timezone picker with a rolling NumberPicker plus a search box.
 * Mirrors AlertsCreator.createTimezonePickerDialog but adds filtering and fixes
 * the show/dismiss wiring.
 */
public final class ChatTimeZonePickerSheet {

    private ChatTimeZonePickerSheet() {}

    public static BottomSheet show(Context context, String title, String currentTimezoneId,
                                   Utilities.Callback<String> whenPicked) {
        final int currentAccount = UserConfig.selectedAccount;
        TimezonesController controller = TimezonesController.getInstance(currentAccount);
        controller.load();
        if (controller.getTimezones().isEmpty()) {
            return null;
        }

        final ArrayList<TLRPC.TL_timezone> all = new ArrayList<>(controller.getTimezones());
        Collections.sort(all, (a, b) -> a.utc_offset - b.utc_offset);
        final ArrayList<TLRPC.TL_timezone> filtered = new ArrayList<>(all);

        BottomSheet.Builder builder = new BottomSheet.Builder(context, false, null);
        builder.setApplyBottomPadding(false);

        final NumberPicker picker = new NumberPicker(context) {
            @Override
            protected CharSequence getContentDescription(int value) {
                if (value < 0 || value >= filtered.size()) return "";
                return controller.getTimezoneName(filtered.get(value), true);
            }
        };
        picker.setAllItemsCount(24);
        picker.setItemCount(8);
        picker.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        picker.setGravity(Gravity.CENTER);
        picker.setMinValue(0);
        picker.setMaxValue(Math.max(0, filtered.size() - 1));
        picker.setFormatter(value -> {
            if (value < 0 || value >= filtered.size()) return "";
            return controller.getTimezoneName(filtered.get(value), true);
        });
        // Restore current selection if any.
        for (int i = 0; i < filtered.size(); ++i) {
            if (TextUtils.equals(currentTimezoneId, filtered.get(i).id)) {
                picker.setValue(i);
                break;
            }
        }

        // Container: title + search + picker + button.
        LinearLayout container = new LinearLayout(context) {
            @Override
            protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                picker.getLayoutParams().height = dp(NumberPicker.DEFAULT_SIZE_PER_COUNT) * 8;
                super.onMeasure(widthMeasureSpec, heightMeasureSpec);
            }
        };
        container.setOrientation(LinearLayout.VERTICAL);

        // Title.
        FrameLayout titleLayout = new FrameLayout(context);
        TextView titleView = new TextView(context);
        titleView.setText(title);
        titleView.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
        titleView.setTypeface(AndroidUtilities.bold());
        titleLayout.addView(titleView, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT,
                Gravity.LEFT | Gravity.TOP, 0, 12, 0, 0));
        container.addView(titleLayout, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT,
                Gravity.LEFT | Gravity.TOP, 22, 0, 0, 4));

        // Search box.
        FrameLayout searchContainer = new FrameLayout(context);
        searchContainer.setBackground(Theme.createRoundRectDrawable(dp(10),
                Theme.getColor(Theme.key_graySection)));

        ImageView searchIcon = new ImageView(context);
        searchIcon.setImageResource(R.drawable.smiles_tab_search);
        searchIcon.setColorFilter(new PorterDuffColorFilter(
                Theme.getColor(Theme.key_dialogSearchHint), PorterDuff.Mode.SRC_IN));
        searchContainer.addView(searchIcon, LayoutHelper.createFrame(20, 20,
                Gravity.LEFT | Gravity.CENTER_VERTICAL, 12, 0, 0, 0));

        final EditText searchField = new EditText(context);
        searchField.setHint(LocaleController.getString(R.string.Search));
        searchField.setHintTextColor(Theme.getColor(Theme.key_dialogSearchHint));
        searchField.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        searchField.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        searchField.setBackground(null);
        searchField.setSingleLine(true);
        searchField.setInputType(InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS | InputType.TYPE_CLASS_TEXT);
        searchField.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
        searchField.setPadding(0, 0, 0, 0);
        searchContainer.addView(searchField, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT,
                Gravity.LEFT | Gravity.CENTER_VERTICAL, 40, 0, 12, 0));

        container.addView(searchContainer, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 44,
                0, 22, 8, 22, 4));

        // Picker.
        LinearLayout pickerRow = new LinearLayout(context);
        pickerRow.setOrientation(LinearLayout.HORIZONTAL);
        pickerRow.setWeightSum(1f);
        pickerRow.addView(picker, LayoutHelper.createLinear(0, 54 * 8, 1f));
        container.addView(pickerRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT,
                1f, 0, 0, 12, 0, 12));

        // Filter handler.
        searchField.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override
            public void afterTextChanged(Editable s) {
                String q = s == null ? "" : s.toString().trim().toLowerCase(Locale.ROOT);
                String previousId = filtered.isEmpty() ? null : filtered.get(picker.getValue()).id;
                filtered.clear();
                if (q.isEmpty()) {
                    filtered.addAll(all);
                } else {
                    for (TLRPC.TL_timezone tz : all) {
                        if (matches(tz, q, controller)) {
                            filtered.add(tz);
                        }
                    }
                }
                picker.setMinValue(0);
                picker.setMaxValue(Math.max(0, filtered.size() - 1));
                int targetIndex = 0;
                for (int i = 0; i < filtered.size(); ++i) {
                    if (TextUtils.equals(previousId, filtered.get(i).id)) {
                        targetIndex = i;
                        break;
                    }
                }
                picker.setValue(targetIndex);
                picker.invalidate();
            }
        });

        // Select button.
        final BottomSheet[] sheetRef = new BottomSheet[1];
        ButtonWithCounterView button = new ButtonWithCounterView(context, null);
        button.setText(LocaleController.getString(R.string.Select), false);
        button.setOnClickListener(v -> {
            if (!filtered.isEmpty()) {
                String id = filtered.get(Math.max(0, Math.min(picker.getValue(), filtered.size() - 1))).id;
                if (whenPicked != null) whenPicked.run(id);
            }
            if (sheetRef[0] != null) sheetRef[0].dismiss();
        });
        container.addView(button, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48,
                0, 16, 12, 16, 12));

        builder.setCustomView(container);
        BottomSheet sheet = builder.create();
        sheetRef[0] = sheet;
        sheet.setBackgroundColor(Theme.getColor(Theme.key_dialogBackground));
        sheet.fixNavigationBar(Theme.getColor(Theme.key_dialogBackground));
        return sheet;
    }

    private static boolean matches(TLRPC.TL_timezone tz, String q, TimezonesController controller) {
        if (tz == null) return false;
        if (tz.id != null && tz.id.toLowerCase(Locale.ROOT).contains(q)) return true;
        if (tz.name != null && tz.name.toLowerCase(Locale.ROOT).contains(q)) return true;
        String pretty = controller.getTimezoneName(tz, true);
        return pretty != null && pretty.toLowerCase(Locale.ROOT).contains(q);
    }
}
