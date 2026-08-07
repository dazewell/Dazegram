package xyz.nextalone.nagram.ui.composer;

import android.text.TextUtils;

import xyz.nextalone.nagram.NaConfig;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;

/**
 * Reads and writes the composer toolbar layout: which buttons are on, which zone holds each one,
 * and the order inside a zone.
 *
 * Stored as one string of four "|" separated zones (start, middle, end, hidden), each a comma
 * joined list of button keys, matching how the text style order is kept. A registered key that the
 * stored string never mentions falls back to its own default zone rather than disappearing, so a
 * button added in a later version still shows up for someone who saved a layout before it existed.
 */
public final class ComposerLayout {

    private static final char ZONE_SEPARATOR = '|';
    private static final String KEY_SEPARATOR = ",";

    private static String parsedFrom;
    private static List<List<String>> zones;

    private ComposerLayout() {
    }

    public static synchronized List<String> zone(int zone) {
        parse();
        // The cache is shared by every toolbar being built, so hand out a read-only view of it
        // rather than the list itself - a caller that sorted or trimmed it in place would silently
        // rewrite the layout for every other composer.
        return Collections.unmodifiableList(zones.get(zone));
    }

    public static synchronized int zoneOf(String key) {
        parse();
        for (int i = 0; i < ComposerButtons.ZONE_COUNT; i++) {
            if (zones.get(i).contains(key)) {
                return i;
            }
        }
        return ComposerButtons.ZONE_HIDDEN;
    }

    public static boolean isVisible(String key) {
        return zoneOf(key) != ComposerButtons.ZONE_HIDDEN;
    }

    /** Position of a button inside its own zone, or {@link Integer#MAX_VALUE} when it is hidden. */
    public static synchronized int indexOf(String key) {
        parse();
        for (int i = 0; i < ComposerButtons.ZONE_COUNT - 1; i++) {
            int index = zones.get(i).indexOf(key);
            if (index >= 0) {
                return index;
            }
        }
        return Integer.MAX_VALUE;
    }

    /**
     * The trailing zone button that holds the panel's trailing edge. Only a button that stays for the
     * whole life of the toolbar qualifies: anchoring the edge to one that comes and goes leaves the
     * capsule measuring against a zero width child.
     */
    public static synchronized String trailingKey() {
        parse();
        List<String> end = zones.get(ComposerButtons.ZONE_END);
        for (int i = end.size() - 1; i >= 0; i--) {
            ComposerButtons.Button button = ComposerButtons.get(end.get(i));
            if (button != null && button.stable) {
                return button.key;
            }
        }
        return null;
    }

    public static synchronized void save(List<List<String>> next) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < ComposerButtons.ZONE_COUNT; i++) {
            if (i > 0) {
                builder.append(ZONE_SEPARATOR);
            }
            builder.append(TextUtils.join(KEY_SEPARATOR, next.get(i)));
        }
        String value = builder.toString();
        NaConfig.INSTANCE.getComposerToolbarLayout().setConfigString(value);
        parsedFrom = null;
    }

    public static synchronized void reset() {
        NaConfig.INSTANCE.getComposerToolbarLayout().setConfigString("");
        parsedFrom = null;
    }

    /** A fresh copy the editor can shuffle without touching what the toolbar is reading. */
    public static synchronized List<List<String>> snapshot() {
        parse();
        List<List<String>> copy = new ArrayList<>(ComposerButtons.ZONE_COUNT);
        for (int i = 0; i < ComposerButtons.ZONE_COUNT; i++) {
            copy.add(new ArrayList<>(zones.get(i)));
        }
        return copy;
    }

    public static List<List<String>> defaults() {
        return normalize(emptyZones());
    }

    /**
     * Seeds the layout for someone upgrading from a build without the editor: the toolbar keeps
     * exactly the buttons it was showing, and everything the editor newly makes available starts
     * hidden. Seeding from every text style flag instead would drop a dozen buttons into the panel
     * on first launch, which nobody asked for.
     */
    public static synchronized void migrate() {
        if (NaConfig.INSTANCE.getComposerLayoutMigrated().Bool()) {
            return;
        }
        NaConfig.INSTANCE.getComposerLayoutMigrated().setConfigBool(true);
        if (!TextUtils.isEmpty(NaConfig.INSTANCE.getComposerToolbarLayout().String())) {
            return;
        }
        List<List<String>> seeded = emptyZones();
        seeded.get(ComposerButtons.ZONE_START).add(ComposerButtons.EMOJI);
        seeded.get(ComposerButtons.ZONE_MIDDLE).add(ComposerButtons.RICH);
        seeded.get(ComposerButtons.ZONE_MIDDLE).add(ComposerButtons.AI);
        if (NaConfig.INSTANCE.getShowTextQuote().Bool()) {
            seeded.get(ComposerButtons.ZONE_MIDDLE).add("quote");
        }
        if (NaConfig.INSTANCE.getShowTextSpoiler().Bool()) {
            seeded.get(ComposerButtons.ZONE_MIDDLE).add("spoiler");
        }
        if (NaConfig.INSTANCE.getShowTextMono().Bool()) {
            seeded.get(ComposerButtons.ZONE_MIDDLE).add("mono");
        }
        if (NaConfig.INSTANCE.getShowTextRegular().Bool()) {
            seeded.get(ComposerButtons.ZONE_MIDDLE).add("regular");
        }
        seeded.get(ComposerButtons.ZONE_END).add(ComposerButtons.EXPAND);
        seeded.get(ComposerButtons.ZONE_END).add(ComposerButtons.SCHEDULE);
        seeded.get(ComposerButtons.ZONE_END).add(ComposerButtons.ATTACH);
        for (ComposerButtons.Button button : ComposerButtons.all()) {
            boolean placed = false;
            for (int i = 0; i < ComposerButtons.ZONE_COUNT; i++) {
                placed |= seeded.get(i).contains(button.key);
            }
            if (!placed) {
                seeded.get(ComposerButtons.ZONE_HIDDEN).add(button.key);
            }
        }
        save(seeded);
    }

    private static void parse() {
        String stored = NaConfig.INSTANCE.getComposerToolbarLayout().String();
        if (stored == null) {
            stored = "";
        }
        if (zones != null && stored.equals(parsedFrom)) {
            return;
        }
        List<List<String>> parsed = emptyZones();
        if (!TextUtils.isEmpty(stored)) {
            String[] parts = stored.split("\\" + ZONE_SEPARATOR, -1);
            for (int i = 0; i < Math.min(parts.length, ComposerButtons.ZONE_COUNT); i++) {
                if (TextUtils.isEmpty(parts[i])) {
                    continue;
                }
                parsed.get(i).addAll(Arrays.asList(parts[i].split(KEY_SEPARATOR)));
            }
        }
        zones = normalize(parsed);
        parsedFrom = stored;
    }

    private static List<List<String>> emptyZones() {
        List<List<String>> result = new ArrayList<>(ComposerButtons.ZONE_COUNT);
        for (int i = 0; i < ComposerButtons.ZONE_COUNT; i++) {
            result.add(new ArrayList<>());
        }
        return result;
    }

    /**
     * Drops keys the build no longer knows, gives unmentioned buttons their default zone, pulls the
     * trailing only buttons back to the trailing zone, empties the overflow out of the single slot
     * leading zone, and makes sure a stable button ends up holding the trailing edge.
     */
    public static List<List<String>> normalize(List<List<String>> input) {
        List<List<String>> result = emptyZones();
        HashSet<String> seen = new HashSet<>();
        for (int i = 0; i < ComposerButtons.ZONE_COUNT; i++) {
            for (String key : input.get(i)) {
                ComposerButtons.Button button = ComposerButtons.get(key);
                if (button == null || !seen.add(key)) {
                    continue;
                }
                int zone = button.canSitIn(i) ? i : ComposerButtons.ZONE_END;
                result.get(zone).add(key);
            }
        }
        for (ComposerButtons.Button button : ComposerButtons.all()) {
            if (seen.add(button.key)) {
                result.get(button.defaultZone).add(button.key);
            }
        }
        List<String> start = result.get(ComposerButtons.ZONE_START);
        while (start.size() > ComposerButtons.START_CAPACITY) {
            result.get(ComposerButtons.ZONE_MIDDLE).add(0, start.remove(start.size() - 1));
        }
        List<String> end = result.get(ComposerButtons.ZONE_END);
        for (int i = end.size() - 1; i >= 0; i--) {
            ComposerButtons.Button button = ComposerButtons.get(end.get(i));
            if (button != null && button.stable) {
                end.add(end.remove(i));
                break;
            }
        }
        return result;
    }
}
