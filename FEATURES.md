# Dazegram features

Extra bits I've added on top of [NagramX](https://github.com/risin42/NagramX). Most are on out of the box. Where something has a setting, I've said so.

<!-- House style: an entry is a definition, not a writeup. Max 70 words of prose
     under the ### heading (images and tables don't count); most sit around 35.
     What it does and where it is, then its setting and default. No edge cases,
     no failure behaviour, no rationale. Extending a feature means folding it
     into the existing entry and re-counting, never a second heading. -->

## Chats and privacy

### Per-chat time zones <!-- #timezones -->

Set a time zone for any personal chat or group from its profile edit view. The chat header and contacts list then show the peer's local time as a clock pill; tap it for a time converter you can drop into the message box as a formatted line. Scheduling a message to a chat with a zone set adds *My time* / *Peer's time* tabs to the picker.

<img height="200" alt="time zone picker sheet listing selectable IANA time zones under a search field" src="docs/images/features/timezones-1.png" />
<img height="200" alt="chat list row showing a peer's name with a clock pill giving their local time" src="docs/images/features/timezones-2.png" />

### Customized privacy <!-- #customized-privacy --> <!-- #hide-last-message --> <!-- #require-password --> <!-- #disguise-alerting -->

Each chat's ⋯ menu has one `Chat privacy` item, opening a sheet with `Hide last message` (with its own placeholder text for the chat list), `Require password`, and `Disguise notifications` — cover persona selection plus a preview button. Turning `Require password` on also switches hiding on if it was off. Covered notifications follow Telegram's own silent-vs-alert signal; mute, sound and watch tuning stay in Telegram and Android settings.

<img height="260" alt="Chat privacy sheet with Hide last message and Require password switched on, a custom placeholder text row between them, and the Disguise notifications card below" src="docs/images/features/chat-privacy-sheet.png" />

### Privacy profiles <!-- #privacy-profiles -->

Save sets of auto-lock timeouts under Nagram Settings → Passcode and switch between them, each with its own icon and colour. Activate a profile for now, for a stretch of time, or until a specific moment; long-press the Settings tab for a quick switcher. Changing the auto-lock timeout the normal way, restoring a backup, or clearing your passcode drops the active profile.

### Passcode setup safety <!-- #passcode-setup-safety -->

A Panic Code that matches an unlock code is a security risk, so setup now requires yours to be unique — it can't match your app passcode or any account's passcode. The setup screens also name which code you're setting (App, Panic, or Account). Codes set before this check existed may clash, so the settings screen will prompt you to re-set your Panic Code.

### Reply threads in private chats <!-- #personal-replies -->

Private chats now show a reply glyph and count next to a message's timestamp, bringing group-style reply threads to 1-to-1 chats. Tap the reply count to swap the history for the message and its replies without leaving the chat.

<img height="150" alt="private chat message bubble showing a reply glyph and count next to its timestamp" src="docs/images/features/reply-threads.png" />

### Message bookmarks <!-- #bookmarks-300 -->

Bookmarks come from NagramX; this fork raises the per-chat cap from 30 to 300. Saving from the message menu, per-account storage, and inclusion in settings backup and restore all work as they did.

### Ayu Mode shortcut <!-- #ayu-mode -->

A launcher shortcut (long-press the app icon, or pin it to your home screen) that opens the app with Ghost Mode already on. Tapping it flips every Ghost toggle you haven't locked and pushes you offline, then opens as normal.

### Hold messages while Ghost Mode is on <!-- #ghost-hold -->

Turn on **Hold Messages** under Settings → Ghost Mode (off by default) and, while Ghost Mode is active, plain text messages you send stay on your device instead of going to the server. They wait in that chat's Scheduled list captioned "Held — not sent". Switching Ghost Mode off asks you to confirm, then sends everything held. Attachments, paid chats and disappearing messages always go out right away.

<img height="260" alt="the Hold Messages toggle in its Ghost Mode section with the explanatory footer beneath it" src="docs/images/features/ghost-hold-setting.png" />
<img height="260" alt="a chat's Scheduled list with three held messages, each captioned Held — not sent" src="docs/images/features/ghost-hold-scheduled.png" />
<img height="260" alt="the Send held messages? confirmation shown when Ghost Mode is switched off with messages still held" src="docs/images/features/ghost-hold-turn-off.png" />
<img height="260" alt="a bulletin reading Held — won't send until Ghost Mode is off, shown above the composer straight after a send was held" src="docs/images/features/ghost-hold-bulletin.png" />

### Ghost icon stays put under stories <!-- #ghost-icon -->

With Ghost Mode on, the ghost indicator next to the chat list title stays visible even when contacts' stories collapse the header.

### Ghost send warning <!-- #ghost-send-warning --> <!-- #ghost-type-warning -->

Ghost Mode hides read receipts, typing and online status, but never holds a send back. The first time you type into an empty message box in a chat each Ghost session, you get a heads-up pointing you at Hold Messages, and that chat then stays quiet for the rest of the session. A chat you haven't typed into keeps warning as sends go out.

### Clear Message Database removes only this install's media <!-- #clear-db-own-media -->

Clear Message Database now removes only the media this install has database rows for, preventing a second install sharing the Downloads folder from having its media wiped.

### Keep a chat's messages off your watch <!-- #wear-messages -->

Each chat's Notifications screen (open a chat → its name → Notifications) has a **Show on Watch** switch under Message Preview, on by default. Turn it off and that chat's message notifications stop reaching a paired Wear OS watch, while the phone notification is unaffected. It isn't available on secret chats — set it on a forum's main chat to cover every topic.

## Composer and input

### Composer toolbar <!-- #composer-toolbar --> <!-- #composer-bubbles --> <!-- #toggle-formatting -->

The compose field sits in a glass text pill with Send or mic at its trailing end, and a row of action bubbles below it — Quote, Spoiler, Select All and Clear — which enable when there's text to act on.

Style buttons (Bold, Italic, Monospace, Strikethrough, Underline, Spoiler, Quote, Code) toggle off if you re-apply them to already-styled text, here or in the platform's selection popup.

<img height="180" alt="compose field with a phrase selected, so the action bubbles in the row below render enabled rather than greyed out" src="docs/images/features/composer-toolbar-live.png" />

### Send and mic inside the input <!-- #composer-input -->

Send and the mic sit inside the text pill, drawn slightly in from its rounded end so a thin ring of glass shows around them.

### Wallpaper pattern shows through the composer glass <!-- #glass-pattern -->

If your chat wallpaper has a pattern on it, it now reads through the glass composer panels as soft texture, not just the colour behind it — the more transparent you set the composer glass, the more of it shows. It follows the wallpaper as it changes, and the dimmed backdrop behind a round video recording shows it too.

### Composer toolbar layout editor <!-- #composer-layout --> <!-- #composer-layout-tap-toggle --> <!-- #composer-leading-2slot -->

The button row under the compose box is yours to arrange via chat settings. You can place any action in any zone (Leading, Scrolling, Trailing, Hidden). The Leading zone is capped at two slots. Tapping a row in Hidden or Scrolling toggles it straight to the other section without dragging. Hold any button on the live toolbar for about one second to open this editor directly.

<img height="260" alt="layout editor listing draggable toolbar buttons under a Leading zone capped at two slots, a drop hint, and the start of the Scrolling zone" src="docs/images/features/composer-layout-zones.png" />

### Attach button stays visible while typing <!-- #composer-attach-pinned -->

The attach paperclip remains visible on the toolbar even when the field is full of text, rather than swapping into the header overflow menu.

### Cut, Copy, Paste buttons <!-- #composer-clipboard-actions -->

Cut, Copy, and Paste are available as composer toolbar buttons, added through the layout editor.

### Composer toolbar size <!-- #composer-scale --> <!-- #composer-spacing -->

The toolbar row can be scaled from 75% to 125% in 5% steps. A second slider sets icon spacing, packing buttons closer without shrinking them. At small toolbar sizes the tightest spacing steps grey out so icons can't overlap.

<img height="260" alt="Toolbar size slider set to 90 percent above an Icon spacing slider set to 87 percent, each with its explanation below" src="docs/images/features/composer-toolbar-sliders.png" />

### Composer glass transparency <!-- #composer-transparency -->

Light and dark theme each get their own slider (0–50%, default 25%) in the layout editor for how much wallpaper shows through the composer's glass — the message field, its icon row, the floating buttons over the message list, and a channel's bottom bar. Takes effect when you leave the editor; nothing changes while chat blur is off.

<img height="220" alt="separate transparency sliders for light and dark theme, set to 35 and 15 percent, both on a 0 to 50 percent scale" src="docs/images/features/composer-transparency-sliders.png" />

### Quick schedule button <!-- #quick-schedule -->

The calendar icon is a one-tap schedule shortcut, instead of long-pressing Send.

### Floating input controls <!-- #input-satellites -->

The floating send-column layout is gone. The glass-fill helper stays with the close button in reply, edit, forward, and link-preview panels.

### Message input text size <!-- #input-text-size --> <!-- #composer-emoji-scale -->

The text you type in the compose box has its own size, separate from chat bubbles, adjusted via a slider in chat settings. Custom animated emoji scale with this slider.

### Physical keyboard hotkeys <!-- #keyboard-hotkeys -->

Matches Telegram Desktop bindings for BT/USB keyboards. Does nothing on software keyboards. Can be disabled in settings. Style shortcuts toggle off when re-run on already-styled text, same as the Composer toolbar buttons above.

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

<img height="180" alt="composer preview of quoted text pulled in from a message via Cite" src="docs/images/features/cite.png" />

### Scheduled message triggers <!-- #reschedule --> <!-- #eventschedule -->

Pick several scheduled messages and use *Reschedule* to move them all at once with a base time and interval. *Send on event* arms from the schedule picker or from bulk *Reschedule*: one trigger watches message type, text patterns, or both, and sends early while each message keeps its fallback schedule. Setups save as presets, and armed triggers live under Chats nav ⋯ → *Message Triggers*.

<img height="260" alt="bulk Reschedule sheet with a base time, per-message interval, and a delay slider" src="docs/images/features/reschedule.png" />
<img height="260" alt="Send on event trigger editor with By message type and Or by text collapsible sections" src="docs/images/features/trigger-editor.png" />
<img height="260" alt="Message Triggers list showing armed triggers with their message-type and text conditions" src="docs/images/features/message-triggers-list.png" />
<img height="260" alt="the Presets card listing one saved preset by name with a summary of its conditions, above a Save current as preset row" src="docs/images/features/trigger-presets-card.png" />

### Remember the schedule offset <!-- #schedule-remember -->

Tap the bookmark icon in the schedule picker to remember your schedule offset — new messages then default to that offset instead of 10 minutes ahead. Reschedule and Edit schedule pickers get the same delay slider, but keep opening on the message's existing time unless you drag it. Turn the bookmark off to revert to normal behavior.

### Tidier scheduled selection bar <!-- #scheduled-selection-toolbar -->

When you select messages in the scheduled view, the top bar keeps just Send Now, Reschedule, and Delete, tucking Copy and Forward into the overflow menu.

<img height="90" alt="scheduled-view selection toolbar showing Send Now, Reschedule, Delete and an overflow menu" src="docs/images/features/scheduled-selection-toolbar.png" />

### Forward scheduled messages <!-- #scheduled-forward -->

Telegram's forward API cannot forward a message that hasn't been sent yet. The selection bar's ⋯ → Forward now re-sends the picked scheduled messages instead, so they arrive as new scheduled messages. Media must be in the app's cache to be re-sent. Polls, locations, and contacts cannot be forwarded this way.

### Repost as Copy <!-- #repost-reply --> <!-- #repost-spread -->

*Repost as Copy* comes from NagramX — turn it on in settings (it's off by default) and it re-sends a message without a "Forwarded from" header, re-uploading the media. This fork keeps the original reply and quote, offers to delete the original once the repost lands, and spreads a scheduled repost across its own send times. Reposting a selection through *NoQuote* spaces the copies three minutes apart.

<img height="150" alt="Reposted as a copy confirmation bar with a Delete action for the original messages" src="docs/images/features/repost-copy.png" />

### Pin or number several messages at once <!-- #bulk-actions -->

Select messages to *Pin all* (applies your pin choice to the whole selection) or *Reply with numbers* to create an indexed table of contents via numbered replies.

<img height="260" alt="chat with several messages replied-to in sequence, each carrying a small index number" src="docs/images/features/bulk-actions.png" />

### Fullscreen message input <!-- #fullscreen-input -->

Tap the expand button to grow the input between the chat header and the keyboard. Tap it again to return to normal height.

<img height="260" alt="message input expanded to fill the space between the chat header and the keyboard, with a text formatting row along the bottom" src="docs/images/features/fullscreen-input-on.png" />

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

<img height="260" alt="round video message playing with its caption line rendered in a bubble below it" src="docs/images/features/video-cc-playback.png" />

### External microphone toggle in video message popup <!-- #external-mic -->

When camera mode is set to Ask, the camera choice popup has an External Microphone toggle to record through a headset instead of the built-in mic.

### Infinite video message <!-- #infinite-video -->

*Infinite Recording* stitches 60-second round video message segments end to end instead of stopping at the usual cap. Toggle it from the camera overlay while recording — off by default, and unavailable during slow mode, paid messages, secret chats, or view-once. N-Settings → Chat → Camera → *Infinite Recording cap* sets how long it can run before it stops itself: 10 (default), 15, 20, 30, 60 minutes, or Unlimited.

### Warning before a round video message hits its limit <!-- #video-limit-warning -->

Round video recordings warn you before they end. Configured via N-Settings → Chat → Camera, you get a light warning buzz 5 seconds before the cutoff, and a medium cutoff buzz when it actually lands.

### Smoother video message zoom <!-- #video-zoom -->

The zoom control under the round video camera was rebuilt with a full-range slider and step buttons.

### Scrub the video message preview <!-- #video-scrub -->

The preview you get after recording a round video message has a playback cursor you can drag to scrub through the video.

<img height="90" alt="post-recording preview strip with a discard button, a filmstrip carrying a draggable playback cursor at its midpoint, and a send button" src="docs/images/features/video-scrub-preview.png" />

### Bigger recorder pause and once buttons <!-- #recorder-controls -->

The pause button and the view-once "(1)" toggle are larger and lifted slightly higher off the send button to prevent accidental sends.

### Don't lose an unsent video message <!-- #video-draft-guard -->

A round video message you've recorded but haven't sent survives backing out of the chat, switching apps mid-recording, or the chat locking behind a passcode — the clip waits in the preview, trimmed the way you left it, for up to a day. What comes back after the app or chat was torn down is the trim strip and send button rather than the round preview itself.

### Custom file names for saved media <!-- #custom-file-names -->

Turn on *Custom File Names* (N-Settings → General → Storage) to save videos, voice, and round messages under the message's send date and time — `20260101_173812.mp4` by default — instead of Telegram's generic `video.mp4`. The setting's dialog lets you customize the pattern with `{date}`, `{time}`, and `{name}` (the sender's original filename), with a live preview as you type. Saved photos are unaffected.

### Send a muted gallery video as a real video <!-- #silent-video -->

Muting a video in the gallery editor before you send it used to force it into a low-quality looping GIF. Now the quality button stays live after you mute — tap it once and the clip becomes an ordinary silent video instead, sent at a quality you pick from the usual SD/HD sheet, with a real duration and scrubber. Unmute and mute again to go back to GIF.

## Transcription

### Whisper transcription controls <!-- #whisper-transcription -->

The Whisper (Workers AI) provider settings have options to skip silent audio and disable previous context to prevent repeated or made-up phrases.

### Groq transcription provider <!-- #groq-transcription-provider -->

A fast, free voice-to-text option that runs Whisper on Groq. Audio uploads have a 25 MB ceiling on the free tier.

### Retry or switch a transcription provider <!-- #transcribe-retry -->

If multiple providers are configured, the Retry option on a transcription becomes "Retry with…". You can also long-press the transcription button on a voice or round video message to stop a running attempt and open the provider list.

<img height="220" alt="Retry with... menu listing the configured transcription providers" src="docs/images/features/transcribe-retry.png" />

## Appearance

### Extera themes <!-- #extera-themes -->

Extera Light and Extera Dark bring [exteraGram](https://github.com/exteraSquad/exteraGram)'s look to Dazegram. The design is exteraSquad's. Only the palettes were rebuilt here — reverse-engineered rather than copied, and shipped as Monet-token theme assets — so the look, and the credit for it, stay theirs.

<img height="260" alt="chat rendered in the Extera Light theme" src="docs/images/features/extera-light.png" />
<img height="260" alt="the same chat rendered in the Extera Dark theme" src="docs/images/features/extera-dark.png" />

### Monet wallpaper pattern <!-- #monet-pattern-tile -->

You can apply a chat pattern over your live Material You color. Turn it off by opening the tile and clearing the pattern.

<img height="260" alt="chat background pattern tinted with the current Material You accent color" src="docs/images/features/monet-pattern.png" />

### Fixed app icon uses Default art on DazegramX <!-- #app-icon-fallback -->

On DazegramX (Unofficial), the app's fixed system-level icon — the one Android shows in the app switcher and permission dialogs — is the same *Default* art as the Chat Settings → App Icon picker's Default option. Dazegram (Official) keeps its usual Blue icon. Picking a launcher icon under Chat Settings → App Icon works as before on both variants.

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
