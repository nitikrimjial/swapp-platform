package com.swapp.common;

/**
 * A latitude/longitude pair with the small amount of spherical geometry Swapp needs.
 * Distances use the haversine formula on a spherical Earth, which is accurate to well under 1% at city scale.
 */
public record GeoPoint(double latitude, double longitude) {

    public static final double EARTH_RADIUS_KM = 6371.0088;
    /** ~111 m of latitude; coordinates are stored at this precision so exact addresses are never revealed. */
    private static final double PRIVACY_PRECISION = 1000.0;

    public GeoPoint {
        if (Double.isNaN(latitude) || latitude < -90 || latitude > 90) {
            throw ApiException.badRequest("Latitude must be between -90 and 90");
        }
        if (Double.isNaN(longitude) || longitude < -180 || longitude > 180) {
            throw ApiException.badRequest("Longitude must be between -180 and 180");
        }
    }

    /** Builds a point when both values are present; null when both are absent; 400 when only one is given. */
    public static GeoPoint ofNullable(Double latitude, Double longitude) {
        if (latitude == null && longitude == null) {
            return null;
        }
        if (latitude == null || longitude == null) {
            throw ApiException.badRequest("Provide both latitude and longitude, or neither");
        }
        return new GeoPoint(latitude, longitude);
    }

    public GeoPoint rounded() {
        return new GeoPoint(Math.round(latitude * PRIVACY_PRECISION) / PRIVACY_PRECISION,
                Math.round(longitude * PRIVACY_PRECISION) / PRIVACY_PRECISION);
    }

    public double distanceKmTo(GeoPoint other) {
        double dLat = Math.toRadians(other.latitude - latitude);
        double dLon = Math.toRadians(other.longitude - longitude);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(latitude)) * Math.cos(Math.toRadians(other.latitude))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * EARTH_RADIUS_KM * Math.asin(Math.min(1, Math.sqrt(a)));
    }

    /** Latitude span (degrees) of a circle with the given radius. */
    public static double latitudeDelta(double radiusKm) {
        return Math.toDegrees(radiusKm / EARTH_RADIUS_KM);
    }

    /** Longitude span (degrees) of a circle with the given radius at this point's latitude. */
    public double longitudeDelta(double radiusKm) {
        double cos = Math.cos(Math.toRadians(latitude));
        return cos < 1e-6 ? 180 : Math.min(180, Math.toDegrees(radiusKm / (EARTH_RADIUS_KM * cos)));
    }
}
