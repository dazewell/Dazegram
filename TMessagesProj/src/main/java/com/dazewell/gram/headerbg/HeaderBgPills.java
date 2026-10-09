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

import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.SimpleTextView;
import org.telegram.ui.Components.AnimatedTextView;
import org.telegram.ui.Components.ChatAvatarContainer;
import org.telegram.ui.Components.NumberTextView;
import org.telegram.ui.Components.PinnedLineView;

/**
 * The translucent capsules the two pill text modes draw behind the header's and the pinned bar's text and buttons:
 * one behind the text, one behind each row's trailing icons, and a circle behind the back button.
 * Their shapes come from the children's geometry, which can change without the parent redrawing (a new subtitle or
 * a pinned swap only re-records the child), so the drawer measures them before every frame and invalidates the
 * parent only when a shape moved; the draw passes just paint what was measured. Main thread only.
 */
final class HeaderBgPills {

    private static final int MAX_SHAPES = 3;
    // Wider than this, an action bar child is the expanded search field, not an icon button.
    private static final int MAX_ICON_DP = 56;
    // Every shape keeps this far from the screen's sides.
    private static final int EDGE_DP = 4;
    // A text pill keeps this far from the trailing icons' capsule, and shrinks rather than move it.
    private static final int GAP_DP = 8;
    // Narrower than this after clamping, a text pill is dropped rather than drawn as a sliver.
    private static final int MIN_PILL_DP = 40;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Shapes header = new Shapes();
    private final Shapes panel = new Shapes();
    private final Shapes scratch = new Shapes();
    private final RectF rect = new RectF();
    private final RectF text = new RectF();
    private final RectF cluster = new RectF();
    // A located view's x, y and combined alpha in the ancestor's coordinates.
    private final float[] loc = new float[3];

    /** The backing colour for a text mode, or 0 for the modes that have none. */
    static int fill(int mode) {
        if (mode == HeaderBgForeground.DARK_PILL) {
            return 0x52000000;
        }
        if (mode == HeaderBgForeground.LIGHT_PILL) {
            return 0x73ffffff;
        }
        return 0;
    }

    /** The header's shapes in ActionBar coordinates, or none with a null bar. True when they changed. */
    boolean measureHeader(ActionBar bar, ChatAvatarContainer container) {
        scratch.clear();
        if (bar != null && !bar.isSearchFieldVisible() && bar.getWidth() > 0) {
            float minX = dp(EDGE_DP);
            float maxX = bar.getWidth() - dp(EDGE_DP);
            float minY = bar.getOccupyStatusBar() ? AndroidUtilities.statusBarHeight : 0;

            float leftLimit = minX;
            View back = bar.getBackButton();
            if (back != null && back.getWidth() > 0 && locate(back, bar)) {
                float cx = loc[0] + back.getWidth() / 2f;
                float cy = loc[1] + back.getHeight() / 2f;
                rect.set(cx - dp(18), cy - dp(18), cx + dp(18), cy + dp(18));
                clampX(rect, minX, maxX);
                scratch.add(rect, loc[2]);
                leftLimit = rect.right + dp(6);
            }

            float rightLimit = maxX;
            if (bar.menu != null && cluster(bar.menu, bar, maxX)) {
                scratch.add(cluster, loc[2]);
                rightLimit = cluster.left - dp(GAP_DP);
            }

            if (container != null && locate(container, bar)) {
                float alpha = loc[2];
                text.setEmpty();
                SimpleTextView title = container.getTitleTextView();
                if (title != null && locate(title, bar)) {
                    int start = textStart(title);
                    float width = Math.min(title.getWidth() - start, title.getTextWidth() + title.getRightDrawableWidth());
                    union(text, loc[0] + start, loc[1], loc[0] + start + width, loc[1] + title.getHeight());
                }
                View subtitle = container.getSubtitleTextView();
                if (subtitle instanceof SimpleTextView && locate(subtitle, bar)) {
                    SimpleTextView s = (SimpleTextView) subtitle;
                    int start = textStart(s);
                    float width = Math.min(s.getWidth() - start, s.getTextWidth());
                    union(text, loc[0] + start, loc[1], loc[0] + start + width, loc[1] + s.getHeight());
                } else if (subtitle instanceof AnimatedTextView && locate(subtitle, bar)) {
                    AnimatedTextView s = (AnimatedTextView) subtitle;
                    int start = s.getPaddingLeft();
                    float width = Math.min(s.getWidth() - start, s.getDrawable().getCurrentWidth());
                    union(text, loc[0] + start, loc[1], loc[0] + start + width, loc[1] + s.getHeight());
                }
                // The time-zone chip is the container's only plain TextView, and the field holding it is private.
                for (int i = 0; i < container.getChildCount(); i++) {
                    View child = container.getChildAt(i);
                    if (child instanceof TextView && locate(child, bar)) {
                        union(text, loc[0], loc[1], loc[0] + child.getWidth(), loc[1] + child.getHeight());
                    }
                }
                View avatar = container.getAvatarImageView();
                if (avatar != null && locate(avatar, bar)) {
                    leftLimit = Math.max(leftLimit, loc[0] + avatar.getWidth() + dp(3));
                }
                if (!text.isEmpty()) {
                    rect.set(Math.max(text.left - dp(8), leftLimit), Math.max(text.top - dp(3), minY),
                            Math.min(text.right + dp(10), rightLimit), text.bottom + dp(3));
                    if (rect.width() >= dp(MIN_PILL_DP) && rect.height() > 0) {
                        scratch.add(rect, alpha);
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
        if (panelView != null && panelView.getWidth() > 0 && parent instanceof ViewGroup && locate((View) parent, panelView)) {
            ViewGroup strip = (ViewGroup) parent;
            float x = loc[0];
            float y = loc[1];
            float alpha = loc[2];
            float minX = dp(EDGE_DP);
            float maxX = panelView.getWidth() - dp(EDGE_DP);

            float rightLimit = maxX;
            if (cluster(strip, panelView, maxX, views.listButton.get(), views.closeButton.get())) {
                scratch.add(cluster, loc[2]);
                rightLimit = cluster.left - dp(GAP_DP);
            }

            float textLeft = Float.MAX_VALUE;
            float textRight = -Float.MAX_VALUE;
            float lineLeft = Float.MAX_VALUE;
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
                } else if (child instanceof TextView) {
                    // The strip's inline button, its only TextView; the text pill stops short of it.
                    rightLimit = Math.min(rightLimit, x + left - dp(GAP_DP));
                }
                if (textWidth > 0) {
                    textLeft = Math.min(textLeft, left);
                    textRight = Math.max(textRight, left + textWidth);
                }
            }
            if (textRight > textLeft) {
                // The line sits inside the pill, with room before it where the screen edge allows.
                float left = lineLeft != Float.MAX_VALUE ? lineLeft - dp(6) : textLeft - dp(10);
                float top = y + Math.min(strip.getHeight(), dp(48)) / 2f - dp(18);
                rect.set(Math.max(x + left, minX), top, Math.min(x + textRight + dp(10), rightLimit), top + dp(36));
                if (rect.width() >= dp(MIN_PILL_DP)) {
                    scratch.add(rect, alpha);
                }
            }
        }
        return panel.takeIfChanged(scratch);
    }

    void drawHeader(Canvas canvas, int fill, float alpha) {
        draw(canvas, header, fill, alpha);
    }

    void drawPanel(Canvas canvas, int fill, float alpha) {
        draw(canvas, panel, fill, alpha);
    }

    /** The combined player row's compact pinned copy: one capsule over the whole view, which holds only its text. */
    void drawCompact(Canvas canvas, int fill, int width, int height) {
        if (fill == 0) {
            return;
        }
        paint.setColor(fill);
        rect.set(dp(EDGE_DP), dp(4), width - dp(EDGE_DP), height - dp(4));
        float r = radius(rect);
        canvas.drawRoundRect(rect, r, r, paint);
    }

    private void draw(Canvas canvas, Shapes s, int fill, float alpha) {
        if (fill == 0 || alpha <= 0f) {
            return;
        }
        int base = Color.alpha(fill);
        paint.setColor(fill);
        for (int i = 0; i < s.count; i++) {
            float[] v = s.values;
            int k = i * 5;
            rect.set(v[k], v[k + 1], v[k + 2], v[k + 3]);
            paint.setAlpha((int) (base * alpha * v[k + 4]));
            float r = radius(rect);
            canvas.drawRoundRect(rect, r, r, paint);
        }
    }

    // Fully rounded up to the point where a tall shape would read as a circle beside the flatter ones.
    private static float radius(RectF r) {
        return Math.min(r.height() / 2f, dp(18));
    }

    // One 32dp capsule behind a row's visible icon buttons, from the first one's centre to the last one's, filled
    // into cluster with its alpha in loc[2]. With only given, just those children count; otherwise any of the row's.
    private boolean cluster(ViewGroup row, View ancestor, float maxX, View... only) {
        float first = Float.MAX_VALUE, last = -Float.MAX_VALUE, cy = 0, alpha = 0;
        int count = row.getChildCount();
        for (int i = 0; i < count; i++) {
            View child = row.getChildAt(i);
            if (only.length > 0 && child != only[0] && (only.length < 2 || child != only[1])) {
                continue;
            }
            if (child.getWidth() <= 0 || child.getWidth() > dp(MAX_ICON_DP) || !locate(child, ancestor)) {
                continue;
            }
            float cx = loc[0] + child.getWidth() / 2f;
            first = Math.min(first, cx);
            last = Math.max(last, cx);
            cy = loc[1] + child.getHeight() / 2f;
            alpha = Math.max(alpha, loc[2]);
        }
        if (first == Float.MAX_VALUE) {
            return false;
        }
        cluster.set(first - dp(16), cy - dp(16), Math.min(last + dp(16), maxX), cy + dp(16));
        loc[2] = alpha;
        return true;
    }

    private static void clampX(RectF r, float minX, float maxX) {
        if (r.left < minX) {
            r.offset(minX - r.left, 0);
        }
        if (r.right > maxX) {
            r.offset(maxX - r.right, 0);
        }
    }

    private static void union(RectF into, float left, float top, float right, float bottom) {
        if (right <= left || bottom <= top) {
            return;
        }
        if (into.isEmpty()) {
            into.set(left, top, right, bottom);
        } else {
            into.union(left, top, right, bottom);
        }
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
        // left, top, right, bottom, alpha per shape.
        final float[] values = new float[MAX_SHAPES * 5];
        int count;

        void clear() {
            count = 0;
        }

        void add(RectF r, float alpha) {
            if (count >= MAX_SHAPES) {
                return;
            }
            int k = count++ * 5;
            values[k] = r.left;
            values[k + 1] = r.top;
            values[k + 2] = r.right;
            values[k + 3] = r.bottom;
            values[k + 4] = alpha;
        }

        // Copies from when the two differ, so the caller invalidates only on a real move.
        boolean takeIfChanged(Shapes from) {
            boolean same = count == from.count;
            for (int i = 0; same && i < count * 5; i++) {
                same = values[i] == from.values[i];
            }
            if (same) {
                return false;
            }
            count = from.count;
            System.arraycopy(from.values, 0, values, 0, count * 5);
            return true;
        }
    }
}
