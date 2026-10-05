package xyz.nextalone.nagram.helper

import androidx.core.content.pm.ShortcutManagerCompat
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.FileLog
import org.telegram.messenger.MediaDataController
import org.telegram.messenger.UserConfig
import org.telegram.messenger.Utilities
import xyz.nextalone.nagram.NaConfig

object ShortcutHelper {
    // The id MediaDataController.buildShortcuts() gives the "New Message" shortcut. Everything it
    // builds goes out in one pass, so this one missing means the whole set is missing. Testing for
    // an empty list instead would miss it: NotificationsController pushes its own ndid_* shortcuts.
    private const val COMPOSE_SHORTCUT_ID = "compose"

    // Off, it is no longer published, so the marker falls to the first fork shortcut that is on.
    @JvmStatic
    fun isComposeShortcutEnabled(): Boolean = NaConfig.newConversationShortcut.Bool()

    private fun markerShortcutId(): String? {
        val wanted = ArrayList<String>()
        if (isComposeShortcutEnabled()) {
            wanted.add(COMPOSE_SHORTCUT_ID)
        }
        com.radolyn.ayugram.shortcuts.GhostModeShortcut.addShortcutId(wanted)
        com.radolyn.ayugram.videonote.VideoNoteShortcut.addShortcutId(wanted)
        return wanted.firstOrNull()
    }

    private var checking = false

    // Call this from the UI thread: the checking flag isn't synchronized, and buildShortcuts()
    // reads its hint list there.
    @JvmStatic
    fun restoreLauncherShortcuts() {
        val account = UserConfig.selectedAccount
        if (checking || !UserConfig.getInstance(account).isClientActivated) {
            return
        }
        // Nothing of ours is published, so there is nothing to bring back
        val marker = markerShortcutId() ?: return
        checking = true
        Utilities.globalQueue.postRunnable {
            var missing = false
            try {
                missing = ShortcutManagerCompat.getDynamicShortcuts(ApplicationLoader.applicationContext)
                    .none { it.id == marker }
            } catch (e: Exception) {
                FileLog.e(e)
            }
            AndroidUtilities.runOnUIThread {
                checking = false
                // The account can be switched or logged out while the two hops above are in flight,
                // and buildShortcuts() writes the shortcuts app-wide from whichever one it runs on.
                if (!missing || account != UserConfig.selectedAccount || !UserConfig.getInstance(account).isClientActivated) {
                    return@runOnUIThread
                }
                val mediaDataController = MediaDataController.getInstance(account)
                // Hints are empty in a fresh process, so this pass only brings back the static
                // shortcuts. loadHints() pulls the cached top peers and rebuilds with the recent
                // chats once they're in; it returns early when frequent contacts are turned off.
                mediaDataController.loadHints(true)
                mediaDataController.buildShortcuts()
            }
        }
    }
}
