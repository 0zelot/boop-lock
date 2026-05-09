package fyi.ozelot.booplock;

import android.Manifest;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.snackbar.Snackbar;

import java.util.List;

import fyi.ozelot.booplock.admin.LockAdminReceiver;
import fyi.ozelot.booplock.data.AttemptRecord;
import fyi.ozelot.booplock.data.AttemptStorage;
import fyi.ozelot.booplock.data.Prefs;
import fyi.ozelot.booplock.notify.NotificationHelper;
import fyi.ozelot.booplock.ui.AttemptAdapter;

/**
 * Main app screen.
 *
 * Checks four prerequisites in priority order:
 *  1. Device Admin — critical; nothing works without it.
 *  2. Camera — without the permission no photo can be taken.
 *  3. Notifications — without the permission the alert won't be delivered.
 *  4. Battery optimization — CRITICAL on Samsung One UI, where the system
 *     aggressively kills background processes and blocks broadcasts.
 *
 * Also resets a stuck cycle in onResume — failsafe in case USER_PRESENT
 * was never received and FallbackReset also failed.
 */
public class MainActivity extends AppCompatActivity {

    public static final String EXTRA_HIGHLIGHT_RECORD_ID = "highlight_record_id";

    private RecyclerView recycler;
    private View emptyView;
    private View permissionPanel;
    private TextView permissionPanelText;
    private MaterialButton permissionPanelButton;

    private AttemptStorage storage;
    private AttemptAdapter adapter;

    private ActivityResultLauncher<String> requestCameraLauncher;
    private ActivityResultLauncher<String> requestNotificationsLauncher;
    private ActivityResultLauncher<Intent> requestAdminLauncher;
    private ActivityResultLauncher<Intent> requestBatteryLauncher;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        storage = new AttemptStorage(this);
        recycler = findViewById(R.id.recycler);
        emptyView = findViewById(R.id.empty_view);
        permissionPanel = findViewById(R.id.permission_panel);
        permissionPanelText = findViewById(R.id.permission_panel_text);
        permissionPanelButton = findViewById(R.id.permission_panel_button);

        adapter = new AttemptAdapter(record -> {
            Intent i = new Intent(MainActivity.this, PhotoViewActivity.class);
            i.putExtra(PhotoViewActivity.EXTRA_RECORD_ID, record.id);
            startActivity(i);
        });
        recycler.setLayoutManager(new LinearLayoutManager(this));
        recycler.setAdapter(adapter);

        requestCameraLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(),
                granted -> updatePermissionPanel());

        requestNotificationsLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(),
                granted -> updatePermissionPanel());

        requestAdminLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> updatePermissionPanel());

        requestBatteryLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> updatePermissionPanel());

        NotificationHelper.cancelAlert(this);
        NotificationHelper.ensureChannels(this);
    }

    @Override
    protected void onResume() {
        super.onResume();

        Prefs prefs = Prefs.get(this);

        // Failsafe: app open = device unlocked.
        // Clear a stuck cycle if USER_PRESENT/FallbackReset both failed.
        if (prefs.isCaptureTriggeredInCycle() && prefs.isCaptureCompleteInCycle()) {
            prefs.resetCurrentCycle();
        }

        // In-app alert: push was not delivered (missing permission / blocked channel).
        // Show a Snackbar with a button to navigate to the entry.
        if (prefs.hasUnseenAlert()) {
            int count = prefs.getUnseenAlertCount();
            long recordId = prefs.getUnseenAlertRecord();
            prefs.clearUnseenAlert();
            showInAppAlert(count, recordId);
        }

        refreshList();
        updatePermissionPanel();
    }

    private void showInAppAlert(int failedCount, long recordId) {
        String msg = getResources().getQuantityString(
                R.plurals.failed_attempts, failedCount, failedCount);
        Snackbar sb = Snackbar.make(recycler,
                getString(R.string.in_app_alert_fmt, msg),
                Snackbar.LENGTH_LONG);
        if (recordId > 0) {
            sb.setAction(R.string.in_app_alert_action, v -> {
                Intent i = new Intent(this, PhotoViewActivity.class);
                i.putExtra(PhotoViewActivity.EXTRA_RECORD_ID, recordId);
                startActivity(i);
            });
        }
        sb.show();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main_menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_settings) {
            startActivity(new Intent(this, SettingsActivity.class));
            return true;
        } else if (id == R.id.action_debug) {
            startActivity(new Intent(this, fyi.ozelot.booplock.debug.DebugActivity.class));
            return true;
        } else if (id == R.id.action_clear) {
            confirmClearAll();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void confirmClearAll() {
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(R.string.clear_all_title)
                .setMessage(R.string.clear_all_message)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.clear_all_confirm, (d, w) -> {
                    storage.deleteAll();
                    refreshList();
                })
                .show();
    }

    private void refreshList() {
        List<AttemptRecord> records = storage.readAllNewestFirst();
        adapter.submitList(records);
        emptyView.setVisibility(records.isEmpty() ? View.VISIBLE : View.GONE);
        recycler.setVisibility(records.isEmpty() ? View.GONE : View.VISIBLE);
    }

    /**
     * Permission panel — checks in priority order.
     * Battery optimization is last because it is a "soft" blocker (does not
     * completely prevent operation, but breaks Samsung).
     */
    private void updatePermissionPanel() {
        DevicePolicyManager dpm = (DevicePolicyManager) getSystemService(DEVICE_POLICY_SERVICE);
        ComponentName admin = LockAdminReceiver.getComponentName(this);
        boolean adminEnabled = dpm != null && dpm.isAdminActive(admin);

        boolean cameraGranted = ContextCompat.checkSelfPermission(this,
                Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;

        boolean notificationsGranted = true;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationsGranted = ContextCompat.checkSelfPermission(this,
                    Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
        }

        boolean batteryOptimized = isBatteryOptimized();
        boolean channelBlocked = isAlertChannelBlocked();

        if (!adminEnabled) {
            show(R.string.perm_need_admin, R.string.perm_btn_admin,
                    v -> requestDeviceAdmin());
        } else if (!cameraGranted) {
            show(R.string.perm_need_camera, R.string.perm_btn_camera,
                    v -> requestCameraLauncher.launch(Manifest.permission.CAMERA));
        } else if (!notificationsGranted) {
            show(R.string.perm_need_notifications, R.string.perm_btn_notifications,
                    v -> {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            requestNotificationsLauncher.launch(
                                    Manifest.permission.POST_NOTIFICATIONS);
                        }
                    });
        } else if (channelBlocked) {
            // Notification channel blocked in system settings —
            // most common cause of missing alerts on Samsung One UI.
            show(R.string.perm_need_channel, R.string.perm_btn_channel,
                    v -> openNotificationChannelSettings());
        } else if (batteryOptimized) {
            show(R.string.perm_need_battery, R.string.perm_btn_battery,
                    v -> requestBatteryOptimizationExemption());
        } else {
            permissionPanel.setVisibility(View.GONE);
        }
    }

    private void show(int textRes, int btnRes, View.OnClickListener listener) {
        permissionPanel.setVisibility(View.VISIBLE);
        permissionPanelText.setText(textRes);
        permissionPanelButton.setText(btnRes);
        permissionPanelButton.setOnClickListener(listener);
    }

    /** Returns true if the alert channel is blocked by the user. */
    private boolean isAlertChannelBlocked() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false;
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) return false;
        NotificationChannel ch = nm.getNotificationChannel(
                fyi.ozelot.booplock.notify.NotificationHelper.CHANNEL_ALERT);
        return ch != null && ch.getImportance() == NotificationManager.IMPORTANCE_NONE;
    }

    private void openNotificationChannelSettings() {
        Intent i = new Intent(android.provider.Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, getPackageName())
                .putExtra(android.provider.Settings.EXTRA_CHANNEL_ID,
                        fyi.ozelot.booplock.notify.NotificationHelper.CHANNEL_ALERT);
        try {
            startActivity(i);
        } catch (Exception e) {
            Snackbar.make(recycler, R.string.error_battery_settings, Snackbar.LENGTH_LONG).show();
        }
    }

    /** Returns true if the system is still optimizing battery for this app. */
    private boolean isBatteryOptimized() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return false;
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        return pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName());
    }

    private void requestBatteryOptimizationExemption() {
        try {
            // Direct exemption request — on most ROMs opens a dialog
            // "Allow unrestricted background activity?".
            Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            i.setData(Uri.parse("package:" + getPackageName()));
            requestBatteryLauncher.launch(i);
        } catch (Exception e) {
            // Fallback: open general battery optimization settings.
            try {
                requestBatteryLauncher.launch(
                        new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            } catch (Exception ex) {
                Snackbar.make(recycler, R.string.error_battery_settings, Snackbar.LENGTH_LONG).show();
            }
        }
    }

    private void requestDeviceAdmin() {
        Intent intent = new Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN);
        intent.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN,
                LockAdminReceiver.getComponentName(this));
        intent.putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                getString(R.string.device_admin_description));
        try {
            requestAdminLauncher.launch(intent);
        } catch (Exception e) {
            Snackbar.make(recycler, R.string.error_admin_intent, Snackbar.LENGTH_LONG).show();
        }
    }
}
