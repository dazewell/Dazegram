package com.radolyn.ayugram.shortcuts;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.animation.LayoutTransition;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.radolyn.ayugram.videonote.VideoNoteShortcut;

import org.telegram.messenger.MediaDataController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ActionBar.ThemeDescription;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

import java.util.ArrayList;

import tw.nekomimi.nekogram.config.CellGroup;
import tw.nekomimi.nekogram.config.cell.AbstractConfigCell;
import tw.nekomimi.nekogram.config.cell.ConfigCellCustom;
import tw.nekomimi.nekogram.config.cell.ConfigCellDivider;
import tw.nekomimi.nekogram.config.cell.ConfigCellHeader;
import tw.nekomimi.nekogram.config.cell.ConfigCellSelectBox;
import tw.nekomimi.nekogram.config.cell.ConfigCellTextCheck;
import tw.nekomimi.nekogram.settings.BaseNekoXSettingsActivity;
import xyz.nextalone.nagram.NaConfig;

/**
 * N-Settings -> General -> Launcher shortcuts. One section per fork shortcut, under a mock of the launcher's long-press
 * popup that shows which of them are on.
 */
public class LauncherShortcutsActivity extends BaseNekoXSettingsActivity {

    public static final String SETTINGS_KEY = "shortcuts";

    private final CellGroup cellGroup = new CellGroup(this);

    private final AbstractConfigCell headerPreview = cellGroup.appendCell(new ConfigCellHeader(getString(R.string.ComposerPreviewHeader)));
    private final AbstractConfigCell previewRow = cellGroup.appendCell(new ConfigCellCustom(null, ConfigCellCustom.CUSTOM_ITEM_LauncherShortcutsPreview, false));
    private final AbstractConfigCell dividerPreview = cellGroup.appendCell(new ConfigCellDivider());

    private final AbstractConfigCell headerGhostMode = cellGroup.appendCell(new ConfigCellHeader(getString(R.string.GhostMode)));
    private final AbstractConfigCell ghostModeRow = cellGroup.appendCell(new ConfigCellTextCheck(NaConfig.INSTANCE.getGhostModeShortcut(), getString(R.string.GhostModeShortcutNotice)));
    private final AbstractConfigCell dividerGhostMode = cellGroup.appendCell(new ConfigCellDivider());

    private final AbstractConfigCell headerVideoNote = cellGroup.appendCell(new ConfigCellHeader(getString(R.string.VideoNoteShortcutLabel)));
    private final AbstractConfigCell videoNoteRow = cellGroup.appendCell(new ConfigCellTextCheck(NaConfig.INSTANCE.getVideoNoteShortcut(), getString(R.string.VideoNoteShortcutNotice)));
    private final AbstractConfigCell videoNoteCameraRow = cellGroup.appendCell(new ConfigCellSelectBox("VideoNoteShortcutCamera", NaConfig.INSTANCE.getVideoNoteShortcutCamera(), new String[]{
            getString(R.string.CameraInVideoMessagesFront),
            getString(R.string.CameraInVideoMessagesRear)
    }, null));
    private final AbstractConfigCell dividerVideoNote = cellGroup.appendCell(new ConfigCellDivider());

    private ListAdapter listAdapter;
    private PreviewCell previewCell;

    public LauncherShortcutsActivity() {
        updateCameraRow(false);
        addRowsToMap(cellGroup);
    }

    @Override
    protected RecyclerListView.SelectionAdapter getListAdapter() {
        return listAdapter;
    }

    @Override
    protected CellGroup getCellGroup() {
        return cellGroup;
    }

    @Override
    protected String getSettingsPrefix() {
        return SETTINGS_KEY;
    }

    @Override
    public int getBaseGuid() {
        return 15000;
    }

    @Override
    public int getDrawable() {
        return R.drawable.msg_home;
    }

    @Override
    public String getTitle() {
        return getString(R.string.LauncherShortcuts);
    }

    @Override
    public View createView(Context context) {
        View superView = super.createView(context);

        listAdapter = new ListAdapter(context);
        listView.setAdapter(listAdapter);
        setupDefaultListeners();

        cellGroup.callBackSettingsChanged = (key, newValue) -> onShortcutSettingChanged(key);

        return superView;
    }

    @Override
    protected void onConfigImported(String key, Object value) {
        onShortcutSettingChanged(key);
    }

    private void onShortcutSettingChanged(String key) {
        boolean videoNote = key.equals(NaConfig.INSTANCE.getVideoNoteShortcut().getKey());
        if (!videoNote && !key.equals(NaConfig.INSTANCE.getGhostModeShortcut().getKey())) {
            return;
        }
        MediaDataController.getInstance(currentAccount).buildShortcuts();
        if (videoNote) {
            updateCameraRow(true);
        }
        if (previewCell != null) {
            previewCell.update();
        }
    }

    // The camera only means something while the shortcut is on. ConfigCellSelectBox can't be dimmed, so the row is
    // taken out instead, and the row map is rebuilt so search results and settings links still land on the right row.
    private void updateCameraRow(boolean animated) {
        boolean show = VideoNoteShortcut.isEnabled();
        int index = cellGroup.rows.indexOf(videoNoteCameraRow);
        if (show && index < 0) {
            index = cellGroup.rows.indexOf(videoNoteRow) + 1;
            cellGroup.rows.add(index, videoNoteCameraRow);
            if (animated && listAdapter != null) {
                listAdapter.notifyItemInserted(index);
            }
        } else if (!show && index >= 0) {
            cellGroup.rows.remove(index);
            if (animated && listAdapter != null) {
                listAdapter.notifyItemRemoved(index);
            }
        } else {
            return;
        }
        if (animated && listAdapter != null) {
            listAdapter.notifyItemChanged(cellGroup.rows.indexOf(videoNoteRow)); // its divider follows the next row
        }
        addRowsToMap(cellGroup);
    }

    @Override
    public ArrayList<ThemeDescription> getThemeDescriptions() {
        ArrayList<ThemeDescription> themeDescriptions = super.getThemeDescriptions();
        themeDescriptions.add(new ThemeDescription(null, 0, null, null, null, () -> {
            if (previewCell != null) {
                previewCell.update();
            }
        }, Theme.key_windowBackgroundWhite));
        return themeDescriptions;
    }

    private class ListAdapter extends BaseListAdapter {

        ListAdapter(Context context) {
            super(context);
        }

        @Override
        protected View onCreateCustomViewHolder(@NonNull ViewGroup parent, int viewType) {
            if (viewType == ConfigCellCustom.CUSTOM_ITEM_LauncherShortcutsPreview) {
                return previewCell = new PreviewCell(mContext);
            }
            return null;
        }

        @Override
        protected void onBindCustomViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            if (holder.itemView instanceof PreviewCell cell) {
                cell.update();
            }
        }
    }

    /**
     * The launcher's long-press popup as the user will see it: Telegram's own New message first, then each fork
     * shortcut that is on, in the rank buildShortcuts gives them. Recent-chat shortcuts are left out, they come and go.
     */
    private static class PreviewCell extends FrameLayout {

        private static final int ITEM_HEIGHT = 48;
        private static final int POPUP_PADDING = 6;
        private static final int POPUP_TOP = 8;
        private static final int POPUP_BOTTOM = 16;
        private static final int ITEM_COUNT = 3;

        private final LinearLayout popup;
        private final GradientDrawable popupBackground = new GradientDrawable();
        private final TextView[] labels = new TextView[ITEM_COUNT];
        private final View ghostModeItem;
        private final View videoNoteItem;

        PreviewCell(Context context) {
            super(context);
            popup = new LinearLayout(context);
            popup.setOrientation(LinearLayout.VERTICAL);
            popup.setPadding(0, dp(POPUP_PADDING), 0, dp(POPUP_PADDING));
            popupBackground.setCornerRadius(dp(20));
            popup.setBackground(popupBackground);
            addView(popup, LayoutHelper.createFrame(240, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL | Gravity.TOP, 0, POPUP_TOP, 0, 0));

            addItem(context, 0, R.drawable.shortcut_compose, null, R.string.NewConversationShortcut);
            // Built once, not per bind: both are fresh bitmaps, and the base page rebinds everything on resume.
            ghostModeItem = addItem(context, 1, 0, GhostModeShortcut.createIcon(), R.string.AyuModeShortcut);
            videoNoteItem = addItem(context, 2, 0, VideoNoteShortcut.createIcon(), R.string.VideoNoteShortcutLabel);

            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
            update();

            // Set last so the initial state doesn't animate in. Items fade in and out and the card eases to its new
            // height instead of snapping. Kept off the parent hierarchy, which would reach into the RecyclerView.
            LayoutTransition itemTransition = new LayoutTransition();
            itemTransition.setAnimateParentHierarchy(false);
            itemTransition.setDuration(220);
            popup.setLayoutTransition(itemTransition);
            LayoutTransition cardTransition = new LayoutTransition();
            cardTransition.enableTransitionType(LayoutTransition.CHANGING);
            cardTransition.setAnimateParentHierarchy(false);
            cardTransition.setDuration(220);
            setLayoutTransition(cardTransition);
        }

        // Always as tall as the card with every item showing, so a switch only changes the card, never the rows below
        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int height = dp(POPUP_TOP + 2 * POPUP_PADDING + ITEM_COUNT * ITEM_HEIGHT + POPUP_BOTTOM);
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
        }

        private View addItem(Context context, int index, int iconRes, Bitmap iconBitmap, int labelRes) {
            LinearLayout item = new LinearLayout(context);
            item.setOrientation(LinearLayout.HORIZONTAL);
            item.setGravity(Gravity.CENTER_VERTICAL);

            ImageView icon = new ImageView(context);
            icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
            if (iconBitmap != null) {
                icon.setImageBitmap(iconBitmap);
            } else {
                icon.setImageResource(iconRes);
            }
            item.addView(icon, LayoutHelper.createLinear(32, 32, Gravity.CENTER_VERTICAL, 16, 0, 16, 0));

            TextView label = new TextView(context);
            label.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
            label.setSingleLine(true);
            label.setEllipsize(android.text.TextUtils.TruncateAt.END);
            label.setText(getString(labelRes));
            item.addView(label, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL, 0, 0, 16, 0));
            labels[index] = label;

            popup.addView(item, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, ITEM_HEIGHT));
            return item;
        }

        void update() {
            setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
            popupBackground.setColor(Theme.getColor(Theme.key_windowBackgroundGray));
            for (TextView label : labels) {
                label.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            }
            ghostModeItem.setVisibility(GhostModeShortcut.isEnabled() ? VISIBLE : GONE);
            videoNoteItem.setVisibility(VideoNoteShortcut.isEnabled() ? VISIBLE : GONE);
        }
    }
}
