package com.radolyn.ayugram.videonote;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.app.Activity;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
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
import android.view.WindowManager;
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

/**
 * The text memo: a bare card to type a message into, over a plain surface, in its own task. Nothing of the app is
 * shown, so it never touches the app lock: a locked app stays locked, and closing returns to the launcher. The text is
 * never stored; leaving the screen drops it.
 */
public class TextMemoActivity extends Activity {

    private int account = -1;
    private FrameLayout root;
    private LinearLayout card;
    private EditTextBoldCursor field;
    private TextView discardButton;
    private ImageView sendButton;
    private boolean canSend;
    private boolean dismissing;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        ApplicationLoader.postInitApplication();
        Window window = getWindow();
        // Always, not only with a passcode: this screen exists to be private, and the cost is a blank recents card
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE);
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
        animateIn();
    }

    private void createView() {
        root = new FrameLayout(this);
        // Edge to edge on recent Android, so adjustResize doesn't move anything: the card rides the keyboard by insets
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.ime() | WindowInsetsCompat.Type.displayCutout());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return WindowInsetsCompat.CONSUMED;
        });

        card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setElevation(dp(8));
        card.setPadding(dp(20), dp(16), dp(12), dp(12));
        root.addView(card, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.BOTTOM, 8, 8, 8, 8));

        TextView title = new TextView(this);
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        title.setTypeface(AndroidUtilities.bold());
        title.setText(LocaleController.getString(R.string.TextMemoShortcutLabel));
        title.setTag(Theme.key_dialogTextBlack);
        card.addView(title, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 0, 8, 4));

        field = new EditTextBoldCursor(this);
        field.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 18);
        field.setBackground(null);
        field.setPadding(0, dp(8), dp(8), dp(8));
        field.setGravity(Gravity.TOP | Gravity.START);
        field.setMinLines(3);
        field.setMaxLines(8);
        field.setVerticalScrollBarEnabled(true);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES | InputType.TYPE_TEXT_FLAG_AUTO_CORRECT);
        field.setImeOptions(EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        field.setHint(LocaleController.getString(R.string.TypeMessage));
        field.setCursorSize(dp(20));
        field.setCursorWidth(1.5f);
        field.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                updateButtons(true);
            }
        });
        card.addView(field, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        FrameLayout bottom = new FrameLayout(this);
        card.addView(bottom, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48, 0, 4, 0, 0));

        discardButton = new TextView(this);
        discardButton.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        discardButton.setTypeface(AndroidUtilities.bold());
        discardButton.setGravity(Gravity.CENTER);
        discardButton.setPadding(dp(12), 0, dp(12), 0);
        discardButton.setText(LocaleController.getString(R.string.Discard));
        discardButton.setOnClickListener(v -> dismiss());
        bottom.addView(discardButton, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, 40, Gravity.START | Gravity.CENTER_VERTICAL, -12, 0, 0, 0));

        sendButton = new ImageView(this);
        sendButton.setScaleType(ImageView.ScaleType.CENTER);
        sendButton.setImageResource(R.drawable.ic_send);
        sendButton.setContentDescription(LocaleController.getString(R.string.Send));
        sendButton.setElevation(dp(2));
        sendButton.setOnClickListener(v -> send());
        bottom.addView(sendButton, LayoutHelper.createFrame(48, 48, Gravity.END | Gravity.CENTER_VERTICAL));

        updateButtons(false);
    }

    private void applyColors() {
        int surface = Theme.getColor(Theme.key_windowBackgroundGray);
        root.setBackgroundColor(surface);
        boolean light = AndroidUtilities.computePerceivedBrightness(surface) > 0.721f;
        WindowInsetsControllerCompat controller = WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        controller.setAppearanceLightStatusBars(light);
        controller.setAppearanceLightNavigationBars(light);

        GradientDrawable cardBackground = new GradientDrawable();
        cardBackground.setCornerRadius(dp(20));
        cardBackground.setColor(Theme.getColor(Theme.key_dialogBackground));
        card.setBackground(cardBackground);
        for (int i = 0; i < card.getChildCount(); i++) {
            if (card.getChildAt(i) instanceof TextView text && text.getTag() instanceof Integer key) {
                text.setTextColor(Theme.getColor(key));
            }
        }
        field.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        field.setHintTextColor(Theme.getColor(Theme.key_dialogTextHint));
        field.setCursorColor(Theme.getColor(Theme.key_windowBackgroundWhiteInputFieldActivated));
        discardButton.setTextColor(Theme.getColor(Theme.key_dialogTextBlue));
        discardButton.setBackground(Theme.createRadSelectorDrawable(Theme.multAlpha(Theme.getColor(Theme.key_dialogTextBlue), .12f), 20, 20));

        GradientDrawable sendBackground = new GradientDrawable();
        sendBackground.setShape(GradientDrawable.OVAL);
        sendBackground.setColor(Theme.getColor(Theme.key_chat_messagePanelSend));
        sendButton.setBackground(sendBackground);
        sendButton.setColorFilter(new PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN));
    }

    private void animateIn() {
        root.setAlpha(0f);
        root.animate().alpha(1f).setDuration(120).start();
        card.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                card.getViewTreeObserver().removeOnPreDrawListener(this);
                card.setTranslationY(card.getHeight() + dp(8));
                card.animate().translationY(0).setDuration(280).setInterpolator(CubicBezierInterpolator.DEFAULT).start();
                return true;
            }
        });
        field.requestFocus();
        field.post(() -> AndroidUtilities.showKeyboard(field));
    }

    private void updateButtons(boolean animated) {
        boolean hasText = !TextUtils.isEmpty(getText());
        if (hasText != canSend || !animated) {
            canSend = hasText;
            float alpha = hasText ? 1f : .4f;
            float scale = hasText ? 1f : .8f;
            if (animated) {
                sendButton.animate().alpha(alpha).scaleX(scale).scaleY(scale).setDuration(150).setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT).start();
            } else {
                sendButton.setAlpha(alpha);
                sendButton.setScaleX(scale);
                sendButton.setScaleY(scale);
            }
            sendButton.setClickable(hasText);
        }
        boolean showDiscard = field.length() > 0;
        if (showDiscard != (discardButton.getVisibility() == View.VISIBLE)) {
            discardButton.setVisibility(showDiscard ? View.VISIBLE : View.INVISIBLE);
            if (showDiscard && animated) {
                discardButton.setAlpha(0f);
                discardButton.animate().alpha(1f).setDuration(150).start();
            }
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
        try {
            // Resolved now, not at launch: the recipient may have changed their privacy while this was open
            long dialogId = VideoNoteTarget.resolve(account, false);
            SendMessagesHelper.prepareSendingText(AccountInstance.getInstance(account), text, dialogId, true, 0, 0, 0);
            sendButton.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP, HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING);
        } catch (Throwable e) {
            FileLog.e(e);
        }
        dismiss();
    }

    private void dismiss() {
        if (dismissing) {
            return;
        }
        dismissing = true;
        field.setText("");
        AndroidUtilities.hideKeyboard(field);
        card.animate().translationY(card.getHeight() + dp(8)).setDuration(200).setInterpolator(CubicBezierInterpolator.EASE_IN).start();
        root.animate().alpha(0f).setStartDelay(80).setDuration(120).withEndAction(this::finishQuietly).start();
    }

    private void finishQuietly() {
        if (!isFinishing()) {
            finish();
            overridePendingTransition(0, 0);
        }
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
