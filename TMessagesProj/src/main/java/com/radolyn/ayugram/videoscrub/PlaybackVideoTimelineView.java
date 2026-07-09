package com.radolyn.ayugram.videoscrub;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.MotionEvent;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.Utilities;
import org.telegram.ui.Components.VideoTimelineView;

/**
 * Round video preview timeline with a draggable playback cursor on top of the stock trim view.
 * Interaction follows the video editor's VideoTimelinePlayView (tap between the handles jumps
 * there, cursor never leaves the trim range), except the trim handles keep touch priority so
 * a cursor resting on a handle can't steal its grab.
 */
public class PlaybackVideoTimelineView extends VideoTimelineView {

    private final Paint playPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint playShadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float playProgress;
    private boolean pressedPlay;
    private float pressDx;
    private VideoTimelineViewDelegate delegate;
    private TimeHintView timeHintView;
    private Utilities.Callback<Float> onSeek;

    public PlaybackVideoTimelineView(Context context) {
        super(context);
        playPaint.setColor(Color.WHITE);
        playPaint.setStrokeWidth(AndroidUtilities.dpf2(2f));
        playPaint.setStyle(Paint.Style.STROKE);
        playPaint.setStrokeCap(Paint.Cap.ROUND);
        playShadowPaint.set(playPaint);
        playShadowPaint.setColor(0x4c000000);
        playShadowPaint.setStrokeWidth(AndroidUtilities.dpf2(4f));
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
        getParent().requestDisallowInterceptTouchEvent(true);
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
        float top = topOffset + AndroidUtilities.dp(4);
        float bottom = getMeasuredHeight() - topOffset - AndroidUtilities.dp(4);
        canvas.drawLine(playX, top, playX, bottom, playShadowPaint);
        canvas.drawLine(playX, top, playX, bottom, playPaint);
    }
}
