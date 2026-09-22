package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Path;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;

import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.blur3.drawable.BlurredBackgroundDrawable;

public class SearchTabsAndFiltersLayout extends FrameLayout implements Theme.Colorable {
    private final Path clipPath = new Path();
    private BlurredBackgroundDrawable blurredBackgroundDrawable;
    private boolean flatBackground;

    public SearchTabsAndFiltersLayout(@NonNull Context context) {
        super(context);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        updateClipPath(w, h);
    }

    private void updateClipPath(int w, int h) {
        clipPath.rewind();
        if (flatBackground) {
            clipPath.addRect(0, 0, w, h, Path.Direction.CW);
        } else {
            clipPath.addRoundRect(dp(9), dp(9), w - dp(9), h - dp(9),
                    dp(16), dp(16), Path.Direction.CW);
        }
    }

    @Override
    protected void dispatchDraw(@NonNull Canvas canvas) {
        canvas.save();
        canvas.clipPath(clipPath);
        super.dispatchDraw(canvas);
        canvas.restore();
    }

    public void setBlurredBackground(BlurredBackgroundDrawable drawable) {
        setBackground(blurredBackgroundDrawable = drawable);
    }

    public void setFlatBackground(boolean flatBackground) {
        if (this.flatBackground != flatBackground) {
            this.flatBackground = flatBackground;
            updateClipPath(getWidth(), getHeight());
            invalidate();
        }
    }

    @Override
    public void updateColors() {
        if (blurredBackgroundDrawable != null) {
            blurredBackgroundDrawable.updateColors();
        }
    }
}
