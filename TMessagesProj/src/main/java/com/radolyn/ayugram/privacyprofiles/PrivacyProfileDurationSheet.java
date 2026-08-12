package com.radolyn.ayugram.privacyprofiles;

import android.content.Context;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AlertsCreator;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.NumberPicker;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

/**
 * The "Activate for" custom-duration sheet: two stock wheels (hours, minutes) and nothing else.
 * Built on the same shape as AlertsCreator.createSoundFrequencyPickerDialog -- BottomSheet.Builder
 * + ScheduleDatePickerColors + a container whose onMeasure fixes the wheel height at 5 visible
 * values in portrait / 3 in landscape -- rather than adding a static to that 9,000-line upstream
 * file. No colon divider, no preset pills, no caption, no profile name: the presets live in the
 * profile's own ItemOptions menu, and this sheet only exists for a genuinely custom time.
 */
public final class PrivacyProfileDurationSheet {

    private PrivacyProfileDurationSheet() {}

    private static final int MAX_HOURS = 168; // exactly 7 days

    /** onActivated runs only after a successful activation. */
    public static void show(BaseFragment fragment, PrivacyProfile profile, Runnable onActivated) {
        Context context = fragment.getParentActivity();
        if (context == null) return;

        AlertsCreator.ScheduleDatePickerColors colors = new AlertsCreator.ScheduleDatePickerColors(fragment.getResourceProvider());
        BottomSheet.Builder builder = new BottomSheet.Builder(context, false, fragment.getResourceProvider());
        builder.setApplyBottomPadding(false);

        final NumberPicker hours = new NumberPicker(context, fragment.getResourceProvider()) {
            @Override
            protected CharSequence getContentDescription(int value) {
                return LocaleController.formatPluralString("Hours", value);
            }
        };
        hours.setMinValue(0);
        hours.setMaxValue(MAX_HOURS);
        hours.setTextColor(colors.textColor);
        hours.setWrapSelectorWheel(false);
        hours.setFormatter(value -> LocaleController.formatPluralString("Hours", value));

        final NumberPicker minutes = new NumberPicker(context, fragment.getResourceProvider()) {
            @Override
            protected CharSequence getContentDescription(int value) {
                return LocaleController.formatPluralString("Minutes", value);
            }
        };
        minutes.setMinValue(0);
        minutes.setMaxValue(59);
        minutes.setTextColor(colors.textColor);
        minutes.setWrapSelectorWheel(false);
        minutes.setFormatter(value -> LocaleController.formatPluralString("Minutes", value));

        // Fixed wheel height, driven from the container's own onMeasure exactly as the stock
        // pickers do -- the wheels are added with height 0 and no weight, so there is no
        // weighted empty space to collapse or stretch.
        LinearLayout container = new LinearLayout(context) {
            boolean ignoreLayout = false;

            @Override
            protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                ignoreLayout = true;
                int count = AndroidUtilities.displaySize.x > AndroidUtilities.displaySize.y ? 3 : 5;
                hours.setItemCount(count);
                hours.getLayoutParams().height = dp(NumberPicker.DEFAULT_SIZE_PER_COUNT) * count;
                minutes.setItemCount(count);
                minutes.getLayoutParams().height = dp(NumberPicker.DEFAULT_SIZE_PER_COUNT) * count;
                ignoreLayout = false;
                super.onMeasure(widthMeasureSpec, heightMeasureSpec);
            }

            @Override
            public void requestLayout() {
                if (ignoreLayout) return;
                super.requestLayout();
            }
        };
        container.setOrientation(LinearLayout.VERTICAL);

        FrameLayout titleLayout = new FrameLayout(context);
        container.addView(titleLayout, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT | Gravity.TOP, 22, 0, 0, 4));

        TextView titleView = new TextView(context);
        titleView.setText(getString(R.string.PrivacyProfileActivateForTitle));
        titleView.setTextColor(colors.textColor);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
        titleView.setTypeface(AndroidUtilities.bold());
        titleView.setOnTouchListener((v, event) -> true);
        titleLayout.addView(titleView, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT | Gravity.TOP, 0, 12, 0, 0));

        LinearLayout wheelsRow = new LinearLayout(context);
        wheelsRow.setOrientation(LinearLayout.HORIZONTAL);
        wheelsRow.setWeightSum(1f);
        wheelsRow.addView(hours, LayoutHelper.createLinear(0, 0, 0.5f));
        wheelsRow.addView(minutes, LayoutHelper.createLinear(0, 0, 0.5f));
        container.addView(wheelsRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 1f, 0, 0, 12, 0, 12));

        TextView buttonTextView = new TextView(context) {
            @Override
            public CharSequence getAccessibilityClassName() {
                return Button.class.getName();
            }
        };
        buttonTextView.setPadding(dp(34), 0, dp(34), 0);
        buttonTextView.setGravity(Gravity.CENTER);
        buttonTextView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        buttonTextView.setTypeface(AndroidUtilities.bold());
        container.addView(buttonTextView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48, Gravity.LEFT | Gravity.BOTTOM, 16, 15, 16, 16));

        final BottomSheet[] sheet = new BottomSheet[1];

        Runnable refresh = () -> {
            long durationMs = hours.getValue() * 3600000L + minutes.getValue() * 60000L;
            boolean enabled = durationMs > 0;
            buttonTextView.setText(enabled
                    ? LocaleController.formatString(R.string.PrivacyProfileActivateForButton, formatDuration(hours.getValue(), minutes.getValue()))
                    : getString(R.string.PrivacyProfileChooseATime));
            buttonTextView.setEnabled(enabled);
            buttonTextView.setAlpha(enabled ? 1f : 0.5f);
            buttonTextView.setTextColor(enabled ? colors.buttonTextColor : colors.textColor);
            buttonTextView.setBackgroundDrawable(enabled
                    ? Theme.createSimpleSelectorRoundRectDrawable(dp(8), colors.buttonBackgroundColor, colors.buttonBackgroundPressedColor)
                    : Theme.createRoundRectDrawable(dp(8), Theme.multAlpha(colors.textColor, 0.08f)));
        };

        // Cross-wheel clamp on every change, the same way the stock time pickers do it, so the
        // user can never see a value past the cap that then gets silently rewritten on confirm.
        hours.setOnValueChangedListener((picker, oldVal, newVal) -> {
            if (newVal == MAX_HOURS) {
                minutes.setMaxValue(0);
                minutes.setValue(0);
                if (PrivacyProfilesController.shouldShowSevenDayCapHint()) {
                    BulletinFactory.of(fragment)
                            .createSimpleBulletin(R.raw.info, getString(R.string.PrivacyProfileSevenDayCap)).show();
                }
            } else {
                minutes.setMaxValue(59);
            }
            refresh.run();
        });
        minutes.setOnValueChangedListener((picker, oldVal, newVal) -> refresh.run());

        long prefillMs = PrivacyProfilesController.getLastCustomDurationMillis(profile.id);
        if (prefillMs < 0) prefillMs = 60 * 60 * 1000L; // never customised for this profile: 1 hour
        int initialHours = (int) Math.min(MAX_HOURS, prefillMs / 3600000L);
        int initialMinutes = initialHours == MAX_HOURS ? 0 : (int) ((prefillMs % 3600000L) / 60000L);
        if (initialHours == MAX_HOURS) {
            minutes.setMaxValue(0);
        }
        hours.setValue(initialHours);
        minutes.setValue(initialMinutes);
        refresh.run();

        buttonTextView.setOnClickListener(v -> {
            long durationMs = hours.getValue() * 3600000L + minutes.getValue() * 60000L;
            if (durationMs <= 0) return;
            if (PrivacyProfilesController.activate(profile.id, PrivacyProfilesController.ActivationMode.FOR_CUSTOM, durationMs)) {
                if (onActivated != null) onActivated.run();
            }
            builder.getDismissRunnable().run();
        });

        builder.setCustomView(container);
        sheet[0] = builder.show();
        sheet[0].setBackgroundColor(colors.backgroundColor);
        sheet[0].fixNavigationBar(colors.backgroundColor);
    }

    /** "2 hours 30 minutes", or just the non-zero part when the other is zero. */
    public static String formatDuration(int hours, int minutes) {
        if (hours > 0 && minutes > 0) {
            return LocaleController.formatPluralString("Hours", hours) + " " + LocaleController.formatPluralString("Minutes", minutes);
        }
        if (hours > 0) {
            return LocaleController.formatPluralString("Hours", hours);
        }
        return LocaleController.formatPluralString("Minutes", minutes);
    }
}
