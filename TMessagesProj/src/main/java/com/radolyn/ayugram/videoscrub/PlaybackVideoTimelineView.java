package com.radolyn.ayugram.videoscrub;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.ViewParent;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.Utilities;
import org.telegram.ui.Components.VideoTimelineView;

/**
 * Round video preview timeline with a draggable playback cursor on top of the stock trim view.
 * Interaction and cursor styling follow the video editor's VideoTimelinePlayView (tap between
 * the handles jumps there, cursor never leaves the trim range), except the trim handles keep
 * touch priority so a cursor resting on a handle can't steal its grab.
 */
public class PlaybackVideoTimelineView extends VideoTimelineView {

    private final Paint playPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint playShadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF playRect = new RectF();
    private float playProgress;
    private boolean pressedPlay;
    private float pressDx;
    private VideoTimelineViewDelegate delegate;
    private TimeHintView timeHintView;
    private Utilities.Callback<Float> onSeek;

    public PlaybackVideoTimelineView(Context context) {
        super(context);
        playPaint.setColor(Color.WHITE);
        playPaint.setStyle(Paint.Style.FILL);
        playShadowPaint.setColor(0x26000000);
        playShadowPaint.setStyle(Paint.Style.FILL);
    }

    public void setOnSeek(Utilities.Callback<Float> listener) {
        onSeek = listener;
    }

    // live position feed from the preview player; ignored while the user is scrubbing
    public void setProgress(float progress) {
        if (pressedPlay) {
            return;
        }
        playProgress = clampToTrim(progress);
        invalidate();
    }

    @Override
    public void setDelegate(VideoTimelineViewDelegate videoTimelineViewDelegate) {
        super.setDelegate(videoTimelineViewDelegate);
        delegate = videoTimelineViewDelegate;
    }

    @Override
    public void setTimeHintView(TimeHintView view) {
        super.setTimeHintView(view);
        timeHintView = view;
    }

    @Override
    public void setVideoPath(String path) {
        super.setVideoPath(path);
        playProgress = 0f;
        pressedPlay = false;
    }

    private float clampToTrim(float progress) {
        return Math.max(getLeftProgress(), Math.min(getRightProgress(), progress));
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event == null || getVideoLength() <= 0) {
            return super.onTouchEvent(event);
        }
        float x = event.getX();
        float y = event.getY();
        int width = getMeasuredWidth() - AndroidUtilities.dp(24);
        int startX = (int) (width * getLeftProgress()) + AndroidUtilities.dp(12);
        int endX = (int) (width * getRightProgress()) + AndroidUtilities.dp(12);
        int playX = (int) (width * playProgress) + AndroidUtilities.dp(12);

        if (event.getAction() == MotionEvent.ACTION_DOWN) {
            // trim handles keep priority: the cursor only takes touches the handles declined
            if (super.onTouchEvent(event)) {
                return true;
            }
            if (y >= 0 && y <= getMeasuredHeight() && startX <= x && x <= endX) {
                int addition = AndroidUtilities.dp(8);
                if (playX - addition <= x && x <= playX + addition) {
                    startPlayDrag(x - playX, playX);
                } else {
                    startPlayDrag(0, (int) x);
                    moveCursor(x);
                }
                return true;
            }
            return false;
        } else if (event.getAction() == MotionEvent.ACTION_MOVE) {
            if (pressedPlay) {
                moveCursor(x - pressDx);
                return true;
            }
            boolean handled = super.onTouchEvent(event);
            if (handled) {
                // a trim handle is moving: keep the cursor inside the new range
                float clamped = clampToTrim(playProgress);
                if (clamped != playProgress) {
                    playProgress = clamped;
                    invalidate();
                }
            }
            return handled;
        } else if (event.getAction() == MotionEvent.ACTION_UP || event.getAction() == MotionEvent.ACTION_CANCEL) {
            if (pressedPlay) {
                pressedPlay = false;
                if (delegate != null) {
                    delegate.didStopDragging();
                }
                if (timeHintView != null) {
                    timeHintView.show(false);
                }
                invalidate();
                return true;
            }
        }
        return super.onTouchEvent(event);
    }

    private void startPlayDrag(float dx, int cursorX) {
        ViewParent parent = getParent();
        if (parent != null) {
            parent.requestDisallowInterceptTouchEvent(true);
        }
        pressedPlay = true;
        pressDx = dx;
        if (delegate != null) {
            delegate.didStartDragging();
        }
        updateTimeHint(cursorX);
        invalidate();
    }

    private void moveCursor(float cursorX) {
        int width = getMeasuredWidth() - AndroidUtilities.dp(24);
        if (width <= 0) {
            return;
        }
        playProgress = clampToTrim((cursorX - AndroidUtilities.dp(12)) / width);
        if (onSeek != null) {
            onSeek.run(playProgress);
        }
        updateTimeHint((int) (width * playProgress) + AndroidUtilities.dp(12));
        invalidate();
    }

    private void updateTimeHint(int cursorX) {
        if (timeHintView == null) {
            return;
        }
        timeHintView.setTime((int) (getVideoLength() / 1000f * playProgress));
        timeHintView.setCx(cursorX + getLeft());
        timeHintView.show(true);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (getVideoLength() <= 0) {
            return;
        }
        int width = getMeasuredWidth() - AndroidUtilities.dp(24);
        float playX = width * clampToTrim(playProgress) + AndroidUtilities.dp(12);
        int topOffset = (getMeasuredHeight() - AndroidUtilities.dp(32)) >> 1;
        float top = topOffset - AndroidUtilities.dp(2);
        float bottom = getMeasuredHeight() - topOffset + AndroidUtilities.dp(2);
        float halfWidth = AndroidUtilities.dpf2(1.5f);
        float shadowInset = AndroidUtilities.dpf2(0.66f);
        float radius = AndroidUtilities.dp(6);
        playRect.set(playX - halfWidth, top, playX + halfWidth, bottom);
        playRect.inset(-shadowInset, -shadowInset);
        canvas.drawRoundRect(playRect, radius, radius, playShadowPaint);
        playRect.set(playX - halfWidth, top, playX + halfWidth, bottom);
        canvas.drawRoundRect(playRect, radius, radius, playPaint);
    }
}
