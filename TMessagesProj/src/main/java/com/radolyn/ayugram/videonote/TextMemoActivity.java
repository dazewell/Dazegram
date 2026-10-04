package com.radolyn.ayugram.videonote;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
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
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.SendMessagesHelper;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RLottieImageView;
import org.telegram.ui.Components.SizeNotifierFrameLayout;

/**
 * The text memo: a compose card addressed to the memo's recipient, over the chat wallpaper, in its own task. No chat is
 * shown, so it never touches the app lock: a locked app stays locked, and closing returns to the launcher. The text is
 * never stored; leaving the screen drops it.
 */
public class TextMemoActivity extends Activity {

    private int account = -1;
    private SizeNotifierFrameLayout root;
    private LinearLayout hero;
    private RLottieImageView plane;
    private LinearLayout card;
    private BackupImageView avatar;
    private AvatarDrawable avatarDrawable;
    private TextView toLabel;
    private TextView nameView;
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

        WindowCompat.setDecorFitsSystemWindows(window, false);
        window.setStatusBarColor(Color.TRANSPARENT);
        window.setNavigationBarColor(Color.TRANSPARENT);
        createView();
        setContentView(root);
        applyColors();
        // Until the target resolves, show who it was picked as; Saved Messages if nobody, the same fallback it resolves to
        long stored = VideoNoteTarget.get(account);
        setRecipient(stored != 0 ? MessagesController.getInstance(account).getUser(stored) : null, false);
        VideoNoteTarget.resolveAsync(account, false, id -> {
            dialogId = id;
            if (pendingText != null) {
                sendTo(pendingText, id);
                pendingText = null;
            } else if (!dismissing) {
                setRecipient(id == UserConfig.getInstance(account).getClientUserId() ? null : MessagesController.getInstance(account).getUser(id), true);
            }
        });
        animateIn();
    }

    private void createView() {
        // The chat wallpaper behind the card, as the video memo's shield shows: Telegram's own look, but no chat. A
        // see-through window was tried first; a launcher can drop its home screen for a second or two when it hands
        // over, and that showed through as black.
        root = new SizeNotifierFrameLayout(this);
        Drawable wallpaper = Theme.getCachedWallpaper();
        if (wallpaper == null) {
            Theme.loadWallpaper(false); // a cold start from the shortcut, before anything else loaded it
            wallpaper = Theme.getCachedWallpaper();
        }
        if (wallpaper != null) {
            root.setBackgroundImage(wallpaper, Theme.isWallpaperMotion());
        } else {
            root.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
        }
        // Edge to edge on recent Android, so adjustResize doesn't move anything: the card rides the keyboard by insets
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.ime() | WindowInsetsCompat.Type.displayCutout());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            // A tall keyboard leaves no room for the plane above the card; it steps aside rather than crowd it
            int ime = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom;
            setHeroHidden(v.getHeight() > 0 && ime > v.getHeight() * 0.55f);
            return WindowInsetsCompat.CONSUMED;
        });
        // A tap outside closes an empty card like any dialog, but never throws away typed text
        root.setOnClickListener(v -> {
            if (field.length() == 0) {
                dismiss();
            }
        });

        // Depth under the status bar, so the wallpaper's pattern doesn't run straight into it
        View scrim = new View(this);
        scrim.setTag("scrim");
        root.addView(scrim, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        hero = new LinearLayout(this);
        hero.setOrientation(LinearLayout.VERTICAL);
        hero.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(hero, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP, 0, 96, 0, 0));

        plane = new RLottieImageView(this);
        plane.setScaleType(ImageView.ScaleType.CENTER);
        plane.setAnimation(R.raw.plane_logo_plain, 64, 64);
        hero.addView(plane, LayoutHelper.createLinear(104, 104, Gravity.CENTER_HORIZONTAL));

        TextView hint = new TextView(this);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(dp(12), dp(6), dp(12), dp(6));
        hint.setText(LocaleController.getString(R.string.TextMemoHint));
        hint.setTag("pill");
        hero.addView(hint, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 0, 12, 0, 0));

        card = new LinearLayout(this);
        card.setClickable(true);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setElevation(dp(8));
        card.setPadding(dp(16), dp(14), dp(16), dp(14));
        root.addView(card, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.BOTTOM, 8, 8, 8, 8));

        // To: the recipient, which stands in for a title
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        card.addView(header, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48));

        avatarDrawable = new AvatarDrawable();
        avatar = new BackupImageView(this);
        avatar.setRoundRadius(dp(18));
        header.addView(avatar, LayoutHelper.createLinear(36, 36, Gravity.CENTER_VERTICAL));

        LinearLayout names = new LinearLayout(this);
        names.setOrientation(LinearLayout.VERTICAL);
        header.addView(names, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL, 10, 0, 8, 0));

        toLabel = new TextView(this);
        toLabel.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        toLabel.setText(LocaleController.getString(R.string.TextMemoTo));
        names.addView(toLabel, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));

        nameView = new TextView(this);
        nameView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        nameView.setTypeface(AndroidUtilities.bold());
        nameView.setSingleLine(true);
        nameView.setEllipsize(TextUtils.TruncateAt.END);
        names.addView(nameView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        closeButton = new ImageView(this);
        closeButton.setScaleType(ImageView.ScaleType.CENTER);
        closeButton.setImageResource(R.drawable.ic_close_white);
        closeButton.setContentDescription(LocaleController.getString(R.string.Discard));
        closeButton.setOnClickListener(v -> dismiss());
        header.addView(closeButton, LayoutHelper.createLinear(40, 40, Gravity.CENTER_VERTICAL));

        // The input drawn like the composer's bubble, tinted with the send colour, and outlined harder while focused
        inputBubble = new FrameLayout(this);
        inputBackground = new GradientDrawable();
        inputBackground.setCornerRadius(dp(18));
        inputBubble.setBackground(inputBackground);
        inputBubble.setPadding(dp(14), dp(10), dp(14), dp(10));
        card.addView(inputBubble, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 12, 0, 0));

        field = new EditTextBoldCursor(this);
        field.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 18);
        field.setBackground(null);
        field.setPadding(0, 0, 0, 0);
        field.setGravity(Gravity.TOP | Gravity.START);
        field.setMinLines(3);
        field.setMaxLines(8);
        field.setVerticalScrollBarEnabled(true);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES | InputType.TYPE_TEXT_FLAG_AUTO_CORRECT);
        // Nothing is kept by the app; ask the keyboard not to keep it either
        field.setImeOptions(EditorInfo.IME_FLAG_NO_EXTRACT_UI | EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
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
        inputBubble.addView(field, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        FrameLayout bottom = new FrameLayout(this);
        card.addView(bottom, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 52, 0, 10, 0, 0));

        sendButton = new ImageView(this);
        sendButton.setScaleType(ImageView.ScaleType.CENTER);
        sendButton.setImageResource(R.drawable.ic_send);
        sendButton.setContentDescription(LocaleController.getString(R.string.Send));
        sendButton.setElevation(dp(4));
        sendButton.setOnClickListener(v -> send());
        bottom.addView(sendButton, LayoutHelper.createFrame(52, 52, Gravity.END | Gravity.CENTER_VERTICAL));

        updateSendButton(false);
    }

    private void applyColors() {
        boolean dark = Theme.isCurrentThemeDark();
        WindowInsetsControllerCompat controller = WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        controller.setAppearanceLightStatusBars(false); // the scrim keeps the top dark enough for light icons
        controller.setAppearanceLightNavigationBars(!dark);

        root.findViewWithTag("scrim").setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{dark ? 0x66000000 : 0x40000000, 0, 0}));

        int service = Theme.getColor(Theme.key_chat_serviceBackground);
        GradientDrawable disc = new GradientDrawable();
        disc.setShape(GradientDrawable.OVAL);
        disc.setColor(service);
        plane.setBackground(disc);
        TextView hint = hero.findViewWithTag("pill");
        GradientDrawable pill = new GradientDrawable();
        pill.setCornerRadius(dp(14));
        pill.setColor(service);
        hint.setBackground(pill);
        hint.setTextColor(Theme.getColor(Theme.key_chat_serviceText));

        GradientDrawable cardBackground = new GradientDrawable();
        cardBackground.setCornerRadius(dp(24));
        cardBackground.setColor(Theme.getColor(Theme.key_dialogBackground));
        card.setBackground(cardBackground);
        toLabel.setTextColor(Theme.getColor(Theme.key_dialogTextGray2));
        nameView.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
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
        sendButton.setColorFilter(new PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN));
    }

    // Nobody, or yourself, reads as Saved Messages
    private void setRecipient(TLRPC.User user, boolean animated) {
        Runnable apply = () -> {
            if (user == null) {
                avatarDrawable.setAvatarType(AvatarDrawable.AVATAR_TYPE_SAVED);
                avatar.setImageDrawable(avatarDrawable);
                nameView.setText(LocaleController.getString(R.string.SavedMessages));
            } else {
                avatarDrawable.setAvatarType(AvatarDrawable.AVATAR_TYPE_NORMAL);
                avatarDrawable.setInfo(account, user);
                avatar.setForUserOrChat(user, avatarDrawable);
                nameView.setText(UserObject.getUserName(user));
            }
        };
        if (!animated) {
            apply.run();
            return;
        }
        avatar.animate().alpha(0f).setDuration(75).withEndAction(() -> {
            apply.run();
            avatar.animate().alpha(1f).setDuration(75).start();
        }).start();
    }

    private void animateIn() {
        View scrim = root.findViewWithTag("scrim");
        scrim.setAlpha(0f);
        scrim.animate().alpha(1f).setDuration(250).start();
        hero.setAlpha(0f);
        hero.animate().alpha(1f).setStartDelay(80).setDuration(200).start();
        plane.postDelayed(plane::playAnimation, 120);
        card.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                card.getViewTreeObserver().removeOnPreDrawListener(this);
                card.setTranslationY(card.getHeight() + dp(8));
                card.setScaleX(.96f);
                card.setScaleY(.96f);
                card.animate().translationY(0).scaleX(1f).scaleY(1f).setDuration(320).setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT).start();
                return true;
            }
        });
        field.requestFocus();
        field.post(() -> AndroidUtilities.showKeyboard(field));
    }

    private void setHeroHidden(boolean hidden) {
        if (hidden != heroHidden && !dismissing) {
            heroHidden = hidden;
            hero.animate().alpha(hidden ? 0f : 1f).setStartDelay(0).setDuration(150).start();
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
            sendButton.setAlpha(hasText ? 1f : .4f);
            sendButton.setScaleX(hasText ? 1f : .85f);
            sendButton.setScaleY(hasText ? 1f : .85f);
        } else if (hasText) {
            // A pop past full size as it wakes up
            sendButton.animate().alpha(1f).scaleX(1.12f).scaleY(1.12f).setDuration(110).setInterpolator(CubicBezierInterpolator.EASE_OUT).withEndAction(() ->
                    sendButton.animate().scaleX(1f).scaleY(1f).setDuration(70).setInterpolator(CubicBezierInterpolator.EASE_IN).start()).start();
        } else {
            sendButton.animate().alpha(.4f).scaleX(.85f).scaleY(.85f).setDuration(150).setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT).start();
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
        flyOut(text);
        dismiss();
    }

    // The text leaves as an outgoing bubble, up and away, which is all the confirmation there is
    private void flyOut(String text) {
        try {
            TextView bubble = new TextView(this);
            bubble.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
            bubble.setMaxLines(4);
            bubble.setEllipsize(TextUtils.TruncateAt.END);
            bubble.setText(text);
            bubble.setTextColor(Theme.getColor(Theme.key_chat_messageTextOut));
            bubble.setPadding(dp(12), dp(8), dp(12), dp(8));
            bubble.setMaxWidth((int) (root.getWidth() * .7f));
            GradientDrawable bubbleBackground = new GradientDrawable();
            bubbleBackground.setCornerRadius(dp(16));
            bubbleBackground.setColor(Theme.getColor(Theme.key_chat_outBubble));
            bubble.setBackground(bubbleBackground);
            bubble.setElevation(dp(2));

            int[] rootAt = new int[2];
            int[] inputAt = new int[2];
            root.getLocationInWindow(rootAt);
            inputBubble.getLocationInWindow(inputAt);
            root.addView(bubble, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP | Gravity.END));
            bubble.setTranslationX(-(root.getWidth() - (inputAt[0] - rootAt[0]) - inputBubble.getWidth()) + root.getPaddingRight());
            bubble.setTranslationY(inputAt[1] - rootAt[1] - root.getPaddingTop());
            bubble.animate()
                    .translationYBy(-root.getHeight() * .35f)
                    .scaleX(.6f).scaleY(.6f)
                    .setDuration(380).setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT).start();
            bubble.animate().alpha(0f).setStartDelay(260).setDuration(120).start();
            plane.animate().translationXBy(dp(60)).translationYBy(-dp(60)).alpha(0f).setDuration(300).start();
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    private void sendTo(String text, long dialogId) {
        try {
            SendMessagesHelper.prepareSendingText(AccountInstance.getInstance(account), text, dialogId, true, 0, 0, 0);
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    private void dismiss() {
        if (dismissing) {
            return;
        }
        dismissing = true;
        field.setText("");
        AndroidUtilities.hideKeyboard(field);
        card.animate().translationY(card.getHeight() + dp(8)).setDuration(200).setInterpolator(CubicBezierInterpolator.EASE_IN).start();
        root.postDelayed(() -> {
            if (!isFinishing()) {
                finish();
            }
        }, 400);
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
