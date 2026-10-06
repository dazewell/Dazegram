package com.dazewell.gram.ui;

import static org.telegram.messenger.LocaleController.getString;

import android.animation.ValueAnimator;
import android.content.Context;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.view.accessibility.AccessibilityNodeInfo;

import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.TextSettingsCell;
import org.telegram.ui.Components.ColoredImageSpan;
import org.telegram.ui.Components.CubicBezierInterpolator;

/** Section title with a rotating disclosure arrow, paired with {@link CollapsibleFrame} in sections sheets. */
public final class DisclosureHeaderCell extends TextSettingsCell {
    private CharSequence titleText = "";
    private CharSequence summaryText = "";
    private boolean expanded;
    private boolean initialized;
    private float arrowRotation;
    private ValueAnimator arrowAnimator;

    public DisclosureHeaderCell(Context context) {
        this(context, null);
    }

    public DisclosureHeaderCell(Context context, Theme.ResourcesProvider resourcesProvider) {
        super(context, 21, resourcesProvider);
        setBackground(Theme.getSelectorDrawable(false, resourcesProvider));
        setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider));
    }

    public void bind(CharSequence title, CharSequence summary, boolean expanded) {
        boolean stateChanged = initialized && this.expanded != expanded;
        this.titleText = title;
        this.summaryText = summary;
        this.expanded = expanded;
        float targetRotation = expanded ? 180f : 0f;
        if (arrowAnimator != null) {
            arrowAnimator.cancel();
            arrowAnimator = null;
        }
        if (!initialized) {
            initialized = true;
            arrowRotation = targetRotation;
            applyText();
            return;
        }
        if (!stateChanged) {
            arrowRotation = targetRotation;
            applyText();
            return;
        }
        arrowAnimator = ValueAnimator.ofFloat(arrowRotation, targetRotation);
        arrowAnimator.setDuration(340);
        arrowAnimator.setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT);
        arrowAnimator.addUpdateListener(animator -> {
            arrowRotation = (float) animator.getAnimatedValue();
            applyText();
        });
        applyText();
        arrowAnimator.start();
    }

    private CharSequence composeAccessibilityText() {
        String state = getString(expanded ? R.string.AccDescrExpanded : R.string.AccDescrCollapsed);
        if (TextUtils.isEmpty(summaryText)) {
            return titleText + ", " + state;
        }
        return titleText + ", " + summaryText + ", " + state;
    }

    private void applyText() {
        SpannableStringBuilder titleWithArrow = new SpannableStringBuilder();
        titleWithArrow.append(titleText).append(' ');
        int spanStart = titleWithArrow.length();
        titleWithArrow.append('\uFFFC');
        ColoredImageSpan arrowSpan = new ColoredImageSpan(R.drawable.arrow_more);
        arrowSpan.setScale(0.6f, 0.6f);
        arrowSpan.rotate(arrowRotation);
        titleWithArrow.setSpan(arrowSpan, spanStart, spanStart + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        setTextAndValue(titleWithArrow, summaryText, false, expanded);
        setContentDescription(composeAccessibilityText());
    }

    @Override
    public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        info.setClassName(android.widget.Button.class.getName());
        info.setClickable(true);
        info.setText(composeAccessibilityText());
    }
}
