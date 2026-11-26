package com.example.ace;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.format.DateFormat;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TableLayout;
import android.widget.TableRow;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.ActionBarDrawerToggle;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
import androidx.appcompat.widget.Toolbar;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.navigation.NavigationView;

import org.json.JSONObject;

import java.io.File;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "AceMain";
    private static final int REQ_PERMISSIONS = 42;
    private static final String PREFS_AB = "ace_ab";
    private static final String PREF_SEED = "seed";
    private static final String PREF_KEY = "key_scale";
    private static final String PREF_TIMESIG = "timesig";
    private static final String PREF_DIT = "dit_file";
    private static final String PREF_LM = "lm_file";
    private static final String PREF_TEXTENC = "textenc_file";
    private static final String PREF_VAE = "vae_file";

    // Generation-side parameters (language, sampling). Separate from both the
    // A/B seed prefs and the performance tuning prefs.
    private static final String PREFS_GENERATION = "ace_generation";
    private static final String PREF_VOCAL_LANGUAGE = "vocal_language";
    private static final String PREF_USE_COT_CAPTION = "use_cot_caption";
    private static final String PREF_LM_TEMPERATURE = "lm_temperature";
    private static final String PREF_LM_TOP_P = "lm_top_p";
    private static final String PREF_LM_TOP_K = "lm_top_k";
    private static final String PREF_LM_CFG_SCALE = "lm_cfg_scale";
    private static final String PREF_LM_NEGATIVE_PROMPT = "lm_negative_prompt";
    private static final String PREF_INFERENCE_STEPS = "inference_steps";
    private static final String PREF_GUIDANCE_SCALE = "guidance_scale";
    private static final String PREF_SHIFT = "shift";
    private static final String PREF_SOLVER = "solver";

    // One-time migration: align all generation knobs with request.cpp defaults.
    private static final String PREF_OFFICIAL_DEFAULTS_VERSION = "official_defaults_version";
    private static final int OFFICIAL_DEFAULTS_VERSION = 1;

    // Performance settings. These are deliberately separated from the seed
    // preferences so resetting A/B data never silently changes tuning.
    private static final String PREFS_SETTINGS = "ace_settings";
    private static final String PREF_VAE_CHUNK = "vae_chunk";
    private static final String PREF_VAE_OVERLAP = "vae_overlap";
    private static final String PREF_KV_SEQ = "kv_seq";
    private static final String PREF_THREADS = "threads";
    private static final String PREF_CACHE_TIER = "cache_tier";
    private static final String PREF_FLASH_ATTN = "flash_attn";
    private static final String PREF_OUTPUT_FORMAT = "output_format";

    // Recommended defaults. Do not change these without a listening test:
    // 512/32 is the validated VAE path, 4096 KV is the reduced-capacity path
    // that completed 30s generations, 0 threads = hardware_concurrency()-2,
    // 0 cache tier = auto by device RAM, FlashAttention ON is the fast path.
    private static final int DEFAULT_VAE_CHUNK = 512;
    private static final int DEFAULT_VAE_OVERLAP = 32;
    private static final int DEFAULT_KV_SEQ = 4096;
    private static final int DEFAULT_THREADS = 0;
    private static final int DEFAULT_CACHE_TIER = 0;
    private static final boolean DEFAULT_FLASH_ATTN = true;
    private static final String DEFAULT_OUTPUT_FORMAT = "wav16";

    // Preset values behind the settings spinners. Keeping these as arrays
    // means users can only select known-safe combinations.
    private static final int[] VAE_CHUNKS  = { 512, 640, 768, 1024 };
    private static final int[] VAE_OVERLAPS = { 32, 40, 48, 64 };
    private static final int[] KV_SEQS     = { 2048, 4096, 8192 };
    private static final int[] THREADS     = { 0, 2, 4, 6, 8 };

    // Stable, verified configuration (hardcoded — no diagnostic toggles).
    private static final boolean FLASH_ATTN = true;
    private static final String DIT_FILE = "acestep-v15-turbo-Q8_0.gguf";

    private static final String[] MODEL_FILES = {
            "vae-BF16.gguf",
            "Qwen3-Embedding-0.6B-Q8_0.gguf",
            "acestep-5Hz-lm-1.7B-Q8_0.gguf",
            "acestep-v15-turbo-Q8_0.gguf"
    };
    private static final long[] MODEL_SIZES = {
            337420928L, 784144960L, 1975837568L, 2549528000L
    };

    private DrawerLayout drawerLayout;
    private NavigationView navView;

    // Compose page
    private View composeView;
    private EditText editCaption, editLyrics, editDuration, editSeed;
    private EditText editBpm, editLmNegative;
    private Spinner spinnerKey, spinnerTimesig, spinnerDit, spinnerLm, spinnerTextEnc, spinnerVae;
    // Compose-page generation controls. Performance tuning lives only on Settings.
    private Spinner spinnerVocalLanguage, spinnerLmTemp, spinnerLmTopP, spinnerLmTopK;
    private Spinner spinnerLmCfg, spinnerInferenceSteps, spinnerGuidance, spinnerShift, spinnerSolver;
    private SwitchCompat switchCaptionLock;
    private Button btnGenerate, btnPlay;
    private ProgressBar progress;
    private TextView textElapsed, textStatus;
    private SeekBar playerSeek;
    private TextView playerTime;
    private AudioPlayer audioPlayer;

    // Model file values, parallel to the spinner label arrays.
    private String[] keyValues, timesigValues, ditValues, lmValues, textEncValues, vaeValues;
    // Generation-spinner values, parallel to the label arrays in strings.xml.
    private String[] languageValues, tempValues, topPValues, topKValues, lmLcfgValues;
    private String[] stepsValues, guidanceValues, shiftValues, solverValues;

    // Live progress + elapsed timer. Five real steps reported from native:
    // 0 preparing, 1 loading models, 2 DiT denoise, 3 VAE decode, 4 write.
    // Java broadcasts stage 1 (LM planning) before synth, mapped to a label.
    private static final String[] STEP_LABELS = {
            "1/5 LM 规划",
            "2/5 加载模型",
            "3/5 DiT 去噪",
            "4/5 VAE 解码",
            "5/5 写出音频"
    };
    // Baseline progress (0..1000) at the start of each step; time interpolation
    // moves within the step until the next real stage report arrives.
    private static final int[] STEP_BASE = { 20, 100, 160, 520, 960 };
    private static final int[] STEP_SPAN = { 80, 60, 360, 440, 40 };
    private android.os.Handler uiHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private long genStartMs = 0;
    private long stageStartMs = 0;
    private int progressStage = -1;      // Java broadcast stage (LM/synth/done)
    private int nativeStep = -1;          // native-reported synth step 0..4
    private int genDurationSec = 30;
    private final Runnable elapsedTick = new Runnable() {
        @Override public void run() {
            if (genStartMs > 0) {
                long sec = (System.currentTimeMillis() - genStartMs) / 1000;
                textElapsed.setText(String.format(Locale.getDefault(), "%02d:%02d", sec / 60, sec % 60));
                // Native reports the true synth step; adopt it when available.
                int ns = AceEngine.sNativeStage;
                // native 0 = transient "preparing synth" right after LM; ignore.
                if (ns >= 1 && ns <= 4 && ns != nativeStep) {
                    nativeStep = ns;
                    stageStartMs = System.currentTimeMillis();
                    if (textStatus != null) textStatus.setText(STEP_LABELS[ns]);
                    progress.setProgress(STEP_BASE[ns]);
                }
                advanceStepProgress();
                uiHandler.postDelayed(this, 500);
            }
        }
    };

    // Models page
    private View modelsView;
    private EditText editModels;
    private Button btnDownload;
    private ProgressBar downloadProgress;
    private TextView textModelStatus;

    // History page
    private View historyView;
    private HistoryStore historyStore;
    private HistoryAdapter historyAdapter;
    private TextView historyEmpty;

    // Settings page
    private View settingsView;
    private Spinner spinnerOutputFormat, spinnerVaeTile, spinnerKvSeq, spinnerThreads, spinnerCacheTier;
    private SwitchCompat switchFlashAttn;
    private TextView textSettingsStatus;
    private String[] outputFormatValues;

    private String lastOutputPath;

    private final HistoryAdapter.Listener historyListener = new HistoryAdapter.Listener() {
        @Override public void onPlay(HistoryRecord r) { playPath(r.outputPath); }
        @Override public void onDetails(HistoryRecord r) { showDetailDialog(r); }
        @Override public void onDelete(HistoryRecord r) {
            historyStore.remove(r.id);
            refreshHistory();
            Toast.makeText(MainActivity.this, "Record deleted", Toast.LENGTH_SHORT).show();
        }
    };

    private final BroadcastReceiver genReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (GenerationService.BROADCAST_PROGRESS.equals(action)) {
                int stage = intent.getIntExtra(GenerationService.EXTRA_STAGE, -1);
                int p = intent.getIntExtra(GenerationService.EXTRA_PROGRESS, 0);
                String msg = intent.getStringExtra(GenerationService.EXTRA_MESSAGE);
                progressStage = stage;
                stageStartMs = System.currentTimeMillis();
                if (nativeStep < 0) {
                    // Before native synth stages begin: show the Java-side
                    // message (engine load / LM planning).
                    progress.setProgress(p * 10);  // service reports 0..100, bar 0..1000
                    textStatus.setText(msg != null ? msg : "Generating...");
                }
                // Once native reports a synth step, its label + time-based
                // interpolation take over (see elapsedTick).
            } else if (GenerationService.BROADCAST_DONE.equals(action)) {
                String path = intent.getStringExtra(GenerationService.EXTRA_MESSAGE);
                lastOutputPath = path;
                btnGenerate.setEnabled(true);
                stopElapsedTimer();
                boolean fileOk = path != null && new File(path).exists();
                btnPlay.setEnabled(fileOk);
                playerSeek.setEnabled(fileOk);
                textStatus.setText(fileOk ? "Done: " + new File(path).getName() : "Done (file missing)");
                progress.setProgress(1000);
                recordFromIntent(intent, true);
                if (fileOk) playPath(path);
                Toast.makeText(MainActivity.this, "Generation complete", Toast.LENGTH_SHORT).show();
            } else if (GenerationService.BROADCAST_ERROR.equals(action)) {
                String err = intent.getStringExtra(GenerationService.EXTRA_MESSAGE);
                btnGenerate.setEnabled(true);
                stopElapsedTimer();
                textStatus.setText("Error: " + err);
                Log.e(TAG, "Generation error: " + err);
                recordFromIntent(intent, false);
                Toast.makeText(MainActivity.this, "Failed: " + err, Toast.LENGTH_LONG).show();
            }
        }
    };

    private final BroadcastReceiver dlReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (DownloadService.BROADCAST_PROGRESS.equals(action)) {
                int p = intent.getIntExtra(DownloadService.EXTRA_PROGRESS, 0);
                String msg = intent.getStringExtra(DownloadService.EXTRA_MESSAGE);
                downloadProgress.setProgress(p);
                if (textModelStatus != null) textModelStatus.setText(msg);
            } else if (DownloadService.BROADCAST_DONE.equals(action)) {
                downloadProgress.setProgress(100);
                String single = intent.getStringExtra(DownloadService.EXTRA_SINGLE_FILE);
                if (single != null && !single.isEmpty()) {
                    if (textModelStatus != null) textModelStatus.setText("Downloaded: " + single);
                    Toast.makeText(MainActivity.this, "已下载 " + single, Toast.LENGTH_SHORT).show();
                } else {
                    if (textModelStatus != null) textModelStatus.setText("All models downloaded");
                    Toast.makeText(MainActivity.this, "Models ready", Toast.LENGTH_SHORT).show();
                }
                btnDownload.setEnabled(true);
            } else if (DownloadService.BROADCAST_ERROR.equals(action)) {
                String err = intent.getStringExtra(DownloadService.EXTRA_MESSAGE);
                btnDownload.setEnabled(true);
                if (textModelStatus != null) textModelStatus.setText("Download error: " + err);
                Toast.makeText(MainActivity.this, "Download failed: " + err, Toast.LENGTH_LONG).show();
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        drawerLayout = findViewById(R.id.drawerLayout);
        navView = findViewById(R.id.navView);
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        ActionBarDrawerToggle toggle = new ActionBarDrawerToggle(this, drawerLayout, toolbar,
                R.string.nav_drawer_open, R.string.nav_drawer_close);
        drawerLayout.addDrawerListener(toggle);
        toggle.syncState();

        LayoutInflater inflater = LayoutInflater.from(this);
        FrameLayout frame = findViewById(R.id.contentFrame);
        composeView = inflater.inflate(R.layout.fragment_compose, frame, false);
        modelsView = inflater.inflate(R.layout.fragment_models, frame, false);
        historyView = inflater.inflate(R.layout.fragment_history, frame, false);
        settingsView = inflater.inflate(R.layout.fragment_settings, frame, false);
        frame.addView(composeView);
        frame.addView(modelsView);
        frame.addView(historyView);
        frame.addView(settingsView);

        migrateOfficialDefaults();
        bindComposeViews();
        bindModelsViews();
        bindHistoryViews();
        bindSettingsViews();

        showPage(R.id.nav_compose);
        navView.setCheckedItem(R.id.nav_compose);
        navView.setNavigationItemSelectedListener(item -> {
            showPage(item.getItemId());
            drawerLayout.closeDrawer(GravityCompat.START);
            return true;
        });

        File modelDir = new File(getExternalFilesDir(null), "models");
        if (!modelDir.exists()) modelDir.mkdirs();
        editModels.setText(modelDir.getAbsolutePath());

        btnDownload.setOnClickListener(v -> startDownload());
        modelsView.findViewById(R.id.btnCheck).setOnClickListener(v -> checkModels());
        modelsView.findViewById(R.id.btnDownloadQ4).setOnClickListener(v ->
                startSingleDownload("acestep-v15-turbo-Q4_K_M.gguf"));
        modelsView.findViewById(R.id.btnDownloadQ5).setOnClickListener(v ->
                startSingleDownload("acestep-v15-turbo-Q5_K_M.gguf"));
        modelsView.findViewById(R.id.btnDownloadQ6).setOnClickListener(v ->
                startSingleDownload("acestep-v15-turbo-Q6_K.gguf"));
        modelsView.findViewById(R.id.btnDownloadLm06).setOnClickListener(v ->
                startSingleDownload("acestep-5Hz-lm-0.6B-Q8_0.gguf"));
        btnGenerate.setOnClickListener(v -> startGeneration());
        btnPlay.setOnClickListener(v -> playLastResult());
        historyView.findViewById(R.id.btnClearHistory).setOnClickListener(v -> confirmClearHistory());

        refreshHistory();
        restoreLastOutput();
        requestPermissionsIfNeeded();
        checkModels();
        handlePerfIntent(getIntent());
    }

    /**
     * Headless test entry. Allows adb to start a generation with explicit
     * tuning extras without touching the UI (no simulated clicks). The
     * activity remains the exported entry point; the service itself stays
     * private.
     */
    private void handlePerfIntent(Intent incoming) {
        if (incoming == null || !"com.example.ace.PERF_START".equals(incoming.getAction())) return;
        Intent i = new Intent(this, GenerationService.class);
        i.setAction(GenerationService.ACTION_START);
        String[] stringExtras = {
                GenerationService.EXTRA_MODELS_DIR, GenerationService.EXTRA_CAPTION,
                GenerationService.EXTRA_LYRICS, GenerationService.EXTRA_OUTPUT,
                GenerationService.EXTRA_KEY, GenerationService.EXTRA_OUTPUT_FORMAT,
                GenerationService.EXTRA_LM_FILE, GenerationService.EXTRA_TEXTENC_FILE,
                GenerationService.EXTRA_DIT_FILE, GenerationService.EXTRA_VAE_FILE_SEL,
                GenerationService.EXTRA_VOCAL_LANGUAGE, GenerationService.EXTRA_LM_NEGATIVE_PROMPT,
                GenerationService.EXTRA_SOLVER
        };
        String[] intExtras = {
                GenerationService.EXTRA_DURATION, GenerationService.EXTRA_SEED,
                GenerationService.EXTRA_BPM, GenerationService.EXTRA_TIMESIG,
                GenerationService.EXTRA_VAE_CHUNK, GenerationService.EXTRA_VAE_OVERLAP,
                GenerationService.EXTRA_KV_SEQ, GenerationService.EXTRA_THREADS,
                GenerationService.EXTRA_CACHE_TIER, GenerationService.EXTRA_LM_TOP_K,
                GenerationService.EXTRA_INFERENCE_STEPS
        };
        String[] floatExtras = {
                GenerationService.EXTRA_LM_TEMPERATURE, GenerationService.EXTRA_LM_TOP_P,
                GenerationService.EXTRA_LM_CFG_SCALE, GenerationService.EXTRA_GUIDANCE_SCALE,
                GenerationService.EXTRA_SHIFT
        };
        String[] boolTrueExtras = {
                GenerationService.EXTRA_FLASH_ATTN,
                GenerationService.EXTRA_USE_COT_CAPTION
        };
        String[] boolFalseExtras = new String[0];
        for (String key : stringExtras) {
            if (incoming.hasExtra(key)) i.putExtra(key, incoming.getStringExtra(key));
        }
        for (String key : intExtras) {
            if (incoming.hasExtra(key)) i.putExtra(key, incoming.getIntExtra(key, 0));
        }
        for (String key : floatExtras) {
            if (incoming.hasExtra(key)) i.putExtra(key, incoming.getFloatExtra(key, 0f));
        }
        for (String key : boolTrueExtras) {
            if (incoming.hasExtra(key)) i.putExtra(key, incoming.getBooleanExtra(key, true));
        }
        for (String key : boolFalseExtras) {
            if (incoming.hasExtra(key)) i.putExtra(key, incoming.getBooleanExtra(key, false));
        }
        startForegroundService(i);
        textStatus.setText("Perf intent started");
    }

    /** Load the most recent successful track into the player on startup. */
    private void restoreLastOutput() {
        HistoryRecord last = null;
        for (HistoryRecord r : historyStore.getAll()) {
            if (r.success && r.outputPath != null && new File(r.outputPath).exists()) {
                if (last == null || r.startMs > last.startMs) last = r;
            }
        }
        if (last != null) {
            lastOutputPath = last.outputPath;
            btnPlay.setEnabled(true);
            playerSeek.setEnabled(true);
            textStatus.setText("Ready: " + new File(last.outputPath).getName());
        }
    }

    /**
     * Reset legacy app-specific generation values to the official request.cpp
     * defaults. Runs once after upgrade so an existing install does not keep
     * seed=20 / bpm=190 / key=E minor / caption-lock from earlier experiments.
     */
    private void migrateOfficialDefaults() {
        SharedPreferences ab = getSharedPreferences(PREFS_AB, MODE_PRIVATE);
        if (ab.getInt(PREF_OFFICIAL_DEFAULTS_VERSION, 0) < OFFICIAL_DEFAULTS_VERSION) {
            ab.edit()
                    .putLong(PREF_SEED, -1L)
                    .putString(PREF_KEY, "")
                    .putInt(PREF_OFFICIAL_DEFAULTS_VERSION, OFFICIAL_DEFAULTS_VERSION)
                    .apply();
        }
        SharedPreferences gen = getSharedPreferences(PREFS_GENERATION, MODE_PRIVATE);
        if (gen.getInt(PREF_OFFICIAL_DEFAULTS_VERSION, 0) < OFFICIAL_DEFAULTS_VERSION) {
            gen.edit().clear()
                    .putInt(PREF_OFFICIAL_DEFAULTS_VERSION, OFFICIAL_DEFAULTS_VERSION)
                    .apply();
        }
    }

    private void bindComposeViews() {
        editCaption = composeView.findViewById(R.id.editCaption);
        editBpm = composeView.findViewById(R.id.editBpm);
        spinnerTimesig = composeView.findViewById(R.id.spinnerTimesig);
        editLyrics = composeView.findViewById(R.id.editLyrics);
        editDuration = composeView.findViewById(R.id.editDuration);
        editSeed = composeView.findViewById(R.id.editSeed);
        spinnerKey = composeView.findViewById(R.id.spinnerKey);
        spinnerDit = composeView.findViewById(R.id.spinnerDit);
        timesigValues = getResources().getStringArray(R.array.timesig_values);
        spinnerLm = composeView.findViewById(R.id.spinnerLm);
        spinnerTextEnc = composeView.findViewById(R.id.spinnerTextEnc);
        spinnerVae = composeView.findViewById(R.id.spinnerVae);
        keyValues = getResources().getStringArray(R.array.key_scale_values);
        ditValues = getResources().getStringArray(R.array.dit_model_values);
        lmValues = getResources().getStringArray(R.array.lm_model_values);
        textEncValues = getResources().getStringArray(R.array.textenc_model_values);
        vaeValues = getResources().getStringArray(R.array.vae_model_values);
        SharedPreferences prefs = getSharedPreferences(PREFS_AB, MODE_PRIVATE);
        editSeed.setText(String.valueOf(prefs.getLong(PREF_SEED, -1L)));
        // Official default is auto (empty keyscale); migration resets legacy values.
        spinnerKey.setSelection(indexOfString(keyValues,
                prefs.getString(PREF_KEY, ""), 0));
        // 4/4 is the practical default requested for the compose page.
        spinnerTimesig.setSelection(indexOfString(timesigValues,
                prefs.getString(PREF_TIMESIG, "4"), 3));
        spinnerDit.setSelection(indexOfString(ditValues, prefs.getString(PREF_DIT, ""), 0));
        spinnerLm.setSelection(indexOfString(lmValues, prefs.getString(PREF_LM, ""), 0));
        spinnerTextEnc.setSelection(indexOfString(textEncValues, prefs.getString(PREF_TEXTENC, ""), 0));
        spinnerVae.setSelection(indexOfString(vaeValues, prefs.getString(PREF_VAE, ""), 0));

        // Compose-page generation controls. Values are discrete presets so no
        // malformed number can reach the native request parser.
        spinnerVocalLanguage = composeView.findViewById(R.id.spinnerVocalLanguage);
        spinnerLmTemp = composeView.findViewById(R.id.spinnerLmTemp);
        spinnerLmTopP = composeView.findViewById(R.id.spinnerLmTopP);
        spinnerLmTopK = composeView.findViewById(R.id.spinnerLmTopK);
        spinnerLmCfg = composeView.findViewById(R.id.spinnerLmCfg);
        spinnerInferenceSteps = composeView.findViewById(R.id.spinnerInferenceSteps);
        spinnerGuidance = composeView.findViewById(R.id.spinnerGuidance);
        spinnerShift = composeView.findViewById(R.id.spinnerShift);
        spinnerSolver = composeView.findViewById(R.id.spinnerSolver);
        switchCaptionLock = composeView.findViewById(R.id.switchCaptionLock);
        editLmNegative = composeView.findViewById(R.id.editLmNegative);
        languageValues = getResources().getStringArray(R.array.vocal_language_values);
        tempValues = getResources().getStringArray(R.array.lm_temperature_values);
        topPValues = getResources().getStringArray(R.array.lm_top_p_values);
        topKValues = getResources().getStringArray(R.array.lm_top_k_values);
        lmLcfgValues = getResources().getStringArray(R.array.lm_cfg_values);
        stepsValues = getResources().getStringArray(R.array.inference_steps_values);
        guidanceValues = getResources().getStringArray(R.array.guidance_values);
        shiftValues = getResources().getStringArray(R.array.shift_values);
        solverValues = getResources().getStringArray(R.array.solver_values);
        attachGenerationListeners();
        loadGenerationParams();

        btnGenerate = composeView.findViewById(R.id.btnGenerate);
        btnPlay = composeView.findViewById(R.id.btnPlay);
        progress = composeView.findViewById(R.id.progress);
        textElapsed = composeView.findViewById(R.id.textElapsed);
        textStatus = composeView.findViewById(R.id.textStatus);
        playerSeek = composeView.findViewById(R.id.playerSeek);
        playerTime = composeView.findViewById(R.id.playerTime);

        audioPlayer = new AudioPlayer();
        audioPlayer.bind(playerSeek, playerTime, playing -> {
            btnPlay.setText(playing ? "Pause" : "Play");
            playerSeek.setEnabled(audioPlayer.isPlaying() || hasAudio());
        });
    }

    private boolean hasAudio() {
        return lastOutputPath != null && new File(lastOutputPath).exists();
    }

    // ---- Compose-page generation controls (PREFS_GENERATION) ----

    private boolean generationLoading = false;

    private void attachGenerationListeners() {
        android.widget.AdapterView.OnItemSelectedListener save =
                new android.widget.AdapterView.OnItemSelectedListener() {
                    @Override public void onItemSelected(android.widget.AdapterView<?> parent,
                                                          View view, int pos, long id) {
                        if (generationLoading) return;
                        saveGenerationParams();
                    }
                    @Override public void onNothingSelected(android.widget.AdapterView<?> parent) { }
                };
        spinnerVocalLanguage.setOnItemSelectedListener(save);
        spinnerLmTemp.setOnItemSelectedListener(save);
        spinnerLmTopP.setOnItemSelectedListener(save);
        spinnerLmTopK.setOnItemSelectedListener(save);
        spinnerLmCfg.setOnItemSelectedListener(save);
        spinnerInferenceSteps.setOnItemSelectedListener(save);
        spinnerGuidance.setOnItemSelectedListener(save);
        spinnerShift.setOnItemSelectedListener(save);
        spinnerSolver.setOnItemSelectedListener(save);
        switchCaptionLock.setOnCheckedChangeListener((btn, checked) -> {
            if (generationLoading) return;
            saveGenerationParams();
        });
    }

    /** Restore generation params. Values are stored exactly as spinner values. */
    private void loadGenerationParams() {
        if (spinnerVocalLanguage == null) return;
        generationLoading = true;
        SharedPreferences p = getSharedPreferences(PREFS_GENERATION, MODE_PRIVATE);
        spinnerVocalLanguage.setSelection(indexOfString(languageValues,
                p.getString(PREF_VOCAL_LANGUAGE, ""), 0));
        // UI toggle is "keep prompt"; native flag is the inverse "let LM rewrite".
        switchCaptionLock.setChecked(!p.getBoolean(PREF_USE_COT_CAPTION, true));
        spinnerLmTemp.setSelection(indexOfString(tempValues,
                p.getString(PREF_LM_TEMPERATURE, "0.85"), 1));
        spinnerLmTopP.setSelection(indexOfString(topPValues,
                p.getString(PREF_LM_TOP_P, "0.90"), 1));
        spinnerLmTopK.setSelection(indexOfString(topKValues,
                p.getString(PREF_LM_TOP_K, "0"), 0));
        spinnerLmCfg.setSelection(indexOfString(lmLcfgValues,
                p.getString(PREF_LM_CFG_SCALE, "2.0"), 1));
        spinnerInferenceSteps.setSelection(indexOfString(stepsValues,
                p.getString(PREF_INFERENCE_STEPS, "0"), 0));
        spinnerGuidance.setSelection(indexOfString(guidanceValues,
                p.getString(PREF_GUIDANCE_SCALE, "0"), 0));
        spinnerShift.setSelection(indexOfString(shiftValues,
                p.getString(PREF_SHIFT, "0"), 0));
        spinnerSolver.setSelection(indexOfString(solverValues,
                p.getString(PREF_SOLVER, "euler"), 0));
        editLmNegative.setText(p.getString(PREF_LM_NEGATIVE_PROMPT, ""));
        generationLoading = false;
    }

    /** Persist generation params and return the values for this run. */
    private void saveGenerationParams() {
        if (generationLoading || spinnerVocalLanguage == null) return;
        SharedPreferences p = getSharedPreferences(PREFS_GENERATION, MODE_PRIVATE);
        p.edit()
                .putString(PREF_VOCAL_LANGUAGE,
                        selectedValue(spinnerVocalLanguage, languageValues))
                .putBoolean(PREF_USE_COT_CAPTION, !switchCaptionLock.isChecked())
                .putString(PREF_LM_TEMPERATURE, selectedValue(spinnerLmTemp, tempValues))
                .putString(PREF_LM_TOP_P, selectedValue(spinnerLmTopP, topPValues))
                .putString(PREF_LM_TOP_K, selectedValue(spinnerLmTopK, topKValues))
                .putString(PREF_LM_CFG_SCALE, selectedValue(spinnerLmCfg, lmLcfgValues))
                .putString(PREF_INFERENCE_STEPS, selectedValue(spinnerInferenceSteps, stepsValues))
                .putString(PREF_GUIDANCE_SCALE, selectedValue(spinnerGuidance, guidanceValues))
                .putString(PREF_SHIFT, selectedValue(spinnerShift, shiftValues))
                .putString(PREF_SOLVER, selectedValue(spinnerSolver, solverValues))
                .putString(PREF_LM_NEGATIVE_PROMPT,
                        editLmNegative.getText().toString().trim())
                .apply();
    }

    private static String selectedValue(Spinner spinner, String[] values) {
        int pos = spinner.getSelectedItemPosition();
        if (pos < 0 || pos >= values.length) pos = 0;
        return values[pos];
    }

    private void bindModelsViews() {
        editModels = modelsView.findViewById(R.id.editModels);
        btnDownload = modelsView.findViewById(R.id.btnDownload);
        downloadProgress = modelsView.findViewById(R.id.downloadProgress);
        textModelStatus = modelsView.findViewById(R.id.textModelStatus);
        // Apply the single verified configuration on startup.
        AceEngine.setFlashAttn(FLASH_ATTN);
    }

    private void bindHistoryViews() {
        historyEmpty = historyView.findViewById(R.id.historyEmpty);
        historyStore = new HistoryStore(this);
        historyAdapter = new HistoryAdapter(historyStore.getAll(), historyListener);
        RecyclerView recycler = historyView.findViewById(R.id.historyRecycler);
        recycler.setLayoutManager(new LinearLayoutManager(this));
        recycler.setAdapter(historyAdapter);
    }

    private void bindSettingsViews() {
        spinnerOutputFormat = settingsView.findViewById(R.id.spinnerOutputFormat);
        spinnerVaeTile = settingsView.findViewById(R.id.spinnerVaeTile);
        spinnerKvSeq = settingsView.findViewById(R.id.spinnerKvSeq);
        spinnerThreads = settingsView.findViewById(R.id.spinnerThreads);
        spinnerCacheTier = settingsView.findViewById(R.id.spinnerCacheTier);
        switchFlashAttn = settingsView.findViewById(R.id.switchFlashAttn);
        textSettingsStatus = settingsView.findViewById(R.id.textSettingsStatus);
        outputFormatValues = getResources().getStringArray(R.array.output_format_values);

        settingsView.findViewById(R.id.btnSaveSettings).setOnClickListener(v -> saveSettings());
        settingsView.findViewById(R.id.btnResetSettings).setOnClickListener(v -> resetSettings());
        loadSettings();
    }

    private void loadSettings() {
        SharedPreferences p = getSharedPreferences(PREFS_SETTINGS, MODE_PRIVATE);
        spinnerOutputFormat.setSelection(indexOfString(outputFormatValues,
                p.getString(PREF_OUTPUT_FORMAT, DEFAULT_OUTPUT_FORMAT), 0));
        // Migrate older free-form values to the closest defined preset.
        int chunk = p.getInt(PREF_VAE_CHUNK, DEFAULT_VAE_CHUNK);
        int overlap = p.getInt(PREF_VAE_OVERLAP, DEFAULT_VAE_OVERLAP);
        int tileIndex = 0;
        for (int i = 0; i < VAE_CHUNKS.length; i++) {
            if (VAE_CHUNKS[i] == chunk && VAE_OVERLAPS[i] == overlap) {
                tileIndex = i;
                break;
            }
        }
        spinnerVaeTile.setSelection(tileIndex);
        spinnerKvSeq.setSelection(indexOfValue(KV_SEQS,
                p.getInt(PREF_KV_SEQ, DEFAULT_KV_SEQ), 1));
        spinnerThreads.setSelection(indexOfValue(THREADS,
                p.getInt(PREF_THREADS, DEFAULT_THREADS), 0));
        spinnerCacheTier.setSelection(p.getInt(PREF_CACHE_TIER, DEFAULT_CACHE_TIER));
        switchFlashAttn.setChecked(p.getBoolean(PREF_FLASH_ATTN, DEFAULT_FLASH_ATTN));
        textSettingsStatus.setText("当前设置会在下一次生成时生效。");
    }

    private void saveSettings() {
        int tileIndex = spinnerVaeTile.getSelectedItemPosition();
        int chunk = VAE_CHUNKS[tileIndex];
        int overlap = VAE_OVERLAPS[tileIndex];
        int kv = KV_SEQS[spinnerKvSeq.getSelectedItemPosition()];
        int threads = THREADS[spinnerThreads.getSelectedItemPosition()];
        int tier = spinnerCacheTier.getSelectedItemPosition();
        boolean flash = switchFlashAttn.isChecked();
        String format = outputFormatValues[spinnerOutputFormat.getSelectedItemPosition()];

        SharedPreferences p = getSharedPreferences(PREFS_SETTINGS, MODE_PRIVATE);
        p.edit()
                .putInt(PREF_VAE_CHUNK, chunk)
                .putInt(PREF_VAE_OVERLAP, overlap)
                .putInt(PREF_KV_SEQ, kv)
                .putInt(PREF_THREADS, threads)
                .putInt(PREF_CACHE_TIER, tier)
                .putBoolean(PREF_FLASH_ATTN, flash)
                .putString(PREF_OUTPUT_FORMAT, format)
                .apply();
        textSettingsStatus.setText("已保存：format=" + format + "，tile=" + chunk + "/" + overlap
                + "，KV=" + kv + "，threads=" + (threads == 0 ? "auto" : threads)
                + "，tier=" + tier + "，FA=" + (flash ? "ON" : "OFF"));
        Toast.makeText(this, "设置已保存", Toast.LENGTH_SHORT).show();
    }

    private void resetSettings() {
        SharedPreferences p = getSharedPreferences(PREFS_SETTINGS, MODE_PRIVATE);
        p.edit()
                .putInt(PREF_VAE_CHUNK, DEFAULT_VAE_CHUNK)
                .putInt(PREF_VAE_OVERLAP, DEFAULT_VAE_OVERLAP)
                .putInt(PREF_KV_SEQ, DEFAULT_KV_SEQ)
                .putInt(PREF_THREADS, DEFAULT_THREADS)
                .putInt(PREF_CACHE_TIER, DEFAULT_CACHE_TIER)
                .putBoolean(PREF_FLASH_ATTN, DEFAULT_FLASH_ATTN)
                .putString(PREF_OUTPUT_FORMAT, DEFAULT_OUTPUT_FORMAT)
                .apply();
        loadSettings();
        textSettingsStatus.setText("已恢复推荐值。");
        Toast.makeText(this, "已恢复推荐值", Toast.LENGTH_SHORT).show();
    }

    private static int indexOfValue(int[] values, int value, int fallbackIndex) {
        for (int i = 0; i < values.length; i++) {
            if (values[i] == value) return i;
        }
        return fallbackIndex;
    }

    private static int indexOfString(String[] values, String value, int fallbackIndex) {
        if (values == null) return fallbackIndex;
        for (int i = 0; i < values.length; i++) {
            if (values[i].equals(value)) return i;
        }
        return fallbackIndex;
    }

    private static int parseIntValue(String s, int fallback) {
        try { return Integer.parseInt(s); }
        catch (Exception e) { return fallback; }
    }

    private static float parseFloatValue(String s, float fallback) {
        try { return Float.parseFloat(s); }
        catch (Exception e) { return fallback; }
    }

    private void startElapsedTimer(int durationSec) {
        genStartMs = System.currentTimeMillis();
        stageStartMs = genStartMs;
        progressStage = 0;
        nativeStep = -1;
        AceEngine.sNativeStage = -1;
        genDurationSec = durationSec;
        textElapsed.setText("00:00");
        textStatus.setText("1/5 LM 规划");
        progress.setProgress(STEP_BASE[0]);
        uiHandler.removeCallbacks(elapsedTick);
        uiHandler.post(elapsedTick);
    }

    private void stopElapsedTimer() {
        genStartMs = 0;
        progressStage = -1;
        nativeStep = -1;
        AceEngine.sNativeStage = -1;
        uiHandler.removeCallbacks(elapsedTick);
    }

    /**
     * Advance progress within the current real step. The step itself comes
     * from native stage callbacks (load / DiT / VAE / write) or the LM stage
     * broadcast, so the label always shows what is actually running. Between
     * stage boundaries we interpolate with a time estimate so the bar keeps
     * moving instead of freezing.
     */
    private void advanceStepProgress() {
        if (genStartMs <= 0) return;
        int step;
        if (nativeStep >= 0) {
            step = nativeStep;
        } else if (progressStage == 1) {
            step = 0;  // LM planning broadcast
        } else {
            return;
        }
        long since = System.currentTimeMillis() - stageStartMs;
        // Scale the estimate by audio duration; 30s is the calibrated case.
        double scale = Math.max(0.4, genDurationSec / 30.0);
        int v = STEP_BASE[step] + (int) (STEP_SPAN[step] * since / (10000.0 * scale));
        int cap = STEP_BASE[step] + STEP_SPAN[step];
        progress.setProgress(Math.min(v, cap));
    }

    /** Copy persisted tuning into the generation service intent. */
    private void applyTuningExtras(Intent i) {
        SharedPreferences p = getSharedPreferences(PREFS_SETTINGS, MODE_PRIVATE);
        i.putExtra(GenerationService.EXTRA_VAE_CHUNK, p.getInt(PREF_VAE_CHUNK, DEFAULT_VAE_CHUNK));
        i.putExtra(GenerationService.EXTRA_VAE_OVERLAP, p.getInt(PREF_VAE_OVERLAP, DEFAULT_VAE_OVERLAP));
        i.putExtra(GenerationService.EXTRA_KV_SEQ, p.getInt(PREF_KV_SEQ, DEFAULT_KV_SEQ));
        i.putExtra(GenerationService.EXTRA_THREADS, p.getInt(PREF_THREADS, DEFAULT_THREADS));
        i.putExtra(GenerationService.EXTRA_CACHE_TIER, p.getInt(PREF_CACHE_TIER, DEFAULT_CACHE_TIER));
        i.putExtra(GenerationService.EXTRA_FLASH_ATTN,
                p.getBoolean(PREF_FLASH_ATTN, DEFAULT_FLASH_ATTN));
        i.putExtra(GenerationService.EXTRA_OUTPUT_FORMAT,
                p.getString(PREF_OUTPUT_FORMAT, DEFAULT_OUTPUT_FORMAT));
    }

    private void showPage(int itemId) {
        composeView.setVisibility(itemId == R.id.nav_compose ? View.VISIBLE : View.GONE);
        modelsView.setVisibility(itemId == R.id.nav_models ? View.VISIBLE : View.GONE);
        historyView.setVisibility(itemId == R.id.nav_history ? View.VISIBLE : View.GONE);
        settingsView.setVisibility(itemId == R.id.nav_settings ? View.VISIBLE : View.GONE);
        // Refresh the visible page so values stay in sync across navigation.
        if (itemId == R.id.nav_compose) loadGenerationParams();
        else if (itemId == R.id.nav_settings) loadSettings();

        int title = R.string.title_compose;
        if (itemId == R.id.nav_models) title = R.string.title_models;
        else if (itemId == R.id.nav_history) title = R.string.title_history;
        else if (itemId == R.id.nav_settings) title = R.string.title_settings;
        if (getSupportActionBar() != null) getSupportActionBar().setTitle(title);
    }

    @Override
    public void onBackPressed() {
        if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
            drawerLayout.closeDrawer(GravityCompat.START);
        } else {
            super.onBackPressed();
        }
    }

    private void recordFromIntent(Intent intent, boolean success) {
        HistoryRecord r = new HistoryRecord();
        r.caption = editCaption.getText().toString().trim();
        r.lyrics = editLyrics.getText().toString().trim();
        try { r.duration = Integer.parseInt(editDuration.getText().toString().trim()); }
        catch (Exception ignored) { r.duration = 30; }
        r.startMs = intent.getLongExtra(GenerationService.EXTRA_START_MS, System.currentTimeMillis());
        r.endMs = intent.getLongExtra(GenerationService.EXTRA_END_MS, System.currentTimeMillis());
        r.totalMs = intent.getLongExtra(GenerationService.EXTRA_TOTAL_MS, r.endMs - r.startMs);
        r.lmMs = intent.getLongExtra(GenerationService.EXTRA_LM_MS, 0);
        r.synthMs = intent.getLongExtra(GenerationService.EXTRA_SYNTH_MS, 0);
        r.seed = intent.getLongExtra(GenerationService.EXTRA_SEED, -1);
        r.vaeFile = intent.getStringExtra(GenerationService.EXTRA_VAE_FILE);
        r.plannerCaption = intent.getStringExtra(GenerationService.EXTRA_PLANNER_CAPTION);
        r.plannerMeta = intent.getStringExtra(GenerationService.EXTRA_PLANNER_META);
        r.codesCount = intent.getIntExtra(GenerationService.EXTRA_CODES_COUNT, 0);
        r.manifestPath = intent.getStringExtra(GenerationService.EXTRA_MANIFEST_PATH);
        r.manifestJson = intent.getStringExtra(GenerationService.EXTRA_MANIFEST_JSON);
        r.success = success;
        r.id = r.startMs;
        if (success) {
            r.outputPath = intent.getStringExtra(GenerationService.EXTRA_MESSAGE);
            r.fileSize = intent.getLongExtra(GenerationService.EXTRA_FILE_SIZE, 0);
        } else {
            r.error = intent.getStringExtra(GenerationService.EXTRA_MESSAGE);
        }
        historyStore.add(r);
        refreshHistory();
    }

    private void refreshHistory() {
        historyAdapter.notifyDataSetChanged();
        historyEmpty.setVisibility(historyStore.size() == 0 ? View.VISIBLE : View.GONE);
    }

    private void confirmClearHistory() {
        if (historyStore.size() == 0) {
            Toast.makeText(this, "History is already empty", Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Clear history")
                .setMessage("Delete all " + historyStore.size() + " records? Audio files will be kept.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Delete", (d, w) -> {
                    historyStore.clear();
                    refreshHistory();
                    Toast.makeText(this, "History cleared", Toast.LENGTH_SHORT).show();
                })
                .show();
    }

    /** Build the per-step timing table from the manifest's result.stages. */
    private void populateStageTable(TableLayout table, HistoryRecord r) {
        if (table == null) return;
        table.removeAllViews();

        long loadMs = 0, ditMs = 0, vaeMs = 0, writeMs = 0;
        long totalMs = r.totalMs, lmMs = r.lmMs, synthMs = r.synthMs;
        try {
            if (r.manifestJson != null && !r.manifestJson.isEmpty()) {
                JSONObject res = new JSONObject(r.manifestJson).optJSONObject("result");
                if (res != null) {
                    JSONObject stages = res.optJSONObject("stages");
                    if (stages != null) {
                        loadMs = stages.optLong("synthLoadMs", 0);
                        ditMs = stages.optLong("ditMs", 0);
                        vaeMs = stages.optLong("vaeMs", 0);
                        writeMs = stages.optLong("audioWriteMs", 0);
                    }
                    if (totalMs <= 0) totalMs = res.optLong("totalMs", 0);
                    if (lmMs <= 0) lmMs = res.optLong("lmMs", 0);
                    if (synthMs <= 0) synthMs = res.optLong("synthMs", 0);
                }
            }
        } catch (Exception ignored) { }

        addStageRow(table, "步骤 Step", "耗时", "占比", true);
        addStageRow(table, "1. LM 规划", formatMs(lmMs), pct(lmMs, totalMs), false);
        addStageRow(table, "2. 加载模型", formatMs(loadMs), pct(loadMs, totalMs), false);
        addStageRow(table, "3. DiT 去噪", formatMs(ditMs), pct(ditMs, totalMs), false);
        addStageRow(table, "4. VAE 解码", formatMs(vaeMs), pct(vaeMs, totalMs), false);
        addStageRow(table, "5. 写出音频", formatMs(writeMs), pct(writeMs, totalMs), false);
        addStageRow(table, "合计 Total", formatMs(totalMs), "100%", true);
    }

    private void addStageRow(TableLayout table, String a, String b, String c, boolean header) {
        TableRow row = new TableRow(this);
        TableRow.LayoutParams lp = new TableRow.LayoutParams(
                0, TableRow.LayoutParams.WRAP_CONTENT, 1f);
        TextView t1 = new TextView(this);
        t1.setText(a);
        TextView t2 = new TextView(this);
        t2.setText(b);
        t2.setGravity(android.view.Gravity.END);
        TextView t3 = new TextView(this);
        t3.setText(c);
        t3.setGravity(android.view.Gravity.END);
        int color = header ? getResources().getColor(R.color.primary)
                : getResources().getColor(R.color.text_secondary);
        for (TextView t : new TextView[]{t1, t2, t3}) {
            t.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12);
            t.setTextColor(color);
            if (header) t.setTypeface(null, android.graphics.Typeface.BOLD);
            t.setPadding(2, 6, 2, 6);
            t.setLayoutParams(lp);
            row.addView(t);
        }
        table.addView(row);
    }

    private static String pct(long part, long total) {
        if (total <= 0 || part <= 0) return "—";
        return Math.round(100.0 * part / total) + "%";
    }

    private void showDetailDialog(HistoryRecord r) {
        View v = getLayoutInflater().inflate(R.layout.dialog_detail, null);
        ((TextView) v.findViewById(R.id.dTime)).setText(formatFullTime(r.startMs));
        ((TextView) v.findViewById(R.id.dTiming)).setText(String.format(Locale.getDefault(),
                "Total %s  ·  LM %s  ·  DiT+VAE %s  ·  Requested %ds\nSeed %d  ·  VAE %s",
                formatMs(r.totalMs), formatMs(r.lmMs), formatMs(r.synthMs), r.duration,
                r.seed, r.vaeFile == null || r.vaeFile.isEmpty() ? "stock" : r.vaeFile));
        populateStageTable((TableLayout) v.findViewById(R.id.dStageTable), r);
        ((TextView) v.findViewById(R.id.dCaption)).setText(
                r.caption == null || r.caption.isEmpty() ? "(none)" : r.caption);
        String planner = (r.plannerCaption == null || r.plannerCaption.isEmpty()
                ? "(no rewrite data)" : r.plannerCaption)
                + "\n" + (r.plannerMeta == null || r.plannerMeta.isEmpty() ? "" : r.plannerMeta)
                + "\ncodes=" + r.codesCount;
        ((TextView) v.findViewById(R.id.dPlanner)).setText(planner);
        ((TextView) v.findViewById(R.id.dLyrics)).setText(
                r.lyrics == null || r.lyrics.isEmpty() ? "(none)" : r.lyrics);
        ((TextView) v.findViewById(R.id.dPath)).setText(
                r.outputPath == null || r.outputPath.isEmpty() ? "—" : r.outputPath);

        String config = "—";
        try {
            if (r.manifestJson != null && !r.manifestJson.isEmpty()) {
                JSONObject m = new JSONObject(r.manifestJson);
                JSONObject t = m.optJSONObject("tuning");
                if (t != null) {
                    config = String.format(Locale.getDefault(),
                            "tile=%d/%d  KV=%d  threads=%s  cacheTier=%d  FA=%s  steps=%d  solver=%s",
                            t.optInt("vae_chunk"), t.optInt("vae_overlap"), t.optInt("kv_seq"),
                            t.optInt("threads") == 0 ? "auto" : String.valueOf(t.optInt("threads")),
                            t.optInt("cache_tier"), t.optBoolean("flash_attn"),
                            t.optInt("inference_steps"), t.optString("solver"));
                }
                JSONObject g = m.optJSONObject("generation");
                if (g != null) {
                    String lang = g.optString("vocal_language", "");
                    config += String.format(Locale.getDefault(),
                            "\n\nGeneration:\nlang=%s  promptLocked=%s  temp=%.2f  topP=%.2f  topK=%d  lmCfg=%.1f"
                                    + "\nsteps=%d  guidance=%.1f  shift=%.1f  solver=%s",
                            lang.isEmpty() ? "auto" : lang,
                            !g.optBoolean("use_cot_caption", false),
                            g.optDouble("lm_temperature", 0.85),
                            g.optDouble("lm_top_p", 0.90),
                            g.optInt("lm_top_k", 0),
                            g.optDouble("lm_cfg_scale", 2.0),
                            g.optInt("inference_steps", 0),
                            g.optDouble("guidance_scale", 0),
                            g.optDouble("shift", 0),
                            g.optString("solver", "euler"));
                    String neg = g.optString("lm_negative_prompt", "");
                    if (!neg.isEmpty()) config += "\nnegative='" + neg + "'";
                }
                String cmd = m.optString("reproduce_adb_command", "");
                if (!cmd.isEmpty()) config += "\n\nADB:\n" + cmd;
            }
        } catch (Exception e) {
            config = "(manifest parse failed)";
        }
        ((TextView) v.findViewById(R.id.dConfig)).setText(config);
        ((TextView) v.findViewById(R.id.dManifestPath)).setText(
                r.manifestPath == null || r.manifestPath.isEmpty() ? "—" : r.manifestPath);
        ((TextView) v.findViewById(R.id.dManifestJson)).setText(
                r.manifestJson == null || r.manifestJson.isEmpty() ? "—" : r.manifestJson);

        if (!r.success) {
            v.findViewById(R.id.dErrorTitle).setVisibility(View.VISIBLE);
            v.findViewById(R.id.dError).setVisibility(View.VISIBLE);
            ((TextView) v.findViewById(R.id.dError)).setText(
                    r.error == null ? "Unknown error" : r.error);
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setView(v)
                .setPositiveButton("Close", null)
                .setNegativeButton("Delete", (d, w) -> {
                    historyStore.remove(r.id);
                    refreshHistory();
                });
        if (r.success && r.outputPath != null && new File(r.outputPath).exists()) {
            builder.setNeutralButton("Play", (d, w) -> playPath(r.outputPath));
        }
        builder.show();
    }

    private static String formatMs(long ms) {
        if (ms <= 0) return "—";
        long s = ms / 1000;
        if (s < 60) return s + "s";
        return (s / 60) + "m " + (s % 60) + "s";
    }

    private static String formatFullTime(long ms) {
        return DateFormat.format("yyyy-MM-dd HH:mm:ss", new Date(ms)).toString();
    }

    private boolean isOnline() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return false;
        NetworkCapabilities nc = cm.getNetworkCapabilities(cm.getActiveNetwork());
        return nc != null && nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
    }

    private void startDownload() {
        String dir = editModels.getText().toString().trim();
        if (dir.isEmpty()) {
            Toast.makeText(this, "Set model directory first", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!isOnline()) {
            Toast.makeText(this, "No network connection", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent i = new Intent(this, DownloadService.class);
        i.setAction(DownloadService.ACTION_START);
        i.putExtra(DownloadService.EXTRA_DIR, dir);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i);
        else startService(i);
        btnDownload.setEnabled(false);
        downloadProgress.setProgress(0);
        if (textModelStatus != null) textModelStatus.setText("Starting download (~5.6 GB)...");
        Toast.makeText(this, "Downloading via hf-mirror", Toast.LENGTH_SHORT).show();
    }

    private void startSingleDownload(String fileName) {
        String dir = editModels.getText().toString().trim();
        if (dir.isEmpty()) {
            Toast.makeText(this, "Set model directory first", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!isOnline()) {
            Toast.makeText(this, "No network connection", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent i = new Intent(this, DownloadService.class);
        i.setAction(DownloadService.ACTION_START);
        i.putExtra(DownloadService.EXTRA_DIR, dir);
        i.putExtra(DownloadService.EXTRA_SINGLE_FILE, fileName);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i);
        else startService(i);
        btnDownload.setEnabled(false);
        downloadProgress.setProgress(0);
        if (textModelStatus != null) textModelStatus.setText("Downloading " + fileName + "...");
        Toast.makeText(this, "Downloading " + fileName, Toast.LENGTH_SHORT).show();
    }

    private void checkModels() {
        String dir = editModels.getText().toString().trim();
        File d = new File(dir);
        int ok = 0;
        long total = 0;
        for (int i = 0; i < MODEL_FILES.length; i++) {
            File f = new File(d, MODEL_FILES[i]);
            if (f.exists() && f.length() == MODEL_SIZES[i]) {
                ok++;
                total += f.length();
            }
        }
        String status = "Models: " + ok + "/" + MODEL_FILES.length +
                " ready (" + String.format("%.2f GB", total / (1024.0 * 1024 * 1024)) + ")";
        if (textModelStatus != null) textModelStatus.setText(status);
        if (textStatus != null && getString(R.string.status_idle).equals(textStatus.getText().toString())) {
            textStatus.setText(status);
        }
    }

    private void requestPermissionsIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_PERMISSIONS);
            }
        }
    }

    private void startGeneration() {
        String modelsDir = editModels.getText().toString().trim();
        String caption = editCaption.getText().toString().trim();
        String lyrics = editLyrics.getText().toString().trim();
        int duration;
        try { duration = Integer.parseInt(editDuration.getText().toString().trim()); }
        catch (NumberFormatException e) { duration = 30; }
        long seed;
        try { seed = Long.parseLong(editSeed.getText().toString().trim()); }
        catch (NumberFormatException e) { seed = -1; }
        SharedPreferences prefs = getSharedPreferences(PREFS_AB, MODE_PRIVATE);
        prefs.edit().putLong(PREF_SEED, seed).apply();
        if (caption.isEmpty()) {
            Toast.makeText(this, "Please enter a caption", Toast.LENGTH_SHORT).show();
            return;
        }
        if (duration < 10) duration = 10;
        if (duration > 120) duration = 120;

        File dir = new File(modelsDir);
        // Validate the four models actually selected on this page. The DiT
        // variant may be Q4/Q5/Q6; LM/TextEnc/VAE may also be switched.
        String selDit = ditValues[spinnerDit.getSelectedItemPosition()];
        String selLm = lmValues[spinnerLm.getSelectedItemPosition()];
        String selTextEnc = textEncValues[spinnerTextEnc.getSelectedItemPosition()];
        String selVae = vaeValues[spinnerVae.getSelectedItemPosition()];
        String[] selected = { selVae, selTextEnc, selLm, selDit };
        for (String f : selected) {
            if (f == null || f.isEmpty()) continue;
            if (!new File(dir, f).exists()) {
                Toast.makeText(this, "缺少模型 " + f + " — 请先在「模型」页下载", Toast.LENGTH_LONG).show();
                return;
            }
        }

        File outDir = new File(getExternalFilesDir(null), "output");
        if (!outDir.exists()) outDir.mkdirs();

        // Resolve output format and matching extension.
        SharedPreferences settings = getSharedPreferences(PREFS_SETTINGS, MODE_PRIVATE);
        String format = settings.getString(PREF_OUTPUT_FORMAT, DEFAULT_OUTPUT_FORMAT);
        String ext = "mp3".equals(format) ? ".mp3" : ".wav";
        String output = new File(outDir, "ace_" + System.currentTimeMillis() + ext).getAbsolutePath();

        // Selected model files from the compose page.
        String ditFile = ditValues[spinnerDit.getSelectedItemPosition()];
        String lmFile = lmValues[spinnerLm.getSelectedItemPosition()];
        String textEncFile = textEncValues[spinnerTextEnc.getSelectedItemPosition()];
        String vaeFile = vaeValues[spinnerVae.getSelectedItemPosition()];

        Intent i = new Intent(this, GenerationService.class);
        i.setAction(GenerationService.ACTION_START);
        i.putExtra(GenerationService.EXTRA_MODELS_DIR, modelsDir);
        i.putExtra(GenerationService.EXTRA_CAPTION, caption);
        i.putExtra(GenerationService.EXTRA_LYRICS, lyrics);
        i.putExtra(GenerationService.EXTRA_DURATION, duration);
        i.putExtra(GenerationService.EXTRA_OUTPUT, output);
        i.putExtra(GenerationService.EXTRA_SEED, (int) seed);
        try {
            String bt = editBpm.getText().toString().trim();
            if (!bt.isEmpty()) i.putExtra(GenerationService.EXTRA_BPM, Integer.parseInt(bt));
        } catch (NumberFormatException ignored) { }
        String kt = keyValues[spinnerKey.getSelectedItemPosition()];
        if (kt != null && !kt.isEmpty()) i.putExtra(GenerationService.EXTRA_KEY, kt);
        String ts = selectedValue(spinnerTimesig, timesigValues);
        int timesig = parseIntValue(ts, 0);
        if (timesig > 0) i.putExtra(GenerationService.EXTRA_TIMESIG, timesig);
        i.putExtra(GenerationService.EXTRA_DIT_FILE, ditFile);
        i.putExtra(GenerationService.EXTRA_LM_FILE, lmFile);
        i.putExtra(GenerationService.EXTRA_TEXTENC_FILE, textEncFile);
        i.putExtra(GenerationService.EXTRA_VAE_FILE_SEL, vaeFile);
        // Persist selections so they are restored on next launch.
        getSharedPreferences(PREFS_AB, MODE_PRIVATE).edit()
                .putString(PREF_KEY, kt == null ? "" : kt)
                .putString(PREF_TIMESIG, ts)
                .putString(PREF_DIT, ditFile)
                .putString(PREF_LM, lmFile)
                .putString(PREF_TEXTENC, textEncFile)
                .putString(PREF_VAE, vaeFile)
                .apply();
        applyTuningExtras(i);

        // Generation-side settings from the compose page. saveGenerationParams()
        // also captures a typed negative prompt even if no spinner was touched.
        saveGenerationParams();
        SharedPreferences gen = getSharedPreferences(PREFS_GENERATION, MODE_PRIVATE);
        i.putExtra(GenerationService.EXTRA_VOCAL_LANGUAGE,
                gen.getString(PREF_VOCAL_LANGUAGE, ""));
        i.putExtra(GenerationService.EXTRA_USE_COT_CAPTION,
                gen.getBoolean(PREF_USE_COT_CAPTION, true));
        i.putExtra(GenerationService.EXTRA_LM_TEMPERATURE,
                parseFloatValue(gen.getString(PREF_LM_TEMPERATURE, "0.85"), 0.85f));
        i.putExtra(GenerationService.EXTRA_LM_TOP_P,
                parseFloatValue(gen.getString(PREF_LM_TOP_P, "0.90"), 0.90f));
        i.putExtra(GenerationService.EXTRA_LM_TOP_K,
                parseIntValue(gen.getString(PREF_LM_TOP_K, "0"), 0));
        i.putExtra(GenerationService.EXTRA_LM_CFG_SCALE,
                parseFloatValue(gen.getString(PREF_LM_CFG_SCALE, "2.0"), 2.0f));
        i.putExtra(GenerationService.EXTRA_LM_NEGATIVE_PROMPT,
                gen.getString(PREF_LM_NEGATIVE_PROMPT, ""));
        i.putExtra(GenerationService.EXTRA_INFERENCE_STEPS,
                parseIntValue(gen.getString(PREF_INFERENCE_STEPS, "0"), 0));
        i.putExtra(GenerationService.EXTRA_GUIDANCE_SCALE,
                parseFloatValue(gen.getString(PREF_GUIDANCE_SCALE, "0"), 0f));
        i.putExtra(GenerationService.EXTRA_SHIFT,
                parseFloatValue(gen.getString(PREF_SHIFT, "0"), 0f));
        i.putExtra(GenerationService.EXTRA_SOLVER,
                gen.getString(PREF_SOLVER, "euler"));

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i);
        else startService(i);
        btnGenerate.setEnabled(false);
        progress.setProgress(0);
        textStatus.setText("Starting...");
        startElapsedTimer(duration);
    }

    private void playLastResult() {
        Log.i(TAG, "playLastResult: path=" + lastOutputPath + " playing="
                + (audioPlayer != null && audioPlayer.isPlaying()));
        if (audioPlayer != null && audioPlayer.isPlaying()) {
            audioPlayer.toggle();
            return;
        }
        if (hasAudio()) {
            playPath(lastOutputPath);
        } else {
            Toast.makeText(this, "No audio yet", Toast.LENGTH_SHORT).show();
        }
    }

    /** Play a file with the built-in player (no external app needed). */
    private void playPath(String path) {
        if (path == null || !new File(path).exists()) {
            Toast.makeText(this, "Audio file not found", Toast.LENGTH_SHORT).show();
            return;
        }
        lastOutputPath = path;
        btnPlay.setEnabled(true);
        playerSeek.setEnabled(true);
        if (audioPlayer == null) {
            audioPlayer = new AudioPlayer();
            audioPlayer.bind(playerSeek, playerTime, playing -> {
                btnPlay.setText(playing ? "Pause" : "Play");
                playerSeek.setEnabled(audioPlayer.isPlaying() || hasAudio());
            });
        }
        if (!audioPlayer.play(path)) {
            Toast.makeText(this, "Could not play this file", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onDestroy() {
        if (audioPlayer != null) audioPlayer.destroy();
        super.onDestroy();
    }

    @Override
    protected void onResume() {
        super.onResume();
        IntentFilter gf = new IntentFilter();
        gf.addAction(GenerationService.BROADCAST_PROGRESS);
        gf.addAction(GenerationService.BROADCAST_DONE);
        gf.addAction(GenerationService.BROADCAST_ERROR);
        LocalBroadcastManager.getInstance(this).registerReceiver(genReceiver, gf);

        IntentFilter df = new IntentFilter();
        df.addAction(DownloadService.BROADCAST_PROGRESS);
        df.addAction(DownloadService.BROADCAST_DONE);
        df.addAction(DownloadService.BROADCAST_ERROR);
        LocalBroadcastManager.getInstance(this).registerReceiver(dlReceiver, df);
    }

    @Override
    protected void onPause() {
        LocalBroadcastManager.getInstance(this).unregisterReceiver(genReceiver);
        LocalBroadcastManager.getInstance(this).unregisterReceiver(dlReceiver);
        super.onPause();
    }
}
