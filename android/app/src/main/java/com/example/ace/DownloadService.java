package com.example.ace;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Downloads GGUF models from hf-mirror with resume support. */
public class DownloadService extends Service {

    private static final String TAG = "AceDownload";
    private static final String CHANNEL_ID = "ace_download";
    private static final String BASE_URL =
            "https://hf-mirror.com/Serveurperso/ACE-Step-1.5-GGUF/resolve/main/";

    public static final String ACTION_START = "com.example.ace.DOWNLOAD_START";
    public static final String ACTION_CANCEL = "com.example.ace.DOWNLOAD_CANCEL";
    public static final String EXTRA_DIR = "dir";
    // Optional: name of a single extra model to download (e.g. DiT Q4_K_M).
    // When absent, the 4 base models are downloaded.
    public static final String EXTRA_SINGLE_FILE = "single_file";

    public static final String BROADCAST_PROGRESS = "com.example.ace.DL_PROGRESS";
    public static final String BROADCAST_DONE = "com.example.ace.DL_DONE";
    public static final String BROADCAST_ERROR = "com.example.ace.DL_ERROR";
    public static final String EXTRA_FILE = "file";
    public static final String EXTRA_INDEX = "index";
    public static final String EXTRA_TOTAL = "total";
    public static final String EXTRA_PROGRESS = "progress";
    public static final String EXTRA_MESSAGE = "message";

    private static final String[] FILES = {
            "vae-BF16.gguf",
            "Qwen3-Embedding-0.6B-Q8_0.gguf",
            "acestep-5Hz-lm-1.7B-Q8_0.gguf",
            "acestep-v15-turbo-Q8_0.gguf"
    };

    private static final long[] EXPECTED_SIZES = {
            337420928L,
            784144960L,
            1975837568L,
            2549528000L
    };

    // Official optional quantizations from Serveurperso/ACE-Step-1.5-GGUF.
    // Exact byte sizes from the repository file listing.
    private static final String[] OPTIONAL_FILES = {
            "acestep-v15-turbo-Q4_K_M.gguf",   // 1.4 GB
            "acestep-v15-turbo-Q5_K_M.gguf",   // 1.6 GB
            "acestep-v15-turbo-Q6_K.gguf",     // 1.9 GB
            "acestep-5Hz-lm-0.6B-Q8_0.gguf"    // 0.7 GB
    };
    private static final long[] OPTIONAL_SIZES = {
            1445710272L,
            1700140224L,
            1970472064L,
            709846656L
    };

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private volatile boolean cancelled = false;

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;
        String action = intent.getAction();
        if (ACTION_CANCEL.equals(action)) {
            cancelled = true;
            return START_NOT_STICKY;
        }
        if (!ACTION_START.equals(action)) {
            return START_NOT_STICKY;
        }

        final String dir = intent.getStringExtra(EXTRA_DIR);
        if (dir == null) return START_NOT_STICKY;
        final String singleFile = intent.getStringExtra(EXTRA_SINGLE_FILE);

        cancelled = false;
        startForeground(2, buildNotification("Preparing download...", 0));
        if (singleFile != null && !singleFile.isEmpty()) {
            executor.execute(() -> downloadSingle(dir, singleFile));
        } else {
            executor.execute(() -> downloadAll(dir));
        }
        return START_NOT_STICKY;
    }

    /** Download one optional GGUF, reusing the same validated transfer path. */
    private void downloadSingle(String dir, String name) {
        File dirFile = new File(dir);
        if (!dirFile.exists() && !dirFile.mkdirs()) {
            broadcastError("Cannot create directory: " + dir);
            finish();
            return;
        }
        long expected = -1;
        for (int i = 0; i < OPTIONAL_FILES.length; i++) {
            if (OPTIONAL_FILES[i].equals(name)) { expected = OPTIONAL_SIZES[i]; break; }
        }
        if (expected < 0) {
            broadcastError("Unknown model file: " + name);
            finish();
            return;
        }
        File target = new File(dirFile, name);
        if (target.exists() && target.length() == expected) {
            broadcastProgress(0, name, 100, "Already downloaded: " + name);
        } else if (!downloadFile(name, target, expected, 0)) {
            if (!cancelled) broadcastError("Failed to download " + name + ". Check logcat.");
            finish();
            return;
        }
        if (!cancelled) {
            Intent done = new Intent(BROADCAST_DONE);
            done.putExtra(EXTRA_SINGLE_FILE, name);
            LocalBroadcastManager.getInstance(this).sendBroadcast(done);
        }
        finish();
    }

    private void downloadAll(String dir) {
        File dirFile = new File(dir);
        if (!dirFile.exists() && !dirFile.mkdirs()) {
            broadcastError("Cannot create directory: " + dir);
            finish();
            return;
        }

        for (int i = 0; i < FILES.length; i++) {
            if (cancelled) break;
            String name = FILES[i];
            File target = new File(dirFile, name);
            long expected = EXPECTED_SIZES[i];

            if (target.exists() && target.length() == expected) {
                broadcastProgress(i, name, 100, "Already downloaded: " + name);
                continue;
            }

            boolean ok = downloadFile(name, target, expected, i);
            if (!ok) {
                if (!cancelled) broadcastError("Failed to download " + name + ". Check logcat.");
                finish();
                return;
            }
        }

        if (!cancelled) {
            Intent done = new Intent(BROADCAST_DONE);
            LocalBroadcastManager.getInstance(this).sendBroadcast(done);
        }
        finish();
    }

    private boolean downloadFile(String name, File target, long expected, int index) {
        long existing = target.exists() ? target.length() : 0;
        if (existing >= expected) {
            // Corrupt or wrong size: restart from scratch.
            if (!target.delete()) {
                Log.w(TAG, "Cannot delete corrupt file " + name);
            }
            existing = 0;
        }

        HttpURLConnection conn = null;
        try {
            URL url = new URL(BASE_URL + name);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(20000);
            conn.setReadTimeout(30000);
            conn.setInstanceFollowRedirects(true);
            if (existing > 0) {
                conn.setRequestProperty("Range", "bytes=" + existing + "-");
            }
            conn.connect();

            int code = conn.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK && code != HttpURLConnection.HTTP_PARTIAL) {
                Log.e(TAG, "HTTP " + code + " for " + name);
                return false;
            }

            boolean append = (code == HttpURLConnection.HTTP_PARTIAL && existing > 0);
            long contentLength = conn.getContentLengthLong() + (append ? existing : 0);
            if (contentLength <= 0) contentLength = expected;

            try (InputStream in = conn.getInputStream();
                 FileOutputStream out = new FileOutputStream(target, append)) {
                byte[] buf = new byte[64 * 1024];
                long total = existing;
                int n;
                long lastReport = 0;
                while ((n = in.read(buf)) != -1) {
                    if (cancelled) return false;
                    out.write(buf, 0, n);
                    total += n;
                    long now = System.currentTimeMillis();
                    if (now - lastReport > 300) {
                        int pct = (int) (total * 100 / contentLength);
                        String msg = name + "  " + formatMB(total) + " / " + formatMB(contentLength);
                        broadcastProgress(index, name, pct, msg);
                        lastReport = now;
                    }
                }
                out.flush();
            }

            if (target.length() != expected) {
                Log.e(TAG, "Size mismatch for " + name + ": " + target.length() + " != " + expected);
                return false;
            }
            broadcastProgress(index, name, 100, "Downloaded: " + name);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Download failed: " + name, e);
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static String formatMB(long bytes) {
        return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
    }

    private void broadcastProgress(int index, String name, int pct, String msg) {
        Intent i = new Intent(BROADCAST_PROGRESS);
        i.putExtra(EXTRA_INDEX, index);
        i.putExtra(EXTRA_TOTAL, FILES.length);
        i.putExtra(EXTRA_FILE, name);
        i.putExtra(EXTRA_PROGRESS, pct);
        i.putExtra(EXTRA_MESSAGE, msg);
        LocalBroadcastManager.getInstance(this).sendBroadcast(i);
    }

    private void broadcastError(String msg) {
        Intent i = new Intent(BROADCAST_ERROR);
        i.putExtra(EXTRA_MESSAGE, msg);
        LocalBroadcastManager.getInstance(this).sendBroadcast(i);
    }

    private void finish() {
        stopForeground(true);
        stopSelf();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "Model download", NotificationManager.IMPORTANCE_LOW);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }

    private Notification buildNotification(String text, int progress) {
        Intent open = new Intent(this, MainActivity.class);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, flags);
        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        return b.setContentTitle("ACE-Step model download")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }
}
