# Dead ends

Hypotheses that were investigated and **disproven**, with the evidence that
killed them. Recorded so the next investigation doesn't spend time re-testing
a theory that's already dead. Re-verify the citation before relying on it —
see the README.

## "The enabled launcher activity-alias drives the ColorOS notification icon"

Disproven on the tested ColorOS device, for the pre-change manifest. Selecting
the Neon launcher icon in Chat Settings flips the enabled activity alias
through `LauncherIconController.setIcon` (`LauncherIconController.java:27-34`),
called from the picker's tap handler at `AppIconsSelectorCell.java:136` — not
from the unrelated `tryFixLauncherIconIfNeeded` startup safety net
(`LauncherIconController.java:11-19`), which only runs at app launch to catch
a state where no alias is enabled at all. Selecting Neon changed the
home-screen icon as expected. But the ColorOS notification stayed on the
Telegram-blue paper plane rather than following Neon. Notifications never read
an activity-alias icon in the first place: `NotificationsController` derives
its small icon purely from `NaConfig.notificationIcon` via
`getNotificationIconResId()` (`NotificationsController.java:6531-6545`), and
both `.setSmallIcon(...)` call sites (`:4703`, `:5761`) call that method, not
any launcher-icon or activity-alias lookup. Before this branch's change, the
`<application>` node's own `android:icon`/`android:roundIcon` still pointed at
the Telegram-blue mipmaps (`ic_launcher_nagram_blue`/`_round` — see the "before"
side of commit `cc2b3e0786` in this PR, since this PR's final state replaces
those literal resource refs with a per-variant `${fixedAppIcon}`/
`${fixedAppIconRound}` manifest placeholder — see the "Positive result"
paragraph below — and a plain current-tree line citation would no longer be
reverifiable), so the
enabled-alias theory and the fixed-application-icon theory were
indistinguishable from this evidence alone — both pointed at the same blue
asset. This entry only kills the activity-alias theory.

**Positive result (post-smoke).** On dazewell's tested ColorOS device, the
Unofficial (`nekox.messenger`, DazegramX) smoke build from PR #291 head
`a6938cde4a` was installed with the Neon launcher alias still selected in Chat
Settings. That build's `<application>` node carried a direct
`android:icon="@mipmap/ic_launcher_nagram"`/`android:roundIcon="@mipmap/ic_launcher_nagram_round"`
(no placeholder yet at that head). Triggering a new-message notification
showed the Default/orange icon art while the home-screen launcher icon
remained Neon. With only those two attributes repointed from the
Telegram-blue mipmaps to the Default ones (`#app-icon-fallback`) and nothing
else changed, this confirms — for this device and mode — that the fixed
`<application>` icon, not the enabled launcher activity alias, is the source
ColorOS reads for the notification icon. Evidence is visual-only; no device
trace was captured, so this is tested-device evidence, not a general claim
about ColorOS behavior across other versions, OEM skins, or notification
configurations. This result still applies after the later variant-scope
correction: that change replaced the literal resource with a
`${fixedAppIcon}`/`${fixedAppIconRound}` manifest placeholder resolved per
package variant (`build.gradle` `manifestPlaceholders`), and for Unofficial
that placeholder resolves to the same `ic_launcher_nagram`/`_round` pair the
smoke build already tested — Unofficial's merged manifest icon attributes are
unchanged, byte-for-byte, by that correction. This entry says nothing about
Official (`org.telegram.messenger.beta`, Dazegram), which keeps its
pre-existing Blue fixed icon and was not part of this investigation.

*(Established 2026-09-04, confirmed 2026-09-05.)*

## "The notification icon can be made to follow the Chat Settings > App Icon selection at runtime"

Disproven, on three independent grounds — the strongest of them
device-confirmed — that together mean this does not extend or contradict the
two entries above; it answers the follow-up question of whether the
existing per-alias mismatch could be fixed dynamically rather than by picking
a single fixed icon.

**Reason A (device-confirmed) — on the tested ColorOS device, the dedicated
small-icon channel this fork already has doesn't render at all.** Changing
Nagram Settings › General › Notification Icon
(`NaConfig.notificationIcon`, the `ConfigCellSelectBox` at
`NekoGeneralSettingsActivity.java:228-232`) to a visibly different value and
restarting the app produces **no change whatsoever** in the status bar or
shade on that device. This is a direct on-device observation, the same
evidential class as the Neon-launcher/orange-notification test in the entry
above, not an inference: ColorOS discards the `setSmallIcon()` argument
entirely and renders only the app-identity icon it derives from
`ApplicationInfo.icon`. The value `getNotificationIconResId()` computes and
passes at `NotificationsController.java:4703` and `:5761` is accepted by the
API and then silently dropped by this OEM shade. This makes the feature
unbuildable on its own, independent of everything below: **a feature whose
entire observable effect is discarded by the target device is not a
feature**, no matter what art it would have used.

**Reason B — the OEM shade icon this fork can actually influence is
immutable at runtime.** The confirmed source on the tested ColorOS device is
`ApplicationInfo.icon`, a resource id baked into the manifest and resolved by
`system_server` from the app's parsed resource table, re-read only at
install/update — there is no public API that mutates it post-install. Each
runtime candidate considered, and why it's dead:
- **RRO / `OverlayManager`** — requires the overlay be signed with the
  platform key or preinstalled as privileged; not available to a normally-
  signed app, and no runtime-resource-override code exists anywhere in this
  tree.
- **`setSmallIcon(Icon)` with full-colour art** — since API 21 the platform
  composites the status-bar/notification small icon from its **alpha channel
  only**, discarding colour; `minSdk` is 27 (`build.gradle:36`, established
  in the entry above), so no supported version behaves differently. On this
  device the point is moot regardless, per Reason A: the argument to
  `setSmallIcon` isn't rendered at all.
- **`setLargeIcon`** — the one non-monochrome notification surface, already
  occupied by the conversation avatar
  (`NotificationsController.java:4737`, `:4741`, `:4751`, `:5838`). Repurposing
  it for the app icon would mean losing the avatar, a downgrade not a fix.
- **`NotificationChannel`** — carries no icon of any kind.
- **`Person` / bubble / conversation icons** — these show the contact's
  avatar by design; they never surface the app's own icon.
- **Resource-qualifier trickery** (e.g. a `mipmap-night/` variant so the
  `ApplicationInfo.icon` drawable resolved at parse time differs) —
  considered and rejected explicitly: the app has no way to force system
  night mode on demand, this yields at most two resolved states rather than
  the picker's fifteen, and which qualifier ships is OEM-dependent.
- **Per-alias `<activity-alias>` icons** — already disproven on-device by the
  entry above; notifications never read that value at all.

**Reason C — no notification-legal art exists for the picker's icons, even
setting Reasons A and B aside.** Limiting scope to just the monochrome status-bar
small icon Android actually composites, there is nothing in the tree to map
the 15-entry `LauncherIconController.LauncherIcon` picker
(`LauncherIconController.java:36-50`) onto:
- Of those 15 entries, 9 (`DEFAULT`, `GOOGLE`, `COLORFUL`, `DARKGREEN`,
  `NEON`, `NIELLO`, `BLUE`, `DARKBLUE`, `BLURBLUE`) resolve to adaptive-icon
  XML under `mipmap-anydpi-v26/` that **all** reference the same monochrome
  layer, `@drawable/ic_launcher_nagram_monochrome` — confirmed by grep across
  every file in that directory. That drawable is not even one stable asset:
  it exists only per package-variant source set, at
  `TMessagesProj/src/iconOfficial/res/drawable/ic_launcher_nagram_monochrome.xml`
  and `TMessagesProj/src/iconUnofficial/res/drawable/ic_launcher_nagram_monochrome.xml`
  separately (no `src/main/res/drawable/` copy), each `108dp`/`512`-viewport
  with a distinct path.
- 1 entry (`TELEGRAM`, `ic_launcher_dr.xml`/`_round`) references
  `@drawable/icon_plane` (`TMessagesProj/src/main/res/drawable/icon_plane.xml`,
  `90dp`/`90` viewport).
- The remaining 5 entries (`VINTAGE`→`icon_6_launcher`, `AQUA`→`icon_4_launcher`,
  `PREMIUM`→`icon_3_launcher`, `TURBO`→`icon_5_launcher`,
  `NOX`→`icon_2_launcher`) have **no `<monochrome>` element at all** — confirmed
  by the same grep returning zero matches for any of those five files.
- Net: 2 distinct shapes cover 10 of the 15 picker entries, and the other 5
  have nothing to map to. This also weakens the case for adding a "match app
  icon" mode on redundancy grounds, not just geometry: the existing
  `notificationIcon` setting's own value `0` is already labeled "Telegram"
  (`strings.xml:1575`, `R.drawable.notification`) and its default value `1`
  is already the nagram glyph. Even setting geometry aside, mapping the
  launcher picker's 9-entry "nagram-style" cluster and its 1-entry
  `TELEGRAM` slot onto notification art would land on shapes conceptually
  adjacent to icons this setting can already produce manually — it does not
  reach a state the four-option setting is currently unable to express.
- Geometry rules out using either of the two available launcher monochrome
  shapes as a notification icon even where one exists — this is a separate,
  independently-fatal point from the redundancy argument above, not
  contingent on it. Both `ic_launcher_nagram_monochrome` variants are
  `108dp`/`512`-viewport adaptive layers with a path spanning roughly
  x139→371 (Official) or x162→349 (Unofficial) — each only ~40-45% of the
  canvas, because adaptive icons reserve a safe zone around the mark.
  `icon_plane.xml` is `90dp`/`90`-viewport with a path spanning roughly
  x28→58, ~33% of its canvas. The real notification glyphs, e.g.
  `TMessagesProj/src/main/res/drawable-anydpi/nagram_notification.xml`, are
  `24dp`/`24`-viewport with art filling the box edge-to-edge. Pointing either
  candidate at `setSmallIcon` would render a visibly shrunken glyph inside a
  mostly-empty status-bar icon.

**Standing constraint for future disguise/privacy work.** Reason A narrows
the earlier framing of the launcher-alias disguise consequence to one
channel, and makes it absolute rather than merely "the shade keeps showing
the real icon": on this device, `ApplicationInfo.icon` is not just the icon
ColorOS *prefers* to show, it is the **only** icon channel ColorOS renders in
a notification at all — there is no secondary glyph channel to fall back to,
because the one Android API that exists for that purpose (`setSmallIcon`) is
confirmed inert here. A launcher-alias disguise hides the app on the home
screen and **cannot** hide it in the notification shade on this skin, with no
in-app mitigation available, because the channel it would need to use isn't
read. Any future disguise or customized-privacy feature on this fork should
treat that as a hard device-class limitation, not a bug to route around.

**Collateral fact, not a change request.** `NaConfig.notificationIcon` ships
four selectable options that are inert on the tested ColorOS device — picking
any of them changes nothing observable there. This is recorded as an observed
device limitation only. The setting is not fork-broken: it works as designed
on stock Android and (presumably) other OEM skins that honour `setSmallIcon`,
this is one OEM's shade behaviour, and this investigation does not propose
hiding, gating, or annotating the setting for it — that would be adding
device-specific UI for a single OEM, out of scope for this PR and not asked
for.

*(Established 2026-09-05.)*

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

## "Spoiler atlas flicker is a coordinate-space mismatch between clip mapping and draw bounds"

Disproven. The atlas clip mapping and spoiler draw bounds are already
self-consistent: `applyClip` normalizes incoming bounds into atlas-local modulo
space and unions wrapped segments (`SpoilerEffectBitmapFactory.java:120-130`),
while the consumer draw path feeds current bounds directly into that same update
flow (`SpoilerEffect.java:323,329`). Particle admission itself is clip-relative
with an intentional +/-1dp damage margin (`SpoilerEffect.java:369-372,464`), so
the fix target is atlas lifetime/completeness, not coordinate remapping.

*(Established 2026-09-04.)*

## "Persistent clipped repaint can be made coherent by tuning clear/draw region"

Disproven. In the shared atlas producer, simulation advances by intersection on
spoiler-cell bounds (`SpoilerEffectBitmapFactory.java:96-104`) while trigger
regions are modulo-mapped and unioned (`SpoilerEffectBitmapFactory.java:120-130`)
and particle admission itself is clip-relative with a 1dp margin
(`SpoilerEffect.java:342,369-372,464`). Under clipped rasterization those three
surfaces cannot stay generation-coherent at mapped region seams: adjacent texels
inevitably come from different animation passes. Full-atlas redraw is required
for coherent published generations.

*(Established 2026-09-04.)*

## "Per-consumer ownership can make shared-atlas partial repaint coherent"

Disproven. The atlas trigger API carries only `Rect region` (`checkUpdate(Rect)`,
`SpoilerEffectBitmapFactory.java:112-118`) and merges into one shared union
(`SpoilerEffectBitmapFactory.java:120-130`), with no consumer identity channel.
Call sites are also content-agnostic view draws: one `DialogCell` can render
text spoilers and spoilered thumbs in the same draw pass
(`DialogCell.java:4416-4424,4842-4846`), and pinned-bar spoiler thumbs route
through a generic `BackupImageView` overlay (`ChatActivity.java:12765-12791`).
Ownership bookkeeping would still publish mixed generations unless atlas publish
is full-generation per pass.

*(Established 2026-09-04.)*

## "`startSpoilers` / `stopSpoilers` currently freeze and resume spoiler simulation"

Disproven. The lifecycle still posts start/stop notifications
(`ChatActivity.java:7548,7583`) and cells still forward them into
`setSuppressUpdates(...)` (`ChatMessageCell.java:401-420`,
`ChatActionCell.java:210-213`), but `SpoilerEffect` only stores that flag and
invalidates (`SpoilerEffect.java:105,159-161`) and the active draw/update path
does not consult it (`SpoilerEffect.java:317-329,342-507`). In this lineage the
chain is write-only; do not scope fixes around freeze-on-scroll behavior.

*(Established 2026-09-04.)*

## "Spoiler particles are the media/text privacy mask"

Disproven. Media masking comes from the blurred image layer inside the spoiler
clip (`ChatMessageCell.java:15363-15396`) and from the reusable blur receiver
path that is generated via stack blur then drawn as a separate layer
(`BackupImageView.java:104-108,361,470`). Particle noise is a decorative overlay
drawn after the blur layer (`ChatMessageCell.java:15395-15407`).

Text masking is also structural before particles are drawn: text is rendered
with spoiler rectangles clipped out (`SimpleTextView.java:1210-1218,1234-1248`),
and spoiler entities are carried by style runs (`ChatActivity.java:30998`,
`DialogCell.java:1926,2017`), so plaintext protection is not coupled to atlas
texture refresh cadence.

*(Established 2026-09-04.)*

## "Composer glass/default-wallpaper work (#287) or paid-media GroupMedia lifecycle caused deterministic spoiler-atlas flicker"

Disproven for this repro pair. The spoiler atlas path is centralized in
`SpoilerEffectBitmapFactory` and consumed via `SpoilerEffect` draw/update
(`SpoilerEffectBitmapFactory.java:26-47,146-168`; `SpoilerEffect.java:323,329`).
Composer glass/default-wallpaper code lives in `ChatActivityEnterView`
(`ChatActivityEnterView.java:1820-1868,5195-5196`) and paid-media lifecycle work
lives in `ChatMessageCell`'s `GroupMedia` branch
(`ChatMessageCell.java:9242-9251,24444-24445`): distinct subsystems, not the
atlas producer invariant that this fix changes.

*(Established 2026-09-04.)*
