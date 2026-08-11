package tw.nekomimi.nekogram.settings;

import static org.telegram.messenger.LocaleController.getString;

import android.app.Dialog;
import android.content.Context;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
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
                    String status;
                    if (active != null && active.id == profile.id) {
                        Long deadline = com.radolyn.ayugram.privacyprofiles.PrivacyProfilesController.getActiveDeadline();
                        if (deadline != null) {
                            status = LocaleController.formatString(R.string.PrivacyProfileActiveUntil, LocaleController.formatDateTime(deadline / 1000, true));
                        } else {
                            status = getString(R.string.PrivacyProfileActive);
                        }
                    } else {
                        status = autoLockValueText(profile.timeout);
                    }
                    cell.setTextAndValue(profile.name, status, position + 1 != profilesEndRow);
                    org.telegram.ui.Components.AvatarDrawable avatarDrawable = new org.telegram.ui.Components.AvatarDrawable();
                    avatarDrawable.setInfo(profile.colorSeed, profile.name, null);
                    cell.setImage(avatarDrawable);
                    break;
                }
            }
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull android.view.ViewGroup parent, int viewType) {
            if (viewType == 20) {
                TextDetailCell cell = new TextDetailCell(mContext, resourcesProvider);
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
            } else if (position == showInSettingsRow || position == showNotificationContentWhenLockedRow) {
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

    private int autoLockValueIndex(int value) {
        for (int i = 0; i < AUTO_LOCK_VALUES.length; i++) {
            if (AUTO_LOCK_VALUES[i] == value) return i;
        }
        return 4;
    }

    /** Add/edit dialog: name field + the same stock auto-lock picker PasscodeActivity uses. */
    private void showAddEditProfileDialog(@Nullable com.radolyn.ayugram.privacyprofiles.PrivacyProfile existing) {
        if (getParentActivity() == null) return;
        Context context = getParentActivity();
        LinearLayout linearLayout = new LinearLayout(context);
        linearLayout.setOrientation(LinearLayout.VERTICAL);
        int pad = AndroidUtilities.dp(17);
        linearLayout.setPadding(pad, AndroidUtilities.dp(6), pad, 0);

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
        if (existing != null) {
            editText.setText(existing.name);
            editText.setSelection(editText.getText().length());
        }
        linearLayout.addView(editText, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 36));

        NumberPicker numberPicker = new NumberPicker(context);
        numberPicker.setMinValue(0);
        numberPicker.setMaxValue(AUTO_LOCK_VALUES.length - 1);
        numberPicker.setValue(autoLockValueIndex(existing != null ? existing.timeout : SharedConfig.autoLockIn));
        numberPicker.setFormatter(this::autoLockValueText2);
        linearLayout.addView(numberPicker, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 8, 0, 0));

        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle(getString(existing == null ? R.string.PrivacyProfileAdd : R.string.PrivacyProfileEdit));
        builder.setView(linearLayout);
        builder.setNegativeButton(getString(R.string.Cancel), null);
        builder.setPositiveButton(getString(R.string.PrivacyProfileSave), (dialog, which) -> {
            String name = editText.getText() != null ? editText.getText().toString() : "";
            int timeout = AUTO_LOCK_VALUES[numberPicker.getValue()];
            if (existing == null) {
                if (com.radolyn.ayugram.privacyprofiles.PrivacyProfilesController.getProfileCount() >= com.radolyn.ayugram.privacyprofiles.PrivacyProfilesController.MAX_PROFILES) {
                    BulletinFactory.of(this).createErrorBulletin(getString(R.string.PrivacyProfileMaxCount)).show();
                    return;
                }
                com.radolyn.ayugram.privacyprofiles.PrivacyProfilesController.addProfile(name, timeout);
            } else {
                // Only update the pinned shortcut's label if the edit actually took (editProfile
                // rejects e.g. an empty trimmed name) -- otherwise the shortcut label would drift
                // from the persisted profile name.
                if (com.radolyn.ayugram.privacyprofiles.PrivacyProfilesController.editProfile(existing.id, name, timeout)) {
                    com.radolyn.ayugram.privacyprofiles.PrivacyProfileShortcuts.updateLabel(existing.withName(name.trim()).withTimeout(timeout));
                }
            }
            updateRows();
            listAdapter.notifyDataSetChanged();
        });
        showDialog(builder.create());
    }

    // NumberPicker.Formatter is a functional interface with an (int) -> String signature; keep a
    // second name so it doesn't collide with the (int) -> String helper used by the row/status text.
    private String autoLockValueText2(int index) {
        return autoLockValueText(AUTO_LOCK_VALUES[index]);
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
            o.add(R.drawable.msg_permissions, getString(R.string.PrivacyProfileActivateNow), () -> {
                com.radolyn.ayugram.privacyprofiles.PrivacyProfilesController.activate(profile.id, com.radolyn.ayugram.privacyprofiles.PrivacyProfilesController.ActivationMode.NOW, 0);
                refreshProfileRows();
            });
            o.add(R.drawable.msg_mute_period, getString(R.string.PrivacyProfileActivateFor), () -> showActivateForMenu(profile, anchor));
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
        o.add(R.drawable.msg_home, getString(R.string.PrivacyProfileAddToHomeScreen), () ->
            com.radolyn.ayugram.privacyprofiles.PrivacyProfileShortcuts.requestPin(profile));
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

    private void showActivateForMenu(com.radolyn.ayugram.privacyprofiles.PrivacyProfile profile, View anchor) {
        if (getParentActivity() == null) return;
        ItemOptions o = ItemOptions.makeOptions(this, anchor);
        long[] durationsMs = {15 * 60 * 1000L, 60 * 60 * 1000L, 4 * 60 * 60 * 1000L, 24 * 60 * 60 * 1000L, 7 * 24 * 60 * 60 * 1000L};
        int[] labels = {R.string.PrivacyProfileFor15Minutes, R.string.PrivacyProfileFor1Hour, R.string.PrivacyProfileFor4Hours, R.string.PrivacyProfileFor1Day, R.string.PrivacyProfileFor1Week};
        for (int i = 0; i < durationsMs.length; i++) {
            long duration = durationsMs[i];
            o.add(getString(labels[i]), () -> {
                com.radolyn.ayugram.privacyprofiles.PrivacyProfilesController.activate(profile.id, com.radolyn.ayugram.privacyprofiles.PrivacyProfilesController.ActivationMode.FOR, duration);
                updateRows();
                listAdapter.notifyDataSetChanged();
            });
        }
        o.show();
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
