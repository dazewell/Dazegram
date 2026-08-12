# NagramX features

Extra bits I've added on top of [NagramX](https://github.com/risin42/NagramX). Most are on out of the box. Where something has a setting, I've said so.

## Chats and privacy

### Per-chat time zones <!-- #timezones -->

Set a time zone for any personal chat or group through the profile edit view. The chat header and contacts list show the peer's current local time as a clock pill. Tap the pill to get a worldtimebuddy-style side-by-side time converter in a bottom sheet view. Slide the strip under the fixed centre cursor to line up a moment in both zones down to 15-minute steps, or hit "Now" to jump back to the current time. From that sheet you can drop the lined-up time into the message box, with a live preview of what will land there. The template it renders from sits behind a collapsible *Message format* section, so the sheet stays about the times: open it to tap tokens like `{peer_time}` or `{offset}` into the template. You can also pick the language the message renders in, handy when you write in a language other than your app's, so weekday names and the default wording come out in it. The template and language save for this account or across all of them. Next to "Now" there's a *Range* pill for when you need a window instead of a moment. The time you're on becomes the start, the end lands an hour later, and the strip shifts so you're holding that end — slide on to stretch it. The span gets shaded across both lanes, each edge gets its own row, and the length sits at the end of the second one; tap the row you aren't holding to move that edge instead, and tap *Range* again to go back to a single moment. Ranges render from their own format with their own tokens (`{my_range}`, `{duration}` and the rest), and the *Message format* section follows whichever kind you're picking, so nothing extra shows up until you need it. Each message also shows both your local time and the peer's. When you schedule a message to a chat with a zone set, the schedule sheet gains a *My time* / *Peer's time* tab above the picker wheels — switch to *Peer's time* to dial the moment in on their clock instead of yours, and the line under the wheels always shows the other side, with the date when it falls on a different day.

<img height="200" alt="time zone feature showcase configuration" src="https://github.com/user-attachments/assets/606a0f24-1c3b-48d3-b013-d9782bb12854" />
<img height="200" alt="time zone feature showcase view etc" src="https://github.com/user-attachments/assets/00b9724e-e31d-4067-a095-ab26827b5710" />

### Hide last message <!-- #hide-last-message -->

Flag a chat from its in-chat ⋯ menu to swap its last message in the chat list for a placeholder, so someone glancing at your screen can't read it. The placeholder text is set per chat. The real message still shows when you open the chat.

### Require password <!-- #require-password -->

When an app passcode is set, flag a chat from its in-chat ⋯ menu to ask for the passcode (or fingerprint) before it opens — however you reach it: a tap, a notification, a shortcut, a search result, or a long-press peek. Turning it on also hides the chat's last message in the list. Once unlocked, the chat stays open until you background the app, then it asks again. The lock leans on the app passcode, so removing that passcode leaves the flagged chats open until you set one again.

### Privacy profiles <!-- #privacy-profiles -->

Save a set of auto-lock timeouts under Nagram Settings > Passcode and switch between them without digging back into the timeout picker each time. A profile is just a name and one of the stock auto-lock values — it never touches the passcode itself, biometrics, panic code, screen capture, or any per-chat lock. Device-wide, so it applies across every account.

Activate a profile for now, for a stock stretch (1 hour, 8 hours, a day), for a custom duration, or until a specific date and time — a timed activation quietly reverts to whatever timeout was active before once it expires, no need to remember to switch back. The presets sit directly in a profile's own menu, and "For a custom time…" opens a plain hours/minutes picker for anything in between, capped at 7 days; the last custom duration you set is remembered per profile and offered as its own row next time. Long-press the Settings tab for the "Auto-lock profile" list — a *No profile* entry plus every profile you've saved, exactly one of them ticked — and flip between them without opening Passcode settings at all, as long as a passcode is set and at least one profile exists. While a profile is active, the Settings tab carries that profile's own icon, and Nagram Settings shows its name next to *Passcode*, so you can tell which one is on at a glance; turn the tab icon off with *Show the active profile on the Settings tab* if you'd rather it stayed private. Each profile gets its own icon, picked from the same icon grid used for chat folders, shown instead of a plain initial everywhere a profile appears. Each profile can also be pinned to your home screen as its own launcher shortcut carrying that icon, so tapping it activates that profile and opens the app in one step; saving a new profile offers to pin it right away. Changing the auto-lock timeout through the regular picker, restoring a settings backup, or clearing your passcode on last logout all drop whatever profile was active and adopt the new value as the baseline to return to.

### Reply threads in private chats <!-- #personal-replies -->

Groups show a small reply glyph and a count on a message that's been replied to, and let you open those replies on their own. One-to-one chats never got that, because Telegram only keeps a reply counter for groups. This works it out from the history already on your phone: a message someone has replied to picks up the same glyph and count next to its timestamp, and its menu gains *View N Replies*.

Tapping that swaps the history for the message and its replies without leaving the chat — the header says how many, and the compose box stays where it is, so you can answer from inside the view. A reply you send there shows up once you go back. Back, or the arrow in the header, puts the normal history back where you left it. Tapping a reply's quote jumps to the original in the list.

It counts what's stored on this device, so a reply that was never downloaded, or one you've since cleared out of the cache, won't show up. Only messages someone actually wrote count — the notices the chat posts itself, like *pinned a message*, name the message they're about but aren't replies to it. Deleted messages that Ghost Mode keeps around aren't counted either — they're gone from Telegram's own cache, which is what this reads. On by default; turn it off under *Reply threads in private chats* in chat settings.

### Message bookmarks <!-- #bookmarks-300 -->

Save up to 300 messages per chat from the message menu. Bookmarks keep their existing per-account storage and are included in settings backup and restore.

### Ayu Mode shortcut <!-- #ayu-mode -->

A launcher shortcut (long-press the app icon, or pin it to your home screen) that opens the app with Ghost Mode already on. Tapping it flips every Ghost toggle you haven't locked and pushes you offline, then opens as normal — it only ever turns Ghost on, so it's safe to tap when you're already in it. It's named "Ayu Mode" so it doesn't give itself away on your home screen.

### Ghost icon stays put under stories <!-- #ghost-icon -->

With Ghost Mode on, the ghost indicator next to the chat list title used to vanish the moment contacts' stories collapsed the header. Now it stays visible in the collapsed header too, following the same Ghost mode status toggle.

## Composer and input

### Composer toolbar <!-- #composer-toolbar -->

The separate glass text pill keeps its renderer 16dp from both logical edges and holds Send or mic at its trailing end. A shared glass action capsule sits below it: emoji stays at one edge, attachments holds the other with expand and schedule beside it, and the controls between them scroll only when needed. Quote, Spoiler, Select All, and Clear stay in the toolbar. Quote, Spoiler, and Clear enable when text is selected; Select All enables whenever the field has text and selects the whole draft. Monospace is gone from the row (still there in the long-press selection menu). Toggle any of them off in settings.

### Send and mic inside the input <!-- #composer-input -->

Send and the mic used to sit outside the text pill, in their own column past its end. Now the pill runs the full width of the row and they sit inside it, drawn a few dp in from its rounded end so a thin ring of the pill's glass shows all the way around them. The text stops 16dp short of them on every line, matching the inset on the start side, so nothing runs underneath and the row reads even on both sides. Same buttons, same gestures, same long-press menu.

### Composer toolbar layout editor <!-- #composer-layout --> <!-- #composer-layout-tap-toggle -->

The button row under the compose box is yours to arrange. A new editor in the chat settings lists every button — emoji, attachments, expand, schedule, rich draft, AI, all of the formatting actions (bold, italic, underline, strikethrough, monospace, code block, spoiler, quote, link, and clear formatting) and a new Select All — and you drag them into the order you want. Three zones: one leading button pinned at the start, a scrolling middle that holds as many as you like, and a trailing group at the end where attachments live because it carries its own show/hide behaviour. Schedule can now go in the scrolling middle too, not just trailing — it has no positioning that requires the edge. Anything you don't want goes in the Hidden section and stops taking up space. Reset puts it back to the stock arrangement. Your existing toolbar carries over on upgrade, so nothing moves until you move it. Tapping a row in Hidden or in the scrolling Middle section now toggles it straight to the other one, a shortcut for the common move without dragging across a long list; the leading and trailing zones stay drag-and-drop only since they're capacity/anchor constrained. Dragging a button up and out of Hidden also no longer risks snapping it into the leading slot the instant it scrolls near the top — that swap now needs a brief, deliberate hold to actually commit. Hold any button on the live toolbar for about one second to open this editor directly. The preview above the list now dims AI, rich draft, expand and schedule to show they only appear in a real chat once its own state allows it (an active draft, room to expand, pending scheduled messages) — they aren't unconditionally on the row the way emoji or attachments are.

### Cut, Copy, Paste buttons <!-- #composer-clipboard-actions -->

Cut, Copy, and Paste are available as composer toolbar buttons, same as Select All and the formatting actions, added through the layout editor. Cut and Copy enable when text is selected and act on that selection; Paste works whenever the field is open, dropping the clipboard's contents at the cursor or over the selection. They reuse the same clipboard handling as the long-press selection menu. Hidden by default — turn them on in the layout editor if you want them in the row.

### Composer toolbar size <!-- #composer-scale -->

The toolbar row can be scaled from 75% to 125% in 5% steps, from the standard settings slider at the top of the layout editor. It scales the whole thing together — the row height, the button cells and the glyphs inside them — so the capsule keeps its proportions and its rounded ends at every size, rather than just spacing the same buttons further apart. The preview above the slider shows the real toolbar and a small input area on your own chat wallpaper, so you can judge the scale in context as you drag. Defaults to 100%, and Reset puts it back.

### Quick schedule button <!-- #quick-schedule -->

The calendar icon stays with the trailing composer actions while you type. One tap to schedule instead of long-pressing Send. Toggle it off in settings if you prefer the default. It no longer takes width from the message text.

### Floating input controls <!-- #input-satellites -->

The floating send-column layout is gone. The glass-fill helper stays with the close button in reply, edit, forward, and link-preview panels, plus special primary-action states that need their own surface.

### Message input text size <!-- #input-text-size --> <!-- #composer-emoji-scale -->

The text you type in the compose box has its own size, separate from the chat bubbles. A slider in the chat settings (right under the quick schedule toggle) runs from 14 to 20 — the default 18 matches stock Telegram, and dropping it a couple of points fits more of a long draft on screen. It's one size in every state now: the box uses your chosen size whether it's the normal compose row, expanded fullscreen, or an edit, so the text doesn't change size as the box grows. Emoji follow the same size: custom animated emoji rescale with the slider instead of keeping the size they had when they were inserted, and they stay inside their own line rather than overlapping the line above.

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

Select text in a message and tap *Cite* to drop it into your input box as a quote block, formatting and all. Unlike the regular Quote option, it doesn't start a reply — the quote just becomes part of what you're typing, so you can cite a few messages and answer them all in one. If you've already typed something, a citation gets a blank line before it so it doesn't run into your text, and your cursor always lands on a fresh line below the quote.

### Reschedule selected messages <!-- #reschedule -->

Pick several scheduled messages and move them all at once. Set a base time and an interval: the first lands on the base time, the next one an interval later, and so on in their current order. Intervals run from one minute up to days, with a live preview of the span before you confirm. The edits go to Telegram one at a time, and once they're all through the app reads the schedule back from the server: a popup confirms everything took, or tells you how many messages kept their old time so you can fix them before they fire. One caveat on ordering: Telegram's own scheduler can run a minute or two late, and when it catches up it sends whatever is overdue in roughly the order the messages were first written, not the times you set. That's out of the app's hands, so intervals under three minutes show a warning; if the exact order matters, give messages three minutes or more of spacing.

### Send a scheduled message early on an event <!-- #eventschedule -->

When you schedule a message, the picker now has a *Send on event* row. Tap it and you can set a trigger: pick which kinds of incoming message should count (voice, video message, video, photo, text — any combination), and/or a text pattern to match against the message or its caption. The pattern is a simple glob by default (`*` matches anything, `?` one character) and it looks for a hit anywhere in the message, so a bare word fires on anything containing that word — both `*` and `?` reach across line breaks, so a code block or a quote doesn't hide it. Flip on *Regular expression* for a real regex; that's a substring search too, and it's how you pin a pattern to the whole text if you want that (`^…$`). You need at least one condition; if you set both a type and a pattern, either one matching is enough to fire — the pattern is checked on any incoming message regardless of its type, so a caption match works even without the Text message toggle. The message still gets a normal schedule date in the same sheet — that's the fallback: if nothing triggers it, the server just sends it at that time as usual. When a matching message lands in that chat while the app is running, the scheduled one goes out right then, or after a short delay you can pick (up to five minutes). Messages with the exact same trigger are sent one at a time in their scheduled-page order; each message's delay holds the queue before the next one starts. In groups and channels anything from someone else can trigger it; your own messages don't. Armed messages show a small bolt next to their time in the scheduled view, and long-pressing one lists the trigger so you can tap through and change or remove it (that reopens the same schedule sheet). The last trigger you set up is remembered and prefilled for the next one. Two things to keep in mind: triggers only work while the app is running (otherwise the message just goes out at its scheduled time), and this is separate from the premium repeat option — a repeating schedule stays a plain schedule.

### Remember the schedule offset <!-- #schedule-remember -->

The schedule sheet's *Default delay* slider always opens the picker the same distance from now — ten minutes by default, with additional stops at one, three, six, and twelve months. Those month choices are fixed 30-, 90-, 180-, and 365-day delays, and twelve months is the longest Telegram accepts. Next to the *Schedule* button at the bottom there's now a small bookmark button; long-press it and a hint says what it's for. Turn it on and the picker stops using the slider: it opens on however far ahead you last scheduled something. Set a message for two hours out and the next sheet opens two hours out too. The label above the slider switches to *Remembered delay* the moment you tap the button, so the tap has something to show for itself — and if nothing's saved yet, a hint says it takes the next delay you schedule and keeps opening on the slider's until then. Once there is an offset the label carries it and the slider dims, so you know what's coming before the wheels even settle. With the bookmark on, that label follows the wheels as you spin them, counting the delay you're dialling in — so what it reads is exactly what gets saved when you confirm. Switch the bookmark off and the label goes back to being the slider's, unchanged from before. It's the offset that's kept, not the clock time, so it moves with you through the day. Dragging the slider is a fresh choice of delay, so it turns the bookmark back off and drops what was saved; tapping the bookmark off drops it as well. The bookmark is in the reschedule sheets too, both for a single message and for a bulk *Reschedule selected*, but there it only saves: those sheets always open on the time the message already has, and confirming one keeps that delay for the next picker like any other schedule does. They have no slider, so there's no delay label above the wheels — the button being lit is the state, and its long-press hint reads for the sheet you're in.

### Tidier scheduled selection bar <!-- #scheduled-selection-toolbar -->

When you select messages in the scheduled view, the top bar used to fill with so many buttons that the count of how many you'd picked got squeezed off-screen. Now it keeps just Send Now, Reschedule, and Delete (on the right) and tucks Copy and Forward into the overflow (⋯) menu. Send Now also asks for confirmation first, so a stray tap doesn't fire everything off early. Only the scheduled view changes; normal chats keep their usual selection bar.

### Forward scheduled messages <!-- #scheduled-forward -->

Forward from the scheduled view. Telegram can't forward a message that hasn't been sent yet — its Forward button either sat greyed out or, when it did go through, the copies landed as failed sends with a red "!" that kept erroring on retry. Now the selection bar's ⋯ → Forward re-sends the picked messages instead of asking the server to forward them, so they arrive as new scheduled (or sent-now) messages: pick a chat, and you get the usual forward box to set the time. Handy for repeating something you've already lined up — a 1:1 chat back into itself, or Saved Messages into Saved Messages. Since they're re-sent, they come without a "forwarded from" header; that costs nothing here, as scheduled messages are always your own. Media has to be in the app's cache to be re-sent, so anything not downloaded yet says so rather than failing silently. Forward is offered only for what can be re-sent this way — polls, locations and contacts stay out. One related fix: when you attach a comment to a forward you're scheduling and *Send Comment After Forwarding* is on, the comment used to land a second ahead of the messages it was meant to follow; it now sits after them.

### Pin or number several messages at once <!-- #bulk-actions -->

Select a few messages and the selection bar's ⋯ menu gets two new actions. *Pin all* pins everything you picked using the same dialog a single message gives you (the notify option, and pin-for-both in a private chat), applying that one choice to the whole selection; it pins oldest to newest so the latest lands on top of the stack. *Reply with numbers* posts a short reply to each selected message, numbered in order. A reply is the one reference Telegram maps to the other person's side, so unlike a link to a private-chat message it doubles as a table of contents the peer can tap through to jump between the messages. Before sending it asks for the number to start counting from (1 by default), so you can carry on an index across several batches. Those replies go out silently, so a long index doesn't ping them once per entry. Both actions run one message at a time rather than all at once, to hold the order and stay under Telegram's flood limits. *Pin all* appears wherever you're allowed to pin; *Reply with numbers* wherever you can send.

### Fullscreen message input <!-- #fullscreen-input -->

The toolbar keeps its expand button, sitting with the pinned trailing actions next to attachments and schedule so it stays put instead of scrolling away with the rest of the controls. It's there whenever the normal composer toolbar is (the rich draft editor still takes its slot). Tap it to grow the input between the chat header and the input method; tap it again to return to the normal height. It works with the keyboard down too: the tap raises the keyboard and the field expands as soon as there's room. The expanded state itself ends when the draft is cleared, focus leaves the field, the keyboard and emoji panel are both gone, or recording starts, but the button stays where it is. Formatting and cursor position stay put because it is the same text box.

### Don't lose typed text on an accidental back <!-- #discard-guard -->

Two spots used to throw away what you'd typed if you swiped back by accident, because neither keeps a draft: composing in the scheduled view, and editing an existing message. Now, while there's unsaved text in either, the swipe-back gesture is held so it can't quietly drop you out, and pressing back (the header arrow or the system button) asks first — *Discard message?* when composing a scheduled message, *Discard changes?* when you've edited one — so a stray gesture can't throw the text away. Leave the box empty, or edit a message without actually changing its text or formatting, and back goes straight through as before. An in-progress edit — and text you're composing in the scheduled view — also survives an app-lock or minimize: if the app locks (or you switch away and come back) while there's unsaved text, it's put back when you return. Normal composing is untouched — those drafts already save on their own.

## Media and camera

### Recording mode hint <!-- #media-tooltip-repeat -->

The hint that explains how to switch between voice and round-video recording appears only a few times, then stays out of your way.

### Video message playback modes <!-- #video-playback-modes -->

A button in the player bar cycles how round video messages play — *play once* (stop after the current one), *play all* (keep going through the chat's video messages, the default), or *repeat one* (loop the current one). The choice is remembered.

### Mute video messages <!-- #video-mute -->

A mute button next to the playback-mode toggle silences round video messages while you keep watching. It works with the playback modes, so you can loop or play through a chat's video messages with the sound off. The setting sticks until you switch it back.

### Closed captions on video messages <!-- #video-cc -->

Transcribing a round video message normally swaps the circle out for a text bubble, so you either watch the person or read what they said. A CC button sits above the transcribe button on video messages and changes that: tap it and the message transcribes, starts playing, and the text shows up as a caption strip floating just above the message box, line by line as the video runs. Nothing covers the face and the strip stays put while the chat scrolls. It spins while it's waiting on the transcription, the same as the transcribe button does.

There's nothing to switch off: CC captions the play it starts and then lets go, so watching the message again is a normal tap and tapping CC is how you ask for captions again. The transcribe button is untouched: it still opens the text bubble the way it always has. Captions only ever come from a transcription you asked for, so nothing quietly costs you API credit for playing a video.

Providers that return timings (Groq, Cloudflare Workers AI) get captions that line up with the speech. The ones that only return prose (Gemini, OpenAI-compatible chat, and Telegram's own premium transcription) get the text split into lines and paced across the video's length, so it still reads along instead of sitting there as one frozen block.

### External microphone toggle in video message popup <!-- #external-mic -->

When camera mode is set to Ask, the camera choice popup now has an External Microphone toggle at the top. Flip it to record through a wired or Bluetooth headset instead of the phone's built-in mic, without digging through Settings. It's the same setting you'd find in Settings, so it sticks across recordings.

### Infinite video message <!-- #infinite-video -->

Round video messages stop dead at 60 seconds, which is annoying when you're mid-sentence. With camera mode set to Ask, the camera choice popup has an *Infinite Recording* toggle under the microphone one. Turn it on and the 60-second mark stops being a wall: the recorder closes off that minute, sends it as its own round message, and carries straight on into the next one. The camera doesn't blink and the mic never restarts, so a Bluetooth headset stays connected through the handover. You stop it the normal way, by lifting your finger or tapping send, and the last piece goes through like any other video message. There's a ceiling of ten minutes, after which it stops on its own and leaves the final piece in the preview for you.

Unlike the microphone toggle, this one is off every time you start a recording. It keeps sending on its own, so it should be a decision you make each time rather than something you forget is on. It's also greyed out where sending unattended wouldn't work: slow mode, paid messages, scheduled messages, secret chats, and when view-once is armed. If you arm view-once or slow mode kicks in partway through, the recording just stops at the next minute the way it normally would.

You don't have to decide up front, though. The round camera's button row (next to the flash) carries the same toggle while the recording runs, so you can flip infinite mode on when you realise halfway through that a minute won't be enough — and off again if you change your mind, in which case the recording just runs to the 60-second mark and lands in the preview as usual. You'll normally reach it after locking the recording, since while your finger is still on the record button that button owns the gesture, the same as the flash. Anything already sent stays sent. The button is white-filled while infinite mode is on, and it disappears where the mode can't run: the wrong kind of chat, view-once armed, or the tenth and last minute, where there's nothing left to roll over into. It shows up in the fixed Front and Rear camera modes too, which never get the popup at all.

### Smoother video message zoom <!-- #video-zoom -->

The zoom control under the round video camera was rebuilt with a full-range slider and a − / + pair that steps the zoom one notch at a time (hold to keep going). Two flags live in NagramX chat settings, under Camera: *Reset zoom when switching cameras* starts the zoom fresh each time you flip cameras instead of carrying it over, and *Hardware smooth zoom* hands the animation to the camera itself for a smoother glide — the app checks whether your phone supports it when you flip it on.

### Scrub the video message preview <!-- #video-scrub -->

The preview you get after recording a round video message used to have only the two trim handles on its timeline, so checking a spot near the end meant dragging a handle there and remembering to drag it back. The timeline now has a playback cursor too. It follows the playback, and you can drag it to scrub: the video pauses while you hold it, seeks as you move, and resumes from where you let go. Tapping anywhere between the handles jumps straight there. While you scrub, the same time tooltip the trim handles use shows the position. Trimming itself hasn't changed.

### Bigger recorder pause and once buttons <!-- #recorder-controls -->

The pause button and the view-once "(1)" toggle that stack above the send button while you record a voice or round video message were small and sat close to it, so reaching for them often caught the send button instead. They're larger now and lifted a bit higher off the send button, in both voice and video recording.

## Transcription

### Whisper transcription controls <!-- #transcribe-retry -->

The Whisper (Workers AI) provider kept inventing names and stray comments during quiet parts of a message. Its settings now carry a few knobs for exactly this, shown only while Whisper (or Auto, which can fall back to it) is selected: pick a language or leave it on Auto-detect, turn on voice activity detection to skip silent and non-speech audio, turn off "use previous context" so it stops repeating or making up phrases, and a slider to skip silences longer than a set number of seconds (off by default). The first two default to the pairing that keeps most of the made-up text out.

### Groq transcription provider <!-- #transcribe-retry -->

A new free voice-to-text option that runs Whisper on Groq, which is fast and does well with Russian. Pick "Groq (Whisper)" and paste an API key (the key row links straight to console.groq.com where you get one). Groq says it doesn't train on your audio, so it sits better with private messages than the free tiers that learn from whatever you send. Two extra rows show up while it's selected: a model choice between accurate and faster, and a language you can pin or leave on Auto-detect. Uploads stop at 25 MB on the free tier, so a very long voice message will ask you to trim it or switch providers.

### Retry a transcription with another provider <!-- #transcribe-retry -->

If you have two or more providers set up with working credentials, the Retry option on a transcription becomes "Retry with…" and lists just those configured providers, so you can bounce a garbled message off a different service without opening settings first. Whichever one you pick becomes your new default too, so a plain Retry keeps using it afterward. With one provider (or none) set up it stays a plain "Retry" that re-runs straight away.

### Long-press the transcription button to switch providers <!-- #transcribe-retry -->

The same "Retry with…" list is one press away on the message itself. Hold the "A" transcription button on a voice or round video to stop whatever attempt is running (handy when a provider hangs and the spinner won't quit) and open the provider list right there. It only kicks in with your own providers; with one set up it just re-runs.
