package com.radolyn.ayugram.eventschedule;

import java.util.ArrayList;
import java.util.List;

/**
 * Immutable trigger definition captured from the "Send on event" controls.
 *
 * <p>Contains only user-selected matching configuration and no per-message state. It is used by
 * both single-message edit arm/disarm flows and bulk arming. {@code null} still means "trigger
 * left Off".
 */
public final class EventScheduleConfig {

    public final int types;
    public final ArrayList<String> patterns;
    public final boolean regex;
    public final int delaySeconds;

    public EventScheduleConfig(int types, List<String> patterns, boolean regex, int delaySeconds) {
        this.types = types;
        this.patterns = new ArrayList<>(patterns == null ? java.util.Collections.emptyList() : patterns);
        this.regex = regex;
        this.delaySeconds = delaySeconds;
    }
}
