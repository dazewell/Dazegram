package com.radolyn.ayugram.privacyprofiles;

/**
 * A named, saved auto-lock timeout. See {@link PrivacyProfilesController} for what activating
 * one actually does -- this class is plain data.
 */
public final class PrivacyProfile {

    /**
     * Fallback icon for profiles saved before this field existed. Must be a key that actually
     * exists in {@code tw.nekomimi.nekogram.folder.FolderIconHelper#folderIcons} -- see
     * {@link PrivacyProfilesController}'s load path for the migration that backfills this value
     * on any profile missing it.
     */
    public static final String DEFAULT_ICON = "\uD83D\uDCAC"; // same glyph as filter_all

    public final long id;
    public final String name;
    public final int timeout;
    /**
     * Picks the circle colour behind the glyph. Dual-meaning by design: a random long on profiles
     * created before the colour was user-choosable, and a plain 0..6 palette index once the user
     * picks a swatch. Both are legal because {@code AvatarDrawable.getColorIndex} reduces with
     * {@code Math.abs(id % length)}, for which 0..6 are fixed points -- so no migration is needed
     * and an unedited profile keeps the exact colour it has always had. Read it ONLY through
     * {@code AvatarDrawable.getColorIndex}: a hand-rolled {@code % 7} returns a negative index for
     * the random seeds and blows up on array access.
     */
    public final long colorSeed;
    /**
     * Which row of the palette the colour comes from: 0 = the light {@code keys_avatar_background}
     * row, 1 = the deep {@code keys_avatar_background2} row. Absent in profiles saved before the
     * deep row existed, which load as 0 and so keep exactly the colour they have always had.
     * Resolve it only through {@code PrivacyProfileColorRow.colorFor}, which clamps a deep tone
     * back to light under a theme that can't render the two rows apart.
     */
    public final int tone;
    public final long createdAt;
    public final String icon;

    public PrivacyProfile(long id, String name, int timeout, long colorSeed, long createdAt, String icon) {
        this(id, name, timeout, colorSeed, PrivacyProfileColorRow.TONE_LIGHT, createdAt, icon);
    }

    public PrivacyProfile(long id, String name, int timeout, long colorSeed, int tone, long createdAt, String icon) {
        this.id = id;
        this.name = name;
        this.timeout = timeout;
        this.colorSeed = colorSeed;
        this.tone = tone;
        this.createdAt = createdAt;
        this.icon = icon;
    }

    public PrivacyProfile withName(String newName) {
        return new PrivacyProfile(id, newName, timeout, colorSeed, tone, createdAt, icon);
    }

    public PrivacyProfile withTimeout(int newTimeout) {
        return new PrivacyProfile(id, name, newTimeout, colorSeed, tone, createdAt, icon);
    }

    public PrivacyProfile withIcon(String newIcon) {
        return new PrivacyProfile(id, name, timeout, colorSeed, tone, createdAt, newIcon);
    }

    public PrivacyProfile withColorSeed(long newColorSeed) {
        return new PrivacyProfile(id, name, timeout, newColorSeed, tone, createdAt, icon);
    }

    public PrivacyProfile withTone(int newTone) {
        return new PrivacyProfile(id, name, timeout, colorSeed, newTone, createdAt, icon);
    }
}
