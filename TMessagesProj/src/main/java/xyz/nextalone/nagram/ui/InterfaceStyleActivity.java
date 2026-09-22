package xyz.nextalone.nagram.ui;

import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LiteMode;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ActionBar.ThemeDescription;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.RadioButtonCell;
import org.telegram.ui.Cells.SlideIntChooseView;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Components.BatteryDrawable;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

import java.util.ArrayList;

import xyz.nextalone.nagram.NaConfig;
import xyz.nextalone.nagram.helpers.InterfaceStyleController;

public class InterfaceStyleActivity extends BaseFragment {

    private static final int TYPE_HEADER = 0;
    private static final int TYPE_RADIO = 1;
    private static final int TYPE_INFO = 2;
    private static final int TYPE_CHECK = 3;
    private static final int TYPE_SLIDER = 4;

    private static final int BLUR_STRENGTH_MIN = 0;
    private static final int BLUR_STRENGTH_MAX = 100;
    private static final int[] BLUR_STRENGTH_STEPS = {
            0, 10, 20, 30, 40, 50, 60, 70, 80, 90, 100
    };

    private int rowHeader;
    private int rowLiquidGlass;
    private int rowMaterialDesign3;
    private int rowApplyToHeader;
    private int rowApplyChatHeader;
    private int rowApplyChatListTopBar;
    private int rowApplyButtons;
    private int rowApplyBottomNavigation;
    private int rowBlurStrengthHeader;
    private int rowBlurStrength;
    private int rowBlurStrengthInfo;
    private int rowPanelColorsHeader;
    private int rowMatchClassicDayHeader;
    private int rowInfo;
    private int rowCount;

    private RecyclerListView listView;
    private ListAdapter listAdapter;
    private boolean interfaceStyleRebuildPending;

    @Override
    public View createView(Context context) {
        NaConfig.migrateComposerGlassTransparency(Theme.isCurrentThemeDark());

        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setTitle(getString(R.string.InterfaceStyle));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });

        fragmentView = new FrameLayout(context);
        fragmentView.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
        FrameLayout frameLayout = (FrameLayout) fragmentView;

        listView = new RecyclerListView(context);
        listView.setVerticalScrollBarEnabled(false);
        listView.setLayoutManager(new LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false));
        listView.setAdapter(listAdapter = new ListAdapter(context));
        listView.setOnItemClickListener((view, position, x, y) -> {
            if (LiteMode.isPowerSaverApplied()) {
                BulletinFactory.of(this).createSimpleBulletin(new BatteryDrawable(.1f, Color.WHITE, Theme.getColor(Theme.key_dialogSwipeRemove), 1.3f), getString(R.string.LiteBatteryRestricted)).show();
                return;
            }
            if (position == rowLiquidGlass) {
                if (LiteMode.isLiquidGlassSupported()) {
                    setLiquidGlassEnabled(true);
                }
            } else if (position == rowMaterialDesign3) {
                setLiquidGlassEnabled(false);
            } else if (position == rowApplyChatHeader) {
                boolean checked = NaConfig.INSTANCE.getInterfaceStyleApplyChatHeader().toggleConfigBool();
                ((TextCheckCell) view).setChecked(checked);
                reloadInterfaceStyle();
            } else if (position == rowApplyChatListTopBar) {
                boolean checked = NaConfig.INSTANCE.getInterfaceStyleApplyChatListTopBar().toggleConfigBool();
                ((TextCheckCell) view).setChecked(checked);
                reloadInterfaceStyle();
            } else if (position == rowApplyButtons) {
                boolean checked = NaConfig.INSTANCE.getInterfaceStyleApplyButtons().toggleConfigBool();
                ((TextCheckCell) view).setChecked(checked);
                reloadInterfaceStyle();
            } else if (position == rowApplyBottomNavigation) {
                boolean checked = NaConfig.INSTANCE.getInterfaceStyleApplyBottomNavigation().toggleConfigBool();
                ((TextCheckCell) view).setChecked(checked);
                reloadInterfaceStyle();
            } else if (position == rowMatchClassicDayHeader) {
                boolean checked = NaConfig.INSTANCE.getInterfaceStyleMatchClassicDayHeader().toggleConfigBool();
                ((TextCheckCell) view).setChecked(checked);
                reloadInterfaceStyle();
            }
        });
        frameLayout.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.TOP | Gravity.LEFT));

        return fragmentView;
    }

    @Override
    public void onResume() {
        super.onResume();
        if (listAdapter != null) {
            updateRows();
            listAdapter.notifyDataSetChanged();
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        flushInterfaceStyleRebuild();
    }

    @Override
    public void onFragmentDestroy() {
        flushInterfaceStyleRebuild();
        super.onFragmentDestroy();
    }

    @Override
    public boolean isSupportEdgeToEdge() {
        return true;
    }

    @Override
    public void onInsets(int left, int top, int right, int bottom) {
        listView.setPadding(0, 0, 0, bottom);
        listView.setClipToPadding(false);
    }

    private boolean liquidGlassSelected() {
        return !InterfaceStyleController.isMaterialDesign3();
    }

    private void setLiquidGlassEnabled(boolean enabled) {
        if (enabled == LiteMode.isEnabledSetting(LiteMode.FLAG_LIQUID_GLASS)) {
            return;
        }
        LiteMode.toggleFlag(LiteMode.FLAG_LIQUID_GLASS, enabled);
        reloadInterfaceStyle();
    }

    private void reloadInterfaceStyle() {
        updateRows();
        interfaceStyleRebuildPending = false;
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.reloadInterface);
        listAdapter.notifyDataSetChanged();
    }

    private void flushInterfaceStyleRebuild() {
        if (!interfaceStyleRebuildPending) {
            return;
        }
        interfaceStyleRebuildPending = false;
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.reloadInterface);
    }

    private static boolean isClassicOrDayTheme() {
        Theme.ThemeInfo themeInfo = Theme.getCurrentTheme();
        return themeInfo != null && ("Blue".equals(themeInfo.name) || "Day".equals(themeInfo.name));
    }

    private static SlideIntChooseView.Options blurStrengthOptions() {
        return SlideIntChooseView.Options.make(0, BLUR_STRENGTH_STEPS, 1,
                (type, value) -> value + "%");
    }

    private static int currentBlurStrength() {
        int percent = NaConfig.INSTANCE.getInterfaceStyleBlurStrength().Int();
        return Math.max(BLUR_STRENGTH_MIN, Math.min(BLUR_STRENGTH_MAX, percent));
    }

    private void updateRows() {
        int row = 0;
        rowHeader = row++;
        rowLiquidGlass = row++;
        rowMaterialDesign3 = row++;
        rowBlurStrengthHeader = row++;
        rowBlurStrength = row++;
        rowBlurStrengthInfo = row++;
        if (InterfaceStyleController.isMaterialDesign3()) {
            rowApplyToHeader = row++;
            rowApplyChatHeader = row++;
            rowApplyChatListTopBar = row++;
            rowApplyButtons = row++;
            rowApplyBottomNavigation = row++;
            if (isClassicOrDayTheme()) {
                rowPanelColorsHeader = row++;
                rowMatchClassicDayHeader = row++;
            } else {
                rowPanelColorsHeader = -1;
                rowMatchClassicDayHeader = -1;
            }
        } else {
            rowApplyToHeader = -1;
            rowApplyChatHeader = -1;
            rowApplyChatListTopBar = -1;
            rowApplyButtons = -1;
            rowApplyBottomNavigation = -1;
            rowPanelColorsHeader = -1;
            rowMatchClassicDayHeader = -1;
        }
        rowInfo = row++;
        rowCount = row;
    }

    private class ListAdapter extends RecyclerListView.SelectionAdapter {
        private final Context context;

        ListAdapter(Context context) {
            this.context = context;
            updateRows();
        }

        @Override
        public int getItemCount() {
            return rowCount;
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            int position = holder.getAdapterPosition();
            return position == rowMaterialDesign3
                    || position == rowLiquidGlass && LiteMode.isLiquidGlassSupported()
                    || rowApplyChatHeader >= 0 && position == rowApplyChatHeader
                    || rowApplyChatListTopBar >= 0 && position == rowApplyChatListTopBar
                    || rowApplyButtons >= 0 && position == rowApplyButtons
                    || rowApplyBottomNavigation >= 0 && position == rowApplyBottomNavigation
                    || rowMatchClassicDayHeader >= 0 && position == rowMatchClassicDayHeader;
        }

        @Override
        public int getItemViewType(int position) {
            if (position == rowHeader || position == rowApplyToHeader || position == rowBlurStrengthHeader || position == rowPanelColorsHeader) {
                return TYPE_HEADER;
            } else if (position == rowInfo || position == rowBlurStrengthInfo) {
                return TYPE_INFO;
            } else if (position == rowApplyChatHeader || position == rowApplyChatListTopBar || position == rowApplyButtons || position == rowApplyBottomNavigation || position == rowMatchClassicDayHeader) {
                return TYPE_CHECK;
            } else if (position == rowBlurStrength) {
                return TYPE_SLIDER;
            }
            return TYPE_RADIO;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view;
            if (viewType == TYPE_HEADER) {
                view = new HeaderCell(context);
                view.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
            } else if (viewType == TYPE_INFO) {
                view = new TextInfoPrivacyCell(context);
                view.setBackground(Theme.getThemedDrawable(context, R.drawable.greydivider, Theme.getColor(Theme.key_windowBackgroundGrayShadow)));
            } else if (viewType == TYPE_CHECK) {
                view = new TextCheckCell(context);
                view.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
            } else if (viewType == TYPE_SLIDER) {
                view = new SlideIntChooseView(context, null);
                view.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
            } else {
                view = new RadioButtonCell(context);
                view.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
            }
            view.setLayoutParams(new RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT));
            return new RecyclerListView.Holder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            if (position == rowHeader) {
                ((HeaderCell) holder.itemView).setText(getString(R.string.InterfaceStyleHeaderStyle));
            } else if (position == rowApplyToHeader) {
                ((HeaderCell) holder.itemView).setText(getString(R.string.InterfaceStyleApplyToHeader));
            } else if (position == rowBlurStrengthHeader) {
                ((HeaderCell) holder.itemView).setText(getString(R.string.InterfaceStyleBlurStrength));
            } else if (position == rowPanelColorsHeader) {
                ((HeaderCell) holder.itemView).setText(getString(R.string.InterfaceStylePanelColorsHeader));
            } else if (position == rowLiquidGlass) {
                RadioButtonCell cell = (RadioButtonCell) holder.itemView;
                if (LiteMode.isLiquidGlassSupported()) {
                    cell.setTextAndValue(getString(R.string.InterfaceStyleLiquidGlass), true, liquidGlassSelected());
                    cell.setAlpha(1.0f);
                } else {
                    cell.setTextAndValueAndCheck(getString(R.string.InterfaceStyleLiquidGlass), getString(R.string.InterfaceStyleLiquidGlassUnsupported), true, false);
                    cell.setAlpha(0.5f);
                }
            } else if (position == rowMaterialDesign3) {
                RadioButtonCell cell = (RadioButtonCell) holder.itemView;
                cell.setTextAndValue(getString(R.string.StyleMaterialDesign3), false, !liquidGlassSelected());
                cell.setAlpha(1.0f);
            } else if (position == rowApplyChatHeader) {
                TextCheckCell cell = (TextCheckCell) holder.itemView;
                cell.setTextAndValueAndCheck(getString(R.string.InterfaceStyleApplyChatHeader), getString(R.string.InterfaceStyleApplyChatHeaderInfo), NaConfig.INSTANCE.getInterfaceStyleApplyChatHeader().Bool(), true, true, true);
                cell.setEnabled(true, null);
            } else if (position == rowApplyChatListTopBar) {
                TextCheckCell cell = (TextCheckCell) holder.itemView;
                cell.setTextAndValueAndCheck(getString(R.string.InterfaceStyleApplyChatListTopBar), getString(R.string.InterfaceStyleApplyChatListTopBarInfo), NaConfig.INSTANCE.getInterfaceStyleApplyChatListTopBar().Bool(), true, true, true);
                cell.setEnabled(true, null);
            } else if (position == rowApplyButtons) {
                TextCheckCell cell = (TextCheckCell) holder.itemView;
                cell.setTextAndValueAndCheck(getString(R.string.InterfaceStyleApplyButtons), getString(R.string.InterfaceStyleApplyButtonsInfo), NaConfig.INSTANCE.getInterfaceStyleApplyButtons().Bool(), true, true, true);
                cell.setEnabled(true, null);
            } else if (position == rowApplyBottomNavigation) {
                TextCheckCell cell = (TextCheckCell) holder.itemView;
                cell.setTextAndValueAndCheck(getString(R.string.InterfaceStyleApplyBottomNavigation), getString(R.string.InterfaceStyleApplyBottomNavigationInfo), NaConfig.INSTANCE.getInterfaceStyleApplyBottomNavigation().Bool(), true, false, true);
                cell.setEnabled(true, null);
            } else if (position == rowBlurStrength) {
                SlideIntChooseView cell = (SlideIntChooseView) holder.itemView;
                cell.setLabel(getString(R.string.InterfaceStyleBlurStrengthAccDescr));
                cell.set(currentBlurStrength(), blurStrengthOptions(), value -> {
                    if (value == NaConfig.INSTANCE.getInterfaceStyleBlurStrength().Int()) {
                        return;
                    }
                    NaConfig.INSTANCE.getInterfaceStyleBlurStrength().setConfigInt(value);
                    interfaceStyleRebuildPending = true;
                });
            } else if (position == rowBlurStrengthInfo) {
                TextInfoPrivacyCell cell = (TextInfoPrivacyCell) holder.itemView;
                cell.setText(getString(R.string.InterfaceStyleBlurStrengthInfo));
                cell.setFixedSize(0);
            } else if (position == rowMatchClassicDayHeader) {
                TextCheckCell cell = (TextCheckCell) holder.itemView;
                cell.setTextAndValueAndCheck(getString(R.string.InterfaceStyleMatchClassicDayHeader), getString(R.string.InterfaceStyleMatchClassicDayHeaderInfo), NaConfig.INSTANCE.getInterfaceStyleMatchClassicDayHeader().Bool(), true, false, true);
                cell.setEnabled(true, null);
            } else if (position == rowInfo) {
                TextInfoPrivacyCell cell = (TextInfoPrivacyCell) holder.itemView;
                cell.setText(getString(R.string.InterfaceStyleInfo));
                cell.setFixedSize(0);
            }
        }
    }

    @Override
    public ArrayList<ThemeDescription> getThemeDescriptions() {
        ArrayList<ThemeDescription> themeDescriptions = new ArrayList<>();
        ThemeDescription.ThemeDescriptionDelegate delegate = () -> {
            if (listView == null) {
                return;
            }
            for (int i = 0; i < listView.getChildCount(); i++) {
                View child = listView.getChildAt(i);
                if (child instanceof SlideIntChooseView) {
                    ((SlideIntChooseView) child).updateColors();
                }
            }
        };
        themeDescriptions.add(new ThemeDescription(listView, ThemeDescription.FLAG_CELLBACKGROUNDCOLOR, new Class[]{HeaderCell.class, RadioButtonCell.class, TextCheckCell.class, SlideIntChooseView.class}, null, null, null, Theme.key_windowBackgroundWhite));
        themeDescriptions.add(new ThemeDescription(null, 0, null, null, null, delegate, Theme.key_windowBackgroundWhiteGrayText));
        themeDescriptions.add(new ThemeDescription(null, 0, null, null, null, delegate, Theme.key_windowBackgroundWhiteValueText));
        themeDescriptions.add(new ThemeDescription(fragmentView, ThemeDescription.FLAG_BACKGROUND, null, null, null, null, Theme.key_windowBackgroundGray));
        themeDescriptions.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_BACKGROUND, null, null, null, null, Theme.key_actionBarDefault));
        themeDescriptions.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_AB_ITEMSCOLOR, null, null, null, null, Theme.key_actionBarDefaultIcon));
        themeDescriptions.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_AB_TITLECOLOR, null, null, null, null, Theme.key_actionBarDefaultTitle));
        themeDescriptions.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_AB_SELECTORCOLOR, null, null, null, null, Theme.key_actionBarDefaultSelector));
        themeDescriptions.add(new ThemeDescription(listView, ThemeDescription.FLAG_LISTGLOWCOLOR, null, null, null, null, Theme.key_actionBarDefault));
        themeDescriptions.add(new ThemeDescription(listView, ThemeDescription.FLAG_SELECTOR, null, null, null, null, Theme.key_listSelector));
        themeDescriptions.add(new ThemeDescription(listView, 0, new Class[]{View.class}, Theme.dividerPaint, null, null, Theme.key_divider));
        themeDescriptions.add(new ThemeDescription(listView, ThemeDescription.FLAG_TEXTCOLOR, new Class[]{HeaderCell.class}, new String[]{"textView"}, null, null, null, Theme.key_windowBackgroundWhiteBlueHeader));
        themeDescriptions.add(new ThemeDescription(listView, ThemeDescription.FLAG_TEXTCOLOR, new Class[]{RadioButtonCell.class}, new String[]{"textView"}, null, null, null, Theme.key_windowBackgroundWhiteBlackText));
        themeDescriptions.add(new ThemeDescription(listView, ThemeDescription.FLAG_TEXTCOLOR, new Class[]{RadioButtonCell.class}, new String[]{"valueTextView"}, null, null, null, Theme.key_windowBackgroundWhiteGrayText2));
        themeDescriptions.add(new ThemeDescription(listView, ThemeDescription.FLAG_TEXTCOLOR, new Class[]{TextCheckCell.class}, new String[]{"textView"}, null, null, null, Theme.key_windowBackgroundWhiteBlackText));
        themeDescriptions.add(new ThemeDescription(listView, ThemeDescription.FLAG_TEXTCOLOR, new Class[]{TextCheckCell.class}, new String[]{"valueTextView"}, null, null, null, Theme.key_windowBackgroundWhiteGrayText2));
        themeDescriptions.add(new ThemeDescription(listView, ThemeDescription.FLAG_CHECKBOX, new Class[]{TextCheckCell.class}, new String[]{"checkBox"}, null, null, null, Theme.key_switchTrack));
        themeDescriptions.add(new ThemeDescription(listView, ThemeDescription.FLAG_CHECKBOXCHECK, new Class[]{TextCheckCell.class}, new String[]{"checkBox"}, null, null, null, Theme.key_switchTrackChecked));
        themeDescriptions.add(new ThemeDescription(listView, ThemeDescription.FLAG_CHECKBOX, new Class[]{RadioButtonCell.class}, new String[]{"radioButton"}, null, null, null, Theme.key_radioBackground));
        themeDescriptions.add(new ThemeDescription(listView, ThemeDescription.FLAG_CHECKBOXCHECK, new Class[]{RadioButtonCell.class}, new String[]{"radioButton"}, null, null, null, Theme.key_radioBackgroundChecked));
        themeDescriptions.add(new ThemeDescription(listView, ThemeDescription.FLAG_BACKGROUNDFILTER, new Class[]{TextInfoPrivacyCell.class}, null, null, null, Theme.key_windowBackgroundGrayShadow));
        themeDescriptions.add(new ThemeDescription(listView, ThemeDescription.FLAG_TEXTCOLOR, new Class[]{TextInfoPrivacyCell.class}, new String[]{"textView"}, null, null, null, Theme.key_windowBackgroundWhiteGrayText4));
        return themeDescriptions;
    }
}
