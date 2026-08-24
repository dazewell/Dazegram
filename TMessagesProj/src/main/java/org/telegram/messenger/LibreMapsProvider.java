package org.telegram.messenger;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Point;
import android.graphics.PointF;
import android.graphics.drawable.BitmapDrawable;
import android.location.Location;
import android.location.LocationManager;
import android.os.Bundle;
import android.text.Html;
import android.text.method.LinkMovementMethod;
import android.view.MotionEvent;
import android.view.View;
import android.widget.TextView;

import androidx.core.util.Consumer;

import org.maplibre.android.MapLibre;
import org.maplibre.android.geometry.LatLngBounds;
import org.maplibre.android.maps.MapLibreMap;
import org.maplibre.android.style.layers.FillLayer;
import org.maplibre.android.style.layers.LineLayer;
import org.maplibre.android.style.sources.GeoJsonSource;

import java.util.ArrayList;
import java.util.List;

import moe.hx030.momogram.maplibre.DeviceLocationController;
import moe.hx030.momogram.maplibre.GeoUtils;
import moe.hx030.momogram.maplibre.MapLibreView;
import moe.hx030.momogram.maplibre.MapPin;
import moe.hx030.momogram.maplibre.MapStyleFactory;

public class LibreMapsProvider implements IMapsProvider {

    @Override
    public void initializeMaps(Context context) {
        MapLibre.getInstance(context);
    }

    @Override
    public IMapView onCreateMapView(Context context) {
        return new LibreMapLibreView(context);
    }

    @Override
    public IMarkerOptions onCreateMarkerOptions(IMapView imapView) {
        return new LibreMarkerOptions((MapLibreView) imapView.getView());
    }

    @Override
    public ICircleOptions onCreateCircleOptions() {
        return new LibreCircleOptions();
    }

    @Override
    public ILatLngBoundsBuilder onCreateLatLngBoundsBuilder() {
        return new LibreLatLngBoundsBuilder();
    }

    @Override
    public ICameraUpdate newCameraUpdateLatLng(LatLng latLng) {
        return new LibreCameraUpdate(latLng, null, null);
    }

    @Override
    public ICameraUpdate newCameraUpdateLatLngZoom(LatLng latLng, float zoom) {
        return new LibreCameraUpdate(latLng, (double) zoom, null);
    }

    @Override
    public ICameraUpdate newCameraUpdateLatLngBounds(ILatLngBounds bounds, int padding) {
        return new LibreCameraUpdateBounds((LibreLatLngBounds) bounds, padding);
    }

    @Override
    public IMapStyleOptions loadRawResourceStyle(Context context, int resId) {
        return null;
    }

    @Override
    public String getMapsAppPackageName() {
        return "";
    }

    @Override
    public int getInstallMapsString() {
        return 0;
    }

    public static org.maplibre.android.geometry.LatLng getMapLibreLatLng(LatLng latLng) {
        return new org.maplibre.android.geometry.LatLng(latLng.latitude, latLng.longitude);
    }

    public final static class LibreCameraUpdate implements ICameraUpdate {
        private final LatLng target;
        private final Double zoom;
        private final Integer duration;

        LibreCameraUpdate(LatLng target, Double zoom, Integer duration) {
            this.target = target;
            this.zoom = zoom;
            this.duration = duration;
        }
    }

    public final static class LibreCameraUpdateBounds implements ICameraUpdate {
        private final LibreLatLngBounds targetBounds;
        private final int padding;

        LibreCameraUpdateBounds(LibreLatLngBounds targetBounds, int padding) {
            this.targetBounds = targetBounds;
            this.padding = padding;
        }
    }

    public final static class LibreMapLibreView implements IMapView {
        private final MapLibreView mapView;
        private ITouchInterceptor dispatchInterceptor;
        private ITouchInterceptor interceptInterceptor;
        private Runnable onLayoutListener;

        private LibreMapLibreView(Context context) {
            mapView = new MapLibreView(context) {
                @Override
                public boolean dispatchTouchEvent(MotionEvent ev) {
                    if (dispatchInterceptor != null) {
                        return dispatchInterceptor.onInterceptTouchEvent(ev, super::dispatchTouchEvent);
                    }
                    return super.dispatchTouchEvent(ev);
                }

                @Override
                public boolean onInterceptTouchEvent(MotionEvent ev) {
                    if (interceptInterceptor != null) {
                        return interceptInterceptor.onInterceptTouchEvent(ev, super::onInterceptTouchEvent);
                    }
                    return super.onInterceptTouchEvent(ev);
                }

                @Override
                protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
                    super.onLayout(changed, left, top, right, bottom);
                    if (onLayoutListener != null) {
                        onLayoutListener.run();
                    }
                }
            };
            mapView.setMaxZoomLevel(20.0d);
            mapView.setBuiltInZoomControls(false);
            mapView.setMultiTouchControls(true);
            mapView.setCenter(new org.maplibre.android.geometry.LatLng(48.85825, 2.29448));
            mapView.setZoom(7.0);
        }

        @Override
        public void setOnDispatchTouchEventInterceptor(ITouchInterceptor touchInterceptor) {
            dispatchInterceptor = touchInterceptor;
        }

        @Override
        public void setOnInterceptTouchEventInterceptor(ITouchInterceptor touchInterceptor) {
            interceptInterceptor = touchInterceptor;
        }

        @Override
        public void setOnLayoutListener(Runnable callback) {
            onLayoutListener = callback;
        }

        @Override
        public View getView() {
            return mapView;
        }

        @Override
        public void getMapAsync(Consumer<IMap> callback) {
            mapView.getMapAsync(mapLibreMap -> {
                LibreMapImpl mapImpl = new LibreMapImpl(mapView, mapLibreMap);
                callback.accept(mapImpl);
            });
        }

        @Override
        public void onPause() {
            mapView.onPause();
        }

        @Override
        public void onResume() {
            mapView.onResume();
        }

        @Override
        public void onCreate(Bundle saved) {
        }

        @Override
        public void onDestroy() {
        }

        @Override
        public void onLowMemory() {
        }
    }

    public final static class LibreMapImpl implements IMap {
        private final MapLibreView mapView;
        private final MapLibreMap mapLibreMap;
        private TextView attributionOverlay;
        private DeviceLocationController locationController;
        private Consumer<Location> onMyLocationChangeListener;
        private OnMarkerClickListener onMarkerClickListener;
        private LibreUISettings uiSettings;
        private Runnable onCameraIdleCallback;
        private OnCameraMoveStartedListener onCameraMoveStartedListener;
        private Runnable onCameraMoveCallback;
        private List<MapPin> markers = new ArrayList<>();

        private LibreMapImpl(MapLibreView mapView, MapLibreMap mapLibreMap) {
            this.mapView = mapView;
            this.mapLibreMap = mapLibreMap;
            this.attributionOverlay = new TextView(mapView.getContext());
            attributionOverlay.setText(Html.fromHtml(MapStyleFactory.OSM_ATTRIBUTION_HTML));
            attributionOverlay.setShadowLayer(1, -1, -1, Color.WHITE);
            attributionOverlay.setLinksClickable(true);
            attributionOverlay.setMovementMethod(LinkMovementMethod.getInstance());
            mapView.addCameraListener(new MapLibreView.CameraListener() {
                @Override
                public void onCameraChanged() {
                    if (onCameraMoveCallback != null) {
                        onCameraMoveCallback.run();
                    }
                    if (onCameraIdleCallback != null) {
                        onCameraIdleCallback.run();
                    }
                }
            });
        }

        @Override
        public void setMapType(int mapType) {
            switch (mapType) {
                case MAP_TYPE_NORMAL: {
                    mapView.setRasterStyle(MapStyleFactory.RasterStyle.OSM);
                    break;
                }
                case MAP_TYPE_SATELLITE: {
                    mapView.setRasterStyle(MapStyleFactory.RasterStyle.WIKIMEDIA);
                    break;
                }
                case MAP_TYPE_HYBRID: {
                    mapView.setRasterStyle(MapStyleFactory.RasterStyle.CARTO_DARK);
                    break;
                }
            }
        }

        @Override
        public void animateCamera(ICameraUpdate update) {
            if (update instanceof LibreCameraUpdate) {
                LibreCameraUpdate libreUpdate = (LibreCameraUpdate) update;
                if (libreUpdate.zoom == null) {
                    mapView.animateTo(getMapLibreLatLng(libreUpdate.target));
                } else {
                    mapView.animateTo(getMapLibreLatLng(libreUpdate.target), libreUpdate.zoom, libreUpdate.duration != null ? (long) libreUpdate.duration : 300L);
                }
            } else if (update instanceof LibreCameraUpdateBounds) {
                LibreCameraUpdateBounds boundsUpdate = (LibreCameraUpdateBounds) update;
                if (boundsUpdate.targetBounds != null && boundsUpdate.targetBounds.bounds != null) {
                    mapView.fitBounds(boundsUpdate.targetBounds.bounds, true, boundsUpdate.padding);
                }
            }
        }

        @Override
        public void animateCamera(ICameraUpdate update, ICancelableCallback callback) {
            this.animateCamera(update);
        }

        @Override
        public void animateCamera(ICameraUpdate update, int duration, ICancelableCallback callback) {
            if (update instanceof LibreCameraUpdate) {
                LibreCameraUpdate libreUpdate = (LibreCameraUpdate) update;
                if (libreUpdate.zoom == null) {
                    mapView.animateTo(getMapLibreLatLng(libreUpdate.target), mapView.getZoomLevelDouble(), (long) duration);
                } else {
                    mapView.animateTo(getMapLibreLatLng(libreUpdate.target), libreUpdate.zoom, (long) duration);
                }
            } else if (update instanceof LibreCameraUpdateBounds) {
                LibreCameraUpdateBounds boundsUpdate = (LibreCameraUpdateBounds) update;
                if (boundsUpdate.targetBounds != null && boundsUpdate.targetBounds.bounds != null) {
                    mapView.fitBounds(boundsUpdate.targetBounds.bounds, true, boundsUpdate.padding, mapView.getMaxZoomLevel(), duration);
                }
            }
        }

        @Override
        public void moveCamera(ICameraUpdate update) {
            if (update instanceof LibreCameraUpdate) {
                LibreCameraUpdate libreUpdate = (LibreCameraUpdate) update;
                mapView.setCenter(getMapLibreLatLng(libreUpdate.target));
                if (libreUpdate.zoom != null) {
                    mapView.setZoom(libreUpdate.zoom);
                }
            } else if (update instanceof LibreCameraUpdateBounds) {
                LibreCameraUpdateBounds boundsUpdate = (LibreCameraUpdateBounds) update;
                if (boundsUpdate.targetBounds != null && boundsUpdate.targetBounds.bounds != null) {
                    mapView.fitBounds(boundsUpdate.targetBounds.bounds, false, boundsUpdate.padding);
                }
            }
        }

        @Override
        public float getMaxZoomLevel() {
            return (float) mapView.getMaxZoomLevel();
        }

        @Override
        public float getMinZoomLevel() {
            return (float) mapView.getMinZoomLevel();
        }

        @Override
        public void setMyLocationEnabled(boolean enabled) {
            if (enabled) {
                if (locationController == null) {
                    locationController = new DeviceLocationController();
                    locationController.setMinTimeMs(10000);
                    locationController.setMinDistanceMeters(10);
                    locationController.addProvider(LocationManager.NETWORK_PROVIDER);
                    locationController.setListener(location -> {
                        mapView.setLocationOverlay(location, true);
                        if (onMyLocationChangeListener != null) {
                            onMyLocationChangeListener.accept(location);
                        }
                    });
                }
                locationController.start();
            } else {
                if (locationController != null) {
                    locationController.stop();
                    mapView.setLocationOverlay(null, false);
                }
            }
        }

        @Override
        public IUISettings getUiSettings() {
            if (this.uiSettings == null) {
                this.uiSettings = new LibreUISettings();
            }
            return this.uiSettings;
        }

        @Override
        public void setOnCameraIdleListener(Runnable callback) {
            this.onCameraIdleCallback = callback;
        }

        @Override
        public void setOnCameraMoveStartedListener(OnCameraMoveStartedListener onCameraMoveStartedListener) {
            this.onCameraMoveStartedListener = onCameraMoveStartedListener;
        }

        @Override
        public CameraPosition getCameraPosition() {
            org.maplibre.android.camera.CameraPosition position = mapLibreMap.getCameraPosition();
            if (position.target != null) {
                return new CameraPosition(new LatLng(position.target.getLatitude(), position.target.getLongitude()), (float) position.zoom);
            }
            return new CameraPosition(new LatLng(mapView.getCenter().getLatitude(), mapView.getCenter().getLongitude()), (float) position.zoom);
        }

        @Override
        public void setOnMapLoadedCallback(Runnable callback) {
            // ignore
        }

        @Override
        public IProjection getProjection() {
            return new LibreProjection(mapView, mapLibreMap);
        }

        @Override
        public void setPadding(int left, int top, int right, int bottom) {
            mapView.setPadding(left, top, right, bottom);
        }

        @Override
        public void setMapStyle(IMapStyleOptions style) {
            // ignore
        }

        @Override
        public IMarker addMarker(IMarkerOptions markerOptions) {
            LibreMarkerOptions options = (LibreMarkerOptions) markerOptions;
            MapPin pin = mapView.addPin(
                    options.drawable,
                    getMapLibreLatLng(options.position),
                    options.anchorU,
                    options.anchorV,
                    view -> {
                        if (onMarkerClickListener != null) {
                            onMarkerClickListener.onClick(options.markerRef.get());
                        }
                    }
            );
            options.markerRef.set(new LibreMarker(options, pin));
            markers.add(pin);
            return options.markerRef.get();
        }

        @Override
        public void setOnMyLocationChangeListener(Consumer<Location> callback) {
            this.onMyLocationChangeListener = callback;
            if (locationController != null) {
                locationController.runOnFirstFix(() -> {
                    AndroidUtilities.runOnUIThread(() -> {
                        Location lastLocation = locationController.getLastLocation();
                        if (lastLocation != null) {
                            callback.accept(lastLocation);
                        }
                    });
                });
            }
        }

        @Override
        public void setOnMarkerClickListener(OnMarkerClickListener markerClickListener) {
            this.onMarkerClickListener = markerClickListener;
        }

        @Override
        public void setOnCameraMoveListener(Runnable callback) {
            this.onCameraMoveCallback = callback;
        }

        @Override
        public ICircle addCircle(ICircleOptions circleOptions) {
            LibreCircleOptions options = (LibreCircleOptions) circleOptions;
            return new LibreCircle(mapView, mapLibreMap, options);
        }
    }

    public final static class LibreMarkerOptions implements IMarkerOptions {
        private final MapLibreView mapView;
        private LatLng position;
        private android.graphics.drawable.Drawable drawable;
        private float anchorU = 0.5f;
        private float anchorV = 1.0f;
        private String title;
        private String snippet;
        private boolean flat;
        private java.util.concurrent.atomic.AtomicReference<LibreMarker> markerRef = new java.util.concurrent.atomic.AtomicReference<>();

        private LibreMarkerOptions(MapLibreView mapView) {
            this.mapView = mapView;
        }

        @Override
        public IMarkerOptions position(LatLng latLng) {
            this.position = latLng;
            return this;
        }

        @Override
        public IMarkerOptions icon(Resources resources, Bitmap bitmap) {
            this.drawable = new BitmapDrawable(resources, bitmap);
            return this;
        }

        @Override
        public IMarkerOptions icon(Resources resources, int resId) {
            this.drawable = resources.getDrawable(resId);
            return this;
        }

        @Override
        public IMarkerOptions anchor(float lat, float lng) {
            this.anchorU = lat;
            this.anchorV = lng;
            return this;
        }

        @Override
        public IMarkerOptions title(String title) {
            this.title = title;
            return this;
        }

        @Override
        public IMarkerOptions snippet(String snippet) {
            this.snippet = snippet;
            return this;
        }

        @Override
        public IMarkerOptions flat(boolean flat) {
            this.flat = flat;
            return this;
        }
    }

    public final static class LibreMarker implements IMarker {
        private final MapPin pin;
        private Object tag;
        private LatLng position;
        private int rotation;
        private android.graphics.drawable.Drawable icon;
        private final java.util.concurrent.atomic.AtomicReference<LibreMarkerOptions> optionsRef;

        private LibreMarker(LibreMarkerOptions options, MapPin pin) {
            this.optionsRef = new java.util.concurrent.atomic.AtomicReference<>(options);
            this.pin = pin;
            this.position = options.position;
            this.icon = options.drawable;
        }

        @Override
        public Object getTag() {
            return tag;
        }

        @Override
        public void setTag(Object tag) {
            this.tag = tag;
        }

        @Override
        public LatLng getPosition() {
            return position;
        }

        @Override
        public void setPosition(LatLng latLng) {
            this.position = latLng;
            pin.setPosition(getMapLibreLatLng(latLng));
        }

        @Override
        public void setRotation(int rotation) {
            this.rotation = rotation;
            pin.setRotation(rotation);
        }

        @Override
        public void setIcon(Resources resources, Bitmap bitmap) {
            this.icon = new BitmapDrawable(resources, bitmap);
            pin.setIcon(icon);
        }

        @Override
        public void setIcon(Resources resources, int resId) {
            this.icon = resources.getDrawable(resId);
            pin.setIcon(icon);
        }

        @Override
        public void remove() {
            pin.remove();
        }
    }

    public final static class LibreCircle implements ICircle {
        private final MapLibreView mapView;
        private final MapLibreMap mapLibreMap;
        private final LibreCircleOptions options;
        private String sourceId;
        private String fillLayerId;
        private String lineLayerId;
        private static final String CIRCLE_SOURCE_ID = "circle-proximity-source";
        private static final String CIRCLE_FILL_LAYER_ID = "circle-proximity-fill";
        private static final String CIRCLE_LINE_LAYER_ID = "circle-proximity-line";

        public LibreCircle(MapLibreView mapView, MapLibreMap mapLibreMap, LibreCircleOptions options) {
            this.mapView = mapView;
            this.mapLibreMap = mapLibreMap;
            this.options = options;
            applyCircle();
        }

        private void applyCircle() {
            if (options.center == null) {
                return;
            }

            mapLibreMap.getStyle(style -> {
                List<org.maplibre.android.geometry.LatLng> circlePoints = GeoUtils.circlePoints(
                    getMapLibreLatLng(options.center),
                    options.radius
                );

                GeoJsonSource source = new GeoJsonSource(CIRCLE_SOURCE_ID, createCircleGeoJson(circlePoints));

                FillLayer fillLayer = new FillLayer(CIRCLE_FILL_LAYER_ID, CIRCLE_SOURCE_ID);
                fillLayer.setProperties(
                    org.maplibre.android.style.layers.PropertyFactory.fillColor(options.fillColor),
                    org.maplibre.android.style.layers.PropertyFactory.fillOpacity(0.3f)
                );

                LineLayer lineLayer = new LineLayer(CIRCLE_LINE_LAYER_ID, CIRCLE_SOURCE_ID);
                lineLayer.setProperties(
                    org.maplibre.android.style.layers.PropertyFactory.lineColor(options.strokeColor),
                    org.maplibre.android.style.layers.PropertyFactory.lineWidth(options.strokeWidth),
                    org.maplibre.android.style.layers.PropertyFactory.lineOpacity(1.0f)
                );

                style.addSource(source);
                style.addLayer(fillLayer);
                style.addLayer(lineLayer);

                sourceId = CIRCLE_SOURCE_ID;
                fillLayerId = CIRCLE_FILL_LAYER_ID;
                lineLayerId = CIRCLE_LINE_LAYER_ID;
            });
        }

        private String createCircleGeoJson(List<org.maplibre.android.geometry.LatLng> points) {
            StringBuilder sb = new StringBuilder();
            sb.append("{\"type\":\"FeatureCollection\",\"features\":[{\"type\":\"Feature\",\"properties\":{},\"geometry\":{\"type\":\"Polygon\",\"coordinates\":[[");
            for (int i = 0; i < points.size(); i++) {
                if (i > 0) {
                    sb.append(",");
                }
                sb.append("[").append(points.get(i).getLongitude()).append(",").append(points.get(i).getLatitude()).append("]");
            }
            sb.append(",[").append(points.get(0).getLongitude()).append(",").append(points.get(0).getLatitude()).append("]");
            sb.append("]]}}]}");
            return sb.toString();
        }

        @Override
        public void setStrokeColor(int color) {
            options.strokeColor = color;
            if (mapLibreMap != null && lineLayerId != null) {
                mapLibreMap.getStyle(style -> {
                    LineLayer lineLayer = style.getLayerAs(lineLayerId);
                    if (lineLayer != null) {
                        lineLayer.setProperties(
                            org.maplibre.android.style.layers.PropertyFactory.lineColor(color)
                        );
                    }
                });
            }
        }

        @Override
        public void setFillColor(int color) {
            options.fillColor = color;
            if (mapLibreMap != null && fillLayerId != null) {
                mapLibreMap.getStyle(style -> {
                    FillLayer fillLayer = style.getLayerAs(fillLayerId);
                    if (fillLayer != null) {
                        fillLayer.setProperties(
                            org.maplibre.android.style.layers.PropertyFactory.fillColor(color)
                        );
                    }
                });
            }
        }

        @Override
        public void setRadius(double radius) {
            options.radius = radius;
            applyCircle();
        }

        @Override
        public double getRadius() {
            return options.radius;
        }

        @Override
        public void setCenter(LatLng latLng) {
            options.center = latLng;
            applyCircle();
        }

        @Override
        public void remove() {
            if (mapLibreMap != null) {
                mapLibreMap.getStyle(style -> {
                    if (fillLayerId != null) {
                        style.removeLayer(fillLayerId);
                    }
                    if (lineLayerId != null) {
                        style.removeLayer(lineLayerId);
                    }
                    if (sourceId != null) {
                        style.removeSource(sourceId);
                    }
                });
            }
        }
    }

    public final static class LibreCircleOptions implements ICircleOptions {
        private LatLng center;
        private double radius = 500;
        private int strokeColor = Color.BLUE;
        private int fillColor = Color.argb(77, 0, 0, 255);
        private float strokeWidth = 3.0f;

        @Override
        public ICircleOptions center(LatLng latLng) {
            this.center = latLng;
            return this;
        }

        @Override
        public ICircleOptions radius(double radius) {
            this.radius = radius;
            return this;
        }

        @Override
        public ICircleOptions strokeColor(int color) {
            this.strokeColor = color;
            return this;
        }

        @Override
        public ICircleOptions fillColor(int color) {
            this.fillColor = color;
            return this;
        }

        @Override
        public ICircleOptions strokePattern(List<PatternItem> patternItems) {
            return this;
        }

        @Override
        public ICircleOptions strokeWidth(int width) {
            this.strokeWidth = width;
            return this;
        }
    }

    public final static class LibreLatLngBoundsBuilder implements ILatLngBoundsBuilder {
        private final List<LatLng> geoPoints = new ArrayList<>();

        @Override
        public ILatLngBoundsBuilder include(LatLng latLng) {
            geoPoints.add(latLng);
            return this;
        }

        @Override
        public ILatLngBounds build() {
            if (geoPoints.isEmpty()) {
                return null;
            }
            LibreLatLngBounds bounds = new LibreLatLngBounds();
            List<org.maplibre.android.geometry.LatLng> maplibreList = new ArrayList<>();
            for (LatLng latLng : geoPoints) {
                maplibreList.add(new org.maplibre.android.geometry.LatLng(latLng.latitude, latLng.longitude));
            }
            bounds.bounds = GeoUtils.bounds(maplibreList);
            return bounds;
        }
    }

    public final static class LibreLatLngBounds implements ILatLngBounds {
        private LatLngBounds bounds = null;

        @Override
        public LatLng getCenter() {
            if (bounds != null) {
                org.maplibre.android.geometry.LatLng center = bounds.getCenter();
                return new LatLng(center.getLatitude(), center.getLongitude());
            }
            return null;
        }
    }

    public final static class LibreProjection implements IProjection {
        private final MapLibreView mapView;
        private final MapLibreMap mapLibreMap;

        public LibreProjection(MapLibreView mapView, MapLibreMap mapLibreMap) {
            this.mapView = mapView;
            this.mapLibreMap = mapLibreMap;
        }

        @Override
        public Point toScreenLocation(LatLng latLng) {
            PointF point = mapView.project(getMapLibreLatLng(latLng));
            if (point != null) {
                return new Point((int) point.x, (int) point.y);
            }
            return new Point(0, 0);
        }
    }

    public final static class LibreUISettings implements IUISettings {
        @Override
        public void setZoomControlsEnabled(boolean enabled) {
        }

        @Override
        public void setMyLocationButtonEnabled(boolean enabled) {
        }

        @Override
        public void setCompassEnabled(boolean enabled) {
        }
    }
}
