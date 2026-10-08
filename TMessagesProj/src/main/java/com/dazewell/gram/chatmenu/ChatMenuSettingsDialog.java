package com.dazewell.gram.chatmenu;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.TextCell;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import tw.nekomimi.nekogram.config.ConfigItem;
import xyz.nextalone.nagram.NaConfig;

import static org.telegram.messenger.LocaleController.getString;

// NagramX: N-Settings > Chats > Chat menu. Two sections mirror the chat screen - header buttons and "..." menu
// items - each listed in the order the chat shows them and draggable within its own section.
public final class ChatMenuSettingsDialog {

    private static final int TYPE_HEADER = 0;
    private static final int TYPE_ITEM = 1;

    private record Row(String key, int section, CharSequence title, int icon, ConfigItem config) {
        boolean isHeader() {
            return config == null;
        }
    }

    private ChatMenuSettingsDialog() {
    }

    private static HashMap<String, Row> headerRows() {
        HashMap<String, Row> rows = new HashMap<>();
        rows.put(ChatMenuOrder.BELL, new Row(ChatMenuOrder.BELL, 0, getString(R.string.NaxNotifProfileBell), R.drawable.msg_unmute, NaConfig.INSTANCE.getChatMenuItemNotifProfileBell()));
        rows.put(ChatMenuOrder.CALL, new Row(ChatMenuOrder.CALL, 0, getString(R.string.Call), R.drawable.msg_callback, NaConfig.INSTANCE.getChatMenuItemCall()));
        return rows;
    }

    private static HashMap<String, Row> menuRows() {
        HashMap<String, Row> rows = new HashMap<>();
        put(rows, "admins", getString(R.string.ChannelAdministrators), R.drawable.msg_admins, NaConfig.INSTANCE.getShortcutsAdministrators());
        put(rows, "recent", getString(R.string.EventLog), R.drawable.msg_log, NaConfig.INSTANCE.getShortcutsRecentActions());
        put(rows, "stats", getString(R.string.Statistics), R.drawable.msg_stats, NaConfig.INSTANCE.getShortcutsStatistics());
        put(rows, "permissions", getString(R.string.ChannelPermissions), R.drawable.msg_permissions, NaConfig.INSTANCE.getShortcutsPermissions());
        put(rows, "members", getString(R.string.GroupMembers), R.drawable.msg_groups, NaConfig.INSTANCE.getShortcutsMembers());
        put(rows, "boost", getString(R.string.BoostingBoostGroupMenu), R.drawable.boost_channel_solar, NaConfig.INSTANCE.getChatMenuItemBoostGroup());
        put(rows, "linked", getString(R.string.LinkedGroupChat), R.drawable.msg_discussion, NaConfig.INSTANCE.getChatMenuItemLinkedChat());
        put(rows, "beginning", getString(R.string.ToTheBeginning), R.drawable.ic_upward, NaConfig.INSTANCE.getChatMenuItemToBeginning());
        put(rows, "gotomsg", getString(R.string.ToTheMessage), R.drawable.msg_go_up, NaConfig.INSTANCE.getChatMenuItemGoToMessage());
        put(rows, "hidetitle", getString(R.string.HideTitle), R.drawable.hide_title, NaConfig.INSTANCE.getChatMenuItemHideTitle());
        put(rows, "viewdeleted", getString(R.string.ViewDeleted), R.drawable.msg_view_file, NaConfig.INSTANCE.getChatMenuItemViewDeleted());
        put(rows, "cleardeleted", getString(R.string.ClearDeleted), R.drawable.msg_clear, NaConfig.INSTANCE.getChatMenuItemClearDeleted());
        put(rows, "deleteown", getString(R.string.DeleteAllFromSelf), R.drawable.msg_delete, NaConfig.INSTANCE.getChatMenuItemDeleteOwnMessages());
        return rows;
    }

    private static void put(HashMap<String, Row> rows, String key, CharSequence title, int icon, ConfigItem config) {
        rows.put(key, new Row(key, 1, title, icon, config));
    }

    private static void fill(ArrayList<Row> out, List<String> headerOrder, List<String> menuOrder) {
        out.clear();
        HashMap<String, Row> header = headerRows();
        HashMap<String, Row> menu = menuRows();
        out.add(new Row(null, 0, getString(R.string.ChatMenuHeaderSection), 0, null));
        for (String key : headerOrder) out.add(header.get(key));
        out.add(new Row(null, 1, getString(R.string.ChatMenuMoreSection), 0, null));
        for (String key : menuOrder) out.add(menu.get(key));
    }

    private static List<String> keys(ArrayList<Row> rows, int section) {
        ArrayList<String> keys = new ArrayList<>();
        for (Row row : rows) {
            if (!row.isHeader() && row.section == section) keys.add(row.key);
        }
        return keys;
    }

    public static void show(BaseFragment fragment) {
        Context context = fragment.getParentActivity();
        if (context == null) return;
        Theme.ResourcesProvider resourcesProvider = fragment.getResourceProvider();
        ArrayList<Row> rows = new ArrayList<>();
        fill(rows, ChatMenuOrder.headerOrder(), ChatMenuOrder.menuOrder());

        RecyclerView listView = new RecyclerView(context);
        listView.setLayoutManager(new LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false));
        RecyclerView.Adapter<RecyclerView.ViewHolder> adapter = new RecyclerView.Adapter<>() {
            @Override
            public int getItemViewType(int position) {
                return rows.get(position).isHeader() ? TYPE_HEADER : TYPE_ITEM;
            }

            @NonNull
            @Override
            public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
                View view;
                if (viewType == TYPE_HEADER) {
                    view = new HeaderCell(context, resourcesProvider);
                } else {
                    view = new TextCell(context, 23, false, true, resourcesProvider);
                    view.setBackground(Theme.getSelectorDrawable(false));
                }
                view.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                return new RecyclerView.ViewHolder(view) {
                };
            }

            @Override
            public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
                Row row = rows.get(position);
                if (row.isHeader()) {
                    ((HeaderCell) holder.itemView).setText(row.title);
                    return;
                }
                TextCell cell = (TextCell) holder.itemView;
                cell.setTextAndCheckAndIcon(row.title, row.config.Bool(), row.icon, false);
                cell.setOnClickListener(v -> {
                    int pos = holder.getAdapterPosition();
                    if (pos < 0 || pos >= rows.size() || rows.get(pos).isHeader()) return;
                    cell.setChecked(rows.get(pos).config.toggleConfigBool());
                    NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.reloadInterface);
                });
            }

            @Override
            public int getItemCount() {
                return rows.size();
            }
        };
        listView.setAdapter(adapter);

        new ItemTouchHelper(new ItemTouchHelper.Callback() {
            @Override
            public int getMovementFlags(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder) {
                return viewHolder.getItemViewType() == TYPE_ITEM ? makeMovementFlags(ItemTouchHelper.UP | ItemTouchHelper.DOWN, 0) : 0;
            }

            // A row only moves within its own section; section titles are never a drop target.
            @Override
            public boolean canDropOver(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder current, @NonNull RecyclerView.ViewHolder target) {
                int from = current.getAdapterPosition();
                int to = target.getAdapterPosition();
                if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION) return false;
                Row a = rows.get(from);
                Row b = rows.get(to);
                return !b.isHeader() && a.section == b.section;
            }

            @Override
            public boolean onMove(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder, @NonNull RecyclerView.ViewHolder target) {
                int from = viewHolder.getAdapterPosition();
                int to = target.getAdapterPosition();
                if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION) return false;
                rows.add(to, rows.remove(from));
                adapter.notifyItemMoved(from, to);
                return true;
            }

            @Override
            public void onSwiped(@NonNull RecyclerView.ViewHolder viewHolder, int direction) {
            }
        }).attachToRecyclerView(listView);

        AlertDialog.Builder builder = new AlertDialog.Builder(context, resourcesProvider);
        builder.setTitle(getString(R.string.ChatMenu));
        builder.setView(listView);
        builder.setPositiveButton(getString(R.string.OK), (d, which) -> {
            NaConfig.INSTANCE.getChatHeaderOrder().setConfigString(ChatMenuOrder.join(keys(rows, 0)));
            NaConfig.INSTANCE.getChatMenuOrder().setConfigString(ChatMenuOrder.join(keys(rows, 1)));
            NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.reloadInterface);
        });
        builder.setNegativeButton(getString(R.string.Cancel), null);
        // Reset restores the order only; the toggles keep their state, as in Text style.
        builder.setNeutralButton(getString(R.string.Reset), (d, which) -> {
            NaConfig.INSTANCE.getChatHeaderOrder().setConfigString("");
            NaConfig.INSTANCE.getChatMenuOrder().setConfigString("");
            NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.reloadInterface);
        });
        fragment.showDialog(builder.create());
    }
}
