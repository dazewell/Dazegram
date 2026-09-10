package com.radolyn.ayugram.ghosthold;

import android.content.SharedPreferences;
import android.util.Log;

import androidx.annotation.Nullable;

import org.telegram.SQLite.SQLiteCursor;
import org.telegram.SQLite.SQLiteDatabase;
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
 * text send is not transmitted. Instead it is persisted in a fork-owned,
 * per-account database ({@code ghosthold_<account>.db}, see {@link GhostHoldStore})
 * that no stock query ever names, so it renders in that chat's Scheduled list and
 * survives an app kill while being structurally invisible to the send path. That
 * invisibility is the no-leak invariant (P1): stock cannot transmit a row it
 * cannot see. Nothing about a held message lives only in memory -- the fork row is
 * the sole source of truth for queue membership.
 *
 * <p>A held message renders through a single read-time injection at the scheduled
 * load chokepoint ({@link #injectHeldScheduled}); the injected objects are
 * display-only and never written back to any stock table.
 *
 * <p>The queue drains ("flush") only when Ghost Mode turns off. Ghost is a
 * derived predicate over five independently-flippable toggles
 * ({@link NekoConfig#isGhostModeActive()}), so the flush is triggered from an
 * edge detector that watches the derived active/inactive transition rather than
 * any single toggle, plus a convergence check at process start. On flush a record
 * is handed back to the normal send funnel by reusing its negative id; the fork
 * record is deleted only once the funnel has provably written its stock row
 * (delete-on-write), after which stock owns delivery and cross-restart retry.
 *
 * <p>Everything here is global (Ghost is a single unsuffixed preference), but the
 * storage and sending sides are per-account: local message ids collide across
 * accounts, so every held record is addressed by {@code (account, mid, dialogId)},
 * never id alone.
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
    // random_id is a client-only field that is not part of the serialized TL blob.
    // The flush re-drive needs it so it reuses this id instead of the funnel minting
    // a fresh one (random_id == 0 guard at SendMessagesHelper ~:4965), which keeps a
    // re-driven send correlated with any prior attempt. Carry it in params and restore
    // it in toHeldItem.
    private static final String PARAM_RANDOM = "ghost_hold_random";
    // Set on a re-driven send after a durable-write failure, so the divert hook
    // lets it through to the network instead of trying to hold it again (which
    // would loop while the write keeps failing). See persistHeld's failure path.
    private static final String PARAM_BYPASS = "ghost_hold_bypass";

    private static final String PREFS_NAME = "ghosthold_state";
    private static final String KEY_LAST_GHOST_ACTIVE = "last_ghost_active";
    // Snapshot of the five ghost toggles captured while Ghost is active, so a
    // cancelled flush restores exactly that per-toggle state instead of force-
    // enabling all of them (setGhostMode(true) would).
    private static final String KEY_GHOST_SNAPSHOT_VALID = "ghost_snapshot_valid";
    private static final String KEY_GHOST_SNAPSHOT_PREFIX = "ghost_snapshot_";

    // Gap between successive sends on flush, so an entire backlog does not leave
    // in the same instant -- a simultaneous burst is itself a signal Ghost ended.
    private static final long FLUSH_STAGGER_MS = 1500;

    // NAX_SMOKE_ghost-hold: temporary smoke diagnostics tag. Removed in a later
    // commit once the smoke build confirms reachability. Log.e/.i/.w only, since
    // proguard strips Log.v/.d from the release build the smoke APK installs.
    private static final String SMOKE = "NAX_SMOKE_ghost-hold";

    private static volatile boolean flushInProgress;

    // Set once we register the foreground retry below, so it is never added twice.
    private static volatile boolean foregroundRetryArmed;

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
        // Re-arm this account lazily. initAccount is guarded by accountInited and is
        // a cheap boolean check after the first run, but on an in-process re-login
        // (LoginActivity reuses the slot without going back through
        // ApplicationLoader) checkOnProcessStart never runs again, so the fork
        // observers would stay unregistered. Re-arming at the send chokepoint puts
        // them back before the first held send of the new session.
        initAccount(account);
        if (!isHoldableTextSend(account, peer, params)) {
            return false;
        }
        return persistHeld(account, peer, params);
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
        // A re-drive after a failed durable write is explicitly not to be held.
        if (p.params != null && PARAM_VALUE.equals(p.params.get(PARAM_BYPASS))) {
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
        // Fail-closed backstop. Every semantic exclusion above is a denylist entry,
        // so a SendMessageParams field we have never heard of -- a future upstream
        // addition, or an existing one nobody wired in -- would sail through and be
        // silently dropped when of() rebuilds the message from the stored row. Invert
        // it: refuse unless every field outside the set we actually persist is at its
        // default. A new field then defaults to "refuse to hold" (send now, correctly,
        // and let the exposure warning speak) rather than "hold and mangle".
        if (!onlyPersistedFieldsSet(p)) {
            return false;
        }
        return true;
    }

    // Field names on SendMessageParams whose value we faithfully persist and restore
    // (or that carry no reconstructable state), so a non-default value on one of them
    // does not force a refuse. Everything not in this set must be at its default for a
    // send to be holdable -- see onlyPersistedFieldsSet. Keep in sync when the set of
    // fields persistHeld carries changes.
    private static final java.util.Set<String> PERSISTED_FIELDS = new java.util.HashSet<>(java.util.Arrays.asList(
            "message",            // the text itself
            "entities",           // carried onto msg.entities
            "params",             // copied into the stored params map
            "notify",             // carried as msg.silent
            "scheduleDate",       // carried as msg.date
            "scheduleRepeatPeriod", // carried in params (PARAM_REPEAT)
            "effect_id",          // carried in params (PARAM_EFFECT)
            "searchLinks",        // carried in params (PARAM_NO_WEBPAGE)
            "invert_media",       // carried onto msg.invert_media
            "replyToMsg",         // reply header reconstructed in persistHeld
            "replyToTopMsg",      // reply header reconstructed in persistHeld
            "replyQuote",         // text quote reconstructed; poll/todo refused above
            "sendMessageChatArguments", // always non-null on a composer send; its
                                        // meaningful fields are checked explicitly above
            "peer",               // the destination, not reconstructable state
            "retryMessageObject", // null here (a re-drive returned false already)
            "canSendGames"        // defaults true on every send; dice handled by text
    ));

    private static volatile java.lang.reflect.Field[] paramFieldsCache;

    /**
     * True iff every declared field of {@code p} outside {@link #PERSISTED_FIELDS} is
     * at its type default (null / 0 / false). This is the fail-closed half of
     * {@link #isHoldableTextSend}: it catches any field -- current-but-unwired or added
     * upstream later -- that we would otherwise persist a row for and then silently
     * drop on flush. Fields are public, so no setAccessible; the field array is cached
     * once. On any reflection error, fail closed (return false -> refuse to hold).
     */
    private static boolean onlyPersistedFieldsSet(SendMessagesHelper.SendMessageParams p) {
        java.lang.reflect.Field[] fields = paramFieldsCache;
        if (fields == null) {
            fields = p.getClass().getDeclaredFields();
            paramFieldsCache = fields;
        }
        try {
            for (java.lang.reflect.Field f : fields) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
                    continue;
                }
                if (PERSISTED_FIELDS.contains(f.getName())) {
                    continue;
                }
                Class<?> type = f.getType();
                if (type.isPrimitive()) {
                    // f.get autoboxes; compare against the type's zero/false default.
                    // Covers all eight primitives, so a future field of any primitive
                    // type is checked rather than falling through as a silent pass.
                    Object v = f.get(p);
                    if (type == boolean.class) {
                        if ((Boolean) v) {
                            return false;
                        }
                    } else if (type == char.class) {
                        if ((Character) v != 0) {
                            return false;
                        }
                    } else if (((Number) v).doubleValue() != 0.0) {
                        return false;
                    }
                } else if (f.get(p) != null) {
                    return false;
                }
            }
        } catch (ReflectiveOperationException e) {
            FileLog.e(e);
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

    private static boolean persistHeld(int account, long peer, SendMessagesHelper.SendMessageParams params) {
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
        // Carry random_id so toHeldItem can restore it (the blob does not hold it).
        // msg.random_id was just minted above and is always non-zero.
        stored.put(PARAM_RANDOM, Long.toString(msg.random_id));
        msg.params = stored;

        final TLRPC.Message stableMsg = msg;
        final byte[] blob;
        try {
            blob = GhostHoldStore.encode(stableMsg);
        } catch (Exception e) {
            // Serializing a plain-text message does not fail in practice; if it
            // somehow does we must not hold a message we could not persist. Return
            // false so maybeHold falls through to the normal send path, where the
            // message is actually sent (and the send-exposure warning fires) rather
            // than being silently swallowed.
            FileLog.e(e);
            return false;
        }
        final SendMessagesHelper.SendMessageParams originalParams = params;
        final GhostHoldStore store = GhostHoldStore.getInstance(account);
        final GhostHoldStore.HeldRecord record =
                new GhostHoldStore.HeldRecord(stableMsg.id, peer, stableMsg.date, GhostHoldStore.STATE_HELD, blob);
        // Critical: the durable fork row must exist before the user is told "Held".
        // insertOnQueue writes ghost_held on the store's serial queue; the interface
        // update, the Scheduled-list insert and the bulletin are chained after it so
        // they run only once the row has landed. This orders the signal after
        // durability without blocking the UI thread on a synchronous DB write.
        store.getQueue().postRunnable(() -> {
            boolean ok = store.insertOnQueue(record);
            if (!ok) {
                // The durable write failed (e.g. disk full). We already told the
                // funnel we would hold this send, so it did nothing; if we also drop
                // it here the user's message is lost (P2). Re-drive it through the
                // normal send path with the hold bypassed so it is actually sent.
                AndroidUtilities.runOnUIThread(() -> redriveAfterPersistFailure(account, originalParams));
                return;
            }
            postScheduledCount(account, peer);
            AndroidUtilities.runOnUIThread(() -> {
                MessageObject mo = new MessageObject(account, stableMsg, true, true);
                mo.scheduled = true;
                ArrayList<MessageObject> objArr = new ArrayList<>();
                objArr.add(mo);
                controller.updateInterfaceWithMessages(peer, objArr, 1);
                NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.dialogsNeedReload);
                Log.i(SMOKE, "expected: diverted send to hold and added to scheduled list account=" + account + " dialog=" + peer);
                showDivertBulletin();
            });
        });
        return true;
    }

    /**
     * Last-resort recovery when the durable hold write failed: send the original
     * message the normal way, with {@link #PARAM_BYPASS} set so the divert hook does
     * not try to hold it again. Losing the message would violate P2, so a message we
     * could not hold is sent rather than dropped.
     */
    private static void redriveAfterPersistFailure(int account, SendMessagesHelper.SendMessageParams params) {
        if (params.params == null) {
            params.params = new HashMap<>();
        }
        params.params.put(PARAM_BYPASS, PARAM_VALUE);
        Log.e(SMOKE, "durable hold write failed; re-driving send to avoid loss account=" + account);
        SendMessagesHelper.getInstance(account).sendMessage(params);
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
        SharedPreferences.Editor editor = prefs().edit();
        editor.putBoolean(KEY_LAST_GHOST_ACTIVE, nowActive);
        if (nowActive) {
            // Remember the exact toggle configuration while Ghost is active; a
            // cancelled flush restores precisely this, not an all-toggles-on state.
            writeGhostSnapshot(editor);
        }
        editor.apply();
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
        // Per-account bring-up, before any flush prompt below can collect: register
        // the fork observers, migrate any legacy held rows out of the stock tables
        // into ghost_held, and reconcile a flush interrupted by a kill. All three
        // are enqueued here so they are ordered ahead of the collect that promptFlush
        // triggers; each is idempotent and safe to re-run on a later launch.
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            if (UserConfig.getInstance(a).isClientActivated()) {
                initAccount(a);
            }
        }
        SharedPreferences.Editor editor = prefs().edit();
        editor.putBoolean(KEY_LAST_GHOST_ACTIVE, nowActive);
        if (nowActive) {
            writeGhostSnapshot(editor);
        }
        editor.apply();
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
            int pending = items.size();
            BaseFragment fragment = LaunchActivity.getLastFragment();
            if (fragment == null || fragment.getParentActivity() == null) {
                // No foreground screen to confirm on (e.g. process-start convergence
                // before any UI is up, including a headless push-service process whose
                // only checkOnProcessStart() runs with no Activity). Defer, never drain:
                // a backlog must not leave without the user seeing the "Send held
                // messages?" dialog. Release the claim and leave every row held, then
                // arm a one-time foreground retry so the same confirmation is re-offered
                // the moment a screen next exists in this process -- otherwise, since
                // checkOnProcessStart() is the only startup call site, foregrounding
                // later in the same process would never retry. Deferred, not skipped --
                // a user who keeps dismissing keeps deferring, which is their choice
                // (nothing is lost, the rows stay visible in Scheduled).
                flushInProgress = false;
                armForegroundRetry();
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

    /**
     * Register a once-per-process foreground listener that re-offers a deferred flush
     * the next time the app has a foreground screen. Called only when a flush was
     * deferred for want of a UI (see promptFlush's no-fragment branch); reuses the
     * app-wide ForegroundDetector so no lifecycle hook is added to a base file. The
     * listener stays for the process lifetime: if the user dismisses the re-offered
     * dialog it defers again, and the next foreground re-offers once more. The retry
     * is gated on Ghost being off -- while Ghost is on the backlog must stay held, so
     * there is nothing to flush and we skip the collect entirely.
     */
    private static void armForegroundRetry() {
        if (foregroundRetryArmed) {
            return;
        }
        foregroundRetryArmed = true;
        try {
            org.telegram.ui.Components.ForegroundDetector detector = org.telegram.ui.Components.ForegroundDetector.getInstance();
            if (detector == null) {
                foregroundRetryArmed = false;
                return;
            }
            detector.addListener(new org.telegram.ui.Components.ForegroundDetector.Listener() {
                @Override
                public void onBecameForeground() {
                    if (!NekoConfig.isGhostModeActive()) {
                        AndroidUtilities.runOnUIThread(GhostHoldController::promptFlush);
                    }
                }

                @Override
                public void onBecameBackground() {
                }
            });
            Log.i(SMOKE, "foreground retry armed for deferred flush");
        } catch (Exception e) {
            foregroundRetryArmed = false;
            FileLog.e(e);
        }
    }

    private static tw.nekomimi.nekogram.config.ConfigItem[] ghostToggleItems() {
        return new tw.nekomimi.nekogram.config.ConfigItem[]{
                NekoConfig.sendReadMessagePackets,
                NekoConfig.sendReadStoriesPackets,
                NekoConfig.sendOnlinePackets,
                NekoConfig.sendUploadProgress,
                NekoConfig.sendOfflinePacketAfterOnline,
        };
    }

    private static void writeGhostSnapshot(SharedPreferences.Editor editor) {
        tw.nekomimi.nekogram.config.ConfigItem[] items = ghostToggleItems();
        for (tw.nekomimi.nekogram.config.ConfigItem item : items) {
            // Key the snapshot by the toggle's own stable config key, never by array
            // index: a future reorder or insertion in ghostToggleItems() would otherwise
            // silently restore the wrong privacy toggle on cancel.
            editor.putBoolean(KEY_GHOST_SNAPSHOT_PREFIX + item.getKey(), item.Bool());
        }
        editor.putBoolean(KEY_GHOST_SNAPSHOT_VALID, true);
    }

    private static void restoreGhost() {
        if (prefs().getBoolean(KEY_GHOST_SNAPSHOT_VALID, false)) {
            // Cancel means "keep holding". Re-activate Ghost by restoring the exact
            // per-toggle state captured while it was last active, writing back only the
            // toggles that actually changed -- so we never switch on a toggle the user
            // left off. setGhostMode(true) would force all five into ghost state.
            tw.nekomimi.nekogram.config.ConfigItem[] items = ghostToggleItems();
            for (tw.nekomimi.nekogram.config.ConfigItem item : items) {
                boolean prior = prefs().getBoolean(KEY_GHOST_SNAPSHOT_PREFIX + item.getKey(), item.Bool());
                if (item.Bool() != prior) {
                    item.setConfigBool(prior);
                }
            }
        } else {
            // No captured active state (Ghost was never active in a tracked session);
            // fall back to enabling Ghost so a cancelled flush still keeps messages held.
            NekoConfig.setGhostMode(true);
        }
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
        final int pending = all;
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
        final GhostHoldStore store = GhostHoldStore.getInstance(item.account);
        // Never act on the snapshot captured at collect time. During the stagger the
        // user can delete a held row (the messagesDeleted observer removes the fork
        // record); re-read the current record on the store's serial queue immediately
        // before dispatch. A gone record means discard. Mark the record FLUSHING here,
        // durably, so a kill after handoff begins is reconciled at next start rather
        // than silently re-driven or lost.
        store.getQueue().postRunnable(() -> {
            final GhostHoldStore.HeldRecord rec = store.selectOnQueue(item.mid);
            HeldItem fresh = null;
            // Only proceed to dispatch once the FLUSHING claim has durably persisted.
            // If the UPDATE fails, leave the row HELD and skip it this cycle: it
            // re-drives on the next flush. Dispatching on a failed claim would let a
            // kill after the stock write but before fork deletion strand the row as
            // HELD, which reconcile (FLUSHING-only) would then re-send -- a duplicate.
            if (rec != null && store.updateStateOnQueue(item.mid, GhostHoldStore.STATE_FLUSHING)) {
                fresh = toHeldItem(item.account, rec);
            }
            final HeldItem f = fresh;
            AndroidUtilities.runOnUIThread(() -> dispatchFreshItem(item, f, remaining, pending));
        });
    }

    private static void dispatchFreshItem(HeldItem item, @Nullable HeldItem fresh, AtomicInteger remaining, int pending) {
        final int account = item.account;
        final long dialogId = item.dialogId;
        final int mid = item.mid;
        // The record vanished during the stagger (user deleted it): nothing to send,
        // nothing to remove.
        if (fresh == null) {
            onItemTerminal(remaining, pending);
            return;
        }
        // Ghost flipped back on during the re-read hop -> keep it held. Revert the
        // FLUSHING mark so it renders and re-drives cleanly on the next flush.
        if (NekoConfig.isGhostModeActive()) {
            revertToHeld(account, mid, () -> onItemTerminal(remaining, pending));
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
        // Once handed to the funnel this is a normal outgoing message, so it must not
        // still carry our hold markers -- the funnel writes the stock row from this
        // same message, and a marked stock row is exactly the leak this rebuild
        // removes. The three fields we care about were lifted into first-class fields
        // just above. of(mo) passed messageOwner.params by reference as p.params, so
        // stripping the keys here unmarks the row the funnel writes.
        if (m.params != null) {
            m.params.remove(PARAM_MARKER);
            m.params.remove(PARAM_NO_WEBPAGE);
            m.params.remove(PARAM_REPEAT);
            m.params.remove(PARAM_EFFECT);
            m.params.remove(PARAM_RANDOM);
        }

        // Re-drive in place. The funnel keys its destination table off scheduleDate
        // alone (SendMessagesHelper:5306-5320), not any current table, so a send-now
        // re-drive (scheduleDate == 0) makes the funnel write messages_v2, while a
        // future-dated one writes scheduled_messages_v2 -- both under the reused
        // negative id, which is what completeHandoff probes for.
        // An automated flush never opens a paywall. The dialog was not paid when this
        // row was held (isHoldableTextSend excludes paid dialogs), but it can become
        // paid before the flush. Re-driving it now would make the funnel open the Stars
        // paywall; if Ghost is re-enabled while that paywall is open and the user then
        // accepts, the funnel's deferred callback -- which we do not own -- would
        // transmit under Ghost, breaking the core no-leak invariant. So re-run the same
        // paid check here: if the dialog is now paid, leave the record held rather than
        // re-drive it. It stays visible in Scheduled, the flush bulletin reports it as
        // not sent, and the user can send it by hand at the price they are shown.
        if (isPaidDialog(account, dialogId)) {
            revertToHeld(account, mid, () -> onItemTerminal(remaining, pending));
            return;
        }
        final boolean fut = future;
        final SendMessagesHelper.SendMessageParams sendParams = p;
        final GhostHoldStore store = GhostHoldStore.getInstance(account);
        // Final revalidation immediately before dispatch. flushItem marked the record
        // FLUSHING and then hopped to the UI thread; during that hop the user can
        // delete the held row (the messagesDeleted observer removes the fork record).
        // Acting on the snapshot alone would send a message the user just deleted, so
        // re-read on the store's serial queue right before the send and discard if the
        // record is gone or no longer FLUSHING. This does not close the window to zero
        // -- the send itself must run on the UI thread one hop later -- but it shrinks
        // it to that single hop, which is the tightest a UI-thread send allows.
        store.getQueue().postRunnable(() -> {
            GhostHoldStore.HeldRecord still = store.selectOnQueue(mid);
            final boolean valid = still != null && still.state == GhostHoldStore.STATE_FLUSHING;
            AndroidUtilities.runOnUIThread(() -> {
                if (!valid) {
                    onItemTerminal(remaining, pending);
                    return;
                }
                // NagramX: Ghost and paid were checked before this final store-queue
                // hop, but the user can re-enable Ghost -- or the dialog can become
                // paid -- during it. The retry object bypasses maybeHold, so dispatching
                // now would transmit under Ghost, or open a Stars paywall whose deferred
                // callback we do not own could transmit under Ghost later. Re-check both
                // on this last UI turn and keep the message held if either is true.
                if (NekoConfig.isGhostModeActive() || isPaidDialog(account, dialogId)) {
                    revertToHeld(account, mid, () -> onItemTerminal(remaining, pending));
                    return;
                }
                SendMessagesHelper.getInstance(account).sendMessage(sendParams);
                // Delete-on-write completion: the fork record is removed only once the
                // funnel has provably written its stock row for this negative id.
                // completeHandoff proves that on the storage queue (enqueued after the
                // funnel's own putMessages(useQueue=true)) and signals this item
                // terminal only after it has run. If the funnel wrote nothing (early
                // return / became paid), the record is reverted to HELD for the next
                // flush; nothing is ever lost.
                completeHandoff(account, mid, dialogId, fut, () -> onItemTerminal(remaining, pending));
            });
        });
    }
    /**
     * Delete-on-write completion. After the flush hands a held message back to the
     * send funnel, the fork record is removed only once the funnel has provably
     * written its stock row under the reused negative id. Runs on the storage queue,
     * enqueued after the funnel's own {@code putMessages(useQueue=true)}, so by the
     * time it runs a completed send-now has written {@code messages_v2}, a completed
     * future-dated send has written {@code scheduled_messages_v2}, and an early
     * return (became paid, {@code sendToUser == null}) has written nothing.
     *
     * <p>Present ⇒ stock now owns delivery and cross-restart retry for this row
     * exactly as {@code getUnsentMessages} does for any unsent message, so the fork
     * record is deleted and P2 is preserved by the stock row, not by us. Absent ⇒ the
     * funnel wrote nothing, so the record is reverted to HELD and re-driven on the
     * next flush. The only ambiguity is a kill after the server confirmed and remapped
     * {@code -N → +P} but before this delete: at the next start's reconcile the row is
     * absent-by-{@code -N} and is re-driven, producing a duplicate. Per the design that
     * is the correct direction to fail -- duplicate, never loss.
     */
    private static void completeHandoff(int account, int mid, long dialogId, boolean future, @Nullable Runnable onDone) {
        MessagesStorage storage = MessagesStorage.getInstance(account);
        storage.getStorageQueue().postRunnable(() -> {
            boolean handedOff = false;
            try {
                SQLiteDatabase db = storage.getDatabase();
                String table = future ? "scheduled_messages_v2" : "messages_v2";
                SQLiteCursor probe = db.queryFinalized("SELECT 1 FROM " + table + " WHERE mid = " + mid + " AND uid = " + dialogId + " LIMIT 1");
                handedOff = probe.next();
                probe.dispose();
            } catch (Exception e) {
                FileLog.e(e);
            }
            final boolean ho = handedOff;
            GhostHoldStore store = GhostHoldStore.getInstance(account);
            store.getQueue().postRunnable(() -> {
                if (ho) {
                    store.deleteOnQueue(mid);
                    if (!future) {
                        // NagramX: a send-now handoff wrote the messages_v2 twin and
                        // removed the fork row, but an already-open Scheduled list still
                        // holds the display-only held object -- the HELD-only render
                        // filter only governs future loads. Drop it from that open list
                        // by mid so it can't be deleted there as held, which would clear
                        // an empty scheduled_messages_v2 while stranding the messages_v2
                        // twin for the unsent scan. Pure UI dispatch, no stock write;
                        // scheduled-scoped so the main-view twin's fragment ignores it.
                        AndroidUtilities.runOnUIThread(() -> removeStaleScheduledItem(account, dialogId, mid));
                    }
                } else {
                    store.updateStateOnQueue(mid, GhostHoldStore.STATE_HELD);
                }
                postScheduledCount(account, dialogId);
                // Signal the item terminal only after this resolution has run, whatever
                // its outcome, and always on the UI thread (flushInProgress lives there).
                // This is what makes flush completion observed, not timed.
                if (onDone != null) {
                    AndroidUtilities.runOnUIThread(onDone);
                }
            });
        });
    }

    /**
     * Removes a handed-off send-now message from any already-open Scheduled list by
     * posting a scheduled-scoped {@code messagesDeleted} for its negative id. The
     * render-time HELD-only filter only affects future loads, so without this an open
     * list keeps the stale display-only held object after the fork row is deleted; a
     * user deleting it there would clear scheduled_messages_v2 (empty for a send-now)
     * while leaving the messages_v2 twin for the unsent scan to transmit. This is a
     * pure NotificationCenter dispatch -- no stock read or write -- and the scheduled
     * flag scopes the UI reaction to Scheduled fragments, so the main-view twin is
     * untouched. channelId is -dialogId for a channel/supergroup and 0 otherwise,
     * matching ChatActivity.processDeletedMessages' channel gate.
     */
    private static void removeStaleScheduledItem(int account, long dialogId, int mid) {
        long channelId = 0;
        if (DialogObject.isChatDialog(dialogId)) {
            TLRPC.Chat chat = MessagesController.getInstance(account).getChat(-dialogId);
            if (ChatObject.isChannel(chat)) {
                channelId = -dialogId;
            }
        }
        ArrayList<Integer> ids = new ArrayList<>(1);
        ids.add(mid);
        NotificationCenter.getInstance(account).postNotificationName(
                NotificationCenter.messagesDeleted, ids, channelId, true, false, false, 0);
    }

    /**
     * Reverts a record's FLUSHING mark back to HELD when the flush declined to send
     * it (Ghost came back on, or the dialog became paid), then runs {@code onDone} on
     * the UI thread. The record stays visible in Scheduled and re-drives cleanly on
     * the next flush.
     */
    private static void revertToHeld(int account, int mid, @Nullable Runnable onDone) {
        GhostHoldStore store = GhostHoldStore.getInstance(account);
        store.getQueue().postRunnable(() -> {
            store.updateStateOnQueue(mid, GhostHoldStore.STATE_HELD);
            if (onDone != null) {
                AndroidUtilities.runOnUIThread(onDone);
            }
        });
    }

    // ---- held-queue reads ----

    /**
     * Async count of held messages for the settings screen; delivered on the UI thread.
     */
    public static void countHeld(Utilities.Callback<Integer> onDone) {
        collectHeld(items -> onDone.run(items.size()));
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
            final GhostHoldStore store = GhostHoldStore.getInstance(account);
            store.getQueue().postRunnable(() -> {
                // NagramX: HELD only, never FLUSHING. This collection feeds the flush,
                // so a FLUSHING row here would be dispatched again -- and a row stuck
                // FLUSHING (its handoff already wrote the stock twin but the follow-up
                // delete failed) would send a duplicate. FLUSHING rows are resolved
                // solely by startup reconciliation (present twin -> delete, absent ->
                // re-hold), not by the flush. The settings count reads the same set,
                // where HELD-only is also correct: a handed-off row is being sent, not
                // held.
                for (GhostHoldStore.HeldRecord rec : store.selectByStateOnQueue(GhostHoldStore.STATE_HELD)) {
                    HeldItem it = toHeldItem(account, rec);
                    if (it != null) {
                        result.add(it);
                    }
                }
                if (remaining.decrementAndGet() == 0) {
                    AndroidUtilities.runOnUIThread(() -> onDone.run(new ArrayList<>(result)));
                }
            });
        }
    }

    // ---- render injection (read-time, display-only) ----

    /**
     * Injects this account's held messages for {@code dialogId} into a freshly loaded
     * scheduled-message list as display-only objects. Called at the single read-time
     * chokepoint in {@code MessagesController.processLoadedMessages}, after every cache
     * write has already happened and only into the UI-bound {@code objects} list, so a
     * held message is rendered but never written back to any stock table -- the property
     * that keeps the no-leak invariant structural rather than guarded. Each object is
     * built from a fresh decode of the stored blob, so nothing the UI does to a shown
     * message can reach the cache. Runs on the message-load thread and reads only the
     * store's lock-free published snapshot.
     */
    public static void injectHeldScheduled(int account, long dialogId, ArrayList<MessageObject> objects) {
        if (objects == null) {
            return;
        }
        // Re-arm after an in-process re-login (see maybeHold): the render path is
        // reached post-login when the user opens a scheduled list, so it re-registers
        // the observers if checkOnProcessStart did not run again.
        initAccount(account);
        long selfId = UserConfig.getInstance(account).getClientUserId();
        GhostHoldStore store = GhostHoldStore.getInstance(account);
        // The published snapshot is read off-queue for speed, so it can momentarily
        // still hold a previous slot owner's rows in the window between a re-login and
        // the queue re-opening the file under the new owner. ownsUser refuses that
        // window: it returns true only once an activated open has stamped this user,
        // so a stale snapshot is never rendered. Fails toward showing nothing until
        // the store is confirmed, never toward showing another user's held messages.
        if (!store.ownsUser(selfId)) {
            return;
        }
        List<GhostHoldStore.HeldRecord> records = store.cachedForDialog(dialogId);
        if (records == null || records.isEmpty()) {
            return;
        }
        java.util.HashSet<Integer> present = new java.util.HashSet<>();
        for (int i = 0; i < objects.size(); i++) {
            present.add(objects.get(i).getId());
        }
        for (GhostHoldStore.HeldRecord rec : records) {
            // Inject only HELD rows. A FLUSHING row has been handed to the send
            // funnel: once the funnel writes its stock row the message is an ordinary
            // sending message and already renders in the timeline, so continuing to
            // show it here would be a stale second copy -- and, worse, would let the
            // user delete a handed-off message through the scheduled path, which only
            // clears scheduled_messages_v2 and would strand the messages_v2 twin for
            // the unsent scan to auto-send. Not rendering it means it cannot be
            // deleted through the held path, so that race cannot arise. A FLUSHING row
            // that the funnel did not actually write is reverted to HELD promptly
            // (revertToHeld / completeHandoff / startup reconcile), so it reappears.
            if (rec.state != GhostHoldStore.STATE_HELD) {
                continue;
            }
            if (present.contains(rec.mid)) {
                // A stock scheduled row for this mid is still present (a migration delete
                // not yet applied): show it once, from the stock copy, not twice.
                continue;
            }
            TLRPC.Message m = GhostHoldStore.decode(rec.data, selfId);
            if (m == null) {
                continue;
            }
            m.id = rec.mid;
            m.dialog_id = rec.dialogId;
            m.date = rec.date;
            MessageObject mo = new MessageObject(account, m, true, true);
            mo.scheduled = true;
            objects.add(mo);
            present.add(rec.mid);
        }
    }

    /**
     * Decodes a stored record into a flush-ready {@link HeldItem}, restoring random_id
     * from params (it is not part of the serialized blob) so the re-drive reuses the
     * same id instead of the funnel minting a fresh one (random_id == 0 guard at
     * SendMessagesHelper ~:4965). Returns null on a decode failure, leaving the record
     * in place for a later attempt rather than dropping it.
     */
    @Nullable
    private static HeldItem toHeldItem(int account, GhostHoldStore.HeldRecord rec) {
        long selfId = UserConfig.getInstance(account).getClientUserId();
        TLRPC.Message message = GhostHoldStore.decode(rec.data, selfId);
        if (message == null) {
            return null;
        }
        message.id = rec.mid;
        message.dialog_id = rec.dialogId;
        message.date = rec.date;
        if (message.random_id == 0 && message.params != null) {
            String raw = message.params.get(PARAM_RANDOM);
            if (raw != null) {
                try {
                    message.random_id = Long.parseLong(raw);
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return new HeldItem(account, rec.mid, rec.dialogId, message);
    }

    /**
     * Recomputes and posts the absolute scheduled count for a dialog (stock
     * server-scheduled rows + fork-held rows) so the Scheduled button updates when a
     * held message appears or is removed, not just on a fresh chat open.
     * scheduledMessagesUpdated sets the count absolutely, so posting the combined total
     * cannot double-count. The stock count is read on the storage queue; the fork count
     * comes from the store's published snapshot.
     */
    private static void postScheduledCount(int account, long dialogId) {
        MessagesStorage storage = MessagesStorage.getInstance(account);
        storage.getStorageQueue().postRunnable(() -> {
            int stock = 0;
            try {
                SQLiteDatabase db = storage.getDatabase();
                SQLiteCursor cursor = db.queryFinalized("SELECT COUNT(mid) FROM scheduled_messages_v2 WHERE uid = " + dialogId);
                if (cursor.next()) {
                    stock = cursor.intValue(0);
                }
                cursor.dispose();
            } catch (Exception e) {
                FileLog.e(e);
            }
            int fork = 0;
            GhostHoldStore store = GhostHoldStore.getInstance(account);
            if (store.ownsUser(UserConfig.getInstance(account).getClientUserId())) {
                fork = store.cachedCountForDialog(dialogId);
            }
            final int total = stock + fork;
            AndroidUtilities.runOnUIThread(() ->
                    NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.scheduledMessagesUpdated, dialogId, total, true));
        });
    }

    // ---- per-account bring-up ----

    private static final GhostHoldObserver[] observers = new GhostHoldObserver[UserConfig.MAX_ACCOUNT_COUNT];
    private static final boolean[] accountInited = new boolean[UserConfig.MAX_ACCOUNT_COUNT];

    /**
     * One-time per-account initialisation: register the fork observers (held-row
     * deletion and logout), warm the store's snapshot for the render injection, migrate
     * any legacy held rows out of the stock tables, and reconcile a flush interrupted by
     * a kill. Idempotent per process; the logout observer clears the flag so a re-login
     * re-runs it.
     */
    private static void initAccount(int account) {
        if (accountInited[account]) {
            return;
        }
        AndroidUtilities.runOnUIThread(() -> {
            // Claim the account and install the observer in the same UI turn. If the
            // flag were set before the observer were actually registered, a logout in
            // that gap would be missed (appDidLogout also posts on the UI thread): the
            // DB file would not be deleted and the next login would skip re-init. The
            // outer check is only a cheap off-thread fast path; this one, on the UI
            // thread, is authoritative and also prevents a double install.
            if (accountInited[account]) {
                return;
            }
            accountInited[account] = true;
            NotificationCenter nc = NotificationCenter.getInstance(account);
            GhostHoldObserver obs = new GhostHoldObserver(account);
            observers[account] = obs;
            nc.addObserver(obs, NotificationCenter.messagesDeleted);
            nc.addObserver(obs, NotificationCenter.appDidLogout);
            GhostHoldStore store = GhostHoldStore.getInstance(account);
            store.getQueue().postRunnable(() -> store.selectAllOnQueue());
            migrateAccount(account);
            reconcileFlushing(account);
        });
    }

    /**
     * Moves any legacy held rows -- written by the previous storage design as marked,
     * negative-id, {@code send_state = 1} rows in the stock scheduled_messages_v2 /
     * messages_v2 tables -- into ghost_held. The fork insert happens before the stock
     * delete so there is never a window where the message exists in neither place (P2);
     * a kill in between re-runs idempotently on the next start (mid is the PK, insert
     * REPLACEs). A row whose blob will not decode is left in the stock table (still
     * guarded) and logged rather than dropped.
     */
    private static void migrateAccount(int account) {
        MessagesStorage storage = MessagesStorage.getInstance(account);
        long selfId = UserConfig.getInstance(account).getClientUserId();
        final GhostHoldStore store = GhostHoldStore.getInstance(account);
        // Capture the store's generation now; the fork-queue insert below drops the
        // batch if it has changed, i.e. a logout tore the store down after this
        // collection began.
        final int genAtStart = store.currentGeneration();
        storage.getStorageQueue().postRunnable(() -> {
            ArrayList<GhostHoldStore.HeldRecord> toInsert = new ArrayList<>();
            ArrayList<Integer> schedDelete = new ArrayList<>();
            ArrayList<Integer> mainDelete = new ArrayList<>();
            java.util.HashSet<Long> dialogs = new java.util.HashSet<>();
            SQLiteDatabase db = storage.getDatabase();
            collectLegacy(db, "scheduled_messages_v2", account, selfId, toInsert, schedDelete, dialogs);
            collectLegacy(db, "messages_v2", account, selfId, toInsert, mainDelete, dialogs);
            if (toInsert.isEmpty()) {
                return;
            }
            store.getQueue().postRunnable(() -> {
                // Drop the batch if the store was torn down (logout) after it was
                // collected. Safe: the stock rows are deleted only after a confirmed
                // insert, so nothing was removed and the next init re-migrates them.
                // Applying it would stamp a reopened store -- a different user's (a
                // cross-account leak), or the same user's post-logout store the deletion
                // was meant to leave empty (resurrecting destroyed messages). The check
                // and the teardown's increment both run on this fork queue, so the
                // compare is atomic with the teardown.
                if (store.currentGeneration() != genAtStart) {
                    Log.i(SMOKE, "migrate: dropped stale batch account=" + account + " store reopened");
                    return;
                }
                java.util.HashSet<Integer> insertedOk = new java.util.HashSet<>();
                for (GhostHoldStore.HeldRecord rec : toInsert) {
                    if (store.insertOnQueue(rec)) {
                        insertedOk.add(rec.mid);
                    }
                }
                if (insertedOk.isEmpty()) {
                    return;
                }
                storage.getStorageQueue().postRunnable(() -> {
                    // Delete a legacy stock row only once its fork insert is confirmed
                    // durable. A row whose insert failed stays in the stock table (still
                    // guarded) so it is retried on the next start rather than deleted
                    // after a lost insert -- deleting an unconfirmed row would lose the
                    // message (P2).
                    ArrayList<Integer> sd = retainConfirmed(schedDelete, insertedOk);
                    ArrayList<Integer> md = retainConfirmed(mainDelete, insertedOk);
                    try {
                        if (!sd.isEmpty()) {
                            db.executeFast("DELETE FROM scheduled_messages_v2 WHERE mid IN(" + join(sd) + ")").stepThis().dispose();
                        }
                        if (!md.isEmpty()) {
                            db.executeFast("DELETE FROM messages_v2 WHERE mid IN(" + join(md) + ")").stepThis().dispose();
                        }
                    } catch (Exception e) {
                        FileLog.e(e);
                    }
                    Log.i(SMOKE, "migrate: moved " + insertedOk.size() + " legacy held rows to ghost_held account=" + account);
                    for (long d : dialogs) {
                        postScheduledCount(account, d);
                    }
                });
            });
        });
    }

    private static void collectLegacy(SQLiteDatabase db, String table, int account, long selfId,
                                      ArrayList<GhostHoldStore.HeldRecord> toInsert,
                                      ArrayList<Integer> toDelete, java.util.HashSet<Long> dialogs) {
        SQLiteCursor cursor = null;
        try {
            cursor = db.queryFinalized("SELECT data, mid, uid, date FROM " + table + " WHERE mid < 0 AND send_state = 1");
            while (cursor.next()) {
                byte[] blob = cursor.byteArrayValue(0);
                if (blob == null) {
                    continue;
                }
                TLRPC.Message m = GhostHoldStore.decode(blob, selfId);
                if (m == null) {
                    // Undecodable: leave the stock row intact (the retained guards keep it
                    // from auto-sending) and log rather than drop the user's message.
                    Log.e(SMOKE, "migrate: skipped undecodable legacy row in " + table + " account=" + account);
                    continue;
                }
                if (!isHeldMessage(m)) {
                    continue;
                }
                int mid = cursor.intValue(1);
                long uid = cursor.longValue(2);
                int date = cursor.intValue(3);
                toInsert.add(new GhostHoldStore.HeldRecord(mid, uid, date, GhostHoldStore.STATE_HELD, blob));
                toDelete.add(mid);
                dialogs.add(uid);
            }
        } catch (Exception e) {
            FileLog.e(e);
        } finally {
            if (cursor != null) {
                cursor.dispose();
            }
        }
    }

    /**
     * Reconciles records left in FLUSHING by a kill mid-handoff. For each, probe the
     * stock tables for the reused negative id: present ⇒ the funnel wrote the row and
     * stock now owns delivery, so delete the fork record; absent ⇒ the write never
     * landed, or the send already completed and remapped the id (indistinguishable
     * here), so revert to HELD and let the next flush re-drive it. Failing an absent
     * probe toward re-drive is the deliberate "duplicate, never loss" direction.
     */
    private static void reconcileFlushing(int account) {
        GhostHoldStore store = GhostHoldStore.getInstance(account);
        store.getQueue().postRunnable(() -> {
            ArrayList<GhostHoldStore.HeldRecord> flushing = store.selectByStateOnQueue(GhostHoldStore.STATE_FLUSHING);
            if (flushing.isEmpty()) {
                return;
            }
            MessagesStorage storage = MessagesStorage.getInstance(account);
            storage.getStorageQueue().postRunnable(() -> {
                ArrayList<Integer> present = new ArrayList<>();
                ArrayList<Integer> absent = new ArrayList<>();
                SQLiteDatabase db = storage.getDatabase();
                for (GhostHoldStore.HeldRecord rec : flushing) {
                    boolean found = false;
                    try {
                        SQLiteCursor c = db.queryFinalized("SELECT 1 FROM messages_v2 WHERE mid = " + rec.mid + " AND uid = " + rec.dialogId + " UNION ALL SELECT 1 FROM scheduled_messages_v2 WHERE mid = " + rec.mid + " AND uid = " + rec.dialogId + " LIMIT 1");
                        found = c.next();
                        c.dispose();
                    } catch (Exception e) {
                        FileLog.e(e);
                    }
                    (found ? present : absent).add(rec.mid);
                }
                store.getQueue().postRunnable(() -> {
                    if (!present.isEmpty()) {
                        store.deleteManyOnQueue(present);
                    }
                    for (int mid : absent) {
                        store.updateStateOnQueue(mid, GhostHoldStore.STATE_HELD);
                    }
                    Log.i(SMOKE, "reconcile: flushing resolved handedOff=" + present.size() + " reheld=" + absent.size() + " account=" + account);
                });
            });
        });
    }

    private static String join(ArrayList<Integer> ids) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < ids.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(ids.get(i));
        }
        return sb.toString();
    }

    /** Subset of {@code ids} whose fork insert was confirmed durable. */
    private static ArrayList<Integer> retainConfirmed(ArrayList<Integer> ids, java.util.HashSet<Integer> confirmed) {
        ArrayList<Integer> out = new ArrayList<>();
        for (int mid : ids) {
            if (confirmed.contains(mid)) {
                out.add(mid);
            }
        }
        return out;
    }

    /**
     * Per-account observer for held-row deletion and logout. Deletion: when the user
     * deletes a held row from Scheduled, or deletes a handed-off message's stock twin
     * from the timeline, the notification carries the (negative) id and the matching
     * fork record is removed. Logout: the account's ghost_held database file is deleted
     * so a re-login never inherits held messages.
     */
    private static final class GhostHoldObserver implements NotificationCenter.NotificationCenterDelegate {
        private final int account;

        GhostHoldObserver(int account) {
            this.account = account;
        }

        @Override
        public void didReceivedNotification(int id, int acc, Object... args) {
            if (id == NotificationCenter.appDidLogout) {
                GhostHoldStore store = GhostHoldStore.getInstance(account);
                store.getQueue().postRunnable(store::deleteDatabaseFileOnQueue);
                accountInited[account] = false;
                NotificationCenter nc = NotificationCenter.getInstance(account);
                nc.removeObserver(this, NotificationCenter.messagesDeleted);
                nc.removeObserver(this, NotificationCenter.appDidLogout);
                observers[account] = null;
                return;
            }
            if (id == NotificationCenter.messagesDeleted) {
                // NagramX: react to both scheduled-list deletions (a held row the user
                // removed from Scheduled) and ordinary timeline deletions. After a
                // send-now handoff the stock twin lives in the timeline under the reused
                // negative id and is deleted with scheduled == false; ignoring that would
                // leave the fork record FLUSHING while its twin is gone, and reconcile
                // would read the absent row as an interrupted handoff and re-drive it,
                // resurrecting a message the user deleted. selectOnQueue below matches
                // only our own negative ids, so any unrelated deletion is a no-op.
                @SuppressWarnings("unchecked")
                ArrayList<Integer> mids = (ArrayList<Integer>) args[0];
                if (mids == null || mids.isEmpty()) {
                    return;
                }
                ArrayList<Integer> negs = new ArrayList<>();
                for (int mid : mids) {
                    if (mid < 0) {
                        negs.add(mid);
                    }
                }
                if (negs.isEmpty()) {
                    return;
                }
                GhostHoldStore store = GhostHoldStore.getInstance(account);
                store.getQueue().postRunnable(() -> {
                    java.util.HashSet<Long> dialogs = new java.util.HashSet<>();
                    ArrayList<Integer> toDelete = new ArrayList<>();
                    for (int mid : negs) {
                        GhostHoldStore.HeldRecord rec = store.selectOnQueue(mid);
                        if (rec != null) {
                            dialogs.add(rec.dialogId);
                            toDelete.add(mid);
                        }
                    }
                    if (toDelete.isEmpty()) {
                        return;
                    }
                    store.deleteManyOnQueue(toDelete);
                    for (long d : dialogs) {
                        postScheduledCount(account, d);
                    }
                    Log.i(SMOKE, "held delete: removed " + toDelete.size() + " held rows via messagesDeleted account=" + account);
                });
            }
        }
    }

    private static int countDistinctChats(ArrayList<HeldItem> items) {
        java.util.HashSet<String> seen = new java.util.HashSet<>();
        for (HeldItem item : items) {
            seen.add(item.account + ":" + item.dialogId);
        }
        return seen.size();
    }

    private static final class HeldItem {
        final int account;
        final int mid;
        final long dialogId;
        final TLRPC.Message message;

        HeldItem(int account, int mid, long dialogId, TLRPC.Message message) {
            this.account = account;
            this.mid = mid;
            this.dialogId = dialogId;
            this.message = message;
        }
    }
}
