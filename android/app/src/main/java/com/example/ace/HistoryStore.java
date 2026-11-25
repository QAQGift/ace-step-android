package com.example.ace;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** Persists generation history to a JSON file in app-private storage. */
public class HistoryStore {
    private static final String TAG = "AceHistory";
    private static final String FILE_NAME = "generation_history.json";
    private static final int MAX_RECORDS = 100;

    private final File file;
    private final List<HistoryRecord> records = new ArrayList<>();

    public HistoryStore(Context ctx) {
        File dir = ctx.getExternalFilesDir(null);
        file = new File(dir != null ? dir : ctx.getFilesDir(), FILE_NAME);
        load();
    }

    public List<HistoryRecord> getAll() {
        return records;
    }

    public int size() {
        return records.size();
    }

    /** Add a new record at the top; keep at most MAX_RECORDS. */
    public void add(HistoryRecord r) {
        records.add(0, r);
        trim();
        save();
    }

    public void remove(long id) {
        records.removeIf(r -> r.id == id);
        save();
    }

    public void clear() {
        records.clear();
        save();
    }

    private void trim() {
        while (records.size() > MAX_RECORDS) {
            records.remove(records.size() - 1);
        }
    }

    private void load() {
        records.clear();
        if (!file.exists()) return;
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            JSONArray arr = new JSONArray(sb.toString());
            for (int i = 0; i < arr.length(); i++) {
                HistoryRecord r = HistoryRecord.fromJson(arr.optJSONObject(i));
                if (r != null) records.add(r);
            }
            sort();
        } catch (Exception e) {
            Log.w(TAG, "Failed to load history: " + e.getMessage());
        }
    }

    private void save() {
        sort();
        JSONArray arr = new JSONArray();
        for (HistoryRecord r : records) {
            try {
                arr.put(r.toJson());
            } catch (Exception ignored) {
            }
        }
        try (FileWriter fw = new FileWriter(file, false)) {
            fw.write(arr.toString());
        } catch (IOException e) {
            Log.w(TAG, "Failed to save history: " + e.getMessage());
        }
    }

    private void sort() {
        Collections.sort(records, Comparator.comparingLong((HistoryRecord r) -> r.startMs).reversed());
    }
}
