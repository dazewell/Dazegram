package moe.hx030.momogram.maplibre;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PointF;
import android.graphics.drawable.Drawable;
import android.location.Location;
import android.util.AttributeSet;
import android.view.Gravity;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.maplibre.android.MapLibre;
import org.maplibre.android.camera.CameraPosition;
import org.maplibre.android.camera.CameraUpdate;
import org.maplibre.android.camera.CameraUpdateFactory;
import org.maplibre.android.geometry.LatLng;
import org.maplibre.android.geometry.LatLngBounds;
import org.maplibre.android.maps.MapLibreMap;
import org.maplibre.android.maps.OnMapReadyCallback;
import org.maplibre.android.maps.Style;
import org.maplibre.android.style.layers.RasterLayer;
import org.maplibre.android.style.sources.RasterSource;
import org.maplibre.android.style.sources.TileSet;

import java.util.ArrayList;
import java.util.List;

public class MapLibreView extends FrameLayout {
    public interface OnReadyListener {
        void onReady(MapLibreMap mapLibreMap);
    }

    public interface CameraListener {
        void onCameraChanged();
    }

    private final org.maplibre.android.maps.MapView backingMapView;
    private final FrameLayout markerLayer;
    private final DrawingOverlayView drawingOverlayView;
    private final List<MapPin> pins = new ArrayList<>();
    private final List<CameraListener> cameraListeners = new ArrayList<>();
    private final List<OnReadyListener> pendingReadyListeners = new ArrayList<>();

    private MapLibreMap mapLibreMap;
    private MapStyleFactory.RasterStyle rasterStyle = MapStyleFactory.RasterStyle.OSM;
    private MapStyleFactory.RasterStyle pendingRasterStyle = null;
    private boolean mapInitialized = false;
    private boolean started;
    private double maxZoomLevel = 20.0d;
    private double minZoomLevel = 0.0d;
    private int virtualPaddingLeft;
    private int virtualPaddingTop;
    private int virtualPaddingRight;
    private int virtualPaddingBottom;
    private LatLng pendingCenter;
    private Double pendingZoom;

    public MapLibreView(@NonNull Context context) {
        this(context, null);
    }

    public MapLibreView(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        MapLibre.getInstance(context.getApplicationContext());

        backingMapView = new org.maplibre.android.maps.MapView(context);
        backingMapView.onCreate(null);
        addView(backingMapView, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        drawingOverlayView = new DrawingOverlayView(context);
        addView(drawingOverlayView, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        markerLayer = new FrameLayout(context);
        markerLayer.setClipChildren(false);
        markerLayer.setClipToPadding(false);
        LayoutParams markerParams = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT);
        markerParams.gravity = Gravity.TOP | Gravity.LEFT;
        addView(markerLayer, markerParams);

        backingMapView.getMapAsync(new OnMapReadyCallback() {
            @Override
            public void onMapReady(MapLibreMap maplibreMap) {
                mapLibreMap = maplibreMap;
                mapLibreMap.addOnCameraMoveListener(MapLibreView.this::dispatchCameraChanged);
                mapLibreMap.addOnCameraIdleListener(MapLibreView.this::dispatchCameraChanged);
                mapLibreMap.setMaxZoomPreference(maxZoomLevel);
                mapLibreMap.setMinZoomPreference(minZoomLevel);
                mapLibreMap.setPadding(virtualPaddingLeft, virtualPaddingTop, virtualPaddingRight, virtualPaddingBottom);
                applyRasterStyle(rasterStyle, MapLibreView.this::applyPendingCamera);
                for (OnReadyListener listener : pendingReadyListeners) {
                    listener.onReady(mapLibreMap);
                }
                pendingReadyListeners.clear();
            }
        });
    }

    public void getMapAsync(@NonNull OnReadyListener listener) {
        if (mapLibreMap != null) {
            listener.onReady(mapLibreMap);
        } else {
            pendingReadyListeners.add(listener);
        }
    }

    public void onResume() {
        if (!started) {
            backingMapView.onStart();
            started = true;
        }
        backingMapView.onResume();
    }

    public void onPause() {
        backingMapView.onPause();
        if (started) {
            backingMapView.onStop();
            started = false;
        }
    }

    public void setMaxZoomLevel(double maxZoomLevel) {
        this.maxZoomLevel = maxZoomLevel;
        if (mapLibreMap != null) {
            mapLibreMap.setMaxZoomPreference(maxZoomLevel);
        }
    }

    public double getMaxZoomLevel() {
        return maxZoomLevel;
    }

    public double getMinZoomLevel() {
        return minZoomLevel;
    }

    public double getZoomLevelDouble() {
        return mapLibreMap != null ? mapLibreMap.getCameraPosition().zoom : (pendingZoom != null ? pendingZoom : minZoomLevel);
    }

    public void setMultiTouchControls(boolean enabled) {
    }

    public void setBuiltInZoomControls(boolean enabled) {
    }

    @Override
    public void setPadding(int left, int top, int right, int bottom) {
        virtualPaddingLeft = left;
        virtualPaddingTop = top;
        virtualPaddingRight = right;
        virtualPaddingBottom = bottom;
        if (mapLibreMap != null) {
            mapLibreMap.setPadding(left, top, right, bottom);
        }
        invalidateOverlays();
    }

    @Override
    public int getPaddingLeft() {
        return virtualPaddingLeft;
    }

    @Override
    public int getPaddingTop() {
        return virtualPaddingTop;
    }

    @Override
    public int getPaddingRight() {
        return virtualPaddingRight;
    }

    @Override
    public int getPaddingBottom() {
        return virtualPaddingBottom;
    }

    @Nullable
    public MapLibreMap getMap() {
        return mapLibreMap;
    }

    @Nullable
    public LatLng getCenter() {
        if (mapLibreMap != null) {
            return mapLibreMap.getCameraPosition().target;
        }
        return pendingCenter;
    }

    public void setCenter(@NonNull LatLng target) {
        pendingCenter = target;
        if (mapLibreMap != null) {
            mapLibreMap.moveCamera(CameraUpdateFactory.newLatLng(target));
        }
    }

    public void setZoom(double zoom) {
        pendingZoom = zoom;
        if (mapLibreMap != null) {
            mapLibreMap.moveCamera(CameraUpdateFactory.zoomTo(zoom));
        }
    }

    public void animateTo(@NonNull LatLng target) {
        animateTo(target, null, null);
    }

    public void animateTo(@NonNull LatLng target, @Nullable Double zoom, @Nullable Long durationMs) {
        pendingCenter = target;
        if (zoom != null) {
            pendingZoom = zoom;
        }
        if (mapLibreMap == null) {
            return;
        }
        CameraPosition.Builder builder = new CameraPosition.Builder()
                .target(target)
                .zoom(zoom != null ? zoom : mapLibreMap.getCameraPosition().zoom);
        int duration = durationMs != null ? durationMs.intValue() : 300;
        mapLibreMap.animateCamera(CameraUpdateFactory.newCameraPosition(builder.build()), duration);
    }

    public void fitBounds(@NonNull LatLngBounds bounds, boolean animated, int padding) {
        fitBounds(bounds, animated, padding, maxZoomLevel, 300L);
    }

    public void fitBounds(@NonNull LatLngBounds bounds, boolean animated, int padding, double maxZoom, long durationMs) {
        if (mapLibreMap == null) {
            pendingCenter = bounds.getCenter();
            return;
        }
        int[] paddingArray = new int[]{
                getPaddingLeft() + padding,
                getPaddingTop() + padding,
                getPaddingRight() + padding,
                getPaddingBottom() + padding
        };
        CameraPosition cameraPosition = mapLibreMap.getCameraForLatLngBounds(bounds, paddingArray);
        if (cameraPosition == null) {
            return;
        }
        double boundedZoom = Math.min(cameraPosition.zoom, maxZoom);
        CameraUpdate update = CameraUpdateFactory.newCameraPosition(new CameraPosition.Builder(cameraPosition).zoom(boundedZoom).build());
        if (animated) {
            mapLibreMap.animateCamera(update, (int) durationMs);
        } else {
            mapLibreMap.moveCamera(update);
        }
    }

    public void setRasterStyle(@NonNull MapStyleFactory.RasterStyle rasterStyle) {
        this.rasterStyle = rasterStyle;
        applyRasterStyle(rasterStyle, this::applyPendingCamera);
    }

    public void addCameraListener(@NonNull CameraListener listener) {
        cameraListeners.add(listener);
    }

    public MapPin addPin(@NonNull Drawable icon, @NonNull LatLng position, float anchorU, float anchorV, @Nullable OnClickListener onClickListener) {
        MapPin pin = new MapPin(this, icon, position, anchorU, anchorV, onClickListener);
        pins.add(pin);
        markerLayer.addView(pin.getView());
        updatePin(pin);
        return pin;
    }

    public void removePin(@NonNull MapPin pin) {
        pins.remove(pin);
        markerLayer.removeView(pin.getView());
    }

    public boolean containsPin(@Nullable MapPin pin) {
        return pin != null && pins.contains(pin);
    }

    public void updatePin(@NonNull MapPin pin) {
        if (mapLibreMap == null || pin.getView().getDrawable() == null) {
            return;
        }
        int width = Math.max(pin.getView().getDrawable().getIntrinsicWidth(), 1);
        int height = Math.max(pin.getView().getDrawable().getIntrinsicHeight(), 1);
        pin.getView().measure(
                MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY)
        );
        pin.getView().layout(0, 0, width, height);
        PointF point = mapLibreMap.getProjection().toScreenLocation(pin.getPosition());
        pin.getView().setTranslationX(point.x - width * pin.getAnchorU());
        pin.getView().setTranslationY(point.y - height * pin.getAnchorV());
    }

    public void invalidateOverlays() {
        for (MapPin pin : pins) {
            updatePin(pin);
        }
        drawingOverlayView.invalidate();
    }

    @Nullable
    public PointF project(@NonNull LatLng latLng) {
        if (mapLibreMap == null) {
            return null;
        }
        return mapLibreMap.getProjection().toScreenLocation(latLng);
    }

    public void setLocationOverlay(@Nullable Location location, boolean drawAccuracy) {
        drawingOverlayView.setLocation(location);
        drawingOverlayView.setDrawAccuracy(drawAccuracy);
    }

    public void setPolygon(@Nullable List<LatLng> points, int strokeColor, int fillColor, float strokeWidth) {
        drawingOverlayView.setPolygon(points, strokeColor, fillColor, strokeWidth);
    }

    private void dispatchCameraChanged() {
        invalidateOverlays();
        for (CameraListener listener : cameraListeners) {
            listener.onCameraChanged();
        }
    }

    private void applyPendingCamera() {
        if (mapLibreMap == null) {
            return;
        }
        if (pendingCenter != null) {
            mapLibreMap.moveCamera(CameraUpdateFactory.newLatLng(pendingCenter));
        }
        if (pendingZoom != null) {
            mapLibreMap.moveCamera(CameraUpdateFactory.zoomTo(pendingZoom));
        }
        dispatchCameraChanged();
    }

    private void applyRasterStyle(@NonNull MapStyleFactory.RasterStyle rasterStyle, @Nullable Runnable onLoaded) {
        if (mapLibreMap == null) {
            return;
        }
        mapLibreMap.setStyle(new Style.Builder().fromJson("{\"version\":8,\"sources\":{},\"layers\":[]}"), style -> {
            TileSet tileSet = new TileSet("2.0.0", rasterStyle.getTiles());
            tileSet.setScheme("xyz");
            tileSet.setMinZoom(rasterStyle.getMinZoom());
            tileSet.setMaxZoom(rasterStyle.getMaxZoom());
            tileSet.setAttribution(rasterStyle.getAttributionText());
            style.addSource(new RasterSource("raster-source", tileSet, rasterStyle.getTileSize()));
            style.addLayer(new RasterLayer("raster-layer", "raster-source"));
            if (onLoaded != null) {
                onLoaded.run();
            }
        });
    }

    private final class DrawingOverlayView extends android.view.View {
        private final Paint polygonStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint polygonFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint accuracyPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint pointPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        private List<LatLng> polygon;
        private Location location;
        private boolean drawAccuracy = true;

        DrawingOverlayView(Context context) {
            super(context);
            polygonStrokePaint.setStyle(Paint.Style.STROKE);
            polygonFillPaint.setStyle(Paint.Style.FILL);
            accuracyPaint.setStyle(Paint.Style.FILL);
            pointPaint.setStyle(Paint.Style.FILL);
            pointPaint.setColor(0xff4286F5);
            accuracyPaint.setColor(0x334286F5);
            setWillNotDraw(false);
        }

        void setPolygon(@Nullable List<LatLng> polygon, int strokeColor, int fillColor, float strokeWidth) {
            this.polygon = polygon;
            polygonStrokePaint.setColor(strokeColor);
            polygonStrokePaint.setStrokeWidth(strokeWidth);
            polygonFillPaint.setColor(fillColor);
            invalidate();
        }

        void setLocation(@Nullable Location location) {
            this.location = location == null ? null : new Location(location);
            invalidate();
        }

        void setDrawAccuracy(boolean drawAccuracy) {
            this.drawAccuracy = drawAccuracy;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (mapLibreMap == null) {
                return;
            }
            if (polygon != null && polygon.size() > 1) {
                android.graphics.Path path = new android.graphics.Path();
                PointF first = mapLibreMap.getProjection().toScreenLocation(polygon.get(0));
                path.moveTo(first.x, first.y);
                for (int i = 1; i < polygon.size(); i++) {
                    PointF point = mapLibreMap.getProjection().toScreenLocation(polygon.get(i));
                    path.lineTo(point.x, point.y);
                }
                path.close();
                canvas.drawPath(path, polygonFillPaint);
                canvas.drawPath(path, polygonStrokePaint);
            }
            if (location != null) {
                PointF point = mapLibreMap.getProjection().toScreenLocation(new LatLng(location.getLatitude(), location.getLongitude()));
                if (drawAccuracy && location.hasAccuracy()) {
                    double metersPerPixel = 156543.03392d * Math.cos(Math.toRadians(location.getLatitude())) / Math.pow(2.0d, mapLibreMap.getCameraPosition().zoom);
                    float radius = metersPerPixel > 0.0d ? (float) (location.getAccuracy() / metersPerPixel) : 0.0f;
                    canvas.drawCircle(point.x, point.y, radius, accuracyPaint);
                }
                canvas.drawCircle(point.x, point.y, 10.0f, pointPaint);
            }
        }
    }
}
