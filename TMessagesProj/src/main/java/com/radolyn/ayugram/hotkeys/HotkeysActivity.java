package com.radolyn.ayugram.hotkeys;

import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Cells.TextSettingsCell;

import java.util.ArrayList;

import tw.nekomimi.nekogram.settings.BaseNekoSettingsActivity;
import tw.nekomimi.nekogram.ui.cells.HeaderCell;

/**
 * Read-only reference page for the physical keyboard hotkeys, built from
 * {@link HotkeyController#getBindingTable()}.
 */
public class HotkeysActivity extends BaseNekoSettingsActivity {

    private record Item(int type, String text, String keys) {
    }

    private final ArrayList<Item> items = new ArrayList<>();

    @Override
    protected void updateRows() {
        super.updateRows();
        items.clear();
        for (HotkeyController.Section section : HotkeyController.getBindingTable()) {
            items.add(new Item(TYPE_HEADER, getString(section.titleRes()), null));
            for (HotkeyController.Binding binding : section.bindings()) {
                items.add(new Item(TYPE_SETTINGS, getString(binding.labelRes()), binding.keys()));
            }
            items.add(new Item(TYPE_INFO_PRIVACY, getString(section.noticeRes()), null));
        }
    }

    @Override
    protected void onItemClick(View view, int position, float x, float y) {
    }

    @Override
    protected String getActionBarTitle() {
        return getString(R.string.PhysicalKeyboardHotkeys);
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
        public int getItemCount() {
            return items.size();
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            return false;
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position, boolean partial) {
            Item item = items.get(position);
            switch (holder.getItemViewType()) {
                case TYPE_HEADER:
                    ((HeaderCell) holder.itemView).setText(item.text);
                    break;
                case TYPE_SETTINGS:
                    boolean divider = position + 1 < items.size() && items.get(position + 1).type == TYPE_SETTINGS;
                    ((TextSettingsCell) holder.itemView).setTextAndValue(item.text, item.keys, divider);
                    break;
                case TYPE_INFO_PRIVACY:
                    TextInfoPrivacyCell cell = (TextInfoPrivacyCell) holder.itemView;
                    cell.setBackground(Theme.getThemedDrawable(mContext, R.drawable.greydivider, Theme.key_windowBackgroundGrayShadow));
                    cell.setText(item.text);
                    break;
            }
        }

        @Override
        public int getItemViewType(int position) {
            return items.get(position).type;
        }
    }
}
