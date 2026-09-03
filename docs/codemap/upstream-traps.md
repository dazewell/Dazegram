# Upstream traps

Non-obvious behaviour in base-fork code that has already bitten someone.
What the trap is, where it lives, and what it costs if you miss it.
Re-verify the citation before relying on it — see the README.

## One `schedule_date` per forward batch

`TL_messages_forwardMessages` carries a single `schedule_date` field for the
**entire request**, set once on the request object
(`SendMessagesHelper.java:2628-2642`). Forwarding several messages at once
therefore lands every one of them on an identical scheduled timestamp — this
is the stock Telegram API request shape, not a fork defect. It is the root
cause of "I rescheduled one message and several others moved": the scheduled
list re-sorts around the resulting tie in `schedule_date`, which looks like
several messages moved together when only one send actually changed.
Distinct per-message times require distinct forward requests.

*(Established 2026-09-02.)*

## Forwarding aliases the source message's media object

The forward path assigns the new local placeholder's media straight from the
source message rather than copying it —
`newMsg.media = msgObj.messageOwner.media` (`SendMessagesHelper.java:2398`).
`updateMediaPaths`, which reconciles the placeholder once the server confirms
the send, then mutates that same `TLRPC.MessageMedia` object in place — see
the photo-size and live-photo writes at `SendMessagesHelper.java:8759-8760`
and `:8780-8782`. Because of the alias, those writes land on the **original
forwarded message's media too**, not just the copy's.

This is upstream code, not a fork addition. Filed as
[#267](https://github.com/dazewell/Dazegram/issues/267). The copy-send route
avoids it because it builds fresh media from a local path instead of aliasing
(`tw/nekomimi/nekogram/helpers/MessageHelper.createSendingMediaInfo`).

*(Established 2026-09-02.)*

## A shared `extension` variable leaks across a mixed document batch

`SendMessagesHelper.prepareSendingMedia`'s "send as documents" flush declares
one `extension` variable outside its per-item loop
(`SendMessagesHelper.java:10980`), reassigns it per item as each document is
queued (e.g. `:11534-11552`), and then passes its **final leftover value**
as the `mime` argument to every `prepareSendingDocumentInternal` call for the
whole batch (`SendMessagesHelper.java:11718`). A batch of documents with
different extensions therefore gets the wrong MIME type on most of its
members — and since audio-attribute extraction inside
`prepareSendingDocumentInternal` branches on the mime it's handed
(`SendMessagesHelper.java:9377-9396`), a mixed-extension album can also lose
audio attributes it should have kept.

*(Established 2026-09-02.)*

## Sending a captured album as documents can split it by media type

`prepareSendingDocumentInternal` partitions a `groupId` by media type: when
the current item's derived `docType` differs from the previous item's, it
calls `finishGroup` and rotates to a fresh `groupId`
(`SendMessagesHelper.java:9646-9650`, types assigned at `:9631-9645`). That's
correct behaviour for an arbitrary multi-file share where the caller wants
photos and non-previewable files kept in separate groups. It's **wrong** for
a captured source album being resent as documents, where group membership
should follow the original album rather than get re-split by type. Passing
`docType == null` for that call suppresses the rotation, since the guard at
`:9646-9650` requires a non-null `docType` to fire.

*(Established 2026-09-02.)*

## `canEditMessageScheduleTime` has no `id <= 0` guard

`MessageObject.canEditMessageScheduleTime`
(`MessageObject.java:11768-11783`) has no check on `message.id`, unlike its
siblings `canEditMessageAnytime` (`:11745-11766`, bails on `message.id < 0` at
`:11746`) and `canEditMessage` (starting `:11785`, same bail at `:11792`).

This is **not currently exploitable**: an outgoing message with `id <= 0`
that isn't a send-error resolves to `MESSAGE_TYPE_INVALID` in
`ChatActivity.getMessageType` (`ChatActivity.java:20537-20546`), and
`processRowSelect` refuses to select any row whose type is below
`MESSAGE_TYPE_MEDIA` — which `MESSAGE_TYPE_INVALID` (`-1`) is
(`ChatActivity.java:21147-21154`). The scheduled-message Reschedule path never
reaches a message in that state. Recorded here as a latent gap with its
shadowing guard so a future change to `getMessageType` or `processRowSelect`
doesn't silently reopen it.

*(Established 2026-09-02.)*
