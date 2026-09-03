package com.radolyn.ayugram.chatprivacy;

import android.content.Context;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.radolyn.ayugram.chatlock.ChatLockController;
import com.radolyn.ayugram.hidelastmessage.HideLastMessageController;
import com.radolyn.ayugram.hidelastmessage.HideLastMessageDialog;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.NotificationsController;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Cells.TextSettingsCell;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.LayoutHelper;

import tw.nekomimi.nekogram.helpers.PopupHelper;

import java.util.ArrayList;

public final class ChatPrivacySheet {

    private ChatPrivacySheet() {}

    public static void show(BaseFragment fragment, long dialogId) {
        if (fragment == null || fragment.getParentActivity() == null || dialogId == 0) {
            return;
        }
        final int account = fragment.getCurrentAccount();
        final Context context = fragment.getParentActivity();
        final BottomSheet[] sheetRef = new BottomSheet[1];

        BottomSheet.Builder builder = new BottomSheet.Builder(context, false, fragment.getResourceProvider());

        LinearLayout container = new LinearLayout(context);
        container.setOrientation(LinearLayout.VERTICAL);

        TextView titleView = new TextView(context);
        titleView.setText(LocaleController.getString(R.string.ChatPrivacyTitle));
        titleView.setTextColor(Theme.getColor(Theme.key_dialogTextBlack, fragment.getResourceProvider()));
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
        titleView.setTypeface(AndroidUtilities.bold());
        container.addView(titleView, LayoutHelper.createLinearRelatively(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.START | Gravity.TOP, 22, 12, 22, 8));

        final TextCheckCell hideCell = new TextCheckCell(context, 21, true, fragment.getResourceProvider());
        hideCell.setBackground(Theme.getSelectorDrawable(false, fragment.getResourceProvider()));
        container.addView(hideCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        final TextSettingsCell placeholderCell = new TextSettingsCell(context, 21, fragment.getResourceProvider());
        placeholderCell.setCanDisable(true);
        placeholderCell.setBackground(Theme.getSelectorDrawable(false, fragment.getResourceProvider()));
        container.addView(placeholderCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        final TextCheckCell lockCell = new TextCheckCell(context, 21, true, fragment.getResourceProvider());
        lockCell.setBackground(Theme.getSelectorDrawable(false, fragment.getResourceProvider()));
        container.addView(lockCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        final HeaderCell notificationsHeader = new HeaderCell(context, fragment.getResourceProvider());
        notificationsHeader.setText(LocaleController.getString(R.string.NaxCoverSectionTitle));
        container.addView(notificationsHeader, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        final TextCheckCell disguiseCell = new TextCheckCell(context, 21, true, fragment.getResourceProvider());
        disguiseCell.setBackground(Theme.getSelectorDrawable(false, fragment.getResourceProvider()));
        container.addView(disguiseCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        final TextSettingsCell coverCell = new TextSettingsCell(context, 21, fragment.getResourceProvider());
        coverCell.setBackground(Theme.getSelectorDrawable(false, fragment.getResourceProvider()));
        container.addView(coverCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        // Always-visible footer stating the cover limitations; stays put whether or not Cover is shown.
        final TextInfoPrivacyCell footerCell = new TextInfoPrivacyCell(context, 21, fragment.getResourceProvider());
        footerCell.setText(LocaleController.getString(R.string.NaxCoverLimitations));
        container.addView(footerCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        final Runnable[] refreshRef = new Runnable[1];
        refreshRef[0] = () -> {
            boolean hidden = HideLastMessageController.isHidden(account, dialogId);
            hideCell.setTextAndCheck(LocaleController.getString(R.string.HideLastMessageTitle), hidden, true);

            placeholderCell.setVisibility(hidden ? View.VISIBLE : View.GONE);
            if (hidden) {
                placeholderCell.setTextAndValue(
                        LocaleController.getString(R.string.ChatPrivacyPlaceholderText),
                        HideLastMessageController.getPlaceholder(account, dialogId),
                        true
                );
            }

            boolean hasPasscode = SharedConfig.passcodeHash.length() > 0;
            boolean flagged = ChatLockController.isFlagged(account, dialogId);
            if (hasPasscode) {
                lockCell.setTextAndCheck(LocaleController.getString(R.string.ChatPrivacyRequirePassword), flagged, false);
                lockCell.setEnabled(true);
            } else if (flagged) {
                lockCell.setTextAndValueAndCheck(
                        LocaleController.getString(R.string.ChatPrivacyRequirePassword),
                        LocaleController.getString(R.string.ChatPrivacyPasscodeMissingLocked),
                        true,
                        true,
                        false
                );
                lockCell.setEnabled(true);
            } else {
                lockCell.setTextAndValueAndCheck(
                        LocaleController.getString(R.string.ChatPrivacyRequirePassword),
                        LocaleController.getString(R.string.ChatPrivacyPasscodeRequired),
                        false,
                        true,
                        false
                );
                lockCell.setEnabled(false);
            }
            placeholderCell.setEnabled(hidden);

            boolean disguised = NotificationCoverController.isCovered(account, dialogId);
            disguiseCell.setTextAndValueAndCheck(
                    LocaleController.getString(R.string.NaxCoverDisguiseTitle),
                    LocaleController.getString(R.string.NaxCoverDisguiseSubtitle),
                    disguised,
                    true,
                    true
            );
            coverCell.setVisibility(disguised ? View.VISIBLE : View.GONE);
            if (disguised) {
                coverCell.setTextAndValue(
                        LocaleController.getString(R.string.NaxCoverRowTitle),
                        NotificationCoverController.activePersonaLabel(account, dialogId),
                        true
                );
            }
        };

        hideCell.setOnClickListener(v -> {
            boolean nowHidden = !HideLastMessageController.isHidden(account, dialogId);
            HideLastMessageController.setHidden(account, dialogId, nowHidden, null);
            refreshRef[0].run();
        });

        placeholderCell.setOnClickListener(v -> {
            if (!HideLastMessageController.isHidden(account, dialogId)) {
                return;
            }
            HideLastMessageDialog.showPlaceholderEditor(fragment, dialogId, refreshRef[0]);
        });

        lockCell.setOnClickListener(v -> {
            boolean hasPasscode = SharedConfig.passcodeHash.length() > 0;
            boolean flagged = ChatLockController.isFlagged(account, dialogId);
            if (!hasPasscode && !flagged) {
                return;
            }
            boolean nowLocked = !flagged;
            if (!hasPasscode) {
                nowLocked = false;
            }
            ChatLockController.setLocked(account, dialogId, nowLocked);
            if (nowLocked) {
                if (!HideLastMessageController.isHidden(account, dialogId)) {
                    HideLastMessageController.setHidden(account, dialogId, true, null);
                }
                if (sheetRef[0] != null) {
                    BulletinFactory.of(sheetRef[0].container, fragment.getResourceProvider())
                            .createSimpleBulletin(R.raw.passcode_lock, LocaleController.getString(R.string.ChatLockEnabledHint)).show();
                }
            }
            refreshRef[0].run();
        });

        disguiseCell.setOnClickListener(v -> {
            boolean nowDisguised = !NotificationCoverController.isCovered(account, dialogId);
            NotificationCoverController.setEnabled(account, dialogId, nowDisguised);
            android.util.Log.i("NAX_SMOKE", "NAX_SMOKE_customized-privacy-cover-engine disguise toggled on=" + nowDisguised);
            NotificationsController.getInstance(account).showNotifications();
            if (sheetRef[0] != null) {
                BulletinFactory.of(sheetRef[0].container, fragment.getResourceProvider())
                        .createSimpleBulletin(
                                nowDisguised ? R.raw.silent_mute : R.raw.silent_unmute,
                                LocaleController.getString(nowDisguised ? R.string.NaxCoverEnabledHint : R.string.NaxCoverDisabledHint)
                        ).show();
            }
            refreshRef[0].run();
        });

        coverCell.setOnClickListener(v -> {
            if (!NotificationCoverController.isCovered(account, dialogId)) {
                return;
            }
            showCoverPicker(fragment, account, dialogId, refreshRef[0]);
        });

        refreshRef[0].run();

        builder.setCustomView(container);
        sheetRef[0] = builder.create();
        fragment.showDialog(sheetRef[0]);
    }

    private static void showCoverPicker(BaseFragment fragment, int account, long dialogId, Runnable refresh) {
        if (fragment.getParentActivity() == null) {
            return;
        }
        int[] ids = NotificationCoverController.personaIds();
        int active = NotificationCoverController.resolvePersonaId(account, dialogId);
        ArrayList<String> entries = new ArrayList<>(ids.length);
        int checked = 0;
        for (int i = 0; i < ids.length; i++) {
            entries.add(NotificationCoverController.personaLabel(ids[i]));
            if (ids[i] == active) {
                checked = i;
            }
        }
        PopupHelper.show(
                entries,
                LocaleController.getString(R.string.NaxCoverSheetTitle),
                checked,
                fragment.getParentActivity(),
                which -> {
                    NotificationCoverController.setPersona(account, dialogId, ids[which]);
                    NotificationsController.getInstance(account).showNotifications();
                    refresh.run();
                },
                fragment.getResourceProvider()
        );
    }
}
