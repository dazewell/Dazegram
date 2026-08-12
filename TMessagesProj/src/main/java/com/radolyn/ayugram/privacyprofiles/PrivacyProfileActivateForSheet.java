package com.radolyn.ayugram.privacyprofiles;

import android.content.Context;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AlertsCreator;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.NumberPicker;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

/**
 * "Activate {profile} for..." bottom sheet (round 2, item 2). Modeled directly on
 * AlertsCreator.createSoundFrequencyPickerDialog (BottomSheet.Builder + ScheduleDatePickerColors
 * + a NumberPicker-based divider row) and createMuteForPickerDialog -- this is the same primitive
 * family, just with an added preset-pill row on top. No alarm/timer machinery: activating just
 * stores a deadline, which PrivacyProfilesController.reconcile() settles later, same as every
 * other activation path in this feature.
 */
public final class PrivacyProfileActivateForSheet {

    private PrivacyProfileActivateForSheet() {}

    // NumberPicker.setEnabled() only gates touch handling (see NumberPicker.onTouchEvent /
    // onInterceptTouchEvent) -- it doesn't change how the wheel draws, so a disabled wheel looks
    // identical to an enabled one. Dim it too so the 168h cap is visible, not just enforced.
    private static void setMinutesEnabled(NumberPicker minutes, boolean enabled) {
        minutes.setEnabled(enabled);
        minutes.setAlpha(enabled ? 1f : 0.5f);
    }

    private static final long[] PRESET_DURATIONS_MS = {
            15 * 60 * 1000L,
            60 * 60 * 1000L,
            4 * 60 * 60 * 1000L,
            24 * 60 * 60 * 1000L,
            7 * 24 * 60 * 60 * 1000L,
    };
    private static final int[] PRESET_LABELS = {
            R.string.PrivacyProfileFor15Minutes,
            R.string.PrivacyProfileFor1Hour,
            R.string.PrivacyProfileFor4Hours,
            R.string.PrivacyProfileFor1Day,
            R.string.PrivacyProfileFor1Week,
    };
    private static final int MAX_HOURS = 168; // exactly one week; minutes are forced to 0 and disabled at this value

    /** onActivated runs only after a successful PrivacyProfilesController.activate() call. */
    public static void show(BaseFragment fragment, PrivacyProfile profile, Runnable onActivated) {
        Context context = fragment.getParentActivity();
        if (context == null) return;

        AlertsCreator.ScheduleDatePickerColors colors = new AlertsCreator.ScheduleDatePickerColors(fragment.getResourceProvider());
        BottomSheet.Builder builder = new BottomSheet.Builder(context, false, fragment.getResourceProvider());
        builder.setApplyBottomPadding(false);

        LinearLayout container = new LinearLayout(context);
        container.setOrientation(LinearLayout.VERTICAL);

        TextView titleView = new TextView(context);
        titleView.setText(LocaleController.formatString(R.string.PrivacyProfileActivateForTitle, profile.name));
        titleView.setTextColor(colors.textColor);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
        titleView.setTypeface(AndroidUtilities.bold());
        titleView.setOnTouchListener((v, event) -> true);
        container.addView(titleView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT, 22, 12, 22, 4));

        // Preset pills, horizontally scrollable. Tapping one only moves the wheels below; it never
        // activates by itself, matching the wheel-first design the brief calls for.
        HorizontalScrollView pillsScroll = new HorizontalScrollView(context);
        pillsScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout pillsRow = new LinearLayout(context);
        pillsRow.setOrientation(LinearLayout.HORIZONTAL);
        pillsScroll.addView(pillsRow, LayoutHelper.createScroll(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT));
        container.addView(pillsScroll, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 8, 0, 8, 0));

        final NumberPicker hours = new NumberPicker(context, fragment.getResourceProvider());
        final NumberPicker minutes = new NumberPicker(context, fragment.getResourceProvider());
        final TextView[] pills = new TextView[PRESET_DURATIONS_MS.length];

        Runnable[] refreshRef = new Runnable[1];
        for (int i = 0; i < PRESET_DURATIONS_MS.length; i++) {
            final int index = i;
            TextView pill = new TextView(context);
            pill.setText(getString(PRESET_LABELS[i]));
            pill.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            pill.setGravity(Gravity.CENTER);
            pill.setPadding(dp(14), dp(8), dp(14), dp(8));
            pill.setOnClickListener(v -> {
                long ms = PRESET_DURATIONS_MS[index];
                hours.setValue((int) Math.min(MAX_HOURS, ms / 3600000L));
                minutes.setValue((int) ((ms % 3600000L) / 60000L));
                setMinutesEnabled(minutes, hours.getValue() != MAX_HOURS);
                refreshRef[0].run();
            });
            pills[i] = pill;
            pillsRow.addView(pill, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 0, i == 0 ? 22 : 8, 0, 0));
        }

        // Hours/minutes wheels with a fixed ":" divider, same fake-picker-as-divider idiom
        // createSoundFrequencyPickerDialog uses so the divider vertically centers on the wheels
        // instead of needing separate layout math.
        hours.setMinValue(0);
        hours.setMaxValue(MAX_HOURS);
        hours.setTextColor(colors.textColor);
        hours.setWrapSelectorWheel(false);
        hours.setFormatter(v -> String.format(LocaleController.getInstance().getCurrentLocale(), "%d", v));

        minutes.setMinValue(0);
        minutes.setMaxValue(59);
        minutes.setTextColor(colors.textColor);
        minutes.setWrapSelectorWheel(false);
        minutes.setFormatter(v -> String.format(LocaleController.getInstance().getCurrentLocale(), "%02d", v));

        NumberPicker divider = new NumberPicker(context, fragment.getResourceProvider());
        divider.setMinValue(0);
        divider.setMaxValue(0);
        divider.setValue(0);
        divider.setTextColor(colors.textColor);
        divider.setWrapSelectorWheel(false);
        divider.setFormatter(v -> ":");

        LinearLayout wheelsRow = new LinearLayout(context) {
            boolean ignoreLayout = false;
            @Override
            protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                ignoreLayout = true;
                int count = 5;
                hours.setItemCount(count);
                hours.getLayoutParams().height = dp(NumberPicker.DEFAULT_SIZE_PER_COUNT) * count;
                minutes.setItemCount(count);
                minutes.getLayoutParams().height = dp(NumberPicker.DEFAULT_SIZE_PER_COUNT) * count;
                divider.setItemCount(count);
                divider.getLayoutParams().height = dp(NumberPicker.DEFAULT_SIZE_PER_COUNT) * count;
                ignoreLayout = false;
                super.onMeasure(widthMeasureSpec, heightMeasureSpec);
            }
            @Override
            public void requestLayout() {
                if (ignoreLayout) return;
                super.requestLayout();
            }
        };
        wheelsRow.setOrientation(LinearLayout.HORIZONTAL);
        wheelsRow.setWeightSum(1f);
        wheelsRow.addView(hours, LayoutHelper.createLinear(0, 54 * 5, 0.42f));
        wheelsRow.addView(divider, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 0.16f, Gravity.CENTER_VERTICAL));
        wheelsRow.addView(minutes, LayoutHelper.createLinear(0, 54 * 5, 0.42f));
        container.addView(wheelsRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 1f, 0, 0, 12, 0, 0));

        TextView captionView = new TextView(context);
        captionView.setText(getString(R.string.PrivacyProfileActivateForCaption));
        captionView.setTextColor(Theme.multAlpha(colors.textColor, 0.6f));
        captionView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        captionView.setGravity(Gravity.CENTER);
        container.addView(captionView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 22, 4, 22, 8));

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
        buttonTextView.setText(getString(R.string.PrivacyProfileActivateForConfirm));
        container.addView(buttonTextView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48, Gravity.LEFT | Gravity.BOTTOM, 16, 0, 16, 16));

        Runnable refresh = () -> {
            long currentMs = hours.getValue() * 3600000L + minutes.getValue() * 60000L;
            for (int i = 0; i < pills.length; i++) {
                boolean selected = PRESET_DURATIONS_MS[i] == currentMs;
                pills[i].setBackground(Theme.createSimpleSelectorRoundRectDrawable(dp(999),
                        selected ? colors.buttonBackgroundColor : Theme.multAlpha(colors.textColor, 0.08f),
                        selected ? colors.buttonBackgroundPressedColor : Theme.multAlpha(colors.textColor, 0.16f)));
                pills[i].setTextColor(selected ? colors.buttonTextColor : colors.textColor);
            }
            boolean enabled = currentMs > 0;
            buttonTextView.setAlpha(enabled ? 1f : 0.5f);
            buttonTextView.setEnabled(enabled);
            buttonTextView.setBackgroundDrawable(enabled
                    ? Theme.createSimpleSelectorRoundRectDrawable(dp(8), colors.buttonBackgroundColor, colors.buttonBackgroundPressedColor)
                    : Theme.createRoundRectDrawable(dp(8), Theme.multAlpha(colors.textColor, 0.08f)));
        };
        refreshRef[0] = refresh;

        // setValue() never fires OnValueChangeListener (only user scrolling does -- see
        // NumberPicker.setValueInternal), so no re-entrancy guard is needed between the pills and
        // the wheels in either direction.
        hours.setOnValueChangedListener((picker, oldVal, newVal) -> {
            if (newVal == MAX_HOURS) {
                minutes.setValue(0);
                setMinutesEnabled(minutes, false);
            } else {
                setMinutesEnabled(minutes, true);
            }
            refresh.run();
        });
        minutes.setOnValueChangedListener((picker, oldVal, newVal) -> refresh.run());

        long prefillMs = PrivacyProfilesController.getLastCustomDurationMillis(profile.id);
        if (prefillMs < 0) prefillMs = 60 * 60 * 1000L; // first-ever use on this profile: default to 1 hour
        int initialHours = (int) Math.min(MAX_HOURS, prefillMs / 3600000L);
        int initialMinutes = initialHours == MAX_HOURS ? 0 : (int) ((prefillMs % 3600000L) / 60000L);
        hours.setValue(initialHours);
        minutes.setValue(initialMinutes);
        setMinutesEnabled(minutes, initialHours != MAX_HOURS);
        refresh.run();

        buttonTextView.setOnClickListener(v -> {
            long durationMs = hours.getValue() * 3600000L + minutes.getValue() * 60000L;
            if (durationMs <= 0) return;
            if (PrivacyProfilesController.activate(profile.id, PrivacyProfilesController.ActivationMode.FOR, durationMs)) {
                if (onActivated != null) onActivated.run();
            }
            builder.getDismissRunnable().run();
        });

        builder.setCustomView(container);
        BottomSheet bottomSheet = builder.show();
        bottomSheet.setBackgroundColor(colors.backgroundColor);
        bottomSheet.fixNavigationBar(colors.backgroundColor);
    }
}
