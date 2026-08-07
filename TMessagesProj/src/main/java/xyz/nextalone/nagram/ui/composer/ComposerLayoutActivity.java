package xyz.nextalone.nagram.ui.composer;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.SimpleTextView;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ActionBar.ThemeDescription;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

import java.util.ArrayList;
import java.util.List;

/**
 * Lets the user place each composer toolbar button into a zone and order it, by dragging rows
 * between four sections. Config only: it never touches a live enter view, it reads and writes the
 * layout through {@link ComposerLayout} and asks the toolbar to rebuild by posting reloadInterface.
 */
public class ComposerLayoutActivity extends BaseFragment {

    private static final int TYPE_PREVIEW = 0;
    private static final int TYPE_HEADER = 1;
    private static final int TYPE_BUTTON = 2;
    private static final int TYPE_PLACEHOLDER = 3;
    private static final int TYPE_INFO = 4;

    private static final int reset_id = 1;

    private static final int[] ZONE_ORDER = {
            ComposerButtons.ZONE_START,
            ComposerButtons.ZONE_MIDDLE,
            ComposerButtons.ZONE_END,
            ComposerButtons.ZONE_HIDDEN,
    };

    private RecyclerListView listView;
    private ListAdapter adapter;
    private ItemTouchHelper itemTouchHelper;

    private final ArrayList<Item> items = new ArrayList<>();
    private List<List<String>> lastSaved;
    private boolean rebuildPending;

    private static final class Item {
        final int type;
        final int zone;
        final String key;

        Item(int type, int zone, String key) {
            this.type = type;
            this.zone = zone;
            this.key = key;
        }
    }

    @Override
    public boolean onFragmentCreate() {
        lastSaved = ComposerLayout.snapshot();
        buildItems(lastSaved);
        return super.onFragmentCreate();
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(LocaleController.getString(R.string.ComposerLayoutTitle));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                } else if (id == reset_id) {
                    resetLayout();
                }
            }
        });

        ActionBarMenuItem other = actionBar.createMenu().addItem(0, R.drawable.ic_ab_other);
        other.addSubItem(reset_id, R.drawable.msg_reset_solar, LocaleController.getString(R.string.ComposerLayoutReset));

        FrameLayout frameLayout = new FrameLayout(context);
        fragmentView = frameLayout;
        frameLayout.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));

        listView = new RecyclerListView(context);
        listView.setLayoutManager(new LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false));
        listView.setVerticalScrollBarEnabled(false);
        itemTouchHelper = new ItemTouchHelper(new TouchHelperCallback());
        itemTouchHelper.attachToRecyclerView(listView);
        frameLayout.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        listView.setAdapter(adapter = new ListAdapter(context));

        return fragmentView;
    }

    @Override
    public void onPause() {
        super.onPause();
        persist();
        flushRebuild();
    }

    @Override
    public void onFragmentDestroy() {
        persist();
        flushRebuild();
        super.onFragmentDestroy();
    }

    private void buildItems(List<List<String>> zones) {
        items.clear();
        items.add(new Item(TYPE_PREVIEW, -1, null));
        for (int zone : ZONE_ORDER) {
            items.add(new Item(TYPE_HEADER, zone, null));
            List<String> keys = zones.get(zone);
            if (keys.isEmpty()) {
                items.add(new Item(TYPE_PLACEHOLDER, zone, null));
            } else {
                for (String key : keys) {
                    items.add(new Item(TYPE_BUTTON, zone, key));
                }
            }
            if (zone == ComposerButtons.ZONE_START) {
                items.add(new Item(TYPE_INFO, ComposerButtons.ZONE_START, null));
            }
        }
        items.add(new Item(TYPE_INFO, -1, null));
    }

    /** Rebuilds the four zone lists from the current flat order, taking each button's zone from the
     * header above it so the model always matches what the user sees. */
    private List<List<String>> collect() {
        List<List<String>> zones = new ArrayList<>(ComposerButtons.ZONE_COUNT);
        for (int i = 0; i < ComposerButtons.ZONE_COUNT; i++) {
            zones.add(new ArrayList<>());
        }
        for (int i = 0; i < items.size(); i++) {
            Item item = items.get(i);
            if (item.type == TYPE_BUTTON) {
                int zone = zoneAt(i);
                if (zone >= 0) {
                    zones.get(zone).add(item.key);
                }
            }
        }
        return zones;
    }

    private int zoneAt(int position) {
        for (int i = position; i >= 0; i--) {
            if (items.get(i).type == TYPE_HEADER) {
                return items.get(i).zone;
            }
        }
        return -1;
    }

    private int buttonCountInZone(int zone, int exclude) {
        int count = 0;
        for (int i = 0; i < items.size(); i++) {
            if (i != exclude && items.get(i).type == TYPE_BUTTON && zoneAt(i) == zone) {
                count++;
            }
        }
        return count;
    }

    private void persist() {
        List<List<String>> current = ComposerLayout.normalize(collect());
        if (current.equals(lastSaved)) {
            return;
        }
        ComposerLayout.save(current);
        lastSaved = current;
        // The stored layout is the toolbar's only input, so it is written on every drop; the rebuild
        // that picks it up is deferred to leaving the screen, since rebuilding every chat behind the
        // editor after each micro-drag buys nothing the preview row isn't already showing.
        rebuildPending = true;
    }

    private void flushRebuild() {
        if (!rebuildPending) {
            return;
        }
        rebuildPending = false;
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.reloadInterface);
    }

    private void resetLayout() {
        ComposerLayout.reset();
        lastSaved = ComposerLayout.snapshot();
        buildItems(lastSaved);
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
        rebuildPending = true;
    }

    private class ListAdapter extends RecyclerListView.SelectionAdapter {

        private final Context context;

        ListAdapter(Context context) {
            this.context = context;
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            return false;
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        @Override
        public int getItemViewType(int position) {
            return items.get(position).type;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view;
            switch (viewType) {
                case TYPE_PREVIEW:
                    view = new PreviewCell(context);
                    break;
                case TYPE_HEADER:
                    view = new HeaderCell(context);
                    view.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
                    break;
                case TYPE_PLACEHOLDER:
                    view = new PlaceholderCell(context);
                    view.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
                    break;
                case TYPE_INFO:
                    view = new TextInfoPrivacyCell(context);
                    break;
                default:
                    ButtonRowCell cell = new ButtonRowCell(context);
                    cell.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
                    cell.reorderView.setOnTouchListener((v, event) -> {
                        if (event.getAction() == MotionEvent.ACTION_DOWN) {
                            itemTouchHelper.startDrag(listView.getChildViewHolder(cell));
                        }
                        return false;
                    });
                    view = cell;
                    break;
            }
            return new RecyclerListView.Holder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            Item item = items.get(position);
            switch (item.type) {
                case TYPE_PREVIEW:
                    ((PreviewCell) holder.itemView).setLayout(collect());
                    break;
                case TYPE_HEADER:
                    ((HeaderCell) holder.itemView).setText(headerTitle(item.zone));
                    break;
                case TYPE_PLACEHOLDER:
                    ((PlaceholderCell) holder.itemView).textView.setText(LocaleController.getString(R.string.ComposerZoneEmpty));
                    break;
                case TYPE_INFO:
                    int infoRes = item.zone == ComposerButtons.ZONE_START ? R.string.ComposerZoneLeadingInfo : R.string.ComposerLayoutInfo;
                    ((TextInfoPrivacyCell) holder.itemView).setText(LocaleController.getString(infoRes));
                    break;
                default:
                    ComposerButtons.Button button = ComposerButtons.get(item.key);
                    ((ButtonRowCell) holder.itemView).setButton(button, position + 1 < items.size() && items.get(position + 1).type == TYPE_BUTTON);
                    break;
            }
        }
    }

    private static CharSequence headerTitle(int zone) {
        switch (zone) {
            case ComposerButtons.ZONE_START:
                return LocaleController.getString(R.string.ComposerZoneLeading);
            case ComposerButtons.ZONE_MIDDLE:
                return LocaleController.getString(R.string.ComposerZoneScrolling);
            case ComposerButtons.ZONE_END:
                return LocaleController.getString(R.string.ComposerZoneTrailing);
            default:
                return LocaleController.getString(R.string.ComposerZoneHidden);
        }
    }

    private class TouchHelperCallback extends ItemTouchHelper.Callback {

        @Override
        public boolean isLongPressDragEnabled() {
            return false;
        }

        @Override
        public int getMovementFlags(RecyclerView recyclerView, RecyclerView.ViewHolder viewHolder) {
            if (viewHolder.getItemViewType() != TYPE_BUTTON) {
                return makeMovementFlags(0, 0);
            }
            return makeMovementFlags(ItemTouchHelper.UP | ItemTouchHelper.DOWN, 0);
        }

        @Override
        public boolean canDropOver(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder current, @NonNull RecyclerView.ViewHolder target) {
            int type = target.getItemViewType();
            return type == TYPE_BUTTON || type == TYPE_PLACEHOLDER;
        }

        @Override
        public boolean onMove(RecyclerView recyclerView, RecyclerView.ViewHolder source, RecyclerView.ViewHolder target) {
            int from = source.getAdapterPosition();
            int to = target.getAdapterPosition();
            if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION) {
                return false;
            }
            Item dragged = items.get(from);
            if (dragged.type != TYPE_BUTTON) {
                return false;
            }
            int targetType = items.get(to).type;
            if (targetType != TYPE_BUTTON && targetType != TYPE_PLACEHOLDER) {
                return false;
            }
            int targetZone = zoneAt(to);
            if (targetZone < 0) {
                return false;
            }
            ComposerButtons.Button button = ComposerButtons.get(dragged.key);
            if (button == null || !button.canSitIn(targetZone)) {
                return false;
            }
            if (targetZone == ComposerButtons.ZONE_START && buttonCountInZone(ComposerButtons.ZONE_START, from) >= ComposerButtons.START_CAPACITY) {
                return false;
            }
            items.add(to, items.remove(from));
            adapter.notifyItemMoved(from, to);
            return true;
        }

        @Override
        public void onSelectedChanged(RecyclerView.ViewHolder viewHolder, int actionState) {
            if (actionState != ItemTouchHelper.ACTION_STATE_IDLE) {
                listView.cancelClickRunnables(false);
                if (viewHolder != null) {
                    viewHolder.itemView.setPressed(true);
                }
            }
            super.onSelectedChanged(viewHolder, actionState);
        }

        @Override
        public void onSwiped(RecyclerView.ViewHolder viewHolder, int direction) {
        }

        @Override
        public void clearView(RecyclerView recyclerView, RecyclerView.ViewHolder viewHolder) {
            super.clearView(recyclerView, viewHolder);
            viewHolder.itemView.setPressed(false);
            // A cross-section move can empty the source zone or drop a button next to a stale
            // placeholder. Persist takes the flat order as truth, then a rebuild restores one
            // placeholder per empty zone and drops the redundant ones.
            persist();
            buildItems(lastSaved);
            adapter.notifyDataSetChanged();
        }
    }

    private static class PlaceholderCell extends FrameLayout {

        final TextView textView;

        PlaceholderCell(Context context) {
            super(context);
            textView = new TextView(context);
            textView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2));
            textView.setTextSize(15);
            textView.setGravity(Gravity.CENTER_VERTICAL | (LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT));
            addView(textView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.LEFT, 22, 0, 22, 0));
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(dp(48), MeasureSpec.EXACTLY));
        }
    }

    private static class ButtonRowCell extends FrameLayout {

        final ImageView iconView;
        final SimpleTextView titleView;
        final TextView subtitleView;
        final ImageView reorderView;
        private boolean needDivider;

        ButtonRowCell(Context context) {
            super(context);
            setWillNotDraw(false);

            boolean rtl = LocaleController.isRTL;

            iconView = new ImageView(context);
            iconView.setScaleType(ImageView.ScaleType.CENTER);
            iconView.setColorFilter(new PorterDuffColorFilter(Theme.getColor(Theme.key_windowBackgroundWhiteGrayIcon), PorterDuff.Mode.MULTIPLY));
            addView(iconView, LayoutHelper.createFrame(24, 24, (rtl ? Gravity.RIGHT : Gravity.LEFT) | Gravity.CENTER_VERTICAL, 20, 0, 20, 0));

            titleView = new SimpleTextView(context);
            titleView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            titleView.setTextSize(16);
            titleView.setMaxLines(1);
            titleView.setGravity((rtl ? Gravity.RIGHT : Gravity.LEFT) | Gravity.TOP);
            addView(titleView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, (rtl ? Gravity.RIGHT : Gravity.LEFT) | Gravity.TOP, 64, 8, 64, 0));

            subtitleView = new TextView(context);
            subtitleView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2));
            subtitleView.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 13);
            subtitleView.setLines(1);
            subtitleView.setMaxLines(1);
            subtitleView.setSingleLine(true);
            subtitleView.setEllipsize(android.text.TextUtils.TruncateAt.END);
            subtitleView.setGravity(rtl ? Gravity.RIGHT : Gravity.LEFT);
            addView(subtitleView, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, (rtl ? Gravity.RIGHT : Gravity.LEFT) | Gravity.TOP, 64, 32, 64, 0));
            subtitleView.setVisibility(GONE);

            reorderView = new ImageView(context);
            reorderView.setScaleType(ImageView.ScaleType.CENTER);
            reorderView.setImageResource(R.drawable.list_reorder);
            reorderView.setColorFilter(new PorterDuffColorFilter(Theme.getColor(Theme.key_stickers_menu), PorterDuff.Mode.MULTIPLY));
            reorderView.setContentDescription(LocaleController.getString(R.string.FilterReorder));
            reorderView.setClickable(true);
            addView(reorderView, LayoutHelper.createFrame(48, 48, (rtl ? Gravity.LEFT : Gravity.RIGHT) | Gravity.CENTER_VERTICAL, 6, 0, 6, 0));
        }

        void setButton(ComposerButtons.Button button, boolean divider) {
            needDivider = divider;
            setWillNotDraw(!needDivider);
            if (button == null) {
                iconView.setVisibility(INVISIBLE);
                titleView.setText("");
                subtitleView.setVisibility(GONE);
                return;
            }
            if (button.iconRes != 0) {
                iconView.setVisibility(VISIBLE);
                iconView.setImageResource(button.iconRes);
            } else {
                iconView.setVisibility(INVISIBLE);
            }
            titleView.setText(LocaleController.getString(button.titleRes));
            if (button.kind == ComposerButtons.KIND_FORMAT) {
                subtitleView.setVisibility(VISIBLE);
                subtitleView.setText(LocaleController.getString(R.string.ComposerFormatNeedsSelection));
                titleView.setTranslationY(-dp(8));
            } else {
                subtitleView.setVisibility(GONE);
                titleView.setTranslationY(0);
            }
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(dp(52), MeasureSpec.EXACTLY));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if (needDivider) {
                canvas.drawLine(dp(LocaleController.isRTL ? 0 : 64), getHeight() - 1, getWidth() - dp(LocaleController.isRTL ? 64 : 0), getHeight() - 1, Theme.dividerPaint);
            }
        }
    }

    private static class PreviewCell extends FrameLayout {

        private final LinearLayout row;

        PreviewCell(Context context) {
            super(context);
            setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
            row = new LinearLayout(context);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            // The real toolbar mirrors under RTL, so the preview has to as well or leading and
            // trailing read backwards against the row they are describing.
            row.setLayoutDirection(LocaleController.isRTL ? LAYOUT_DIRECTION_RTL : LAYOUT_DIRECTION_LTR);
            addView(row, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.CENTER_VERTICAL, 12, 0, 12, 0));
        }

        void setLayout(List<List<String>> zones) {
            row.removeAllViews();
            addZone(zones.get(ComposerButtons.ZONE_START));
            addSpacer();
            addZone(zones.get(ComposerButtons.ZONE_MIDDLE));
            addSpacer();
            addZone(zones.get(ComposerButtons.ZONE_END));
        }

        private void addZone(List<String> keys) {
            for (String key : keys) {
                ComposerButtons.Button button = ComposerButtons.get(key);
                if (button == null || button.iconRes == 0) {
                    continue;
                }
                ImageView icon = new ImageView(getContext());
                icon.setScaleType(ImageView.ScaleType.CENTER);
                icon.setImageResource(button.iconRes);
                icon.setColorFilter(new PorterDuffColorFilter(Theme.getColor(Theme.key_windowBackgroundWhiteGrayIcon), PorterDuff.Mode.MULTIPLY));
                if (button.kind == ComposerButtons.KIND_FORMAT) {
                    icon.setAlpha(0.4f);
                }
                row.addView(icon, LayoutHelper.createLinear(32, 32));
            }
        }

        private void addSpacer() {
            View spacer = new View(getContext());
            row.addView(spacer, LayoutHelper.createLinear(0, 0, 1f));
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(dp(56), MeasureSpec.EXACTLY));
        }
    }

    @Override
    public ArrayList<ThemeDescription> getThemeDescriptions() {
        ArrayList<ThemeDescription> descriptions = new ArrayList<>();

        descriptions.add(new ThemeDescription(fragmentView, ThemeDescription.FLAG_BACKGROUND, null, null, null, null, Theme.key_windowBackgroundGray));
        descriptions.add(new ThemeDescription(listView, ThemeDescription.FLAG_CELLBACKGROUNDCOLOR, new Class[]{HeaderCell.class, ButtonRowCell.class, PlaceholderCell.class, PreviewCell.class}, null, null, null, Theme.key_windowBackgroundWhite));

        descriptions.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_BACKGROUND, null, null, null, null, Theme.key_actionBarDefault));
        descriptions.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_AB_ITEMSCOLOR, null, null, null, null, Theme.key_actionBarDefaultIcon));
        descriptions.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_AB_TITLECOLOR, null, null, null, null, Theme.key_actionBarDefaultTitle));
        descriptions.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_AB_SELECTORCOLOR, null, null, null, null, Theme.key_actionBarDefaultSelector));

        descriptions.add(new ThemeDescription(listView, ThemeDescription.FLAG_SELECTOR, null, null, null, null, Theme.key_listSelector));
        descriptions.add(new ThemeDescription(listView, 0, new Class[]{View.class}, Theme.dividerPaint, null, null, Theme.key_divider));

        descriptions.add(new ThemeDescription(listView, 0, new Class[]{HeaderCell.class}, new String[]{"textView"}, null, null, null, Theme.key_windowBackgroundWhiteBlueHeader));
        descriptions.add(new ThemeDescription(listView, 0, new Class[]{ButtonRowCell.class}, new String[]{"titleView"}, null, null, null, Theme.key_windowBackgroundWhiteBlackText));
        descriptions.add(new ThemeDescription(listView, 0, new Class[]{ButtonRowCell.class}, new String[]{"subtitleView"}, null, null, null, Theme.key_windowBackgroundWhiteGrayText2));
        descriptions.add(new ThemeDescription(listView, 0, new Class[]{ButtonRowCell.class}, new String[]{"iconView"}, null, null, null, Theme.key_windowBackgroundWhiteGrayIcon));
        descriptions.add(new ThemeDescription(listView, 0, new Class[]{ButtonRowCell.class}, new String[]{"reorderView"}, null, null, null, Theme.key_stickers_menu));
        descriptions.add(new ThemeDescription(listView, 0, new Class[]{PlaceholderCell.class}, new String[]{"textView"}, null, null, null, Theme.key_windowBackgroundWhiteGrayText2));

        return descriptions;
    }
}
