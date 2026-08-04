package xyz.nextalone.nagram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.graphics.Canvas;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.Nullable;

import org.telegram.ui.Components.blur3.BlurredBackgroundDrawableViewFactory;
import org.telegram.ui.Components.blur3.drawable.BlurredBackgroundDrawable;
import org.telegram.ui.Components.blur3.drawable.color.BlurredBackgroundColorProvider;

import java.util.ArrayList;

public final class InputSatellites {

    private static final int FILL_MARGIN = 5;
    private static final int WARMUP_FRAMES = 3;

    private final ArrayList<GlassFill> fills = new ArrayList<>();
    private @Nullable BlurredBackgroundDrawableViewFactory factory;
    private @Nullable BlurredBackgroundColorProvider colorProvider;

    public void configure(BlurredBackgroundDrawableViewFactory factory, BlurredBackgroundColorProvider colorProvider) {
        this.factory = factory;
        this.colorProvider = colorProvider;
    }

    public void glass(View view) {
        glass(view, null);
    }

    public void glass(View view, @Nullable Condition condition) {
        if (view == null) {
            return;
        }
        for (int i = 0; i < fills.size(); i++) {
            if (fills.get(i).view == view) {
                return;
            }
        }
        fills.add(new GlassFill(view, condition));
    }

    public void drawFill(Canvas canvas, View view) {
        if (factory == null) {
            return;
        }
        for (int i = 0; i < fills.size(); i++) {
            GlassFill fill = fills.get(i);
            if (fill.view != view) {
                continue;
            }
            if (wants(fill)) {
                View invalidationTarget = view.getParent() instanceof View ? (View) view.getParent() : view;
                paintFill(canvas, fill, view, 255, invalidationTarget);
            }
            return;
        }
    }

    public void drawFills(Canvas canvas, ViewGroup parent) {
        if (factory == null) {
            return;
        }
        for (int i = 0; i < fills.size(); i++) {
            GlassFill fill = fills.get(i);
            View view = fill.view;
            if (view.getParent() != parent || !wants(fill)) {
                continue;
            }
            float alpha = view.getAlpha();
            float scaleX = view.getScaleX();
            float scaleY = view.getScaleY();
            if (alpha <= 0.01f || scaleX == 0 || scaleY == 0) {
                continue;
            }
            canvas.save();
            canvas.translate(view.getLeft() + view.getTranslationX(), view.getTop() + view.getTranslationY());
            canvas.scale(scaleX, scaleY, view.getPivotX(), view.getPivotY());
            paintFill(canvas, fill, view, (int) (alpha * 255), parent);
            canvas.restore();
        }
    }

    public void updateColors() {
        for (int i = 0; i < fills.size(); i++) {
            BlurredBackgroundDrawable drawable = fills.get(i).drawable;
            if (drawable != null) {
                drawable.updateColors();
            }
        }
    }

    private boolean wants(GlassFill fill) {
        return fill.view.getVisibility() == View.VISIBLE && (fill.condition == null || fill.condition.holds());
    }

    private void paintFill(Canvas canvas, GlassFill fill, View target, int alpha, View invalidationTarget) {
        if (target.getWidth() <= 0 || target.getHeight() <= 0) {
            return;
        }
        if (fill.drawable == null) {
            fill.drawable = factory.create(fill.view, colorProvider);
            fill.drawable.setPadding(dp(FILL_MARGIN));
            fill.warmup = WARMUP_FRAMES;
        }
        float radius = Math.max(0, (Math.min(target.getWidth(), target.getHeight()) - dp(FILL_MARGIN * 2)) / 2f);
        if (fill.radius != radius) {
            fill.radius = radius;
            fill.drawable.setRadius(radius);
        }
        fill.drawable.setBounds(0, 0, target.getWidth(), target.getHeight());
        fill.drawable.setAlpha(alpha);
        fill.drawable.draw(canvas);
        if (fill.warmup > 0) {
            fill.warmup--;
            invalidationTarget.postInvalidateOnAnimation();
        }
    }

    private static final class GlassFill {
        final View view;
        final @Nullable Condition condition;
        @Nullable BlurredBackgroundDrawable drawable;
        float radius = -1;
        int warmup;

        GlassFill(View view, @Nullable Condition condition) {
            this.view = view;
            this.condition = condition;
        }
    }

    public interface Condition {
        boolean holds();
    }
}
