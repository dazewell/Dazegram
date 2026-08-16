// Sanity-checks the Extera Light/Dark Monet assets before a PR. Not a build gate - there is no
// test source set under TMessagesProj/src, so this is a standalone script (JDK 21 single-file
// source launch, no build step, no dependencies), the same way IconInk.java in this directory is.
//
// It checks three things, since a mistyped, dropped, or unrecognized token is invisible to
// eyeballing a themed screen:
//
//   1. monet_extera_{light,dark}.attheme carry every key their monet_{light,dark}.attheme
//      counterpart does (a superset), so nothing silently falls back to the XML default.
//   2. every key in the two Extera assets is one ThemeColors.stringKeyToInt() actually resolves -
//      parsed straight out of ThemeColors.java's colorKeysMap, the sole map that backs it - or is
//      an unknown key already present verbatim in the corresponding baseline asset (a pre-existing
//      bug this change didn't introduce and isn't the place to fix). Theme.getThemeFileValues()
//      silently drops any key stringKeyToInt() can't resolve (keyFromString >= 0 guard), so a typo'd
//      or renamed key is a complete no-op at runtime with nothing to show for it on screen.
//   3. every value in the two Extera assets either matches a valid 6- or 8-digit '#' hex color, is
//      a raw ARGB int literal, or resolves against MonetHelper's own token vocabulary - parsed
//      straight out of MonetHelper.java so this can never drift from what the app actually resolves
//      at runtime. 0 doubles as MonetHelper's own "failed to resolve" sentinel (see getColor()'s
//      catch clause), so a bad token and an intentional zero both render identically and neither
//      eyeballing a themed screen nor a runtime log line can tell them apart after the fact.
//
// Run with no build step and no dependencies (JDK 21 single-file source launch), from the repo
// root, and paste the output in the PR body:
//
//     java Tools/scripts/VerifyExteraThemes.java

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class VerifyExteraThemes {
    private static final Path ASSETS = Paths.get("TMessagesProj/src/main/assets");
    private static final Path MONET_HELPER = Paths.get("TMessagesProj/src/main/java/tw/nekomimi/nekogram/helpers/MonetHelper.java");
    private static final Path THEME_COLORS = Paths.get("TMessagesProj/src/main/java/org/telegram/ui/ActionBar/ThemeColors.java");
    private static final Pattern HEX_COLOR = Pattern.compile("^#([0-9A-Fa-f]{6}|[0-9A-Fa-f]{8})$");

    public static void main(String[] args) throws IOException {
        Set<String> vocabulary = loadVocabulary(MONET_HELPER);
        System.out.println("Loaded " + vocabulary.size() + " tokens from " + MONET_HELPER);
        Set<String> validKeys = loadValidKeys(THEME_COLORS);
        System.out.println("Loaded " + validKeys.size() + " runtime key names from " + THEME_COLORS);

        boolean ok = true;
        ok &= checkSuperset("monet_light.attheme", "monet_extera_light.attheme");
        ok &= checkSuperset("monet_dark.attheme", "monet_extera_dark.attheme");
        ok &= checkKeys("monet_light.attheme", "monet_extera_light.attheme", validKeys);
        ok &= checkKeys("monet_dark.attheme", "monet_extera_dark.attheme", validKeys);
        ok &= checkValues("monet_extera_light.attheme", vocabulary);
        ok &= checkValues("monet_extera_dark.attheme", vocabulary);
        ok &= checkWallpaperPanelContrast("monet_extera_light.attheme", vocabulary);
        ok &= checkWallpaperPanelContrast("monet_extera_dark.attheme", vocabulary);

        System.out.println(ok ? "OK" : "FAILED");
        if (!ok) {
            System.exit(1);
        }
    }

    // ThemeColors.stringKeyToInt() is built entirely from colorKeysMap's put(key_x, "name") calls
    // (createColorKeysStringMap() just inverts that same map), so this regex is exact - it can't
    // drift from what the runtime actually resolves.
    private static Set<String> loadValidKeys(Path themeColorsJava) throws IOException {
        String source = Files.readString(themeColorsJava, StandardCharsets.UTF_8);
        Set<String> keys = new TreeSet<>();
        Matcher m = Pattern.compile("colorKeysMap\\.put\\([A-Za-z0-9_]+,\\s*\"([^\"]+)\"\\)").matcher(source);
        while (m.find()) {
            keys.add(m.group(1));
        }
        return keys;
    }

    // MonetHelper's `ids` and `avatarBaseColors` maps are both just chains of put("token", ...);
    // this is the only place either map is populated, so a plain regex over the whole file is
    // exact and needs no Java parser.
    private static Set<String> loadVocabulary(Path monetHelperJava) throws IOException {
        String source = Files.readString(monetHelperJava, StandardCharsets.UTF_8);
        Set<String> tokens = new TreeSet<>();
        Matcher m = Pattern.compile("put\\(\"([A-Za-z0-9_]+)\"").matcher(source);
        while (m.find()) {
            tokens.add(m.group(1));
        }
        return tokens;
    }

    private static Map<String, String> parseAttheme(Path file) throws IOException {
        Map<String, String> values = new LinkedHashMap<>();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (line.isEmpty() || line.equals("end") || line.startsWith("//")) {
                continue;
            }
            int idx = line.indexOf('=');
            if (idx < 0) {
                continue;
            }
            values.put(line.substring(0, idx), line.substring(idx + 1));
        }
        return values;
    }

    private static boolean checkSuperset(String baseName, String extraName) throws IOException {
        Map<String, String> base = parseAttheme(ASSETS.resolve(baseName));
        Map<String, String> extra = parseAttheme(ASSETS.resolve(extraName));
        List<String> missing = base.keySet().stream()
                .filter(k -> !extra.containsKey(k))
                .sorted()
                .collect(Collectors.toList());
        if (!missing.isEmpty()) {
            System.out.println(extraName + " is missing " + missing.size() + " key(s) from " + baseName + ": " + missing);
            return false;
        }
        System.out.println(extraName + ": superset of " + baseName + " (" + extra.size() + " >= " + base.size() + " keys)");
        return true;
    }

    // A key this change introduces that stringKeyToInt() can't resolve is a silent no-op at
    // runtime (Theme.getThemeFileValues()' keyFromString >= 0 guard just drops it). A key already
    // present under the same unresolved name in the baseline asset is a pre-existing bug this
    // change inherited rather than introduced, so it's tolerated rather than failed here - fixing
    // the base file is out of scope for this script.
    private static boolean checkKeys(String baseName, String extraName, Set<String> validKeys) throws IOException {
        Map<String, String> base = parseAttheme(ASSETS.resolve(baseName));
        Map<String, String> extra = parseAttheme(ASSETS.resolve(extraName));
        List<String> newlyInvalid = extra.keySet().stream()
                .filter(k -> !validKeys.contains(k) && !base.containsKey(k))
                .sorted()
                .collect(Collectors.toList());
        if (!newlyInvalid.isEmpty()) {
            System.out.println(extraName + " has " + newlyInvalid.size() + " key(s) unknown to ThemeColors and not inherited from "
                    + baseName + " (dropped silently at runtime): " + newlyInvalid);
            return false;
        }
        List<String> inheritedInvalid = extra.keySet().stream()
                .filter(k -> !validKeys.contains(k))
                .sorted()
                .collect(Collectors.toList());
        if (!inheritedInvalid.isEmpty()) {
            System.out.println(extraName + ": " + inheritedInvalid.size() + " key(s) unknown to ThemeColors, inherited verbatim from "
                    + baseName + " (pre-existing, not introduced here): " + inheritedInvalid);
        }
        System.out.println(extraName + ": no newly-introduced unresolvable keys");
        return true;
    }

    // Mirrors MonetHelper.getColor()'s own parse order: strip a trailing (NN) alpha suffix first,
    // then check the remainder for a trailing _NN darken suffix, same as the resolver does.
    private static boolean isResolvable(String value, Set<String> vocabulary) {
        String v = value;
        if (HEX_COLOR.matcher(v).matches() || v.matches("-?\\d+")) {
            return true;
        }
        if (v.startsWith("#")) {
            // Wrong-length/invalid hex: Color.parseColor() throws, falls back to
            // Utilities.parseInt() (likely 0) - same silent failure class as a bad token.
            return false;
        }

        Matcher alpha = Pattern.compile("^(.*)\\((\\d{1,3})\\)$").matcher(v);
        if (alpha.matches()) {
            int percent;
            try {
                percent = Integer.parseInt(alpha.group(2));
            } catch (NumberFormatException e) {
                return false;
            }
            if (percent < 0 || percent > 100) {
                return false;
            }
            v = alpha.group(1);
        }

        Matcher darken = Pattern.compile("^(.+)_(\\d+)$").matcher(v);
        if (darken.matches() && vocabulary.contains(darken.group(1))) {
            // getColor() does Integer.parseInt(darkenPercentValue) with no bounds guard of its
            // own - a digit run long enough to overflow int throws, gets swallowed by getColor()'s
            // catch-all, and silently resolves to 0, exactly like an unknown token would.
            try {
                Integer.parseInt(darken.group(2));
            } catch (NumberFormatException e) {
                return false;
            }
            v = darken.group(1);
        }

        return vocabulary.contains(v);
    }

    // Guards the exact regression this file was written to catch: chat_wallpaper resolving to
    // the same ramp step as chat_messagePanelBackground (with the same or near-same darken amount)
    // makes the composer panel disappear into the chat canvas, on every wallpaper, since two
    // identical tokens always resolve identically regardless of the Material You seed they're fed.
    // This compares token *structure* (ramp step + darken suffix), never a resolved RGB value -
    // resolved colors are wallpaper-dependent and can't be known statically, only the relationship
    // between tokens can. A value this script can't decompose into a plain ramp token (a raw hex or
    // literal int) is skipped rather than failed, per the same "unresolvable shape" tolerance
    // checkValues already applies.
    private static final int MIN_DARKEN_DELTA = 10;

    private static boolean checkWallpaperPanelContrast(String assetName, Set<String> vocabulary) throws IOException {
        Map<String, String> values = parseAttheme(ASSETS.resolve(assetName));
        String wallpaper = values.get("chat_wallpaper");
        String panel = values.get("chat_messagePanelBackground");
        if (wallpaper == null || panel == null) {
            System.out.println(assetName + ": chat_wallpaper/chat_messagePanelBackground missing, skipping contrast check");
            return true;
        }

        String[] wallpaperParts = decomposeToken(wallpaper, vocabulary);
        String[] panelParts = decomposeToken(panel, vocabulary);
        if (wallpaperParts == null || panelParts == null) {
            System.out.println(assetName + ": chat_wallpaper/chat_messagePanelBackground isn't a plain ramp token, skipping contrast check");
            return true;
        }

        if (!wallpaperParts[0].equals(panelParts[0])) {
            // Different ramp step already means a distinct tone, whatever darken/alpha rides on top.
            System.out.println(assetName + ": chat_wallpaper (" + wallpaper + ") and chat_messagePanelBackground (" + panel
                    + ") sit on different ramp steps");
            return true;
        }

        int wallpaperDarken = Integer.parseInt(wallpaperParts[1]);
        int panelDarken = Integer.parseInt(panelParts[1]);
        int delta = Math.abs(wallpaperDarken - panelDarken);
        if (delta < MIN_DARKEN_DELTA) {
            System.out.println(assetName + ": chat_wallpaper=" + wallpaper + " and chat_messagePanelBackground=" + panel
                    + " resolve to the same ramp step with only a " + delta + "% darken difference - the composer panel"
                    + " would be indistinguishable from the chat canvas on every wallpaper");
            return false;
        }
        System.out.println(assetName + ": chat_wallpaper (" + wallpaper + ") and chat_messagePanelBackground (" + panel
                + ") share a ramp step but differ by " + delta + "% darken - contrast holds");
        return true;
    }

    // Mirrors MonetHelper.getColor()'s own strip order exactly: a trailing "(NN)" alpha suffix
    // first, then a trailing "_NN" darken suffix - but only when the part before that underscore is
    // itself a resolvable token (same canResolveColor() guard getColor() uses), otherwise the
    // whole string is the literal token (e.g. "n1_900" is one token, not "n1" darkened by 900).
    // Returns {baseToken, darkenPercent} (darkenPercent defaults to "0"), or null if the value isn't
    // a plain ramp token at all.
    private static String[] decomposeToken(String value, Set<String> vocabulary) {
        String v = value;
        if (HEX_COLOR.matcher(v).matches() || v.matches("-?\\d+")) {
            return null;
        }
        Matcher alpha = Pattern.compile("^(.*)\\((\\d{1,3})\\)$").matcher(v);
        if (alpha.matches()) {
            v = alpha.group(1);
        }

        int lastUnderscore = v.lastIndexOf('_');
        if (lastUnderscore > 0 && lastUnderscore < v.length() - 1) {
            String suffix = v.substring(lastUnderscore + 1);
            String candidateBase = v.substring(0, lastUnderscore);
            if (suffix.chars().allMatch(Character::isDigit) && vocabulary.contains(candidateBase)) {
                return new String[]{candidateBase, suffix};
            }
        }
        if (!vocabulary.contains(v)) {
            return null;
        }
        return new String[]{v, "0"};
    }

    private static boolean checkValues(String assetName, Set<String> vocabulary) throws IOException {
        Map<String, String> values = parseAttheme(ASSETS.resolve(assetName));
        List<String> bad = new ArrayList<>();
        for (Map.Entry<String, String> e : values.entrySet()) {
            if (!isResolvable(e.getValue(), vocabulary)) {
                bad.add(e.getKey() + "=" + e.getValue());
            }
        }
        if (!bad.isEmpty()) {
            System.out.println(assetName + " has " + bad.size() + " unresolvable value(s): " + bad);
            return false;
        }
        System.out.println(assetName + ": all " + values.size() + " values resolve");
        return true;
    }
}
