# Nagram X
[![Crowdin](https://badges.crowdin.net/NagramX/localized.svg)](https://crowdin.com/project/NagramX)  
A variant of [Nagram](https://github.com/NextAlone/Nagram) with additional features.

**This is a personal fork** by [@dazewell](https://github.com/dazewell) with a few extras on top of [NagramX](https://github.com/risin42/NagramX):

 - **Per-chat time zones:** set a time zone for any personal chat or group through the profile edit view. The chat header and contacts list show the peer's current local time as a clock pill. Tap the pill to get a worldtimebuddy-style side-by-side time converter in a bottom sheet view. Each message also shows both your local time and the peer's.

   <img height="200" alt="time zone feature showcase configuration" src="https://github.com/user-attachments/assets/606a0f24-1c3b-48d3-b013-d9782bb12854" />
   <img height="200" alt="time zone feature showcase view etc" src="https://github.com/user-attachments/assets/00b9724e-e31d-4067-a095-ab26827b5710" />

 - **Quick schedule button:** the calendar icon stays visible in the input bar while you type. One tap to schedule instead of long-pressing Send. Toggle it off in settings if you prefer the default.

 - **Physical keyboard hotkeys:** matches Telegram Desktop bindings for BT/USB keyboards. Does nothing on software keyboards. Can be disabled in settings.

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

 - **Hide last message:** flag a chat from its in-chat ⋯ menu to swap its last message in the chat list for a placeholder, so someone glancing at your screen can't read it. The placeholder text is set per chat. The real message still shows when you open the chat.

 - **Require password:** when an app passcode is set, flag a chat from its in-chat ⋯ menu to ask for the passcode (or fingerprint) before it opens. It doesn't matter how you get there: a tap, a notification, a shortcut, a search result, or a long-press peek all hit the same lock. Turning this on also hides the chat's last message in the list. Once you unlock the chat it stays open until you put the app in the background, then it asks again. Since the lock leans on the app passcode, removing that passcode leaves the flagged chats open again; they lock back up on their own once you set a passcode.

 - **Video message playback modes:** a button in the player bar cycles how round video messages play — *play once* (stop after the current one), *play all* (keep going through the chat's video messages, the default), or *repeat one* (loop the current one). The choice is remembered.

 - **Mute video messages:** a mute button next to the playback-mode toggle silences round video messages while you keep watching. It works with the playback modes, so you can loop or play through a chat's video messages with the sound off. The setting sticks until you switch it back.

 - **External microphone toggle in video message popup:** when camera mode is set to Ask, the camera choice popup now has an External Microphone toggle at the top. Flip it to record through a wired or Bluetooth headset instead of the phone's built-in mic, without digging through Settings. The switch reflects the same setting as the one in Settings and persists across recordings.

 - **Reschedule selected messages:** pick several scheduled messages and move them all to a new time at once. Set a base time and an interval: the first message lands on the base time, the next one an interval later, and so on in their current order. Intervals run from 15 seconds up to days. There's a live preview of the resulting span before you confirm, so you can re-queue a whole batch without editing each one by hand.

 - **Cite:** select text in a message and tap *Cite* to drop it into your input box as a quote block on a new line. Formatting from the original (bold, italic, links, custom emoji) is kept, the same as if you'd copied and pasted it yourself. Unlike the regular Quote option, it doesn't start a reply — the quote just becomes part of what you're typing, so you can cite a few different messages and answer all of them in one long message.

 - **Ayu Mode shortcut:** a launcher shortcut (long-press the app icon, or pin it to your home screen) that opens the app with Ghost Mode already on. Tapping it flips every Ghost toggle you haven't locked and pushes you offline right away, then the app opens as normal. It only ever turns Ghost on, so it's safe to tap when you're already in Ghost. It's named "Ayu Mode" rather than anything obvious so it doesn't give itself away on your home screen. If it doesn't show up right after install, flip Settings → Privacy and Security → "Suggest frequent contacts" off (and back on if you want it) once to make it appear.

 - **Ghost icon stays put under stories:** with Ghost Mode on, the ghost indicator next to the chat list title used to vanish the moment contacts' stories collapsed the header, and only came back when you pulled the list down. Now it stays visible in the collapsed header too, sitting where your emoji or premium status would be. It follows the same Ghost mode status toggle, so it shows in both spots or neither.

 - **Whisper transcription controls:** the Whisper (Workers AI) voice-to-text provider kept inventing names and stray comments during quiet parts of a message, which is just how the model behaves on silence. Its settings now carry a few knobs Cloudflare exposes for exactly this, and they only appear while Whisper (or Auto, which can fall back to it) is the selected provider. You can pick a language or leave it on Auto-detect, switch on voice activity detection to skip silent and non-speech audio, switch off "use previous context" so it stops repeating or making up phrases, and drag a slider to skip silences longer than a set number of seconds (off by default). Voice activity detection starts on and previous-context starts off, since that pairing is what keeps most of the made-up text out.

 - **Groq transcription provider:** a new free voice-to-text option that runs Whisper on Groq, which is fast and does well with Russian. Pick "Groq (Whisper)" as the provider and paste an API key (the key row links straight to console.groq.com where you get one). Groq says it doesn't train on your audio and only holds requests briefly for abuse checks, so it sits better with private messages than the free tiers that learn from whatever you send. Two extra rows show up while it's selected: a model choice between accurate (whisper-large-v3) and faster (the turbo variant), and a language you can pin or leave on Auto-detect for a small accuracy and speed gain. Free-tier uploads stop at 25 MB, so a very long voice message will ask you to trim it or switch providers. Whisper likes to invent subtitle-style lines ("captions by ...") over silent stretches, so segments it scores as non-speech get dropped before you see the text.

 - **Retry a transcription with another provider:** the Retry option on a voice or video message's transcription used to quietly re-run whichever provider is picked in settings. Now, if you have two or more providers set up with working credentials, it reads "Retry with…" and opens a short list of just those configured providers, so you can bounce a garbled message off a different service without opening settings first. With one provider (or none) set up it stays a plain "Retry" that re-runs straight away.

## Download

Latest versions are available through:
* [Telegram Channel](https://t.me/NagramX) (Latest Beta)
* [GitHub Actions](https://github.com/risin42/NagramX/actions/workflows/staging.yml) (CI Artifacts)
* [GitHub Releases](https://github.com/risin42/NagramX/releases) (Latest Stable)

## Package names

I ship two builds, and each one deliberately borrows another app's package name:

* **Dazegram** — `org.telegram.messenger.beta` (Telegram Beta's package)
* **Dazegram X** — `nekox.messenger` (NekoX's package)

This is on purpose. Icon packs already ship custom icons for Telegram Beta and NekoX, so by parasiting on their package names my builds get themed icons out of the box instead of waiting for any pack to add me. The catch is that you can't keep the app I'm borrowing from installed at the same time, since Android won't allow two apps to share one package name.

## Verify APK

Both builds are signed with my certificate:

* SHA-256: `40:56:B5:DF:0C:20:58:46:51:EE:AF:70:95:A8:EF:5A:A4:73:02:4D:8A:22:57:7E:89:F0:85:A8:EF:3A:24:4C`

## Compilation Guide

1. Obtain API credentials (`TELEGRAM_APP_ID` and `TELEGRAM_APP_HASH`) from [Telegram Developer Portal](https://my.telegram.org/auth). Create `local.properties` in the project root with:

   ```properties
   TELEGRAM_APP_ID=<your_telegram_app_id>
   TELEGRAM_APP_HASH=<your_telegram_app_hash>
   ```

2. For APK signing: Replace `release.keystore` with your keystore and add signing configuration to `local.properties`:

   ```properties
   KEYSTORE_PASS=<your_keystore_password>
   ALIAS_NAME=<your_alias_name>
   ALIAS_PASS=<your_alias_password>
   ```

3. For FCM support: Replace `TMessagesProj/google-services.json` with your own configuration file.

4. Open the project in Android Studio to start building.

## GitHub Actions Build

1. Replace `TMessagesProj/release.keystore` with your keystore file.

2. Configure `local.properties` with the following:

   ```properties
   KEYSTORE_PASS=<your_keystore_password>
   ALIAS_NAME=<your_alias_name>
   ALIAS_PASS=<your_alias_password>
   TELEGRAM_APP_ID=<your_telegram_app_id>
   TELEGRAM_APP_HASH=<your_telegram_app_hash>
   ```

   Base64 encode the contents of this file.

3. Configure GitHub Action secrets:
   - `LOCAL_PROPERTIES`: Base64-encoded content from step 2
   - `HELPER_BOT_TOKEN`: Telegram bot token from [@Botfather](https://t.me/Botfather) (e.g., `1111:abcd`)
   - `HELPER_BOT_TARGET`: Primary Telegram chat ID (e.g., `777000`)
   - `HELPER_BOT_CANARY_TARGET`: Chat ID for test builds and metadata (can match `HELPER_BOT_TARGET`)

4. Trigger the Release Build workflow.

## Acknowledgments

- [AyuGram](https://github.com/AyuGram/AyuGram4A)
- [Cherrygram](https://github.com/arsLan4k1390/Cherrygram)
- [Dr4iv3rNope](https://github.com/Dr4iv3rNope/NotSoAndroidAyuGram)
- [exteraGram](https://github.com/exteraSquad/exteraGram)
- [Nagram](https://github.com/NextAlone/Nagram)
- [Nekogram](https://github.com/Nekogram/Nekogram)
- [OctoGram](https://github.com/OctoGramApp/OctoGram)
