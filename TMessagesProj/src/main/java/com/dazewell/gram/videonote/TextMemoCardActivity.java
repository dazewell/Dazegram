package com.dazewell.gram.videonote;

/**
 * The text memo without the wallpaper behind it, its own launcher shortcut. All it changes is the manifest entry's
 * floating theme, which can't be switched at runtime, and so the mode: TextMemoActivity builds just the card.
 */
public class TextMemoCardActivity extends TextMemoActivity {

    @Override
    protected boolean hasBackdrop() {
        return false;
    }
}
