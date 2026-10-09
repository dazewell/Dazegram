package com.dazewell.gram.helpers

import androidx.core.content.pm.ShortcutManagerCompat
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.FileLog
import org.telegram.messenger.MediaDataController
import org.telegram.messenger.UserConfig
import org.telegram.messenger.Utilities
import xyz.nextalone.nagram.NaConfig

object ShortcutHelper {
    // The id MediaDataController.buildShortcuts() gives the "New conversation" shortcut.
    private const val COMPOSE_SHORTCUT_ID = "compose"

    // A launcher label for the fork's own shortcuts. Both package variants sit side by side on one phone and a pinned
    // copy or a shortcut picker lists them under the same name, so the Unofficial one (any package that isn't the
    // `.beta` Official, the same test build.gradle uses) leads with an X. Leading, because launchers cut a long label
    // at the end. The manifest's picker entries do the same through the memoLabelPrefix placeholder.
    @JvmStatic
    fun variantLabel(label: String): String =
        if (ApplicationLoader.applicationContext.packageName.endsWith(".beta")) label else "X $label"

    @JvmStatic
    fun isComposeShortcutEnabled(): Boolean = NaConfig.newConversationShortcut.Bool()

    // The static shortcuts buildShortcuts() publishes right now. A logout wipes them all at once, so none of them
    // being there means they are missing. Testing for an empty list instead would miss it:
    // NotificationsController pushes its own ndid_* shortcuts. Any one of them, not a single marker, because a
    // launcher may reject one on its own and that must not read as a wipe. Recent chats are left out, they come and
    // go, so their absence proves nothing.
    private fun staticShortcutIds(): List<String> {
        val wanted = ArrayList<String>()
        if (isComposeShortcutEnabled()) {
            wanted.add(COMPOSE_SHORTCUT_ID)
        }
        com.dazewell.gram.shortcuts.GhostModeShortcut.addShortcutId(wanted)
        com.dazewell.gram.videonote.VideoNoteShortcut.addShortcutId(wanted)
        return wanted
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
        val wanted = staticShortcutIds()
        if (wanted.isEmpty()) {
            return
        }
        checking = true
        Utilities.globalQueue.postRunnable {
            var missing = false
            try {
                missing = ShortcutManagerCompat.getDynamicShortcuts(ApplicationLoader.applicationContext)
                    .none { it.id in wanted }
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
