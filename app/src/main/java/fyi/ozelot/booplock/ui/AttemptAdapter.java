package fyi.ozelot.booplock.ui;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import fyi.ozelot.booplock.R;
import fyi.ozelot.booplock.data.AttemptRecord;

/**
 * History list adapter. Thumbnails are loaded off-thread to keep scrolling smooth.
 * For 100–200 entries (the typical scale) Glide/Picasso is unnecessary —
 * a simple executor plus a cached bitmap field in ViewHolder is enough.
 */
public class AttemptAdapter extends RecyclerView.Adapter<AttemptAdapter.VH> {

    public interface OnRecordClick {
        void onClick(AttemptRecord record);
    }

    private final List<AttemptRecord> items = new ArrayList<>();
    private final OnRecordClick clickListener;

    private final ExecutorService bgExec = Executors.newFixedThreadPool(2);
    private final Handler ui = new Handler(Looper.getMainLooper());

    private static final SimpleDateFormat DATE_FMT =
            new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());

    public AttemptAdapter(OnRecordClick listener) {
        this.clickListener = listener;
        setHasStableIds(true);
    }

    public void submitList(List<AttemptRecord> records) {
        items.clear();
        items.addAll(records);
        notifyDataSetChanged();
    }

    @Override
    public long getItemId(int position) {
        return items.get(position).id;
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_attempt, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH holder, int position) {
        AttemptRecord r = items.get(position);
        holder.bind(r);
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    class VH extends RecyclerView.ViewHolder {
        final ImageView thumb;
        final TextView dateText;
        final TextView countText;
        long currentId = -1;

        VH(@NonNull View itemView) {
            super(itemView);
            thumb = itemView.findViewById(R.id.thumb);
            dateText = itemView.findViewById(R.id.date_text);
            countText = itemView.findViewById(R.id.count_text);
        }

        void bind(AttemptRecord r) {
            currentId = r.id;
            dateText.setText(DATE_FMT.format(new Date(r.timestampMs)));
            countText.setText(itemView.getResources().getQuantityString(
                    R.plurals.failed_attempts, r.failedCount, r.failedCount));
            thumb.setImageResource(R.drawable.ic_thumb_placeholder);
            itemView.setOnClickListener(v -> {
                if (clickListener != null) clickListener.onClick(r);
            });

            if (r.photoPath == null) return;
            final long bindId = r.id;
            final String path = r.photoPath;
            bgExec.execute(() -> {
                Bitmap bm = decodeThumb(path, 200, 200);
                ui.post(() -> {
                    if (currentId == bindId && bm != null) {
                        thumb.setImageBitmap(bm);
                    }
                });
            });
        }
    }

    /** Sampled decoder — protects memory; a 4K thumbnail is not needed. */
    static Bitmap decodeThumb(String path, int reqW, int reqH) {
        File f = new File(path);
        if (!f.exists()) return null;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path, o);
        int sample = 1;
        while ((o.outWidth / sample) > reqW * 2 && (o.outHeight / sample) > reqH * 2) {
            sample *= 2;
        }
        BitmapFactory.Options o2 = new BitmapFactory.Options();
        o2.inSampleSize = sample;
        return BitmapFactory.decodeFile(path, o2);
    }
}
