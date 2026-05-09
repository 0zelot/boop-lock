package fyi.ozelot.booplock.data;

import androidx.annotation.Nullable;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Locale;

public class AttemptLocation {

    public final double latitude;
    public final double longitude;
    public final float accuracyMeters;
    public final long timestampMs;
    @Nullable
    public final String provider;

    public AttemptLocation(double latitude, double longitude, float accuracyMeters,
                           long timestampMs, @Nullable String provider) {
        this.latitude = latitude;
        this.longitude = longitude;
        this.accuracyMeters = accuracyMeters;
        this.timestampMs = timestampMs;
        this.provider = provider;
    }

    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("latitude", latitude);
        o.put("longitude", longitude);
        o.put("accuracyMeters", accuracyMeters);
        o.put("timestamp", timestampMs);
        if (provider != null && !provider.isEmpty()) {
            o.put("provider", provider);
        }
        return o;
    }

    public static AttemptLocation fromJson(JSONObject o) throws JSONException {
        return new AttemptLocation(
                o.getDouble("latitude"),
                o.getDouble("longitude"),
                (float) o.optDouble("accuracyMeters", -1),
                o.optLong("timestamp", 0),
                o.optString("provider", null)
        );
    }

    public String coordinates() {
        return String.format(Locale.US, "%.5f, %.5f", latitude, longitude);
    }

    public String mapsUrl() {
        return String.format(Locale.US,
                "https://maps.google.com/?q=%.6f,%.6f",
                latitude, longitude);
    }
}
