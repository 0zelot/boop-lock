package fyi.ozelot.booplock.receiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import fyi.ozelot.booplock.data.Prefs;

/**
 * Alarm failsafe: if CaptureService crashed (camera error, killed by the system)
 * and never reset the cycle, this receiver fires 30 seconds after USER_PRESENT
 * and clears the stuck state.
 *
 * Without this, the captureTriggered=true flag would remain set forever
 * and no subsequent attempt would be captured.
 */
public class FallbackResetReceiver extends BroadcastReceiver {

    private static final String TAG = "FallbackResetReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        Prefs prefs = Prefs.get(context);

        // Reset only if the cycle is still ongoing — the service may have already
        // finished and reset it before the 30-second alarm fired.
        if (prefs.isCaptureTriggeredInCycle()) {
            Log.w(TAG, "Fallback reset triggered - service did not complete in time");
            prefs.resetCurrentCycle();
        }
    }
}
