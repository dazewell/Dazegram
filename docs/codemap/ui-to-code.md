# UI → code map

"When the user taps X, the code that runs is Y." Re-verify the citation
before relying on it — see the README.

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
five presentations reached the gate with the count defaulting to 0 and the
interval row never appeared — the feature shipped working through one of six
doors. The durable lesson: **adding a value to one presentation of a shared
screen has to enumerate the others.** It is now computed at gate time from the
delegate's live selection (`DialogsActivityDelegate.getForwardSpreadSlotCount`,
overridden at `ChatActivity.java:37073`) using the same selection logic the
dispatch forwards, so no presentation can reach the gate with a stale count.

*(Established 2026-09-02, PR #270.)*

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
