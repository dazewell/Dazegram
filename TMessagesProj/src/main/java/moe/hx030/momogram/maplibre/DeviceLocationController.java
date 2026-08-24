
package moe.hx030.momogram.maplibre;

import android.annotation.SuppressLint;
import android.content.Context;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.telegram.messenger.ApplicationLoader;

import java.util.ArrayList;
import java.util.List;

public class DeviceLocationController implements LocationListener {
    public interface Listener {
        void onLocationChanged(Location location);
    }

    private final List<String> providers = new ArrayList<>();
    private final List<Runnable> firstFixRunnables = new ArrayList<>();
    private long minTimeMs;
    private float minDistanceMeters;
    private LocationManager locationManager;
    private Listener listener;
    private Location lastLocation;
    private boolean enabled;

    public DeviceLocationController() {
        providers.add(LocationManager.GPS_PROVIDER);
    }

    public void setListener(@Nullable Listener listener) {
        this.listener = listener;
    }

    public void setMinTimeMs(long minTimeMs) {
        this.minTimeMs = minTimeMs;
    }

    public void setMinDistanceMeters(float minDistanceMeters) {
        this.minDistanceMeters = minDistanceMeters;
    }

    public void addProvider(@NonNull String provider) {
        if (!providers.contains(provider)) {
            providers.add(provider);
        }
    }

    @Nullable
    public Location getLastLocation() {
        return lastLocation;
    }

    public void runOnFirstFix(@NonNull Runnable runnable) {
        if (lastLocation != null) {
            runnable.run();
        } else {
            firstFixRunnables.add(runnable);
        }
    }

    @SuppressLint("MissingPermission")
    public void start() {
        enabled = true;
        if (locationManager == null) {
            locationManager = (LocationManager) ApplicationLoader.applicationContext.getSystemService(Context.LOCATION_SERVICE);
        }
        if (locationManager == null) {
            return;
        }
        stopInternal();
        for (String provider : providers) {
            try {
                locationManager.requestLocationUpdates(provider, minTimeMs, minDistanceMeters, this);
            } catch (Throwable ignore) {
            }
        }
    }

    public void stop() {
        enabled = false;
        stopInternal();
    }

    private void stopInternal() {
        if (locationManager != null) {
            try {
                locationManager.removeUpdates(this);
            } catch (Throwable ignore) {
            }
        }
    }

    @Override
    public void onLocationChanged(@NonNull Location location) {
        lastLocation = new Location(location);
        if (!firstFixRunnables.isEmpty()) {
            List<Runnable> pending = new ArrayList<>(firstFixRunnables);
            firstFixRunnables.clear();
            for (Runnable runnable : pending) {
                runnable.run();
            }
        }
        if (listener != null) {
            listener.onLocationChanged(lastLocation);
        }
        if (!enabled) {
            stopInternal();
        }
    }

    @Override
    public void onStatusChanged(String provider, int status, Bundle extras) {
    }

    @Override
    public void onProviderEnabled(@NonNull String provider) {
    }

    @Override
    public void onProviderDisabled(@NonNull String provider) {
    }
}
