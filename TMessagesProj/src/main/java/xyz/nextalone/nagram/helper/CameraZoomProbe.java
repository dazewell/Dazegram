package xyz.nextalone.nagram.helper;

import android.content.Context;
import android.hardware.Camera;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.os.Build;
import android.util.Log;
import android.util.Range;
import android.util.SizeF;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BuildConfig;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.Utilities;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

// Temporary survey of what the device exposes for multi-lens zoom, dumped once per process when the
// round video recorder opens. Only camera hardware facts are logged.
public final class CameraZoomProbe {

    private static final String TAG = "NAX_SMOKE";
    private static final String P = "NAX_SMOKE_video-zoom-presets ";
    private static volatile boolean done;

    private CameraZoomProbe() {
    }

    public static void dump(boolean useCamera2) {
        if (done) return;
        done = true;
        Utilities.globalQueue.postRunnable(() -> {
            try {
                Log.i(TAG, P + "BEGIN build=" + BuildConfig.BUILD_VERSION_STRING + " app=" + BuildConfig.APPLICATION_ID
                        + " sdk=" + Build.VERSION.SDK_INT + " device=" + Build.MANUFACTURER + "/" + Build.MODEL
                        + " roundUsesCamera2=" + useCamera2 + " camera2Force=" + SharedConfig.useCamera2Force);
                dumpCamera1();
                dumpCamera2();
            } catch (Throwable t) {
                Log.e(TAG, P + "probe failed " + t.getClass().getName());
            }
            Log.i(TAG, P + "END");
        });
    }

    // Called from CameraSession once the round camera's parameters are read, so no second open is needed.
    public static void camera1Opened(int cameraId, boolean front, int maxZoom, List<Integer> ratios, boolean smooth) {
        int n = ratios == null ? 0 : ratios.size();
        Log.i(TAG, P + "camera1 opened id=" + cameraId + " front=" + front + " maxZoom=" + maxZoom + " smooth=" + smooth
                + " ratios=" + n + (n > 0 ? " first=" + ratios.get(0) + " last=" + ratios.get(n - 1) : ""));
    }

    public static void camera2Opened(String cameraId, boolean front) {
        Log.i(TAG, P + "camera2 opened id=" + cameraId + " front=" + front);
    }

    private static void dumpCamera1() {
        int count = Camera.getNumberOfCameras();
        StringBuilder sb = new StringBuilder(P).append("camera1 count=").append(count);
        Camera.CameraInfo info = new Camera.CameraInfo();
        for (int i = 0; i < count; i++) {
            try {
                Camera.getCameraInfo(i, info);
                sb.append(" [").append(i).append(info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT ? " front" : " back").append(']');
            } catch (Throwable t) {
                sb.append(" [").append(i).append(" err]");
            }
        }
        Log.i(TAG, sb.toString());
    }

    private static void dumpCamera2() {
        final CameraManager manager = (CameraManager) ApplicationLoader.applicationContext.getSystemService(Context.CAMERA_SERVICE);
        String[] ids;
        try {
            ids = manager.getCameraIdList();
        } catch (Throwable t) {
            Log.e(TAG, P + "camera2 id list failed " + t.getClass().getName());
            return;
        }
        Log.i(TAG, P + "camera2 ids=" + Arrays.toString(ids));
        Set<String> listed = new HashSet<>(Arrays.asList(ids));
        Set<String> physicalSeen = new HashSet<>();
        for (String id : ids) {
            dumpCharacteristics(manager, id, "listed", physicalSeen);
        }
        for (String id : physicalSeen) {
            if (!listed.contains(id)) {
                dumpCharacteristics(manager, id, "physical", null);
            }
        }
        // Some vendors keep auxiliary lenses out of the id list but still answer for them directly.
        for (int i = 0; i < 16; i++) {
            String id = String.valueOf(i);
            if (listed.contains(id) || physicalSeen.contains(id)) continue;
            try {
                manager.getCameraCharacteristics(id);
            } catch (Throwable t) {
                continue;
            }
            dumpCharacteristics(manager, id, "hidden", null);
        }
    }

    private static void dumpCharacteristics(CameraManager manager, String id, String kind, Set<String> physicalOut) {
        CameraCharacteristics c;
        try {
            c = manager.getCameraCharacteristics(id);
        } catch (Throwable t) {
            Log.w(TAG, P + "camera2 " + kind + " id=" + id + " characteristics failed " + t.getClass().getName());
            return;
        }
        StringBuilder sb = new StringBuilder(P).append("camera2 ").append(kind).append(" id=").append(id);
        Integer facing = c.get(CameraCharacteristics.LENS_FACING);
        sb.append(" facing=").append(facing == null ? "?" : facing == CameraCharacteristics.LENS_FACING_FRONT ? "front" : facing == CameraCharacteristics.LENS_FACING_BACK ? "back" : "ext");
        sb.append(" hw=").append(c.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL));
        float[] focal = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
        sb.append(" focal=").append(Arrays.toString(focal));
        SizeF sensor = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE);
        sb.append(" sensor=").append(sensor);
        if (focal != null && focal.length > 0 && sensor != null && sensor.getWidth() > 0) {
            double diag = Math.hypot(sensor.getWidth(), sensor.getHeight());
            sb.append(" eq35=").append(Math.round(focal[0] * 43.27 / diag)).append("mm");
        }
        sb.append(" maxDigital=").append(c.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Range<Float> range = c.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE);
            sb.append(" zoomRatioRange=").append(range);
        }
        int[] caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);
        boolean logical = false;
        if (caps != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            for (int cap : caps) {
                if (cap == CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA) {
                    logical = true;
                }
            }
        }
        sb.append(" logicalMulti=").append(logical);
        if (logical) {
            Set<String> physical = c.getPhysicalCameraIds();
            sb.append(" physicalIds=").append(physical);
            if (physicalOut != null) {
                physicalOut.addAll(physical);
            }
        }
        Log.i(TAG, sb.toString());
    }
}
