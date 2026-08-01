package xyz.nextalone.nagram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.graphics.Canvas;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.Nullable;

import org.telegram.ui.Components.ChatActivityEnterView;
import org.telegram.ui.Components.blur3.BlurredBackgroundDrawableViewFactory;
import org.telegram.ui.Components.blur3.drawable.BlurredBackgroundDrawable;
import org.telegram.ui.Components.blur3.drawable.color.BlurredBackgroundColorProvider;
import org.telegram.ui.Components.chat.ChatInputViewsContainer;

import java.util.ArrayList;

/**
 * NagramX (#input-satellites): the send column stops being a passenger of the input island and starts
 * floating next to it. The island already knows how to give up horizontal room — the channel buttons
 * below the composer do exactly that — so all we do is publish how much room the right-hand column
 * currently paints over, and the island retreats behind it. Nothing is reparented, no layout pass is
 * added: the offset is a float the island reads while drawing.
 * <p>
 * The width is derived from the live buttons rather than enumerated per state, so slow mode, the stars
 * price pill, the edit-mode done button and the stickers arrow all stay correct without this class
 * knowing which state the composer is in.
 * <p>
 * The buttons that paint no fill of their own (stickers arrow, cancel-inline-bot, slow mode, the mic slot
 * while it acts as the attach menu, and the rich-editor button that sits above the send button once a draft
 * wraps past two lines) get the same glass circle the other floating controls use, so a satellite never sits
 * naked over the wallpaper. Those are plain {@code ImageView}/{@code TextView}s that cannot host the circle
 * in their own background, so {@link #sendButtonContainer} paints the ones it owns from the children's live
 * bounds, and the rich-editor button — which lives in the text field container instead — paints its own from
 * {@link #drawFill}. No backgrounds are replaced, so there is nothing to re-install when upstream swaps a
 * ripple.
 */
public final class InputSatellites {

    /** Breathing room between the island's edge and the satellite column. */
    private static final int GAP = 7;
    /** Inset of the painted fill inside its button, matching the accent disc the mic slot draws. */
    private static final int FILL_MARGIN = 3;
    private static final int FILL_RADIUS = 19;
    /**
     * Redraws requested right after a circle is created. The blur source only captures the wallpaper
     * under a glass drawable once that drawable is registered *and* has recorded itself once, so the
     * very first recording of a fresh circle is empty. Every redraw re-records it, but a satellite
     * that arrives into a still screen — the stickers arrow, which appears when the emoji panel opens
     * and nothing moves afterwards — would never get one, and would stay an empty circle for good.
     */
    private static final int WARMUP_FRAMES = 3;

    private final View enterView;
    private final ViewGroup sendButtonContainer;

    /**
     * Right-column buttons that live outside {@link #sendButtonContainer}: the edit-mode done button and the
     * rich-editor button.
     */
    private final ArrayList<View> extraColumn = new ArrayList<>();
    /** Satellites that paint no fill of their own and get the glass circle from here. */
    private final ArrayList<GlassFill> fills = new ArrayList<>();

    private @Nullable ChatInputViewsContainer island;
    private @Nullable BlurredBackgroundDrawableViewFactory factory;
    private @Nullable BlurredBackgroundColorProvider colorProvider;

    private float publishedOffset = -1;

    public InputSatellites(View enterView, ViewGroup sendButtonContainer) {
        this.enterView = enterView;
        this.sendButtonContainer = sendButtonContainer;
    }

    public void attach(ChatInputViewsContainer island,
                       BlurredBackgroundDrawableViewFactory factory,
                       BlurredBackgroundColorProvider colorProvider) {
        this.island = island;
        this.factory = factory;
        this.colorProvider = colorProvider;
        update();
    }

    /** Counts a right-column button that is not a child of the send button container. */
    public void track(View view) {
        if (view != null && !extraColumn.contains(view)) {
            extraColumn.add(view);
        }
    }

    /** Gives a fill-less satellite the standard glass circle, leaving its own background untouched. */
    public void glass(View view) {
        glass(view, null);
    }

    /**
     * Same circle as {@link #glass(View)}, drawn only while {@code when} says so. Used by the mic slot,
     * which paints an accent disc of its own in every state but the attach-menu one.
     */
    public void glass(View view, @Nullable Condition when) {
        if (view == null) {
            return;
        }
        for (int i = 0; i < fills.size(); i++) {
            if (fills.get(i).view == view) {
                return;
            }
        }
        fills.add(new GlassFill(view, when));
    }

    /**
     * Paints the satellites' glass circles. Called from {@link #sendButtonContainer}'s draw pass before its
     * children, so a circle lands under the button's own ripple instead of hiding it, and follows the alpha,
     * scale and translation of the button it belongs to.
     */
    public void drawFills(Canvas canvas) {
        if (factory == null) {
            return;
        }
        for (int i = 0; i < fills.size(); i++) {
            final GlassFill fill = fills.get(i);
            final View view = fill.view;
            if (view.getParent() != sendButtonContainer || !wants(fill)) {
                continue;
            }
            final float alpha = view.getAlpha();
            final float scaleX = view.getScaleX(), scaleY = view.getScaleY();
            if (alpha <= 0.01f || scaleX == 0 || scaleY == 0) {
                continue;
            }
            canvas.save();
            canvas.translate(view.getLeft() + view.getTranslationX(), view.getTop() + view.getTranslationY());
            canvas.scale(scaleX, scaleY, view.getPivotX(), view.getPivotY());
            paintFill(canvas, fill, (int) (alpha * 255), sendButtonContainer);
            canvas.restore();
        }
    }

    /**
     * The same circle, painted by the satellite itself from its own draw pass, in its own coordinate space:
     * alpha, scale and translation then come from the parent's child transform for free. Used by the
     * rich-editor button, the one satellite that hangs off the text field container rather than the send
     * column — that container carries the whole composer row, and re-recording its draw pass on every
     * descendant invalidation, every cursor blink of a long draft, to place one circle would be a poor trade.
     */
    public void drawFill(Canvas canvas, View view) {
        if (factory == null) {
            return;
        }
        for (int i = 0; i < fills.size(); i++) {
            final GlassFill fill = fills.get(i);
            if (fill.view == view) {
                if (wants(fill)) {
                    paintFill(canvas, fill, 255, view);
                }
                return;
            }
        }
    }

    /** Whether a registered satellite is on screen and currently asking for its circle. */
    private boolean wants(GlassFill fill) {
        return fill.view.getVisibility() == View.VISIBLE && (fill.when == null || fill.when.holds());
    }

    /**
     * Draws one circle over the satellite's own bounds, the canvas already carrying whatever transform the
     * caller needs. A freshly created drawable asks {@code invalidationTarget} for a few more frames before
     * it settles — see {@link #WARMUP_FRAMES}.
     */
    private void paintFill(Canvas canvas, GlassFill fill, int alpha, View invalidationTarget) {
        if (fill.drawable == null) {
            fill.drawable = createFill(fill.view);
            fill.warmup = WARMUP_FRAMES;
        }
        fill.drawable.setAlpha(alpha);
        fill.drawable.setBounds(0, 0, fill.view.getWidth(), fill.view.getHeight());
        fill.drawable.draw(canvas);
        if (fill.warmup > 0) {
            fill.warmup--;
            invalidationTarget.postInvalidateOnAnimation();
        }
    }

    public void updateColors() {
        for (int i = 0; i < fills.size(); i++) {
            final BlurredBackgroundDrawable drawable = fills.get(i).drawable;
            if (drawable != null) {
                drawable.updateColors();
            }
        }
    }

    /**
     * Recomputes the island's right offset. Cheap enough to call from every draw: it only touches the
     * island when the value actually changes.
     */
    public void update() {
        if (island == null) {
            return;
        }

        float fill = 0;
        // The composer shares its island with the search bar and the join/unblock overlays; when it is not
        // on screen the island must span the full width again.
        if (enterView.getVisibility() == View.VISIBLE && enterView.getAlpha() > 0.01f) {
            if (sendButtonContainer.getVisibility() == View.VISIBLE) {
                for (int i = 0; i < sendButtonContainer.getChildCount(); i++) {
                    fill = Math.max(fill, visualWidth(sendButtonContainer.getChildAt(i)));
                }
            }
            for (int i = 0; i < extraColumn.size(); i++) {
                fill = Math.max(fill, visualWidth(extraColumn.get(i)));
            }
        }

        final float offset = fill > 0 ? fill + dp(FILL_MARGIN) + dp(GAP) : 0;
        if (offset != publishedOffset) {
            publishedOffset = offset;
            island.setSatelliteOffsets(0, offset);
        }
    }

    /**
     * Width of what a right-column button actually paints. Buttons are right-aligned in their container,
     * so this is measured from the container's right edge. Anything mid-transition is counted once it is
     * more than half faded in, which keeps the island's edge from chasing every animation frame.
     */
    private float visualWidth(View view) {
        if (view == null || view.getVisibility() != View.VISIBLE
                || view.getAlpha() < 0.5f || view.getScaleX() < 0.5f) {
            return 0;
        }
        if (view instanceof ChatActivityEnterView.SendButton) {
            return ((ChatActivityEnterView.SendButton) view).getVisualWidth();
        }
        return Math.max(0, view.getWidth() - dp(FILL_MARGIN) * 2);
    }

    private BlurredBackgroundDrawable createFill(View view) {
        final BlurredBackgroundDrawable drawable = factory.create(view, colorProvider);
        drawable.setRadius(dp(FILL_RADIUS));
        drawable.setPadding(dp(FILL_MARGIN));
        return drawable;
    }

    /** Whether a satellite currently wants its glass circle. */
    public interface Condition {
        boolean holds();
    }

    private static final class GlassFill {
        final View view;
        final @Nullable Condition when;
        @Nullable BlurredBackgroundDrawable drawable;
        int warmup;

        GlassFill(View view, @Nullable Condition when) {
            this.view = view;
            this.when = when;
        }
    }
}
