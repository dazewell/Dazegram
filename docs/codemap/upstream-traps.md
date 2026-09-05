# Upstream traps

Non-obvious behaviour in base-fork code that has already bitten someone.
What the trap is, where it lives, and what it costs if you miss it.
Re-verify the citation before relying on it — see the README.

## Editing `TMessagesProj/build.gradle` fails Sync guard check until its blob pin is bumped

Any fork-authored PR that edits `TMessagesProj/build.gradle` — even far from
the signing block, anywhere in the file — fails the `Sync guard check`
workflow with `signing-config build.gradle blob changed: <blob>`, because that
file is blob-pinned whole, not diffed at the hunk level. `Test-SignerBlobs`
compares the candidate's git blob hash for the whole file against a single
recorded value and fails on any mismatch (`.github/sync/sync-guard.ps1:303-308`),
and the pin it checks against lives in `.github/sync/pins.env` as the
`SIGNING_GRADLE_PATH`/`SIGNING_GRADLE_BLOB` pair (guard 13, signing identity).
`GRADLE_SURFACE` in the same file documents `build.gradle` as membership-only
for every other executable-surface file, but `pins.env`'s own comment on that
line says `build.gradle` is the one exception, blob-pinned instead of just
tracked — easy to miss because it reads like the opposite of a warning.

The fix is a second, separate commit in the same PR: recompute the blob with
`git rev-parse HEAD:TMessagesProj/build.gradle` at the final tree (never
hand-copy a hash out of a CI log) and update `SIGNING_GRADLE_BLOB` in
`pins.env` to match. There's direct precedent for this exact shape of commit:
`da79971452` ("repin signing gradle blob for the icon-comment edit
#dazegram-icons"), a one-line `pins.env` bump in its own commit, done after an
unrelated `build.gradle` comment edit tripped the same guard.

*(Established 2026-09-05.)*

## `NaConfig.notificationIcon` is a persisted index into an upstream-owned enumeration that has already diverged

The setting and `getNotificationIconResId()` came from upstream Nagram commit
`bc0f99fb0b` ("feat: add option of changing notification icon", Revincx,
2023-01-19 — `git show bc0f99fb0b --stat` shows it touching
`NotificationsController.java`, `NekoGeneralSettingsActivity.java`, and
`NaConfig.kt`). That commit introduced `getNotificationIconResId()` as
`private` and non-static, with 3 cases (`case 0` → `offical_notification`,
`case 1` → `nagram_notification`, `case 2` → `notification`, per that
commit's diff). The method's signature never diverged — this fork's version
is still `private int` (`NotificationsController.java:6531-6544`) — only its
value domain did: this fork inserted `nagramx_notification` as a new `case 1`
and shifted upstream's `case 1`/`case 2` down to `case 2`/`case 3`, while
repointing `case 0` from upstream's `offical_notification` to this fork's own
`notification` asset. The backing config is `NaConfig.kt:256-260` (key
`"NotificationIcon"`, `configTypeInt`, default `1`), surfaced as 4 labels
(Telegram, NagramX, Nagram, NekoX) at `NekoGeneralSettingsActivity.java:228-232`.

The trap: **the same stored integer already means a different icon in the two
codebases.** A user's persisted `1` is upstream's `nagram_notification` but
this fork's `nagramx_notification` — already a silent divergence, tolerated
because the fork never re-merges upstream's notification-icon UI wholesale.
Appending a fifth fork-only value to this same enumeration would collide
silently with whatever upstream adds as its own next case, and any user who
had picked that value would see something else after the next reconciliation
merge, with no error and no migration to catch it. No later upstream commit
extending this domain has been verified, but nothing rules one out — extend
this behavior with a **new fork-owned `NaConfig` key** instead — one whose
value domain upstream has no way to write into — never by widening the value
domain of a key
upstream already owns and may extend.

*(Established 2026-09-05.)*

## `triggerKey()` is firing identity, queue bucket identity, and overview grouping identity

`EventScheduleController.queueKey(...)` composes queue identity as
`account + dialogId + triggerKey` (`EventScheduleController.java:120-125`), so
any change in `triggerKey()` repartitions the runtime send queues. The Message
Triggers screen groups rows by the same key (`MessageTriggersActivity.java:465-479`),
so it is also the overview grouping identity.

`EventScheduleEntry.triggerKey()` therefore has to stay order-independent and
injective for arbitrary user text. The current encoding canonicalizes the
normalized pattern set, sorts with `String` natural order, and length-prefixes
each element before concatenation (`EventScheduleEntry.java:163-174`). A
delimiter-only encoding or order-sensitive list key can collide unrelated
pattern sets, causing both wrong queue sharing and wrong UI grouping.

*(Established 2026-09-03.)*

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

## MessageDrawable's static motion background is a foreign invalidate producer

`MessageDrawable` keeps a static `MotionBackgroundDrawable[] motionBackground`
(`ActionBar/MessageDrawable.java:65`), enables `postInvalidateParent` on those
instances (`:231`, `:251`), and consumes them as a shader source
(`getBitmapShader` at `:256`) with bounds updates (`:286`) rather than drawing
that drawable as the chat wallpaper. In the drawable implementation,
`postInvalidateParent` posts the global `invalidateMotionBackground`
notification (`Components/MotionBackgroundDrawable.java:363`) and self-reposts
its own animation runnable every 16ms while active (`:372`).

That makes MessageDrawable a second app-wide producer of
`invalidateMotionBackground` events that are unrelated to the current chat
wallpaper motion. ChatActivity therefore has to ignore producer-mismatch events
for proxy recomposition (`ChatActivity.java:23831`) so those bubble-animation
ticks do not drive unnecessary wallpaper composite refreshes. ThemePreview's
observer branch remains arg-agnostic (`ThemePreviewActivity.java:3599`), so the
payload is safe for existing preview behavior.

*(Established 2026-09-04.)*

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
`ChatActivity.getMessageType` (`ChatActivity.java:20672-20696`), and
`processRowSelect` refuses to select any row whose type is below
`MESSAGE_TYPE_MEDIA` — which `MESSAGE_TYPE_INVALID` (`-1`) is
(`ChatActivity.java:21294`). The scheduled-message Reschedule path never
reaches a message in that state **for a directly-selected single message**;
the bulk path has a separate, unproven gap covered by its own entry below
("Bulk reschedule's album expansion bypasses the selection type check").
Recorded here as a latent gap with its shadowing guard so a future change to
`getMessageType` or `processRowSelect` doesn't silently reopen it.

*(Established 2026-09-02, citations refreshed 2026-09-03.)*

## `ShareAlert.darkTheme` (`= forCall`) is not VoIP-exclusive — it's just a misleading name

`ShareAlert.darkTheme` is assigned `= forCall` in the constructor
(`ShareAlert.java:452`). It has nothing to do with the app's light/dark theme
setting, but it is **not** exclusive to the VoIP call-invite screen either —
an earlier version of this entry claimed that, and it was wrong. Two callers
pass `forCall = true`: `GroupCallActivity.java:6722` (the VoIP group-call
invite share, with `copyLink2` non-null so `linkToCopy[1] != null`, reaching
the `dp(111)` header-height branch — not dead code) and
`PhotoViewer.java:8780` (ordinary photo/video sharing from the media viewer,
with `copyLink2 == null`, so it stays on the `dp(58)` branch despite
`forCall == true`). Every other `ShareAlert` construction site, including the
`ChatActivity` channel-post share arrow (`ChatActivity.java:42773`), passes
`forCall = false`.

The header-height ternary that appears throughout the file
(`dp(darkTheme && linkToCopy[1] != null ? 111 : 58)`, e.g.
`ShareAlert.java:1170`) branches on `darkTheme`, so a dark-looking screenshot
of the share sheet still tells you nothing about which branch is live — that
part holds. It just doesn't mean the caller is a VoIP screen; check the
actual constructor call and its `copyLink2` argument.

*(Established 2026-09-03.)*

## `allowSelectChildAtPosition`'s `y` is grid-local in the non-fullscreen case; adding `systemInsets.top` double-counts it there

`ShareAlert`'s `gridView` and `searchGridView` both override
`allowSelectChildAtPosition(x, y)` to gate taps below the header
(`ShareAlert.java:1168`, `:1253`).

**Non-fullscreen (`isFullscreen == false`) — the only case any current caller
reaches:** `containerView.onMeasure` sets `getPaddingTop()` to
`systemInsets.top`, gated by `if (!isFullscreen)` (`ShareAlert.java:699-703`),
and `onLayout` places every `Gravity.TOP` child, including the grid, at
`getPaddingTop() + topOffset` (`ShareAlert.java:855`). So in this case the
grid's `y` already has the status-bar inset netted out before the guard ever
runs, and adding `+ systemInsets.top` to the threshold double-counts it —
pushing the tap dead band down over the entire first avatar row, a silent
miss with no visual feedback that reads to a user as "the app ignored my tap"
rather than as an error.

**Fullscreen (`isFullscreen == true`):** the `setPadding` call above is
skipped entirely, so `getPaddingTop()` doesn't carry `systemInsets.top` and
the coordinate math differs from the case above. No current caller constructs
`ShareAlert` with `fullScreen = true` — checked every `new ShareAlert(...)`
and `ShareAlert.createShareAlert(...)` call site in the tree — so this branch
is presently unexercised. Worth knowing if a future caller ever does pass
`fullScreen = true`: the fix here was scoped to the reachable
(non-fullscreen) case only.

The identical `+ systemInsets.top` term is *correct* two hundred lines away,
in `containerView`'s own `onDraw` (`ShareAlert.java:928`, `:930`): there it
converts a grid-local `scrollOffsetY` into `containerView`'s own canvas space,
which is not padding-translated. NagramX's fix removes the extra term at both
`allowSelectChildAtPosition` call sites, leaving `y >= dp(...)` with nothing
added.

*(Established 2026-09-03.)*

## Vendored `update to <version>` commits are single-parent squashes, not merges

Commits like `37bd22c0f4` ("update to 12.7.0 (6740)") that bulk-vendor an
upstream Telegram release have a single parent (`628eabc372`) rather than
being a 3-way merge. A fork fix living in a file one of these commits
rewrites is therefore **silently overwritten with no merge conflict to flag
it** — there's nothing to alert the next vendoring pass that a line it's about
to replace was deliberately changed. `ShareAlert.java` alone has been
rewritten by six such bumps since 2025-11.

Concretely, for the exact hook this investigation touched
(`ShareAlert.java:1169-1170`): `37bd22c0f4`'s diff shows it *replacing*
`+ AndroidUtilities.statusBarHeight` with `+ systemInsets.top` in
`allowSelectChildAtPosition` — re-expressing an inset term that was already
there under a different API, not introducing one from a bare `dp(...)`
threshold. A vendoring commit rewriting a line doesn't announce whether it's
carrying a term forward, changing its source, or dropping fork-added
behaviour; only reading the actual diff tells you which. This is why a
one-token fork fix in a hot upstream file needs a `// NagramX:` comment
explaining the *why*: the comment is the only thing that survives to tell a
future investigator the line was intentional, since the diff itself won't.

*(Established 2026-09-03.)*

## Bulk reschedule's album expansion bypasses the selection type check

The single-message reschedule path is gated: `ChatActivity.getMessageType`
returns `MESSAGE_TYPE_INVALID` for a not-yet-reconciled outgoing message
(`id <= 0`, not a send error, `ChatActivity.java:20672-20696`), and
`processRowSelect` refuses to select anything below `MESSAGE_TYPE_MEDIA`
(`ChatActivity.java:21294`) — see the matching dead-end entry. The bulk
`RescheduleSpreadExecutor` path does **not** inherit that gate the same way.

`resolveRescheduleItems` picks an album's representative as the **minimum-id**
member of the group (`ChatActivity.java:37756-37762`,
`if (group.messages.get(k).getId() < first.getId()) first = ...`) — not the
message the user actually selected, and with no positivity check on that
comparison. A still-sending sibling carries a negative local id, which sorts
below every positive server id, so it can become `first` (and therefore
`target.id`) outright.

Separately, `EventScheduleBulkArmer.AlbumIdentity.of`
(`EventScheduleBulkArmer.java:79-91`) captures every `group.messages.get(k).getId()`
into `serverIds` (→ `RescheduleSpreadExecutor.Target.albumIds`) by iterating
the live group map directly, with no `getMessageType`/selectability check per
member — it only ever sees the *representative* that passed selection, not
each sibling. A non-positive sibling id can therefore land in `albumIds` even
when the representative itself is positive and was validly selected.

Reachability of either case through the shipped UI is **unproven either
way** — album sends have not been observed acking asynchronously enough to
leave one sibling negative while another is already positive — this is
recorded as a live gap, not a confirmed defect. `RescheduleSpreadExecutor.sendNext`
guards against both shapes directly (`target.id <= 0` and any non-positive
`target.albumIds` member) rather than relying on this selection-level gating,
since the gating above was never proven to reach this executor's inputs.

*(Established 2026-09-03.)*

## Release builds strip `Log.v` and `Log.d`

`TMessagesProj/proguard-rules.pro:173-176` has an `-assumenosideeffects` block
for `android.util.Log` that lists `v(...)` and `d(...)`, so R8 removes every
`Log.v` and `Log.d` call from the minified release variant. `Log.e`, `Log.i`
and `Log.w` are not listed and survive. Any diagnostic that has to appear on a
real device must use one of those three — a `Log.d` line compiles fine and then
emits nothing once installed.

The **local debug compile gate cannot catch this**:
`:TMessagesProj:compileDebugJavaWithJavac` builds the non-minified debug
variant, where the rule does not apply and `Log.d` works. The stripping only
happens in the minified release build that `staging.yml` produces — which is
the only variant that ever reaches a phone. So a `Log.d` diagnostic passes the
gate, passes CI, installs, and is silent, with nothing upstream of the device
to flag it.

Cost the `#repost-spread` instrumentation a full device test cycle: the
`NAX_SPREAD_DIAG` logging was written with `Log.d`, produced zero logcat output
on the installed staging APK, and had to be reissued at `Log.e`. Referenced by
[PR #270](https://github.com/dazewell/Dazegram/pull/270).

*(Established 2026-09-02.)*

## Notifications post from two independent builders — fixing one leaks the other

`NotificationsController` renders a message notification twice over: the account
**summary/group** is built in `showOrUpdateNotification` (the `mBuilder` InboxStyle
around `NotificationsController.java:4392-4423`, posted as `mainNotification` inside
`showExtraNotifications`), and each **per-dialog child** is built separately in
`showExtraNotifications` (`:4908`+). Both read real `pushMessages` content. Anything
that means to suppress a chat's real name/sender/text has to intercept **both**: a
child-only change still leaks every real line through the summary's InboxStyle. The
disguised-cover engine resolves the exact covered-dialog set in a **preflight** at the
top of `showOrUpdateNotification`, before any real identity/content is read
(`:4140`+), then threads that one immutable set + grouping into `showExtraNotifications`,
which builds a fresh generic summary instead of the real one when any covered dialog is
present (`naxBuildCoverSummary` at `:5878`) and swaps each covered child for a fresh
tagged builder, routing on `naxCoveredSet.contains(dialogId)` (`:5038`+).

## Popup notifications are a third leak surface, separate from summary + children

`popupMessages` is fed from `addToPopupMessages(...)` during new/edit processing,
outside the summary/child builder flow (`NotificationsController.java:947-977`,
`:1196-1200`, `:1253-1256`). A cover implementation that only swaps summary/child
notifications still leaks covered content through popup windows unless this path is
blocked too.

The hardened cover path now blocks covered members at source
(`NotificationCoverController.blocksPopupMessage(...)` in `addToPopupMessages`,
`NotificationsController.java:949-952`) and also purges already-queued popup rows in
the covered preflight pass (`NotificationsController.java:4194-4210`) so enabling
disguise mid-stream cannot leave stale covered popup cards behind.

*(Established 2026-09-03.)*

## `validateChannelId` observes/creates a chat-named OS channel as a side effect

`showExtraNotifications` calls `validateChannelId(lastDialogId, ...)` on the summary
builder (`NotificationsController.java:4939`), which synchronizes against — and can
create — a real per-dialog `NotificationChannel` named after the chat. Reusing it for
a covered chat would leave the chat's real name visible in Android Settings even though
the notification itself is disguised, so that call sits in the **non-covered branch
only**: when any dialog is covered the real summary is never built and this is never
reached. Cover channels are created directly (not through `validateChannelId`) so they
never adopt real-chat identity.

## `minSdk` is 27, so the `SDK_INT <= 19` notification branch is dead

`build.gradle:36` pins `minSdk = 27`. The `Build.VERSION.SDK_INT <= 19` early-return in
`showExtraNotifications` (`NotificationsController.java:4944`) and the other
`<= 19` guards never execute on a shipped build; don't spend effort covering them, and
treat `<= 27` conditions as "always true on the oldest supported device."

*(Established 2026-09-03.)*

## Process-global spoiler atlas publishes one full animation generation per pass

`SpoilerEffectBitmapFactory` is a process-global singleton atlas producer
(`SpoilerEffectBitmapFactory.java:26-47`), and `SpoilerEffect.draw` is the path
that submits active bounds into that producer (`SpoilerEffect.java:323,329`).
Text surfaces use this path through spoiler clip-out + draw in `SimpleTextView`
(`SimpleTextView.java:1210-1218,1247-1248`). Pinned-bar media thumbs use the
same path via the top-panel `BackupImageView` overlay's embedded
`SpoilerEffect` (`ChatActivity.java:12765-12791,30841-30843`). In-message media
particles are a separate renderer (`SpoilerEffect2`) in `ChatMessageCell`
(`ChatMessageCell.java:15399-15405`) and are not controlled here.

Current invariant: every accepted background publish is one full-atlas
generation. The update runnable now allocates background bitmap/canvas once,
then on each accepted pass erases the full bitmap (when reusing) and runs one
unconditional `doDraw(backgroundCanvas, fullRegion)` before publish copy and
UI-thread shader swap (`SpoilerEffectBitmapFactory.java:146-168`).

Why partial repaint cannot be coherent on this atlas: producer-side simulation
advances by cell intersection with the trigger union (`Rect.intersects` in
`SpoilerEffectBitmapFactory.doDraw`, `SpoilerEffectBitmapFactory.java:96-104`),
while particle admission is clip-relative with a damage margin
(`SpoilerEffect.java:342,369-372,464`). At the same time, `applyClip` maps view
bounds into wrapped atlas coordinates and unions wrapped segments
(`SpoilerEffectBitmapFactory.java:120-130`). Clipping rasterization to only a
subset of those intersected cells necessarily mixes generations across adjacent
texels and overlapping mapped bounds.

Cost facts that stay true regardless of clip strategy:
`Utilities.copyBitmaps(backgroundBitmap, nextBufferBitmap)` is already a full
bitmap copy on every accepted pass (`SpoilerEffectBitmapFactory.java:163`);
native implementation copies the full pixel payload (`image.cpp:1249-1334`,
contiguous path `memcpy(rowBytes * height)` at `image.cpp:1327`).
Full draw also already exists on first UI paint and on LiteMode restore
(`SpoilerEffectBitmapFactory.java:79,86`), so mechanism B aligns publish with
the existing full-generation paths instead of introducing a new one.

The dirty-union trigger drop is intentionally not fixed here. `checkUpdate` +
`applyClip` + `clipRegion` remain trigger-only (`SpoilerEffectBitmapFactory.java:112-130`),
and the callback still clears that union each frame (`SpoilerEffectBitmapFactory.java:136-139`).
With full-atlas publish, missed trigger unions may reduce temporal smoothness
but no longer produce spatially mixed generations.

Threading hazard to keep: `isRunning` is cleared only after the UI publish hop
sets `currentBitmapBuffer` and shader (`SpoilerEffectBitmapFactory.java:165-168`).
Clearing it earlier would allow a new pass to start writing while the previous
buffer index is still pending publication.

*(Established 2026-09-04.)*
