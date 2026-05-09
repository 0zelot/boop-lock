package fyi.ozelot.booplock.notify;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

import fyi.ozelot.booplock.MainActivity;
import fyi.ozelot.booplock.R;
import fyi.ozelot.booplock.data.Prefs;
import fyi.ozelot.booplock.debug.DebugLog;

public class NotificationHelper {

    private static final String TAG = "NotificationHelper";

    public static final String CHANNEL_SERVICE = "booplock_service";
    public static final String CHANNEL_ALERT   = "booplock_alert";

    public static final int NOTIF_SERVICE_ID = 1001;
    public static final int NOTIF_ALERT_ID   = 1002;

    public static void ensureChannels(Context ctx) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        if (nm == null) return;

        if (nm.getNotificationChannel(CHANNEL_SERVICE) == null) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_SERVICE,
                    ctx.getString(R.string.channel_service_name),
                    NotificationManager.IMPORTANCE_MIN);
            ch.setDescription(ctx.getString(R.string.channel_service_desc));
            ch.setShowBadge(false);
            ch.setSound(null, null);
            nm.createNotificationChannel(ch);
        }

        if (nm.getNotificationChannel(CHANNEL_ALERT) == null) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ALERT,
                    ctx.getString(R.string.channel_alert_name),
                    NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription(ctx.getString(R.string.channel_alert_desc));
            nm.createNotificationChannel(ch);
        }
    }

    public static Notification buildServiceNotification(Context ctx) {
        ensureChannels(ctx);
        return new NotificationCompat.Builder(ctx, CHANNEL_SERVICE)
                .setContentTitle(ctx.getString(R.string.service_notif_title))
                .setContentText(ctx.getString(R.string.service_notif_text))
                .setSmallIcon(R.drawable.ic_notification_lock)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .setOngoing(true)
                .setShowWhen(false)
                .build();
    }

    /**
     * Posts an alert notification. If it cannot be sent (missing permissions
     * or blocked channel), sets the hasUnseenAlert flag in Prefs so that
     * MainActivity displays an in-app banner on next open.
     */
    public static void postAlert(Context ctx, long recordId) {
        ensureChannels(ctx);

        DebugLog.i(ctx, "NotificationHelper: postAlert START recordId=" + recordId);

        // Step 1: POST_NOTIFICATIONS permission (Android 13+/API 33+).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            boolean hasPermission = ContextCompat.checkSelfPermission(ctx,
                    Manifest.permission.POST_NOTIFICATIONS)
                    == PackageManager.PERMISSION_GRANTED;
            if (!hasPermission) {
                DebugLog.e(ctx, "NotificationHelper: BLOCKED - POST_NOTIFICATIONS permission missing");
                markUnseenAlert(ctx, recordId);
                return;
            }
            DebugLog.i(ctx, "NotificationHelper: POST_NOTIFICATIONS OK");
        }

        // Step 2: notifications enabled at the app level.
        if (!NotificationManagerCompat.from(ctx).areNotificationsEnabled()) {
            DebugLog.e(ctx, "NotificationHelper: BLOCKED - notifications disabled for app");
            markUnseenAlert(ctx, recordId);
            return;
        }
        DebugLog.i(ctx, "NotificationHelper: areNotificationsEnabled OK");

        // Step 3: alert channel is not blocked by the user.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = ctx.getSystemService(NotificationManager.class);
            if (nm != null) {
                NotificationChannel ch = nm.getNotificationChannel(CHANNEL_ALERT);
                if (ch == null) {
                    DebugLog.w(ctx, "NotificationHelper: ALERT channel does not exist - creating");
                    ensureChannels(ctx);
                } else {
                    DebugLog.i(ctx, "NotificationHelper: ALERT channel importance=" + ch.getImportance());
                    if (ch.getImportance() == NotificationManager.IMPORTANCE_NONE) {
                        DebugLog.e(ctx, "NotificationHelper: BLOCKED - ALERT channel blocked by user");
                        markUnseenAlert(ctx, recordId);
                        return;
                    }
                }
            }
        }

        doNotify(ctx, recordId);
        DebugLog.i(ctx, "NotificationHelper: nm.notify() called - notification should be visible");
        Prefs.get(ctx).clearUnseenAlert();
    }

    private static void doNotify(Context ctx, long recordId) {
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        if (nm == null) return;

        Intent open = new Intent(ctx, MainActivity.class)
                .putExtra(MainActivity.EXTRA_HIGHLIGHT_RECORD_ID, recordId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            piFlags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent pi = PendingIntent.getActivity(ctx, 0, open, piFlags);

        Notification n = new NotificationCompat.Builder(ctx, CHANNEL_ALERT)
                .setContentTitle(ctx.getString(R.string.alert_title))
                .setContentText(ctx.getString(R.string.alert_text_fmt))
                .setSmallIcon(R.drawable.ic_notification_alert)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setAutoCancel(true)
                .setContentIntent(pi)
                .build();

        nm.notify(NOTIF_ALERT_ID, n);
        Log.i(TAG, "Alert notification posted recordId=" + recordId);
    }

    /** Stores unseen alert data for MainActivity to display. */
    private static void markUnseenAlert(Context ctx, long recordId) {
        Prefs.get(ctx).setUnseenAlert(recordId);
    }

    public static void cancelAlert(Context ctx) {
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        if (nm != null) nm.cancel(NOTIF_ALERT_ID);
    }
}
