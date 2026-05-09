package fyi.ozelot.booplock;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.MenuItem;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.exifinterface.media.ExifInterface;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import fyi.ozelot.booplock.data.AttemptRecord;
import fyi.ozelot.booplock.data.AttemptStorage;

/**
 * Full-screen photo viewer for a selected history entry.
 *
 * Rotation is read from EXIF — cameras often store RAW data in a different
 * orientation than expected for display.
 */
public class PhotoViewActivity extends AppCompatActivity {

    public static final String EXTRA_RECORD_ID = "record_id";

    private static final SimpleDateFormat DATE_FMT =
            new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());

    private final ExecutorService bgExec = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private long recordId;
    private AttemptStorage storage;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_photo_view);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        recordId = getIntent().getLongExtra(EXTRA_RECORD_ID, -1L);
        storage = new AttemptStorage(this);

        ImageView img = findViewById(R.id.photo);
        TextView meta = findViewById(R.id.meta);
        View progress = findViewById(R.id.progress);

        AttemptRecord rec = storage.findById(recordId);
        if (rec == null) {
            meta.setText(R.string.photo_not_found);
            progress.setVisibility(View.GONE);
            return;
        }

        meta.setText(getString(R.string.photo_meta_fmt,
                DATE_FMT.format(new Date(rec.timestampMs)),
                getResources().getQuantityString(
                        R.plurals.failed_attempts, rec.failedCount, rec.failedCount)));

        if (rec.photoPath == null) {
            progress.setVisibility(View.GONE);
            return;
        }

        bgExec.execute(() -> {
            Bitmap bm = decodeWithRotation(rec.photoPath);
            ui.post(() -> {
                progress.setVisibility(View.GONE);
                if (bm != null) img.setImageBitmap(bm);
            });
        });
    }

    @Override
    public boolean onCreateOptionsMenu(android.view.Menu menu) {
        getMenuInflater().inflate(R.menu.photo_menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        int id = item.getItemId();
        if (id == android.R.id.home) {
            finish();
            return true;
        }
        if (id == R.id.action_delete) {
            new MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.delete_one_title)
                    .setMessage(R.string.delete_one_message)
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(R.string.delete_one_confirm, (d, w) -> {
                        storage.deleteById(recordId);
                        finish();
                    })
                    .show();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    /** Decode with sampling and rotate according to EXIF. */
    @Nullable
    private static Bitmap decodeWithRotation(String path) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path, o);
        int sample = 1;
        // Target: ~2 megapixels for screen display.
        while ((o.outWidth / sample) > 1600 || (o.outHeight / sample) > 1600) {
            sample *= 2;
        }
        BitmapFactory.Options o2 = new BitmapFactory.Options();
        o2.inSampleSize = sample;
        Bitmap bm = BitmapFactory.decodeFile(path, o2);
        if (bm == null) return null;

        try {
            ExifInterface exif = new ExifInterface(path);
            int orient = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL);
            float rotation;
            switch (orient) {
                case ExifInterface.ORIENTATION_ROTATE_90: rotation = 90; break;
                case ExifInterface.ORIENTATION_ROTATE_180: rotation = 180; break;
                case ExifInterface.ORIENTATION_ROTATE_270: rotation = 270; break;
                default: rotation = 0;
            }
            if (rotation != 0) {
                Matrix m = new Matrix();
                m.postRotate(rotation);
                Bitmap rotated = Bitmap.createBitmap(bm, 0, 0, bm.getWidth(), bm.getHeight(), m, true);
                if (rotated != bm) bm.recycle();
                bm = rotated;
            }
        } catch (IOException ignore) { /* leave original */ }
        return bm;
    }
}
