package xyz.nextalone.nagram.helpers;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.UserObject;
import org.telegram.ui.ActionBar.SimpleTextView;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.Components.AnimatedEmojiSpan;
import org.telegram.ui.Components.ChatActivityTopPanelLayout;
import org.telegram.ui.Components.FragmentContextView;
import org.telegram.ui.Components.LayoutHelper;

import xyz.nextalone.nagram.NaConfig;

/**
 * The chat's player strip. With CombinePinnedWithPlayer on, while both it and the pinned
 * message strip are shown, the pinned strip is hidden and a compact copy of it is drawn at
 * the leading edge of this row. The player keeps its full width, its children are pushed
 * right by left padding, so its progress line still spans the whole row.
 *
 * ChatActivity stays the owner of the pinned strip: its "wants it shown" flag is
 * {@code pinnedView.getTag() == null}, and only rows this class hid are ever shown again here.
 */
public class PinnedPlayerRow extends FrameLayout {

    private static final int MIN_COMPACT_DP = 96;
    private static final int MAX_COMPACT_DP = 136;
    private static final float COMPACT_FRACTION = 0.3f;
    // Leading play button and title margin in FragmentContextView.
    private static final int PLAYER_LEADING_DP = 35;
    private static final int MIN_TITLE_DP = 80;

    private ChatActivity chat;
    private View pinnedView;
    private View pinnedListButton;
    private CompactPinnedView compact;

    private boolean merged;
    private boolean hidPinned;
    private boolean applying;
    private int cancelledDraws;

    private final Runnable panelListener = this::apply;
    private final ViewTreeObserver.OnPreDrawListener preDrawListener = () -> {
        if (chat == null || !merged && !NaConfig.INSTANCE.getCombinePinnedWithPlayer().Bool()) {
            return true;
        }
        // Style, button or width changes reach us without a panel visibility change. Skip the
        // frame that still shows the old state; the guard stops a runaway if apply never settles.
        if (apply() && ++cancelledDraws <= 2) {
            return false;
        }
        cancelledDraws = 0;
        return true;
    };

    public PinnedPlayerRow(@NonNull Context context) {
        super(context);
    }

    public static void attachPinned(View row, ChatActivity chat, View pinnedView, View pinnedListButton) {
        if (!(row instanceof PinnedPlayerRow) || chat == null || pinnedView == null) {
            return;
        }
        final PinnedPlayerRow r = (PinnedPlayerRow) row;
        r.chat = chat;
        r.pinnedView = pinnedView;
        r.pinnedListButton = pinnedListButton;
        if (r.compact == null) {
            r.compact = r.new CompactPinnedView(r.getContext());
            r.compact.setVisibility(GONE);
            r.addView(r.compact, LayoutHelper.createFrame(MIN_COMPACT_DP, LayoutHelper.MATCH_PARENT, Gravity.LEFT | Gravity.TOP));
        }
        r.setPanelListener(true);
        r.apply();
    }

    public static void setPinnedContent(View row, CharSequence label, int number, CharSequence text, boolean buttonVariant) {
        if (!(row instanceof PinnedPlayerRow)) {
            return;
        }
        final PinnedPlayerRow r = (PinnedPlayerRow) row;
        if (r.compact == null) {
            return;
        }
        r.compact.buttonVariant = buttonVariant;
        r.compact.setContent(label, number, text);
        r.apply();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        getViewTreeObserver().addOnPreDrawListener(preDrawListener);
        setPanelListener(true);
    }

    @Override
    protected void onDetachedFromWindow() {
        getViewTreeObserver().removeOnPreDrawListener(preDrawListener);
        setPanelListener(false);
        super.onDetachedFromWindow();
    }

    private void setPanelListener(boolean set) {
        final ViewParent parent = getParent();
        if (parent instanceof ChatActivityTopPanelLayout) {
            final ChatActivityTopPanelLayout panel = (ChatActivityTopPanelLayout) parent;
            if (set && chat != null) {
                panel.naxOnViewVisibilityChanged = panelListener;
            } else if (!set && panel.naxOnViewVisibilityChanged == panelListener) {
                panel.naxOnViewVisibilityChanged = null;
            }
        }
    }

    private FragmentContextView findPlayer() {
        for (int i = 0, n = getChildCount(); i < n; i++) {
            final View child = getChildAt(i);
            if (child instanceof FragmentContextView) {
                return (FragmentContextView) child;
            }
        }
        return null;
    }

    private boolean longPressBlocked() {
        // Mirrors the guard on the pinned strip's own long-press in ChatActivity.createPinnedMessageView.
        return AndroidUtilities.isTablet() || chat.isThreadChat() && !UserObject.isBot(chat.getCurrentUser());
    }

    // 0 when the player would be left too little room for its title.
    private int compactWidth(FragmentContextView player) {
        final int rowWidth = getMeasuredWidth();
        if (rowWidth <= 0) {
            return 0;
        }
        int trailing = 0;
        for (int i = 0, n = player.getChildCount(); i < n; i++) {
            final View child = player.getChildAt(i);
            if (child.getVisibility() != VISIBLE || !(child.getLayoutParams() instanceof FrameLayout.LayoutParams)) {
                continue;
            }
            final FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) child.getLayoutParams();
            if (lp.gravity == -1 || (lp.gravity & Gravity.HORIZONTAL_GRAVITY_MASK) != Gravity.RIGHT) {
                continue;
            }
            final int width = lp.width > 0 ? lp.width : child.getMeasuredWidth();
            trailing = Math.max(trailing, lp.rightMargin + width);
        }
        final int fits = rowWidth - dp(PLAYER_LEADING_DP) - trailing - dp(MIN_TITLE_DP);
        final int width = Math.min(Math.max((int) (rowWidth * COMPACT_FRACTION), dp(MIN_COMPACT_DP)), dp(MAX_COMPACT_DP));
        final int result = Math.min(width, fits);
        return result >= dp(MIN_COMPACT_DP) ? result : 0;
    }

    // Returns whether anything changed.
    private boolean apply() {
        if (applying || chat == null || pinnedView == null || compact == null) {
            return false;
        }
        final ViewParent parent = getParent();
        final FragmentContextView player = findPlayer();
        if (!(parent instanceof ChatActivityTopPanelLayout) || player == null) {
            return false;
        }
        final ChatActivityTopPanelLayout panel = (ChatActivityTopPanelLayout) parent;
        final boolean pinnedWanted = pinnedView.getTag() == null;
        final int width = compactWidth(player);
        final boolean merge = NaConfig.INSTANCE.getCombinePinnedWithPlayer().Bool()
            && pinnedWanted
            && panel.isViewVisible(this)
            && player.getCurrentStyle() == FragmentContextView.STYLE_AUDIO_PLAYER
            && !compact.buttonVariant
            // The list button is the only way to the pinned list where long-press is blocked.
            && !(pinnedListButton != null && pinnedListButton.getTag() != null && longPressBlocked())
            && width > 0;

        boolean changed = false;
        applying = true;
        try {
            if (merge) {
                final ViewGroup.LayoutParams lp = compact.getLayoutParams();
                if (lp.width != width) {
                    lp.width = width;
                    compact.setLayoutParams(lp);
                    changed = true;
                }
                if (indexOfChild(compact) < indexOfChild(player)) {
                    compact.bringToFront();
                    changed = true;
                }
                if (compact.getVisibility() != VISIBLE) {
                    compact.setVisibility(VISIBLE);
                    changed = true;
                }
                if (player.getPaddingLeft() != width) {
                    player.setPadding(width, 0, 0, 0);
                    changed = true;
                }
                if (panel.isViewVisible(pinnedView)) {
                    panel.setViewVisible(pinnedView, false, true);
                    changed = true;
                }
                hidPinned = true;
            } else {
                if (compact.getVisibility() != GONE) {
                    compact.setVisibility(GONE);
                    changed = true;
                }
                if (player.getPaddingLeft() != 0) {
                    player.setPadding(0, 0, 0, 0);
                    changed = true;
                }
                if (hidPinned) {
                    hidPinned = false;
                    if (pinnedWanted) {
                        // The strip is never measured while hidden, so an animated cycle to the
                        // next pin never finished and its two text slots are out of step. A fresh
                        // non-animated rebuild puts the current pin back into slot 0.
                        chat.updatePinnedMessageView(true);
                        if (pinnedView.getTag() == null) {
                            panel.setViewVisible(pinnedView, true, true);
                        }
                        changed = true;
                    }
                }
            }
            merged = merge;
        } finally {
            applying = false;
        }
        return changed;
    }

    private class CompactPinnedView extends FrameLayout {

        private final SimpleTextView labelView;
        private final SimpleTextView textView;
        private boolean buttonVariant;
        private int labelColor;
        private int textColor;
        private float startY;
        private float lastY;

        CompactPinnedView(Context context) {
            super(context);
            setWillNotDraw(false);
            setBackground(Theme.getSelectorDrawable(false));
            // The strip's own performLongClick already gives the long-press haptic.
            setHapticFeedbackEnabled(false);
            final int left = InterfaceStyleController.applyChatHeader() ? 6 : 13;

            labelView = new SimpleTextView(context);
            labelView.setTextSize(12);
            labelView.setTypeface(AndroidUtilities.bold());
            labelView.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            addView(labelView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 16, Gravity.LEFT | Gravity.TOP, left, 3, 8, 0));

            textView = new SimpleTextView(context);
            textView.setTextSize(13);
            textView.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            addView(textView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 17, Gravity.LEFT | Gravity.TOP, left, 18, 8, 0));

            // performClick/performLongClick skip hit testing and the enabled flag, so the
            // strip's own listeners and its tablet/thread guard run, and preview mode is honoured here.
            setOnClickListener(v -> {
                if (pinnedView != null && pinnedView.isEnabled()) {
                    pinnedView.performClick();
                }
            });
            setOnLongClickListener(v -> {
                if (pinnedView == null || !pinnedView.isEnabled()) {
                    return false;
                }
                startY = lastY;
                return pinnedView.performLongClick();
            });
        }

        void setContent(CharSequence label, int number, CharSequence text) {
            final CharSequence fullLabel = number > 0 ? TextUtils.concat(label, " #" + number) : label;
            labelView.setText(fullLabel);
            // Animated emoji spans belong to one view each, so the strip's copy can't be shared.
            textView.setText(text == null ? null : AnimatedEmojiSpan.cloneSpans(text));
            setContentDescription(text == null ? fullLabel : TextUtils.concat(fullLabel, ": ", text));
        }

        @SuppressLint("ClickableViewAccessibility")
        @Override
        public boolean onTouchEvent(MotionEvent event) {
            // Same preview drag as the pinned strip's onTouchEvent.
            lastY = event.getY();
            if (chat != null) {
                if (event.getAction() == MotionEvent.ACTION_UP) {
                    chat.finishPreviewFragment();
                } else if (event.getAction() == MotionEvent.ACTION_MOVE) {
                    final float dy = startY - lastY;
                    chat.movePreviewFragment(dy);
                    if (dy < 0) {
                        startY = lastY;
                    }
                }
            }
            return super.onTouchEvent(event);
        }

        @Override
        protected void dispatchDraw(@NonNull Canvas canvas) {
            final Theme.ResourcesProvider resourcesProvider = chat != null ? chat.getResourceProvider() : null;
            final int label = Theme.getColor(Theme.key_chat_topPanelTitle, resourcesProvider);
            final int text = Theme.getColor(Theme.key_chat_topPanelMessage, resourcesProvider);
            if (label != labelColor) {
                labelColor = label;
                labelView.setTextColor(label);
            }
            if (text != textColor) {
                textColor = text;
                textView.setTextColor(text);
            }
            super.dispatchDraw(canvas);
            final float x = getWidth() - 1;
            canvas.drawLine(x, dp(8), x, getHeight() - dp(8), Theme.dividerPaint);
        }
    }
}
