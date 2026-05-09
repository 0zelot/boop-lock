package fyi.ozelot.booplock.data;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;

/**
 * Thin wrapper over SharedPreferences. Holds:
 *   - user settings (threshold, notifications),
 *   - transient state of the current cycle (error counter, whether photo was taken).
 *
 * Keys prefixed "pref_*" are shared with preferences.xml (PreferenceFragmentCompat),
 * so changes in the Settings UI are automatically picked up here.
 *
 * The cycle state contains two flags that together eliminate the race condition
 * between CaptureService and UnlockReceiver:
 *
 *   captureTriggered  = true immediately after the service command is sent
 *   captureComplete   = true only after the service has written the photo to disk
 *   pendingNotify     = true when USER_PRESENT arrived before the service finished
 *
 * This allows the service to send the notification if USER_PRESENT arrived first,
 * and the receiver to send it if the service finished first.
 */
public class Prefs {

    public static final String KEY_THRESHOLD = "pref_threshold";
    public static final String KEY_NOTIFICATIONS_ENABLED = "pref_notifications_enabled";

    private static final String KEY_CYCLE_FAILED_COUNT = "cycle_failed_count";
    private static final String KEY_CYCLE_CAPTURE_TRIGGERED = "cycle_capture_triggered";
    private static final String KEY_CYCLE_CAPTURE_COMPLETE = "cycle_capture_complete";
    private static final String KEY_CYCLE_PENDING_NOTIFY = "cycle_pending_notify";
    private static final String KEY_CYCLE_LAST_PHOTO_PATH = "cycle_last_photo_path";
    private static final String KEY_CYCLE_LAST_RECORD_ID = "cycle_last_record_id";

    // In-app alert: used when push could not be delivered (missing permission / blocked channel).
    private static final String KEY_UNSEEN_ALERT_RECORD  = "unseen_alert_record";

    private static final int DEFAULT_THRESHOLD = 1;

    private final SharedPreferences sp;

    private static volatile Prefs INSTANCE;

    public static Prefs get(Context ctx) {
        if (INSTANCE == null) {
            synchronized (Prefs.class) {
                if (INSTANCE == null) {
                    INSTANCE = new Prefs(ctx.getApplicationContext());
                }
            }
        }
        return INSTANCE;
    }

    private Prefs(Context appContext) {
        this.sp = PreferenceManager.getDefaultSharedPreferences(appContext);
    }

    // --- User settings ---

    public int getThreshold() {
        Object raw = sp.getAll().get(KEY_THRESHOLD);
        if (raw instanceof Integer) {
            return Math.max(1, (Integer) raw);
        }
        if (raw instanceof String) {
            try {
                return Math.max(1, Integer.parseInt((String) raw));
            } catch (NumberFormatException ignored) { /* fall-through */ }
        }
        return DEFAULT_THRESHOLD;
    }

    public boolean areNotificationsEnabled() {
        return sp.getBoolean(KEY_NOTIFICATIONS_ENABLED, true);
    }

    // --- Current cycle state ---

    public int incrementCurrentFailedCount() {
        int next = sp.getInt(KEY_CYCLE_FAILED_COUNT, 0) + 1;
        sp.edit().putInt(KEY_CYCLE_FAILED_COUNT, next).apply();
        return next;
    }

    public int getCurrentFailedCount() {
        return sp.getInt(KEY_CYCLE_FAILED_COUNT, 0);
    }

    public boolean isCaptureTriggeredInCycle() {
        return sp.getBoolean(KEY_CYCLE_CAPTURE_TRIGGERED, false);
    }

    public void setCaptureTriggeredInCycle(boolean v) {
        sp.edit().putBoolean(KEY_CYCLE_CAPTURE_TRIGGERED, v).apply();
    }

    public boolean isCaptureCompleteInCycle() {
        return sp.getBoolean(KEY_CYCLE_CAPTURE_COMPLETE, false);
    }

    public void setCaptureCompleteInCycle(boolean v) {
        sp.edit().putBoolean(KEY_CYCLE_CAPTURE_COMPLETE, v).apply();
    }

    /** true when USER_PRESENT arrived before the service finished saving the photo. */
    public boolean isPendingNotify() {
        return sp.getBoolean(KEY_CYCLE_PENDING_NOTIFY, false);
    }

    public void setPendingNotify(boolean v) {
        sp.edit().putBoolean(KEY_CYCLE_PENDING_NOTIFY, v).apply();
    }

    public void setLastCyclePhotoPath(String path) {
        sp.edit().putString(KEY_CYCLE_LAST_PHOTO_PATH, path).apply();
    }

    public String getLastCyclePhotoPath() {
        return sp.getString(KEY_CYCLE_LAST_PHOTO_PATH, null);
    }

    public void setLastCycleRecordId(long id) {
        sp.edit().putLong(KEY_CYCLE_LAST_RECORD_ID, id).apply();
    }

    public long getLastCycleRecordId() {
        return sp.getLong(KEY_CYCLE_LAST_RECORD_ID, -1L);
    }

    // --- In-app alert (fallback when push could not be delivered) ---

    public void setUnseenAlert(long recordId) {
        sp.edit()
                .putLong(KEY_UNSEEN_ALERT_RECORD, recordId)
                .apply();
    }

    public boolean hasUnseenAlert() {
        return sp.contains(KEY_UNSEEN_ALERT_RECORD);
    }

    public long getUnseenAlertRecord() {
        return sp.getLong(KEY_UNSEEN_ALERT_RECORD, -1L);
    }

    public void clearUnseenAlert() {
        sp.edit()
                .remove(KEY_UNSEEN_ALERT_RECORD)
                .apply();
    }

    /** Clears the entire cycle state — called after a successful unlock. */
    public void resetCurrentCycle() {
        sp.edit()
                .remove(KEY_CYCLE_FAILED_COUNT)
                .remove(KEY_CYCLE_CAPTURE_TRIGGERED)
                .remove(KEY_CYCLE_CAPTURE_COMPLETE)
                .remove(KEY_CYCLE_PENDING_NOTIFY)
                .remove(KEY_CYCLE_LAST_PHOTO_PATH)
                .remove(KEY_CYCLE_LAST_RECORD_ID)
                .apply();
    }
}
