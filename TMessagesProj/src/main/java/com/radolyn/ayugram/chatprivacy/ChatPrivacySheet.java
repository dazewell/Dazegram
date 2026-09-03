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
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Cells.TextSettingsCell;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.LayoutHelper;

public final class ChatPrivacySheet {

    private ChatPrivacySheet() {}

    public static void show(BaseFragment fragment, long dialogId) {
        if (fragment == null || fragment.getParentActivity() == null || dialogId == 0) {
            return;
        }
        final int account = fragment.getCurrentAccount();
        final Context context = fragment.getParentActivity();

        BottomSheet.Builder builder = new BottomSheet.Builder(context, false, fragment.getResourceProvider());
        builder.setApplyBottomPadding(false);

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
                BulletinFactory.of(fragment).createSimpleBulletin(R.raw.passcode_lock, LocaleController.getString(R.string.ChatLockEnabledHint)).show();
            }
            refreshRef[0].run();
        });

        refreshRef[0].run();

        builder.setCustomView(container);
        fragment.showDialog(builder.create());
    }
}
