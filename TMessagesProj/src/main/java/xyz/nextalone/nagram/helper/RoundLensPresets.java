package xyz.nextalone.nagram.helper;

import android.content.Context;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.os.Build;
import android.util.Range;
import android.util.SizeF;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;
import java.util.Set;

// Fixed zoom stops for the round video recorder, matching the lenses a logical multi-camera reports:
// the ultrawide floor, 1x, each telephoto, and twice the longest telephoto. Hardware facts, so cached per
// camera id for the process.
public final class RoundLensPresets {

    private static final float[] NONE = new float[0];
    private static final int MAX_PRESETS = 4;
    private static final HashMap<String, float[]> cache = new HashMap<>();

    private RoundLensPresets() {
    }

    // empty when the camera has a single lens, or the platform can't zoom by ratio
    public static float[] get(String cameraId) {
        if (cameraId == null) return NONE;
        synchronized (cache) {
            float[] presets = cache.get(cameraId);
            if (presets == null) {
                presets = compute(cameraId);
                cache.put(cameraId, presets);
            }
            return presets;
        }
    }

    public static String label(float ratio) {
        return ratio < 1f ? String.format(Locale.US, "%.1fx", ratio) : String.format(Locale.US, "%dx", Math.round(ratio));
    }

    private static float[] compute(String cameraId) {
        if (Build.VERSION.SDK_INT < 30) return NONE;
        try {
            final CameraManager manager = (CameraManager) ApplicationLoader.applicationContext.getSystemService(Context.CAMERA_SERVICE);
            final CameraCharacteristics c = manager.getCameraCharacteristics(cameraId);
            final Range<Float> range = c.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE);
            if (range == null) return NONE;
            final float min = range.getLower(), max = range.getUpper();
            final ArrayList<Float> stops = new ArrayList<>();
            if (min < 0.95f) {
                stops.add(Math.max(min, Math.round(min * 10f) / 10f));
            }
            stops.add(1f);
            final float base = equivalentFocal(c);
            float longest = 0f;
            final Set<String> physical = c.getPhysicalCameraIds();
            if (base > 0f) {
                for (String id : physical) {
                    final float eq = equivalentFocal(manager.getCameraCharacteristics(id));
                    // anything short of 1.5x is the main sensor or the ultrawide, which the range floor covers
                    final float ratio = eq / base;
                    if (ratio >= 1.5f && Math.round(ratio) <= max) {
                        addStop(stops, Math.round(ratio));
                        longest = Math.max(longest, Math.round(ratio));
                    }
                }
            }
            if (longest > 0f && longest * 2f <= max) {
                addStop(stops, longest * 2f);
            }
            if (stops.size() < 2) return NONE;
            stops.sort(Float::compare);
            final int n = Math.min(MAX_PRESETS, stops.size());
            final float[] result = new float[n];
            for (int i = 0; i < n; i++) {
                result[i] = stops.get(i);
            }
            return result;
        } catch (Exception e) {
            FileLog.e(e);
            return NONE;
        }
    }

    private static void addStop(ArrayList<Float> stops, float ratio) {
        for (float s : stops) {
            if (label(s).equals(label(ratio))) return;
        }
        stops.add(ratio);
    }

    // 35mm-equivalent focal length, so lenses on different sensor sizes compare
    private static float equivalentFocal(CameraCharacteristics c) {
        final float[] focal = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
        final SizeF sensor = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE);
        if (focal == null || focal.length == 0 || sensor == null) return 0f;
        final double diagonal = Math.hypot(sensor.getWidth(), sensor.getHeight());
        return diagonal <= 0 ? 0f : (float) (focal[0] * 43.27 / diagonal);
    }
}
