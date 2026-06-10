# Physical Keyboard Hotkeys

Telegram Desktop-style keyboard shortcuts for Android when a hardware keyboard is attached, plus NagramX-custom bindings. Toggleable via **NagramX Settings → General → Physical keyboard shortcuts** (`NaConfig.physicalKeyboardHotkeys`, default ON). All shortcuts require a physical (alphabetic, non-virtual) keyboard — soft-keyboard events are ignored.

## Key bindings

### Navigation / global (mirrors Telegram Desktop defaults)
| Keys | Action | Scope |
|---|---|---|
| `Esc` | Cancel reply/edit panel → close search → back | everywhere |
| `Ctrl+W` | Close current chat | chat |
| `Ctrl+F` | Search in chat / chat list | chat, dialogs |
| `Ctrl+PgDn` / `Alt+↓` | Next chat (same folder order) | chat |
| `Ctrl+PgUp` / `Alt+↑` | Previous chat | chat |
| `Ctrl+Alt+Home` / `Ctrl+Alt+End` | First / last chat | chat |
| `Ctrl+Shift+↓` / `Ctrl+Shift+↑` | Next / previous folder tab | dialogs |
| `Ctrl+0` | Saved Messages | chat, dialogs |
| `Ctrl+1`…`Ctrl+8` | Pinned chat 1–8 (main folder) | chat, dialogs |
| `Ctrl+9` | Archive | chat, dialogs |
| `Ctrl+J` | Contacts | everywhere |
| `Ctrl+L` | Lock app (when passcode set) | everywhere |
| `Ctrl+M` | Minimize app | everywhere |
| `Ctrl+R` | Mark current chat as read | chat |

### Message input
| Keys | Action |
|---|---|
| `Alt+Enter` | **Schedule message** — opens schedule date picker for typed text (NagramX custom) |
| `↑` (empty, focused input) | Edit your last sent message |

### Text formatting (selection required, any `EditTextCaption`)
| Keys | Action |
|---|---|
| `Ctrl+B` / `Ctrl+I` / `Ctrl+U` | Bold / italic / underline |
| `Ctrl+K` | Create link |
| `Ctrl+Shift+X` | Strikethrough |
| `Ctrl+Shift+M` | Monospace |
| `Ctrl+Shift+P` | Spoiler |
| `Ctrl+Shift+N` | Plain (remove formatting) |

Desktop bindings intentionally not ported: `Ctrl+Q` (quit), media-key bindings (handled by Android media session), support-mode shortcuts.

## Implementation

Core logic lives in `TMessagesProj/src/main/java/com/radolyn/ayugram/hotkeys/HotkeyController.java` (static, stateless). Hooks injected into base code:

- `LaunchActivity.dispatchKeyEvent` → `HotkeyController.handleGlobalKey(...)` — single entry point for all global/navigation keys; runs before the view tree sees the event. Skipped while passcode lock, PhotoViewer or ArticleViewer is active.
- `ChatActivity` → `hotkeyOpenSearch()`, `hotkeyCancelFieldPanel()` (clicks the reply-panel ✕), `hotkeyEditLastOutgoingMessage()` (find logic in `HotkeyController.findLastEditableOutgoingMessage`, mirrors the context-menu `allowEdit` conditions).
- `ChatActivityEnterView.scheduleMessageFromHotkey()` — reuses the quick-schedule path (`AlertsCreator.createScheduleDatePickerDialog` → `sendMessageInternal`); requires `canScheduleMessage()` and non-empty input.
- `EditTextCaption.onKeyShortcut` → `HotkeyController.handleTextStyleShortcut(...)` — maps Ctrl combos to existing `performMenuAction` ids.
- `DialogsActivity` → `hotkeyOpenSearch()` (mirrors the action-bar search button), `hotkeySwitchFolder(forward)`.
- `FilterTabsView.selectTabWithOffset(offset)` — selects an adjacent tab through the full `scrollToTab` path so the page switch animates and delegates fire.

Next/prev chat uses `MessagesController.getDialogs(folderId)` for the current dialog's folder, skips `TL_dialogFolder` rows, and opens via `presentFragment(new ChatActivity(args), true)` after `checkCanOpenChat`.
