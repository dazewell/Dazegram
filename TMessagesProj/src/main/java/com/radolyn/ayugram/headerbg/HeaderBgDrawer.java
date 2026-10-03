package com.radolyn.ayugram.headerbg;

import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Shader;
import android.view.View;

import androidx.core.graphics.ColorUtils;

import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.ImageReceiver;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.UserObject;
import org.telegram.messenger.ChatObject;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ChatActivity;

import xyz.nextalone.nagram.helpers.InterfaceStyleController;
import xyz.nextalone.nagram.helpers.InterfaceStyleSolidHeader;

/**
 * Paints a chat's photo behind the MD3 flat chat header. ActionBar calls {@link #draw} right after
 * it paints the flat surface and before its children, so the title and icons stay on top and Glass,
 * which never takes that branch, can't show it.
 * <p>One instance per ActionBar, owned by that ActionBar's field. The image and the avatar observer
 * follow the ActionBar's own window attachment rather than the fragment, because a theme or language
 * rebuild replaces the ActionBar while the fragment lives on.
 */
public final class HeaderBgDrawer implements NotificationCenter.NotificationCenterDelegate, View.OnAttachStateChangeListener {

    private final int account;
    private final long peerId;
    private final ActionBar actionBar;
    private final Theme.ResourcesProvider resourcesProvider;
    private final ImageReceiver imageReceiver;
    public final HeaderBgSettings settings;
    private boolean hasPhoto;

    private final Paint gradientPaint = new Paint();
    private int shaderW, shaderH, shaderFrom = -1, shaderColor, shaderStrength;
    private boolean shaderRtl;
    private int filterHue = Integer.MIN_VALUE, filterColor, filterStrength;

    private HeaderBgDrawer(ChatActivity fragment, ActionBar actionBar) {
        this.account = fragment.getCurrentAccount();
        this.actionBar = actionBar;
        this.resourcesProvider = fragment.getResourceProvider();
        TLRPC.User user = fragment.getCurrentUser();
        this.peerId = user != null ? user.id : -fragment.getCurrentChat().id;
        this.settings = HeaderBgSettings.load(account, fragment.getDialogId());
        imageReceiver = new ImageReceiver(actionBar);
        imageReceiver.setCurrentAccount(account);
        imageReceiver.setCrossfadeWithOldImage(true);
        loadPhoto();
        actionBar.addOnAttachStateChangeListener(this);
        if (actionBar.isAttachedToWindow()) {
            onViewAttachedToWindow(actionBar);
        }
    }

    /**
     * Chats keyed to one peer's photo. Saved Messages, Replies and Verify draw a glyph, and saved or
     * monoforum sub-chats would key one setting to a header showing someone else. Forum topics and
     * comment threads share their chat's dialog id, so they show the chat's photo and setting.
     */
    public static boolean eligible(ChatActivity fragment) {
        if (fragment.getChatMode() != ChatActivity.MODE_DEFAULT || fragment.isInPreviewMode()
                || fragment.isInBubbleMode() || fragment.isInsideContainer) {
            return false;
        }
        TLRPC.User user = fragment.getCurrentUser();
        if (user != null) {
            return !UserObject.isUserSelf(user) && !UserObject.isReplyUser(user) && user.id != UserObject.VERIFY;
        }
        TLRPC.Chat chat = fragment.getCurrentChat();
        return chat != null && !ChatObject.isMonoForum(chat);
    }

    /** From createView: attaches a drawer only when this chat has the background turned on. */
    public static void install(ChatActivity fragment) {
        if (HeaderBgSettings.isEnabled(fragment.getCurrentAccount(), fragment.getDialogId())) {
            obtain(fragment);
        }
    }

    /** The drawer on the fragment's current ActionBar, created on demand for the sheet. */
    public static HeaderBgDrawer obtain(ChatActivity fragment) {
        ActionBar actionBar = fragment.getActionBar();
        if (actionBar == null || !eligible(fragment)) {
            return null;
        }
        if (actionBar.naxHeaderBg == null) {
            actionBar.naxHeaderBg = new HeaderBgDrawer(fragment, actionBar);
        }
        return actionBar.naxHeaderBg;
    }

    public boolean hasPhoto() {
        return hasPhoto;
    }

    public void invalidate() {
        actionBar.invalidate();
    }

    private TLObject peer() {
        MessagesController mc = MessagesController.getInstance(account);
        return peerId > 0 ? mc.getUser(peerId) : mc.getChat(-peerId);
    }

    private void loadPhoto() {
        TLObject peer = peer();
        ImageLocation big = peer != null ? ImageLocation.getForUserOrChat(account, peer, ImageLocation.TYPE_BIG) : null;
        hasPhoto = big != null;
        if (!hasPhoto) {
            imageReceiver.clearImage();
            return;
        }
        // The small photo is what the header avatar already shows, so it is usually cached and
        // stands in until the big one arrives.
        ImageLocation small = ImageLocation.getForUserOrChat(account, peer, ImageLocation.TYPE_SMALL);
        imageReceiver.setImage(big, null, small, "50_50", (String) null, peer, 0);
    }

    @Override
    public void onViewAttachedToWindow(View v) {
        // The observer was off while another screen covered the chat; pick up a photo changed meanwhile.
        loadPhoto();
        imageReceiver.onAttachedToWindow();
        NotificationCenter.getInstance(account).addObserver(this, NotificationCenter.updateInterfaces);
    }

    @Override
    public void onViewDetachedFromWindow(View v) {
        imageReceiver.onDetachedFromWindow();
        NotificationCenter.getInstance(account).removeObserver(this, NotificationCenter.updateInterfaces);
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id != NotificationCenter.updateInterfaces || args.length == 0 || !(args[0] instanceof Integer)) {
            return;
        }
        int mask = (Integer) args[0];
        if ((mask & (MessagesController.UPDATE_MASK_AVATAR | MessagesController.UPDATE_MASK_CHAT_AVATAR)) != 0) {
            loadPhoto();
            invalidate();
        }
    }

    public void draw(Canvas canvas, int width, int height) {
        if (!settings.enabled || !hasPhoto || width <= 0 || height <= 0 || !InterfaceStyleController.applyChatHeader()) {
            return;
        }
        // Selection mode swaps in its own bar; fade with it instead of popping. The bar is created
        // invisible at full alpha, so its alpha only counts while it is actually visible.
        View actionMode = actionBar.getActionMode();
        float visible = actionMode != null && actionMode.getVisibility() == View.VISIBLE ? 1f - actionBar.getActionModeFactor() : 1f;
        if (visible <= 0f) {
            return;
        }
        int bw = imageReceiver.getBitmapWidth();
        int bh = imageReceiver.getBitmapHeight();
        if (bw <= 0 || bh <= 0) {
            return;
        }
        float scale = Math.max(width / (float) bw, height / (float) bh) * settings.zoom / 100f;
        float dw = bw * scale;
        float dh = bh * scale;
        // Offsets pan within the overflow only, so no edge of the photo ever shows.
        float slackX = (dw - width) / 2f;
        float slackY = (dh - height) / 2f;
        float x = -slackX - slackX * settings.offsetX / 100f;
        float y = -slackY - slackY * settings.offsetY / 100f;

        canvas.save();
        canvas.clipRect(0, 0, width, height);
        updateTint();
        imageReceiver.setImageCoords(x, y, dw, dh);
        imageReceiver.setAlpha(settings.opacity / 100f * visible);
        imageReceiver.draw(canvas);
        if (settings.gradient && settings.gradientStrength > 0) {
            updateGradient(width, height);
            gradientPaint.setAlpha((int) (255 * visible));
            canvas.drawRect(0, 0, width, height, gradientPaint);
        }
        canvas.restore();
    }

    private void updateTint() {
        int hue = settings.tintHue;
        int color = tintColor(hue, resourcesProvider);
        if (hue == filterHue && color == filterColor && settings.tintStrength == filterStrength) {
            return;
        }
        filterHue = hue;
        filterColor = color;
        filterStrength = settings.tintStrength;
        imageReceiver.setColorFilter(hue == HeaderBgSettings.TINT_AUTO || filterStrength == 0 ? null
                : new PorterDuffColorFilter(ColorUtils.setAlphaComponent(color, 255 * filterStrength / 100), PorterDuff.Mode.SRC_ATOP));
    }

    /** Resolved on every use, so the Theme and hue tints follow a theme switch. */
    public static int tintColor(int hue, Theme.ResourcesProvider resourcesProvider) {
        if (hue == HeaderBgSettings.TINT_THEME) {
            return Theme.getColor(Theme.key_featuredStickers_addButton, resourcesProvider);
        }
        return hue >= 0 ? Theme.getColor(Theme.keys_avatar_background[hue], resourcesProvider) : 0;
    }

    private void updateGradient(int width, int height) {
        int color = surfaceColor();
        boolean rtl = LocaleController.isRTL;
        if (width == shaderW && height == shaderH && settings.gradientFrom == shaderFrom && color == shaderColor
                && settings.gradientStrength == shaderStrength && rtl == shaderRtl) {
            return;
        }
        shaderW = width;
        shaderH = height;
        shaderFrom = settings.gradientFrom;
        shaderColor = color;
        shaderStrength = settings.gradientStrength;
        shaderRtl = rtl;
        float x0 = 0, y0 = 0, x1 = 0, y1 = 0;
        if (shaderFrom == HeaderBgSettings.FROM_TOP) {
            y1 = height;
        } else if (shaderFrom == HeaderBgSettings.FROM_BOTTOM) {
            y0 = height;
        } else if (rtl) {
            x0 = width;
        } else {
            x1 = width;
        }
        int start = ColorUtils.setAlphaComponent(color, 255 * shaderStrength / 100);
        gradientPaint.setShader(new LinearGradient(x0, y0, x1, y1, start, ColorUtils.setAlphaComponent(color, 0), Shader.TileMode.CLAMP));
    }

    // The colour the flat header is painted with, opaque, so the fade lands on it without a seam.
    private int surfaceColor() {
        int color = InterfaceStyleController.chatHeaderSurfaceColor(resourcesProvider);
        if (InterfaceStyleSolidHeader.chatHeader() && !actionBar.isActionModeShowed()) {
            color = InterfaceStyleSolidHeader.chatHeaderSurface(color);
        }
        return ColorUtils.setAlphaComponent(color, 255);
    }
}
