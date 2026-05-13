package com.example.safety;

import android.location.Location;
import java.util.Calendar;

public class RiskPredictor {

    // Dummy Dataset simulating our ML Training Data
    private static final RiskZone[] DUMMY_DATASET = {
            new RiskZone("Outer Ring Road Isolated Stretch, Bangalore", 12.9250, 77.6840, 0, 4, "High incidence of late-night muggings reported historically."),
            new RiskZone("Delhi NCR - Old Railway Hub", 28.6619, 77.2274, 23, 4, "Isolated transit zone. High harassment risk after 11 PM."),
            new RiskZone("Mumbai - Kurla East Industrial", 19.0650, 72.8790, 1, 5, "Low foot traffic and poor lighting.")
    };

    public static RiskResult evaluateRisk(Location currentLocation) {
        if (currentLocation == null) return new RiskResult(false, "Safe Zone", "Awaiting GPS lock...");

        Calendar calendar = Calendar.getInstance();
        int currentHour = calendar.get(Calendar.HOUR_OF_DAY);

        for (RiskZone zone : DUMMY_DATASET) {
            float[] results = new float[1];
            Location.distanceBetween(
                    currentLocation.getLatitude(), currentLocation.getLongitude(),
                    zone.lat, zone.lng, results
            );

            float distanceInMeters = results[0];

            // If user is within 2km AND it's the dangerous time
            if (distanceInMeters < 2000) {
                if (currentHour >= zone.dangerStartHour || currentHour <= zone.dangerEndHour) {
                    return new RiskResult(
                            true,
                            "CAUTION: Entering " + zone.name,
                            zone.historicalContext + " Proceed with vigilance."
                    );
                }
            }
        }
        return new RiskResult(false, "Status: Secure", "No historical threats detected at this hour.");
    }

    static class RiskZone {
        String name; double lat, lng; int dangerStartHour, dangerEndHour; String historicalContext;
        RiskZone(String n, double l, double g, int s, int e, String c) {
            name = n; lat = l; lng = g; dangerStartHour = s; dangerEndHour = e; historicalContext = c;
        }
    }

    public static class RiskResult {
        public boolean isHighRisk; public String title; public String reason;
        RiskResult(boolean hr, String t, String r) { isHighRisk = hr; title = t; reason = r; }
    }
}