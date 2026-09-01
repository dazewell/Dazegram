package com.radolyn.ayugram.eventschedule;

import android.content.Context;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.R;
import org.telegram.messenger.UserObject;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.GraySectionCell;
import org.telegram.ui.Cells.UserCell;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.ItemOptions;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;

/**
 * Read-only list of every armed {@code #eventschedule} trigger for the current account, reached
 * by long-pressing the Chats nav button (see {@link MessageTriggersMenu}). Its whole reason to
 * exist: after arming several messages on one trigger, this is the one place that tells the truth
 * about whether they are actually armed, without the user having to wait and see if they fire.
 *
 * <p>Deliberately flat, not the two-level {@code BookmarkManagerActivity} drill-down shape: a
 * collapsed count is exactly the kind of confident-looking summary that this page exists to
 * avoid. Every live entry gets its own visible row.
 *
 * <p>Named "Message Triggers" to the user -- "armed" is the engine's own internal vocabulary
 * ({@code STATE_ARMED}) and never appears in the trigger sheet itself ({@code EventScheduleTrigger}
 * = "Send on event"), so surfacing it here as a screen name was the one place this feature broke
 * its own naming.
 */
public class MessageTriggersActivity extends BaseFragment {

    /**
     * Marker for the two kinds of row this list renders: one header per (dialogId, triggerKey)
     * group -- chat name plus the trigger shown once -- followed by that group's live entries.
     * A plain interface rather than sealed: nothing else in this codebase uses sealed types, and
     * the exhaustiveness win here doesn't carry its weight against staying consistent with it.
     */
    private interface ListItem {
    }

    /** Chat name and this group's trigger chip, rendered once per (dialogId, triggerKey) group. */
    private record HeaderItem(String chatTitle, CharSequence triggerSummary) implements ListItem {
    }

    /**
     * One live armed entry. Brief/timeline intentionally do not repeat the chat name or the
     * trigger -- both already sit in this row's HeaderItem, so repeating them per row would be
     * exactly the redundant-by-construction look dazewell's screenshot showed: identical avatar,
     * identical chat name, identical trigger text, distinguishable only by an easy-to-miss year.
     */
    private record RowItem(long dialogId, TLObject peer, CharSequence brief, CharSequence timeline, EventScheduleEntry entry, boolean divider) implements ListItem {
    }

    private RecyclerListView listView;
    private ListAdapter adapter;
    private TextView emptyView;
    private final ArrayList<ListItem> items = new ArrayList<>();
    private int loadRequestId;

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(LocaleController.getString(R.string.MessageTriggersTitle));

        FrameLayout frameLayout = new FrameLayout(context);
        fragmentView = frameLayout;
        frameLayout.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));

        listView = new RecyclerListView(context);
        listView.setLayoutManager(new LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false));
        listView.setVerticalScrollBarEnabled(false);
        frameLayout.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        adapter = new ListAdapter(context, items);
        listView.setAdapter(adapter);

        listView.setOnItemClickListener((view, position) -> {
            if (position < 0 || position >= items.size() || !(items.get(position) instanceof RowItem row)) {
                return;
            }
            openScheduled(row.dialogId());
        });
        listView.setOnItemLongClickListener((view, position) -> {
            if (position < 0 || position >= items.size() || !(items.get(position) instanceof RowItem row)) {
                return false;
            }
            EventScheduleEntry entry = row.entry();
            ItemOptions.makeOptions(this, view)
                    .setScrimViewBackground(listView.getClipBackground(view))
                    .add(R.drawable.msg_delete, LocaleController.getString(R.string.EventScheduleClear), true, () -> confirmRemove(entry))
                    .show();
            return true;
        });

        emptyView = new TextView(context);
        emptyView.setText(LocaleController.getString(R.string.MessageTriggersEmpty));
        emptyView.setTextColor(Theme.getColor(Theme.key_emptyListPlaceholder));
        emptyView.setTextSize(15);
        emptyView.setGravity(Gravity.CENTER);
        emptyView.setPadding(AndroidUtilities.dp(24), AndroidUtilities.dp(24), AndroidUtilities.dp(24), AndroidUtilities.dp(24));
        frameLayout.addView(emptyView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        // NagramX: no reloadData() here -- onResume() runs right after this on initial presentation
        // (same as BookmarkManagerActivity), and calling it in both places would schedule two full
        // loads on first entry for nothing.

        return fragmentView;
    }

    @Override
    public void onResume() {
        super.onResume();
        reloadData();
    }

    private void openScheduled(long dialogId) {
        Bundle args = new Bundle();
        if (DialogObject.isEncryptedDialog(dialogId)) {
            args.putInt("enc_id", DialogObject.getEncryptedChatId(dialogId));
        } else if (DialogObject.isChatDialog(dialogId)) {
            args.putLong("chat_id", -dialogId);
        } else {
            args.putLong("user_id", dialogId);
        }
        args.putInt("chatMode", ChatActivity.MODE_SCHEDULED);
        presentFragment(new ChatActivity(args));
    }

    /**
     * A confirmation, not an Undo: round 2 found Undo can resurrect a trigger whose send already
     * completed. EventScheduleController.fire()'s success callback (EventScheduleController.java:
     * 588-599) checks EventScheduleStore.contains() immediately before removing the entry, and
     * re-persisting via armExisting() would put back stale serverIds this page can never fire
     * again -- exactly the failure class this page exists to expose. This page cannot tell
     * "removed before completion" and "completed before Undo" apart: both leave the entry absent
     * from the store with serverIds unchanged. Asking first, then only ever removing, sidesteps
     * that distinction instead of trying to make it.
     */
    private void confirmRemove(@NonNull EventScheduleEntry entry) {
        if (getParentActivity() == null) {
            return;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle(LocaleController.getString(R.string.EventScheduleClear));
        builder.setMessage(LocaleController.getString(R.string.MessageTriggersRemoveConfirm));
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        builder.setPositiveButton(LocaleController.getString(R.string.Remove), (dialog, which) -> removeEntry(entry));
        showDialog(builder.create());
    }

    /**
     * No Undo, so no re-persist path exists any more: whichever runs first between this and
     * fire()'s success callback, the entry ends up removed from the store and stays that way. The
     * bulletin only ever confirms a removal, never offers to reverse a send that may already be at
     * the server.
     */
    private void removeEntry(@NonNull EventScheduleEntry entry) {
        int account = getCurrentAccount();
        EventScheduleStore.remove(account, entry);
        // NagramX: strip the row now, synchronously -- reloadData() below is async (it hops
        // through the storage queue), and leaving a confirmed-removed row in place until it
        // returns would let it be tapped or long-pressed again during that gap.
        removeRowSynchronously(entry);
        reloadData();
        BulletinFactory.of(this).createSimpleBulletin(R.raw.ic_delete, LocaleController.getString(R.string.MessageTriggersRemoved)).show();
    }

    /**
     * Also drops the group's HeaderItem if this was its only row -- otherwise a lone header with
     * nothing under it would sit there until the async reload catches up.
     */
    private void removeRowSynchronously(@NonNull EventScheduleEntry entry) {
        int rowIndex = -1;
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i) instanceof RowItem row && row.entry() == entry) {
                rowIndex = i;
                break;
            }
        }
        if (rowIndex < 0) {
            return;
        }
        boolean onlyRowInGroup = (rowIndex == 0 || items.get(rowIndex - 1) instanceof HeaderItem)
                && (rowIndex == items.size() - 1 || !(items.get(rowIndex + 1) instanceof RowItem));
        items.remove(rowIndex);
        if (onlyRowInGroup && rowIndex > 0 && items.get(rowIndex - 1) instanceof HeaderItem) {
            items.remove(rowIndex - 1);
        }
        adapter.notifyDataSetChanged();
        updateEmptyView();
    }

    /**
     * serverIds non-empty is the only thing that means "this arm is genuinely live" -- the send ack
     * already landed. An unbound arm (serverIds empty) is either still binding or already dead (its
     * bind window expired), and the in-memory GC that would clean up a dead one runs on
     * AndroidUtilities.runOnUIThread(..., delay), which is backed by Handler.postDelayed and does
     * not run through deep sleep, while bindExpiresAt keeps counting on elapsedRealtime through it.
     * So after a long screen-off this page can render before that GC has caught up, and store
     * membership alone would show a dead arm as an ordinary armed row -- the exact lie this page
     * exists to end. Omit both the still-binding and the dead case; a message armed moments ago can
     * take a few seconds to appear here once its ack lands, which is expected.
     */
    // Package-private (not private) so MessageTriggersMenu's row-visibility gate can share this exact
    // definition instead of drifting from it -- see the class doc above for why store membership
    // alone is not enough.
    static boolean isLiveArm(@NonNull EventScheduleEntry entry) {
        return !entry.serverIds.isEmpty();
    }

    private void reloadData() {
        final int account = getCurrentAccount();
        final int requestId = ++loadRequestId;
        Utilities.globalQueue.postRunnable(() -> {
            // NagramX: this snapshot decides only which peers to preload. The rows themselves are
            // rebuilt from a *fresh* forAccount() read at the UI-thread callback below, not from
            // this list -- loadRequestId only rejects a stale *reload*, it says nothing about a
            // fire/removal/expiry that happens during this storage-queue hop, and reusing this
            // snapshot there would render a since-changed trigger as though nothing had happened.
            ArrayList<EventScheduleEntry> snapshotForPeers = new ArrayList<>();
            for (EventScheduleEntry entry : EventScheduleStore.forAccount(account)) {
                if (isLiveArm(entry)) {
                    snapshotForPeers.add(entry);
                }
            }
            MessagesController messagesController = MessagesController.getInstance(account);
            ArrayList<Long> usersToLoad = new ArrayList<>();
            ArrayList<Long> chatsToLoad = new ArrayList<>();
            HashSet<Long> seenUsers = new HashSet<>();
            HashSet<Long> seenChats = new HashSet<>();
            for (EventScheduleEntry entry : snapshotForPeers) {
                collectPeerToLoad(messagesController, entry.dialogId, usersToLoad, chatsToLoad, seenUsers, seenChats);
            }
            MessagesStorage messagesStorage = MessagesStorage.getInstance(account);
            messagesStorage.getStorageQueue().postRunnable(() -> {
                ArrayList<TLRPC.User> loadedUsers = new ArrayList<>(usersToLoad.size());
                ArrayList<TLRPC.Chat> loadedChats = new ArrayList<>(chatsToLoad.size());
                try {
                    if (!usersToLoad.isEmpty()) {
                        messagesStorage.getUsersInternal(usersToLoad, loadedUsers);
                    }
                    if (!chatsToLoad.isEmpty()) {
                        messagesStorage.getChatsInternal(TextUtils.join(",", chatsToLoad), loadedChats);
                    }
                } catch (Exception e) {
                    FileLog.e(e);
                }
                AndroidUtilities.runOnUIThread(() -> {
                    if (requestId != loadRequestId) {
                        return;
                    }
                    messagesController.putUsers(loadedUsers, true);
                    messagesController.putChats(loadedChats, true);
                    // NagramX: re-read the store here rather than reusing snapshotForPeers -- see
                    // the comment above it for why the pre-hop snapshot cannot be trusted for rows.
                    ArrayList<EventScheduleEntry> live = new ArrayList<>();
                    for (EventScheduleEntry entry : EventScheduleStore.forAccount(account)) {
                        if (isLiveArm(entry)) {
                            live.add(entry);
                        }
                    }
                    applyRows(messagesController, live);
                });
            });
        });
    }

    private void collectPeerToLoad(MessagesController messagesController, long dialogId, ArrayList<Long> usersToLoad, ArrayList<Long> chatsToLoad, HashSet<Long> seenUsers, HashSet<Long> seenChats) {
        long userDialogId = DialogObject.isEncryptedDialog(dialogId) ? encryptedChatUserDialogId(messagesController, dialogId) : dialogId;
        if (userDialogId != 0 && DialogObject.isUserDialog(userDialogId)) {
            TLRPC.User user = messagesController.getUser(userDialogId);
            if ((user == null || user.min) && seenUsers.add(userDialogId)) {
                usersToLoad.add(userDialogId);
            }
        } else if (DialogObject.isChatDialog(dialogId)) {
            long chatId = -dialogId;
            TLRPC.Chat chat = messagesController.getChat(chatId);
            if ((chat == null || chat.min || TextUtils.isEmpty(chat.title)) && seenChats.add(chatId)) {
                chatsToLoad.add(chatId);
            }
        }
    }

    // NagramX: a secret chat's dialogId resolves to neither isUserDialog nor isChatDialog -- the
    // real peer is the encrypted chat's other user, same pattern DialogCell uses for its avatar/title.
    private static long encryptedChatUserDialogId(MessagesController messagesController, long dialogId) {
        TLRPC.EncryptedChat encryptedChat = messagesController.getEncryptedChat(DialogObject.getEncryptedChatId(dialogId));
        return encryptedChat != null ? encryptedChat.user_id : 0;
    }

    private TLObject resolvePeer(MessagesController messagesController, long dialogId) {
        if (DialogObject.isEncryptedDialog(dialogId)) {
            long userDialogId = encryptedChatUserDialogId(messagesController, dialogId);
            return userDialogId != 0 ? messagesController.getUser(userDialogId) : null;
        } else if (DialogObject.isUserDialog(dialogId)) {
            return messagesController.getUser(dialogId);
        } else if (DialogObject.isChatDialog(dialogId)) {
            return messagesController.getChat(-dialogId);
        }
        return null;
    }

    @NonNull
    private String resolveTitle(TLObject peer) {
        if (peer instanceof TLRPC.Chat chat) {
            return chat.title != null ? chat.title : "";
        } else if (peer instanceof TLRPC.User user) {
            return UserObject.isUserSelf(user) ? LocaleController.getString(R.string.SavedMessages) : UserObject.getUserName(user);
        }
        return LocaleController.getString(R.string.HiddenName);
    }

    /**
     * Groups by (dialogId, triggerKey) -- EventScheduleController.queueKey() minus the account
     * term, which is constant on a single-account page, so this grouping is provably the engine's
     * own firing identity. A record key (not a delimiter-joined string) because triggerKey() embeds
     * the raw user-entered pattern, which could otherwise collide with a hand-picked delimiter.
     * Orders within a group with the engine's own QUEUE_ORDER rather than reimplementing it, and
     * orders groups by soonest fallbackDate.
     */
    private record GroupKey(long dialogId, String triggerKey) {
    }

    private void applyRows(MessagesController messagesController, ArrayList<EventScheduleEntry> live) {
        LinkedHashMap<GroupKey, ArrayList<EventScheduleEntry>> groups = new LinkedHashMap<>();
        for (EventScheduleEntry entry : live) {
            GroupKey key = new GroupKey(entry.dialogId, entry.triggerKey());
            ArrayList<EventScheduleEntry> group = groups.get(key);
            if (group == null) {
                group = new ArrayList<>();
                groups.put(key, group);
            }
            group.add(entry);
        }
        ArrayList<ArrayList<EventScheduleEntry>> groupList = new ArrayList<>(groups.values());
        for (ArrayList<EventScheduleEntry> group : groupList) {
            Collections.sort(group, EventScheduleController.QUEUE_ORDER);
        }
        groupList.sort((a, b) -> Integer.compare(a.get(0).fallbackDate, b.get(0).fallbackDate));

        long nowSeconds = System.currentTimeMillis() / 1000L;
        items.clear();
        for (ArrayList<EventScheduleEntry> group : groupList) {
            EventScheduleEntry first = group.get(0);
            TLObject peer = resolvePeer(messagesController, first.dialogId);
            String chatTitle = resolveTitle(peer);
            // triggerKey() guarantees every member of this group shares types/regex/pattern, so
            // any member's summary(false) speaks for the whole group -- that's what makes it safe
            // to show the trigger once here instead of repeating it on every row below.
            items.add(new HeaderItem(chatTitle, first.summary(false)));
            for (int i = 0; i < group.size(); i++) {
                EventScheduleEntry entry = group.get(i);
                CharSequence brief = resolveBrief(entry);
                CharSequence timeline = resolveTimeline(entry, nowSeconds);
                boolean divider = i < group.size() - 1;
                items.add(new RowItem(entry.dialogId, peer, brief, timeline, entry, divider));
            }
        }
        adapter.notifyDataSetChanged();
        updateEmptyView();
    }

    /**
     * NagramX: the batched (dialogId, messageId) preview lookup lands in a later commit; until
     * then -- and on any cache miss once it does -- this always reports "message unavailable". A
     * miss here must never hide the row or affect its liveness, which is decided only by
     * serverIds (see isLiveArm above).
     */
    private CharSequence resolveBrief(@NonNull EventScheduleEntry entry) {
        return LocaleController.getString(R.string.MessageTriggersUnavailable);
    }

    /**
     * delaySeconds is deliberately not part of triggerKey(), so it can differ between two entries
     * in the same header's group -- it has to stay per-row rather than moving into the once-per-
     * group chip.
     */
    @NonNull
    private CharSequence resolveTimeline(@NonNull EventScheduleEntry entry, long nowSeconds) {
        String time = entry.fallbackDate <= nowSeconds
                ? LocaleController.getString(R.string.MessageTriggersAnyMoment)
                : LocaleController.formatDateTime(entry.fallbackDate, true);
        if (entry.delaySeconds <= 0) {
            return time;
        }
        return "+" + entry.delaySeconds + "s \u00b7 " + time;
    }

    private void updateEmptyView() {
        if (emptyView == null) {
            return;
        }
        emptyView.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
    }

    /**
     * Chat name (GraySectionCell) plus this group's trigger, styled with the same rounded-rect
     * chip recipe EventScheduleHelper.addTriggerRow uses for the schedule sheet -- deliberately
     * not clickable, this page is read-only and per-row/chip editing is out of scope for it.
     */
    private static class TriggerGroupHeaderCell extends LinearLayout {
        private final GraySectionCell sectionCell;
        private final TextView chip;

        TriggerGroupHeaderCell(Context context) {
            super(context);
            setOrientation(VERTICAL);
            setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));

            sectionCell = new GraySectionCell(context);
            addView(sectionCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

            chip = new TextView(context);
            chip.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            chip.setPadding(AndroidUtilities.dp(12), AndroidUtilities.dp(5), AndroidUtilities.dp(12), AndroidUtilities.dp(5));
            chip.setMinHeight(AndroidUtilities.dp(28));
            chip.setMaxLines(3);
            chip.setEllipsize(TextUtils.TruncateAt.END);
            chip.setGravity(Gravity.CENTER);

            FrameLayout chipContainer = new FrameLayout(context);
            chipContainer.addView(chip, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL | (LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT), 16, 6, 16, 10));
            addView(chipContainer, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        }

        void bind(String chatTitle, CharSequence triggerSummary) {
            sectionCell.setText(chatTitle);
            int textColor = Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2);
            int backgroundColor = Theme.getColor(Theme.key_windowBackgroundGray);
            int chipBg = Theme.blendOver(backgroundColor, Theme.multAlpha(textColor, 0.075f));
            int chipSelector = Theme.multAlpha(textColor, 0.1f);
            chip.setTextColor(textColor);
            chip.setBackground(Theme.createSimpleSelectorRoundRectDrawable(AndroidUtilities.dp(14), chipBg, Theme.blendOver(chipBg, chipSelector)));
            chip.setText(triggerSummary);
        }
    }

    private static class ListAdapter extends RecyclerListView.SelectionAdapter {
        private static final int VIEW_TYPE_HEADER = 0;
        private static final int VIEW_TYPE_ROW = 1;

        private final Context context;
        private final ArrayList<ListItem> items;

        ListAdapter(Context context, ArrayList<ListItem> items) {
            this.context = context;
            this.items = items;
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            return holder.itemView instanceof UserCell;
        }

        @Override
        public int getItemViewType(int position) {
            return items.get(position) instanceof HeaderItem ? VIEW_TYPE_HEADER : VIEW_TYPE_ROW;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            if (viewType == VIEW_TYPE_HEADER) {
                return new RecyclerListView.Holder(new TriggerGroupHeaderCell(context));
            }
            return new RecyclerListView.Holder(new UserCell(context, 8, 0, false));
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            if (position < 0 || position >= items.size()) {
                return;
            }
            ListItem item = items.get(position);
            if (item instanceof HeaderItem header && holder.itemView instanceof TriggerGroupHeaderCell cell) {
                cell.bind(header.chatTitle(), header.triggerSummary());
            } else if (item instanceof RowItem row && holder.itemView instanceof UserCell cell) {
                cell.setData(row.peer(), row.brief(), row.timeline(), 0, row.divider());
            }
        }

        @Override
        public int getItemCount() {
            return items.size();
        }
    }
}
