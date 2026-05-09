package fyi.ozelot.booplock.debug;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Simple logger that writes events to a text file.
 * The file is capped at 200 lines (FIFO rotation) to prevent unbounded growth.
 * Reading is done in DebugActivity.
 */
public class DebugLog {

    private static final String TAG = "BoopLock";
    private static final String FILE_NAME = "debug_log.txt";
    private static final int MAX_LINES = 200;

    private static final SimpleDateFormat FMT =
            new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.getDefault());

    public static synchronized void i(Context ctx, String message) {
        log(ctx, "I", message);
    }

    public static synchronized void w(Context ctx, String message) {
        log(ctx, "W", message);
    }

    public static synchronized void e(Context ctx, String message) {
        log(ctx, "E", message);
    }

    private static void log(Context ctx, String level, String message) {
        String line = FMT.format(new Date()) + " [" + level + "] " + message;
        Log.i(TAG, message);

        try {
            File f = logFile(ctx);
            String existing = f.exists() ? readFile(f) : "";
            String[] lines = existing.isEmpty() ? new String[0] : existing.split("\n");

            // Rotation: keep the last MAX_LINES-1 lines plus the new one.
            StringBuilder sb = new StringBuilder();
            int start = Math.max(0, lines.length - (MAX_LINES - 1));
            for (int i = start; i < lines.length; i++) {
                sb.append(lines[i]).append('\n');
            }
            sb.append(line).append('\n');

            try (FileOutputStream fos = new FileOutputStream(f, false)) {
                fos.write(sb.toString().getBytes(StandardCharsets.UTF_8));
            }
        } catch (IOException ignored) { /* do not interrupt flow because of a log write */ }
    }

    public static synchronized String readAll(Context ctx) {
        try {
            File f = logFile(ctx);
            if (!f.exists()) return "(no logs)";
            return readFile(f);
        } catch (IOException e) {
            return "(read error: " + e.getMessage() + ")";
        }
    }

    public static synchronized void clear(Context ctx) {
        logFile(ctx).delete();
    }

    private static File logFile(Context ctx) {
        return new File(ctx.getApplicationContext().getFilesDir(), FILE_NAME);
    }

    private static String readFile(File f) throws IOException {
        byte[] buf = new byte[(int) f.length()];
        try (java.io.FileInputStream fis = new java.io.FileInputStream(f)) {
            int read = 0;
            while (read < buf.length) {
                int n = fis.read(buf, read, buf.length - read);
                if (n < 0) break;
                read += n;
            }
        }
        return new String(buf, StandardCharsets.UTF_8);
    }
}
