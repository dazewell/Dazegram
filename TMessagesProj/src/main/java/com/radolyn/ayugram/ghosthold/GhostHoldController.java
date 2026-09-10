package com.radolyn.ayugram.ghosthold;

import android.content.SharedPreferences;
import android.util.Log;

import androidx.annotation.Nullable;

import org.telegram.SQLite.SQLiteCursor;
import org.telegram.SQLite.SQLiteDatabase;
import org.telegram.SQLite.SQLiteException;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BuildConfig;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.SendMessageChatArguments;
import org.telegram.messenger.SendMessagesHelper;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.NativeByteBuffer;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.LaunchActivity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import tw.nekomimi.nekogram.NekoConfig;

/**
 * "Hold Messages" for Ghost Mode.
 *
 * <p>When Ghost Mode is active and the Hold Messages preference is on, a plain
 * text send is not transmitted. Instead it is persisted as a held row in
 * {@code scheduled_messages_v2} (send_state = 1, negative id, marked in
 * {@link TLRPC.Message#params}), so it renders in that chat's Scheduled list and
 * survives an app kill. Nothing about a held message lives only in memory -- the
 * database row is the sole source of truth for queue membership.
 *
 * <p>The queue drains ("flush") only when Ghost Mode turns off. Ghost is a
 * derived predicate over five independently-flippable toggles
 * ({@link NekoConfig#isGhostModeActive()}), so the flush is triggered from an
 * edge detector that watches the derived active/inactive transition rather than
 * any single toggle, plus a convergence check at process start.
 *
 * <p>Everything here is global (Ghost is a single unsuffixed preference), but the
 * sending side is per-account: local message ids collide across accounts, so
 * every held row is addressed by {@code (account, mid, dialogId)}, never id alone.
 */
public final class GhostHoldController {

    // Distinct fork sentinel for an undated held message. Kept away from
    // upstream's 0x7FFFFFFE "send when online" value (ChatMessageCell), which
    // carries a live upstream meaning we must not collide with.
    public static final int GHOST_HELD_DATE_SENTINEL = 0x7FFFFFFD;

    private static final String PARAM_MARKER = "ghost_hold";
    private static final String PARAM_VALUE = "1";
    // Records that the user disabled link preview for this send, so the flush
    // does not re-enable it (searchLinks defaults to true on the send funnel).
    private static final String PARAM_NO_WEBPAGE = "ghost_hold_no_webpage";
    // The send's repeat period and message effect are not carried on the stored
    // TLRPC.Message, so they ride in params (the marker vehicle) and are restored
    // onto the SendMessageParams on flush. Absent means the send had none.
    private static final String PARAM_REPEAT = "ghost_hold_repeat";
    private static final String PARAM_EFFECT = "ghost_hold_effect";

    private static final String PREFS_NAME = "ghosthold_state";
    private static final String KEY_LAST_GHOST_ACTIVE = "last_ghost_active";

    // Gap between successive sends on flush, so an entire backlog does not leave
    // in the same instant -- a simultaneous burst is itself a signal Ghost ended.
    private static final long FLUSH_STAGGER_MS = 1500;

    // NAX_SMOKE_ghost-hold: temporary smoke diagnostics tag. Removed in a later
    // commit once the smoke build confirms reachability. Log.e/.i/.w only, since
    // proguard strips Log.v/.d from the release build the smoke APK installs.
    private static final String SMOKE = "NAX_SMOKE_ghost-hold";

    private static volatile boolean flushInProgress;

    private GhostHoldController() {}

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE);
    }

    // ---- predicates ----

    public static boolean isHoldActive() {
        return NekoConfig.isGhostModeActive() && NekoConfig.holdMessagesWhileGhost.Bool();
    }

    public static boolean isHeld(@Nullable MessageObject mo) {
        return mo != null && isHeldMessage(mo.messageOwner);
    }

    public static boolean isHeldMessage(@Nullable TLRPC.Message m) {
        return m != null && m.params != null && PARAM_VALUE.equals(m.params.get(PARAM_MARKER));
    }

    // ---- the send-time hook ----

    /**
     * Consulted once, at the single {@code sendMessage(SendMessageParams)} funnel.
     * Reads Ghost state and the hold preference as one act and, when a plain text
     * message should be held, persists it and returns true so the caller returns
     * before any in-flight send state is created.
     */
    public static boolean maybeHold(int account, long peer, SendMessagesHelper.SendMessageParams params) {
        if (!isHoldableTextSend(account, peer, params)) {
            return false;
        }
        persistHeld(account, peer, params);
        return true;
    }

    /**
     * The hold allowlist: true only for a plain text send whose every property we
     * can persist on the stored {@link TLRPC.Message} (or carry in its params) and
     * restore faithfully on flush.
     *
     * <p>This is deliberately an allowlist, not a denylist of known media fields.
     * A denylist fails unsafe -- a future SendMessageParams field nobody here has
     * heard of would be held and silently degraded on flush. An allowlist fails
     * safe: an unrecognised send simply is not held, falls through to the normal
     * send path (where the send-exposure warning tells the user their status was
     * exposed), and is never corrupted. When teaching hold a new field, persist AND
     * restore it first, then relax the matching guard here -- never the reverse.
     *
     * <p>Held faithfully: message text, entities, the full reply header
     * (reply_to_msg_id, top/forum, quote + quote entities), invert_media, the
     * silent flag, a user-picked schedule date, scheduleRepeatPeriod, effect_id,
     * and link-preview suppression (searchLinks). Everything else excludes the send.
     */
    private static boolean isHoldableTextSend(int account, long peer, @Nullable SendMessagesHelper.SendMessageParams p) {
        if (p == null || !isHoldActive()) {
            return false;
        }
        // A flush re-drive carries retryMessageObject; it must never be re-held.
        if (p.retryMessageObject != null) {
            return false;
        }
        // Secret chats have their own send machinery and lifetime; leave them alone.
        if (DialogObject.isEncryptedDialog(peer)) {
            return false;
        }
        // Must be a text send: text present, no media of any kind.
        if (p.message == null) {
            return false;
        }
        if (p.location != null || p.photo != null || p.videoEditedInfo != null
                || p.document != null || p.game != null || p.poll != null
                || p.pollSendParams != null || p.todo != null || p.invoice != null
                || p.mediaWebPage != null || p.cover != null || p.user != null
                || p.richMessage != null || p.sendingStory != null) {
            return false;
        }
        // The chat-arguments bundle is ALWAYS attached on a composer send:
        // ChatActivity.getMessageChatSendParams() builds a fresh non-null object
        // even for an ordinary chat, so `sendMessageChatArguments != null` carries
        // no information and would refuse every normal message. Test its fields
        // instead. A welcome-message send redirects the peer, and a quick-reply
        // (business) send carries its shortcut here -- the funnel reads
        // quickReplyShortcut from this bundle when p.quick_reply_shortcut is unset
        // and never writes it back, so the direct p.quick_reply_shortcut test below
        // does not see it. Neither is persisted, so exclude both; an empty bundle
        // (the normal case) is holdable.
        SendMessageChatArguments chatArgs = p.sendMessageChatArguments;
        if (chatArgs != null && (chatArgs.welcomeMessageChatId != 0
                || chatArgs.quickReplyShortcut != null
                || chatArgs.quickReplyShortcutId != 0)) {
            return false;
        }
        // Metadata we do not persist and restore -> refuse rather than degrade:
        //  reply markup (an inline keyboard attached to the send),
        //  a story-reply target,
        //  a poll-vote or todo-task reply quote (replyQuote.poll / replyQuote.todo):
        //    the funnel writes reply_to.poll_option / todo_item_id from these
        //    (SendMessagesHelper ~:5065-5068), but persistHeld only carries a plain
        //    text quote, so a held poll-vote/todo reply would flush as an ordinary
        //    reply -- changing what the message *does*, not just how it looks. A
        //    plain text-quote reply IS persisted, so it stays holdable,
        //  a quick-reply shortcut (business) set directly on the params,
        //  a monoforum destination peer,
        //  suggestion params (suggested posts),
        //  a non-zero dice stake,
        //  a manually-resolved link preview (the auto preview is regenerated on
        //    flush via searchLinks; a user-edited webPage is not, so refuse it),
        //  a per-message self-destruct timer,
        //  (an ephemeral receiver is refused in its own block below, since the
        //    funnel derives it from more than the explicit field),
        //  a bare dice-emoji message (text is in MessagesController.diceEmojies): the
        //    funnel converts such a send into a dice *media* message when
        //    canSendGames is true (SendMessagesHelper ~:4664). Our hook runs before
        //    that conversion, so it still looks like plain text here, and of()
        //    restores canSendGames = true on flush -- so a held dice emoji becomes
        //    media then, out of v1 scope and arriving by a path nobody chose. Keyed
        //    on the funnel's own trigger (the emoji text), not on canSendGames:
        //    canSendGames gates the conversion but is also true for every ordinary
        //    send, so testing !canSendGames would refuse the non-converting case and
        //    admit the converting one,
        //  an explicit pangu override (canUsePangu != null): of() restores null (the
        //    config default), so a held text with pangu forced on/off would be spaced
        //    differently on flush. A normal send leaves canUsePangu == null, so the
        //    test is not vacuous; null means "apply the pangu setting in force when
        //    this sends", which for a deferred hold is flush time -- the field
        //    behaving as specified, so it is documented rather than excluded.
        if (p.replyMarkup != null
                || p.replyToStoryItem != null
                || (p.replyQuote != null && (p.replyQuote.poll || p.replyQuote.todo))
                || p.quick_reply_shortcut != null || p.quick_reply_shortcut_id != 0
                || p.monoForumPeer != 0
                || p.suggestionParams != null
                || p.dice_stake != 0
                || p.webPage != null
                || p.ttl != 0
                || isDiceEmojiText(account, p.message)
                || p.canUsePangu != null) {
            return false;
        }
        // Ephemeral receiver: the funnel picks the ephemeral target from three
        // sources (SendMessagesHelper ~:4470-4476) and our hook runs before that
        // pick. We persist none of them and of() cannot rebuild an ephemeral send, so
        // a held ephemeral message would flush as an ordinary, non-vanishing one -- a
        // privacy degradation, not a cosmetic one. The explicit ephemeralReceiverBotId
        // field is only one source: also refuse a reply to an ephemeral message and
        // an ephemeral slash command. The command case is keyed on the funnel's own
        // getEphemeralCommandBotId -- a side-effect-free lookup that returns 0 for any
        // text not starting with '/' or a non-chat peer -- so the two cannot disagree.
        if (p.ephemeralReceiverBotId != 0
                || (p.replyToMsg != null && p.replyToMsg.isEphemeral())
                || org.telegram.messenger.utils.EphemeralMessagesHelper.getInstance(account).getEphemeralCommandBotId(p.message, peer) != 0) {
            return false;
        }
        // Send-as identity: a channel/megagroup post can resolve a non-self sender
        // (a linked channel, an anonymous admin, a broadcast identity). persistHeld
        // hardcodes from_id = self, so holding such a send would flush it under the
        // wrong identity. Mirror the funnel's resolution and refuse a non-self one.
        if (resolvesNonSelfSendAs(account, peer)) {
            return false;
        }
        // Paid direct messages: holding one defers a payment to a later moment the
        // user did not choose (a flush triggered by toggling Ghost off), possibly at
        // a price that changed while it sat, and pops the Stars paywall then. The
        // paywall (AlertsCreator.ensurePaidMessageConfirmation / showPayForMessageAlert)
        // exposes no cancel signal, so a deferred flush item could not be released on
        // a dismissed dialog either. Refuse to hold: send now, at the price the user
        // saw, and let the send-exposure warning tell them their status was exposed.
        // The same check runs again at flush time (dispatchFreshItem) so that a dialog
        // which becomes paid *after* it was held is not auto-re-driven into a paywall:
        // an automated flush never opens a paywall.
        if (isPaidDialog(account, peer)) {
            return false;
        }
        return true;
    }

    /**
     * True if sending to {@code peer} would require a Stars payment. Mirrors the
     * funnel's own paid check ({@link SendMessagesHelper} ~:4458-4462) so the hold
     * predicate, the flush-time re-check, and the funnel cannot disagree about what
     * "paid" means.
     */
    private static boolean isPaidDialog(int account, long peer) {
        MessagesController controller = MessagesController.getInstance(account);
        long payStars = controller.getSendPaidMessagesStars(peer);
        if (payStars <= 0) {
            payStars = DialogObject.getMessagesStarsPrice(controller.isUserContactBlocked(peer));
        }
        return payStars > 0;
    }

    /**
     * True if {@code message} is a bare dice emoji that the funnel would turn into
     * a dice media message ({@link SendMessagesHelper} ~:4664). Keyed on the same
     * {@link MessagesController#diceEmojies} set the funnel branches on, so the
     * hold predicate and the funnel cannot disagree about what a dice send is.
     * Null-guards the set (the funnel reaches ~:4664 only under conditions that
     * imply it is loaded; our hook runs on every send, so guard it here).
     */
    private static boolean isDiceEmojiText(int account, @Nullable String message) {
        if (message == null) {
            return false;
        }
        java.util.Set<String> dice = MessagesController.getInstance(account).diceEmojies;
        return dice != null && dice.contains(message.replace("\ufe0f", ""));
    }

    /**
     * True if a send to {@code peer} would resolve a send-as sender other than this
     * account's own user. Mirrors the funnel, which only applies send-as for a
     * channel input peer ({@link SendMessagesHelper} ~:4518); a user DM or a basic
     * group never does.
     */
    private static boolean resolvesNonSelfSendAs(int account, long peer) {
        if (peer >= 0) {
            return false;
        }
        MessagesController controller = MessagesController.getInstance(account);
        TLRPC.Chat chat = controller.getChat(-peer);
        if (chat == null || !ChatObject.isChannel(chat)) {
            return false;
        }
        long selfId = UserConfig.getInstance(account).getClientUserId();
        return ChatObject.getSendAsPeerId(chat, controller.getChatFull(-peer), true) != selfId;
    }

    /**
     * Smoke tripwire: called from the send funnel only after {@link #maybeHold}
     * returned false. If a send the allowlist would have held is proceeding to the
     * network while hold is active, that is the leak the feature exists to prevent
     * -- log it loudly. Excluded variants (media, reply markup, send-as, ...)
     * proceed by design and must not trip this.
     */
    public static void smokeProceedTripwire(int account, long peer, SendMessagesHelper.SendMessageParams params) {
        if (isHoldableTextSend(account, peer, params)) {
            Log.e(SMOKE, "forbidden: holdable plain text send proceeded to the network while ghost+hold active dialog=" + peer);
        }
    }

    private static void persistHeld(int account, long peer, SendMessagesHelper.SendMessageParams params) {
        final MessagesController controller = MessagesController.getInstance(account);
        final UserConfig userConfig = UserConfig.getInstance(account);

        TLRPC.TL_message msg = new TLRPC.TL_message();
        msg.message = params.message;
        if (params.entities != null && !params.entities.isEmpty()) {
            msg.entities = params.entities;
            msg.flags |= TLRPC.MESSAGE_FLAG_HAS_ENTITIES;
        }
        msg.media = new TLRPC.TL_messageMediaEmpty();
        msg.flags |= TLRPC.MESSAGE_FLAG_HAS_MEDIA;
        msg.local_id = msg.id = userConfig.getNewMessageId();
        userConfig.saveConfig(false);
        msg.out = true;
        msg.from_id = new TLRPC.TL_peerUser();
        msg.from_id.user_id = userConfig.getClientUserId();
        msg.flags |= TLRPC.MESSAGE_FLAG_HAS_FROM_ID;
        msg.peer_id = controller.getPeer(peer);
        msg.dialog_id = peer;
        msg.random_id = SendMessagesHelper.getInstance(account).getNextRandomId();
        msg.silent = !params.notify || MessagesController.getNotificationsSettings(account).getBoolean("silent_" + peer, false);
        msg.invert_media = params.invert_media;
        // A user-picked schedule date is recorded and shown but has no enforcement
        // power while held; an undated hold sorts under its own sentinel header.
        msg.date = params.scheduleDate != 0 ? params.scheduleDate : GHOST_HELD_DATE_SENTINEL;
        msg.send_state = MessageObject.MESSAGE_SEND_STATE_SENDING;
        msg.unread = true;
        msg.attachPath = "";

        // Reconstruct the full reply header, not just reply_to_msg_id: the flush
        // re-drive reuses this stored message verbatim (of(MessageObject) passes a
        // null replyToMsg, so the funnel keeps whatever reply_to is on the row), so
        // a topic/quote reply that isn't captured here is silently dropped on send.
        // Mirrors the funnel's own header construction (SendMessagesHelper ~:5031).
        MessageObject replyToMsg = params.replyToMsg;
        MessageObject replyToTopMsg = params.replyToTopMsg;
        ChatActivity.ReplyQuote replyQuote = params.replyQuote;
        if (replyQuote != null && replyQuote.message != null && replyToMsg != null) {
            replyToMsg = replyQuote.message;
        }
        if (replyToMsg != null && (replyToTopMsg == null || replyToMsg != replyToTopMsg || replyToTopMsg.getId() != 1)) {
            msg.reply_to = new TLRPC.TL_messageReplyHeader();
            msg.flags |= TLRPC.MESSAGE_FLAG_REPLY;
            msg.reply_to.flags |= 16;
            msg.reply_to.reply_to_msg_id = replyToMsg.getId();
            if (replyToTopMsg != null && replyToTopMsg != replyToMsg && replyToTopMsg.getId() != 1) {
                msg.reply_to.reply_to_top_id = replyToTopMsg.getId();
                msg.reply_to.flags |= 2;
                if (replyToTopMsg.isTopicMainMessage) {
                    msg.reply_to.forum_topic = true;
                    msg.reply_to.flags |= 8;
                }
            } else if (replyToMsg.isTopicMainMessage) {
                msg.reply_to.forum_topic = true;
                msg.reply_to.flags |= 8;
            }
            if (replyQuote != null && !replyQuote.todo && !replyQuote.poll) {
                msg.reply_to.quote_text = replyQuote.getText();
                if (!android.text.TextUtils.isEmpty(msg.reply_to.quote_text)) {
                    msg.reply_to.quote = true;
                    msg.reply_to.flags |= 64;
                    msg.reply_to.flags |= 1024;
                    msg.reply_to.quote_offset = replyQuote.start;
                    ArrayList<TLRPC.MessageEntity> quoteEntities = replyQuote.getEntities();
                    if (quoteEntities != null && !quoteEntities.isEmpty()) {
                        msg.reply_to.quote_entities = new ArrayList<>(quoteEntities);
                        msg.reply_to.flags |= 128;
                    }
                }
            }
        }

        HashMap<String, String> stored = params.params != null ? new HashMap<>(params.params) : new HashMap<>();
        stored.put(PARAM_MARKER, PARAM_VALUE);
        if (!params.searchLinks) {
            stored.put(PARAM_NO_WEBPAGE, PARAM_VALUE);
        }
        // scheduleRepeatPeriod and effect_id live only on SendMessageParams, not on
        // the stored TLRPC.Message, so carry them here and restore them on flush.
        if (params.scheduleRepeatPeriod != 0) {
            stored.put(PARAM_REPEAT, Integer.toString(params.scheduleRepeatPeriod));
        }
        if (params.effect_id != 0) {
            stored.put(PARAM_EFFECT, Long.toString(params.effect_id));
        }
        msg.params = stored;

        final TLRPC.Message stableMsg = msg;
        ArrayList<TLRPC.Message> arr = new ArrayList<>();
        arr.add(stableMsg);
        MessagesStorage storage = MessagesStorage.getInstance(account);
        // Critical: the durable row must exist before the user is told "Held".
        // putMessages(useQueue=true) enqueues the write on the storage queue; the
        // interface update, the Scheduled-list insert and the bulletin are chained
        // on that same serial queue so they run only after the write has landed.
        // This orders the signal after durability without blocking the UI thread
        // on a synchronous DB write (which would risk an ANR).
        storage.putMessages(arr, false, true, false, 0, 1, 0);
        storage.getStorageQueue().postRunnable(() -> AndroidUtilities.runOnUIThread(() -> {
            MessageObject mo = new MessageObject(account, stableMsg, true, true);
            mo.scheduled = true;
            ArrayList<MessageObject> objArr = new ArrayList<>();
            objArr.add(mo);
            controller.updateInterfaceWithMessages(peer, objArr, 1);
            NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.dialogsNeedReload);
            Log.i(SMOKE, "expected: diverted send to hold and added to scheduled list account=" + account + " dialog=" + peer);
            showDivertBulletin();
        }));
    }

    private static void showDivertBulletin() {
        BaseFragment fragment = LaunchActivity.getLastFragment();
        if (fragment == null || fragment.getParentActivity() == null) {
            return;
        }
        BulletinFactory.of(fragment).createSimpleBulletin(R.raw.chats_infotip, LocaleController.getString(R.string.GhostHoldDiverted)).show();
    }

    // ---- ghost-off edge detection ----

    /**
     * Invoked wherever Ghost state can change (the aggregate toggle and each of
     * the five individual toggles). Compares the persisted previous active state
     * against the current derived value and, on a true -> false edge, requests a
     * flush. Idempotent: a repeated call with no transition does nothing.
     */
    public static synchronized void onGhostStateMaybeChanged() {
        boolean nowActive = NekoConfig.isGhostModeActive();
        boolean wasActive = prefs().getBoolean(KEY_LAST_GHOST_ACTIVE, false);
        prefs().edit().putBoolean(KEY_LAST_GHOST_ACTIVE, nowActive).apply();
        if (wasActive && !nowActive) {
            Log.i(SMOKE, "flush trigger: ghost-off edge detected, requesting flush");
            AndroidUtilities.runOnUIThread(GhostHoldController::promptFlush);
        }
    }

    /**
     * Convergence check at process start. If Ghost ended (or was never on) while a
     * backlog remained -- e.g. the app was killed after Ghost went off but before the
     * flush confirmation was accepted -- re-offer the same "Send held messages?"
     * confirmation so the queue is not stranded across launches. It prompts, never
     * drains silently: the confirmation is a UX guarantee dazewell approved, so a
     * flush with no foreground screen is deferred to a later launch that has one, not
     * skipped. This can re-prompt a flush that was confirmed then interrupted, because
     * we deliberately do not persist a "confirmed" marker to tell that apart from
     * "never confirmed" -- asking twice is cheap, sending unasked is not. Also seeds
     * the edge baseline.
     */
    public static void checkOnProcessStart() {
        boolean nowActive = NekoConfig.isGhostModeActive();
        Log.i(SMOKE, "begin: process start build=" + BuildConfig.BUILD_VERSION_STRING + " app=" + BuildConfig.APPLICATION_ID + " ghostActive=" + nowActive + " holdEnabled=" + NekoConfig.holdMessagesWhileGhost.Bool());
        prefs().edit().putBoolean(KEY_LAST_GHOST_ACTIVE, nowActive).apply();
        if (!nowActive) {
            Log.i(SMOKE, "flush trigger: process-start convergence, ghost inactive");
            AndroidUtilities.runOnUIThread(GhostHoldController::promptFlush);
        }
    }

    // ---- flush ----

    private static void promptFlush() {
        if (flushInProgress) {
            return;
        }
        // Claim the flush before the async collect, not after: collectHeld hops to
        // the storage queue and back, and two ghost-off edges in that window would
        // otherwise both pass this guard, collect the same rows and open two
        // confirmations -> duplicate sends. All flushInProgress access is on the UI
        // thread, so a plain boolean serialises correctly. Cleared on empty, cancel,
        // or completion (performFlush).
        flushInProgress = true;
        collectHeld(items -> {
            if (items.isEmpty()) {
                flushInProgress = false;
                return;
            }
            int pending = countPending(items);
            BaseFragment fragment = LaunchActivity.getLastFragment();
            if (pending == 0) {
                // Only stale confirmed-sent orphans remain; clear them silently,
                // never re-send, and show nothing.
                performFlush(items);
                return;
            }
            if (fragment == null || fragment.getParentActivity() == null) {
                // No foreground screen to confirm on (e.g. process-start convergence
                // before any UI is up). Defer, never drain: a backlog must not leave
                // without the user seeing the "Send held messages?" dialog. Release
                // the claim and leave every row held; the next ghost-off edge or
                // process start re-offers the same confirmation. Deferred, not skipped
                // -- a user who keeps dismissing keeps deferring, which is their choice
                // (nothing is lost, the rows stay visible in Scheduled).
                flushInProgress = false;
                return;
            }

            int chats = countDistinctChats(items);
            String body;
            if (pending == 1) {
                body = LocaleController.getString(R.string.GhostHoldFlushConfirmOne);
            } else {
                body = LocaleController.formatString(R.string.GhostHoldFlushConfirmMany, pending, chats);
            }
            AlertDialog.Builder builder = new AlertDialog.Builder(fragment.getParentActivity());
            builder.setTitle(LocaleController.getString(R.string.GhostHoldFlushConfirmTitle));
            builder.setMessage(body);
            builder.setPositiveButton(LocaleController.getString(R.string.MessageScheduleSend), (dialog, which) -> performFlush(items));
            builder.setNegativeButton(LocaleController.getString(R.string.Cancel), (dialog, which) -> {
                flushInProgress = false;
                restoreGhost();
            });
            builder.setOnCancelListener(dialog -> {
                flushInProgress = false;
                restoreGhost();
            });
            builder.show();
        });
    }

    private static int countPending(ArrayList<HeldItem> items) {
        int pending = 0;
        for (HeldItem item : items) {
            if (!item.alreadySent) {
                pending++;
            }
        }
        return pending;
    }

    private static void restoreGhost() {
        NekoConfig.setGhostMode(true);
        // Re-seed the baseline so the next genuine ghost-off edge still fires.
        prefs().edit().putBoolean(KEY_LAST_GHOST_ACTIVE, NekoConfig.isGhostModeActive()).apply();
        NotificationCenter.getInstance(UserConfig.selectedAccount).postNotificationName(NotificationCenter.mainUserInfoChanged);
    }

    private static void performFlush(ArrayList<HeldItem> items) {
        if (items.isEmpty()) {
            flushInProgress = false;
            return;
        }
        flushInProgress = true;
        // Every item is processed (stale orphans get cleaned), but only genuinely
        // pending sends are reported to the user, so the counts stay honest.
        final int all = items.size();
        final int pending = countPending(items);
        // Completion is observed, not timed: each item, whatever its fate (sent and
        // cleaned, re-held because Ghost came back on, discarded because the user
        // deleted it mid-stagger, or a stale orphan cleared) decrements this counter,
        // and only the item that brings it to zero releases the flush guard and
        // reports. An elapsed-time completion could fire while a paid-message dialog
        // still waits on the user, and clearing the guard early would let a second
        // ghost-off start a concurrent flush over this one.
        final AtomicInteger remaining = new AtomicInteger(all);
        for (int i = 0; i < all; i++) {
            HeldItem item = items.get(i);
            AndroidUtilities.runOnUIThread(() -> flushItem(item, remaining, pending), i * FLUSH_STAGGER_MS);
        }
    }

    private static void onItemTerminal(AtomicInteger remaining, int pending) {
        if (remaining.decrementAndGet() > 0) {
            return;
        }
        // Runs on the UI thread (every caller path posts here), so this write to
        // flushInProgress is serialised with promptFlush's claim.
        flushInProgress = false;
        if (pending <= 0) {
            return;
        }
        countHeld(stillHeld -> {
            BaseFragment f = LaunchActivity.getLastFragment();
            if (f == null || f.getParentActivity() == null) {
                return;
            }
            CharSequence text;
            if (stillHeld <= 0) {
                text = LocaleController.formatPluralString("GhostHoldFlushed", pending);
            } else {
                int sent = Math.max(0, pending - stillHeld);
                text = LocaleController.formatString(R.string.GhostHoldFlushedPartial, sent, pending);
            }
            Log.i(SMOKE, "end: flush complete pending=" + pending + " stillHeld=" + stillHeld);
            BulletinFactory.of(f).createSimpleBulletin(R.raw.chats_infotip, text).show();
        });
    }

    private static void flushItem(HeldItem item, AtomicInteger remaining, int pending) {
        // Re-check Ghost per item on the privacy invariant, which is about Ghost
        // alone, not the hold preference: if Ghost came back on mid-flush this
        // message must stay held even when Hold Messages was turned off in the same
        // window (isHoldActive() would be false then and wrongly let it leak).
        if (NekoConfig.isGhostModeActive()) {
            onItemTerminal(remaining, pending);
            return;
        }
        final MessagesStorage storage = MessagesStorage.getInstance(item.account);
        // Never act on the snapshot captured at collect time. During the stagger the
        // user can delete or edit a held row; re-read the current row immediately
        // before dispatch off the same serial storage queue, then decide on the UI
        // thread. A delete makes the re-read return null (discard, touch nothing); an
        // edit is honoured because the re-driven copy is the fresh one, not the stale.
        storage.getStorageQueue().postRunnable(() -> {
            final HeldItem fresh = reReadHeldRow(item, storage);
            AndroidUtilities.runOnUIThread(() -> dispatchFreshItem(item, fresh, remaining, pending));
        });
    }

    private static void dispatchFreshItem(HeldItem item, @Nullable HeldItem fresh, AtomicInteger remaining, int pending) {
        final int account = item.account;
        final long dialogId = item.dialogId;
        final int mid = item.mid;
        // The row vanished during the stagger (user deleted it, or it was already
        // cleaned): nothing to send, nothing to remove.
        if (fresh == null) {
            onItemTerminal(remaining, pending);
            return;
        }
        // Ghost flipped back on during the re-read hop -> keep it held.
        if (NekoConfig.isGhostModeActive()) {
            onItemTerminal(remaining, pending);
            return;
        }
        // Correlation gate: a held row whose message has already been dispatched and
        // confirmed by the server (its random_id now maps to a positive server id)
        // must never be re-driven -- otherwise a paid-DM send would re-open the
        // paywall and risk a second charge, and a normal send would duplicate. Such
        // a stale orphan is only removed here, never re-sent.
        if (fresh.alreadySent) {
            cleanupAfterHandoff(account, mid, dialogId, true, () -> onItemTerminal(remaining, pending));
            return;
        }

        final TLRPC.Message m = fresh.message;
        int now = ConnectionsManager.getInstance(account).getCurrentTime();
        boolean future = m.date != GHOST_HELD_DATE_SENTINEL && m.date > now;
        int scheduleDate = future ? m.date : 0;

        MessageObject mo = new MessageObject(account, m, false, true);
        mo.scheduled = future;
        // of(MessageObject) rebuilds the send from the stored message verbatim --
        // text, entities, the full reply header, silent, invert_media and params
        // (including our marker) all ride along, so nothing is hand-reconstructed
        // and lost. It forces searchLinks/scheduleDate on, so override both below.
        SendMessagesHelper.SendMessageParams p = SendMessagesHelper.SendMessageParams.of(mo);
        p.scheduleDate = scheduleDate;
        p.searchLinks = m.params == null || !PARAM_VALUE.equals(m.params.get(PARAM_NO_WEBPAGE));
        if (m.params != null) {
            String repeat = m.params.get(PARAM_REPEAT);
            if (repeat != null) {
                try {
                    p.scheduleRepeatPeriod = Integer.parseInt(repeat);
                } catch (NumberFormatException ignore) {
                }
            }
            String effect = m.params.get(PARAM_EFFECT);
            if (effect != null) {
                try {
                    p.effect_id = Long.parseLong(effect);
                } catch (NumberFormatException ignore) {
                }
            }
        }

        // Re-drive in place. The funnel keys its destination table off scheduleDate
        // alone (SendMessagesHelper:5306-5320), not the row's current table, so a
        // send-now re-drive (scheduleDate == 0) makes the funnel write the row into
        // messages_v2 as part of sending, while a future-dated one rewrites the same
        // scheduled_messages_v2 row in place.
        // An automated flush never opens a paywall. The dialog was not paid when this
        // row was held (isHoldableTextSend excludes paid dialogs), but it can become
        // paid before the flush. Re-driving it now would make the funnel open the Stars
        // paywall; if Ghost is re-enabled while that paywall is open and the user then
        // accepts, the funnel's deferred callback -- which we do not own -- would
        // transmit under Ghost, breaking the core no-leak invariant. So re-run the same
        // paid check here: if the dialog is now paid, leave the row held rather than
        // re-drive it. It stays visible in Scheduled, the flush bulletin reports it as
        // not sent, and the user can send it by hand at the price they are shown. This
        // is the hold-time paid exclusion applied again at flush time.
        if (isPaidDialog(account, dialogId)) {
            onItemTerminal(remaining, pending);
            return;
        }
        SendMessagesHelper.getInstance(account).sendMessage(p);
        // Remove the held row only once the send has demonstrably been handed off --
        // never as a consequence of sendMessage() merely returning. cleanupAfterHandoff
        // proves handoff by the destination row's existence on the same serial queue,
        // and signals this item terminal only after it has run.
        cleanupAfterHandoff(account, mid, dialogId, fresh.inMainTable, () -> onItemTerminal(remaining, pending));
    }

    /**
     * Re-reads the current held row for {@code (mid, dialogId)} from the table it
     * was collected in, immediately before dispatch. Returns a fresh {@link HeldItem}
     * (with a re-evaluated {@code alreadySent}) or null if the row is gone or no
     * longer held. The {@code send_state = 1} filter keeps this to rows still in the
     * held/unsent state, so a row already moved on is treated as vanished.
     */
    @Nullable
    private static HeldItem reReadHeldRow(HeldItem item, MessagesStorage storage) {
        SQLiteDatabase db = storage.getDatabase();
        long selfId = UserConfig.getInstance(item.account).clientUserId;
        String table = item.inMainTable ? "messages_v2" : "scheduled_messages_v2";
        SQLiteCursor cursor = null;
        try {
            cursor = db.queryFinalized("SELECT data, mid, uid, date FROM " + table + " WHERE mid = " + item.mid + " AND uid = " + item.dialogId + " AND send_state = 1");
            if (cursor.next()) {
                return readHeldRow(item.account, cursor, selfId, item.inMainTable, db);
            }
        } catch (Exception e) {
            FileLog.e(e);
        } finally {
            if (cursor != null) {
                cursor.dispose();
            }
        }
        return null;
    }

    /**
     * Removes the held {@code scheduled_messages_v2} row iff the send provably
     * reached its destination table. Runs on the storage queue, enqueued after the
     * funnel's own {@code putMessages(useQueue=true)} write, so by the time it runs
     * a completed send has already written {@code messages_v2} (send-now) and an
     * early-return (paid confirmation, {@code sendToUser == null}) has written
     * nothing. If the destination row exists we delete the held row; otherwise we
     * leave it in the Scheduled list, still held, to be re-driven on the next flush.
     *
     * <p>{@code handoffProven} skips the existence probe: it is set when the row was
     * collected from {@code messages_v2} (already in the destination table) or when
     * the row is a confirmed-sent stale orphan being cleared. Future-dated re-drives
     * rewrite the scheduled row in place (no messages_v2 row), so the probe finds
     * nothing and does nothing -- the funnel's own scheduled confirmation deletes
     * that negative-id row. A kill between the funnel write and this delete leaves
     * the row in both tables; the both-tables convergence dedups on the next flush,
     * failing toward "sent twice", never "lost".
     */
    private static void cleanupAfterHandoff(int account, int mid, long dialogId, boolean handoffProven, @Nullable Runnable onDone) {
        MessagesStorage storage = MessagesStorage.getInstance(account);
        storage.getStorageQueue().postRunnable(() -> {
            try {
                SQLiteDatabase db = storage.getDatabase();
                boolean handedOff = handoffProven;
                if (!handedOff) {
                    SQLiteCursor probe = db.queryFinalized("SELECT 1 FROM messages_v2 WHERE mid = " + mid + " AND uid = " + dialogId + " LIMIT 1");
                    handedOff = probe.next();
                    probe.dispose();
                }
                if (handedOff) {
                    db.executeFast("DELETE FROM scheduled_messages_v2 WHERE mid = " + mid + " AND uid = " + dialogId).stepThis().dispose();
                    // Recompute the dialog's remaining scheduled count (held + server-scheduled)
                    // so scheduledMessagesCount isn't zeroed while other rows still exist.
                    int count = 0;
                    SQLiteCursor cursor = db.queryFinalized("SELECT COUNT(mid) FROM scheduled_messages_v2 WHERE uid = " + dialogId);
                    if (cursor.next()) {
                        count = cursor.intValue(0);
                    }
                    cursor.dispose();
                    final int finalCount = count;
                    AndroidUtilities.runOnUIThread(() ->
                            NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.scheduledMessagesUpdated, dialogId, finalCount, true));
                }
            } catch (Exception e) {
                FileLog.e(e);
            } finally {
                // Signal the item terminal only after this cleanup has run, whatever
                // its outcome, and always on the UI thread (flushInProgress lives
                // there). This is what makes flush completion observed, not timed.
                if (onDone != null) {
                    AndroidUtilities.runOnUIThread(onDone);
                }
            }
        });
    }

    // ---- held-queue reads ----

    /**
     * Async count for the settings screen; result delivered on the UI thread.
     * Stale confirmed-sent orphans awaiting cleanup are not counted as held.
     */
    public static void countHeld(Utilities.Callback<Integer> onDone) {
        collectHeld(items -> {
            int held = 0;
            for (HeldItem item : items) {
                if (!item.alreadySent) {
                    held++;
                }
            }
            onDone.run(held);
        });
    }

    private static void collectHeld(Utilities.Callback<ArrayList<HeldItem>> onDone) {
        ArrayList<Integer> accounts = new ArrayList<>();
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            if (UserConfig.getInstance(a).isClientActivated()) {
                accounts.add(a);
            }
        }
        if (accounts.isEmpty()) {
            AndroidUtilities.runOnUIThread(() -> onDone.run(new ArrayList<>()));
            return;
        }
        final List<HeldItem> result = Collections.synchronizedList(new ArrayList<>());
        final AtomicInteger remaining = new AtomicInteger(accounts.size());
        for (int account : accounts) {
            MessagesStorage storage = MessagesStorage.getInstance(account);
            storage.getStorageQueue().postRunnable(() -> {
                result.addAll(queryHeld(account, storage));
                if (remaining.decrementAndGet() == 0) {
                    AndroidUtilities.runOnUIThread(() -> onDone.run(new ArrayList<>(result)));
                }
            });
        }
    }

    private static ArrayList<HeldItem> queryHeld(int account, MessagesStorage storage) {
        // Keyed by local mid: a message killed mid-flush can sit in both tables at
        // once (the funnel wrote messages_v2, the scheduled orphan was never
        // deleted). Prefer the messages_v2 copy -- it is already in the destination
        // table, so its re-drive is a plain retry and cleanupAfterHandoff removes the
        // scheduled leftover by mid. Local mids are unique per account across both
        // tables, so the mid is a safe dedup key.
        java.util.LinkedHashMap<Integer, HeldItem> byMid = new java.util.LinkedHashMap<>();
        SQLiteDatabase db = storage.getDatabase();
        long selfId = UserConfig.getInstance(account).clientUserId;

        // Loop A: main-table held rows -- a send-now re-drive killed before its
        // server confirmation. Stock retry refuses these (the marker guard in
        // retrySendMessage), so the flush owns their rescue.
        SQLiteCursor cursor = null;
        try {
            cursor = db.queryFinalized("SELECT data, mid, uid, date FROM messages_v2 WHERE mid < 0 AND send_state = 1");
            while (cursor.next()) {
                HeldItem item = readHeldRow(account, cursor, selfId, true, db);
                if (item != null) {
                    byMid.put(item.mid, item);
                }
            }
        } catch (Exception e) {
            FileLog.e(e);
        } finally {
            if (cursor != null) {
                cursor.dispose();
            }
        }

        // Loop B: scheduled-table held rows -- the normal held queue, plus any
        // scheduled orphan left behind by a completed send-now re-drive.
        cursor = null;
        try {
            cursor = db.queryFinalized("SELECT data, mid, uid, date FROM scheduled_messages_v2 WHERE mid < 0 AND send_state = 1");
            while (cursor.next()) {
                int mid = cursor.intValue(1);
                if (byMid.containsKey(mid)) {
                    continue;
                }
                HeldItem item = readHeldRow(account, cursor, selfId, false, db);
                if (item != null) {
                    byMid.put(mid, item);
                }
            }
        } catch (Exception e) {
            FileLog.e(e);
        } finally {
            if (cursor != null) {
                cursor.dispose();
            }
        }
        return new ArrayList<>(byMid.values());
    }

    @Nullable
    private static HeldItem readHeldRow(int account, SQLiteCursor cursor, long selfId, boolean inMainTable, SQLiteDatabase db) throws SQLiteException {
        NativeByteBuffer data = cursor.byteBufferValue(0);
        if (data == null) {
            return null;
        }
        TLRPC.Message message = TLRPC.Message.TLdeserialize(data, data.readInt32(false), false);
        if (message != null) {
            message.readAttachPath(data, selfId);
        }
        data.reuse();
        if (!isHeldMessage(message)) {
            return null;
        }
        message.id = cursor.intValue(1);
        message.dialog_id = cursor.longValue(2);
        message.date = cursor.intValue(3);
        // A scheduled orphan whose random_id now maps to a positive server id has
        // already been sent (its main-table twin was id-remapped away, so it no
        // longer matches the both-tables dedup). It must be cleared, never re-driven.
        boolean alreadySent = !inMainTable && randomsMapToSentId(db, message.random_id);
        return new HeldItem(account, message.id, message.dialog_id, message, inMainTable, alreadySent);
    }

    /**
     * True if {@code randoms_v2} maps this random_id to a positive (server) message
     * id, i.e. the send was confirmed. A negative mapping (or none) means the row is
     * still local and unsent. random_id survives the id remap that confirmation
     * performs, so it is the one correlation key that outlives a send.
     */
    private static boolean randomsMapToSentId(SQLiteDatabase db, long randomId) {
        if (randomId == 0) {
            return false;
        }
        SQLiteCursor cursor = null;
        try {
            cursor = db.queryFinalized("SELECT mid FROM randoms_v2 WHERE random_id = " + randomId);
            while (cursor.next()) {
                if (cursor.intValue(0) > 0) {
                    return true;
                }
            }
        } catch (Exception e) {
            FileLog.e(e);
        } finally {
            if (cursor != null) {
                cursor.dispose();
            }
        }
        return false;
    }

    private static int countDistinctChats(ArrayList<HeldItem> items) {
        java.util.HashSet<String> seen = new java.util.HashSet<>();
        for (HeldItem item : items) {
            if (item.alreadySent) {
                continue;
            }
            seen.add(item.account + ":" + item.dialogId);
        }
        return seen.size();
    }

    private static final class HeldItem {
        final int account;
        final int mid;
        final long dialogId;
        final TLRPC.Message message;
        // Collected from messages_v2 (a killed send-now re-drive) rather than the
        // scheduled table; its handoff is already proven by its presence there.
        final boolean inMainTable;
        // A scheduled orphan whose send was already confirmed; clear, never re-send.
        final boolean alreadySent;

        HeldItem(int account, int mid, long dialogId, TLRPC.Message message, boolean inMainTable, boolean alreadySent) {
            this.account = account;
            this.mid = mid;
            this.dialogId = dialogId;
            this.message = message;
            this.inMainTable = inMainTable;
            this.alreadySent = alreadySent;
        }
    }
}
