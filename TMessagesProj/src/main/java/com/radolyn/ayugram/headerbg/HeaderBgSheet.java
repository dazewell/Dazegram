package com.radolyn.ayugram.headerbg;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.radolyn.ayugram.ui.CollapsibleFrame;
import com.radolyn.ayugram.ui.DisclosureHeaderCell;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.browser.Browser;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Cells.TextSettingsCell;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.SectionsScrollView;
import org.telegram.ui.Components.SeekBarView;

import java.util.ArrayList;
import java.util.function.BooleanSupplier;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;

import xyz.nextalone.nagram.NaConfig;
import xyz.nextalone.nagram.helpers.InterfaceStyleController;

/**
 * The per-chat header background sheet. It never dims and is capped below the header, so every
 * change shows on the real header as it is made. Changes are kept as they are made: there is no
 * cancel, only Reset.
 */
public final class HeaderBgSheet {

    private static final int SWATCH_DP = 28;

    private HeaderBgSheet() {}

    public static void show(ChatActivity fragment) {
        if (fragment == null || fragment.getParentActivity() == null) {
            return;
        }
        if (!InterfaceStyleController.applyChatHeader()) {
            showUnavailable(fragment);
            return;
        }
        new HeaderBgSheet.Editor(fragment).show();
    }

    // Glass, or MD3 with the chat header left out: explain and link to the switch that fixes it.
    private static void showUnavailable(ChatActivity fragment) {
        Context context = fragment.getParentActivity();
        Theme.ResourcesProvider rp = fragment.getResourceProvider();
        boolean md3 = InterfaceStyleController.isMaterialDesign3();
        LinearLayout content = newContent(context);
        addTitle(content, rp);

        TextView info = new TextView(context);
        info.setText(getString(md3 ? R.string.HeaderBackgroundApplyOffInfo : R.string.HeaderBackgroundGlassInfo));
        info.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, rp));
        info.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        info.setLineSpacing(dp(2), 1f);
        info.setPadding(dp(21), dp(14), dp(21), dp(14));
        content.addView(info, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        BottomSheet[] sheet = new BottomSheet[1];
        TextSettingsCell open = new TextSettingsCell(context, 21, rp);
        open.setBackground(Theme.getSelectorDrawable(false, rp));
        open.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText4, rp));
        open.setText(getString(R.string.HeaderBackgroundOpenSettings), false);
        String row = md3 ? NaConfig.INSTANCE.getInterfaceStyleApplyChatHeader().getKey() : xyz.nextalone.nagram.ui.InterfaceStyleActivity.ROW_KEY_STYLE;
        open.setOnClickListener(v -> {
            if (sheet[0] != null) {
                sheet[0].dismiss();
            }
            Browser.openUrl(context, "https://t.me/nasettings/interface_style?r=" + row);
        });
        content.addView(open, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        LinearLayout buttons = newButtons(content, rp);
        addButton(buttons, getString(R.string.Close), rp, false, v -> sheet[0].dismiss());
        sheet[0] = present(fragment, content, false);
    }

    private static final class Editor {
        private final ChatActivity fragment;
        private final Context context;
        private final Theme.ResourcesProvider rp;
        private final int account;
        private final long dialogId;
        private final HeaderBgSettings s;
        private final boolean hasPhoto;
        private final ArrayList<Runnable> syncs = new ArrayList<>();
        private final ArrayList<GateLayout> bodies = new ArrayList<>();
        private BottomSheet sheet;

        Editor(ChatActivity fragment) {
            this.fragment = fragment;
            this.context = fragment.getParentActivity();
            this.rp = fragment.getResourceProvider();
            this.account = fragment.getCurrentAccount();
            this.dialogId = fragment.getDialogId();
            this.s = HeaderBgSettings.load(account, dialogId);
            HeaderBgDrawer drawer = HeaderBgDrawer.obtain(fragment);
            this.hasPhoto = drawer != null && drawer.hasPhoto();
        }

        // Re-obtained every time: a theme or language rebuild swaps the ActionBar under an open sheet.
        private void push() {
            HeaderBgDrawer drawer = HeaderBgDrawer.obtain(fragment);
            if (drawer != null) {
                drawer.settings.copyFrom(s);
                drawer.onStatusIconsChanged = statusSync;
                drawer.invalidate();
            }
        }

        private final Runnable statusSync = this::syncStatusBar;

        // While open, the sheet's own window draws the status bar, with icons set from the theme when it was
        // built; it takes the chat's current choice instead, so the icons follow the photo under it.
        private void syncStatusBar() {
            if (sheet == null || !sheet.isShowing()) {
                return;
            }
            // The window ORs every view's flag, and the sheet sets it on its container as well as the decor.
            boolean light = fragment.isLightStatusBar();
            AndroidUtilities.setLightStatusBar(sheet.getContainer(), light);
            AndroidUtilities.setLightStatusBar(sheet.getWindow(), light);
        }

        private void save() {
            s.save(account, dialogId);
        }

        private void changed(boolean persist) {
            push();
            if (persist) {
                save();
            }
            for (Runnable r : syncs) {
                r.run();
            }
        }

        void show() {
            LinearLayout content = newContent(context);
            addTitle(content, rp);

            TextCheckCell enableCell = new TextCheckCell(context, 21, false, rp);
            enableCell.setBackground(Theme.getSelectorDrawable(false, rp));
            enableCell.setOnClickListener(v -> {
                if (!hasPhoto && !s.enabled) {
                    return;
                }
                s.enabled = !s.enabled;
                changed(true);
            });
            syncs.add(() -> {
                enableCell.setTextAndCheck(getString(R.string.HeaderBackgroundUse), s.enabled, false);
                enableCell.setEnabled(hasPhoto || s.enabled);
                boolean active = s.enabled && hasPhoto;
                for (GateLayout body : bodies) {
                    body.setGateOpen(active);
                }
            });
            content.addView(enableCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
            if (!hasPhoto) {
                TextInfoPrivacyCell noPhoto = new TextInfoPrivacyCell(context, 21, rp);
                noPhoto.setText(getString(R.string.HeaderBackgroundNoPhoto));
                content.addView(noPhoto, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
            } else {
                addSpacer(content, 8);
            }

            GateLayout position = section(content, R.string.HeaderBackgroundPosition, true,
                    () -> signed(s.offsetX) + " · " + signed(s.offsetY) + " · " + s.zoom + "%");
            slider(position, R.string.HeaderBackgroundOffsetX, -100, 100, () -> s.offsetX, v -> s.offsetX = v, true);
            slider(position, R.string.HeaderBackgroundOffsetY, -100, 100, () -> s.offsetY, v -> s.offsetY = v, true);
            slider(position, R.string.HeaderBackgroundZoom, 100, 300, () -> s.zoom, v -> s.zoom = v, false);
            TextCheckCell extendCell = new TextCheckCell(context, 21, false, rp);
            extendCell.setBackground(Theme.getSelectorDrawable(false, rp));
            extendCell.setOnClickListener(v -> {
                s.extendPanel = !s.extendPanel;
                changed(true);
            });
            syncs.add(() -> extendCell.setTextAndValueAndCheck(getString(R.string.HeaderBackgroundExtend), getString(R.string.HeaderBackgroundExtendInfo), s.extendPanel, true, false));
            position.addView(extendCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
            addSpacer(content, 8);

            GateLayout look = section(content, R.string.HeaderBackgroundLook, false,
                    () -> s.opacity + "% · " + tintName(s.tintHue));
            slider(look, R.string.HeaderBackgroundOpacity, 10, 100, () -> s.opacity, v -> s.opacity = v, false);
            slider(look, R.string.HeaderBackgroundBlur, 0, 100, () -> s.blur, v -> s.blur = v, false);
            slider(look, R.string.HeaderBackgroundDesaturate, 0, 100, () -> s.desaturate, v -> s.desaturate = v, false);
            tintRow(look);
            View tintStrength = slider(look, R.string.HeaderBackgroundTintStrength, 0, 100, () -> s.tintStrength, v -> s.tintStrength = v, false);
            syncs.add(() -> setRowEnabled(tintStrength, s.tintHue != HeaderBgSettings.TINT_AUTO));
            TextSettingsCell alternateCell = choice(look, () -> true, () -> s.alternate = (s.alternate + 1) % (HeaderBgSettings.ALT_PIN + 1));
            syncs.add(() -> alternateCell.setTextAndValue(getString(R.string.HeaderBackgroundAlternate), alternateName(s.alternate), false));
            addSpacer(content, 8);

            // The choices sit together under the switch, where a tap target reads as one; the sliders follow.
            GateLayout gradient = section(content, R.string.HeaderBackgroundGradient, false,
                    () -> s.gradient ? s.gradientStrength + "% · " + fromName(s.gradientFrom) + " · " + curveName(s.gradientCurve)
                            : getString(R.string.HeaderBackgroundGradientOff));
            TextCheckCell gradientCell = new TextCheckCell(context, 21, false, rp);
            gradientCell.setBackground(Theme.getSelectorDrawable(false, rp));
            gradientCell.setOnClickListener(v -> {
                s.gradient = !s.gradient;
                changed(true);
            });
            syncs.add(() -> gradientCell.setTextAndValueAndCheck(getString(R.string.HeaderBackgroundGradient), getString(R.string.HeaderBackgroundGradientInfo), s.gradient, true, false));
            gradient.addView(gradientCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
            TextSettingsCell fromCell = choice(gradient, () -> s.gradient, () -> s.gradientFrom = (s.gradientFrom + 1) % 3);
            TextSettingsCell curveCell = choice(gradient, () -> s.gradient, () -> s.gradientCurve = (s.gradientCurve + 1) % (HeaderBgSettings.CURVE_SMOOTH + 1));
            View gradientStrength = slider(gradient, R.string.HeaderBackgroundGradientStrength, 0, 100, () -> s.gradientStrength, v -> s.gradientStrength = v, false);
            View fadeRange = rangeSlider(gradient);
            syncs.add(() -> {
                fromCell.setTextAndValue(getString(R.string.HeaderBackgroundGradientFrom), fromName(s.gradientFrom), true);
                curveCell.setTextAndValue(getString(R.string.HeaderBackgroundGradientCurve), curveName(s.gradientCurve), false);
                setRowEnabled(fromCell, s.gradient);
                setRowEnabled(curveCell, s.gradient);
                setRowEnabled(gradientStrength, s.gradient);
                setRowEnabled(fadeRange, s.gradient);
            });

            LinearLayout buttons = newButtons(content, rp);
            addButton(buttons, getString(R.string.Reset), rp, true, v -> {
                s.resetLook();
                changed(true);
            });
            addButton(buttons, getString(R.string.Done), rp, false, v -> sheet.dismiss());

            for (Runnable r : syncs) {
                r.run();
            }
            sheet = present(fragment, content, true);
            push();
            syncStatusBar();
            // A drag the sheet closed under never reported its stop.
            sheet.setOnHideListener(d -> {
                save();
                HeaderBgDrawer drawer = HeaderBgDrawer.obtain(fragment);
                if (drawer != null && drawer.onStatusIconsChanged == statusSync) {
                    drawer.onStatusIconsChanged = null;
                }
            });
        }

        private GateLayout section(LinearLayout content, int title, boolean expanded, java.util.function.Supplier<CharSequence> summary) {
            DisclosureHeaderCell header = new DisclosureHeaderCell(context, rp);
            content.addView(header, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
            GateLayout body = new GateLayout(context);
            body.setOrientation(LinearLayout.VERTICAL);
            bodies.add(body);
            CollapsibleFrame frame = new CollapsibleFrame(context, body);
            content.addView(frame, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
            boolean[] open = {expanded};
            frame.setExpanded(expanded, false);
            syncs.add(() -> header.bind(getString(title), summary.get(), open[0]));
            header.setOnClickListener(v -> {
                open[0] = !open[0];
                frame.setExpanded(open[0], true);
                header.bind(getString(title), summary.get(), open[0]);
            });
            return body;
        }

        // A row that cycles its value on tap; ignored while it is not enabled.
        private TextSettingsCell choice(LinearLayout parent, BooleanSupplier enabled, Runnable next) {
            TextSettingsCell cell = new TextSettingsCell(context, 21, rp);
            cell.setBackground(Theme.getSelectorDrawable(false, rp));
            cell.setOnClickListener(v -> {
                if (!enabled.getAsBoolean()) {
                    return;
                }
                next.run();
                changed(true);
            });
            parent.addView(cell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
            return cell;
        }

        private View rangeSlider(LinearLayout parent) {
            GateLayout row = new GateLayout(context);
            row.setOrientation(LinearLayout.VERTICAL);
            TextView valueView = sliderHeader(row, R.string.HeaderBackgroundGradientRange);
            RangeSeekBar bar = new RangeSeekBar(context, 100, HeaderBgSettings.MIN_FADE_SPAN, rp);
            Runnable label = () -> valueView.setText(s.gradientStart + "% – " + s.gradientEnd + "%");
            bar.setDelegate(new RangeSeekBar.Delegate() {
                @Override
                public void onRangeChanged(int start, int end, boolean stop) {
                    s.gradientStart = start;
                    s.gradientEnd = end;
                    label.run();
                    push();
                    if (stop) {
                        changed(true);
                    }
                }

                @Override
                public CharSequence describe(boolean endHandle, int value) {
                    return getString(endHandle ? R.string.HeaderBackgroundGradientEnd : R.string.HeaderBackgroundGradientStart) + ", " + value + "%";
                }
            });
            row.addView(bar, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48, 13, 0, 13, 0));
            syncs.add(() -> {
                label.run();
                if (!bar.isDragging()) {
                    bar.setRange(s.gradientStart, s.gradientEnd);
                }
            });
            parent.addView(row, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
            return row;
        }

        // The title on the left and the live value on the right, above a slider; returns the value view.
        private TextView sliderHeader(LinearLayout row, int title) {
            LinearLayout header = new LinearLayout(context);
            header.setOrientation(LinearLayout.HORIZONTAL);
            header.setGravity(Gravity.CENTER_VERTICAL);
            row.addView(header, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 21, 8, 21, 0));

            TextView titleView = new TextView(context);
            titleView.setText(getString(title));
            titleView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, rp));
            titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
            titleView.setSingleLine(true);
            titleView.setEllipsize(TextUtils.TruncateAt.END);
            header.addView(titleView, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));

            TextView valueView = new TextView(context);
            valueView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, rp));
            valueView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            header.addView(valueView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));
            return valueView;
        }

        private View slider(LinearLayout parent, int title, int min, int max, IntSupplier get, IntConsumer set, boolean signed) {
            // A gate, not setEnabled: SeekBarView takes drags whether or not it is enabled.
            GateLayout row = new GateLayout(context);
            row.setOrientation(LinearLayout.VERTICAL);
            TextView valueView = sliderHeader(row, title);

            SeekBarView bar = new SeekBarView(context, rp);
            bar.setReportChanges(true);
            bar.setDelegate(new SeekBarView.SeekBarViewDelegate() {
                @Override
                public void onSeekBarDrag(boolean stop, float progress) {
                    set.accept(min + Math.round(progress * (max - min)));
                    valueView.setText(format(get.getAsInt(), signed));
                    push();
                    if (stop) {
                        changed(true);
                    }
                }

                @Override
                public CharSequence getContentDescription() {
                    return getString(title) + ", " + valueView.getText();
                }

                @Override
                public int getStepsCount() {
                    return max - min;
                }
            });
            // 48dp rather than the app-wide 38dp, as in the trigger sheet, to clear the touch-target minimum.
            row.addView(bar, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48, 13, 0, 13, 0));
            Runnable place = () -> {
                if (!bar.isDragging()) {
                    bar.setProgress((get.getAsInt() - min) / (float) (max - min));
                }
            };
            syncs.add(() -> {
                valueView.setText(format(get.getAsInt(), signed));
                place.run();
            });
            // SeekBarView keeps the thumb in pixels from whatever width it had when the value was set, and
            // the sheet measures it at more than one width while opening, so re-place it after every layout.
            // setProgress only invalidates, so this can't loop.
            bar.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> place.run());
            parent.addView(row, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
            return row;
        }

        private void tintRow(LinearLayout parent) {
            TextView label = new TextView(context);
            label.setText(getString(R.string.HeaderBackgroundTint));
            label.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, rp));
            label.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
            parent.addView(label, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 21, 8, 21, 0));

            LinearLayout row = new LinearLayout(context);
            row.setOrientation(LinearLayout.HORIZONTAL);
            int count = Theme.keys_avatar_background.length;
            for (int i = HeaderBgSettings.TINT_THEME; i < count; i++) {
                final int hue = i;
                ImageView swatch = new ImageView(context);
                swatch.setScaleType(ImageView.ScaleType.CENTER);
                swatch.setContentDescription(tintName(hue));
                swatch.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector, rp), Theme.RIPPLE_MASK_CIRCLE_20DP));
                swatch.setAccessibilityDelegate(new View.AccessibilityDelegate() {
                    @Override
                    public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                        super.onInitializeAccessibilityNodeInfo(host, info);
                        info.setClassName("android.widget.RadioButton");
                        info.setCheckable(true);
                        info.setChecked(s.tintHue == hue);
                    }
                });
                swatch.setOnClickListener(v -> {
                    if (s.tintHue == hue) {
                        return;
                    }
                    s.tintHue = hue;
                    changed(true);
                });
                syncs.add(() -> swatch.setImageDrawable(swatchDrawable(hue, s.tintHue == hue)));
                row.addView(swatch, LayoutHelper.createLinear(0, 48, 1f));
            }
            parent.addView(row, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 8, 0, 8, 0));
        }

        // Same circle-and-ring look as the privacy profile colours. Auto is an empty ring; Theme is the
        // accent with a palette mark, so it doesn't read as just another blue.
        private Drawable swatchDrawable(int hue, boolean selected) {
            int size = dp(SWATCH_DP);
            int ring = Theme.getColor(Theme.key_featuredStickers_addButton, rp);
            if (hue == HeaderBgSettings.TINT_AUTO) {
                return Theme.createOutlineCircleDrawable(size, selected ? ring : Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, rp), dp(2));
            }
            int color = HeaderBgDrawer.tintColor(hue, rp);
            int inner = selected ? dp(SWATCH_DP - 8) : size;
            ArrayList<Drawable> layers = new ArrayList<>();
            layers.add(Theme.createCircleDrawable(inner, color));
            if (hue == HeaderBgSettings.TINT_THEME) {
                Drawable mark = context.getResources().getDrawable(R.drawable.msg_theme).mutate();
                mark.setColorFilter(new android.graphics.PorterDuffColorFilter(Theme.getColor(Theme.key_featuredStickers_buttonText, rp), android.graphics.PorterDuff.Mode.SRC_IN));
                layers.add(mark);
            }
            if (selected) {
                layers.add(Theme.createOutlineCircleDrawable(size, ring, dp(2)));
            }
            LayerDrawable layered = new LayerDrawable(layers.toArray(new Drawable[0]));
            int inset = (size - inner) / 2;
            layered.setLayerInset(0, inset, inset, inset, inset);
            if (hue == HeaderBgSettings.TINT_THEME) {
                int markInset = (size - dp(18)) / 2;
                layered.setLayerInset(1, markInset, markInset, markInset, markInset);
            }
            return layered;
        }
    }

    /** A section body that greys out and swallows touches while the feature is off. */
    private static final class GateLayout extends LinearLayout {
        private boolean open = true;

        GateLayout(Context context) {
            super(context);
        }

        void setGateOpen(boolean open) {
            this.open = open;
            setAlpha(open ? 1f : 0.5f);
            // Touch interception doesn't stop accessibility actions, so hide the controls from them too.
            setImportantForAccessibility(open ? IMPORTANT_FOR_ACCESSIBILITY_AUTO : IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        }

        @Override
        public boolean onInterceptTouchEvent(MotionEvent ev) {
            return !open || super.onInterceptTouchEvent(ev);
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            return !open || super.onTouchEvent(event);
        }
    }

    private static void setRowEnabled(View row, boolean enabled) {
        if (row instanceof GateLayout) {
            ((GateLayout) row).setGateOpen(enabled);
            return;
        }
        row.setAlpha(enabled ? 1f : 0.5f);
        setEnabledDeep(row, enabled);
    }

    private static void setEnabledDeep(View view, boolean enabled) {
        view.setEnabled(enabled);
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                setEnabledDeep(group.getChildAt(i), enabled);
            }
        }
    }

    private static String format(int value, boolean signed) {
        return signed ? signed(value) : value + "%";
    }

    private static String signed(int value) {
        return (value > 0 ? "+" : "") + value + "%";
    }

    private static String tintName(int hue) {
        if (hue == HeaderBgSettings.TINT_AUTO) {
            return getString(R.string.HeaderBackgroundTintAuto);
        }
        if (hue == HeaderBgSettings.TINT_THEME) {
            return getString(R.string.HeaderBackgroundTintTheme);
        }
        return AvatarDrawable.colorName(hue);
    }

    private static String fromName(int from) {
        if (from == HeaderBgSettings.FROM_TOP) {
            return getString(R.string.HeaderBackgroundFromTop);
        }
        if (from == HeaderBgSettings.FROM_BOTTOM) {
            return getString(R.string.HeaderBackgroundFromBottom);
        }
        return getString(R.string.HeaderBackgroundFromTitle);
    }

    private static String alternateName(int alternate) {
        switch (alternate) {
            case HeaderBgSettings.ALT_HEADER:
                return getString(R.string.HeaderBackgroundAlternateHeader);
            case HeaderBgSettings.ALT_BOTH:
                return getString(R.string.HeaderBackgroundAlternateBoth);
            case HeaderBgSettings.ALT_PIN:
                return getString(R.string.HeaderBackgroundAlternatePin);
            default:
                return getString(R.string.HeaderBackgroundGradientOff);
        }
    }

    private static String curveName(int curve) {
        switch (curve) {
            case HeaderBgSettings.CURVE_EASE_IN:
                return getString(R.string.HeaderBackgroundCurveEaseIn);
            case HeaderBgSettings.CURVE_EASE_OUT:
                return getString(R.string.HeaderBackgroundCurveEaseOut);
            case HeaderBgSettings.CURVE_SMOOTH:
                return getString(R.string.HeaderBackgroundCurveSmooth);
            default:
                return getString(R.string.HeaderBackgroundCurveLinear);
        }
    }

    private static LinearLayout newContent(Context context) {
        LinearLayout content = new SectionsScrollView.SectionsLinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        return content;
    }

    private static void addTitle(LinearLayout content, Theme.ResourcesProvider rp) {
        TextView title = new TextView(content.getContext());
        title.setText(getString(R.string.HeaderBackground));
        title.setTextColor(Theme.getColor(Theme.key_dialogTextBlack, rp));
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
        title.setTypeface(AndroidUtilities.bold());
        title.setTag(RecyclerListView.TAG_NOT_SECTION);
        content.addView(title, LayoutHelper.createLinearRelatively(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.START | Gravity.TOP, 22, 12, 22, 12));
    }

    private static void addSpacer(LinearLayout content, int heightDp) {
        View spacer = new View(content.getContext());
        spacer.setTag(RecyclerListView.TAG_NOT_SECTION);
        content.addView(spacer, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, heightDp));
    }

    private static LinearLayout newButtons(LinearLayout content, Theme.ResourcesProvider rp) {
        LinearLayout buttons = new LinearLayout(content.getContext());
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setTag(RecyclerListView.TAG_NOT_SECTION);
        content.addView(buttons, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 50, 0, 8, 0, 0));
        return buttons;
    }

    private static void addButton(LinearLayout buttons, String text, Theme.ResourcesProvider rp, boolean start, View.OnClickListener listener) {
        TextView button = new TextView(buttons.getContext());
        button.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        button.setTextColor(Theme.getColor(Theme.key_dialogTextBlue4, rp));
        button.setGravity(Gravity.CENTER);
        button.setSingleLine(true);
        button.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_dialogButtonSelector, rp), 0));
        button.setPadding(dp(18), 0, dp(18), 0);
        button.setText(text);
        button.setTypeface(AndroidUtilities.bold());
        button.setOnClickListener(listener);
        if (!start && buttons.getChildCount() == 0) {
            buttons.addView(new View(buttons.getContext()), LayoutHelper.createLinear(0, 1, 1f));
        }
        buttons.addView(button, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.MATCH_PARENT));
        if (start) {
            buttons.addView(new View(buttons.getContext()), LayoutHelper.createLinear(0, 1, 1f));
        }
    }

    private static BottomSheet present(ChatActivity fragment, LinearLayout content, boolean capBelowHeader) {
        Context context = fragment.getParentActivity();
        Theme.ResourcesProvider rp = fragment.getResourceProvider();
        SectionsScrollView scrollView = new SectionsScrollView(context, content, rp, true);
        scrollView.setFillViewport(true);
        scrollView.setVerticalScrollBarEnabled(false);
        scrollView.addView(content, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        ActionBar actionBar = fragment.getActionBar();
        LinearLayout wrapper = new LinearLayout(context) {
            @Override
            protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                if (capBelowHeader) {
                    // Stop short of the header so it stays fully visible above the sheet.
                    int header = actionBar != null ? actionBar.getHeight() : 0;
                    int cap = Math.max(dp(240), AndroidUtilities.displaySize.y - header - dp(48));
                    int size = MeasureSpec.getSize(heightMeasureSpec);
                    int mode = MeasureSpec.getMode(heightMeasureSpec);
                    heightMeasureSpec = MeasureSpec.makeMeasureSpec(mode == MeasureSpec.UNSPECIFIED ? cap : Math.min(size, cap),
                            mode == MeasureSpec.EXACTLY ? MeasureSpec.EXACTLY : MeasureSpec.AT_MOST);
                }
                super.onMeasure(widthMeasureSpec, heightMeasureSpec);
            }
        };
        wrapper.setOrientation(LinearLayout.VERTICAL);
        wrapper.addView(scrollView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        BottomSheet.Builder builder = new BottomSheet.Builder(context, false, rp);
        builder.setCustomView(wrapper);
        BottomSheet sheet = builder.create();
        int gray = Theme.getColor(Theme.key_windowBackgroundGray, rp);
        sheet.setBackgroundColor(gray);
        sheet.fixNavigationBar(gray);
        // Undimmed so the live header above reads true to what will stay.
        sheet.setDimBehind(false);
        fragment.showDialog(sheet);
        return sheet;
    }
}
