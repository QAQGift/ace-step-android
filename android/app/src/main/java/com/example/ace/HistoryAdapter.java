package com.example.ace;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Adapter for the generation history list. */
public class HistoryAdapter extends RecyclerView.Adapter<HistoryAdapter.VH> {

    public interface Listener {
        void onPlay(HistoryRecord r);
        void onDetails(HistoryRecord r);
        void onDelete(HistoryRecord r);
    }

    private final List<HistoryRecord> data;
    private final Listener listener;
    private final SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault());

    public HistoryAdapter(List<HistoryRecord> data, Listener listener) {
        this.data = data;
        this.listener = listener;
    }

    public static class VH extends RecyclerView.ViewHolder {
        TextView hTime, hStatus, hCaption, hMeta;
        Button hPlay, hDetail, hDelete;

        public VH(@NonNull View v) {
            super(v);
            hTime = v.findViewById(R.id.hTime);
            hStatus = v.findViewById(R.id.hStatus);
            hCaption = v.findViewById(R.id.hCaption);
            hMeta = v.findViewById(R.id.hMeta);
            hPlay = v.findViewById(R.id.hPlay);
            hDetail = v.findViewById(R.id.hDetail);
            hDelete = v.findViewById(R.id.hDelete);
        }
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_history, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int pos) {
        HistoryRecord r = data.get(pos);

        h.hTime.setText(fmt.format(new Date(r.startMs)));

        if (r.success) {
            h.hStatus.setText("SUCCESS");
            h.hStatus.setTextColor(h.itemView.getContext().getColor(R.color.success));
            h.hStatus.setBackgroundResource(R.color.success_bg);
        } else {
            h.hStatus.setText("FAILED");
            h.hStatus.setTextColor(h.itemView.getContext().getColor(R.color.failed));
            h.hStatus.setBackgroundResource(R.color.failed_bg);
        }

        h.hCaption.setText(r.caption != null && !r.caption.isEmpty() ? r.caption : "(no prompt)");

        String duration = formatDuration(r.duration);
        String total = formatMs(r.totalMs);
        String meta = duration + " audio · " + total + " total";
        if (r.success && r.fileSize > 0) {
            meta += " · " + String.format(Locale.getDefault(), "%.1f MB audio",
                    r.fileSize / (1024.0 * 1024.0));
        } else if (!r.success && r.error != null && !r.error.isEmpty()) {
            meta += " · " + (r.error.length() > 40 ? r.error.substring(0, 40) + "…" : r.error);
        }
        h.hMeta.setText(meta);

        h.hPlay.setEnabled(r.success);
        h.hPlay.setAlpha(r.success ? 1.0f : 0.4f);
        h.hPlay.setOnClickListener(v -> listener.onPlay(r));
        h.hDetail.setOnClickListener(v -> listener.onDetails(r));
        h.hDelete.setOnClickListener(v -> listener.onDelete(r));
    }

    @Override
    public int getItemCount() {
        return data.size();
    }

    private static String formatMs(long ms) {
        if (ms <= 0) return "—";
        long s = ms / 1000;
        if (s < 60) return s + "s";
        return (s / 60) + "m " + (s % 60) + "s";
    }

    private static String formatDuration(int sec) {
        if (sec <= 0) return "—";
        return sec + "s";
    }
}
