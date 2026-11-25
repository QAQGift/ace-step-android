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

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Foreground service that runs the native generation pipeline off the UI thread. */
public class GenerationService extends Service {

    private static final String TAG = "AceGen";
    private static final String CHANNEL_ID = "ace_generation";

    public static final String ACTION_START = "com.example.ace.START";
    public static final String ACTION_CANCEL = "com.example.ace.CANCEL";
    public static final String EXTRA_MODELS_DIR = "models_dir";
    public static final String EXTRA_CAPTION = "caption";
    public static final String EXTRA_LYRICS = "lyrics";
    public static final String EXTRA_DURATION = "duration";
    public static final String EXTRA_OUTPUT = "output";
    public static final String EXTRA_SEED = "seed";

    public static final String BROADCAST_PROGRESS = "com.example.ace.PROGRESS";
    public static final String BROADCAST_DONE = "com.example.ace.DONE";
    public static final String BROADCAST_ERROR = "com.example.ace.ERROR";
    public static final String EXTRA_PROGRESS = "progress";
    public static final String EXTRA_STAGE = "stage";
    public static final String EXTRA_MESSAGE = "message";
    // Detailed metadata carried back to the UI for history records.
    public static final String EXTRA_START_MS = "start_ms";
    public static final String EXTRA_END_MS = "end_ms";
    public static final String EXTRA_TOTAL_MS = "total_ms";
    public static final String EXTRA_LM_MS = "lm_ms";
    public static final String EXTRA_SYNTH_MS = "synth_ms";
    public static final String EXTRA_FILE_SIZE = "file_size";
    public static final String EXTRA_SUCCESS = "success";
    public static final String EXTRA_VAE_FILE = "vae_file";
    public static final String EXTRA_PLANNER_CAPTION = "planner_caption";
    public static final String EXTRA_PLANNER_META = "planner_meta";
    public static final String EXTRA_CODES_COUNT = "codes_count";
    public static final String EXTRA_BPM = "bpm";
    public static final String EXTRA_KEY = "key";
    public static final String EXTRA_TIMESIG = "timesig";

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private volatile boolean cancelled = false;
    private volatile long nativeHandle = 0;

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
            if (nativeHandle != 0) AceEngine.cancel(nativeHandle);
            return START_NOT_STICKY;
        }
        if (!ACTION_START.equals(action)) return START_NOT_STICKY;

        final String modelsDir = intent.getStringExtra(EXTRA_MODELS_DIR);
        final String caption = intent.getStringExtra(EXTRA_CAPTION);
        final String lyrics = intent.getStringExtra(EXTRA_LYRICS);
        final int duration = intent.getIntExtra(EXTRA_DURATION, 30);
        final String output = intent.getStringExtra(EXTRA_OUTPUT);
        final int seed = intent.getIntExtra(EXTRA_SEED, -1);
        final int bpm = intent.getIntExtra(EXTRA_BPM, 0);
        final String key = intent.getStringExtra(EXTRA_KEY);
        final int timesig = intent.getIntExtra(EXTRA_TIMESIG, 0);

        cancelled = false;
        startForeground(1, buildNotification("Generating...", 0));
        executor.execute(() -> runGeneration(modelsDir, caption, lyrics, duration, output,
                                              seed, bpm, key, timesig));
        return START_NOT_STICKY;
    }

    // Verified stable configuration (no diagnostic switches).
    private static final boolean FLASH_ATTN = true;
    private static final String VAE_FILE = "vae-BF16.gguf";
    private static final String DIT_FILE = "acestep-v15-turbo-Q8_0.gguf";
    private static final boolean KEEP_CAPTION = true;

    private void runGeneration(String modelsDir, String caption, String lyrics,
                               int duration, String output, int seed,
                               int bpm, String key, int timesig) {
        long startMs = System.currentTimeMillis();
        long lmMs = 0;
        long synthMs = 0;
        String plannerCaption = "";
        String plannerMeta = "";
        int codesCount = 0;
        try {
            if (modelsDir == null || !new File(modelsDir).exists()) {
                throw new IllegalArgumentException("Model directory not found: " + modelsDir);
            }

            publishProgress(0, 0, "Loading engine...");
            AceEngine.setFlashAttn(FLASH_ATTN);
            nativeHandle = AceEngine.init(modelsDir, true);
            if (nativeHandle == 0) {
                throw new RuntimeException("Native engine init failed. Check logcat (tag AceJNI).");
            }
            AceEngine.setVae(nativeHandle, VAE_FILE);
            AceEngine.setDit(nativeHandle, DIT_FILE);
            if (cancelled) return;

            String requestJson = buildRequestJson(caption, lyrics, duration, seed, bpm, key, timesig);
            publishProgress(1, 5, "Planning lyrics and structure...");
            long lmStart = System.currentTimeMillis();
            String enriched = AceEngine.lm(nativeHandle, requestJson);
            lmMs = System.currentTimeMillis() - lmStart;
            if (enriched != null) {
                try {
                    org.json.JSONObject p = new org.json.JSONObject(enriched);
                    plannerCaption = p.optString("caption", "");
                    StringBuilder sb = new StringBuilder();
                    String[] keys = {"bpm", "keyscale", "timesignature", "vocal_language", "duration"};
                    for (String k : keys) {
                        if (p.has(k)) sb.append(k).append('=').append(p.opt(k)).append(' ');
                    }
                    plannerMeta = sb.toString().trim();
                    String codes = p.optString("audio_codes", "");
                    if (!codes.isEmpty()) {
                        for (int i = 0; i < codes.length(); i++) if (codes.charAt(i) == ',') codesCount++;
                        if (codesCount > 0 || !codes.isEmpty()) codesCount++;
                    }
                    Log.i(TAG, "planner caption=" + plannerCaption + " | " + plannerMeta
                            + " | codes=" + codesCount);
                } catch (Exception e) {
                    Log.w(TAG, "planner json parse failed", e);
                }
            }
            if (enriched == null) {
                throw new RuntimeException("LM stage failed. Check logcat (tag AceJNI).");
            }
            if (cancelled) return;

            publishProgress(2, 15, "Generating audio (DiT + VAE)...");
            long synthStart = System.currentTimeMillis();
            String result = AceEngine.synth(nativeHandle, enriched, output);
            synthMs = System.currentTimeMillis() - synthStart;
            if (result == null) {
                throw new RuntimeException("Synth stage failed. Check logcat (tag AceJNI).");
            }

            long endMs = System.currentTimeMillis();
            long fileSize = new File(output).length();
            publishProgress(4, 100, "Done: " + result);
            broadcastDone(result, startMs, endMs, lmMs, synthMs, fileSize, seed,
                          plannerCaption, plannerMeta, codesCount);
        } catch (Exception e) {
            Log.e(TAG, "Generation failed", e);
            long endMs = System.currentTimeMillis();
            broadcastError(e.getMessage() != null ? e.getMessage() : e.toString(),
                    startMs, endMs, lmMs, synthMs, seed,
                    plannerCaption, plannerMeta, codesCount);
        } finally {
            if (nativeHandle != 0) {
                AceEngine.release(nativeHandle);
                nativeHandle = 0;
            }
            stopForeground(true);
            stopSelf();
        }
    }

    /** Detect a language tag written in the lyrics, e.g. [zh] or [en]. */
    private static String detectLanguage(String lyrics) {
        if (lyrics == null) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(?im)^\\s*\\[(zh|en|ja|ko|fr|de|es|it|pt|ru|th|vi|id|ar|hi)\\s*\\]")
                .matcher(lyrics);
        return m.find() ? m.group(1).toLowerCase(java.util.Locale.ROOT) : null;
    }

    private String buildRequestJson(String caption, String lyrics, int duration, int seed,
                                    int bpm, String key, int timesig) throws JSONException {
        String cleanLyrics = lyrics == null ? "" : lyrics.trim();
        // ACE-Step treats "[Instrumental]" (exact match) as the instrumental-mode
        // signal. The old default pre-filled that tag, so users appended their
        // own lyrics AFTER it -> "[Instrumental][verse]..." which is a
        // contradictory signal: the LM pushes vocals to the end and suppresses
        // them. Strip a leading "[Instrumental]" whenever real lyrics follow.
        final String instrumental = "[Instrumental]";
        if (cleanLyrics.startsWith(instrumental) && cleanLyrics.length() > instrumental.length()) {
            String rest = cleanLyrics.substring(instrumental.length()).trim();
            if (!rest.isEmpty()) {
                cleanLyrics = rest;
            }
        }
        JSONObject o = new JSONObject();
        o.put("caption", caption == null ? "" : caption);
        o.put("lyrics", cleanLyrics.isEmpty() ? "[Instrumental]" : cleanLyrics);
        // Pin the vocal language when the user's lyrics carry an explicit tag.
        // Otherwise the planner guesses (we saw it pick "ar" for Chinese lyrics),
        // which changes singing style and can make vocals sound wrong.
        String lang = detectLanguage(cleanLyrics);
        if (lang != null) o.put("vocal_language", lang);
        if (bpm > 0) o.put("bpm", bpm);
        if (key != null && !key.isEmpty()) o.put("keyscale", key);
        if (timesig > 0) o.put("timesignature", String.valueOf(timesig));
        o.put("duration", duration);
        o.put("output_format", "wav16");
        o.put("lm_temperature", 0.85);
        o.put("use_cot_caption", !KEEP_CAPTION);
        o.put("seed", seed);
        return o.toString();
    }

    private void publishProgress(int stage, int progress, String message) {
        Intent i = new Intent(BROADCAST_PROGRESS);
        i.putExtra(EXTRA_STAGE, stage);
        i.putExtra(EXTRA_PROGRESS, progress);
        i.putExtra(EXTRA_MESSAGE, message);
        LocalBroadcastManager.getInstance(this).sendBroadcast(i);
    }

    private void broadcastDone(String path, long startMs, long endMs,
                               long lmMs, long synthMs, long fileSize,
                               int seed, String plannerCaption, String plannerMeta, int codesCount) {
        Intent i = new Intent(BROADCAST_DONE);
        i.putExtra(EXTRA_MESSAGE, path);
        i.putExtra(EXTRA_START_MS, startMs);
        i.putExtra(EXTRA_END_MS, endMs);
        i.putExtra(EXTRA_TOTAL_MS, endMs - startMs);
        i.putExtra(EXTRA_LM_MS, lmMs);
        i.putExtra(EXTRA_SYNTH_MS, synthMs);
        i.putExtra(EXTRA_FILE_SIZE, fileSize);
        i.putExtra(EXTRA_SEED, (long) seed);
        i.putExtra(EXTRA_VAE_FILE, VAE_FILE);
        i.putExtra(EXTRA_PLANNER_CAPTION, plannerCaption == null ? "" : plannerCaption);
        i.putExtra(EXTRA_PLANNER_META, plannerMeta == null ? "" : plannerMeta);
        i.putExtra(EXTRA_CODES_COUNT, codesCount);
        i.putExtra(EXTRA_SUCCESS, true);
        LocalBroadcastManager.getInstance(this).sendBroadcast(i);
    }

    private void broadcastError(String message, long startMs, long endMs,
                                long lmMs, long synthMs, int seed,
                                String plannerCaption, String plannerMeta, int codesCount) {
        Intent i = new Intent(BROADCAST_ERROR);
        i.putExtra(EXTRA_MESSAGE, message);
        i.putExtra(EXTRA_START_MS, startMs);
        i.putExtra(EXTRA_END_MS, endMs);
        i.putExtra(EXTRA_TOTAL_MS, endMs - startMs);
        i.putExtra(EXTRA_LM_MS, lmMs);
        i.putExtra(EXTRA_SYNTH_MS, synthMs);
        i.putExtra(EXTRA_SEED, (long) seed);
        i.putExtra(EXTRA_VAE_FILE, VAE_FILE);
        i.putExtra(EXTRA_PLANNER_CAPTION, plannerCaption == null ? "" : plannerCaption);
        i.putExtra(EXTRA_PLANNER_META, plannerMeta == null ? "" : plannerMeta);
        i.putExtra(EXTRA_CODES_COUNT, codesCount);
        i.putExtra(EXTRA_SUCCESS, false);
        LocalBroadcastManager.getInstance(this).sendBroadcast(i);
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "Music generation", NotificationManager.IMPORTANCE_LOW);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }

    private Notification buildNotification(String text, int progress) {
        Intent open = new Intent(this, MainActivity.class);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, flags);
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            b = new Notification.Builder(this, CHANNEL_ID);
        } else {
            b = new Notification.Builder(this);
        }
        return b.setContentTitle("ACE-Step")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_media_play)
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
