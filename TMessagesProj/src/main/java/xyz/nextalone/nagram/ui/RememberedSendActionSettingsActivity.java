package xyz.nextalone.nagram.ui;

import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.view.View;

import org.telegram.messenger.R;
import org.telegram.ui.Components.RecyclerListView;

import tw.nekomimi.nekogram.config.CellGroup;
import tw.nekomimi.nekogram.config.cell.AbstractConfigCell;
import tw.nekomimi.nekogram.config.cell.ConfigCellHeader;
import tw.nekomimi.nekogram.config.cell.ConfigCellTextCheck;
import tw.nekomimi.nekogram.settings.BaseNekoXSettingsActivity;
import xyz.nextalone.nagram.NaConfig;

/**
 * Chat Settings -> Remembered send action. Four app-wide toggles: whether long-pressing Send and
 * picking silent / send-when-online / schedule arms it for the next plain tap, one switch per
 * action, plus whether the armed action resets when you leave the chat. All default enabled.
 * Turning an action switch off doesn't hide that row from the long-press menu or stop it sending
 * normally -- it just stops that pick from being remembered afterwards.
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

    private final AbstractConfigCell headerActions = cellGroup.appendCell(new ConfigCellHeader(getString(R.string.RememberedSendActionNotice)));
    private final AbstractConfigCell silentRow = cellGroup.appendCell(new ConfigCellTextCheck(NaConfig.INSTANCE.getRememberSendActionSilent(), null, getString(R.string.RememberSendActionSilent)));
    private final AbstractConfigCell sendWhenOnlineRow = cellGroup.appendCell(new ConfigCellTextCheck(NaConfig.INSTANCE.getRememberSendActionSendWhenOnline(), null, getString(R.string.RememberSendActionSendWhenOnline)));
    private final AbstractConfigCell scheduleRow = cellGroup.appendCell(new ConfigCellTextCheck(NaConfig.INSTANCE.getRememberSendActionSchedule(), null, getString(R.string.RememberSendActionSchedule)));
    private final AbstractConfigCell headerReset = cellGroup.appendCell(new ConfigCellHeader(""));
    private final AbstractConfigCell resetOnLeaveRow = cellGroup.appendCell(new ConfigCellTextCheck(NaConfig.INSTANCE.getRememberSendActionResetOnLeave(), getString(R.string.RememberSendActionResetOnLeaveNotice), getString(R.string.RememberSendActionResetOnLeave)));

    public RememberedSendActionSettingsActivity() {
        addRowsToMap(cellGroup);
    }

    @Override
    public View createView(Context context) {
        View superView = super.createView(context);

        listAdapter = new ListAdapter(context);
        listView.setAdapter(listAdapter);

        setupDefaultListeners();

        return superView;
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
