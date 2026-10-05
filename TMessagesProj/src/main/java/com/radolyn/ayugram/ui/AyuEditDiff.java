package com.radolyn.ayugram.ui;

import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;

import tw.nekomimi.nekogram.helpers.MessageHelper;

/**
 * Word-level inline diff for the edits history: text removed since the previous version is kept
 * in place and struck through, text added is bold and underlined. Marks are ordinary message
 * entities, so the bubble renders them like any other formatting.
 */
final class AyuEditDiff {

    // An edit this sprawling no longer reads as a diff, and the LCS table would cost megabytes.
    private static final long MAX_CELLS = 250_000L;

    private static final int EQUAL = 0;
    private static final int DELETED = 1;
    private static final int INSERTED = 2;

    static final class Result {
        final String text;
        final ArrayList<TLRPC.MessageEntity> entities;

        Result(String text, ArrayList<TLRPC.MessageEntity> entities) {
            this.text = text;
            this.entities = entities;
        }
    }

    private static final class Op {
        final int type;
        final String text;

        Op(int type, String text) {
            this.type = type;
            this.text = text;
        }
    }

    private AyuEditDiff() {
    }

    /**
     * Diffs {@code newText} against {@code oldText}. {@code newEntities} are offsets into
     * {@code newText} and are carried over to the result. Returns null when the texts are equal
     * or too large to diff, in which case the version is shown as is.
     */
    static Result build(String oldText, String newText, ArrayList<TLRPC.MessageEntity> newEntities) {
        if (oldText == null) {
            oldText = "";
        }
        if (newText == null) {
            newText = "";
        }
        if (oldText.equals(newText)) {
            return null;
        }
        ArrayList<String> a = tokenize(oldText);
        ArrayList<String> b = tokenize(newText);

        int prefix = 0;
        while (prefix < a.size() && prefix < b.size() && a.get(prefix).equals(b.get(prefix))) {
            prefix++;
        }
        int suffix = 0;
        while (suffix < a.size() - prefix && suffix < b.size() - prefix
                && a.get(a.size() - 1 - suffix).equals(b.get(b.size() - 1 - suffix))) {
            suffix++;
        }
        int n = a.size() - prefix - suffix;
        int m = b.size() - prefix - suffix;
        if ((long) (n + 1) * (m + 1) > MAX_CELLS) {
            return null;
        }

        int[][] lcs = new int[n + 1][m + 1];
        for (int i = n - 1; i >= 0; i--) {
            for (int j = m - 1; j >= 0; j--) {
                lcs[i][j] = a.get(prefix + i).equals(b.get(prefix + j))
                        ? lcs[i + 1][j + 1] + 1
                        : Math.max(lcs[i + 1][j], lcs[i][j + 1]);
            }
        }

        ArrayList<Op> ops = new ArrayList<>();
        for (int k = 0; k < prefix; k++) {
            ops.add(new Op(EQUAL, b.get(k)));
        }
        int i = 0, j = 0;
        while (i < n || j < m) {
            if (i < n && j < m && a.get(prefix + i).equals(b.get(prefix + j))) {
                ops.add(new Op(EQUAL, b.get(prefix + j)));
                i++;
                j++;
            } else if (j >= m || (i < n && lcs[i + 1][j] >= lcs[i][j + 1])) {
                ops.add(new Op(DELETED, a.get(prefix + i)));
                i++;
            } else {
                ops.add(new Op(INSERTED, b.get(prefix + j)));
                j++;
            }
        }
        for (int k = b.size() - suffix; k < b.size(); k++) {
            ops.add(new Op(EQUAL, b.get(k)));
        }

        // Fold whitespace that merely separates two changes into both sides, so "a b" -> "c d"
        // reads as one struck phrase and one added phrase instead of interleaved words.
        for (int k = 1; k < ops.size() - 1; k++) {
            Op op = ops.get(k);
            if (op.type == EQUAL && op.text.trim().isEmpty()
                    && ops.get(k - 1).type != EQUAL && ops.get(k + 1).type != EQUAL) {
                ops.set(k, new Op(INSERTED, op.text));
                ops.add(k, new Op(DELETED, op.text));
                k++;
            }
        }

        StringBuilder out = new StringBuilder(oldText.length() + newText.length() + 8);
        ArrayList<TLRPC.MessageEntity> entities = new ArrayList<>();
        // Where each char of newText landed in out; EQUAL and INSERTED ops replay newText in order.
        int[] newToOut = new int[newText.length() + 1];
        int newPos = 0;
        int k = 0;
        while (k < ops.size()) {
            if (ops.get(k).type == EQUAL) {
                newPos = appendNew(out, ops.get(k).text, newToOut, newPos);
                k++;
                continue;
            }
            StringBuilder deleted = new StringBuilder();
            StringBuilder inserted = new StringBuilder();
            while (k < ops.size() && ops.get(k).type != EQUAL) {
                Op op = ops.get(k);
                (op.type == DELETED ? deleted : inserted).append(op.text);
                k++;
            }
            if (deleted.length() > 0) {
                int start = out.length();
                out.append(deleted);
                entities.add(entity(new TLRPC.TL_messageEntityStrike(), start, deleted.length()));
                if (inserted.length() > 0 && !Character.isWhitespace(deleted.charAt(deleted.length() - 1))
                        && !Character.isWhitespace(inserted.charAt(0))) {
                    out.append(' ');
                }
            }
            if (inserted.length() > 0) {
                int start = out.length();
                newPos = appendNew(out, inserted, newToOut, newPos);
                entities.add(entity(new TLRPC.TL_messageEntityBold(), start, inserted.length()));
                entities.add(entity(new TLRPC.TL_messageEntityUnderline(), start, inserted.length()));
            }
        }
        newToOut[newText.length()] = out.length();

        if (newEntities != null) {
            for (TLRPC.MessageEntity original : newEntities) {
                if (original == null || original.length <= 0 || original.offset < 0
                        || original.offset + original.length > newText.length()) {
                    continue;
                }
                TLRPC.MessageEntity copy = MessageHelper.copyMessageEntity(original);
                if (copy == null) {
                    continue;
                }
                copy.offset = newToOut[original.offset];
                copy.length = newToOut[original.offset + original.length - 1] + 1 - copy.offset;
                entities.add(copy);
            }
        }
        return new Result(out.toString(), entities);
    }

    private static int appendNew(StringBuilder out, CharSequence text, int[] newToOut, int newPos) {
        for (int c = 0; c < text.length(); c++) {
            newToOut[newPos++] = out.length();
            out.append(text.charAt(c));
        }
        return newPos;
    }

    private static TLRPC.MessageEntity entity(TLRPC.MessageEntity entity, int offset, int length) {
        entity.offset = offset;
        entity.length = length;
        return entity;
    }

    // Runs of letters/digits, runs of whitespace, and runs of anything else, so an emoji sequence
    // or "?!" stays in one piece and a surrogate pair is never split.
    private static ArrayList<String> tokenize(String text) {
        ArrayList<String> tokens = new ArrayList<>();
        int start = 0;
        int startClass = -1;
        for (int c = 0; c < text.length(); ) {
            int codePoint = text.codePointAt(c);
            int cls = isWordChar(codePoint) ? 0 : Character.isWhitespace(codePoint) ? 1 : 2;
            if (startClass != -1 && cls != startClass) {
                tokens.add(text.substring(start, c));
                start = c;
            }
            startClass = cls;
            c += Character.charCount(codePoint);
        }
        if (start < text.length()) {
            tokens.add(text.substring(start));
        }
        return tokens;
    }

    private static boolean isWordChar(int codePoint) {
        if (codePoint >= 0xFE00 && codePoint <= 0xFE0F || codePoint >= 0xE0100 && codePoint <= 0xE01EF) {
            return false; // variation selectors belong to the emoji before them
        }
        if (Character.isLetterOrDigit(codePoint) || codePoint == '_') {
            return true;
        }
        int type = Character.getType(codePoint);
        // Combining marks (accents, Indic vowel signs) belong to the word they sit on.
        return type == Character.NON_SPACING_MARK || type == Character.COMBINING_SPACING_MARK;
    }
}
