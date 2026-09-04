package xyz.nextalone.nagram.helper;

import android.os.SystemClock;
import android.util.Log;
import android.util.SparseArray;
import android.util.SparseIntArray;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.Components.MotionBackgroundDrawable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Locale;

/**
 * Temporary Phase A diagnostics for #glass-pattern-fix.
 * One idle summary per motion burst, no per-frame logs.
 */
public final class GlassPatternSmokeDiagnostics {

    private static final String LOG_TAG = "NagramX";
    public static final String SMOKE_TAG = "NAX_SMOKE_glass-pattern-fix";
    private static final long IDLE_SUMMARY_DELAY_MS = 300L;
    private static final int UNKNOWN_PRODUCER_ID = Integer.MIN_VALUE;
    private static final int MAX_COMPOSE_SAMPLES = 1024;

    private static final Object lock = new Object();
    private static final SparseArray<BurstState> statesByOwner = new SparseArray<>();
    private static final SparseIntArray generationCountByProducer = new SparseIntArray();
    private static final SparseIntArray motionPostCountByProducer = new SparseIntArray();

    private GlassPatternSmokeDiagnostics() {
    }

    public static void onInvalidateMotionBackgroundPosted(MotionBackgroundDrawable producer) {
        if (producer == null) {
            return;
        }
        synchronized (lock) {
            int producerId = System.identityHashCode(producer);
            motionPostCountByProducer.put(producerId, motionPostCountByProducer.get(producerId) + 1);
        }
    }

    public static void onProceduralGradientGenerated(MotionBackgroundDrawable producer) {
        if (producer == null) {
            return;
        }
        synchronized (lock) {
            int producerId = System.identityHashCode(producer);
            generationCountByProducer.put(producerId, generationCountByProducer.get(producerId) + 1);
        }
    }

    public static void onInvalidateMotionBackgroundArrival(int ownerId, Object producerArg, float refreshRateHz, boolean refreshPending) {
        synchronized (lock) {
            BurstState state = stateForOwnerLocked(ownerId);
            long now = SystemClock.elapsedRealtime();
            if (!state.active) {
                state.resetBurst(now);
                state.active = true;
            }
            state.lastArrivalUptimeMs = now;
            state.arrivals++;
            if (refreshPending) {
                state.arrivalsWhileRefreshPending++;
            }
            if (refreshRateHz > 0f) {
                state.refreshRateHz = refreshRateHz;
            }
            ProducerIdentity producer = resolveProducerIdentity(producerArg);
            state.producerArrivalCount.put(producer.id, state.producerArrivalCount.get(producer.id) + 1);
            if (state.producerLabelById.indexOfKey(producer.id) < 0) {
                state.producerLabelById.put(producer.id, producer.label);
            }
            if (producer.trackGeneration && state.generationBaselineByProducer.indexOfKey(producer.id) < 0) {
                state.generationBaselineByProducer.put(producer.id, generationCountByProducer.get(producer.id));
                state.motionPostBaselineByProducer.put(producer.id, motionPostCountByProducer.get(producer.id));
            }
            AndroidUtilities.cancelRunOnUIThread(state.idleSummaryRunnable);
            AndroidUtilities.runOnUIThread(state.idleSummaryRunnable, IDLE_SUMMARY_DELAY_MS);
        }
    }

    public static void onRefreshExecution(int ownerId) {
        synchronized (lock) {
            BurstState state = statesByOwner.get(ownerId);
            if (state == null || !state.active) {
                return;
            }
            long now = SystemClock.elapsedRealtime();
            state.refreshExecutions++;
            if (state.lastRefreshUptimeMs > 0) {
                long gap = now - state.lastRefreshUptimeMs;
                if (gap > state.maxInterExecutionGapMs) {
                    state.maxInterExecutionGapMs = gap;
                }
            }
            state.lastRefreshUptimeMs = now;
        }
    }

    public static void onComposeSample(int ownerId, boolean gradientChanged, long durationNanos) {
        synchronized (lock) {
            BurstState state = statesByOwner.get(ownerId);
            if (state == null || !state.active) {
                return;
            }
            if (state.composeDurationNanos.size() < MAX_COMPOSE_SAMPLES) {
                state.composeDurationNanos.add(durationNanos);
            }
            if (gradientChanged) {
                state.freshCompositeCount++;
            }
        }
    }

    public static void onOwnerDestroyed(int ownerId) {
        synchronized (lock) {
            BurstState state = statesByOwner.get(ownerId);
            if (state == null) {
                return;
            }
            AndroidUtilities.cancelRunOnUIThread(state.idleSummaryRunnable);
            statesByOwner.remove(ownerId);
        }
    }

    private static void emitSummary(int ownerId) {
        final String summary;
        synchronized (lock) {
            BurstState state = statesByOwner.get(ownerId);
            if (state == null || !state.active || state.arrivals == 0) {
                return;
            }
            long now = SystemClock.elapsedRealtime();
            long idleMs = now - state.lastArrivalUptimeMs;
            if (idleMs < IDLE_SUMMARY_DELAY_MS) {
                AndroidUtilities.cancelRunOnUIThread(state.idleSummaryRunnable);
                AndroidUtilities.runOnUIThread(state.idleSummaryRunnable, IDLE_SUMMARY_DELAY_MS - idleMs);
                return;
            }
            long burstDurationMs = now - state.startedUptimeMs;
            long composeP95Ns = percentile95(state.composeDurationNanos);
            long composeMaxNs = max(state.composeDurationNanos);
            int proceduralGenerationCount = generationDelta(state);
            int motionPostCount = motionPostDelta(state);
            summary = String.format(Locale.US,
                    "%s burst owner=%s durationMs=%d arrivals=%d arrivalsWhileRefreshPending=%d refreshExecutions=%d maxInterExecutionGapMs=%d composeP95Ms=%.2f composeMaxMs=%.2f refreshRateHz=%.1f producers=%s freshCompositeCount=%d proceduralGradientGenerationCount=%d motionPostCount=%d",
                    SMOKE_TAG,
                    hex(ownerId),
                    burstDurationMs,
                    state.arrivals,
                    state.arrivalsWhileRefreshPending,
                    state.refreshExecutions,
                    state.maxInterExecutionGapMs,
                    nanosToMs(composeP95Ns),
                    nanosToMs(composeMaxNs),
                    state.refreshRateHz,
                    producersSummary(state),
                    state.freshCompositeCount,
                    proceduralGenerationCount,
                    motionPostCount
            );
            state.active = false;
            state.clearCounters();
        }
        Log.i(LOG_TAG, summary);
    }

    private static BurstState stateForOwnerLocked(int ownerId) {
        BurstState state = statesByOwner.get(ownerId);
        if (state != null) {
            return state;
        }
        state = new BurstState(ownerId);
        statesByOwner.put(ownerId, state);
        return state;
    }

    private static ProducerIdentity resolveProducerIdentity(Object producerArg) {
        if (producerArg instanceof MotionBackgroundDrawable) {
            int id = System.identityHashCode(producerArg);
            return new ProducerIdentity(id, "MotionBackgroundDrawable@" + hex(id), true);
        }
        if (producerArg == null) {
            return new ProducerIdentity(UNKNOWN_PRODUCER_ID, "none", false);
        }
        int id = System.identityHashCode(producerArg);
        String className = producerArg.getClass().getSimpleName();
        return new ProducerIdentity(id, className + "@" + hex(id), false);
    }

    private static String producersSummary(BurstState state) {
        StringBuilder sb = new StringBuilder();
        sb.append(state.producerArrivalCount.size()).append("[");
        for (int i = 0; i < state.producerArrivalCount.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            int producerId = state.producerArrivalCount.keyAt(i);
            String label = state.producerLabelById.get(producerId);
            if (label == null) {
                label = "id@" + hex(producerId);
            }
            sb.append(label).append("=").append(state.producerArrivalCount.valueAt(i));
        }
        sb.append("]");
        return sb.toString();
    }

    private static int generationDelta(BurstState state) {
        int sum = 0;
        for (int i = 0; i < state.generationBaselineByProducer.size(); i++) {
            int producerId = state.generationBaselineByProducer.keyAt(i);
            int baseline = state.generationBaselineByProducer.valueAt(i);
            int current = generationCountByProducer.get(producerId);
            if (current > baseline) {
                sum += current - baseline;
            }
        }
        return sum;
    }

    private static int motionPostDelta(BurstState state) {
        int sum = 0;
        for (int i = 0; i < state.motionPostBaselineByProducer.size(); i++) {
            int producerId = state.motionPostBaselineByProducer.keyAt(i);
            int baseline = state.motionPostBaselineByProducer.valueAt(i);
            int current = motionPostCountByProducer.get(producerId);
            if (current > baseline) {
                sum += current - baseline;
            }
        }
        return sum;
    }

    private static long percentile95(ArrayList<Long> samplesNs) {
        if (samplesNs.isEmpty()) {
            return 0L;
        }
        ArrayList<Long> sorted = new ArrayList<>(samplesNs);
        Collections.sort(sorted);
        int index = (int) Math.ceil(sorted.size() * 0.95d) - 1;
        if (index < 0) {
            index = 0;
        } else if (index >= sorted.size()) {
            index = sorted.size() - 1;
        }
        return sorted.get(index);
    }

    private static long max(ArrayList<Long> samplesNs) {
        long max = 0L;
        for (int i = 0; i < samplesNs.size(); i++) {
            long value = samplesNs.get(i);
            if (value > max) {
                max = value;
            }
        }
        return max;
    }

    private static String hex(int value) {
        return Integer.toHexString(value);
    }

    private static double nanosToMs(long nanos) {
        return nanos / 1_000_000.0d;
    }

    private static final class ProducerIdentity {
        final int id;
        final String label;
        final boolean trackGeneration;

        ProducerIdentity(int id, String label, boolean trackGeneration) {
            this.id = id;
            this.label = label;
            this.trackGeneration = trackGeneration;
        }
    }

    private static final class BurstState {
        final Runnable idleSummaryRunnable;
        final SparseIntArray producerArrivalCount = new SparseIntArray();
        final SparseArray<String> producerLabelById = new SparseArray<>();
        final SparseIntArray generationBaselineByProducer = new SparseIntArray();
        final SparseIntArray motionPostBaselineByProducer = new SparseIntArray();
        final ArrayList<Long> composeDurationNanos = new ArrayList<>();

        boolean active;
        long startedUptimeMs;
        long lastArrivalUptimeMs;
        long lastRefreshUptimeMs;
        long maxInterExecutionGapMs;
        float refreshRateHz = AndroidUtilities.screenRefreshRate;
        int arrivals;
        int arrivalsWhileRefreshPending;
        int refreshExecutions;
        int freshCompositeCount;

        BurstState(int ownerId) {
            idleSummaryRunnable = () -> emitSummary(ownerId);
        }

        void resetBurst(long now) {
            clearCounters();
            startedUptimeMs = now;
            lastArrivalUptimeMs = now;
            lastRefreshUptimeMs = 0L;
            if (refreshRateHz <= 0f) {
                refreshRateHz = AndroidUtilities.screenRefreshRate;
            }
        }

        void clearCounters() {
            arrivals = 0;
            arrivalsWhileRefreshPending = 0;
            refreshExecutions = 0;
            maxInterExecutionGapMs = 0L;
            freshCompositeCount = 0;
            producerArrivalCount.clear();
            producerLabelById.clear();
            generationBaselineByProducer.clear();
            motionPostBaselineByProducer.clear();
            composeDurationNanos.clear();
        }
    }
}
