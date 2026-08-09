package xyz.nextalone.nagram.ui.composer;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Shader;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.SystemClock;
import android.text.TextPaint;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.SeekBar;
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
import org.telegram.ui.Components.ChatActivityEnterView;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.MotionBackgroundDrawable;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.SeekBarView;
import org.telegram.ui.Components.blur3.BlurredBackgroundDrawableViewFactory;
import org.telegram.ui.Components.blur3.drawable.color.BlurredBackgroundColorProviderThemed;
import org.telegram.ui.Components.chat.WallpaperBitmapProvider;

import xyz.nextalone.nagram.NaConfig;
import xyz.nextalone.nagram.ui.ComposerToolbarLayout;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

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
    private static final int TYPE_SCALE = 5;

    private static final int SCALE_MIN = 75;
    private static final int SCALE_MAX = 125;
    private static final int SCALE_STEP = 5;
    private static final int STEP_COUNT = (SCALE_MAX - SCALE_MIN) / SCALE_STEP + 1;

    /** Header text plus the breathing room above and below the capsule, in dp. */
    private static final int PREVIEW_HEADER_HEIGHT = 40;
    private static final int PREVIEW_INPUT_GAP = 2;
    private static final int PREVIEW_INPUT_HEIGHT = 44;
    private static final int PREVIEW_PADDING = 12;

    private static final int[] PREVIEW_ZONES = {
            ComposerButtons.ZONE_START,
            ComposerButtons.ZONE_MIDDLE,
            ComposerButtons.ZONE_END,
    };

    /** One height for the pinned preview and the list's matching top margin, so the two cannot
     * drift apart as the scale changes the capsule's size. */
    private static int previewHeight() {
        return PREVIEW_HEADER_HEIGHT + ComposerToolbarLayout.height() + PREVIEW_INPUT_GAP
                + PREVIEW_INPUT_HEIGHT + PREVIEW_PADDING;
    }

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
        frameLayout.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.TOP | Gravity.LEFT, 0, previewHeight(), 0, 0));
        listView.setAdapter(adapter = new ListAdapter(context));

        // Pinned rather than scrolled with the list: it is the feedback surface for every drag, so
        // it has to stay on screen while the user works down a twenty-row list.
        previewCell = new PreviewCell(context);
        frameLayout.addView(previewCell, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, previewHeight(), Gravity.TOP | Gravity.LEFT));
        updatePreview();

        return fragmentView;
    }

    private void updatePreview() {
        if (previewCell == null) {
            return;
        }
        previewCell.setLayout(ComposerLayout.normalize(collect()));
        // The capsule grows with the scale, so the pinned preview and the space the list leaves for
        // it are re-applied together - a stale margin would either clip the panel or float the list.
        int height = dp(previewHeight());
        ViewGroup.LayoutParams previewParams = previewCell.getLayoutParams();
        if (previewParams != null && previewParams.height != height) {
            previewParams.height = height;
            previewCell.setLayoutParams(previewParams);
        }
        if (listView != null && listView.getLayoutParams() instanceof FrameLayout.LayoutParams) {
            FrameLayout.LayoutParams listParams = (FrameLayout.LayoutParams) listView.getLayoutParams();
            if (listParams.topMargin != height) {
                listParams.topMargin = height;
                listView.setLayoutParams(listParams);
            }
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
        // Sits above the zone list so the size control is the first thing under the preview, rather
        // than something the user only finds after scrolling past twenty draggable rows.
        items.add(new Item(TYPE_SCALE, -1, null));
        items.add(new Item(TYPE_INFO, -1, null));
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
        NaConfig.INSTANCE.getComposerToolbarScale().setConfigInt(100);
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
                case TYPE_SCALE:
                    view = new ScaleCell(context);
                    view.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
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
                    cell.setOnClickListener(v -> {
                        int position = listView.getChildAdapterPosition(cell);
                        if (position != RecyclerView.NO_POSITION) {
                            toggleHiddenMiddle(position);
                        }
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
                case TYPE_SCALE:
                    ((ScaleCell) holder.itemView).update();
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
            case -1:
                return R.string.ComposerScaleInfo;
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

    private static final long START_ZONE_SWAP_DWELL_MS = 350;

    private int pendingStartSwapFrom = RecyclerView.NO_POSITION;
    private int pendingStartSwapTo = RecyclerView.NO_POSITION;
    private long pendingStartSwapSince;

    /** Tap shortcut for Hidden <-> Scrolling: dragging across a long Hidden list is fiddly, so a plain
     * tap flips a row to the other side without needing to reach the row's spot at all. Start and End
     * stay drag-only - they are capacity/anchor constrained (single slot, trailing-pinned) in ways a
     * blind tap-to-the-end can't express. */
    private void toggleHiddenMiddle(int position) {
        if (position < 0 || position >= items.size()) {
            return;
        }
        Item item = items.get(position);
        if (item.type != TYPE_BUTTON) {
            return;
        }
        int zone = zoneAt(position);
        if (zone != ComposerButtons.ZONE_MIDDLE && zone != ComposerButtons.ZONE_HIDDEN) {
            return;
        }
        ComposerButtons.Button button = ComposerButtons.get(item.key);
        int targetZone = zone == ComposerButtons.ZONE_HIDDEN ? ComposerButtons.ZONE_MIDDLE : ComposerButtons.ZONE_HIDDEN;
        if (button == null || !button.canSitIn(targetZone)) {
            return;
        }
        List<List<String>> zones = collect();
        zones.get(zone).remove(item.key);
        // Appended at the end of the target zone: a sensible, predictable default rather than trying
        // to guess where in the middle of an existing order a tapped button "should" go.
        zones.get(targetZone).add(item.key);
        buildItems(zones);
        adapter.notifyDataSetChanged();
        persist();
        updatePreview();
    }

    private void clearPendingStartSwap() {
        pendingStartSwapFrom = RecyclerView.NO_POSITION;
        pendingStartSwapTo = RecyclerView.NO_POSITION;
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
                // Autoscroll carries the dragged row over Leading just passing through on the way to
                // a zone further up the list, and onMove fires on every one of those scroll ticks, so
                // committing on the first hit turned "scrolling past the top" into an accidental swap.
                // Require the same pair to keep showing up for a short dwell before it actually commits.
                long now = SystemClock.uptimeMillis();
                if (pendingStartSwapFrom != from || pendingStartSwapTo != to) {
                    pendingStartSwapFrom = from;
                    pendingStartSwapTo = to;
                    pendingStartSwapSince = now;
                    return false;
                }
                if (now - pendingStartSwapSince < START_ZONE_SWAP_DWELL_MS) {
                    return false;
                }
                clearPendingStartSwap();
                Collections.swap(items, from, to);
                adapter.notifyItemMoved(from, to);
                adapter.notifyItemMoved(to > from ? to - 1 : to + 1, from);
                updatePreview();
                return true;
            }
            clearPendingStartSwap();
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
            clearPendingStartSwap();
            // A cross-section move can empty the source zone or drop a button next to a stale
            // placeholder. Persist takes the flat order as truth, then a rebuild restores one
            // placeholder per empty zone and drops the redundant ones.
            persist();
            buildItems(lastSaved);
            adapter.notifyDataSetChanged();
            updatePreview();
        }
    }

    /**
     * Scales the whole panel - row, cells and glyphs together - between 75% and 125%. Modelled on the
     * sticker size row: an unlabelled track with the value in the corner, rather than a label under
     * every stop, which at eleven stops collides at the crowded end of the track.
     */
    private class ScaleCell extends FrameLayout {

        private final SeekBarView sizeBar;
        private final TextPaint textPaint;

        ScaleCell(Context context) {
            super(context);
            setWillNotDraw(false);

            textPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
            textPaint.setTextSize(dp(16));

            sizeBar = new SeekBarView(context);
            sizeBar.setReportChanges(true);
            sizeBar.setSeparatorsCount(STEP_COUNT);
            sizeBar.setDelegate((stop, progress) -> {
                int percent = SCALE_MIN + Math.round(progress * (STEP_COUNT - 1)) * SCALE_STEP;
                if (percent == NaConfig.INSTANCE.getComposerToolbarScale().Int()) {
                    return;
                }
                NaConfig.INSTANCE.getComposerToolbarScale().setConfigInt(percent);
                // Deferred to leaving the screen like the layout itself: rebuilding every chat behind
                // the editor on each step of the drag would stutter for no visible gain here.
                rebuildPending = true;
                invalidate();
                // The preview is the whole point of the slider, so it does follow the drag. It
                // rebuilds a single toolbar, which is cheap next to the eleven stops of a sweep.
                updatePreview();
            });
            addView(sizeBar, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 38, Gravity.LEFT | Gravity.TOP, 9, 5, 43, 11));
        }

        @Override
        public CharSequence getAccessibilityClassName() {
            return SeekBar.class.getName();
        }

        @Override
        public void onInitializeAccessibilityNodeInfo(android.view.accessibility.AccessibilityNodeInfo info) {
            super.onInitializeAccessibilityNodeInfo(info);
            // Announced as a percentage rather than the raw slider sweep, so the default is still a
            // landmark to come back to without seeing the value in the corner.
            info.setContentDescription(LocaleController.formatString(
                    R.string.ComposerScaleAccDescr, currentScale()));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            textPaint.setColor(Theme.getColor(Theme.key_windowBackgroundWhiteValueText));
            String value = String.format(Locale.US, "%d%%", currentScale());
            canvas.drawText(value, getMeasuredWidth() - dp(39), dp(28), textPaint);
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int width = MeasureSpec.getSize(widthMeasureSpec);
            super.onMeasure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(dp(52), MeasureSpec.EXACTLY));
            // Only seeded when the bar is idle. The preview now resizes as the slider moves, which
            // re-measures this row mid gesture, and re-asserting the quantised value there would
            // snap the thumb out from under the finger.
            if (!sizeBar.isDragging()) {
                sizeBar.setProgress((currentScale() - SCALE_MIN) / (float) (SCALE_MAX - SCALE_MIN));
            }
        }

        void update() {
            sizeBar.setProgress((currentScale() - SCALE_MIN) / (float) (SCALE_MAX - SCALE_MIN));
            invalidate();
        }
    }

    private static int currentScale() {
        int percent = NaConfig.INSTANCE.getComposerToolbarScale().Int();
        return Math.max(SCALE_MIN, Math.min(SCALE_MAX, percent));
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

    /**
     * Shows the arrangement being edited as the real thing: an actual {@link ComposerToolbarLayout}
     * with its glass capsule, on the user's chat wallpaper, at the size the scale slider is set to.
     * A schematic row of flat icons could not show what the two settings on this screen actually do.
     */
    private static class PreviewCell extends FrameLayout {

        private final TextView header;
        private final FrameLayout stage;
        private final Drawable shadowDrawable;
        private final WallpaperBitmapProvider wallpaperProvider = new WallpaperBitmapProvider();
        private ComposerToolbarLayout toolbar;
        private List<List<String>> zones;
        private Drawable backgroundDrawable;

        PreviewCell(Context context) {
            super(context);
            setWillNotDraw(false);

            shadowDrawable = Theme.getThemedDrawableByKey(context, R.drawable.greydivider_bottom, Theme.key_windowBackgroundGrayShadow);

            header = new TextView(context);
            header.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 15);
            header.setTypeface(AndroidUtilities.bold());
            header.setGravity(LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT);
            header.setText(LocaleController.getString(R.string.ComposerPreviewHeader));
            addView(header, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, (LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT) | Gravity.TOP, 22, 13, 22, 0));

            stage = new FrameLayout(context);
            stage.setClipChildren(false);
            addView(stage, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP | Gravity.LEFT, 8, PREVIEW_HEADER_HEIGHT, 8, 0));
        }

        /**
         * Rebuilt rather than resized whenever the layout or the scale changes: the toolbar bakes its
         * row height into its slots' layout params and its glass radius when the glass is attached,
         * on the premise that a panel keeps one height for its whole life. Resizing in place would
         * break that premise and the bounds animator with it.
         */
        void setLayout(List<List<String>> zones) {
            this.zones = zones;
            header.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueHeader));
            stage.removeAllViews();

            toolbar = new ComposerToolbarLayout(getContext());
            // Fed the wallpaper as a static source rather than the chat's render node blur: there is
            // no live chat behind this row to sample from, but the capsule still has to be drawn over
            // the same backdrop it is composited against, or its translucency reads wrong.
            attachGlass(Theme.getCachedWallpaperNonBlocking());

            String trailingKey = trailingKeyOf(zones.get(ComposerButtons.ZONE_END));
            for (int zone : PREVIEW_ZONES) {
                List<String> keys = zones.get(zone);
                for (int order = 0; order < keys.size(); order++) {
                    String key = keys.get(order);
                    ComposerButtons.Button button = ComposerButtons.get(key);
                    if (button == null || button.iconRes == 0) {
                        continue;
                    }
                    ImageView icon = new ImageView(getContext());
                    icon.setImageResource(button.iconRes);
                    icon.setColorFilter(new PorterDuffColorFilter(Theme.getColor(Theme.key_chat_messagePanelIcons), PorterDuff.Mode.SRC_IN));
                    toolbar.addConfigurable(key, icon, zone, order, trailingKey);
                }
            }
            addMockInput();
            stage.addView(toolbar, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, ComposerToolbarLayout.height(),
                    Gravity.TOP | Gravity.START, 0, PREVIEW_INPUT_HEIGHT + PREVIEW_INPUT_GAP, 0, 0));
        }

        private void addMockInput() {
            FrameLayout input = new FrameLayout(getContext());
            input.setFocusable(false);
            input.setClickable(false);
            input.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);

            GradientDrawable bodyBackground = new GradientDrawable();
            bodyBackground.setColor(Theme.getColor(Theme.key_chat_messagePanelBackground));
            bodyBackground.setCornerRadius(dp(PREVIEW_INPUT_HEIGHT / 2f));
            FrameLayout body = new FrameLayout(getContext());
            body.setBackground(bodyBackground);
            body.setFocusable(false);
            body.setClickable(false);
            body.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
            input.addView(body, LayoutHelper.createFrame(
                    LayoutHelper.MATCH_PARENT, PREVIEW_INPUT_HEIGHT, Gravity.TOP | Gravity.START));

            TextView hint = new TextView(getContext());
            hint.setText(LocaleController.getString(R.string.Message));
            hint.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 16);
            hint.setTextColor(Theme.getColor(Theme.key_chat_messagePanelHint));
            hint.setGravity(Gravity.CENTER_VERTICAL);
            hint.setFocusable(false);
            hint.setClickable(false);
            FrameLayout.LayoutParams hintParams = (FrameLayout.LayoutParams) LayoutHelper.createFrame(
                    LayoutHelper.MATCH_PARENT, PREVIEW_INPUT_HEIGHT, Gravity.TOP | Gravity.START, 0, 0, 0, 0);
            hintParams.setMarginStart(dp(8));
            hintParams.setMarginEnd(dp(47));
            body.addView(hint, hintParams);

            ChatActivityEnterView.SendButton send = new ChatActivityEnterView.SendButton(
                    getContext(), R.drawable.send_plane_24, null, true);
            send.setBackgroundInset(dp(3));
            send.setFocusable(false);
            send.setClickable(false);
            send.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            FrameLayout.LayoutParams sendParams = (FrameLayout.LayoutParams) LayoutHelper.createFrame(
                    PREVIEW_INPUT_HEIGHT, PREVIEW_INPUT_HEIGHT, Gravity.TOP | Gravity.END);
            sendParams.setMarginEnd(dp(3));
            body.addView(send, sendParams);
            stage.addView(input, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, PREVIEW_INPUT_HEIGHT,
                    Gravity.TOP | Gravity.START));
        }

        /** Points the capsule's glass at whatever the chat wallpaper currently is. A null wallpaper
         * still yields a usable source, so the capsule is never left unpainted. */
        private void attachGlass(Drawable wallpaper) {
            toolbar.attachGlass(
                    new BlurredBackgroundDrawableViewFactory(wallpaperProvider.updateSourceFromBackgroundViewDrawable(wallpaper)),
                    new BlurredBackgroundColorProviderThemed(null, Theme.key_chat_messagePanelVoiceLockBackground));
        }

        /** Mirrors {@link ComposerLayout#trailingKey()} against the layout being dragged rather than
         * the saved one, so the pinned trailing button is right mid gesture too. */
        private static String trailingKeyOf(List<String> end) {
            for (int i = end.size() - 1; i >= 0; i--) {
                ComposerButtons.Button button = ComposerButtons.get(end.get(i));
                if (button != null && button.stable) {
                    return button.key;
                }
            }
            return null;
        }

        /** The preview colours its own children at build time, so a live theme switch has to rebuild
         * it rather than just repaint the background. */
        void applyTheme() {
            backgroundDrawable = null;
            if (zones != null) {
                setLayout(zones);
            }
        }

        // Purely a display surface sitting above a drag and drop list: without this a horizontal
        // fling would scroll the toolbar's middle group, or take the gesture off the list below.
        @Override
        public boolean onInterceptTouchEvent(MotionEvent event) {
            return true;
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            return true;
        }

        @Override
        protected void onDraw(Canvas canvas) {
            Drawable wallpaper = Theme.getCachedWallpaperNonBlocking();
            if (Theme.wallpaperLoadTask != null) {
                invalidate();
            }
            if (wallpaper != backgroundDrawable) {
                backgroundDrawable = wallpaper;
                // The glass samples the wallpaper it is drawn over, so a wallpaper that arrives
                // after the toolbar was built has to be handed to it, not just painted here. Only on
                // an actual change, so this stays off the per frame path.
                if (toolbar != null) {
                    attachGlass(wallpaper);
                }
            }
            if (wallpaper == null) {
                canvas.drawColor(Theme.getColor(Theme.key_windowBackgroundWhite));
            } else {
                drawWallpaper(canvas, wallpaper);
            }
            shadowDrawable.setBounds(0, 0, getMeasuredWidth(), getMeasuredHeight());
            shadowDrawable.draw(canvas);
        }

        private void drawWallpaper(Canvas canvas, Drawable drawable) {
            if (drawable instanceof ColorDrawable || drawable instanceof GradientDrawable || drawable instanceof MotionBackgroundDrawable) {
                drawable.setBounds(0, 0, getMeasuredWidth(), getMeasuredHeight());
                drawable.draw(canvas);
                return;
            }
            if (!(drawable instanceof BitmapDrawable)) {
                return;
            }
            BitmapDrawable bitmapDrawable = (BitmapDrawable) drawable;
            bitmapDrawable.setFilterBitmap(true);
            canvas.save();
            if (bitmapDrawable.getTileModeX() == Shader.TileMode.REPEAT) {
                float scale = 2.0f / AndroidUtilities.density;
                canvas.scale(scale, scale);
                drawable.setBounds(0, 0, (int) Math.ceil(getMeasuredWidth() / scale), (int) Math.ceil(getMeasuredHeight() / scale));
            } else {
                float scale = Math.max(
                        getMeasuredWidth() / (float) drawable.getIntrinsicWidth(),
                        getMeasuredHeight() / (float) drawable.getIntrinsicHeight());
                int width = (int) Math.ceil(drawable.getIntrinsicWidth() * scale);
                int height = (int) Math.ceil(drawable.getIntrinsicHeight() * scale);
                int x = (getMeasuredWidth() - width) / 2;
                int y = (getMeasuredHeight() - height) / 2;
                canvas.clipRect(0, 0, getMeasuredWidth(), getMeasuredHeight());
                drawable.setBounds(x, y, x + width, y + height);
            }
            drawable.draw(canvas);
            canvas.restore();
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(dp(previewHeight()), MeasureSpec.EXACTLY));
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
        // Delegate only, with no background flag: the preview paints the chat wallpaper itself now,
        // so letting the theme engine set a flat colour on it would just be overdrawn.
        descriptions.add(new ThemeDescription(null, 0, null, null, null, delegate, Theme.key_windowBackgroundWhite));

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
