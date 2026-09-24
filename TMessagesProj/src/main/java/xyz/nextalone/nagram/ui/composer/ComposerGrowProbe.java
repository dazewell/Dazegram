package xyz.nextalone.nagram.ui.composer;

import android.util.Log;
import android.view.View;

import org.telegram.messenger.BuildConfig;

// Temporary device probe for the reply-strip offset while the field grows. Numbers only.
public final class ComposerGrowProbe {

    private static final String TAG = "NAX_SMOKE_composer_grow";
    private static final int MAX_FRAMES = 1500;
    private static final int MAX_STACKS = 80;

    public static int site;
    private static boolean begun;
    private static int frames;
    private static int stacks;

    private ComposerGrowProbe() {
    }

    public static void begin() {
        if (begun) {
            return;
        }
        begun = true;
        Log.e(TAG, "NAX_SMOKE_composer_grow BEGIN build=" + BuildConfig.BUILD_VERSION_STRING + " app=" + BuildConfig.APPLICATION_ID);
    }

    public static void end() {
        Log.e(TAG, "NAX_SMOKE_composer_grow END frames=" + frames + " stacks=" + stacks);
    }

    public static void topView(View enter, float factor, float toFactor, View topView, float visibility, View textFieldContainer, View editContainer, View toolbar, float island, float islandTarget) {
        if (topView == null || visibility <= 0 || frames >= MAX_FRAMES) {
            return;
        }
        frames++;
        Log.e(TAG, "NAX_SMOKE_composer_grow TV site=" + site
            + " mh=" + enter.getMeasuredHeight() + " h=" + enter.getHeight()
            + " f=" + factor + " to=" + toFactor
            + " tvmh=" + topView.getMeasuredHeight() + " tvlp=" + topView.getLayoutParams().height
            + " vis=" + visibility + " ty=" + topView.getTranslationY()
            + " tfc=" + textFieldContainer.getTop() + ".." + textFieldContainer.getBottom() + "/" + textFieldContainer.getMeasuredHeight()
            + " mec=" + editContainer.getTop() + ".." + editContainer.getBottom() + "/" + editContainer.getMeasuredHeight()
            + " tb=" + (toolbar == null ? "-" : toolbar.getTop() + ".." + toolbar.getBottom() + "/" + toolbar.getMeasuredHeight())
            + " isl=" + island + " islT=" + islandTarget);
        site = 0;
    }

    public static void measured(View enter, int before) {
        if (stacks >= MAX_STACKS) {
            return;
        }
        stacks++;
        final StackTraceElement[] st = new Throwable().getStackTrace();
        final StringBuilder sb = new StringBuilder();
        for (int i = 2; i < Math.min(st.length, 14); i++) {
            sb.append(' ').append(st[i].getClassName()).append('.').append(st[i].getMethodName()).append(':').append(st[i].getLineNumber());
        }
        Log.e(TAG, "NAX_SMOKE_composer_grow MEASURE before=" + before + " mh=" + enter.getMeasuredHeight() + " h=" + enter.getHeight() + " at" + sb);
    }

    public static void laidOut(View enter, boolean changed, int top, int bottom) {
        if (frames >= MAX_FRAMES) {
            return;
        }
        frames++;
        Log.e(TAG, "NAX_SMOKE_composer_grow LAYOUT changed=" + changed + " top=" + top + " bottom=" + bottom + " mh=" + enter.getMeasuredHeight());
    }

    public static void island(float current, float target, float enterIsland, View enter, View container, float bubbleTop, float bubbleBottom, float bubbleHeight) {
        if (enter == null || container == null || frames >= MAX_FRAMES) {
            return;
        }
        frames++;
        final int[] a = new int[2];
        final int[] b = new int[2];
        enter.getLocationInWindow(a);
        container.getLocationInWindow(b);
        final int enterTop = a[1] - b[1];
        Log.e(TAG, "NAX_SMOKE_composer_grow ISL cur=" + current + " tgt=" + target + " enterIsl=" + enterIsland
            + " pill=" + bubbleTop + ".." + bubbleBottom + " bh=" + bubbleHeight
            + " enter=" + enterTop + ".." + (enterTop + enter.getHeight()) + " ety=" + enter.getTranslationY());
    }
}
