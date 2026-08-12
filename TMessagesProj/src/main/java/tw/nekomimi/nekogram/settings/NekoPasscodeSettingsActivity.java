package tw.nekomimi.nekogram.settings;

import static org.telegram.messenger.LocaleController.getString;

import android.app.Dialog;
import android.content.Context;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AccountInstance;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BuildConfig;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Cells.TextDetailCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Cells.TextSettingsCell;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.ItemOptions;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.NumberPicker;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.URLSpanNoUnderline;
import org.telegram.ui.PasscodeActivity;
import org.telegram.ui.PrivacySettingsActivity;

import java.util.ArrayList;
import java.util.Locale;

import tw.nekomimi.nekogram.helpers.PasscodeHelper;
import tw.nekomimi.nekogram.ui.cells.AccountCell;
import tw.nekomimi.nekogram.ui.cells.HeaderCell;
import xyz.nextalone.nagram.NaConfig;

public class NekoPasscodeSettingsActivity extends BaseNekoSettingsActivity {

    private boolean passcodeSet;

    private int profilesHeaderRow;
    private int profilesStartRow;
    private int profilesEndRow;
    private int profilesAddRow;
    private int profilesShowOnTabRow;
    private int profilesFooterRow;

    private int showInSettingsRow;
    private int showInSettings2Row;

    private int accountsStartRow;
    private int accountsEndRow;

    private int setPanicCodeRow;
    private int removePanicCodeRow;
    private int panicCode2Row;

    private int clearPasscodesRow;
    private int clearPasscodes2Row;

    private int showNotificationContentWhenLockedRow;
    private int showNotificationContentWhenLocked2Row;

    private final ArrayList<Integer> accounts = new ArrayList<>();
    private java.util.List<com.radolyn.ayugram.privacyprofiles.PrivacyProfile> profiles = new ArrayList<>();

    @Override
    public boolean onFragmentCreate() {
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            var u = AccountInstance.getInstance(a).getUserConfig().getCurrentUser();
            if (u != null) {
                accounts.add(a);
            }
        }
        return super.onFragmentCreate();
    }

    @Override
    protected void onItemClick(View view, int position, float x, float y) {
        if (!passcodeSet) {
            showBulletin();
            return;
        }
        if (position == profilesAddRow) {
            if (com.radolyn.ayugram.privacyprofiles.PrivacyProfilesController.getProfileCount() >= com.radolyn.ayugram.privacyprofiles.PrivacyProfilesController.MAX_PROFILES) {
                BulletinFactory.of(this).createErrorBulletin(getString(R.string.PrivacyProfileMaxCount)).show();
                return;
            }
            showAddEditProfileDialog(null);
            return;
        }
        if (position >= profilesStartRow && position < profilesEndRow) {
            showProfileActions(profiles.get(position - profilesStartRow), view);
            return;
        }
        if (position > accountsStartRow && position < accountsEndRow) {
            var account = accounts.get(position - accountsStartRow - 1);
            var builder = new AlertDialog.Builder(getParentActivity());

            var linearLayout = new LinearLayout(getParentActivity());
            linearLayout.setOrientation(LinearLayout.VERTICAL);

            if (PasscodeHelper.hasPasscodeForAccount(account)) {
                TextCheckCell hideAccount = new TextCheckCell(getParentActivity(), 23, true);
                hideAccount.setTextAndCheck(getString(R.string.PasscodeHideAccount), PasscodeHelper.isAccountHidden(account), false);
                hideAccount.setOnClickListener(view13 -> {
                    boolean hide = !hideAccount.isChecked();
                    PasscodeHelper.setHideAccount(account, hide);
                    hideAccount.setChecked(hide);
                    getNotificationCenter().postNotificationName(NotificationCenter.mainUserInfoChanged);
                });
                hideAccount.setBackground(Theme.getSelectorDrawable(false));
                linearLayout.addView(hideAccount, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
            }

            TextCheckCell allowPanic = new TextCheckCell(getParentActivity(), 23, true);
            allowPanic.setTextAndCheck(getString(R.string.PasscodeAllowPanic), PasscodeHelper.isAccountAllowPanic(account), false);
            allowPanic.setOnClickListener(view13 -> {
                boolean hide = !allowPanic.isChecked();
                PasscodeHelper.setAccountAllowPanic(account, hide);
                allowPanic.setChecked(hide);
            });
            allowPanic.setBackground(Theme.getSelectorDrawable(false));
            linearLayout.addView(allowPanic, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

            AlertDialog.AlertDialogCell editPasscode = new AlertDialog.AlertDialogCell(getParentActivity(), null);
            editPasscode.setTextAndIcon(PasscodeHelper.hasPasscodeForAccount(account) ? getString(R.string.PasscodeEdit) : getString(R.string.PasscodeSet), 0);
            editPasscode.setOnClickListener(view1 -> {
                builder.getDismissRunnable().run();
                presentFragment(new PasscodeActivity(PasscodeActivity.TYPE_SETUP_CODE, account));
            });
            editPasscode.setBackground(Theme.getSelectorDrawable(false));
            linearLayout.addView(editPasscode, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

            if (PasscodeHelper.hasPasscodeForAccount(account)) {
                AlertDialog.AlertDialogCell removePasscode = new AlertDialog.AlertDialogCell(getParentActivity(), null);
                removePasscode.setTextAndIcon(getString(R.string.PasscodeRemove), 0);
                removePasscode.setOnClickListener(view12 -> {
                    AlertDialog alertDialog = new AlertDialog.Builder(getParentActivity())
                            .setTitle(getString(R.string.PasscodeRemove))
                            .setMessage(getString(R.string.PasscodeRemoveConfirmMessage))
                            .setNegativeButton(getString(R.string.Cancel), null)
                            .setPositiveButton(getString(R.string.DisablePasscodeTurnOff), (dialog, which) -> {
                                var hidden = PasscodeHelper.isAccountHidden(account);
                                PasscodeHelper.removePasscodeForAccount(account);
                                listAdapter.notifyItemChanged(position);
                                if (hidden) {
                                    getNotificationCenter().postNotificationName(NotificationCenter.mainUserInfoChanged);
                                }
                            }).create();
                    showDialog(alertDialog);
                    ((TextView) alertDialog.getButton(Dialog.BUTTON_POSITIVE)).setTextColor(Theme.getColor(Theme.key_dialogTextRed));
                });
                removePasscode.setBackground(Theme.getSelectorDrawable(false));
                linearLayout.addView(removePasscode, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
            }

            builder.setView(linearLayout);
            showDialog(builder.create());
        } else if (position == clearPasscodesRow) {
            PasscodeHelper.clearAll();
            finishFragment();
        } else if (position == setPanicCodeRow) {
            presentFragment(new PasscodeActivity(PasscodeActivity.TYPE_SETUP_CODE, Integer.MAX_VALUE));
        } else if (position == removePanicCodeRow) {
            AlertDialog alertDialog = new AlertDialog.Builder(getParentActivity())
                    .setTitle(getString(R.string.PasscodePanicCodeRemove))
                    .setMessage(getString(R.string.PasscodePanicCodeRemoveConfirmMessage))
                    .setNegativeButton(getString(R.string.Cancel), null)
                    .setPositiveButton(getString(R.string.DisablePasscodeTurnOff), (dialog, which) -> {
                        PasscodeHelper.removePasscodeForAccount(Integer.MAX_VALUE);
                        listAdapter.notifyItemChanged(setPanicCodeRow);
                        listAdapter.notifyItemRemoved(removePanicCodeRow);
                        updateRows();
                    }).create();
            showDialog(alertDialog);
            ((TextView) alertDialog.getButton(Dialog.BUTTON_POSITIVE)).setTextColor(Theme.getColor(Theme.key_dialogTextRed));
        } else if (position == profilesShowOnTabRow) {
            boolean value = !com.radolyn.ayugram.privacyprofiles.PrivacyProfilesController.showActiveOnSettingsTab();
            com.radolyn.ayugram.privacyprofiles.PrivacyProfilesController.setShowActiveOnSettingsTab(value);
            if (view instanceof TextCheckCell) {
                ((TextCheckCell) view).setChecked(value);
            }
        } else if (position == showInSettingsRow) {
            PasscodeHelper.setHideSettings(!PasscodeHelper.isSettingsHidden());
            if (view instanceof TextCheckCell) {
                ((TextCheckCell) view).setChecked(!PasscodeHelper.isSettingsHidden());
            }
        } else if (position == showNotificationContentWhenLockedRow) {
            boolean value = NaConfig.INSTANCE.getShowNotificationPreviewWhenLocked().toggleConfigBool();
            if (view instanceof TextCheckCell textCheckCell) {
                textCheckCell.setChecked(value);
            }
        }
    }

    @Override
    protected BaseListAdapter createAdapter(Context context) {
        return new ListAdapter(context);
    }

    @Override
    protected String getActionBarTitle() {
        return getString(R.string.PasscodeNeko);
    }

    @Override
    protected String getKey() {
        return PasscodeHelper.getSettingsKey();
    }

    @Override
    public void onResume() {
        passcodeSet = SharedConfig.passcodeHash.length() > 0;
        if (!passcodeSet) {
            showBulletin();
        }
        updateRows();
        super.onResume();
    }

    @Override
    protected void updateRows() {
        super.updateRows();

        profiles = com.radolyn.ayugram.privacyprofiles.PrivacyProfilesController.getProfiles();
        profilesHeaderRow = rowCount++;
        profilesStartRow = rowCount;
        rowCount += profiles.size();
        profilesEndRow = rowCount;
        profilesAddRow = rowCount++;
        // Only meaningful once at least one profile exists.
        profilesShowOnTabRow = profiles.isEmpty() ? -1 : rowCount++;
        profilesFooterRow = rowCount++;

        showInSettingsRow = rowCount++;
        showInSettings2Row = rowCount++;

        accountsStartRow = rowCount++;
        rowCount += accounts.size();
        accountsEndRow = rowCount++;

        setPanicCodeRow = rowCount++;
        if (!PasscodeHelper.hasPanicCode()) {
            removePanicCodeRow = -1;
        } else {
            removePanicCodeRow = rowCount++;
        }
        panicCode2Row = rowCount++;

        if (BuildConfig.DEBUG) {
            clearPasscodesRow = rowCount++;
            clearPasscodes2Row = rowCount++;
        } else {
            clearPasscodesRow = -1;
            clearPasscodes2Row = -1;
        }

        showNotificationContentWhenLockedRow = rowCount++;
        showNotificationContentWhenLocked2Row = rowCount++;
    }

    private class ListAdapter extends BaseListAdapter {

        public ListAdapter(Context context) {
            super(context);
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            switch (holder.getItemViewType()) {
                case 1: {
                    if (position == clearPasscodes2Row) {
                        holder.itemView.setBackground(Theme.getThemedDrawable(mContext, R.drawable.greydivider_bottom, Theme.key_windowBackgroundGrayShadow));
                    } else {
                        holder.itemView.setBackground(Theme.getThemedDrawable(mContext, R.drawable.greydivider, Theme.key_windowBackgroundGrayShadow));
                    }
                    break;
                }
                case 2: {
                    TextSettingsCell textCell = (TextSettingsCell) holder.itemView;
                    textCell.setCanDisable(true);
                    textCell.setEnabled(passcodeSet, null);
                    textCell.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
                    if (position == setPanicCodeRow) {
                        textCell.setText(PasscodeHelper.hasPanicCode() ? getString(R.string.PasscodePanicCodeEdit) : getString(R.string.PasscodePanicCodeSet), removePanicCodeRow != -1);
                    } else if (position == clearPasscodesRow) {
                        textCell.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteRedText3));
                        textCell.setText("Clear passcodes", false);
                    } else if (position == removePanicCodeRow) {
                        textCell.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteRedText3));
                        textCell.setText(getString(R.string.PasscodePanicCodeRemove), false);
                    } else if (position == profilesAddRow) {
                        textCell.setText(getString(R.string.PrivacyProfilesAdd), false);
                    }
                    break;
                }
                case 3: {
                    TextCheckCell textCell = (TextCheckCell) holder.itemView;
                    textCell.setEnabled(passcodeSet, null);
                    if (position == showInSettingsRow) {
                        textCell.setTextAndCheck(getString(R.string.PasscodeShowInSettings), !PasscodeHelper.isSettingsHidden(), false);
                    } else if (position == profilesShowOnTabRow) {
                        textCell.setTextAndCheck(getString(R.string.PrivacyProfileShowOnSettingsTab), com.radolyn.ayugram.privacyprofiles.PrivacyProfilesController.showActiveOnSettingsTab(), false);
                    } else if (position == showNotificationContentWhenLockedRow) {
                        textCell.setTextAndCheck(getString(R.string.PasscodeShowMessagePreviewWhenLocked), NaConfig.INSTANCE.getShowNotificationPreviewWhenLocked().Bool(), false);
                    }
                    break;
                }
                case 4: {
                    HeaderCell cell = (HeaderCell) holder.itemView;
                    cell.setEnabled(passcodeSet, null);
                    if (position == accountsStartRow) {
                        cell.setText(getString(R.string.Account));
                    } else if (position == profilesHeaderRow) {
                        cell.setText(getString(R.string.PrivacyProfiles));
                    }
                    break;
                }
                case 7: {
                    TextInfoPrivacyCell cell = (TextInfoPrivacyCell) holder.itemView;
                    cell.setEnabled(passcodeSet, null);
                    cell.setBackground(Theme.getThemedDrawable(mContext, R.drawable.greydivider, Theme.key_windowBackgroundGrayShadow));
                    if (position == accountsEndRow) {
                        cell.setText(getString(R.string.PasscodeAbout));
                    } else if (position == panicCode2Row) {
                        cell.setText(getString(R.string.PasscodePanicCodeAbout));
                    } else if (position == showInSettings2Row) {
                        var link = String.format(Locale.ENGLISH, "https://t.me/nasettings/%s", PasscodeHelper.getSettingsKey());
                        var stringBuilder = new SpannableStringBuilder(AndroidUtilities.replaceTags(getString(R.string.PasscodeShowInSettingsAbout)));
                        stringBuilder.append("\n").append(link);
                        stringBuilder.setSpan(new URLSpanNoUnderline(null) {
                            @Override
                            public void onClick(@NonNull View view) {
                                android.content.ClipboardManager clipboard = (android.content.ClipboardManager) ApplicationLoader.applicationContext.getSystemService(Context.CLIPBOARD_SERVICE);
                                android.content.ClipData clip = android.content.ClipData.newPlainText("label", link);
                                clipboard.setPrimaryClip(clip);
                                BulletinFactory.of(NekoPasscodeSettingsActivity.this).createCopyLinkBulletin().show();
                            }
                        }, stringBuilder.length() - link.length(), stringBuilder.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                        cell.setText(stringBuilder);
                    } else if (position == showNotificationContentWhenLocked2Row) {
                        cell.setText(getString(R.string.PasscodeShowMessagePreviewWhenLockedAbout));
                        cell.setBackground(Theme.getThemedDrawable(mContext, R.drawable.greydivider_bottom, Theme.key_windowBackgroundGrayShadow));
                    } else if (position == profilesFooterRow) {
                        cell.setText(getString(R.string.PrivacyProfilesAbout));
                        cell.setBackground(Theme.getThemedDrawable(mContext, R.drawable.greydivider_bottom, Theme.key_windowBackgroundGrayShadow));
                    }
                    break;
                }
                case 11: {
                    AccountCell cell = (AccountCell) holder.itemView;
                    cell.setEnabled(passcodeSet);
                    int account = accounts.get(position - accountsStartRow - 1);
                    cell.setAccount(account, PasscodeHelper.hasPasscodeForAccount(account), position + 1 != accountsEndRow);
                    break;
                }
                case 20: {
                    TextDetailCell cell = (TextDetailCell) holder.itemView;
                    cell.setAlpha(passcodeSet ? 1f : 0.5f);
                    com.radolyn.ayugram.privacyprofiles.PrivacyProfile profile = profiles.get(position - profilesStartRow);
                    com.radolyn.ayugram.privacyprofiles.PrivacyProfile active = com.radolyn.ayugram.privacyprofiles.PrivacyProfilesController.getActiveProfile();
                    // Configured auto-lock value is always shown, on its own line -- the active/timed
                    // status (when this profile happens to be the active one) is a second line below
                    // it rather than replacing it, so the two never blend into one ambiguous string.
                    String configured = autoLockValueVerbose(profile.timeout);
                    CharSequence value;
                    if (active != null && active.id == profile.id) {
                        Long deadline = com.radolyn.ayugram.privacyprofiles.PrivacyProfilesController.getActiveDeadline();
                        String status = deadline != null
                                ? LocaleController.formatString(R.string.PrivacyProfileActiveUntil, LocaleController.formatDateTime(deadline / 1000, true))
                                : getString(R.string.PrivacyProfileActive);
                        value = configured + "\n" + status;
                    } else {
                        value = configured;
                    }
                    cell.setTextAndValue(profile.name, value, position + 1 != profilesEndRow);
                    cell.setImage(com.radolyn.ayugram.privacyprofiles.PrivacyProfileIcons.circleDrawable(mContext, profile, 36), getString(R.string.PrivacyProfileIconContentDescription));
                    break;
                }
            }
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull android.view.ViewGroup parent, int viewType) {
            if (viewType == 20) {
                TextDetailCell cell = new TextDetailCell(mContext, resourcesProvider, false, true);
                cell.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundWhite));
                cell.setLayoutParams(new RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT));
                return new RecyclerListView.Holder(cell);
            }
            return super.onCreateViewHolder(parent, viewType);
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            return passcodeSet && (holder.getItemViewType() == 20 || super.isEnabled(holder));
        }

        @Override
        public int getItemViewType(int position) {
            if (position == clearPasscodes2Row) {
                return 1;
            } else if (position == clearPasscodesRow || position == setPanicCodeRow || position == removePanicCodeRow || position == profilesAddRow) {
                return 2;
            } else if (position == showInSettingsRow || position == showNotificationContentWhenLockedRow || position == profilesShowOnTabRow) {
                return 3;
            } else if (position == accountsStartRow || position == profilesHeaderRow) {
                return 4;
            } else if (position == showInSettings2Row || position == accountsEndRow || position == panicCode2Row || position == showNotificationContentWhenLocked2Row || position == profilesFooterRow) {
                return 7;
            } else if (position > accountsStartRow && position < accountsEndRow) {
                return 11;
            } else if (position >= profilesStartRow && position < profilesEndRow) {
                return 20;
            }
            return 2;
        }
    }

    private void showBulletin() {
        BulletinFactory.of(this).createSimpleBulletin(R.raw.chats_infotip, getString(R.string.PasscodeNeeded), getString(R.string.Settings), () -> {
            presentFragment(new PrivacySettingsActivity());
            AndroidUtilities.scrollToFragmentRow(parentLayout, "passcodeRow");
        }).show();
    }

    private static final int[] AUTO_LOCK_VALUES = {0, 1, 60, 60 * 5, 60 * 60, 60 * 60 * 5};

    /** Short form, used only by the add/edit dialog's NumberPicker wheel -- it doesn't ellipsize,
     * so the verbose row strings below would clip there. */
    private String autoLockValueText(int value) {
        if (value == 0) {
            return getString(R.string.AutoLockDisabled);
        } else if (value == 1) {
            return LocaleController.getString("AutoLockImmediately", R.string.AutoLockImmediately);
        } else if (value == 60) {
            return LocaleController.formatString("AutoLockInTime", R.string.AutoLockInTime, LocaleController.formatPluralString("Minutes", 1));
        } else if (value == 60 * 5) {
            return LocaleController.formatString("AutoLockInTime", R.string.AutoLockInTime, LocaleController.formatPluralString("Minutes", 5));
        } else if (value == 60 * 60) {
            return LocaleController.formatString("AutoLockInTime", R.string.AutoLockInTime, LocaleController.formatPluralString("Hours", 1));
        } else if (value == 60 * 60 * 5) {
            return LocaleController.formatString("AutoLockInTime", R.string.AutoLockInTime, LocaleController.formatPluralString("Hours", 5));
        }
        return "";
    }

    /** Verbose form, used only by the profile row (item 6) -- exact fixed strings, one per
     * supported timeout, never fragment-style ("5 min") the way the dialog wheel shows it. */
    private String autoLockValueVerbose(int value) {
        if (value == 0) {
            return getString(R.string.PrivacyProfileLockNever);
        } else if (value == 1) {
            return getString(R.string.PrivacyProfileLockImmediately);
        } else if (value == 60) {
            return getString(R.string.PrivacyProfileLockAfter1Minute);
        } else if (value == 60 * 5) {
            return getString(R.string.PrivacyProfileLockAfter5Minutes);
        } else if (value == 60 * 60) {
            return getString(R.string.PrivacyProfileLockAfter1Hour);
        } else if (value == 60 * 60 * 5) {
            return getString(R.string.PrivacyProfileLockAfter5Hours);
        }
        return "";
    }

    private int autoLockValueIndex(int value) {
        for (int i = 0; i < AUTO_LOCK_VALUES.length; i++) {
            if (AUTO_LOCK_VALUES[i] == value) return i;
        }
        return 4;
    }

    /**
     * Add/edit dialog: a leading round icon button (tap opens the existing folder-icon-grid
     * picker), the name field, then an "Auto-lock" label over the stock short-form picker.
     */
    private void showAddEditProfileDialog(@Nullable com.radolyn.ayugram.privacyprofiles.PrivacyProfile existing) {
        if (getParentActivity() == null) return;
        Context context = getParentActivity();
        LinearLayout linearLayout = new LinearLayout(context);
        linearLayout.setOrientation(LinearLayout.VERTICAL);
        int pad = AndroidUtilities.dp(17);
        linearLayout.setPadding(pad, AndroidUtilities.dp(20), pad, 0);

        final String[] selectedIcon = {existing != null ? existing.icon : com.radolyn.ayugram.privacyprofiles.PrivacyProfile.DEFAULT_ICON};
        // Same seed feeds both the live preview below and (for a new profile) the profile actually
        // persisted on Save -- otherwise the preview would show a color the saved profile never gets,
        // since addProfile can't reuse this value as the profile id (id uniqueness is checked inside
        // the controller's lock, not here).
        final long newColorSeed = Utilities.random.nextLong();
        // Only used to render the live icon-preview button; the profile itself (existing or new)
        // is never mutated directly here -- addProfile/editProfile take the final icon explicitly.
        com.radolyn.ayugram.privacyprofiles.PrivacyProfile previewSeed = existing != null ? existing
                : new com.radolyn.ayugram.privacyprofiles.PrivacyProfile(0, "", 0, newColorSeed, 0, selectedIcon[0]);

        LinearLayout nameRow = new LinearLayout(context);
        nameRow.setOrientation(LinearLayout.HORIZONTAL);
        nameRow.setGravity(Gravity.CENTER_VERTICAL);

        android.widget.ImageView iconButton = new android.widget.ImageView(context);
        iconButton.setContentDescription(getString(R.string.PrivacyProfileIconContentDescription));
        iconButton.setImageDrawable(com.radolyn.ayugram.privacyprofiles.PrivacyProfileIcons.circleDrawable(context,
                previewSeed.withIcon(selectedIcon[0]), 40));
        iconButton.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector), Theme.RIPPLE_MASK_CIRCLE_20DP));
        nameRow.addView(iconButton, LayoutHelper.createLinear(48, 48, 0, 0, 0, 12, 0));

        EditTextBoldCursor editText = new EditTextBoldCursor(context);
        editText.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 16);
        editText.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        editText.setHintTextColor(Theme.getColor(Theme.key_dialogTextHint));
        editText.setSingleLine(true);
        editText.setMaxLines(1);
        editText.setLines(1);
        editText.setHint(getString(R.string.PrivacyProfileNamePlaceholder));
        editText.setPadding(0, AndroidUtilities.dp(8), 0, AndroidUtilities.dp(8));
        editText.setBackgroundDrawable(Theme.createEditTextDrawable(context, true));
        editText.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(24)});
        if (existing != null) {
            editText.setText(existing.name);
            editText.setSelection(editText.getText().length());
        }
        nameRow.addView(editText, LayoutHelper.createLinear(0, 48, 1f));
        linearLayout.addView(nameRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        // Kept as a Dialog[] holder (not a local var) so the form's own dismiss listener below can
        // close it too, if the form goes away for some other reason while the grid is still open.
        final Dialog[] iconPickerDialog = {null};
        iconButton.setOnClickListener(v -> iconPickerDialog[0] = tw.nekomimi.nekogram.folder.IconSelectorAlert.show(context, emoticon -> {
            selectedIcon[0] = emoticon;
            iconButton.setImageDrawable(com.radolyn.ayugram.privacyprofiles.PrivacyProfileIcons.circleDrawable(context,
                    previewSeed.withIcon(emoticon), 40));
        }));

        TextView autoLockLabel = new TextView(context);
        autoLockLabel.setText(getString(R.string.PrivacyProfileAutoLockLabel));
        autoLockLabel.setTextColor(Theme.getColor(Theme.key_dialogTextGray2));
        autoLockLabel.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 14);
        linearLayout.addView(autoLockLabel, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 28, 0, 0));

        NumberPicker numberPicker = new NumberPicker(context);
        numberPicker.setMinValue(0);
        numberPicker.setMaxValue(AUTO_LOCK_VALUES.length - 1);
        numberPicker.setValue(autoLockValueIndex(existing != null ? existing.timeout : SharedConfig.autoLockIn));
        numberPicker.setFormatter(v -> autoLockValueText(AUTO_LOCK_VALUES[v]));
        // Explicit 3 visible rows: the default item count leaves the wheel taller than this
        // dialog needs and pushes the caption below the fold on short screens.
        numberPicker.setItemCount(3);
        linearLayout.addView(numberPicker, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, NumberPicker.DEFAULT_SIZE_PER_COUNT * 3, 0, 4, 0, 0));

        TextView caption = new TextView(context);
        caption.setText(autoLockValueVerbose(AUTO_LOCK_VALUES[numberPicker.getValue()]));
        caption.setTextColor(Theme.getColor(Theme.key_dialogTextGray2));
        caption.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 13);
        caption.setGravity(Gravity.CENTER);
        linearLayout.addView(caption, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 4, 0, 8));
        // The caption states what the *currently selected* value actually does, so it has to
        // track the wheel rather than describing the control in the abstract.
        numberPicker.setOnValueChangedListener((picker, oldVal, newVal) ->
                caption.setText(autoLockValueVerbose(AUTO_LOCK_VALUES[newVal])));

        android.widget.ScrollView scrollView = new android.widget.ScrollView(context);
        scrollView.setFillViewport(true);
        scrollView.addView(linearLayout, new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT));

        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle(getString(existing == null ? R.string.PrivacyProfileAdd : R.string.PrivacyProfileEdit));
        builder.setView(scrollView);
        builder.setNegativeButton(getString(R.string.Cancel), null);
        builder.setPositiveButton(getString(R.string.PrivacyProfileSave), (dialog, which) -> {
            String name = editText.getText() != null ? editText.getText().toString() : "";
            int timeout = AUTO_LOCK_VALUES[numberPicker.getValue()];
            if (existing == null) {
                if (com.radolyn.ayugram.privacyprofiles.PrivacyProfilesController.getProfileCount() >= com.radolyn.ayugram.privacyprofiles.PrivacyProfilesController.MAX_PROFILES) {
                    BulletinFactory.of(this).createErrorBulletin(getString(R.string.PrivacyProfileMaxCount)).show();
                    return;
                }
                com.radolyn.ayugram.privacyprofiles.PrivacyProfile created =
                        com.radolyn.ayugram.privacyprofiles.PrivacyProfilesController.addProfile(name, timeout, selectedIcon[0], newColorSeed);
                if (created != null) {
                    showProfileSavedBulletin(created);
                }
            } else {
                // Only update the pinned shortcut's label/icon if the edit actually took (editProfile
                // rejects e.g. an empty trimmed name) -- otherwise the shortcut would drift from the
                // persisted profile.
                if (com.radolyn.ayugram.privacyprofiles.PrivacyProfilesController.editProfile(existing.id, name, timeout, selectedIcon[0])) {
                    com.radolyn.ayugram.privacyprofiles.PrivacyProfileShortcuts.updateLabel(existing.withName(name.trim()).withTimeout(timeout).withIcon(selectedIcon[0]));
                }
            }
            updateRows();
            listAdapter.notifyDataSetChanged();
        });
        AlertDialog dialog = builder.create();
        showDialog(dialog, d -> {
            if (iconPickerDialog[0] != null && iconPickerDialog[0].isShowing()) {
                iconPickerDialog[0].dismiss();
            }
        });
        // Save stays dimmed until there's a real name to save. Wired after create() because the
        // button view only exists once the dialog is built.
        View saveButton = dialog.getButton(Dialog.BUTTON_POSITIVE);
        if (saveButton != null) {
            Runnable updateSaveEnabled = () -> {
                boolean enabled = editText.getText() != null && editText.getText().toString().trim().length() > 0;
                saveButton.setEnabled(enabled);
                saveButton.setAlpha(enabled ? 1f : 0.5f);
            };
            updateSaveEnabled.run();
            editText.addTextChangedListener(new android.text.TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

                @Override
                public void onTextChanged(CharSequence s, int start, int before, int count) {}

                @Override
                public void afterTextChanged(android.text.Editable s) {
                    updateSaveEnabled.run();
                }
            });
        }
    }

    /** First successful profile CREATE: an inline shortcut nudge, not shown on edits. Uses
     * DURATION_LONG explicitly (not the 4-arg overload's text-length heuristic, which would pick
     * DURATION_SHORT for a string this short) since this bulletin's only purpose is its action button. */
    private void showProfileSavedBulletin(com.radolyn.ayugram.privacyprofiles.PrivacyProfile profile) {
        BulletinFactory.of(this).createSimpleBulletin(R.raw.contact_check, getString(R.string.PrivacyProfileSavedBulletin), getString(R.string.PrivacyProfileAddToHomeScreen), org.telegram.ui.Components.Bulletin.DURATION_LONG, () -> requestPinWithFallback(profile)).show();
    }

    /** Shared by both "Add to home screen" entry points (the saved-bulletin action and the
     * per-profile action menu) so an unsupported launcher is never a silent no-op in either place. */
    private void requestPinWithFallback(com.radolyn.ayugram.privacyprofiles.PrivacyProfile profile) {
        if (!com.radolyn.ayugram.privacyprofiles.PrivacyProfileShortcuts.requestPin(profile)) {
            BulletinFactory.of(this).createErrorBulletin(getString(R.string.PrivacyProfileShortcutsUnsupported)).show();
        }
    }

    private void refreshProfileRows() {
        updateRows();
        listAdapter.notifyDataSetChanged();
    }

    private void showProfileActions(com.radolyn.ayugram.privacyprofiles.PrivacyProfile profile, View anchor) {
        if (getParentActivity() == null) return;
        com.radolyn.ayugram.privacyprofiles.PrivacyProfile active = com.radolyn.ayugram.privacyprofiles.PrivacyProfilesController.getActiveProfile();
        boolean isActive = active != null && active.id == profile.id;
        boolean isTimed = isActive && com.radolyn.ayugram.privacyprofiles.PrivacyProfilesController.getActiveDeadline() != null;

        ItemOptions o = ItemOptions.makeOptions(this, anchor);
        if (!isActive) {
            o.add(R.drawable.msg_permissions, getString(R.string.PrivacyProfileTurnOn), () -> {
                com.radolyn.ayugram.privacyprofiles.PrivacyProfilesController.activate(profile.id, com.radolyn.ayugram.privacyprofiles.PrivacyProfilesController.ActivationMode.NOW, 0);
                refreshProfileRows();
            });
            // Stock presets activate straight away -- no intermediate sheet. They use FOR, which
            // deliberately doesn't overwrite the remembered custom duration below.
            addDurationPreset(o, profile, 1, 0);
            addDurationPreset(o, profile, 8, 0);
            addDurationPreset(o, profile, 24, 0);
            long remembered = com.radolyn.ayugram.privacyprofiles.PrivacyProfilesController.getLastCustomDurationMillis(profile.id);
            if (remembered > 0 && !isStockPreset(remembered)) {
                int rh = (int) (remembered / 3600000L);
                int rm = (int) ((remembered % 3600000L) / 60000L);
                o.add(R.drawable.msg_mute_period, LocaleController.formatString(R.string.PrivacyProfileForDuration,
                        com.radolyn.ayugram.privacyprofiles.PrivacyProfileDurationSheet.formatDuration(rh, rm)), () -> {
                    com.radolyn.ayugram.privacyprofiles.PrivacyProfilesController.activate(profile.id, com.radolyn.ayugram.privacyprofiles.PrivacyProfilesController.ActivationMode.FOR_CUSTOM, remembered);
                    refreshProfileRows();
                });
            }
            o.add(R.drawable.msg_mute_period, getString(R.string.PrivacyProfileForCustomTime), () ->
                com.radolyn.ayugram.privacyprofiles.PrivacyProfileDurationSheet.show(this, profile, this::refreshProfileRows));
            o.add(R.drawable.msg_calendar2, getString(R.string.PrivacyProfileActivateUntil), () -> showActivateUntilPicker(profile));
        } else {
            o.add(R.drawable.msg_permissions, getString(R.string.PrivacyProfileTurnOff), () -> {
                com.radolyn.ayugram.privacyprofiles.PrivacyProfilesController.deactivate();
                refreshProfileRows();
            });
            if (isTimed) {
                o.add(R.drawable.msg_mute_period, getString(R.string.PrivacyProfileCancelTimer), () -> {
                    com.radolyn.ayugram.privacyprofiles.PrivacyProfilesController.cancelTimer();
                    refreshProfileRows();
                });
            }
        }
        o.addGap();
        o.add(R.drawable.msg_edit, getString(R.string.Edit), () -> showAddEditProfileDialog(profile));
        o.add(R.drawable.msg_home, getString(R.string.PrivacyProfileAddToHomeScreen), () -> requestPinWithFallback(profile));
        o.add(R.drawable.msg_delete, getString(R.string.PrivacyProfileDelete), true, () -> {
            AlertDialog alertDialog = new AlertDialog.Builder(getParentActivity())
                    .setTitle(getString(R.string.PrivacyProfileDeleteConfirmTitle))
                    .setMessage(getString(R.string.PrivacyProfileDeleteConfirmMessage))
                    .setNegativeButton(getString(R.string.Cancel), null)
                    .setPositiveButton(getString(R.string.PrivacyProfileDelete), (dialog, which) -> {
                        com.radolyn.ayugram.privacyprofiles.PrivacyProfilesController.deleteProfile(profile.id);
                        refreshProfileRows();
                    }).create();
            showDialog(alertDialog);
            ((TextView) alertDialog.getButton(Dialog.BUTTON_POSITIVE)).setTextColor(Theme.getColor(Theme.key_dialogTextRed));
        });
        o.show();
    }

    private static final long[] STOCK_PRESET_MS = {3600000L, 8 * 3600000L, 24 * 3600000L};

    private static boolean isStockPreset(long durationMs) {
        for (long p : STOCK_PRESET_MS) {
            if (p == durationMs) return true;
        }
        return false;
    }

    private void addDurationPreset(ItemOptions o, com.radolyn.ayugram.privacyprofiles.PrivacyProfile profile, int hours, int minutes) {
        final long durationMs = hours * 3600000L + minutes * 60000L;
        o.add(R.drawable.msg_mute_period, LocaleController.formatString(R.string.PrivacyProfileForDuration,
                com.radolyn.ayugram.privacyprofiles.PrivacyProfileDurationSheet.formatDuration(hours, minutes)), () -> {
            com.radolyn.ayugram.privacyprofiles.PrivacyProfilesController.activate(profile.id, com.radolyn.ayugram.privacyprofiles.PrivacyProfilesController.ActivationMode.FOR, durationMs);
            refreshProfileRows();
        });
    }

    private void showActivateUntilPicker(com.radolyn.ayugram.privacyprofiles.PrivacyProfile profile) {
        if (getParentActivity() == null) return;
        org.telegram.ui.Components.AlertsCreator.createDatePickerDialog(getParentActivity(), getString(R.string.PrivacyProfileActivateUntil), getString(R.string.Done), 0, (notify, scheduleDate, scheduleRepeatPeriod) -> {
            long deadlineMillis = scheduleDate * 1000L;
            if (deadlineMillis <= System.currentTimeMillis()) {
                return;
            }
            com.radolyn.ayugram.privacyprofiles.PrivacyProfilesController.activate(profile.id, com.radolyn.ayugram.privacyprofiles.PrivacyProfilesController.ActivationMode.UNTIL, deadlineMillis);
            updateRows();
            listAdapter.notifyDataSetChanged();
        }).show();
    }
}
