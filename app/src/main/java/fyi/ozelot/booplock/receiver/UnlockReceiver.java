package fyi.ozelot.booplock.receiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import fyi.ozelot.booplock.data.Prefs;
import fyi.ozelot.booplock.debug.DebugLog;
import fyi.ozelot.booplock.notify.NotificationHelper;

public class UnlockReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) return;
        String action = intent.getAction();

        if (Intent.ACTION_BOOT_COMPLETED.equals(action)) {
            Prefs.get(context).resetCurrentCycle();
            DebugLog.i(context, "UnlockReceiver: BOOT_COMPLETED - cycle reset");
            return;
        }

        if (!Intent.ACTION_USER_PRESENT.equals(action)) return;

        Prefs prefs = Prefs.get(context);
        boolean captureWasTriggered = prefs.isCaptureTriggeredInCycle();
        boolean captureIsDone      = prefs.isCaptureCompleteInCycle();
        boolean notifEnabled       = prefs.areNotificationsEnabled();
        int failedCount            = prefs.getCurrentFailedCount();
        long lastRecordId          = prefs.getLastCycleRecordId();

        DebugLog.i(context, "UnlockReceiver: USER_PRESENT"
                + " triggered=" + captureWasTriggered
                + " complete=" + captureIsDone
                + " notifEnabled=" + notifEnabled
                + " failed=" + failedCount
                + " recordId=" + lastRecordId);

        // Notification is already sent from CaptureService immediately after saving the photo.
        // Here we only reset the cycle (backup for biometric unlock,
        // which does not trigger onPasswordSucceeded).
        DebugLog.i(context, "UnlockReceiver: USER_PRESENT - cycle reset (notification already sent by service)");
        prefs.resetCurrentCycle();
    }

    private static void scheduleFallbackReset(Context context) {
        android.app.AlarmManager am =
                (android.app.AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;

        Intent i = new Intent(context, FallbackResetReceiver.class);
        int flags = android.app.PendingIntent.FLAG_UPDATE_CURRENT;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            flags |= android.app.PendingIntent.FLAG_IMMUTABLE;
        }
        android.app.PendingIntent pi = android.app.PendingIntent.getBroadcast(
                context, 0, i, flags);

        long trigger = System.currentTimeMillis() + 30_000L;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            am.setExactAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, trigger, pi);
        } else {
            am.setExact(android.app.AlarmManager.RTC_WAKEUP, trigger, pi);
        }
        DebugLog.i(context, "UnlockReceiver: FallbackReset scheduled in 30s");
    }
}
