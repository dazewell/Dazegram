# Upstream traps

Non-obvious behaviour in base-fork code that has already bitten someone.
What the trap is, where it lives, and what it costs if you miss it.
Re-verify the citation before relying on it — see the README.

## SectionsScrollView sectioning differs from RecyclerListView defaults

`SectionsScrollView.isSectionView(...)` excludes only views tagged
`RecyclerListView.TAG_NOT_SECTION` plus `TextInfoPrivacyCell`,
`ShadowSectionCell`, and `HintInnerCell` (`SectionsScrollView.java:36-41`).
Unlike `RecyclerListView`'s section checker, it does **not** auto-exclude
`CollapseTextCell` or `GraySectionCell`
(`RecyclerListView.java:3289`). So disclosure/header rows that should stay on
gray must be explicitly tagged `TAG_NOT_SECTION`; otherwise they get pulled
into white rounded cards.

Card width also comes from the first child in a section run: background bounds
use `from` child X/width (`SectionsScrollView.java:163-168`). If the first
section member has horizontal margins (for example a caption row with 21dp
insets), that narrower width becomes the whole card width. Margined captions
belong outside the card run (tagged out, or moved outside the grouped container)
to keep card edges aligned.

There is a known neighbour-visibility gap in `clipChild(...)`, but it is
**bounded and cosmetic-only** — it cannot produce a full-card clip. Child
gathering used for backgrounds skips `GONE` children
(`SectionsScrollView.java:107`), but `clipChild`'s own prev/next lookup reads
`contentView`'s full child list by raw index with no visibility gate
(`SectionsScrollView.java:190-194`). That gap does not touch the clip
rectangle itself: `AndroidUtilities.rectTmp` is computed once
(`SectionsScrollView.java:196-201`) and is byte-identical across all three
branches `clipChild` can take with it (`SectionsScrollView.java:202-219`).
`prev`/`next` decide only whether to skip clipping entirely
(the early return at `:205`) or which corners get rounded — bounded by the
16dp `sectionRadius`. A hidden neighbour also does not reliably push toward
MORE clipping: `isSectionView` (`:36-41`) returns `true` for a `GONE` view, so
the missing gate pushes `prev`/`next` MORE often true, which fires the no-clip
early return (`:205`) MORE often — but that early return can itself leave
either rounded corners where square ones were expected, or vice versa,
depending on which of the three branches (`:207-219`) would otherwise have
run. Don't assert a direction; the safe, established claim is only that the
effect is bounded to corner treatment and cannot erase a child.

*(Established 2026-09-07; corrected 2026-09-09, `#eventschedule`, after a
near-total vertical clip bug — a different mechanism entirely, below — was
first suspected to be this same gap and proven not to be.)*

## SectionsLinearLayout replays a stale clip after its parent SectionsScrollView resizes

`SectionsLinearLayout.drawChild` clips each child via the parent
`SectionsScrollView.clipChild(...)` and records that `canvas.clipPath(...)`
into the LinearLayout's OWN display list (`SectionsScrollView.java:228-235`
calling into `:185-220`) — but the clip rectangle's operands are the PARENT
`SectionsScrollView`'s `getScrollY()`/`getHeight()`
(`SectionsScrollView.java:196-200`). `onScrollChanged` already invalidates
both the scroll view and `contentView` when scroll changes
(`SectionsScrollView.java:79-87`) — that idiom covered the scroll operand but,
until this fix, nothing invalidated `contentView` when the PARENT's SIZE
changed instead. **This only bites when the content view's OWN frame stays
unchanged across that resize** — the scroll view has `isFillViewport = true`
(`BottomBuilder.kt:60`), so content SHORTER than the viewport is stretched to
fill it and gets dirtied normally by that layout pass; the hazard is specific
to content TALLER than the keyboard-open viewport, which fillViewport leaves
alone. Fixed by adding the missing half of the same idiom in an
`onSizeChanged` override (`SectionsScrollView.java:90-100`).

Observable signature, so a future investigation recognises it fast: the
card's white background is painted full-height (fresh, from the ScrollView's
own re-recorded `dispatchDraw`/`drawSectionsBackgrounds`) while the card's
CONTENTS are cut mid-row (stale, from the child's cached clip). The
draw-path trace above — the stale display list, the operands it was
recorded with, the missing invalidation — is what actually establishes the
diagnosis, independent of any touch. Separately, interacting with the
affected area was OBSERVED to repair it; that observation is consistent with
the diagnosis but doesn't itself prove no layout occurred, since the SeekBar
this card holds runs its own delegate on drag that updates a label and can
invalidate or re-lay-out itself (`EventScheduleHelper.java:1003-1012`) — a
generic "any touch invalidates the child" claim is not established. Confirmed
trigger: the Early-send bottom sheet's Delay card
(`EventScheduleHelper.java:966-1033`, reached via the only `sections=true`
`BottomBuilder` consumer in the tree, `EventScheduleHelper.java:656`) clipped
almost fully invisible after the IME hid and resized the sheet — the resize
mode is declared at `BottomSheet.java:218`, measured at `:591-657`, and
applied at `:1474-1476` and `:1570-1572` — while two pattern rows had pushed
the card below the keyboard-open viewport bottom.

*(Established 2026-09-09, `#eventschedule`.)*

## clipChild has no inverted-rect guard, unlike its background-drawing sibling

`drawSectionBackground` guards against an inverted `rectTmp` before using it
(`SectionsScrollView.java:169`: `if (rectTmp.bottom < rectTmp.top) return;`),
but `clipChild` builds the same kind of rect and hands it straight to
`Path.addRoundRect`/`canvas.clipPath` with no equivalent check
(`SectionsScrollView.java:196-219`). An inverted
rect there yields an empty `Path`, and clipping to an empty path erases the
child entirely rather than just mis-rounding a corner. Not the mechanism
behind the near-total-clip bug above (that one traced to a stale cached
clip, not an inverted rect computed at draw time) and not fixed here — scoped
out of that change by the reviewing architect as a separate, pre-existing
hazard.

*(Established 2026-09-09, `#eventschedule`; pre-existing and not fixed in
this branch.)*

## An app can never change an existing notification channel's importance in code; only the user can, via system settings

`NotificationCoverController.ensureChannel(...)` still no-ops when the channel
id already exists (`NotificationCoverController.java:639-651`), because
Android treats re-creating an existing `NotificationChannel` id as a silent
no-op. There is no app-side API to raise or lower that existing channel's
importance in place - only the user can do that in Android notification
settings.

That is why the cover path still keeps two channel tiers per persona instead
of one mutable tier: `childChannelId(...)` (silent/low) and
`alertChannelId(...)` (alert/default+vibration)
(`NotificationCoverController.java:655-715`). The behavior change moved the
choice source, not the channel model: `postChild(...)` now routes between those
two existing tiers from upstream's silent signal (`silent` parameter) rather
than from a per-chat fork toggle (`NotificationCoverController.java:719-784`).
`postPreview(...)` is user-initiated and always uses the alert tier
(`NotificationCoverController.java:836-885`), while `NaxCoverAlertChannelName`
remains the alert-tier label in settings (`strings_nax.xml`).

*(Established 2026-09-07, `#disguise-alerting`.)*

## `GROUP_ALERT_SUMMARY` still mutes grouped children; non-silent covered events must skip it

`useSummaryNotification` is still the same gate in
`NotificationsController.showExtraNotifications(...)`
(`NotificationsController.java:4993`): grouped notifications are common once
multiple dialogs are pending. In that state, `GROUP_ALERT_SUMMARY` still mutes
the child regardless of the child's own channel importance. The new cover path
therefore applies `setGroupAlertBehavior(GROUP_ALERT_SUMMARY)` only when the
upstream signal is silent, and skips it when upstream says the event is
non-silent (`NotificationCoverController.java:761-765`). If that line is
applied unconditionally in the non-silent branch, alert-tier covered children
become inert as soon as grouping turns on.

*(Established 2026-09-07, `#disguise-alerting`.)*

## `isSilent` at `showExtraNotifications(...)` is rebuild-scoped, not a per-dialog covered flag

The `isSilent` argument passed into `showExtraNotifications(...)` is the
upstream rebuild-level suppression result (`notifyDisabled`) from
`showOrUpdateNotification(...)`, not a per-dialog covered-message property
(`NotificationsController.java:4505`, `:4912`, `:4987`). Reusing it unchanged
for every covered child can spread the newest dialog's suppression state across
unrelated covered dialogs; ignoring it entirely can re-enable suppressed rebuild
paths.

Current fix splits scopes explicitly: preflight captures a rebuild-wide flag
(`naxRebuildSuppressed`) and a read-only per-covered-dialog suppression map
(`naxCoverSuppressed` via `naxCoveredDialogSuppressed(...)`), then fanout composes
`coverSilent = naxRebuildSuppressed || naxDialogSuppressed == null || naxDialogSuppressed || (dialogId == lastDialogId && isSilent)`
(`NotificationsController.java:4193-4202`, `:5116-5117`, `:6001-6029`). This keeps fail-closed
behavior for missing map entries and reuses upstream's method-level suppression
for the one `lastDialogId` it actually describes, without mutating
`smartNotificationsDialogs`.

One more trap exists after that composition: a rebuild can repost the same
represented covered members again. Using token snapshots as membership state is
wrong here: they are capped at `SUPPRESSION_LIMIT` by `buildRecord(...)`
(`NotificationCoverController.java:972-995`) and therefore cannot represent an
exact membership baseline for growth decisions.

Current fix splits those roles. Exact per-dialog active membership is stored on
its own key namespace (`KEY_ACTIVE_CHILD_MEMBERS`) with an explicit
`over_capacity` sentinel for `displayCount > SUPPRESSION_LIMIT`, while token
snapshots stay interaction payload only (`NotificationCoverController.java:65`,
`:561-562`, `:1085-1134`). Child alert tier is then gated by
`effectiveSilent = upstreamSilent || migration || overCapacity || !hasGrowth` in
`postChild(...)` (`NotificationCoverController.java:758-778`): unchanged reposts
force silent, growth can alert only when upstream also allows it.

Migration trap: branches without a stored membership key but with an already
live cover token must not infer growth. That case is forced silent once and
seeds baseline only after successful post + CAS (`NotificationCoverController.java:767-770`,
`:803-811`).

Ordering trap: token records are written before `notify(...)`, but membership
baseline is written after `notify(...)` and only when active tap pointer still
matches this post token (CAS), so a concurrent interaction or newer post cannot
be overwritten by stale baseline state (`NotificationCoverController.java:767-776`,
`:803-811`).

Cleanup trap: membership key lifecycle is owned by
`clearDialogInteractionState(...)`; reconcile must scan membership-prefix keys
or orphan baselines can survive after a dialog no longer posts a cover
(`NotificationCoverController.java:1278-1282`, `:1341-1343`, `:1356`).

Android can drop notifications at global count limits without throwing from
`notify(...)`. This path therefore only treats Java exceptions as hard post
failures; absence of an exception is not a proof that Android displayed the new
card.

*(Established 2026-09-07, `#disguise-alerting`.)*

## Pre-existing (out of scope): mixed covered + uncovered batches can inherit the silent cover-summary alert behavior

This branch still has a pre-existing mixed-batch trap that this change does not
fix. When any covered dialog is present, `showExtraNotifications(...)` posts
the fork cover summary instead of the real summary (`NotificationsController.java:5016-5030`),
and that cover summary is always built on the low/silent cover-summary channel
(`NotificationCoverController.buildCoverSummary(...)` ->
`summaryChannelId(...)` -> `ensureChannel(...)`,
`NotificationCoverController.java:639-651, 667-675, 785-835`).

At the same time, non-covered child notifications in a grouped batch still set
`GROUP_ALERT_SUMMARY` in the regular child path
(`NotificationsController.java:5830`). So in a mixed covered/uncovered
grouped batch, an uncovered child that would otherwise alert can be muted by
the grouped-summary policy it shares with the silent cover summary. This is
recorded as pre-existing and out of scope for this tuning slice.

*(Established 2026-09-07, pre-existing and not fixed in this branch.)*

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
value domain did. Upstream's three cases were `0` → `offical_notification`,
`1` → `nagram_notification`, `2` → `notification`, defaulting to
`offical_notification`. This fork's four are `0` → `notification`, `1` →
`nagramx_notification`, `2` → `nagram_notification`, `3` → `neko_notification`,
defaulting to `notification`. The divergence is therefore not a uniform
shift: `offical_notification` left the domain entirely; upstream's `case 2`
asset (`notification`) became this fork's `case 0` and its default; only
upstream's `case 1` asset (`nagram_notification`) moved down a slot, to
`case 2`; and `nagramx_notification` (`case 1`) and `neko_notification`
(`case 3`) are both new here. The backing config is
`TMessagesProj/src/main/kotlin/xyz/nextalone/nagram/NaConfig.kt:256-260` (key
`"NotificationIcon"`, `configTypeInt`, default `1`), surfaced as 4 labels
(Telegram, NagramX, Nagram, NekoX) at `NekoGeneralSettingsActivity.java:228-232`.

The trap: **the same stored integer already means a different icon in the two
codebases.** A user's persisted `1` is upstream's `nagram_notification` but
this fork's `nagramx_notification` — already a silent divergence, tolerated
because the fork never re-merges upstream's notification-icon UI wholesale.
The exposure is not conditional on this fork adding anything further. As
verified, upstream's domain ends at `case 2`, so the next value upstream
appends would be `case 3` — which this fork already uses for
`neko_notification`. A user who had picked NekoX would then silently get
upstream's new icon after a reconciliation merge, with no error and no
migration to catch it. Appending further fork-only values (`case 4` and
beyond) only widens the overlap. No later upstream commit
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

## The `transtale` -> `translate` package rename means upstream `transtale/*` changes must be ported, and upstream callers of `transtale` symbols silently fail to compile

Fork commit `0887abcd02` ("chore: fix typos & optimize imports") renamed the
translation package `tw.nekomimi.nekogram.transtale` ->
`tw.nekomimi.nekogram.translate` (`git show --stat 0887abcd02` shows the git
rename explicitly as `.../nekogram/{transtale => translate}/Translator.kt`).
Our live translator is
`TMessagesProj/src/main/java/tw/nekomimi/nekogram/translate/Translator.kt`; the
old `transtale/Translator.kt` path no longer exists on `dev`. So any upstream
change to a file under `transtale/` arrives as a modify/delete conflict
(deleted on our side), and any upstream code that *calls* a symbol added under
`transtale/` will not resolve against our tree until the symbol is ported into
`translate/` by hand.

This bit the 2026-09-06 sync of `NextAlone/Nagram` onto anchor
`b03d83df87`: upstream added `Translator.translateShowAlert(...)` to its
`transtale/Translator.kt` and called it from a bot-button long-press menu at
`981806a992:TMessagesProj/src/main/java/org/telegram/ui/ChatActivity.java:41468`
(the immutable sync snapshot -- cite that, not the moving `nagram/dev` ref,
which drifts on every upstream push and rots the line number).
Because the merge produced no conflict at that call site (it landed inside a
`ChatActivity` region that did conflict, but the call itself was conflict-free
text), taking upstream's `ChatActivity` hunk without porting the helper would
have pushed a `dev` that does not compile -- the same silent non-compile class
`.github/sync/pins.env` records from the 12.10.1 sync. The fix was to port
`translateShowAlert` into `translate/Translator.kt`, adapted to the fork's
reworked translate API (no `TranslateDb.currentTarget` cache; `AlertUtil`
progress/copy/failure dialogs), and take the `ChatActivity` call.

The standing rule this leaves for every future sync: whenever upstream touches
a `transtale/*` symbol, compare the incoming change against the candidate's
`translate/*` implementation **and its callers**, and port anything missing.
Don't lean on a one-shot presence check like `git grep translateShowAlert
origin/dev` -- that grep succeeds forever once this PR lands and would falsely
reassure even if a *later* upstream change to the same helper went unported.
The failure is silent at merge time (upstream callers of `transtale` symbols
compile against upstream but not against our renamed tree), so it only surfaces
at the compile gate; that is the check to trust, not a grep.

*(Established 2026-09-06, during the NextAlone/Nagram sync reconciliation, snapshot `981806a992`.)*

## The aggregated group summary notification bridges every pushed chat's message text to Wear, even when a chat's own child notification is `setLocalOnly`

`NotificationsController.showExtraNotifications(...)` posts one per-dialog child
notification per chat plus, when `useSummaryNotification` is true — API 27
(`O_MR1`) and below unconditionally, otherwise only when more than one non-story
message dialog is pushed (`sortedDialogs.size() > (storyPushMessages.isEmpty() ? 1 : 2)`,
`NotificationsController.java:4958`) —
a single aggregate summary built from `notificationBuilder` — the `mBuilder`
first assembled back in `showOrUpdateNotification`. That summary's `InboxStyle`
is not a count: it adds up to 10 lines of real `getStringForMessage(...)` output
— sender names and message text drawn from every pushed dialog
(`NotificationsController.java:4443`) — and it is **never** given `setLocalOnly`.
The trap: giving a per-dialog child `setLocalOnly(true)` (encrypted chats have
always done this, `:5834`; the `#wear-messages` per-chat "Show on Watch" toggle
does it at `:5838`) does **not** stop that chat's text riding to a paired Wear OS
watch, because the *summary* is a separate `Notification` without `setLocalOnly`.
So a per-chat "keep this off the watch" control governs the child but not the
shared summary; whenever Android builds that summary it can still preview a
switched-off chat on the watch — and on API 27 and below it builds one even for a
single unread chat, so a lone switched-off chat is not fully hidden there either.

This is upstream behaviour, and it is exactly how Telegram already treats secret
chats: their child is local-only (`:5834`) while the aggregate summary that
previews them is not. **`#wear-messages` deliberately does not fix it** — the
shipped feature is the two per-dialog hooks only (child `setLocalOnly` at the
shared builder chokepoint `:5838`, and the disguised-cover child card in
`NotificationCoverController.postChild:734`). The accepted, product-approved
consequence is the summary leak above. Three cheaper summary fixes were tried
across review and rejected, so do not re-derive them:

- **Gate the whole summary local-only when any pushed chat is watch-off** (an
  early revision's batch-wide `naxAnyWatchOff` scan). Wrong: the fork's disguised
  "cover" children still use `GROUP_ALERT_SUMMARY` on the upstream-silent path
  (`NotificationCoverController.java:724-799`), and older builds routed every
  covered child through that same silent grouped path; blanking the summary
  therefore silenced watch *alerting* for watch-on chats in those grouped
  cases the moment one unrelated chat was switched off — a real on-device
  regression with "Disguise notification" on.
- **Re-arm the children with `GROUP_ALERT_CHILDREN`** so they alert without the
  summary. Dead: children fall back to `OTHER_NOTIFICATIONS_CHANNEL`, which is
  created with sound/vibration/lights disabled (`:292-296`), so flipping alert
  ownership to the children mutes everything anyway.
- **Redact the summary content** to a safe-representative form. Viable but not
  bought: it needs a safe representative across both the `InboxStyle` branch and
  the non-inbox branch, plus title, ticker, person, actions, channel and the Wear
  dismissal id — and Samsung skips the inbox summary path entirely (`allowSummary`
  false, `:4366-4367`), so the redaction would have to cover a second
  construction path too. Deferred by product decision, not overlooked.

Eliminating the leak properly needs a different grouping/summary design. Until
then, the per-chat hooks are honest about what they do — they keep a chat's *own*
notification off the watch — and `FEATURES.md` states the summary limitation
plainly rather than implying full suppression.

## Two independent "any overlap hides the Quote button" gates, one of them a private upstream method

`EditTextBoldCursor.shouldShowQuoteButton()` (`EditTextBoldCursor.java:1213-1236`, private, pure
upstream — git log shows only version-bump commits, zero `// NagramX:` markers before this entry)
decides whether the platform's own text-selection popup (`FloatingToolbar`, wired via
`floatingToolbar.setQuoteShowVisible(this::shouldShowQuoteButton)`, `EditTextBoldCursor.java:1194`)
shows a Quote item at all. Before `#toggle-formatting` it hid the item the moment *any*
`QuoteSpan.QuoteStyleSpan` overlapped the selection, so a quote could be added but never removed
from that surface. `ComposerFormattingActions.isQuoteAvailable(Editable,int,int)`
(`ComposerFormattingActions.java:229-241`, fork-owned) enables/disables the glass composer
toolbar's Quote button on the *identical* condition, independently. Both had to be relaxed in the
same change to make quote toggle-off reachable outside the header overflow menu and the
`Ctrl+Shift+.` hotkey (the two paths that already called `makeSelectedQuote()` directly): now
enabled/shown when there's no overlap (add) OR the selection exactly contains one quote block
(remove), still hidden/disabled only for a partial/non-containing overlap. The
`EditTextBoldCursor.java` edit is the one sanctioned upstream-file touch for this change — everything
else lives in `EditTextCaption.java` and the fork-owned `ComposerFormattingActions.java`.

*(Established 2026-09-06, `#toggle-formatting`.)*

Key-format note for a future edit in this area: `WearBridgeHelper` owns the
`nax_wear_<dialogId>` format, but `ProfileNotificationsActivity` repeats the
`"nax_wear_" + dialogId` literal inline for both its read and its write, so a
rename of the key must touch the settings screen too, not just the helper.

*(Established 2026-09-06, during the #wear-messages build; three review rounds on the summary region ended by dropping all summary suppression to the two per-chat hooks only.)*

## `VideoEditedInfo`'s serialised form round-trips on every send, and `parseString` infers `muted` from `bitrate == -1`

The `ve` string is not just a draft/restore mechanism -- the full
serialise/parse cycle runs on the ordinary send path:
`SendMessagesHelper.java:4824` -> `:4969` -> `:5269` -> `MessageObject.java:4473`
-> `MediaController.java:6846`. Crucially `parseString` derives `muted` from the
legacy bitrate slot (`VideoEditedInfo.java:590-599`) rather than storing it, so
any field added to the format is subject to that inference on every send, not
only on restore. Miss this and a change that sets `muted` in memory while also
writing a real bitrate will have the mute silently discarded at parse time,
because a non-`-1` bitrate re-derives `muted = false`. Conversion runs off the
parsed object (`scheduleVideoConvert(message.obj)`,
`SendMessagesHelper.java:6628` -> `MediaController.java:6592`), so the
in-memory value never reaches the transcoder. This is why the silent-video
feature forces the legacy slot to `-1` whenever muted and carries the real
bitrate in a separate version-12 extension block.

*(Established 2026-09-06, during the silent-video feature build, PR #300.)*

## `MediaController.makeVideoBitrate`'s early return skips its own `maxBitrate` clamp

At `MediaController.java:7018-7021`, `minBitrate` is computed and then
`if (originalBitrate < minBitrate) return remeasuredBitrate;` returns *before*
reaching the `maxBitrate` clamp further down (`:7061-7062`). The helper is not
guaranteed to return a value under its own maximum. A 4096x4096 source at
35 Mbps resized to 3840x3840 gives `minBitrate` = 36.16 Mbps and returns
32,812,500 -- above the 28.4 Mbps `VIDEO_BITRATE_2160` tier. This only escapes
for a near-square source whose long edge exceeds 3840; below that the top tier
takes the same-dimension branch instead. Any code that assumes the returned
bitrate sits inside the range the app normally generates will be wrong on
large near-square sources. Two of three reviewers on PR #300 asserted the
same-dimension branch was the only path that could produce an unclamped
bitrate; that is false, and only a direct read of the early return settled it.

*(Established 2026-09-06, during the silent-video feature build, PR #300.)*

## `needConvert()` returns false on `bitrate == -2`, and no conversion means audio is never stripped

`VideoEditedInfo.needConvert()` (`VideoEditedInfo.java:777-780`) returns false
when `bitrate == -2` (the "Original" quality selection, set in
`PhotoViewer.updateWidthHeightBitrateForCompression` around `:22055-22058`).
When it returns false, `prepareSendingMedia` leaves `path` pointing at the
original file (`SendMessagesHelper.java:11455-11460`) and no conversion is
scheduled (`:6620-6628`). The transcoder is the only thing that strips the
audio track, so a muted clip that reaches the send path carrying `-2` uploads
the untouched source with its sound intact. Treat "muted" and
"`bitrate == -2`" as a combination that must never be serialised together. It
is currently unreachable only by accident -- the mute tap normalises the tier
via `getCurrentVideoEditedInfo()` forcing `selectedCompression = 1`
(`PhotoViewer.java:10229-10231`), any editor exit re-stores the normalised
value, and attach-sheet show resets entries
(`ChatAttachAlertPhotoLayout.java:3916` -> `:1919` ->
`MediaController.java:534-556`). None of those are load-bearing by design, so
any change that makes a pre-mute quality tier survive a mute must first ensure
`-2` cannot reach a muted record.

*(Established 2026-09-06, during the silent-video feature build, PR #300.)*

## Rounded grouped settings cards already exist upstream as `RecyclerListView.setSections()` — don't hand-roll them

The rounded, flat, shadowless grouped-card look that every fork settings page
wears is one call, not a layout you build: `listView.setSections(true)`. The
one-arg overload resolves to `setSections(dp(12), dp(16), true)` and thence to
the DEFAULT section-exclusion predicate (`RecyclerListView.java:3284-3296`),
so card boundaries fall wherever an excluded cell (`TextInfoPrivacyCell`,
`ShadowSectionCell`, ...) sits and are auto-detected live per frame. Both fork
settings base classes already call the same one-arg form
(`BaseNekoSettingsActivity.java:146`, `BaseNekoXSettingsActivity.java:125`), so
a page only misses the treatment if it extends `BaseFragment` directly and
never opted in. Prefer the one-arg overload over the explicit-parameter form:
the latter re-declares the exclusion predicate in fork code, a fork-local copy
of an upstream list that drifts silently on the next sync.

The trap sits next door: `RecyclerListView` also takes `forcedSections`, which
pins card ranges to STATIC adapter positions. It is safe only where drag stays
inside one section — `FiltersSetupActivity` uses it for exactly that
(`FiltersSetupActivity.java:1162-1176`, intra-section reorder). On a page whose
drag crosses section boundaries it fails twice at once, because a forced range
also SUPPRESSES auto-detection for its positions in the draw loop
(`RecyclerListView.java:3529-3534`). If the page mutates its item list live
mid-drag (as a cross-zone reorder does), the forced range holds pre-drag
positions for the whole gesture: it draws a card at the wrong bounds AND hides
the correct auto-detected one, self-correcting only on release — a defect that
never fails CI and never shows in a static screenshot. On any such page,
`setSections(true)` alone is the whole answer; auto-detection is correct, live
and free.

*(Established 2026-09-06, adopting the treatment on `ComposerLayoutActivity`.)*

## `SlideIntChooseView` swallows the seek bar's drag-end signal

`SeekBarView` exposes exactly two delegate callbacks (`SeekBarView.java:96-97`): `onSeekBarDrag(boolean stop, float progress)` and a default `onSeekBarPressed(boolean pressed)`. There is **no** `onSeekBarReleased` — release is `onSeekBarPressed(false)`. And the terminal drag callback is asymmetric across the two ways a gesture can end: every `setSeekBarDrag(...)` in the terminal branch sits inside `if (getAction() == ACTION_UP)` (`:271`), firing `onSeekBarDrag(stop=true, …)` at `:280` for the non-two-sided slider, so **`ACTION_UP` delivers a final drag callback but `ACTION_CANCEL` delivers none at all** — a cancelled gesture only reaches `onSeekBarPressed(false)` (`:286`), the same release signal `ACTION_UP` also sends after its drag callback. Press is `onSeekBarPressed(true)` from the move handler (`:313`). `SlideIntChooseView`'s anonymous delegate (`SlideIntChooseView.java:95-115`) throws all of that away: it ignores `stop`, overrides neither pressed callback, narrows the reported callback to `Utilities.Callback<Integer>` (a bare value, no drag-state), keeps `seekBarView` private with no accessor, and — because `setReportChanges(true)` is on for the composer's Toolbar-size slider (`SlideIntChooseView.java:94`) — fires continuously on every step crossed during a drag with no way for a caller to tell a mid-drag step from the last one. So a consumer that needs "the drag ended" cannot get it from this widget, and a consumer that needs "the gesture was cancelled" gets nothing from the drag callback at all.

The fork-only workaround, used by `ComposerLayoutActivity` for the packing-row settle, is to override `dispatchTouchEvent` on the fragment's own root `FrameLayout` (`ComposerLayoutActivity.java:287`) and treat `ACTION_UP`/`ACTION_CANCEL` there as the gesture-end edge. `SlideIntChooseView` calls `requestDisallowInterceptTouchEvent(true)` on `ACTION_DOWN` (`:86-89`), which suppresses the `RecyclerView`'s *interception* but never *dispatch*, so the root still sees every event including the terminal one. Do **not** reach for a debounce/quiet-interval timer instead — users hold this slider still to read the live preview, so a timer fires mid-gesture with the finger down. And do **not** try to add a `getSeekBarView()` accessor or an `onSeekBarPressed` forward to fix it at source: `SlideIntChooseView` and `SeekBarView` carry zero fork edits and are touched by nearly every upstream bump, so an edit there is a permanent rebase tax.

*(Established 2026-09-06, #composer-spacing.)*

## `SlideIntChooseView.getProgress` and `getValue` are not inverses when `betweenSteps > 1`

`SlideIntChooseView` maps a value to a track position with `getProgress` (`:186-197`) and a track position back to a value with `getValue` (`:199-207`), but the two use different arithmetic. `getProgress` computes `Math.round(...) / options.betweenSteps` (`:192`), and because `Math.round(float)` returns `int` and `Options.betweenSteps` is declared `int` (`:345`), that is an **integer division**: for any value that falls between two anchors, the sub-anchor term truncates to `0`, so the value collapses onto its *lower* anchor's track position. `getValue` divides the analogous term by `(float) options.betweenSteps` (`:204`), a float division, so it *does* return real sub-anchor values. The round trip therefore fails: `getValue` hands out `89`, but `getProgress(89)` returns the track position of `85`, not of `89`.

With `betweenSteps == 1` the division is `/1` and both are exact, so this only bites a caller that configures a step table with anchors more than one unit apart and a `betweenSteps > 1` to subdivide them. Two distinct symptoms follow, both silent:

- **Thumb desyncs from its own label on rebind.** `set()` (`:172-184`) drives the thumb with `setProgress(getProgress(value))` while `updateTexts` prints the exact `value`. A between-anchor value lands the thumb on the lower anchor while the label reads the true number. A user's own drag hides this (the thumb rests at the raw finger position and is never re-derived); the first *rebind* of the row exposes it.
- **`setMinValueAllowed` misdraws the dimmed floor band.** It sets `minProgress` to `getProgress(floor)` (`:230`), so a floor that is not itself an anchor truncates: a floor of `88` over anchors `{85,90,95,100}` yields `getProgress(88)=0` and **no band renders**; a floor of `93` yields `getProgress(93)=0.333` (the position of `90`) so the band renders but **stops short** of the real floor.

The fix is fork-side and does not touch the widget: list every reachable value as its own anchor and set `betweenSteps = 1`, so `getProgress` has nothing to truncate. In the composer layout editor this was `SPACING_STEPS`/`SPACING_BETWEEN_STEPS` in `ComposerLayoutActivity`, moved from `{85,90,95,100}` + `5` to the full `85..100` + `1`. Do not "simplify" such a table back to sparse anchors with a larger `betweenSteps` — it reintroduces both symptoms.

The thumb-vs-label desync above is not limited to the `betweenSteps > 1` case — it is the normal resting state of *any* `SlideIntChooseView` after a drag, because the underlying `SeekBarView` never canonicalizes the released position to a discrete step. On `ACTION_UP`, when the touch lands off the thumb, it *does* reassign `thumbX` to the raw finger pixel, clamped to `minThumbX()`/`getMeasuredWidth() - selectorWidth` (`SeekBarView.java:259-262`), before reporting the released progress to its delegate — `setSeekBarDrag(true, thumbX / (w - selectorWidth))` (`SeekBarView.java:280`) — but nothing rounds that position to a step. The one place `thumbX` gets rounded at all doesn't reach the stored field: `onDraw` shadows it into a local (`int thumbX = this.thumbX;`, `SeekBarView.java:496`) before rounding that local when `delegate.needVisuallyDivideSteps()` is true (`:503-505`), which `SlideIntChooseView` hard-codes to `false` (`SlideIntChooseView.java:124-126`) anyway — so even a caller that flipped that gate would only be rounding a draw-time copy, never correcting the field it draws from next frame. Meanwhile the label is driven independently by `updateTexts(value, true)` from the drag callback (`SlideIntChooseView.java:110`). So a thumb released between two representable values rests off-detent while the label already shows the nearest value, and stays there until something *rebinds* the row (`set()` → `setProgress(getProgress(value), false)`, `:172-184`). With `betweenSteps == 1` that rebind lands the thumb exactly on the label's value, which is what `ComposerLayoutActivity`'s gesture-end `snapSliderRows()` relies on to snap all four composer sliders on `ACTION_UP`/`ACTION_CANCEL`.

*(Established 2026-09-06, #composer-spacing.)*

## `SlideIntChooseView.setMinValueAllowed` corrects the thumb upward only, never back down

`SlideIntChooseView.setMinValueAllowed` (`:222-233`) enforces its floor purely through `seekBarView.setMinProgress(getProgress(value))`, and the enforcement is one-directional. `SeekBarView.setProgress(float, boolean)` clamps the new thumb position against `minThumbX()` (`SeekBarView.java:411-412`), and `minThumbX()` (`:353-355`) is derived from `minProgress` — the value left over from the *previous* call. `setMinProgress` (`:229-234`) only re-applies progress when `getProgress() < minProgress`, i.e. it corrects the thumb **upward** only. So *raising* the floor moves a too-low thumb up, but *lowering* the floor never moves a too-high thumb back down.

The consequence bites any caller that rebinds one of these sliders with both a new value and a lower floor in a single `set()` + `setMinValueAllowed()` pair, when `set()` runs first: `set()` drives `setProgress` while `minProgress` is still the old, higher floor, so the thumb is clamped to the old floor's position even though `updateTexts` moves the printed label to the new value. Label and thumb then disagree until something else rebinds the row. In the composer layout editor this surfaced as: Toolbar size 100%→75%→100% across two gestures left Icon spacing reading 85 with the thumb pinned hard right (`ComposerLayoutActivity`'s `TYPE_SPACING` bind). The fix, all fork-side, is to drop the allowed minimum to the slider's own floor **before** `set()` so the value lands unclamped, then raise it to the real floor **after** — never edit `SeekBarView`/`SlideIntChooseView` for it.

*(Established 2026-09-06, #composer-spacing.)*

## `Tools/scripts/requirements.txt` is installed by every publish build, so anything added there taxes the APK pipeline

`staging.yml` runs `python -m pip install -r Tools/scripts/requirements.txt` immediately before invoking `upload.py`, the script that posts a finished build to Telegram (`.github/workflows/staging.yml:454-456`). That requirements file exists for `upload.py` alone — it pins the pyrogram client and its crypto extension, nothing else. Any dependency added to it is therefore downloaded and installed on **every** publish build, including builds that never touch the tool that needs it.

This is not visible from `requirements.txt` itself, which looks like a general-purpose manifest for `Tools/scripts/`. It is not. When the README wall compositor needed Pillow, the dependency went into a separate `Tools/scripts/requirements-images.txt` specifically to keep it off the build path; that file carries the reasoning inline, but only someone already editing it would see it.

Before adding a Python dependency anywhere under `Tools/scripts/`, decide which file it belongs in: the CI-installed one, or a separate manifest installed by hand. Getting it wrong costs build minutes on every publish, permanently, for no benefit.

*(Established 2026-09-06, #docs, PR #294.)*

## `Path.is_absolute()` is `False` for Windows drive-relative paths, so confinement checks need `path.anchor`

A path like `C:foo.png` has a drive but no root, so Python reports `Path("C:foo.png").is_absolute()` as `False` on Windows. Joining it does **not** behave like a relative path, though: `PureWindowsPath.__truediv__` replaces the drive component, so `out_dir / "C:foo.png"` silently discards `out_dir` entirely.

Any guard written as "reject absolute paths, then join" therefore lets drive-relative values straight through and writes outside the directory it was meant to confine. The correct test is `path.anchor`, which is `"C:"` for exactly these values and empty for a genuine relative path.

Both path guards in the wall compositor check `.anchor` rather than relying on `is_absolute()` alone — `_confine_source` for panel sources (`Tools/scripts/compose_walls.py:157-171`) and `_require_plain_png_filename` for wall outputs (`Tools/scripts/compose_walls.py:187-200`). The output guard shipped with only the `is_absolute()` check first and was caught in review; the source guard had the same gap and was closed in the same pass.

*(Established 2026-09-06, #docs, PR #294.)*

## `commit-tag.yml`'s job name is declared unquoted, so its real status-check context is the truncated string `Every commit carries a`, and the required-checks ruleset pins that exact truncation

`commit-tag.yml` declares its job as `name: Every commit carries a #tag`, unquoted (`.github/workflows/commit-tag.yml:16`). In YAML a space-preceded `#` starts a comment, so everything from `#tag` on is discarded and the job's real name — and therefore the **GitHub status-check context** it reports under — is the truncated literal `Every commit carries a` (22 chars, trailing space trimmed). Verified live with `gh pr view 334 --json statusCheckRollup --jq '.statusCheckRollup[].name'`, which returns exactly `Every commit carries a` (2026-09-10).

The no-bypass ruleset that gates merges into `dev` requires **that exact truncated string** as its one status check: ruleset `22861936` (`dev required checks (no bypass)`), `rule=required_status_checks`, context `Every commit carries a`, `strict=false`, `bypass_actors: []` — all confirmed via `gh api repos/dazewell/Dazegram/rulesets/22861936` (2026-09-10). **Quoting the job name to "fix" the YAML would rename the check context to `Every commit carries a #tag`; the required context `Every commit carries a` would then never report, and every PR into `dev` would sit forever waiting on a check that no longer runs — a hard deadlock against the live ruleset.** If the name is ever corrected, the ruleset's required context must be updated **first**, in a separate step, or in lockstep. This is why `commit-tag.yml` is deliberately out of scope for any "tidy the workflow YAML" change.

The reason this ruleset is *separate* from the pre-existing `dev` ruleset rather than a rule added to it: ruleset `18550420` (`dev no-force no-delete + Copilot review`) carries `bypass_actors` including `RepositoryRole 5` (admin) at `bypass_mode: always` (plus a DeployKey and an Integration, same mode) — confirmed via `gh api repos/dazewell/Dazegram/rulesets/18550420` (2026-09-10). An agent runs under dazewell's admin token, so any required check added to `18550420` would be bypassed for exactly the actor it is meant to bind. Ruleset `22861936` has an **empty** bypass list, which is the whole point: it is the one gate on `dev` that an admin-token agent cannot bypass, and it is what makes conditional agent merge authority safe.

One live behaviour to expect around all this: GitHub computes `mergeable` and `mergeStateStatus` **asynchronously**, and they are distinct fields — `mergeable` settles to `MERGEABLE`/`CONFLICTING`, while `CLEAN` is a value of `mergeStateStatus`. After ruleset `22861936` was created, open PRs read `mergeable: UNKNOWN` (and `mergeStateStatus: UNKNOWN`) for minutes before `mergeable` settled to `MERGEABLE` and `mergeStateStatus` to `CLEAN`, and a PR that had already MERGED read `UNKNOWN` indefinitely (2026-09-10). So `mergeable: MERGEABLE` is never a freshness guarantee — it answers "does this textually merge", not "is this green" — and the instant any merge moves `dev`, every other open PR's `mergeStateStatus` drops back to `UNKNOWN` until a background job recomputes it. Gate on `mergeStateStatus == CLEAN` plus a head-pinned green head check, re-read each time by polling `mergeStateStatus` itself, never on a cached or just-observed `mergeable`.

*(Established 2026-09-10, #docs.)*