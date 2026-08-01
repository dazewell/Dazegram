package xyz.nextalone.nagram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
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
 * The buttons that paint no fill of their own (stickers arrow, cancel-inline-bot, slow mode, and the
 * mic slot while it acts as the attach menu) get the same glass circle the other floating controls use,
 * so a satellite never sits naked over the wallpaper.
 */
public final class InputSatellites {

    /** Breathing room between the island's edge and the satellite column. */
    private static final int GAP = 7;
    /** Inset of the painted fill inside its button, matching the accent disc the mic slot draws. */
    private static final int FILL_MARGIN = 3;
    private static final int FILL_RADIUS = 19;

    private final View enterView;
    private final ViewGroup sendButtonContainer;

    /** Right-column buttons that live outside {@link #sendButtonContainer} (the edit-mode done button). */
    private final ArrayList<View> extraColumn = new ArrayList<>();
    /** Requested before the blur factory arrives from ChatActivity; applied on {@link #attach}. */
    private final ArrayList<View> pendingFills = new ArrayList<>();
    private final ArrayList<GlassFill> fills = new ArrayList<>();

    private @Nullable ChatInputViewsContainer island;
    private @Nullable BlurredBackgroundDrawableViewFactory factory;
    private @Nullable BlurredBackgroundColorProvider colorProvider;

    private @Nullable BlurredBackgroundDrawable manualFill;
    private @Nullable View manualFillView;

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
        for (int i = 0; i < pendingFills.size(); i++) {
            applyFill(pendingFills.get(i));
        }
        pendingFills.clear();
        update();
    }

    /** Counts a right-column button that is not a child of the send button container. */
    public void track(View view) {
        if (view != null && !extraColumn.contains(view)) {
            extraColumn.add(view);
        }
    }

    /** Gives a fill-less satellite the standard glass circle, keeping whatever ripple it already had. */
    public void glass(View view) {
        if (view == null) {
            return;
        }
        if (factory == null) {
            pendingFills.add(view);
        } else {
            applyFill(view);
        }
    }

    /**
     * Same circle as {@link #glass(View)}, but painted by the caller. Used by the mic slot, which swaps its
     * own background between a ripple and nothing depending on state and would overwrite ours.
     */
    public void drawFill(Canvas canvas, View host) {
        if (factory == null || host == null) {
            return;
        }
        if (manualFill == null || manualFillView != host) {
            manualFillView = host;
            manualFill = createFill(host);
        }
        manualFill.setBounds(0, 0, host.getMeasuredWidth(), host.getMeasuredHeight());
        manualFill.draw(canvas);
    }

    public void updateColors() {
        for (int i = 0; i < fills.size(); i++) {
            final GlassFill fill = fills.get(i);
            fill.drawable.updateColors();
            // A theme change is also where a button is most likely to have had its background replaced
            // from under us — upstream re-creates ripples there. Put ours back if that happened.
            if (fill.view != null && fill.view.getBackground() != fill.applied) {
                apply(fill);
            }
        }
        if (manualFill != null) {
            manualFill.updateColors();
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

    private void applyFill(View view) {
        final GlassFill fill = new GlassFill(view, createFill(view));
        fills.add(fill);
        apply(fill);
    }

    /** (Re-)installs the glass circle under whatever background the button is wearing right now. */
    private void apply(GlassFill fill) {
        final Drawable previous = fill.view.getBackground();
        fill.applied = previous == null
                ? fill.drawable
                : new LayerDrawable(new Drawable[]{fill.drawable, previous});
        fill.view.setBackground(fill.applied);
    }

    private BlurredBackgroundDrawable createFill(View view) {
        final BlurredBackgroundDrawable drawable = factory.create(view, colorProvider);
        drawable.setRadius(dp(FILL_RADIUS));
        drawable.setPadding(dp(FILL_MARGIN));
        // Purely visual inset — it must not become view padding and shift the icon.
        drawable.setHasPadding(false);
        return drawable;
    }

    private static final class GlassFill {
        final View view;
        final BlurredBackgroundDrawable drawable;
        Drawable applied;

        GlassFill(View view, BlurredBackgroundDrawable drawable) {
            this.view = view;
            this.drawable = drawable;
        }
    }
}
