package tw.nekomimi.nekogram.ui.components;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.SystemClock;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.SharedConfig;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.CubicBezierInterpolator;

import java.util.List;

import tw.nekomimi.nekogram.helpers.VideoCaptionsHelper;

// The caption strip for a playing round video message. Sits over the chat as a transparent overlay
// and paints a pill just above the composer, so it holds still while the video scrolls instead of
// chasing the circle around, and it never covers the speaker's face.
//
// Kept out of ChatMessageCell and out of the video container on purpose: the container is clipped
// to a circle and scaled during the play-all transition, and the cell is the file that hurts most
// to touch on an upstream merge.
public class VideoMessageCaptionView extends View {

    private static final long FADE_DURATION = 200;

    private final TextPaint textPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    private List<VideoCaptionsHelper.Segment> segments;
    private String untimedText;
    private int currentSegment = -1;
    private long lastPositionMs;
    private int account;
    private MessageObject message;

    private final Runnable onToggled = this::resolve;

    private StaticLayout layout;
    private StaticLayout previousLayout;
    private String currentText;
    private long fadeStart;

    private float anchorBottom;
    private boolean positioned;

    public VideoMessageCaptionView(Context context) {
        super(context);
        setVisibility(GONE);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        textPaint.setTextSize(dp(Math.max(14, SharedConfig.fontSize - 2)));
        textPaint.setTypeface(AndroidUtilities.getTypeface(AndroidUtilities.TYPEFACE_ROBOTO_MEDIUM));
    }

    public void setMessage(int account, MessageObject messageObject) {
        this.account = account;
        this.message = messageObject != null && messageObject.isRoundVideo() ? messageObject : null;
        this.lastPositionMs = 0;
        resolve();
    }

    // Called again whenever captions are armed on a message or a transcription lands, so text that
    // already exists shows up straight away instead of after the next playback.
    private void resolve() {
        segments = null;
        untimedText = null;
        currentSegment = -1;
        layout = null;
        previousLayout = null;
        currentText = null;
        if (message != null && VideoCaptionsHelper.isArmed(account, message)) {
            segments = VideoCaptionsHelper.getSegments(account, message);
            if (segments == null) {
                String text = VideoCaptionsHelper.getUntimedText(message);
                segments = VideoCaptionsHelper.approximateSegments(text, (long) (message.getDuration() * 1000L));
                if (segments == null && text != null) {
                    untimedText = text;
                    setText(text);
                    return;
                }
            }
        }
        if (segments != null) {
            // Playback doesn't wait for the transcription, so pick up wherever the video is now.
            applyProgress(lastPositionMs);
            return;
        }
        updateVisible();
        invalidate();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        VideoCaptionsHelper.addListener(onToggled);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        VideoCaptionsHelper.removeListener(onToggled);
    }

    public void clear() {
        message = null;
        segments = null;
        untimedText = null;
        currentSegment = -1;
        lastPositionMs = 0;
        layout = null;
        previousLayout = null;
        currentText = null;
        positioned = false;
        updateVisible();
    }

    // The pill hangs off the top of the composer bubble, which moves with the keyboard and the
    // insets, so this gets refreshed on every progress tick rather than once per message.
    public void setAnchorBottom(float y) {
        positioned = true;
        if (anchorBottom == y) {
            return;
        }
        anchorBottom = y;
        if (getVisibility() == VISIBLE) {
            invalidate();
        }
    }

    public void setProgress(long positionMs) {
        lastPositionMs = positionMs;
        applyProgress(positionMs);
    }

    private void applyProgress(long positionMs) {
        if (segments == null) {
            return;
        }
        int index = VideoCaptionsHelper.findSegment(segments, positionMs);
        if (index == currentSegment) {
            return;
        }
        currentSegment = index;
        setText(index < 0 ? null : segments.get(index).text);
    }

    private void setText(String text) {
        currentText = text;
        previousLayout = layout;
        fadeStart = SystemClock.elapsedRealtime();
        layout = buildLayout(text);
        updateVisible();
        invalidate();
    }

    private StaticLayout buildLayout(String text) {
        if (TextUtils.isEmpty(text)) {
            return null;
        }
        int width = Math.max(dp(120), getMeasuredWidth() - dp(64));
        StaticLayout built = new StaticLayout(text, textPaint, width, Layout.Alignment.ALIGN_CENTER, 1f, 0f, false);
        if (built.getLineCount() > 2) {
            // Two lines is what fits under the circle without crowding the bubble below it.
            CharSequence trimmed = text.subSequence(0, built.getLineEnd(1));
            trimmed = TextUtils.ellipsize(trimmed, textPaint, width * 2f - dp(8), TextUtils.TruncateAt.END);
            built = new StaticLayout(trimmed, textPaint, width, Layout.Alignment.ALIGN_CENTER, 1f, 0f, false);
        }
        return built;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        // Text can land before the first measure pass (re-entering a chat while a video plays), and
        // a layout built against a zero width stays cramped until the next segment swaps it out.
        if (w != oldw && currentText != null) {
            layout = buildLayout(currentText);
            previousLayout = null;
            invalidate();
        }
    }

    private void updateVisible() {
        boolean visible = layout != null || previousLayout != null;
        if ((getVisibility() == VISIBLE) != visible) {
            setVisibility(visible ? VISIBLE : GONE);
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (!positioned || (layout == null && previousLayout == null)) {
            return;
        }
        float progress = Math.min(1f, (SystemClock.elapsedRealtime() - fadeStart) / (float) FADE_DURATION);
        progress = CubicBezierInterpolator.EASE_OUT_QUINT.getInterpolation(progress);
        if (progress < 1f) {
            invalidate();
        } else if (previousLayout != null) {
            previousLayout = null;
            updateVisible();
        }
        if (layout != null) {
            draw(canvas, layout, progress);
        }
        if (previousLayout != null) {
            draw(canvas, previousLayout, 1f - progress);
        }
    }

    private void draw(Canvas canvas, StaticLayout staticLayout, float alpha) {
        if (alpha <= 0) {
            return;
        }
        float textWidth = 0;
        for (int i = 0; i < staticLayout.getLineCount(); i++) {
            textWidth = Math.max(textWidth, staticLayout.getLineWidth(i));
        }
        float width = textWidth + dp(20);
        float height = staticLayout.getHeight() + dp(10);
        float left = (getMeasuredWidth() - width) / 2f;
        float top = anchorBottom - dp(8) - height;
        if (top < 0) {
            return;
        }
        rect.set(left, top, left + width, top + height);

        Theme.applyServiceShaderMatrix(getMeasuredWidth(), getMeasuredHeight(), 0, 0);
        Paint backgroundPaint = Theme.chat_actionBackgroundPaint;
        int oldAlpha = backgroundPaint.getAlpha();
        backgroundPaint.setAlpha((int) (oldAlpha * alpha));
        canvas.drawRoundRect(rect, height / 2f, height / 2f, backgroundPaint);
        backgroundPaint.setAlpha(oldAlpha);
        if (Theme.hasGradientService()) {
            int oldDarkenAlpha = Theme.chat_actionBackgroundGradientDarkenPaint.getAlpha();
            Theme.chat_actionBackgroundGradientDarkenPaint.setAlpha((int) (oldDarkenAlpha * alpha));
            canvas.drawRoundRect(rect, height / 2f, height / 2f, Theme.chat_actionBackgroundGradientDarkenPaint);
            Theme.chat_actionBackgroundGradientDarkenPaint.setAlpha(oldDarkenAlpha);
        }

        textPaint.setColor(Theme.getColor(Theme.key_chat_serviceText));
        textPaint.setAlpha((int) (255 * alpha));
        canvas.save();
        canvas.translate(left + dp(10) + (width - dp(20) - staticLayout.getWidth()) / 2f, top + dp(5));
        staticLayout.draw(canvas);
        canvas.restore();
    }

    @Override
    public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        info.setVisibleToUser(false);
    }
}
