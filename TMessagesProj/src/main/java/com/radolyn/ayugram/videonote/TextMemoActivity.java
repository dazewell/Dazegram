package com.radolyn.ayugram.videonote;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.view.inputmethod.EditorInfo;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import org.telegram.messenger.AccountInstance;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.SendMessagesHelper;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RLottieImageView;
import org.telegram.ui.Components.SizeNotifierFrameLayout;

/**
 * The text memo: a compose card over the chat wallpaper, in its own task. Neither the chat nor who it goes to is shown,
 * so it never touches the app lock: a locked app stays locked, and closing returns to the launcher. The text is never
 * stored; leaving the screen drops it.
 */
public class TextMemoActivity extends Activity {

    private int account = -1;
    private SizeNotifierFrameLayout root;
    private View scrim;
    private LinearLayout content;
    private LinearLayout hero;
    private RLottieImageView plane;
    private TextView hint;
    private LinearLayout card;
    private TextView title;
    private ImageView closeButton;
    private FrameLayout inputBubble;
    private GradientDrawable inputBackground;
    private EditTextBoldCursor field;
    private ImageView sendButton;
    private boolean canSend;
    private boolean dismissing;
    private boolean heroHidden;
    // 0 until the recipient is resolved; a send before that waits for it in pendingText
    private long dialogId;
    private String pendingText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        ApplicationLoader.postInitApplication();
        Window window = getWindow();
        super.onCreate(savedInstanceState);

        Intent intent = getIntent();
        // A restore after process death or a relaunch from history must not reopen it, and nothing replays the intent
        boolean genuine = savedInstanceState == null && intent != null
                && (intent.getFlags() & Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) == 0 && TextMemoShortcut.isGenuine(intent);
        TextMemoShortcut.consume(intent);
        int selected = UserConfig.selectedAccount;
        if (!genuine || !UserConfig.getInstance(selected).isClientActivated()) {
            finish();
            return;
        }
        account = selected;
        VideoNoteTarget.resolveAsync(account, false, id -> {
            dialogId = id;
            if (pendingText != null) {
                sendTo(pendingText, id);
                pendingText = null;
            }
        });

        // No white flash if the wallpaper takes a moment
        window.setBackgroundDrawable(new ColorDrawable(Theme.getColor(Theme.key_windowBackgroundGray)));
        WindowCompat.setDecorFitsSystemWindows(window, false);
        window.setStatusBarColor(Color.TRANSPARENT);
        window.setNavigationBarColor(Color.TRANSPARENT);
        createView();
        setContentView(root);
        applyColors();
        animateIn();
    }

    private void createView() {
        // The chat wallpaper behind the card, as the video memo's shield shows: Telegram's own look, but no chat. A
        // see-through window was tried first; a launcher can drop its home screen for a second or two when it hands
        // over, and that showed through as black.
        root = new SizeNotifierFrameLayout(this) {
            // There's no action bar here; left on, the wallpaper is clipped below where one would be
            @Override
            protected boolean isActionBarVisible() {
                return false;
            }

            @Override
            protected boolean isStatusBarVisible() {
                return false;
            }
        };
        Drawable wallpaper = Theme.getCachedWallpaper();
        if (wallpaper == null) {
            Theme.loadWallpaper(false); // a cold start from the shortcut, before anything else loaded it
            wallpaper = Theme.getCachedWallpaper();
        }
        if (wallpaper != null) {
            root.setBackgroundImage(wallpaper, Theme.isWallpaperMotion());
        }
        // A tap outside closes an empty card like any dialog, but never throws away typed text
        root.setOnClickListener(v -> {
            if (field.length() == 0) {
                dismiss();
            }
        });

        // Keeps the status icons legible over the wallpaper
        scrim = new View(this);
        root.addView(scrim, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 120, Gravity.TOP));

        // The wallpaper and scrim run edge to edge; everything else sits inside the system bars and above the keyboard
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        root.addView(content, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            // Frozen while leaving: the keyboard hides at the start of an exit, and the card leaves on its own motion
            // rather than jumping down with the inset
            if (!dismissing) {
                Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.ime() | WindowInsetsCompat.Type.displayCutout());
                content.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            }
            return WindowInsetsCompat.CONSUMED;
        });

        hero = new LinearLayout(this);
        hero.setOrientation(LinearLayout.VERTICAL);
        hero.setGravity(Gravity.CENTER);
        content.addView(hero, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f));
        // Too little room above the card, with the keyboard up on a short screen: the plane steps aside
        hero.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> setHeroHidden(b - t < dp(120)));

        plane = new RLottieImageView(this);
        plane.setScaleType(ImageView.ScaleType.CENTER);
        plane.setAnimation(R.raw.plane_logo_plain, 40, 40);
        hero.addView(plane, LayoutHelper.createLinear(72, 72, Gravity.CENTER_HORIZONTAL));

        hint = new TextView(this);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(dp(12), dp(6), dp(12), dp(6));
        hint.setText(LocaleController.getString(R.string.TextMemoHint));
        hero.addView(hint, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 0, 10, 0, 0));

        card = new LinearLayout(this);
        card.setClickable(true);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setElevation(dp(8));
        card.setPadding(dp(16), dp(12), dp(16), dp(16));
        content.addView(card, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 8, 8, 8, 8));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        card.addView(header, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 44));

        title = new TextView(this);
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 17);
        title.setTypeface(AndroidUtilities.bold());
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setText(LocaleController.getString(R.string.TextMemoShortcutLabel));
        header.addView(title, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL));

        closeButton = new ImageView(this);
        closeButton.setScaleType(ImageView.ScaleType.CENTER);
        closeButton.setImageResource(R.drawable.ic_close_white);
        closeButton.setContentDescription(LocaleController.getString(R.string.Discard));
        closeButton.setOnClickListener(v -> dismiss());
        header.addView(closeButton, LayoutHelper.createLinear(40, 40, Gravity.CENTER_VERTICAL));

        LinearLayout inputRow = new LinearLayout(this);
        inputRow.setOrientation(LinearLayout.HORIZONTAL);
        card.addView(inputRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 8, 0, 0));

        // The input drawn like the composer's bubble, tinted with the send colour, and outlined harder while focused
        inputBubble = new FrameLayout(this);
        inputBackground = new GradientDrawable();
        inputBackground.setCornerRadius(dp(18));
        inputBubble.setBackground(inputBackground);
        inputBubble.setPadding(dp(14), dp(8), dp(14), dp(8));
        inputBubble.setMinimumHeight(dp(44));
        inputRow.addView(inputBubble, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));

        field = new EditTextBoldCursor(this);
        field.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        field.setBackground(null);
        field.setPadding(0, 0, 0, 0);
        field.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        field.setMinLines(1);
        field.setMaxLines(6);
        field.setVerticalScrollBarEnabled(true);
        // The composer's own flags, so the keyboard behaves as it does in any chat
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        field.setImeOptions(EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        field.setHint(LocaleController.getString(R.string.TypeMessage));
        field.setCursorSize(dp(20));
        field.setCursorWidth(1.5f);
        field.setOnFocusChangeListener((v, hasFocus) -> animateOutline(hasFocus));
        field.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                updateSendButton(true);
            }
        });
        inputBubble.addView(field, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL));

        sendButton = new ImageView(this);
        sendButton.setScaleType(ImageView.ScaleType.CENTER);
        sendButton.setImageResource(R.drawable.ic_send);
        sendButton.setContentDescription(LocaleController.getString(R.string.Send));
        sendButton.setElevation(dp(4));
        sendButton.setOnClickListener(v -> send());
        inputRow.addView(sendButton, LayoutHelper.createLinear(44, 44, Gravity.BOTTOM, 8, 0, 0, 0));

        updateSendButton(false);
    }

    private void applyColors() {
        boolean dark = Theme.isCurrentThemeDark();
        WindowInsetsControllerCompat controller = WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        controller.setAppearanceLightStatusBars(false); // the scrim keeps the top dark enough for light icons
        controller.setAppearanceLightNavigationBars(!dark);
        scrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{dark ? 0x40000000 : 0x33000000, 0}));

        int service = Theme.getColor(Theme.key_chat_serviceBackground);
        GradientDrawable disc = new GradientDrawable();
        disc.setShape(GradientDrawable.OVAL);
        disc.setColor(service);
        plane.setBackground(disc);
        GradientDrawable pill = new GradientDrawable();
        pill.setCornerRadius(dp(14));
        pill.setColor(service);
        hint.setBackground(pill);
        hint.setTextColor(Theme.getColor(Theme.key_chat_serviceText));

        GradientDrawable cardBackground = new GradientDrawable();
        cardBackground.setCornerRadius(dp(24));
        cardBackground.setColor(Theme.getColor(Theme.key_dialogBackground));
        card.setBackground(cardBackground);
        title.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        closeButton.setColorFilter(new PorterDuffColorFilter(Theme.getColor(Theme.key_dialogTextGray2), PorterDuff.Mode.SRC_IN));
        closeButton.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector), Theme.RIPPLE_MASK_CIRCLE_20DP));

        int accent = Theme.getColor(Theme.key_chat_messagePanelSend);
        inputBackground.setColor(Theme.multAlpha(accent, .10f));
        inputBackground.setStroke(dp(1), Theme.multAlpha(accent, .25f));
        field.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        field.setHintTextColor(Theme.getColor(Theme.key_dialogTextHint));
        field.setCursorColor(accent);

        GradientDrawable sendBackground = new GradientDrawable();
        sendBackground.setShape(GradientDrawable.OVAL);
        sendBackground.setColor(accent);
        sendButton.setBackground(sendBackground);
        // The colour the record button's icon uses on the same accent, which a light accent needs over plain white
        sendButton.setColorFilter(new PorterDuffColorFilter(Theme.getColor(Theme.key_chat_messagePanelVoicePressed), PorterDuff.Mode.SRC_IN));
    }

    private void animateIn() {
        scrim.setAlpha(0f);
        scrim.animate().alpha(1f).setDuration(250).setInterpolator(CubicBezierInterpolator.EASE_OUT).start();
        card.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                card.getViewTreeObserver().removeOnPreDrawListener(this);
                card.setTranslationY(card.getHeight() + dp(8));
                card.setScaleX(.96f);
                card.setScaleY(.96f);
                card.animate().translationY(0).scaleX(1f).scaleY(1f).setDuration(340).setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT).start();
                return true;
            }
        });
        plane.setAlpha(0f);
        plane.setTranslationY(dp(12));
        plane.setScaleX(.8f);
        plane.setScaleY(.8f);
        plane.animate().translationY(0).scaleX(1f).scaleY(1f).setStartDelay(100).setDuration(320).setInterpolator(CubicBezierInterpolator.EASE_OUT_BACK).start();
        plane.animate().alpha(1f).setStartDelay(100).setDuration(200).start();
        hint.setAlpha(0f);
        hint.animate().alpha(1f).setStartDelay(100).setDuration(200).start();
        plane.postDelayed(idleLoop, 220);
        // Once the card is nearly in, so the keyboard's lift continues its entry instead of fighting it
        field.requestFocus();
        field.postDelayed(() -> AndroidUtilities.showKeyboard(field), 180);
    }

    // The plane's hover, then a rest: a calm hero, not a spinner. It stops while there's text or no room for it.
    private final Runnable idleLoop = new Runnable() {
        @Override
        public void run() {
            if (dismissing) {
                return;
            }
            if (!heroHidden && field.length() == 0) {
                playPlane();
            }
            plane.postDelayed(this, 2500);
        }
    };

    private void playPlane() {
        if (plane.getAnimatedDrawable() != null) {
            plane.getAnimatedDrawable().setCurrentFrame(0, false);
        }
        plane.playAnimation();
    }

    private void setHeroHidden(boolean hidden) {
        if (hidden != heroHidden && !dismissing) {
            heroHidden = hidden;
            hero.animate().alpha(hidden ? 0f : 1f).setDuration(150).start();
        }
    }

    private void animateOutline(boolean focused) {
        int accent = Theme.getColor(Theme.key_chat_messagePanelSend);
        float from = focused ? 0f : 1f;
        ValueAnimator animator = ValueAnimator.ofFloat(from, 1f - from);
        animator.setDuration(150);
        animator.addUpdateListener(a -> {
            float t = (float) a.getAnimatedValue();
            inputBackground.setStroke(AndroidUtilities.dp(1 + .5f * t), Theme.multAlpha(accent, .25f + .75f * t));
        });
        animator.start();
    }

    private void updateSendButton(boolean animated) {
        boolean hasText = !TextUtils.isEmpty(getText());
        if (hasText == canSend && animated) {
            return;
        }
        canSend = hasText;
        sendButton.setClickable(hasText);
        if (!animated) {
            sendButton.setAlpha(hasText ? 1f : .5f);
            sendButton.setScaleX(hasText ? 1f : .85f);
            sendButton.setScaleY(hasText ? 1f : .85f);
        } else if (hasText) {
            // A pop past full size as it wakes up
            sendButton.animate().alpha(1f).scaleX(1.12f).scaleY(1.12f).setDuration(110).setInterpolator(CubicBezierInterpolator.EASE_OUT).withEndAction(() ->
                    sendButton.animate().scaleX(1f).scaleY(1f).setDuration(70).setInterpolator(CubicBezierInterpolator.EASE_IN).start()).start();
        } else {
            sendButton.animate().alpha(.5f).scaleX(.85f).scaleY(.85f).setDuration(150).setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT).start();
        }
    }

    private String getText() {
        return field.getText() == null ? "" : field.getText().toString().trim();
    }

    private void send() {
        String text = getText();
        if (dismissing || text.isEmpty()) {
            return;
        }
        sendButton.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP, HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING);
        // Sent now, not after the animation, so the message isn't held up by it
        if (dialogId != 0) {
            sendTo(text, dialogId);
        } else {
            pendingText = text; // goes out from the resolve callback, which outlives this screen
        }
        beginExit();
        // One thing moves at a time: the text lifts out of the card to the plane, the plane takes off, then the card drops
        sendButton.animate().alpha(.5f).scaleX(.85f).scaleY(.85f).setDuration(150).setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT).start();
        flyToPlane(text);
        field.setAlpha(0f);
        hint.animate().alpha(0f).setStartDelay(120).setDuration(150).start();
        AndroidUtilities.runOnUIThread(this::takeOff, 200);
        exitCard(280);
    }

    // The text, as an outgoing bubble over the input, flies into the plane
    private void flyToPlane(String text) {
        try {
            TextView bubble = new TextView(this);
            bubble.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
            bubble.setMaxLines(4);
            bubble.setEllipsize(TextUtils.TruncateAt.END);
            bubble.setText(text);
            bubble.setTextColor(Theme.getColor(Theme.key_chat_messageTextOut));
            bubble.setPadding(dp(12), dp(8), dp(12), dp(8));
            GradientDrawable bubbleBackground = new GradientDrawable();
            bubbleBackground.setCornerRadius(dp(16));
            bubbleBackground.setColor(Theme.getColor(Theme.key_chat_outBubble));
            bubble.setBackground(bubbleBackground);
            bubble.setElevation(dp(2));

            // Same box as the input; the root has no padding, so window coordinates relative to it place it exactly
            int[] rootAt = new int[2];
            int[] inputAt = new int[2];
            int[] planeAt = new int[2];
            root.getLocationInWindow(rootAt);
            inputBubble.getLocationInWindow(inputAt);
            plane.getLocationInWindow(planeAt);
            root.addView(bubble, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP | Gravity.START));
            bubble.measure(View.MeasureSpec.makeMeasureSpec(inputBubble.getWidth(), View.MeasureSpec.AT_MOST), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            float startX = inputAt[0] - rootAt[0];
            float startY = inputAt[1] - rootAt[1];
            float endX = planeAt[0] - rootAt[0] + plane.getWidth() / 2f - bubble.getMeasuredWidth() / 2f;
            float endY = planeAt[1] - rootAt[1] + plane.getHeight() / 2f - bubble.getMeasuredHeight() / 2f;
            bubble.setTranslationX(startX);
            bubble.setTranslationY(startY);
            ValueAnimator flight = ValueAnimator.ofFloat(0f, 1f);
            flight.setDuration(280);
            flight.setInterpolator(CubicBezierInterpolator.EASE_BOTH);
            flight.addUpdateListener(a -> {
                float t = (float) a.getAnimatedValue();
                bubble.setTranslationX(startX + (endX - startX) * t);
                bubble.setTranslationY(startY + (endY - startY) * t);
                bubble.setScaleX(1f - .7f * t);
                bubble.setScaleY(1f - .7f * t);
                // Gone by the time it reaches the plane, over its last 80 ms
                bubble.setAlpha(Math.min(1f, (1f - t) * 280 / 80f));
            });
            flight.addListener(new android.animation.AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(android.animation.Animator animation) {
                    root.removeView(bubble);
                }
            });
            flight.start();
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    // A catch as the text lands, then the plane leaves toward the top end corner
    private void takeOff() {
        if (isFinishing()) {
            return;
        }
        float direction = LocaleController.isRTL ? -1 : 1;
        plane.animate().scaleX(1.12f).scaleY(1.12f).setStartDelay(0).setDuration(90).setInterpolator(CubicBezierInterpolator.EASE_OUT).withEndAction(() -> {
            playPlane();
            ValueAnimator flight = ValueAnimator.ofFloat(0f, 1f);
            flight.setDuration(260);
            flight.setInterpolator(CubicBezierInterpolator.EmphasizedAccelerate);
            flight.addUpdateListener(a -> {
                float t = (float) a.getAnimatedValue();
                plane.setTranslationX(direction * dp(120) * t);
                plane.setTranslationY(-dp(150) * t);
                plane.setRotation(-12 * direction * t);
                plane.setScaleX(1.12f - .42f * t);
                plane.setScaleY(1.12f - .42f * t);
                plane.setAlpha(Math.min(1f, (1f - t) * 260 / 140f));
            });
            flight.start();
        }).start();
    }

    private void sendTo(String text, long dialogId) {
        try {
            SendMessagesHelper.prepareSendingText(AccountInstance.getInstance(account), text, dialogId, true, 0, 0, 0);
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    // Close, back, or a tap outside an empty card: nothing flies, so it never looks sent
    private void dismiss() {
        if (dismissing) {
            return;
        }
        beginExit();
        field.setText("");
        hero.animate().alpha(0f).setDuration(120).start();
        exitCard(0);
    }

    // Shared start of both exits: stop whatever the open or idle loop has running, freeze the insets, drop the keyboard
    private void beginExit() {
        dismissing = true;
        plane.removeCallbacks(idleLoop);
        plane.animate().cancel();
        hint.animate().cancel();
        sendButton.animate().cancel();
        AndroidUtilities.hideKeyboard(field);
        // A fallback in case an end action never runs
        root.postDelayed(() -> {
            if (!isFinishing()) {
                finish();
            }
        }, 800);
    }

    private void exitCard(long delay) {
        scrim.animate().alpha(0f).setStartDelay(delay + 20).setDuration(200).start();
        card.animate().translationY(root.getHeight() - card.getTop()).scaleX(1f).scaleY(1f)
                .setStartDelay(delay).setDuration(220).setInterpolator(CubicBezierInterpolator.EmphasizedAccelerate)
                .withEndAction(() -> {
                    if (!isFinishing()) {
                        finish();
                    }
                }).start();
    }

    @Override
    public void onBackPressed() {
        if (root == null) {
            super.onBackPressed();
            return;
        }
        dismiss();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // A tap on the shortcut while this is up just keeps it; anything else closes it
        boolean genuine = TextMemoShortcut.isGenuine(intent);
        TextMemoShortcut.consume(intent);
        if (!genuine && root != null) {
            dismiss();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (account >= 0) {
            ConnectionsManager.getInstance(account).setAppPaused(false, false);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (account >= 0) {
            ConnectionsManager.getInstance(account).setAppPaused(true, false);
        }
    }

    // Leaving the screen drops the text: it is never kept anywhere, so nobody finds it waiting later
    @Override
    protected void onStop() {
        super.onStop();
        if (field != null) {
            field.setText("");
        }
        if (!isFinishing()) {
            finish();
        }
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        AndroidUtilities.checkDisplaySize(this, newConfig);
    }
}
