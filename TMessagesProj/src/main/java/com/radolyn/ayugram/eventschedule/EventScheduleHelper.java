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
import org.telegram.ui.Components.SeekBarView;

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

    private static final int BOLT_SIZE_DP = 11;

    // One-shot context set by the ChatActivity "Edit schedule time" hook, consumed by the next
    // addTriggerRow. The "edit a Saved Messages reminder" path sets nothing useful, so the row
    // validates the dialog id and clears the flag unconditionally to avoid leaking into a later sheet.
    private static boolean editPending;
    private static int editAccount;
    private static long editDialogId;
    private static int[] editMessageIds;
    private static Runnable editOnChanged;

    private EventScheduleHelper() {}

    public static void armEdit(int account, long dialogId, int[] messageIds, Runnable onChanged) {
        editPending = true;
        editAccount = account;
        editDialogId = dialogId;
        editMessageIds = messageIds;
        editOnChanged = onChanged;
    }

    public static TriggerRow addTriggerRow(Context context, LinearLayout container, int account, long dialogId,
                                           long selfUserId, boolean isReschedule, boolean hasForcedTitle,
                                           int textColor, int backgroundColor) {
        boolean editHere = editPending && editAccount == account && editDialogId == dialogId;
        int[] editIds = editHere ? editMessageIds : null;
        Runnable onChanged = editHere ? editOnChanged : null;
        editPending = false;
        editMessageIds = null;
        editOnChanged = null;

        if (isReschedule || hasForcedTitle || dialogId == 0 || dialogId == -1
                || dialogId == selfUserId || DialogObject.isEncryptedDialog(dialogId)) {
            return null;
        }

        Row row = new Row(account, dialogId, editIds, onChanged);

        TextView chip = new TextView(context);
        chip.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        chip.setTextColor(textColor);
        chip.setPadding(dp(12), dp(5), dp(12), dp(5));
        // Summaries can list several types plus a delay, so let the pill wrap and grow instead of
        // clipping to the single-line height the "Repeat" chip uses; keep short pills that same size.
        chip.setMinHeight(dp(28));
        chip.setMaxLines(3);
        chip.setEllipsize(android.text.TextUtils.TruncateAt.END);
        final int chipBg = Theme.blendOver(backgroundColor, Theme.multAlpha(textColor, 0.075f));
        final int chipSelector = Theme.multAlpha(textColor, 0.1f);
        chip.setBackground(Theme.createSimpleSelectorRoundRectDrawable(dp(14), chipBg, Theme.blendOver(chipBg, chipSelector)));
        chip.setGravity(Gravity.CENTER);
        chip.setOnClickListener(v -> row.openSheet(context));
        row.chip = chip;
        row.updateChip();

        FrameLayout chipContainer = new FrameLayout(context);
        chipContainer.addView(chip, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 32, 4, 32, 5));
        container.addView(chipContainer, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        return row;
    }

    /** Prepends a small bolt to the time string of a scheduled message that carries a live trigger. */
    public static CharSequence decorateTimeString(int account, MessageObject msg, CharSequence timeString) {
        EventScheduleController.ensureWarm(account);
        if (!isArmed(account, msg)) return timeString;
        SpannableStringBuilder sb = new SpannableStringBuilder("\u200b ");
        ColoredImageSpan bolt = new ColoredImageSpan(R.drawable.msg_instant_solar);
        bolt.setSize(dp(BOLT_SIZE_DP));
        bolt.spaceScaleX = 0.9f;
        sb.setSpan(bolt, 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.append(timeString);
        return sb;
    }

    /**
     * Width the bolt adds to the time layout. chat_timePaint.measureText ignores the span (it rides
     * a zero-width char), so the cell must reserve this or the status ticks collide with it -- the
     * same reservation the peer-time glyph needs.
     */
    public static int timeGlyphReserve(int account, MessageObject msg) {
        if (!isArmed(account, msg)) return 0;
        return dp(BOLT_SIZE_DP);
    }

    private static boolean isArmed(int account, MessageObject msg) {
        if (msg == null || !msg.scheduled || !EventScheduleStore.hasAny(account)) return false;
        return EventScheduleStore.findByMessage(account, msg.getDialogId(), msg.getId()) != null;
    }

    private static final class Row implements TriggerRow {
        final int account;
        final long dialogId;
        final int[] editIds;
        final EventScheduleEntry editEntry;
        final Runnable onChanged;

        boolean enabled;
        int types;
        String pattern;
        boolean regex;
        int delay;

        TextView chip;

        Row(int account, long dialogId, int[] editIds, Runnable onChanged) {
            this.account = account;
            this.dialogId = dialogId;
            this.editIds = editIds;
            this.onChanged = onChanged;
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

            final int[] delayValues = {0, 5, 10, 30, 60, 300};
            int startIndex = 0;
            for (int i = 0; i < delayValues.length; i++) {
                if (delayValues[i] <= delay) startIndex = i;
            }
            final int[] delayIndex = {startIndex};

            final int accentColor = Theme.getColor(Theme.key_player_progress);
            LinearLayout delayLayout = new LinearLayout(context);
            delayLayout.setOrientation(LinearLayout.VERTICAL);

            LinearLayout delayHeader = new LinearLayout(context);
            delayHeader.setOrientation(LinearLayout.HORIZONTAL);
            delayHeader.setGravity(Gravity.CENTER_VERTICAL);
            delayLayout.addView(delayHeader, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 24, 22, 8, 22, 0));

            TextView delayTitle = new TextView(context);
            delayTitle.setText(getString(R.string.EventScheduleDelayTitle));
            delayTitle.setTextColor(accentColor);
            delayTitle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            delayTitle.setTypeface(org.telegram.messenger.AndroidUtilities.bold());
            delayTitle.setGravity(Gravity.CENTER_VERTICAL);
            delayTitle.setSingleLine(true);
            delayTitle.setEllipsize(TextUtils.TruncateAt.END);
            delayHeader.addView(delayTitle, LayoutHelper.createLinear(0, LayoutHelper.MATCH_PARENT, 1f));

            final TextView delayValue = new TextView(context);
            delayValue.setText(formatDelayLabel(delayValues[startIndex]));
            delayValue.setTextColor(accentColor);
            delayValue.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            delayValue.setGravity(Gravity.CENTER_VERTICAL | Gravity.RIGHT);
            delayHeader.addView(delayValue, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.MATCH_PARENT));

            final SeekBarView delaySeekBar = new SeekBarView(context, null);
            delaySeekBar.setReportChanges(true);
            delaySeekBar.setSeparatorsCount(delayValues.length);
            delaySeekBar.setDelegate(new SeekBarView.SeekBarViewDelegate() {
                @Override
                public void onSeekBarDrag(boolean stop, float progress) {
                    int step = Math.round(progress * (delayValues.length - 1));
                    delayIndex[0] = step;
                    delayValue.setText(formatDelayLabel(delayValues[step]));
                    if (stop) {
                        delaySeekBar.setProgress(step / (float) (delayValues.length - 1), true);
                    }
                }

                @Override
                public CharSequence getContentDescription() {
                    return delayTitle.getText() + ", " + delayValue.getText();
                }

                @Override
                public int getStepsCount() {
                    return delayValues.length - 1;
                }
            });
            delayLayout.addView(delaySeekBar, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 38, 13, 0, 13, 0));
            final float initialDelayProgress = startIndex / (float) (delayValues.length - 1);
            org.telegram.messenger.AndroidUtilities.doOnLayout(delaySeekBar, () -> delaySeekBar.setProgress(initialDelayProgress));
            builder.addCustomView(delayLayout);

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
                refresh();
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
                refresh();
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

        // Repaint the scheduled view so the bolt appears/disappears at once (editing a live message
        // is a local-only store change with no server round-trip to rebind the cell).
        private void refresh() {
            if (onChanged != null) onChanged.run();
        }
    }

    private static String formatDelayLabel(int seconds) {
        if (seconds <= 0) {
            return getString(R.string.EventScheduleNoDelay);
        }
        if (seconds < 60 || seconds % 60 != 0) {
            return org.telegram.messenger.LocaleController.formatPluralString("Seconds", seconds);
        }
        return org.telegram.messenger.LocaleController.formatPluralString("Minutes", seconds / 60);
    }
}
