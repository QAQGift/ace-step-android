package com.example.ace;

import android.app.ActivityManager;
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
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
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
    public static final String EXTRA_VAE_CHUNK = "vae_chunk";
    public static final String EXTRA_VAE_OVERLAP = "vae_overlap";
    public static final String EXTRA_KV_SEQ = "kv_seq";
    public static final String EXTRA_THREADS = "threads";
    public static final String EXTRA_CACHE_TIER = "cache_tier";
    public static final String EXTRA_FLASH_ATTN = "flash_attn";
    public static final String EXTRA_OUTPUT_FORMAT = "output_format";
    public static final String EXTRA_LM_FILE = "lm_file";
    public static final String EXTRA_TEXTENC_FILE = "textenc_file";
    public static final String EXTRA_DIT_FILE = "dit_file";
    public static final String EXTRA_VAE_FILE_SEL = "vae_file_sel";

    // Generation (content/sampling) parameters. These are distinct from the
    // performance tuning extras above and are recorded separately in the manifest.
    public static final String EXTRA_VOCAL_LANGUAGE = "vocal_language";
    public static final String EXTRA_USE_COT_CAPTION = "use_cot_caption";
    public static final String EXTRA_LM_TEMPERATURE = "lm_temperature";
    public static final String EXTRA_LM_TOP_P = "lm_top_p";
    public static final String EXTRA_LM_TOP_K = "lm_top_k";
    public static final String EXTRA_LM_CFG_SCALE = "lm_cfg_scale";
    public static final String EXTRA_LM_NEGATIVE_PROMPT = "lm_negative_prompt";
    public static final String EXTRA_INFERENCE_STEPS = "inference_steps";
    public static final String EXTRA_GUIDANCE_SCALE = "guidance_scale";
    public static final String EXTRA_SHIFT = "shift";
    public static final String EXTRA_SOLVER = "solver";

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
    public static final String EXTRA_VAE_TILE = "vae_tile";
    public static final String EXTRA_CACHE_MODE = "cache_mode";
    public static final String EXTRA_MANIFEST_PATH = "manifest_path";
    public static final String EXTRA_MANIFEST_JSON = "manifest_json";

    /** Generation-side knobs, kept separate from CPU/cache tuning knobs. */
    private static final class GenOptions {
        String vocalLanguage = "";
        boolean useCotCaption = true;    // official default: LM enriches the caption
        float lmTemperature = 0.85f;
        float lmTopP = 0.90f;
        int lmTopK = 0;
        float lmCfgScale = 2.0f;
        String lmNegativePrompt = "";
        int inferenceSteps = 0;          // 0 = auto from model
        float guidanceScale = 0.0f;      // 0 = auto
        float shift = 0.0f;              // 0 = auto
        String solver = "euler";

        static GenOptions from(Intent i) {
            GenOptions g = new GenOptions();
            String v = i.getStringExtra(EXTRA_VOCAL_LANGUAGE);
            if (v != null) g.vocalLanguage = v.trim();
            g.useCotCaption = i.getBooleanExtra(EXTRA_USE_COT_CAPTION, true);
            g.lmTemperature = i.getFloatExtra(EXTRA_LM_TEMPERATURE, g.lmTemperature);
            g.lmTopP = i.getFloatExtra(EXTRA_LM_TOP_P, g.lmTopP);
            g.lmTopK = i.getIntExtra(EXTRA_LM_TOP_K, g.lmTopK);
            g.lmCfgScale = i.getFloatExtra(EXTRA_LM_CFG_SCALE, g.lmCfgScale);
            String neg = i.getStringExtra(EXTRA_LM_NEGATIVE_PROMPT);
            if (neg != null) g.lmNegativePrompt = neg.trim();
            g.inferenceSteps = i.getIntExtra(EXTRA_INFERENCE_STEPS, g.inferenceSteps);
            g.guidanceScale = i.getFloatExtra(EXTRA_GUIDANCE_SCALE, g.guidanceScale);
            g.shift = i.getFloatExtra(EXTRA_SHIFT, g.shift);
            String solver = i.getStringExtra(EXTRA_SOLVER);
            if (solver != null && !solver.trim().isEmpty()) g.solver = solver.trim();
            return g;
        }

        JSONObject toJson() throws JSONException {
            JSONObject o = new JSONObject();
            o.put("vocal_language", vocalLanguage);
            o.put("use_cot_caption", useCotCaption);
            o.put("lm_temperature", lmTemperature);
            o.put("lm_top_p", lmTopP);
            o.put("lm_top_k", lmTopK);
            o.put("lm_cfg_scale", lmCfgScale);
            o.put("lm_negative_prompt", lmNegativePrompt);
            o.put("inference_steps", inferenceSteps);
            o.put("guidance_scale", guidanceScale);
            o.put("shift", shift);
            o.put("solver", solver);
            return o;
        }
    }

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
            long handle = AceEngine.currentHandle();
            if (handle != 0) AceEngine.cancel(handle);
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
        final int vaeChunk = intent.getIntExtra(EXTRA_VAE_CHUNK, 512);
        final int vaeOverlap = intent.getIntExtra(EXTRA_VAE_OVERLAP, 32);
        final int kvSeq = intent.getIntExtra(EXTRA_KV_SEQ, 4096);
        final int threads = intent.getIntExtra(EXTRA_THREADS, 0);
        final int cacheTier = intent.getIntExtra(EXTRA_CACHE_TIER, 0);
        final boolean flashAttn = intent.getBooleanExtra(EXTRA_FLASH_ATTN, true);
        final String outputFormat = intent.getStringExtra(EXTRA_OUTPUT_FORMAT);
        final String lmFile = intent.getStringExtra(EXTRA_LM_FILE);
        final String textEncFile = intent.getStringExtra(EXTRA_TEXTENC_FILE);
        final String ditFile = intent.getStringExtra(EXTRA_DIT_FILE);
        final String vaeFileSel = intent.getStringExtra(EXTRA_VAE_FILE_SEL);
        final GenOptions gen = GenOptions.from(intent);

        cancelled = false;
        startForeground(1, buildNotification("Generating...", 0));
        executor.execute(() -> runGeneration(modelsDir, caption, lyrics, duration, output,
                                              seed, bpm, key, timesig,
                                              vaeChunk, vaeOverlap, kvSeq, threads, cacheTier, flashAttn,
                                              outputFormat, lmFile, textEncFile, ditFile, vaeFileSel, gen));
        return START_NOT_STICKY;
    }

    // Default model files. Callers may override each one via intent extras,
    // provided the selected GGUF exists in the model directory.
    private static final String DEFAULT_VAE_FILE = "vae-BF16.gguf";
    private static final String DEFAULT_DIT_FILE = "acestep-v15-turbo-Q8_0.gguf";
    private static final String DEFAULT_LM_FILE = "acestep-5Hz-lm-1.7B-Q8_0.gguf";
    private static final String DEFAULT_TEXT_ENC_FILE = "Qwen3-Embedding-0.6B-Q8_0.gguf";
    private static final String DEFAULT_OUTPUT_FORMAT = "wav16";

    private int autoCacheTier() {
        ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
        if (am == null) return 1;
        ActivityManager.MemoryInfo info = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(info);
        long totalMb = info.totalMem / (1024L * 1024L);
        if (totalMb >= 10000L) return 3;
        if (totalMb >= 6000L) return 2;
        return 1;
    }

    private static long tierBudgetMb(int tier) {
        switch (tier) {
            case 3: return 8192L;
            case 2: return 5120L;
            case 1: return 2048L;
            default: return 0L;
        }
    }

    private void runGeneration(String modelsDir, String caption, String lyrics,
                               int duration, String output, int seed,
                               int bpm, String key, int timesig,
                               int vaeChunk, int vaeOverlap, int kvSeq,
                               int threads, int requestedTier, boolean flashAttn,
                               String outputFormat, String lmFileSel, String textEncFileSel,
                               String ditFileSel, String vaeFileSel, GenOptions gen) {
        // Resolve model files: fall back to defaults when the caller did not
        // supply one, and only accept an override when the file actually exists.
        File modelDirFile = new File(modelsDir == null ? "." : modelsDir);
        String lmFile = resolveModelFile(modelDirFile, lmFileSel, DEFAULT_LM_FILE);
        String textEncFile = resolveModelFile(modelDirFile, textEncFileSel, DEFAULT_TEXT_ENC_FILE);
        String ditFile = resolveModelFile(modelDirFile, ditFileSel, DEFAULT_DIT_FILE);
        String vaeFile = resolveModelFile(modelDirFile, vaeFileSel, DEFAULT_VAE_FILE);
        if (outputFormat == null || outputFormat.isEmpty()) outputFormat = DEFAULT_OUTPUT_FORMAT;

        long startMs = System.currentTimeMillis();
        long lmMs = 0;
        long synthMs = 0;
        long synthLoadMs = 0;
        long ditMs = 0;
        long vaeMs = 0;
        long audioWriteMs = 0;
        String plannerCaption = "";
        String plannerMeta = "";
        int codesCount = 0;
        int cacheTier = requestedTier > 0 ? requestedTier : 0;
        String requestJson = "";
        String enriched = "";
        String manifestPath = "";
        String manifestJson = "";
        try {
            if (modelsDir == null || !new File(modelsDir).exists()) {
                throw new IllegalArgumentException("Model directory not found: " + modelsDir);
            }

            publishProgress(0, 0, "Loading engine...");
            AceEngine.setFlashAttn(flashAttn);
            AceEngine.setThreads(threads);

            if (requestedTier <= 0) cacheTier = autoCacheTier();
            nativeHandle = AceEngine.ensure(modelsDir);
            if (nativeHandle == 0) {
                throw new RuntimeException("Native engine init failed. Check logcat (tag AceJNI).");
            }
            AceEngine.setCachePolicy(nativeHandle, 1, tierBudgetMb(cacheTier));
            AceEngine.setVae(nativeHandle, vaeFile);
            AceEngine.setDit(nativeHandle, ditFile);
            AceEngine.setLm(nativeHandle, lmFile);
            AceEngine.setTextEnc(nativeHandle, textEncFile);
            AceEngine.setVaeTiling(nativeHandle, vaeChunk, vaeOverlap);
            AceEngine.setKvSeq(nativeHandle, kvSeq);
            Log.i(TAG, "tuning: tile=" + vaeChunk + "/" + vaeOverlap
                    + " kv=" + kvSeq + " threads=" + (threads > 0 ? threads : "auto")
                    + " cacheTier=" + cacheTier + " flashAttn=" + flashAttn
                    + " format=" + outputFormat);
            Log.i(TAG, "models: lm=" + lmFile + " textenc=" + textEncFile
                    + " dit=" + ditFile + " vae=" + vaeFile);
            Log.i(TAG, "generation: lang='" + gen.vocalLanguage + "' captionLock=" + !gen.useCotCaption
                    + " temp=" + gen.lmTemperature + " topP=" + gen.lmTopP + " topK=" + gen.lmTopK
                    + " lmCfg=" + gen.lmCfgScale + " steps=" + gen.inferenceSteps
                    + " guidance=" + gen.guidanceScale + " shift=" + gen.shift + " solver=" + gen.solver);
            if (cancelled) return;

            requestJson = buildRequestJson(caption, lyrics, duration, seed, bpm, key, timesig, outputFormat, gen);
            publishProgress(1, 5, "Planning lyrics and structure...");
            long lmStart = System.currentTimeMillis();
            enriched = AceEngine.lm(nativeHandle, requestJson);
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
            // Per-stage native timings: [load, dit, vae, write] ms.
            long[] stageMs = AceEngine.getSynthTimings(nativeHandle);
            if (stageMs != null && stageMs.length == 4) {
                synthLoadMs = stageMs[0];
                ditMs = stageMs[1];
                vaeMs = stageMs[2];
                audioWriteMs = stageMs[3];
                Log.i(TAG, "synth stages: load=" + synthLoadMs + "ms dit=" + ditMs
                        + "ms vae=" + vaeMs + "ms write=" + audioWriteMs + "ms");
            }

            long endMs = System.currentTimeMillis();
            long fileSize = new File(output).length();
            String[] manifest = writeManifest(output, modelsDir, requestJson, enriched,
                    caption, lyrics, duration, seed, bpm, key, timesig,
                    vaeChunk, vaeOverlap, kvSeq, threads, cacheTier, flashAttn, outputFormat,
                    lmFile, textEncFile, ditFile, vaeFile, gen,
                    startMs, endMs, lmMs, synthMs, synthLoadMs, ditMs, vaeMs,
                    audioWriteMs, fileSize, plannerCaption, plannerMeta, codesCount);
            manifestPath = manifest[0];
            manifestJson = manifest[1];
            publishProgress(4, 100, "Done: " + result);
            broadcastDone(result, startMs, endMs, lmMs, synthMs, fileSize, seed,
                          plannerCaption, plannerMeta, codesCount,
                          vaeChunk, vaeOverlap, kvSeq, cacheTier,
                          manifestPath, manifestJson, vaeFile);
        } catch (Exception e) {
            Log.e(TAG, "Generation failed", e);
            long endMs = System.currentTimeMillis();
            broadcastError(e.getMessage() != null ? e.getMessage() : e.toString(),
                    startMs, endMs, lmMs, synthMs, seed,
                    plannerCaption, plannerMeta, codesCount,
                    vaeChunk, vaeOverlap, kvSeq, cacheTier,
                    manifestPath, manifestJson, vaeFile);
        } finally {
            // Native engine stays resident on purpose. It is released only when
            // the process dies or the model directory changes.
            stopForeground(true);
            stopSelf();
        }
    }

    /**
     * Write a reproducibility manifest next to the generated WAV. The WAV
     * itself is left byte-identical to the official audio_write() output;
     * metadata goes into <output basename>.json so a track can be replayed
     * with the exact prompt, seed, tuning knobs and model set.
     */
    private String[] writeManifest(String output, String modelsDir,
                                   String requestJson, String enriched,
                                   String caption, String lyrics, int duration, int seed,
                                   int bpm, String key, int timesig,
                                   int vaeChunk, int vaeOverlap, int kvSeq, int threads,
                                   int cacheTier, boolean flashAttn, String outputFormat,
                                   String lmFile, String textEncFile, String ditFile, String vaeFile,
                                   GenOptions gen,
                                   long startMs, long endMs, long lmMs, long synthMs,
                                   long synthLoadMs, long ditMs, long vaeMs, long audioWriteMs,
                                   long fileSize, String plannerCaption, String plannerMeta,
                                   int codesCount) {
        try {
            JSONObject o = new JSONObject();
            o.put("schema", "ace-step-android/manifest-v1");
            o.put("createdMs", endMs);
            o.put("startedMs", startMs);

            JSONObject input = new JSONObject();
            input.put("caption", caption == null ? "" : caption);
            input.put("lyrics", lyrics == null ? "" : lyrics);
            input.put("duration", duration);
            input.put("seed", seed);
            input.put("bpm", bpm);
            input.put("keyscale", key == null ? "" : key);
            input.put("timesignature", timesig);
            input.put("requestJson", requestJson == null ? "" : requestJson);
            o.put("input", input);
            o.put("generation", gen.toJson());

            boolean turbo = ditFile != null && ditFile.contains("turbo");
            int resolvedSteps = gen.inferenceSteps > 0 ? gen.inferenceSteps : (turbo ? 8 : 50);
            float resolvedGuidance = gen.guidanceScale > 0 ? gen.guidanceScale : 1.0f;
            float resolvedShift = gen.shift > 0 ? gen.shift : (turbo ? 3.0f : 1.0f);

            JSONObject tuning = new JSONObject();
            tuning.put("vae_chunk", vaeChunk);
            tuning.put("vae_overlap", vaeOverlap);
            tuning.put("kv_seq", kvSeq);
            tuning.put("kv_fallback_seq", 8192);
            tuning.put("threads", threads);  // 0 = hardware_concurrency()-2
            tuning.put("cache_tier", cacheTier);  // 0 auto, 1 low, 2 medium, 3 high
            tuning.put("cache_policy", "tiered");
            tuning.put("flash_attn", flashAttn);
            tuning.put("solver", gen.solver);
            tuning.put("inference_steps", resolvedSteps);
            tuning.put("guidance_scale", resolvedGuidance);
            tuning.put("shift", resolvedShift);
            tuning.put("output_format", outputFormat == null ? DEFAULT_OUTPUT_FORMAT : outputFormat);
            tuning.put("cot_caption", gen.useCotCaption);
            o.put("tuning", tuning);

            JSONObject models = new JSONObject();
            models.put("models_dir", modelsDir == null ? "" : modelsDir);
            models.put("lm", lmFile);
            models.put("text_encoder", textEncFile);
            models.put("dit", ditFile);
            models.put("vae", vaeFile);
            o.put("models", models);

            long resolvedSeed = seed;
            long lmSeed = -1;
            try {
                JSONObject e = new JSONObject(enriched == null ? "{}" : enriched);
                resolvedSeed = e.optLong("seed", seed);
                lmSeed = e.optLong("lm_seed", -1);
            } catch (Exception ignored) {
            }

            JSONObject result = new JSONObject();
            result.put("seed", seed);
            result.put("resolvedSeed", resolvedSeed);
            result.put("lmSeed", lmSeed);
            result.put("outputPath", output == null ? "" : output);
            result.put("fileSize", fileSize);
            result.put("totalMs", endMs - startMs);
            result.put("lmMs", lmMs);
            result.put("synthMs", synthMs);
            JSONObject stages = new JSONObject();
            stages.put("synthLoadMs", synthLoadMs);
            stages.put("ditMs", ditMs);
            stages.put("vaeMs", vaeMs);
            stages.put("audioWriteMs", audioWriteMs);
            result.put("stages", stages);
            result.put("plannerCaption", plannerCaption == null ? "" : plannerCaption);
            result.put("plannerMeta", plannerMeta == null ? "" : plannerMeta);
            result.put("codesCount", codesCount);
            result.put("enrichedRequest", enriched == null ? "" : enriched);
            o.put("result", result);

            JSONObject device = new JSONObject();
            device.put("android_release", Build.VERSION.RELEASE);
            device.put("sdk_int", Build.VERSION.SDK_INT);
            device.put("abis", String.join(",", Build.SUPPORTED_ABIS));
            device.put("logical_cpus", Runtime.getRuntime().availableProcessors());
            ActivityManager.MemoryInfo mem = new ActivityManager.MemoryInfo();
            ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
            if (am != null) {
                am.getMemoryInfo(mem);
                device.put("total_ram_mb", mem.totalMem / (1024L * 1024L));
            }
            try {
                device.put("app_version", getPackageManager()
                        .getPackageInfo(getPackageName(), 0).versionName);
            } catch (Exception ignored) {
            }
            o.put("device", device);

            // Use the resolved seed in the replay command: passing the original
            // -1 would generate a different random track instead of reproducing
            // this manifest.
            o.put("reproduce_adb_command", buildAdbCommand(modelsDir, caption, lyrics,
                    duration, resolvedSeed, bpm, key, timesig, output,
                    vaeChunk, vaeOverlap, kvSeq, threads, cacheTier, flashAttn,
                    outputFormat, lmFile, textEncFile, ditFile, vaeFile, gen));

            File wav = new File(output == null ? "output.wav" : output);
            String name = wav.getName();
            int dot = name.lastIndexOf('.');
            String base = dot > 0 ? name.substring(0, dot) : name;
            File manifest = new File(wav.getParentFile(), base + ".json");

            String json = o.toString(2);
            try (OutputStreamWriter w = new OutputStreamWriter(
                    new FileOutputStream(manifest), StandardCharsets.UTF_8)) {
                w.write(json);
            }
            Log.i(TAG, "manifest written: " + manifest.getAbsolutePath());
            return new String[] { manifest.getAbsolutePath(), json };
        } catch (Exception e) {
            Log.w(TAG, "failed to write manifest", e);
            return new String[] { "", "{}" };
        }
    }

    private static String shellQuote(String s) {
        return "'" + (s == null ? "" : s.replace("'", "'\\''")) + "'";
    }

    private static String buildAdbCommand(String modelsDir, String caption, String lyrics,
                                          int duration, long seed, int bpm, String key, int timesig,
                                          String output, int vaeChunk, int vaeOverlap, int kvSeq,
                                          int threads, int cacheTier, boolean flashAttn,
                                          String outputFormat, String lmFile, String textEncFile,
                                          String ditFile, String vaeFile, GenOptions gen) {
        StringBuilder b = new StringBuilder("adb shell am start -n com.example.ace/.MainActivity")
                .append(" -a com.example.ace.PERF_START")
                .append(" --es models_dir ").append(shellQuote(modelsDir))
                .append(" --es caption ").append(shellQuote(caption))
                .append(" --es lyrics ").append(shellQuote(lyrics))
                .append(" --ei duration ").append(duration)
                .append(" --ei seed ").append(seed)
                .append(" --ei bpm ").append(bpm)
                .append(" --es key ").append(shellQuote(key))
                .append(" --ei timesig ").append(timesig)
                .append(" --es output ").append(shellQuote(output))
                .append(" --ei vae_chunk ").append(vaeChunk)
                .append(" --ei vae_overlap ").append(vaeOverlap)
                .append(" --ei kv_seq ").append(kvSeq)
                .append(" --ei threads ").append(threads)
                .append(" --ei cache_tier ").append(cacheTier)
                .append(" --ez flash_attn ").append(flashAttn)
                .append(" --es output_format ").append(shellQuote(outputFormat))
                .append(" --es lm_file ").append(shellQuote(lmFile))
                .append(" --es textenc_file ").append(shellQuote(textEncFile))
                .append(" --es dit_file ").append(shellQuote(ditFile))
                .append(" --es vae_file_sel ").append(shellQuote(vaeFile))
                .append(" --es vocal_language ").append(shellQuote(gen.vocalLanguage))
                .append(" --ez use_cot_caption ").append(gen.useCotCaption)
                .append(" --ef lm_temperature ").append(gen.lmTemperature)
                .append(" --ef lm_top_p ").append(gen.lmTopP)
                .append(" --ei lm_top_k ").append(gen.lmTopK)
                .append(" --ef lm_cfg_scale ").append(gen.lmCfgScale)
                .append(" --es lm_negative_prompt ").append(shellQuote(gen.lmNegativePrompt))
                .append(" --ei inference_steps ").append(gen.inferenceSteps)
                .append(" --ef guidance_scale ").append(gen.guidanceScale)
                .append(" --ef shift ").append(gen.shift)
                .append(" --es solver ").append(shellQuote(gen.solver));
        return b.toString();
    }

    /** Accept a model-file override only if it exists; otherwise use the default. */
    private static String resolveModelFile(File modelDir, String selected, String fallback) {
        if (selected != null && !selected.trim().isEmpty()) {
            String name = selected.trim();
            File f = name.contains("/") ? new File(name) : new File(modelDir, name);
            if (f.exists() && f.isFile()) return name;
        }
        return fallback;
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
                                    int bpm, String key, int timesig, String outputFormat,
                                    GenOptions gen) throws JSONException {
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
        // Explicit UI selection wins; otherwise keep the legacy [zh] tag detection.
        String lang = (gen.vocalLanguage == null || gen.vocalLanguage.isEmpty())
                ? detectLanguage(cleanLyrics) : gen.vocalLanguage;
        if (lang != null && !lang.isEmpty()) o.put("vocal_language", lang);
        if (bpm > 0) o.put("bpm", bpm);
        if (key != null && !key.isEmpty()) o.put("keyscale", key);
        if (timesig > 0) o.put("timesignature", String.valueOf(timesig));
        o.put("duration", duration);
        o.put("output_format", outputFormat == null || outputFormat.isEmpty() ? DEFAULT_OUTPUT_FORMAT : outputFormat);

        o.put("lm_temperature", gen.lmTemperature);
        o.put("lm_top_p", gen.lmTopP);
        if (gen.lmTopK > 0) o.put("lm_top_k", gen.lmTopK);
        o.put("lm_cfg_scale", gen.lmCfgScale);
        if (!gen.lmNegativePrompt.isEmpty()) o.put("lm_negative_prompt", gen.lmNegativePrompt);
        o.put("use_cot_caption", gen.useCotCaption);
        if (gen.inferenceSteps > 0) o.put("inference_steps", gen.inferenceSteps);
        if (gen.guidanceScale > 0) o.put("guidance_scale", gen.guidanceScale);
        if (gen.shift > 0) o.put("shift", gen.shift);
        if (gen.solver != null && !gen.solver.isEmpty() && !"euler".equals(gen.solver)) {
            o.put("solver", gen.solver);
        }
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
                               int seed, String plannerCaption, String plannerMeta, int codesCount,
                               int vaeChunk, int vaeOverlap, int kvSeq, int cacheTier,
                               String manifestPath, String manifestJson, String vaeFile) {
        Intent i = new Intent(BROADCAST_DONE);
        i.putExtra(EXTRA_MESSAGE, path);
        i.putExtra(EXTRA_START_MS, startMs);
        i.putExtra(EXTRA_END_MS, endMs);
        i.putExtra(EXTRA_TOTAL_MS, endMs - startMs);
        i.putExtra(EXTRA_LM_MS, lmMs);
        i.putExtra(EXTRA_SYNTH_MS, synthMs);
        i.putExtra(EXTRA_FILE_SIZE, fileSize);
        i.putExtra(EXTRA_SEED, (long) seed);
        i.putExtra(EXTRA_VAE_FILE, vaeFile);
        i.putExtra(EXTRA_PLANNER_CAPTION, plannerCaption == null ? "" : plannerCaption);
        i.putExtra(EXTRA_PLANNER_META, plannerMeta == null ? "" : plannerMeta);
        i.putExtra(EXTRA_CODES_COUNT, codesCount);
        i.putExtra(EXTRA_VAE_TILE, vaeChunk + "/" + vaeOverlap);
        i.putExtra(EXTRA_CACHE_MODE, "kv=" + kvSeq + ",tier=" + cacheTier);
        i.putExtra(EXTRA_MANIFEST_PATH, manifestPath == null ? "" : manifestPath);
        i.putExtra(EXTRA_MANIFEST_JSON, manifestJson == null ? "" : manifestJson);
        i.putExtra(EXTRA_SUCCESS, true);
        Log.i("AceDone", "success path=" + path + " totalMs=" + (endMs - startMs)
                + " lmMs=" + lmMs + " synthMs=" + synthMs
                + " tile=" + vaeChunk + "/" + vaeOverlap + " kv=" + kvSeq + " tier=" + cacheTier);
        LocalBroadcastManager.getInstance(this).sendBroadcast(i);
    }

    private void broadcastError(String message, long startMs, long endMs,
                                long lmMs, long synthMs, int seed,
                                String plannerCaption, String plannerMeta, int codesCount,
                                int vaeChunk, int vaeOverlap, int kvSeq, int cacheTier,
                                String manifestPath, String manifestJson, String vaeFile) {
        Intent i = new Intent(BROADCAST_ERROR);
        i.putExtra(EXTRA_MESSAGE, message);
        i.putExtra(EXTRA_START_MS, startMs);
        i.putExtra(EXTRA_END_MS, endMs);
        i.putExtra(EXTRA_TOTAL_MS, endMs - startMs);
        i.putExtra(EXTRA_LM_MS, lmMs);
        i.putExtra(EXTRA_SYNTH_MS, synthMs);
        i.putExtra(EXTRA_SEED, (long) seed);
        i.putExtra(EXTRA_VAE_FILE, vaeFile);
        i.putExtra(EXTRA_PLANNER_CAPTION, plannerCaption == null ? "" : plannerCaption);
        i.putExtra(EXTRA_PLANNER_META, plannerMeta == null ? "" : plannerMeta);
        i.putExtra(EXTRA_CODES_COUNT, codesCount);
        i.putExtra(EXTRA_VAE_TILE, vaeChunk + "/" + vaeOverlap);
        i.putExtra(EXTRA_CACHE_MODE, "kv=" + kvSeq + ",tier=" + cacheTier);
        i.putExtra(EXTRA_MANIFEST_PATH, manifestPath == null ? "" : manifestPath);
        i.putExtra(EXTRA_MANIFEST_JSON, manifestJson == null ? "" : manifestJson);
        i.putExtra(EXTRA_SUCCESS, false);
        Log.i("AceDone", "failed message=" + message + " totalMs=" + (endMs - startMs)
                + " tile=" + vaeChunk + "/" + vaeOverlap + " kv=" + kvSeq + " tier=" + cacheTier);
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
