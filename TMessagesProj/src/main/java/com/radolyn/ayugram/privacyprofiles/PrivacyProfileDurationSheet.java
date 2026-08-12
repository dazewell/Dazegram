package com.radolyn.ayugram.privacyprofiles;

import android.content.Context;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
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
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.NumberPicker;
import org.telegram.ui.Components.ScrollSlidingTextTabStrip;

import java.util.Calendar;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

/**
 * "Set a timer" -- one sheet with two tabs, reached from the profile's own menu and from the
 * quick-switch row's clock. <b>For</b> is preset pills over hours/minutes wheels; <b>Until</b> is
 * the stock day/hour/minute trio. Built on BottomSheet.Builder + ScheduleDatePickerColors and the
 * stock container/onMeasure idiom rather than adding statics to AlertsCreator, which stays a
 * read-only consumer (its {@code checkScheduleDate} does the Until clamping).
 */
public final class PrivacyProfileDurationSheet {

    private PrivacyProfileDurationSheet() {}

    private static final int MAX_HOURS = 168; // exactly 7 days
    private static final int MAX_DAYS = 7;
    private static final long MAX_UNTIL_SECONDS = MAX_DAYS * 24L * 3600L;

    private static final long[] PRESET_MS = {
            3600000L,
            4 * 3600000L,
            8 * 3600000L,
            24 * 3600000L,
            7 * 24 * 3600000L,
    };

    private static String presetLabel(long ms) {
        int hours = (int) (ms / 3600000L);
        if (hours % (24 * 7) == 0) return LocaleController.formatPluralString("Weeks", hours / (24 * 7));
        if (hours % 24 == 0) return LocaleController.formatPluralString("Days", hours / 24);
        return LocaleController.formatPluralString("Hours", hours);
    }

    public static void show(BaseFragment fragment, PrivacyProfile profile, Runnable onActivated) {
        Context context = fragment.getParentActivity();
        if (context == null) return;

        final AlertsCreator.ScheduleDatePickerColors colors = new AlertsCreator.ScheduleDatePickerColors(fragment.getResourceProvider());
        final BottomSheet.Builder builder = new BottomSheet.Builder(context, false, fragment.getResourceProvider());
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

        final NumberPicker dayPicker = new NumberPicker(context, fragment.getResourceProvider());
        dayPicker.setTextColor(colors.textColor);
        dayPicker.setTextOffset(dp(10));
        final NumberPicker untilHour = new NumberPicker(context, fragment.getResourceProvider()) {
            @Override
            protected CharSequence getContentDescription(int value) {
                return LocaleController.formatPluralString("Hours", value);
            }
        };
        untilHour.setTextColor(colors.textColor);
        untilHour.setTextOffset(-dp(10));
        final NumberPicker untilMinute = new NumberPicker(context, fragment.getResourceProvider()) {
            @Override
            protected CharSequence getContentDescription(int value) {
                return LocaleController.formatPluralString("Minutes", value);
            }
        };
        untilMinute.setTextColor(colors.textColor);
        untilMinute.setTextOffset(-dp(34));

        final long now = System.currentTimeMillis();
        dayPicker.setMinValue(0);
        dayPicker.setMaxValue(MAX_DAYS);
        dayPicker.setWrapSelectorWheel(false);
        dayPicker.setFormatter(value -> {
            if (value == 0) return getString(R.string.MessageScheduleToday);
            Calendar calendar = Calendar.getInstance();
            calendar.setTimeInMillis(now + value * 86400000L);
            return LocaleController.getInstance().getFormatterScheduleDay().format(calendar.getTimeInMillis());
        });
        untilHour.setMinValue(0);
        untilHour.setMaxValue(23);
        untilHour.setFormatter(value -> String.format("%02d", value));
        untilMinute.setMinValue(0);
        untilMinute.setMaxValue(59);
        untilMinute.setFormatter(value -> String.format("%02d", value));

        // Both tabs' wheels share one onMeasure so the sheet keeps a stable height when tabs swap:
        // 5 visible values portrait, 3 landscape, the stock idiom.
        final LinearLayout container = new LinearLayout(context) {
            boolean ignoreLayout = false;

            @Override
            protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                ignoreLayout = true;
                int count = AndroidUtilities.displaySize.x > AndroidUtilities.displaySize.y ? 3 : 5;
                int h = dp(NumberPicker.DEFAULT_SIZE_PER_COUNT) * count;
                NumberPicker[] all = {hours, minutes, dayPicker, untilHour, untilMinute};
                for (NumberPicker p : all) {
                    p.setItemCount(count);
                    p.getLayoutParams().height = h;
                }
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
        container.addView(titleLayout, LayoutHelper.createLinearRelatively(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.START | Gravity.TOP, 22, 0, 0, 4));
        TextView titleView = new TextView(context);
        titleView.setText(getString(R.string.PrivacyProfileSetTimerTitle));
        titleView.setTextColor(colors.textColor);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
        titleView.setTypeface(AndroidUtilities.bold());
        titleView.setOnTouchListener((v, event) -> true);
        titleLayout.addView(titleView, LayoutHelper.createFrameRelatively(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.START | Gravity.TOP, 0, 12, 0, 0));

        final ScrollSlidingTextTabStrip tabs = new ScrollSlidingTextTabStrip(context, fragment.getResourceProvider());
        tabs.setUseSameWidth(true);
        // The strip's own defaults are action-bar keys, which aren't meant to sit on a sheet's
        // background; the profile-tab set is what other in-sheet strips use.
        tabs.setColors(Theme.key_profile_tabSelectedLine, Theme.key_profile_tabSelectedText, Theme.key_profile_tabText, Theme.key_profile_tabSelector);
        container.addView(tabs, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 44, 0, 4, 0, 0));

        // Both tab bodies are built up front and toggled VISIBLE/INVISIBLE, never GONE: the shared
        // onMeasure above reaches into every wheel's layout params, so a lazily-built wheel would
        // NPE, and a GONE child would make the sheet's height jump between tabs.
        final FrameLayout tabContent = new FrameLayout(context);

        final LinearLayout forTab = new LinearLayout(context);
        forTab.setOrientation(LinearLayout.VERTICAL);

        HorizontalScrollView pillsScroll = new HorizontalScrollView(context);
        pillsScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout pillsRow = new LinearLayout(context);
        pillsRow.setOrientation(LinearLayout.HORIZONTAL);
        pillsScroll.addView(pillsRow, LayoutHelper.createScroll(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.START));
        forTab.addView(pillsScroll, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 8, 0, 4));
        final TextView[] pills = new TextView[PRESET_MS.length];

        LinearLayout forWheels = new LinearLayout(context);
        forWheels.setOrientation(LinearLayout.HORIZONTAL);
        forWheels.setWeightSum(1f);
        forWheels.addView(hours, LayoutHelper.createLinear(0, 0, 0.5f));
        forWheels.addView(minutes, LayoutHelper.createLinear(0, 0, 0.5f));
        forTab.addView(forWheels, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 1f, 0, 0, 0, 0, 12));

        final LinearLayout untilWheels = new LinearLayout(context);
        untilWheels.setOrientation(LinearLayout.HORIZONTAL);
        untilWheels.setWeightSum(1f);
        untilWheels.addView(dayPicker, LayoutHelper.createLinear(0, 0, 0.5f));
        untilWheels.addView(untilHour, LayoutHelper.createLinear(0, 0, 0.2f));
        untilWheels.addView(untilMinute, LayoutHelper.createLinear(0, 0, 0.3f));

        tabContent.addView(forTab, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        // Centred vertically: the For body is taller by the pills row, and pinning both to the top
        // would make the wheels visibly jump up when the Until tab is selected.
        tabContent.addView(untilWheels, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL));
        container.addView(tabContent, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 1f, 0, 0, 0, 0, 0));

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
        container.addView(buttonTextView, LayoutHelper.createLinearRelatively(LayoutHelper.MATCH_PARENT, 48, Gravity.START | Gravity.BOTTOM, 16, 15, 16, 16));

        final BottomSheet[] sheet = new BottomSheet[1];
        final int[] currentTab = {0};

        final Runnable refresh = () -> {
            boolean untilTab = currentTab[0] == 1;
            boolean enabled;
            if (untilTab) {
                enabled = true;
                buttonTextView.setText(LocaleController.formatString(R.string.PrivacyProfileActivateUntilButton,
                        LocaleController.formatDateTime(untilDeadline(now, dayPicker, untilHour, untilMinute) / 1000, true)));
            } else {
                long durationMs = hours.getValue() * 3600000L + minutes.getValue() * 60000L;
                enabled = durationMs > 0;
                buttonTextView.setText(enabled
                        ? LocaleController.formatString(R.string.PrivacyProfileActivateForButton, formatDuration(hours.getValue(), minutes.getValue()))
                        : getString(R.string.PrivacyProfileChooseATime));
                for (int i = 0; i < pills.length; i++) {
                    boolean selected = PRESET_MS[i] == durationMs;
                    // setSelected so TalkBack announces the state, not just the fill colour.
                    pills[i].setSelected(selected);
                    pills[i].setBackground(Theme.createSimpleSelectorRoundRectDrawable(dp(999),
                            selected ? colors.buttonBackgroundColor : Theme.multAlpha(colors.textColor, 0.08f),
                            selected ? colors.buttonBackgroundPressedColor : Theme.multAlpha(colors.textColor, 0.16f)));
                    pills[i].setTextColor(selected ? colors.buttonTextColor : colors.textColor);
                }
            }
            buttonTextView.setEnabled(enabled);
            buttonTextView.setAlpha(enabled ? 1f : 0.5f);
            buttonTextView.setTextColor(enabled ? colors.buttonTextColor : colors.textColor);
            buttonTextView.setBackgroundDrawable(enabled
                    ? Theme.createSimpleSelectorRoundRectDrawable(dp(8), colors.buttonBackgroundColor, colors.buttonBackgroundPressedColor)
                    : Theme.createRoundRectDrawable(dp(8), Theme.multAlpha(colors.textColor, 0.08f)));
        };

        for (int i = 0; i < PRESET_MS.length; i++) {
            final long ms = PRESET_MS[i];
            TextView pill = new TextView(context);
            pill.setText(presetLabel(ms));
            pill.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            pill.setGravity(Gravity.CENTER);
            pill.setPadding(dp(14), dp(8), dp(14), dp(8));
            pill.setOnClickListener(v -> {
                hours.setValue((int) Math.min(MAX_HOURS, ms / 3600000L));
                minutes.setValue((int) ((ms % 3600000L) / 60000L));
                // setValue() never fires the wheel's listener, so the cap is applied by hand --
                // and this IS user-initiated, so the one-time hint may fire.
                applyHourCap(hours, minutes, true, sheet, fragment);
                refresh.run();
            });
            pills[i] = pill;
            // Absolute margins here, unlike the title/button: this row's children are never
            // reversed, so a start-relative margin would flip to the right of a left-drawn pill
            // in RTL and knock the row out of line with the title.
            pillsRow.addView(pill, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, i == 0 ? 22 : 8, 0, 0, 0));
        }

        hours.setOnValueChangedListener((picker, oldVal, newVal) -> {
            applyHourCap(hours, minutes, true, sheet, fragment);
            refresh.run();
        });
        minutes.setOnValueChangedListener((picker, oldVal, newVal) -> refresh.run());

        final NumberPicker.OnValueChangeListener untilChanged = (picker, oldVal, newVal) -> {
            clampUntil(now, dayPicker, untilHour, untilMinute);
            refresh.run();
        };
        dayPicker.setOnValueChangedListener(untilChanged);
        untilHour.setOnValueChangedListener(untilChanged);
        untilMinute.setOnValueChangedListener(untilChanged);

        // Prefill. Which tab opens follows what the profile is actually doing right now.
        PrivacyProfile active = PrivacyProfilesController.getActiveProfile();
        Long deadline = active != null && active.id == profile.id ? PrivacyProfilesController.getActiveDeadline() : null;
        boolean untilMode = deadline != null && PrivacyProfilesController.isActiveUntilMode();
        long prefillMs;
        if (deadline != null && !untilMode) {
            // Time remaining, rounded to the nearest minute -- truncating would quietly shave up
            // to 59 seconds off a timer the user may only be re-confirming.
            prefillMs = Math.max(0, Math.round((deadline - now) / 60000.0) * 60000L);
        } else {
            prefillMs = PrivacyProfilesController.getLastCustomDurationMillis(profile.id);
            if (prefillMs < 0) prefillMs = 3600000L;
        }
        int initialHours = (int) Math.min(MAX_HOURS, prefillMs / 3600000L);
        hours.setValue(initialHours);
        minutes.setValue(initialHours == MAX_HOURS ? 0 : (int) ((prefillMs % 3600000L) / 60000L));
        // Prefill must never consume the one-shot hint: it isn't user-initiated.
        applyHourCap(hours, minutes, false, sheet, fragment);

        long untilStart = untilMode ? deadline : now + 3600000L;
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(untilStart);
        Calendar midnight = Calendar.getInstance();
        midnight.setTimeInMillis(now);
        midnight.set(Calendar.HOUR_OF_DAY, 0);
        midnight.set(Calendar.MINUTE, 0);
        midnight.set(Calendar.SECOND, 0);
        midnight.set(Calendar.MILLISECOND, 0);
        int dayOffset = (int) Math.max(0, Math.min(MAX_DAYS, (cal.getTimeInMillis() - midnight.getTimeInMillis()) / 86400000L));
        dayPicker.setValue(dayOffset);
        untilHour.setValue(cal.get(Calendar.HOUR_OF_DAY));
        untilMinute.setValue(cal.get(Calendar.MINUTE));
        clampUntil(now, dayPicker, untilHour, untilMinute);

        final Runnable showTab = () -> {
            boolean untilTab = currentTab[0] == 1;
            forTab.setVisibility(untilTab ? View.INVISIBLE : View.VISIBLE);
            untilWheels.setVisibility(untilTab ? View.VISIBLE : View.INVISIBLE);
            refresh.run();
        };

        tabs.setDelegate(new ScrollSlidingTextTabStrip.ScrollSlidingTabStripDelegate() {
            @Override
            public void onPageSelected(int page, boolean forward) {
                currentTab[0] = page;
                showTab.run();
            }

            @Override
            public void onPageScrolled(float progress) {
            }
        });
        tabs.addTextTab(0, getString(R.string.PrivacyProfileTabFor));
        tabs.addTextTab(1, getString(R.string.PrivacyProfileTabUntil));
        tabs.finishAddingTabs();
        currentTab[0] = untilMode ? 1 : 0;
        tabs.setInitialTabId(currentTab[0]);
        // setInitialTabId never calls the delegate, so the first content/button state is synced here.
        showTab.run();

        buttonTextView.setOnClickListener(v -> {
            boolean ok;
            if (currentTab[0] == 1) {
                // The minimum was enforced against the `now` captured when the sheet opened. Sitting
                // on it past the chosen minute would otherwise leave a fully-enabled button that
                // does nothing, so re-clamp against the real clock and use the snapped-forward value.
                clampUntil(System.currentTimeMillis(), dayPicker, untilHour, untilMinute);
                long deadlineMillis = untilDeadline(now, dayPicker, untilHour, untilMinute);
                if (deadlineMillis <= System.currentTimeMillis()) {
                    refresh.run();
                    return;
                }
                ok = PrivacyProfilesController.activate(profile.id, PrivacyProfilesController.ActivationMode.UNTIL, deadlineMillis);
            } else {
                long durationMs = hours.getValue() * 3600000L + minutes.getValue() * 60000L;
                if (durationMs <= 0) return;
                // A duration landing exactly on a stock preset counts as a preset however it was
                // reached, so dialling to it by hand can't overwrite the remembered custom value.
                boolean preset = false;
                for (long p : PRESET_MS) {
                    if (p == durationMs) {
                        preset = true;
                        break;
                    }
                }
                ok = PrivacyProfilesController.activate(profile.id,
                        preset ? PrivacyProfilesController.ActivationMode.FOR : PrivacyProfilesController.ActivationMode.FOR_CUSTOM,
                        durationMs);
            }
            if (!ok) {
                // The profile was deleted from another surface while this sheet was open. Say so
                // and stay put rather than closing on a change that didn't happen.
                if (sheet[0] != null) {
                    BulletinFactory.of(sheet[0].container, fragment.getResourceProvider())
                            .createErrorBulletin(getString(R.string.PrivacyProfileNotFound)).show();
                }
                return;
            }
            if (onActivated != null) onActivated.run();
            builder.getDismissRunnable().run();
        });

        builder.setCustomView(container);
        sheet[0] = builder.show();
        sheet[0].setBackgroundColor(colors.backgroundColor);
        sheet[0].fixNavigationBar(colors.backgroundColor);
    }

    /**
     * Clamps minutes to 0 at the 168-hour ceiling. {@code userInitiated} gates the one-time hint:
     * the flag behind it is consumed the moment it's read, so a prefill that happens to land on
     * the cap must not claim the single showing with nothing on screen.
     */
    private static void applyHourCap(NumberPicker hours, NumberPicker minutes, boolean userInitiated, BottomSheet[] sheet, BaseFragment fragment) {
        if (hours.getValue() == MAX_HOURS) {
            minutes.setMaxValue(0);
            minutes.setValue(0);
            // sheet[0] is tested before the hint is claimed: shouldShowSevenDayCapHint() consumes
            // the one-shot as it reads it, so it must not be called unless it can be shown.
            if (userInitiated && sheet[0] != null && PrivacyProfilesController.shouldShowSevenDayCapHint()) {
                // The sheet's own container: a fragment-scoped bulletin lands in the Activity
                // window, underneath this sheet's Dialog window, where nobody would see it.
                BulletinFactory.of(sheet[0].container, fragment.getResourceProvider())
                        .createSimpleBulletin(R.raw.info, getString(R.string.PrivacyProfileSevenDayCap)).show();
            }
        } else {
            minutes.setMaxValue(59);
        }
    }

    /** Keeps the Until wheels out of the past and under the same 7-day ceiling the For tab has. */
    private static void clampUntil(long now, NumberPicker dayPicker, NumberPicker hourPicker, NumberPicker minutePicker) {
        AlertsCreator.checkScheduleDate(null, null, MAX_UNTIL_SECONDS, 0, dayPicker, hourPicker, minutePicker);
        // checkScheduleDate stops at 23:59 on the last day, which can be up to a day past 168h.
        // Tighten the final day to the current wall clock so both tabs really stop at 7 days.
        if (dayPicker.getValue() == MAX_DAYS) {
            Calendar cal = Calendar.getInstance();
            cal.setTimeInMillis(now);
            int nowHour = cal.get(Calendar.HOUR_OF_DAY);
            int nowMinute = cal.get(Calendar.MINUTE);
            hourPicker.setMaxValue(nowHour);
            minutePicker.setMaxValue(hourPicker.getValue() == nowHour ? nowMinute : 59);
        } else {
            hourPicker.setMaxValue(23);
            minutePicker.setMaxValue(59);
        }
    }

    private static long untilDeadline(long now, NumberPicker dayPicker, NumberPicker hourPicker, NumberPicker minutePicker) {
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(now + dayPicker.getValue() * 86400000L);
        calendar.set(Calendar.HOUR_OF_DAY, hourPicker.getValue());
        calendar.set(Calendar.MINUTE, minutePicker.getValue());
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        return calendar.getTimeInMillis();
    }

    /** "2 hours 30 minutes", or just the non-zero part when the other is zero. */
    private static String formatDuration(int hours, int minutes) {
        if (hours > 0 && minutes > 0) {
            return LocaleController.formatPluralString("Hours", hours) + " " + LocaleController.formatPluralString("Minutes", minutes);
        }
        if (hours > 0) {
            return LocaleController.formatPluralString("Hours", hours);
        }
        return LocaleController.formatPluralString("Minutes", minutes);
    }
}
