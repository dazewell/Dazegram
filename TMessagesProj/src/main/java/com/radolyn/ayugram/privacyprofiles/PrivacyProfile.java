package com.radolyn.ayugram.privacyprofiles;

/**
 * A named, saved auto-lock timeout. See {@link PrivacyProfilesController} for what activating
 * one actually does -- this class is plain data.
 */
public final class PrivacyProfile {

    public final long id;
    public final String name;
    public final int timeout;
    public final long colorSeed;
    public final long createdAt;

    public PrivacyProfile(long id, String name, int timeout, long colorSeed, long createdAt) {
        this.id = id;
        this.name = name;
        this.timeout = timeout;
        this.colorSeed = colorSeed;
        this.createdAt = createdAt;
    }

    public PrivacyProfile withName(String newName) {
        return new PrivacyProfile(id, newName, timeout, colorSeed, createdAt);
    }

    public PrivacyProfile withTimeout(int newTimeout) {
        return new PrivacyProfile(id, name, newTimeout, colorSeed, createdAt);
    }
}
