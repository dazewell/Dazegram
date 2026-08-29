package tw.nekomimi.nekogram.helpers;

import android.text.TextUtils;
import android.webkit.MimeTypeMap;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessageObject;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import xyz.nextalone.nagram.NaConfig;

// Names saved video, voice and round messages from the message date instead of Telegram's
// "video.mp4". Everything about the naming lives here so MediaController only gets one-line hooks.
// Photos, documents and music are never touched - the guard in applies() decides that by media kind.
public final class SaveFileNameHelper {

    public static final String DEFAULT_PATTERN = "{date}_{time}";

    // MediaStore caps DISPLAY_NAME around 255 bytes; stay under it and leave room for the extension.
    private static final int MAX_NAME_BYTES = 200;

    // A fixed instant so the settings screen shows a stable, legible example rather than "now".
    private static final long DEMO_MILLIS = demoMillis();

    private SaveFileNameHelper() {
    }

    private static boolean applies(MessageObject messageObject) {
        return NaConfig.INSTANCE.getCustomFileNamesEnabled().Bool()
                && messageObject != null
                && messageObject.messageOwner != null
                && (messageObject.isVideo() || messageObject.isVoice() || messageObject.isRoundVideo());
    }

    // API 29+ MediaStore path. Returns the DISPLAY_NAME to use, or the incoming name unchanged when
    // the feature is off or this isn't a covered media kind. MediaStore de-duplicates colliding names.
    public static String apply(String incomingFilename, File sourceFile, String mimeType, MessageObject messageObject) {
        if (!applies(messageObject)) {
            return incomingFilename;
        }
        String base = renderBase(messageObject, incomingFilename);
        String ext = resolveExtension(FileLoader.getFileExtension(sourceFile), incomingFilename, mimeType);
        return withExtension(base, ext);
    }

    // Single-item legacy path (API < 29), used for both the type==0 and type==1 branches in saveFile.
    // When the feature applies the name is claimed atomically (createNewFile) so two same-second saves
    // can't render to the same File and overwrite each other; otherwise it falls back to the stock name.
    public static String legacyName(File dir, MessageObject messageObject, File sourceFile, String incomingName, String mime, int defaultType) {
        String srcExt = FileLoader.getFileExtension(sourceFile);
        if (!applies(messageObject)) {
            return AndroidUtilities.generateFileName(defaultType, srcExt);
        }
        String base = renderBase(messageObject, incomingName);
        String ext = resolveExtension(srcExt, incomingName, mime);
        return claimLegacyName(dir, base, ext, messageObject.messageOwner.id);
    }

    // Bulk legacy path (API < 29). Returns an atomically-claimed name when the feature applies, else
    // null so the caller keeps its stock name and its existing dedupe loop untouched (feature-off is
    // then byte-for-byte today's behaviour).
    public static String legacyBulkClaim(File dir, MessageObject messageObject, String stockName, String mime) {
        if (!applies(messageObject)) {
            return null;
        }
        String base = renderBase(messageObject, stockName);
        String ext = resolveExtension(extensionOf(stockName), stockName, mime);
        return claimLegacyName(dir, base, ext, messageObject.messageOwner.id);
    }

    // Rendered example for the settings row's detail line: a fixed demo instant and a legible demo
    // {name} so a {name} pattern still shows something. Never alters the stored pattern.
    public static String renderForDisplay(String pattern) {
        return renderBase(pattern, DEMO_MILLIS, "clip");
    }

    // OK-time validation in the dialog. Renders against the empty-name worst case (voice and round
    // always resolve {name} empty) and reports whether the result would degenerate to nothing usable.
    public static boolean isPatternValid(String pattern) {
        String rendered = sanitize(renderRaw(pattern, DEMO_MILLIS, ""));
        return !isDegenerate(rendered);
    }

    private static String renderBase(MessageObject messageObject, String incomingName) {
        return renderBase(NaConfig.INSTANCE.getCustomFileNamesPattern().String(), messageObject.messageOwner.date * 1000L, stripExtension(incomingName));
    }

    private static String renderBase(String pattern, long millis, String name) {
        String rendered = sanitize(renderRaw(pattern, millis, name));
        if (isDegenerate(rendered)) {
            rendered = sanitize(renderRaw(DEFAULT_PATTERN, millis, name));
        }
        return rendered;
    }

    private static String renderRaw(String pattern, long millis, String name) {
        if (pattern == null) {
            pattern = DEFAULT_PATTERN;
        }
        // Locale.US so the digits stay ASCII - a locale like fa/ar would otherwise render
        // Arabic-Indic digits straight into the file name.
        Date date = new Date(millis);
        String dateToken = new SimpleDateFormat("yyyyMMdd", Locale.US).format(date);
        String timeToken = new SimpleDateFormat("HHmmss", Locale.US).format(date);

        StringBuilder sb = new StringBuilder(pattern.length() + 16);
        int i = 0;
        while (i < pattern.length()) {
            char c = pattern.charAt(i);
            if (c == '{') {
                int end = pattern.indexOf('}', i);
                if (end != -1) {
                    String token = pattern.substring(i + 1, end);
                    switch (token) {
                        case "date" -> sb.append(dateToken);
                        case "time" -> sb.append(timeToken);
                        case "name" -> sb.append(name == null ? "" : name);
                        default -> { } // unknown token renders empty, never left as literal braces
                    }
                    i = end + 1;
                    continue;
                }
            }
            sb.append(c);
            i++;
        }
        return sb.toString();
    }

    private static String sanitize(String rendered) {
        String s = FileLoader.fixFileName(rendered);
        if (s == null) {
            s = "";
        }
        s = s.trim();
        return capUtf8(s, MAX_NAME_BYTES);
    }

    private static boolean isDegenerate(String s) {
        if (TextUtils.isEmpty(s)) {
            return true;
        }
        if (s.charAt(0) == '.') {
            // A leading dot is a hidden file - invisible in the gallery and indistinguishable from a
            // failed save.
            return true;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != '.' && !Character.isWhitespace(c)) {
                return false;
            }
        }
        return true;
    }

    // Atomic create-if-absent claim: createNewFile() is race-free against the other save thread and
    // another process, so no shared counter is needed. Matches the surrounding createNewFile idiom.
    private static String claimLegacyName(File dir, String base, String ext, int fallbackId) {
        for (int i = 0; i < 1000; i++) {
            String candidate = withExtension(i == 0 ? base : base + " (" + i + ")", ext);
            try {
                if (new File(dir, candidate).createNewFile()) {
                    return candidate;
                }
            } catch (IOException e) {
                FileLog.e(e);
            }
        }
        String fallbackBase = base + "_" + fallbackId;
        for (int i = 0; i < 1000; i++) {
            String candidate = withExtension(i == 0 ? fallbackBase : fallbackBase + " (" + i + ")", ext);
            try {
                if (new File(dir, candidate).createNewFile()) {
                    return candidate;
                }
            } catch (IOException e) {
                FileLog.e(e);
            }
        }
        return withExtension(fallbackBase, ext);
    }

    private static String resolveExtension(String sourceExt, String incomingName, String mime) {
        if (isRealExtension(sourceExt)) {
            return sourceExt;
        }
        String fromName = extensionOf(incomingName);
        if (isRealExtension(fromName)) {
            return fromName;
        }
        if (!TextUtils.isEmpty(mime)) {
            String fromMime = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime);
            if (!TextUtils.isEmpty(fromMime)) {
                return fromMime;
            }
        }
        return "";
    }

    private static boolean isRealExtension(String ext) {
        return !TextUtils.isEmpty(ext) && ext.indexOf('.') == -1 && ext.indexOf(' ') == -1 && ext.length() <= 5;
    }

    private static String extensionOf(String name) {
        if (TextUtils.isEmpty(name)) {
            return "";
        }
        int idx = name.lastIndexOf('.');
        return idx > 0 && idx < name.length() - 1 ? name.substring(idx + 1) : "";
    }

    private static String stripExtension(String name) {
        if (TextUtils.isEmpty(name)) {
            return "";
        }
        int idx = name.lastIndexOf('.');
        return idx > 0 ? name.substring(0, idx) : name;
    }

    private static String withExtension(String base, String ext) {
        return TextUtils.isEmpty(ext) ? base : base + "." + ext;
    }

    private static String capUtf8(String s, int maxBytes) {
        if (s.getBytes(StandardCharsets.UTF_8).length <= maxBytes) {
            return s;
        }
        int len = s.length();
        while (len > 0 && s.substring(0, len).getBytes(StandardCharsets.UTF_8).length > maxBytes) {
            len--;
        }
        return s.substring(0, len);
    }

    private static long demoMillis() {
        // 2026-01-01 17:38:12, device-local, matching the worked example in the settings copy.
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.set(2026, java.util.Calendar.JANUARY, 1, 17, 38, 12);
        c.set(java.util.Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }
}
