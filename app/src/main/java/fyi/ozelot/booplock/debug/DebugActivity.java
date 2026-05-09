package fyi.ozelot.booplock.debug;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.os.Bundle;
import android.view.MenuItem;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.snackbar.Snackbar;

import fyi.ozelot.booplock.R;

public class DebugActivity extends AppCompatActivity {

    private TextView logView;
    private ScrollView scroll;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_debug);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) getSupportActionBar().setDisplayHomeAsUpEnabled(true);

        logView = findViewById(R.id.log_text);
        scroll = findViewById(R.id.scroll);

        MaterialButton clearBtn = findViewById(R.id.btn_clear);
        clearBtn.setOnClickListener(v -> {
            DebugLog.clear(this);
            loadLog();
        });

        MaterialButton copyBtn = findViewById(R.id.btn_copy);
        copyBtn.setOnClickListener(v -> {
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("BoopLock log", logView.getText()));
                Snackbar.make(scroll, "Copied to clipboard", Snackbar.LENGTH_SHORT).show();
            }
        });

        loadLog();
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadLog();
    }

    private void loadLog() {
        String content = DebugLog.readAll(this);
        logView.setText(content);
        // Scroll to the end (newest entries).
        scroll.post(() -> scroll.fullScroll(ScrollView.FOCUS_DOWN));
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}
