package fyi.ozelot.booplock.email;

import android.content.Context;
import android.os.Build;
import android.provider.Settings;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import fyi.ozelot.booplock.data.AttemptRecord;
import fyi.ozelot.booplock.data.Prefs;

public final class EmailAttemptMailer {

    public interface Callback {
        void onComplete(EmailSender.Result result);
    }

    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor();
    private static final SimpleDateFormat DATE_FMT =
            new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());

    private EmailAttemptMailer() {
    }

    public static void sendAttemptAsync(Context ctx, AttemptRecord record, Callback callback) {
        Context app = ctx.getApplicationContext();
        Prefs prefs = Prefs.get(app);

        if (!prefs.isEmailEnabled()) {
            complete(callback, EmailSender.Result.failure("Email delivery is disabled."));
            return;
        }

        EmailSender.Config config = EmailSender.Config.fromPrefs(prefs);
        if (!config.isComplete()) {
            complete(callback, EmailSender.Result.failure("Email settings are incomplete."));
            return;
        }

        EXEC.execute(() -> {
            List<EmailSender.Attachment> attachments = attachmentsFor(record);
            String timestamp = DATE_FMT.format(new Date(record.timestampMs));
            String subject = "BoopLock unlock attempt - " + timestamp;
            String body = "BoopLock detected a failed unlock attempt to your "
                    + deviceName(app) + ".\n\n"
                    + "Time: " + timestamp + "\n"
                    + "Photos: " + record.photoPaths.size() + "\n"
                    + "Video: " + (record.hasVideo() ? "yes" : "no") + "\n"
                    + locationText(record);
            complete(callback, EmailSender.send(config, subject, body, attachments));
        });
    }

    /** Returns the user-visible device name (Bluetooth name if set, otherwise model name). */
    private static String deviceName(Context ctx) {
        String name = Settings.Global.getString(ctx.getContentResolver(), "device_name");
        if (name != null && !name.trim().isEmpty()) return name.trim();
        return Build.MODEL;
    }

    private static String locationText(AttemptRecord record) {
        if (!record.hasLocation()) return "Location: unavailable\n";
        String accuracy = record.location.accuracyMeters >= 0
                ? " (~" + Math.round(record.location.accuracyMeters) + "m)"
                : "";
        return "Location: " + record.location.coordinates() + accuracy + "\n"
                + "Maps: " + record.location.mapsUrl() + "\n";
    }

    private static List<EmailSender.Attachment> attachmentsFor(AttemptRecord record) {
        List<EmailSender.Attachment> out = new ArrayList<>();
        int index = 1;
        for (String path : record.photoPaths) {
            File file = new File(path);
            out.add(new EmailSender.Attachment(file,
                    String.format(Locale.US, "booplock_photo_%02d.jpg", index++)));
        }
        if (record.hasVideo()) {
            out.add(new EmailSender.Attachment(new File(record.videoPath),
                    "booplock_video.mp4"));
        }
        return out;
    }

    private static void complete(Callback callback, EmailSender.Result result) {
        if (callback != null) callback.onComplete(result);
    }
}
