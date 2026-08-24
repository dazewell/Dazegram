package moe.hx030.momogram.maplibre;

import java.util.List;

import org.maplibre.android.geometry.LatLng;
import org.maplibre.android.geometry.LatLngBounds;

public final class GeoUtils {
    public static final double MIN_LATITUDE = -85.05112878d;
    public static final double MAX_LATITUDE = 85.05112878d;
    public static final double MIN_LONGITUDE = -180.0d;
    public static final double MAX_LONGITUDE = 180.0d;

    private GeoUtils() {
    }

    public static LatLng move(LatLng start, double northMeters, double eastMeters) {
        double lonDiff = meterToLongitude(eastMeters, start.getLatitude());
        double latDiff = meterToLatitude(northMeters);
        return new LatLng(start.getLatitude() + latDiff, start.getLongitude() + lonDiff);
    }

    public static List<LatLng> circlePoints(LatLng center, double meters) {
        List<LatLng> points = new java.util.ArrayList<>(64);
        double radiusDegreesLat = meters / 111320.0d;
        double radiusDegreesLon = meters / (111320.0d * Math.cos(Math.toRadians(center.getLatitude())));
        if (Double.isNaN(radiusDegreesLon) || Double.isInfinite(radiusDegreesLon)) {
            radiusDegreesLon = 0.0d;
        }
        for (int i = 0; i < 64; i++) {
            double angle = 2.0d * Math.PI * i / 64.0d;
            double lat = center.getLatitude() + Math.sin(angle) * radiusDegreesLat;
            double lon = center.getLongitude() + Math.cos(angle) * radiusDegreesLon;
            points.add(new LatLng(lat, lon));
        }
        return points;
    }

    public static LatLngBounds bounds(List<LatLng> points) {
        if (points == null || points.isEmpty()) {
            throw new IllegalArgumentException("Cannot create bounds from an empty point list");
        }
        if (points.size() == 1) {
            LatLng point = points.get(0);
            double latInset = 0.0001d;
            double lonInset = 0.0001d / Math.max(Math.cos(Math.toRadians(point.getLatitude())), 0.01d);
            return new LatLngBounds.Builder()
                    .include(new LatLng(point.getLatitude() - latInset, point.getLongitude() - lonInset))
                    .include(new LatLng(point.getLatitude() + latInset, point.getLongitude() + lonInset))
                    .build();
        }
        LatLngBounds.Builder builder = new LatLngBounds.Builder();
        for (LatLng point : points) {
            builder.include(point);
        }
        return builder.build();
    }

    private static double meterToLongitude(double metersEast, double latitude) {
        double earthRadius = 6366198.0d;
        double latArc = Math.toRadians(latitude);
        double radius = Math.cos(latArc) * earthRadius;
        double rad = metersEast / radius;
        return Math.toDegrees(rad);
    }

    private static double meterToLatitude(double metersNorth) {
        double earthRadius = 6366198.0d;
        double rad = metersNorth / earthRadius;
        return Math.toDegrees(rad);
    }
}
