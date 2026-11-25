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
import android.widget.ArrayAdapter;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.Spinner;
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

import java.io.File;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "AceMain";
    private static final int REQ_PERMISSIONS = 42;
    private static final String PREFS_AB = "ace_ab";
    private static final String PREF_SEED = "seed";
    private static final String PREF_FLASH_ATTN = "flash_attn";
    private static final String PREF_SCRAG_VAE = "scrag_vae";
    private static final String PREF_OFFICIAL_TILING = "official_tiling";
    private static final String PREF_KEEP_CAPTION = "keep_caption";
    private static final String PREF_BF16_LM = "bf16_lm";
    private static final String PREF_DIT_VARIANT = "dit_variant";
    private static final String PREF_CAPTION_PRESET = "caption_preset";

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
    private EditText editBpm, editKey, editTimesig;
    private Spinner spinnerCaptionPreset;
    private SwitchCompat switchKeepCaption;
    private Button btnGenerate, btnPlay;
    private ProgressBar progress;
    private TextView textStatus;
    private SeekBar playerSeek;
    private TextView playerTime;
    private AudioPlayer audioPlayer;

    // Models page
    private View modelsView;
    private EditText editModels;
    private SwitchCompat switchFlashAttn, switchScragVae, switchOfficialTiling, switchBf16Lm;
    private Button btnDownloadBf16;
    private ProgressBar bf16Progress;
    private TextView textBf16Status;
    private Spinner spinnerDit;
    private Button btnDownloadShift1, btnDownloadShift3;
    private ProgressBar ditProgress;
    private TextView textDitStatus;
    private Button btnDownload;
    private ProgressBar downloadProgress;
    private TextView textModelStatus;

    // History page
    private View historyView;
    private HistoryStore historyStore;
    private HistoryAdapter historyAdapter;
    private TextView historyEmpty;

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
                int p = intent.getIntExtra(GenerationService.EXTRA_PROGRESS, 0);
                String msg = intent.getStringExtra(GenerationService.EXTRA_MESSAGE);
                progress.setProgress(p);
                textStatus.setText(msg != null ? msg : "Generating...");
            } else if (GenerationService.BROADCAST_DONE.equals(action)) {
                String path = intent.getStringExtra(GenerationService.EXTRA_MESSAGE);
                lastOutputPath = path;
                btnGenerate.setEnabled(true);
                boolean fileOk = path != null && new File(path).exists();
                btnPlay.setEnabled(fileOk);
                playerSeek.setEnabled(fileOk);
                textStatus.setText(fileOk ? "Done: " + new File(path).getName() : "Done (file missing)");
                progress.setProgress(100);
                recordFromIntent(intent, true);
                if (fileOk) playPath(path);
                Toast.makeText(MainActivity.this, "Generation complete", Toast.LENGTH_SHORT).show();
            } else if (GenerationService.BROADCAST_ERROR.equals(action)) {
                String err = intent.getStringExtra(GenerationService.EXTRA_MESSAGE);
                btnGenerate.setEnabled(true);
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
                String file = intent.getStringExtra(DownloadService.EXTRA_FILE);
                if (DownloadService.LM_BF16.equals(file)) {
                    if (bf16Progress != null) bf16Progress.setProgress(p);
                    if (textBf16Status != null) textBf16Status.setText(msg);
                } else if (DownloadService.DIT_SHIFT1.equals(file)
                        || DownloadService.DIT_SHIFT3.equals(file)) {
                    if (ditProgress != null) ditProgress.setProgress(p);
                    if (textDitStatus != null) textDitStatus.setText(file + ": " + msg);
                }
            } else if (DownloadService.BROADCAST_DONE.equals(action)) {
                downloadProgress.setProgress(100);
                if (textModelStatus != null) textModelStatus.setText("All models downloaded");
                btnDownload.setEnabled(true);
                if (btnDownloadBf16 != null) btnDownloadBf16.setEnabled(true);
                refreshBf16Status();
                refreshDitStatus();
                Toast.makeText(MainActivity.this, "Models ready", Toast.LENGTH_SHORT).show();
                checkModels();
            } else if (DownloadService.BROADCAST_ERROR.equals(action)) {
                String err = intent.getStringExtra(DownloadService.EXTRA_MESSAGE);
                btnDownload.setEnabled(true);
                if (btnDownloadBf16 != null) btnDownloadBf16.setEnabled(true);
                if (textModelStatus != null) textModelStatus.setText("Download error: " + err);
                if (textBf16Status != null) textBf16Status.setText("Download failed: " + err);
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
        frame.addView(composeView);
        frame.addView(modelsView);
        frame.addView(historyView);

        bindComposeViews();
        bindModelsViews();
        bindHistoryViews();

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
        btnGenerate.setOnClickListener(v -> startGeneration());
        btnPlay.setOnClickListener(v -> playLastResult());
        historyView.findViewById(R.id.btnClearHistory).setOnClickListener(v -> confirmClearHistory());

        refreshHistory();
        restoreLastOutput();
        requestPermissionsIfNeeded();
        checkModels();
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

    private void bindComposeViews() {
        editCaption = composeView.findViewById(R.id.editCaption);
        editBpm = composeView.findViewById(R.id.editBpm);
        editKey = composeView.findViewById(R.id.editKey);
        editTimesig = composeView.findViewById(R.id.editTimesig);
        spinnerCaptionPreset = composeView.findViewById(R.id.spinnerCaptionPreset);
        String[] presetNames = getResources().getStringArray(R.array.caption_presets);
        String[] presetValues = getResources().getStringArray(R.array.caption_preset_values);
        ArrayAdapter<String> presetAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, presetNames);
        presetAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerCaptionPreset.setAdapter(presetAdapter);
        SharedPreferences p = getSharedPreferences(PREFS_AB, MODE_PRIVATE);
        int presetIdx = p.getInt(PREF_CAPTION_PRESET, 0);
        if (presetIdx < 0 || presetIdx >= presetValues.length) presetIdx = 0;
        final String[] finalValues = presetValues;
        spinnerCaptionPreset.setSelection(presetIdx);
        editCaption.setText(finalValues[presetIdx]);
        if (presetIdx == 5) {
            editBpm.setText("190");
            editKey.setText("E minor");
            editTimesig.setText("4");
        }
        spinnerCaptionPreset.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view,
                                                 int pos, long id) {
                if (pos >= 0 && pos < finalValues.length) {
                    editCaption.setText(finalValues[pos]);
                    // Preset 5 replicates the known-good ComfyUI sample:
                    // pin all metadata so the planner never guesses.
                    if (pos == 5) {
                        editBpm.setText("190");
                        editKey.setText("E minor");
                        editTimesig.setText("4");
                    }
                    getSharedPreferences(PREFS_AB, MODE_PRIVATE).edit()
                            .putInt(PREF_CAPTION_PRESET, pos).apply();
                }
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) { }
        });
        editLyrics = composeView.findViewById(R.id.editLyrics);
        editDuration = composeView.findViewById(R.id.editDuration);
        editSeed = composeView.findViewById(R.id.editSeed);
        switchKeepCaption = composeView.findViewById(R.id.switchKeepCaption);
        SharedPreferences prefs = getSharedPreferences(PREFS_AB, MODE_PRIVATE);
        editSeed.setText(String.valueOf(prefs.getLong(PREF_SEED, -1)));
        switchKeepCaption.setChecked(prefs.getBoolean(PREF_KEEP_CAPTION, true));
        btnGenerate = composeView.findViewById(R.id.btnGenerate);
        btnPlay = composeView.findViewById(R.id.btnPlay);
        progress = composeView.findViewById(R.id.progress);
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

    private void bindModelsViews() {
        editModels = modelsView.findViewById(R.id.editModels);
        btnDownload = modelsView.findViewById(R.id.btnDownload);
        downloadProgress = modelsView.findViewById(R.id.downloadProgress);
        textModelStatus = modelsView.findViewById(R.id.textModelStatus);

        switchFlashAttn = modelsView.findViewById(R.id.switchFlashAttn);
        SharedPreferences prefs = getSharedPreferences(PREFS_AB, MODE_PRIVATE);
        boolean flashAttn = prefs.getBoolean(PREF_FLASH_ATTN, true);
        switchFlashAttn.setChecked(flashAttn);
        AceEngine.setFlashAttn(flashAttn);
        switchFlashAttn.setOnCheckedChangeListener((button, checked) -> {
            prefs.edit().putBoolean(PREF_FLASH_ATTN, checked).apply();
            AceEngine.setFlashAttn(checked);
            Toast.makeText(MainActivity.this,
                    "CPU flash attention " + (checked ? "ON" : "OFF"), Toast.LENGTH_SHORT).show();
        });

        switchScragVae = modelsView.findViewById(R.id.switchScragVae);
        switchScragVae.setChecked(prefs.getBoolean(PREF_SCRAG_VAE, false));
        switchScragVae.setOnCheckedChangeListener((button, checked) ->
                prefs.edit().putBoolean(PREF_SCRAG_VAE, checked).apply());

        switchOfficialTiling = modelsView.findViewById(R.id.switchOfficialTiling);
        switchOfficialTiling.setChecked(prefs.getBoolean(PREF_OFFICIAL_TILING, false));
        switchOfficialTiling.setOnCheckedChangeListener((button, checked) ->
                prefs.edit().putBoolean(PREF_OFFICIAL_TILING, checked).apply());

        btnDownloadBf16 = modelsView.findViewById(R.id.btnDownloadBf16);
        bf16Progress = modelsView.findViewById(R.id.bf16Progress);
        textBf16Status = modelsView.findViewById(R.id.textBf16Status);
        switchBf16Lm = modelsView.findViewById(R.id.switchBf16Lm);
        boolean useBf16 = prefs.getBoolean(PREF_BF16_LM, false);
        switchBf16Lm.setChecked(useBf16);
        refreshBf16Status();
        switchBf16Lm.setOnCheckedChangeListener((button, checked) -> {
            prefs.edit().putBoolean(PREF_BF16_LM, checked).apply();
            refreshBf16Status();
        });
        btnDownloadBf16.setOnClickListener(v -> startBf16Download());

        // DiT variant selector + on-device downloads
        spinnerDit = modelsView.findViewById(R.id.spinnerDit);
        String[] ditChoices = {"turbo (default)", "shift3 (clear vocals)", "shift1 (low male vocal)",
                "continuous"};
        ArrayAdapter<String> ditAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, ditChoices);
        ditAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerDit.setAdapter(ditAdapter);
        int ditIdx = prefs.getInt(PREF_DIT_VARIANT, 0);
        if (ditIdx < 0 || ditIdx >= ditChoices.length) ditIdx = 0;
        spinnerDit.setSelection(ditIdx);
        spinnerDit.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view,
                                                 int pos, long id) {
                prefs.edit().putInt(PREF_DIT_VARIANT, pos).apply();
                refreshDitStatus();
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) { }
        });
        btnDownloadShift1 = modelsView.findViewById(R.id.btnDownloadShift1);
        btnDownloadShift3 = modelsView.findViewById(R.id.btnDownloadShift3);
        ditProgress = modelsView.findViewById(R.id.ditProgress);
        textDitStatus = modelsView.findViewById(R.id.textDitStatus);
        btnDownloadShift1.setOnClickListener(v -> startDitDownload(DownloadService.DIT_SHIFT1));
        btnDownloadShift3.setOnClickListener(v -> startDitDownload(DownloadService.DIT_SHIFT3));
        refreshDitStatus();
    }

    private String ditFileForSelection() {
        int pos = spinnerDit != null ? spinnerDit.getSelectedItemPosition() : 0;
        switch (pos) {
            case 1: return DownloadService.DIT_SHIFT3;
            case 2: return DownloadService.DIT_SHIFT1;
            case 3: return "acestep-v15-turbo-continuous-Q8_0.gguf";
            default: return "acestep-v15-turbo-Q8_0.gguf";
        }
    }

    private void refreshDitStatus() {
        if (textDitStatus == null) return;
        String dir = editModels.getText().toString().trim();
        String sel = ditFileForSelection();
        File f = new File(dir, sel);
        long expected = sel.equals("acestep-v15-turbo-Q8_0.gguf")
                ? 2549528000L : DownloadService.DIT_SHIFT_SIZE;
        boolean ready = f.exists() && f.length() == expected;
        textDitStatus.setText(ready ? "Ready: " + sel + " (" + f.length() / 1024 / 1024 + " MB)"
                : "Not downloaded: " + sel);
    }

    private void startDitDownload(String file) {
        String dir = editModels.getText().toString().trim();
        if (dir.isEmpty() || !isOnline()) {
            Toast.makeText(this, "Check model dir and network", Toast.LENGTH_SHORT).show();
            return;
        }
        File target = new File(dir, file);
        if (target.exists()) {
            Toast.makeText(this, "File already exists — check size in Models", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent i = new Intent(this, DownloadService.class);
        i.setAction(DownloadService.ACTION_START_SINGLE);
        i.putExtra(DownloadService.EXTRA_DIR, dir);
        i.putExtra(DownloadService.EXTRA_SINGLE_FILE, file);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i);
        else startService(i);
        if (ditProgress != null) ditProgress.setProgress(0);
        if (textDitStatus != null) textDitStatus.setText("Downloading " + file + "...");
        Toast.makeText(this, "Downloading " + file, Toast.LENGTH_SHORT).show();
    }

    private void refreshBf16Status() {
        if (textBf16Status == null) return;
        File f = new File(editModels.getText().toString().trim(), DownloadService.LM_BF16);
        boolean exists = f.exists() && f.length() == DownloadService.LM_BF16_SIZE;
        textBf16Status.setText(exists ? "BF16 ready (" + f.length() / 1024 / 1024 + " MB)"
                : "BF16 not downloaded yet");
    }

    private void startBf16Download() {
        String dir = editModels.getText().toString().trim();
        if (dir.isEmpty()) {
            Toast.makeText(this, "Set model directory first", Toast.LENGTH_SHORT).show();
            return;
        }
        File f = new File(dir, DownloadService.LM_BF16);
        if (f.exists() && f.length() == DownloadService.LM_BF16_SIZE) {
            Toast.makeText(this, "BF16 already downloaded", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent i = new Intent(this, DownloadService.class);
        i.setAction(DownloadService.ACTION_START_SINGLE);
        i.putExtra(DownloadService.EXTRA_DIR, dir);
        i.putExtra(DownloadService.EXTRA_SINGLE_FILE, DownloadService.LM_BF16);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i);
        else startService(i);
        btnDownloadBf16.setEnabled(false);
        textBf16Status.setText("Downloading BF16 LM...");
    }

    private void bindHistoryViews() {
        historyEmpty = historyView.findViewById(R.id.historyEmpty);
        historyStore = new HistoryStore(this);
        historyAdapter = new HistoryAdapter(historyStore.getAll(), historyListener);
        RecyclerView recycler = historyView.findViewById(R.id.historyRecycler);
        recycler.setLayoutManager(new LinearLayoutManager(this));
        recycler.setAdapter(historyAdapter);
    }

    private void showPage(int itemId) {
        composeView.setVisibility(itemId == R.id.nav_compose ? View.VISIBLE : View.GONE);
        modelsView.setVisibility(itemId == R.id.nav_models ? View.VISIBLE : View.GONE);
        historyView.setVisibility(itemId == R.id.nav_history ? View.VISIBLE : View.GONE);

        int title = R.string.title_compose;
        if (itemId == R.id.nav_models) title = R.string.title_models;
        else if (itemId == R.id.nav_history) title = R.string.title_history;
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
        r.flashAttn = intent.getBooleanExtra(GenerationService.EXTRA_FLASH_ATTN, true);
        r.vaeFile = intent.getStringExtra(GenerationService.EXTRA_VAE_FILE);
        r.keepCaption = intent.getBooleanExtra(GenerationService.EXTRA_KEEP_CAPTION, true);
        r.plannerCaption = intent.getStringExtra(GenerationService.EXTRA_PLANNER_CAPTION);
        r.plannerMeta = intent.getStringExtra(GenerationService.EXTRA_PLANNER_META);
        r.codesCount = intent.getIntExtra(GenerationService.EXTRA_CODES_COUNT, 0);
        r.bf16Lm = intent.getBooleanExtra(GenerationService.EXTRA_BF16_LM, false);
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

    private void showDetailDialog(HistoryRecord r) {
        View v = getLayoutInflater().inflate(R.layout.dialog_detail, null);
        ((TextView) v.findViewById(R.id.dTime)).setText(formatFullTime(r.startMs));
        ((TextView) v.findViewById(R.id.dTiming)).setText(String.format(Locale.getDefault(),
                "Total %s  ·  LM %s  ·  DiT+VAE %s  ·  Requested %ds\nSeed %d  ·  FlashAttn %s  ·  LM %s  ·  VAE %s",
                formatMs(r.totalMs), formatMs(r.lmMs), formatMs(r.synthMs), r.duration,
                r.seed, r.flashAttn ? "ON" : "OFF",
                r.bf16Lm ? "BF16" : "Q8_0",
                r.vaeFile == null || r.vaeFile.isEmpty() ? "stock" : r.vaeFile));
        ((TextView) v.findViewById(R.id.dCaption)).setText(
                r.caption == null || r.caption.isEmpty() ? "(none)" : r.caption);
        String planner = (r.plannerCaption == null || r.plannerCaption.isEmpty()
                ? "(no rewrite data)" : r.plannerCaption)
                + "\n" + (r.plannerMeta == null || r.plannerMeta.isEmpty() ? "" : r.plannerMeta)
                + "\ncodes=" + r.codesCount + " · keepCaption=" + r.keepCaption;
        ((TextView) v.findViewById(R.id.dPlanner)).setText(planner);
        ((TextView) v.findViewById(R.id.dLyrics)).setText(
                r.lyrics == null || r.lyrics.isEmpty() ? "(none)" : r.lyrics);
        ((TextView) v.findViewById(R.id.dPath)).setText(
                r.outputPath == null || r.outputPath.isEmpty() ? "—" : r.outputPath);
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
        int ready = 0;
        for (String f : MODEL_FILES) {
            if (new File(dir, f).exists()) ready++;
        }
        if (ready < MODEL_FILES.length) {
            Toast.makeText(this, "Download all 4 models first (" + ready + "/4)", Toast.LENGTH_LONG).show();
            return;
        }
        boolean useScrag = switchScragVae != null && switchScragVae.isChecked();
        String vaeFile = useScrag ? "scragvae-BF16.gguf" : "vae-BF16.gguf";
        if (useScrag && !new File(dir, vaeFile).exists()) {
            Toast.makeText(this, "Missing scragvae-BF16.gguf in models folder", Toast.LENGTH_LONG).show();
            return;
        }

        File outDir = new File(getExternalFilesDir(null), "output");
        if (!outDir.exists()) outDir.mkdirs();
        String output = new File(outDir, "ace_" + System.currentTimeMillis() + ".wav").getAbsolutePath();

        Intent i = new Intent(this, GenerationService.class);
        i.setAction(GenerationService.ACTION_START);
        i.putExtra(GenerationService.EXTRA_MODELS_DIR, modelsDir);
        i.putExtra(GenerationService.EXTRA_CAPTION, caption);
        i.putExtra(GenerationService.EXTRA_LYRICS, lyrics);
        i.putExtra(GenerationService.EXTRA_DURATION, duration);
        i.putExtra(GenerationService.EXTRA_OUTPUT, output);
        i.putExtra(GenerationService.EXTRA_SEED, (int) seed);
        i.putExtra(GenerationService.EXTRA_FLASH_ATTN, switchFlashAttn.isChecked());
        i.putExtra(GenerationService.EXTRA_VAE_FILE, vaeFile);
        i.putExtra(GenerationService.EXTRA_VAE_TILING, switchOfficialTiling.isChecked());
        i.putExtra(GenerationService.EXTRA_KEEP_CAPTION, switchKeepCaption.isChecked());
        boolean useBf16 = switchBf16Lm != null && switchBf16Lm.isChecked();
        if (useBf16 && !new File(dir, DownloadService.LM_BF16).exists()) {
            Toast.makeText(this, "Download BF16 LM first", Toast.LENGTH_LONG).show();
            return;
        }
        i.putExtra(GenerationService.EXTRA_BF16_LM, useBf16);
        String ditFile = ditFileForSelection();
        if (!new File(dir, ditFile).exists()) {
            Toast.makeText(this, "Missing " + ditFile + " — download it in Models", Toast.LENGTH_LONG).show();
            return;
        }
        i.putExtra(GenerationService.EXTRA_DIT_FILE, ditFile);
        try {
            String bt = editBpm.getText().toString().trim();
            if (!bt.isEmpty()) i.putExtra(GenerationService.EXTRA_BPM, Integer.parseInt(bt));
        } catch (NumberFormatException ignored) { }
        String kt = editKey.getText().toString().trim();
        if (!kt.isEmpty()) i.putExtra(GenerationService.EXTRA_KEY, kt);
        try {
            String tt = editTimesig.getText().toString().trim();
            if (!tt.isEmpty()) i.putExtra(GenerationService.EXTRA_TIMESIG, Integer.parseInt(tt));
        } catch (NumberFormatException ignored) { }
        getSharedPreferences(PREFS_AB, MODE_PRIVATE).edit()
                .putBoolean(PREF_KEEP_CAPTION, switchKeepCaption.isChecked()).apply();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i);
        else startService(i);
        btnGenerate.setEnabled(false);
        progress.setProgress(0);
        textStatus.setText("Starting...");
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
