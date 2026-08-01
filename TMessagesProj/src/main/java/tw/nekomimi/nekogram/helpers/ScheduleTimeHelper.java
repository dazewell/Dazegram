package tw.nekomimi.nekogram.helpers;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.text.Layout;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.NumberPicker;
import org.telegram.ui.Components.ScaleStateListAnimator;
import org.telegram.ui.Components.SeekBarView;
import org.telegram.ui.Stories.recorder.HintView2;

import java.util.Calendar;

import xyz.nextalone.nagram.NaConfig;

public final class ScheduleTimeHelper {

    private static final int DEFAULT_SCHEDULE_STEP_COUNT = 30;
    private static final int DEFAULT_SCHEDULE_LAST_MINUTE_STEP = 11;
    private static final int DEFAULT_SCHEDULE_LAST_HOUR_STEP = 22;
    private static final long SEND_WHEN_ONLINE_DATE = 0x7FFFFFFEL;
    // The day wheel stops at 365, so an offset past that could never be picked again.
    private static final int MAX_REMEMBERED_MINUTES = 365 * 24 * 60;
    // Room for two lines of hint plus its arrow; the hint draws bottom-up inside it.
    private static final int HINT_HEIGHT_DP = 120;

    private ScheduleTimeHelper() {
    }

    public static boolean shouldUseDefaultSchedule(long currentDate) {
        return currentDate <= 0 || currentDate == SEND_WHEN_ONLINE_DATE;
    }

    public static long getInitialTargetTime(long currentDate) {
        if (shouldUseDefaultSchedule(currentDate)) {
            int remembered = getRememberedMinutes();
            return getTargetTimeFromNow(remembered > 0 ? remembered : getSliderMinutes());
        }
        return currentDate * 1000L;
    }

    /**
     * Stores the offset the user just confirmed so the next sheet opens on it. Measured from the
     * minute the sheet was seeded off, so confirming an untouched sheet writes back exactly the
     * offset it opened with even if a minute ticks over while it's up.
     */
    public static void rememberOffset(long currentDate, long openedAt, long targetTime) {
        if (!shouldUseDefaultSchedule(currentDate) || !NaConfig.INSTANCE.getRememberScheduleOffset().Bool()) {
            return;
        }
        final int offset = getOffsetMinutes(openedAt, targetTime);
        if (NaConfig.INSTANCE.getRememberedScheduleOffset().Int() != offset) {
            NaConfig.INSTANCE.getRememberedScheduleOffset().setConfigInt(offset);
        }
    }

    /** The offset {@code targetTime} would be stored as, so the header can show it before it's confirmed. */
    private static int getOffsetMinutes(long openedAt, long targetTime) {
        final long now = System.currentTimeMillis();
        // Past a minute open the seeded time is stale (validation drags it forward), so from then on
        // measure what's actually left rather than what was seeded.
        final long from = roundUpToScheduleMinute(now - openedAt > 60000L ? now : openedAt);
        final long minutes = (targetTime / 60000L * 60000L - from) / 60000L;
        return (int) Math.max(1, Math.min(MAX_REMEMBERED_MINUTES, minutes));
    }

    /** The instant the wheels are sitting on, read as device-local time. */
    public static long getTargetTimeFromPickers(NumberPicker dayPicker, NumberPicker hourPicker, NumberPicker minutePicker) {
        final Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(System.currentTimeMillis());
        calendar.add(Calendar.DAY_OF_YEAR, dayPicker.getValue());
        calendar.set(Calendar.HOUR_OF_DAY, hourPicker.getValue());
        calendar.set(Calendar.MINUTE, minutePicker.getValue());
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        return calendar.getTimeInMillis();
    }

    private static int getRememberedMinutes() {
        if (!NaConfig.INSTANCE.getRememberScheduleOffset().Bool()) {
            return 0;
        }
        return Utilities.clamp(NaConfig.INSTANCE.getRememberedScheduleOffset().Int(), MAX_REMEMBERED_MINUTES, 0);
    }

    private static void forgetOffset() {
        if (NaConfig.INSTANCE.getRememberedScheduleOffset().Int() != 0) {
            NaConfig.INSTANCE.getRememberedScheduleOffset().setConfigInt(0);
        }
    }

    private static int getSliderMinutes() {
        return getDefaultScheduleMinutes(getDefaultScheduleStep(NaConfig.INSTANCE.getDefaultScheduledTime().Int()));
    }

    /**
     * Sheet-scoped glue for the Remember toggle. The state itself lives in NaConfig, but the toggle
     * sits in the confirm row while the slider block that reacts to it is built earlier and higher
     * up the sheet, so each side registers what it needs to redraw when the other flips it.
     */
    public static final class RememberToggle {

        private final long openedAt;
        private final Utilities.Callback0Return<Long> selectedTime;
        private Utilities.Callback<Boolean> onSliderBlock;
        private Runnable onPickers;
        private Utilities.Callback<Boolean> onButton;
        private View button;
        private Runnable showHint;

        /**
         * @param openedAt     the minute the wheels were seeded off, the baseline every offset is
         *                     measured from so the header can't disagree with what gets stored
         * @param selectedTime the instant the wheels are on right now, device-local whichever
         *                     time-zone tab is showing
         */
        public RememberToggle(long openedAt, Utilities.Callback0Return<Long> selectedTime) {
            this.openedAt = openedAt;
            this.selectedTime = selectedTime;
        }

        public boolean isOn() {
            return NaConfig.INSTANCE.getRememberScheduleOffset().Bool();
        }

        /** The wheels moved: with the toggle on, the header is tracking them, so re-read it. */
        public void onPickersChanged() {
            if (onPickers != null) {
                onPickers.run();
            }
        }

        private int getLiveOffsetMinutes() {
            return getOffsetMinutes(openedAt, selectedTime.run());
        }

        private void set(boolean on, boolean animated) {
            NaConfig.INSTANCE.getRememberScheduleOffset().setConfigBool(on);
            if (!on) {
                forgetOffset();
            }
            if (onSliderBlock != null) {
                onSliderBlock.run(animated);
            }
            if (onButton != null) {
                onButton.run(animated);
            }
            // Switched on with nothing saved yet, so the sheet still opens on the slider and the tap
            // looks like it did nothing. Say what it's waiting for instead of leaving that unexplained.
            if (on && showHint != null && getRememberedMinutes() == 0) {
                showHint.run();
            }
        }
    }

    /**
     * Wraps the sheet's confirm button so the Remember toggle rides in the same row instead of
     * costing the sheet another row of height. With no toggle to show (editing an existing
     * schedule, bulk reschedule) the button is handed back untouched, layout unchanged.
     */
    public static View wrapConfirmRow(Context context, TextView buttonTextView, RememberToggle remember, int buttonBackgroundColor, int buttonTextColor) {
        if (remember == null) {
            return buttonTextView;
        }

        final LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.addView(buttonTextView, LayoutHelper.createLinear(0, 48, 1f));

        final ImageView toggle = new ImageView(context) {
            @Override
            public CharSequence getAccessibilityClassName() {
                return android.widget.ToggleButton.class.getName();
            }

            @Override
            public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(info);
                info.setCheckable(true);
                info.setChecked(remember.isOn());
            }
        };
        toggle.setImageResource(R.drawable.baseline_bookmark_24);
        toggle.setScaleType(ImageView.ScaleType.CENTER);
        toggle.setContentDescription(getString(R.string.ScheduleRemember));
        ScaleStateListAnimator.apply(toggle, .06f, 1.2f);
        final LinearLayout.LayoutParams toggleParams = LayoutHelper.createLinear(48, 48, Gravity.CENTER_VERTICAL);
        // Start margin rather than left, so the gap stays between the two buttons in RTL.
        toggleParams.setMarginStart(dp(6));
        row.addView(toggle, toggleParams);

        final Utilities.Callback<Boolean> updateToggle = animated -> {
            final boolean on = remember.isOn();
            toggle.setBackground(Theme.AdaptiveRipple.filledRect(on ? buttonBackgroundColor : Theme.multAlpha(buttonBackgroundColor, .15f), 24));
            toggle.setColorFilter(on ? buttonTextColor : buttonBackgroundColor, PorterDuff.Mode.SRC_IN);
        };
        remember.onButton = updateToggle;
        updateToggle.run(false);

        toggle.setOnClickListener(v -> remember.set(!remember.isOn(), true));
        remember.button = toggle;
        return row;
    }

    /**
     * The button is a bare icon, so what it does has to be sayable: long-pressing it explains the
     * toggle, and switching it on with nothing saved yet says so on its own. The hint is hung on the
     * sheet's container because the confirm row is one button tall and would clip a tooltip away.
     */
    public static void setupRememberHint(BottomSheet bottomSheet, RememberToggle remember) {
        if (remember == null || remember.button == null || bottomSheet.getContainerView() == null) {
            return;
        }
        final View button = remember.button;
        final ViewGroup container = bottomSheet.getContainerView();
        final HintView2[] shown = new HintView2[1];

        remember.showHint = () -> {
            if (shown[0] != null) {
                shown[0].hide();
                shown[0] = null;
            }
            final HintView2 hint = shown[0] = new HintView2(button.getContext(), HintView2.DIRECTION_BOTTOM);
            hint.setMultilineText(true);
            hint.setTextAlign(Layout.Alignment.ALIGN_CENTER);
            hint.setRounding(12);
            hint.setPadding(dp(8), 0, dp(8), 0);
            hint.setMaxWidth(240);
            hint.setDuration(5_000L);
            hint.setText(getString(remember.isOn() && getRememberedMinutes() == 0
                    ? R.string.ScheduleRememberHintEmpty
                    : R.string.ScheduleRememberHint));
            hint.setOnHiddenListener(() -> AndroidUtilities.removeFromParent(hint));

            // Where the button ended up in the sheet, so the arrow points at it whichever side the
            // layout direction puts it on and however tall the sheet's own rows made the confirm row.
            // Those coordinates are from the container's outer edge while the hint is laid out inside
            // its padding (the sheet's shadow inset), so the padding comes back off both axes.
            final Rect bounds = new Rect(0, 0, button.getWidth(), button.getHeight());
            container.offsetDescendantRectToMyCoords(button, bounds);
            container.addView(hint, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, HINT_HEIGHT_DP, Gravity.TOP));
            hint.setJointPx(0, bounds.centerX() - container.getPaddingLeft() - dp(8));
            hint.setTranslationY(bounds.top - container.getPaddingTop() - dp(HINT_HEIGHT_DP + 2));
            hint.show();
        };

        button.setOnLongClickListener(v -> {
            remember.showHint.run();
            return true;
        });
    }

    public static void setPickersFromTargetTime(long targetTime, Calendar calendar, NumberPicker dayPicker, NumberPicker hourPicker, NumberPicker minutePicker) {
        calendar.setTimeInMillis(System.currentTimeMillis());
        calendar.set(Calendar.MINUTE, 0);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        calendar.set(Calendar.HOUR_OF_DAY, 0);
        int days = (int) ((targetTime - calendar.getTimeInMillis()) / (24 * 60 * 60 * 1000));
        if (days >= 0) {
            calendar.setTimeInMillis(targetTime);
            minutePicker.setValue(calendar.get(Calendar.MINUTE));
            hourPicker.setValue(calendar.get(Calendar.HOUR_OF_DAY));
            dayPicker.setValue(days);
        }
    }

    public static void addDefaultScheduleSlider(
            Context context,
            LinearLayout container,
            Theme.ResourcesProvider resourcesProvider,
            Calendar calendar,
            NumberPicker dayPicker,
            NumberPicker hourPicker,
            NumberPicker minutePicker,
            RememberToggle remember,
            Runnable onPickersChanged
    ) {
        final LinearLayout quickScheduleLayout = new LinearLayout(context);
        quickScheduleLayout.setOrientation(LinearLayout.VERTICAL);
        container.addView(quickScheduleLayout, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 6, 0, 8));

        final LinearLayout quickScheduleHeader = new LinearLayout(context);
        quickScheduleHeader.setOrientation(LinearLayout.HORIZONTAL);
        quickScheduleHeader.setGravity(Gravity.CENTER_VERTICAL);
        quickScheduleLayout.addView(quickScheduleHeader, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 24, 22, 0, 22, 0));

        final int accentColor = Theme.getColor(Theme.key_player_progress, resourcesProvider);

        final TextView quickScheduleTitle = new TextView(context);
        quickScheduleTitle.setText(getString(R.string.DefaultScheduleDelay));
        quickScheduleTitle.setTextColor(accentColor);
        quickScheduleTitle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        quickScheduleTitle.setTypeface(AndroidUtilities.bold());
        quickScheduleTitle.setGravity(Gravity.CENTER_VERTICAL);
        quickScheduleTitle.setSingleLine(true);
        quickScheduleTitle.setEllipsize(TextUtils.TruncateAt.END);
        quickScheduleHeader.addView(quickScheduleTitle, LayoutHelper.createLinear(0, LayoutHelper.MATCH_PARENT, 1f));

        final TextView quickScheduleValue = new TextView(context);
        int step = getDefaultScheduleStep(NaConfig.INSTANCE.getDefaultScheduledTime().Int());
        int minutes = getDefaultScheduleMinutes(step);
        quickScheduleValue.setText(formatDefaultScheduleMinutes(minutes));
        quickScheduleValue.setTextColor(accentColor);
        quickScheduleValue.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        quickScheduleValue.setGravity(Gravity.CENTER_VERTICAL | Gravity.RIGHT);
        quickScheduleHeader.addView(quickScheduleValue, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.MATCH_PARENT));

        final float initialProgress = getDefaultScheduleProgress(step);

        final SeekBarView quickScheduleSeekBar = new SeekBarView(context, resourcesProvider);
        quickScheduleSeekBar.setReportChanges(true);
        quickScheduleSeekBar.setSeparatorsCount(DEFAULT_SCHEDULE_STEP_COUNT);

        // What the slider is sitting on, which is what the header falls back to once nothing is
        // remembered any more.
        final int[] sliderMinutes = { minutes };
        // With the toggle on the header tracks the wheels rather than a stored number, so it reads
        // as the delay being chosen right now — and what it shows is what confirming would store.
        final Runnable updateValue = () -> quickScheduleValue.setText(formatDefaultScheduleMinutes(
                remember.isOn() ? remember.getLiveOffsetMinutes() : sliderMinutes[0]));
        final Utilities.Callback<Boolean> updateRemember = animated -> {
            final boolean on = remember.isOn();
            // The header follows the button the moment it's tapped, so the tap is visibly about the
            // delay above. Until something is actually saved the slider still seeds the wheels, so it
            // keeps its value and stays lit; once there's an offset it dims to the fallback it is.
            quickScheduleTitle.setText(getString(on ? R.string.ScheduleRememberedDelay : R.string.DefaultScheduleDelay));
            updateValue.run();
            final float alpha = getRememberedMinutes() > 0 ? .4f : 1f;
            if (animated) {
                quickScheduleSeekBar.animate().alpha(alpha).setDuration(180).start();
            } else {
                quickScheduleSeekBar.setAlpha(alpha);
            }
        };
        remember.onSliderBlock = updateRemember;
        remember.onPickers = updateValue;
        updateRemember.run(false);

        quickScheduleSeekBar.setDelegate(new SeekBarView.SeekBarViewDelegate() {
            @Override
            public void onSeekBarDrag(boolean stop, float progress) {
                int step = Math.round(progress * (DEFAULT_SCHEDULE_STEP_COUNT - 1));
                int minutes = getDefaultScheduleMinutes(step);
                sliderMinutes[0] = minutes;
                if (NaConfig.INSTANCE.getDefaultScheduledTime().Int() != minutes) {
                    NaConfig.INSTANCE.getDefaultScheduledTime().setConfigInt(minutes);
                }
                // Touching the slider is a fresh choice of delay, so the remembered one is dropped:
                // it would fight the value the wheels are being set to right here.
                if (remember.isOn()) {
                    remember.set(false, true);
                } else {
                    updateRemember.run(true);
                }
                setPickersFromTargetTime(getTargetTimeFromNow(minutes), calendar, dayPicker, hourPicker, minutePicker);
                onPickersChanged.run();
                if (stop) {
                    quickScheduleSeekBar.setProgress(getDefaultScheduleProgress(step), true);
                }
            }

            @Override
            public CharSequence getContentDescription() {
                return quickScheduleTitle.getText() + ", " + quickScheduleValue.getText();
            }

            @Override
            public int getStepsCount() {
                return DEFAULT_SCHEDULE_STEP_COUNT - 1;
            }
        });
        quickScheduleLayout.addView(quickScheduleSeekBar, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 38, 13, 0, 13, 0));
        AndroidUtilities.doOnLayout(quickScheduleSeekBar, () -> quickScheduleSeekBar.setProgress(initialProgress));
    }

    private static int getDefaultScheduleMinutes(int step) {
        step = Utilities.clamp(step, DEFAULT_SCHEDULE_STEP_COUNT - 1, 0);
        if (step <= DEFAULT_SCHEDULE_LAST_MINUTE_STEP) {
            return (step + 1) * 5;
        } else if (step <= DEFAULT_SCHEDULE_LAST_HOUR_STEP) {
            return (step - 10) * 60;
        }
        return (step - DEFAULT_SCHEDULE_LAST_HOUR_STEP) * 24 * 60;
    }

    private static int getDefaultScheduleStep(int minutes) {
        int bestStep = 0;
        int bestDiff = Integer.MAX_VALUE;
        for (int i = 0; i < DEFAULT_SCHEDULE_STEP_COUNT; i++) {
            int diff = Math.abs(getDefaultScheduleMinutes(i) - minutes);
            if (diff < bestDiff) {
                bestStep = i;
                bestDiff = diff;
            }
        }
        return bestStep;
    }

    private static float getDefaultScheduleProgress(int step) {
        return step / (float) (DEFAULT_SCHEDULE_STEP_COUNT - 1);
    }

    private static String formatDefaultScheduleMinutes(int minutes) {
        // Slider values are always whole minutes/hours/days, but a remembered offset can be any
        // mix of them, so build the label from whichever parts are non-zero.
        final int days = minutes / (24 * 60);
        final int hours = minutes / 60 % 24;
        final int mins = minutes % 60;
        final StringBuilder sb = new StringBuilder();
        if (days > 0) {
            sb.append(LocaleController.formatPluralString("Days", days));
        }
        if (hours > 0) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(LocaleController.formatPluralString("Hours", hours));
        }
        if (mins > 0 || sb.length() == 0) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(LocaleController.formatPluralString("Minutes", mins));
        }
        return sb.toString();
    }

    private static long getTargetTimeFromNow(int minutes) {
        return roundUpToScheduleMinute(System.currentTimeMillis() + (long) minutes * 60 * 1000L);
    }

    private static long roundUpToScheduleMinute(long time) {
        return ((time + 59999L) / 60000L) * 60000L;
    }
}
