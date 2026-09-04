package com.radolyn.ayugram.chatprivacy;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.UserConfig;

public class CoverInteractionActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent intent = getIntent();
        if (intent == null) {
            finish();
            return;
        }
        int currentAccount = intent.getIntExtra("currentAccount", UserConfig.selectedAccount);
        if (!UserConfig.isValidAccount(currentAccount)) {
            finish();
            return;
        }
        String token = intent.getStringExtra(NotificationCoverController.EXTRA_COVER_TOKEN);
        int event = intent.getIntExtra(NotificationCoverController.EXTRA_COVER_EVENT, 0);
        if (TextUtils.isEmpty(token)) {
            finish();
            return;
        }

        ApplicationLoader.postInitApplication();
        NotificationCoverController.handleInteractionFromActivity(this, currentAccount, token, event);
        finish();
    }
}
