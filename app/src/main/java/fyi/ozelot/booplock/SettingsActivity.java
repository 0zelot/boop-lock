package fyi.ozelot.booplock;

import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.MenuItem;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.preference.EditTextPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.SwitchPreferenceCompat;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import fyi.ozelot.booplock.admin.LockAdminReceiver;
import fyi.ozelot.booplock.data.Prefs;
import fyi.ozelot.booplock.email.EmailSender;

/**
 * Settings screen. PreferenceFragmentCompat automatically saves values to
 * the default SharedPreferences — the same file that the Prefs class reads.
 */
public class SettingsActivity extends AppCompatActivity {

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.settings_container), (v, insets) -> {
            int navBottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom;
            v.setPadding(v.getPaddingLeft(), v.getPaddingTop(), v.getPaddingRight(), navBottom);
            return insets;
        });

        if (savedInstanceState == null) {
            getSupportFragmentManager().beginTransaction()
                    .replace(R.id.settings_container, new SettingsFragment())
                    .commit();
        }
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    public static class SettingsFragment extends PreferenceFragmentCompat {

        private final ExecutorService emailExec = Executors.newSingleThreadExecutor();
        private final Handler ui = new Handler(Looper.getMainLooper());

        @Override
        public void onCreatePreferences(@Nullable Bundle savedInstanceState, @Nullable String rootKey) {
            setPreferencesFromResource(R.xml.preferences, rootKey);

            EditTextPreference threshold = findPreference(Prefs.KEY_THRESHOLD);
            if (threshold != null) {
                threshold.setOnBindEditTextListener(et ->
                        et.setInputType(InputType.TYPE_CLASS_NUMBER));
                threshold.setSummaryProvider(pref -> {
                    int v = Prefs.get(requireContext()).getThreshold();
                    return getResources().getQuantityString(
                            R.plurals.threshold_summary, v, v);
                });
            }

            SwitchPreferenceCompat video = findPreference(Prefs.KEY_VIDEO_ENABLED);
            if (video != null) {
                video.setDefaultValue(true);
            }

            SwitchPreferenceCompat location = findPreference(Prefs.KEY_LOCATION_ENABLED);
            if (location != null) {
                location.setDefaultValue(true);
            }

            SwitchPreferenceCompat notifs = findPreference(Prefs.KEY_NOTIFICATIONS_ENABLED);
            if (notifs != null) {
                notifs.setDefaultValue(true);
            }

            configureEmailPreferences();

            Preference adminPref = findPreference("pref_open_admin");
            if (adminPref != null) {
                adminPref.setOnPreferenceClickListener(p -> {
                    DevicePolicyManager dpm = (DevicePolicyManager)
                            requireContext().getSystemService(DEVICE_POLICY_SERVICE);
                    ComponentName admin = LockAdminReceiver.getComponentName(requireContext());
                    boolean active = dpm != null && dpm.isAdminActive(admin);
                    if (active) {
                        // Open global device administrator settings.
                        startActivity(new Intent(android.provider.Settings.ACTION_SECURITY_SETTINGS));
                    } else {
                        Intent i = new Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN);
                        i.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin);
                        i.putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                                getString(R.string.device_admin_description));
                        startActivity(i);
                    }
                    return true;
                });
            }
        }

        private void configureEmailPreferences() {
            SwitchPreferenceCompat emailEnabled = findPreference(Prefs.KEY_EMAIL_ENABLED);
            if (emailEnabled != null) {
                emailEnabled.setDefaultValue(false);
            }

            EditTextPreference host = findPreference(Prefs.KEY_EMAIL_SMTP_HOST);
            if (host != null) {
                host.setOnBindEditTextListener(et ->
                        et.setInputType(InputType.TYPE_CLASS_TEXT
                                | InputType.TYPE_TEXT_VARIATION_URI));
            }

            EditTextPreference port = findPreference(Prefs.KEY_EMAIL_SMTP_PORT);
            if (port != null) {
                port.setDefaultValue("587");
                port.setOnBindEditTextListener(et ->
                        et.setInputType(InputType.TYPE_CLASS_NUMBER));
            }

            EditTextPreference from = findPreference(Prefs.KEY_EMAIL_FROM);
            if (from != null) {
                from.setOnBindEditTextListener(et ->
                        et.setInputType(InputType.TYPE_CLASS_TEXT
                                | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS));
            }

            EditTextPreference password = findPreference(Prefs.KEY_EMAIL_PASSWORD);
            if (password != null) {
                password.setOnBindEditTextListener(et ->
                        et.setInputType(InputType.TYPE_CLASS_TEXT
                                | InputType.TYPE_TEXT_VARIATION_PASSWORD));
                password.setSummaryProvider(pref -> {
                    String value = ((EditTextPreference) pref).getText();
                    boolean empty = value == null || value.isEmpty();
                    return getString(empty
                            ? R.string.pref_email_password_empty
                            : R.string.pref_email_password_set);
                });
            }

            EditTextPreference to = findPreference(Prefs.KEY_EMAIL_TO);
            if (to != null) {
                to.setOnBindEditTextListener(et ->
                        et.setInputType(InputType.TYPE_CLASS_TEXT
                                | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS));
            }

            Preference test = findPreference(Prefs.KEY_EMAIL_TEST);
            if (test != null) {
                test.setOnPreferenceClickListener(pref -> {
                    sendTestEmail(pref);
                    return true;
                });
            }
        }

        private void sendTestEmail(Preference testPref) {
            EmailSender.Config config = EmailSender.Config.fromPrefs(Prefs.get(requireContext()));
            if (!config.isComplete()) {
                showEmailResult(false, getString(R.string.email_settings_incomplete));
                return;
            }

            testPref.setEnabled(false);
            testPref.setSummary(R.string.pref_email_test_sending);
            emailExec.execute(() -> {
                EmailSender.Result result = EmailSender.send(
                        config,
                        "BoopLock test email",
                        "This is a BoopLock SMTP test email.",
                        Collections.emptyList());
                ui.post(() -> {
                    if (!isAdded()) return;
                    testPref.setEnabled(true);
                    testPref.setSummary(R.string.pref_email_test_summary);
                    showEmailResult(result.success, result.message);
                });
            });
        }

        private void showEmailResult(boolean success, String message) {
            new MaterialAlertDialogBuilder(requireContext())
                    .setTitle(success
                            ? R.string.email_test_success_title
                            : R.string.email_test_error_title)
                    .setMessage(message)
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
        }

        @Override
        public void onDestroy() {
            emailExec.shutdownNow();
            super.onDestroy();
        }
    }
}
