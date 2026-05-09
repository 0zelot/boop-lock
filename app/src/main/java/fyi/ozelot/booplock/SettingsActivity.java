package fyi.ozelot.booplock;

import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.view.MenuItem;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.preference.EditTextPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.SwitchPreferenceCompat;

import fyi.ozelot.booplock.admin.LockAdminReceiver;
import fyi.ozelot.booplock.data.Prefs;

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

            SwitchPreferenceCompat notifs = findPreference(Prefs.KEY_NOTIFICATIONS_ENABLED);
            if (notifs != null) {
                notifs.setDefaultValue(true);
            }

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
    }
}
