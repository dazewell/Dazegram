package tw.nekomimi.nekogram.helpers;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;
import android.util.Base64;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.ui.LaunchActivity;

import java.nio.charset.StandardCharsets;

public class PasscodeHelper {
    private static final SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("nekopasscode", Context.MODE_PRIVATE);

    public static boolean checkPasscode(Activity activity, String passcode) {
        if (hasPasscodeForAccount(Integer.MAX_VALUE)) {
            String passcodeHash = preferences.getString("passcodeHash" + Integer.MAX_VALUE, "");
            String passcodeSaltString = preferences.getString("passcodeSalt" + Integer.MAX_VALUE, "");
            if (checkPasscodeHash(passcode, passcodeHash, passcodeSaltString)) {
                for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
                    if (UserConfig.getInstance(a).isClientActivated() && isAccountAllowPanic(a)) {
                        MessagesController.getInstance(a).performLogout(1);
                    }
                }
                return false;
            }
        }
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            if (UserConfig.getInstance(a).isClientActivated() && hasPasscodeForAccount(a)) {
                String passcodeHash = preferences.getString("passcodeHash" + a, "");
                String passcodeSaltString = preferences.getString("passcodeSalt" + a, "");
                if (checkPasscodeHash(passcode, passcodeHash, passcodeSaltString)) {
                    if (activity instanceof LaunchActivity launchActivity) {
                        launchActivity.switchToAccount(a, true);
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static boolean checkPasscodeHash(String passcode, String passcodeHash, String passcodeSaltString) {
        try {
            byte[] passcodeSalt;
            if (passcodeSaltString.length() > 0) {
                passcodeSalt = Base64.decode(passcodeSaltString, Base64.DEFAULT);
            } else {
                passcodeSalt = new byte[0];
            }
            byte[] passcodeBytes = passcode.getBytes(StandardCharsets.UTF_8);
            byte[] bytes = new byte[32 + passcodeBytes.length];
            System.arraycopy(passcodeSalt, 0, bytes, 0, 16);
            System.arraycopy(passcodeBytes, 0, bytes, 16, passcodeBytes.length);
            System.arraycopy(passcodeSalt, 0, bytes, passcodeBytes.length + 16, 16);
            String hash = Utilities.bytesToHex(Utilities.computeSHA256(bytes, 0, bytes.length));
            return passcodeHash.equals(hash);
        } catch (Exception e) {
            FileLog.e(e);
        }
        return false;
    }

    // NagramX: for an account that's still active (re-logging in to the same slot, or the user
    // just clearing their own code) -- keeps allowPanic, since the UI lets it be set independently
    // of having a passcode at all.
    public static void removePasscodeForAccount(int account) {
        preferences.edit()
                .remove("passcodeHash" + account)
                .remove("passcodeSalt" + account)
                .remove("hide" + account)
                .apply();
    }

    // NagramX: for an account slot that's actually going away -- logout, remote/forced logout,
    // ToS decline, or the slot about to be reused by a fresh login. Also drops allowPanic, since a
    // stale "true" would let a departed account's slot keep counting toward Panic coverage, and a
    // stale "false" would carry over to whoever logs into that slot next.
    public static void clearAccountState(int account) {
        preferences.edit()
                .remove("passcodeHash" + account)
                .remove("passcodeSalt" + account)
                .remove("hide" + account)
                .remove("allowPanic" + account)
                .apply();
    }

    // NagramX: sweeps every slot with no current user and clears its passcode state in one editor.
    // Repairs installs where an account logged out before this existed and left its slot's
    // passcodeHash/passcodeSalt/hide/allowPanic behind -- those stale keys could reject a Panic
    // Code as "already used by an account" that no longer exists. Bounded to MAX_ACCOUNT_COUNT
    // slots, and only ever removes these four exact keys -- never settingsHash, hideSettings, or
    // the Integer.MAX_VALUE Panic record.
    public static void clearInactiveAccountState() {
        SharedPreferences.Editor editor = null;
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            if (UserConfig.getInstance(a).getCurrentUser() != null) {
                continue;
            }
            if (!preferences.contains("passcodeHash" + a) && !preferences.contains("passcodeSalt" + a)
                    && !preferences.contains("hide" + a) && !preferences.contains("allowPanic" + a)) {
                continue;
            }
            if (editor == null) {
                editor = preferences.edit();
            }
            editor.remove("passcodeHash" + a)
                    .remove("passcodeSalt" + a)
                    .remove("hide" + a)
                    .remove("allowPanic" + a);
        }
        if (editor != null) {
            editor.apply();
        }
    }

    public static boolean isAccountAllowPanic(int account) {
        return preferences.getBoolean("allowPanic" + account, true);
    }

    public static boolean isAccountHidden(int account) {
        return hasPasscodeForAccount(account) && preferences.getBoolean("hide" + account, false);
    }

    public static void setAccountAllowPanic(int account, boolean panic) {
        preferences.edit()
                .putBoolean("allowPanic" + account, panic)
                .apply();
    }

    public static void setHideAccount(int account, boolean hide) {
        preferences.edit()
                .putBoolean("hide" + account, hide)
                .apply();
    }

    public static boolean setPasscodeForAccount(String firstPassword, int account) {
        try {
            byte[] passcodeSalt = new byte[16];
            Utilities.random.nextBytes(passcodeSalt);
            byte[] passcodeBytes = firstPassword.getBytes(StandardCharsets.UTF_8);
            byte[] bytes = new byte[32 + passcodeBytes.length];
            System.arraycopy(passcodeSalt, 0, bytes, 0, 16);
            System.arraycopy(passcodeBytes, 0, bytes, 16, passcodeBytes.length);
            System.arraycopy(passcodeSalt, 0, bytes, passcodeBytes.length + 16, 16);
            preferences.edit()
                    .putString("passcodeHash" + account, Utilities.bytesToHex(Utilities.computeSHA256(bytes, 0, bytes.length)))
                    .putString("passcodeSalt" + account, Base64.encodeToString(passcodeSalt, Base64.DEFAULT))
                    .apply();
            // NagramX: reread the exact slot we just wrote and confirm the code verifies against it, so a
            // mis-targeted or dropped write surfaces as a save failure instead of a silent lockout. This
            // checks that we wrote where we meant to, not that the write reached disk.
            String hash = preferences.getString("passcodeHash" + account, "");
            byte[] salt = decodeSalt(preferences.getString("passcodeSalt" + account, ""));
            return matchesHash(firstPassword, hash, salt);
        } catch (Exception e) {
            FileLog.e(e);
        }
        return false;
    }

    // NagramX: pure hash matcher for setup-time collision checks. Unlike SharedConfig.checkPasscode
    // (which migrates a legacy hash and saves) and checkPasscode above (which triggers the Panic
    // logout), this has no side effects, so it is safe to call while a code is being chosen. A
    // zero-length salt means the old MD5 layout, matching SharedConfig's own fallback.
    public static boolean matchesHash(String candidate, String hashHex, byte[] salt) {
        if (candidate == null || TextUtils.isEmpty(hashHex)) {
            return false;
        }
        try {
            if (salt == null || salt.length == 0) {
                return Utilities.MD5(candidate).equals(hashHex);
            }
            byte[] passcodeBytes = candidate.getBytes(StandardCharsets.UTF_8);
            byte[] bytes = new byte[32 + passcodeBytes.length];
            System.arraycopy(salt, 0, bytes, 0, 16);
            System.arraycopy(passcodeBytes, 0, bytes, 16, passcodeBytes.length);
            System.arraycopy(salt, 0, bytes, passcodeBytes.length + 16, 16);
            return hashHex.equals(Utilities.bytesToHex(Utilities.computeSHA256(bytes, 0, bytes.length)));
        } catch (Exception e) {
            FileLog.e(e);
        }
        return false;
    }

    // NagramX: does the candidate equal the configured Panic Code? Read-only.
    public static boolean matchesPanic(String candidate) {
        if (!hasPasscodeForAccount(Integer.MAX_VALUE)) {
            return false;
        }
        String hash = preferences.getString("passcodeHash" + Integer.MAX_VALUE, "");
        byte[] salt = decodeSalt(preferences.getString("passcodeSalt" + Integer.MAX_VALUE, ""));
        return matchesHash(candidate, hash, salt);
    }

    // NagramX: first account slot whose stored passcode equals the candidate, or -1. Scans every slot
    // that has a record, activation aside, because an inactive-but-configured account still owns a
    // code the Panic Code must stay distinct from.
    public static int findMatchingAccount(String candidate) {
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            if (!hasPasscodeForAccount(a)) {
                continue;
            }
            String hash = preferences.getString("passcodeHash" + a, "");
            byte[] salt = decodeSalt(preferences.getString("passcodeSalt" + a, ""));
            if (matchesHash(candidate, hash, salt)) {
                return a;
            }
        }
        return -1;
    }

    private static byte[] decodeSalt(String saltString) {
        if (!TextUtils.isEmpty(saltString)) {
            try {
                return Base64.decode(saltString, Base64.DEFAULT);
            } catch (Exception e) {
                // NagramX: a corrupt stored salt should make the slot simply not match a setup
                // candidate, not crash the setup screen -- an empty salt fails the SHA comparison.
                FileLog.e(e);
            }
        }
        return new byte[0];
    }

    public static boolean hasPasscodeForAccount(int account) {
        return preferences.contains("passcodeHash" + account) && preferences.contains("passcodeSalt" + account);
    }

    public static boolean hasPanicCode() {
        return hasPasscodeForAccount(Integer.MAX_VALUE);
    }

    public static String getSettingsKey() {
        var settingsHash = preferences.getString("settingsHash", "");
        if (!TextUtils.isEmpty(settingsHash)) {
            return settingsHash;
        }
        byte[] bytes = new byte[8];
        Utilities.random.nextBytes(bytes);
        var hash = Base64.encodeToString(bytes, Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
        preferences.edit().putString("settingsHash", hash).apply();
        return hash;
    }

    public static boolean isSettingsHidden() {
        return preferences.getBoolean("hideSettings", false);
    }

    public static void setHideSettings(boolean hide) {
        preferences.edit()
                .putBoolean("hideSettings", hide)
                .apply();
    }

    public static boolean isEnabled() {
        return !preferences.getAll().isEmpty();
    }

    public static void clearAll() {
        preferences.edit().clear().apply();
    }
}
