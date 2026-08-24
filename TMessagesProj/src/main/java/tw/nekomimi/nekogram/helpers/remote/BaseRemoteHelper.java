package tw.nekomimi.nekogram.helpers.remote;

import android.app.Activity;
import android.content.SharedPreferences;
import android.text.TextUtils;

import org.json.JSONException;
import org.json.JSONObject;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;

public abstract class BaseRemoteHelper {
    public static final long CHANNEL_METADATA_ID = 2477822904L;
    public static final String CHANNEL_METADATA_NAME = "nagramx_remote_metadata";

    // NagramX: the metadata channel belongs to the abandoned upstream (risin42), so nothing
    // loads from it anymore. Suppressed here rather than at the call sites so no future caller
    // can reopen it; a blocked call reports an empty result so cached data and callbacks are
    // unaffected. The request code below is kept as the reference implementation for a
    // fork-owned source: re-enabling means changing CHANNEL_METADATA_* and dropping this guard —
    // that restores the network fetch for all three load() consumers here: emoji packs
    // (EmojiHelper), link-preview rewrite rules (PagePreviewRulesHelper), and update metadata
    // (UpdateHelper.checkNewVersionAvailable). Dropping the guard is not enough to bring back
    // visible update checks, though: LaunchActivity.checkAppUpdate never reaches
    // UpdateHelper.checkNewVersionAvailable at all, because it's gated independently at five
    // further sites this boolean does not touch, so all five need to be reverted by hand too.
    // The two LaunchActivity gates carry a short back-reference to this flag; the other three do
    // not, so start from this list rather than expecting to find a trail from them:
    //   - LaunchActivity.checkAppUpdate's own if (true) gate (~L6119)
    //   - LaunchActivity's unconditional pending-update purge on resume (~L7210)
    //   - SettingsActivity's Update Channel picker label (~L1521) and setEnabled(false) (~L1556)
    //   - ProfileActivity's copy of the same picker (~L4823 / ~L4858)
    //   - the reworded UpdateChecksOffNax string (strings_nax.xml ~L284)
    private static final boolean REMOTE_METADATA_DISABLED = true;

    protected static final SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("nekoremoteconfig", Activity.MODE_PRIVATE);

    protected MessagesController getMessagesController() {
        return MessagesController.getInstance(UserConfig.selectedAccount);
    }

    protected ConnectionsManager getConnectionsManager() {
        return ConnectionsManager.getInstance(UserConfig.selectedAccount);
    }

    protected MessagesStorage getMessagesStorage() {
        return MessagesStorage.getInstance(UserConfig.selectedAccount);
    }

    protected FileLoader getFileLoader() {
        return FileLoader.getInstance(UserConfig.selectedAccount);
    }

    abstract protected void onError(String text, Delegate delegate);

    abstract protected String getTag();

    protected void onLoadSuccess(ArrayList<JSONObject> responses, Delegate delegate) {
        var tag = getTag();
        var json = responses.size() > 0 ? responses.get(0) : null;
        if (json == null) {
            preferences.edit()
                    .remove(tag + "_update_time")
                    .remove(tag)
                    .apply();
        } else {
            preferences.edit()
                    .putLong(tag + "_update_time", System.currentTimeMillis())
                    .putString(tag, json.toString())
                    .apply();
        }
    }

    private void onGetMessageSuccess(TLObject response, Delegate delegate) {
        var tag = "#" + getTag();
        final var res = (TLRPC.messages_Messages) response;
        getMessagesController().removeDeletedMessagesFromArray(CHANNEL_METADATA_ID, res.messages);
        ArrayList<JSONObject> responses = new ArrayList<>();
        for (var message : res.messages) {
            if (TextUtils.isEmpty(message.message) || !message.message.startsWith(tag)) {
                continue;
            }
            try {
                responses.add(new JSONObject(message.message.substring(tag.length()).trim()));
            } catch (JSONException e) {
                FileLog.e(e);
            }
        }
        onLoadSuccess(responses, delegate);
    }

    public void load() {
        load(false, null);
    }

    public void load(Delegate delegate) {
        load(false, delegate);
    }

    private void load(boolean forceRefreshAccessHash, Delegate delegate) {
        if (REMOTE_METADATA_DISABLED) {
            if (delegate != null) {
                delegate.onTLResponse(null, null);
            }
            return;
        }
        var tag = "#" + getTag();
        TLRPC.TL_messages_search req = new TLRPC.TL_messages_search();
        req.limit = 10;
        req.offset_id = 0;
        req.filter = new TLRPC.TL_inputMessagesFilterEmpty();
        req.q = tag;
        req.peer = getMessagesController().getInputPeer(-CHANNEL_METADATA_ID);
        if (req.peer == null || req.peer.access_hash == 0 || forceRefreshAccessHash) {
            TLRPC.TL_contacts_resolveUsername req1 = new TLRPC.TL_contacts_resolveUsername();
            req1.username = CHANNEL_METADATA_NAME;
            getConnectionsManager().sendRequest(req1, (response1, error1) -> {
                if (error1 != null) {
                    return;
                }
                if (!(response1 instanceof TLRPC.TL_contacts_resolvedPeer resolvedPeer)) {
                    return;
                }
                getMessagesController().putUsers(resolvedPeer.users, false);
                getMessagesController().putChats(resolvedPeer.chats, false);
                getMessagesStorage().putUsersAndChats(resolvedPeer.users, resolvedPeer.chats, false, true);
                if ((resolvedPeer.chats == null || resolvedPeer.chats.size() == 0)) {
                    return;
                }
                req.peer = new TLRPC.TL_inputPeerChannel();
                req.peer.channel_id = resolvedPeer.chats.get(0).id;
                req.peer.access_hash = resolvedPeer.chats.get(0).access_hash;
                getConnectionsManager().sendRequest(req, (response, error) -> {
                    if (error == null) {
                        onGetMessageSuccess(response, delegate);
                    } else {
                        onError(error.text, delegate);
                    }
                });
            });
        } else {
            getConnectionsManager().sendRequest(req, (response, error) -> {
                if (error == null) {
                    onGetMessageSuccess(response, delegate);
                } else {
                    load(true, delegate);
                }
            });
        }
    }

    public interface Delegate {
        void onTLResponse(TLRPC.TL_help_appUpdate res, String error);
    }
}
