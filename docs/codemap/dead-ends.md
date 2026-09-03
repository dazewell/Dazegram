# Dead ends

Hypotheses that were investigated and **disproven**, with the evidence that
killed them. Recorded so the next investigation doesn't spend time re-testing
a theory that's already dead. Re-verify the citation before relying on it —
see the README.

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

## "Rescheduling a message races an unreconciled placeholder"

Disproven, on reachability rather than on the race itself. An outgoing,
not-yet-reconciled message (`id <= 0`, not a send error) resolves to
`MESSAGE_TYPE_INVALID` in `ChatActivity.getMessageType`
(`ChatActivity.java:20537-20546`), and `processRowSelect` refuses to select
any row below `MESSAGE_TYPE_MEDIA` (`ChatActivity.java:21147-21154`) — so it
can't be routed to Reschedule through the UI in the first place. Had it
somehow reached `editMessage` anyway, the failure path shows an
`EditMessageError` alert (`AlertsCreator.java:450-457`); no such alert was
observed during the investigation that raised this theory, consistent with
the path never being reachable. See the matching latent-gap note in
`upstream-traps.md` (`canEditMessageScheduleTime` has no `id <= 0` guard) —
the reachability guard lives one layer up, in `ChatActivity`, not in that
method itself.

*(Established 2026-09-02.)*

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
