package tw.nekomimi.nekogram.helpers;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.NumberPicker;
import org.telegram.ui.Components.SeekBarView;
import org.telegram.ui.Components.Switch;

import java.util.Calendar;

import xyz.nextalone.nagram.NaConfig;

public final class ScheduleTimeHelper {

    private static final int DEFAULT_SCHEDULE_STEP_COUNT = 30;
    private static final int DEFAULT_SCHEDULE_LAST_MINUTE_STEP = 11;
    private static final int DEFAULT_SCHEDULE_LAST_HOUR_STEP = 22;
    private static final long SEND_WHEN_ONLINE_DATE = 0x7FFFFFFEL;
    // The day wheel stops at 365, so an offset past that could never be picked again.
    private static final int MAX_REMEMBERED_MINUTES = 365 * 24 * 60;

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
        final long now = System.currentTimeMillis();
        // Past a minute open the seeded time is stale (validation drags it forward), so from then on
        // measure what's actually left rather than what was seeded.
        final long from = roundUpToScheduleMinute(now - openedAt > 60000L ? now : openedAt);
        final long minutes = (targetTime / 60000L * 60000L - from) / 60000L;
        final int offset = (int) Math.max(1, Math.min(MAX_REMEMBERED_MINUTES, minutes));
        if (NaConfig.INSTANCE.getRememberedScheduleOffset().Int() != offset) {
            NaConfig.INSTANCE.getRememberedScheduleOffset().setConfigInt(offset);
        }
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

        final LinearLayout rememberRow = new LinearLayout(context);
        rememberRow.setOrientation(LinearLayout.HORIZONTAL);
        rememberRow.setGravity(Gravity.CENTER_VERTICAL);
        quickScheduleLayout.addView(rememberRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 32, 22, 0, 22, 0));

        final TextView rememberTitle = new TextView(context);
        rememberTitle.setText(getString(R.string.ScheduleRemember));
        rememberTitle.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText3, resourcesProvider));
        rememberTitle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        rememberTitle.setGravity(Gravity.CENTER_VERTICAL);
        rememberTitle.setSingleLine(true);
        rememberTitle.setEllipsize(TextUtils.TruncateAt.END);
        rememberRow.addView(rememberTitle, LayoutHelper.createLinear(0, LayoutHelper.MATCH_PARENT, 1f));

        final TextView rememberValue = new TextView(context);
        rememberValue.setTextColor(accentColor);
        rememberValue.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        rememberValue.setGravity(Gravity.CENTER_VERTICAL);
        // Padding instead of a margin so the gap before the switch survives RTL.
        rememberValue.setPadding(dp(8), 0, dp(8), 0);
        rememberRow.addView(rememberValue, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.MATCH_PARENT));

        final Switch rememberSwitch = new Switch(context, resourcesProvider);
        rememberSwitch.setColors(Theme.key_switchTrack, Theme.key_switchTrackChecked, Theme.key_windowBackgroundWhite, Theme.key_windowBackgroundWhite);
        rememberSwitch.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        rememberRow.addView(rememberSwitch, LayoutHelper.createLinear(37, 20, Gravity.CENTER_VERTICAL));

        final Utilities.Callback<Boolean> updateRemember = animated -> {
            boolean on = NaConfig.INSTANCE.getRememberScheduleOffset().Bool();
            int remembered = on ? NaConfig.INSTANCE.getRememberedScheduleOffset().Int() : 0;
            rememberSwitch.setChecked(on, animated);
            rememberValue.setText(remembered > 0 ? formatDefaultScheduleMinutes(remembered) : "");
            rememberValue.setVisibility(remembered > 0 ? View.VISIBLE : View.GONE);
            rememberRow.setContentDescription(rememberTitle.getText() + ", "
                    + (remembered > 0 ? rememberValue.getText() + ", " : "")
                    + getString(on ? R.string.NotificationsOn : R.string.NotificationsOff));
        };
        updateRemember.run(false);

        rememberRow.setOnClickListener(v -> {
            boolean on = !NaConfig.INSTANCE.getRememberScheduleOffset().Bool();
            NaConfig.INSTANCE.getRememberScheduleOffset().setConfigBool(on);
            if (!on) {
                forgetOffset();
            }
            updateRemember.run(true);
        });

        final SeekBarView quickScheduleSeekBar = new SeekBarView(context, resourcesProvider);
        quickScheduleSeekBar.setReportChanges(true);
        quickScheduleSeekBar.setSeparatorsCount(DEFAULT_SCHEDULE_STEP_COUNT);
        quickScheduleSeekBar.setDelegate(new SeekBarView.SeekBarViewDelegate() {
            @Override
            public void onSeekBarDrag(boolean stop, float progress) {
                int step = Math.round(progress * (DEFAULT_SCHEDULE_STEP_COUNT - 1));
                int minutes = getDefaultScheduleMinutes(step);
                quickScheduleValue.setText(formatDefaultScheduleMinutes(minutes));
                if (NaConfig.INSTANCE.getDefaultScheduledTime().Int() != minutes) {
                    NaConfig.INSTANCE.getDefaultScheduledTime().setConfigInt(minutes);
                }
                // Touching the slider is a fresh choice of delay, so the remembered one is dropped:
                // it would fight the value the wheels are being set to right here.
                if (NaConfig.INSTANCE.getRememberScheduleOffset().Bool()) {
                    NaConfig.INSTANCE.getRememberScheduleOffset().setConfigBool(false);
                    forgetOffset();
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
