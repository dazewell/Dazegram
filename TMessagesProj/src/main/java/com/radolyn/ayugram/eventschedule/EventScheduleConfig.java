package com.radolyn.ayugram.eventschedule;

/**
 * Immutable trigger definition captured from the "Send on event" chip when a bulk reschedule
 * sheet is confirmed. Carries only the user-chosen matching config -- the same fields an
 * {@link EventScheduleEntry} stores -- and no per-message state: one config is applied
 * identically to every message the reschedule actually moved. {@code null} stands for "chip
 * left Off", i.e. arm nothing.
 */
public final class EventScheduleConfig {

    public final int types;
    public final String pattern;
    public final boolean regex;
    public final int delaySeconds;

    public EventScheduleConfig(int types, String pattern, boolean regex, int delaySeconds) {
        this.types = types;
        this.pattern = pattern == null ? "" : pattern;
        this.regex = regex;
        this.delaySeconds = delaySeconds;
    }
}
