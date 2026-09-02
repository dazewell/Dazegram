package com.radolyn.ayugram.eventschedule;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.text.Editable;
import android.text.InputType;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.inputmethod.EditorInfo;
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
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.SeekBarView;

import java.util.regex.Pattern;

import tw.nekomimi.nekogram.ui.BottomBuilder;
import tw.nekomimi.nekogram.utils.AlertUtil;
import tw.nekomimi.nekogram.utils.AndroidUtil;
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
    private static int[] editLocalIds;
    private static int editOriginalScheduleDate;
    private static Runnable editOnChanged;

    private EventScheduleHelper() {}

    public static void armEdit(int account, long dialogId, int[] messageIds, int[] localIds, int originalScheduleDate, Runnable onChanged) {
        editPending = true;
        editAccount = account;
        editDialogId = dialogId;
        editMessageIds = messageIds;
        editLocalIds = localIds;
        editOriginalScheduleDate = originalScheduleDate;
        editOnChanged = onChanged;
    }

    public static TriggerRow addTriggerRow(Context context, LinearLayout container, int account, long dialogId,
                                           long selfUserId, boolean isReschedule, boolean hasForcedTitle,
                                           int textColor, int backgroundColor) {
        boolean editHere = editPending && editAccount == account && editDialogId == dialogId;
        int[] editIds = editHere ? editMessageIds : null;
        int[] editLocals = editHere ? editLocalIds : null;
        int editOriginalDate = editHere ? editOriginalScheduleDate : 0;
        Runnable onChanged = editHere ? editOnChanged : null;
        editPending = false;
        editMessageIds = null;
        editLocalIds = null;
        editOnChanged = null;

        if (isReschedule || hasForcedTitle || dialogId == 0 || dialogId == -1
                || dialogId == selfUserId || DialogObject.isEncryptedDialog(dialogId)) {
            return null;
        }

        // editIds is an immutable primitive snapshot captured when the sheet opened; a send ack mutates
        // the MessageObject but can never update this array, so a non-positive id here means at least one
        // target is still in flight. Decide once, now, and stay decided for the sheet's life: withhold the
        // trigger entirely (albums are all-or-nothing -- one unsent member refuses the whole group) and
        // show a disabled note instead. The user reopens the sheet after the ack to get positive ids and
        // the normal control. The fallback schedule-time edit still commits normally; only the trigger is
        // withheld, because returning no TriggerRow means the confirmation runs no event-schedule mutation.
        if (editIds != null && hasNonPositiveId(editIds)) {
            addInFlightNote(context, container, textColor, backgroundColor);
            return null;
        }

        Row row = new Row(account, dialogId, editIds, editLocals, editOriginalDate, onChanged);

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

    private static boolean hasNonPositiveId(int[] ids) {
        for (int id : ids) {
            if (id <= 0) return true;
        }
        return false;
    }

    // A non-clickable, dimmed twin of the trigger chip. It carries no Row and no click handler, so it
    // can't be armed and can't turn into the live control while the sheet stays open -- reopening the
    // sheet after the send acks is what surfaces the real chip.
    private static void addInFlightNote(Context context, LinearLayout container, int textColor, int backgroundColor) {
        TextView chip = new TextView(context);
        chip.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        chip.setTextColor(Theme.multAlpha(textColor, 0.5f));
        chip.setPadding(dp(12), dp(5), dp(12), dp(5));
        chip.setMinHeight(dp(28));
        chip.setMaxLines(3);
        chip.setEllipsize(android.text.TextUtils.TruncateAt.END);
        final int chipBg = Theme.blendOver(backgroundColor, Theme.multAlpha(textColor, 0.075f));
        chip.setBackground(Theme.createRoundRectDrawable(dp(14), chipBg));
        chip.setGravity(Gravity.CENTER);
        chip.setText(getString(R.string.EventScheduleTriggerInFlight));

        FrameLayout chipContainer = new FrameLayout(context);
        chipContainer.addView(chip, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 32, 4, 32, 5));
        container.addView(chipContainer, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
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
        final int[] editLocalIds;
        final int editOriginalScheduleDate;
        final Runnable onChanged;

        boolean enabled;
        int types;
        String pattern;
        boolean regex;
        int delay;
        String pendingEntryKey;

        TextView chip;

        Row(int account, long dialogId, int[] editIds, int[] editLocalIds, int editOriginalScheduleDate, Runnable onChanged) {
            this.account = account;
            this.dialogId = dialogId;
            this.editIds = editIds;
            this.editLocalIds = editLocalIds;
            this.editOriginalScheduleDate = editOriginalScheduleDate;
            this.onChanged = onChanged;
            // Seed the controls from any trigger this message already has. This is a UI seed only -- the
            // arming decision at commit re-resolves ownership from scratch, so a snapshot taken here can't
            // route a stale entry.
            EventScheduleEntry existing = editIds != null && editIds.length > 0
                    ? EventScheduleStore.findByMessage(account, dialogId, editIds[0]) : null;
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
            builder.addTitle(getString(R.string.EventScheduleTitle), getString(R.string.EventScheduleArmed));

            builder.addTitle(getString(R.string.EventScheduleSectionType), false, null);
            TextCheckCell voiceCell = builder.addCheckItem(getString(R.string.AttachAudio), (types & EventScheduleEntry.TYPE_VOICE) != 0, false, null);
            TextCheckCell roundCell = builder.addCheckItem(getString(R.string.EventScheduleTypeRound), (types & EventScheduleEntry.TYPE_ROUND) != 0, false, null);
            TextCheckCell videoCell = builder.addCheckItem(getString(R.string.AttachVideo), (types & EventScheduleEntry.TYPE_VIDEO) != 0, false, null);
            TextCheckCell photoCell = builder.addCheckItem(getString(R.string.AttachPhoto), (types & EventScheduleEntry.TYPE_PHOTO) != 0, false, null);
            TextCheckCell textCell = builder.addCheckItem(getString(R.string.EventScheduleTypeText), (types & EventScheduleEntry.TYPE_TEXT) != 0, false, null);

            builder.addTitle(getString(R.string.EventScheduleSectionPattern), false, getString(R.string.EventScheduleMatchInfo));

            FrameLayout patternBox = new FrameLayout(context);
            patternBox.setBackground(Theme.createRoundRectDrawable(dp(10), Theme.getColor(Theme.key_graySection)));
            EditTextBoldCursor patternField = new EditTextBoldCursor(context);
            patternField.setBackground(null);
            patternField.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
            patternField.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
            patternField.setHintTextColor(Theme.getColor(Theme.key_dialogSearchHint));
            patternField.setHint(getString(R.string.EventSchedulePatternHint));
            patternField.setCursorSize(dp(18));
            patternField.setCursorColor(Theme.getColor(Theme.key_chat_TextSelectionCursor));
            patternField.setHandlesColor(Theme.getColor(Theme.key_chat_TextSelectionCursor));
            patternField.setSingleLine(true);
            patternField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
            patternField.setImeOptions(EditorInfo.IME_ACTION_DONE);
            patternField.setPadding(dp(12), dp(10), dp(12), dp(10));
            patternField.setMinimumHeight(dp(48));
            patternField.setGravity(Gravity.CENTER_VERTICAL | (org.telegram.messenger.LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT));
            patternField.setText(pattern);
            patternBox.addView(patternField, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
            builder.addCustomView(patternBox);
            patternBox.setLayoutParams(LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT, 21, 4, 21, 8));

            TextCheckCell regexCell = builder.addCheckItem(getString(R.string.EventScheduleUseRegex), regex, false, getString(R.string.EventScheduleRegexInfo), null);

            // NagramX: build the field, seed its text, THEN build regexCell, THEN attach the watcher --
            // in that order. Attaching the watcher before regexCell exists would have setText() above
            // fire it against a still-null cell and crash; syncRegexEnabled is the one place that
            // decides the dependency state, called here for the initial paint and again on every edit
            // so the two can never drift apart.
            patternField.addTextChangedListener(new TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

                @Override
                public void onTextChanged(CharSequence s, int start, int before, int count) {}

                @Override
                public void afterTextChanged(Editable s) {
                    syncRegexEnabled(patternField, regexCell);
                }
            });
            syncRegexEnabled(patternField, regexCell);

            final int[] delayValues = {0, 5, 10, 30, 60, 300};
            int startIndex = 0;
            for (int i = 0; i < delayValues.length; i++) {
                if (delayValues[i] <= delay) startIndex = i;
            }
            final int[] delayIndex = {startIndex};

            LinearLayout delayLayout = new LinearLayout(context);
            delayLayout.setOrientation(LinearLayout.VERTICAL);

            LinearLayout delayHeader = new LinearLayout(context);
            delayHeader.setOrientation(LinearLayout.HORIZONTAL);
            delayHeader.setGravity(Gravity.CENTER_VERTICAL);
            delayLayout.addView(delayHeader, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 21, 8, 21, 0));

            TextView delayTitle = new TextView(context);
            delayTitle.setText(getString(R.string.EventScheduleDelayTitle));
            delayTitle.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
            delayTitle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
            delayTitle.setGravity(Gravity.CENTER_VERTICAL);
            delayTitle.setSingleLine(true);
            delayTitle.setEllipsize(TextUtils.TruncateAt.END);
            delayHeader.addView(delayTitle, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));

            final TextView delayValue = new TextView(context);
            delayValue.setText(formatDelayLabel(delayValues[startIndex]));
            delayValue.setTextColor(Theme.getColor(Theme.key_dialogTextGray3));
            delayValue.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            delayValue.setGravity(Gravity.CENTER_VERTICAL | (org.telegram.messenger.LocaleController.isRTL ? Gravity.LEFT : Gravity.RIGHT));
            delayHeader.addView(delayValue, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));

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
            // NagramX: 38dp is the app-wide height for this widget (BrightnessControlCell:76,
            // ThemePreviewActivity:2086, BlurSettingsBottomSheet:72,100,129) -- this sheet deliberately
            // diverges to 48dp to clear the touch-target minimum; don't "fix" it back to 38.
            delayLayout.addView(delaySeekBar, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48, 13, 0, 13, 0));
            final float initialDelayProgress = startIndex / (float) (delayValues.length - 1);
            org.telegram.messenger.AndroidUtilities.doOnLayout(delaySeekBar, () -> delaySeekBar.setProgress(initialDelayProgress));
            builder.addCustomView(delayLayout);

            if (enabled) {
                builder.addItem(getString(R.string.EventScheduleClear), R.drawable.msg_delete, true, it -> {
                    enabled = false;
                    updateChip();
                    return kotlin.Unit.INSTANCE;
                });
            }
            TextView doneButton = builder.addButton(getString(R.string.Done), true, false, it -> {
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
                        AndroidUtil.showInputError(patternField);
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
            // NagramX: registered after addButton so doneButton is assigned; noAutoDismiss above
            // means performClick() re-runs the real validation/commit lambda rather than a copy of it.
            patternField.setOnEditorActionListener((v, actionId, event) -> {
                if (actionId == EditorInfo.IME_ACTION_DONE) {
                    doneButton.performClick();
                    return true;
                }
                return false;
            });
            builder.addCancelButton();
            builder.show();
        }

        // NagramX: TextCheckCell.setEnabled(boolean) is the only override that reaches the Switch,
        // but addCheckItem wires the switch's own click straight back to performClick() on the row,
        // which ignores the enabled flag -- so the single-arg call alone leaves a dimmed row that
        // still toggles. setEnabled(boolean, animators) drives the alpha fade but calls super, not the
        // single-arg override, so both calls are required together.
        private static void syncRegexEnabled(EditText patternField, TextCheckCell regexCell) {
            // NagramX: trim to match the Done handler's newPattern, or whitespace-only text would
            // enable the toggle here but save against the empty pattern that trim() actually commits.
            boolean hasPattern = !TextUtils.isEmpty(patternField.getText().toString().trim());
            // Construction seeds (enabled, EventScheduleRegexInfo); skip the redundant re-layout
            // this would otherwise trigger on every keystroke once state hasn't actually changed.
            if (hasPattern == regexCell.isEnabled()) return;
            regexCell.setTextAndValueAndCheck(
                    getString(R.string.EventScheduleUseRegex),
                    getString(hasPattern ? R.string.EventScheduleRegexInfo : R.string.EventScheduleRegexNeedsPattern),
                    regexCell.isChecked(), true, true);
            regexCell.setEnabled(hasPattern);
            regexCell.setEnabled(hasPattern, null);
        }

        @Override
        public void commit(int scheduleDate, int repeatPeriod) {
            // Premium repeat and early-trigger don't compose; a repeat is always a plain schedule.
            boolean armed = enabled && repeatPeriod == 0;
            if (editIds != null && editIds.length > 0) {
                // Editing an existing scheduled message. Re-resolve ownership at commit across both id
                // spaces plus the schedule-date fallback rather than trusting a snapshot from when the
                // sheet opened, so a trigger armed or turned off in the meantime is handled correctly and
                // the same message can't end up with two triggers.
                if (armed) {
                    boolean claimed = EventScheduleController.commitEditArm(account, dialogId, editIds, editLocalIds,
                            editOriginalScheduleDate, types, pattern, regex, delay, scheduleDate);
                    if (!claimed) {
                        // Another trigger already owns this message (only reachable against data a prior
                        // defect persisted). Leave every trigger untouched; the schedule-date edit still
                        // proceeds, so tell the user the trigger specifically was not changed.
                        AlertUtil.showToast(getString(R.string.EventScheduleTriggerConflict));
                        return;
                    }
                } else {
                    EventScheduleController.commitEditOff(account, dialogId, editIds, editLocalIds, editOriginalScheduleDate);
                }
                refresh();
                return;
            }
            if (!armed) {
                // Trigger explicitly off: drop only still-unclaimed pending arms in this dialog.
                EventScheduleController.killUnclaimedForDialog(account, dialogId);
                pendingEntryKey = null;
                return;
            }
            EventScheduleEntry entry = new EventScheduleEntry();
            entry.types = types;
            entry.pattern = pattern == null ? "" : pattern;
            entry.regex = regex;
            entry.delaySeconds = delay;
            entry.createdAt = System.currentTimeMillis();
            if (!TextUtils.isEmpty(pendingEntryKey)) {
                EventScheduleController.killPending(account, pendingEntryKey);
            }
            pendingEntryKey = EventScheduleController.armPending(account, dialogId, entry, scheduleDate);
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
