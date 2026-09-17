package xyz.nextalone.nagram.ui;

import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.view.View;

import org.telegram.messenger.R;
import org.telegram.ui.Components.RecyclerListView;

import tw.nekomimi.nekogram.config.CellGroup;
import tw.nekomimi.nekogram.config.cell.AbstractConfigCell;
import tw.nekomimi.nekogram.config.cell.ConfigCellDivider;
import tw.nekomimi.nekogram.config.cell.ConfigCellHeader;
import tw.nekomimi.nekogram.config.cell.ConfigCellTextCheck;
import tw.nekomimi.nekogram.settings.BaseNekoXSettingsActivity;
import xyz.nextalone.nagram.NaConfig;
import xyz.nextalone.nagram.RememberedSendAction;

/**
 * Chat Settings -> Remembered send action. A master switch at the top turns the whole feature on
 * or off; four app-wide toggles below it cover whether long-pressing Send and picking silent /
 * send-when-online / schedule arms it for the next send, one switch per action, plus whether
 * the armed action resets when you leave the chat. All default enabled. Turning an action switch
 * off doesn't hide that row from the long-press menu or stop it sending normally -- it just stops
 * that pick from being remembered afterwards. Turning the master switch off disables (not hides)
 * the four rows below it, same as they read everywhere else the feature checks: master-off always
 * wins over an individual toggle still being on.
 */
public class RememberedSendActionSettingsActivity extends BaseNekoXSettingsActivity {

    private ListAdapter listAdapter;

    @Override
    protected RecyclerListView.SelectionAdapter getListAdapter() {
        return listAdapter;
    }

    @Override
    protected CellGroup getCellGroup() {
        return cellGroup;
    }

    private final CellGroup cellGroup = new CellGroup(this);

    private final ConfigCellTextCheck masterRow = (ConfigCellTextCheck) cellGroup.appendCell(new ConfigCellTextCheck(NaConfig.INSTANCE.getRememberSendActionMaster(), getString(R.string.RememberedSendActionNotice), getString(R.string.RememberSendActionMaster)));
    // NagramX: short blue section header, same as any other CellGroup section split -- just labels the
    // three action toggles below it as a group, distinct from the master row above and the reset row below.
    private final AbstractConfigCell headerWhatGetsRemembered = cellGroup.appendCell(new ConfigCellHeader(getString(R.string.RememberSendActionWhatGetsRemembered)));
    private final ConfigCellTextCheck silentRow = (ConfigCellTextCheck) cellGroup.appendCell(new ConfigCellTextCheck(NaConfig.INSTANCE.getRememberSendActionSilent(), null, getString(R.string.SendWithoutSound)));
    private final ConfigCellTextCheck sendWhenOnlineRow = (ConfigCellTextCheck) cellGroup.appendCell(new ConfigCellTextCheck(NaConfig.INSTANCE.getRememberSendActionSendWhenOnline(), null, getString(R.string.SendWhenOnline)));
    private final ConfigCellTextCheck scheduleRow = (ConfigCellTextCheck) cellGroup.appendCell(new ConfigCellTextCheck(NaConfig.INSTANCE.getRememberSendActionSchedule(), null, getString(R.string.ScheduleMessage)));
    private final ConfigCellTextCheck resetOnLeaveRow = (ConfigCellTextCheck) cellGroup.appendCell(new ConfigCellTextCheck(NaConfig.INSTANCE.getRememberSendActionResetOnLeave(), getString(R.string.RememberSendActionResetOnLeaveNotice), getString(R.string.RememberSendActionResetOnLeave)));
    // NagramX: CellGroup.needSetDivider() always peeks one row past the current one, so the last row
    // in the group needs a divider appended after it or binding indexes off the end of the list.
    private final AbstractConfigCell dividerReset = cellGroup.appendCell(new ConfigCellDivider());

    public RememberedSendActionSettingsActivity() {
        addRowsToMap(cellGroup);
    }

    @Override
    public View createView(Context context) {
        View superView = super.createView(context);

        listAdapter = new ListAdapter(context);
        listView.setAdapter(listAdapter);

        setupDefaultListeners();

        // NagramX (#remember-send-action): reflect the master switch on the four rows it governs, both
        // on first open and on every later toggle -- disabled, not removed, so re-enabling the master
        // doesn't need the four rows re-inserted or their own state re-taught.
        updateChildRowsEnabled();
        cellGroup.callBackSettingsChanged = (key, newValue) -> {
            boolean value = newValue instanceof Boolean && (Boolean) newValue;
            if (key.equals(masterRow.getKey())) {
                updateChildRowsEnabled();
                // NagramX (#remember-send-action): both long-press menus' own master handlers disarm
                // the slot immediately on the off transition -- this page must match, or turning the
                // master off here and back on before returning to the chat leaves the original armed
                // action untouched and it replays as if nothing happened. The master config is global,
                // not scoped to the currently viewed account, so every account's slot needs clearing.
                if (!value) {
                    RememberedSendAction.disarmAll();
                }
            } else if (!value) {
                RememberedSendAction.disarmAllIfArmed(childActionForKey(key));
            }
        };

        return superView;
    }

    private void updateChildRowsEnabled() {
        boolean on = NaConfig.INSTANCE.getRememberSendActionMaster().Bool();
        silentRow.setEnabled(on);
        sendWhenOnlineRow.setEnabled(on);
        scheduleRow.setEnabled(on);
        resetOnLeaveRow.setEnabled(on);
        // NagramX (#remember-send-action): ConfigCellTextCheck.setEnabled(boolean) only flips the flag
        // -- the dimming itself lives in the two-arg overload that only onBindViewHolder calls, so an
        // in-place master toggle left these four rows at full opacity while silently no-op'ing their
        // taps until an unrelated adapter refresh happened to rebind them. Rebind explicitly instead of
        // waiting for that.
        if (listAdapter != null) {
            notifyChildRowChanged(silentRow);
            notifyChildRowChanged(sendWhenOnlineRow);
            notifyChildRowChanged(scheduleRow);
            notifyChildRowChanged(resetOnLeaveRow);
        }
    }

    private void notifyChildRowChanged(AbstractConfigCell row) {
        int index = cellGroup.rows.indexOf(row);
        if (index >= 0) {
            listAdapter.notifyItemChanged(index);
        }
    }

    // NagramX (#remember-send-action): maps a child toggle's own key to the armed-action constant it
    // governs, or NONE for a key that isn't one of the three action toggles (reset-on-leave doesn't
    // arm anything itself).
    private int childActionForKey(String key) {
        if (key.equals(silentRow.getKey())) return RememberedSendAction.SILENT;
        if (key.equals(sendWhenOnlineRow.getKey())) return RememberedSendAction.SEND_WHEN_ONLINE;
        if (key.equals(scheduleRow.getKey())) return RememberedSendAction.SCHEDULE;
        return RememberedSendAction.NONE;
    }

    @Override
    public String getTitle() {
        return getString(R.string.RememberedSendAction);
    }

    private class ListAdapter extends BaseListAdapter {
        public ListAdapter(Context context) {
            super(context);
        }
    }
}
