# NagramX features

Extra bits I've added on top of [NagramX](https://github.com/risin42/NagramX). Most are on out of the box. Where something has a setting, I've said so.

## Chats and privacy

### Per-chat time zones <!-- #timezones -->

Set a time zone for any personal chat or group through the profile edit view. The chat header and contacts list show the peer's current local time as a clock pill. Tap the pill to get a worldtimebuddy-style side-by-side time converter in a bottom sheet view. Each message also shows both your local time and the peer's.

<img height="200" alt="time zone feature showcase configuration" src="https://github.com/user-attachments/assets/606a0f24-1c3b-48d3-b013-d9782bb12854" />
<img height="200" alt="time zone feature showcase view etc" src="https://github.com/user-attachments/assets/00b9724e-e31d-4067-a095-ab26827b5710" />

### Hide last message <!-- #hide-last-message -->

Flag a chat from its in-chat ⋯ menu to swap its last message in the chat list for a placeholder, so someone glancing at your screen can't read it. The placeholder text is set per chat. The real message still shows when you open the chat.

### Require password <!-- #require-password -->

When an app passcode is set, flag a chat from its in-chat ⋯ menu to ask for the passcode (or fingerprint) before it opens — however you reach it: a tap, a notification, a shortcut, a search result, or a long-press peek. Turning it on also hides the chat's last message in the list. Once unlocked, the chat stays open until you background the app, then it asks again. The lock leans on the app passcode, so removing that passcode leaves the flagged chats open until you set one again.

### Ayu Mode shortcut <!-- #ayu-mode -->

A launcher shortcut (long-press the app icon, or pin it to your home screen) that opens the app with Ghost Mode already on. Tapping it flips every Ghost toggle you haven't locked and pushes you offline, then opens as normal — it only ever turns Ghost on, so it's safe to tap when you're already in it. It's named "Ayu Mode" so it doesn't give itself away on your home screen. If it doesn't show up after install, toggle Settings → Privacy and Security → "Suggest frequent contacts" off and on once to make it appear.

### Ghost icon stays put under stories <!-- #ghost-icon -->

With Ghost Mode on, the ghost indicator next to the chat list title used to vanish the moment contacts' stories collapsed the header. Now it stays visible in the collapsed header too, following the same Ghost mode status toggle.

## Composer and input

### Quick schedule button <!-- #quick-schedule -->

The calendar icon stays visible in the input bar while you type. One tap to schedule instead of long-pressing Send. Toggle it off in settings if you prefer the default.

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

Select text in a message and tap *Cite* to drop it into your input box as a quote block, formatting and all. Unlike the regular Quote option, it doesn't start a reply — the quote just becomes part of what you're typing, so you can cite a few messages and answer them all in one.

### Reschedule selected messages <!-- #reschedule -->

Pick several scheduled messages and move them all at once. Set a base time and an interval: the first lands on the base time, the next one an interval later, and so on in their current order. Intervals run from one minute up to days, with a live preview of the span before you confirm. The edits go to Telegram one at a time, and once they're all through the app reads the schedule back from the server: a popup confirms everything took, or tells you how many messages kept their old time so you can fix them before they fire. One caveat on ordering: Telegram's own scheduler can run a minute or two late, and when it catches up it sends whatever is overdue in roughly the order the messages were first written, not the times you set. That's out of the app's hands, so intervals under three minutes show a warning; if the exact order matters, give messages three minutes or more of spacing.

### Tidier scheduled selection bar <!-- #scheduled-selection-toolbar -->

When you select messages in the scheduled view, the top bar used to fill with so many buttons that the count of how many you'd picked got squeezed off-screen. Now it keeps just Send Now, Reschedule, and Delete (on the right) and tucks Copy and Forward into the overflow (⋯) menu. Send Now also asks for confirmation first, so a stray tap doesn't fire everything off early. Only the scheduled view changes; normal chats keep their usual selection bar.

### Fullscreen message input <!-- #fullscreen-input -->

Once your draft grows past two lines, a small expand button shows up in the top corner of the message box, next to the AI edit button. Tap it and the input grows to fill the space between the chat header and the keyboard, so you can read a long message all at once instead of scrolling inside a six-line box. The icon flips to a collapse arrow, and tapping again returns to normal. It doesn't stick around: it turns itself off when you send, clear the text, tap out of the box, or start recording a voice or video message. Formatting (bold, spoilers, custom emoji, links) and your cursor position stay put, since it's the same text box, just taller. It also works while editing a message, and the button keeps out of the way in landscape and split-screen.

### Don't lose typed text on an accidental back <!-- #discard-guard -->

Two spots used to throw away what you'd typed if you swiped back by accident, because neither keeps a draft: composing in the scheduled view, and editing an existing message. Now, while there's unsaved text in either, the swipe-back gesture is held so it can't quietly drop you out, and pressing back (the arrow or the system button) asks first — *Discard message?* when composing a scheduled message, *Discard changes?* when you've edited one — so a stray gesture can't throw the text away. Leave the box empty, or edit a message without actually changing its text or formatting, and back goes straight through as before. Normal composing is untouched — those drafts already save on their own.

## Media and camera

### Video message playback modes <!-- #video-playback-modes -->

A button in the player bar cycles how round video messages play — *play once* (stop after the current one), *play all* (keep going through the chat's video messages, the default), or *repeat one* (loop the current one). The choice is remembered.

### Mute video messages <!-- #video-mute -->

A mute button next to the playback-mode toggle silences round video messages while you keep watching. It works with the playback modes, so you can loop or play through a chat's video messages with the sound off. The setting sticks until you switch it back.

### External microphone toggle in video message popup <!-- #external-mic -->

When camera mode is set to Ask, the camera choice popup now has an External Microphone toggle at the top. Flip it to record through a wired or Bluetooth headset instead of the phone's built-in mic, without digging through Settings. It's the same setting you'd find in Settings, so it sticks across recordings.

### Smoother video message zoom <!-- #video-zoom -->

The zoom control under the round video camera was rebuilt with a full-range slider and a − / + pair that steps the zoom one notch at a time (hold to keep going). Two flags live in NagramX chat settings, under Camera: *Reset zoom when switching cameras* starts the zoom fresh each time you flip cameras instead of carrying it over, and *Hardware smooth zoom* hands the animation to the camera itself for a smoother glide — the app checks whether your phone supports it when you flip it on.

### Scrub the video message preview <!-- #video-scrub -->

The preview you get after recording a round video message used to have only the two trim handles on its timeline, so checking a spot near the end meant dragging a handle there and remembering to drag it back. The timeline now has a playback cursor too. It follows the playback, and you can drag it to scrub: the video pauses while you hold it, seeks as you move, and resumes from where you let go. Tapping anywhere between the handles jumps straight there. While you scrub, the same time tooltip the trim handles use shows the position. Trimming itself hasn't changed.

## Transcription

### Whisper transcription controls <!-- #transcribe-retry -->

The Whisper (Workers AI) provider kept inventing names and stray comments during quiet parts of a message. Its settings now carry a few knobs for exactly this, shown only while Whisper (or Auto, which can fall back to it) is selected: pick a language or leave it on Auto-detect, turn on voice activity detection to skip silent and non-speech audio, turn off "use previous context" so it stops repeating or making up phrases, and a slider to skip silences longer than a set number of seconds (off by default). The first two default to the pairing that keeps most of the made-up text out.

### Groq transcription provider <!-- #transcribe-retry -->

A new free voice-to-text option that runs Whisper on Groq, which is fast and does well with Russian. Pick "Groq (Whisper)" and paste an API key (the key row links straight to console.groq.com where you get one). Groq says it doesn't train on your audio, so it sits better with private messages than the free tiers that learn from whatever you send. Two extra rows show up while it's selected: a model choice between accurate and faster, and a language you can pin or leave on Auto-detect. Uploads stop at 25 MB on the free tier, so a very long voice message will ask you to trim it or switch providers.

### Retry a transcription with another provider <!-- #transcribe-retry -->

If you have two or more providers set up with working credentials, the Retry option on a transcription becomes "Retry with…" and lists just those configured providers, so you can bounce a garbled message off a different service without opening settings first. Whichever one you pick becomes your new default too, so a plain Retry keeps using it afterward. With one provider (or none) set up it stays a plain "Retry" that re-runs straight away.

### Long-press the transcription button to switch providers <!-- #transcribe-retry -->

The same "Retry with…" list is one press away on the message itself. Hold the "A" transcription button on a voice or round video to stop whatever attempt is running (handy when a provider hangs and the spinner won't quit) and open the provider list right there. It only kicks in with your own providers; with one set up it just re-runs.
