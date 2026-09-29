package com.radolyn.ayugram.videonote;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.AndroidUtilities.dpf2;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.view.MotionEvent;
import android.view.View;

import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.ui.Components.AnimatedFloat;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.blur3.drawable.BlurredBackgroundDrawable;

/**
 * The paused round video preview's sound toggle. A tap on the preview used to toggle its sound, and with
 * VideoHoldToSend that tap holds to send instead, so the sound gets a chip in the composer's record controls: one
 * more in the stack with the pause and view-once "(1)" chips, above the "(1)" or in its slot when there is none.
 * It is drawn with the host's own background, shadow and icon colour, so it follows every theme with them.
 * UI thread only.
 */
public final class VideoPreviewSoundChip {

    private final View host;
    private final Utilities.Callback0Return<VideoHoldToSend> hold;
    private final AnimatedFloat shown;
    private final Drawable mutedIcon;
    private final Drawable unmutedIcon;
    private final RectF rect = new RectF();
    private BlurredBackgroundDrawable glassBackground;
    private boolean visible;
    private boolean pressed;

    // hold is the camera's hold-to-send while the composer has a round video preview, otherwise null
    public VideoPreviewSoundChip(View host, Utilities.Callback0Return<VideoHoldToSend> hold) {
        this.host = host;
        this.hold = hold;
        shown = new AnimatedFloat(host, 0, 320, CubicBezierInterpolator.EASE_OUT_QUINT);
        // the story editor's sound toggle icons, showing the current state: media_unmute is the crossed-out speaker
        mutedIcon = host.getResources().getDrawable(R.drawable.media_unmute).mutate();
        unmutedIcon = host.getResources().getDrawable(R.drawable.media_mute).mutate();
    }

    // The Liquid Glass background, made the same way as the host's other chips.
    public void setGlassBackground(BlurredBackgroundDrawable background) {
        background.setRadius(dp(22));
        background.setPadding(dp(3));
        glassBackground = background;
    }

    public void updateColors(int iconColor) {
        mutedIcon.setColorFilter(new PorterDuffColorFilter(iconColor, PorterDuff.Mode.SRC_IN));
        unmutedIcon.setColorFilter(new PorterDuffColorFilter(iconColor, PorterDuff.Mode.SRC_IN));
        if (glassBackground != null) {
            glassBackground.updateColors();
        }
    }

    // top is the top chip of the host's stack, step one chip plus the gap between them, scale the stack's own.
    // fill and shadow are what the host draws its chips with when there is no glass.
    public void draw(Canvas canvas, RectF top, float step, float scale, Paint fill, Drawable shadow) {
        final VideoHoldToSend h = hold.run();
        visible = h != null && h.hasPreviewSound();
        final float s = scale * shown.set(visible);
        rect.set(top.left, top.top - step, top.right, top.bottom - step);
        if (s <= 0f) {
            return;
        }
        canvas.save();
        canvas.scale(s, s, rect.centerX(), rect.centerY());
        final Drawable background = glassBackground != null ? glassBackground : shadow;
        background.setBounds((int) (rect.left - dpf2(3)), (int) (rect.top - dpf2(3)), (int) (rect.right + dpf2(3)), (int) (rect.bottom + dpf2(3)));
        background.draw(canvas);
        if (glassBackground == null) {
            canvas.drawRoundRect(rect, dpf2(22), dpf2(22), fill);
        }
        final Drawable icon = h == null || h.isPreviewMuted() ? mutedIcon : unmutedIcon;
        final int cx = (int) rect.centerX(), cy = (int) rect.centerY();
        final int halfW = icon.getIntrinsicWidth() / 2, halfH = icon.getIntrinsicHeight() / 2;
        icon.setBounds(cx - halfW, cy - halfH, cx + halfW, cy + halfH);
        icon.draw(canvas);
        canvas.restore();
    }

    // True for the whole of a press that started on the chip, so the host leaves that gesture alone.
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                pressed = contains(event.getX(), event.getY());
                return pressed;
            case MotionEvent.ACTION_UP:
                if (pressed) {
                    pressed = false;
                    if (contains(event.getX(), event.getY())) {
                        toggle();
                    }
                    return true;
                }
                return false;
            case MotionEvent.ACTION_CANCEL:
                final boolean was = pressed;
                pressed = false;
                return was;
            default:
                return pressed;
        }
    }

    public boolean isVisible() {
        return visible;
    }

    public boolean contains(float x, float y) {
        return visible && rect.contains(x, y);
    }

    public void toggle() {
        final VideoHoldToSend h = hold.run();
        if (h != null && h.togglePreviewSound()) {
            host.invalidate();
        }
    }

    public void populate(AccessibilityNodeInfoCompat info, Rect tmp) {
        tmp.set((int) rect.left, (int) rect.top, (int) rect.right, (int) rect.bottom);
        info.setBoundsInParent(tmp);
        final VideoHoldToSend h = hold.run();
        info.setText(LocaleController.getString(h == null || h.isPreviewMuted() ? R.string.Unmute : R.string.Mute));
    }
}
