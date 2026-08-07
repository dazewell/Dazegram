package xyz.nextalone.nagram.ui.composer;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
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
import androidx.core.graphics.ColorUtils;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
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

import xyz.nextalone.nagram.ui.ComposerToolbarLayout;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Lets the user place each composer toolbar button into a zone and order it, by dragging rows
 * between four sections. Config only: it never touches a live enter view, it reads and writes the
 * layout through {@link ComposerLayout} and asks the toolbar to rebuild by posting reloadInterface.
 */
public class ComposerLayoutActivity extends BaseFragment {

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
    private PreviewCell previewCell;

    private final ArrayList<Item> items = new ArrayList<>();
    private List<List<String>> lastSaved;
    private boolean rebuildPending;
    private boolean startZoneArmed;

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
        frameLayout.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.TOP | Gravity.LEFT, 0, 96, 0, 0));
        listView.setAdapter(adapter = new ListAdapter(context));

        // Pinned rather than scrolled with the list: it is the feedback surface for every drag, so
        // it has to stay on screen while the user works down a twenty-row list.
        previewCell = new PreviewCell(context);
        frameLayout.addView(previewCell, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 96, Gravity.TOP | Gravity.LEFT));
        updatePreview();

        return fragmentView;
    }

    private void updatePreview() {
        if (previewCell != null) {
            previewCell.setLayout(collect());
        }
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
        for (int zone : ZONE_ORDER) {
            items.add(new Item(TYPE_HEADER, zone, null));
            List<String> keys = zones.get(zone);
            if (keys.isEmpty()) {
                // Leading is guaranteed to hold exactly one button, so it never needs the empty
                // state; the other zones can legitimately be emptied.
                if (zone != ComposerButtons.ZONE_START) {
                    items.add(new Item(TYPE_PLACEHOLDER, zone, null));
                }
            } else {
                for (String key : keys) {
                    items.add(new Item(TYPE_BUTTON, zone, key));
                }
            }
            // Every zone is closed by its own footer, which both explains the zone and draws the
            // shadow that separates it from the next one.
            items.add(new Item(TYPE_INFO, zone, null));
        }
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

    private void setStartZoneArmed(boolean armed) {
        if (startZoneArmed == armed) {
            return;
        }
        startZoneArmed = armed;
        for (int i = 0; i < listView.getChildCount(); i++) {
            View child = listView.getChildAt(i);
            if (!(child instanceof ButtonRowCell)) {
                continue;
            }
            int position = listView.getChildAdapterPosition(child);
            if (position != RecyclerView.NO_POSITION && zoneAt(position) == ComposerButtons.ZONE_START) {
                ((ButtonRowCell) child).setArmed(armed, true);
            }
        }
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
        updatePreview();
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
                case TYPE_HEADER:
                    ((HeaderCell) holder.itemView).setText(headerTitle(item.zone));
                    break;
                case TYPE_PLACEHOLDER:
                    ((PlaceholderCell) holder.itemView).textView.setText(LocaleController.getString(R.string.ComposerZoneEmpty));
                    break;
                case TYPE_INFO:
                    ((TextInfoPrivacyCell) holder.itemView).setText(LocaleController.getString(footerText(item.zone)));
                    break;
                default:
                    ComposerButtons.Button button = ComposerButtons.get(item.key);
                    ButtonRowCell cell = (ButtonRowCell) holder.itemView;
                    cell.setButton(button, position + 1 < items.size() && items.get(position + 1).type == TYPE_BUTTON);
                    // The leading slot holds exactly one button and cannot be emptied, so there is
                    // nothing to drag: showing a handle there would promise a gesture that does not
                    // exist. It stays a drop target - replacing it is a swap driven by the incoming
                    // button, not by this row.
                    cell.setFixed(item.zone == ComposerButtons.ZONE_START);
                    cell.setArmed(item.zone == ComposerButtons.ZONE_START && startZoneArmed, false);
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

    private static int footerText(int zone) {
        switch (zone) {
            case ComposerButtons.ZONE_START:
                return R.string.ComposerZoneLeadingInfo;
            case ComposerButtons.ZONE_MIDDLE:
                return R.string.ComposerZoneScrollingInfo;
            case ComposerButtons.ZONE_END:
                return R.string.ComposerZoneTrailingInfo;
            default:
                return R.string.ComposerLayoutInfo;
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
            // The leading button is not draggable - it is replaced by dropping another button on
            // it, which keeps the slot permanently filled instead of leaving a hole behind.
            if (zoneAt(viewHolder.getAdapterPosition()) == ComposerButtons.ZONE_START) {
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
            if (targetZone == ComposerButtons.ZONE_START) {
                // Leading is a single occupied slot: the drop exchanges the two buttons in one
                // gesture, so the count never changes and the slot never empties. Repeating the
                // gesture puts things back.
                ComposerButtons.Button incumbent = ComposerButtons.get(items.get(to).key);
                int sourceZone = zoneAt(from);
                if (targetType != TYPE_BUTTON || incumbent == null || sourceZone < 0 || !incumbent.canSitIn(sourceZone)) {
                    return false;
                }
                Collections.swap(items, from, to);
                adapter.notifyItemMoved(from, to);
                adapter.notifyItemMoved(to > from ? to - 1 : to + 1, from);
                updatePreview();
                return true;
            }
            // A button that was just swapped into Leading is still mid-gesture and still carries the
            // movement flags it started with, so without this it could be dragged straight back out
            // and leave the slot empty until drop.
            if (zoneAt(from) == ComposerButtons.ZONE_START) {
                return false;
            }
            items.add(to, items.remove(from));
            adapter.notifyItemMoved(from, to);
            updatePreview();
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
            boolean arm = false;
            if (actionState == ItemTouchHelper.ACTION_STATE_DRAG && viewHolder != null) {
                int position = viewHolder.getAdapterPosition();
                if (position != RecyclerView.NO_POSITION) {
                    ComposerButtons.Button button = ComposerButtons.get(items.get(position).key);
                    // Only light up for a button that is actually allowed in there; the absence of
                    // the highlight is how a trailing-only button says "not here".
                    arm = button != null && button.canSitIn(ComposerButtons.ZONE_START);
                }
            }
            setStartZoneArmed(arm);
            super.onSelectedChanged(viewHolder, actionState);
        }

        @Override
        public void onSwiped(RecyclerView.ViewHolder viewHolder, int direction) {
        }

        @Override
        public void clearView(RecyclerView recyclerView, RecyclerView.ViewHolder viewHolder) {
            super.clearView(recyclerView, viewHolder);
            viewHolder.itemView.setPressed(false);
            setStartZoneArmed(false);
            // A cross-section move can empty the source zone or drop a button next to a stale
            // placeholder. Persist takes the flat order as truth, then a rebuild restores one
            // placeholder per empty zone and drops the redundant ones.
            persist();
            buildItems(lastSaved);
            adapter.notifyDataSetChanged();
            updatePreview();
        }
    }

    private static class PlaceholderCell extends FrameLayout {

        final TextView textView;

        PlaceholderCell(Context context) {
            super(context);
            textView = new TextView(context);
            textView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2));
            textView.setTextSize(15);
            textView.setGravity(Gravity.CENTER);
            addView(textView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.CENTER, 22, 0, 22, 0));
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(dp(56), MeasureSpec.EXACTLY));
        }
    }

    private static class ButtonRowCell extends FrameLayout {

        final ImageView iconView;
        final SimpleTextView titleView;
        final ImageView reorderView;
        private boolean needDivider;
        private float armProgress;
        private ValueAnimator armAnimator;
        private final Paint armPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        void setFixed(boolean fixed) {
            reorderView.setVisibility(fixed ? GONE : VISIBLE);
            FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) titleView.getLayoutParams();
            // Reclaim the handle gutter so the label is not left sitting against empty space.
            int trailing = dp(fixed ? 22 : 56);
            boolean rtl = LocaleController.isRTL;
            if ((rtl ? params.leftMargin : params.rightMargin) != trailing) {
                if (rtl) {
                    params.leftMargin = trailing;
                } else {
                    params.rightMargin = trailing;
                }
                titleView.setLayoutParams(params);
            }
        }

        /** Marks the row as the live drop target while a compatible button is being dragged. */
        void setArmed(boolean armed, boolean animated) {
            float target = armed ? 1f : 0f;
            if (armAnimator != null) {
                armAnimator.cancel();
                armAnimator = null;
            }
            if (!animated) {
                armProgress = target;
                invalidate();
                return;
            }
            armAnimator = ValueAnimator.ofFloat(armProgress, target);
            armAnimator.setDuration(150);
            armAnimator.addUpdateListener(a -> {
                armProgress = (float) a.getAnimatedValue();
                invalidate();
            });
            armAnimator.start();
        }

        @Override
        protected void onDetachedFromWindow() {
            if (armAnimator != null) {
                armAnimator.cancel();
                armAnimator = null;
            }
            super.onDetachedFromWindow();
        }

        ButtonRowCell(Context context) {
            super(context);
            setWillNotDraw(false);

            boolean rtl = LocaleController.isRTL;

            iconView = new ImageView(context);
            // FIT_CENTER, not CENTER: a couple of the registry drawables are authored much smaller
            // than 24dp and CENTER would draw them at their intrinsic size, which is why the AI
            // star arrived as a speck.
            iconView.setScaleType(ImageView.ScaleType.FIT_CENTER);
            iconView.setColorFilter(new PorterDuffColorFilter(Theme.getColor(Theme.key_windowBackgroundWhiteGrayIcon), PorterDuff.Mode.SRC_IN));
            addView(iconView, LayoutHelper.createFrame(24, 24, (rtl ? Gravity.RIGHT : Gravity.LEFT) | Gravity.CENTER_VERTICAL, 20, 0, 20, 0));

            titleView = new SimpleTextView(context);
            titleView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            titleView.setTextSize(16);
            titleView.setMaxLines(1);
            titleView.setGravity((rtl ? Gravity.RIGHT : Gravity.LEFT) | Gravity.CENTER_VERTICAL);
            // Centred against the row rather than pinned to a top offset, so the title, the icon and
            // the drag handle all sit on one line no matter what the row contains.
            addView(titleView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, (rtl ? Gravity.RIGHT : Gravity.LEFT) | Gravity.CENTER_VERTICAL, rtl ? 56 : 64, 0, rtl ? 64 : 56, 0));

            reorderView = new ImageView(context);
            reorderView.setScaleType(ImageView.ScaleType.CENTER);
            reorderView.setImageResource(R.drawable.list_reorder);
            reorderView.setColorFilter(new PorterDuffColorFilter(Theme.getColor(Theme.key_stickers_menu), PorterDuff.Mode.SRC_IN));
            reorderView.setContentDescription(LocaleController.getString(R.string.FilterReorder));
            reorderView.setClickable(true);
            addView(reorderView, LayoutHelper.createFrame(48, 48, (rtl ? Gravity.LEFT : Gravity.RIGHT) | Gravity.CENTER_VERTICAL, 6, 0, 6, 0));
        }

        void setButton(ComposerButtons.Button button, boolean divider) {
            needDivider = divider;
            if (button == null) {
                iconView.setVisibility(INVISIBLE);
                iconView.setScaleX(1f);
                iconView.setScaleY(1f);
                titleView.setText("");
                return;
            }
            if (button.iconRes != 0) {
                iconView.setVisibility(VISIBLE);
                iconView.setImageResource(button.iconRes);
            } else {
                iconView.setVisibility(INVISIBLE);
            }
            // Reassigned on every bind because these rows are recycled.
            iconView.setScaleX(button.iconScale);
            iconView.setScaleY(button.iconScale);
            titleView.setText(LocaleController.getString(button.titleRes));
        }

        void applyTheme() {
            iconView.setColorFilter(new PorterDuffColorFilter(Theme.getColor(Theme.key_windowBackgroundWhiteGrayIcon), PorterDuff.Mode.SRC_IN));
            reorderView.setColorFilter(new PorterDuffColorFilter(Theme.getColor(Theme.key_stickers_menu), PorterDuff.Mode.SRC_IN));
            titleView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(dp(56), MeasureSpec.EXACTLY));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if (armProgress > 0) {
                int wash = Theme.getColor(Theme.key_windowBackgroundWhiteBlueText);
                armPaint.setColor(ColorUtils.setAlphaComponent(wash, (int) (26 * armProgress)));
                canvas.drawRect(0, 0, getWidth(), getHeight(), armPaint);
            }
            if (needDivider) {
                canvas.drawLine(dp(LocaleController.isRTL ? 0 : 64), getHeight() - dp(1), getWidth() - dp(LocaleController.isRTL ? 64 : 0), getHeight() - dp(1), Theme.dividerPaint);
            }
        }
    }

    private static class PreviewCell extends FrameLayout {

        private final TextView header;
        private final LinearLayout row;
        private List<List<String>> zones;

        PreviewCell(Context context) {
            super(context);
            setWillNotDraw(false);
            setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));

            header = new TextView(context);
            header.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 15);
            header.setTypeface(AndroidUtilities.bold());
            header.setGravity(LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT);
            header.setText(LocaleController.getString(R.string.ComposerPreviewHeader));
            addView(header, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, (LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT) | Gravity.TOP, 22, 13, 22, 0));

            row = new LinearLayout(context);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            // The real toolbar mirrors under RTL, so the preview has to as well or leading and
            // trailing read backwards against the row they are describing.
            row.setLayoutDirection(LocaleController.isRTL ? LAYOUT_DIRECTION_RTL : LAYOUT_DIRECTION_LTR);
            addView(row, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 48, Gravity.TOP | Gravity.LEFT, 12, 40, 12, 0));
        }

        void setLayout(List<List<String>> zones) {
            this.zones = zones;
            header.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueHeader));
            row.removeAllViews();
            addZone(zones.get(ComposerButtons.ZONE_START));
            addGap();
            addZone(zones.get(ComposerButtons.ZONE_MIDDLE));
            addGap();
            addZone(zones.get(ComposerButtons.ZONE_END));
        }

        /** The preview colours its own children at build time, so a live theme switch has to rebuild
         * it rather than just repaint the background. */
        void applyTheme() {
            setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
            if (zones != null) {
                setLayout(zones);
            }
        }

        private void addZone(List<String> keys) {
            for (String key : keys) {
                ComposerButtons.Button button = ComposerButtons.get(key);
                if (button == null || button.iconRes == 0) {
                    continue;
                }
                ImageView icon = new ImageView(getContext());
                icon.setImageResource(button.iconRes);
                ComposerToolbarLayout.applyIconBox(icon, 32, button.iconScale);
                icon.setColorFilter(new PorterDuffColorFilter(Theme.getColor(Theme.key_windowBackgroundWhiteGrayIcon), PorterDuff.Mode.SRC_IN));
                row.addView(icon, LayoutHelper.createLinear(32, 32));
            }
        }

        /** Weighted gap with a hairline in the middle, so the three zones stay visually separable
         * even when the row is nearly full. */
        private void addGap() {
            row.addView(new View(getContext()), LayoutHelper.createLinear(0, 0, 1f));
            View mark = new View(getContext());
            mark.setBackgroundColor(ColorUtils.setAlphaComponent(
                    Theme.getColor(Theme.key_windowBackgroundWhiteGrayIcon), 0x3D));
            row.addView(mark, LayoutHelper.createLinear(2, 24, 0f, Gravity.CENTER_VERTICAL));
            row.addView(new View(getContext()), LayoutHelper.createLinear(0, 0, 1f));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            canvas.drawLine(0, getHeight() - dp(1), getWidth(), getHeight() - dp(1), Theme.dividerPaint);
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(dp(96), MeasureSpec.EXACTLY));
        }
    }

    @Override
    public ArrayList<ThemeDescription> getThemeDescriptions() {
        ArrayList<ThemeDescription> descriptions = new ArrayList<>();

        ThemeDescription.ThemeDescriptionDelegate delegate = () -> {
            if (listView != null) {
                for (int i = 0; i < listView.getChildCount(); i++) {
                    View child = listView.getChildAt(i);
                    if (child instanceof ButtonRowCell) {
                        ((ButtonRowCell) child).applyTheme();
                    }
                }
            }
            if (previewCell != null) {
                previewCell.applyTheme();
            }
        };

        descriptions.add(new ThemeDescription(fragmentView, ThemeDescription.FLAG_BACKGROUND, null, null, null, null, Theme.key_windowBackgroundGray));
        descriptions.add(new ThemeDescription(listView, ThemeDescription.FLAG_CELLBACKGROUNDCOLOR, new Class[]{HeaderCell.class, ButtonRowCell.class, PlaceholderCell.class}, null, null, null, Theme.key_windowBackgroundWhite));
        descriptions.add(new ThemeDescription(previewCell, ThemeDescription.FLAG_BACKGROUND, null, null, null, delegate, Theme.key_windowBackgroundWhite));

        descriptions.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_BACKGROUND, null, null, null, null, Theme.key_actionBarDefault));
        descriptions.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_AB_ITEMSCOLOR, null, null, null, null, Theme.key_actionBarDefaultIcon));
        descriptions.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_AB_TITLECOLOR, null, null, null, null, Theme.key_actionBarDefaultTitle));
        descriptions.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_AB_SELECTORCOLOR, null, null, null, null, Theme.key_actionBarDefaultSelector));

        descriptions.add(new ThemeDescription(listView, ThemeDescription.FLAG_SELECTOR, null, null, null, null, Theme.key_listSelector));
        descriptions.add(new ThemeDescription(listView, 0, new Class[]{View.class}, Theme.dividerPaint, null, null, Theme.key_divider));

        descriptions.add(new ThemeDescription(listView, 0, new Class[]{HeaderCell.class}, new String[]{"textView"}, null, null, null, Theme.key_windowBackgroundWhiteBlueHeader));
        descriptions.add(new ThemeDescription(listView, 0, new Class[]{ButtonRowCell.class}, new String[]{"titleView"}, null, null, null, Theme.key_windowBackgroundWhiteBlackText));
        descriptions.add(new ThemeDescription(listView, 0, new Class[]{PlaceholderCell.class}, new String[]{"textView"}, null, null, null, Theme.key_windowBackgroundWhiteGrayText2));

        return descriptions;
    }
}
