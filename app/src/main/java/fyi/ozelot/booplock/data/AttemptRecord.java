package fyi.ozelot.booplock.data;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * A single history entry. Photos are stored as an ordered list: front-camera shots first,
 * rear-camera shots second.
 *
 * JSON file storage is used instead of a database because the scale is small
 * and the app must stay lightweight.
 *
 * Backward compat: records written by older builds stored a single "photoPath" string.
 * fromJson() converts that to a single-element list transparently.
 */
public class AttemptRecord {

    public final long id;
    public final long timestampMs;
    public final int failedCount;
    /** Absolute paths to JPEG files, front-camera-first. */
    public final List<String> photoPaths;
    /** Absolute path to the MP4 recording, or null when no video was saved. */
    @Nullable
    public final String videoPath;
    @Nullable
    public final AttemptLocation location;

    public AttemptRecord(long id, long timestampMs, int failedCount, List<String> photoPaths) {
        this(id, timestampMs, failedCount, photoPaths, null, null);
    }

    public AttemptRecord(long id, long timestampMs, int failedCount, List<String> photoPaths,
                         @Nullable String videoPath) {
        this(id, timestampMs, failedCount, photoPaths, videoPath, null);
    }

    public AttemptRecord(long id, long timestampMs, int failedCount, List<String> photoPaths,
                         @Nullable String videoPath, @Nullable AttemptLocation location) {
        this.id = id;
        this.timestampMs = timestampMs;
        this.failedCount = failedCount;
        this.photoPaths = photoPaths != null ? new ArrayList<>(photoPaths) : new ArrayList<>();
        this.videoPath = (videoPath != null && !videoPath.isEmpty()) ? videoPath : null;
        this.location = location;
    }

    /** First available photo path, or null if none were saved. */
    @Nullable
    public String primaryPhotoPath() {
        return photoPaths.isEmpty() ? null : photoPaths.get(0);
    }

    public boolean hasVideo() {
        return videoPath != null && !videoPath.isEmpty();
    }

    public boolean hasLocation() {
        return location != null;
    }

    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("id", id);
        o.put("timestamp", timestampMs);
        o.put("failedCount", failedCount);
        JSONArray arr = new JSONArray();
        for (String p : photoPaths) arr.put(p);
        o.put("photoPaths", arr);
        if (hasVideo()) {
            o.put("videoPath", videoPath);
        }
        if (hasLocation()) {
            o.put("location", location.toJson());
        }
        return o;
    }

    public static AttemptRecord fromJson(JSONObject o) throws JSONException {
        List<String> paths = new ArrayList<>();
        if (o.has("photoPaths")) {
            JSONArray arr = o.getJSONArray("photoPaths");
            for (int i = 0; i < arr.length(); i++) paths.add(arr.getString(i));
        } else {
            // Backward compat: old format stored a single string
            String p = o.optString("photoPath", null);
            if (p != null && !p.isEmpty()) paths.add(p);
        }
        String videoPath = null;
        if (o.has("videoPath") && !o.isNull("videoPath")) {
            String p = o.optString("videoPath", null);
            if (p != null && !p.isEmpty()) videoPath = p;
        }
        AttemptLocation location = null;
        if (o.has("location") && !o.isNull("location")) {
            location = AttemptLocation.fromJson(o.getJSONObject("location"));
        }
        return new AttemptRecord(
                o.getLong("id"),
                o.getLong("timestamp"),
                o.getInt("failedCount"),
                paths,
                videoPath,
                location
        );
    }
}
