# UI → code map

"When the user taps X, the code that runs is Y." Re-verify the citation
before relying on it — see the README.

## Chat privacy overflow row owns both per-chat privacy controls

The in-chat overflow menu now has one `Chat privacy` row (`nkheaderbtn_chat_privacy`)
that opens `ChatPrivacySheet.show(...)` (`org/telegram/ui/ChatActivity.java:498`,
`:5168`, `:48085-48086`).

Inside that sheet, `Hide last message` toggles
`HideLastMessageController.setHidden(...)`, and the `Placeholder text` value row
opens `HideLastMessageDialog.showPlaceholderEditor(...)` for Save/Cancel editing
(`com/radolyn/ayugram/chatprivacy/ChatPrivacySheet.java:90-99`, `:145-156`;
`com/radolyn/ayugram/hidelastmessage/HideLastMessageDialog.java:113-172`).

`Require password` state is read from the persisted lock flag via
`ChatLockController.isFlagged(...)` (not `isLocked(...)`), so a stored flag is
still shown when the global app passcode is absent
(`com/radolyn/ayugram/chatlock/ChatLockController.java:70-80`;
`com/radolyn/ayugram/chatprivacy/ChatPrivacySheet.java:101-124`, `:158-168`).
When turned on with a passcode present, the sheet keeps the existing one-way
coupling: it auto-enables hide only when hide was off, preserving a custom
placeholder, and shows the existing enabled bulletin (`ChatPrivacySheet.java:169-177`).

*(Established 2026-09-03.)*

## Chat privacy sheet's Notifications section drives disguised covers

The same sheet has a `Notifications` header, a `Disguise notifications`
`TextCheckCell`, and a `Cover` `TextSettingsCell` (visible only while disguise
is on). The switch toggles `NotificationCoverController.setEnabled(...)` and
queues a rebuild through `NotificationsController.getInstance(account).showNotifications()`;
the `Cover` row opens the reused single-select `PopupHelper.show(...)` radio
sheet and calls `setPersona(...)` + the same rebuild
(`com/radolyn/ayugram/chatprivacy/ChatPrivacySheet.java:61-90`, `:200-219`,
`:249-275`; `tw/nekomimi/nekogram/helpers/PopupHelper.java:32-54`). The UI never posts or
cancels a notification itself — it only writes config and asks the controller to
rebuild, matching the existing settings-write precedent.

Cover config is stored in the account's notifications `SharedPreferences`
(`MessagesController.getNotificationsSettings(account)`), keyed
`nax_cover_v1_enabled_<dialogId>` / `nax_cover_v1_persona_<dialogId>`, with lazy
generic channels under `nax_cover_v1_channel_<personaId>` /
`nax_cover_v1_summary_channel`
(`com/radolyn/ayugram/chatprivacy/NotificationCoverController.java:45-72`,
`:243-267`, `:682-710`).

*(Established 2026-09-03.)*

## Chat privacy sheet now owns tap behavior and preview for covered notifications

When `Disguise notifications` is on, the same sheet also shows `Tap action`
and `Preview notification` rows. `Tap action` opens the same single-select
picker pattern (`PopupHelper.show(...)`) and writes
`NotificationCoverController.setTapAction(...)`; `Preview notification` calls
`NotificationCoverController.postPreview(...)` and only shows a bulletin result
(`com/radolyn/ayugram/chatprivacy/ChatPrivacySheet.java:82-90`, `:143-160`,
`:221-240`, `:277-303`).

Cover interactions keep opaque token handling but now split transport by tap mode:
Hollow child taps, child dismiss, summary tap/dismiss, and preview tap stay on immutable
broadcast PendingIntents to `NotificationDismissReceiver`; Open chat child taps use an
immutable **activity** PendingIntent to `CoverInteractionActivity`, which calls
`NotificationCoverController.handleInteractionFromActivity(...)` and then routes through
the existing `OpenChatReceiver`/`LaunchActivity` open-chat path after token validation +
suppression commit
(`NotificationCoverController.java:752-772`, `:792-836`, `:907-934`, `:1060-1167`;
`com/radolyn/ayugram/chatprivacy/CoverInteractionActivity.java:13-33`;
`org/telegram/messenger/NotificationDismissReceiver.java:27-33`;
`org/telegram/messenger/OpenChatReceiver.java:42-46`;
`org/telegram/ui/LaunchActivity.java:1572-1579`, `:3012-3021`, `:3066-3075`).

*(Established 2026-09-03.)*

## Covered-chat open clear path writes suppression on notificationsQueue

`ChatActivity` clears covered members only after visibility/passcode gates:
`clearCoveredNotificationsIfVisible()` bails if the chat-lock overlay is still
shown or app passcode is pending, then calls
`NotificationsController.suppressVisibleCoveredDialog(dialog_id)` from
`onResume`, `onBecomeFullyVisible`, and the post-chat-lock-unlock callback
(`org/telegram/ui/ChatActivity.java:3755-3759`, `:3776-3784`, `:29244`,
`:32179`).

`suppressVisibleCoveredDialog(...)` then posts onto `notificationsQueue` and
runs suppression against the live push snapshot before rebuilding notifications
(`org/telegram/messenger/NotificationsController.java:3313-3318`;
`com/radolyn/ayugram/chatprivacy/NotificationCoverController.java:601-634`).

*(Established 2026-09-03.)*

## Selection bar left button ("NoQuote")

The button at the bottom-left of the message-selection action bar is the
**configurable left action button**, not an overflow menu item. It defaults
to the NoQuote forward action, present out of the box with no setting turned
on:

- Action constants: `ChatsHelper.LEFT_BUTTON_*`
  (`tw/nekomimi/nekogram/helpers/ChatsHelper.java:42-47`).
- Which action is active: `NaConfig.leftBottomButton`
  (`NaConfig.kt:1314-1319`, `LeftBottomButtonAction`), **default `0` =
  `LEFT_BUTTON_NOQUOTE`**.
- Label: `ChatsHelper.getLeftButtonText` (`ChatsHelper.java:91-99`) — the
  default case returns `NoQuoteForwardShort`.
- Click handling: `ChatsHelper.makeReplyButtonClick`
  (`ChatsHelper.java:134-167`). The `LEFT_BUTTON_NOQUOTE` case sets
  `ChatActivity.noForwardQuote = true` and calls `openForward(false)`
  (`ChatsHelper.java:157-166`).

**This is a different door from `nkbtn_forward_noquote`**, the item in the
selection *overflow* menu, gated by `NaConfig.showNoQuoteForward`
(`NaConfig.kt:171-176`, config key `NoQuoteForward`, **default `false`**).
Conflating the always-present left button with this off-by-default menu item
produced two wrong conclusions in one session before this was written down.

`LEFT_BUTTON_DIRECT_SHARE` is the only left-button action that reaches
`ShareAlert`, via `createShareAlertSelected`
(`ChatsHelper.java:142-144`). Forwarding through the NoQuote button does
**not** go through `ShareAlert`.

*(Established 2026-09-02.)*

## Single tap on a scheduled message

A single tap on a row in the scheduled-messages list opens the **message
context menu**, not selection mode. `ChatActivity`'s item click listener only
routes to row-selection (`processRowSelect`) when the action bar is already
showing selection mode; otherwise it falls through to `createMenu(view,
true, false, x, y, false)`
(`org/telegram/ui/ChatActivity.java:2053-2077`).

`createMenu` clears any previously-checked selection before building the menu
— it resets `selectedObject`/`selectedObjectGroup`/`forwardingMessage` and
empties `selectedMessagesIds`/`selectedMessagesCanCopyIds`/
`selectedMessagesCanStarIds` for both message-list slots
(`ChatActivity.java:33060-33070`). A stale multi-selection from before the tap
cannot leak into the single-message menu that opens.

The "Reschedule" item in that per-message menu is
`ChatActivity.OPTION_EDIT_SCHEDULE_TIME` (`ChatActivity.java:1403`, handled at
`:36713`), string `MessageScheduleEditTime`. It is a **different feature**
from the toolbar's bulk reschedule button below.

*(Established 2026-09-02.)*

## Long-press Send opens the schedule sheet via `ChatActivityEnterView`, not the forward picker

Long-pressing the message input bar's Send button in a chat opens the schedule
sheet through `ChatActivityEnterView.onSendLongClick`
(`ChatActivityEnterView.java:5564`), whose "Schedule Message" popup item calls
`AlertsCreator.createScheduleDatePickerDialog`
(`ChatActivityEnterView.java:5614`). That call enters the 4-arg overload at
`AlertsCreator.java:4388` and funnels through five more delegating overloads —
`:4408` → `:4412` → `:4416` → `:4427` → `:4438` — into the terminal
implementation at `:4445`, where every schedule sheet is actually built.

**This is a different door from long-pressing Send in the forward chat-picker
(`DialogsActivity`).** The picker has its own long-press-Send handler,
`DialogsActivity.onSendLongClick` (`DialogsActivity.java:12208`), whose own
"Schedule Message" item (`:12265-12281`) calls the *same* `AlertsCreator.java:4388`
entry overload directly from the picker's own `writeButton`. The two are easy to
conflate — both are "long-press Send, choose Schedule" from the user's point of
view — but they are wired to different widgets, and only one of them fires for
a given gesture.

**A forward to a single chosen chat is staged into that chat's own input bar,
not sent from the picker.** `ChatActivity.openForward`
(`ChatActivity.java:13645-13730`) presents `DialogsActivity` as a
`DIALOGS_TYPE_FORWARD` picker; selecting a single destination chat that isn't
already open calls back into `ChatActivity.didSelectDialogs`
(`ChatActivity.java:36966`), which — for the plain single-chat, no-comment,
not-scheduled case — opens a new `ChatActivity` for that dialog and calls
`showFieldPanelForForward(true, fmessages)` on it
(`ChatActivity.java:37112-37143`) instead of sending immediately. That queues
the forward into the new chat's own field panel, so the Send button the user
then long-presses belongs to the target chat's `ChatActivityEnterView`, not the
picker's `writeButton`. This is why the picker's own schedule path can look
correct in review — it compiles, it is wired to a real menu item — and still
never execute for this gesture: the picker has already closed and handed off
before the user reaches the button it owns.

**Measured, not inferred.** Instrumented build `3a55877cb1` (confirmed
installed as `org.telegram.messenger.beta`, `versionName=12.10.1-3a55877`,
with the diagnostic literals verified present in the pulled `base.apk`'s DEX)
was exercised by selecting messages, using the fork's left NoQuote button,
choosing a target chat, and long-pressing Send → Schedule. The only captured
`NAX_SPREAD_DIAG` output was a `Throwable` at the
`createScheduleDatePickerDialog` chokepoint whose top frames were
`ChatActivityEnterView.lambda$onSendLongClick$63` funnelling into
`AlertsCreator.createScheduleDatePickerDialog`. No forward-picker presentation
logged during that same run either, consistent with the picker path not being
the one exercised for this gesture.

**Why it matters:** a spread-interval feature was built and reviewed against
the picker's schedule path (`DialogsActivity`), passed two architect rounds and
three independent final-state reviews, and did not work on device — because
none of those reviews could establish which code path a real long-press-Send
gesture actually takes on this device. That requires a stack trace from an
installed build, not a reading of the diff.

*(Established 2026-09-02, PR #270.)*

## Forward picker (`DIALOGS_TYPE_FORWARD`) has six presentation sites

`ChatActivity` opens the forward chat-picker (`DialogsActivity` with
`dialogsType == DIALOGS_TYPE_FORWARD`) from **six** places, each building its
own argument `Bundle`:

- `:4013` — quote-reply picker (single message).
- `:5807` — reply-to-author quote picker (single message).
- `:12327` — `selectAnotherChat` (`:12300`), the forward **preview's** "select
  another chat". Multi-message; populates `selectedMessagesIds[0]` (`:12322`)
  and syncs `noForwardQuote = messagePreviewParams.hideForwardSendersName`
  (`:12306`). **This is the route reached via "Hide sender's name"** — that
  toggle is a preview control, so stock Forward + hide-sender lands here, not in
  `openForward`.
- `:13728` — `openForward` (`:13651`), the selection bar's Forward / left
  NoQuote button. Multi-message.
- `:35941` — context-menu single-message forward (`OPTION_FORWARD`); sets
  `forwardingMessage`.
- `:36267` — context-menu reply-to-author (`OPTION_REPLY`).

The `#repost-spread` spread-interval gate needs the forward slot count. It was
first threaded as a Bundle int written by **only `openForward`**, so the other
five presentations — `selectAnotherChat` included — reached the gate with the
count defaulting to 0 and the interval row never appeared. The durable lesson:
**adding a value to one presentation of a shared screen has to enumerate the
others.** It is now computed at gate time from the delegate's live selection
(`DialogsActivityDelegate.getForwardSpreadSlotCount`, overridden in
`ChatActivity`) using the same selection logic the dispatch forwards, so no
presentation can reach the gate with a stale count.

*(Established 2026-09-02, PR #270.)*

## Channel post share arrow: two different code paths for "quick share sheet"

A single tap on the share arrow under a channel post opens
`ChatMessageCell.Delegate.didPressSideButton` (`ChatActivity.java:42733`), which
constructs `new ShareAlert(...)` (`ChatActivity.java:42773`) with `fullScreen`
and `forCall` both `false`. This is the bottom sheet with a "Send to..." search
field, a 4-column avatar grid, and a COPY LINK footer.

A **long-press-and-drag** on the same arrow is a completely different widget:
`didQuickShareStart` (`ChatActivity.java:42652`) opens
`QuickShareSelectorOverlayLayout` (`org/telegram/ui/Components/quickforward/`),
a hand-drawn popup with its own bespoke hit-testing. It shares no code with
`ShareAlert`.

A bug report describing "the quick share sheet" can mean either, and recon
cannot tell which from the report alone — the two have no code in common, so
guessing wrong burns a whole investigation cycle in the wrong files. The
distinguishing marks: `ShareAlert` has the search field and COPY LINK footer;
`QuickShareSelectorOverlayLayout` does not.

*(Established 2026-09-03.)*

## Bulk reschedule toolbar button

The fork's selection-toolbar "Reschedule" button (`nkactionbarbtn_reschedule`,
`ChatActivity.java:493`) only shows in scheduled-message mode with at least
one row checked (`ChatActivity.java:11492`). Its click handler calls
`performRescheduleSpreadSelectedMessages()` (`ChatActivity.java:4341-4342`),
which collects **every currently-selected id across both message-list slots**
(`ChatActivity.java:37447-37454`) before building the reschedule preview. It
operates on the whole live checkbox set, unlike the single-message
`OPTION_EDIT_SCHEDULE_TIME` above, which only ever touches the one message the
menu was opened on.

*(Established 2026-09-02.)*
