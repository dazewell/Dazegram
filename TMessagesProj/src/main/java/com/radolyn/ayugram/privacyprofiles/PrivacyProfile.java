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
    public final long colorSeed;
    public final long createdAt;
    public final String icon;

    public PrivacyProfile(long id, String name, int timeout, long colorSeed, long createdAt, String icon) {
        this.id = id;
        this.name = name;
        this.timeout = timeout;
        this.colorSeed = colorSeed;
        this.createdAt = createdAt;
        this.icon = icon;
    }

    public PrivacyProfile withName(String newName) {
        return new PrivacyProfile(id, newName, timeout, colorSeed, createdAt, icon);
    }

    public PrivacyProfile withTimeout(int newTimeout) {
        return new PrivacyProfile(id, name, newTimeout, colorSeed, createdAt, icon);
    }

    public PrivacyProfile withIcon(String newIcon) {
        return new PrivacyProfile(id, name, timeout, colorSeed, createdAt, newIcon);
    }
}
