package com.dazewell.gram.headerbg;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.TextView;

import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.SimpleTextView;
import org.telegram.ui.Components.AnimatedTextView;
import org.telegram.ui.Components.ChatAvatarContainer;
import org.telegram.ui.Components.NumberTextView;
import org.telegram.ui.Components.PinnedLineView;

/**
 * The translucent backings the two pill text modes draw behind the header's and the pinned bar's text and buttons.
 * Their shapes come from the children's geometry, which can change without the parent redrawing (a new subtitle or
 * a pinned swap only re-records the child), so the drawer measures them before every frame and invalidates the
 * parent only when a shape moved; the draw passes just paint what was measured. Main thread only.
 */
final class HeaderBgPills {

    private static final int MAX_CIRCLES = 8;
    // Wider than this, an action bar child is the expanded search field, not an icon button.
    private static final int MAX_ICON_DP = 56;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Shapes header = new Shapes();
    private final Shapes panel = new Shapes();
    private final Shapes scratch = new Shapes();
    private final RectF compact = new RectF();
    // A located view's x, y and combined alpha in the ancestor's coordinates.
    private final float[] loc = new float[3];

    /** The backing colour for a text mode, or 0 for the modes that have none. */
    static int fill(int mode) {
        if (mode == HeaderBgForeground.DARK_PILL) {
            return 0x66000000;
        }
        if (mode == HeaderBgForeground.LIGHT_PILL) {
            return 0x8cffffff;
        }
        return 0;
    }

    /** The header's shapes in ActionBar coordinates, or none with a null bar. True when they changed. */
    boolean measureHeader(ActionBar bar, ChatAvatarContainer container) {
        scratch.clear();
        if (bar != null && !bar.isSearchFieldVisible()) {
            if (container != null && locate(container, bar)) {
                float alpha = loc[2];
                SimpleTextView title = container.getTitleTextView();
                if (title != null && locate(title, bar)) {
                    int start = textStart(title);
                    float width = Math.min(title.getWidth() - start, title.getTextWidth() + title.getRightDrawableWidth());
                    scratch.union(loc[0] + start, loc[1], loc[0] + start + width, loc[1] + title.getHeight());
                }
                View subtitle = container.getSubtitleTextView();
                if (subtitle instanceof SimpleTextView && locate(subtitle, bar)) {
                    SimpleTextView s = (SimpleTextView) subtitle;
                    int start = textStart(s);
                    float width = Math.min(s.getWidth() - start, s.getTextWidth());
                    scratch.union(loc[0] + start, loc[1], loc[0] + start + width, loc[1] + s.getHeight());
                } else if (subtitle instanceof AnimatedTextView && locate(subtitle, bar)) {
                    AnimatedTextView s = (AnimatedTextView) subtitle;
                    int start = s.getPaddingLeft();
                    float width = Math.min(s.getWidth() - start, s.getDrawable().getCurrentWidth());
                    scratch.union(loc[0] + start, loc[1], loc[0] + start + width, loc[1] + s.getHeight());
                }
                // The time-zone chip is the container's only plain TextView, and the field holding it is private.
                for (int i = 0; i < container.getChildCount(); i++) {
                    View child = container.getChildAt(i);
                    if (child instanceof TextView && locate(child, bar)) {
                        scratch.union(loc[0], loc[1], loc[0] + child.getWidth(), loc[1] + child.getHeight());
                    }
                }
                if (scratch.hasBlock) {
                    scratch.block.inset(-dp(8), -dp(4));
                    scratch.blockAlpha = alpha;
                }
            }
            addCircle(bar.getBackButton(), bar, dp(20));
            if (bar.menu != null) {
                for (int i = 0; i < bar.menu.getChildCount(); i++) {
                    View item = bar.menu.getChildAt(i);
                    if (item.getWidth() <= dp(MAX_ICON_DP)) {
                        addCircle(item, bar, dp(20));
                    }
                }
            }
        }
        return header.takeIfChanged(scratch);
    }

    /** The pinned strip's shapes in the panel's coordinates, or none with a null panel. True when they changed. */
    boolean measurePanel(View panelView, HeaderBgForeground.PinnedViews views) {
        scratch.clear();
        SimpleTextView[] names = views != null ? views.names.get() : null;
        ViewParent parent = names != null && names[0] != null ? names[0].getParent() : null;
        if (panelView != null && parent instanceof ViewGroup && locate((View) parent, panelView)) {
            ViewGroup strip = (ViewGroup) parent;
            float x = loc[0];
            float y = loc[1];
            scratch.blockAlpha = loc[2];
            float textLeft = Float.MAX_VALUE;
            float textRight = -Float.MAX_VALUE;
            float lineLeft = Float.MAX_VALUE;
            float buttonsLeft = Float.MAX_VALUE;
            for (int i = 0; i < strip.getChildCount(); i++) {
                View child = strip.getChildAt(i);
                if (child.getVisibility() != View.VISIBLE || child.getAlpha() <= 0f) {
                    continue;
                }
                float left = child.getX();
                float textWidth = -1;
                if (child instanceof PinnedLineView) {
                    lineLeft = Math.min(lineLeft, left);
                } else if (child instanceof SimpleTextView) {
                    SimpleTextView t = (SimpleTextView) child;
                    left += textStart(t);
                    textWidth = Math.min(t.getWidth(), t.getTextWidth());
                } else if (child instanceof NumberTextView) {
                    textWidth = ((NumberTextView) child).getTextWidth();
                } else if (child == views.listButton.get() || child == views.closeButton.get() || child instanceof TextView) {
                    // The list and close buttons, and the strip's inline button, which is the only TextView in it.
                    buttonsLeft = Math.min(buttonsLeft, left);
                }
                if (textWidth > 0) {
                    textLeft = Math.min(textLeft, left);
                    textRight = Math.max(textRight, left + textWidth);
                }
            }
            if (textRight > textLeft) {
                float left = lineLeft != Float.MAX_VALUE ? lineLeft - dp(8) : textLeft - dp(10);
                float right = Math.min(textRight + dp(10), buttonsLeft - dp(4));
                float height = Math.min(strip.getHeight(), dp(48));
                if (right > left) {
                    scratch.union(x + left, y + dp(4), x + right, y + height - dp(4));
                }
            }
            addCircle(views.listButton.get(), panelView, dp(18));
            addCircle(views.closeButton.get(), panelView, dp(18));
        }
        return panel.takeIfChanged(scratch);
    }

    void drawHeader(Canvas canvas, int fill, float alpha) {
        draw(canvas, header, fill, alpha);
    }

    void drawPanel(Canvas canvas, int fill, float alpha) {
        draw(canvas, panel, fill, alpha);
    }

    /** The combined player row's compact pinned copy: one backing over the whole view, which holds only its text. */
    void drawCompact(Canvas canvas, int fill, int width, int height) {
        if (fill == 0) {
            return;
        }
        paint.setColor(fill);
        compact.set(dp(2), dp(4), width - dp(4), height - dp(4));
        canvas.drawRoundRect(compact, dp(12), dp(12), paint);
    }

    private void draw(Canvas canvas, Shapes s, int fill, float alpha) {
        if (fill == 0 || alpha <= 0f) {
            return;
        }
        int base = Color.alpha(fill);
        paint.setColor(fill);
        if (s.hasBlock) {
            paint.setAlpha((int) (base * alpha * s.blockAlpha));
            canvas.drawRoundRect(s.block, dp(12), dp(12), paint);
        }
        for (int i = 0; i < s.count; i++) {
            float[] c = s.circles;
            paint.setAlpha((int) (base * alpha * c[i * 4 + 3]));
            canvas.drawCircle(c[i * 4], c[i * 4 + 1], c[i * 4 + 2], paint);
        }
    }

    private void addCircle(View view, View ancestor, int radius) {
        if (view == null || scratch.count >= MAX_CIRCLES || view.getWidth() <= 0 || !locate(view, ancestor)) {
            return;
        }
        int i = scratch.count++ * 4;
        scratch.circles[i] = loc[0] + view.getWidth() / 2f;
        scratch.circles[i + 1] = loc[1] + view.getHeight() / 2f;
        scratch.circles[i + 2] = radius;
        scratch.circles[i + 3] = loc[2];
    }

    // Where the text starts inside the view. getTextStartX() already adds the view's own position in its parent.
    private static int textStart(SimpleTextView view) {
        return view.getTextStartX() - (int) view.getX();
    }

    // Fills loc with the view's position and alpha relative to the ancestor. False when it is not under it,
    // or it or anything between them is hidden.
    private boolean locate(View view, View ancestor) {
        float x = 0, y = 0, alpha = 1f;
        View v = view;
        while (v != ancestor) {
            if (v == null || v.getVisibility() != View.VISIBLE) {
                return false;
            }
            x += v.getX();
            y += v.getY();
            alpha *= v.getAlpha();
            ViewParent p = v.getParent();
            v = p instanceof View ? (View) p : null;
        }
        loc[0] = x;
        loc[1] = y;
        loc[2] = alpha;
        return alpha > 0f;
    }

    private static final class Shapes {
        final RectF block = new RectF();
        boolean hasBlock;
        float blockAlpha;
        // cx, cy, radius, alpha per circle.
        final float[] circles = new float[MAX_CIRCLES * 4];
        int count;

        void clear() {
            hasBlock = false;
            blockAlpha = 0f;
            count = 0;
        }

        void union(float left, float top, float right, float bottom) {
            if (right <= left || bottom <= top) {
                return;
            }
            if (hasBlock) {
                block.union(left, top, right, bottom);
            } else {
                block.set(left, top, right, bottom);
                hasBlock = true;
            }
        }

        // Copies from when the two differ, so the caller invalidates only on a real move.
        boolean takeIfChanged(Shapes from) {
            boolean same = hasBlock == from.hasBlock && count == from.count
                    && (!hasBlock || block.equals(from.block) && blockAlpha == from.blockAlpha);
            for (int i = 0; same && i < count * 4; i++) {
                same = circles[i] == from.circles[i];
            }
            if (same) {
                return false;
            }
            hasBlock = from.hasBlock;
            block.set(from.block);
            blockAlpha = from.blockAlpha;
            count = from.count;
            System.arraycopy(from.circles, 0, circles, 0, count * 4);
            return true;
        }
    }
}
