package fyi.ozelot.booplock;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.exifinterface.media.ExifInterface;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.PagerSnapHelper;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import fyi.ozelot.booplock.data.AttemptRecord;
import fyi.ozelot.booplock.data.AttemptStorage;

/**
 * Full-screen photo viewer for a selected history entry. Swipe left/right to browse
 * all photos captured during the attempt (front-camera shots first, then rear).
 *
 * Rotation is read from EXIF — cameras often store raw data in landscape orientation.
 */
public class PhotoViewActivity extends AppCompatActivity {

    public static final String EXTRA_RECORD_ID = "record_id";

    private static final SimpleDateFormat DATE_FMT =
            new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());

    private final ExecutorService bgExec = Executors.newFixedThreadPool(2);
    private final Handler ui = new Handler(Looper.getMainLooper());

    private long recordId;
    private AttemptStorage storage;
    private AttemptRecord rec;
    private TextView meta;

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
        meta = findViewById(R.id.meta);

        int metaInitialBottom = meta.getPaddingBottom();
        ViewCompat.setOnApplyWindowInsetsListener(meta, (v, insets) -> {
            int navBottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom;
            v.setPadding(v.getPaddingLeft(), v.getPaddingTop(), v.getPaddingRight(),
                    metaInitialBottom + navBottom);
            return insets;
        });

        rec = storage.findById(recordId);
        if (rec == null) {
            meta.setText(R.string.photo_not_found);
            return;
        }

        if (rec.photoPaths.isEmpty()) {
            meta.setText(DATE_FMT.format(new Date(rec.timestampMs)));
            return;
        }

        RecyclerView pager = findViewById(R.id.photo_pager);
        LinearLayoutManager lm = new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false);
        pager.setLayoutManager(lm);
        new PagerSnapHelper().attachToRecyclerView(pager);
        pager.setAdapter(new PhotoPagerAdapter(rec.photoPaths));

        updateMeta(0);

        pager.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(@NonNull RecyclerView rv, int newState) {
                if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                    int pos = lm.findFirstCompletelyVisibleItemPosition();
                    if (pos == RecyclerView.NO_POSITION) pos = lm.findFirstVisibleItemPosition();
                    if (pos != RecyclerView.NO_POSITION) updateMeta(pos);
                }
            }
        });
    }

    private void updateMeta(int photoIndex) {
        String timestamp = DATE_FMT.format(new Date(rec.timestampMs));
        int total = rec.photoPaths.size();
        String page = total > 1 ? "  •  " + (photoIndex + 1) + " / " + total : "";
        meta.setText(timestamp + page);
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

    // --- Pager adapter ---

    private class PhotoPagerAdapter extends RecyclerView.Adapter<PhotoPagerAdapter.VH> {

        private final List<String> paths;

        PhotoPagerAdapter(List<String> paths) {
            this.paths = paths;
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = getLayoutInflater().inflate(R.layout.item_photo_page, parent, false);
            v.setLayoutParams(new RecyclerView.LayoutParams(
                    RecyclerView.LayoutParams.MATCH_PARENT,
                    RecyclerView.LayoutParams.MATCH_PARENT));
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH holder, int position) {
            holder.bind(paths.get(position));
        }

        @Override
        public int getItemCount() {
            return paths.size();
        }

        class VH extends RecyclerView.ViewHolder {
            final ImageView photo;
            final ProgressBar progress;
            String currentPath;

            VH(@NonNull View itemView) {
                super(itemView);
                photo = itemView.findViewById(R.id.page_photo);
                progress = itemView.findViewById(R.id.page_progress);
            }

            void bind(String path) {
                currentPath = path;
                photo.setImageDrawable(null);
                progress.setVisibility(View.VISIBLE);
                bgExec.execute(() -> {
                    Bitmap bm = decodeWithRotation(path);
                    ui.post(() -> {
                        if (!path.equals(currentPath)) return;
                        progress.setVisibility(View.GONE);
                        if (bm != null) photo.setImageBitmap(bm);
                    });
                });
            }
        }
    }

    // --- Bitmap decoding ---

    @Nullable
    private static Bitmap decodeWithRotation(String path) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path, o);
        int sample = 1;
        while ((o.outWidth / sample) > 1600 || (o.outHeight / sample) > 1600) sample *= 2;
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
        } catch (IOException ignore) { /* leave original orientation */ }
        return bm;
    }
}
