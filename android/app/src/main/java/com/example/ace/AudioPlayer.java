package com.example.ace;

import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.SeekBar;
import android.widget.TextView;

import java.io.File;
import java.util.Locale;

/** In-app audio player backed by MediaPlayer, with a seek bar and position clock. */
public class AudioPlayer {

    private static final String TAG = "AcePlayer";
    private static final int TICK_MS = 250;

    public interface Listener {
        void onStateChanged(boolean playing);
    }

    private MediaPlayer player;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private SeekBar seekBar;
    private TextView timeLabel;
    private Listener listener;
    private String currentPath;
    private boolean userSeeking = false;
    private boolean destroyed = false;

    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            if (destroyed) return;
            // Keep rescheduling even before a file is loaded: the previous
            // version returned early when player == null, which killed the
            // ticker permanently after bind() and froze the seek bar forever.
            try {
                if (player != null && player.isPlaying() && seekBar != null && !userSeeking) {
                    int pos = player.getCurrentPosition();
                    int dur = player.getDuration();
                    seekBar.setMax(dur > 0 ? dur : 1);
                    seekBar.setProgress(pos);
                    updateTime(pos, dur);
                }
            } catch (IllegalStateException ignored) { }
            handler.postDelayed(this, TICK_MS);
        }
    };

    public void bind(SeekBar bar, TextView label, Listener l) {
        this.seekBar = bar;
        this.timeLabel = label;
        this.listener = l;
        if (bar != null) {
            bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onStartTrackingTouch(SeekBar s) { userSeeking = true; }
                @Override public void onStopTrackingTouch(SeekBar s) { userSeeking = false; }
                @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                    if (fromUser && player != null) {
                        try { player.seekTo(p); } catch (IllegalStateException ignored) { }
                        updateTime(p, player.getDuration());
                    }
                }
            });
        }
        handler.post(ticker);
    }

    /** Load a file and start playing it. Returns false if the file is missing/unplayable. */
    public boolean play(String path) {
        Log.i(TAG, "play: " + path);
        if (path == null || !new File(path).exists()) return false;
        stop();
        try {
            player = new MediaPlayer();
            player.setDataSource(path);
            player.setOnPreparedListener(mp -> {
                Log.i(TAG, "prepared, duration=" + mp.getDuration());
                if (destroyed) { mp.release(); player = null; return; }
                int dur = mp.getDuration();
                if (seekBar != null) seekBar.setMax(dur > 0 ? dur : 1);
                updateTime(0, dur);
                try { mp.start(); } catch (IllegalStateException ignored) { }
                notifyState(true);
            });
            player.setOnCompletionListener(mp -> {
                notifyState(false);
                updateTime(0, mp.getDuration());
                if (seekBar != null) seekBar.setProgress(0);
            });
            player.setOnErrorListener((mp, what, extra) -> {
                Log.w(TAG, "MediaPlayer error what=" + what + " extra=" + extra);
                notifyState(false);
                return true;
            });
            player.prepareAsync();
            currentPath = path;
            return true;
        } catch (Exception e) {
            Log.w(TAG, "play failed", e);
            stop();
            return false;
        }
    }

    public void pause() {
        if (player != null) {
            try { if (player.isPlaying()) player.pause(); } catch (IllegalStateException ignored) { }
        }
        notifyState(false);
    }

    public void resume() {
        if (player != null) {
            try { if (!player.isPlaying()) { player.start(); notifyState(true); } }
            catch (IllegalStateException ignored) { }
        } else if (currentPath != null) {
            play(currentPath);
        }
    }

    public void toggle() {
        if (isPlaying()) pause(); else resume();
    }

    public boolean isPlaying() {
        if (player == null) return false;
        try { return player.isPlaying(); } catch (IllegalStateException e) { return false; }
    }

    public void stop() {
        if (player != null) {
            try { if (player.isPlaying()) player.stop(); } catch (IllegalStateException ignored) { }
            player.release();
            player = null;
        }
        notifyState(false);
    }

    public void destroy() {
        destroyed = true;
        handler.removeCallbacks(ticker);
        stop();
    }

    private void notifyState(boolean playing) {
        if (listener != null) listener.onStateChanged(playing);
    }

    private void updateTime(int pos, int dur) {
        if (timeLabel == null) return;
        timeLabel.setText(String.format(Locale.getDefault(), "%s / %s", fmt(pos), fmt(dur)));
    }

    private static String fmt(int ms) {
        if (ms < 0) ms = 0;
        int s = ms / 1000;
        return String.format(Locale.getDefault(), "%d:%02d", s / 60, s % 60);
    }
}
