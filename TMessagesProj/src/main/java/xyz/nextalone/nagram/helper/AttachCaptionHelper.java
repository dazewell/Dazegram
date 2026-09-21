package xyz.nextalone.nagram.helper;

import android.text.SpannableStringBuilder;
import android.text.TextUtils;

import org.telegram.messenger.MediaDataController;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;

/**
 * Helpers for the attach-caption rescue (#attach-caption-guard).
 */
public final class AttachCaptionHelper {

    private AttachCaptionHelper() {
    }

    /**
     * Whether a caption editor is holding something its media entry hasn't been given yet.
     *
     * <p>A raw text comparison is not enough. {@code PhotoViewer.applyCaption()} runs the editor text
     * through {@link MediaDataController#getEntities} before storing it, and that parses markdown --
     * typing {@code `code`} stores {@code code}. Comparing the editor against the stored caption would
     * therefore report a difference forever after the first apply. This derives the same canonical
     * text and entities the apply would produce and compares that pair instead.
     *
     * <p>The editor text is copied before it goes in, because {@code getEntities} rewrites the element
     * it is handed and must not be let near the live {@code Editable}.
     */
    public static boolean hasUnappliedCaption(int account, CharSequence editorText, boolean allowNewEntities,
                                              CharSequence storedCaption, ArrayList<TLRPC.MessageEntity> storedEntities) {
        final CharSequence[] candidate = new CharSequence[]{
                editorText == null ? null : new SpannableStringBuilder(editorText)
        };
        final ArrayList<TLRPC.MessageEntity> candidateEntities =
                MediaDataController.getInstance(account).getEntities(candidate, allowNewEntities);
        if (TextUtils.isEmpty(storedCaption) && TextUtils.isEmpty(candidate[0])) {
            return false;
        }
        if (!TextUtils.equals(storedCaption, candidate[0])) {
            return true;
        }
        return !sameEntities(storedEntities, candidateEntities);
    }

    private static boolean sameEntities(ArrayList<TLRPC.MessageEntity> a, ArrayList<TLRPC.MessageEntity> b) {
        final int sizeA = a == null ? 0 : a.size();
        final int sizeB = b == null ? 0 : b.size();
        if (sizeA != sizeB) {
            return false;
        }
        for (int i = 0; i < sizeA; i++) {
            final TLRPC.MessageEntity one = a.get(i);
            final TLRPC.MessageEntity two = b.get(i);
            if (one == null || two == null) {
                if (one != two) {
                    return false;
                }
                continue;
            }
            if (!MediaDataController.entitiesEqual(one, two)) {
                return false;
            }
        }
        return true;
    }
}
