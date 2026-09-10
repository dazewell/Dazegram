package com.radolyn.ayugram.eventschedule;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.text.Editable;
import android.text.InputType;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.DialogObject;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Cells.TextDetailSettingsCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Cells.TextSettingsCell;
import org.telegram.ui.Components.ColoredImageSpan;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.ItemOptions;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.SeekBarView;

import java.util.ArrayList;

import tw.nekomimi.nekogram.NekoConfig;
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

        /** The trigger the user configured on this chip, or null when the chip is left Off. */
        EventScheduleConfig snapshot();
    }

    /**
     * Bulk-reschedule state for the chip; non-null only on the bulk Reschedule sheet. {@code ready}
     * is false when any selected message (album members included) is still in flight -- the chip is
     * then shown disabled and no trigger can be armed. {@code armedCount} is how many selected
     * messages already carry a trigger, surfaced as an overwrite heads-up.
     */
    public static final class BulkTriggerContext {
        public final boolean ready;
        public final int armedCount;

        public BulkTriggerContext(boolean ready, int armedCount) {
            this.ready = ready;
            this.armedCount = armedCount;
        }
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
    private static Runnable editOnChanged;

    private EventScheduleHelper() {}

    public static void armEdit(int account, long dialogId, int[] messageIds, int[] localIds, Runnable onChanged) {
        editPending = true;
        editAccount = account;
        editDialogId = dialogId;
        editMessageIds = messageIds;
        editLocalIds = localIds;
        editOnChanged = onChanged;
    }

    public static TriggerRow addTriggerRow(Context context, LinearLayout container, int account, long dialogId,
                                           long selfUserId, boolean isReschedule, boolean hasForcedTitle,
                                           BulkTriggerContext bulk, int textColor, int backgroundColor) {
        boolean bulkMode = bulk != null;
        // A bulk arm never rides the single-message edit one-shot; still consume it here so it can't
        // leak into a later sheet, but never let it prefill a bulk chip.
        boolean editHere = !bulkMode && editPending && editAccount == account && editDialogId == dialogId;
        int[] editIds = editHere ? editMessageIds : null;
        int[] editLocals = editHere ? editLocalIds : null;
        Runnable onChanged = editHere ? editOnChanged : null;
        editPending = false;
        editMessageIds = null;
        editLocalIds = null;
        editOnChanged = null;

        // NagramX: the bulk Reschedule sheet is the one forcedTitle+reschedule sheet that wants the chip;
        // every other forced-title or reschedule sheet (scheduled infinite-video included) stays blocked.
        if (((isReschedule || hasForcedTitle) && !bulkMode) || dialogId == 0 || dialogId == -1
                || dialogId == selfUserId || DialogObject.isEncryptedDialog(dialogId)) {
            return null;
        }
        if (bulkMode && !bulk.ready) {
            // A selected message is still sending, so arming would attach to a not-yet-server-addressable
            // id: offer nothing and explain why. The reschedule itself still runs.
            addInFlightNote(context, container, textColor, backgroundColor);
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

        Row row = new Row(account, dialogId, editIds, editLocals, onChanged);

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

        // Overwrite heads-up: some selected messages already carry a trigger, so turning this on replaces
        // them. Read-only readout of existing state; the actual confirm rides the reschedule's own dialog.
        if (bulkMode && bulk.armedCount > 0) {
            TextView note = new TextView(context);
            note.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
            note.setTextColor(Theme.multAlpha(textColor, 0.6f));
            note.setGravity(Gravity.CENTER);
            note.setText(org.telegram.messenger.LocaleController.formatPluralString("EventScheduleBulkOverwrite", bulk.armedCount));
            container.addView(note, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 32, 0, 32, 6));
        }

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
        final Runnable onChanged;

        boolean enabled;
        // Whether the user actually operated the trigger controls this time (hit Done to arm, or Clear to
        // turn off). The schedule picker fires commit() even on an edit where the trigger was never
        // touched, so without this an untouched edit would run a mutation off the seeded state alone --
        // and in the pre-existing multi-owner case that mutation is a destructive turn-off.
        boolean userTouchedTrigger;
        int types;
        final ArrayList<String> patterns = new ArrayList<>();
        boolean regex;
        // Always kept within [0, MAX_DELAY_SECONDS] -- clamped at seed time below, and enforced again by
        // EventScheduleStore.persist regardless of what this ends up carrying. Not necessarily a member of
        // delayValues, though (see delayValue's label comment in openSheet()).
        int delay;
        String pendingEntryKey;

        TextView chip;

        private static final class PatternFieldRow {
            final LinearLayout container;
            final FrameLayout fieldBox;
            final EditTextBoldCursor field;
            final ImageView removeButton;
            final TextView messageView;

            // Which action the trailing icon performs right now -- decided fresh at accessibility-sync
            // time from the shared row-count/blank rules, never toggled directly by a click handler.
            boolean clearMode;

            PatternFieldRow(LinearLayout container, FrameLayout fieldBox, EditTextBoldCursor field,
                            ImageView removeButton, TextView messageView) {
                this.container = container;
                this.fieldBox = fieldBox;
                this.field = field;
                this.removeButton = removeButton;
                this.messageView = messageView;
            }
        }

        /** Applies one saved preset onto the sheet's live draft state; assigned after the delay slider exists (I-5). */
        private interface PresetApplier {
            void apply(EventSchedulePresetStore.Preset preset);
        }

        /** Result of {@link #extractAndValidate}: the type bitmask plus the trimmed, deduped pattern list. */
        private static final class ExtractedConfig {
            final int types;
            final ArrayList<String> patterns;

            ExtractedConfig(int types, ArrayList<String> patterns) {
                this.types = types;
                this.patterns = patterns;
            }
        }

        private static final class DisclosureHeaderCell extends TextSettingsCell {
            private CharSequence titleText = "";
            private CharSequence summaryText = "";
            private boolean expanded;
            private boolean initialized;
            private float arrowRotation;
            private ValueAnimator arrowAnimator;

            DisclosureHeaderCell(Context context) {
                super(context, 21);
                setBackground(Theme.getSelectorDrawable(false));
                setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            }

            void bind(CharSequence title, CharSequence summary, boolean expanded) {
                boolean stateChanged = initialized && this.expanded != expanded;
                this.titleText = title;
                this.summaryText = summary;
                this.expanded = expanded;
                float targetRotation = expanded ? 180f : 0f;
                if (arrowAnimator != null) {
                    arrowAnimator.cancel();
                    arrowAnimator = null;
                }
                if (!initialized) {
                    initialized = true;
                    arrowRotation = targetRotation;
                    applyText();
                    return;
                }
                if (!stateChanged) {
                    arrowRotation = targetRotation;
                    applyText();
                    return;
                }
                arrowAnimator = ValueAnimator.ofFloat(arrowRotation, targetRotation);
                arrowAnimator.setDuration(340);
                arrowAnimator.setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT);
                arrowAnimator.addUpdateListener(animator -> {
                    arrowRotation = (float) animator.getAnimatedValue();
                    applyText();
                });
                applyText();
                arrowAnimator.start();
            }

            private CharSequence composeAccessibilityText() {
                String state = getString(expanded ? R.string.AccDescrExpanded : R.string.AccDescrCollapsed);
                if (TextUtils.isEmpty(summaryText)) {
                    return titleText + ", " + state;
                }
                return titleText + ", " + summaryText + ", " + state;
            }

            private void applyText() {
                SpannableStringBuilder titleWithArrow = new SpannableStringBuilder();
                titleWithArrow.append(titleText).append(' ');
                int spanStart = titleWithArrow.length();
                titleWithArrow.append('\uFFFC');
                ColoredImageSpan arrowSpan = new ColoredImageSpan(R.drawable.arrow_more);
                arrowSpan.setScale(0.6f, 0.6f);
                arrowSpan.rotate(arrowRotation);
                titleWithArrow.setSpan(arrowSpan, spanStart, spanStart + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                setTextAndValue(titleWithArrow, summaryText, false, expanded);
                setContentDescription(composeAccessibilityText());
            }

            @Override
            public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(info);
                info.setClassName(android.widget.Button.class.getName());
                info.setClickable(true);
                info.setText(composeAccessibilityText());
            }
        }

        Row(int account, long dialogId, int[] editIds, int[] editLocalIds, Runnable onChanged) {
            this.account = account;
            this.dialogId = dialogId;
            this.editIds = editIds;
            this.editLocalIds = editLocalIds;
            this.onChanged = onChanged;
            // Seed the controls from any trigger this message already has, resolved the same exact-id way
            // as commit (all edit ids + local ids) so a still-pending owner shows as armed instead of off.
            // Only an unambiguous single owner seeds the controls "on"; a MULTI conflict stays off and is
            // left for the user to resolve explicitly, never auto-changed. This is a UI seed only -- the
            // arming decision at commit re-resolves ownership from scratch.
            EventScheduleStore.OwnerSeed seed = editIds != null && editIds.length > 0
                    ? EventScheduleStore.resolveOwnerSeedForEdit(account, dialogId, editIds, editLocalIds)
                    : new EventScheduleStore.OwnerSeed(EventScheduleStore.EditOwner.NONE, null);
            if (seed.kind == EventScheduleStore.EditOwner.SINGLE) {
                EventScheduleEntry existing = seed.entry;
                enabled = true;
                types = existing.types & EventScheduleEntry.TYPE_MASK;
                patterns.addAll(existing.normalizedPatterns());
                regex = existing.regex;
                delay = existing.delaySeconds;
            } else {
                EventScheduleLastSetup.Setup remembered = EventScheduleLastSetup.get(account);
                if (remembered != null) {
                    types = remembered.types;
                    patterns.addAll(remembered.patterns);
                    regex = remembered.regex;
                    delay = remembered.delaySeconds;
                } else {
                    // NagramX: new trigger seeds are per-account local prefs so they stay device-local and
                    // avoid cloud-exported globals; legacy NaConfig scalars stay read-only fallback for users
                    // upgrading with a pre-existing last setup.
                    NaConfig cfg = NaConfig.INSTANCE;
                    int legacyTypes = cfg.getEventScheduleLastTypes().Int() & EventScheduleEntry.TYPE_MASK;
                    String firstPattern = EventScheduleEntry.normalizePattern(cfg.getEventScheduleLastPattern().String());
                    boolean hasLegacy = legacyTypes != 0 || !TextUtils.isEmpty(firstPattern);
                    if (hasLegacy) {
                        types = legacyTypes;
                        if (!TextUtils.isEmpty(firstPattern)) {
                            patterns.add(firstPattern);
                        }
                        regex = cfg.getEventScheduleLastPatternRegex().Bool();
                        delay = cfg.getEventScheduleLastDelay().Int();
                    } else {
                        types = 0;
                        regex = false;
                        delay = 0;
                    }
                }
            }
            types &= EventScheduleEntry.TYPE_MASK;
            // NagramX: unconditional presentation clamp -- an existing trigger predating this cap (or a
            // stale EventScheduleLastDelay recorded before it shipped) can carry a delay above the max.
            // This isn't the actual enforcement (that's EventScheduleStore.persist, which clamps every
            // runtime write regardless of what this field holds), but delay is read directly by snapshot()
            // and commit() below even when the sheet is never opened, so it must already be in range the
            // moment the row is constructed, not just once the sheet's controls are shown.
            delay = Math.max(0, Math.min(delay, EventScheduleEntry.MAX_DELAY_SECONDS));
        }

        void updateChip() {
            if (chip == null) return;
            CharSequence value = enabled ? summary() : getString(R.string.EventScheduleTriggerOff);
            chip.setText(getString(R.string.EventScheduleTrigger) + ": " + value);
        }

        private CharSequence summary() {
            EventScheduleEntry e = new EventScheduleEntry();
            e.types = types;
            e.setPatterns(EventScheduleEntry.normalizeCommittedPatterns(patterns));
            e.delaySeconds = delay;
            CharSequence s = e.summary(true);
            return TextUtils.isEmpty(s) ? getString(R.string.EventScheduleTriggerOff) : s;
        }

        private static PatternFieldRow createPatternFieldRow(Context context, String initialText) {
            LinearLayout container = new LinearLayout(context);
            container.setOrientation(LinearLayout.VERTICAL);
            container.setMinimumHeight(dp(50));

            FrameLayout box = new FrameLayout(context);
            box.setMinimumHeight(dp(50));

            EditTextBoldCursor field = new EditTextBoldCursor(context);
            field.setBackground(null);
            field.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
            field.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
            field.setHintTextColor(Theme.getColor(Theme.key_dialogSearchHint));
            field.setHint(getString(R.string.EventSchedulePatternHint));
            field.setCursorSize(dp(18));
            field.setCursorColor(Theme.getColor(Theme.key_chat_TextSelectionCursor));
            field.setHandlesColor(Theme.getColor(Theme.key_chat_TextSelectionCursor));
            field.setSingleLine(true);
            field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
            field.setImeOptions(EditorInfo.IME_ACTION_NEXT);
            field.setMinimumHeight(dp(50));
            field.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(EventScheduleEntry.MAX_PATTERN_LENGTH)});
            int startPad = dp(21);
            int endPad = dp(70); // keep end inset reserved even for a sole row (no reflow when remove appears)
            if (org.telegram.messenger.LocaleController.isRTL) {
                field.setPadding(endPad, dp(15), startPad, dp(15));
            } else {
                field.setPadding(startPad, dp(15), endPad, dp(15));
            }
            field.setGravity(Gravity.CENTER_VERTICAL | (org.telegram.messenger.LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT));
            field.setText(initialText);
            box.addView(field, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

            ImageView remove = new ImageView(context);
            remove.setImageResource(R.drawable.poll_remove);
            remove.setScaleType(ImageView.ScaleType.CENTER);
            remove.setColorFilter(new PorterDuffColorFilter(Theme.getColor(Theme.key_dialogIcon), PorterDuff.Mode.SRC_IN));
            remove.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector), 1));
            remove.setFocusable(false);
            remove.setFocusableInTouchMode(false);
            remove.setImportantForAccessibility(android.view.View.IMPORTANT_FOR_ACCESSIBILITY_YES);
            int removeGravity = Gravity.CENTER_VERTICAL | (org.telegram.messenger.LocaleController.isRTL ? Gravity.LEFT : Gravity.RIGHT);
            box.addView(remove, LayoutHelper.createFrame(
                    48, 48, removeGravity,
                    org.telegram.messenger.LocaleController.isRTL ? 17 : 0, 0,
                    org.telegram.messenger.LocaleController.isRTL ? 0 : 17, 0));

            TextView message = new TextView(context);
            message.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            message.setVisibility(android.view.View.GONE);
            int textGravity = org.telegram.messenger.LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT;
            message.setGravity(textGravity);
            message.setPadding(dp(21), 0, dp(21), dp(8));

            View divider = new View(context);
            divider.setBackgroundColor(Theme.getColor(Theme.key_divider));
            LinearLayout.LayoutParams dividerParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 1);
            if (org.telegram.messenger.LocaleController.isRTL) {
                dividerParams.setMargins(0, 0, dp(21), 0);
            } else {
                dividerParams.setMargins(dp(21), 0, 0, 0);
            }

            container.addView(box, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
            container.addView(message, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
            container.addView(divider, dividerParams);
            return new PatternFieldRow(container, box, field, remove, message);
        }

        private static void clearRowMessage(PatternFieldRow row) {
            row.messageView.setText(null);
            row.messageView.setVisibility(android.view.View.GONE);
        }

        private static void showRowMessage(PatternFieldRow row, String message, boolean error) {
            row.messageView.setText(message);
            row.messageView.setTextColor(Theme.getColor(error ? Theme.key_text_RedRegular : Theme.key_dialogTextGray3));
            row.messageView.setVisibility(android.view.View.VISIBLE);
        }

        private static void updateRowAccessibility(ArrayList<PatternFieldRow> rows) {
            // The trailing icon slot is remove-mode with 2+ rows, clear-mode with exactly one -- decided
            // fresh from rows.size() every call, never toggled by the click handler itself. afterTextChanged
            // calls this on every keystroke of every row, so both the icon swap (setImageResource) and the
            // content description are guarded against redundant calls (mirror syncRegexEnabled's own
            // early-out): the icon only changes on a genuine mode change, and the description -- positional,
            // depends on i in remove-mode -- is only re-set when the text it would produce actually differs
            // from what's already there, so a position shift after an add/remove still refreshes it.
            boolean wantClearMode = rows.size() == 1;
            for (int i = 0; i < rows.size(); i++) {
                PatternFieldRow row = rows.get(i);
                if (row.clearMode != wantClearMode) {
                    row.clearMode = wantClearMode;
                    row.removeButton.setImageResource(wantClearMode ? R.drawable.ic_close_white : R.drawable.poll_remove);
                }
                CharSequence wantDescription = wantClearMode
                        ? getString(R.string.EventScheduleClearPattern)
                        : org.telegram.messenger.LocaleController.formatString(R.string.EventScheduleRemovePattern, i + 1);
                if (!TextUtils.equals(row.removeButton.getContentDescription(), wantDescription)) {
                    row.removeButton.setContentDescription(wantDescription);
                }
                // Same shared predicate governs visibility for both modes -- rows.size() only picks WHICH
                // icon/action above, it never gates visibility on its own.
                row.removeButton.setVisibility(!isBlankRow(row) ? View.VISIBLE : View.INVISIBLE);
            }
        }

        private static boolean isBlankRow(PatternFieldRow row) {
            return TextUtils.isEmpty(EventScheduleEntry.normalizePattern(row.field.getText().toString()));
        }

        private static void updateAddRow(ArrayList<PatternFieldRow> rows, org.telegram.ui.Cells.TextCell addRow) {
            boolean show = rows.size() < EventScheduleEntry.MAX_PATTERN_COUNT && !hasBlankRow(rows);
            addRow.setVisibility(show ? View.VISIBLE : View.GONE);
        }

        private static boolean hasAnyPattern(ArrayList<PatternFieldRow> rows) {
            for (int i = 0; i < rows.size(); i++) {
                if (!isBlankRow(rows.get(i))) {
                    return true;
                }
            }
            return false;
        }

        private static boolean hasBlankRow(ArrayList<PatternFieldRow> rows) {
            for (int i = 0; i < rows.size(); i++) {
                if (isBlankRow(rows.get(i))) {
                    return true;
                }
            }
            return false;
        }

        // NagramX: TextCheckCell.setEnabled(boolean) is the only override that reaches the Switch,
        // but addCheckItem wires the switch's own click straight back to performClick() on the row,
        // which ignores the enabled flag -- so the single-arg call alone leaves a dimmed row that
        // still toggles. setEnabled(boolean, animators) drives the alpha fade but calls super, not the
        // single-arg override, so both calls are required together.
        private static void syncRegexEnabled(ArrayList<PatternFieldRow> rows, TextCheckCell regexCell) {
            boolean hasPattern = hasAnyPattern(rows);
            if (hasPattern == regexCell.isEnabled()) return;
            regexCell.setTextAndValueAndCheck(
                    getString(R.string.EventScheduleUseRegex),
                    getString(hasPattern ? R.string.EventScheduleRegexInfo : R.string.EventScheduleRegexNeedsPattern),
                    regexCell.isChecked(), true, true);
            regexCell.setEnabled(hasPattern);
            regexCell.setEnabled(hasPattern, null);
        }

        private static EditTextBoldCursor focusedField(ArrayList<PatternFieldRow> rows) {
            for (int i = 0; i < rows.size(); i++) {
                if (rows.get(i).field.isFocused()) return rows.get(i).field;
            }
            return null;
        }

        private static void restartFocusedInput(Context context, ArrayList<PatternFieldRow> rows) {
            EditTextBoldCursor focused = focusedField(rows);
            if (focused == null) return;
            android.view.inputmethod.InputMethodManager imm =
                    (android.view.inputmethod.InputMethodManager) context.getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.restartInput(focused);
        }

        private static void focusRow(EditTextBoldCursor field) {
            field.requestFocus();
            org.telegram.messenger.AndroidUtilities.showKeyboard(field);
            field.post(() -> field.requestRectangleOnScreen(new android.graphics.Rect(0, 0, field.getWidth(), field.getHeight()), true));
        }

        private static boolean validateRegexRow(ArrayList<PatternFieldRow> rows, PatternFieldRow row) {
            String value = EventScheduleEntry.normalizePattern(row.field.getText().toString());
            if (TextUtils.isEmpty(value)) {
                clearRowMessage(row);
                return true;
            }
            if (!EventScheduleEntry.isPatternValid(value, true)) {
                showRowMessage(row, getString(R.string.EventScheduleInvalidRegexRow), true);
                return false;
            }
            clearRowMessage(row);
            return true;
        }

        private static void showDuplicateNoticeIfAny(ArrayList<PatternFieldRow> rows, PatternFieldRow row) {
            String value = EventScheduleEntry.normalizePattern(row.field.getText().toString());
            if (TextUtils.isEmpty(value)) {
                clearRowMessage(row);
                return;
            }
            int rowIndex = rows.indexOf(row);
            for (int i = 0; i < rowIndex; i++) {
                String before = EventScheduleEntry.normalizePattern(rows.get(i).field.getText().toString());
                if (value.equals(before)) {
                    showRowMessage(row, getString(R.string.EventScheduleDuplicatePatternRow), false);
                    return;
                }
            }
            clearRowMessage(row);
        }

        private static void clearAllRowMessages(ArrayList<PatternFieldRow> rows) {
            for (int i = 0; i < rows.size(); i++) {
                clearRowMessage(rows.get(i));
            }
        }

        private static void updateImeActions(Context context, ArrayList<PatternFieldRow> rows,
                                             Runnable addRowAction, Runnable doneAction,
                                             boolean restartInput) {
            for (int i = 0; i < rows.size(); i++) {
                PatternFieldRow row = rows.get(i);
                boolean last = i == rows.size() - 1;
                boolean canAppendFromLastRow = last
                        && !isBlankRow(row)
                        && rows.size() < EventScheduleEntry.MAX_PATTERN_COUNT;
                int action = (!last || canAppendFromLastRow)
                        ? EditorInfo.IME_ACTION_NEXT
                        : EditorInfo.IME_ACTION_DONE;
                row.field.setImeOptions(action);
                row.field.setOnEditorActionListener((v, actionId, event) -> {
                    int index = rows.indexOf(row);
                    if (index < 0) return false;
                    if (actionId != EditorInfo.IME_ACTION_NEXT
                            && actionId != EditorInfo.IME_ACTION_DONE
                            && actionId != EditorInfo.IME_NULL) return false;
                    if (index < rows.size() - 1) {
                        focusRow(rows.get(index + 1).field);
                        return true;
                    }
                    if (rows.size() < EventScheduleEntry.MAX_PATTERN_COUNT
                            && !hasBlankRow(rows)
                            && !isBlankRow(row)) {
                        addRowAction.run();
                    } else {
                        doneAction.run();
                    }
                    return true;
                });
            }
            if (restartInput) {
                restartFocusedInput(context, rows);
            }
        }

        /**
         * Shared by Done and "Save current as preset...": computes the type bitmask from the check-row
         * group (index order matches the TYPE_* bit order the cells were added in), trims and dedupes the
         * pattern rows (tracking each unique value's originating row for error-targeting), and validates
         * every non-blank pattern when regex mode is on. On success returns the extracted config; on
         * failure runs the exact same deferred UI failure path Done's own handler used to run inline
         * (auto-expand the text section first via doOnLayout when it isn't already open, because the bad
         * row isn't laid out yet at that point) and returns null.
         */
        private static ExtractedConfig extractAndValidate(Context context, ArrayList<PatternFieldRow> rows,
                LinearLayout patternArea, ArrayList<TextCheckCell> typeGroup, TextCheckCell regexCell,
                boolean[] typeExpanded, boolean[] textExpanded, Runnable[] syncGroupVisibility,
                Runnable[] updateTypeHeader, Runnable[] updateTextHeader) {
            int[] typeBits = {EventScheduleEntry.TYPE_VOICE, EventScheduleEntry.TYPE_ROUND,
                    EventScheduleEntry.TYPE_VIDEO, EventScheduleEntry.TYPE_PHOTO, EventScheduleEntry.TYPE_TEXT};
            int newTypes = 0;
            for (int i = 0; i < typeGroup.size() && i < typeBits.length; i++) {
                if (typeGroup.get(i).isChecked()) newTypes |= typeBits[i];
            }

            ArrayList<String> trimmed = new ArrayList<>();
            ArrayList<PatternFieldRow> trimmedRows = new ArrayList<>();
            for (int i = 0; i < rows.size(); i++) {
                String value = EventScheduleEntry.normalizePattern(rows.get(i).field.getText().toString());
                if (!TextUtils.isEmpty(value)) {
                    trimmed.add(value);
                    trimmedRows.add(rows.get(i));
                }
            }
            ArrayList<String> unique = new ArrayList<>();
            ArrayList<PatternFieldRow> uniqueRows = new ArrayList<>();
            java.util.HashSet<String> seen = new java.util.HashSet<>();
            for (int i = 0; i < trimmed.size(); i++) {
                String value = trimmed.get(i);
                if (seen.add(value)) {
                    unique.add(value);
                    uniqueRows.add(trimmedRows.get(i));
                }
            }

            boolean useRegex = regexCell.isChecked();
            clearAllRowMessages(rows);
            if (useRegex) {
                for (int i = 0; i < unique.size(); i++) {
                    if (!EventScheduleEntry.isPatternValid(unique.get(i), true)) {
                        PatternFieldRow badRow = uniqueRows.get(i);
                        if (!textExpanded[0]) {
                            textExpanded[0] = true;
                            syncGroupVisibility[0].run();
                            updateTextHeader[0].run();
                            org.telegram.messenger.AndroidUtilities.doOnLayout(patternArea, () -> {
                                showRowMessage(badRow, getString(R.string.EventScheduleInvalidRegexRow), true);
                                focusRow(badRow.field);
                                AndroidUtil.showInputError(badRow.field);
                            });
                        } else {
                            showRowMessage(badRow, getString(R.string.EventScheduleInvalidRegexRow), true);
                            focusRow(badRow.field);
                            AndroidUtil.showInputError(badRow.field);
                        }
                        return null;
                    }
                }
            }
            if (newTypes == 0 && unique.isEmpty()) {
                if (!typeExpanded[0] || !textExpanded[0]) {
                    typeExpanded[0] = true;
                    textExpanded[0] = true;
                    syncGroupVisibility[0].run();
                    updateTypeHeader[0].run();
                    updateTextHeader[0].run();
                }
                AlertUtil.showToast(getString(R.string.EventScheduleNeedCondition));
                return null;
            }
            return new ExtractedConfig(newTypes, unique);
        }

        void openSheet(Context context) {
            BottomBuilder builder = new BottomBuilder(context, true, Theme.getColor(Theme.key_windowBackgroundGray), true);
            builder.addTitle(getString(R.string.EventScheduleTitle), getString(R.string.EventScheduleArmed));
            View introSpacer = builder.addCustomView(new View(context));
            introSpacer.setTag(RecyclerListView.TAG_NOT_SECTION);
            introSpacer.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
            introSpacer.setLayoutParams(LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 4));

            final boolean[] typeExpanded = {false};
            final boolean[] textExpanded = {false};
            final Runnable[] updateTypeHeader = new Runnable[]{() -> {}};
            final Runnable[] updateTextHeader = new Runnable[]{() -> {}};
            final Runnable[] syncGroupVisibility = new Runnable[]{() -> {}};
            final Runnable[] collapseTextGroup = new Runnable[]{() -> {}};
            final Runnable[] syncTypeDividers = new Runnable[]{() -> {}};
            final Runnable[] syncTextDividers = new Runnable[]{() -> {}};

            final DisclosureHeaderCell typeHeader = new DisclosureHeaderCell(context);
            builder.addCustomView(typeHeader);

            TextCheckCell voiceCell = builder.addCheckItem(getString(R.string.AttachAudio), (types & EventScheduleEntry.TYPE_VOICE) != 0, false, null,
                    (cell, isChecked) -> {
                        updateTypeHeader[0].run();
                        return kotlin.Unit.INSTANCE;
                    });
            TextCheckCell roundCell = builder.addCheckItem(getString(R.string.AttachRound), (types & EventScheduleEntry.TYPE_ROUND) != 0, false, null,
                    (cell, isChecked) -> {
                        updateTypeHeader[0].run();
                        return kotlin.Unit.INSTANCE;
                    });
            TextCheckCell videoCell = builder.addCheckItem(getString(R.string.AttachVideo), (types & EventScheduleEntry.TYPE_VIDEO) != 0, false, null,
                    (cell, isChecked) -> {
                        updateTypeHeader[0].run();
                        return kotlin.Unit.INSTANCE;
                    });
            TextCheckCell photoCell = builder.addCheckItem(getString(R.string.AttachPhoto), (types & EventScheduleEntry.TYPE_PHOTO) != 0, false, null,
                    (cell, isChecked) -> {
                        updateTypeHeader[0].run();
                        return kotlin.Unit.INSTANCE;
                    });
            TextCheckCell textCell = builder.addCheckItem(getString(R.string.EventScheduleTypeText), (types & EventScheduleEntry.TYPE_TEXT) != 0, false, null,
                    (cell, isChecked) -> {
                        updateTypeHeader[0].run();
                        return kotlin.Unit.INSTANCE;
                    });
            final ArrayList<TextCheckCell> typeGroup = new ArrayList<>();
            typeGroup.add(voiceCell);
            typeGroup.add(roundCell);
            typeGroup.add(videoCell);
            typeGroup.add(photoCell);
            typeGroup.add(textCell);

            View sectionSpacer = builder.addCustomView(new View(context));
            sectionSpacer.setTag(RecyclerListView.TAG_NOT_SECTION);
            sectionSpacer.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
            sectionSpacer.setLayoutParams(LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 8));

            final DisclosureHeaderCell textHeader = new DisclosureHeaderCell(context);
            builder.addCustomView(textHeader);

            LinearLayout patternArea = new LinearLayout(context);
            patternArea.setOrientation(LinearLayout.VERTICAL);
            builder.addCustomView(patternArea);

            LinearLayout patternRowsContainer = new LinearLayout(context);
            patternRowsContainer.setOrientation(LinearLayout.VERTICAL);
            patternArea.addView(patternRowsContainer, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

            final ArrayList<PatternFieldRow> rows = new ArrayList<>();
            ArrayList<String> initial = new ArrayList<>(patterns);
            if (initial.isEmpty()) {
                initial.add("");
            }
            for (int i = 0; i < initial.size() && rows.size() < EventScheduleEntry.MAX_PATTERN_COUNT; i++) {
                PatternFieldRow row = createPatternFieldRow(context, initial.get(i));
                rows.add(row);
                patternRowsContainer.addView(row.container, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
            }

            org.telegram.ui.Cells.TextCell addPatternRow = new org.telegram.ui.Cells.TextCell(context);
            addPatternRow.setBackground(Theme.getSelectorDrawable(false));
            addPatternRow.setColors(Theme.key_dialogTextBlue2, Theme.key_dialogTextBlue2);
            addPatternRow.setTextAndIcon(getString(R.string.EventScheduleAddPattern), R.drawable.msg_add, true);
            patternArea.addView(addPatternRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48));

            TextCheckCell regexCell = builder.addCheckItem(getString(R.string.EventScheduleUseRegex), regex, false, getString(R.string.EventScheduleRegexInfo),
                    (cell, isChecked) -> {
                        updateTextHeader[0].run();
                        return kotlin.Unit.INSTANCE;
                    });
            final TextInfoPrivacyCell patternInfo = new TextInfoPrivacyCell(context, 21);
            patternInfo.setText(getString(R.string.EventScheduleMatchInfo));
            builder.addCustomView(patternInfo);
            View textDelaySpacer = builder.addCustomView(new View(context));
            textDelaySpacer.setTag(RecyclerListView.TAG_NOT_SECTION);
            textDelaySpacer.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
            textDelaySpacer.setLayoutParams(LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 8));

            // ---- Presets section (I-8: starts its own section run right after textDelaySpacer) ----
            final ArrayList<EventSchedulePresetStore.Preset> presetList = EventSchedulePresetStore.getAll(account);
            final java.text.Collator presetCollator = java.text.Collator.getInstance();
            presetCollator.setStrength(java.text.Collator.SECONDARY);
            final java.util.Comparator<EventSchedulePresetStore.Preset> presetComparator = (a, b) -> {
                int byName = presetCollator.compare(a.name, b.name);
                if (byName != 0) return byName;
                return Long.compare(a.createdAt, b.createdAt);
            };

            // Captured once the sheet is actually shown (I-3: the ItemOptions ViewGroup overload needs a
            // container, and this sheet has no BaseFragment to hang the BaseFragment overload off of).
            final BottomSheet[] sheetHolder = new BottomSheet[1];
            // Whichever of the naming dialog or the remove-confirm dialog is currently up, so the sheet's
            // pre-dismiss listener can close it instead of leaving it to outlive the sheet underneath it.
            final AlertDialog[] activePresetDialogHolder = new AlertDialog[1];
            // A posted keyboard/focus Runnable tied to whichever dialog is currently up (the naming
            // dialog defers requestFocus/showKeyboard past its own onShow) -- cancelled alongside the
            // dialog itself so it can't run against a view whose dialog has already been torn down.
            final Runnable[] activePresetDialogPendingFocus = new Runnable[1];
            // Critical-2 fix: the sheet's own pre-dismiss listener only fires on BottomSheet.dismiss(),
            // not when the hosting Activity is destroyed out from under an open dialog (AlertDialog
            // registers itself with a global observer list unregistered only in dismiss()) -- so this
            // same idempotent cleanup also runs from the sheet container's detach-from-window callback,
            // wired in once the sheet is created below.
            final Runnable dismissActivePresetDialog = () -> {
                if (activePresetDialogPendingFocus[0] != null) {
                    org.telegram.messenger.AndroidUtilities.cancelRunOnUIThread(activePresetDialogPendingFocus[0]);
                    activePresetDialogPendingFocus[0] = null;
                }
                if (activePresetDialogHolder[0] != null) {
                    activePresetDialogHolder[0].dismiss();
                    activePresetDialogHolder[0] = null;
                }
            };
            final boolean[] presetsExpanded = {false};
            final boolean[] presetsBuilt = {false};
            final Runnable[] updatePresetsHeader = new Runnable[]{() -> {}};
            final Runnable[] syncPresetsVisibility = new Runnable[]{() -> {}};
            final Runnable[] rebuildPresetsList = new Runnable[]{() -> {}};
            // Real bodies assigned after the delay slider is built (I-5): both need stagedDelay/delayIndex/
            // delaySeekBar/delayValue, which are declared later in this method.
            final PresetApplier[] applyPresetHolder = new PresetApplier[]{preset -> {}};
            final Runnable[] savePresetActionHolder = new Runnable[]{() -> {}};

            final DisclosureHeaderCell presetsHeader = new DisclosureHeaderCell(context);
            builder.addCustomView(presetsHeader);

            final LinearLayout presetsContainer = new LinearLayout(context);
            presetsContainer.setOrientation(LinearLayout.VERTICAL);
            presetsContainer.setVisibility(View.GONE);
            builder.addCustomView(presetsContainer);

            // Terminate this section run here -- without a TAG_NOT_SECTION spacer, delayCardContainer
            // (added further below) stays part of the same SectionsScrollView run as the Presets header
            // and rows, so the delay slider would get painted inside the Presets card.
            View presetsDelaySpacer = builder.addCustomView(new View(context));
            presetsDelaySpacer.setTag(RecyclerListView.TAG_NOT_SECTION);
            presetsDelaySpacer.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
            presetsDelaySpacer.setLayoutParams(LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 8));

            final java.util.function.Consumer<EventSchedulePresetStore.Preset> confirmRemovePreset = (preset) -> {
                AlertDialog.Builder confirmBuilder = new AlertDialog.Builder(context);
                confirmBuilder.setTitle(getString(R.string.EventSchedulePresetRemove));
                confirmBuilder.setMessage(org.telegram.messenger.LocaleController.formatString(
                        R.string.EventSchedulePresetRemoveConfirm, preset.name));
                confirmBuilder.setNegativeButton(getString(R.string.Cancel), null);
                confirmBuilder.setPositiveButton(getString(R.string.Remove), (dialog, which) -> {
                    EventSchedulePresetStore.remove(account, preset.id);
                    for (int i = presetList.size() - 1; i >= 0; i--) {
                        if (preset.id.equals(presetList.get(i).id)) {
                            presetList.remove(i);
                        }
                    }
                    updatePresetsHeader[0].run();
                    if (presetsBuilt[0]) {
                        rebuildPresetsList[0].run();
                    }
                    AlertUtil.showToast(getString(R.string.EventSchedulePresetRemoved));
                });
                confirmBuilder.makeRed(android.app.Dialog.BUTTON_POSITIVE);
                AlertDialog confirmDialog = confirmBuilder.show();
                activePresetDialogHolder[0] = confirmDialog;
                confirmDialog.setOnDismissListener(d -> {
                    if (activePresetDialogHolder[0] == confirmDialog) activePresetDialogHolder[0] = null;
                });
            };

            rebuildPresetsList[0] = () -> {
                presetsContainer.removeAllViews();
                java.util.Collections.sort(presetList, presetComparator);
                for (int i = 0; i < presetList.size(); i++) {
                    EventSchedulePresetStore.Preset preset = presetList.get(i);
                    // I-1: TextSettingsCell.valueTextView is single-line and shrinks the title's width --
                    // TextDetailSettingsCell supports the title-over-multiline-detail layout this row needs.
                    TextDetailSettingsCell presetCell = new TextDetailSettingsCell(context);
                    presetCell.setBackground(Theme.getSelectorDrawable(false));
                    presetCell.setMultilineDetail(true);
                    presetCell.setTextAndValue(preset.name, presetSummary(preset), i < presetList.size() - 1);
                    presetCell.setOnClickListener(v -> applyPresetHolder[0].apply(preset));
                    presetCell.setOnLongClickListener(v -> {
                        BottomSheet sheet = sheetHolder[0];
                        if (sheet == null) return true;
                        ItemOptions.makeOptions(sheet.getContainer(), presetCell)
                                .add(R.drawable.msg_delete, getString(R.string.EventSchedulePresetRemove), true,
                                        () -> confirmRemovePreset.accept(preset))
                                .show();
                        return true;
                    });
                    presetsContainer.addView(presetCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
                }
                org.telegram.ui.Cells.TextCell saveAsPresetRow = new org.telegram.ui.Cells.TextCell(context);
                saveAsPresetRow.setBackground(Theme.getSelectorDrawable(false));
                saveAsPresetRow.setColors(Theme.key_dialogTextBlue2, Theme.key_dialogTextBlue2);
                saveAsPresetRow.setTextAndIcon(getString(R.string.EventSchedulePresetSaveAction), R.drawable.msg_add, false);
                saveAsPresetRow.setOnClickListener(v -> savePresetActionHolder[0].run());
                presetsContainer.addView(saveAsPresetRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48));
            };

            updatePresetsHeader[0] = () -> {
                CharSequence summary = presetList.isEmpty()
                        ? getString(R.string.EventSchedulePresetsNone)
                        : org.telegram.messenger.LocaleController.formatPluralString("EventSchedulePresetsSaved", presetList.size());
                presetsHeader.bind(getString(R.string.EventScheduleSectionPresets), summary, presetsExpanded[0]);
            };
            syncPresetsVisibility[0] = () -> presetsContainer.setVisibility(presetsExpanded[0] ? View.VISIBLE : View.GONE);

            presetsHeader.setOnClickListener(v -> {
                // Minor-4: build the (up to 50) preset rows lazily, on first expand -- not at sheet
                // construction, which would inflate them on every sheet open even when never viewed.
                if (!presetsBuilt[0]) {
                    rebuildPresetsList[0].run();
                    presetsBuilt[0] = true;
                }
                presetsExpanded[0] = !presetsExpanded[0];
                syncPresetsVisibility[0].run();
                updatePresetsHeader[0].run();
            });
            updatePresetsHeader[0].run();

            // NagramX: build initial rows, THEN regexCell, THEN attach watchers and sync once -- appended
            // rows are allowed to attach after regexCell exists.
            final Runnable[] doneActionHolder = new Runnable[]{() -> {}};
            final Runnable[] addRowActionHolder = new Runnable[]{() -> {}};
            final java.util.function.Consumer<PatternFieldRow> removeRow = (row) -> {
                int index = rows.indexOf(row);
                if (index < 0) return;
                if (rows.size() <= 1) return;
                boolean hadFocus = row.field.isFocused();
                patternRowsContainer.removeView(row.container);
                rows.remove(index);
                clearAllRowMessages(rows);
                updateRowAccessibility(rows);
                syncRegexEnabled(rows, regexCell);
                updateAddRow(rows, addPatternRow);
                updateImeActions(context, rows, addRowActionHolder[0], doneActionHolder[0], true);
                updateTextHeader[0].run();
                if (hadFocus) {
                    int next = Math.max(0, Math.min(index, rows.size() - 1));
                    focusRow(rows.get(next).field);
                }
            };

            java.util.function.Consumer<PatternFieldRow> attachWatchers = (row) -> {
                row.field.addTextChangedListener(new TextWatcher() {
                    @Override
                    public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

                    @Override
                    public void onTextChanged(CharSequence s, int start, int before, int count) {}

                    @Override
                    public void afterTextChanged(Editable s) {
                        clearRowMessage(row);
                        syncRegexEnabled(rows, regexCell);
                        updateRowAccessibility(rows);
                        updateAddRow(rows, addPatternRow);
                        updateImeActions(context, rows, addRowActionHolder[0], doneActionHolder[0], false);
                        updateTextHeader[0].run();
                    }
                });
                row.field.setOnFocusChangeListener((v, hasFocus) -> {
                    if (hasFocus) return;
                    if (isBlankRow(row) && rows.size() > 1) {
                        removeRow.accept(row);
                        return;
                    }
                    clearRowMessage(row);
                    if (regexCell.isChecked() && textExpanded[0]) {
                        validateRegexRow(rows, row);
                    }
                    if (textExpanded[0] && row.messageView.getVisibility() != android.view.View.VISIBLE) {
                        showDuplicateNoticeIfAny(rows, row);
                    }
                    updateRowAccessibility(rows);
                    updateAddRow(rows, addPatternRow);
                    updateImeActions(context, rows, addRowActionHolder[0], doneActionHolder[0], false);
                    updateTextHeader[0].run();
                });
                row.field.setOnKeyListener((v, keyCode, event) -> {
                    if (keyCode == android.view.KeyEvent.KEYCODE_DEL
                            && event.getAction() == android.view.KeyEvent.ACTION_DOWN
                            && isBlankRow(row) && rows.size() > 1) {
                        removeRow.accept(row);
                        return true;
                    }
                    return false;
                });
                // Branches on rows.size() at click time, not a listener swapped in when the row count
                // changes elsewhere -- a swapped listener risks wiping text after a row was just added, or
                // vice versa. With exactly one row the slot is the clear button: wipe the text and rely
                // entirely on the TextWatcher above for housekeeping (never removeRow, which hard no-ops
                // at rows.size() <= 1 anyway); with 2+ rows it's the ordinary remove button.
                row.removeButton.setOnClickListener(v -> {
                    if (rows.size() == 1) {
                        row.field.setText(null);
                        // focusRow both requests focus and shows the keyboard, which covers "keep focus
                        // open" (already focused: harmless re-request) and "focus it and open the
                        // keyboard" (not focused) in one call, per spec.
                        focusRow(row.field);
                        // Clearing crosses the blank/non-blank boundary without a focus change, so the IME
                        // action label (which depends on isBlankRow) needs an explicit refresh here.
                        restartFocusedInput(context, rows);
                    } else {
                        removeRow.accept(row);
                    }
                });
            };
            for (int i = 0; i < rows.size(); i++) {
                attachWatchers.accept(rows.get(i));
            }

            addRowActionHolder[0] = () -> {
                if (rows.size() >= EventScheduleEntry.MAX_PATTERN_COUNT || hasBlankRow(rows)) return;
                PatternFieldRow row = createPatternFieldRow(context, "");
                rows.add(row);
                patternRowsContainer.addView(row.container, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
                attachWatchers.accept(row);
                clearAllRowMessages(rows);
                updateRowAccessibility(rows);
                syncRegexEnabled(rows, regexCell);
                updateAddRow(rows, addPatternRow);
                updateImeActions(context, rows, addRowActionHolder[0], doneActionHolder[0], true);
                updateTextHeader[0].run();
                org.telegram.messenger.AndroidUtilities.doOnLayout(patternRowsContainer, () -> focusRow(row.field));
            };
            addPatternRow.setOnClickListener(v -> addRowActionHolder[0].run());

            java.util.function.Supplier<String> typeSummary = () -> {
                ArrayList<String> selected = new ArrayList<>();
                if (voiceCell.isChecked()) selected.add(getString(R.string.AttachAudio));
                if (roundCell.isChecked()) selected.add(getString(R.string.AttachRound));
                if (videoCell.isChecked()) selected.add(getString(R.string.AttachVideo));
                if (photoCell.isChecked()) selected.add(getString(R.string.AttachPhoto));
                if (textCell.isChecked()) selected.add(getString(R.string.EventScheduleTypeText));
                if (selected.isEmpty()) {
                    return getString(R.string.EventScheduleTriggerOff);
                }
                if (selected.size() <= 2) {
                    return TextUtils.join(", ", selected);
                }
                return selected.get(0) + ", " + selected.get(1) + " "
                        + org.telegram.messenger.LocaleController.formatString(R.string.EventSchedulePatternMore, selected.size() - 2);
            };
            java.util.function.Supplier<String> textSummary = () -> {
                ArrayList<String> unique = new ArrayList<>();
                java.util.HashSet<String> seen = new java.util.HashSet<>();
                for (int i = 0; i < rows.size(); i++) {
                    String value = EventScheduleEntry.normalizePattern(rows.get(i).field.getText().toString());
                    if (TextUtils.isEmpty(value) || !seen.add(value)) {
                        continue;
                    }
                    unique.add(value);
                }
                if (unique.isEmpty()) {
                    return getString(R.string.EventScheduleTriggerOff);
                }
                String summary = unique.get(0);
                if (unique.size() > 1) {
                    summary = summary + " " + org.telegram.messenger.LocaleController.formatString(R.string.EventSchedulePatternMore, unique.size() - 1);
                }
                if (regexCell.isChecked()) {
                    summary = summary + " \u00b7 " + getString(R.string.EventScheduleUseRegex);
                }
                return summary;
            };
            syncTypeDividers[0] = () -> {
                for (int i = 0; i < typeGroup.size(); i++) {
                    typeGroup.get(i).setDivider(typeExpanded[0] && i < typeGroup.size() - 1);
                }
            };
            syncTextDividers[0] = () -> regexCell.setDivider(false);
            updateTypeHeader[0] = () -> {
                typeHeader.bind(getString(R.string.EventScheduleSectionType), typeSummary.get(), typeExpanded[0]);
            };
            updateTextHeader[0] = () -> {
                textHeader.bind(getString(R.string.EventScheduleSectionPattern), textSummary.get(), textExpanded[0]);
            };
            syncGroupVisibility[0] = () -> {
                int typeVisibility = typeExpanded[0] ? View.VISIBLE : View.GONE;
                for (int i = 0; i < typeGroup.size(); i++) {
                    typeGroup.get(i).setVisibility(typeVisibility);
                }
                int textVisibility = textExpanded[0] ? View.VISIBLE : View.GONE;
                patternArea.setVisibility(textVisibility);
                regexCell.setVisibility(textVisibility);
                patternInfo.setVisibility(textVisibility);
                syncTypeDividers[0].run();
                syncTextDividers[0].run();
            };
            collapseTextGroup[0] = () -> {
                EditTextBoldCursor focused = focusedField(rows);
                if (focused != null) {
                    // Collapsing hides the text group; dismiss keyboard/focus first so no hidden input keeps focus.
                    org.telegram.messenger.AndroidUtilities.hideKeyboard(focused);
                    focused.clearFocus();
                }
                clearAllRowMessages(rows);
                boolean hasPattern = hasAnyPattern(rows);
                for (int i = rows.size() - 1; i >= 0; i--) {
                    PatternFieldRow row = rows.get(i);
                    if (isBlankRow(row) && (hasPattern || rows.size() > 1)) {
                        removeRow.accept(row);
                    }
                }
                if (!hasAnyPattern(rows)) {
                    while (rows.size() > 1) {
                        removeRow.accept(rows.get(rows.size() - 1));
                    }
                }
                clearAllRowMessages(rows);
                textExpanded[0] = false;
                syncGroupVisibility[0].run();
                updateTextHeader[0].run();
            };
            typeHeader.setOnClickListener(v -> {
                typeExpanded[0] = !typeExpanded[0];
                syncGroupVisibility[0].run();
                updateTypeHeader[0].run();
            });
            textHeader.setOnClickListener(v -> {
                if (textExpanded[0]) {
                    collapseTextGroup[0].run();
                } else {
                    textExpanded[0] = true;
                    syncGroupVisibility[0].run();
                    updateTextHeader[0].run();
                }
            });

            syncRegexEnabled(rows, regexCell);
            updateRowAccessibility(rows);
            updateAddRow(rows, addPatternRow);
            boolean hasAnyCondition = (types & EventScheduleEntry.TYPE_MASK) != 0 || hasAnyPattern(rows);
            typeExpanded[0] = !hasAnyCondition;
            textExpanded[0] = false;
            syncGroupVisibility[0].run();
            updateTypeHeader[0].run();
            updateTextHeader[0].run();

            final int[] delayValues = {0, 2, 5, 10, 15, 20, 25, EventScheduleEntry.MAX_DELAY_SECONDS};
            int startIndex = 0;
            for (int i = 0; i < delayValues.length; i++) {
                if (delayValues[i] <= delay) startIndex = i;
            }
            // NagramX: delayIndex tracks the slider's current visual step (for the snap-to-step call on
            // release); stagedDelay is the value Done will actually commit. It starts equal to the raw
            // seed `delay` -- not delayValues[startIndex] -- so an untouched Done reuses the exact seed
            // (which need not be a stop; see the label comment below) and only a real onSeekBarDrag
            // callback overwrites it with a snapped stop. Neither is written back to the Row's `delay`
            // field until Done (see the Done handler): Cancel must discard a mid-drag selection, and this
            // sheet has no other way to leave the delay unmodified than simply never writing it.
            final int[] delayIndex = {startIndex};
            final int[] stagedDelay = {delay};

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
            // NagramX: label the sheet-local staged value, not the floor-matched stop -- fromJson's
            // Math.min clamp does not guarantee stop membership (a raw disk value like 7 stays 7, which
            // isn't in delayValues), so a floor-matched label could disagree with what Done actually
            // commits. Labeling stagedDelay directly keeps the two in lockstep by construction, both here
            // at initial paint (stagedDelay starts equal to the raw seed) and after a real drag
            // (stagedDelay is reassigned alongside this label in the delegate below).
            delayValue.setText(formatDelayLabel(delay));
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
                    stagedDelay[0] = delayValues[step];
                    delayValue.setText(formatDelayLabel(stagedDelay[0]));
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
            FrameLayout delayCardContainer = new FrameLayout(context);
            delayCardContainer.addView(delayLayout, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
            builder.addCustomView(delayCardContainer);

            // I-5: real bodies for the Presets section's apply/save actions, deferred to here because both
            // need delaySeekBar/stagedDelay/delayIndex/delayValue, which only exist from this point on.
            applyPresetHolder[0] = (preset) -> {
                // I-7(i): unfocus + dismiss keyboard first, mirroring collapseTextGroup's own sequencing.
                // Order matters: hide the keyboard while the field is still attached (hideKeyboard needs
                // a live view to resolve its window token), then clear `rows`/the container BEFORE calling
                // clearFocus() -- clearFocus() synchronously invokes the row's focus-change listener,
                // which auto-removes a blank row when it loses focus with 2+ rows present; if that ran
                // here it would remove+refocus a row we're about to discard anyway. Clearing `rows` first
                // makes that listener's rows.indexOf(row) come back -1, so removeRow's own guard no-ops
                // it instead of doing that redundant (and focus-stealing) work.
                EditTextBoldCursor focused = focusedField(rows);
                if (focused != null) {
                    org.telegram.messenger.AndroidUtilities.hideKeyboard(focused);
                }
                rows.clear();
                patternRowsContainer.removeAllViews();
                if (focused != null) {
                    focused.clearFocus();
                }

                // I-7(ii)-(iii): full rebuild from scratch via the existing row-creation + watcher-attach
                // helpers (never a parallel "faster" path) -- this is a full replace, not a merge.
                ArrayList<String> seedPatterns = new ArrayList<>(preset.patterns);
                if (seedPatterns.isEmpty()) {
                    seedPatterns.add("");
                }
                for (int i = 0; i < seedPatterns.size() && rows.size() < EventScheduleEntry.MAX_PATTERN_COUNT; i++) {
                    PatternFieldRow row = createPatternFieldRow(context, seedPatterns.get(i));
                    rows.add(row);
                    patternRowsContainer.addView(row.container, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
                    attachWatchers.accept(row);
                }

                // I-7(iv): the same six-call housekeeping tail every other row mutation runs.
                clearAllRowMessages(rows);
                updateRowAccessibility(rows);
                syncRegexEnabled(rows, regexCell);
                updateAddRow(rows, addPatternRow);
                updateImeActions(context, rows, addRowActionHolder[0], doneActionHolder[0], true);
                updateTextHeader[0].run();

                voiceCell.setChecked((preset.types & EventScheduleEntry.TYPE_VOICE) != 0);
                roundCell.setChecked((preset.types & EventScheduleEntry.TYPE_ROUND) != 0);
                videoCell.setChecked((preset.types & EventScheduleEntry.TYPE_VIDEO) != 0);
                photoCell.setChecked((preset.types & EventScheduleEntry.TYPE_PHOTO) != 0);
                textCell.setChecked((preset.types & EventScheduleEntry.TYPE_TEXT) != 0);

                regexCell.setChecked(preset.regex);
                if (preset.regex) {
                    for (int i = 0; i < rows.size(); i++) {
                        validateRegexRow(rows, rows.get(i));
                    }
                } else {
                    clearAllRowMessages(rows);
                }

                // I-6: floor-snap only the slider's visual position; stagedDelay (what Done will commit)
                // is set to the exact preset value, never the floored stop -- collapsing this distinction
                // would silently corrupt the delay the user saved.
                int floorIndex = 0;
                for (int i = 0; i < delayValues.length; i++) {
                    if (delayValues[i] <= preset.delaySeconds) floorIndex = i;
                }
                delayIndex[0] = floorIndex;
                stagedDelay[0] = preset.delaySeconds;
                delayValue.setText(formatDelayLabel(stagedDelay[0]));
                delaySeekBar.setProgress(floorIndex / (float) (delayValues.length - 1), true);

                // Full replace of the expand state too, mirroring the sheet's own initial-expand decision
                // logic, applied independently per section.
                typeExpanded[0] = preset.types != 0;
                textExpanded[0] = !preset.patterns.isEmpty();
                syncGroupVisibility[0].run();
                updateTypeHeader[0].run();
                updateTextHeader[0].run();

                presetsExpanded[0] = false;
                syncPresetsVisibility[0].run();
                updatePresetsHeader[0].run();
            };

            savePresetActionHolder[0] = () -> {
                if (presetList.size() >= EventScheduleEntry.MAX_PRESET_COUNT) {
                    AlertUtil.showToast(getString(R.string.EventSchedulePresetLimitReached));
                    return;
                }
                ExtractedConfig extracted = extractAndValidate(context, rows, patternArea, typeGroup, regexCell,
                        typeExpanded, textExpanded, syncGroupVisibility, updateTypeHeader, updateTextHeader);
                if (extracted == null) return;
                boolean savedRegex = regexCell.isChecked();
                int savedDelay = stagedDelay[0];

                EditTextBoldCursor nameField = new EditTextBoldCursor(context);
                nameField.setBackground(null);
                nameField.setLineColors(Theme.getColor(Theme.key_dialogInputField), Theme.getColor(Theme.key_dialogInputFieldActivated), Theme.getColor(Theme.key_text_RedBold));
                nameField.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
                nameField.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
                nameField.setHintTextColor(Theme.getColor(Theme.key_dialogSearchHint));
                nameField.setHint(getString(R.string.EventSchedulePresetNameHint));
                nameField.setMaxLines(1);
                nameField.setLines(1);
                nameField.setSingleLine(true);
                nameField.setImeOptions(EditorInfo.IME_ACTION_DONE);
                nameField.setCursorColor(Theme.getColor(Theme.key_chat_TextSelectionCursor));
                nameField.setCursorSize(dp(18));
                nameField.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(EventScheduleEntry.MAX_PRESET_NAME_LENGTH)});
                nameField.setPadding(0, dp(4), 0, 0);

                final TextView errorView = new TextView(context);
                errorView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
                errorView.setTextColor(Theme.getColor(Theme.key_text_RedRegular));
                errorView.setVisibility(View.GONE);
                errorView.setPadding(dp(24), dp(4), dp(24), 0);

                LinearLayout dialogLayout = new LinearLayout(context);
                dialogLayout.setOrientation(LinearLayout.VERTICAL);
                dialogLayout.addView(nameField, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 36, 24, 6, 24, 0));
                dialogLayout.addView(errorView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 4, 0, 6));

                AlertDialog.Builder nameBuilder = new AlertDialog.Builder(context);
                nameBuilder.setTitle(getString(R.string.EventSchedulePresetSaveTitle));
                nameBuilder.setView(dialogLayout);
                nameBuilder.setNegativeButton(getString(R.string.Cancel), null);
                nameBuilder.setPositiveButton(getString(R.string.Save), (dialog, which) -> {});
                final AlertDialog nameDialog = nameBuilder.create();
                activePresetDialogHolder[0] = nameDialog;
                nameDialog.setOnDismissListener(d -> {
                    if (activePresetDialogHolder[0] == nameDialog) activePresetDialogHolder[0] = null;
                    if (activePresetDialogPendingFocus[0] != null) {
                        org.telegram.messenger.AndroidUtilities.cancelRunOnUIThread(activePresetDialogPendingFocus[0]);
                        activePresetDialogPendingFocus[0] = null;
                    }
                });
                Runnable requestFocusAndKeyboard = () -> {
                    activePresetDialogPendingFocus[0] = null;
                    nameField.requestFocus();
                    org.telegram.messenger.AndroidUtilities.showKeyboard(nameField);
                };
                nameDialog.setOnShowListener(d -> {
                    activePresetDialogPendingFocus[0] = requestFocusAndKeyboard;
                    org.telegram.messenger.AndroidUtilities.runOnUIThread(requestFocusAndKeyboard);
                });
                nameDialog.show();
                nameDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                    String candidate = nameField.getText().toString().trim();
                    if (TextUtils.isEmpty(candidate)) {
                        shakeAndVibrate(nameField);
                        return;
                    }
                    if (EventSchedulePresetStore.nameExists(account, candidate)) {
                        errorView.setText(getString(R.string.EventSchedulePresetNameDuplicate));
                        errorView.setVisibility(View.VISIBLE);
                        shakeAndVibrate(nameField);
                        return;
                    }
                    boolean added = EventSchedulePresetStore.add(account, candidate, extracted.types,
                            extracted.patterns, savedRegex, savedDelay);
                    org.telegram.messenger.AndroidUtilities.hideKeyboard(nameField);
                    nameDialog.dismiss();
                    if (!added) {
                        // Cap was reached by a concurrent save between the check above and here.
                        AlertUtil.showToast(getString(R.string.EventSchedulePresetLimitReached));
                        return;
                    }
                    presetList.clear();
                    presetList.addAll(EventSchedulePresetStore.getAll(account));
                    presetsBuilt[0] = true;
                    if (!presetsExpanded[0]) {
                        presetsExpanded[0] = true;
                        syncPresetsVisibility[0].run();
                    }
                    rebuildPresetsList[0].run();
                    updatePresetsHeader[0].run();
                    AlertUtil.showToast(getString(R.string.EventSchedulePresetSaved));
                });
            };

            if (enabled) {
                View removeSpacer = builder.addCustomView(new View(context));
                removeSpacer.setTag(RecyclerListView.TAG_NOT_SECTION);
                removeSpacer.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
                removeSpacer.setLayoutParams(LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 12));
                builder.addItem(getString(R.string.EventScheduleClear), R.drawable.msg_delete, true, it -> {
                    enabled = false;
                    userTouchedTrigger = true;
                    updateChip();
                    return kotlin.Unit.INSTANCE;
                });
            }

            TextView doneButton = builder.addButton(getString(R.string.Done), true, false, it -> {
                ExtractedConfig extracted = extractAndValidate(context, rows, patternArea, typeGroup, regexCell,
                        typeExpanded, textExpanded, syncGroupVisibility, updateTypeHeader, updateTextHeader);
                if (extracted == null) return kotlin.Unit.INSTANCE;

                int newDelay = stagedDelay[0];
                enabled = true;
                userTouchedTrigger = true;
                types = extracted.types;
                patterns.clear();
                patterns.addAll(extracted.patterns);
                regex = regexCell.isChecked();
                delay = newDelay;
                EventScheduleLastSetup.put(account, types, patterns, regex, delay);
                updateChip();
                builder.dismiss();
                return kotlin.Unit.INSTANCE;
            });

            doneActionHolder[0] = doneButton::performClick;
            updateImeActions(context, rows, addRowActionHolder[0], doneActionHolder[0], false);

            regexCell.setOnClickListener(v -> {
                if (!regexCell.isEnabled()) {
                    return;
                }
                boolean target = !regexCell.isChecked();
                regexCell.setChecked(target);
                if (!target) {
                    clearAllRowMessages(rows);
                } else {
                    for (int i = 0; i < rows.size(); i++) {
                        validateRegexRow(rows, rows.get(i));
                    }
                }
                updateTextHeader[0].run();
            });

            builder.addCancelButton();
            builder.setOnPreDismissListener(dialog -> dismissActivePresetDialog.run());
            BottomSheet sheet = builder.create();
            sheetHolder[0] = sheet;
            // Critical-2: a pre-dismiss listener alone misses the case where the hosting Activity is
            // destroyed while a preset dialog is up (destroy != BottomSheet.dismiss()) -- the container
            // detaching from its window is the one signal common to both a normal dismiss and an
            // Activity-driven teardown, so hook cleanup there too. Idempotent: whichever cleanup path
            // runs first nulls activePresetDialogHolder[0], so the other is a safe no-op.
            sheet.getContainer().addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
                @Override
                public void onViewAttachedToWindow(View v) {
                }

                @Override
                public void onViewDetachedFromWindow(View v) {
                    dismissActivePresetDialog.run();
                }
            });
            sheet.show();
        }

        @Override
        public EventScheduleConfig snapshot() {
            return enabled ? new EventScheduleConfig(types, patterns, regex, delay) : null;
        }

        @Override
        public void commit(int scheduleDate, int repeatPeriod) {
            // NOTE: this decision tree (!userTouchedTrigger -> refresh / armed -> arm / else off) has an
            // async twin in EventScheduleController.finishCommitEdit, reached via reconcileThenCommitEdit
            // just below when a durable orphan forces a storage hop first. They diverge on purpose: this
            // synchronous path calls refresh() (it owns the open sheet's overview) and reaches the common
            // no-reconcile case, while the twin runs after dismiss with no fragment, so it repaints on its
            // own and adds the dialog-wide fail-closed gate. A new intent must be added in BOTH places.
            // Premium repeat and early-trigger don't compose; a repeat is always a plain schedule.
            boolean armed = enabled && repeatPeriod == 0;
            EventScheduleConfig config = new EventScheduleConfig(types, patterns, regex, delay);
            if (editIds != null && editIds.length > 0) {
                // Editing an existing scheduled message. The schedule picker fires this commit even when the
                // user never opened the trigger controls, so a schedule-only edit must not reconfigure or
                // remove a trigger the user never chose to change -- otherwise it reads as a turn-off and
                // destroys durable state in the pre-existing multi-owner case.
                if (EventScheduleController.needsCommitReconcile(account, dialogId)) {
                    // Rare: an unbound entry in this dialog still carries only durable correlation keys (a
                    // restart orphan the warm reconcile has not healed yet). Resolve it to current server
                    // ids first so the edit can't create a second trigger beside it. Runs async after the
                    // sheet dismisses; on failure the controller shows the toast itself. No fragment or
                    // callback crosses the hop -- the overview repaints on its own, refresh() is not called.
                    EventScheduleController.reconcileThenCommitEdit(account, dialogId, editIds, editLocalIds,
                            userTouchedTrigger, armed, config, scheduleDate);
                    return;
                }
                if (!userTouchedTrigger) {
                    // The user changed the send time but never opened the trigger controls, so leave the
                    // trigger's configuration exactly as it is. fallbackDate is derived from the message's
                    // schedule time, not trigger config, so it must still track the new time -- a stale
                    // value prunes the trigger on reload and mis-orders the send queue. Only ever refreshes
                    // a lone owner; a multi-owner conflict or none is untouched, so the destructive case
                    // (an untouched edit reading as a turn-off) stays closed.
                    EventScheduleController.commitEditRefresh(account, dialogId, editIds, editLocalIds,
                            scheduleDate);
                    return;
                }
                // Re-resolve ownership at commit across both id spaces rather than trusting the seed, so a
                // trigger armed or turned off in the meantime is handled correctly and the same message
                // can't end up with two triggers.
                if (armed) {
                    boolean claimed = EventScheduleController.commitEditArm(account, dialogId, editIds, editLocalIds,
                            config, scheduleDate);
                    if (!claimed) {
                        // Another trigger already owns this message (only reachable against data a prior
                        // defect persisted). Leave every trigger untouched; the schedule-date edit still
                        // proceeds, so tell the user the trigger specifically was not changed.
                        AlertUtil.showToast(getString(R.string.EventScheduleTriggerConflict));
                        return;
                    }
                } else {
                    EventScheduleController.commitEditOff(account, dialogId, editIds, editLocalIds);
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
            entry.setPatterns(config.patterns);
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

    // I-2: EventScheduleEntry.summary(boolean) never references regex -- append the same suffix the
    // sheet's own textSummary builds, so two presets differing only by regex don't render identically.
    private static CharSequence presetSummary(EventSchedulePresetStore.Preset preset) {
        EventScheduleEntry e = new EventScheduleEntry();
        e.types = preset.types;
        e.setPatterns(EventScheduleEntry.normalizeCommittedPatterns(preset.patterns));
        e.delaySeconds = preset.delaySeconds;
        CharSequence summary = e.summary(true);
        if (preset.regex) {
            String suffix = getString(R.string.EventScheduleUseRegex);
            summary = TextUtils.isEmpty(summary) ? suffix : summary + " \u00b7 " + suffix;
        }
        return summary;
    }

    private static void shakeAndVibrate(View view) {
        org.telegram.messenger.AndroidUtilities.shakeView(view);
        try {
            if (!NekoConfig.disableVibration.Bool()) {
                view.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP,
                        android.view.HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING);
            }
        } catch (Exception ignore) {
        }
    }
}
