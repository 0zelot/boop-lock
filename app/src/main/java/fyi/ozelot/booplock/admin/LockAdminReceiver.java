package fyi.ozelot.booplock.admin;

import android.app.admin.DeviceAdminReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

import fyi.ozelot.booplock.data.Prefs;
import fyi.ozelot.booplock.debug.DebugLog;
import fyi.ozelot.booplock.notify.NotificationHelper;
import fyi.ozelot.booplock.service.CaptureService;

public class LockAdminReceiver extends DeviceAdminReceiver {

    private static final String TAG = "LockAdminReceiver";

    public static ComponentName getComponentName(Context ctx) {
        return new ComponentName(ctx.getApplicationContext(), LockAdminReceiver.class);
    }

    @Override
    public void onEnabled(Context context, Intent intent) {
        super.onEnabled(context, intent);
        DebugLog.i(context, "AdminReceiver: ENABLED");
    }

    @Override
    public void onDisabled(Context context, Intent intent) {
        super.onDisabled(context, intent);
        DebugLog.w(context, "AdminReceiver: DISABLED - no more events");
        Prefs.get(context).resetCurrentCycle();
    }

    @Override
    public void onPasswordFailed(Context context, Intent intent) {
        super.onPasswordFailed(context, intent);

        Prefs prefs = Prefs.get(context);
        int failedInCycle = prefs.incrementCurrentFailedCount();
        int threshold = prefs.getThreshold();
        boolean alreadyTriggered = prefs.isCaptureTriggeredInCycle();

        DebugLog.i(context, "AdminReceiver: onPasswordFailed #" + failedInCycle
                + " threshold=" + threshold
                + " alreadyTriggered=" + alreadyTriggered);

        if (failedInCycle >= threshold && !alreadyTriggered) {
            prefs.setCaptureTriggeredInCycle(true);
            DebugLog.i(context, "AdminReceiver: threshold reached, starting CaptureService");
            startCaptureService(context, failedInCycle);
        } else if (alreadyTriggered) {
            DebugLog.i(context, "AdminReceiver: photo already taken in this cycle, skipping");
        } else {
            DebugLog.i(context, "AdminReceiver: waiting for more errors (threshold=" + threshold + ")");
        }
    }

    @Override
    public void onPasswordSucceeded(Context context, Intent intent) {
        super.onPasswordSucceeded(context, intent);
        // Notification is already sent from CaptureService immediately after saving the photo.
        // Here we only reset the cycle in case the service didn't finish in time.
        DebugLog.i(context, "AdminReceiver: onPasswordSucceeded - cycle reset");
        Prefs.get(context).resetCurrentCycle();
    }

    private void startCaptureService(Context context, int failedCount) {
        Intent serviceIntent = new Intent(context, CaptureService.class);
        serviceIntent.putExtra(CaptureService.EXTRA_FAILED_COUNT, failedCount);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent);
                DebugLog.i(context, "AdminReceiver: startForegroundService OK");
            } else {
                context.startService(serviceIntent);
                DebugLog.i(context, "AdminReceiver: startService OK");
            }
        } catch (Exception e) {
            DebugLog.e(context, "AdminReceiver: service start error: " + e.getMessage());
            Log.e(TAG, "Cannot start capture service", e);
        }
    }
}
