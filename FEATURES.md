# Dazegram features

Extra bits I've added on top of [NagramX](https://github.com/risin42/NagramX). Most are on out of the box. Where something has a setting, I've said so.

## Chats and privacy

### Per-chat time zones <!-- #timezones -->

Set a time zone for any personal chat or group through the profile edit view. The chat header and contacts list show the peer's current local time as a clock pill. Tap the pill to get a side-by-side time converter in a bottom sheet. Slide the strip to line up a moment in both zones, or hit "Now". You can drop the lined-up time into the message box. The template renders from a collapsible *Message format* section, where you can tap tokens like `{peer_time}`, `{offset}`, `{my_range}`, or `{duration}` into the template, and you can pick the language the message renders in. Next to "Now", there's a *Range* pill for when you need a window instead of a single moment. When you schedule a message to a chat with a zone set, the schedule sheet gains a *My time* / *Peer's time* tab above the picker wheels to schedule in their time.

<img height="200" alt="time zone feature showcase configuration" src="https://github.com/user-attachments/assets/606a0f24-1c3b-48d3-b013-d9782bb12854" />
<img height="200" alt="time zone feature showcase view etc" src="https://github.com/user-attachments/assets/00b9724e-e31d-4067-a095-ab26827b5710" />

### Customized privacy <!-- #customized-privacy --> <!-- #hide-last-message --> <!-- #require-password -->

Each chat now has one `Chat privacy` item in its in-chat ⋯ menu. It opens a single sheet with two controls: `Hide last message` (with editable placeholder text per chat) and `Require password`. Turning on `Require password` still auto-enables `Hide last message` only when it was off, so an existing custom placeholder stays untouched. Turning `Require password` off still does not turn hiding off.

The same sheet has a `Notifications` section with a `Disguise notifications` switch, off by default. While it is on, that chat uses a generic cover persona instead of real chat/sender/message content. You can pick the persona (`Cover`) and post a safe `Preview notification` from the sheet without changing unread state.

Covered taps/dismissals only clear that represented cover and never open the chat or mark anything read. Suppression of represented covered members survives restarts and temporary disable/re-enable of disguise. Attempting to open the chat through the app clears the current cover, including when a password/PIN prompt is cancelled or fails; cover tap/dismiss itself still never marks anything read. Genuinely new members can cover again later. Live incoming/ongoing call UI and stories stay outside disguise, while missed-call service notifications are covered generically. Android still owns app attribution, badges, and any already-pinned old conversation/channel labels, and Android notification settings stay authoritative.

### Privacy profiles <!-- #privacy-profiles -->

Save a set of auto-lock timeouts under Nagram Settings > Passcode and switch between them. Activate a profile for now, for a stretch of time, or until a specific moment. Long-press the Settings tab for the "Auto-lock profile" list to quickly switch. Each profile gets its own icon and colour. Changing the auto-lock timeout through the regular picker, restoring a backup, or clearing your passcode will drop whatever profile was active and adopt the new baseline value.

### Passcode setup safety <!-- #passcode-setup-safety -->

Setting a Panic Code that matches an unlock code is a security risk. Setup now ensures your Panic Code is unique; it cannot match your app passcode or any account's passcode. The setup screens also state clearly which code you are setting (App, Panic, or Account) to prevent confusion. Old Panic Codes set before this safety check existed might clash; the settings screen will prompt you to re-set your Panic Code if you are unsure it is unique.

### Reply threads in private chats <!-- #personal-replies -->

Private chats now show a reply glyph and count next to a message's timestamp, bringing group-style reply threads to 1-to-1 chats. Tap the reply count to swap the history for the message and its replies without leaving the chat.

### Message bookmarks <!-- #bookmarks-300 -->

Save up to 300 messages per chat from the message menu. Bookmarks keep their existing per-account storage and are included in settings backup and restore.

### Ayu Mode shortcut <!-- #ayu-mode -->

A launcher shortcut (long-press the app icon, or pin it to your home screen) that opens the app with Ghost Mode already on. Tapping it flips every Ghost toggle you haven't locked and pushes you offline, then opens as normal.

### Ghost icon stays put under stories <!-- #ghost-icon -->

With Ghost Mode on, the ghost indicator next to the chat list title stays visible even when contacts' stories collapse the header.

### Clear Message Database removes only this install's media <!-- #clear-db-own-media -->

Clear Message Database now removes only the media this install has database rows for, preventing a second install sharing the Downloads folder from having its media wiped.

## Composer and input

### Composer toolbar <!-- #composer-toolbar --> <!-- #composer-bubbles -->

The separate glass text pill holds Send or mic at its trailing end. A row of action bubbles sits below it, drawn in the same blurred glass. Quote, Spoiler, Select All, and Clear stay in the toolbar and enable when text is selected or the field has text. Light and dark theme share one base transparency formula for this glass; how much wallpaper shows through each is set separately (see Composer glass transparency below), rather than light theme carrying its own fixed, noticeably more opaque override.

### Send and mic inside the input <!-- #composer-input -->

Send and the mic sit inside the text pill, drawn slightly in from its rounded end so a thin ring of glass shows around them.

### Wallpaper pattern shows through the composer glass <!-- #glass-pattern -->

If your chat wallpaper has a pattern on it - the built-in gradient, a pattern from a theme, or one you've set from Settings > Chat Background - it now reads through the glass composer panels, not just the colour behind it. The glass samples a small proxy of the wallpaper rather than the wallpaper itself, so the pattern reads as soft, enlarged texture rather than a crisp motif - and the more transparent you set the composer glass, the more of it shows. It follows the wallpaper as it changes or rotates on send, and fades in with the pattern when a chat opens. The dimmed backdrop behind a round video recording shows the pattern too.

### Composer toolbar layout editor <!-- #composer-layout --> <!-- #composer-layout-tap-toggle --> <!-- #composer-leading-2slot -->

The button row under the compose box is yours to arrange via chat settings. You can place any action in any zone (Leading, Middle, Trailing, Hidden). The Leading zone is capped at two slots. Tapping a row in Hidden or Middle toggles it straight to the other section without dragging. Hold any button on the live toolbar for about one second to open this editor directly.

### Attach button stays visible while typing <!-- #composer-attach-pinned -->

The attach paperclip remains visible on the toolbar even when the field is full of text, rather than swapping into the header overflow menu.

### Cut, Copy, Paste buttons <!-- #composer-clipboard-actions -->

Cut, Copy, and Paste are available as composer toolbar buttons, added through the layout editor.

### Composer toolbar size <!-- #composer-scale --> <!-- #composer-spacing -->

The toolbar row can be scaled from 75% to 125% in 5% steps. A second slider sets icon spacing, packing buttons closer without shrinking them. At small toolbar sizes, the tightest spacing steps will grey out to prevent icons from overlapping.

### Composer glass transparency <!-- #composer-transparency -->

Light and dark theme each get their own slider in the layout editor for how much wallpaper shows through the composer's glass — the message field, its icon row, the button clusters beside it, the instant-camera controls, the floating buttons over the message list (page down, mentions, reactions), and a channel's bottom bar — from 0 to 50% in 5% steps. Both default to 25%, close to what the glass already did in the common case, though if you have Liquid Glass mode on this reads a bit more transparent than its old fixed default, since the composer now uses this one configurable value across both themes instead of a fixed one. Takes effect when you leave the editor. The live preview above the sliders only reflects whichever theme is currently running, and nothing changes while chat blur is off.

### Quick schedule button <!-- #quick-schedule -->

The calendar icon is a one-tap schedule shortcut, instead of long-pressing Send.

### Floating input controls <!-- #input-satellites -->

The floating send-column layout is gone. The glass-fill helper stays with the close button in reply, edit, forward, and link-preview panels.

### Message input text size <!-- #input-text-size --> <!-- #composer-emoji-scale -->

The text you type in the compose box has its own size, separate from chat bubbles, adjusted via a slider in chat settings. Custom animated emoji scale with this slider.

### Physical keyboard hotkeys <!-- #keyboard-hotkeys -->

Matches Telegram Desktop bindings for BT/USB keyboards. Does nothing on software keyboards. Can be disabled in settings.

| Shortcut | Action |
|---|---|
| `Esc` | Cancel reply/edit, close search, go back |
| `Up` (empty input) | Edit last sent message |
| `Ctrl+F` | Search in current chat / chat list |
| `Ctrl+W` | Close current chat |
| `Ctrl+PgDn` / `Ctrl+PgUp` | Next / previous chat |
| `Ctrl+Alt+Home` / `End` | First / last chat |
| `Ctrl+Shift+↓` / `↑` | Next / previous folder |
| `Ctrl+0` | Saved Messages |
| `Ctrl+1`..`8` | Pinned chat 1–8 |
| `Ctrl+9` | Archive |
| `Ctrl+J` | Contacts |
| `Ctrl+L` | Lock app (when passcode is set) |
| `Ctrl+M` | Minimize app |
| `Ctrl+R` | Mark current chat as read |
| `Alt+Enter` | Schedule message (Enter confirms the picker) |
| `Alt+↑` / `↓` | Step reply target older / newer; past newest clears |
| `Alt+;` | Emoji search: type to filter, arrows to pick, Enter inserts, Ctrl+Enter sends, Esc closes |
| `↑` / `↓` / `Enter` (suggestions open) | Navigate the inline `:emoji` / `@` / `#` / `/` autocomplete; keep typing to filter, Enter inserts the highlighted one |
| `Ctrl+B` / `I` / `U` / `K` | Bold / italic / underline / link |
| `Ctrl+Shift+X` / `M` / `P` / `N` | Strikethrough / monospace / spoiler / plain |
| `Ctrl+Shift+.` | Quote (works at cursor without a selection too) |

### Cite <!-- #cite -->

Select text in a message and tap *Cite* to drop it into your input box as a quote block. Unlike a regular Reply, it just becomes part of what you're typing, letting you cite multiple messages and answer them in one draft.

### Reschedule selected messages <!-- #reschedule -->

Pick several scheduled messages and move them all at once by setting a base time and interval. Note: Telegram's own scheduler can run a minute or two late; if exact order matters, give messages three minutes or more of spacing.

The sheet also has the same *Send on event* control as the single-message schedule picker. Leave it off and Reschedule works exactly as before. Switch it on and the whole selection shares one trigger, so a single key phrase (or a voice or video match) sends them one at a time instead of you sending each by hand. The base time and interval set the fallback times, and with them the order the messages go out in; the trigger's own delay is what spaces the sends once it fires — think of the interval as the order and the delay as the pacing. The minute-or-two caveat above is about those fallback times only: a trigger sends early and skips Telegram's scheduler, so lean on the delay for spacing there. A message that already carries a trigger is left alone unless you confirm replacing it, and a message set to repeat is moved but not armed, since a repeat and an early trigger don't go together. If any message in the selection is still being sent the trigger isn't applied to the batch — wait for it to finish and try again — and one note at the end tells you what was armed and what wasn't. If the chat is still confirming an earlier trigger (for a short while after a restart), the whole selection is moved but none are armed, with a note to add the trigger again in a moment.

### Send a scheduled message early on an event <!-- #eventschedule -->

The schedule picker has a *Send on event* row to set a trigger (e.g., voice, video, text) to send the message early. Text matching now supports up to five patterns on one trigger, evaluated as OR (any one match is enough). The editor starts with one field; *Add pattern* reveals more only when you ask, up to the cap, and each row can be removed in place. The trigger has one shared mode switch for all rows: glob by default (`*` matches anything, `?` one character) or one trigger-wide *Regular expression* mode. Invalid regex rows are flagged inline by row, duplicate rows are kept but noted inline and ignored at save, and the saved summary shows the first pattern plus `+N more`. After a successful Done, the full setup (types, ordered patterns, regex mode, and delay) is remembered per account and restored the next time you start a new trigger.

If triggered, it sends immediately; otherwise, it sends at its scheduled fallback time. Several scheduled messages can share one trigger and will send one by one in fallback-time order, each keeping its own delay. To put that shared trigger on a whole group at once, select them, tap *Reschedule*, and turn on *Send on event* there instead of setting each message by hand. If one send cannot continue right now, the trigger stays armed, a heads-up says the run stopped partway, and matching again continues the rest. The trigger row is unavailable while a message is still being sent (the *Send on event* spot shows a note instead); reopen the schedule sheet once it has finished sending and the row is back. A message is meant to carry only one trigger: editing a message that already has one updates that same trigger instead of adding a second. In the rare case it finds a message already claimed by more than one trigger, it leaves those triggers as they are and changes only the schedule time, with a note that the trigger was left unchanged. Triggers survive an app restart while the scheduled send is still confirming; until a trigger is confirmed again after a restart, changing (or turning off) a trigger anywhere in that chat is declined with the same *trigger was left unchanged* note, while the schedule time still changes — it clears on its own once the confirmation comes back.

Long-press the Chats nav button (or use the equivalent overflow menu entry when the bottom bar is hidden) and pick *Message Triggers* to see every trigger currently armed on the current account: which chat, what it's waiting for, and when it falls back. Tap a chat's row to open its scheduled messages; long-press a row and confirm to remove that trigger — removal doesn't undo a send already at the server, so there's no Undo action on it. A trigger you just armed can take a few seconds to show up here (longer for media still uploading) while its send confirmation comes back from the server — that delay is expected, not a bug. The row only appears once there is something to show.

### Remember the schedule offset <!-- #schedule-remember -->

Tap the small bookmark icon in the schedule picker to remember your schedule offset. Instead of defaulting to 10 minutes ahead, a new message's picker will always open to the time offset you last saved. A Reschedule or Edit schedule time picker keeps opening on the message's existing time either way — it only feeds the offset you confirm back into what gets remembered for next time. Turn the bookmark off to revert to normal behavior.

Reschedule and Edit schedule time pickers also get the same delay slider a new message's picker has, so you can jump straight to "10 minutes from now", "2 hours from now", and so on instead of spinning the day/hour/minute wheels by hand. It opens on your saved default delay, same as the new-message slider, but leaving it alone keeps the message's existing scheduled time — only dragging it moves anything, and it moves just this one sheet, never the account-wide default delay or what's remembered. Send-when-online reschedules don't show the slider, since there's no time to compute a delay from.

### Tidier scheduled selection bar <!-- #scheduled-selection-toolbar -->

When you select messages in the scheduled view, the top bar keeps just Send Now, Reschedule, and Delete, tucking Copy and Forward into the overflow menu.

### Forward scheduled messages <!-- #scheduled-forward -->

Telegram's forward API cannot forward a message that hasn't been sent yet. The selection bar's ⋯ → Forward now re-sends the picked scheduled messages instead, so they arrive as new scheduled messages. Media must be in the app's cache to be re-sent. Polls, locations, and contacts cannot be forwarded this way.

### Keep the reply when reposting as a copy <!-- #repost-reply -->

Repost as Copy (off by default, turn it on in settings) re-sends a message without a "Forwarded from" header, which lets it carry the original reply target and quote. Reposting media this way re-uploads the file, so you pay for the data upload again. After a repost as a copy fully succeeds, a notice offers to delete the original messages: tap Delete to bring up Telegram's own delete-confirmation dialog, or leave it and it clears itself after 15 seconds, leaving the originals untouched. Whichever of the original messages are still on screen flash briefly the moment the notice appears, so you can see exactly which ones are on the line before you decide. Alternatively, select both the message and its reply target and use plain Repost to keep both the reply and "Forwarded from" header without re-uploading. In a forum's "View as messages", reposting drops the reply.

### Spread a scheduled repost over its own times <!-- #repost-spread -->

Select several messages, drop the sender's name — the selection bar's bottom-left *NoQuote* button (its default action), or stock *Forward* with *Hide sender's name* turned on in the preview — and pick a chat. The forward is staged into that chat's input bar; long-press its *Send* button, choose *Schedule*, and pick a base time. Each item is then scheduled on its own time three minutes apart — base, base + 3 min, base + 6 min, and so on — instead of every item landing on the same timestamp. That single shared timestamp was why a batch scheduled this way used to shuffle as a block when you tried to reschedule one of them; giving each its own time is what fixes it, so they now behave as ordinary, independent scheduled messages you can edit one at a time. This only kicks in when the forward drops the sender's name and two or more items are going out; a plain *Forward* that keeps the "Forwarded from" header schedules the old way, on one shared time.

The three-minute spacing is fixed on this door. Telegram's own scheduler can still run a minute or two late, so treat the spacing as the order the items go out in rather than an exact clock. If you want to choose the gap yourself, or you're scheduling to more than one chat at once, use the forward chat-picker's own *Send* long-press instead: when two or more slots are going out it shows an interval row where you set the base time and the gap by hand.

Each item becomes its own scheduled send, so a photo/video/document album counts as one slot on one time, not one per file. Because this re-sends rather than forwards, media has to be in the app's cache and is re-uploaded, so you pay for the upload again. The spread only engages when every item in the selection can be re-sent as a copy and the last slot still lands inside Telegram's roughly one-year scheduling limit, checked once for the whole selection before anything goes out. On the automatic door that check never blocks you: if something in the batch can't be copied as a repost (polls, locations, contacts) or the span would overrun the one-year limit, the batch just goes out as an ordinary forward on one shared time — exactly what it would do without this feature, so you're never left with nothing sent. The chat-picker door, where you asked for the spread explicitly, instead tells you up front how many items it can't repost and leaves the picker open. The cache check is a snapshot, though — it reserves nothing, so if a file is evicted or its upload fails after that point, that slot may simply not go out, or it may land as a failed message you can retry. While the copies are still uploading they aren't on the server's schedule yet, so for that short window they won't show in the scheduled list; they appear once each upload confirms. "Absent" here means absent from the server's scheduled set — a slot the server rejects can still leave a failed placeholder on your side to retry, same as any failed send.

Because this stages the copies into a different chat from where the originals live, it doesn't offer to delete the originals the way a same-chat Repost as Copy does — on this route the originals are always left untouched. Opening a photo full-screen and forwarding from there keeps Telegram's ordinary forward, so a batch scheduled that way still shares one time — use the selection bar's *Forward* for the spread.

### Pin or number several messages at once <!-- #bulk-actions -->

Select messages to *Pin all* (applies your pin choice to the whole selection) or *Reply with numbers* to create an indexed table of contents via numbered replies.

### Fullscreen message input <!-- #fullscreen-input -->

Tap the expand button to grow the input between the chat header and the keyboard. Tap it again to return to normal height.

### Don't lose typed text on an accidental back <!-- #discard-guard -->

A swipe-back gesture while composing a scheduled or edited message will hold and prompt for confirmation so you don't accidentally discard it. Unsaved text in these states also survives an app-lock or if you minimize and return to the app.

## Media and camera

### Recording mode hint <!-- #media-tooltip-repeat -->

The hint that explains how to switch between voice and round-video recording appears only a few times, then stays out of your way.

### Video message playback modes <!-- #video-playback-modes -->

A button in the player bar cycles how round video messages play — play once, play all, or repeat one.

### Mute video messages <!-- #video-mute -->

A mute button next to the playback-mode toggle silences round video messages.

### Closed captions on video messages <!-- #video-cc -->

A CC button above the transcribe button provides live, line-by-line captioning over the video. Providers that return timings (Groq, Cloudflare) get captions that line up exactly with the speech; others get evenly-paced text. Captions only ever come from a transcription you explicitly ask for, so they never trigger unasked transcriptions or use surprise API credit.

### External microphone toggle in video message popup <!-- #external-mic -->

When camera mode is set to Ask, the camera choice popup has an External Microphone toggle to record through a headset instead of the built-in mic.

### Infinite video message <!-- #infinite-video -->

Infinite Recording stitches 60-second round video message segments end to end instead of stopping at the usual cap. Toggle it from the button on the camera overlay while recording (with camera mode set to Ask, the camera-choice popup offers the same toggle before you start) -- it's OFF by default at the start of every recording, and greyed out during slow mode, paid messages, secret chats, and when view-once is armed. In a scheduled chat it works only when camera mode is set to Ask: toggling it on there asks when the first segment should go out (at least 3 minutes ahead), then each stitched segment after that is scheduled 2 minutes past the one before. N-Settings → Chat → Camera → *Infinite Recording cap* controls how long it's allowed to run before it stops itself: 10 (the default, matching the old fixed ceiling), 15, 20, 30, 60 minutes, or Unlimited.

### Warning before a round video message hits its limit <!-- #video-limit-warning -->

Round video recordings warn you before they end. Configured via N-Settings → Chat → Camera, you get a light warning buzz 5 seconds before the cutoff, and a medium cutoff buzz when it actually lands.

### Smoother video message zoom <!-- #video-zoom -->

The zoom control under the round video camera was rebuilt with a full-range slider and step buttons.

### Scrub the video message preview <!-- #video-scrub -->

The preview you get after recording a round video message has a playback cursor you can drag to scrub through the video.

### Bigger recorder pause and once buttons <!-- #recorder-controls -->

The pause button and the view-once "(1)" toggle are larger and lifted slightly higher off the send button to prevent accidental sends.

### Don't lose an unsent video message <!-- #video-draft-guard -->

A round video message you've recorded but haven't sent is easy to lose by accident. A stray back gesture used to delete the finished clip for good — now backing out of a chat leaves it alone. And switching away from the app mid-recording no longer throws away what you'd captured: the clip is finished off and left in the preview, ready to send when you come back. That now holds even when the chat itself gets torn down while you're away — which is what a passcode set to lock immediately does: reopen the chat and the clip is still waiting, trimmed the way you left it, for up to a day. What comes back is the trim strip and the send button — the round preview itself isn't restored yet, so you can send the clip but not watch it back first. One gap remains beyond that: a round video recorded in the scheduled composer records and sends as before but isn't kept this way.

### Floating camera button in the attach sheet <!-- #camera-fab -->

With Disable Instant Camera on (the default), the photo attach sheet no longer keeps a live-preview camera cell at the front of the grid. Your photos start at the first slot, and a round camera button floats in the corner instead. Tapping it opens the camera the same way the old cell did, asking for camera permission the first time. Turn Disable Instant Camera off under N-Settings → Chat → Camera and the in-grid live camera tile comes back with no floating button, exactly as before.

### Custom file names for saved media <!-- #custom-file-names -->

When you save a video, voice or round message to your gallery, Telegram normally gives the file an uninformative name like `video.mp4`, then `video (1).mp4` for the next one. Turn on Custom File Names under N-Settings → General → Storage and saved files are named from the date and time the message was sent instead — `20260101_173812.mp4` by default. The setting row opens a small dialog where you flip the feature on and, if you like, change the pattern: `{date}` and `{time}` are the message's send date, and `{name}` is the sender's file name — usually blank for voice, round messages, and most videos. Anything else you type stays as is, and an unknown `{placeholder}` is dropped. As you type, a live preview below the field shows what the current pattern would save a video as, or warns you if it would produce no usable name; Reset is disabled once the field already matches the default. This covers a video sent as an uncompressed file attachment too, so under the default pattern its saved name comes from the message date rather than the sender's original filename. Two messages saved from the same second get a ` (1)` suffix instead of overwriting each other. Saved photos are left exactly as they are today.

## Transcription

### Whisper transcription controls <!-- #whisper-transcription -->

The Whisper (Workers AI) provider settings have options to skip silent audio and disable previous context to prevent repeated or made-up phrases.

### Groq transcription provider <!-- #groq-transcription-provider -->

A fast, free voice-to-text option that runs Whisper on Groq. Audio uploads have a 25 MB ceiling on the free tier.

### Retry or switch a transcription provider <!-- #transcribe-retry -->

If multiple providers are configured, the Retry option on a transcription becomes "Retry with…". You can also long-press the transcription button on a voice or round video message to stop a running attempt and open the provider list.

## Appearance

### Extera themes <!-- #extera-themes -->

Extera Light and Extera Dark bring exteraGram's look to Dazegram. They were recreated from [exteraGram](https://github.com/exteraSquad/exteraGram), with credit to its authors.

### Monet wallpaper pattern <!-- #monet-pattern-tile -->

You can apply a chat pattern over your live Material You color. Turn it off by opening the tile and clearing the pattern.

### Tab indicator outline <!-- #tab-style -->

The active tab indicator can be drawn with a thin outline in the tab's text colour over a translucent fill, instead of the solid filled pill. Turn on *Tab indicator outline* in Nagram Settings > General, under the folder tab options. It's off by default, so tabs keep the solid fill unless you switch it on.

### Fixed app icon uses Default art on DazegramX <!-- #app-icon-fallback -->

The `<application>` icon and round icon in the app manifest — Android's fixed, non-selectable app identity, used for things like the app switcher and system permission dialogs — differs by package variant. On DazegramX (Unofficial), it's the Default icon art — the same art as the *Default* option in the Chat Settings > App Icon picker. On Dazegram (Official), it keeps its existing Blue icon, unchanged. This is separate from the launcher icon you pick under Chat Settings > App Icon: that picker still works exactly as before, on both variants, and changes what shows on your home screen through its own activity aliases. Choosing a different launcher icon there does not change this fixed `<application>` icon. Nagram Settings > General also has its own separate Notification Icon setting; stock Android honours it, but on the tested ColorOS device it appeared to be ignored, showing this fixed `<application>` icon in notifications instead — other versions, OEM skins, or notification configurations may differ.

<!-- Retired entries, plus sync-reconciliation and superseded feature slugs that have no catalog entry of their own.
     The behaviour still ships; it is documented in README instead of here.
     Slugs kept so old commits stay greppable and the catalog check keeps passing. -->
<!-- #nagram-sync -->
<!-- #tab-outline -->
<!-- #dazegram-icons -->
<!-- #update-checks-off -->
<!-- #metadata-channel-off -->
<!-- #solid-themes -->
<!-- The Transcription entries above were split from one commit tagged #transcribe-retry;
     #whisper-transcription and #groq-transcription-provider are new slugs for that split. -->
