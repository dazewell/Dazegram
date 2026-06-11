package tw.nekomimi.nekogram.config.cell;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.os.Bundle;
import android.view.accessibility.AccessibilityNodeInfo;

import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.Cells.NotificationsCheckCell;

import tw.nekomimi.nekogram.config.CellGroup;
import tw.nekomimi.nekogram.config.ConfigItem;

/**
 * A bool config row split in two like the notification settings rows:
 * tapping the switch toggles the config, tapping the text opens a page.
 */
public class ConfigCellTextCheckPage extends AbstractConfigCell implements WithBindConfig, WithKey {
    private final ConfigItem bindConfig;
    private final CharSequence title;
    private final CharSequence subtitle;
    private final Runnable onTextClick;
    private boolean enabled = true;
    public Cell cell;

    /**
     * The row view. The node is announced as a Switch, so an accessibility click must
     * toggle rather than fall through the x-coordinate split (which sees x=0 and would
     * open the page); the page stays reachable through a custom accessibility action.
     */
    public static class Cell extends NotificationsCheckCell {
        private ConfigCellTextCheckPage owner;

        public Cell(Context context) {
            super(context);
        }

        @Override
        public void setEnabled(boolean value) {
            super.setEnabled(value);
            for (int i = 0; i < getChildCount(); i++) {
                getChildAt(i).setAlpha(value ? 1.0f : 0.5f);
            }
        }

        @Override
        public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
            super.onInitializeAccessibilityNodeInfo(info);
            if (owner != null && owner.onTextClick != null) {
                info.addAction(new AccessibilityNodeInfo.AccessibilityAction(R.id.acc_action_open_page, getString(R.string.Open)));
            }
        }

        @Override
        public boolean performAccessibilityAction(int action, Bundle arguments) {
            if (owner != null && owner.enabled) {
                if (action == AccessibilityNodeInfo.ACTION_CLICK) {
                    owner.toggle(this);
                    return true;
                }
                if (action == R.id.acc_action_open_page && owner.onTextClick != null) {
                    owner.onTextClick.run();
                    return true;
                }
            }
            return super.performAccessibilityAction(action, arguments);
        }
    }

    public ConfigCellTextCheckPage(ConfigItem bind, CharSequence subtitle, Runnable onTextClick) {
        this.bindConfig = bind;
        this.title = getString(bind.getKey());
        this.subtitle = subtitle;
        this.onTextClick = onTextClick;
    }

    public int getType() {
        return CellGroup.ITEM_TYPE_TEXT_CHECK_PAGE;
    }

    public ConfigItem getBindConfig() {
        return bindConfig;
    }

    public String getKey() {
        return bindConfig == null ? null : bindConfig.getKey();
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        if (this.cell != null) {
            this.cell.setEnabled(this.enabled);
        }
    }

    public void onBindViewHolder(RecyclerView.ViewHolder holder) {
        Cell cell = (Cell) holder.itemView;
        this.cell = cell;
        cell.owner = this;
        cell.setTextAndValueAndCheck(title, subtitle, bindConfig.Bool(), cellGroup.needSetDivider(this));
        cell.setEnabled(enabled);
    }

    public void onClick(NotificationsCheckCell cell, float x) {
        if (!enabled) return;

        boolean tappedSwitch = LocaleController.isRTL ? x <= dp(76) : x >= cell.getMeasuredWidth() - dp(76);
        if (tappedSwitch || onTextClick == null) {
            toggle(cell);
        } else {
            onTextClick.run();
        }
    }

    private void toggle(NotificationsCheckCell cell) {
        boolean newV = bindConfig.toggleConfigBool();
        cell.setChecked(newV);
        cellGroup.runCallback(bindConfig.getKey(), newV);
    }
}
