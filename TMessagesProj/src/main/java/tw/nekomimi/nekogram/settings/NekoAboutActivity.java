package tw.nekomimi.nekogram.settings;

import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.R;
import org.telegram.messenger.browser.Browser;
import org.telegram.ui.Cells.TextSettingsCell;

import tw.nekomimi.nekogram.DatacenterActivity;

public class NekoAboutActivity extends BaseNekoSettingsActivity {

    private int sourceCodeRow;
    private int translationRow;
    private int datacenterStatusRow;
    private int acknowledgmentsRow;

    @Override
    protected void updateRows() {
        super.updateRows();

        sourceCodeRow = addRow();
        translationRow = addRow();
        datacenterStatusRow = addRow();
        acknowledgmentsRow = addRow();
    }

    @Override
    protected String getActionBarTitle() {
        return getString(R.string.About);
    }

    @Override
    protected void onItemClick(View view, int position, float x, float y) {
        if (position == translationRow) {
            Browser.openUrl(getParentActivity(), "https://crowdin.com/project/NagramX");
        } else if (position == sourceCodeRow) {
            Browser.openUrl(getParentActivity(), "https://github.com/dazewell/NagramX");
        } else if (position == datacenterStatusRow) {
            presentFragment(new DatacenterActivity(0));
        } else if (position == acknowledgmentsRow) {
            // NagramX: credits the upstream project this fork is built on, not this app itself
            Browser.openUrl(getParentActivity(), "https://github.com/risin42/NagramX");
        }
    }

    @Override
    protected BaseListAdapter createAdapter(Context context) {
        return new ListAdapter(context);
    }

    private class ListAdapter extends BaseListAdapter {

        public ListAdapter(Context context) {
            super(context);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position, boolean partial) {
            if (holder.getItemViewType() == TYPE_SETTINGS) {
                TextSettingsCell textCell = (TextSettingsCell) holder.itemView;
                if (position == sourceCodeRow) {
                    textCell.setTextAndValue(getString(R.string.SourceCode), "Github", true);
                } else if (position == translationRow) {
                    textCell.setTextAndValue(getString(R.string.TransSite), "Crowdin", true);
                } else if (position == datacenterStatusRow) {
                    textCell.setText(getString(R.string.DatacenterStatus), true);
                } else if (position == acknowledgmentsRow) {
                    textCell.setTextAndValue(getString(R.string.Acknowledgments), "NagramX", false);
                }
            }
        }

        @Override
        public int getItemViewType(int position) {
            return TYPE_SETTINGS;
        }
    }
}
