// Sanity-checks the Extera Light/Dark Monet assets before a PR. Not a build gate - there is no
// test source set under TMessagesProj/src, so this is a standalone script (JDK 21 single-file
// source launch, no build step, no dependencies), the same way IconInk.java in this directory is.
//
// It checks two things, since a mistyped or dropped token is invisible to eyeballing a themed screen:
//
//   1. monet_extera_{light,dark}.attheme carry every key their monet_{light,dark}.attheme
//      counterpart does (a superset), so nothing silently falls back to the XML default.
//   2. every value in the two Extera assets either starts with '#', is a raw ARGB int literal, or
//      resolves against MonetHelper's own token vocabulary - parsed straight out of
//      MonetHelper.java so this can never drift from what the app actually resolves at runtime.
//      0 doubles as MonetHelper's own "failed to resolve" sentinel (see getColor()'s catch
//      clause), so a bad token and an intentional zero both render identically and neither
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

    public static void main(String[] args) throws IOException {
        Set<String> vocabulary = loadVocabulary(MONET_HELPER);
        System.out.println("Loaded " + vocabulary.size() + " tokens from " + MONET_HELPER);

        boolean ok = true;
        ok &= checkSuperset("monet_light.attheme", "monet_extera_light.attheme");
        ok &= checkSuperset("monet_dark.attheme", "monet_extera_dark.attheme");
        ok &= checkValues("monet_extera_light.attheme", vocabulary);
        ok &= checkValues("monet_extera_dark.attheme", vocabulary);

        System.out.println(ok ? "OK" : "FAILED");
        if (!ok) {
            System.exit(1);
        }
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

    // Mirrors MonetHelper.getColor()'s own parse order: strip a trailing (NN) alpha suffix first,
    // then check the remainder for a trailing _NN darken suffix, same as the resolver does.
    private static boolean isResolvable(String value, Set<String> vocabulary) {
        String v = value;
        if (v.startsWith("#") || v.matches("-?\\d+")) {
            return true;
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
            v = darken.group(1);
        }

        return vocabulary.contains(v);
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
