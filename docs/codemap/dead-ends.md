# Dead ends

Hypotheses that were investigated and **disproven**, with the evidence that
killed them. Recorded so the next investigation doesn't spend time re-testing
a theory that's already dead. Re-verify the citation before relying on it —
see the README.

## "Reuse `PollEditTextCell` for Send on event pattern rows"

Disproven. `PollEditTextCell` is a poll-specific, heavyweight composite with
emoji-button, checkbox animation, attach/move affordances, and poll-only
state machinery (`PollEditTextCell.java:63-89`). Pulling that class into the
event-schedule sheet would import upstream-fragile behavior and extra surface
the sheet does not need.

The `#eventschedule` editor instead reuses only the local rounded-field recipe
shape and builds a minimal row (`createPatternFieldRow`) in
`EventScheduleHelper.java:324-392`, then ports just the needed behavior
(IME-next/add/remove/focus transfer) at the sheet layer.

*(Established 2026-09-03.)*

## "Two independently-forwarded messages can coalesce into one group"

Disproven. `grouped_id` is only remapped onto a newly-sent message when the
**source** message already had a non-zero `grouped_id`
(`SendMessagesHelper.java:2498`). `MessageObject.getGroupIdForUse` is purely
field-driven — `localSentGroupId` if set, else `messageOwner.grouped_id`
(`MessageObject.java:7941-7942`) — with no date or adjacency fallback, and
`ChatActivity`'s group construction keys strictly on that value. The four call
sites that assign `localSentGroupId` either zero it
(`MessageSendPreview.java:1202`, `ChatActivity.java:10417`,
`ChatActivity.java:23105`) or copy an already-nonzero `grouped_id`
(`ChatActivity.java:24313`) — none of them can manufacture a shared group id
for messages that didn't already have one. Two originally-independent forwards
cannot merge into a group this way.

*(Established 2026-09-02.)*

## "The scheduled list groups messages by identical `schedule_date`"

Disproven. `MessageObject.GroupedMessages.calculate`
(`MessageObject.java:1326`, method start) lays out an already-formed group's
messages; it does not decide group membership from message dates, and no
date-based coalescing exists anywhere in the message-list build. Group
membership is `grouped_id`-only, per the entry above.

*(Established 2026-09-02.)*

## "Rescheduling a message races an unreconciled placeholder" — single-message path only

Disproven for the **single-message** reschedule/edit path, on reachability
rather than on the race itself. An outgoing, not-yet-reconciled message
(`id <= 0`, not a send error) resolves to `MESSAGE_TYPE_INVALID` in
`ChatActivity.getMessageType` (`ChatActivity.java:20672-20696`), and
`processRowSelect` refuses to select any row below `MESSAGE_TYPE_MEDIA`
(`ChatActivity.java:21294`) — so it can't be routed to Reschedule through the
UI in the first place for a directly-selected message. Had it somehow reached
`editMessage` anyway, the failure path shows an `EditMessageError` alert
(`AlertsCreator.java:450-457`); no such alert was observed during the
investigation that raised this theory, consistent with the path never being
reachable. See the matching latent-gap note in `upstream-traps.md`
(`canEditMessageScheduleTime` has no `id <= 0` guard) — the reachability guard
lives one layer up, in `ChatActivity`, not in that method itself.

This disproof does **not** extend to the bulk `RescheduleSpreadExecutor` path:
see "Bulk reschedule's album expansion bypasses the selection type check" in
`upstream-traps.md` for why a non-positive id can still reach that executor
through an album sibling, with reachability left unproven rather than
declared safe.

*(Established 2026-09-02, narrowed to single-message-only 2026-09-03.)*

## "A clickable child view swallowed the share-sheet avatar tap"

Disproven. `RecyclerListView.onInterceptTouchEvent` has a bail-out that skips
the row-select gesture when a clickable child sits under the tap point
(`RecyclerListView.java:1238-1248`), which is the obvious first guess for any
avatar-vs-label hit-test asymmetry. But neither `ShareDialogCell`'s avatar nor
its checkbox can trigger it: `BackupImageView extends View`
(`BackupImageView.java:38`) and `CheckBox2 extends View`
(`CheckBox2.java:18`), and neither sets `clickable`/`focusable` or overrides
`onTouchEvent`. `ShareDialogCell.java` itself has zero touch-handling methods,
and nothing under `org/telegram/ui` attaches a click listener to
`ShareDialogCell.getImageView()`. The real cause was the grid's
`allowSelectChildAtPosition` guard rejecting the tap outright — see
`upstream-traps.md`.

*(Established 2026-09-03.)*

## "The child-coordinate remap is wrong for the share sheet's multi-column grid"

Disproven. `RecyclerListView.onInterceptTouchEvent` remaps a touch into
child-local coordinates symmetrically on both axes —
`x = event.getX() - currentChildView.getLeft()`,
`y = event.getY() - currentChildView.getTop()` (`RecyclerListView.java:1234-1235`).
There's no column-index term in that remap, so it can't itself produce a
result that depends on which column (or row) was tapped. Not a source of the
top-row-only failure.

*(Established 2026-09-03.)*

## "A shared `#eventschedule` bulk trigger moves sibling messages when one is rescheduled"

Disproven. The untouched-trigger single-message edit path calls
`EventScheduleController.commitEditRefresh`, which delegates to
`EventScheduleStore.refreshFallbackForEdit`
(`EventScheduleController.java:545-559`) — that call writes only the edited
message's own `fallbackDate` and never issues an `editMessage` request for any
sibling id. The `scheduleRevision` counter it bumps on a real move
(`EventScheduleController.java:552-555`) is documented in the surrounding
comment as a process-local staleness token an in-flight **bulk arm** must
check against, not a mechanism that itself propagates a time change to other
messages (`EventScheduleController.java:526-544`). Arming the bulk trigger
also requires the user to explicitly open its controls (`armPending` /
`armExisting`, `EventScheduleController.java:308`, `:359`) — nothing arms it
implicitly as a side effect of an unrelated single-message edit.

*(Established 2026-09-02.)*
