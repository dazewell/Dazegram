package com.radolyn.ayugram.eventschedule;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.DialogObject;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Components.ColoredImageSpan;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.SlideChooseView;

import java.util.regex.Pattern;

import tw.nekomimi.nekogram.ui.BottomBuilder;
import tw.nekomimi.nekogram.utils.AlertUtil;
import xyz.nextalone.nagram.NaConfig;

/**
 * UI glue for event-triggered scheduled messages: the "Send on event" chip injected into
 * the schedule date-picker sheet, the config sheet behind it, the bolt marker in the
 * scheduled view, and the long-press menu summary. All base-file hooks are one line each.
 */
public final class EventScheduleHelper {

    public interface TriggerRow {
        void commit(int scheduleDate, int repeatPeriod);
    }

    // One-shot context set by the ChatActivity "Edit schedule time" hook, consumed by the next
    // addTriggerRow. The "edit a Saved Messages reminder" path sets nothing useful, so the row
    // validates the dialog id and clears the flag unconditionally to avoid leaking into a later sheet.
    private static boolean editPending;
    private static int editAccount;
    private static long editDialogId;
    private static int[] editMessageIds;

    private EventScheduleHelper() {}

    public static void armEdit(int account, long dialogId, int[] messageIds) {
        editPending = true;
        editAccount = account;
        editDialogId = dialogId;
        editMessageIds = messageIds;
    }

    public static TriggerRow addTriggerRow(Context context, LinearLayout container, int account, long dialogId,
                                           long selfUserId, boolean isReschedule, boolean hasForcedTitle,
                                           int textColor, int backgroundColor) {
        boolean editHere = editPending && editAccount == account && editDialogId == dialogId;
        int[] editIds = editHere ? editMessageIds : null;
        editPending = false;
        editMessageIds = null;

        if (isReschedule || hasForcedTitle || dialogId == 0 || dialogId == -1
                || dialogId == selfUserId || DialogObject.isEncryptedDialog(dialogId)) {
            return null;
        }

        Row row = new Row(account, dialogId, editIds);

        TextView chip = new TextView(context);
        chip.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        chip.setTextColor(textColor);
        chip.setPadding(dp(12), 0, dp(12), 0);
        final int chipBg = Theme.blendOver(backgroundColor, Theme.multAlpha(textColor, 0.075f));
        final int chipSelector = Theme.multAlpha(textColor, 0.1f);
        chip.setBackground(Theme.createSimpleSelectorRoundRectDrawable(dp(14), chipBg, Theme.blendOver(chipBg, chipSelector)));
        chip.setGravity(Gravity.CENTER);
        chip.setOnClickListener(v -> row.openSheet(context));
        row.chip = chip;
        row.updateChip();

        FrameLayout chipContainer = new FrameLayout(context);
        chipContainer.addView(chip, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, 28, Gravity.CENTER_HORIZONTAL, 32, 4, 32, 5));
        container.addView(chipContainer, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        return row;
    }

    /** Prepends a small bolt to the time string of a scheduled message that carries a live trigger. */
    public static CharSequence decorateTimeString(int account, MessageObject msg, CharSequence timeString) {
        EventScheduleController.ensureWarm(account);
        if (msg == null || !msg.scheduled || !EventScheduleStore.hasAny(account)) return timeString;
        if (EventScheduleStore.findByMessage(account, msg.getDialogId(), msg.getId()) == null) return timeString;
        SpannableStringBuilder sb = new SpannableStringBuilder("\u200b ");
        ColoredImageSpan bolt = new ColoredImageSpan(R.drawable.msg_instant_solar);
        bolt.setSize(dp(11));
        bolt.spaceScaleX = 0.9f;
        sb.setSpan(bolt, 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.append(timeString);
        return sb;
    }

    /** Long-press menu label for an armed scheduled message, or null when it has no trigger. */
    public static CharSequence getMenuSummary(int account, long dialogId, MessageObject selected) {
        if (selected == null || !EventScheduleStore.hasAny(account)) return null;
        EventScheduleEntry entry = EventScheduleStore.findByMessage(account, dialogId, selected.getId());
        if (entry == null) return null;
        CharSequence detail = entry.summary(true);
        return TextUtils.isEmpty(detail) ? getString(R.string.EventScheduleArmed) : detail;
    }

    private static final class Row implements TriggerRow {
        final int account;
        final long dialogId;
        final int[] editIds;
        final EventScheduleEntry editEntry;

        boolean enabled;
        int types;
        String pattern;
        boolean regex;
        int delay;

        TextView chip;

        Row(int account, long dialogId, int[] editIds) {
            this.account = account;
            this.dialogId = dialogId;
            this.editIds = editIds;
            EventScheduleEntry existing = editIds != null && editIds.length > 0
                    ? EventScheduleStore.findByMessage(account, dialogId, editIds[0]) : null;
            this.editEntry = existing;
            if (existing != null) {
                enabled = true;
                types = existing.types;
                pattern = existing.pattern;
                regex = existing.regex;
                delay = existing.delaySeconds;
            } else {
                NaConfig cfg = NaConfig.INSTANCE;
                types = cfg.getEventScheduleLastTypes().Int();
                pattern = cfg.getEventScheduleLastPattern().String();
                regex = cfg.getEventScheduleLastPatternRegex().Bool();
                delay = cfg.getEventScheduleLastDelay().Int();
            }
        }

        void updateChip() {
            if (chip == null) return;
            CharSequence value = enabled ? summary() : getString(R.string.EventScheduleTriggerOff);
            chip.setText(getString(R.string.EventScheduleTrigger) + ": " + value);
        }

        private CharSequence summary() {
            EventScheduleEntry e = new EventScheduleEntry();
            e.types = types;
            e.pattern = pattern == null ? "" : pattern;
            e.delaySeconds = delay;
            CharSequence s = e.summary(true);
            return TextUtils.isEmpty(s) ? getString(R.string.EventScheduleTriggerOff) : s;
        }

        void openSheet(Context context) {
            BottomBuilder builder = new BottomBuilder(context);
            builder.addTitle(getString(R.string.EventScheduleTitle),
                    getString(R.string.EventScheduleMatchInfo) + " " + getString(R.string.EventScheduleArmed));

            TextCheckCell voiceCell = builder.addCheckItem(getString(R.string.AttachAudio), (types & EventScheduleEntry.TYPE_VOICE) != 0, false, null);
            TextCheckCell roundCell = builder.addCheckItem(getString(R.string.AttachRound), (types & EventScheduleEntry.TYPE_ROUND) != 0, false, null);
            TextCheckCell videoCell = builder.addCheckItem(getString(R.string.AttachVideo), (types & EventScheduleEntry.TYPE_VIDEO) != 0, false, null);
            TextCheckCell photoCell = builder.addCheckItem(getString(R.string.AttachPhoto), (types & EventScheduleEntry.TYPE_PHOTO) != 0, false, null);
            TextCheckCell textCell = builder.addCheckItem(getString(R.string.EventScheduleTypeText), (types & EventScheduleEntry.TYPE_TEXT) != 0, false, null);

            EditText patternField = builder.addEditText(getString(R.string.EventSchedulePatternHint));
            patternField.setText(pattern);
            TextCheckCell regexCell = builder.addCheckItem(getString(R.string.EventScheduleUseRegex), regex, false, getString(R.string.EventScheduleRegexInfo), null);

            builder.addTitle(getString(R.string.EventScheduleDelayTitle));
            final int[] delayValues = {0, 5, 10, 30, 60, 300};
            final String[] delayLabels = {getString(R.string.EventScheduleNoDelay), "5s", "10s", "30s", "1m", "5m"};
            int startIndex = 0;
            for (int i = 0; i < delayValues.length; i++) {
                if (delayValues[i] <= delay) startIndex = i;
            }
            final int[] delayIndex = {startIndex};
            SlideChooseView delaySlider = new SlideChooseView(context);
            delaySlider.setOptions(startIndex, delayLabels);
            delaySlider.setCallback(index -> delayIndex[0] = index);
            builder.addCustomView(delaySlider);

            builder.addItem(getString(R.string.EventScheduleClear), R.drawable.msg_delete, true, it -> {
                enabled = false;
                updateChip();
                return kotlin.Unit.INSTANCE;
            });
            builder.addButton(getString(R.string.Done), true, false, it -> {
                int newTypes = 0;
                if (voiceCell.isChecked()) newTypes |= EventScheduleEntry.TYPE_VOICE;
                if (roundCell.isChecked()) newTypes |= EventScheduleEntry.TYPE_ROUND;
                if (videoCell.isChecked()) newTypes |= EventScheduleEntry.TYPE_VIDEO;
                if (photoCell.isChecked()) newTypes |= EventScheduleEntry.TYPE_PHOTO;
                if (textCell.isChecked()) newTypes |= EventScheduleEntry.TYPE_TEXT;
                String newPattern = patternField.getText().toString().trim();
                boolean newRegex = regexCell.isChecked();
                if (newTypes == 0 && TextUtils.isEmpty(newPattern)) {
                    AlertUtil.showToast(getString(R.string.EventScheduleNeedCondition));
                    return kotlin.Unit.INSTANCE;
                }
                if (newRegex && !TextUtils.isEmpty(newPattern)) {
                    try {
                        Pattern.compile(newPattern);
                    } catch (Throwable t) {
                        AlertUtil.showToast(getString(R.string.EventScheduleInvalidRegex));
                        return kotlin.Unit.INSTANCE;
                    }
                }
                int newDelay = delayValues[delayIndex[0]];
                enabled = true;
                types = newTypes;
                pattern = newPattern;
                regex = newRegex;
                delay = newDelay;
                NaConfig cfg = NaConfig.INSTANCE;
                cfg.getEventScheduleLastTypes().setConfigInt(types);
                cfg.getEventScheduleLastPattern().setConfigString(pattern);
                cfg.getEventScheduleLastPatternRegex().setConfigBool(regex);
                cfg.getEventScheduleLastDelay().setConfigInt(delay);
                updateChip();
                builder.dismiss();
                return kotlin.Unit.INSTANCE;
            });
            builder.addCancelButton();
            builder.show();
        }

        @Override
        public void commit(int scheduleDate, int repeatPeriod) {
            // Premium repeat and early-trigger don't compose; a repeat is always a plain schedule.
            boolean armed = enabled && repeatPeriod == 0;
            if (editEntry != null) {
                if (armed) {
                    EventScheduleController.updateForEdit(account, editEntry, types, pattern, regex, delay, scheduleDate);
                } else {
                    EventScheduleStore.remove(account, editEntry);
                }
                return;
            }
            if (!armed) {
                // A stale pending from an earlier "on" run in this dialog must not fire.
                EventScheduleController.killPending(account, dialogId);
                return;
            }
            if (editIds != null && editIds.length > 0) {
                // Editing a scheduled message that had no trigger: attach directly to its server ids.
                EventScheduleEntry entry = new EventScheduleEntry();
                entry.dialogId = dialogId;
                for (int id : editIds) entry.serverIds.add(id);
                entry.types = types;
                entry.pattern = pattern == null ? "" : pattern;
                entry.regex = regex;
                entry.delaySeconds = delay;
                entry.fallbackDate = scheduleDate;
                entry.createdAt = System.currentTimeMillis();
                EventScheduleController.armExisting(account, entry);
                return;
            }
            EventScheduleEntry entry = new EventScheduleEntry();
            entry.types = types;
            entry.pattern = pattern == null ? "" : pattern;
            entry.regex = regex;
            entry.delaySeconds = delay;
            entry.createdAt = System.currentTimeMillis();
            EventScheduleController.armPending(account, dialogId, entry, scheduleDate);
        }
    }
}
