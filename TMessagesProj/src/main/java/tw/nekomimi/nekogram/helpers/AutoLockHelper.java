package tw.nekomimi.nekogram.helpers;

import static org.telegram.messenger.LocaleController.getString;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;

/**
 * The one list of auto-lock timeouts {@code SharedConfig.autoLockIn} may hold, plus the label the
 * pickers render a value with.
 *
 * <p>Upstream spelled the list out three times inside {@code PasscodeActivity}'s picker dialog and
 * once more in its settings row, and the privacy-profiles feature added two further copies -- one
 * for its wheel, one for the validator that decides whether a stored profile is corrupt. Six
 * hand-kept copies of the same list is how they drift apart, so they all read this instead.
 *
 * <p>Values are seconds. 0 means "never locks on its own"; 1 is upstream's "immediately" sentinel,
 * tested before {@code lastPauseTime} in {@code AndroidUtilities.needShowPasscode}, so it is not a
 * literal one-second timeout.
 */
public class AutoLockHelper {

    /** Ordered the way the pickers show them. Reach it through {@link #count()} / {@link #valueAt(int)}. */
    private static final int[] VALUES = {0, 1, 5, 10, 15, 30, 60, 60 * 5, 60 * 60, 60 * 60 * 5};

    /** Where an unrecognised stored value lands, matching {@code SharedConfig.autoLockIn}'s own default. */
    public static final int DEFAULT_VALUE = 60 * 60;

    private AutoLockHelper() {
    }

    /** How many entries a picker wheel over these values has. */
    public static int count() {
        return VALUES.length;
    }

    /** The timeout at a picker position. Out-of-range positions clamp rather than throw. */
    public static int valueAt(int index) {
        if (index < 0) {
            return VALUES[0];
        }
        if (index >= VALUES.length) {
            return VALUES[VALUES.length - 1];
        }
        return VALUES[index];
    }

    public static boolean isSupported(int value) {
        for (int v : VALUES) {
            if (v == value) {
                return true;
            }
        }
        return false;
    }

    /**
     * Picker position for a stored value. Nothing validates {@code autoLockIn} on load, so a
     * hand-edited settings backup or a downgrade from a build with more values can hand us
     * something that is not in the list; those land on the default rather than on position 0,
     * which upstream left them at and then wrote back out as "Disabled" when the dialog was
     * confirmed.
     */
    public static int index(int value) {
        int fallback = 0;
        for (int i = 0; i < VALUES.length; i++) {
            if (VALUES[i] == value) {
                return i;
            }
            if (VALUES[i] == DEFAULT_VALUE) {
                fallback = i;
            }
        }
        return fallback;
    }

    /**
     * Short form: "Disabled", "Immediately", or "in 30 seconds". Used both by the picker wheels,
     * which do not ellipsize, and by the auto-lock settings row.
     */
    public static String label(int value) {
        if (value == 0) {
            return getString(R.string.AutoLockDisabled);
        }
        if (value == 1) {
            return getString(R.string.AutoLockImmediately);
        }
        return LocaleController.formatString(R.string.AutoLockInTime, duration(value));
    }

    /** Bare "30 seconds" / "5 minutes", without the "in %1$s" wrapper. */
    public static String duration(int seconds) {
        if (seconds < 60) {
            return LocaleController.formatPluralString("Seconds", seconds);
        }
        if (seconds < 60 * 60) {
            return LocaleController.formatPluralString("Minutes", seconds / 60);
        }
        if (seconds < 60 * 60 * 24) {
            return LocaleController.formatPluralString("Hours", (int) Math.ceil(seconds / 60.0f / 60));
        }
        return LocaleController.formatPluralString("Days", (int) Math.ceil(seconds / 60.0f / 60 / 24));
    }
}
