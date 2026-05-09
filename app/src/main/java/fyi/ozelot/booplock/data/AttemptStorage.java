package fyi.ozelot.booplock.data;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Persistent entry list stored in a single JSON file in internal storage.
 *
 * Reads and writes are synchronized on the class monitor — at most a handful
 * of threads (UI, CaptureService) ever access it, so this is sufficient.
 * For larger scale, this should be migrated to Room/SQLite.
 */
public class AttemptStorage {

    private static final String TAG = "AttemptStorage";
    private static final String FILE_NAME = "attempts.json";
    private static final String CAPTURES_DIR = "captures";

    private final File storeFile;
    private final File capturesDir;

    public AttemptStorage(Context ctx) {
        File base = ctx.getApplicationContext().getFilesDir();
        this.storeFile = new File(base, FILE_NAME);
        this.capturesDir = new File(base, CAPTURES_DIR);
        if (!capturesDir.exists() && !capturesDir.mkdirs()) {
            Log.w(TAG, "Cannot create captures dir: " + capturesDir);
        }
    }

    public File getCapturesDir() {
        return capturesDir;
    }

    /** Creates a new photo file — path to be used by the camera service. */
    public File newPhotoFile(long timestampMs) {
        return new File(capturesDir, "capture_" + timestampMs + ".jpg");
    }

    public synchronized AttemptRecord append(long timestampMs, int failedCount, String photoPath) {
        List<AttemptRecord> list = readAll();
        long nextId = nextId(list);
        AttemptRecord rec = new AttemptRecord(nextId, timestampMs, failedCount, photoPath);
        list.add(rec);
        writeAll(list);
        return rec;
    }

    public synchronized List<AttemptRecord> readAllNewestFirst() {
        List<AttemptRecord> list = readAll();
        Collections.sort(list, new Comparator<AttemptRecord>() {
            @Override
            public int compare(AttemptRecord a, AttemptRecord b) {
                return Long.compare(b.timestampMs, a.timestampMs);
            }
        });
        return list;
    }

    public synchronized AttemptRecord findById(long id) {
        for (AttemptRecord r : readAll()) {
            if (r.id == id) return r;
        }
        return null;
    }

    public synchronized void deleteById(long id) {
        List<AttemptRecord> list = readAll();
        AttemptRecord toDelete = null;
        for (AttemptRecord r : list) {
            if (r.id == id) {
                toDelete = r;
                break;
            }
        }
        if (toDelete == null) return;
        list.remove(toDelete);
        if (toDelete.photoPath != null) {
            File f = new File(toDelete.photoPath);
            if (f.exists() && !f.delete()) {
                Log.w(TAG, "Cannot delete photo file: " + f);
            }
        }
        writeAll(list);
    }

    public synchronized void deleteAll() {
        List<AttemptRecord> list = readAll();
        for (AttemptRecord r : list) {
            if (r.photoPath != null) {
                File f = new File(r.photoPath);
                if (f.exists()) //noinspection ResultOfMethodCallIgnored
                    f.delete();
            }
        }
        writeAll(new ArrayList<AttemptRecord>());
    }

    // --- I/O ---

    private List<AttemptRecord> readAll() {
        List<AttemptRecord> out = new ArrayList<>();
        if (!storeFile.exists()) return out;
        try {
            byte[] bytes = readBytes(storeFile);
            String json = new String(bytes, StandardCharsets.UTF_8);
            if (json.isEmpty()) return out;
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                out.add(AttemptRecord.fromJson(o));
            }
        } catch (IOException | JSONException e) {
            Log.e(TAG, "Failed to read store, returning empty list", e);
        }
        return out;
    }

    private void writeAll(List<AttemptRecord> list) {
        try {
            JSONArray arr = new JSONArray();
            for (AttemptRecord r : list) {
                arr.put(r.toJson());
            }
            byte[] data = arr.toString().getBytes(StandardCharsets.UTF_8);
            try (FileOutputStream fos = new FileOutputStream(storeFile, false)) {
                fos.write(data);
            }
        } catch (IOException | JSONException e) {
            Log.e(TAG, "Failed to write store", e);
        }
    }

    private static byte[] readBytes(File f) throws IOException {
        long len = f.length();
        if (len > Integer.MAX_VALUE) throw new IOException("File too big");
        byte[] buf = new byte[(int) len];
        try (java.io.FileInputStream fis = new java.io.FileInputStream(f)) {
            int read = 0;
            while (read < buf.length) {
                int n = fis.read(buf, read, buf.length - read);
                if (n < 0) break;
                read += n;
            }
        }
        return buf;
    }

    private static long nextId(List<AttemptRecord> list) {
        long max = 0;
        for (AttemptRecord r : list) {
            if (r.id > max) max = r.id;
        }
        return max + 1;
    }
}
