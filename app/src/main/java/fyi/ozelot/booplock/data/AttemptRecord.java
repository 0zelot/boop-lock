package fyi.ozelot.booplock.data;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * A single history entry: when and how many unlock attempts failed,
 * plus the path to the front camera photo.
 *
 * JSON file storage is used instead of a database because the scale is small
 * and the app must stay lightweight.
 */
public class AttemptRecord {

    public final long id;
    public final long timestampMs;
    public final int failedCount;
    /** Absolute path to the JPEG file in the app's private directory. */
    public final String photoPath;

    public AttemptRecord(long id, long timestampMs, int failedCount, String photoPath) {
        this.id = id;
        this.timestampMs = timestampMs;
        this.failedCount = failedCount;
        this.photoPath = photoPath;
    }

    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("id", id);
        o.put("timestamp", timestampMs);
        o.put("failedCount", failedCount);
        o.put("photoPath", photoPath);
        return o;
    }

    public static AttemptRecord fromJson(JSONObject o) throws JSONException {
        return new AttemptRecord(
                o.getLong("id"),
                o.getLong("timestamp"),
                o.getInt("failedCount"),
                o.optString("photoPath", null)
        );
    }
}
