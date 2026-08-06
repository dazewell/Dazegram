package tw.nekomimi.nekogram.ui.components;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.view.MotionEvent;

import org.telegram.messenger.MediaController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.ChatMessageCell;
import org.telegram.ui.Components.AnimatedFloat;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.TranscribeButton;

import tw.nekomimi.nekogram.helpers.VideoCaptionsHelper;

// The CC target that sits one slot above the transcribe button on a video message. One press
// transcribes it if it hasn't been already and plays it back with captions, so the transcribe button
// keeps doing exactly what it always did: swap the circle out for a text bubble.
//
// Drawn by the cell rather than being a real View: everything else in that corner (the transcribe
// button, the side buttons) is drawn too, and a child view there would need its own layout pass on
// a cell that already positions its parts by hand.
public class VideoCaptionsButton {

    private final ChatMessageCell parent;
    private final Drawable icon;
    private final Rect bounds = new Rect();
    private final Rect pressBounds = new Rect();
    private final RectF rect = new RectF();

    private boolean pressed;
    private float drawnAlpha;
    private int iconColor;
    private Paint loadingPaint;
    private int lastMessageId;
    private final AnimatedFloat loadingFloat;
    private final long loadingStart = SystemClock.elapsedRealtime();

    public VideoCaptionsButton(ChatMessageCell parent) {
        this.parent = parent;
        icon = parent.getContext().getResources().getDrawable(R.drawable.baseline_closed_caption_24).mutate();
        loadingFloat = new AnimatedFloat(parent, 250, CubicBezierInterpolator.EASE_OUT_QUINT);
    }

    // Everything CC does on a press, kept out of ChatMessageCell: caption this play, put the circle
    // back if the transcribe button had swapped it for text, then play it, transcribing first when
    // there's nothing to caption yet. There's no second press to undo: captions end with the play.
    public static void press(int account, MessageObject messageObject, ChatMessageCell.ChatMessageCellDelegate delegate) {
        if (messageObject == null || VideoCaptionsHelper.isTranscribingForCaptions(account, messageObject)) {
            return;
        }
        VideoCaptionsHelper.arm(account, messageObject);
        TranscribeButton.closeVideoTranscriptionForCaptions(messageObject);
        if (VideoCaptionsHelper.hasFinalText(messageObject)) {
            MediaController.getInstance().playMessage(messageObject);
        } else {
            // Starting the video now would run the first half of it against an empty strip, so it
            // waits for the text and ChatActivity presses play when it lands.
            VideoCaptionsHelper.awaitPlayback(account, messageObject);
            TranscribeButton.transcribeForCaptions(messageObject, delegate);
        }
    }

    public void setBounds(int x, int y, int size) {
        bounds.set(x, y, x + size, y + size);
        pressBounds.set(bounds);
        // Only widened sideways: the transcribe button sits right underneath and CC is tested first,
        // so a taller hit box would start eating its taps.
        pressBounds.inset(-dp(4), 0);
    }

    public void getBounds(Rect out) {
        out.set(bounds);
    }

    // The cell stops drawing this while a round video plays, and a stale hit rect there would eat
    // taps meant for the video itself.
    public void hide() {
        drawnAlpha = 0;
        pressed = false;
    }

    public void draw(Canvas canvas, float alpha, MessageObject message, boolean loading) {
        drawnAlpha = alpha;
        if (alpha <= 0) {
            return;
        }
        int id = message == null ? 0 : message.getId();
        if (id != lastMessageId) {
            lastMessageId = id;
            // Cells get recycled, so without this the spinner fades out of whatever the previous
            // message in this slot was doing.
            loadingFloat.set(loading ? 1f : 0f, true);
        }
        rect.set(bounds);
        Theme.applyServiceShaderMatrix(parent.getMeasuredWidth(), parent.getMeasuredHeight(), 0, 0);
        Paint backgroundPaint = Theme.chat_actionBackgroundPaint;
        int oldAlpha = backgroundPaint.getAlpha();
        backgroundPaint.setAlpha((int) (oldAlpha * alpha));
        canvas.drawRoundRect(rect, rect.height() / 2f, rect.height() / 2f, backgroundPaint);
        backgroundPaint.setAlpha(oldAlpha);
        if (Theme.hasGradientService()) {
            int oldDarkenAlpha = Theme.chat_actionBackgroundGradientDarkenPaint.getAlpha();
            Theme.chat_actionBackgroundGradientDarkenPaint.setAlpha((int) (oldDarkenAlpha * alpha));
            canvas.drawRoundRect(rect, rect.height() / 2f, rect.height() / 2f, Theme.chat_actionBackgroundGradientDarkenPaint);
            Theme.chat_actionBackgroundGradientDarkenPaint.setAlpha(oldDarkenAlpha);
        }

        int color = Theme.getColor(Theme.key_chat_serviceText);
        if (color != iconColor) {
            iconColor = color;
            icon.setColorFilter(new android.graphics.PorterDuffColorFilter(color, android.graphics.PorterDuff.Mode.SRC_IN));
        }

        float loadingT = loadingFloat.set(loading ? 1f : 0f);
        if (loadingT > 0f) {
            drawLoading(canvas, alpha * loadingT, color);
        }

        int inset = dp(5);
        icon.setBounds(bounds.left + inset, bounds.top + inset, bounds.right - inset, bounds.bottom - inset);
        icon.setAlpha((int) (255 * alpha * (pressed ? 0.6f : 1f)));
        icon.draw(canvas);
    }

    // The same "working on it" cue the transcribe button gives, drawn as a plain sweeping arc since
    // this one is a circle and doesn't need that button's rounded-rect path walk.
    private void drawLoading(Canvas canvas, float alpha, int color) {
        if (loadingPaint == null) {
            loadingPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            loadingPaint.setStyle(Paint.Style.STROKE);
            loadingPaint.setStrokeCap(Paint.Cap.ROUND);
            loadingPaint.setStrokeWidth(dp(1.5f));
        }
        loadingPaint.setColor(color);
        loadingPaint.setAlpha((int) (255 * alpha));
        float inset = dp(1.5f) / 2f;
        rect.set(bounds.left + inset, bounds.top + inset, bounds.right - inset, bounds.bottom - inset);
        long elapsed = SystemClock.elapsedRealtime() - loadingStart;
        float start = elapsed * 360f / 1000f;
        // Sweep breathes between a short dash and most of the ring so it reads as motion even at the
        // point in the cycle where the head and tail are moving at the same speed.
        float sweep = 30 + 160 * (1f - (float) Math.cos(elapsed / 900f * Math.PI * 2f)) / 2f;
        canvas.drawArc(rect, start % 360f, sweep, false, loadingPaint);
        parent.invalidate();
    }

    public boolean onTouch(int action, float x, float y) {
        if (drawnAlpha <= 0) {
            return false;
        }
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            boolean wasPressed = pressed;
            pressed = false;
            if (wasPressed) {
                parent.invalidate();
                // Sliding off before letting go is how you back out of a press, and this one costs
                // an API call, so it has to be cancellable.
                if (action == MotionEvent.ACTION_UP && pressBounds.contains((int) x, (int) y)) {
                    onTap();
                }
                return true;
            }
            return false;
        }
        if (action == MotionEvent.ACTION_DOWN && pressBounds.contains((int) x, (int) y)) {
            pressed = true;
            parent.invalidate();
            return true;
        }
        return pressed;
    }

    public void onTap() {}
}
